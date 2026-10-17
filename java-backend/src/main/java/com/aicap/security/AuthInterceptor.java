package com.aicap.security;

import com.aicap.common.ApiException;
import com.aicap.entity.User;
import com.aicap.mapper.UserMapper;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;

/**
 * 解析 Authorization: Bearer <token> → 填充 AuthContext。
 * 与 FastAPI security.get_current_user 语义对齐:无效/过期 → 401 {"detail":"未登录或登录已过期"}。
 * 白名单路径(login/health/文档)直接放行,由对应控制器自行处理。
 */
@Component
@RequiredArgsConstructor
public class AuthInterceptor implements HandlerInterceptor {

    private final JwtUtil jwtUtil;
    private final UserMapper userMapper;
    private final RolePolicy rolePolicy;
    private final ProjectAccess projectAccess;

    /** 无需登录的路径(前缀匹配) */
    private static final String[] PUBLIC_PREFIXES = {
            "/api/health",
            "/api/auth/login",
            "/swagger-ui",
            "/v3/api-docs",
            "/error",
    };

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
        if ("OPTIONS".equalsIgnoreCase(request.getMethod())) {
            return true; // CORS 预检放行
        }
        String path = request.getRequestURI();
        for (String prefix : PUBLIC_PREFIXES) {
            if (path.startsWith(prefix)) {
                return true;
            }
        }

        String auth = request.getHeader("Authorization");
        String token = null;
        if (auth != null && auth.startsWith("Bearer ")) {
            token = auth.substring(7);
        }
        Integer uid = token == null ? null : jwtUtil.parseUserId(token);
        User user = uid == null ? null : userMapper.selectById(uid);
        if (user == null) {
            throw ApiException.unauthorized("未登录或登录已过期");
        }
        // All legacy workspace tables belong to project 1. Check membership before every
        // workspace route so alternative controllers and AI endpoints cannot bypass it.
        long projectId = 1;
        if (!path.startsWith("/api/projects") && !path.equals("/api/auth/me")) {
            String selected = request.getHeader("X-Project-Id");
            if (selected != null && !selected.isBlank()) {
                try {
                    projectId = Long.parseLong(selected);
                } catch (NumberFormatException e) {
                    throw ApiException.badRequest("X-Project-Id 无效");
                }
            }
            projectAccess.requireMember(projectId, user.getId());
            if (projectId != 1 && !(path.startsWith("/api/stories") || path.startsWith("/api/epics") || path.startsWith("/api/tasks")
                    || path.startsWith("/api/pool") || path.startsWith("/api/milestones")
                    || path.equals("/api/dashboard") || path.equals("/api/auth/users")))
                throw ApiException.notImplemented("此项目的功能尚未启用");
        }
        AuthContext.set(user);
        AuthContext.setPermissions(rolePolicy.forRole(user.getRole()));
        AuthContext.setProjectId(projectId);
        return true;
    }

    @Override
    public void afterCompletion(HttpServletRequest request, HttpServletResponse response,
                                Object handler, Exception ex) {
        AuthContext.clear();
    }
}
