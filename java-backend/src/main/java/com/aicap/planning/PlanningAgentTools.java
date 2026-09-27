package com.aicap.planning;

import com.aicap.agent.AgentError;
import com.aicap.entity.Story;
import com.aicap.entity.Task;
import com.aicap.entity.User;
import com.aicap.entity.Milestone;
import com.aicap.mapper.MilestoneMapper;
import com.aicap.mapper.StoryMapper;
import com.aicap.mapper.TaskMapper;
import com.aicap.mapper.UserMapper;
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
    private final MilestoneMapper milestones;
    private final ObjectMapper mapper;

    public List<JsonNode> definitions() {
        return List.of(
                fn("get_project_overview", "读取真实项目概况", empty()),
                fn("get_stories", "读取真实用户故事，可按 sprint/activity/owner_id 筛选",
                        params(Map.of("sprint", "integer", "activity", "integer", "owner_id", "integer"))),
                fn("get_tasks", "读取真实任务，可按 owner_id/week/sprint 筛选",
                        params(Map.of("owner_id", "integer", "week", "integer", "sprint", "integer"))),
                fn("get_members", "读取真实成员、角色和容量", empty()),
                fn("get_sprints", "读取当前项目有效迭代与周范围", empty()),
                fn("get_task_by_id", "读取指定真实任务", param("task_id", "string")),
                fn("get_story_by_id", "读取指定真实用户故事", param("story_id", "string")),
                fn("get_member_tasks", "读取指定成员的真实任务", param("owner_id", "integer")),
                fn("get_task_schedule", "读取任务当前排期", param("task_id", "string")),
                fn("get_task_dependencies", "读取任务的前置和后续依赖", param("task_id", "string")),
                fn("get_task_priority", "读取任务当前优先级", param("task_id", "string")),
                fn("get_milestones", "读取真实项目里程碑", empty()),
                fn("get_milestone_by_id", "读取指定里程碑", param("milestone_id", "string")),
                fn("get_member_load", "读取指定成员六周负载", param("owner_id", "integer"))
        );
    }

    /** 入队时记录的最小上下文快照，用于审计，不替代模型运行期间的工具查询。 */
    public ObjectNode contextSnapshot() {
        ObjectNode snapshot = mapper.createObjectNode();
        snapshot.set("overview", overview());
        snapshot.set("sprints", sprints().path("items"));
        snapshot.set("members", members().path("items"));
        return snapshot;
    }

    public JsonNode execute(String name, JsonNode args) throws AgentError {
        if (!(args instanceof ObjectNode object)) {
            throw new AgentError("invalid_tool_arguments", "参数必须是对象");
        }
        return switch (name) {
            case "get_project_overview" -> overview();
            case "get_stories" -> storyList(object);
            case "get_tasks" -> taskList(object);
            case "get_members" -> members();
            case "get_sprints" -> sprints();
            case "get_task_by_id" -> task(object);
            case "get_story_by_id" -> story(object);
            case "get_member_tasks" -> memberTasks(object.path("owner_id").asInt());
            case "get_task_schedule" -> schedule(object);
            case "get_task_dependencies" -> dependencies(object);
            case "get_task_priority" -> priority(object);
            case "get_milestones" -> milestoneList();
            case "get_milestone_by_id" -> milestone(object);
            case "get_member_load" -> load(object.path("owner_id").asInt());
            default -> throw new AgentError("tool_not_allowed", "未授权工具");
        };
    }

    private ObjectNode overview() {
        ObjectNode node = mapper.createObjectNode();
        node.put("task_count", tasks.selectCount(null));
        node.put("story_count", stories.selectCount(null));
        node.put("member_count", users.selectCount(null));
        node.put("sprint_count", 4);
        node.put("week_count", 6);
        node.put("data_source", "database");
        return node;
    }

    private ObjectNode storyList(ObjectNode args) {
        QueryWrapper<Story> query = new QueryWrapper<Story>().orderByAsc("id");
        if (args.has("sprint")) query.eq("sprint", args.path("sprint").asInt());
        if (args.has("activity")) query.eq("activity", args.path("activity").asInt());
        if (args.has("owner_id")) query.eq("owner_id", args.path("owner_id").asInt());
        ArrayNode items = mapper.createArrayNode();
        stories.selectList(query).forEach(story -> items.add(storyJson(story)));
        ObjectNode result = mapper.createObjectNode();
        result.set("items", items);
        return result;
    }

    private ObjectNode taskList(ObjectNode args) {
        QueryWrapper<Task> query = new QueryWrapper<Task>().orderByAsc("id");
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

    private ObjectNode task(ObjectNode args) {
        String id = args.path("task_id").asText().trim().toUpperCase();
        Task task = tasks.selectById(id);
        return task == null ? missing("task", id) : taskJson(task);
    }

    private ObjectNode story(ObjectNode args) {
        String id = args.path("story_id").asText().trim().toUpperCase();
        Story story = stories.selectById(id);
        return story == null ? missing("story", id) : storyJson(story);
    }

    private ObjectNode memberTasks(int ownerId) {
        if (users.selectById(ownerId) == null) return missing("member", String.valueOf(ownerId));
        return taskList(mapper.createObjectNode().put("owner_id", ownerId));
    }

    private ObjectNode schedule(ObjectNode args) {
        String id = args.path("task_id").asText().trim().toUpperCase();
        Task task = tasks.selectById(id);
        if (task == null) return missing("task", id);
        ObjectNode result = taskJson(task);
        result.remove(List.of("name", "owner_id", "hours", "story_ref", "status", "depends_on"));
        result.put("sprint_start", (task.getWeekStart() - 1) / 2 + 1);
        result.put("sprint_end", (task.getWeekEnd() - 1) / 2 + 1);
        return result;
    }

    private ObjectNode dependencies(ObjectNode args) {
        String id = args.path("task_id").asText().trim().toUpperCase();
        Task task = tasks.selectById(id);
        if (task == null) return missing("task", id);
        ArrayNode predecessors = mapper.createArrayNode();
        for (String ref : String.valueOf(task.getDependsOn() == null ? "" : task.getDependsOn()).split(",")) {
            String dep = ref.trim().toUpperCase();
            if (!dep.isBlank()) { Task row = tasks.selectById(dep); if (row != null) predecessors.add(taskJson(row)); }
        }
        ArrayNode dependents = mapper.createArrayNode();
        for (Task row : tasks.selectList(null)) {
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

    private ObjectNode priority(ObjectNode args) {
        String id = args.path("task_id").asText().trim().toUpperCase();
        Task task = tasks.selectById(id);
        if (task == null) return missing("task", id);
        ObjectNode result = mapper.createObjectNode(); result.put("task_id", id);
        result.put("priority", task.getPriority() == null ? "Should" : task.getPriority()); return result;
    }

    private ObjectNode milestoneList() {
        ArrayNode items = mapper.createArrayNode();
        milestones.selectList(new QueryWrapper<Milestone>().orderByAsc("week").orderByAsc("id")).forEach(m -> items.add(milestoneJson(m)));
        ObjectNode result = mapper.createObjectNode(); result.set("items", items); return result;
    }

    private ObjectNode milestone(ObjectNode args) {
        String id = args.path("milestone_id").asText().trim().toUpperCase();
        Milestone row = milestones.selectById(id); return row == null ? missing("milestone", id) : milestoneJson(row);
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

    private ObjectNode members() {
        ArrayNode items = mapper.createArrayNode();
        users.selectList(new QueryWrapper<User>().orderByAsc("id")).forEach(user -> {
            ObjectNode node = mapper.createObjectNode();
            node.put("id", user.getId());
            node.put("name", user.getDisplayName());
            node.put("role", user.getRole());
            node.put("capacity_hours", user.getCapacityHours());
            items.add(node);
        });
        ObjectNode result = mapper.createObjectNode();
        result.set("items", items);
        return result;
    }

    private ObjectNode sprints() {
        ArrayNode items = mapper.createArrayNode();
        for (int sprint = 1; sprint <= 4; sprint++) {
            ObjectNode node = mapper.createObjectNode();
            node.put("sprint", sprint);
            node.put("week_start", (sprint - 1) * 2 + 1);
            node.put("week_end", sprint * 2);
            items.add(node);
        }
        ObjectNode result = mapper.createObjectNode();
        result.set("items", items);
        return result;
    }

    private ObjectNode load(int ownerId) {
        User user = users.selectById(ownerId);
        if (user == null) return missing("member", String.valueOf(ownerId));
        int[] weekly = new int[6];
        for (Task task : tasks.selectList(new QueryWrapper<Task>().eq("owner_id", ownerId))) {
            int start = Math.max(1, task.getWeekStart());
            int end = Math.min(6, task.getWeekEnd());
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
        result.put("capacity_hours", user.getCapacityHours());
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
