package com.aicap.service;

import com.aicap.profile.GitHubProperties;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * US22 质量分析的真实证据层:把「库内 GitHub 同步活动」「GitHub Actions 运行结论」
 * 「仓库文件树(测试/CI/文档存在性)」汇总成可解释的 evidence,任何一段拿不到都显式
 * 降级(available=false + reason),绝不虚构覆盖率或代码质量结论。
 *
 * 证据语义(对齐 US22 验收条件「结论引用真实证据,不虚构测试」):
 *   - code_activity: 只统计 GitHub 同步来的 commit/pr/review/test 条数,不推断质量;
 *   - ci: 只复述 Actions 的运行结论与通过率,不推断代码好坏;
 *   - repository: 只报告测试文件/CI 配置/文档目录是否存在,不评估其质量。
 */
@Service
public class QualityEvidenceService {
    private final GitHubProperties props;
    private final JdbcTemplate jdbc;
    private final ObjectMapper mapper = new ObjectMapper();

    private final HttpClient http = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(8))
            .followRedirects(HttpClient.Redirect.NORMAL)
            .build();

    private static final long CACHE_TTL_MS = 5 * 60_000L;
    private volatile Map<String, Object> ciCache;
    private volatile long ciCacheAt;
    private volatile Map<String, Object> repoCache;
    private volatile long repoCacheAt;

    public QualityEvidenceService(GitHubProperties props, JdbcTemplate jdbc) {
        this.props = props;
        this.jdbc = jdbc;
    }

    /** 汇总三类证据。code_activity 查库,ci 与 repository 走 GitHub(带 5 分钟缓存)。 */
    public Map<String, Object> collect(String projectId) {
        Map<String, Object> evidence = new LinkedHashMap<>();
        evidence.put("code_activity", codeActivity(projectId));
        evidence.put("ci", ciEvidence());
        evidence.put("repository", repositoryEvidence());
        return evidence;
    }

    // ---------- 1) 库内 GitHub 同步活动(真实落库,不推断质量) ----------

    Map<String, Object> codeActivity(String projectId) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("source", "github_sync");
        Integer total = jdbc.queryForObject("SELECT COUNT(*) FROM activity_records WHERE project_id=? AND source='github'",
                Integer.class, projectId);
        boolean synced = total != null && total > 0;
        m.put("synced", synced);
        m.put("commits_total", countActivity(projectId, "commit", null, null));
        m.put("commits_last_14d", countActivity(projectId, "commit", 14, null));
        m.put("test_commits", countActivity(projectId, "commit", null, "test"));
        m.put("doc_commits", countActivity(projectId, "commit", null, "doc"));
        m.put("prs_total", countActivity(projectId, "pr", null, null));
        m.put("reviews_total", countActivity(projectId, "review", null, null));
        m.put("test_activities", countActivity(projectId, "test", null, null));
        m.put("note", synced ? "条数为 GitHub 同步落库的真实事件计数,不代表测试覆盖率或代码质量。"
                : "尚未同步任何 GitHub 活动,需先配置 GITHUB_TOKEN/GITHUB_REPO 并执行同步。");
        return m;
    }

    private int countActivity(String projectId, String type, Integer days, String topic) {
        StringBuilder sql = new StringBuilder("SELECT COUNT(*) FROM activity_records WHERE project_id=? AND source='github' AND activity_type=?");
        if (days != null) sql.append(" AND happened_at >= ?");
        if (topic != null) sql.append(" AND (LOWER(title) LIKE ? OR LOWER(module) LIKE ?)");
        List<Object> args = new java.util.ArrayList<>(List.of(projectId, type));
        if (days != null) args.add(java.sql.Timestamp.valueOf(LocalDate.now().minusDays(days).atStartOfDay()));
        if (topic != null) { args.add("%" + topic + "%"); args.add("%" + topic + "%"); }
        Integer n = jdbc.queryForObject(sql.toString(), Integer.class, args.toArray());
        return n == null ? 0 : n;
    }

    // ---------- 2) GitHub Actions 运行结论(复述,不推断) ----------

    Map<String, Object> ciEvidence() {
        long now = System.currentTimeMillis();
        Map<String, Object> cached = ciCache;
        if (cached != null && now - ciCacheAt < CACHE_TTL_MS) return cached;
        Map<String, Object> m = buildCiEvidence();
        ciCache = m; ciCacheAt = now;
        return m;
    }

    Map<String, Object> buildCiEvidence() {
        Map<String, Object> m = new LinkedHashMap<>();
        if (!configured()) { m.put("available", false); m.put("reason", "未配置 GITHUB_TOKEN/GITHUB_REPO"); return m; }
        try {
            String body = get(props.getApiBase() + "/repos/" + props.getRepo() + "/actions/runs?per_page=30");
            Map<String, Object> parsed = parseRuns(body);
            parsed.put("available", true);
            return parsed;
        } catch (Exception e) {
            m.put("available", false);
            m.put("reason", "无法读取 CI 运行记录: " + brief(e));
            return m;
        }
    }

    /** 纯解析,便于单测。过滤掉 GitHub Pages 部署,只看项目自身 workflow 的已完成运行。 */
    static Map<String, Object> parseRuns(String body) throws Exception {
        JsonNode root = new ObjectMapper().readTree(body);
        JsonNode runs = root.path("workflow_runs");
        int total = 0, success = 0, failure = 0;
        String lastConclusion = null;
        Map<String, Object> lastFailed = null;
        for (JsonNode run : runs) {
            String name = run.path("name").asText("");
            if (name.toLowerCase().contains("pages") || name.toLowerCase().contains("deployment")) continue;
            if (!"completed".equals(run.path("status").asText())) continue;
            String conclusion = run.path("conclusion").asText("");
            total++;
            if ("success".equals(conclusion)) success++;
            else if ("failure".equals(conclusion)) failure++;
            if (lastConclusion == null) lastConclusion = conclusion;
            if ("failure".equals(conclusion) && lastFailed == null) {
                lastFailed = new LinkedHashMap<>();
                lastFailed.put("run_name", name);
                lastFailed.put("run_number", run.path("run_number").asInt(0));
                lastFailed.put("html_url", run.path("html_url").asText(""));
                lastFailed.put("created_at", run.path("created_at").asText(""));
                lastFailed.put("head_sha", run.path("head_sha").asText("").substring(0, Math.min(7, run.path("head_sha").asText("").length())));
            }
        }
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("runs_total", total);
        m.put("last_conclusion", lastConclusion == null ? "unknown" : lastConclusion);
        m.put("pass_rate", total == 0 ? null : Math.round(success * 1000.0 / total) / 10.0);
        m.put("last_failed", lastFailed);
        m.put("note", "仅复述 GitHub Actions 已完成的运行结论,不据此推断代码质量或测试覆盖率。");
        return m;
    }

    // ---------- 3) 仓库文件树(测试/CI/文档存在性,不评估质量) ----------

    Map<String, Object> repositoryEvidence() {
        long now = System.currentTimeMillis();
        Map<String, Object> cached = repoCache;
        if (cached != null && now - repoCacheAt < CACHE_TTL_MS) return cached;
        Map<String, Object> m = buildRepositoryEvidence();
        repoCache = m; repoCacheAt = now;
        return m;
    }

    Map<String, Object> buildRepositoryEvidence() {
        Map<String, Object> m = new LinkedHashMap<>();
        if (!configured()) { m.put("available", false); m.put("reason", "未配置 GITHUB_TOKEN/GITHUB_REPO"); return m; }
        try {
            String repoBody = get(props.getApiBase() + "/repos/" + props.getRepo());
            String branch = new ObjectMapper().readTree(repoBody).path("default_branch").asText("main");
            String treeBody = get(props.getApiBase() + "/repos/" + props.getRepo() + "/git/trees/" + branch + "?recursive=1");
            Map<String, Object> parsed = scanTree(treeBody);
            parsed.put("available", true);
            parsed.put("repo", props.getRepo());
            parsed.put("default_branch", branch);
            return parsed;
        } catch (Exception e) {
            m.put("available", false);
            m.put("reason", "无法读取仓库文件树: " + brief(e));
            return m;
        }
    }

    /** 纯解析,便于单测。 */
    static Map<String, Object> scanTree(String body) throws Exception {
        JsonNode root = new ObjectMapper().readTree(body);
        JsonNode tree = root.path("tree");
        boolean hasCi = false, hasDocs = false, hasTests = false;
        int testFiles = 0;
        for (JsonNode node : tree) {
            String path = node.path("path").asText("").toLowerCase();
            if (path.matches("\\.github/workflows/.*\\.ya?ml")) hasCi = true;
            if (path.startsWith("docs/") || path.endsWith(".md")) hasDocs = true;
            if (path.contains("src/test/") || path.contains("__tests__/")
                    || path.matches(".*(test|spec|_test|_spec)\\.(java|js|ts|py)$")) { hasTests = true; testFiles++; }
        }
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("truncated", root.path("truncated").asBoolean(false));
        m.put("has_ci_workflow", hasCi);
        m.put("has_tests", hasTests);
        m.put("test_file_count", testFiles);
        m.put("has_docs", hasDocs);
        m.put("note", "仅报告测试文件/CI 配置/文档目录是否存在,不评估其质量。");
        return m;
    }

    // ---------- 工具 ----------

    private boolean configured() {
        return props.isEnabled() && !props.getToken().isBlank() && !props.getRepo().isBlank();
    }

    private String get(String url) throws Exception {
        HttpRequest req = HttpRequest.newBuilder(URI.create(url))
                .header("Authorization", "Bearer " + props.getToken())
                .header("Accept", "application/vnd.github+json")
                .header("User-Agent", "aicap-quality-analysis")
                .timeout(Duration.ofSeconds(12))
                .GET().build();
        HttpResponse<String> resp = http.send(req, HttpResponse.BodyHandlers.ofString());
        if (resp.statusCode() == 401) throw new IllegalStateException("GitHub token 无效(401)");
        if (resp.statusCode() == 403) throw new IllegalStateException("GitHub 限流或权限不足(403)");
        if (resp.statusCode() == 404) throw new IllegalStateException("仓库不存在或无权限(404)");
        if (resp.statusCode() >= 400) throw new IllegalStateException("HTTP " + resp.statusCode());
        return resp.body();
    }

    private static String brief(Exception e) {
        String msg = e.getMessage();
        if (msg == null) return e.getClass().getSimpleName();
        return msg.length() > 80 ? msg.substring(0, 80) : msg;
    }
}
