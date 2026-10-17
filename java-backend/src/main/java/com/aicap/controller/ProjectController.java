package com.aicap.controller;

import com.aicap.security.Roles;
import com.aicap.security.RolePolicy;
import com.aicap.common.ApiException;
import com.aicap.entity.Project;
import com.aicap.entity.ProjectMember;
import com.aicap.mapper.ProjectMapper;
import com.aicap.mapper.ProjectMemberMapper;
import com.aicap.mapper.UserMapper;
import com.aicap.mapper.TaskMapper;
import com.aicap.entity.Task;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.UpdateWrapper;
import com.fasterxml.jackson.databind.JsonNode;
import com.aicap.service.ProjectAccessService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/projects")
@RequiredArgsConstructor
public class ProjectController {
    private final ProjectAccessService projects;
    private final ProjectMapper projectMapper;
    private final ProjectMemberMapper memberMapper;
    private final UserMapper userMapper;
    private final TaskMapper taskMapper;
    private final RolePolicy rolePolicy;

    @GetMapping public List<Map<String,Object>> list(){ Roles.any(); return projects.listAccessible(); }
    @GetMapping("/{projectId}") public Map<String,Object> get(@PathVariable String projectId){ Roles.any(); return projects.detail(projectId); }
    @GetMapping("/{projectId}/members") public List<Map<String,Object>> members(@PathVariable String projectId){ Roles.any(); return projects.projectMembers(projectId); }
    @PostMapping public Map<String,Object> create(@RequestBody Map<String,Object> body){ Roles.writer(); return projects.create(body); }

    @PatchMapping("/{projectId}")
    @Transactional
    public Map<String,Object> update(@PathVariable String projectId, @RequestBody JsonNode body) {
        projects.requireOwner(projectId);
        if (!body.has("name") && !body.has("description"))
            throw ApiException.unprocessable("至少提供项目名称或描述");
        Project project = projectMapper.selectById(ProjectAccessService.normalize(projectId));
        if (body.has("name")) {
            if (!body.get("name").isTextual()) throw ApiException.unprocessable("项目名称必须为字符串");
            project.setName(body.get("name").asText().trim());
        }
        if (body.has("description")) {
            if (!body.get("description").isTextual()) throw ApiException.unprocessable("项目描述必须为字符串");
            String original = project.getDescription() == null ? "" : project.getDescription();
            java.util.regex.Matcher metadata = java.util.regex.Pattern
                    .compile("(\\n?\\[AICAP_ACTIVITIES:[A-Za-z0-9_-]+])$").matcher(original);
            String suffix = metadata.find() ? metadata.group(1) : "";
            project.setDescription(body.get("description").asText().trim() + suffix);
        }
        if (project.getName().isBlank() || project.getName().length() > 200 || project.getDescription() == null || project.getDescription().length() > 1000)
            throw ApiException.unprocessable("项目名称或描述长度不合法");
        project.setUpdatedAt(java.time.LocalDateTime.now());
        projectMapper.updateById(project);
        return projects.detail(projectId);
    }

    @PutMapping("/{projectId}/members/{userId}")
    @Transactional
    public Map<String,Object> putMember(@PathVariable String projectId, @PathVariable Integer userId, @RequestBody JsonNode body) {
        projects.requireOwner(projectId);
        if (userMapper.selectById(userId) == null) throw ApiException.notFound("用户不存在");
        String scoped = ProjectAccessService.normalize(projectId);
        ProjectMember member = memberMapper.selectOne(new QueryWrapper<ProjectMember>()
                .eq("project_id", scoped).eq("user_id", userId).last("LIMIT 1"));
        String role = body.has("member_role") ? body.path("member_role").asText()
                : member == null ? "member" : member.getProjectRole();
        if (!rolePolicy.exists(role)) throw ApiException.unprocessable("角色不存在");
        if (member == null) {
            member = new ProjectMember(); member.setProjectId(scoped); member.setUserId(userId);
            member.setJoinedAt(java.time.LocalDateTime.now()); member.setCapacityHours(60);
            member.setWeeklyCapacityHours(com.aicap.service.ProjectCapacityPolicy.defaultWeeklyCapacity(userId));
        }
        member.setProjectRole(role);
        if (body.has("weekly_capacity_hours")) {
            JsonNode capacity = body.get("weekly_capacity_hours");
            if (!capacity.isNull() && (!capacity.isIntegralNumber() || capacity.asInt() < 0 || capacity.asInt() > 168))
                throw ApiException.unprocessable("weekly_capacity_hours 必须在 0..168 之间或为 null");
            member.setWeeklyCapacityHours(capacity.isNull() ? null : capacity.asInt());
        }
        if (member.getId() == null) memberMapper.insert(member); else memberMapper.updateById(member);
        if (body.has("weekly_capacity_hours") && body.get("weekly_capacity_hours").isNull())
            memberMapper.update(null, new UpdateWrapper<ProjectMember>()
                    .set("weekly_capacity_hours", null).eq("project_id", scoped).eq("user_id", userId));
        return projects.projectMembers(scoped).stream().filter(row -> userId.equals(row.get("user_id")))
                .findFirst().orElseThrow();
    }

    @DeleteMapping("/{projectId}/members/{userId}")
    @Transactional
    public Map<String,Boolean> removeMember(@PathVariable String projectId, @PathVariable Integer userId) {
        projects.requireOwner(projectId);
        if (Roles.any().getId().equals(userId)) throw ApiException.conflict("不能移除当前负责人");
        String scoped = ProjectAccessService.normalize(projectId);
        if (taskMapper.selectCount(new QueryWrapper<Task>().eq("project_id", scoped).eq("owner_id", userId)) > 0)
            throw ApiException.conflict("该成员仍有项目任务");
        int removed = memberMapper.delete(new QueryWrapper<ProjectMember>().eq("project_id", scoped).eq("user_id", userId));
        if (removed == 0) throw ApiException.notFound("项目成员不存在");
        return Map.of("removed", true);
    }
}
