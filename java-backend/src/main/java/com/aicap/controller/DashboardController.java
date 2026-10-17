package com.aicap.controller;

import com.aicap.entity.Story;
import com.aicap.mapper.StoryMapper;
import com.aicap.security.Roles;
import com.aicap.security.AuthContext;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import org.springframework.jdbc.core.JdbcTemplate;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.sql.Timestamp;
import java.time.LocalDateTime;

/** 仪表盘统计(对齐 FastAPI routers/dashboard.py) */
@RestController
@RequestMapping("/api/dashboard")
@RequiredArgsConstructor
public class DashboardController {

    private final StoryMapper storyMapper;
    private final JdbcTemplate jdbc;

    @GetMapping("")
    public Map<String, Object> dashboard() {
        Roles.any();
        long total = storyMapper.selectCount(new QueryWrapper<Story>().eq("project_id", AuthContext.projectId()));
        long done = countStatus(2);
        long doing = countStatus(1);
        long todo = countStatus(0);
        int percent = total > 0 ? (int) Math.round(done * 100.0 / total) : 0;

        List<Map<String, Object>> bySprint = new ArrayList<>();
        for (int sp = 1; sp <= 4; sp++) {
            long t = storyMapper.selectCount(new QueryWrapper<Story>().eq("project_id", AuthContext.projectId()).eq("sprint", sp));
            long d = storyMapper.selectCount(new QueryWrapper<Story>().eq("project_id", AuthContext.projectId()).eq("sprint", sp).eq("status", 2));
            bySprint.add(Map.of(
                    "sprint", sp,
                    "total", t,
                    "done", d,
                    "percent", t > 0 ? (int) Math.round(d * 100.0 / t) : 0));
        }

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("total", total);
        body.put("done", done);
        body.put("doing", doing);
        body.put("todo", todo);
        body.put("percent", percent);
        body.put("by_sprint", bySprint);
        Timestamp latest = jdbc.queryForObject("SELECT GREATEST(" +
                "COALESCE((SELECT MAX(created_at) FROM story_logs WHERE project_id=?),'1970-01-01')," +
                "COALESCE((SELECT MAX(created_at) FROM task_logs WHERE project_id=?),'1970-01-01')," +
                "COALESCE((SELECT MAX(created_at) FROM pool_items WHERE project_id=?),'1970-01-01'))",
                Timestamp.class, AuthContext.projectId(), AuthContext.projectId(), AuthContext.projectId());
        body.put("last_updated_at", latest == null || latest.toLocalDateTime().getYear() == 1970
                ? null : latest.toLocalDateTime());
        return body;
    }

    private long countStatus(int status) {
        return storyMapper.selectCount(new QueryWrapper<Story>().eq("project_id", AuthContext.projectId()).eq("status", status));
    }
}
