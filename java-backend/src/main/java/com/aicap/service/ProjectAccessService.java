package com.aicap.service;

import com.aicap.common.ApiException;
import com.aicap.entity.Project;
import com.aicap.entity.ProjectMember;
import com.aicap.entity.User;
import com.aicap.mapper.ProjectMapper;
import com.aicap.mapper.ProjectMemberMapper;
import com.aicap.mapper.StoryMapper;
import com.aicap.mapper.TaskMapper;
import com.aicap.mapper.UserMapper;
import com.aicap.security.AuthContext;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.Base64;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

@Service
@RequiredArgsConstructor
public class ProjectAccessService {
    private static final Pattern PROJECT_CODE = Pattern.compile("^P(\\d+)$");
    private static final Pattern ACTIVITY_METADATA = Pattern.compile("\\n?\\[AICAP_ACTIVITIES:([A-Za-z0-9_-]+)]$");
    private static final String ACTIVITY_METADATA_PREFIX = "[AICAP_ACTIVITIES:";
    private final ProjectMapper projects;
    private final ProjectMemberMapper members;
    private final StoryMapper stories;
    private final TaskMapper tasks;
    private final UserMapper users;
    private final ObjectMapper mapper;

    public ProjectMember requireMember(String projectId) {
        User user = AuthContext.currentUser();
        if (user == null) throw ApiException.unauthorized("未登录或登录已过期");
        Project project = projects.selectById(normalize(projectId));
        if (project == null || "ARCHIVED".equalsIgnoreCase(project.getStatus())) {
            throw ApiException.notFound("项目不存在或已归档");
        }
        ProjectMember member = members.selectOne(new QueryWrapper<ProjectMember>()
                .eq("project_id", project.getId()).eq("user_id", user.getId()).last("LIMIT 1"));
        if (member == null) throw ApiException.forbidden("无权访问该项目");
        return member;
    }

    public ProjectMember requireWriter(String projectId) {
        ProjectMember member = requireMember(projectId);
        if ("viewer".equalsIgnoreCase(member.getProjectRole())) throw ApiException.forbidden("该项目中仅有查看权限");
        return member;
    }

    public ProjectMember requireOwner(String projectId) {
        ProjectMember member = requireMember(projectId);
        if (!List.of("owner", "admin").contains(member.getProjectRole().toLowerCase())) {
            throw ApiException.forbidden("仅项目负责人可执行此操作");
        }
        return member;
    }

    public void requireProjectMember(String projectId, Integer userId) {
        if (userId == null) return;
        long count = members.selectCount(new QueryWrapper<ProjectMember>()
                .eq("project_id", normalize(projectId)).eq("user_id", userId));
        if (count == 0) throw ApiException.badRequest("负责人不是当前项目成员");
    }

    public List<Map<String, Object>> listAccessible() {
        User user = AuthContext.currentUser();
        if (user == null) throw ApiException.unauthorized("未登录或登录已过期");
        List<ProjectMember> links = members.selectList(new QueryWrapper<ProjectMember>().eq("user_id", user.getId()));
        return links.stream().map(link -> summary(projects.selectById(link.getProjectId()), link)).filter(java.util.Objects::nonNull).toList();
    }

    public Map<String, Object> detail(String projectId) {
        ProjectMember member = requireMember(projectId);
        return summary(projects.selectById(normalize(projectId)), member);
    }

    public List<Map<String, Object>> projectMembers(String projectId) {
        requireMember(projectId);
        Project project = projects.selectById(normalize(projectId));
        int projectWeeks = ProjectCapacityPolicy.projectWeeks(project);
        return members.selectList(new QueryWrapper<ProjectMember>().eq("project_id", normalize(projectId)).orderByAsc("user_id"))
                .stream().map(m -> {
                    Map<String,Object> row = new LinkedHashMap<>();
                    row.put("id", m.getId()); row.put("project_id", m.getProjectId()); row.put("user_id", m.getUserId());
                    double weekly = ProjectCapacityPolicy.weeklyCapacity(m.getWeeklyCapacityHours(), m.getCapacityHours(), m.getUserId());
                    row.put("project_role", m.getProjectRole()); row.put("capacity_hours", m.getCapacityHours());
                    row.put("weekly_capacity_hours", weekly);
                    row.put("configured_weekly_capacity_hours", m.getWeeklyCapacityHours());
                    row.put("project_weeks", projectWeeks);
                    row.put("total_capacity_hours", weekly * projectWeeks); row.put("joined_at", m.getJoinedAt());
                    return row;
                }).toList();
    }

    @Transactional
    public Map<String, Object> updateMemberCapacity(String projectId, Integer userId, Integer weeklyCapacity) {
        requireOwner(projectId);
        if (weeklyCapacity != null && (weeklyCapacity < 0 || weeklyCapacity > 10000)) {
            throw ApiException.badRequest("周容量须为 0 到 10000 人时，或留空使用默认值");
        }
        ProjectMember member = members.selectOne(new QueryWrapper<ProjectMember>()
                .eq("project_id", normalize(projectId)).eq("user_id", userId).last("LIMIT 1"));
        if (member == null) throw ApiException.notFound("项目成员不存在");
        members.update(null, new com.baomidou.mybatisplus.core.conditions.update.UpdateWrapper<ProjectMember>()
                .eq("id", member.getId()).set("weekly_capacity_hours", weeklyCapacity));
        return projectMembers(projectId).stream()
                .filter(row -> userId.equals(row.get("user_id"))).findFirst()
                .orElseThrow(() -> ApiException.notFound("项目成员不存在"));
    }


    @Transactional
    public synchronized Map<String, Object> create(Map<String, Object> body) {
        User user = AuthContext.currentUser();
        if (user == null) throw ApiException.unauthorized("未登录或登录已过期");
        String name = String.valueOf(body.getOrDefault("name", "")).trim();
        if (name.isBlank()) throw ApiException.badRequest("项目名称不能为空");
        String id = nextProjectId();
        LocalDateTime now = LocalDateTime.now();
        Project project = new Project();
        project.setId(id); project.setName(name); project.setDescription(String.valueOf(body.getOrDefault("description", "")));
        project.setStatus("ACTIVE"); project.setTotalSprints(number(body.get("total_sprints"), 0)); project.setCreatedBy(user.getId());
        project.setCreatedAt(now); project.setUpdatedAt(now); projects.insert(project);
        ProjectMember member = new ProjectMember();
        member.setProjectId(id); member.setUserId(user.getId()); member.setProjectRole("owner");
        member.setCapacityHours(60); member.setWeeklyCapacityHours(ProjectCapacityPolicy.defaultWeeklyCapacity(user.getId())); member.setJoinedAt(now); members.insert(member);
        return summary(project, member);
    }

    /** Create a project only after a generator draft has been confirmed. */
    public synchronized String createGeneratedProject(JsonNode draft, User user) {
        if (user == null) throw ApiException.unauthorized("未登录或登录已过期");
        JsonNode source = draft.path("project");
        String name = source.path("name").asText(source.path("title").asText("新建项目")).trim();
        if (name.isBlank()) name = "新建项目";
        String id = nextProjectId();
        LocalDateTime now = LocalDateTime.now();
        Project project = new Project();
        project.setId(id); project.setName(name); project.setDescription(withActivityMetadata(source.path("description").asText(""), draft.path("activities")));
        project.setStatus("ACTIVE"); project.setTotalSprints(number(source.path("total_sprints").isNumber() ? source.path("total_sprints").numberValue() : draft.path("sprints").size(), 3));
        project.setCreatedBy(user.getId()); project.setCreatedAt(now); project.setUpdatedAt(now); projects.insert(project);

        java.util.LinkedHashSet<Integer> memberIds = new java.util.LinkedHashSet<>();
        memberIds.add(user.getId());
        draft.path("stories").forEach(row -> { if (row.path("owner_id").isIntegralNumber()) memberIds.add(row.path("owner_id").asInt()); });
        draft.path("tasks").forEach(row -> { if (row.path("owner_id").isIntegralNumber()) memberIds.add(row.path("owner_id").asInt()); });
        for (Integer userId : memberIds) {
            User linkedUser = users.selectById(userId);
            if (linkedUser == null) continue;
            ProjectMember link = new ProjectMember(); link.setProjectId(id); link.setUserId(userId);
            link.setProjectRole(userId.equals(user.getId()) ? "owner" : "member");
            link.setCapacityHours(linkedUser.getCapacityHours()); link.setWeeklyCapacityHours(ProjectCapacityPolicy.defaultWeeklyCapacity(userId)); link.setJoinedAt(now); members.insert(link);
        }
        return id;
    }

    private Map<String, Object> summary(Project p, ProjectMember member) {
        if (p == null || "ARCHIVED".equalsIgnoreCase(p.getStatus())) return null;
        Map<String,Object> out = new LinkedHashMap<>();
        out.put("id", p.getId()); out.put("name", p.getName()); out.put("description", visibleDescription(p.getDescription())); out.put("status", p.getStatus());
        out.put("activities", activityNames(p.getDescription()));
        out.put("start_date", p.getStartDate()); out.put("end_date", p.getEndDate()); out.put("total_sprints", p.getTotalSprints());
        out.put("project_weeks", ProjectCapacityPolicy.projectWeeks(p));
        out.put("created_by", p.getCreatedBy()); out.put("created_at", p.getCreatedAt()); out.put("updated_at", p.getUpdatedAt());
        out.put("project_role", member.getProjectRole()); out.put("story_count", stories.selectCount(new QueryWrapper<com.aicap.entity.Story>().eq("project_id", p.getId())));
        out.put("task_count", tasks.selectCount(new QueryWrapper<com.aicap.entity.Task>().eq("project_id", p.getId())));
        out.put("member_count", members.selectCount(new QueryWrapper<ProjectMember>().eq("project_id", p.getId())));
        return out;
    }

    private String nextProjectId() {
        int max = 2;
        for (Project project : projects.selectList(null)) {
            Matcher m = PROJECT_CODE.matcher(project.getId());
            if (m.matches()) max = Math.max(max, Integer.parseInt(m.group(1)));
        }
        return String.format("P%03d", max + 1);
    }

    private int number(Object value, int fallback) {
        if (value instanceof Number n) return n.intValue();
        try { return Integer.parseInt(String.valueOf(value)); } catch (Exception ignored) { return fallback; }
    }

    private String withActivityMetadata(String description, JsonNode activities) {
        List<String> names = activityNames(activities);
        if (names.isEmpty()) return description == null ? "" : description;
        try {
            String base = visibleDescription(description);
            String encoded = Base64.getUrlEncoder().withoutPadding().encodeToString(mapper.writeValueAsBytes(names));
            return base + "\n" + ACTIVITY_METADATA_PREFIX + encoded + "]";
        } catch (Exception ignored) { return description == null ? "" : description; }
    }

    private String visibleDescription(String description) {
        return ACTIVITY_METADATA.matcher(description == null ? "" : description).replaceFirst("").trim();
    }

    private List<String> activityNames(String description) {
        Matcher matcher = ACTIVITY_METADATA.matcher(description == null ? "" : description);
        if (!matcher.find()) return List.of();
        try {
            JsonNode rows = mapper.readTree(Base64.getUrlDecoder().decode(matcher.group(1)));
            return activityNames(rows);
        } catch (Exception ignored) { return List.of(); }
    }

    private List<String> activityNames(JsonNode rows) {
        if (rows == null || !rows.isArray()) return List.of();
        java.util.LinkedHashSet<String> names = new java.util.LinkedHashSet<>();
        for (JsonNode row : rows) {
            String name = row.isTextual()
                    ? row.asText("")
                    : row.path("name").asText(row.path("title").asText(""));
            name = name == null ? "" : name.trim();
            if (!name.isBlank()) names.add(name);
        }
        return List.copyOf(names);
    }

    public static String normalize(String projectId) {
        if (projectId == null || projectId.isBlank()) throw ApiException.badRequest("projectId不能为空");
        return projectId.trim().toUpperCase();
    }
}
