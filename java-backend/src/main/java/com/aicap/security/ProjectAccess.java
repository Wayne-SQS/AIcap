package com.aicap.security;

import com.aicap.common.ApiException;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class ProjectAccess {
    private final JdbcTemplate jdbc;

    public boolean isMember(long projectId, int userId) {
        return jdbc.queryForObject("SELECT COUNT(*) FROM project_members WHERE project_id=? AND user_id=?",
                Integer.class, projectId, userId) > 0;
    }

    public void requireMember(long projectId, int userId) {
        if (!isMember(projectId, userId)) throw ApiException.forbidden("非项目成员无权访问");
    }

    public void requireManager(long projectId, int userId) {
        requireMember(projectId, userId);
        String role = jdbc.queryForObject("SELECT member_role FROM project_members WHERE project_id=? AND user_id=?",
                String.class, projectId, userId);
        if (!"owner".equals(role)) throw ApiException.forbidden("仅项目负责人可管理成员");
    }

    public boolean inCurrentProject(Long resourceProjectId) {
        return resourceProjectId != null && resourceProjectId == AuthContext.projectId();
    }

    public void requireAssignee(int userId) {
        if (!isMember(AuthContext.projectId(), userId))
            throw ApiException.badRequest("负责人不是当前项目成员");
    }
}
