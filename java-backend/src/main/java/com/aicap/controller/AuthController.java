package com.aicap.controller;

import com.aicap.common.ApiException;
import com.aicap.dto.AuthDtos;
import com.aicap.entity.User;
import com.aicap.mapper.UserMapper;
import com.aicap.security.AuthContext;
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

/** 认证接口(对齐 FastAPI routers/auth.py:login/me/users) */
@RestController
@RequestMapping("/api/auth")
@RequiredArgsConstructor
public class AuthController {

    private final UserMapper userMapper;
    private final JwtUtil jwtUtil;
    private final PasswordUtil passwordUtil;

    @PostMapping("/login")
    public AuthDtos.TokenOut login(@RequestBody AuthDtos.LoginIn body) {
        User user = userMapper.selectOne(new QueryWrapper<User>().eq("username", body.username()));
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
