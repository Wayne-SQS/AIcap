package com.aicap.controller;

import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.Map;

/** 健康检查(对齐 FastAPI main.py /api/health:返回 {status, db}) */
@RestController
@RequiredArgsConstructor
public class HealthController {

    private final JdbcTemplate jdbcTemplate;

    @GetMapping("/api/health")
    public Map<String, Object> health() {
        boolean dbOk = false;
        try {
            jdbcTemplate.queryForObject("SELECT 1", Integer.class);
            dbOk = true;
        } catch (Exception ignored) {
            dbOk = false;
        }
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("status", dbOk ? "ok" : "db_error");
        body.put("db", dbOk);
        return body;
    }
}
