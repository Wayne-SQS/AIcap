package com.aicap.service;

import com.aicap.profile.GitHubProperties;
import com.aicap.profile.ProjectRepositoryResolver;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * US22 代码质量评估的数据源:按项目反查仓库,取一批有代表性的源文件交给 {@link RepoQualityScorer}。
 *
 * <p>取数策略(为演示可用性做的取舍,均已显式记录):
 * <ul>
 *   <li>只取源码扩展名(.java/.js/.ts/.py 等),排除 node_modules / target / dist / build / vendor;</li>
 *   <li>优先 src/main、src 等主代码目录,按路径排序后取前 {@link #MAX_FILES} 个,避免整仓拉取;</li>
 *   <li>单文件超过 {@link #MAX_FILE_BYTES} 跳过;整体失败只降级为 available=false + reason,不影响主接口;</li>
 *   <li>结果缓存 {@link #TTL_MS},连续刷新不重复打 GitHub。</li>
 * </ul>
 *
 * <p>覆盖度诚实声明:抽样评估,不等于全仓审计。返回值里带 sampled_files / total_candidates,
 * 让使用者知道"评了多少、总共有多少"。
 */
@Service
public class RepoQualityFetcher {
    private static final Logger log = LoggerFactory.getLogger(RepoQualityFetcher.class);

    private static final int MAX_FILES = 24;
    private static final int MAX_TEST_FILES = 12;
    private static final long MAX_FILE_BYTES = 200_000L;
    private static final long TTL_MS = 10 * 60_000L;

    private static final Set<String> SOURCE_EXT = Set.of("java", "js", "jsx", "mjs", "ts", "tsx", "py");
    private static final List<String> EXCLUDE = List.of(
            "node_modules/", "/target/", "\\target\\", "dist/", "build/", "vendor/", ".min.", "/test/resources/");

    private final GitHubProperties props;
    private final ProjectRepositoryResolver resolver;
    private final RepoQualityScorer scorer;
    private final ObjectMapper mapper = new ObjectMapper();
    private final HttpClient http = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(6))
            .followRedirects(HttpClient.Redirect.NORMAL)
            .build();
    private final ConcurrentHashMap<String, CacheEntry> cache = new ConcurrentHashMap<>();

    private record CacheEntry(Map<String, Object> value, long at) {}

    public RepoQualityFetcher(GitHubProperties props, ProjectRepositoryResolver resolver, RepoQualityScorer scorer) {
        this.props = props;
        this.resolver = resolver;
        this.scorer = scorer;
    }

    /** 只读缓存,不触发网络:主接口用它判断"是否已评估过",避免页面加载时被拉取拖慢。 */
    public Map<String, Object> peek(String projectId) {
        CacheEntry e = cache.get(projectId);
        return e != null && System.currentTimeMillis() - e.at() < TTL_MS ? e.value() : null;
    }

    /** 对项目映射的仓库做代码质量评估;无映射 / 无 token / 拉取失败都降级返回,不抛异常。 */
    public Map<String, Object> evaluate(String projectId) {
        CacheEntry cached = cache.get(projectId);
        long now = System.currentTimeMillis();
        if (cached != null && now - cached.at() < TTL_MS) return cached.value();

        Map<String, Object> out = new LinkedHashMap<>();
        String repo = resolver.resolveRepo(projectId).orElse(null);
        if (repo == null || repo.isBlank()) {
            out.put("available", false);
            out.put("reason", "项目未映射 GitHub 仓库");
            return out;
        }
        if (props.getToken().isBlank()) {
            out.put("available", false);
            out.put("reason", "未配置 GITHUB_TOKEN");
            return out;
        }
        try {
            String branch = defaultBranch(repo);
            Map<String, List<String>> pools = candidatePools(repo, branch);
            List<String> mainPaths = pools.get("main");
            List<String> testPaths = pools.get("test");

            List<RepoQualityScorer.SourceFile> files = new ArrayList<>();
            // 主代码：拉内容，参与全部维度
            for (String path : mainPaths) {
                if (files.size() >= MAX_FILES) break;
                String content = fetchContent(repo, branch, path);
                if (content == null || content.length() > MAX_FILE_BYTES) continue;
                files.add(new RepoQualityScorer.SourceFile(path, content));
            }
            // README 正文：文档内容质量要用
            for (String path : pools.get("readme")) {
                String content = fetchContent(repo, branch, path);
                if (content != null && content.length() <= MAX_FILE_BYTES) {
                    files.add(new RepoQualityScorer.SourceFile(path, content));
                    break;
                }
            }
            // 测试文件：全部登记路径（保证密度按真实比例），前 MAX_TEST_FILES 个拉正文（供断言质量分析）
            int testRegistered = 0, testWithContent = 0;
            for (String path : testPaths) {
                if (testRegistered >= 40) break;
                String content = "";
                if (testWithContent < MAX_TEST_FILES) {
                    String c = fetchContent(repo, branch, path);
                    if (c != null && c.length() <= MAX_FILE_BYTES) { content = c; testWithContent++; }
                }
                files.add(new RepoQualityScorer.SourceFile(path, content));
                testRegistered++;
            }
            Map<String, Object> scored = scorer.score(files);
            out.put("available", true);
            out.put("repo", repo);
            out.put("branch", branch);
            int mainCount = mainPaths.size(), testCount = testPaths.size();
            out.put("sampled_files", Math.min(files.size(), MAX_FILES));
            out.put("total_candidates", mainCount + testCount);
            out.put("main_files", mainCount);
            out.put("test_files_in_repo", testCount);
            out.put("sample_note", "主代码抽样 " + Math.min(files.size(), MAX_FILES) + "/" + mainCount
                    + " 个（上限 " + MAX_FILES + "）；测试文件计数 " + Math.min(testCount, 40)
                    + " 个、其中 " + testWithContent + " 个读取正文做断言分析；非全仓审计");
            out.putAll(scored);
        } catch (Exception e) {
            log.warn("[quality] 代码质量评估失败 project={} repo={}: {}", projectId, repo, e.toString());
            out.put("available", false);
            out.put("reason", "拉取仓库失败: " + brief(e));
        }
        cache.put(projectId, new CacheEntry(out, now));
        return out;
    }

    private String defaultBranch(String repo) throws Exception {
        JsonNode n = mapper.readTree(get(props.getApiBase() + "/repos/" + repo));
        return n.path("default_branch").asText("main");
    }

    /** 候选源文件分两池:主代码(拉内容评分) / 测试代码(只计数),避免测试夹具参与安全与命名扣分 */
    private Map<String, List<String>> candidatePools(String repo, String branch) throws Exception {
        JsonNode tree = mapper.readTree(
                get(props.getApiBase() + "/repos/" + repo + "/git/trees/" + branch + "?recursive=1")).path("tree");
        List<String> main = new ArrayList<>();
        List<String> test = new ArrayList<>();
        List<String> readme = new ArrayList<>();
        for (JsonNode node : tree) {
            if (!"blob".equals(node.path("type").asText(""))) continue;
            String p = node.path("path").asText("");
            String lp = p.toLowerCase();
            // README 单独收：文档内容质量需要正文
            if (lp.matches("(^|.*/)readme\\.(md|txt|rst)$") && p.split("/").length <= 2) {
                readme.add(p);
                continue;
            }
            int dot = p.lastIndexOf('.');
            if (dot < 0 || !SOURCE_EXT.contains(p.substring(dot + 1).toLowerCase())) continue;
            String lower = p.toLowerCase();
            boolean skip = false;
            for (String ex : EXCLUDE) if (lower.contains(ex)) { skip = true; break; }
            if (skip) continue;
            if (RepoQualityScorer.looksLikeTestPath(p)) test.add(p); else main.add(p);
        }
        // src/main 优先,其次 src;同层按字典序,保证同一仓库每次采样一致
        main.sort(Comparator
                .comparingInt((String p) -> p.contains("src/main/") ? 0 : p.startsWith("src/") ? 1 : 2)
                .thenComparing(Comparator.naturalOrder()));
        test.sort(Comparator.naturalOrder());
        Map<String, List<String>> out = new LinkedHashMap<>();
        out.put("main", main);
        out.put("test", test);
        out.put("readme", readme);
        return out;
    }

    /**
     * 用 Contents API 取文件（base64），不用 raw.githubusercontent：
     * 实测 raw 域名在国内网络不可达(657 个候选拉回 0 个),而 api.github.com 可达。
     */
    private String fetchContent(String repo, String branch, String path) {
        try {
            String encodedPath = URLEncoder.encode(path, StandardCharsets.UTF_8).replace("%2F", "/");
            String body = get(props.getApiBase() + "/repos/" + repo + "/contents/" + encodedPath + "?ref=" + branch);
            JsonNode n = mapper.readTree(body);
            if (!"base64".equals(n.path("encoding").asText())) return null;
            String b64 = n.path("content").asText("").replaceAll("\\s", "");
            if (b64.isEmpty()) return null;
            return new String(Base64.getDecoder().decode(b64), StandardCharsets.UTF_8);
        } catch (Exception e) {
            return null;
        }
    }

    private String get(String url) throws Exception {
        HttpRequest req = HttpRequest.newBuilder(URI.create(url))
                .header("Authorization", "Bearer " + props.getToken())
                .header("Accept", "application/vnd.github+json")
                .header("User-Agent", "aicap-quality-analysis")
                .timeout(Duration.ofSeconds(10))
                .GET().build();
        HttpResponse<String> resp = http.send(req, HttpResponse.BodyHandlers.ofString());
        if (resp.statusCode() >= 400) throw new IllegalStateException("HTTP " + resp.statusCode());
        return resp.body();
    }

    private static String brief(Exception e) {
        String m = e.getMessage();
        return m == null ? e.getClass().getSimpleName() : (m.length() > 80 ? m.substring(0, 80) : m);
    }
}
