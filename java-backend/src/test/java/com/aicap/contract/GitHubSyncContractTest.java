package com.aicap.contract;

import com.aicap.entity.ActivityRecord;
import com.aicap.mapper.ActivityRecordMapper;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DuplicateKeyException;

import java.time.LocalDateTime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * GitHub 接入(US34)契约测试:
 * - 未配置 GITHUB_ENABLED/token/repo 时,status 返回 enabled=false,sync 返回"未配置"状态(不发外部请求、不抛错);
 * - activity_records 的 github_event_id 唯一索引生效(幂等去重的基础)。
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "spring.datasource.url=jdbc:mysql://127.0.0.1:3307/aicap_java_test?useUnicode=true&characterEncoding=utf8&serverTimezone=Asia/Shanghai&allowPublicKeyRetrieval=true&useSSL=false",
        "spring.datasource.username=aiguanli",
        "spring.datasource.password=aiguanli-2026",
        "aicap.llm.agent-worker-enabled=false",
        "profile-llm.api-key=",
        "spring.sql.init.data-locations=classpath:db/reset_test_data.sql"
})
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class GitHubSyncContractTest extends ContractTestSupport {

    @Autowired
    private ActivityRecordMapper activityMapper;

    @Test
    void githubStatus_returnsNotConfigured() {
        ApiResponse r = get("/api/profile-agent/github/status", token(USER_ADMIN));
        assertStatus(r, 200);
        assertFalse(r.json().path("enabled").asBoolean(), r.body());
    }

    @Test
    void githubSync_unconfigured_returnsStatusNotError() {
        ApiResponse r = post("/api/profile-agent/github/sync?start=2026-08-25&end=2026-09-15",
                token(USER_ADMIN), null);
        assertStatus(r, 200);   // 业务上"未配置"是有效状态,不是 5xx
        assertFalse(r.json().path("enabled").asBoolean(), r.body());
        assertTrue(r.json().path("error").asText().contains("未配置"), r.body());
        // 四人成员(admin/owner/member)都能触发同步:「完成任务 → 触发智能体读仓库」这条链上
        // 触发者是普通成员;只读查看者(viewer)仍然 403
        assertStatus(post("/api/profile-agent/github/sync?start=2026-08-25&end=2026-09-15",
                token(USER_MEMBER), null), 200);
        assertStatus(post("/api/profile-agent/github/sync?start=2026-08-25&end=2026-09-15",
                token(USER_VIEWER), null), 403);
    }

    @Test
    void githubEventId_uniqueIndex_enforcesDedup() {
        // 清理该测试的残留,保证唯一性验证干净
        activityMapper.delete(new QueryWrapper<ActivityRecord>().eq("github_event_id", "test-sha-0001"));

        ActivityRecord a = new ActivityRecord();
        a.setUserId(1);
        a.setActivityType("commit");
        a.setTitle("契约测试 GitHub 事件");
        a.setDetail("dedup");
        a.setModule("");
        a.setSource("github");
        a.setHappenedAt(LocalDateTime.now().minusMinutes(5));
        a.setGithubEventId("test-sha-0001");
        a.setCreatedAt(LocalDateTime.now());
        activityMapper.insert(a);

        ActivityRecord dup = new ActivityRecord();
        dup.setUserId(1);
        dup.setActivityType("commit");
        dup.setTitle("重复事件");
        dup.setDetail("dup");
        dup.setModule("");
        dup.setSource("github");
        dup.setHappenedAt(LocalDateTime.now().minusMinutes(5));
        dup.setGithubEventId("test-sha-0001");
        dup.setCreatedAt(LocalDateTime.now());
        // 唯一索引兜底:同一 github_event_id 二次插入必须失败
        assertThrows(DuplicateKeyException.class, () -> activityMapper.insert(dup));

        // 不同 github_event_id 可插入
        ActivityRecord other = new ActivityRecord();
        other.setUserId(1);
        other.setActivityType("commit");
        other.setTitle("另一事件");
        other.setDetail("ok");
        other.setModule("");
        other.setSource("github");
        other.setHappenedAt(LocalDateTime.now().minusMinutes(5));
        other.setGithubEventId("test-sha-0002");
        other.setCreatedAt(LocalDateTime.now());
        activityMapper.insert(other);

        Long count = activityMapper.selectCount(new QueryWrapper<ActivityRecord>()
                .eq("github_event_id", "test-sha-0001"));
        assertEquals(1, count);
        // 清理
        activityMapper.delete(new QueryWrapper<ActivityRecord>()
                .in("github_event_id", "test-sha-0001", "test-sha-0002"));
    }
}
