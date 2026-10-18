package com.aicap.planning;

import com.aicap.llm.LlmProperties;
import com.aicap.llm.ModelClient;
import com.aicap.common.AgentError;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.function.BiConsumer;

/** 使用现有 ModelClient 的规划 Agent 推理循环。 */
@Component
@RequiredArgsConstructor
public class PlanningAgentRunner {
    private final PlanningAgentTools tools;
    private final PlanningAnalysisValidator validator;
    private final ObjectMapper mapper;

    private static final String PROMPT = """
            你是爱管理项目规划 Agent，不是普通聊天助手。
            读取当前项目真实数据，禁止编造 Task、Story、成员、Sprint、工时或排期；不能直接修改数据库。
            需要时必须调用只读工具查询真实实体。查询问题返回 actions=[] 且 requires_confirmation=false。
            修改请求只能输出可校验动作，必须等待用户确认后才会执行。支持以下动作：
            UPDATE_TASK_OWNER: {type,task_id,from_owner_id,to_owner_id}
            UPDATE_TASK_SCHEDULE: {type,task_id,target_week}，或 {type,task_id,start_week,end_week}
            UPDATE_STORY_SPRINT: {type,story_id,sprint}
            UPDATE_TASK_DEPENDENCY: {type,task_id,before_dependencies:[...],after_dependencies:[...]}
            UPDATE_TASK_PRIORITY: {type,task_id,before_priority,after_priority}
            CREATE_MILESTONE: {type,milestone_id,name,week,description,related_task_ids:[...]}
            UPDATE_MILESTONE: {type,milestone_id,before:{name,week,description,related_task_ids},after:{...}}
            CREATE_TASK: {type,name,owner_id,hours,week_start,week_end,priority,task_type,story_ref,depends_on}
            新增任务规则（用户说"加一个/建一个任务"时）：
            - name 用用户原话里的任务内容（例如"文档整理"），不要改写成别的标题；
            - owner_id 必须是 get_members 返回的真实成员编号：用户点名某人就按姓名匹配，找不到或重名时不要猜，在 answer 里问清并返回 actions=[]；
            - 用户没指定负责人时默认发起人；没指定工时默认 8；没指定优先级默认 Should；
            - 没指定时间时：用户说了 Sprint N 就换算成 W(2N-1)…W(2N)；仍无依据时用 W1-W1，并在 warnings 里注明"排期按默认值，请在确认前核对"；
            - 不关联故事的任务 task_type 用 management，关联了故事用 feature；kanban_card_id 不要填（挂卡由人工在界面完成）。
            最终只输出 JSON，字段为 intent,summary,answer,actions,affected_entities,warnings,requires_confirmation。
            不确定目标实体时不要猜 ID，应通过工具查询并在 answer 中说明无法确定。
            """;

    public ObjectNode analyze(String request, ModelClient client) throws AgentError { throw new AgentError("project_context_missing", "projectId 必填"); }

    public ObjectNode analyze(String projectId, String request, ModelClient client) throws AgentError { return analyze(projectId, request, client, (kind, detail) -> { }); }

    public ObjectNode analyze(String projectId, String request, ModelClient client, BiConsumer<String, String> eventSink) throws AgentError {
        return analyze(projectId, request, null, client, eventSink);
    }

    /**
     * @param requesterName 发起人显示名(prompt 里"未指定负责人时默认发起人"的依据)。
     *                      查不到时传 null —— 模型会退化成"没有默认值",由 validator 兜底拒绝。
     */
    public ObjectNode analyze(String projectId, String request, String requesterName,
                              ModelClient client, BiConsumer<String, String> eventSink) throws AgentError {
        List<JsonNode> messages = new ArrayList<>();
        messages.add(message("system", requesterName == null || requesterName.isBlank()
                ? PROMPT
                : PROMPT + "\n本次请求的发起人是「" + requesterName + "」；新增任务未指定负责人时默认由发起人负责。\n"));
        messages.add(message("user", request));
        for (int step = 0; step < LlmProperties.MAX_STEPS; step++) {
            ModelClient.Completion completion = client.complete(messages, tools.definitions(), false);
            messages.add(completion.message());
            JsonNode calls = completion.message().path("tool_calls");
            if (calls.isArray() && !calls.isEmpty()) {
                for (JsonNode call : calls) {
                    try {
                        String toolName = call.path("function").path("name").asText();
                        eventSink.accept("tool_call", "调用只读工具 " + toolName);
                        JsonNode arguments = mapper.readTree(call.path("function").path("arguments").asText("{}"));
                        ObjectNode toolMessage = message("tool", mapper.writeValueAsString(
                                tools.execute(projectId, toolName, arguments)));
                        toolMessage.put("tool_call_id", call.path("id").asText());
                        messages.add(toolMessage);
                    } catch (Exception e) {
                        if (e instanceof AgentError agentError) throw agentError;
                        throw new AgentError("invalid_tool", "工具调用失败");
                    }
                }
                continue;
            }
            ModelClient.Completion finalCompletion = client.complete(messages, tools.definitions(), true);
            return validator.validate(projectId, finalCompletion.message().path("content").asText("{}"));
        }
        throw new AgentError("step_limit", "规划分析达到轮数上限");
    }

    private ObjectNode message(String role, String content) {
        ObjectNode message = mapper.createObjectNode();
        message.put("role", role);
        message.put("content", content);
        return message;
    }
}
