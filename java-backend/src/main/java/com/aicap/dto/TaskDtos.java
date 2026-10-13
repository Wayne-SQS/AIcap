package com.aicap.dto;

import com.aicap.entity.Task;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.List;

/** 任务域 DTO(字段名对齐 FastAPI schemas:snake_case) */
public final class TaskDtos {

    private TaskDtos() {
    }

    /**
     * 任务输出(TaskOut)。
     * sprints 由排期(week_start..week_end)派生,不落库、不抄 Story.sprint:
     * 每 2 周一个 Sprint,范围裁剪到 1..6(对齐 FastAPI models.Task.sprints)。
     */
    public record TaskOut(String id, String name,
                          @JsonProperty("owner_id") Integer ownerId,
                          Integer hours,
                          @JsonProperty("week_start") Integer weekStart,
                          @JsonProperty("week_end") Integer weekEnd,
                          @JsonProperty("story_ref") String storyRef,
                          @JsonProperty("kanban_card_id") String kanbanCardId,
                          @JsonProperty("estimated_hours") Integer estimatedHours,
                          @JsonProperty("task_type") String taskType,
                          @JsonProperty("depends_on") String dependsOn,
                          String priority,
                          Integer status,
                          Integer progress,
                          Boolean blocked,
                          List<Integer> sprints,
                          @JsonProperty("primary_story_ref") String primaryStoryRef,
                          @JsonProperty("additional_story_refs") List<String> additionalStoryRefs,
                          @JsonProperty("story_refs") List<String> storyRefs) {
    }

    /** 任务删除/未用 */
    public record DeleteOut(Boolean ok) {
    }

    /** 排期 → Sprint 列表:max(1,week_start)..min(6,week_end),每 2 周归一个 Sprint */
    public static List<Integer> sprintsOf(Integer weekStart, Integer weekEnd) {
        int start = Math.max(1, weekStart == null ? 1 : weekStart);
        int end = Math.min(6, weekEnd == null ? 0 : weekEnd);
        List<Integer> sprints = new java.util.ArrayList<>();
        for (int week = start; week <= end; week++) {
            int sprint = (week - 1) / 2 + 1;
            if (!sprints.contains(sprint)) {
                sprints.add(sprint);
            }
        }
        return sprints;
    }

    public static TaskOut toOut(Task t) {
        return new TaskOut(t.getId(), t.getName(), t.getOwnerId(), t.getHours(),
                t.getWeekStart(), t.getWeekEnd(), t.getStoryRef(), t.getKanbanCardId(),
                t.getEstimatedHours() == null ? 0 : t.getEstimatedHours(),
                t.getTaskType() == null ? "feature" : t.getTaskType(),
                t.getDependsOn(), t.getPriority() == null ? "Should" : t.getPriority(),
                t.getStatus() == null ? 0 : t.getStatus(),
                t.getProgress() == null ? 0 : t.getProgress(),
                t.getBlocked() != null && t.getBlocked() != 0,
                sprintsOf(t.getWeekStart(), t.getWeekEnd()),
                firstStoryRef(t.getStoryRef()),
                additionalStoryRefs(t.getStoryRef()),
                storyRefs(t.getStoryRef()));
    }

    public static TaskOut toOut(Task t, List<String> refs) {
        List<String> safeRefs = refs == null ? List.of() : refs;
        String primary = safeRefs.isEmpty() ? null : safeRefs.get(0);
        List<String> additional = safeRefs.size() <= 1 ? List.of() : safeRefs.subList(1, safeRefs.size());
        TaskOut base = toOut(t);
        return new TaskOut(base.id(), base.name(), base.ownerId(), base.hours(), base.weekStart(), base.weekEnd(),
                base.storyRef(), base.kanbanCardId(), base.estimatedHours(), base.taskType(), base.dependsOn(),
                base.priority(), base.status(), base.progress(), base.blocked(), base.sprints(), primary,
                additional, safeRefs);
    }

    private static List<String> storyRefs(String value) {
        if (value == null || value.isBlank()) return List.of();
        return java.util.Arrays.stream(value.split(","))
                .map(String::trim).filter(s -> !s.isBlank()).toList();
    }

    private static String firstStoryRef(String value) {
        List<String> refs = storyRefs(value);
        return refs.isEmpty() ? null : refs.get(0);
    }

    private static List<String> additionalStoryRefs(String value) {
        List<String> refs = storyRefs(value);
        return refs.size() <= 1 ? List.of() : refs.subList(1, refs.size());
    }

    public static List<TaskOut> toOutList(List<Task> list) {
        return list.stream().map(TaskDtos::toOut).toList();
    }
}
