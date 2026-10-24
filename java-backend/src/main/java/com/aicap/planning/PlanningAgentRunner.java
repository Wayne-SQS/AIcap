package com.aicap.planning;

import com.aicap.llm.LlmProperties;
import com.aicap.llm.ModelClient;
import com.aicap.common.AgentError;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
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
            当请求为“智能排期建议”时，必须先读取 get_tasks、get_members、get_sprints，并对拟调整的任务查询 get_task_dependencies、对相关成员查询 get_member_load；从真实任务读取优先级。
            综合硬依赖顺序、Must/Should/Could 优先级和成员逐周剩余容量，最多提出5项有依据的排期动作；在 summary/answer 中说明选择这些任务的依据、取舍和无法确定的条件，在 warnings 中说明过载或依赖风险。
            不把总容量当逐周空闲量，不根据缺失的实际工时推算进度；容量未配置、依赖不明或项目周范围不足时不要编造可执行日期，可返回空动作并说明需补的数据。任何排期建议仍须人工确认。
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
        if (request != null && request.contains("智能排期建议")) {
            return analyzeSchedule(projectId, request, client, eventSink);
        }
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

    /**
     * US18 reads a bounded, complete scheduling snapshot before one structured model call.
     * This avoids asking the model to issue a separate tool call for every task/member;
     * the generic tool loop can exceed the provider's per-response tool-call limit.
     */
    private ObjectNode analyzeSchedule(String projectId, String request, ModelClient client,
                                       BiConsumer<String, String> eventSink) throws AgentError {
        ObjectNode empty = mapper.createObjectNode();
        JsonNode tasks = read(projectId, "get_tasks", empty, eventSink).path("items");
        JsonNode members = read(projectId, "get_members", empty, eventSink).path("items");
        JsonNode sprints = read(projectId, "get_sprints", empty, eventSink).path("items");
        if (!tasks.isArray() || !members.isArray() || !sprints.isArray()
                || tasks.size() > 80 || members.size() > 30) {
            throw new AgentError("schedule_context_limit", "排期数据过多或不完整，请缩小项目范围后重试");
        }
        ObjectNode context = mapper.createObjectNode();
        context.set("tasks", tasks);
        context.set("members", members);
        context.set("sprints", sprints);
        ArrayNode loads = mapper.createArrayNode();
        for (JsonNode member : members) {
            if (member.path("id").isIntegralNumber()) {
                loads.add(read(projectId, "get_member_load",
                        mapper.createObjectNode().put("owner_id", member.path("id").asInt()), eventSink));
            }
        }
        context.set("member_loads", loads);
        ArrayNode dependencies = mapper.createArrayNode();
        for (JsonNode task : tasks) {
            if (task.path("id").isTextual() && task.path("status").asInt(0) != 2) {
                dependencies.add(read(projectId, "get_task_dependencies",
                        mapper.createObjectNode().put("task_id", task.path("id").asText()), eventSink));
            }
        }
        context.set("task_dependencies", dependencies);
        String snapshot = context.toString();
        if (snapshot.length() > 100_000) {
            throw new AgentError("schedule_context_limit", "排期数据过多，请缩小项目范围后重试");
        }
        List<JsonNode> messages = List.of(
                message("system", PROMPT + "\nUS18 排期模式：下条消息包含已由服务端只读工具取得的当前项目数据。不得再请求工具。只对证据充分的最多 5 个任务给出动作；动作必须改变现有排期，绝不能把当前排期不变的任务写进 actions。可在证据不足时给出空动作与缺口。"),
                message("user", request + "\n只读项目数据：\n" + snapshot));
        ModelClient.Completion completion = client.complete(messages, List.of(), true);
        return validateScheduleCandidates(projectId, tasks, members, completion.message().path("content").asText("{}"));
    }

    /** Keep only actual, independently valid schedule moves; disclose every omitted candidate. */
    private ObjectNode validateScheduleCandidates(String projectId, JsonNode tasks, JsonNode members, String raw) throws AgentError {
        final JsonNode parsed;
        try {
            parsed = mapper.readTree(raw);
        } catch (Exception error) {
            throw new AgentError("invalid_plan", "模型没有返回合法规划 JSON");
        }
        if (!(parsed instanceof ObjectNode plan) || !plan.path("actions").isArray()) {
            throw new AgentError("invalid_plan", "模型没有返回合法规划 JSON");
        }
        ArrayNode accepted = mapper.createArrayNode();
        ArrayNode warnings = mapper.createArrayNode();
        if (plan.path("warnings").isArray()) {
            for (JsonNode warning : plan.path("warnings")) {
                if (warning.isTextual()) warnings.add(warning.asText());
            }
        }
        Set<String> seen = new HashSet<>();
        int inspected = 0;
        for (JsonNode candidate : plan.path("actions")) {
            if (++inspected > 5) {
                warnings.add("模型提出超过 5 项动作，超出部分未进入待确认草案。");
                break;
            }
            String taskId = candidate.path("task_id").asText("");
            if (!"UPDATE_TASK_SCHEDULE".equals(candidate.path("type").asText()) || !seen.add(taskId)) {
                warnings.add("已略过非排期或重复的候选动作。");
                continue;
            }
            JsonNode current = null;
            for (JsonNode task : tasks) {
                if (taskId.equals(task.path("id").asText())) { current = task; break; }
            }
            if (current != null) {
                int oldStart = current.path("week_start").asInt(-1);
                int oldEnd = current.path("week_end").asInt(-1);
                int start = candidate.path("start_week").asInt(-1);
                int end = candidate.path("end_week").asInt(-1);
                if (candidate.path("target_week").isIntegralNumber()) {
                    start = candidate.path("target_week").asInt();
                    end = start + oldEnd - oldStart;
                } else if (start >= 0 && end < 0) end = start + oldEnd - oldStart;
                else if (start < 0 && end >= 0) start = end - oldEnd + oldStart;
                if (start == oldStart && end == oldEnd) {
                    warnings.add(taskId + " 当前排期未变化，已略过该动作。");
                    continue;
                }
            }
            ObjectNode trial = plan.deepCopy();
            trial.set("actions", mapper.createArrayNode().add(candidate.deepCopy()));
            trial.set("affected_entities", mapper.createArrayNode());
            try {
                ObjectNode checked = validator.validate(projectId, trial.toString());
                JsonNode action = checked.path("actions").path(0);
                if (action.path("start_week").asInt() == action.path("before_start_week").asInt()
                        && action.path("end_week").asInt() == action.path("before_end_week").asInt()) {
                    warnings.add(taskId + " 当前排期未变化，已略过该动作。");
                } else {
                    ArrayNode proposed = accepted.deepCopy();
                    proposed.add(action.deepCopy());
                    String risk = newScheduleRisk(tasks, members, proposed);
                    if (risk == null) accepted.add(action.deepCopy());
                    else warnings.add(taskId + " 会引入新的排期风险，已略过：" + risk);
                }
            } catch (AgentError error) {
                warnings.add(taskId + " 的排期未通过校验，已略过：" + error.getMessage());
            }
        }
        plan.set("actions", accepted);
        plan.set("affected_entities", mapper.createArrayNode());
        plan.set("warnings", warnings);
        List<String> retained = new ArrayList<>();
        accepted.forEach(action -> retained.add(action.path("task_id").asText()));
        plan.put("summary", retained.isEmpty() ? "当前没有通过安全校验的排期调整" :
                "基于真实任务、依赖与逐周容量，最终生成 " + retained.size()
                        + " 项待确认排期调整：" + String.join("、", retained));
        if (inspected != accepted.size()) {
            plan.put("answer", retained.isEmpty()
                    ? "模型候选排期均未通过服务端校验；请查看风险与数据缺口后重新分析。"
                    : "最终可确认的任务排期调整：" + String.join("、", retained)
                    + "。其他模型候选已剔除，原因见风险提示；请核对影响分析后确认。");
        }
        return validator.validate(projectId, plan.toString());
    }

    /** Reject a candidate that worsens a hard dependency or creates/worsens weekly overload. */
    private String newScheduleRisk(JsonNode tasks, JsonNode members, ArrayNode proposed) {
        Map<String, JsonNode> byId = new HashMap<>();
        Map<String, int[]> schedule = new HashMap<>();
        for (JsonNode task : tasks) {
            String id = task.path("id").asText();
            byId.put(id, task);
            schedule.put(id, new int[]{task.path("week_start").asInt(), task.path("week_end").asInt()});
        }
        for (JsonNode action : proposed) {
            schedule.put(action.path("task_id").asText(), new int[]{
                    action.path("start_week").asInt(), action.path("end_week").asInt()});
        }
        for (JsonNode task : tasks) {
            String id = task.path("id").asText();
            for (String dependency : task.path("depends_on").asText("").split(",")) {
                JsonNode predecessor = byId.get(dependency.trim());
                if (predecessor == null) continue;
                int beforeGap = predecessor.path("week_end").asInt() - task.path("week_start").asInt();
                int afterGap = schedule.get(dependency.trim())[1] - schedule.get(id)[0];
                if (afterGap > 0 && afterGap > beforeGap) {
                    return id + " 早于前置任务 " + dependency.trim() + " 完成";
                }
            }
        }
        Map<Integer, Double> capacity = new HashMap<>();
        for (JsonNode member : members) {
            if (member.path("id").isIntegralNumber() && member.path("weekly_capacity_hours").isNumber()) {
                capacity.put(member.path("id").asInt(), member.path("weekly_capacity_hours").asDouble());
            }
        }
        Map<Integer, int[]> beforeLoad = weeklyLoad(tasks, null);
        Map<Integer, int[]> afterLoad = weeklyLoad(tasks, schedule);
        for (var owner : afterLoad.entrySet()) {
            double limit = capacity.getOrDefault(owner.getKey(), Double.POSITIVE_INFINITY);
            int[] baseline = beforeLoad.getOrDefault(owner.getKey(), new int[6]);
            for (int week = 0; week < 6; week++) {
                if (owner.getValue()[week] > limit && owner.getValue()[week] > baseline[week]) {
                    return "成员 " + owner.getKey() + " W" + (week + 1) + " 负载将超过周容量";
                }
            }
        }
        return null;
    }

    private Map<Integer, int[]> weeklyLoad(JsonNode tasks, Map<String, int[]> schedule) {
        Map<Integer, int[]> loads = new HashMap<>();
        for (JsonNode task : tasks) {
            if (!task.path("owner_id").isIntegralNumber()) continue;
            int[] range = schedule == null ? new int[]{task.path("week_start").asInt(), task.path("week_end").asInt()}
                    : schedule.get(task.path("id").asText());
            int start = Math.max(1, range[0]);
            int end = Math.min(6, range[1]);
            int count = Math.max(1, end - start + 1);
            int hours = task.path("hours").asInt(0);
            int[] weekly = loads.computeIfAbsent(task.path("owner_id").asInt(), ignored -> new int[6]);
            for (int week = start; week <= end; week++) {
                weekly[week - 1] += hours / count + (week - start < hours % count ? 1 : 0);
            }
        }
        return loads;
    }

    private JsonNode read(String projectId, String name, ObjectNode arguments,
                          BiConsumer<String, String> eventSink) throws AgentError {
        eventSink.accept("tool_call", "调用只读工具 " + name);
        return tools.execute(projectId, name, arguments);
    }

    private ObjectNode message(String role, String content) {
        ObjectNode message = mapper.createObjectNode();
        message.put("role", role);
        message.put("content", content);
        return message;
    }
}
