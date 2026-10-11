package com.aicap.planning;

import com.aicap.entity.User;
import com.aicap.service.ProjectPlanningActionService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** 在单个业务事务中再次校验并执行规划动作，避免部分动作成功。 */
@Service
@RequiredArgsConstructor
public class PlanningAgentExecutionService {
    private final PlanningAnalysisValidator validator;
    private final ProjectPlanningActionService actions;
    private final ObjectMapper mapper;

    @Transactional
    public JsonNode validateAndExecute(String resultJson, User user) throws Exception {
        JsonNode plan = mapper.readTree(resultJson);
        JsonNode validated = validator.validate(plan.toString());
        for (JsonNode action : validated.path("actions")) {
            switch (action.path("type").asText()) {
                case "UPDATE_TASK_OWNER" -> actions.updateTaskOwner(action.path("task_id").asText().toUpperCase(),
                        action.path("to_owner_id").asInt(), user);
                case "UPDATE_TASK_SCHEDULE" -> actions.updateTaskSchedule(action.path("task_id").asText().toUpperCase(),
                        action.path("start_week").asInt(), action.path("end_week").asInt(), user);
                case "UPDATE_STORY_SPRINT" -> actions.updateStorySprint(action.path("story_id").asText().toUpperCase(),
                        action.path("sprint").asInt(), user);
                case "UPDATE_TASK_DEPENDENCY" -> actions.updateTaskDependency(action.path("task_id").asText().toUpperCase(), csv(action.get("before_dependencies")), csv(action.get("after_dependencies")), user);
                case "UPDATE_TASK_PRIORITY" -> actions.updateTaskPriority(action.path("task_id").asText().toUpperCase(), action.path("before_priority").asText("Should"), action.path("after_priority").asText(), user);
                case "CREATE_MILESTONE" -> actions.createMilestone(action.path("milestone_id").asText().toUpperCase(), action.path("name").asText(), action.path("week").asInt(), action.path("description").asText(""), csv(action.get("related_task_ids")), user);
                case "UPDATE_MILESTONE" -> actions.updateMilestone(action.path("milestone_id").asText().toUpperCase(), action.path("before_snapshot").asText(), action.path("after_name").asText(), action.path("after_week").asInt(), action.path("after_description").asText(""), csv(action.get("after_related_task_ids")), user);
                default -> throw new IllegalStateException("规划包含未支持的动作");
            }
        }
        return validated;
    }

    private String csv(JsonNode node) {
        if (node == null || node.isNull()) return "";
        if (node.isArray()) {
            java.util.LinkedHashSet<String> values = new java.util.LinkedHashSet<>();
            node.forEach(item -> { if (!item.asText().isBlank()) values.add(item.asText().trim().toUpperCase()); });
            return String.join(",", values);
        }
        return node.asText("");
    }
}
