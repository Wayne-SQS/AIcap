package com.aicap.contract;

import com.aicap.entity.ActivityRecord;
import com.aicap.mapper.ActivityRecordMapper;
import com.aicap.profile.GitHubActivitySyncService;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * GitHub 接入端到端契约测试(本地 mock GitHub API,验证真实同步链路):
 * - 四类事件(commit/PR 合并/Review/Issue 创建)解析与落库,source=github;
 * - 成员映射(GITHUB_USER_MAPPING → 系统成员),未映射活动跳过并记 warnings;
 * - 无 author.login 的提交者(GitHub 上未关联账号)靠映射的邮箱/姓名键兜底归属,不被当成未映射;
 * - commit/PR/Issue 文本中 Txx 自动关联任务;
 * - 服务端日期边界防线:API 返回范围外(本地 9/16)的 commit 不入库;
 * - 幂等:同一 github_event_id 二次同步 skipped 递增,synced=0;
 * - issues 端点里的 PR 节点被跳过,不与 pulls 重复。
 *
 * 本测试无需真实 token:api-base 指向本地 mock 服务器。
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "spring.datasource.url=jdbc:mysql://127.0.0.1:3307/aicap_java_test?useUnicode=true&characterEncoding=utf8&serverTimezone=Asia/Shanghai&allowPublicKeyRetrieval=true&useSSL=false",
        "spring.datasource.username=aiguanli",
        "spring.datasource.password=aiguanli-2026",
        "aicap.llm.agent-worker-enabled=false",
        "profile-llm.api-key=",
        "spring.sql.init.data-locations=classpath:db/reset_test_data.sql",
        "github.enabled=true",
        "github.token=fake-token-for-contract-test",
        "github.repo=acme/core",
        "github.api-base=http://127.0.0.1:18923",
        "github.user-mapping-json={\"li-ming\":\"李锐铭\",\"zhang-san\":\"孙秋实\",\"gao-owner\":\"高思晗\",\"mcc-dev\":\"高思晗\"}"
})
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class GitHubSyncEndToEndContractTest {

    private static HttpServer server;

    static {
        // 本地 mock GitHub API:按路径返回预置 JSON(不按 since/until 过滤,用于验证服务端边界防线)
        try {
            server = HttpServer.create(new InetSocketAddress(18923), 0);
            server.createContext("/", GitHubSyncEndToEndContractTest::handle);
            server.start();
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    private static void handle(HttpExchange ex) throws IOException {
        String path = ex.getRequestURI().getPath();
        String body;
        if (path.startsWith("/repos/acme/core/commits")) {
            body = """
                    [{"sha":"abc111","commit":{"author":{"name":"李锐铭","email":"liming@x.com","date":"2026-09-10T03:00:00Z"},"message":"feat(auth): T03 权限模块"}},
                     {"sha":"abc222","commit":{"author":{"name":"Zhang San","email":"zhang@x.com","date":"2026-09-15T20:00:00Z"},"message":"fix(profile): 修复 T05"}},
                     {"sha":"abc333","author":{"login":"unknown-dev"},"commit":{"author":{"name":"Unknown","email":"unknown@x.com","date":"2026-09-11T02:00:00Z"},"message":"docs: 更新说明"}},
                     {"sha":"abc444","commit":{"author":{"name":"mcc-dev","email":"mcc@mcc.mcc","date":"2026-09-11T04:00:00Z"},"message":"feat(ui): 四类视图完善"}}]
                    """.replace("\n", "");
        } else if (path.startsWith("/repos/acme/core/pulls/42/reviews")) {
            body = """
                    [{"id":9001,"user":{"login":"gao-owner"},"submitted_at":"2026-09-12T06:00:00Z","state":"APPROVED","body":"LGTM"}]
                    """.replace("\n", "");
        } else if (path.startsWith("/repos/acme/core/pulls")) {
            body = """
                    [{"number":42,"title":"US34 GitHub 同步 T06","body":"实现 T06","user":{"login":"li-ming"},"merged_at":"2026-09-12T05:00:00Z","merged_by":{"login":"gao-owner"}}]
                    """.replace("\n", "");
        } else if (path.startsWith("/repos/acme/core/issues")) {
            body = """
                    [{"number":7,"title":"优化加载 T08","user":{"login":"zhang-san"},"created_at":"2026-09-13T02:00:00Z","body":"详情"},
                     {"number":42,"title":"US34 GitHub 同步 T06","pull_request":{},"user":{"login":"li-ming"},"created_at":"2026-09-12T05:00:00Z"}]
                    """.replace("\n", "");
        } else {
            ex.sendResponseHeaders(404, -1);
            ex.close();
            return;
        }
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        ex.getResponseHeaders().set("Content-Type", "application/json; charset=utf-8");
        ex.sendResponseHeaders(200, bytes.length);
        try (OutputStream os = ex.getResponseBody()) {
            os.write(bytes);
        }
    }

    @BeforeEach
    void clearEvents() {
        // 各测试方法独立:先清掉本测试的 GitHub 事件,保证 synced 计数确定
        activityMapper.delete(new QueryWrapper<ActivityRecord>()
                .in("github_event_id", "abc111", "abc222", "abc333", "abc444", "pr-42-merged", "review-9001", "issue-7"));
    }

    @AfterAll
    void cleanup() {
        activityMapper.delete(new QueryWrapper<ActivityRecord>()
                .in("github_event_id", "abc111", "abc222", "abc333", "abc444", "pr-42-merged", "review-9001", "issue-7"));
        if (server != null) server.stop(0);
    }

    @Autowired
    private GitHubActivitySyncService syncService;
    @Autowired
    private ActivityRecordMapper activityMapper;

    @Test
    void sync_endToEnd_persistsMappedEventsWithTaskLinks() {
        Map<String, Object> r = syncService.sync(LocalDate.of(2026, 9, 1), LocalDate.of(2026, 9, 15));
        @SuppressWarnings("unchecked")
        List<String> errors = (List<String>) r.get("errors");
        assertTrue(errors.isEmpty(), "sync errors: " + errors);
        @SuppressWarnings("unchecked")
        Map<String, Object> pulled = (Map<String, Object>) r.get("pulled");

        // 拉取解析量:pulled = 日期范围内且时间戳可解析的事件数(含未映射成员,口径见 43431dc 三分离)
        // commits API 返回 4:abc111 入库;abc222 越界(本地 9/16)被边界防线过滤,不计入 pulled;
        // abc333 在范围内但作者未映射 → 计入 pulled,入库时并入 skipped;
        // abc444 无 author.login(GitHub 上未关联账号),靠 GITHUB_USER_MAPPING 的姓名键兜底归属 → 入库
        // pr 1 / review 1 / issue 1(PR 节点被跳过)
        assertEquals(3, pulled.get("commits"));
        assertEquals(1, pulled.get("prs"));
        assertEquals(1, pulled.get("reviews"));
        assertEquals(1, pulled.get("issues"));
        // 入库:abc111 + abc444 + pr-42-merged + review-9001 + issue-7 = 5;abc222 越界(本地 9/16)被服务端过滤
        assertEquals(5, r.get("synced"));
        // abc333 未映射 → 计入 skipped(三分离口径:pulled = synced + skipped)
        assertEquals(1, r.get("skipped"));
        @SuppressWarnings("unchecked")
        List<String> warnings = (List<String>) r.get("warnings");
        assertEquals(1, warnings.size());
        assertTrue(warnings.get(0).contains("abc333"), warnings.toString());

        List<ActivityRecord> rows = activityMapper.selectList(new QueryWrapper<ActivityRecord>()
                .eq("source", "github").orderByAsc("github_event_id"));
        assertEquals(5, rows.size(), "source=github 应恰为 5 条");

        var byId = new java.util.HashMap<String, ActivityRecord>();
        for (ActivityRecord a : rows) byId.put(a.getGithubEventId(), a);

        // commit:sha 幂等键 + T03 任务关联 + 成员映射(li-ming→李锐铭 id=1) + 模块 (auth)
        ActivityRecord c = byId.get("abc111");
        assertEquals("commit", c.getActivityType());
        assertEquals("T03", c.getTaskId());
        assertEquals(1, c.getUserId());
        assertEquals("auth", c.getModule());
        assertEquals("2026-09-10 11:00:00", c.getHappenedAt().format(java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")));
        // 越界 commit 未入库
        assertNull(byId.get("abc222"));
        // 无 author.login 的提交(GitHub 上未关联账号,如 mcc@mcc.mcc):
        // 靠 GITHUB_USER_MAPPING 的姓名键兜底(作者名 mcc-dev → 高思晗 id=2),而不是被跳过
        ActivityRecord noLogin = byId.get("abc444");
        assertEquals("commit", noLogin.getActivityType());
        assertEquals(2, noLogin.getUserId(), "无 login 的提交者应能靠映射的姓名键归属");
        // PR 合并:pr-<number>-merged + T06
        ActivityRecord pr = byId.get("pr-42-merged");
        assertEquals("pr", pr.getActivityType());
        assertEquals("T06", pr.getTaskId());
        assertEquals(1, pr.getUserId());
        // Review:review-<id> + 高思晗(id=2)
        ActivityRecord rv = byId.get("review-9001");
        assertEquals("review", rv.getActivityType());
        assertEquals(2, rv.getUserId());
        assertTrue(rv.getTitle().contains("APPROVED"), rv.getTitle());
        // Issue 创建:issue-<number> + 孙秋实(id=3) + T08
        ActivityRecord iss = byId.get("issue-7");
        assertEquals("note", iss.getActivityType());
        assertEquals("T08", iss.getTaskId());
        assertEquals(3, iss.getUserId());
    }

    @Test
    void sync_twice_isIdempotent() {
        Map<String, Object> r1 = syncService.sync(LocalDate.of(2026, 9, 1), LocalDate.of(2026, 9, 15));
        assertEquals(5, r1.get("synced"));
        Map<String, Object> r2 = syncService.sync(LocalDate.of(2026, 9, 1), LocalDate.of(2026, 9, 15));
        assertEquals(0, r2.get("synced"));
        // 5 条已存在(幂等跳过) + 1 条未映射(abc333,每次都计入 skipped) = 6
        assertEquals(6, r2.get("skipped"));
    }
}
