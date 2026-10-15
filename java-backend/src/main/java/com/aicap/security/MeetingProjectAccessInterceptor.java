package com.aicap.security;

import com.aicap.common.ApiException;
import com.aicap.service.ProjectAccessService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;
import org.springframework.web.servlet.HandlerMapping;

import java.util.Map;

/** Applies project membership to every endpoint nested under a meeting ID. */
@Component
@RequiredArgsConstructor
public class MeetingProjectAccessInterceptor implements HandlerInterceptor {
    private final JdbcTemplate jdbc;
    private final ProjectAccessService projectAccess;

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
        if ("OPTIONS".equalsIgnoreCase(request.getMethod())) return true;
        Object variables = request.getAttribute(HandlerMapping.URI_TEMPLATE_VARIABLES_ATTRIBUTE);
        if (!(variables instanceof Map<?, ?> pathVariables)) return true;
        Object meetingId = pathVariables.get("meetingId");
        if (!(meetingId instanceof String id)) return true;

        var rows = jdbc.queryForList("SELECT project_id FROM meetings WHERE id=?", id);
        if (rows.isEmpty()) throw ApiException.notFound("会议不存在");
        String projectId = rows.getFirst().get("project_id").toString();
        String requestedProject = request.getParameter("projectId");
        if (requestedProject != null && !projectId.equals(ProjectAccessService.normalize(requestedProject))) {
            throw ApiException.notFound("会议不存在");
        }

        String method = request.getMethod();
        String path = request.getRequestURI();
        if ("DELETE".equalsIgnoreCase(method) ||
                ("POST".equalsIgnoreCase(method) &&
                        (path.contains("/proposal-reviews") || path.contains("/proposal-executions") ||
                                path.matches(".*/assignment-suggestions/[^/]+/(review|execute)$")))) {
            projectAccess.requireOwner(projectId);
        } else if ("GET".equalsIgnoreCase(method) || "HEAD".equalsIgnoreCase(method)) {
            projectAccess.requireMember(projectId);
        } else {
            projectAccess.requireWriter(projectId);
        }
        return true;
    }
}
