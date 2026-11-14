package com.aicap.controller;

import com.aicap.common.GlobalExceptionHandler;
import com.aicap.mapper.UserMapper;
import com.aicap.security.JwtUtil;
import com.aicap.security.PasswordUtil;
import com.aicap.security.RolePolicy;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import static org.mockito.Mockito.mock;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 登录入参缺失/空白必须是 422(参数校验),不能因为 {@code Map.of(...).get(null)} 抛 NPE 落到 500。
 */
class AuthControllerLoginValidationTest {

    private org.springframework.test.web.servlet.MockMvc mvc() {
        var controller = new AuthController(mock(UserMapper.class), mock(JwtUtil.class),
                mock(PasswordUtil.class), mock(RolePolicy.class), mock(JdbcTemplate.class));
        return MockMvcBuilders.standaloneSetup(controller)
                .setControllerAdvice(new GlobalExceptionHandler()).build();
    }

    @Test void missingUsernameIsRejectedAs422() throws Exception {
        mvc().perform(post("/api/auth/login").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"password\":\"whatever\"}"))
                .andExpect(status().isUnprocessableEntity());
    }

    @Test void blankCredentialsAreRejectedAs422() throws Exception {
        mvc().perform(post("/api/auth/login").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"  \",\"password\":\"  \"}"))
                .andExpect(status().isUnprocessableEntity());
    }
}
