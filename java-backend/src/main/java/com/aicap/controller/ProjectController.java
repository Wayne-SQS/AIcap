package com.aicap.controller;

import com.aicap.common.ApiException;
import com.aicap.security.ProjectAccess;
import com.aicap.security.Roles;
import com.fasterxml.jackson.databind.JsonNode;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;

import java.sql.PreparedStatement;
import java.sql.Statement;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/projects")
@RequiredArgsConstructor
public class ProjectController {
    private final JdbcTemplate jdbc;
    private final ProjectAccess access;

    @GetMapping
    public List<Map<String, Object>> list() {
        int userId = Roles.any().getId();
        return jdbc.queryForList("SELECT p.id,p.name,p.description,p.created_at,m.member_role FROM projects p JOIN project_members m ON m.project_id=p.id WHERE m.user_id=? ORDER BY p.id", userId);
    }

    @PostMapping
    @Transactional
    public Map<String, Object> create(@RequestBody JsonNode body) {
        int userId = Roles.any().getId();
        if (!"admin".equals(Roles.any().getRole())) throw ApiException.forbidden("仅管理员可创建项目");
        String name = body.path("name").asText("").trim();
        String description = body.path("description").asText("").trim();
        if (name.isEmpty() || name.length() > 200 || description.length() > 1000)
            throw ApiException.unprocessable("项目名称或描述长度不合法");
        org.springframework.jdbc.support.GeneratedKeyHolder keys = new org.springframework.jdbc.support.GeneratedKeyHolder();
        jdbc.update(connection -> {
            PreparedStatement statement = connection.prepareStatement(
                    "INSERT INTO projects(name,description,created_by,created_at) VALUES (?,?,?,NOW())",
                    Statement.RETURN_GENERATED_KEYS);
            statement.setString(1, name);
            statement.setString(2, description);
            statement.setInt(3, userId);
            return statement;
        }, keys);
        long id = keys.getKey().longValue();
        jdbc.update("INSERT INTO project_members(project_id,user_id,member_role) VALUES (?,?,'owner')", id, userId);
        return get(id);
    }

    @GetMapping("/{id}")
    public Map<String, Object> get(@PathVariable long id) {
        access.requireMember(id, Roles.any().getId());
        return jdbc.queryForMap("SELECT p.id,p.name,p.description,p.created_at,m.member_role FROM projects p JOIN project_members m ON m.project_id=p.id WHERE p.id=? AND m.user_id=?", id, Roles.any().getId());
    }

    @PatchMapping("/{id}")
    @Transactional
    public Map<String, Object> update(@PathVariable long id, @RequestBody JsonNode body) {
        access.requireManager(id, Roles.any().getId());
        if (!body.has("name") && !body.has("description"))
            throw ApiException.unprocessable("至少提供项目名称或描述");
        Map<String, Object> current = get(id);
        String name = (String) current.get("name");
        String description = (String) current.get("description");
        if (body.has("name")) {
            if (!body.get("name").isTextual()) throw ApiException.unprocessable("项目名称必须为字符串");
            name = body.get("name").asText().trim();
        }
        if (body.has("description")) {
            if (!body.get("description").isTextual()) throw ApiException.unprocessable("项目描述必须为字符串");
            description = body.get("description").asText().trim();
        }
        if (name.isEmpty() || name.length() > 200 || description.length() > 1000)
            throw ApiException.unprocessable("项目名称或描述长度不合法");
        jdbc.update("UPDATE projects SET name=?,description=? WHERE id=?", name, description, id);
        return get(id);
    }

    @GetMapping("/{id}/members")
    public List<Map<String, Object>> members(@PathVariable long id) {
        access.requireMember(id, Roles.any().getId());
        return jdbc.queryForList("SELECT u.id,u.username,u.display_name,m.member_role FROM project_members m JOIN users u ON u.id=m.user_id WHERE m.project_id=? ORDER BY u.id", id);
    }

    @PutMapping("/{id}/members/{userId}")
    public Map<String, Object> putMember(@PathVariable long id, @PathVariable int userId, @RequestBody JsonNode body) {
        access.requireManager(id, Roles.any().getId());
        String role = body.path("member_role").asText("member");
        if (!role.equals("member") && !role.equals("owner")) throw ApiException.unprocessable("member_role 必须是 member 或 owner");
        if (jdbc.queryForObject("SELECT COUNT(*) FROM users WHERE id=?", Integer.class, userId) == 0)
            throw ApiException.notFound("用户不存在");
        jdbc.update("INSERT INTO project_members(project_id,user_id,member_role) VALUES (?,?,?) ON DUPLICATE KEY UPDATE member_role=VALUES(member_role)", id, userId, role);
        return jdbc.queryForMap("SELECT u.id,u.username,u.display_name,m.member_role FROM project_members m JOIN users u ON u.id=m.user_id WHERE m.project_id=? AND u.id=?", id, userId);
    }

    @DeleteMapping("/{id}/members/{userId}")
    public Map<String, Object> removeMember(@PathVariable long id, @PathVariable int userId) {
        access.requireManager(id, Roles.any().getId());
        if (Roles.any().getId() == userId) throw ApiException.conflict("不能移除当前负责人");
        int removed = jdbc.update("DELETE FROM project_members WHERE project_id=? AND user_id=?", id, userId);
        if (removed == 0) throw ApiException.notFound("项目成员不存在");
        return Map.of("removed", true);
    }
}
