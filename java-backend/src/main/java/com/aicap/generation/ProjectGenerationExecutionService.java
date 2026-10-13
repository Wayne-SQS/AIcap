package com.aicap.generation;

import com.aicap.common.ApiException;
import com.aicap.entity.Story;
import com.aicap.entity.Task;
import com.aicap.entity.Milestone;
import com.aicap.mapper.MilestoneMapper;
import com.aicap.entity.User;
import com.aicap.mapper.StoryMapper;
import com.aicap.mapper.TaskMapper;
import com.aicap.mapper.UserMapper;
import com.aicap.service.ProjectAccessService;
import com.aicap.service.TaskStoryRelationService;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.jdbc.core.JdbcTemplate;

import java.time.LocalDateTime;

@Service
@RequiredArgsConstructor
public class ProjectGenerationExecutionService {
    private final ProjectGenerationValidator validator;
    private final StoryMapper stories;
    private final TaskMapper tasks;
    private final MilestoneMapper milestones;
    private final UserMapper users;
    private final ObjectMapper mapper;
    private final JdbcTemplate jdbc;
    private final TaskStoryRelationService taskStoryRelations;
    private final ProjectAccessService projectAccess;

    public void execute(String draftJson, String strategy, User actor) {
        throw ApiException.badRequest("projectId 必填");
    }

    @Transactional
    public void execute(String projectId,String draftJson,String strategy,User actor){
        executeRows(projectId, draftJson, strategy, actor);
    }

    @Transactional
    public String createProjectAndExecute(String draftJson, User actor) {
        try {
            JsonNode draft = mapper.readTree(draftJson);
            if (!(draft instanceof com.fasterxml.jackson.databind.node.ObjectNode)) throw ApiException.conflict("草案格式已失效");
            String projectId = projectAccess.createGeneratedProject(draft, actor);
            executeRows(projectId, draftJson, "create", actor);
            return projectId;
        } catch (ApiException e) {
            throw e;
        } catch (Exception e) {
            throw ApiException.conflict("项目创建失败，事务已回滚");
        }
    }

    private void executeRows(String projectId,String draftJson,String strategy,User actor){
        if(actor==null||!java.util.Set.of("admin","owner","member").contains(actor.getRole()))throw ApiException.forbidden("无权限执行此操作");
        try {
            String scopedProjectId = ProjectAccessService.normalize(projectId);
            JsonNode draft=mapper.readTree(draftJson);
            if(!(draft instanceof com.fasterxml.jackson.databind.node.ObjectNode o))throw ApiException.conflict("草案格式已失效");
            validator.validateForExecution(o,strategy,scopedProjectId);
            if("replace".equals(strategy)) {
                jdbc.update("DELETE FROM milestone_tasks WHERE project_id=?", scopedProjectId);
                jdbc.update("DELETE FROM task_dependencies WHERE project_id=?", scopedProjectId);
                jdbc.update("DELETE FROM task_story_relations WHERE project_id=?", scopedProjectId);
                milestones.delete(new QueryWrapper<Milestone>().eq("project_id", scopedProjectId));
                tasks.delete(new QueryWrapper<Task>().eq("project_id", scopedProjectId));
                stories.delete(new QueryWrapper<Story>().eq("project_id", scopedProjectId));
            }
            for(JsonNode s:o.path("stories")) {
                Story row=new Story(); row.setProjectId(scopedProjectId); row.setId(s.path("id").asText());
                row.setTitle(s.path("title").asText()); row.setDescription(s.path("description").asText(""));
                row.setAcceptance(s.path("acceptance").asText("")); row.setPriority(s.path("priority").asText("Should"));
                row.setSprint(s.path("sprint").asInt(1)); row.setActivity(activityNumber(s, o.path("activities")));
                row.setStatus(s.path("status").asInt(0)); row.setOwnerId(s.path("owner_id").isNull()?null:s.path("owner_id").asInt());
                row.setCreatedAt(LocalDateTime.now()); stories.insert(row);
            }
            for(JsonNode t:o.path("tasks")) {
                String storyRef=t.path("story_ref").asText(null);
                Task row=new Task(); row.setProjectId(scopedProjectId); row.setId(t.path("id").asText());
                row.setName(t.path("name").asText()); row.setOwnerId(t.path("owner_id").asInt()); row.setHours(t.path("hours").asInt());
                row.setWeekStart(t.path("week_start").asInt()); row.setWeekEnd(t.path("week_end").asInt()); row.setStoryRef(storyRef);
                row.setStoryRefLegacy(storyRef); row.setKanbanCardId(t.path("kanban_card_id").asText(null));
                row.setKanbanCardIdLegacy(row.getKanbanCardId()); row.setEstimatedHours(t.path("estimated_hours").asInt(row.getHours()));
                row.setTaskType(t.path("task_type").asText("feature")); row.setDependsOn(t.path("depends_on").asText(null));
                row.setDependsOnLegacy(row.getDependsOn()); row.setPriority(t.path("priority").asText("Should"));
                row.setStatus(t.path("status").asInt(0)); row.setProgress(t.path("progress").asInt(0));
                row.setBlocked(t.path("blocked").asBoolean(false)?1:0);
                if (storyRef != null && !storyRef.isBlank()) {
                    String normalizedStoryRef = String.join(",", csv(storyRef));
                    row.setStoryRef(normalizedStoryRef);
                    row.setStoryRefLegacy(normalizedStoryRef);
                    String primaryStoryRef = csv(normalizedStoryRef).get(0);
                    Story story=stories.selectOne(new QueryWrapper<Story>().eq("project_id", scopedProjectId).eq("code", primaryStoryRef));
                    if (story == null) throw ApiException.badRequest("任务关联故事不存在: " + primaryStoryRef);
                    row.setStoryInternalId(story.getInternalId());
                }
                tasks.insert(row);
                taskStoryRelations.replaceRelations(scopedProjectId, row, row.getStoryRef());
            }
            for(Task row : tasks.selectList(new QueryWrapper<Task>().eq("project_id", scopedProjectId))) {
                for(String dependency : csv(row.getDependsOn())) {
                    Task predecessor=tasks.selectOne(new QueryWrapper<Task>().eq("project_id", scopedProjectId).eq("code", dependency));
                    if(predecessor == null) throw ApiException.badRequest("前置任务不存在: " + dependency);
                    jdbc.update("INSERT INTO task_dependencies(project_id,predecessor_task_internal_id,successor_task_internal_id,created_at) VALUES (?,?,?,NOW())", scopedProjectId, predecessor.getInternalId(), row.getInternalId());
                }
            }
            for(JsonNode m:o.path("milestones")) {
                Milestone row=new Milestone(); row.setProjectId(scopedProjectId); row.setId(m.path("id").asText());
                row.setName(m.path("name").asText()); row.setWeek(m.path("week").asInt()); row.setDescription(m.path("description").asText(""));
                row.setStatus(m.path("status").asText("planned")); String related=m.path("related_task_ids").asText(null);
                row.setRelatedTaskIds(related); row.setRelatedTaskIdsLegacy(related); milestones.insert(row);
                for(String taskCode : csv(related)) {
                    Task task=tasks.selectOne(new QueryWrapper<Task>().eq("project_id", scopedProjectId).eq("code", taskCode));
                    if(task == null) throw ApiException.badRequest("里程碑关联任务不存在: " + taskCode);
                    jdbc.update("INSERT INTO milestone_tasks(project_id,milestone_internal_id,task_internal_id,created_at) VALUES (?,?,?,NOW())", scopedProjectId, row.getInternalId(), task.getInternalId());
                }
            }
        }
        catch(ApiException e){throw e;} catch(Exception e){throw ApiException.conflict("项目数据写入失败，事务已回滚");}
    }

    private java.util.List<String> csv(String value) {
        if (value == null || value.isBlank()) return java.util.List.of();
        return java.util.Arrays.stream(value.split(",")).map(String::trim).filter(s -> !s.isBlank()).map(String::toUpperCase).distinct().toList();
    }

    /** Translate the detailed draft's canonical A<n> Activity code back to the
     * existing integer Story column at the persistence boundary. */
    private int activityNumber(JsonNode story, JsonNode activities) {
        JsonNode value = story.path("activity");
        if (value.isIntegralNumber()) return Math.max(1, value.asInt());
        String reference = value.asText("").trim();
        int ordinal = 1;
        if (activities != null && activities.isArray()) for (JsonNode activity : activities) {
            String code = activity.path("code").asText("").trim();
            String id = activity.path("id").asText("").trim();
            String name = activity.path("name").asText(activity.path("title").asText("")).trim();
            if (reference.equalsIgnoreCase(code) || reference.equalsIgnoreCase(id)
                    || reference.equalsIgnoreCase(name) || reference.equals(String.valueOf(ordinal))
                    || reference.equalsIgnoreCase("A" + ordinal)
                    || reference.equalsIgnoreCase("activity-" + ordinal)
                    || reference.equalsIgnoreCase("activity_" + ordinal)) return ordinal;
            if (id.matches("\\d+")) {
                int numeric = Integer.parseInt(id);
                if (reference.equals(String.valueOf(numeric))) return numeric;
            }
            ordinal++;
        }
        String suffix = reference.replaceFirst("(?i)^A", "").replaceFirst("(?i)^activity[-_]", "");
        try { return Math.max(1, Integer.parseInt(suffix)); } catch (NumberFormatException ignored) { return 1; }
    }
}
