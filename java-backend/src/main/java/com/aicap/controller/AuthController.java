package com.aicap.controller;

import com.aicap.common.ApiException;
import com.aicap.dto.AuthDtos;
import com.aicap.entity.User;
import com.aicap.mapper.UserMapper;
import com.aicap.security.JwtUtil;
import com.aicap.security.PasswordUtil;
import com.aicap.security.Roles;
import com.aicap.security.RolePolicy;
import com.aicap.security.AuthContext;
import com.aicap.security.ProjectAccess;
import com.fasterxml.jackson.databind.JsonNode;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.UpdateWrapper;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

/** 认证接口(对齐 FastAPI routers/auth.py:login/me/me/users) */
@RestController
@RequestMapping("/api/auth")
@RequiredArgsConstructor
public class AuthController {

    /**
     * 真名 ↔ "成员N" 登录别名(对齐 FastAPI auth.py):覆盖全部 5 个种子用户。
     * <p>成员1..成员4 在库中的 username 是<b>真名</b>(李锐铭/高思晗/孙秋实/罗子涵),
     * 因此这 4 组是双向映射,两种叫法都能登录到同一账号。
     * <p>第 5 个用户(只读查看者 viewer)的 username 本身就是 {@code 成员5}、display_name 是
     * {@code 只读查看者}:它直接用 username 就能登录,故只需补一个方向
     * {@code 只读查看者 → 成员5}(FE-D02:此前缺这条别名,真名登录返回 401)。
     * <p>共 9 对键值,未超过 {@link Map#of} 的 10 对上限。
     */
    private static final Map<String, String> LOGIN_ALIASES = Map.of(
            "李锐铭", "成员1", "高思晗", "成员2", "孙秋实", "成员3", "罗子涵", "成员4",
            "成员1", "李锐铭", "成员2", "高思晗", "成员3", "孙秋实", "成员4", "罗子涵",
            "只读查看者", "成员5");

    private final UserMapper userMapper;
    private final JwtUtil jwtUtil;
    private final PasswordUtil passwordUtil;
    private final RolePolicy rolePolicy;
    private final ProjectAccess projectAccess;

    @PostMapping("/login")
    public AuthDtos.TokenOut login(@RequestBody AuthDtos.LoginIn body) {
        User user = userMapper.selectOne(new QueryWrapper<User>().eq("username", body.username()));
        if (user == null) {
            String alias = LOGIN_ALIASES.get(body.username());
            if (alias != null) {
                user = userMapper.selectOne(new QueryWrapper<User>().eq("username", alias));
            }
        }
        if (user == null || !passwordUtil.verify(body.password(), user.getPasswordHash())) {
            throw ApiException.unauthorized("用户名或密码错误");
        }
        return new AuthDtos.TokenOut(jwtUtil.createToken(user.getId()), "bearer",
                AuthDtos.toUserOut(user, rolePolicy.forRole(user.getRole())));
    }

    @GetMapping("/me")
    public AuthDtos.UserOut me() {
        User user = Roles.any();
        return AuthDtos.toUserOut(user, rolePolicy.forRole(user.getRole()));
    }

    @GetMapping("/users")
    public List<AuthDtos.UserOut> users() {
        Roles.any();
        return userMapper.selectList(new QueryWrapper<User>().orderByAsc("id"))
                .stream().filter(u -> projectAccess.isMember(AuthContext.projectId(), u.getId()))
                .map(u -> AuthDtos.toUserOut(u, rolePolicy.forRole(u.getRole()))).toList();
    }

    @PatchMapping("/users/{userId}")
    public AuthDtos.UserOut patchUser(@PathVariable Integer userId, @RequestBody JsonNode body) {
        Roles.reviewer();
        if (AuthContext.projectId() != 1) throw ApiException.notImplemented("项目成员容量配置尚未启用");
        User user = userMapper.selectById(userId);
        if (user == null) throw ApiException.notFound("成员不存在");
        if (!body.has("capacity_hours") && !body.has("role"))
            throw ApiException.unprocessable("capacity_hours 或 role 至少提供一项");
        UpdateWrapper<User> update = new UpdateWrapper<User>().eq("id", userId);
        if (body.has("capacity_hours")) {
            JsonNode value = body.get("capacity_hours");
            if (value.isNull()) update.set("capacity_hours", null);
            else {
                if (!value.isIntegralNumber()) throw ApiException.unprocessable("capacity_hours 必须为整数或 null");
                int capacity = value.asInt();
                if (capacity < 0 || capacity > 10000)
                    throw ApiException.unprocessable("capacity_hours 必须在 0..10000 之间");
                update.set("capacity_hours", capacity);
            }
        }
        if (body.has("role")) {
            if (!body.get("role").isTextual() || !rolePolicy.exists(body.get("role").asText()))
                throw ApiException.unprocessable("角色不存在");
            update.set("role", body.get("role").asText());
        }
        userMapper.update(null, update);
        User updated = userMapper.selectById(userId);
        return AuthDtos.toUserOut(updated, rolePolicy.forRole(updated.getRole()));
    }
}
