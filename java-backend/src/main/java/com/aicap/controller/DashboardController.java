package com.aicap.controller;

import com.aicap.entity.Story;
import com.aicap.mapper.StoryMapper;
import com.aicap.security.Roles;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** 仪表盘统计(对齐 FastAPI routers/dashboard.py) */
@RestController
@RequestMapping("/api/dashboard")
@RequiredArgsConstructor
public class DashboardController {

    private final StoryMapper storyMapper;

    @GetMapping("")
    public Map<String, Object> dashboard() {
        Roles.any();
        long total = storyMapper.selectCount(null);
        long done = countStatus(2);
        long doing = countStatus(1);
        long todo = countStatus(0);
        int percent = total > 0 ? (int) Math.round(done * 100.0 / total) : 0;

        List<Map<String, Object>> bySprint = new ArrayList<>();
        for (int sp = 1; sp <= 3; sp++) {
            long t = storyMapper.selectCount(new QueryWrapper<Story>().eq("sprint", sp));
            long d = storyMapper.selectCount(new QueryWrapper<Story>().eq("sprint", sp).eq("status", 2));
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
        return body;
    }

    private long countStatus(int status) {
        return storyMapper.selectCount(new QueryWrapper<Story>().eq("status", status));
    }
}
