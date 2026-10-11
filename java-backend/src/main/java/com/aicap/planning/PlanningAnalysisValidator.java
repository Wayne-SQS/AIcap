package com.aicap.planning;

import com.aicap.agent.AgentError;
import com.aicap.entity.Story;
import com.aicap.entity.Task;
import com.aicap.entity.User;
import com.aicap.entity.Milestone;
import com.aicap.mapper.StoryMapper;
import com.aicap.mapper.TaskMapper;
import com.aicap.mapper.UserMapper;
import com.aicap.mapper.MilestoneMapper;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

/** 规划结果的确定性校验与影响分析，模型不能绕过这些规则。 */
@Component
@RequiredArgsConstructor
public class PlanningAnalysisValidator {
    private final TaskMapper tasks;
    private final StoryMapper stories;
    private final UserMapper users;
    private final ObjectMapper mapper;
    private final ImpactAnalysisService impactAnalysis;
    private final MilestoneMapper milestones;

    public ObjectNode validate(String text) throws AgentError {
        try {
            JsonNode parsed = mapper.readTree(text);
            if (!(parsed instanceof ObjectNode plan) || !plan.path("actions").isArray()
                    || !plan.path("summary").isTextual()) {
                throw new AgentError("invalid_plan", "模型没有返回合法规划 JSON");
            }

            Set<String> actionKeys = new HashSet<>();
            ArrayNode warnings = mapper.createArrayNode();
            copyTextWarnings(plan.path("warnings"), warnings);
            ArrayNode impacts = mapper.createArrayNode();
            for (JsonNode raw : plan.path("actions")) {
                if (!(raw instanceof ObjectNode action)) throw new AgentError("invalid_plan", "规划动作必须是对象");
                String type = action.path("type").asText(action.path("action_type").asText());
                action.put("type", type);
                switch (type) {
                    case "UPDATE_TASK_OWNER" -> validateOwner(action, actionKeys, warnings);
                    case "UPDATE_TASK_SCHEDULE" -> validateSchedule(action, actionKeys, warnings, impacts);
                    case "UPDATE_STORY_SPRINT" -> validateStorySprint(action, actionKeys, warnings, impacts);
                    case "UPDATE_TASK_DEPENDENCY" -> validateDependencyChange(action, actionKeys, warnings, impacts);
                    case "UPDATE_TASK_PRIORITY" -> validatePriority(action, actionKeys, impacts);
                    case "CREATE_MILESTONE" -> validateCreateMilestone(action, actionKeys, warnings, impacts);
                    case "UPDATE_MILESTONE" -> validateUpdateMilestone(action, actionKeys, warnings, impacts);
                    default -> throw new AgentError("unsupported_action", "不支持的规划动作: " + type);
                }
            }

            plan.set("warnings", warnings);
            plan.set("impacts", impacts);
            ObjectNode impactReport = impactAnalysis.analyze(plan);
            plan.set("impact_analysis", impactReport);
            appendImpactWarnings(impactReport, warnings);
            if (!plan.path("affected_entities").isArray()) plan.set("affected_entities", mapper.createArrayNode());
            ArrayNode affected = (ArrayNode) plan.path("affected_entities");
            for (String key : actionKeys) {
                String id = key.substring(key.indexOf(':') + 1);
                if (!containsText(affected, id)) affected.add(id);
            }
            plan.put("requires_confirmation", !actionKeys.isEmpty());
            return plan;
        } catch (AgentError e) {
            throw e;
        } catch (Exception e) {
            throw new AgentError("invalid_plan", "模型没有返回合法规划 JSON");
        }
    }

    private void validateOwner(ObjectNode action, Set<String> keys, ArrayNode warnings) throws AgentError {
        String taskId = taskId(action);
        if (!keys.add("task:" + taskId)) throw new AgentError("invalid_plan", "动作目标不能重复: " + taskId);
        if (!action.path("to_owner_id").isIntegralNumber()) throw new AgentError("invalid_plan", "负责人编号必须是整数");
        Task task = requireTask(taskId);
        User target = users.selectById(action.path("to_owner_id").asInt());
        if (target == null) throw new AgentError("entity_not_found", "成员不存在");
        int currentOwner = task.getOwnerId() == null ? -1 : task.getOwnerId();
        checkFromOwner(action, currentOwner, taskId);
        if (currentOwner == target.getId()) throw new AgentError("no_op", taskId + " 已由该成员负责");
        action.put("task_id", taskId);
        action.put("from_owner_id", currentOwner);
        action.put("from_owner_name", ownerName(currentOwner));
        action.put("to_owner_name", target.getDisplayName());
        analyzeOwnerLoad(task, target, warnings);
    }

    private void validateSchedule(ObjectNode action, Set<String> keys, ArrayNode warnings, ArrayNode impacts)
            throws AgentError {
        String taskId = taskId(action);
        if (!keys.add("task:" + taskId)) throw new AgentError("invalid_plan", "动作目标不能重复: " + taskId);
        Task task = requireTask(taskId);
        int oldStart = task.getWeekStart();
        int oldEnd = task.getWeekEnd();
        checkBefore(action, "before_start_week", oldStart, taskId + " 的开始周已变化");
        checkBefore(action, "before_end_week", oldEnd, taskId + " 的结束周已变化");
        int duration = oldEnd - oldStart;
        int start = integer(action, "start_week", -1);
        int end = integer(action, "end_week", -1);
        int targetWeek = integer(action, "target_week", -1);
        if (targetWeek != -1) { start = targetWeek; end = targetWeek + duration; }
        else if (start != -1 && end == -1) end = start + duration;
        else if (start == -1 && end != -1) start = end - duration;
        if (start < 1 || end > 6 || start > end) throw new AgentError("invalid_schedule", "任务排期必须在 W1-W6 且开始周不能晚于结束周");
        validateDependencies(task, start, end, warnings);
        action.put("task_id", taskId); action.put("before_start_week", oldStart); action.put("before_end_week", oldEnd);
        action.put("start_week", start); action.put("end_week", end);
        ObjectNode impact = mapper.createObjectNode();
        impact.put("entity", taskId); impact.put("kind", "task_schedule");
        impact.put("before", weekRange(oldStart, oldEnd)); impact.put("after", weekRange(start, end));
        impacts.add(impact);
    }

    private void validateStorySprint(ObjectNode action, Set<String> keys, ArrayNode warnings, ArrayNode impacts)
            throws AgentError {
        String storyId = action.path("story_id").asText(action.path("storyId").asText("")).trim().toUpperCase();
        if (storyId.isBlank()) throw new AgentError("invalid_plan", "Story 编号不能为空");
        if (!keys.add("story:" + storyId)) throw new AgentError("invalid_plan", "动作目标不能重复: " + storyId);
        Story story = stories.selectById(storyId);
        if (story == null) throw new AgentError("entity_not_found", "故事不存在: " + storyId);
        checkBefore(action, "before_sprint", story.getSprint(), storyId + " 的当前 Sprint 已变化");
        int sprint = integer(action, "sprint", integer(action, "sprint_id", -1));
        if (sprint < 1 || sprint > 4) throw new AgentError("invalid_sprint", "Sprint 必须在 1-4 之间");
        action.put("story_id", storyId); action.put("sprint", sprint);
        action.put("before_sprint", story.getSprint());
        ObjectNode impact = mapper.createObjectNode(); impact.put("entity", storyId); impact.put("kind", "story_sprint");
        impact.put("before", story.getSprint()); impact.put("after", sprint); impacts.add(impact);
        for (Task task : tasks.selectList(null)) {
            if (containsRef(task.getStoryRef(), storyId) && !coversSprint(task, sprint)) {
                warnings.add("Sprint 冲突：" + task.getId() + " 的执行周与 " + storyId + " 的 Sprint " + sprint + " 不一致");
            }
        }
    }

    private void validateDependencyChange(ObjectNode action, Set<String> keys, ArrayNode warnings, ArrayNode impacts) throws AgentError {
        String id = taskId(action); if (!keys.add("dependency:" + id)) throw new AgentError("invalid_plan", "任务依赖动作不能重复: " + id);
        Task task = requireTask(id); String before = action.has("before_dependencies") ? csv(action.get("before_dependencies")) : normalize(task.getDependsOn());
        if (!normalize(task.getDependsOn()).equals(before)) throw new AgentError("stale_plan", id + " 的当前依赖已变化，请重新分析");
        if (!action.has("after_dependencies")) throw new AgentError("invalid_plan", "修改后的任务依赖不能为空缺失");
        String after = csv(action.get("after_dependencies"));
        Set<String> deps = new java.util.LinkedHashSet<>(split(after)); if (deps.contains(id)) throw new AgentError("dependency_conflict", "任务不能依赖自身: " + id);
        for (String dep : deps) { Task p = tasks.selectById(dep); if (p == null) throw new AgentError("entity_not_found", "前置任务不存在: " + dep); if (p.getWeekEnd() > task.getWeekStart()) warnings.add("排期风险：" + p.getId() + " 结束 W" + p.getWeekEnd() + "，晚于 " + id + " 当前开始 W" + task.getWeekStart()); }
        if (wouldCycle(id, deps)) throw new AgentError("dependency_cycle", "任务依赖不能形成循环");
        action.put("task_id", id); action.put("before_dependencies", before); action.put("after_dependencies", after);
        ObjectNode impact = mapper.createObjectNode().put("entity", id).put("kind", "task_dependency").put("before", before).put("after", after); impacts.add(impact);
    }

    private void validatePriority(ObjectNode action, Set<String> keys, ArrayNode impacts) throws AgentError {
        String id = taskId(action); if (!keys.add("priority:" + id)) throw new AgentError("invalid_plan", "任务优先级动作不能重复: " + id); Task task = requireTask(id);
        String current = task.getPriority() == null ? "Should" : task.getPriority(); String before = action.path("before_priority").asText(current); String after = action.path("after_priority").asText("");
        if (!current.equals(before)) throw new AgentError("stale_plan", id + " 的当前优先级已变化，请重新分析"); if (!Set.of("Must", "Should", "Could").contains(after)) throw new AgentError("invalid_priority", "任务优先级必须是 Must/Should/Could");
        action.put("task_id", id); action.put("before_priority", current); action.put("after_priority", after); impacts.add(mapper.createObjectNode().put("entity", id).put("kind", "task_priority").put("before", current).put("after", after));
    }

    private void validateCreateMilestone(ObjectNode action, Set<String> keys, ArrayNode warnings, ArrayNode impacts) throws AgentError {
        String id = action.path("milestone_id").asText(action.path("id").asText("")).trim().toUpperCase(); if (!keys.add("milestone:" + id)) throw new AgentError("invalid_plan", "里程碑动作不能重复: " + id); if (id.isBlank() || milestones.selectById(id) != null) throw new AgentError("milestone_conflict", "里程碑编号已存在或为空: " + id);
        int week = integer(action, "week", -1); String related = csv(action.get("related_task_ids")); validateMilestoneFields(id, action.path("name").asText(""), week, related); action.put("milestone_id", id); action.put("week", week); action.put("related_task_ids", related); milestoneWarnings(related, week, warnings); impacts.add(mapper.createObjectNode().put("entity", id).put("kind", "milestone").put("before", "不存在").put("after", "W" + week));
    }

    private void validateUpdateMilestone(ObjectNode action, Set<String> keys, ArrayNode warnings, ArrayNode impacts) throws AgentError {
        String id = action.path("milestone_id").asText("").trim().toUpperCase(); if (!keys.add("milestone:" + id)) throw new AgentError("invalid_plan", "里程碑动作不能重复: " + id); Milestone row = milestones.selectById(id); if (row == null) throw new AgentError("entity_not_found", "里程碑不存在: " + id);
        JsonNode before = action.path("before"); if (before.has("week") && before.path("week").asInt() != row.getWeek()) throw new AgentError("stale_plan", "里程碑已发生变化，请重新生成规划"); JsonNode afterNode = action.get("after"); if (!(afterNode instanceof ObjectNode after)) throw new AgentError("invalid_plan", "里程碑修改必须提供 after 对象"); int week = integer(after, "week", row.getWeek()); String name = after.path("name").asText(row.getName()); String related = after.has("related_task_ids") ? csv(after.get("related_task_ids")) : normalize(row.getRelatedTaskIds()); validateMilestoneFields(id, name, week, related); action.put("milestone_id", id); action.put("after_name", name); action.put("after_week", week); action.put("after_description", after.path("description").asText(row.getDescription())); action.put("after_related_task_ids", related); action.put("before_snapshot", snapshot(row)); milestoneWarnings(related, week, warnings); impacts.add(mapper.createObjectNode().put("entity", id).put("kind", "milestone").put("before", "W" + row.getWeek()).put("after", "W" + week));
    }

    private void validateMilestoneFields(String id, String name, int week, String related) throws AgentError { if (!id.matches("M[A-Z0-9_-]+")) throw new AgentError("invalid_milestone", "里程碑编号格式无效"); if (name.isBlank()) throw new AgentError("invalid_milestone", "里程碑名称不能为空"); if (week < 1 || week > 6) throw new AgentError("invalid_milestone", "里程碑周次必须在 W1-W6"); for (String ref : split(related)) if (tasks.selectById(ref) == null) throw new AgentError("entity_not_found", "关联任务不存在: " + ref); }
    private void milestoneWarnings(String related, int week, ArrayNode warnings) { for (String ref : split(related)) { Task task = tasks.selectById(ref); if (task != null && task.getWeekEnd() > week) warnings.add("里程碑风险：" + ref + " 计划结束 W" + task.getWeekEnd() + " 晚于里程碑 W" + week); } }
    private String csv(JsonNode node) { if (node == null || node.isNull()) return ""; if (node.isArray()) { java.util.List<String> vals = new java.util.ArrayList<>(); node.forEach(n -> vals.add(n.asText().trim().toUpperCase())); return normalize(String.join(",", vals)); } return normalize(node.asText()); }
    private String normalize(String value) { return String.join(",", split(value)); }
    private java.util.Set<String> split(String value) { java.util.LinkedHashSet<String> out = new java.util.LinkedHashSet<>(); if (value != null) for (String x : value.split(",")) if (!x.trim().isBlank()) out.add(x.trim().toUpperCase()); return out; }
    private boolean wouldCycle(String id, Set<String> replacement) { return reaches(id, id, replacement, new HashSet<>()); }
    private boolean reaches(String target, String current, Set<String> replacement, Set<String> seen) { if (!seen.add(current)) return false; Task row = tasks.selectById(current); Set<String> deps = current.equals(target) ? replacement : split(row == null ? "" : row.getDependsOn()); for (String dep : deps) if (target.equals(dep) || reaches(target, dep, replacement, seen)) return true; return false; }
    private String snapshot(Milestone row) { return row.getName() + "|" + row.getWeek() + "|" + (row.getDescription() == null ? "" : row.getDescription()) + "|" + normalize(row.getRelatedTaskIds()); }

    private void validateDependencies(Task task, int newStart, int newEnd, ArrayNode warnings) throws AgentError {
        String depends = task.getDependsOn() == null ? "" : task.getDependsOn();
        for (String ref : depends.split(",")) {
            Task predecessor = tasks.selectById(ref.trim().toUpperCase());
            if (predecessor != null && predecessor.getWeekEnd() > newStart) {
                throw new AgentError("dependency_conflict", task.getId() + " 不能早于前置任务 " + predecessor.getId() + " 结束");
            }
        }
        for (Task dependent : tasks.selectList(null)) {
            if (containsRef(dependent.getDependsOn(), task.getId()) && dependent.getWeekStart() < newEnd) {
                warnings.add("依赖风险：" + dependent.getId() + " 当前从 W" + dependent.getWeekStart()
                        + " 开始，早于 " + task.getId() + " 新的结束周 W" + newEnd);
            }
        }
    }

    private void analyzeOwnerLoad(Task task, User target, ArrayNode warnings) {
        if (task.getHours() == null || task.getHours() <= 0) return;
        int[] load = weeklyLoad(target.getId());
        int start = Math.max(1, task.getWeekStart()); int end = Math.min(6, task.getWeekEnd());
        int count = Math.max(1, end - start + 1); int base = task.getHours() / count; int remainder = task.getHours() % count;
        for (int week = start; week <= end; week++) {
            int after = load[week - 1] + base + (week - start < remainder ? 1 : 0);
            if (after > target.getCapacityHours()) warnings.add("成员负载：" + target.getDisplayName() + " W" + week
                    + " 调整后 " + after + "h / 周容量 " + target.getCapacityHours() + "h");
        }
    }

    private Task requireTask(String id) throws AgentError { Task task = tasks.selectById(id); if (task == null) throw new AgentError("entity_not_found", "任务不存在: " + id); return task; }
    private String taskId(ObjectNode action) throws AgentError { String id = action.path("task_id").asText(action.path("taskId").asText("")).trim().toUpperCase(); if (id.isBlank()) throw new AgentError("invalid_plan", "Task 编号不能为空"); return id; }
    private void checkFromOwner(ObjectNode action, int current, String id) throws AgentError { if (action.has("from_owner_id") && (!action.path("from_owner_id").isIntegralNumber() || action.path("from_owner_id").asInt() != current)) throw new AgentError("stale_plan", id + " 的当前负责人已变化，请重新分析"); }
    private void checkBefore(ObjectNode action, String field, int current, String message) throws AgentError {
        if (action.has(field) && (!action.path(field).isIntegralNumber() || action.path(field).asInt() != current)) {
            throw new AgentError("stale_plan", "项目数据已发生变化，请重新生成规划。" + message);
        }
    }
    private int integer(ObjectNode node, String name, int fallback) {
        JsonNode value = node.get(name);
        if (value == null) return fallback;
        if (value.isIntegralNumber()) return value.asInt();
        if (value.isTextual()) {
            String digits = value.asText().replaceAll("[^0-9-]", "");
            try { return digits.isBlank() ? fallback : Integer.parseInt(digits); } catch (NumberFormatException ignored) { return fallback; }
        }
        return fallback;
    }
    private String ownerName(int id) { if (id < 0) return "未分配"; User user = users.selectById(id); return user == null ? "未知成员" : user.getDisplayName(); }
    private boolean containsRef(String refs, String id) { if (refs == null) return false; for (String ref : refs.split(",")) if (id.equals(ref.trim().toUpperCase())) return true; return false; }
    private boolean coversSprint(Task task, int sprint) { int start = Math.max(1, task.getWeekStart()); int end = Math.min(6, task.getWeekEnd()); return start <= sprint * 2 && end >= (sprint - 1) * 2 + 1; }
    private String weekRange(int start, int end) { return "W" + start + "-W" + end; }
    private boolean containsText(ArrayNode array, String value) { for (JsonNode node : array) if (value.equals(node.asText())) return true; return false; }
    private void copyTextWarnings(JsonNode source, ArrayNode target) { if (source.isArray()) source.forEach(node -> { if (node.isTextual() && !node.asText().isBlank()) target.add(node.asText()); }); }
    private void appendImpactWarnings(ObjectNode report, ArrayNode warnings) {
        for (String key : List.of("loadRisk", "dependencyRisk", "sprintRisk", "delayRisk", "futureTaskImpact")) {
            JsonNode items = report.path(key);
            if (items.isArray()) items.forEach(item -> { String message = item.path("message").asText(""); if (!message.isBlank() && !containsText(warnings, message)) warnings.add(message); });
        }
    }
    private int[] weeklyLoad(int ownerId) { int[] result = new int[6]; for (Task task : tasks.selectList(new QueryWrapper<Task>().eq("owner_id", ownerId))) { int start = Math.max(1, task.getWeekStart()); int end = Math.min(6, task.getWeekEnd()); int count = Math.max(1, end - start + 1); int base = task.getHours() / count; int remainder = task.getHours() % count; for (int week = start; week <= end; week++) result[week - 1] += base + (week - start < remainder ? 1 : 0); } return result; }
}
