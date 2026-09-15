package com.aicap.profile;

import com.aicap.entity.ActivityRecord;
import com.aicap.entity.Task;
import com.aicap.entity.User;
import com.aicap.mapper.ActivityRecordMapper;
import com.aicap.mapper.TaskMapper;
import com.aicap.mapper.UserMapper;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * GitHub 活动同步服务(US34:GitHub 仓库与成员映射)。
 *
 * 拉取仓库的真实活动并写入 activity_records(source=github),供画像智能体分析:
 * - commits   → activity_type=commit   (event_id=commit sha)
 * - PR 合并    → activity_type=pr       (event_id=pr-<number>-merged)
 * - PR Review → activity_type=review    (event_id=review-<id>)
 * - Issue 创建 → activity_type=note     (event_id=issue-<number>)
 *
 * 设计要点(延续画像智能体的"不造假"原则):
 * - 未配置 token/repo 或 enabled=false 时,接口返回未配置状态,不发任何外部请求;
 * - 成员映射:配置映射 → 作者邮箱本地部分 → 提交者名;未匹配到系统成员的活动不写库(记 warnings);
 * - 幂等:github_event_id 唯一索引 + 先查后插,重复同步不产生重复活动;
 * - PR/Issue 的 title/body 中含 "Txx" 时自动关联任务(存在才关联);
 * - 模块:commit 消息按 Angular 风格 "(scope):" 提取;其余事件无模块时留空(如实)。
 */
@Service
@RequiredArgsConstructor
public class GitHubActivitySyncService {

    private static final Logger log = LoggerFactory.getLogger(GitHubActivitySyncService.class);
    private static final DateTimeFormatter TS = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
    private static final Pattern TASK_PATTERN = Pattern.compile("\\bT\\d{2}\\b");
    private static final Pattern SCOPE_PATTERN = Pattern.compile("^\\s*[a-zA-Z]+(?:\\(([^)]+)\\))?:");

    private final GitHubProperties props;
    private final ActivityRecordMapper activityMapper;
    private final UserMapper userMapper;
    private final TaskMapper taskMapper;
    private final ObjectMapper objectMapper;

    private final HttpClient http = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .followRedirects(HttpClient.Redirect.NORMAL)
            .build();

    /** 同步结果(Controller 原样返回给前端) */
    public Map<String, Object> status() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("enabled", props.isEnabled() && !props.getToken().isBlank() && !props.getRepo().isBlank());
        m.put("configured_repo", props.getRepo());
        m.put("auto_sync_hours", props.getAutoSyncHours());
        return m;
    }

    /** 定时自动同步:每小时检查一次;enabled 且 auto_sync_hours>0 时,回溯同步最近 N 小时的活动 */
    @Scheduled(cron = "0 30 * * * *")
    public void autoSync() {
        if (!props.isEnabled() || props.getAutoSyncHours() <= 0) return;
        LocalDateTime now = LocalDateTime.now();
        LocalDate from = now.minusHours(props.getAutoSyncHours()).toLocalDate();
        LocalDate to = now.toLocalDate();
        try {
            Map<String, Object> r = sync(from, to);
            log.info("[github-sync] 定时同步完成 {}~{}: synced={} skipped={}",
                    from, to, r.get("synced"), r.get("skipped"));
        } catch (Exception e) {
            log.warn("[github-sync] 定时同步失败: {}", brief(e));
        }
    }

    /** 执行一次同步;未配置时返回未配置状态(不抛异常、不发请求) */
    public Map<String, Object> sync(LocalDate from, LocalDate to) {
        Map<String, Object> out = new LinkedHashMap<>();
        if (!props.isEnabled() || props.getToken().isBlank() || props.getRepo().isBlank()) {
            out.put("enabled", false);
            out.put("error", "GitHub 同步未配置:需设置 GITHUB_ENABLED=true、GITHUB_TOKEN、GITHUB_REPO(见 application.yml / 说明文档)");
            return out;
        }
        out.put("enabled", true);
        out.put("repo", props.getRepo());
        out.put("from", from.toString());
        out.put("to", to.toString());
        out.put("pulled", new LinkedHashMap<String, Object>());
        out.put("synced", 0);
        out.put("skipped", 0);
        out.put("warnings", new ArrayList<String>());
        out.put("errors", new ArrayList<String>());

        List<String> warnings = castList(out.get("warnings"));
        List<String> errors = castList(out.get("errors"));
        Map<String, Object> pulled = castMap(out.get("pulled"));
        Map<String, Integer> members = loadMembers();   // username/displayName/邮箱本地部分 → id

        // 1) commits
        try {
            List<ActivityRecord> commits = fetchCommits(from, to, warnings, errors);
            pulled.put("commits", commits.size());
            persistAll(commits, out);
        } catch (Exception e) {
            errors.add("commits 拉取失败: " + brief(e));
        }
        // 2) PR(合并事件)
        try {
            List<ActivityRecord> prs = fetchMergedPullRequests(from, to, warnings, errors);
            pulled.put("prs", prs.size());
            persistAll(prs, out);
        } catch (Exception e) {
            errors.add("PR 拉取失败: " + brief(e));
        }
        // 3) PR Reviews
        try {
            List<ActivityRecord> reviews = fetchReviews(from, to, warnings, errors);
            pulled.put("reviews", reviews.size());
            persistAll(reviews, out);
        } catch (Exception e) {
            errors.add("Review 拉取失败: " + brief(e));
        }
        // 4) Issues(创建)
        try {
            List<ActivityRecord> issues = fetchIssues(from, to, warnings, errors);
            pulled.put("issues", issues.size());
            persistAll(issues, out);
        } catch (Exception e) {
            errors.add("Issue 拉取失败: " + brief(e));
        }
        out.put("finished_at", LocalDateTime.now().format(TS));
        return out;
    }

    // ---------- 拉取(每类按页拉取,受 maxPages 限制) ----------

    private List<ActivityRecord> fetchCommits(LocalDate from, LocalDate to,
                                              List<String> warnings, List<String> errors) throws Exception {
        Map<String, Integer> members = loadMembers();
        List<ActivityRecord> out = new ArrayList<>();
        List<JsonNode> rows = getPages("/repos/{repo}/commits",
                Map.of("since", sinceIso(from), "until", untilIso(to)), "sha");
        for (JsonNode c : rows) {
            String sha = c.path("sha").asText("");
            if (sha.isBlank()) continue;
            JsonNode ca = c.path("commit");
            String message = ca.path("message").asText("");
            String title = firstLine(message);
            String login = c.path("author").path("login").asText("");
            String email = ca.path("author").path("email").asText("");
            String name = ca.path("author").path("name").asText("");
            LocalDateTime at = parseTime(ca.path("author").path("date").asText(""));
            // 服务端二次过滤:不信任 API 边界(防御时区/边界误差),只保留本地日期范围内的提交
            if (at == null || at.toLocalDate().isBefore(from) || at.toLocalDate().isAfter(to)) continue;
            ActivityRecord r = baseRecord(login, email, name, at, members);
            r.setActivityType("commit");
            r.setTitle(truncate(title, 200));
            r.setDetail(truncate("GitHub commit " + sha.substring(0, Math.min(10, sha.length())) + " · 作者 "
                    + (name.isBlank() ? login : name) + " · " + firstLine(message), 1000));
            r.setModule(scopeOf(message));
            r.setGithubEventId(sha);
            r.setTaskId(resolveTask(title + " " + message));
            if (r.getUserId() == null) {
                warnings.add("commit " + truncate(sha, 7) + " 作者无法映射到系统成员(" + login + "/" + email + "),已跳过");
                continue;
            }
            out.add(r);
        }
        return out;
    }

    private List<ActivityRecord> fetchMergedPullRequests(LocalDate from, LocalDate to,
                                                         List<String> warnings, List<String> errors) throws Exception {
        Map<String, Integer> members = loadMembers();
        List<ActivityRecord> out = new ArrayList<>();
        List<JsonNode> rows = getPages("/repos/{repo}/pulls",
                Map.of("state", "all", "sort", "updated", "direction", "desc"), "number");
        for (JsonNode p : rows) {
            String mergedAt = p.path("merged_at").asText("");
            if (mergedAt.isBlank()) continue;   // 只同步"已合并"事件
            LocalDateTime at = parseTime(mergedAt);
            if (at == null || at.toLocalDate().isBefore(from) || at.toLocalDate().isAfter(to)) continue;
            int number = p.path("number").asInt(0);
            String title = "PR #" + number + ": " + p.path("title").asText("");
            String login = p.path("user").path("login").asText("");
            String body = p.path("body").asText("");
            ActivityRecord r = baseRecord(login, "", "", at, members);
            r.setActivityType("pr");
            r.setTitle(truncate(title, 200));
            r.setDetail(truncate("GitHub PR 已合并 · 合并人 " + p.path("merged_by").path("login").asText("")
                    + " · " + p.path("title").asText(""), 1000));
            r.setGithubEventId("pr-" + number + "-merged");
            r.setTaskId(resolveTask(title + " " + body));
            if (r.getUserId() == null) {
                warnings.add("PR #" + number + " 创建者无法映射到系统成员(" + login + "),已跳过");
                continue;
            }
            out.add(r);
        }
        return out;
    }

    private List<ActivityRecord> fetchReviews(LocalDate from, LocalDate to,
                                              List<String> warnings, List<String> errors) throws Exception {
        Map<String, Integer> members = loadMembers();
        List<ActivityRecord> out = new ArrayList<>();
        // 先取范围内已合并的 PR 号(限制数量,控制请求量)
        List<Integer> prNumbers = new ArrayList<>();
        List<JsonNode> rows = getPages("/repos/{repo}/pulls",
                Map.of("state", "all", "sort", "updated", "direction", "desc"), "number");
        for (JsonNode p : rows) {
            String mergedAt = p.path("merged_at").asText("");
            if (mergedAt.isBlank()) continue;
            LocalDateTime at = parseTime(mergedAt);
            if (at != null && !at.toLocalDate().isBefore(from) && !at.toLocalDate().isAfter(to)) {
                prNumbers.add(p.path("number").asInt(0));
            }
            if (prNumbers.size() >= props.getReviewPrLimit()) break;
        }
        for (int number : prNumbers) {
            List<JsonNode> reviews = getPages("/repos/{repo}/pulls/" + number + "/reviews",
                    Map.of(), "id");
            for (JsonNode rv : reviews) {
                String submitted = rv.path("submitted_at").asText("");
                if (submitted.isBlank()) continue;
                LocalDateTime at = parseTime(submitted);
                if (at == null || at.toLocalDate().isBefore(from) || at.toLocalDate().isAfter(to)) continue;
                String login = rv.path("user").path("login").asText("");
                String state = rv.path("state").asText("COMMENTED");
                String body = rv.path("body").asText("");
                ActivityRecord r = baseRecord(login, "", "", at, members);
                r.setActivityType("review");
                r.setTitle(truncate("Review PR #" + number + "(" + state + ")", 200));
                r.setDetail(truncate("GitHub Review " + state + " · PR #" + number + (body.isBlank() ? "" : " · " + firstLine(body)), 1000));
                r.setGithubEventId("review-" + rv.path("id").asText(""));
                if (r.getUserId() == null) {
                    warnings.add("PR #" + number + " 的 Review 无法映射到系统成员(" + login + "),已跳过");
                    continue;
                }
                out.add(r);
            }
        }
        return out;
    }

    private List<ActivityRecord> fetchIssues(LocalDate from, LocalDate to,
                                             List<String> warnings, List<String> errors) throws Exception {
        Map<String, Integer> members = loadMembers();
        List<ActivityRecord> out = new ArrayList<>();
        List<JsonNode> rows = getPages("/repos/{repo}/issues",
                Map.of("state", "all", "since", sinceIso(from)), "number");
        for (JsonNode i : rows) {
            if (i.has("pull_request")) continue;   // GitHub issues 端点含 PR,跳过避免与 pulls 重复
            String created = i.path("created_at").asText("");
            LocalDateTime at = parseTime(created);
            if (at == null || at.toLocalDate().isBefore(from) || at.toLocalDate().isAfter(to)) continue;
            int number = i.path("number").asInt(0);
            String title = i.path("title").asText("");
            String login = i.path("user").path("login").asText("");
            String body = i.path("body").asText("");
            ActivityRecord r = baseRecord(login, "", "", at, members);
            r.setActivityType("note");
            r.setTitle(truncate("Issue #" + number + ": " + title, 200));
            r.setDetail(truncate("GitHub issue 创建 · #" + number + " · " + firstLine(body), 1000));
            r.setGithubEventId("issue-" + number);
            r.setTaskId(resolveTask(title + " " + body));
            if (r.getUserId() == null) {
                warnings.add("Issue #" + number + " 创建者无法映射到系统成员(" + login + "),已跳过");
                continue;
            }
            out.add(r);
        }
        return out;
    }

    // ---------- 通用拉取与写入 ----------

    /** 按页拉取 GET 列表;per_page=100,最多 maxPages 页 */
    private List<JsonNode> getPages(String path, Map<String, String> query, String idKey) throws Exception {
        List<JsonNode> out = new ArrayList<>();
        for (int page = 1; page <= props.getMaxPages(); page++) {
            Map<String, String> q = new LinkedHashMap<>(query);
            q.put("per_page", "100");
            q.put("page", String.valueOf(page));
            String url = props.getApiBase() + path.replace("{repo}", props.getRepo()) + "?" + qs(q);
            JsonNode body = getJson(url);
            if (!body.isArray()) break;
            for (JsonNode n : body) {
                JsonNode v = n.path(idKey);
                if (v.isMissingNode() || v.isNull() || v.asText("").isBlank()) continue;
                out.add(n);
            }
            if (body.size() < 100) break;   // 不满一页即结束
        }
        return out;
    }

    private JsonNode getJson(String url) throws Exception {
        HttpRequest req = HttpRequest.newBuilder(URI.create(url))
                .timeout(Duration.ofSeconds(30))
                .header("Authorization", "Bearer " + props.getToken())
                .header("Accept", "application/vnd.github+json")
                .header("X-GitHub-Api-Version", "2022-11-28")
                .header("User-Agent", "aicap-profile-agent")
                .GET().build();
        HttpResponse<String> resp = http.send(req, HttpResponse.BodyHandlers.ofString());
        if (resp.statusCode() == 401) {
            throw new IllegalStateException("GitHub token 无效(401)");
        }
        if (resp.statusCode() == 403) {
            throw new IllegalStateException("GitHub 限流或权限不足(403),请检查 token 的 repo 权限");
        }
        if (resp.statusCode() == 404) {
            throw new IllegalStateException("仓库不存在或无权访问(404): " + props.getRepo());
        }
        // 空仓库是正常初始状态(commits API 返回 409 "Git Repository is empty"),按"暂无数据"处理,不记错误
        if (resp.statusCode() == 409 && resp.body().contains("empty")) {
            return objectMapper.readTree("[]");
        }
        if (resp.statusCode() < 200 || resp.statusCode() >= 300) {
            throw new IllegalStateException("GitHub API " + resp.statusCode() + ": " + truncate(resp.body(), 200));
        }
        return objectMapper.readTree(resp.body());
    }

    private void persistAll(List<ActivityRecord> records, Map<String, Object> out) {
        int synced = (int) out.get("synced");
        int skipped = (int) out.get("skipped");
        for (ActivityRecord r : records) {
            if (r.getGithubEventId() == null) { skipped++; continue; }
            Long exist = activityMapper.selectCount(new QueryWrapper<ActivityRecord>()
                    .eq("github_event_id", r.getGithubEventId()));
            if (exist != null && exist > 0) { skipped++; continue; }
            r.setCreatedAt(LocalDateTime.now());
            r.setSource("github");
            try {
                activityMapper.insert(r);
                synced++;
            } catch (DuplicateKeyException e) {
                skipped++;   // 唯一索引兜底
            }
        }
        out.put("synced", synced);
        out.put("skipped", skipped);
    }

    // ---------- 映射与转换 ----------

    private Map<String, Integer> loadMembers() {
        Map<String, Integer> m = new HashMap<>();
        for (User u : userMapper.selectList(null)) {
            m.put(u.getUsername(), u.getId());
            m.put(u.getDisplayName(), u.getId());
            int at = u.getUsername().indexOf('@');
            if (at > 0) m.put(u.getUsername().substring(0, at), u.getId());
        }
        return m;
    }

    /** 映射 GitHub 提交者 → 系统 user_id;无法映射返回 null */
    private Integer resolveUserId(String login, String email, String name, Map<String, Integer> members) {
        Map<String, String> mapping = parseUserMapping();
        if (login != null && !login.isBlank() && mapping.containsKey(login)) {
            Integer id = members.get(mapping.get(login));
            if (id != null) return id;
        }
        if (login != null && !login.isBlank() && members.containsKey(login)) return members.get(login);
        if (email != null && !email.isBlank()) {
            int at = email.indexOf('@');
            if (at > 0 && members.containsKey(email.substring(0, at))) return members.get(email.substring(0, at));
        }
        if (name != null && !name.isBlank() && members.containsKey(name)) return members.get(name);
        return null;
    }

    /** 解析 GITHUB_USER_MAPPING JSON(如 {"li-ming":"李锐铭"});非法 JSON 记空映射(不炸) */
    private Map<String, String> parseUserMapping() {
        String json = props.getUserMappingJson();
        if (json == null || json.isBlank()) return Map.of();
        try {
            java.util.Map<String, String> m = new HashMap<>();
            JsonNode n = objectMapper.readTree(json);
            if (n.isObject()) {
                n.fields().forEachRemaining(e -> m.put(e.getKey(), e.getValue().asText("")));
            }
            return m;
        } catch (Exception e) {
            log.warn("[github-sync] GITHUB_USER_MAPPING 不是合法 JSON({}),按空映射处理", truncate(json, 100));
            return Map.of();
        }
    }

    private ActivityRecord baseRecord(String login, String email, String name, LocalDateTime at,
                                      Map<String, Integer> members) {
        ActivityRecord r = new ActivityRecord();
        r.setUserId(resolveUserId(login, email, name, members));
        r.setHappenedAt(at == null ? LocalDateTime.now() : at);
        return r;
    }

    /** PR/Issue/commit 文本中提取 Txx 任务号;任务存在才关联 */
    private String resolveTask(String text) {
        String id = taskIdFrom(text);
        if (id == null) return null;
        Task t = taskMapper.selectById(id);
        return t != null ? id : null;
    }

    /** 纯提取(不查库,可单测):文本中第一个 Txx */
    static String taskIdFrom(String text) {
        if (text == null || text.isBlank()) return null;
        Matcher m = TASK_PATTERN.matcher(text);
        return m.find() ? m.group() : null;
    }

    /** Angular 风格 "(scope):" 提取模块(可单测) */
    static String scopeFrom(String message) {
        if (message == null) return "";
        Matcher m = SCOPE_PATTERN.matcher(message);
        if (m.find() && m.group(1) != null && !m.group(1).isBlank()) return m.group(1).trim();
        return "";
    }

    private String scopeOf(String message) {
        return scopeFrom(message);
    }

    // ---------- 小工具 ----------

    /**
     * GitHub 按 UTC 过滤 since/until。
     * 必须按本地(+8)日界换算,否则同步"今天"会漏掉本地当天 08:00 后的提交:
     * since = 起始日本地 00:00 → UTC;until = 结束日次日本地 00:00 → UTC(含结束日整天)。
     */
    private String sinceIso(LocalDate d) {
        return d.atStartOfDay().atOffset(ZoneOffset.ofHours(8)).toInstant().toString();
    }

    private String untilIso(LocalDate d) {
        return d.plusDays(1).atStartOfDay().atOffset(ZoneOffset.ofHours(8)).toInstant().toString();
    }

    private String qs(Map<String, String> q) {
        return q.entrySet().stream()
                .map(e -> enc(e.getKey()) + "=" + enc(e.getValue()))
                .reduce((a, b) -> a + "&" + b)
                .orElse("");
    }

    private String enc(String s) {
        try {
            return java.net.URLEncoder.encode(s, java.nio.charset.StandardCharsets.UTF_8);
        } catch (Exception e) {
            return s;
        }
    }

    private LocalDateTime parseTime(String s) {
        try {
            return s == null || s.isBlank() ? null
                    : LocalDateTime.ofInstant(java.time.Instant.parse(s), ZoneOffset.ofHours(8));
        } catch (Exception e) {
            return null;
        }
    }

    private String firstLine(String s) {
        if (s == null) return "";
        int i = s.indexOf('\n');
        return i < 0 ? s : s.substring(0, i);
    }

    static String truncate(String s, int max) {
        if (s == null) return "";
        return s.length() <= max ? s : s.substring(0, max);
    }

    private String brief(Exception e) {
        return e == null || e.getMessage() == null ? "unknown" : truncate(e.getMessage(), 200);
    }

    @SuppressWarnings("unchecked")
    private List<String> castList(Object o) {
        return (List<String>) o;
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> castMap(Object o) {
        return (Map<String, Object>) o;
    }
}
