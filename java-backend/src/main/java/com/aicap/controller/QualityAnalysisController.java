package com.aicap.controller;

import com.aicap.common.ApiException;
import com.aicap.dto.TaskDtos;
import com.aicap.security.Roles;
import com.aicap.service.ProjectAccessService;
import com.aicap.service.QualityEvidenceService;
import com.aicap.service.QualityTestSetService;
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
 * US22 质量分析:项目级只读质量缺口定位 + 人工确认后生成可追踪改进任务。
 *
 * 缺口来自三路输入:
 *   1) 库内故事/任务的结构化字段(验收条件、认领、需求追溯);
 *   2) 人工提交的测试集(测试用例/测试代码/执行报告)的边界覆盖;
 *   3) 真实外部证据(CI 运行结论、覆盖率、失败测试、仓库文件树、同步活动)。
 * 外部证据拿不到时显式降级,不据此推断质量,也不虚构覆盖率。
 */
@RestController
@RequiredArgsConstructor
@RequestMapping("/api/projects/{projectId}/quality-analysis")
public class QualityAnalysisController {
    private final ProjectAccessService projects;
    private final JdbcTemplate jdbc;
    private final TaskPoolController tasks;
    private final QualityEvidenceService evidence;
    private final QualityTestSetService testSets;
    private final RepoQualityFetcher codeQualityFetcher;
    private final SemanticReviewService semanticReviewer;

    private static final String BASIS = "story_task_specification_and_quality_evidence";
    /** 行覆盖率低于该阈值时定位为测试缺口。 */
    private static final double COVERAGE_LINE_THRESHOLD = 60.0;

    private static final Set<String> FINDING_KINDS = Set.of(
            "story_missing_acceptance", "story_missing_owner", "task_without_story",
            QualityTestSetService.FINDING_KIND, "ci_pipeline_failing", "failed_tests_unresolved",
            "coverage_below_threshold", "repository_missing_tests", "repository_missing_docs",
            "repository_missing_ci", "code_missing_reviews");

    public record ImprovementRequest(String finding_kind, Integer owner_id, Integer hours,
                                     Integer week_start, Integer week_end) {}

    @GetMapping
    public Map<String, Object> analyze(@PathVariable String projectId) {
        Roles.any();
        String scoped = ProjectAccessService.normalize(projectId);
        projects.requireMember(scoped);
        Map<String, Object> ev = evidence.collect(scoped);
        Map<String, Object> payload = calculate(scoped, ev).payload();
        payload.put("evidence", ev);
        // 代码质量评估较慢(要拉仓库文件),这里只回已有缓存;没评估过就提示可触发,不拖慢页面加载。
        Map<String, Object> cq = codeQualityFetcher.peek(scoped);
        payload.put("code_quality", cq != null ? cq : Map.of(
                "available", false, "reason", "尚未评估，可调用 /code-quality 触发"));
        // limitations 按证据实际可用性动态生成,不再硬编码「未接入」。
        payload.put("limitations", buildLimitations(ev));
        return payload;
    }

    /** 提交一份测试集(测试用例/测试代码/执行报告),返回本次边界覆盖与遗漏。 */
    @PostMapping("/test-sets")
    public Map<String, Object> createTestSet(@PathVariable String projectId,
                                             @RequestBody QualityTestSetService.CreateRequest request) {
        var actor = Roles.writer();
        String scoped = ProjectAccessService.normalize(projectId);
        projects.requireWriter(scoped);
        return testSets.create(scoped, actor.getId(), request);
    }

    private String buildLimitations(Map<String, Object> ev) {
        boolean ciAvail = available(ev.get("ci"));
        boolean repoAvail = available(ev.get("repository"));
        boolean actSynced = ev.get("code_activity") instanceof Map<?, ?> m && Boolean.TRUE.equals(m.get("synced"));
        StringBuilder sb = new StringBuilder("依据库内故事/任务结构化字段、已提交测试集的边界覆盖与可获取的 CI/仓库证据定位质量缺口；");
        if (ciAvail && repoAvail && actSynced) sb.append("已接入 CI 运行结论、覆盖率、失败测试、仓库文件树与 GitHub 同步活动；");
        else if (!ciAvail && !repoAvail && !actSynced) sb.append("尚未接入 CI/仓库/同步活动证据，相关缺口不作推断；");
        else sb.append("部分外部证据不可用（见 evidence 各段 reason），未获取的项不作推断；");
        sb.append("不把分析当作已运行测试，也不虚构覆盖率结论；建议需人工复核。");
        return sb.toString();
    }

    private static boolean available(Object node) {
        return node instanceof Map<?, ?> m && Boolean.TRUE.equals(m.get("available"));
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
        // 证据型缺口的来源是 CI/REPO/ACTIVITY 这类虚拟标识,存在性由下面的实时缺口匹配判定。
        if (request.finding_kind().startsWith("story_")) {
            if (jdbc.queryForList("SELECT code FROM stories WHERE project_id=? AND code=? FOR UPDATE",
                    scoped, sourceCode).isEmpty()) throw ApiException.notFound("来源不存在");
        } else if ("task_without_story".equals(request.finding_kind())) {
            if (jdbc.queryForList("SELECT code FROM tasks WHERE project_id=? AND code=? FOR UPDATE",
                    scoped, sourceCode).isEmpty()) throw ApiException.notFound("来源不存在");
        } else if (QualityTestSetService.FINDING_KIND.equals(request.finding_kind())) {
            if (!testSets.exists(scoped, sourceCode)) throw ApiException.notFound("来源不存在");
        }
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
        var source = calculate(scoped, evidence.collect(scoped)).findings().stream()
                .filter(finding -> sourceCode.equals(finding.get("source_code"))
                        && request.finding_kind().equals(finding.get("kind"))).findFirst()
                .orElseThrow(() -> ApiException.conflict("该缺口已不再符合当前证据或证据不可用，请刷新分析"));
        String label = switch (request.finding_kind()) {
            case "story_missing_acceptance" -> "补齐验收条件";
            case "story_missing_owner" -> "认领故事";
            case "task_without_story" -> "补关联故事";
            case QualityTestSetService.FINDING_KIND -> "补齐边界用例";
            case "ci_pipeline_failing" -> "修复 CI 流水线";
            case "failed_tests_unresolved" -> "处理失败测试";
            case "coverage_below_threshold" -> "补充测试覆盖";
            case "repository_missing_tests" -> "补齐测试文件";
            case "repository_missing_docs" -> "补齐文档";
            case "repository_missing_ci" -> "接入 CI 流水线";
            case "code_missing_reviews" -> "建立代码评审";
            default -> "补齐质量缺口";
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

    /** 不带外部证据的确定性分析(单测与默认降级用)。 */
    CalculateResult calculate(String projectId) {
        return calculate(projectId, Map.of());
    }

    CalculateResult calculate(String projectId, Map<String, Object> evidenceMap) {
        var findings = new ArrayList<Map<String, Object>>();
        var storyRows = jdbc.queryForList("SELECT code,title,acceptance,owner_id FROM stories "
                + "WHERE project_id=? ORDER BY code", projectId);
        for (var row : storyRows) {
            String code = String.valueOf(row.get("code"));
            String title = String.valueOf(row.get("title"));
            String acceptance = row.get("acceptance") == null ? "" : row.get("acceptance").toString().strip();
            if (acceptance.isEmpty()) findings.add(finding("story_missing_acceptance", "需求规范", code, title,
                    "故事 " + code + " · " + title,
                    "故事未填写验收条件，完成后无法按 DoD 客观验收。", "补齐可验证的验收条件后再进入完成态。"));
            if (row.get("owner_id") == null) findings.add(finding("story_missing_owner", "需求规范", code, title,
                    "故事 " + code + " · " + title,
                    "故事尚未认领负责人。", "指定负责人，避免无人跟进。"));
        }
        int openTasks = 0;
        var taskRows = jdbc.queryForList("SELECT code,name,task_type,story_internal_id FROM tasks "
                + "WHERE project_id=? AND status IN (0,1) ORDER BY code", projectId);
        for (var row : taskRows) {
            openTasks++;
            boolean feature = "feature".equals(row.get("task_type"));
            if (feature && row.get("story_internal_id") == null) findings.add(finding("task_without_story",
                    "任务追溯", String.valueOf(row.get("code")), String.valueOf(row.get("name")),
                    "任务 " + row.get("code") + " · " + row.get("name"),
                    "开发任务未关联任何故事，工作量无法追溯到需求。", "关联到具体故事，或改判为管理任务。"));
        }
        findings.addAll(testSets.findings(projectId));
        addEvidenceFindings(evidenceMap, findings);

        var result = new LinkedHashMap<String, Object>();
        result.put("project_id", projectId);
        result.put("as_of", LocalDate.now().toString());
        result.put("basis", BASIS);
        result.put("stories_total", storyRows.size());
        result.put("open_tasks", openTasks);
        result.put("findings", findings);
        result.put("test_quality", testSets.analyze(projectId));
        result.put("improvements", jdbc.queryForList("SELECT i.source_code,i.finding_kind,"
                + "i.improvement_task_code,i.reviewed_by,i.reviewed_at,"
                + "t.status AS improvement_status,t.progress AS improvement_progress "
                + "FROM quality_improvements i LEFT JOIN tasks t ON t.project_id=i.project_id "
                + "AND t.code=i.improvement_task_code WHERE i.project_id=? "
                + "ORDER BY i.reviewed_at DESC,i.source_code", projectId));
        result.put("limitations", "依据库内故事与任务的结构化字段及已提交测试集的边界覆盖定位质量缺口；"
                + "不把分析当作已运行测试，也不虚构覆盖率结论。建议需人工复核。");
        return new CalculateResult(result, findings);
    }

    /**
     * 外部证据 → 缺口。只有明确取到「否」或达到阈值才出缺口;未知(null/不可用)不推断,
     * 避免把缺数据误报成质量问题。
     */
    private void addEvidenceFindings(Map<String, Object> ev, List<Map<String, Object>> findings) {
        if (ev.get("code_activity") instanceof Map<?, ?> rawActivity) {
            Map<?, ?> act = rawActivity;
            int prs = number(act.get("prs_total"));
            int reviews = number(act.get("reviews_total"));
            if (Boolean.TRUE.equals(act.get("synced")) && prs >= 3 && reviews == 0) {
                findings.add(finding("code_missing_reviews", "代码质量", "ACTIVITY", "GitHub 同步活动",
                        "GitHub 同步活动 · PR " + prs + " 个",
                        "已同步 " + prs + " 个 PR，但没有任何评审记录，代码变更缺少二次检查。",
                        "为 PR 指定评审人并留存评审记录，再合并到主干。"));
            }
        }
        if (ev.get("ci") instanceof Map<?, ?> rawCi && Boolean.TRUE.equals(rawCi.get("available"))) {
            Map<?, ?> ci = rawCi;
            String repo = text(ci.get("repo"));
            if ("failure".equals(text(ci.get("last_conclusion")))) {
                Map<?, ?> lastFailed = ci.get("last_failed") instanceof Map<?, ?> m ? m : Map.of();
                String run = lastFailed.get("run_number") == null ? ""
                        : " #" + lastFailed.get("run_number") + "（" + text(lastFailed.get("head_sha")) + "）";
                findings.add(finding("ci_pipeline_failing", "代码质量", "CI", "CI 流水线",
                        "CI" + (repo.isBlank() ? "" : " · " + repo) + " · 最近运行" + run,
                        "最近一次已完成的 CI 运行结论为 failure" + run + "，主干缺少可用的流水线结果。",
                        "打开失败运行日志定位原因并修复，恢复绿色流水线后再合并。"));
            }
            if (ci.get("failed_tests") instanceof Map<?, ?> ft && Boolean.TRUE.equals(ft.get("available"))
                    && number(ft.get("failed_tests_total")) > 0) {
                String names = ft.get("failed_tests") instanceof List<?> list
                        ? String.join("、", list.stream().limit(5).map(QualityAnalysisController::text).toList())
                        : "";
                findings.add(finding("failed_tests_unresolved", "测试质量", "CI", "CI 失败测试",
                        "CI" + (repo.isBlank() ? "" : " · " + repo) + " · run #" + text(ft.get("run_number")),
                        "历史最近失败运行中有 " + number(ft.get("failed_tests_total")) + " 项测试未通过"
                                + (names.isBlank() ? "" : "：" + names) + "。",
                        "逐项修复或补测失败用例，重跑 CI 确认全部通过。"));
            }
            if (ci.get("coverage") instanceof Map<?, ?> cov
                    && Boolean.TRUE.equals(cov.get("available")) && cov.get("line_pct") instanceof Number line
                    && line.doubleValue() < COVERAGE_LINE_THRESHOLD) {
                findings.add(finding("coverage_below_threshold", "测试质量", "CI", "JaCoCo 覆盖率",
                        "CI" + (repo.isBlank() ? "" : " · " + repo) + " · JaCoCo",
                        "Java 后端行覆盖率仅 " + line + "%（阈值 " + (int) COVERAGE_LINE_THRESHOLD + "%），指令覆盖率 "
                                + text(cov.get("instruction_pct")) + "%，未覆盖分支缺少回归保护。",
                        "为未覆盖的核心分支补单元测试，把行覆盖率提升到阈值以上。"));
            }
        }
        if (ev.get("repository") instanceof Map<?, ?> rawRepo && Boolean.TRUE.equals(rawRepo.get("available"))) {
            Map<?, ?> repo = rawRepo;
            String repoName = text(repo.get("repo"));
            String location = "仓库 " + repoName + "（" + text(repo.get("default_branch")) + "）";
            if (Boolean.FALSE.equals(repo.get("has_tests"))) {
                findings.add(finding("repository_missing_tests", "测试质量", "REPO", "仓库 " + repoName, location,
                        "文件树中未发现任何测试文件，改动没有自动化回归保护。",
                        "为关键模块补充自动化测试并接入 CI。"));
            }
            if (Boolean.FALSE.equals(repo.get("has_docs"))) {
                findings.add(finding("repository_missing_docs", "文档质量", "REPO", "仓库 " + repoName, location,
                        "文件树中未发现文档目录或 Markdown 说明，交付说明缺失。",
                        "补充 README/使用说明与关键设计文档。"));
            }
            if (Boolean.FALSE.equals(repo.get("has_ci_workflow"))) {
                findings.add(finding("repository_missing_ci", "代码质量", "REPO", "仓库 " + repoName, location,
                        "文件树中未发现 CI 工作流配置，提交没有自动校验。",
                        "在 .github/workflows 下接入构建与测试流水线。"));
            }
        }
    }

    private Map<String, Object> finding(String kind, String category, String code, String name,
                                        String location, String detail, String suggestion) {
        var item = new LinkedHashMap<String, Object>();
        item.put("kind", kind);
        item.put("category", category);
        item.put("source_code", code);
        item.put("source_name", name);
        item.put("location", location);
        item.put("detail", detail);
        item.put("suggestion", suggestion);
        return item;
    }

    private static int number(Object value) {
        return value instanceof Number n ? n.intValue() : 0;
    }

    private static String text(Object value) {
        return value == null ? "" : String.valueOf(value);
    }

    /** 供测试与确认接口复用:载荷整体返回,缺口列表单独取用。 */
    record CalculateResult(Map<String, Object> payload, List<Map<String, Object>> findings) {}
}
