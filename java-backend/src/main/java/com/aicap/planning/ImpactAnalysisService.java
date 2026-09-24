package com.aicap.planning;

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
import org.springframework.stereotype.Service;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** 规划、拖拽和确认前共用的五类影响分析规则。只读，不修改业务数据。 */
@Service
@RequiredArgsConstructor
public class ImpactAnalysisService {
    private final TaskMapper tasks;
    private final StoryMapper stories;
    private final UserMapper users;
    private final MilestoneMapper milestones;
    private final ObjectMapper mapper;

    public ObjectNode analyze(ObjectNode plan) {
        ObjectNode report = mapper.createObjectNode();
        ArrayNode load = mapper.createArrayNode();
        ArrayNode dependency = mapper.createArrayNode();
        ArrayNode sprint = mapper.createArrayNode();
        ArrayNode delay = mapper.createArrayNode();
        ArrayNode future = mapper.createArrayNode();
        Map<Integer, int[]> beforeLoads = new HashMap<>();
        Map<Integer, int[]> afterLoads = new HashMap<>();
        tasks.selectList(null).forEach(task -> {
            if (task.getOwnerId() != null) {
                int[] values = weeklyLoad(task);
                beforeLoads.computeIfAbsent(task.getOwnerId(), ignored -> new int[6]);
                afterLoads.computeIfAbsent(task.getOwnerId(), ignored -> new int[6]);
                add(beforeLoads.get(task.getOwnerId()), values);
                add(afterLoads.get(task.getOwnerId()), values);
            }
        });

        for (JsonNode raw : plan.path("actions")) {
            if (!(raw instanceof ObjectNode action)) continue;
            String type = action.path("type").asText();
            String taskId = action.path("task_id").asText("").toUpperCase();
            if (type.startsWith("UPDATE_TASK_")) {
                Task task = tasks.selectById(taskId);
                if (task == null) continue;
                int oldStart = task.getWeekStart(), oldEnd = task.getWeekEnd();
                int newStart = intValue(action, "start_week", oldStart);
                int newEnd = intValue(action, "end_week", oldEnd);
                int oldOwner = task.getOwnerId() == null ? -1 : task.getOwnerId();
                int newOwner = type.endsWith("OWNER") ? intValue(action, "to_owner_id", oldOwner) : oldOwner;
                subtract(afterLoads.computeIfAbsent(oldOwner, ignored -> new int[6]), weeklyHours(task));
                add(afterLoads.computeIfAbsent(newOwner, ignored -> new int[6]), weeklyHours(task, newStart, newEnd));
                User owner = newOwner < 0 ? null : users.selectById(newOwner);
                if (owner != null) {
                    int[] before = beforeLoads.getOrDefault(newOwner, new int[6]);
                    int[] after = afterLoads.getOrDefault(newOwner, new int[6]);
                    int weeklyCapacity = Math.max(1, owner.getCapacityHours() / 6);
                    for (int week = 0; week < 6; week++) {
                        if (after[week] > weeklyCapacity) {
                            addRisk(load, "high", taskId, owner.getDisplayName() + " W" + (week + 1)
                                    + "：修改前 " + before[week] + "h，修改后 " + after[week]
                                    + "h，周容量 " + weeklyCapacity + "h");
                        }
                    }
                    if (sum(before) != sum(after)) {
                        addRisk(load, "info", taskId, "成员 " + owner.getDisplayName() + " 总工时 "
                                + sum(before) + "h → " + sum(after) + "h");
                    }
                }
                if (type.endsWith("SCHEDULE")) {
                    checkDependencies(task, newStart, newEnd, dependency, future);
                    if (newEnd > oldEnd) {
                        addRisk(delay, "medium", taskId, taskId + " 预计延期 " + (newEnd - oldEnd) + " 周：W"
                                + oldStart + "-W" + oldEnd + " → W" + newStart + "-W" + newEnd);
                    }
                    checkStorySprint(task, newStart, newEnd, sprint);
                } else if ("UPDATE_TASK_DEPENDENCY".equals(type)) {
                    checkDependencyChange(task, action, dependency, future);
                } else if ("UPDATE_TASK_PRIORITY".equals(type)) {
                    String before = action.path("before_priority").asText(task.getPriority() == null ? "Should" : task.getPriority());
                    String after = action.path("after_priority").asText(before);
                    if (!before.equals(after)) {
                        addRisk(sprint, "info", taskId, taskId + " 优先级 " + before + " → " + after + "，请重新确认 Sprint 执行顺序");
                        analyzePriority(task, after, sprint, delay);
                    }
                }
            } else if ("UPDATE_STORY_SPRINT".equals(type)) {
                Story story = stories.selectById(action.path("story_id").asText().toUpperCase());
                if (story == null) continue;
                int next = intValue(action, "sprint", story.getSprint());
                if (next != story.getSprint()) {
                    int beforeCount = stories.selectList(new QueryWrapper<Story>().eq("sprint", next)).size();
                    int afterCount = beforeCount + 1;
                    addRisk(sprint, "medium", story.getId(), "Sprint" + next + " 故事数量 " + beforeCount + " → " + afterCount);
                }
                for (Task task : tasks.selectList(null)) {
                    if (containsRef(task.getStoryRef(), story.getId()) && !coversSprint(task, next)) {
                        addRisk(sprint, "medium", task.getId(), task.getId() + " 的执行周与 " + story.getId() + " 的 Sprint " + next + " 不一致");
                    }
                }
            } else if ("CREATE_MILESTONE".equals(type)) {
                checkMilestoneTasks(csvValue(action.get("related_task_ids"), ""), intValue(action, "week", -1), delay, sprint, future);
            } else if ("UPDATE_MILESTONE".equals(type)) {
                int week = intValue(action, "after_week", -1);
                String related = csvValue(action.get("after_related_task_ids"), "");
                Milestone current = milestones.selectById(action.path("milestone_id").asText("").toUpperCase());
                if (current != null && current.getWeek() != null && current.getWeek() != week) {
                    addRisk(delay, "medium", current.getId(), current.getId() + " 里程碑周次 W" + current.getWeek() + " → W" + week);
                }
                checkMilestoneTasks(related, week, delay, sprint, future);
            }
        }
        report.set("loadRisk", load);
        report.set("dependencyRisk", dependency);
        report.set("sprintRisk", sprint);
        report.set("delayRisk", delay);
        report.set("futureTaskImpact", future);
        return report;
    }

    private void checkDependencies(Task task, int start, int end, ArrayNode dependency, ArrayNode future) {
        for (String ref : refs(task.getDependsOn())) {
            Task predecessor = tasks.selectById(ref);
            if (predecessor != null && predecessor.getWeekEnd() > start) {
                addRisk(dependency, "high", task.getId(), task.getId() + " 早于前置任务 " + predecessor.getId() + " 完成");
            }
        }
        analyzeFutureDependents(task.getId(), end, dependency, future);
    }

    private void checkDependencyChange(Task task, JsonNode action, ArrayNode dependency, ArrayNode future) {
        Set<String> before = new HashSet<>(refs(csvValue(action.get("before_dependencies"), task.getDependsOn())));
        Set<String> after = new HashSet<>(refs(csvValue(action.get("after_dependencies"), task.getDependsOn())));
        for (String id : after) if (!before.contains(id)) {
            Task predecessor = tasks.selectById(id);
            if (predecessor != null) addRisk(dependency, predecessor.getWeekEnd() > task.getWeekStart() ? "high" : "info", task.getId(),
                    task.getId() + " 新增前置依赖 " + id + "（" + id + " 结束 W" + predecessor.getWeekEnd() + "）");
        }
        for (String id : before) if (!after.contains(id)) addRisk(dependency, "info", task.getId(), task.getId() + " 移除前置依赖 " + id);
        analyzeFutureDependents(task.getId(), task.getWeekEnd(), dependency, future);
    }

    private void analyzeFutureDependents(String taskId, int end, ArrayNode dependency, ArrayNode future) {
        Set<String> visited = new HashSet<>();
        ArrayDeque<String> queue = new ArrayDeque<>();
        tasks.selectList(null).stream().filter(item -> containsRef(item.getDependsOn(), taskId))
                .map(Task::getId).forEach(queue::add);
        while (!queue.isEmpty()) {
            String id = queue.remove();
            if (!visited.add(id)) continue;
            Task dependent = tasks.selectById(id);
            if (dependent == null) continue;
            if (dependent.getWeekStart() < end) {
                addRisk(dependency, "medium", id, id + " 依赖 " + taskId + "，当前从 W" + dependent.getWeekStart() + " 开始，可能延期");
            }
            addRisk(future, "medium", id, id + " 是后续受影响任务");
            tasks.selectList(null).stream().filter(item -> containsRef(item.getDependsOn(), id))
                    .map(Task::getId).forEach(queue::add);
        }
    }

    private void analyzePriority(Task task, String priority, ArrayNode sprint, ArrayNode delay) {
        int sprintNumber = (task.getWeekStart() - 1) / 2 + 1;
        List<Task> peers = tasks.selectList(null).stream()
                .filter(item -> !task.getId().equals(item.getId()))
                .filter(item -> item.getWeekStart() <= sprintNumber * 2
                        && item.getWeekEnd() >= (sprintNumber - 1) * 2 + 1)
                .toList();
        long must = peers.stream().filter(item -> "Must".equals(item.getPriority())).count();
        long should = peers.stream().filter(item -> item.getPriority() == null || "Should".equals(item.getPriority())).count();
        long could = peers.stream().filter(item -> "Could".equals(item.getPriority())).count();
        addRisk(sprint, "info", task.getId(), "Sprint" + sprintNumber + " 同期任务优先级：Must "
                + must + "、Should " + should + "、Could " + could);
        if ("Must".equals(priority) && task.getWeekStart() >= 5) {
            addRisk(delay, "medium", task.getId(), task.getId() + " 已提升为 Must，但当前排期在 W"
                    + task.getWeekStart() + "，存在高优先级任务排期靠后风险");
        }
    }

    private void checkMilestoneTasks(String related, int week, ArrayNode delay, ArrayNode sprint, ArrayNode future) {
        if (week < 1) return;
        int milestoneSprint = (week - 1) / 2 + 1;
        for (String ref : refs(related)) {
            Task task = tasks.selectById(ref);
            if (task != null) {
                addRisk(future, "info", ref, ref + " 是里程碑 W" + week + " 的关联任务（计划 W"
                        + task.getWeekStart() + "-W" + task.getWeekEnd() + "）");
                if (task.getWeekEnd() > week) {
                    addRisk(delay, "medium", ref, ref + " 计划结束 W" + task.getWeekEnd() + "，晚于里程碑 W" + week);
                }
                int taskSprint = (task.getWeekEnd() - 1) / 2 + 1;
                if (taskSprint != milestoneSprint) {
                    addRisk(sprint, "info", ref, ref + " 计划结束于 Sprint" + taskSprint
                            + "，里程碑位于 Sprint" + milestoneSprint);
                }
            }
        }
    }

    private void checkStorySprint(Task task, int start, int end, ArrayNode sprint) {
        Set<Integer> ranges = new HashSet<>();
        for (int week = start; week <= end; week++) ranges.add((week + 1) / 2);
        for (Story story : storiesFor(task)) if (!ranges.contains(story.getSprint())) {
            addRisk(sprint, "medium", story.getId(), "新排期与关联 " + story.getId() + " 的 Sprint" + story.getSprint() + " 不一致");
        }
    }

    private List<Story> storiesFor(Task task) {
        List<Story> result = new ArrayList<>();
        for (String ref : refs(task.getStoryRef())) {
            Story story = stories.selectById(ref);
            if (story != null) result.add(story);
        }
        return result;
    }

    private int[] weeklyLoad(Task task) { return weeklyHours(task, task.getWeekStart(), task.getWeekEnd()); }
    private int[] weeklyHours(Task task, int start, int end) {
        int[] result = new int[6];
        int hours = task.getHours() == null ? 0 : task.getHours();
        int count = Math.max(1, end - start + 1), base = hours / count, remainder = hours % count;
        for (int week = start; week <= end && week <= 6; week++) result[week - 1] = base + (week - start < remainder ? 1 : 0);
        return result;
    }
    private int[] weeklyHours(Task task) { return weeklyHours(task, task.getWeekStart(), task.getWeekEnd()); }
    private void add(int[] target, int[] values) { for (int i = 0; i < 6; i++) target[i] += values[i]; }
    private void subtract(int[] target, int[] values) { for (int i = 0; i < 6; i++) target[i] -= values[i]; }
    private int sum(int[] values) { int result = 0; for (int value : values) result += value; return result; }
    private int intValue(JsonNode node, String name, int fallback) { return node.path(name).isIntegralNumber() ? node.path(name).asInt() : fallback; }
    private String csvValue(JsonNode node, String fallback) {
        if (node == null || node.isNull()) return fallback;
        if (node.isArray()) {
            List<String> values = new ArrayList<>();
            node.forEach(item -> { if (!item.asText().isBlank()) values.add(item.asText().trim().toUpperCase()); });
            return String.join(",", values);
        }
        return node.asText(fallback);
    }
    private List<String> refs(String value) { List<String> result = new ArrayList<>(); if (value != null) for (String ref : value.split(",")) if (!ref.isBlank()) result.add(ref.trim().toUpperCase()); return result; }
    private boolean containsRef(String refs, String id) { return refs(refs).contains(id.toUpperCase()); }
    private boolean coversSprint(Task task, int sprint) { return task.getWeekStart() <= sprint * 2 && task.getWeekEnd() >= (sprint - 1) * 2 + 1; }
    private void addRisk(ArrayNode target, String severity, String entity, String message) { target.addObject().put("severity", severity).put("entity", entity).put("message", message); }
}
