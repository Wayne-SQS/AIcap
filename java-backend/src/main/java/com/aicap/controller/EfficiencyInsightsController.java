package com.aicap.controller;

import com.aicap.common.ApiException;
import com.aicap.dto.TaskDtos;
import com.aicap.security.Roles;
import com.aicap.service.ProjectAccessService;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import java.sql.Timestamp;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Project-scoped, evidence-backed task bottlenecks for US23. */
@RestController
@RequiredArgsConstructor
@RequestMapping("/api/projects/{projectId}/efficiency-insights")
public class EfficiencyInsightsController {
    private final ProjectAccessService projects;
    private final JdbcTemplate jdbc;
    private final TaskPoolController tasks;

    public record ImprovementRequest(String issue_kind, Integer owner_id, Integer hours,
                                     Integer week_start, Integer week_end) {}

    @GetMapping
    public Map<String, Object> insights(@PathVariable String projectId) {
        Roles.any();
        String scoped = ProjectAccessService.normalize(projectId);
        projects.requireMember(scoped);
        return calculate(scoped, LocalDate.now());
    }

    /** An explicit human confirmation creates one traceable improvement task per source issue. */
    @PostMapping("/{taskCode}/improvements")
    @Transactional
    public Map<String, Object> createImprovement(@PathVariable String projectId,
                                                  @PathVariable String taskCode,
                                                  @RequestBody ImprovementRequest request) {
        var actor = Roles.writer();
        String scoped = ProjectAccessService.normalize(projectId);
        projects.requireWriter(scoped);
        if (request == null || !("blocked".equals(request.issue_kind()) || "stalled".equals(request.issue_kind())))
            throw ApiException.unprocessable("问题类型无效");
        if (request.owner_id() == null || request.hours() == null || request.week_start() == null
                || request.week_end() == null) throw ApiException.unprocessable("负责人、工时与排期必填");
        // Lock the source task so concurrent confirmations cannot create duplicate tasks.
        if (jdbc.queryForList("SELECT code FROM tasks WHERE project_id=? AND code=? FOR UPDATE",
                scoped, taskCode).isEmpty()) throw ApiException.notFound("源任务不存在");
        var existing = jdbc.queryForList("SELECT improvement_task_code FROM efficiency_improvements "
                + "WHERE project_id=? AND source_task_code=? AND issue_kind=?",
                scoped, taskCode, request.issue_kind());
        if (!existing.isEmpty()) return improvement(scoped, taskCode, request.issue_kind());
        @SuppressWarnings("unchecked")
        var issues = (List<Map<String, Object>>) calculate(scoped, LocalDate.now()).get("issues");
        var source = issues.stream().filter(issue -> taskCode.equals(issue.get("task_code"))
                && request.issue_kind().equals(issue.get("kind"))).findFirst()
                .orElseThrow(() -> ApiException.conflict("该瓶颈已不再符合当前任务证据，请刷新分析"));
        String label = "blocked".equals(request.issue_kind()) ? "解除阻塞" : "复核停滞进度";
        String name = "改进：" + label + " · " + source.get("task_name");
        if (name.length() > 200) name = name.substring(0, 200);
        var body = JsonNodeFactory.instance.objectNode();
        body.put("name", name);
        body.put("owner_id", request.owner_id());
        body.put("hours", request.hours());
        body.put("week_start", request.week_start());
        body.put("week_end", request.week_end());
        body.put("task_type", "management");
        TaskDtos.TaskOut created = tasks.createTask(body, scoped);
        jdbc.update("INSERT INTO efficiency_improvements(project_id,source_task_code,issue_kind,"
                + "improvement_task_code,reviewed_by,reviewed_at) VALUES (?,?,?,?,?,CURRENT_TIMESTAMP)",
                scoped, taskCode, request.issue_kind(), created.id(), actor.getId());
        return improvement(scoped, taskCode, request.issue_kind());
    }

    private Map<String, Object> improvement(String projectId, String taskCode, String kind) {
        @SuppressWarnings("unchecked")
        var records = (List<Map<String, Object>>) calculate(projectId, LocalDate.now()).get("improvements");
        return records.stream().filter(row -> taskCode.equals(row.get("source_task_code"))
                && kind.equals(row.get("issue_kind"))).findFirst().orElseThrow();
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
        var activeIssueKeys = new java.util.HashSet<String>();
        var uncertainTaskCodes = new java.util.HashSet<String>();
        int missingHistory = 0;
        for (var row : rows) {
            int status = ((Number) row.get("status")).intValue();
            boolean blocked = ((Number) row.get("blocked")).intValue() != 0;
            Timestamp transition = (Timestamp) row.get("created_at");
            if (transition == null || row.get("new_status") == null
                    || ((Number) row.get("new_status")).intValue() != status) {
                missingHistory++;
                uncertainTaskCodes.add(String.valueOf(row.get("code")));
            }
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
            activeIssueKeys.add(row.get("code") + ":" + kind);
        }
        var result = new LinkedHashMap<String, Object>();
        result.put("project_id", projectId);
        result.put("as_of", today.toString());
        result.put("basis", "task_blocked_and_status_history");
        result.put("open_tasks", rows.size());
        result.put("missing_or_mismatched_history", missingHistory);
        result.put("stalled_threshold_days", 14);
        result.put("issues", issues);
        var improvements = jdbc.queryForList("SELECT i.source_task_code,i.issue_kind,"
                + "i.improvement_task_code,i.reviewed_by,i.reviewed_at,"
                + "t.status AS improvement_status,t.progress AS improvement_progress,"
                + "src.status AS source_status "
                + "FROM efficiency_improvements i LEFT JOIN tasks t ON t.project_id=i.project_id "
                + "AND t.code=i.improvement_task_code LEFT JOIN tasks src ON src.project_id=i.project_id "
                + "AND src.code=i.source_task_code WHERE i.project_id=? "
                + "ORDER BY i.reviewed_at DESC,i.source_task_code", projectId);
        for (var record : improvements) {
            String code = String.valueOf(record.get("source_task_code"));
            String kind = String.valueOf(record.get("issue_kind"));
            Number status = (Number) record.remove("source_status");
            String outcome;
            if (status == null || ("stalled".equals(kind) && status.intValue() == 1
                    && uncertainTaskCodes.contains(code))) outcome = "insufficient_data";
            else if (activeIssueKeys.contains(code + ":" + kind)) outcome = "still_present";
            else outcome = "signal_cleared";
            record.put("source_outcome", outcome);
            record.put("before_issue", 1);
            record.put("after_issue", "insufficient_data".equals(outcome) ? null : "still_present".equals(outcome) ? 1 : 0);
        }
        result.put("improvements", improvements);
        result.put("limitations", "阻塞依据任务标记；停滞仅依据连续14天未变更的进行中状态。对账只衡量源瓶颈信号是否消失，不证明改进任务导致效率提升；源任务缺失或历史无效时不下结论。");
        return result;
    }
}
