package com.aicap.platform;

import com.aicap.common.AgentError;
import com.aicap.entity.Meeting;
import com.aicap.entity.PoolItem;
import com.aicap.entity.Story;
import com.aicap.entity.Task;
import com.aicap.entity.User;
import com.aicap.mapper.MeetingMapper;
import com.aicap.mapper.PoolItemMapper;
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

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import com.aicap.service.ProjectAccessService;
import com.aicap.service.ProjectAccessService;

/**
 * 会议 Agent 只读工具(对齐 FastAPI meeting_agent/tools.py)。
 * 本轮仅允许新增需求池建议;所有工具只读、不修改任务/故事。
 */
@Component
@RequiredArgsConstructor
public class MeetingTools {

    public static final Set<String> WRITER_ROLES = Set.of("admin", "owner", "member");

    private final UserMapper userMapper;
    private final StoryMapper storyMapper;
    private final PoolItemMapper poolItemMapper;
    private final TaskMapper taskMapper;
    private final MeetingMapper meetingMapper;
    private final ObjectMapper objectMapper;

    // ---------- 工具 JSON Schema 定义(对齐 TOOL_TYPES 的 model_json_schema) ----------

    private ObjectNode objectSchema(ObjectNode properties, ArrayNode required) {
        ObjectNode schema = objectMapper.createObjectNode();
        schema.put("type", "object");
        schema.set("properties", properties);
        schema.set("required", required);
        return schema;
    }

    private ObjectNode function(String name, String description, ObjectNode parameters) {
        ObjectNode function = objectMapper.createObjectNode();
        function.put("name", name);
        function.put("description", description);
        function.set("parameters", parameters);
        ObjectNode node = objectMapper.createObjectNode();
        node.put("type", "function");
        node.set("function", function);
        return node;
    }

    /** 只读查询到的会话工具列表(每次单独构建,防调用方修改污染) */
    public List<JsonNode> toolDefinitions() {
        List<JsonNode> list = new ArrayList<>();
        list.add(function("search_stories",
                "按关键词查询真实故事，检查新需求是否已有。最多20条，更多结果明确标记。",
                projectSearchSchema("按关键词搜索故事标题")));
        list.add(function("search_pool",
                "按关键词查询真实需求池，避免建议重复需求。最多20条。",
                searchSchema("按关键词搜索需求池标题")));
        list.add(function("list_tasks",
                "查询已有任务和工时，可按成员ID筛选。当前系统无任务状态或真实容量，不能推断是否过载。",
                projectTasksSchema()));
        list.add(function("list_members",
                "查询真实成员ID及显示名，名称有歧义时不能猜测。",
                emptySchema()));
        list.add(function("project_summary",
                "查询真实故事统计及当前数据能力限制。",
                projectOnlySchema()));
        list.add(function("list_meetings",
                "按项目列出已保存会议(id/标题/创建时间),按创建时间倒序,最多20条。"
                        + "用于把「最近一场」「标题里点名的那场」解析成 meeting_id;"
                        + "只回元数据,不返回会议转写正文。",
                meetingsListSchema()));
        return list;
    }

    private ObjectNode searchSchema(String title) {
        ObjectNode props = objectMapper.createObjectNode();
        ObjectNode kw = objectMapper.createObjectNode();
        kw.put("type", "string");
        kw.put("minLength", 1);
        kw.put("maxLength", 100);
        kw.put("title", "Keyword");
        props.set("keyword", kw);
        ArrayNode required = objectMapper.createArrayNode();
        required.add("keyword");
        return objectSchema(props, required);
    }

    private ObjectNode projectSearchSchema(String title) {
        ObjectNode schema = searchSchema(title);
        ObjectNode props = (ObjectNode) schema.path("properties");
        ObjectNode project = objectMapper.createObjectNode();
        project.put("type", "string"); project.put("minLength", 1);
        props.set("project_id", project);
        ((ArrayNode) schema.path("required")).add("project_id");
        return schema;
    }

    private ObjectNode projectTasksSchema() {
        ObjectNode schema = tasksSchema();
        ObjectNode props = (ObjectNode) schema.path("properties");
        ObjectNode project = objectMapper.createObjectNode();
        project.put("type", "string"); project.put("minLength", 1);
        props.set("project_id", project);
        ((ArrayNode) schema.path("required")).add("project_id");
        return schema;
    }

    private ObjectNode projectOnlySchema() {
        ObjectNode props = objectMapper.createObjectNode();
        ObjectNode project = objectMapper.createObjectNode();
        project.put("type", "string"); project.put("minLength", 1);
        props.set("project_id", project);
        ArrayNode required = objectMapper.createArrayNode();
        required.add("project_id");
        return objectSchema(props, required);
    }

    /**
     * 会议列表工具的参数声明。
     *
     * <p>{@code project_id} 必填(平台按它做成员校验);{@code keyword} 是标题包含匹配,
     * 供"分析《Sprint2 规划会》那一场"这类点名使用;{@code limit} 上限 20,与其余工具一致。
     * <b>刻意不透出 {@code transcript}</b> —— 转写正文只在会议分析链路里用,不该出现在能力清单的
     * 返回里(主管只需要"有哪些会议",不需要原文)。
     */
    private ObjectNode meetingsListSchema() {
        ObjectNode props = objectMapper.createObjectNode();
        ObjectNode project = objectMapper.createObjectNode();
        project.put("type", "string"); project.put("minLength", 1);
        props.set("project_id", project);
        ObjectNode keyword = objectMapper.createObjectNode();
        keyword.put("type", "string"); keyword.put("minLength", 1); keyword.put("maxLength", 100);
        keyword.put("title", "Title keyword");
        props.set("keyword", keyword);
        ObjectNode limit = objectMapper.createObjectNode();
        limit.put("type", "integer"); limit.put("minimum", 1); limit.put("maximum", 20);
        limit.put("title", "Limit");
        props.set("limit", limit);
        ArrayNode required = objectMapper.createArrayNode();
        required.add("project_id");
        return objectSchema(props, required);
    }

    private ObjectNode tasksSchema() {
        ObjectNode props = objectMapper.createObjectNode();
        ObjectNode owner = objectMapper.createObjectNode();
        owner.put("type", "integer");
        owner.put("minimum", 1);
        owner.put("title", "Owner Id");
        props.set("owner_id", owner);
        return objectSchema(props, objectMapper.createArrayNode());
    }

    private ObjectNode emptySchema() {
        return objectSchema(objectMapper.createObjectNode(), objectMapper.createArrayNode());
    }

    // ---------- 参数校验(对齐 pydantic StrictModel:extra=forbid) ----------

    private ObjectNode objectArgs(String name, JsonNode arguments) throws AgentError {
        if (!(arguments instanceof ObjectNode node)) {
            throw new AgentError("invalid_tool_arguments", "模型工具参数无效");
        }
        return node;
    }

    private String keyword(String name, JsonNode arguments) throws AgentError {
        ObjectNode node = objectArgs(name, arguments);
        if (node.size() != 2 || !node.has("keyword") || !node.has("project_id")) {
            throw new AgentError("invalid_tool_arguments", "模型工具参数无效");
        }
        JsonNode kw = node.path("keyword");
        if (!kw.isTextual()) {
            throw new AgentError("invalid_tool_arguments", "模型工具参数无效");
        }
        String text = kw.asText();
        if (text.isBlank() || text.length() > 100) {
            throw new AgentError("invalid_tool_arguments", "模型工具参数无效");
        }
        return text;
    }

    private String projectId(JsonNode arguments) throws AgentError {
        String id = objectArgs("project", arguments).path("project_id").asText("").trim().toUpperCase();
        if (id.isBlank()) throw new AgentError("invalid_tool_arguments", "project_id 必填");
        return id;
    }

    private String requireProjectContext(JsonNode arguments, String runProjectId) throws AgentError {
        if (runProjectId == null || runProjectId.isBlank()) {
            throw new AgentError("project_context_missing", "Agent Run 缺少 projectId");
        }
        String requested = projectId(arguments);
        String scoped = ProjectAccessService.normalize(runProjectId);
        if (!scoped.equals(requested)) {
            throw new AgentError("project_context_mismatch", "工具 projectId 与 Agent Run 项目不一致");
        }
        return scoped;
    }

    /** {@code list_meetings} 的合法入参(对齐 pydantic StrictModel:extra=forbid) */
    private record MeetingsQuery(String projectId, String keyword, int limit) {
    }

    private MeetingsQuery listMeetingsArgs(JsonNode arguments, String runProjectId) throws AgentError {
        String scoped = requireProjectContext(arguments, runProjectId);
        ObjectNode node = objectArgs("list_meetings", arguments);
        if (node.size() < 1 || node.size() > 3) {
            throw new AgentError("invalid_tool_arguments", "模型工具参数无效");
        }
        String keyword = null;
        if (node.has("keyword")) {
            JsonNode kw = node.path("keyword");
            if (!kw.isTextual()) throw new AgentError("invalid_tool_arguments", "模型工具参数无效");
            keyword = kw.asText();
            if (keyword.isBlank() || keyword.length() > 100) {
                throw new AgentError("invalid_tool_arguments", "模型工具参数无效");
            }
        }
        int limit = 10;
        if (node.has("limit")) {
            JsonNode raw = node.path("limit");
            if (!raw.isInt() || raw.asInt() < 1 || raw.asInt() > 20) {
                throw new AgentError("invalid_tool_arguments", "模型工具参数无效");
            }
            limit = raw.asInt();
        }
        return new MeetingsQuery(scoped, keyword, limit);
    }

    private JsonNode maybeOwnerId(String name, JsonNode arguments) throws AgentError {
        ObjectNode node = objectArgs(name, arguments);
        if (node.size() == 1 && node.has("project_id")) return null;
        if (node.size() != 2 || !node.has("owner_id") || !node.has("project_id")) {
            throw new AgentError("invalid_tool_arguments", "模型工具参数无效");
        }
        JsonNode owner = node.path("owner_id");
        if (!owner.isInt() || owner.asInt() < 1) {
            throw new AgentError("invalid_tool_arguments", "模型工具参数无效");
        }
        return owner;
    }

    private void emptyArgs(String name, JsonNode arguments) throws AgentError {
        ObjectNode node = objectArgs(name, arguments);
        if (node.size() != 0) {
            throw new AgentError("invalid_tool_arguments", "模型工具参数无效");
        }
    }

    // ---------- 执行 ----------

    public JsonNode execute(String name, JsonNode arguments, Integer userId, String projectId) throws AgentError {
        User user = userId == null ? null : userMapper.selectById(userId);
        if (user == null || !WRITER_ROLES.contains(user.getRole())) {
            throw new AgentError("permission_changed", "发起人的权限已变化，分析已停止");
        }
        switch (name) {
            case "project_summary" -> {
                return projectSummary(requireProjectContext(arguments, projectId));
            }
            case "list_meetings" -> {
                MeetingsQuery query = listMeetingsArgs(arguments, projectId);
                return listMeetings(query);
            }
            case "list_members" -> {
                emptyArgs(name, arguments);
                return listMembers();
            }
            case "list_tasks" -> {
                String scopedProjectId = requireProjectContext(arguments, projectId);
                JsonNode ownerId = maybeOwnerId(name, arguments);
                return listTasks(scopedProjectId, ownerId);
            }
            case "search_stories" -> {
                String scopedProjectId = requireProjectContext(arguments, projectId);
                String keyword = keyword(name, arguments);
                return searchStories(scopedProjectId, keyword);
            }
            case "search_pool" -> {
                String keyword = keyword(name, arguments);
                return searchPool(keyword);
            }
            default -> throw new AgentError("tool_not_allowed", "模型请求了未授权工具，分析已停止");
        }
    }

    private ObjectNode projectSummary(String projectId) {
        List<Story> stories = storyMapper.selectList(new QueryWrapper<Story>().eq("project_id", projectId));
        long done = stories.stream().filter(s -> s.getStatus() != null && s.getStatus() == 2).count();
        ObjectNode node = objectMapper.createObjectNode();
        node.put("stories", stories.size());
        node.put("done", done);
        node.putNull("current_sprint");
        node.putNull("member_capacity");
        ArrayNode limits = objectMapper.createArrayNode();
        limits.add("未配置当前Sprint日期");
        limits.add("未持久化成员容量");
        limits.add("任务无状态字段");
        limits.add("本轮只能提出新增需求池建议，其他变更请人工处理");
        node.set("limitations", limits);
        return node;
    }

    /**
     * 按项目列已保存会议。
     *
     * <p>只回 {@code id/title/created_at}(+ {@code truncated} 标记),**不回转写正文**:
     * 主管需要的是"哪一场",原文只该在会议分析链路里被读取。排序写死为 {@code created_at DESC},
     * 因为回填的相对指代("最近一场/最新/上一场")依赖这个顺序,换序会让解析静默错位。
     */
    private ObjectNode listMeetings(MeetingsQuery query) {
        QueryWrapper<Meeting> qw = new QueryWrapper<Meeting>().eq("project_id", query.projectId());
        if (query.keyword() != null) qw.like("title", query.keyword());
        qw.orderByDesc("created_at").last("LIMIT " + (query.limit() + 1));
        List<Meeting> rows = meetingMapper.selectList(qw);
        ArrayNode items = objectMapper.createArrayNode();
        for (Meeting m : rows.subList(0, Math.min(query.limit(), rows.size()))) {
            ObjectNode item = objectMapper.createObjectNode();
            item.put("id", m.getId());
            item.put("title", m.getTitle());
            item.put("created_at", m.getCreatedAt() == null ? "" : m.getCreatedAt().toString());
            items.add(item);
        }
        ObjectNode node = objectMapper.createObjectNode();
        node.set("items", items);
        node.put("truncated", rows.size() > query.limit());
        return node;
    }

    private ObjectNode listMembers() {
        List<User> users = userMapper.selectList(new QueryWrapper<User>().orderByAsc("id").last("LIMIT 101"));
        ArrayNode items = objectMapper.createArrayNode();
        for (User u : users.subList(0, Math.min(100, users.size()))) {
            ObjectNode item = objectMapper.createObjectNode();
            item.put("id", u.getId());
            item.put("name", u.getDisplayName());
            items.add(item);
        }
        ObjectNode node = objectMapper.createObjectNode();
        node.set("items", items);
        node.put("truncated", users.size() > 100);
        return node;
    }

    private ObjectNode listTasks(String projectId, JsonNode ownerId) {
        QueryWrapper<Task> qw = new QueryWrapper<Task>().eq("project_id", projectId);
        if (ownerId != null) qw.eq("owner_id", ownerId.asInt());
        qw.orderByAsc("code").last("LIMIT 21");
        List<Task> tasks = taskMapper.selectList(qw);
        ArrayNode items = objectMapper.createArrayNode();
        for (Task t : tasks.subList(0, Math.min(20, tasks.size()))) {
            ObjectNode item = objectMapper.createObjectNode();
            item.put("id", t.getId());
            item.put("name", t.getName());
            if (t.getOwnerId() != null) item.put("owner_id", t.getOwnerId());
            else item.putNull("owner_id");
            item.put("hours", t.getHours() == null ? 0 : t.getHours());
            if (t.getStoryRef() != null) item.put("story_ref", t.getStoryRef());
            else item.putNull("story_ref");
            if (t.getWeekStart() != null) item.put("week_start", t.getWeekStart());
            else item.putNull("week_start");
            if (t.getWeekEnd() != null) item.put("week_end", t.getWeekEnd());
            else item.putNull("week_end");
            items.add(item);
        }
        ObjectNode node = objectMapper.createObjectNode();
        node.set("items", items);
        node.put("truncated", tasks.size() > 20);
        node.put("limitations", "工时是计划值，缺少任务状态和真实容量，不能计算完成度或过载");
        return node;
    }

    private ObjectNode searchStories(String projectId, String keyword) {
        QueryWrapper<Story> qw = new QueryWrapper<Story>().eq("project_id", projectId).like("title", keyword)
                .orderByAsc("code").last("LIMIT 21");
        List<Story> rows = storyMapper.selectList(qw);
        ArrayNode items = objectMapper.createArrayNode();
        for (Story r : rows.subList(0, Math.min(20, rows.size()))) {
            ObjectNode item = objectMapper.createObjectNode();
            item.put("id", r.getId());
            item.put("title", r.getTitle());
            String desc = r.getDescription();
            item.put("description", desc == null ? "" : desc.substring(0, Math.min(1000, desc.length())));
            item.put("priority", r.getPriority());
            item.put("status", r.getStatus() == null ? 0 : r.getStatus());
            if (r.getOwnerId() != null) item.put("owner_id", r.getOwnerId());
            else item.putNull("owner_id");
            if (r.getSprint() != null) item.put("sprint", r.getSprint());
            else item.putNull("sprint");
            items.add(item);
        }
        ObjectNode node = objectMapper.createObjectNode();
        node.set("items", items);
        node.put("truncated", rows.size() > 20);
        return node;
    }

    private ObjectNode searchPool(String keyword) {
        QueryWrapper<PoolItem> qw = new QueryWrapper<PoolItem>().like("title", keyword)
                .orderByAsc("id").last("LIMIT 21");
        List<PoolItem> rows = poolItemMapper.selectList(qw);
        ArrayNode items = objectMapper.createArrayNode();
        for (PoolItem r : rows.subList(0, Math.min(20, rows.size()))) {
            ObjectNode item = objectMapper.createObjectNode();
            item.put("id", r.getId());
            item.put("title", r.getTitle());
            String desc = r.getDescription();
            item.put("description", desc == null ? "" : desc.substring(0, Math.min(1000, desc.length())));
            item.put("priority", r.getPriority());
            items.add(item);
        }
        ObjectNode node = objectMapper.createObjectNode();
        node.set("items", items);
        node.put("truncated", rows.size() > 20);
        return node;
    }
}
