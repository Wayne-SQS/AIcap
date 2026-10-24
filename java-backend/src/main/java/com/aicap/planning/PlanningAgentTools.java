package com.aicap.planning;

import com.aicap.common.AgentError;
import com.aicap.entity.Story;
import com.aicap.entity.Task;
import com.aicap.entity.User;
import com.aicap.entity.Milestone;
import com.aicap.mapper.MilestoneMapper;
import com.aicap.mapper.StoryMapper;
import com.aicap.mapper.TaskMapper;
import com.aicap.mapper.UserMapper;
import com.aicap.mapper.ProjectMemberMapper;
import com.aicap.entity.ProjectMember;
import com.aicap.entity.Project;
import com.aicap.mapper.ProjectMapper;
import com.aicap.service.ProjectScopedResolver;
import com.aicap.service.ProjectCapacityPolicy;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;

/** Planning Agent 的只读工具层:所有结果直接来自当前数据库。 */
@Component
@RequiredArgsConstructor
public class PlanningAgentTools {
    private final TaskMapper tasks;
    private final StoryMapper stories;
    private final UserMapper users;
    private final ProjectMemberMapper projectMembers;
    private final ProjectMapper projects;
    private final MilestoneMapper milestones;
    private final ObjectMapper mapper;
    private final ProjectScopedResolver resolver;

    public List<JsonNode> definitions() {
        return List.of(
                fn("get_project_overview", "读取真实项目概况", empty()),
                fn("get_stories", "读取真实用户故事，可按 sprint/activity/owner_id 筛选",
                        params(Map.of("sprint", "integer", "activity", "integer", "owner_id", "integer"))),
                fn("get_tasks", "读取真实任务，可按 owner_id/week/sprint 筛选",
                        params(Map.of("owner_id", "integer", "week", "integer", "sprint", "integer"))),
                fn("get_members", "读取真实成员、角色与每周容量 capacity_hours（null 表示未配置）", empty()),
                fn("get_sprints", "读取当前项目有效迭代与周范围", empty()),
                fn("get_task_by_id", "读取指定真实任务", param("task_id", "string")),
                fn("get_story_by_id", "读取指定真实用户故事", param("story_id", "string")),
                fn("get_member_tasks", "读取指定成员的真实任务", param("owner_id", "integer")),
                fn("get_task_schedule", "读取任务当前排期", param("task_id", "string")),
                fn("get_task_dependencies", "读取任务的前置和后续依赖", param("task_id", "string")),
                fn("get_task_priority", "读取任务当前优先级", param("task_id", "string")),
                fn("get_milestones", "读取真实项目里程碑", empty()),
                fn("get_milestone_by_id", "读取指定里程碑", param("milestone_id", "string")),
                fn("get_member_load", "读取指定成员六周负载和每周容量 weekly_capacity_hours", param("owner_id", "integer"))
        );
    }

    /** 入队时记录的最小上下文快照，用于审计，不替代模型运行期间的工具查询。 */
    public ObjectNode contextSnapshot() { throw new IllegalStateException("projectId 必填"); }

    public ObjectNode contextSnapshot(String projectId) {
        ObjectNode snapshot = mapper.createObjectNode();
        snapshot.set("overview", overview(projectId));
        snapshot.set("sprints", sprints(projectId).path("items"));
        snapshot.set("members", members(projectId).path("items"));
        return snapshot;
    }

    public JsonNode execute(String projectId, String name, JsonNode args) throws AgentError {
        if (!(args instanceof ObjectNode object)) {
            throw new AgentError("invalid_tool_arguments", "参数必须是对象");
        }
        return switch (name) {
            case "get_project_overview" -> overview(projectId);
            case "get_stories" -> storyList(projectId, object);
            case "get_tasks" -> taskList(projectId, object);
            case "get_members" -> members(projectId);
            case "get_sprints" -> sprints(projectId);
            case "get_task_by_id" -> task(projectId, object);
            case "get_story_by_id" -> story(projectId, object);
            case "get_member_tasks" -> memberTasks(projectId, object.path("owner_id").asInt());
            case "get_task_schedule" -> schedule(projectId, object);
            case "get_task_dependencies" -> dependencies(projectId, object);
            case "get_task_priority" -> priority(projectId, object);
            case "get_milestones" -> milestoneList(projectId);
            case "get_milestone_by_id" -> milestone(projectId, object);
            case "get_member_load" -> load(projectId, object.path("owner_id").asInt());
            default -> throw new AgentError("tool_not_allowed", "未授权工具");
        };
    }

    private ObjectNode overview(String projectId) {
        ObjectNode node = mapper.createObjectNode();
        node.put("task_count", tasks.selectCount(new QueryWrapper<Task>().eq("project_id", projectId)));
        node.put("story_count", stories.selectCount(new QueryWrapper<Story>().eq("project_id", projectId)));
        node.put("member_count", members(projectId).path("items").size());
        int weeks = projectWeeks(projectId);
        node.put("sprint_count", (weeks + 1) / 2);
        node.put("week_count", weeks);
        node.put("data_source", "database");
        return node;
    }

    private ObjectNode storyList(String projectId, ObjectNode args) {
        QueryWrapper<Story> query = new QueryWrapper<Story>().eq("project_id", projectId).orderByAsc("code");
        if (args.has("sprint")) query.eq("sprint", args.path("sprint").asInt());
        if (args.has("activity")) query.eq("activity", args.path("activity").asInt());
        if (args.has("owner_id")) query.eq("owner_id", args.path("owner_id").asInt());
        ArrayNode items = mapper.createArrayNode();
        stories.selectList(query).forEach(story -> items.add(storyJson(story)));
        ObjectNode result = mapper.createObjectNode();
        result.set("items", items);
        return result;
    }

    private ObjectNode taskList(String projectId, ObjectNode args) {
        QueryWrapper<Task> query = new QueryWrapper<Task>().eq("project_id", projectId).orderByAsc("code");
        if (args.has("owner_id")) query.eq("owner_id", args.path("owner_id").asInt());
        if (args.has("week")) {
            int week = args.path("week").asInt();
            query.le("week_start", week).ge("week_end", week);
        }
        if (args.has("sprint")) {
            int sprint = args.path("sprint").asInt();
            int start = (sprint - 1) * 2 + 1;
            int end = start + 1;
            query.le("week_start", end).ge("week_end", start);
        }
        ArrayNode items = mapper.createArrayNode();
        tasks.selectList(query).forEach(task -> items.add(taskJson(task)));
        ObjectNode result = mapper.createObjectNode();
        result.set("items", items);
        return result;
    }

    private ObjectNode task(String projectId, ObjectNode args) {
        String id = args.path("task_id").asText().trim().toUpperCase();
        Task task = resolver.findTask(projectId, id);
        return task == null ? missing("task", id) : taskJson(task);
    }

    private ObjectNode story(String projectId, ObjectNode args) {
        String id = args.path("story_id").asText().trim().toUpperCase();
        Story story = resolver.findStory(projectId, id);
        return story == null ? missing("story", id) : storyJson(story);
    }

    private ObjectNode memberTasks(String projectId, int ownerId) {
        if (users.selectById(ownerId) == null) return missing("member", String.valueOf(ownerId));
        return taskList(projectId, mapper.createObjectNode().put("owner_id", ownerId));
    }

    private ObjectNode schedule(String projectId, ObjectNode args) {
        String id = args.path("task_id").asText().trim().toUpperCase();
        Task task = resolver.findTask(projectId, id);
        if (task == null) return missing("task", id);
        ObjectNode result = taskJson(task);
        result.remove(List.of("name", "owner_id", "hours", "story_ref", "status", "depends_on"));
        result.put("sprint_start", (task.getWeekStart() - 1) / 2 + 1);
        result.put("sprint_end", (task.getWeekEnd() - 1) / 2 + 1);
        return result;
    }

    private ObjectNode dependencies(String projectId, ObjectNode args) {
        String id = args.path("task_id").asText().trim().toUpperCase();
        Task task = resolver.findTask(projectId, id);
        if (task == null) return missing("task", id);
        ArrayNode predecessors = mapper.createArrayNode();
        for (String ref : String.valueOf(task.getDependsOn() == null ? "" : task.getDependsOn()).split(",")) {
            String dep = ref.trim().toUpperCase();
            if (!dep.isBlank()) { Task row = resolver.findTask(projectId, dep); if (row != null) predecessors.add(taskJson(row)); }
        }
        ArrayNode dependents = mapper.createArrayNode();
        for (Task row : tasks.selectList(new QueryWrapper<Task>().eq("project_id", projectId))) {
            for (String ref : String.valueOf(row.getDependsOn() == null ? "" : row.getDependsOn()).split(",")) {
                if (id.equals(ref.trim().toUpperCase())) { dependents.add(taskJson(row)); break; }
            }
        }
        ObjectNode result = mapper.createObjectNode();
        result.put("task_id", id);
        result.set("predecessors", predecessors);
        result.set("dependents", dependents);
        return result;
    }

    private ObjectNode priority(String projectId, ObjectNode args) {
        String id = args.path("task_id").asText().trim().toUpperCase();
        Task task = resolver.findTask(projectId, id);
        if (task == null) return missing("task", id);
        ObjectNode result = mapper.createObjectNode(); result.put("task_id", id);
        result.put("priority", task.getPriority() == null ? "Should" : task.getPriority()); return result;
    }

    private ObjectNode milestoneList(String projectId) {
        ArrayNode items = mapper.createArrayNode();
        milestones.selectList(new QueryWrapper<Milestone>().eq("project_id", projectId).orderByAsc("week").orderByAsc("code")).forEach(m -> items.add(milestoneJson(m)));
        ObjectNode result = mapper.createObjectNode(); result.set("items", items); return result;
    }

    private ObjectNode milestone(String projectId, ObjectNode args) {
        String id = args.path("milestone_id").asText().trim().toUpperCase();
        Milestone row = resolver.findMilestone(projectId, id); return row == null ? missing("milestone", id) : milestoneJson(row);
    }

    private ObjectNode milestoneJson(Milestone row) {
        ObjectNode node = mapper.createObjectNode(); node.put("id", row.getId()); node.put("name", row.getName());
        node.put("week", row.getWeek()); node.put("description", row.getDescription()); node.put("status", row.getStatus());
        node.put("related_task_ids", row.getRelatedTaskIds()); return node;
    }

    private ObjectNode taskJson(Task task) {
        ObjectNode node = mapper.createObjectNode();
        node.put("id", task.getId());
        node.put("name", task.getName());
        if (task.getOwnerId() == null) node.putNull("owner_id"); else node.put("owner_id", task.getOwnerId());
        node.put("hours", task.getHours());
        node.put("week_start", task.getWeekStart());
        node.put("week_end", task.getWeekEnd());
        node.put("story_ref", task.getStoryRef());
        node.put("status", task.getStatus() == null ? 0 : task.getStatus());
        node.put("depends_on", task.getDependsOn());
        node.put("priority", task.getPriority() == null ? "Should" : task.getPriority());
        return node;
    }

    private ObjectNode storyJson(Story story) {
        ObjectNode node = mapper.createObjectNode();
        node.put("id", story.getId());
        node.put("title", story.getTitle());
        node.put("sprint", story.getSprint());
        node.put("activity", story.getActivity());
        node.put("priority", story.getPriority());
        node.put("status", story.getStatus());
        if (story.getOwnerId() == null) node.putNull("owner_id"); else node.put("owner_id", story.getOwnerId());
        return node;
    }

    private ObjectNode members(String projectId) {
        ArrayNode items = mapper.createArrayNode();
        for (ProjectMember link : projectMembers.selectList(new QueryWrapper<ProjectMember>().eq("project_id", projectId).orderByAsc("user_id"))) {
            User user = users.selectById(link.getUserId());
            if (user == null) continue;
            ObjectNode node = mapper.createObjectNode();
            node.put("id", user.getId());
            node.put("name", user.getDisplayName());
            node.put("role", link.getProjectRole());
            node.put("capacity_hours", link.getCapacityHours());
            node.put("weekly_capacity_hours", ProjectCapacityPolicy.weeklyCapacity(
                    link.getWeeklyCapacityHours(), link.getCapacityHours(), user.getId()));
            items.add(node);
        }
        ObjectNode result = mapper.createObjectNode();
        result.set("items", items);
        return result;
    }

    private ObjectNode sprints(String projectId) {
        ArrayNode items = mapper.createArrayNode();
        int weeks = projectWeeks(projectId);
        for (int sprint = 1; sprint <= (weeks + 1) / 2; sprint++) {
            ObjectNode node = mapper.createObjectNode();
            node.put("sprint", sprint);
            node.put("week_start", (sprint - 1) * 2 + 1);
            node.put("week_end", Math.min(sprint * 2, weeks));
            items.add(node);
        }
        ObjectNode result = mapper.createObjectNode();
        result.set("items", items);
        return result;
    }

    private ObjectNode load(String projectId, int ownerId) {
        User user = users.selectById(ownerId);
        if (user == null) return missing("member", String.valueOf(ownerId));
        ProjectMember link = projectMembers.selectOne(new QueryWrapper<ProjectMember>()
                .eq("project_id", projectId).eq("user_id", ownerId).last("LIMIT 1"));
        if (link == null) return missing("member", String.valueOf(ownerId));
        int[] weekly = new int[projectWeeks(projectId)];
        for (Task task : tasks.selectList(new QueryWrapper<Task>().eq("project_id", projectId).eq("owner_id", ownerId))) {
            int start = Math.max(1, task.getWeekStart());
            int end = Math.min(weekly.length, task.getWeekEnd());
            int count = Math.max(1, end - start + 1);
            int hours = task.getHours() == null ? 0 : task.getHours();
            int base = hours / count;
            int remainder = hours % count;
            for (int week = start; week <= end; week++) weekly[week - 1] += base + (week - start < remainder ? 1 : 0);
        }
        ArrayNode values = mapper.createArrayNode();
        for (int hours : weekly) values.add(hours);
        ObjectNode result = mapper.createObjectNode();
        result.put("owner_id", ownerId);
        result.put("capacity_hours", link.getCapacityHours());
        result.put("weekly_capacity_hours", ProjectCapacityPolicy.weeklyCapacity(
                link.getWeeklyCapacityHours(), link.getCapacityHours(), ownerId));
        result.set("weekly_hours", values);
        return result;
    }

    private ObjectNode missing(String entity, String requestedId) {
        ObjectNode node = mapper.createObjectNode();
        node.put("found", false);
        node.put("entity", entity);
        node.put("requested_id", requestedId);
        return node;
    }

    private int projectWeeks(String projectId) {
        Project project = projects.selectById(projectId);
        return ProjectCapacityPolicy.projectWeeks(project);
    }

    private ObjectNode empty() {
        ObjectNode result = mapper.createObjectNode();
        result.put("type", "object");
        result.set("properties", mapper.createObjectNode());
        return result;
    }

    private ObjectNode param(String name, String type) { return params(Map.of(name, type)); }

    private ObjectNode params(Map<String, String> definitions) {
        ObjectNode properties = mapper.createObjectNode();
        definitions.forEach((name, type) -> properties.set(name, mapper.createObjectNode().put("type", type)));
        ObjectNode result = mapper.createObjectNode();
        result.put("type", "object");
        result.set("properties", properties);
        return result;
    }

    private ObjectNode fn(String name, String description, ObjectNode parameters) {
        ObjectNode function = mapper.createObjectNode();
        function.put("name", name);
        function.put("description", description);
        function.set("parameters", parameters);
        ObjectNode result = mapper.createObjectNode();
        result.put("type", "function");
        result.set("function", function);
        return result;
    }
}
