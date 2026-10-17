package com.aicap.security;

import com.aicap.entity.User;

/**
 * 当前登录用户上下文(请求线程内)。由 AuthInterceptor 填充/清理。
 * 控制器通过 AuthContext.currentUser() 取当前用户,配合 requireRoles 做权限判断。
 */
public final class AuthContext {

    private static final ThreadLocal<User> HOLDER = new ThreadLocal<>();
    private static final ThreadLocal<RolePolicy.Permissions> PERMISSIONS = new ThreadLocal<>();
    private static final ThreadLocal<Long> PROJECT = new ThreadLocal<>();

    private AuthContext() {
    }

    public static void set(User user) {
        HOLDER.set(user);
    }

    public static void setPermissions(RolePolicy.Permissions permissions) { PERMISSIONS.set(permissions); }
    public static void setProjectId(long projectId) { PROJECT.set(projectId); }
    public static long projectId() { return PROJECT.get() == null ? 1L : PROJECT.get(); }

    public static boolean canWrite() { return PERMISSIONS.get() != null && PERMISSIONS.get().canWrite(); }

    public static boolean canManage() { return PERMISSIONS.get() != null && PERMISSIONS.get().canManage(); }

    public static boolean hasPermissions() { return PERMISSIONS.get() != null; }

    public static User currentUser() {
        return HOLDER.get();
    }

    public static Integer currentUserId() {
        User u = HOLDER.get();
        return u == null ? null : u.getId();
    }

    public static void clear() {
        HOLDER.remove();
        PERMISSIONS.remove();
        PROJECT.remove();
    }

    /** 是否具备任一角色;viewer 无写权限故不在此列。 */
    public static boolean hasAnyRole(String... roles) {
        User u = HOLDER.get();
        if (u == null) return false;
        for (String r : roles) {
            if (r.equals(u.getRole())) return true;
        }
        return false;
    }
}
