package com.aicap.planning;

import com.aicap.agent.AgentError;
import com.aicap.agent.AgentProperties;
import com.aicap.agent.ModelClient;
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
            最终只输出 JSON，字段为 intent,summary,answer,actions,affected_entities,warnings,requires_confirmation。
            不确定目标实体时不要猜 ID，应通过工具查询并在 answer 中说明无法确定。
            """;

    public ObjectNode analyze(String request, ModelClient client) throws AgentError {
        return analyze(request, client, (kind, detail) -> { });
    }

    public ObjectNode analyze(String request, ModelClient client, BiConsumer<String, String> eventSink) throws AgentError {
        List<JsonNode> messages = new ArrayList<>();
        messages.add(message("system", PROMPT));
        messages.add(message("user", request));
        for (int step = 0; step < AgentProperties.MAX_STEPS; step++) {
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
                                tools.execute(toolName, arguments)));
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
            return validator.validate(finalCompletion.message().path("content").asText("{}"));
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
