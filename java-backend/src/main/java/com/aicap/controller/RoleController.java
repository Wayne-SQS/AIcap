package com.aicap.controller;

import com.aicap.common.ApiException;
import com.aicap.security.Roles;
import com.fasterxml.jackson.databind.JsonNode;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

@RestController
@RequestMapping("/api/roles")
@RequiredArgsConstructor
public class RoleController {
    private final JdbcTemplate jdbc;
    private static final Set<String> BUILTIN = Set.of("admin", "owner", "member", "viewer");

    @GetMapping
    public List<Map<String, Object>> list() {
        Roles.any();
        List<Map<String, Object>> out = new ArrayList<>();
        out.add(role("admin", true, true));
        out.add(role("owner", true, true));
        out.add(role("member", true, false));
        out.add(role("viewer", false, false));
        out.addAll(jdbc.query("SELECT name,can_write,can_manage FROM roles ORDER BY name",
                (rs, i) -> role(rs.getString(1), rs.getBoolean(2), rs.getBoolean(3))));
        return out;
    }

    @PostMapping
    public Map<String, Object> create(@RequestBody JsonNode body) {
        Roles.reviewer();
        String name = validateName(body.path("name").asText(""));
        if (BUILTIN.contains(name) || exists(name)) throw ApiException.conflict("角色已存在");
        boolean write = flag(body, "can_write", false);
        boolean manage = flag(body, "can_manage", false);
        if (manage && !write) throw ApiException.unprocessable("管理权限需要写入权限");
        jdbc.update("INSERT INTO roles(name,can_write,can_manage) VALUES (?,?,?)", name, write, manage);
        return role(name, write, manage);
    }

    @PatchMapping("/{name}")
    public Map<String, Object> patch(@PathVariable String name, @RequestBody JsonNode body) {
        Roles.reviewer();
        if (!exists(name)) throw ApiException.notFound("角色不存在或为内置角色");
        Map<String, Object> before = jdbc.queryForMap("SELECT can_write,can_manage FROM roles WHERE name=?", name);
        boolean write = flag(body, "can_write", truthy(before.get("can_write")));
        boolean manage = flag(body, "can_manage", truthy(before.get("can_manage")));
        if (manage && !write) throw ApiException.unprocessable("管理权限需要写入权限");
        jdbc.update("UPDATE roles SET can_write=?,can_manage=? WHERE name=?", write, manage, name);
        return role(name, write, manage);
    }

    @DeleteMapping("/{name}")
    public Map<String, Boolean> delete(@PathVariable String name) {
        Roles.reviewer();
        if (!exists(name)) throw ApiException.notFound("角色不存在或为内置角色");
        if (jdbc.queryForObject("SELECT COUNT(*) FROM users WHERE role=?", Integer.class, name) > 0)
            throw ApiException.conflict("仍有成员使用该角色");
        jdbc.update("DELETE FROM roles WHERE name=?", name);
        return Map.of("ok", true);
    }

    private boolean exists(String name) {
        return jdbc.queryForObject("SELECT COUNT(*) FROM roles WHERE name=?", Integer.class, name) > 0;
    }

    private String validateName(String name) {
        String trimmed = name.trim();
        if (!trimmed.matches("[A-Za-z][A-Za-z0-9_-]{1,19}"))
            throw ApiException.unprocessable("角色名须为 2..20 位英文字母、数字、下划线或连字符");
        return trimmed;
    }

    private boolean flag(JsonNode body, String key, boolean fallback) {
        if (!body.has(key)) return fallback;
        if (!body.get(key).isBoolean()) throw ApiException.unprocessable(key + " 必须为布尔值");
        return body.get(key).asBoolean();
    }

    private boolean truthy(Object value) {
        return Boolean.TRUE.equals(value) || value instanceof Number n && n.intValue() != 0;
    }

    private Map<String, Object> role(String name, boolean write, boolean manage) {
        Map<String, Object> value = new LinkedHashMap<>();
        value.put("name", name);
        value.put("can_write", write);
        value.put("can_manage", manage);
        return value;
    }
}
