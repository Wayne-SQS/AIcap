package com.aicap.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.aicap.security.RolePolicy;

/** 认证域 DTO(输出字段名对齐 FastAPI schemas:snake_case) */
public final class AuthDtos {

    private AuthDtos() {
    }

    /** POST /api/auth/login 请求体(FastAPI LoginIn 默认忽略未知字段) */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record LoginIn(String username, String password) {
    }

    /** 用户输出(不含 password_hash;对齐 UserOut) */
    public record UserOut(Integer id, String username,
                          @JsonProperty("display_name") String displayName,
                          String role, String color,
                          @JsonProperty("capacity_hours") Integer capacityHours,
                          @JsonProperty("can_write") Boolean canWrite,
                          @JsonProperty("can_manage") Boolean canManage) {
    }

    /** 登录响应(对齐 TokenOut) */
    public record TokenOut(@JsonProperty("access_token") String accessToken,
                           @JsonProperty("token_type") String tokenType,
                           UserOut user) {
    }

    public static UserOut toUserOut(com.aicap.entity.User u) {
        boolean manage = "admin".equals(u.getRole()) || "owner".equals(u.getRole());
        boolean write = manage || "member".equals(u.getRole());
        return toUserOut(u, new RolePolicy.Permissions(write, manage));
    }

    public static UserOut toUserOut(com.aicap.entity.User u, RolePolicy.Permissions permissions) {
        return new UserOut(u.getId(), u.getUsername(), u.getDisplayName(), u.getRole(), u.getColor(),
                u.getCapacityHours(),
                permissions.canWrite(), permissions.canManage());
    }
}
