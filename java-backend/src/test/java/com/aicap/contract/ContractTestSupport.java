package com.aicap.contract;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.TestInstance;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * 契约测试基类:
 * - 黑盒 REST(TestRestTemplate, RANDOM_PORT 由各具体类注解开启);
 * - 所有请求/响应 body 以 UTF-8 字节收发,避免中文乱码;
 * - 提供登录取 token、唯一名生成、并发双发等工具;
 * - 每类测试开始前清理"上一次残留"(仅删除不在 US01..US37 内的 story 与全部 pool 条目),
 *   保证基线 37/16/0 可重复断言。
 * - 用户在库中的用户名是<b>真名</b>(李锐铭/高思晗/孙秋实/罗子涵/成员5);登录接口支持
 *   真名 ↔ "成员N" 双向别名,因此两类叫法都能拿到 token(见 {@link #ALIAS_ADMIN} 等)。
 * - 注意:删除故事会把挂卡任务的 kanban_card_id 置空(FK ON DELETE SET NULL),因此清理
 *   残留后不要依赖"某任务仍挂着某临时卡"。
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
public abstract class ContractTestSupport {

    protected static final String USER_ADMIN = "李锐铭";
    protected static final String USER_OWNER = "高思晗";
    protected static final String USER_MEMBER = "孙秋实";
    protected static final String USER_MEMBER2 = "罗子涵";
    protected static final String USER_VIEWER = "成员5";
    protected static final String PASSWORD = "123456";

    /** 登录别名(存量库的"成员N"叫法);与 {@link #USER_ADMIN} 等真名指向同一账号 */
    protected static final String ALIAS_ADMIN = "成员1";
    protected static final String ALIAS_OWNER = "成员2";
    protected static final String ALIAS_MEMBER = "成员3";
    protected static final String ALIAS_MEMBER2 = "成员4";

    /** 每个会议 transcript 都包含的片段(证据引用它,满足"证据必须是会议原文连续片段") */
    protected static final String EVIDENCE_FRAGMENT = "增加成员批量导入功能以降低管理员操作成本";

    /** 种子故事 ID 集合 US01..US37;之外的任何故事编号(含旧 Mxx、测试自造的 US38+)一律视为测试残留 */
    private static final Pattern SEEDED_STORY = Pattern.compile("^US(0[1-9]|[12][0-9]|3[0-7])$");

    @Autowired
    protected TestRestTemplate rest;

    protected final ObjectMapper om = new ObjectMapper();
    private final Map<String, String> tokenCache = new ConcurrentHashMap<>();

    /** 统一响应载体 */
    public record ApiResponse(int status, JsonNode json, String body) {
    }

    // ------------------------------------------------------------------
    // 生命周期:清残留
    // ------------------------------------------------------------------

    @BeforeAll
    void purgeResidueFromEarlierRuns() {
        String admin = token(USER_ADMIN);
        // 需求池契约基线为空:清掉全部残留条目(含 approve 落池的 A 编号、POST /pool 的 R 编号)
        ApiResponse poolList = get("/api/pool", admin);
        if (poolList.json() != null && poolList.json().isArray()) {
            for (JsonNode it : poolList.json()) {
                delete("/api/pool/" + it.path("id").asText(), admin);
            }
        }
        // 故事只保留种子 US01..US37;清掉上次崩溃残留的临时故事(US38+ 或历史 Mxx)
        ApiResponse storyList = get("/api/stories", admin);
        if (storyList.json() != null && storyList.json().isArray()) {
            for (JsonNode it : storyList.json()) {
                String id = it.path("id").asText();
                if (id != null && !SEEDED_STORY.matcher(id).matches()) {
                    delete("/api/stories/" + id + "?undone=keep", admin);
                }
            }
        }
    }

    // ------------------------------------------------------------------
    // HTTP 工具(黑盒;UTF-8 字节)
    // ------------------------------------------------------------------

    protected ApiResponse request(HttpMethod method, String path, String token, String jsonBody) {
        HttpHeaders headers = new HttpHeaders();
        headers.set(HttpHeaders.ACCEPT, MediaType.APPLICATION_JSON_VALUE);
        if (token != null) {
            headers.setBearerAuth(token);
        }
        HttpEntity<byte[]> entity;
        if (jsonBody != null) {
            headers.setContentType(MediaType.APPLICATION_JSON);
            entity = new HttpEntity<>(jsonBody.getBytes(StandardCharsets.UTF_8), headers);
        } else {
            entity = new HttpEntity<>(headers);
        }
        ResponseEntity<byte[]> resp;
        try {
            resp = rest.exchange(path, method, entity, byte[].class);
        } catch (Exception e) {
            throw new AssertionError("HTTP 调用异常 " + method + " " + path + ": " + e);
        }
        byte[] raw = resp.getBody();
        String body = raw == null ? "" : new String(raw, StandardCharsets.UTF_8);
        JsonNode json = null;
        if (!body.isBlank()) {
            try {
                json = om.readTree(body);
            } catch (Exception ignored) {
                // 非 JSON 响应:保留 null
            }
        }
        return new ApiResponse(resp.getStatusCode().value(), json, body);
    }

    protected ApiResponse get(String path, String token) {
        return request(HttpMethod.GET, path, token, null);
    }

    protected ApiResponse post(String path, String token, String jsonBody) {
        return request(HttpMethod.POST, path, token, jsonBody);
    }

    protected ApiResponse patch(String path, String token, String jsonBody) {
        return request(HttpMethod.PATCH, path, token, jsonBody);
    }

    protected ApiResponse delete(String path, String token) {
        return request(HttpMethod.DELETE, path, token, null);
    }

    /** 序列化请求体(LinkedHashMap 保持字段顺序稳定) */
    protected String json(Map<String, Object> body) {
        try {
            return om.writeValueAsString(body);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    /** 有序 Map 快速构造 */
    protected Map<String, Object> map(Object... kv) {
        Map<String, Object> m = new LinkedHashMap<>();
        for (int i = 0; i < kv.length; i += 2) {
            m.put(String.valueOf(kv[i]), kv[i + 1]);
        }
        return m;
    }

    // ------------------------------------------------------------------
    // 登录 / 断言 / 唯一名
    // ------------------------------------------------------------------

    protected String token(String username) {
        return tokenCache.computeIfAbsent(username, u -> {
            ApiResponse r = post("/api/auth/login", null,
                    json(map("username", u, "password", PASSWORD)));
            assertEquals(200, r.status(), "登录失败 " + u + ": " + r.body());
            assertNotNull(r.json(), "登录响应非 JSON: " + r.body());
            String t = r.json().path("access_token").asText();
            assertTrue(!t.isEmpty(), "登录响应缺少 access_token: " + r.body());
            return t;
        });
    }

    protected void assertStatus(ApiResponse r, int expected) {
        assertEquals(expected, r.status(), "响应 body: " + r.body());
    }

    protected String detail(ApiResponse r) {
        assertNotNull(r.json(), "错误响应非 JSON: " + r.body());
        return r.json().path("detail").asText();
    }

    protected static String uniq(String prefix) {
        return prefix + "-" + UUID.randomUUID().toString().replace("-", "").substring(0, 12);
    }

    // ------------------------------------------------------------------
    // 并发工具:barrier 对齐后同时发起 n 个调用
    // ------------------------------------------------------------------

    @SafeVarargs
    protected final List<ApiResponse> fireConcurrently(java.util.function.Supplier<ApiResponse>... calls) {
        int n = calls.length;
        ExecutorService pool = Executors.newFixedThreadPool(n);
        CyclicBarrier barrier = new CyclicBarrier(n);
        List<Future<ApiResponse>> futures = new ArrayList<>();
        for (java.util.function.Supplier<ApiResponse> call : calls) {
            futures.add(pool.submit(new Callable<>() {
                @Override
                public ApiResponse call() {
                    try {
                        barrier.await(15, TimeUnit.SECONDS);
                    } catch (Exception e) {
                        throw new IllegalStateException(e);
                    }
                    return call.get();
                }
            }));
        }
        List<ApiResponse> results = new ArrayList<>();
        try {
            for (Future<ApiResponse> f : futures) {
                results.add(f.get(60, TimeUnit.SECONDS));
            }
        } catch (Exception e) {
            fail("并发调用异常: " + e);
        } finally {
            pool.shutdownNow();
        }
        return results;
    }

    /** 需求池中匹配指定 title 的条目数 */
    protected int poolCountByTitle(String title, String token) {
        ApiResponse pool = get("/api/pool", token);
        if (pool.json() == null || !pool.json().isArray()) {
            return 0;
        }
        int c = 0;
        for (JsonNode it : pool.json()) {
            if (title.equals(it.path("title").asText())) {
                c++;
            }
        }
        return c;
    }

    /** 删除需求池条目(清理用;admin 权限) */
    protected void deletePoolItem(String poolId) {
        if (poolId == null || poolId.isEmpty()) {
            return;
        }
        ApiResponse r = delete("/api/pool/" + poolId, token(USER_ADMIN));
        assertEquals(200, r.status(), "清理 pool 条目失败: " + r.body());
    }

    /** 删除故事(清理用;admin 权限) */
    protected void deleteStory(String storyId) {
        if (storyId == null || storyId.isEmpty()) {
            return;
        }
        ApiResponse r = delete("/api/stories/" + storyId + "?undone=keep", token(USER_ADMIN));
        assertEquals(200, r.status(), "清理 story 失败: " + r.body());
    }

    // ------------------------------------------------------------------
    // 复读工具(回归"响应说了但库里没变"类缺陷:断言必须靠重新 GET,而不是只看写响应)
    // ------------------------------------------------------------------

    /** 从 GET /api/tasks 复读指定任务;不存在返回 null。断言失败时带完整列表实体 */
    protected JsonNode taskFromList(String taskId) {
        ApiResponse r = get("/api/tasks", token(USER_ADMIN));
        assertEquals(200, r.status(), "GET /api/tasks 失败: " + r.body());
        assertNotNull(r.json(), "GET /api/tasks 非 JSON: " + r.body());
        assertTrue(r.json().isArray(), "GET /api/tasks 应为数组: " + r.body());
        for (JsonNode t : r.json()) {
            if (taskId.equals(t.path("id").asText())) {
                return t;
            }
        }
        return null;
    }

    /** 从 GET /api/tasks 复读指定任务,不存在直接失败 */
    protected JsonNode taskFromListOrFail(String taskId) {
        JsonNode t = taskFromList(taskId);
        assertNotNull(t, "GET /api/tasks 中找不到任务 " + taskId);
        return t;
    }

    /** 从 GET /api/stories 复读指定故事;不存在返回 null */
    protected JsonNode storyFromList(String storyId) {
        ApiResponse r = get("/api/stories", token(USER_ADMIN));
        assertEquals(200, r.status(), "GET /api/stories 失败: " + r.body());
        assertNotNull(r.json(), "GET /api/stories 非 JSON: " + r.body());
        for (JsonNode s : r.json()) {
            if (storyId.equals(s.path("id").asText())) {
                return s;
            }
        }
        return null;
    }
}
