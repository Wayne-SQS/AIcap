package com.aicap.contract;

import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.springframework.boot.test.context.SpringBootTest;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 会议 + 建议 + 审核契约:
 * - POST /api/meetings(title/transcript @NotBlank)→ 201;缺 title/transcript → 422;
 * - 建议提交:证据必须为会议原文连续片段(否则 422);缺 changes / changes 缺 title /
 *   changes.priority 非法 → 422(JB-04 级联);未知字段严格 422(JB-06);
 * - 幂等:同 (meeting,submitted_by,client_request_id) 同载荷 → 200 同一条;异载荷 → 409;
 * - 审核:approve 落 pool_items(A 命名空间);modify_and_approve 必须带 changes(JB-05);
 *   已审核同结论幂等、异结论 409;并发同结论仅一条副作用、并发异结论 200/409。
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "spring.datasource.url=jdbc:mysql://127.0.0.1:3307/aicap_java_test?useUnicode=true&characterEncoding=utf8&serverTimezone=Asia/Shanghai&allowPublicKeyRetrieval=true&useSSL=false",
        "spring.datasource.username=aiguanli",
        "spring.datasource.password=aiguanli-2026",
        "aicap.agent-worker-enabled=false",
        "spring.sql.init.data-locations=classpath:db/reset_test_data.sql"
})
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class MeetingSuggestionReviewContractTest extends ContractTestSupport {

    // ---------------- 会议 ----------------

    private ApiResponse createMeetingOk() {
        String title = uniq("CT会议");
        String transcript = "会议内容:讨论看板与需求管理,其中提到 " + EVIDENCE_FRAGMENT + "。";
        return post("/api/meetings", token(USER_ADMIN), json(map("title", title, "transcript", transcript)));
    }

    private String meetingId(ApiResponse m) {
        assertEquals(201, m.status(), m.body());
        return m.json().path("id").asText();
    }

    // ---------------- 建议 ----------------

    private ApiResponse submitSuggestion(String meetingId, String clientRequestId,
                                         String title, String priority, Map<String, Object> extra) {
        Map<String, Object> changes = map("title", title);
        if (priority != null) {
            changes.put("priority", priority);
        }
        Map<String, Object> body = map(
                "meeting_id", meetingId,
                "client_request_id", clientRequestId,
                "action", "pool.create",
                "origin", "manual",
                "evidence", EVIDENCE_FRAGMENT,
                "changes", changes);
        if (extra != null) {
            body.putAll(extra);
        }
        return post("/api/suggestions", token(USER_ADMIN), json(body));
    }

    private ApiResponse reviewSuggestion(String suggestionId, String decision, String changeTitle) {
        Map<String, Object> body = map("decision", decision);
        if (changeTitle != null) {
            body.put("changes", map("title", changeTitle, "priority", "Should"));
        }
        return post("/api/suggestions/" + suggestionId + "/review", token(USER_ADMIN), json(body));
    }

    private String cleanupPoolByTitle(String title) {
        ApiResponse pool = get("/api/pool", token(USER_ADMIN));
        String id = null;
        if (pool.json() != null) {
            for (JsonNode it : pool.json()) {
                if (title.equals(it.path("title").asText())) {
                    id = it.path("id").asText();
                    break;
                }
            }
        }
        if (id != null) {
            deletePoolItem(id);
        }
        return id;
    }

    // ==================== @Test ====================

    @Test
    void meeting_create_201_andListable() {
        ApiResponse m = createMeetingOk();
        String mid = meetingId(m);
        assertTrue(!mid.isEmpty());
        ApiResponse list = get("/api/meetings", token(USER_ADMIN));
        assertEquals(200, list.status(), list.body());
        boolean present = false;
        for (JsonNode it : list.json()) {
            if (mid.equals(it.path("id").asText())) {
                present = true;
            }
        }
        assertTrue(present, "创建后的会议应出现在 GET /api/meetings: " + list.body());
    }

    @Test
    void meeting_missingTitle_422() {
        ApiResponse r = post("/api/meetings", token(USER_ADMIN),
                json(map("transcript", "只有转写")));
        assertEquals(422, r.status(), r.body());
        assertTrue(r.json().has("detail"));
    }

    @Test
    void meeting_missingTranscript_422() {
        ApiResponse r = post("/api/meetings", token(USER_ADMIN),
                json(map("title", uniq("CT无转写"))));
        assertEquals(422, r.status(), r.body());
        assertTrue(r.json().has("detail"));
    }

    @Test
    void meeting_titleTooLong_422() {
        ApiResponse r = post("/api/meetings", token(USER_ADMIN),
                json(map("title", "题".repeat(201), "transcript", "正文")));
        assertEquals(422, r.status(), r.body());
    }

    @Test
    void meeting_unknownField_422_strictJson() {
        // MeetingIn 未加 @JsonIgnoreProperties;全局 failOnUnknownProperties → 未知字段 422(JB-06)
        ApiResponse r = post("/api/meetings", token(USER_ADMIN),
                json(map("title", uniq("CT多余字段"), "transcript", "正文", "bogus_field", 1)));
        assertEquals(422, r.status(), r.body());
        assertTrue(r.json().has("detail"));
    }

    @Test
    void suggestion_submit_ok_pending_andListable() {
        String mid = meetingId(createMeetingOk());
        String title = uniq("CT建议正向");
        ApiResponse r = submitSuggestion(mid, uniq("cr"), title, "Should", null);
        assertEquals(200, r.status(), r.body());
        String sid = r.json().path("id").asText();
        assertTrue(!sid.isEmpty());
        assertEquals("pending", r.json().path("status").asText());
        assertEquals(title, r.json().path("changes").path("title").asText());
        // 按 meeting 过滤可见
        ApiResponse list = get("/api/suggestions?meetingId=" + mid, token(USER_ADMIN));
        boolean present = false;
        for (JsonNode it : list.json()) {
            if (sid.equals(it.path("id").asText())) {
                present = true;
            }
        }
        assertTrue(present, "提交后应在 GET /api/suggestions?meetingId= 中可见: " + list.body());
    }

    @Test
    void suggestion_submit_meetingNotFound_404() {
        // 36 位 UUID 形状但库中不存在的 meeting → 服务层 404(避免触发 @Size(max=36) 的 422)
        ApiResponse r = submitSuggestion("00000000-0000-0000-0000-000000000001",
                uniq("cr"), uniq("CT缺会议"), "Could", null);
        assertEquals(404, r.status(), r.body());
        assertTrue(r.json().has("detail"));
    }

    @Test
    void suggestion_submit_evidenceNotInTranscript_422() {
        // 会议转写不含 EVIDENCE_FRAGMENT
        ApiResponse m = post("/api/meetings", token(USER_ADMIN),
                json(map("title", uniq("CT异转写"), "transcript", "本场只讨论无关于建议的日常事务安排。")));
        String mid = meetingId(m);
        ApiResponse r = submitSuggestion(mid, uniq("cr"), uniq("CT伪证据"), "Could", null);
        assertEquals(422, r.status(), r.body());
        assertTrue(r.json().has("detail"));
    }

    @Test
    void suggestion_submit_missingChanges_422() {
        String mid = meetingId(createMeetingOk());
        Map<String, Object> body = map(
                "meeting_id", mid,
                "client_request_id", uniq("cr"),
                "action", "pool.create",
                "origin", "manual",
                "evidence", EVIDENCE_FRAGMENT);
        ApiResponse r = post("/api/suggestions", token(USER_ADMIN), json(body));
        assertEquals(422, r.status(), r.body());
        assertTrue(r.json().has("detail"));
    }

    @Test
    void suggestion_submit_changesMissingTitle_422() {
        String mid = meetingId(createMeetingOk());
        Map<String, Object> body = map(
                "meeting_id", mid,
                "client_request_id", uniq("cr"),
                "action", "pool.create",
                "origin", "manual",
                "evidence", EVIDENCE_FRAGMENT,
                "changes", map("priority", "Should"));
        ApiResponse r = post("/api/suggestions", token(USER_ADMIN), json(body));
        assertEquals(422, r.status(), r.body());
        assertTrue(r.json().has("detail"));
    }

    @Test
    void suggestion_submit_changesInvalidPriority_422() {
        String mid = meetingId(createMeetingOk());
        ApiResponse r = submitSuggestion(mid, uniq("cr"), uniq("CT坏优先级"), "Whenever", null);
        assertEquals(422, r.status(), r.body());
        assertTrue(r.json().has("detail"));
    }

    @Test
    void suggestion_submit_unknownField_422_strictJson() {
        String mid = meetingId(createMeetingOk());
        ApiResponse r = submitSuggestion(mid, uniq("cr"), uniq("CT多余字段"), "Should",
                map("bogus_field", "x"));
        assertEquals(422, r.status(), r.body());
        assertTrue(r.json().has("detail"));
    }

    @Test
    void suggestion_submit_idempotent_sameSuggestionReturned() {
        String mid = meetingId(createMeetingOk());
        String cr = uniq("cr");
        String title = uniq("CT幂等");
        ApiResponse r1 = submitSuggestion(mid, cr, title, "Could", null);
        assertEquals(200, r1.status(), r1.body());
        ApiResponse r2 = submitSuggestion(mid, cr, title, "Could", null);
        assertEquals(200, r2.status(), r2.body());
        assertEquals(r1.json().path("id").asText(), r2.json().path("id").asText(),
                "同 key 重复提交应返回原建议");
        assertEquals("pending", r2.json().path("status").asText());
    }

    @Test
    void suggestion_submit_sameKeyDifferentPayload_409() {
        String mid = meetingId(createMeetingOk());
        String cr = uniq("cr");
        ApiResponse r1 = submitSuggestion(mid, cr, uniq("CT初版"), "Could", null);
        assertEquals(200, r1.status(), r1.body());
        ApiResponse r2 = submitSuggestion(mid, cr, uniq("CT改动版"), "Should", null);
        assertEquals(409, r2.status(), r2.body());
        assertTrue(r2.json().has("detail"), "409 应为 {\"detail\":...}: " + r2.body());
    }

    @Test
    void review_approve_landsPoolItem_andSecondApproveIdempotent() {
        String mid = meetingId(createMeetingOk());
        String title = uniq("CT审批落池");
        ApiResponse s = submitSuggestion(mid, uniq("cr"), title, "Should", null);
        String sid = s.json().path("id").asText();
        String poolItemId = null;
        try {
            ApiResponse rev = reviewSuggestion(sid, "approve", null);
            assertEquals(200, rev.status(), rev.body());
            assertEquals("approved", rev.json().path("status").asText());
            assertEquals("succeeded", rev.json().path("execution_status").asText());
            poolItemId = rev.json().path("pool_item_id").asText();
            assertTrue(!poolItemId.isEmpty(), "approve 后应有 pool_item_id");
            // 第二次同结论 approve → 幂等 200,不产生第二条副作用
            ApiResponse rev2 = reviewSuggestion(sid, "approve", null);
            assertEquals(200, rev2.status(), rev2.body());
            assertEquals(1, poolCountByTitle(title, token(USER_ADMIN)),
                    "同结论幂等应只有 1 条落池: " + title);
            // 池内确实存在该条目且标题一致
            ApiResponse pool = get("/api/pool", token(USER_ADMIN));
            boolean found = false;
            for (JsonNode it : pool.json()) {
                if (poolItemId.equals(it.path("id").asText())
                        && title.equals(it.path("title").asText())) {
                    found = true;
                }
            }
            assertTrue(found, "池中应有 approve 落库条目: " + pool.body());
        } finally {
            if (poolItemId != null && !poolItemId.isEmpty()) {
                deletePoolItem(poolItemId);
            }
        }
    }

    @Test
    void review_modifyAndApprove_withChanges_landsNewTitle() {
        String mid = meetingId(createMeetingOk());
        String originalTitle = uniq("CT改审前");
        ApiResponse s = submitSuggestion(mid, uniq("cr"), originalTitle, "Could", null);
        String sid = s.json().path("id").asText();
        String changedTitle = uniq("CT改审后");
        String poolItemId = null;
        try {
            ApiResponse rev = reviewSuggestion(sid, "modify_and_approve", changedTitle);
            assertEquals(200, rev.status(), rev.body());
            assertEquals("approved", rev.json().path("status").asText());
            poolItemId = rev.json().path("pool_item_id").asText();
            assertTrue(!poolItemId.isEmpty());
            // 落池标题应为修改后的 title
            ApiResponse pool = get("/api/pool", token(USER_ADMIN));
            boolean found = false;
            for (JsonNode it : pool.json()) {
                if (poolItemId.equals(it.path("id").asText())
                        && changedTitle.equals(it.path("title").asText())) {
                    found = true;
                }
            }
            assertTrue(found, "modify_and_approve 落池标题应为修改后: " + pool.body());
        } finally {
            if (poolItemId != null && !poolItemId.isEmpty()) {
                deletePoolItem(poolItemId);
            }
        }
    }

    @Test
    void review_modifyAndApprove_missingChanges_422() {
        String mid = meetingId(createMeetingOk());
        ApiResponse s = submitSuggestion(mid, uniq("cr"), uniq("CT改审缺载荷"), "Could", null);
        String sid = s.json().path("id").asText();
        // modify_and_approve 必须带 changes → 422(JB-05)
        ApiResponse rev = post("/api/suggestions/" + sid + "/review", token(USER_ADMIN),
                json(map("decision", "modify_and_approve")));
        assertEquals(422, rev.status(), rev.body());
        assertTrue(rev.json().has("detail"));
        // 建议保持 pending
        ApiResponse after = get("/api/suggestions/" + sid, token(USER_ADMIN));
        assertEquals(200, after.status(), after.body());
        assertEquals("pending", after.json().path("status").asText());
    }

    @Test
    void review_reject_noPoolSideEffect() {
        String mid = meetingId(createMeetingOk());
        String title = uniq("CT驳回");
        ApiResponse s = submitSuggestion(mid, uniq("cr"), title, "Could", null);
        String sid = s.json().path("id").asText();
        ApiResponse rev = reviewSuggestion(sid, "reject", null);
        assertEquals(200, rev.status(), rev.body());
        assertEquals("rejected", rev.json().path("status").asText());
        assertEquals("not_needed", rev.json().path("execution_status").asText());
        assertEquals(0, poolCountByTitle(title, token(USER_ADMIN)), "驳回不应落池");
    }

    @Test
    void review_alreadyApproved_differentConclusion_409() {
        String mid = meetingId(createMeetingOk());
        String title = uniq("CT异结论");
        ApiResponse s = submitSuggestion(mid, uniq("cr"), title, "Should", null);
        String sid = s.json().path("id").asText();
        String poolItemId = null;
        try {
            ApiResponse approve = reviewSuggestion(sid, "approve", null);
            assertEquals(200, approve.status(), approve.body());
            poolItemId = approve.json().path("pool_item_id").asText();
            ApiResponse reject = reviewSuggestion(sid, "reject", null);
            assertEquals(409, reject.status(), "已审核后异结论应 409: " + reject.body());
            assertTrue(reject.json().has("detail"), "409 应为 {\"detail\":...}: " + reject.body());
        } finally {
            if (poolItemId != null && !poolItemId.isEmpty()) {
                deletePoolItem(poolItemId);
            }
        }
    }

    @Test
    void review_concurrentSameConclusion_singleSideEffect() {
        String mid = meetingId(createMeetingOk());
        String title = uniq("CT并发同结论");
        ApiResponse s = submitSuggestion(mid, uniq("cr"), title, "Should", null);
        String sid = s.json().path("id").asText();
        try {
            List<ApiResponse> results = fireConcurrently(
                    () -> reviewSuggestion(sid, "approve", null),
                    () -> reviewSuggestion(sid, "approve", null));
            assertEquals(2, results.size());
            for (ApiResponse r : results) {
                assertEquals(200, r.status(), "同结论并发应都 200(幂等): " + r.body());
            }
            assertEquals(1, poolCountByTitle(title, token(USER_ADMIN)),
                    "并发同结论只能产生 1 条池副作用: " + title);
        } finally {
            cleanupPoolByTitle(title);
        }
    }

    @Test
    void review_concurrentDifferentConclusion_one200one409() {
        String mid = meetingId(createMeetingOk());
        String title = uniq("CT并发异结论");
        ApiResponse s = submitSuggestion(mid, uniq("cr"), title, "Should", null);
        String sid = s.json().path("id").asText();
        try {
            List<ApiResponse> results = fireConcurrently(
                    () -> reviewSuggestion(sid, "approve", null),
                    () -> reviewSuggestion(sid, "reject", null));
            assertEquals(2, results.size());
            int okCount = 0;
            int conflictCount = 0;
            String poolItemId = null;
            for (ApiResponse r : results) {
                if (r.status() == 200) {
                    okCount++;
                    if (r.json() != null) {
                        String pid = r.json().path("pool_item_id").asText();
                        if (!pid.isEmpty()) {
                            poolItemId = pid;
                        }
                    }
                } else if (r.status() == 409) {
                    conflictCount++;
                    assertTrue(r.json().has("detail"), "409 应为 {\"detail\":...}: " + r.body());
                }
            }
            assertEquals(1, okCount, "并发异结论恰一个成功: " + results);
            assertEquals(1, conflictCount, "并发异结论恰一个 409: " + results);
            // 若 approve 胜出则有一条落池,reject 胜出则无
            int poolCount = poolCountByTitle(title, token(USER_ADMIN));
            assertTrue(poolCount == 0 || poolCount == 1, "落池条数异常: " + poolCount);
        } finally {
            cleanupPoolByTitle(title);
        }
    }
}
