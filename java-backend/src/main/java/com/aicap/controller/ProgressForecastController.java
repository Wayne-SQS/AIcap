package com.aicap.controller;

import com.aicap.security.Roles;
import com.aicap.service.ProjectAccessService;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.sql.Timestamp;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.LinkedHashMap;
import java.util.Map;

/** Read-only, explainable task-throughput forecast for US19. */
@RestController
@RequiredArgsConstructor
@RequestMapping("/api/projects/{projectId}/progress-forecast")
public class ProgressForecastController {
    private final ProjectAccessService projects;
    private final JdbcTemplate jdbc;

    @GetMapping
    public Map<String, Object> forecast(@PathVariable String projectId) {
        Roles.any();
        String scoped = ProjectAccessService.normalize(projectId);
        projects.requireMember(scoped);
        return calculate(scoped, LocalDate.now());
    }

    /** Package-visible to make the date boundary deterministic in contract tests. */
    Map<String, Object> calculate(String projectId, LocalDate today) {
        var counts = jdbc.queryForMap("SELECT COUNT(*) AS total, "
                + "COALESCE(SUM(CASE WHEN status IN (0,1) THEN 1 ELSE 0 END),0) AS open_count, "
                + "COALESCE(SUM(CASE WHEN status=2 THEN 1 ELSE 0 END),0) AS done_count "
                + "FROM tasks WHERE project_id=?", projectId);
        long total = ((Number) counts.get("total")).longValue();
        long open = ((Number) counts.get("open_count")).longValue();
        long done = ((Number) counts.get("done_count")).longValue();
        Timestamp first = jdbc.queryForObject("SELECT MIN(created_at) FROM task_logs WHERE project_id=? "
                + "AND created_at<?", Timestamp.class, projectId, Timestamp.valueOf(today.plusDays(1).atStartOfDay()));
        LocalDate start = first == null ? today : first.toLocalDateTime().toLocalDate();
        if (start.isBefore(today.minusDays(41))) start = today.minusDays(41);
        long observedDays = ChronoUnit.DAYS.between(start, today) + 1;
        Timestamp since = Timestamp.valueOf(start.atStartOfDay());
        Timestamp until = Timestamp.valueOf(today.plusDays(1).atStartOfDay());
        // Only the latest status transition for a currently completed task counts.
        // A task reopened after completion therefore cannot inflate the rate.
        long recentCompletions = jdbc.queryForObject("SELECT COUNT(*) FROM ("
                + "SELECT task_id,MAX(id) AS latest_id FROM task_logs WHERE project_id=? AND created_at<? "
                + "GROUP BY task_id) recent "
                + "JOIN task_logs l ON l.id=recent.latest_id "
                + "JOIN tasks t ON t.project_id=l.project_id AND t.code=l.task_id "
                + "WHERE l.project_id=? AND l.new_status=2 AND t.status=2 AND l.created_at>=?",
                Long.class, projectId, until, projectId, since);

        var result = new LinkedHashMap<String, Object>();
        result.put("project_id", projectId);
        result.put("basis", "task_status_history");
        result.put("history_start", start.toString());
        result.put("history_end", today.toString());
        result.put("observed_days", observedDays);
        result.put("tasks_total", total);
        result.put("tasks_open", open);
        result.put("tasks_done", done);
        result.put("recent_completions", recentCompletions);
        result.put("assumptions", "按最近最多42天任务完成吞吐线性外推；当前未完成任务范围不变，取消任务不计入；未考虑新增需求、估时差异、依赖与阻塞。");
        if (total == 0 || (open == 0 && done == 0)) {
            result.put("status", "empty_scope");
            result.put("completions_per_week", null);
            result.put("estimated_remaining_days", null);
            result.put("estimated_date", null);
        } else if (open == 0) {
            result.put("status", "complete");
            result.put("completions_per_week", null);
            result.put("estimated_remaining_days", 0);
            result.put("estimated_date", today.toString());
        } else if (recentCompletions < 3 || observedDays < 14) {
            result.put("status", "insufficient_data");
            result.put("completions_per_week", null);
            result.put("estimated_remaining_days", null);
            result.put("estimated_date", null);
        } else {
            long days = (long) Math.ceil((double) open * observedDays / recentCompletions);
            result.put("status", "forecast");
            result.put("completions_per_week", Math.round(recentCompletions * 700.0 / observedDays) / 100.0);
            result.put("estimated_remaining_days", days);
            result.put("estimated_date", today.plusDays(days).toString());
        }
        return result;
    }
}
