package com.aicap.controller;

import com.aicap.common.ApiException;
import com.aicap.dto.AuthDtos;
import com.aicap.entity.User;
import com.aicap.mapper.UserMapper;
import com.aicap.security.JwtUtil;
import com.aicap.security.PasswordUtil;
import com.aicap.security.Roles;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
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
     * 真名 ↔ "成员N" 双向登录别名(对齐 FastAPI auth.py):
     * 新库用户名为真名,存量库沿用 成员1..成员4;两种叫法都能登录到同一账号。
     */
    private static final Map<String, String> LOGIN_ALIASES = Map.of(
            "李锐铭", "成员1", "高思晗", "成员2", "孙秋实", "成员3", "罗子涵", "成员4",
            "成员1", "李锐铭", "成员2", "高思晗", "成员3", "孙秋实", "成员4", "罗子涵");

    private final UserMapper userMapper;
    private final JwtUtil jwtUtil;
    private final PasswordUtil passwordUtil;

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
                AuthDtos.toUserOut(user));
    }

    @GetMapping("/me")
    public AuthDtos.UserOut me() {
        return AuthDtos.toUserOut(Roles.any());
    }

    @GetMapping("/users")
    public List<AuthDtos.UserOut> users() {
        Roles.any();
        return userMapper.selectList(new QueryWrapper<User>().orderByAsc("id"))
                .stream().map(AuthDtos::toUserOut).toList();
    }
}
