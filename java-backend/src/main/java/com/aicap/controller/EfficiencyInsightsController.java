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
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.Map;

/** Project-scoped, evidence-backed task bottlenecks for US23. */
@RestController
@RequiredArgsConstructor
@RequestMapping("/api/projects/{projectId}/efficiency-insights")
public class EfficiencyInsightsController {
    private final ProjectAccessService projects;
    private final JdbcTemplate jdbc;

    @GetMapping
    public Map<String, Object> insights(@PathVariable String projectId) {
        Roles.any();
        String scoped = ProjectAccessService.normalize(projectId);
        projects.requireMember(scoped);
        return calculate(scoped, LocalDate.now());
    }

    Map<String, Object> calculate(String projectId, LocalDate today) {
        var rows = jdbc.queryForList("SELECT t.code,t.name,t.status,t.blocked,l.new_status,l.created_at "
                + "FROM tasks t LEFT JOIN ("
                + "SELECT project_id,task_id,MAX(id) AS latest_id FROM task_logs "
                + "WHERE project_id=? AND created_at<? GROUP BY project_id,task_id"
                + ") latest ON latest.project_id=t.project_id AND latest.task_id=t.code "
                + "LEFT JOIN task_logs l ON l.id=latest.latest_id "
                + "WHERE t.project_id=? AND t.status IN (0,1) ORDER BY t.code",
                projectId, Timestamp.valueOf(today.plusDays(1).atStartOfDay()), projectId);
        var issues = new ArrayList<Map<String, Object>>();
        int missingHistory = 0;
        for (var row : rows) {
            int status = ((Number) row.get("status")).intValue();
            boolean blocked = ((Number) row.get("blocked")).intValue() != 0;
            Timestamp transition = (Timestamp) row.get("created_at");
            if (transition == null || row.get("new_status") == null
                    || ((Number) row.get("new_status")).intValue() != status) missingHistory++;
            String kind;
            String suggestion;
            if (blocked) {
                kind = "blocked";
                suggestion = "确认阻塞原因、责任人与解除条件，再更新任务状态。";
            } else if (status == 1 && transition != null
                    && ((Number) row.get("new_status")).intValue() == status
                    && ChronoUnit.DAYS.between(transition.toLocalDateTime().toLocalDate(), today) >= 14) {
                kind = "stalled";
                suggestion = "与负责人复核进度，必要时拆分任务或调整排期。";
            } else continue;
            var issue = new LinkedHashMap<String, Object>();
            issue.put("task_code", row.get("code"));
            issue.put("task_name", row.get("name"));
            issue.put("kind", kind);
            issue.put("current_status", status);
            issue.put("blocked", blocked);
            issue.put("last_status_change", transition == null ? null : transition.toLocalDateTime().toString());
            issue.put("days_in_status", transition == null || row.get("new_status") == null
                    || ((Number) row.get("new_status")).intValue() != status
                    ? null : ChronoUnit.DAYS.between(transition.toLocalDateTime().toLocalDate(), today));
            issue.put("suggestion", suggestion);
            issues.add(issue);
        }
        var result = new LinkedHashMap<String, Object>();
        result.put("project_id", projectId);
        result.put("as_of", today.toString());
        result.put("basis", "task_blocked_and_status_history");
        result.put("open_tasks", rows.size());
        result.put("missing_or_mismatched_history", missingHistory);
        result.put("stalled_threshold_days", 14);
        result.put("issues", issues);
        result.put("limitations", "阻塞依据任务标记；停滞仅依据连续14天未变更的进行中状态，不能证明实际工作停滞。建议需人工复核。无有效状态历史时不推断停滞。");
        return result;
    }
}
