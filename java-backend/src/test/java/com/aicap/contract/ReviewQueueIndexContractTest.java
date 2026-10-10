package com.aicap.contract;

import com.aicap.service.ReviewQueueIndexService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "spring.datasource.url=jdbc:mysql://127.0.0.1:3307/aicap_java_test?useUnicode=true&characterEncoding=utf8&serverTimezone=Asia/Shanghai&allowPublicKeyRetrieval=true&useSSL=false",
        "spring.datasource.username=aiguanli",
        "spring.datasource.password=aiguanli-2026",
        "aicap.llm.agent-worker-enabled=false",
        "spring.sql.init.data-locations=classpath:db/reset_test_data.sql"
})
class ReviewQueueIndexContractTest extends ContractTestSupport {
    @Autowired private JdbcTemplate jdbc;
    @Autowired private ReviewQueueIndexService index;
    private final String meetingId = UUID.randomUUID().toString();
    private final List<String> analysisIds = new ArrayList<>();
    private final String suggestionId = "S" + (100000 + Math.abs(meetingId.hashCode() % 800000));
    private final List<String> suggestionIds = new ArrayList<>();

    @AfterEach
    void cleanup() {
        for (String source : List.of("status", "planning", "review", "retro", "refinement")) {
            String table = "meeting_" + source + "_analyses";
            String reviewTable = "meeting_" + source + "_proposal_reviews";
            jdbc.update("DELETE r FROM " + reviewTable + " r JOIN " + table + " a ON a.id=r.analysis_id WHERE a.meeting_id=?", meetingId);
            jdbc.update("DELETE FROM " + table + " WHERE meeting_id=?", meetingId);
        }
        jdbc.update("DELETE FROM meeting_assignment_analyses WHERE meeting_id=?", meetingId);
        jdbc.update("DELETE FROM meeting_suggestion_records WHERE meeting_id=?", meetingId);
        for (String id : suggestionIds) jdbc.update("DELETE FROM suggestions WHERE id=?", id);
        jdbc.update("DELETE FROM meetings WHERE id=?", meetingId);
    }

    @Test
    void pagesAllSourcesAndKeepsOldPendingProposals() {
        ReviewQueueIndexService.Summary before = index.summary();
        jdbc.update("INSERT INTO meetings(id,title,transcript,created_by,created_at) VALUES(?,?,?,?,?)",
                meetingId, "分页队列测试会议", "测试原文", 1, Timestamp.valueOf(LocalDateTime.of(2025,1,1,10,0)));
        int minute = 1;
        for (String source : List.of("status", "planning", "review", "retro", "refinement")) {
            String id = UUID.randomUUID().toString();
            analysisIds.add(id);
            String table = "meeting_" + source + "_analyses";
            jdbc.update("INSERT INTO " + table + "(id,meeting_id,submitted_by,client_request_id,status,transcript,result_json,snapshots_json,created_at) VALUES(?,?,?,?,?,?,?,?,?)",
                    id, meetingId, 1, "queue-" + source, "pending", "测试原文",
                    "{\"proposed_actions\":[{\"proposal_id\":\"p-" + source + "\"}]}", "[]",
                    Timestamp.valueOf(LocalDateTime.of(2025,1,1,10,minute++)));
        }
        String planningId = analysisIds.get(1);
        jdbc.update("INSERT INTO meeting_planning_proposal_reviews(analysis_id,proposal_index,proposal_id,decision,reason,reviewed_by,original_json,approved_json,execution_status) VALUES(?,?,?,?,?,?,?,?,?)",
                planningId, 0, "p-planning", "approve", "confirmed", 1, "{}", "{}", "not_started");
        String retroId = analysisIds.get(3);
        jdbc.update("INSERT INTO meeting_retro_proposal_reviews(analysis_id,proposal_index,proposal_id,decision,reason,reviewed_by,original_json,approved_json,execution_status) VALUES(?,?,?,?,?,?,?,?,?)",
                retroId, 0, "p-retro", "reject", "rejected", 1, "{}", null, "not_applicable");
        String assignmentId = UUID.randomUUID().toString();
        jdbc.update("INSERT INTO meeting_assignment_analyses(id,meeting_id,submitted_by,client_request_id,input_json,result_json,created_at) VALUES(?,?,?,?,?,?,?)",
                assignmentId, meetingId, 1, "queue-assignment", "{}", "{}",
                Timestamp.valueOf(LocalDateTime.of(2025,1,1,10,6)));
        jdbc.update("INSERT INTO suggestions(id,agent,kind,evidence,affected,note,change_json,status,created_at) VALUES(?,?,?,?,?,?,?,?,?)",
                suggestionId, "meeting", "meeting", "测试", "US01", "测试", "{}", "pending",
                Timestamp.valueOf(LocalDateTime.of(2025,1,1,10,7)));
        suggestionIds.add(suggestionId);
        jdbc.update("INSERT INTO meeting_suggestion_records(suggestion_id,meeting_id,client_request_id,request_hash,submitted_by,origin,reason,execution_status) VALUES(?,?,?,?,?,?,?,?)",
                suggestionId, meetingId, "queue-general", "test-hash", 1, "manual", "", "not_started");

        List<ReviewQueueIndexService.Item> all = new ArrayList<>();
        String cursor = null;
        for (int pageNumber = 0; pageNumber < 12; pageNumber++) {
            ReviewQueueIndexService.Page page = index.page("all", "all", null, null, null, cursor, 2);
            assertTrue(page.items().size() <= 2);
            all.addAll(page.items());
            cursor = page.nextCursor();
            if (cursor == null) break;
        }
        assertNull(cursor, "全来源游标未结束: " + all.stream().map(ReviewQueueIndexService.Item::proposalId).toList());
        assertEquals(7, all.size());
        assertEquals(7, new HashSet<>(all.stream().map(i -> i.source() + ":" + i.proposalId()).toList()).size());
        assertEquals(5, all.stream().filter(i -> i.status().equals("pending")).count());
        assertEquals("pending", all.getLast().status(), "较早的待审提案必须能翻页找到");
        assertEquals(1, index.page("reviewed", "planning", null, null, null, null, 20).items().size());
        assertEquals(0, index.page("pending", "planning", null, null, null, null, 20).items().size());
        assertEquals(1, index.page("pending", "daily", "分页队列", null, null, null, 20).items().size());
        ReviewQueueIndexService.Summary summary = index.summary();
        assertEquals(before.pendingCount() + 5, summary.pendingCount());
        assertEquals(before.executionCount() + 1, summary.executionCount());
        assertNotNull(summary.latest());
        assertNotNull(summary.recentMeeting());
        assertThrows(RuntimeException.class, () -> index.page("all", "all", null, null, null, "invalid", 20));

        ApiResponse viewer = get("/api/review-queue?status=pending&limit=2", token(USER_VIEWER));
        assertStatus(viewer, 200);
        assertEquals(2, viewer.json().path("items").size());
        assertTrue(viewer.json().path("nextCursor").isTextual());
        assertStatus(get("/api/review-queue?limit=101", token(USER_VIEWER)), 422);
        assertStatus(get("/api/review-queue?limit=2", null), 401);
        ApiResponse viewerSummary = get("/api/review-queue/summary", token(USER_VIEWER));
        assertStatus(viewerSummary, 200);
        assertEquals(summary.pendingCount(), viewerSummary.json().path("pendingCount").asLong());
        assertTrue(viewerSummary.json().path("recentMeeting").path("title").isTextual());
        assertStatus(get("/api/review-queue/summary", null), 401);
    }

    @Test
    void summaryCountsRemainingProposalsAndApprovedExecutions() {
        ReviewQueueIndexService.Summary before = index.summary();
        jdbc.update("INSERT INTO meetings(id,title,transcript,created_by,created_at) VALUES(?,?,?,?,?)",
                meetingId, "多提案汇总测试", "测试原文", 1, Timestamp.valueOf(LocalDateTime.of(2025,1,2,10,0)));
        String analysisId = UUID.randomUUID().toString();
        jdbc.update("INSERT INTO meeting_status_analyses(id,meeting_id,submitted_by,client_request_id,status,transcript,result_json,snapshots_json,created_at) VALUES(?,?,?,?,?,?,?,?,?)",
                analysisId, meetingId, 1, "queue-multi-proposal", "completed", "测试原文",
                "{\"proposed_actions\":[{\"proposal_id\":\"one\"},{\"proposal_id\":\"two\"},{\"proposal_id\":\"three\"}]}",
                "[]", Timestamp.valueOf(LocalDateTime.of(2025,1,2,10,1)));
        jdbc.update("INSERT INTO meeting_status_proposal_reviews(analysis_id,proposal_index,proposal_id,decision,reason,reviewed_by,original_json,approved_json,execution_status) VALUES(?,?,?,?,?,?,?,?,?)",
                analysisId, 0, "one", "approve", "confirmed", 1, "{}", "{}", "not_started");
        jdbc.update("INSERT INTO meeting_status_proposal_reviews(analysis_id,proposal_index,proposal_id,decision,reason,reviewed_by,original_json,approved_json,execution_status) VALUES(?,?,?,?,?,?,?,?,?)",
                analysisId, 1, "two", "reject", "rejected", 1, "{}", null, "not_applicable");

        ReviewQueueIndexService.Summary summary = index.summary();
        assertEquals(before.pendingCount() + 1, summary.pendingCount());
        assertEquals(before.executionCount() + 1, summary.executionCount());
        assertEquals(1, index.page("pending", "daily", null, null, null, null, 10).items().size());
        jdbc.update("UPDATE meeting_status_proposal_reviews SET execution_status='succeeded' WHERE analysis_id=? AND proposal_index=0", analysisId);
        assertEquals(before.executionCount(), index.summary().executionCount());
    }

    @Test
    void cursorUsesBinaryOrderForMixedCaseIdsAtTheSameTimestamp() {
        jdbc.update("INSERT INTO meetings(id,title,transcript,created_by,created_at) VALUES(?,?,?,?,?)",
                meetingId, "同秒游标测试", "测试", 1, Timestamp.valueOf(LocalDateTime.of(2025,1,1,10,0)));
        Timestamp time = Timestamp.valueOf(LocalDateTime.of(2025,1,1,10,1));
        for (String id : List.of("SAzz00001", "SaAA00001", "SBaa00001")) {
            suggestionIds.add(id);
            jdbc.update("INSERT INTO suggestions(id,agent,kind,evidence,affected,note,change_json,status,created_at) VALUES(?,?,?,?,?,?,?,?,?)",
                    id, "meeting", "meeting", "测试", "US01", "测试", "{}", "pending", time);
            jdbc.update("INSERT INTO meeting_suggestion_records(suggestion_id,meeting_id,client_request_id,request_hash,submitted_by,origin,reason,execution_status) VALUES(?,?,?,?,?,?,?,?)",
                    id, meetingId, "queue-" + id, "test-hash", 1, "manual", "", "not_started");
        }
        String cursor = null;
        List<String> ids = new ArrayList<>();
        for (int pageNumber = 0; pageNumber < 8; pageNumber++) {
            var page = index.page("pending", "general", "同秒游标测试", null, null, cursor, 1);
            ids.addAll(page.items().stream().map(ReviewQueueIndexService.Item::proposalId).toList());
            cursor = page.nextCursor();
            if (cursor == null) break;
        }
        assertNull(cursor, "同秒游标未结束: " + ids);
        assertEquals(List.of("SaAA00001", "SBaa00001", "SAzz00001"), ids);
    }
}
