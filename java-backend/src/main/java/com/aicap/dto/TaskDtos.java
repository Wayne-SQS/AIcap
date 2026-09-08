package com.aicap.dto;

import com.aicap.entity.Task;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.List;

/** 任务域 DTO(字段名对齐 FastAPI schemas:snake_case) */
public final class TaskDtos {

    private TaskDtos() {
    }

    /** 任务输出(TaskOut) */
    public record TaskOut(String id, String name,
                          @JsonProperty("owner_id") Integer ownerId,
                          Integer hours,
                          @JsonProperty("week_start") Integer weekStart,
                          @JsonProperty("week_end") Integer weekEnd,
                          @JsonProperty("story_ref") String storyRef,
                          @JsonProperty("kanban_card_id") String kanbanCardId,
                          @JsonProperty("estimated_hours") Integer estimatedHours,
                          @JsonProperty("task_type") String taskType,
                          Integer status) {
    }

    /** 任务删除/未用 */
    public record DeleteOut(Boolean ok) {
    }

    public static TaskOut toOut(Task t) {
        return new TaskOut(t.getId(), t.getName(), t.getOwnerId(), t.getHours(),
                t.getWeekStart(), t.getWeekEnd(), t.getStoryRef(), t.getKanbanCardId(),
                t.getEstimatedHours() == null ? 0 : t.getEstimatedHours(),
                t.getTaskType() == null ? "feature" : t.getTaskType(),
                t.getStatus() == null ? 0 : t.getStatus());
    }

    public static List<TaskOut> toOutList(List<Task> list) {
        return list.stream().map(TaskDtos::toOut).toList();
    }
}
