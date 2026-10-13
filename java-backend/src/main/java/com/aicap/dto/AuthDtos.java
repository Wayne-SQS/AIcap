package com.aicap.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

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
                          @JsonProperty("capacity_hours") Integer capacityHours) {
    }

    /** 登录响应(对齐 TokenOut) */
    public record TokenOut(@JsonProperty("access_token") String accessToken,
                           @JsonProperty("token_type") String tokenType,
                           UserOut user) {
    }

    public static UserOut toUserOut(com.aicap.entity.User u) {
        /* 容量三态原样透传:null = 未配置(前端显示「未配置」),0 = 确认为零(任何工时即超载)。
           旧实现 `== null ? 60` 把「没填」改写成 60 —— 这是"未配置与零不可达"在出参层的一层。 */
        return new UserOut(u.getId(), u.getUsername(), u.getDisplayName(), u.getRole(), u.getColor(),
                u.getCapacityHours());
    }
}
