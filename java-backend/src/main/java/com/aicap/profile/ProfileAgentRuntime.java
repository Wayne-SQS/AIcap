package com.aicap.profile;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * 画像智能体运行时:真正的规划循环。
 *
 * 循环结构(对齐验收标准):
 *   感知(注入事实与记忆) → LLM 分析 → 模型返回 {thought, action:{tool,args}, final}
 *   → action: 执行工具,observation 回填上下文,继续下一轮(模型可主动补数据)
 *   → final: 结构化 JSON 结论(必须带 evidence_refs,锚定工具调用)
 *   步数/预算控制:超过 maxSteps 强制收敛,防止失控;每步留痕(可观测)。
 *
 * 与会议智能体的 AgentRunner 完全独立:自己的循环、自己的步进协议、自己的留痕结构。
 */
@Component
public class ProfileAgentRuntime {

    /** 循环中的一步(进 AgentRun.steps) */
    public record Step(int index,
                       String thought,       // 模型的思考
                       String actionTool,    // 调用的工具(null=最终收敛)
                       String actionArgs,
                       String observation,   // 工具返回(截断存储)
                       boolean observationOk,
                       int promptTokens,
                       int completionTokens,
                       String model) {
    }

    /** 循环最终输出 */
    public record AgentOutcome(String finalJson,
                               List<Step> steps,
                               int totalTokens,
                               boolean converged,   // true=模型自己收敛;false=步数耗尽强制收敛
                               String stopReason) {
    }

    /** 模型每轮必须返回的结构:要么 action(继续循环),要么 final(收敛) */
    public static final String STEP_PROTOCOL = """
            每一轮你必须只返回一个 JSON 对象(不要多余文本),二选一:

            继续分析/补数据:
            {"thought":"简述当前判断与缺口","action":{"tool":"工具名","args":{...}}}

            收敛输出最终结论:
            {"thought":"简述收敛理由","final":{...结论对象...}}

            规则:
            - 结论中的每条判断必须能在 evidence_refs 里锚定到具体工具调用或事实编号
            - 数据不足以支撑某条判断时,把该判断标记 confidence 低并说明缺什么,禁止编造
            - 发现可疑点(如长期无活动、状态与活动矛盾)时应主动调用工具核实,而不是直接下结论
            - 尽量在少量轮次内收敛;信息足够时立即给出 final
            """;

    private final ProfileLlmClient llm;
    private final ProfileLlmProperties props;
    private final ProfileToolRegistry tools;
    private final ObjectMapper objectMapper;

    public ProfileAgentRuntime(ProfileLlmClient llm, ProfileLlmProperties props,
                               ProfileToolRegistry tools, ObjectMapper objectMapper) {
        this.llm = llm;
        this.props = props;
        this.tools = tools;
        this.objectMapper = objectMapper;
    }

    public boolean available() {
        return llm.available();
    }

    /**
     * 运行规划循环。
     * @param systemPrompt 角色与任务说明(由调用方按场景拼装:难度评估/成员画像/团队风险)
     * @param userPrompt   本次的输入事实(感知内容,已含数据与记忆)
     */
    public AgentOutcome run(String systemPrompt, String userPrompt) {
        List<ProfileLlmClient.Msg> messages = new ArrayList<>();
        messages.add(ProfileLlmClient.Msg.system(systemPrompt
                + "\n\n## 可用工具\n" + toolSchemasText()
                + "\n\n## 步进协议\n" + STEP_PROTOCOL));
        messages.add(ProfileLlmClient.Msg.user(userPrompt));

        List<Step> steps = new ArrayList<>();
        int totalTokens = 0;
        int maxSteps = Math.max(3, props.getMaxSteps());

        for (int i = 1; i <= maxSteps; i++) {
            ProfileLlmClient.ChatResult r = llm.chat(messages, true);
            totalTokens += r.totalTokens();

            JsonNode node;
            try {
                node = objectMapper.readTree(r.content());
            } catch (Exception e) {
                // 模型输出坏 JSON:回填错误让它修正,消耗一次步数
                messages.add(ProfileLlmClient.Msg.assistant(r.content()));
                messages.add(ProfileLlmClient.Msg.user("输出不是合法 JSON,请严格按步进协议重试"));
                steps.add(new Step(i, "bad_json", null, null, truncate(r.content()), false,
                        r.promptTokens(), r.completionTokens(), r.model()));
                continue;
            }

            // 情形一:模型给出最终结论 → 收敛
            if (node.has("final")) {
                steps.add(new Step(i, node.path("thought").asText(""), null, null,
                        "converged", true, r.promptTokens(), r.completionTokens(), r.model()));
                return new AgentOutcome(node.path("final").toString(), steps, totalTokens, true,
                        "model_converged");
            }

            // 情形二:模型要求调用工具 → 执行并回填 observation
            String toolName = node.path("action").path("tool").asText("");
            String argsJson = node.path("action").path("args").toString();
            ProfileToolRegistry.ToolCall call = tools.invoke(toolName, argsJson);
            messages.add(ProfileLlmClient.Msg.assistant(r.content()));
            messages.add(ProfileLlmClient.Msg.user("工具 " + toolName + " 返回(ok=" + call.ok() + "): "
                    + truncate(call.resultJson())));
            steps.add(new Step(i, node.path("thought").asText(""), toolName, argsJson,
                    truncate(call.resultJson()), call.ok(), r.promptTokens(), r.completionTokens(), r.model()));
        }

        // 步数耗尽:强制要一个最终结论(不再允许 action)
        ProfileLlmClient.ChatResult last = llm.chat(messages, true);
        totalTokens += last.totalTokens();
        String finalJson = extractFinalOrRaw(last.content());
        steps.add(new Step(maxSteps + 1, "forced_convergence", null, null, truncate(last.content()),
                true, last.promptTokens(), last.completionTokens(), last.model()));
        return new AgentOutcome(finalJson, steps, totalTokens, false, "max_steps_reached");
    }

    private String toolSchemasText() {
        StringBuilder sb = new StringBuilder();
        for (var s : tools.schemas()) {
            sb.append("- ").append(s.get("name")).append(": ").append(s.get("description"))
                    .append(" 参数: ").append(s.get("args")).append("\n");
        }
        return sb.toString();
    }

    /** 步数耗尽时从最后输出中提取 final,取不到就包一层原始文本 */
    private String extractFinalOrRaw(String content) {
        try {
            JsonNode node = objectMapper.readTree(content);
            if (node.has("final")) return node.path("final").toString();
        } catch (Exception ignore) {
            // 落入原始文本包装
        }
        try {
            return objectMapper.writeValueAsString(java.util.Map.of(
                    "raw_output", content,
                    "warning", "步数耗尽,模型未按协议收敛"));
        } catch (Exception e) {
            return "{\"error\":\"无法解析模型输出\"}";
        }
    }

    private String truncate(String s) {
        if (s == null) return "";
        return s.length() <= 800 ? s : s.substring(0, 800) + "…(截断)";
    }
}
