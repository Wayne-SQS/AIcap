package com.aicap.service;

import com.aicap.profile.GitHubProperties;
import com.aicap.profile.ProjectRepositoryResolver;
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
import java.util.concurrent.ConcurrentHashMap;

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
    private final ProjectRepositoryResolver resolver;
    private final ObjectMapper mapper = new ObjectMapper();

    private final HttpClient http = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(8))
            .followRedirects(HttpClient.Redirect.NEVER)
            .build();

    private static final long CACHE_TTL_MS = 5 * 60_000L;
    private final ConcurrentHashMap<String, CacheEntry> ciCache = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, CacheEntry> repoCache = new ConcurrentHashMap<>();

    private record CacheEntry(Map<String, Object> value, long at) {}

    public QualityEvidenceService(GitHubProperties props, JdbcTemplate jdbc, ProjectRepositoryResolver resolver) {
        this.props = props;
        this.jdbc = jdbc;
        this.resolver = resolver;
    }

    /** 汇总三类证据。code_activity 查库,ci 与 repository 按项目反查仓库后走 GitHub(带 5 分钟缓存)。 */
    public Map<String, Object> collect(String projectId) {
        Map<String, Object> evidence = new LinkedHashMap<>();
        evidence.put("code_activity", codeActivity(projectId));
        evidence.put("ci", ciEvidence(projectId));
        evidence.put("repository", repositoryEvidence(projectId));
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
        m.put("commits_total", countActivity(projectId, "commit", null, (String[]) null));
        m.put("commits_last_14d", countActivity(projectId, "commit", 14, (String[]) null));
        m.put("test_commits", countActivity(projectId, "commit", null, "test", "测试", "单测", "用例"));
        m.put("doc_commits", countActivity(projectId, "commit", null, "doc", "文档", "readme", "说明"));
        m.put("prs_total", countActivity(projectId, "pr", null, (String[]) null));
        m.put("reviews_total", countActivity(projectId, "review", null, (String[]) null));
        m.put("test_activities", countActivity(projectId, "test", null, (String[]) null));
        m.put("note", synced ? "条数为 GitHub 同步落库的真实事件计数,不代表测试覆盖率或代码质量。"
                : "尚未同步任何 GitHub 活动,需先配置 GITHUB_TOKEN/GITHUB_REPO 并执行同步。");
        return m;
    }

    private int countActivity(String projectId, String type, Integer days, String... keywords) {
        StringBuilder sql = new StringBuilder("SELECT COUNT(*) FROM activity_records WHERE project_id=? AND source='github' AND activity_type=?");
        List<Object> args = new java.util.ArrayList<>(List.of(projectId, type));
        if (days != null) { sql.append(" AND happened_at >= ?"); args.add(java.sql.Timestamp.valueOf(LocalDate.now().minusDays(days).atStartOfDay())); }
        if (keywords != null && keywords.length > 0) {
            sql.append(" AND (");
            for (int i = 0; i < keywords.length; i++) {
                if (i > 0) sql.append(" OR ");
                sql.append("LOWER(title) LIKE ? OR LOWER(module) LIKE ?");
                args.add("%" + keywords[i].toLowerCase() + "%");
                args.add("%" + keywords[i].toLowerCase() + "%");
            }
            sql.append(")");
        }
        Integer n = jdbc.queryForObject(sql.toString(), Integer.class, args.toArray());
        return n == null ? 0 : n;
    }

    // ---------- 2) GitHub Actions 运行结论(复述,不推断) ----------

    Map<String, Object> ciEvidence(String projectId) {
        long now = System.currentTimeMillis();
        CacheEntry cached = ciCache.get(projectId);
        if (cached != null && now - cached.at() < CACHE_TTL_MS) return cached.value();
        Map<String, Object> m = buildCiEvidence(projectId);
        ciCache.put(projectId, new CacheEntry(m, now));
        return m;
    }

    Map<String, Object> buildCiEvidence(String projectId) {
        Map<String, Object> m = new LinkedHashMap<>();
        String repo = resolver.resolveRepo(projectId).orElse(null);
        if (repo == null || repo.isBlank()) { m.put("available", false); m.put("reason", "项目未映射 GitHub 仓库"); return m; }
        if (props.getToken().isBlank()) { m.put("available", false); m.put("reason", "未配置 GITHUB_TOKEN"); return m; }
        try {
            String body = get(props.getApiBase() + "/repos/" + repo + "/actions/runs?per_page=30");
            Map<String, Object> parsed = parseRuns(body);
            parsed.put("available", true);
            parsed.put("repo", repo);
            // 从同一份 runs 里取最近一次成功/失败的 CI run id,分别用于覆盖率与失败测试名。
            JsonNode root = new ObjectMapper().readTree(body);
            long lastSuccessId = -1, lastFailedId = -1;
            for (JsonNode run : root.path("workflow_runs")) {
                String name = run.path("name").asText("");
                if (name.toLowerCase().contains("pages") || name.toLowerCase().contains("deployment")) continue;
                if (!"completed".equals(run.path("status").asText())) continue;
                String c = run.path("conclusion").asText("");
                if (lastSuccessId < 0 && "success".equals(c)) lastSuccessId = run.path("id").asLong();
                if (lastFailedId < 0 && "failure".equals(c)) lastFailedId = run.path("id").asLong();
            }
            Map<String, Object> ft = failedTests(lastFailedId, repo);
            // 附上失败 run 的编号/时间/提交,避免"失败测试 19 项"过期且无来源。
            if (parsed.get("last_failed") instanceof Map<?, ?> lf) {
                ft.put("run_number", lf.get("run_number"));
                ft.put("created_at", lf.get("created_at"));
                ft.put("head_sha", lf.get("head_sha"));
            }
            parsed.put("failed_tests", ft);
            parsed.put("coverage", coverage(lastSuccessId, repo));
            return parsed;
        } catch (Exception e) {
            m.put("available", false);
            m.put("reason", "无法读取 CI 运行记录: " + brief(e));
            return m;
        }
    }

    /** 纯解析,便于单测。过滤掉 GitHub Pages 部署;pass_rate 只算 success/(success+failure),
     *  cancelled/skipped 单独计数,不混入"通过率"分母。 */
    static Map<String, Object> parseRuns(String body) throws Exception {
        JsonNode root = new ObjectMapper().readTree(body);
        JsonNode runs = root.path("workflow_runs");
        int success = 0, failure = 0, cancelled = 0;
        String lastConclusion = null;
        Map<String, Object> lastRun = null;
        Map<String, Object> lastFailed = null;
        for (JsonNode run : runs) {
            String name = run.path("name").asText("");
            if (name.toLowerCase().contains("pages") || name.toLowerCase().contains("deployment")) continue;
            if (!"completed".equals(run.path("status").asText())) continue;
            String conclusion = run.path("conclusion").asText("");
            if (lastRun == null) {
                lastRun = new LinkedHashMap<>();
                lastRun.put("run_number", run.path("run_number").asInt(0));
                lastRun.put("html_url", run.path("html_url").asText(""));
                lastRun.put("created_at", run.path("created_at").asText(""));
                lastRun.put("head_sha", run.path("head_sha").asText(""));
                lastRun.put("conclusion", conclusion);
            }
            if ("success".equals(conclusion)) {
                success++;
                if (lastConclusion == null) lastConclusion = "success";
            } else if ("failure".equals(conclusion)) {
                failure++;
                if (lastConclusion == null) lastConclusion = "failure";
                if (lastFailed == null) {
                    lastFailed = new LinkedHashMap<>();
                    lastFailed.put("run_id", run.path("id").asLong());
                    lastFailed.put("run_name", name);
                    lastFailed.put("run_number", run.path("run_number").asInt(0));
                    lastFailed.put("html_url", run.path("html_url").asText(""));
                    lastFailed.put("created_at", run.path("created_at").asText(""));
                    lastFailed.put("head_sha", run.path("head_sha").asText("").substring(0, Math.min(7, run.path("head_sha").asText("").length())));
                }
            } else {
                cancelled++;  // cancelled / skipped / timed_out 等无定论
            }
        }
        int settled = success + failure;
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("runs_total", settled);
        m.put("runs_cancelled", cancelled);
        m.put("last_conclusion", lastConclusion == null ? "unknown" : lastConclusion);
        m.put("last_run", lastRun);
        m.put("pass_rate", settled == 0 ? null : Math.round(success * 1000.0 / settled) / 10.0);
        m.put("last_failed", lastFailed);
        m.put("note", "仅复述 GitHub Actions 已完成的运行结论,通过率=成功/(成功+失败),取消不计入,不据此推断代码质量或测试覆盖率。");
        return m;
    }

    // ---------- 2b) CI 失败测试名与覆盖率(从 job 日志解析,降级友好) ----------

    private Map<String, Object> failedTests(long runId, String repo) {
        Map<String, Object> m = new LinkedHashMap<>();
        if (runId <= 0) { m.put("available", false); m.put("reason", "近 30 次运行无失败"); return m; }
        try {
            String jobId = backendJobId(runId, repo);
            String log = get(props.getApiBase() + "/repos/" + repo + "/actions/jobs/" + jobId + "/logs");
            m.putAll(parseFailedTests(log));
            m.put("available", true);
            return m;
        } catch (Exception e) {
            m.put("available", false);
            m.put("reason", "无法读取失败测试日志: " + brief(e));
            return m;
        }
    }

    /** 纯解析,便于单测。从 surefire 控制台输出提取失败测试的类.方法名与汇总数字。 */
    static Map<String, Object> parseFailedTests(String log) {
        Map<String, Object> m = new LinkedHashMap<>();
        var tests = new java.util.ArrayList<String>();
        var seen = new java.util.LinkedHashSet<String>();
        java.util.regex.Pattern p = java.util.regex.Pattern.compile(
                "\\[ERROR\\]\\s+([\\p{Alnum}_.$]+)\\s+--\\s+.*<<<\\s+(?:FAILURE|ERROR)!");
        for (String line : log.split("\\R")) {
            var matcher = p.matcher(line);
            if (matcher.find()) { if (seen.add(matcher.group(1))) tests.add(matcher.group(1)); }
        }
        // 取最后一个 [ERROR] Tests run: —— 真实日志里第一个是单类汇总(如 3/0/3),
        // 最终汇总才是整次运行的 842/0/19。循环覆盖到最后一次匹配。
        java.util.regex.Matcher total = java.util.regex.Pattern.compile(
                "\\[ERROR\\] Tests run: (\\d+), Failures: (\\d+), Errors: (\\d+)").matcher(log);
        int run = -1, fails = -1, errs = -1;
        while (total.find()) {
            run = Integer.parseInt(total.group(1));
            fails = Integer.parseInt(total.group(2));
            errs = Integer.parseInt(total.group(3));
        }
        if (run >= 0) {
            m.put("tests_run", run);
            m.put("failures", fails);
            m.put("errors", errs);
        }
        m.put("failed_tests_total", tests.size());
        m.put("failed_tests", tests.size() > 20 ? tests.subList(0, 20) : tests);
        m.put("note", "失败测试名取自 surefire 控制台输出的 <<< FAILURE!/ERROR! 行,去重后最多列 20 条。");
        return m;
    }

    private Map<String, Object> coverage(long runId, String repo) {
        Map<String, Object> m = new LinkedHashMap<>();
        if (runId <= 0) { m.put("available", false); m.put("reason", "近 30 次运行无成功记录"); return m; }
        try {
            String jobId = backendJobId(runId, repo);
            String log = get(props.getApiBase() + "/repos/" + repo + "/actions/jobs/" + jobId + "/logs");
            Map<String, Object> parsed = parseJacoco(log);
            if (parsed == null) { m.put("available", false); m.put("reason", "该运行日志无 JaCoCo 输出(需合并 jacoco 步骤)"); return m; }
            m.putAll(parsed);
            m.put("available", true);
            return m;
        } catch (Exception e) {
            m.put("available", false);
            m.put("reason", "无法读取覆盖率: " + brief(e));
            return m;
        }
    }

    /** 纯解析,便于单测。从日志里 --- JACOCO_CSV --- 到 --- JACOCO_END --- 的 csv 汇总各计数列。
     *  用 lastIndexOf:GitHub Actions 日志会先回显整段脚本(##[group]Run echo "--- JACOCO_CSV ---"...),
     *  第一个标记是命令回显,真正的数据块在最后一次出现标记处。 */
    static Map<String, Object> parseJacoco(String log) {
        int start = log.lastIndexOf("--- JACOCO_CSV ---");
        if (start < 0) return null;
        int end = log.indexOf("--- JACOCO_END ---", start);
        if (end < 0 || end <= start) return null;
        String csv = log.substring(start + "--- JACOCO_CSV ---".length(), end);
        long im = 0, ic = 0, lm = 0, lc = 0;
        boolean any = false;
        for (String line : csv.split("\\R")) {
            line = line.strip();
            if (line.isEmpty() || line.toLowerCase().startsWith("group,package")) continue;
            String[] cols = line.split(",", -1);
            if (cols.length < 9) continue;
            try {
                im += Long.parseLong(cols[3]);
                ic += Long.parseLong(cols[4]);
                lm += Long.parseLong(cols[7]);
                lc += Long.parseLong(cols[8]);
                any = true;
            } catch (NumberFormatException ignored) { /* 跳过非数字行 */ }
        }
        if (!any) return null;
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("instruction_missed", im);
        m.put("instruction_covered", ic);
        m.put("instruction_pct", im + ic == 0 ? null : Math.round(ic * 1000.0 / (im + ic)) / 10.0);
        m.put("line_missed", lm);
        m.put("line_covered", lc);
        m.put("line_pct", lm + lc == 0 ? null : Math.round(lc * 1000.0 / (lm + lc)) / 10.0);
        m.put("note", "覆盖率取自 CI 的 JaCoCo CSV 汇总(指令/行),仅覆盖 Java 后端,不含前端/AI 服务测试,不作质量推断。");
        return m;
    }

    /** 找到该 run 的后端 job(名称含「Java」或结论为 failure 的第一个)。 */
    private String backendJobId(long runId, String repo) throws Exception {
        String body = get(props.getApiBase() + "/repos/" + repo + "/actions/runs/" + runId + "/jobs");
        JsonNode jobs = new ObjectMapper().readTree(body).path("jobs");
        String fallback = null;
        for (JsonNode job : jobs) {
            String name = job.path("name").asText("");
            String conclusion = job.path("conclusion").asText("");
            if (name.contains("Java")) return String.valueOf(job.path("id").asLong());
            if (fallback == null && "failure".equals(conclusion)) fallback = String.valueOf(job.path("id").asLong());
            if (fallback == null) fallback = String.valueOf(job.path("id").asLong());
        }
        if (fallback == null) throw new IllegalStateException("该运行没有 job");
        return fallback;
    }

    // ---------- 3) 仓库文件树(测试/CI/文档存在性,不评估质量) ----------

    Map<String, Object> repositoryEvidence(String projectId) {
        long now = System.currentTimeMillis();
        CacheEntry cached = repoCache.get(projectId);
        if (cached != null && now - cached.at() < CACHE_TTL_MS) return cached.value();
        Map<String, Object> m = buildRepositoryEvidence(projectId);
        repoCache.put(projectId, new CacheEntry(m, now));
        return m;
    }

    Map<String, Object> buildRepositoryEvidence(String projectId) {
        Map<String, Object> m = new LinkedHashMap<>();
        String repo = resolver.resolveRepo(projectId).orElse(null);
        if (repo == null || repo.isBlank()) { m.put("available", false); m.put("reason", "项目未映射 GitHub 仓库"); return m; }
        if (props.getToken().isBlank()) { m.put("available", false); m.put("reason", "未配置 GITHUB_TOKEN"); return m; }
        try {
            String repoBody = get(props.getApiBase() + "/repos/" + repo);
            String branch = new ObjectMapper().readTree(repoBody).path("default_branch").asText("main");
            String treeBody = get(props.getApiBase() + "/repos/" + repo + "/git/trees/" + branch + "?recursive=1");
            Map<String, Object> parsed = scanTree(treeBody);
            parsed.put("available", true);
            parsed.put("repo", repo);
            parsed.put("default_branch", branch);
            return parsed;
        } catch (Exception e) {
            m.put("available", false);
            m.put("reason", "无法读取仓库文件树: " + brief(e));
            return m;
        }
    }

    /** 纯解析,便于单测。测试文件检测用大小写敏感后缀 + snake/kebab 形式,避免 latest.java 被误判为测试。 */
    static Map<String, Object> scanTree(String body) throws Exception {
        JsonNode root = new ObjectMapper().readTree(body);
        JsonNode tree = root.path("tree");
        boolean hasCi = false, hasDocs = false, hasTests = false;
        int testFiles = 0;
        for (JsonNode node : tree) {
            String raw = node.path("path").asText("");
            String path = raw.toLowerCase();
            if (path.matches("\\.github/workflows/.*\\.ya?ml")) hasCi = true;
            if (path.startsWith("docs/") || path.endsWith(".md")) hasDocs = true;
            boolean testFile = raw.matches(".*(Test|Tests|Spec|Specs)\\.(java|js|jsx|ts|tsx|py)$")
                    || path.contains("src/test/") || path.contains("__tests__/")
                    || path.matches(".*[._](test|spec)\\.(java|js|jsx|ts|tsx|py|rb)$")
                    || path.matches("(^|/)(test|spec)_[a-z0-9_.]+\\.(py|js|ts)$")
                    || path.matches("(^|/)test/");
            if (testFile) { hasTests = true; testFiles++; }
        }
        Map<String, Object> m = new LinkedHashMap<>();
        boolean truncated = root.path("truncated").asBoolean(false);
        m.put("truncated", truncated);
        m.put("has_ci_workflow", hasCi ? Boolean.TRUE : truncated ? null : Boolean.FALSE);
        m.put("has_tests", hasTests ? Boolean.TRUE : truncated ? null : Boolean.FALSE);
        m.put("test_file_count", testFiles);
        m.put("has_docs", hasDocs ? Boolean.TRUE : truncated ? null : Boolean.FALSE);
        m.put("note", truncated
                ? "GitHub 文件树已截断：已发现的文件数只是下界；未找到的类型为未知，不代表不存在。"
                : "仅报告测试文件/CI 配置/文档目录是否存在,不评估其质量。");
        return m;
    }

    // ---------- 工具 ----------

    String get(String url) throws Exception {
        URI current = URI.create(url);
        String authOrigin = origin(current);
        for (int hop = 0; hop < 3; hop++) {
            HttpRequest.Builder builder = HttpRequest.newBuilder(current)
                    .header("Accept", "application/vnd.github+json")
                    .header("User-Agent", "aicap-quality-analysis")
                    .timeout(Duration.ofSeconds(12))
                    .GET();
            // Actions 日志会 302 到 Azure Blob(带 SAS)。跨域重定向不能再带我们的 Bearer,
            // 否则 Blob 直接 401 —— 覆盖率与失败测试名会整段变成「token 无效」。
            if (origin(current).equals(authOrigin)) {
                builder.header("Authorization", "Bearer " + props.getToken());
            }
            HttpResponse<String> resp = http.send(builder.build(), HttpResponse.BodyHandlers.ofString());
            int code = resp.statusCode();
            if (code >= 300 && code < 400) {
                String location = resp.headers().firstValue("location")
                        .orElseThrow(() -> new IllegalStateException("重定向缺少 Location"));
                current = current.resolve(location);
                continue;
            }
            if (code == 401) throw new IllegalStateException("GitHub token 无效(401)");
            if (code == 403) throw new IllegalStateException("GitHub 限流或权限不足(403)");
            if (code == 404) throw new IllegalStateException("仓库不存在或无权限(404)");
            if (code >= 400) throw new IllegalStateException("HTTP " + code);
            return resp.body();
        }
        throw new IllegalStateException("GitHub 重定向次数过多");
    }

    private static String origin(URI uri) {
        int port = uri.getPort();
        if (port < 0) port = "https".equalsIgnoreCase(uri.getScheme()) ? 443 : 80;
        return uri.getScheme() + "://" + uri.getHost() + ":" + port;
    }

    private static String brief(Exception e) {
        String msg = e.getMessage();
        if (msg == null) return e.getClass().getSimpleName();
        return msg.length() > 80 ? msg.substring(0, 80) : msg;
    }
}
