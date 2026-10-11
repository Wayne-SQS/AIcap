package com.aicap.contract;

import com.aicap.service.AssignmentSuggestionService;
import com.aicap.service.PlanningAnalysisService;
import com.aicap.service.PlanningProposalExecutionService;
import com.aicap.service.PlanningProposalReviewService;
import com.aicap.service.RefinementAnalysisService;
import com.aicap.service.RefinementProposalExecutionService;
import com.aicap.service.RefinementProposalReviewService;
import com.aicap.service.RetroAnalysisService;
import com.aicap.service.RetroProposalExecutionService;
import com.aicap.service.RetroProposalReviewService;
import com.aicap.service.ReviewAnalysisService;
import com.aicap.service.ReviewProposalExecutionService;
import com.aicap.service.ReviewProposalReviewService;
import com.aicap.service.ReviewQueueHydrationService;
import com.aicap.service.ReviewQueueIndexService;
import com.aicap.service.StatusAnalysisService;
import com.aicap.service.StatusProposalExecutionService;
import com.aicap.service.StatusProposalReviewService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 审核队列批量补全(details)必须与它替代的那些详情端点逐字段一致 ——
 * 客户端因此可以在两条路径之间切换而不改变任何可观察行为。
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "spring.datasource.url=jdbc:mysql://127.0.0.1:3307/aicap_java_test?useUnicode=true&characterEncoding=utf8&serverTimezone=Asia/Shanghai&allowPublicKeyRetrieval=true&useSSL=false",
        "spring.datasource.username=aiguanli",
        "spring.datasource.password=aiguanli-2026",
        "aicap.llm.agent-worker-enabled=false",
        "spring.sql.init.data-locations=classpath:db/reset_test_data.sql"
})
class ReviewQueueHydrationContractTest extends ContractTestSupport {
    private static final List<String> FAMILIES = List.of("status", "planning", "review", "retro", "refinement");
    private static final String TITLE = "水合批次测试会议";

    @Autowired private JdbcTemplate jdbc;
    @Autowired private ObjectMapper mapper;
    @Autowired private ReviewQueueIndexService index;
    @Autowired private ReviewQueueHydrationService hydration;
    @Autowired private StatusAnalysisService statusAnalyses;
    @Autowired private PlanningAnalysisService planningAnalyses;
    @Autowired private ReviewAnalysisService reviewAnalyses;
    @Autowired private RetroAnalysisService retroAnalyses;
    @Autowired private RefinementAnalysisService refinementAnalyses;
    @Autowired private StatusProposalReviewService statusReviews;
    @Autowired private PlanningProposalReviewService planningReviews;
    @Autowired private ReviewProposalReviewService reviewReviews;
    @Autowired private RetroProposalReviewService retroReviews;
    @Autowired private RefinementProposalReviewService refinementReviews;
    @Autowired private StatusProposalExecutionService statusExecutions;
    @Autowired private PlanningProposalExecutionService planningExecutions;
    @Autowired private ReviewProposalExecutionService reviewExecutions;
    @Autowired private RetroProposalExecutionService retroExecutions;
    @Autowired private RefinementProposalExecutionService refinementExecutions;
    @Autowired private AssignmentSuggestionService assignments;

    private final String meetingId = UUID.randomUUID().toString();
    private final String assignmentId = UUID.randomUUID().toString();
    private final String retroActionItemId = UUID.randomUUID().toString();
    private final Map<String, String> analysisIds = new LinkedHashMap<>();

    @AfterEach
    void cleanup() {
        jdbc.update("DELETE FROM meeting_retro_proposal_executions WHERE analysis_id IN (SELECT id FROM meeting_retro_analyses WHERE meeting_id=?)", meetingId);
        jdbc.update("DELETE FROM action_item_logs WHERE action_item_id=?", retroActionItemId);
        jdbc.update("DELETE FROM action_items WHERE meeting_id=?", meetingId);
        for (String family : FAMILIES) {
            jdbc.update("DELETE FROM meeting_" + family + "_proposal_executions WHERE analysis_id IN (SELECT id FROM meeting_" + family + "_analyses WHERE meeting_id=?)", meetingId);
            jdbc.update("DELETE FROM meeting_" + family + "_proposal_reviews WHERE analysis_id IN (SELECT id FROM meeting_" + family + "_analyses WHERE meeting_id=?)", meetingId);
            jdbc.update("DELETE FROM meeting_" + family + "_analyses WHERE meeting_id=?", meetingId);
        }
        jdbc.update("DELETE FROM meeting_assignment_analyses WHERE meeting_id=?", meetingId);
        jdbc.update("DELETE FROM meetings WHERE id=?", meetingId);
    }

    @Test
    void hydratedPageMatchesTheDetailEndpointsFieldForField() {
        seed();
        var page = index.page("all", "all", TITLE, null, null, null, 50);
        assertEquals(11, page.items().size(), "五个分析族各 2 个提案 + 1 条分配建议都应出现在一页里");
        Map<String, JsonNode> details = hydration.hydrate(page.items());
        assertEquals(6, details.size(), "按 source:meeting:analysis 分组后共 6 组");

        for (String family : FAMILIES) {
            String analysisId = analysisIds.get(family);
            JsonNode detail = details.get(ReviewQueueHydrationService.key(sourceKey(family), meetingId, analysisId));
            assertNotNull(detail, family + " 缺少详情");
            assertFalse(detail.has("error"), family + " 详情不应报错: " + detail);
            assertEquals(mapper.valueToTree(expectedAnalysis(family, analysisId)), detail.get("analysis"),
                    family + " analysis 形状与详情端点不一致");
            assertEquals(mapper.valueToTree(expectedReviewState(family, analysisId)), detail.get("reviewState"),
                    family + " reviewState 形状与详情端点不一致");
            assertEquals(mapper.valueToTree(expectedExecutions(family, analysisId)), detail.get("executions"),
                    family + " executions 形状与详情端点不一致");
        }

        JsonNode assignmentDetail = details.get(ReviewQueueHydrationService.key("assignment", meetingId, assignmentId));
        assertNotNull(assignmentDetail);
        assertEquals(mapper.valueToTree(assignments.get(meetingId, assignmentId)), assignmentDetail.get("assignment"),
                "assignment 形状与详情端点不一致");
    }

    @Test
    void httpPageCarriesDetailsNextToTheUnchangedReferenceFields() {
        seed();
        ApiResponse response = get("/api/review-queue?status=all&source=all&search=" + TITLE + "&limit=50", token(USER_VIEWER));
        assertStatus(response, 200);
        var json = response.json();
        assertEquals(11, json.path("items").size(), "items 引用列表不得因新增字段而改变");
        assertTrue(json.has("nextCursor"), "nextCursor 必须仍在响应里");
        assertEquals(6, json.path("details").size());
        for (JsonNode item : json.path("items")) {
            String key = ReviewQueueHydrationService.key(item.path("source").asText(),
                    item.path("meetingId").isNull() ? null : item.path("meetingId").asText(),
                    item.path("analysisId").asText());
            assertTrue(json.path("details").has(key), "缺少详情键 " + key);
        }
        assertEquals(mapper.valueToTree(expectedReviewState("retro", analysisIds.get("retro"))),
                json.path("details").path(ReviewQueueHydrationService.key("retro", meetingId, analysisIds.get("retro"))).path("reviewState"),
                "HTTP 载荷里的详情必须与服务端直连结果一致");
    }

    /**
     * 引用指向的分析在补全时已消失(并发删除)时,只该让这一条退回逐条报错语义,
     * 不得让整页 500。索引的 items 由分析表 join 得出,所以这里直接构造一个失效引用。
     */
    @Test
    void vanishedReferenceIsReportedPerItemInsteadOfFailingTheWholePage() {
        seed();
        var stale = new ReviewQueueIndexService.Item("daily", meetingId, TITLE, "no-such-analysis", "p-daily-0", 0,
                "2025-03-01T09:00:00", 1_000_000L, "pending", "not_started");
        var page = index.page("all", "all", TITLE, null, null, null, 50);
        var items = new ArrayList<>(page.items());
        items.add(stale);
        Map<String, JsonNode> details = hydration.hydrate(items);
        JsonNode staleDetail = details.get(ReviewQueueHydrationService.key("daily", meetingId, "no-such-analysis"));
        assertNotNull(staleDetail);
        assertEquals("状态分析记录不存在", staleDetail.path("error").asText());
        assertFalse(details.get(ReviewQueueHydrationService.key("daily", meetingId, analysisIds.get("status"))).has("error"),
                "一个引用失效不得拖垮同页其他来源");
    }

    private void seed() {
        jdbc.update("INSERT INTO meetings(id,title,transcript,created_by,created_at) VALUES(?,?,?,?,?)",
                meetingId, TITLE, "测试原文", 1, Timestamp.valueOf(LocalDateTime.of(2025, 3, 1, 9, 0)));
        int minute = 1;
        for (String family : FAMILIES) {
            String id = UUID.randomUUID().toString();
            analysisIds.put(family, id);
            String result = "{\"summary\":\"" + family + " 摘要\",\"proposed_actions\":[" +
                    "{\"proposal_id\":\"p-" + family + "-0\",\"reason\":\"待审\"}," +
                    "{\"proposal_id\":\"p-" + family + "-1\",\"reason\":\"已审\"}]}";
            jdbc.update("INSERT INTO meeting_" + family + "_analyses(id,meeting_id,submitted_by,client_request_id,status,transcript,result_json,snapshots_json,created_at) VALUES(?,?,?,?,?,?,?,?,?)",
                    id, meetingId, 1, "hydration-" + family, "completed", "测试原文", result, "[]",
                    Timestamp.valueOf(LocalDateTime.of(2025, 3, 1, 9, minute++)));
            jdbc.update("INSERT INTO meeting_" + family + "_proposal_reviews(analysis_id,proposal_index,proposal_id,decision,reason,reviewed_by,reviewed_at,original_json,approved_json,execution_status) VALUES(?,?,?,?,?,?,?,?,?,?)",
                    id, 1, "p-" + family + "-1", "approve", "确认通过", 1,
                    Timestamp.valueOf(LocalDateTime.of(2025, 3, 1, 10, 0)),
                    "{\"proposal_id\":\"p-" + family + "-1\",\"reason\":\"已审\"}",
                    "{\"proposal_id\":\"p-" + family + "-1\",\"changes\":{\"status\":2}}", "succeeded");
        }
        jdbc.update("INSERT INTO meeting_status_proposal_executions(analysis_id,proposal_index,proposal_id,story_id,previous_status,new_status,story_log_id,executed_by,executed_at) VALUES(?,?,?,?,?,?,?,?,?)",
                analysisIds.get("status"), 1, "p-status-1", "US01000001", 0, 2, 11, 1,
                Timestamp.valueOf(LocalDateTime.of(2025, 3, 1, 11, 0)));
        jdbc.update("INSERT INTO meeting_planning_proposal_executions(analysis_id,proposal_index,proposal_id,story_id,previous_sprint,new_sprint,story_log_id,executed_by,executed_at) VALUES(?,?,?,?,?,?,?,?,?)",
                analysisIds.get("planning"), 1, "p-planning-1", "US01000002", 1, 2, 12, 1,
                Timestamp.valueOf(LocalDateTime.of(2025, 3, 1, 11, 1)));
        jdbc.update("INSERT INTO meeting_review_proposal_executions(analysis_id,proposal_index,proposal_id,story_id,previous_status,new_status,story_log_id,executed_by,executed_at) VALUES(?,?,?,?,?,?,?,?,?)",
                analysisIds.get("review"), 1, "p-review-1", "US01000003", 1, 2, 13, 1,
                Timestamp.valueOf(LocalDateTime.of(2025, 3, 1, 11, 2)));
        jdbc.update("INSERT INTO meeting_refinement_proposal_executions(analysis_id,proposal_index,proposal_id,story_id,story_log_id,executed_by,executed_at) VALUES(?,?,?,?,?,?,?)",
                analysisIds.get("refinement"), 1, "p-refinement-1", "US01000004", 14, 1,
                Timestamp.valueOf(LocalDateTime.of(2025, 3, 1, 11, 3)));
        // retro 的执行挂行动项,需要先建 action_items / action_item_logs 才能满足外键
        jdbc.update("INSERT INTO action_items(id,meeting_id,analysis_id,proposal_index,title,description,status,created_by,created_at) VALUES(?,?,?,?,?,?,?,?,?)",
                retroActionItemId, meetingId, analysisIds.get("retro"), 1, "复盘行动项", "", "open", 1,
                Timestamp.valueOf(LocalDateTime.of(2025, 3, 1, 11, 4)));
        jdbc.update("INSERT INTO action_item_logs(action_item_id,log_type,approved_proposal_json,user_id,created_at) VALUES(?,?,?,?,?)",
                retroActionItemId, "create", "{}", 1, Timestamp.valueOf(LocalDateTime.of(2025, 3, 1, 11, 5)));
        Integer logId = jdbc.queryForObject("SELECT id FROM action_item_logs WHERE action_item_id=?", Integer.class, retroActionItemId);
        jdbc.update("INSERT INTO meeting_retro_proposal_executions(analysis_id,proposal_index,proposal_id,action_item_id,action_item_log_id,executed_by,executed_at) VALUES(?,?,?,?,?,?,?)",
                analysisIds.get("retro"), 1, "p-retro-1", retroActionItemId, logId, 1,
                Timestamp.valueOf(LocalDateTime.of(2025, 3, 1, 11, 6)));
        jdbc.update("INSERT INTO meeting_assignment_analyses(id,meeting_id,submitted_by,client_request_id,input_json,result_json,created_at) VALUES(?,?,?,?,?,?,?)",
                assignmentId, meetingId, 1, "hydration-assignment", "{}",
                "{\"status\":\"no_eligible_members\",\"candidates\":[]}",
                Timestamp.valueOf(LocalDateTime.of(2025, 3, 1, 9, 6)));
    }

    private static String sourceKey(String family) {
        return family.equals("status") ? "daily" : family;
    }

    private JsonNode expectedAnalysis(String family, String analysisId) {
        return mapper.valueToTree(switch (family) {
            case "status" -> statusAnalyses.get(meetingId, analysisId);
            case "planning" -> planningAnalyses.get(meetingId, analysisId);
            case "review" -> reviewAnalyses.get(meetingId, analysisId);
            case "retro" -> retroAnalyses.get(meetingId, analysisId);
            default -> refinementAnalyses.get(meetingId, analysisId);
        });
    }

    private JsonNode expectedReviewState(String family, String analysisId) {
        return mapper.valueToTree(switch (family) {
            case "status" -> statusReviews.list(meetingId, analysisId);
            case "planning" -> planningReviews.list(meetingId, analysisId);
            case "review" -> reviewReviews.list(meetingId, analysisId);
            case "retro" -> retroReviews.list(meetingId, analysisId);
            default -> refinementReviews.list(meetingId, analysisId);
        });
    }

    private JsonNode expectedExecutions(String family, String analysisId) {
        return mapper.valueToTree(switch (family) {
            case "status" -> statusExecutions.list(meetingId, analysisId);
            case "planning" -> planningExecutions.list(meetingId, analysisId);
            case "review" -> reviewExecutions.list(meetingId, analysisId);
            case "retro" -> retroExecutions.list(meetingId, analysisId);
            default -> refinementExecutions.list(meetingId, analysisId);
        });
    }
}
