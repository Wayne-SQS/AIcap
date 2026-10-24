package com.aicap.controller;

import com.aicap.common.ApiException;
import com.aicap.dto.TaskDtos;
import com.aicap.security.Roles;
import com.aicap.service.ProjectAccessService;
import com.aicap.service.QualityEvidenceService;
import com.aicap.service.RepoQualityFetcher;
import com.aicap.service.SemanticReviewService;
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

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * US22 质量分析 MVP:项目级只读规范缺口定位 + 人工确认后生成可追踪改进任务。
 * 规范缺口来自库内故事/任务字段；外部 CI 与仓库文件树作为独立证据展示，不据此推断质量。
 */
@RestController
@RequiredArgsConstructor
@RequestMapping("/api/projects/{projectId}/quality-analysis")
public class QualityAnalysisController {
    private final ProjectAccessService projects;
    private final JdbcTemplate jdbc;
    private final TaskPoolController tasks;
    private final QualityEvidenceService evidence;
    private final RepoQualityFetcher codeQualityFetcher;
    private final SemanticReviewService semanticReviewer;

    private static final Set<String> FINDING_KINDS =
            Set.of("story_missing_acceptance", "story_missing_owner", "task_without_story");

    public record ImprovementRequest(String finding_kind, Integer owner_id, Integer hours,
                                     Integer week_start, Integer week_end) {}

    @GetMapping
    public Map<String, Object> analyze(@PathVariable String projectId) {
        Roles.any();
        String scoped = ProjectAccessService.normalize(projectId);
        projects.requireMember(scoped);
        Map<String, Object> payload = calculate(scoped).payload();
        Map<String, Object> ev = evidence.collect(scoped);
        payload.put("evidence", ev);
        // 代码质量评估较慢(要拉仓库文件),这里只回已有缓存;没评估过就提示可触发,不拖慢页面加载。
        Map<String, Object> cq = codeQualityFetcher.peek(scoped);
        payload.put("code_quality", cq != null ? cq : Map.of(
                "available", false, "reason", "尚未评估，可调用 /code-quality 触发"));
        // limitations 按证据实际可用性动态生成,不再硬编码「未接入」。
        payload.put("limitations", buildLimitations(ev));
        return payload;
    }

    @SuppressWarnings("unchecked")
    private String buildLimitations(Map<String, Object> ev) {
        boolean ciAvail = ev.get("ci") instanceof Map<?, ?> m && Boolean.TRUE.equals(m.get("available"));
        boolean repoAvail = ev.get("repository") instanceof Map<?, ?> m && Boolean.TRUE.equals(m.get("available"));
        boolean actSynced = ev.get("code_activity") instanceof Map<?, ?> m && Boolean.TRUE.equals(m.get("synced"));
        StringBuilder sb = new StringBuilder("仅依据库内故事与任务的结构化字段定位规范缺口；");
        if (ciAvail && repoAvail && actSynced) {
            sb.append("已接入 CI 运行结论、仓库文件树与 GitHub 同步活动证据；");
        } else if (!ciAvail && !repoAvail && !actSynced) {
            sb.append("尚未接入 CI/仓库/同步活动证据；");
        } else {
            // 点名缺哪一段，别让用户对着"部分不可用"猜（缺同步活动就去跑一次同步即可）。
            java.util.List<String> missing = new java.util.ArrayList<>();
            if (!ciAvail) missing.add("CI 运行结论");
            if (!repoAvail) missing.add("仓库文件树");
            if (!actSynced) missing.add("GitHub 同步活动（执行一次同步即可补齐）");
            sb.append("当前不可用的外部证据：").append(String.join("、", missing))
              .append("；其余已在 evidence 中给出。");
        }
        sb.append("不评估代码/文档/测试的真实质量，也不虚构覆盖率结论。建议需人工复核。");
        return sb.toString();
    }

    /** 对项目映射仓库做代码质量评估（抽样静态分析）；首次约数秒，之后 10 分钟内走缓存。 */
    @GetMapping("/code-quality")
    public Map<String, Object> evaluateCodeQuality(@PathVariable String projectId) {
        Roles.any();
        String scoped = ProjectAccessService.normalize(projectId);
        projects.requireMember(scoped);
        return codeQualityFetcher.evaluate(scoped);
    }

    /**
     * 语义评审：判断 README / 测试代码「读者能否照做」，需 LLM。
     * body: {"scope":"readme|tests", "text":"可选，不传则自动从仓库取"}
     * 未配置模型或取不到文本时降级 available=false + reason，不编分数。
     */
    @PostMapping("/semantic-review")
    public Map<String, Object> reviewSemantics(@PathVariable String projectId,
                                              @RequestBody(required = false) Map<String, String> body) {
        Roles.any();
        String scoped = ProjectAccessService.normalize(projectId);
        projects.requireMember(scoped);
        String scope = body == null ? "readme" : body.getOrDefault("scope", "readme");
        if (!"readme".equals(scope) && !"tests".equals(scope))
            throw ApiException.unprocessable("scope 只能是 readme 或 tests");
        String text = body == null ? null : body.get("text");
        String context = body == null ? null : body.get("context");
        if (text == null || text.isBlank()) {
            Map<String, Object> sample = codeQualityFetcher.sampleText(scoped, scope);
            if (!Boolean.TRUE.equals(sample.get("available"))) return sample;
            text = String.valueOf(sample.get("text"));
            context = sample.get("context") == null ? context : String.valueOf(sample.get("context"));
        }
        return semanticReviewer.review(scope, text, context);
    }

    /** 人工确认一条缺口,生成一项管理任务并登记追踪;同一缺口只允许一个未重复的任务。 */
    @PostMapping("/{sourceCode}/improvements")
    @Transactional
    public Map<String, Object> createImprovement(@PathVariable String projectId,
                                                 @PathVariable String sourceCode,
                                                 @RequestBody ImprovementRequest request) {
        var actor = Roles.writer();
        String scoped = ProjectAccessService.normalize(projectId);
        projects.requireWriter(scoped);
        if (request == null || !FINDING_KINDS.contains(request.finding_kind()))
            throw ApiException.unprocessable("缺口类型无效");
        if (request.owner_id() == null || request.hours() == null || request.week_start() == null
                || request.week_end() == null) throw ApiException.unprocessable("负责人、工时与排期必填");
        // 锁定来源行,避免并发确认重复建任务;同时验证来源仍属于本项目。
        boolean storyKind = request.finding_kind().startsWith("story_");
        String table = storyKind ? "stories" : "tasks";
        if (jdbc.queryForList("SELECT code FROM " + table + " WHERE project_id=? AND code=? FOR UPDATE",
                scoped, sourceCode).isEmpty()) throw ApiException.notFound("来源不存在");
        var existing = jdbc.queryForList("SELECT improvement_task_code FROM quality_improvements "
                + "WHERE project_id=? AND source_code=? AND finding_kind=?", scoped, sourceCode, request.finding_kind());
        if (!existing.isEmpty()) {
            String code = String.valueOf(existing.get(0).get("improvement_task_code"));
            Integer stillThere = jdbc.queryForObject("SELECT COUNT(*) FROM tasks WHERE project_id=? AND code=?",
                    Integer.class, scoped, code);
            if (stillThere != null && stillThere > 0) return improvement(scoped, sourceCode, request.finding_kind());
            // 改进任务已被删除:清掉孤儿登记,允许重新确认建新任务(此前会静默指向旧编号)。
            jdbc.update("DELETE FROM quality_improvements WHERE project_id=? AND source_code=? AND finding_kind=?",
                    scoped, sourceCode, request.finding_kind());
        }
        var source = calculate(scoped).findings().stream()
                .filter(finding -> sourceCode.equals(finding.get("source_code"))
                        && request.finding_kind().equals(finding.get("kind"))).findFirst()
                .orElseThrow(() -> ApiException.conflict("该缺口已不再符合当前证据，请刷新分析"));
        String label = switch (request.finding_kind()) {
            case "story_missing_acceptance" -> "补齐验收条件";
            case "story_missing_owner" -> "认领故事";
            default -> "补关联故事";
        };
        String name = "改进：" + label + " · " + source.get("source_code");
        var body = JsonNodeFactory.instance.objectNode();
        body.put("name", name);
        body.put("owner_id", request.owner_id());
        body.put("hours", request.hours());
        body.put("week_start", request.week_start());
        body.put("week_end", request.week_end());
        body.put("task_type", "management");
        TaskDtos.TaskOut created = tasks.createTask(body, scoped);
        jdbc.update("INSERT INTO quality_improvements(project_id,source_code,finding_kind,"
                        + "improvement_task_code,reviewed_by,reviewed_at) VALUES (?,?,?,?,?,CURRENT_TIMESTAMP)",
                scoped, sourceCode, request.finding_kind(), created.id(), actor.getId());
        return improvement(scoped, sourceCode, request.finding_kind());
    }

    private Map<String, Object> improvement(String projectId, String sourceCode, String kind) {
        return jdbc.queryForMap("SELECT i.source_code,i.finding_kind,i.improvement_task_code,"
                + "i.reviewed_by,i.reviewed_at,t.status AS improvement_status,t.progress AS improvement_progress "
                + "FROM quality_improvements i LEFT JOIN tasks t ON t.project_id=i.project_id "
                + "AND t.code=i.improvement_task_code WHERE i.project_id=? AND i.source_code=? "
                + "AND i.finding_kind=?", projectId, sourceCode, kind);
    }

    CalculateResult calculate(String projectId) {
        var findings = new ArrayList<Map<String, Object>>();
        var storyRows = jdbc.queryForList("SELECT code,title,acceptance,owner_id FROM stories "
                + "WHERE project_id=? ORDER BY code", projectId);
        for (var row : storyRows) {
            String code = String.valueOf(row.get("code"));
            String title = String.valueOf(row.get("title"));
            String acceptance = row.get("acceptance") == null ? "" : row.get("acceptance").toString().strip();
            if (acceptance.isEmpty()) findings.add(finding("story_missing_acceptance", code, title,
                    "故事未填写验收条件，完成后无法按 DoD 客观验收。", "补齐可验证的验收条件后再进入完成态。"));
            if (row.get("owner_id") == null) findings.add(finding("story_missing_owner", code, title,
                    "故事尚未认领负责人。", "指定负责人，避免无人跟进。"));
        }
        int openTasks = 0;
        var taskRows = jdbc.queryForList("SELECT code,name,task_type,story_internal_id FROM tasks "
                + "WHERE project_id=? AND status IN (0,1) ORDER BY code", projectId);
        for (var row : taskRows) {
            openTasks++;
            boolean feature = "feature".equals(row.get("task_type"));
            if (feature && row.get("story_internal_id") == null) findings.add(finding("task_without_story",
                    String.valueOf(row.get("code")), String.valueOf(row.get("name")),
                    "开发任务未关联任何故事，工作量无法追溯到需求。", "关联到具体故事，或改判为管理任务。"));
        }
        var result = new LinkedHashMap<String, Object>();
        result.put("project_id", projectId);
        result.put("as_of", LocalDate.now().toString());
        result.put("basis", "story_and_task_specification_fields");
        result.put("stories_total", storyRows.size());
        result.put("open_tasks", openTasks);
        result.put("findings", findings);
        result.put("improvements", jdbc.queryForList("SELECT i.source_code,i.finding_kind,"
                + "i.improvement_task_code,i.reviewed_by,i.reviewed_at,"
                + "t.status AS improvement_status,t.progress AS improvement_progress "
                + "FROM quality_improvements i LEFT JOIN tasks t ON t.project_id=i.project_id "
                + "AND t.code=i.improvement_task_code WHERE i.project_id=? "
                + "ORDER BY i.reviewed_at DESC,i.source_code", projectId));
        result.put("limitations", "仅依据库内故事与任务的结构化字段定位规范缺口；不评估代码/文档/测试的真实质量，也不虚构覆盖率结论。建议需人工复核。");
        return new CalculateResult(result, findings);
    }

    private Map<String, Object> finding(String kind, String code, String name, String detail, String suggestion) {
        var item = new LinkedHashMap<String, Object>();
        item.put("kind", kind);
        item.put("source_code", code);
        item.put("source_name", name);
        item.put("detail", detail);
        item.put("suggestion", suggestion);
        return item;
    }

    /** 供测试与确认接口复用:载荷整体返回,缺口列表单独取用。 */
    record CalculateResult(Map<String, Object> payload, List<Map<String, Object>> findings) {}
}
