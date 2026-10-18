package com.aicap.security;

import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Set;

/** Built-in roles keep their existing privileges; custom roles are persisted in roles. */
@Component
@RequiredArgsConstructor
public class RolePolicy {
    private final JdbcTemplate jdbc;

    public record Permissions(boolean canWrite, boolean canManage) { }

    public boolean exists(String role) {
        if (Set.of("admin", "owner", "member", "viewer").contains(role)) return true;
        return jdbc.queryForObject("SELECT COUNT(*) FROM roles WHERE name=?", Integer.class, role) > 0;
    }

    public Permissions forRole(String role) {
        if ("admin".equals(role) || "owner".equals(role)) return new Permissions(true, true);
        if ("member".equals(role)) return new Permissions(true, false);
        if ("viewer".equals(role)) return new Permissions(false, false);
        List<Permissions> rows = jdbc.query("SELECT can_write,can_manage FROM roles WHERE name=?",
                (rs, n) -> new Permissions(rs.getBoolean(1), rs.getBoolean(2)), role);
        return rows.isEmpty() ? new Permissions(false, false) : rows.get(0);
    }
}
