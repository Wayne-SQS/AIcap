package com.aicap.contract;

import com.aicap.entity.MeetingSuggestionRecord;
import com.aicap.entity.Suggestion;
import com.aicap.mapper.MeetingSuggestionRecordMapper;
import com.aicap.mapper.SuggestionMapper;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpMethod;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * US37（并入 US36 交付）：项目层面协调建议必须说明「依据」与「影响」。
 *
 * <p>钉住三件事：
 * <ol>
 *   <li>团队风险（7 类）每条都带 impact，不能只有证据与建议动作；</li>
 *   <li>送审落库的建议在 note 与 change_json 里都写明影响，并进入统一审核队列；</li>
 *   <li>缺 projectId 的送审请求被拒（422），不会落到默认项目。</li>
 * </ol>
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
class ProfileRiskSuggestionContractTest extends ContractTestSupport {

    private static final String RANGE = "?start=2026-08-25&end=2026-09-15";

    @Autowired
    private SuggestionMapper suggestionMapper;

    @Autowired
    private MeetingSuggestionRecordMapper reviewRecordMapper;

    @Test
    void teamRisks_eachCarryImpact() {
        ApiResponse r = get("/api/profile-agent/analysis" + RANGE, token(USER_MEMBER));
        assertStatus(r, 200);
        JsonNode risks = r.json().path("team_risks");
        assertTrue(risks.isArray(), r.body());
        assertTrue(risks.size() > 0, "种子数据应至少产生一条团队风险 -> " + r.body());
        for (JsonNode risk : risks) {
            String impact = risk.path("impact").asText("");
            assertFalse(impact.isBlank(), "每条风险都要说明影响 -> " + risk);
        }
    }

    @Test
    void submittedSuggestion_carriesImpact_andEntersReviewQueue() {
        // 用罗子涵：admin/member 的 30 秒分析窗口被 ProfileAgentFixContractTest 占用，
        // 限流按 user_id 计数，换一个本类独占的用户才能稳定复现「送审」路径。
        ApiResponse r = post("/api/profile-agent/risks/submit-suggestions" + RANGE + "&agent=任务提交智能体",
                token(USER_MEMBER2), null);
        assertStatus(r, 200);
        JsonNode ids = r.json();
        assertTrue(ids.isArray() && ids.size() > 0, "应至少生成一条待审建议 -> " + r.body());

        List<Suggestion> rows = suggestionMapper.selectList(new QueryWrapper<Suggestion>()
                .eq("kind", "profile").eq("status", "pending"));
        assertFalse(rows.isEmpty(), "送审后应有 pending 建议");

        Suggestion hit = null;
        for (Suggestion s : rows) {
            if (s.getNote() != null && s.getNote().contains("影响")
                    && s.getChangeJson() != null && s.getChangeJson().contains("影响")) {
                hit = s;
                break;
            }
        }
        assertNotNull(hit, "待审建议的 note 与 change_json 都应写明影响");

        // 统一审核中心有对应记录（meeting_id 为空 = 非会议来源）
        MeetingSuggestionRecord record = reviewRecordMapper.selectOne(new QueryWrapper<MeetingSuggestionRecord>()
                .eq("suggestion_id", hit.getId()));
        assertNotNull(record, "建议应同步建审核记录");
        assertEquals("agent", record.getOrigin(), "来源应标为 agent");

        // US20 主旅程：队列可见 → 负责人采纳 → 写入需求池 → 从审核记录回读执行结果。
        String id = ids.get(0).asText();
        ApiResponse queue = get("/api/review-queue?status=pending&source=general&search=" + id,
                token(USER_ADMIN));
        assertStatus(queue, 200);
        assertTrue(queue.json().path("items").toString().contains(id), "风险建议应在统一审核队列: " + queue.body());

        String poolId = null;
        try {
            ApiResponse reviewed = post("/api/suggestions/" + id + "/review", token(USER_ADMIN),
                    "{\"decision\":\"approve\",\"reason\":\"确认风险并安排跟进\"}");
            assertStatus(reviewed, 200);
            assertEquals("approved", reviewed.json().path("status").asText());
            assertEquals("succeeded", reviewed.json().path("execution_status").asText());
            poolId = reviewed.json().path("pool_item_id").asText();
            assertFalse(poolId.isBlank(), "采纳后应创建需求池条目");
            assertTrue(get("/api/pool", token(USER_ADMIN)).body().contains(poolId), "需求池应能回读执行结果");
            assertEquals(poolId, get("/api/suggestions/" + id, token(USER_ADMIN))
                    .json().path("pool_item_id").asText(), "审核记录应回流执行结果");
        } finally {
            if (poolId != null && !poolId.isBlank()) {
                assertStatus(delete("/api/pool/" + poolId, token(USER_ADMIN)), 200);
            }
        }
    }

    @Test
    void submitWithoutProjectId_isRejected() {
        ApiResponse r = requestWithoutProject(HttpMethod.POST,
                "/api/profile-agent/risks/submit-suggestions" + RANGE,
                token(USER_ADMIN), null);
        assertStatus(r, 422);
    }
}
