package com.aicap.security;

import com.aicap.common.ApiException;
import com.aicap.entity.User;

/**
 * 角色守卫(替代 FastAPI require_roles):控制器在写操作前调用。
 * 语义对齐:viewer 只读,写操作需 admin/owner/member;敏感操作需 admin/owner。
 */
public final class Roles {

    private Roles() {
    }

    /** 写操作:admin/owner/member(viewer 拒绝 → 403) */
    public static User writer() {
        User u = AuthContext.currentUser();
        if (u == null) throw ApiException.unauthorized("未登录或登录已过期");
        if (AuthContext.hasAnyRole("admin", "owner", "member")) return u;
        throw ApiException.forbidden("无权限执行此操作");
    }

    /** 敏感写:admin/owner */
    public static User reviewer() {
        User u = AuthContext.currentUser();
        if (u == null) throw ApiException.unauthorized("未登录或登录已过期");
        if (AuthContext.hasAnyRole("admin", "owner")) return u;
        throw ApiException.forbidden("无权限执行此操作");
    }

    /** 任何登录用户 */
    public static User any() {
        User u = AuthContext.currentUser();
        if (u == null) throw ApiException.unauthorized("未登录或登录已过期");
        return u;
    }
}
