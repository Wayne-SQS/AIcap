package com.aicap.controller;

import com.aicap.common.ApiException;
import com.aicap.security.Roles;
import com.aicap.service.ProjectAccessService;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.math.BigDecimal;
import java.sql.Date;
import java.sql.Timestamp;
import java.sql.Types;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Read-only, explainable task-throughput forecast for US19, plus the auditable
 * human-confirmed baseline that lets the team check the forecast after the fact.
 *
 * <p>The forecast itself never writes business data. A baseline is only written
 * when a person explicitly records and then accepts it, and it points back to the
 * exact inputs that produced the date, so "was it right" is answerable later.
 */
@RestController
@RequiredArgsConstructor
@RequestMapping("/api/projects/{projectId}/progress-forecast")
public class ProgressForecastController {
    private final ProjectAccessService projects;
    private final JdbcTemplate jdbc;

    public record DecisionRequest(String decision, String note) {}

    private static final String INSERT_RECORD = "INSERT INTO progress_forecasts(project_id,history_start,"
            + "history_end,observed_days,tasks_total,tasks_open,tasks_done,recent_completions,"
            + "completions_per_week,estimated_remaining_days,estimated_date,forecast_status,assumptions,"
            + "requested_by,requested_at,record_status) "
            + "VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,CURRENT_TIMESTAMP,'proposed')";

    private static final String SELECT_RECORD = "SELECT id,project_id,history_start,history_end,observed_days,"
            + "tasks_total,tasks_open,tasks_done,recent_completions,completions_per_week,"
            + "estimated_remaining_days,estimated_date,forecast_status,assumptions,requested_by,requested_at,"
            + "decided_by,decided_at,decision_note,record_status FROM progress_forecasts ";

    @GetMapping
    public Map<String, Object> forecast(@PathVariable String projectId) {
        Roles.any();
        String scoped = ProjectAccessService.normalize(projectId);
        projects.requireMember(scoped);
        return calculate(scoped, LocalDate.now());
    }

    /** Freeze the current forecast as a proposal; a separate decision is required. */
    @PostMapping("/records")
    @Transactional
    public Map<String, Object> createRecord(@PathVariable String projectId) {
        var actor = Roles.writer();
        String scoped = ProjectAccessService.normalize(projectId);
        projects.requireWriter(scoped);
        return snapshot(scoped, actor.getId(), LocalDate.now());
    }

    @GetMapping("/records")
    public Map<String, Object> records(@PathVariable String projectId) {
        Roles.any();
        String scoped = ProjectAccessService.normalize(projectId);
        projects.requireMember(scoped);
        LocalDate today = LocalDate.now();
        Map<String, Object> current = calculate(scoped, today);
        var list = new ArrayList<Map<String, Object>>();
        for (var row : jdbc.queryForList(SELECT_RECORD + "WHERE project_id=? ORDER BY requested_at DESC,id DESC",
                scoped)) {
            list.add(view(row, current, today));
        }
        var result = new LinkedHashMap<String, Object>();
        result.put("project_id", scoped);
        result.put("as_of", today.toString());
        result.put("records", list);
        result.put("limitations", "对账只比较任务数量与计划日期，不替代重新预测；任务被重开会让 completed_since 变小。");
        return result;
    }

    @PostMapping("/records/{recordId}/decision")
    @Transactional
    public Map<String, Object> decideRecord(@PathVariable String projectId, @PathVariable long recordId,
                                            @RequestBody DecisionRequest request) {
        var actor = Roles.writer();
        String scoped = ProjectAccessService.normalize(projectId);
        projects.requireWriter(scoped);
        if (request == null || request.decision() == null
                || !("accept".equals(request.decision()) || "discard".equals(request.decision())))
            throw ApiException.unprocessable("决策只能是 accept 或 discard");
        if (request.note() != null && request.note().length() > 200)
            throw ApiException.unprocessable("备注不能超过 200 字");
        return decide(scoped, recordId, request.decision(), request.note(), actor.getId(), LocalDate.now());
    }

    /** Package-visible so contract tests can pin the observation date. */
    Map<String, Object> snapshot(String projectId, Integer actorId, LocalDate today) {
        Map<String, Object> current = calculate(projectId, today);
        if (!"forecast".equals(current.get("status")))
            throw ApiException.unprocessable("当前预测未给出交付日期，不能固化为基线");
        var keys = new GeneratedKeyHolder();
        jdbc.update(connection -> {
            var statement = connection.prepareStatement(INSERT_RECORD, new String[] { "id" });
            statement.setString(1, projectId);
            statement.setDate(2, Date.valueOf((String) current.get("history_start")));
            statement.setDate(3, Date.valueOf((String) current.get("history_end")));
            statement.setInt(4, ((Number) current.get("observed_days")).intValue());
            statement.setInt(5, ((Number) current.get("tasks_total")).intValue());
            statement.setInt(6, ((Number) current.get("tasks_open")).intValue());
            statement.setInt(7, ((Number) current.get("tasks_done")).intValue());
            statement.setInt(8, ((Number) current.get("recent_completions")).intValue());
            Object weekly = current.get("completions_per_week");
            if (weekly == null) statement.setNull(9, Types.DECIMAL);
            else statement.setBigDecimal(9, BigDecimal.valueOf(((Number) weekly).doubleValue()));
            statement.setInt(10, ((Number) current.get("estimated_remaining_days")).intValue());
            statement.setDate(11, Date.valueOf((String) current.get("estimated_date")));
            statement.setString(12, (String) current.get("status"));
            statement.setString(13, (String) current.get("assumptions"));
            statement.setInt(14, actorId);
            return statement;
        }, keys);
        return recordView(projectId, generatedId(keys), today);
    }

    /** Package-visible so contract tests can reconcile at a fixed date. */
    Map<String, Object> decide(String projectId, long recordId, String decision, String note,
                               Integer actorId, LocalDate today) {
        var rows = jdbc.queryForList("SELECT record_status FROM progress_forecasts "
                + "WHERE project_id=? AND id=? FOR UPDATE", projectId, recordId);
        if (rows.isEmpty()) throw ApiException.notFound("预测基线不存在");
        String status = (String) rows.get(0).get("record_status");
        String target = "accept".equals(decision) ? "accepted" : "discarded";
        if (!target.equals(status)) {
            if (!"proposed".equals(status)) throw ApiException.conflict("该基线已完成决策");
            jdbc.update("UPDATE progress_forecasts SET record_status=?,decided_by=?,"
                    + "decided_at=CURRENT_TIMESTAMP,decision_note=? "
                    + "WHERE project_id=? AND id=? AND record_status='proposed'",
                    target, actorId, note, projectId, recordId);
        }
        return recordView(projectId, recordId, today);
    }

    /** Package-visible so contract tests can reconcile at a fixed date. */
    Map<String, Object> recordView(String projectId, long recordId, LocalDate today) {
        var row = jdbc.queryForMap(SELECT_RECORD + "WHERE project_id=? AND id=?", projectId, recordId);
        return view(row, calculate(projectId, today), today);
    }

    private Map<String, Object> view(Map<String, Object> row, Map<String, Object> current, LocalDate today) {
        var out = new LinkedHashMap<String, Object>();
        out.put("project_id", row.get("project_id"));
        out.put("id", ((Number) row.get("id")).longValue());
        out.put("record_status", row.get("record_status"));
        out.put("forecast_status", row.get("forecast_status"));
        out.put("history_start", text(row.get("history_start")));
        out.put("history_end", text(row.get("history_end")));
        out.put("observed_days", ((Number) row.get("observed_days")).intValue());
        out.put("tasks_total", ((Number) row.get("tasks_total")).intValue());
        out.put("tasks_open", ((Number) row.get("tasks_open")).intValue());
        out.put("tasks_done", ((Number) row.get("tasks_done")).intValue());
        out.put("recent_completions", ((Number) row.get("recent_completions")).intValue());
        out.put("completions_per_week", row.get("completions_per_week") == null
                ? null : ((Number) row.get("completions_per_week")).doubleValue());
        out.put("estimated_remaining_days", row.get("estimated_remaining_days") == null
                ? null : ((Number) row.get("estimated_remaining_days")).intValue());
        out.put("estimated_date", text(row.get("estimated_date")));
        out.put("assumptions", row.get("assumptions"));
        out.put("requested_by", row.get("requested_by"));
        out.put("requested_at", text(row.get("requested_at")));
        out.put("decided_by", row.get("decided_by"));
        out.put("decided_at", text(row.get("decided_at")));
        out.put("decision_note", row.get("decision_note"));
        out.put("reconciliation", reconcile(row, current, today));
        return out;
    }

    /**
     * A baseline is only "checked" after a person accepted it; until then the numbers
     * are shown but explicitly not treated as a commitment.
     */
    private Map<String, Object> reconcile(Map<String, Object> row, Map<String, Object> current, LocalDate today) {
        long openNow = ((Number) current.get("tasks_open")).longValue();
        long doneNow = ((Number) current.get("tasks_done")).longValue();
        long doneThen = ((Number) row.get("tasks_done")).longValue();
        LocalDate due = date(row.get("estimated_date"));
        Long dueInDays = due == null ? null : ChronoUnit.DAYS.between(today, due);
        String status = (String) row.get("record_status");
        String verdict;
        if (!"accepted".equals(status)) verdict = "not_baseline";
        else if (openNow == 0) verdict = "reached";
        else if (dueInDays == null) verdict = "no_date";
        else if (dueInDays < 0) verdict = "overdue";
        else verdict = "within_plan";
        var reconciliation = new LinkedHashMap<String, Object>();
        reconciliation.put("completed_since", doneNow - doneThen);
        reconciliation.put("open_now", openNow);
        reconciliation.put("due_in_days", dueInDays);
        reconciliation.put("verdict", verdict);
        return reconciliation;
    }

    private static String text(Object value) {
        if (value == null) return null;
        if (value instanceof Timestamp stamp) return stamp.toLocalDateTime().toString();
        if (value instanceof Date day) return day.toLocalDate().toString();
        return String.valueOf(value);
    }

    private static LocalDate date(Object value) {
        if (value == null) return null;
        if (value instanceof LocalDate local) return local;
        if (value instanceof Date day) return day.toLocalDate();
        if (value instanceof Timestamp stamp) return stamp.toLocalDateTime().toLocalDate();
        return LocalDate.parse(String.valueOf(value).substring(0, 10));
    }

    private static long generatedId(GeneratedKeyHolder keys) {
        Map<String, Object> generated = keys.getKeys();
        if (generated == null || generated.isEmpty()) throw new IllegalStateException("无法取得预测基线编号");
        for (var entry : generated.entrySet()) {
            if ("id".equalsIgnoreCase(entry.getKey()) && entry.getValue() instanceof Number number)
                return number.longValue();
        }
        return ((Number) generated.values().iterator().next()).longValue();
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
