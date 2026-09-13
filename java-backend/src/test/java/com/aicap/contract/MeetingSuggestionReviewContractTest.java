package com.aicap.contract;

import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 会议 + 建议 + 审核契约:
 * - POST /api/meetings(title/transcript @NotBlank)→ 201;缺 title/transcript → 422;
 * - 建议提交:证据必须为会议原文连续片段(否则 422);缺 changes / changes 缺 title /
 *   changes.priority 非法 → 422(JB-04 级联);未知字段严格 422(JB-06);
 * - 幂等:同 (meeting,submitted_by,client_request_id) 同载荷 → 200 同一条;异载荷 → 409;
 * - 审核:approve 落 pool_items(A 命名空间);modify_and_approve 必须带 changes(JB-05);
 *   已审核同结论幂等、异结论 409;并发同结论仅一条副作用、并发异结论 200/409;
 * - 会议删除(FE-D03,本文件末尾 3 条):DELETE /api/meetings/{id} 200 且级联清干净
 *   (建议/审核载荷/分析任务及其事件/音频元数据与落盘文件)、404 未找到、403/401 权限。
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "spring.datasource.url=jdbc:mysql://127.0.0.1:3307/aicap_java_test?useUnicode=true&characterEncoding=utf8&serverTimezone=Asia/Shanghai&allowPublicKeyRetrieval=true&useSSL=false",
        "spring.datasource.username=aiguanli",
        "spring.datasource.password=aiguanli-2026",
        "aicap.llm.agent-worker-enabled=false",
        // FE-D03 删除用例需要:①上传 mp3 落到 target/test-audio(与 MeetingAudioContractTest 一致,
        // 不污染运行目录 data/audio);②POST /runs 需要一个"已配置"的模型(否则 503),
        // worker 仍关闭,所以 run 只会停在 queued、不会真的调用模型。
        "aicap.audio.dir=target/test-audio",
        "aicap.llm.api-key=contract-test-key",
        "spring.sql.init.data-locations=classpath:db/reset_test_data.sql"
})
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class MeetingSuggestionReviewContractTest extends ContractTestSupport {

    /** 与 @SpringBootTest properties 的 aicap.audio.dir 一致(相对 java-backend 工作目录) */
    private static final String AUDIO_DIR = "target/test-audio";

    /** 本类所有用例创建的会议 id:删除接口上线后统一在 @AfterAll 清理,不留残留(FE-D03) */
    private final List<String> createdMeetingIds = new ArrayList<>();

    // ---------------- 会议 ----------------

    private ApiResponse createMeetingOk() {
        String title = uniq("CT会议");
        String transcript = "会议内容:讨论看板与需求管理,其中提到 " + EVIDENCE_FRAGMENT + "。";
        return post("/api/meetings", token(USER_ADMIN), json(map("title", title, "transcript", transcript)));
    }

    /** 断言创建成功并登记会议 id(本类所有建会路径都经过这里,保证 @AfterAll 能清干净) */
    private String meetingId(ApiResponse m) {
        assertEquals(201, m.status(), m.body());
        String id = m.json().path("id").asText();
        createdMeetingIds.add(id);
        return id;
    }

    /** 本类用例结束时清掉自己造的会议(200 已删 / 404 先前已删,两种都算无残留) */
    @AfterAll
    void deleteMeetingsCreatedByThisClass() {
        for (String id : createdMeetingIds) {
            ApiResponse r = delete("/api/meetings/" + id, token(USER_ADMIN));
            assertTrue(r.status() == 200 || r.status() == 404,
                    "清理本类会议失败 " + id + ": " + r.status() + " " + r.body());
        }
        createdMeetingIds.clear();
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

    // ==================== 会议删除(FE-D03) ====================

    /** 与 MeetingAudioContractTest 相同的最小合法 mp3:ID3v2 头 + MPEG 帧同步(0xFFFB90 64) */
    private static byte[] tinyMp3() {
        byte[] b = new byte[2048];
        b[0] = 'I';
        b[1] = 'D';
        b[2] = '3';
        b[3] = 3;
        b[10] = (byte) 0xFF;
        b[11] = (byte) 0xFB;
        b[12] = (byte) 0x90;
        b[13] = 0x64;
        return b;
    }

    /** multipart 上传一条 mp3(写法照抄 MeetingAudioContractTest) */
    private ApiResponse uploadAudio(String meetingId, String token) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.MULTIPART_FORM_DATA);
        if (token != null) {
            headers.setBearerAuth(token);
        }
        MultiValueMap<String, Object> body = new LinkedMultiValueMap<>();
        body.add("file", new ByteArrayResource(tinyMp3()) {
            @Override
            public String getFilename() {
                return "会议录音-删除契约.mp3";
            }
        });
        body.add("source", "recorder");
        body.add("duration_ms", "2000");
        HttpEntity<MultiValueMap<String, Object>> entity = new HttpEntity<>(body, headers);
        return toApi(rest.exchange("/api/meetings/" + meetingId + "/audio",
                HttpMethod.POST, entity, byte[].class));
    }

    private ApiResponse toApi(ResponseEntity<byte[]> resp) {
        byte[] raw = resp.getBody();
        String body = raw == null ? "" : new String(raw, StandardCharsets.UTF_8);
        JsonNode json = null;
        if (!body.isBlank()) {
            try {
                json = om.readTree(body);
            } catch (Exception ignored) {
                // 非 JSON 响应
            }
        }
        return new ApiResponse(resp.getStatusCode().value(), json, body);
    }

    /**
     * 在 aicap.audio.dir 目录下找本次上传的落盘文件(服务端命名为 {@code <audioId>.mp3},
     * 相对根目录按 {@code yyyy/MM} 分目录,这里递归查找;目录不存在返回 null)。
     * 路径解析与服务端一致:相对 java-backend 工作目录(surefire 的 basedir)。
     */
    private Path findStoredAudio(String audioId) {
        Path root = Paths.get(AUDIO_DIR).toAbsolutePath().normalize();
        if (!Files.isDirectory(root)) {
            return null;
        }
        try (Stream<Path> walk = Files.walk(root)) {
            return walk.filter(Files::isRegularFile)
                    .filter(p -> p.getFileName().toString().startsWith(audioId))
                    .findFirst()
                    .orElse(null);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private boolean meetingListed(String meetingId) {
        ApiResponse list = get("/api/meetings", token(USER_ADMIN));
        assertEquals(200, list.status(), list.body());
        for (JsonNode it : list.json()) {
            if (meetingId.equals(it.path("id").asText())) {
                return true;
            }
        }
        return false;
    }

    /** 兜底清理:已删则 404,两种都算"无残留"(不让清理异常掩盖主断言的失败原因) */
    private void cleanupMeeting(String meetingId) {
        ApiResponse r = delete("/api/meetings/" + meetingId, token(USER_ADMIN));
        assertTrue(r.status() == 200 || r.status() == 404,
                "清理会议失败(期望 200 或 404): " + r.status() + " " + r.body());
    }

    /** 22. 删除会议:级联清干净建议/审核载荷/分析任务及其事件/音频元数据与落盘文件,已落池条目保留 */
    @Test
    void meeting_delete_ok_cascadesEverything() {
        String mid = meetingId(createMeetingOk());
        String title = uniq("CT删除级联");
        String poolItemId = null;
        try {
            // 造数据 1:一条建议 + 审核通过(产生 approval payload 与独立的需求池条目)
            ApiResponse s = submitSuggestion(mid, uniq("cr"), title, "Should", null);
            assertEquals(200, s.status(), s.body());
            String suggestionId = s.json().path("id").asText();
            ApiResponse rev = reviewSuggestion(suggestionId, "approve", null);
            assertEquals(200, rev.status(), rev.body());
            poolItemId = rev.json().path("pool_item_id").asText();
            assertTrue(!poolItemId.isEmpty(), "approve 后应有 pool_item_id: " + rev.body());

            // 造数据 2:上传一条 mp3(落盘 target/test-audio)
            ApiResponse up = uploadAudio(mid, token(USER_ADMIN));
            assertEquals(200, up.status(), up.body());
            String audioId = up.json().path("id").asText();
            assertNotNull(findStoredAudio(audioId), "上传后音频文件应已落盘: " + audioId);

            // 造数据 3:发起一次分析 → run + queued 事件(worker 关闭,不会真的调用模型)
            ApiResponse run = post("/api/meetings/" + mid + "/runs", token(USER_ADMIN), null);
            assertEquals(200, run.status(), run.body());
            String runId = run.json().path("id").asText();
            assertEquals("queued", run.json().path("status").asText(), run.body());

            // 删除前确认三类从属数据都真实存在(否则"删除后为空"没有意义)
            assertEquals(1, get("/api/suggestions?meetingId=" + mid, token(USER_ADMIN)).json().size());
            assertEquals(1, get("/api/meetings/" + mid + "/audio", token(USER_ADMIN)).json().size());
            assertEquals(1, get("/api/meetings/" + mid + "/runs", token(USER_ADMIN)).json().size());
            assertTrue(meetingListed(mid), "删除前会议应在列表中");

            // 删除
            ApiResponse del = delete("/api/meetings/" + mid, token(USER_ADMIN));
            assertEquals(200, del.status(), del.body());
            assertEquals(mid, del.json().path("id").asText(), del.body());
            assertTrue(del.json().path("deleted").asBoolean(), del.body());
            assertEquals(1, del.json().path("audio_deleted").asInt(-1), del.body());
            assertEquals(1, del.json().path("suggestions_deleted").asInt(-1), del.body());
            assertEquals(1, del.json().path("runs_deleted").asInt(-1), del.body());

            // 会议本体消失
            assertEquals(404, get("/api/meetings/" + mid, token(USER_ADMIN)).status(), "会议应已删除");
            assertFalse(meetingListed(mid), "GET /api/meetings 中不应再有该会议");
            // 分析任务消失(事件外键先删才可能删得掉 run)→ GET /api/agent-runs 404
            assertEquals(404, get("/api/meetings/" + mid + "/runs", token(USER_ADMIN)).status());
            assertEquals(404, get("/api/agent-runs/" + runId, token(USER_ADMIN)).status(),
                    "分析任务行应已随会议删除");
            // 建议与审核载荷消失
            assertEquals(0, get("/api/suggestions?meetingId=" + mid, token(USER_ADMIN)).json().size(),
                    "该会议的建议应清空");
            assertEquals(404, get("/api/suggestions/" + suggestionId, token(USER_ADMIN)).status());
            // 音频元数据与落盘文件消失
            assertEquals(404, get("/api/meetings/" + mid + "/audio", token(USER_ADMIN)).status());
            assertNull(findStoredAudio(audioId), "音频文件必须从 aicap.audio.dir 消失: " + audioId);
            // 已审核通过落库的需求池条目是独立产物,不随会议删除
            assertEquals(1, poolCountByTitle(title, token(USER_ADMIN)),
                    "审批通过的需求池条目不得随会议删除: " + title);
        } finally {
            cleanupMeeting(mid);
            cleanupPoolByTitle(title);
        }
    }

    /** 23. 删除不存在的会议 → 404 + detail */
    @Test
    void meeting_delete_notFound_404() {
        String missing = "00000000-0000-0000-0000-0000000000ff";
        ApiResponse r = delete("/api/meetings/" + missing, token(USER_ADMIN));
        assertEquals(404, r.status(), r.body());
        assertTrue(r.json().has("detail"), "404 响应应为 {\"detail\":...}: " + r.body());
        assertEquals("会议不存在", detail(r), r.body());
    }

    /** 24. 删除权限:viewer/member 403,无 token 401,且越权尝试后会议仍然存在 */
    @Test
    void meeting_delete_forbidden() {
        String mid = meetingId(createMeetingOk());
        try {
            // 仅 admin/owner:viewer 与 member 一律 403(与故事删除、音频删除同口径)
            assertStatus(delete("/api/meetings/" + mid, token(USER_VIEWER)), 403);
            assertStatus(delete("/api/meetings/" + mid, token(USER_MEMBER)), 403);
            // 越权尝试后会议必须仍然存在
            ApiResponse still = get("/api/meetings/" + mid, token(USER_ADMIN));
            assertEquals(200, still.status(), "越权删除不得删掉会议: " + still.body());
            assertEquals(mid, still.json().path("id").asText(), still.body());
            assertTrue(meetingListed(mid), "越权删除后会议仍应在列表中");

            // 无 token → 401
            assertStatus(delete("/api/meetings/" + mid, null), 401);

            // 权限校验先于存在性校验:viewer 删不存在的会议同样 403(不泄露存在性)
            assertStatus(delete("/api/meetings/00000000-0000-0000-0000-0000000000fe", token(USER_VIEWER)), 403);

            // 三轮越权/未鉴权尝试后,会议依旧存在
            assertEquals(200, get("/api/meetings/" + mid, token(USER_ADMIN)).status(),
                    "越权与未鉴权尝试后会议仍应存在");
        } finally {
            cleanupMeeting(mid);
        }
    }
}
