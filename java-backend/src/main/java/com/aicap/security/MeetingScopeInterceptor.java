package com.aicap.security;

import com.aicap.service.MeetingAccessGuard;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;
import org.springframework.web.servlet.HandlerMapping;

import java.util.Map;

/** Project membership gate shared by every nested /api/meetings/{meetingId}/... endpoint. */
@Component
@RequiredArgsConstructor
public class MeetingScopeInterceptor implements HandlerInterceptor {
    private final MeetingAccessGuard guard;

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
        if ("OPTIONS".equalsIgnoreCase(request.getMethod())) return true;
        // Spring's /** also matches zero segments. The base meeting controller has
        // its own role checks, which must run before looking up a nonexistent meeting.
        String path = request.getRequestURI();
        int prefix = path.indexOf("/api/meetings/");
        if (prefix >= 0 && !path.substring(prefix + "/api/meetings/".length()).contains("/")) return true;
        Object variables = request.getAttribute(HandlerMapping.URI_TEMPLATE_VARIABLES_ATTRIBUTE);
        if (!(variables instanceof Map<?, ?> values) || !(values.get("meetingId") instanceof String meetingId)) {
            throw new IllegalStateException("会议路由缺少 meetingId");
        }
        if ("GET".equalsIgnoreCase(request.getMethod()) || "HEAD".equalsIgnoreCase(request.getMethod())) {
            guard.requireMember(meetingId);
        } else {
            guard.requireWriter(meetingId);
        }
        return true;
    }
}
