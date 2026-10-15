package com.aicap.contract;

import com.aicap.service.AssignmentSuggestionService;
import com.aicap.service.PlanningAnalysisService;
import com.aicap.service.PlanningProposalExecutionService;
import com.aicap.service.PlanningProposalReviewService;
import com.aicap.service.RefinementAnalysisService;
import com.aicap.service.RefinementProposalExecutionService;
import com.aicap.service.RefinementProposalReviewService;
import com.aicap.service.ReviewAnalysisService;
import com.aicap.service.ReviewProposalExecutionService;
import com.aicap.service.ReviewProposalReviewService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 独立 MySQL 测试库(aicap_java_test)上的有效契约测试:四类会议提案服务
 * (Planning / Review / Refinement / Assignment)必须按 (project_id,code) 定位故事,
 * 并以内部主键(internal_id)更新,story_logs 写全四列。
 *
 * 每个用例都在 P002 与 P099 中放入<b>同编号</b>故事,执行后断言只有 P002 变化、
 * P099 保持不变,以此证明多项目故事表下的隔离语义与旧单项目假设完全兼容。
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "spring.datasource.url=jdbc:mysql://127.0.0.1:3307/aicap_java_test?useUnicode=true&characterEncoding=utf8&serverTimezone=Asia/Shanghai&allowPublicKeyRetrieval=true&useSSL=false",
        "spring.datasource.username=aiguanli",
        "spring.datasource.password=aiguanli-2026",
        "aicap.llm.agent-worker-enabled=false",
        "spring.sql.init.data-locations=classpath:db/reset_test_data.sql"
})
class MeetingProposalProjectContractTest extends ContractTestSupport {
    private static final String PROJECT = "P002";
    private static final String OTHER_PROJECT = "P099";
    /** 种子里 id=3 的用户是"孙秋实"(member),分配建议的可用成员校验需要真实的成员账号。 */
    private static final int MEMBER_USER_ID = 3;

    @Autowired private JdbcTemplate jdbc;
    @Autowired private ObjectMapper mapper;
    @Autowired private PlanningAnalysisService planningAnalyses;
    @Autowired private PlanningProposalReviewService planningReviews;
    @Autowired private PlanningProposalExecutionService planningExecutions;
    @Autowired private ReviewAnalysisService reviewAnalyses;
    @Autowired private ReviewProposalReviewService reviewReviews;
    @Autowired private ReviewProposalExecutionService reviewExecutions;
    @Autowired private RefinementAnalysisService refinementAnalyses;
    @Autowired private RefinementProposalReviewService refinementReviews;
    @Autowired private RefinementProposalExecutionService refinementExecutions;
    @Autowired private AssignmentSuggestionService assignments;

    private final List<String> meetings = new ArrayList<>();
    /** 本用例创建/触碰过的故事编号,按 (project_id,code) 精确清理。 */
    private final List<String> storyCodes = new ArrayList<>();

    @AfterEach
    void cleanup() {
        for (String meetingId : meetings) {
            jdbc.update("DELETE FROM review_queue_proposals WHERE meeting_id=?", meetingId);
            for (String source : List.of("planning", "review", "refinement")) {
                String analyses = "meeting_" + source + "_analyses";
                jdbc.update("DELETE e FROM meeting_" + source + "_proposal_executions e JOIN " + analyses
                        + " a ON a.id=e.analysis_id WHERE a.meeting_id=?", meetingId);
                jdbc.update("DELETE r FROM meeting_" + source + "_proposal_reviews r JOIN " + analyses
                        + " a ON a.id=r.analysis_id WHERE a.meeting_id=?", meetingId);
                jdbc.update("DELETE FROM " + analyses + " WHERE meeting_id=?", meetingId);
            }
            jdbc.update("DELETE FROM meeting_assignment_analyses WHERE meeting_id=?", meetingId);
        }
        for (String code : storyCodes) {
            jdbc.update("DELETE FROM story_logs WHERE project_id=? AND story_id=?", PROJECT, code);
            jdbc.update("DELETE FROM story_logs WHERE project_id=? AND story_id=?", OTHER_PROJECT, code);
            jdbc.update("DELETE FROM stories WHERE project_id=? AND code=?", PROJECT, code);
            jdbc.update("DELETE FROM stories WHERE project_id=? AND code=?", OTHER_PROJECT, code);
        }
        for (String meetingId : meetings) jdbc.update("DELETE FROM meetings WHERE id=?", meetingId);
        meetings.clear();
        storyCodes.clear();
    }

    // ------------------------------------------------------------------
    // 夹具
    // ------------------------------------------------------------------

    private String newMeetingId() {
        String meetingId = UUID.randomUUID().toString();
        meetings.add(meetingId);
        return meetingId;
    }

    /** 8 位业务编号:与种子 US01..US37 不冲突,且必然大于任何已存在编号。 */
    private String newStoryCode(String meetingId) {
        String code = "US" + (10000000 + Math.abs(meetingId.hashCode() % 80000000));
        storyCodes.add(code);
        return code;
    }

    private void insertMeeting(String meetingId, String title, String transcript) {
        jdbc.update("INSERT INTO meetings(id,project_id,title,transcript,created_by,created_at) "
                + "VALUES(?,?,?,?,1,CURRENT_TIMESTAMP)", meetingId, PROJECT, title, transcript);
    }

    private void insertStory(String projectId, String code, String title, int sprint, int status) {
        jdbc.update("INSERT INTO stories(project_id,code,title,description,acceptance,priority,sprint,activity,status) "
                + "VALUES(?,?,?,'','','Must',?,1,?)", projectId, code, title, sprint, status);
    }

    private long internalId(String projectId, String code) {
        return jdbc.queryForObject("SELECT internal_id FROM stories WHERE project_id=? AND code=?",
                Long.class, projectId, code);
    }

    private void assertShadow(String source, String analysisId, String status, String executionStatus) {
        Map<String, Object> row = jdbc.queryForMap(
                "SELECT status,execution_status FROM review_queue_proposals WHERE source=? AND analysis_id=?",
                source, analysisId);
        assertEquals(status, row.get("status"));
        assertEquals(executionStatus, row.get("execution_status"));
    }

    // ------------------------------------------------------------------
    // Planning: update_story_sprint
    // ------------------------------------------------------------------

    @Test
    void planningProposalUpdatesOnlyOwnProjectStory() throws Exception {
        String meetingId = newMeetingId();
        String storyId = newStoryCode(meetingId);
        insertMeeting(meetingId, "规划隔离测试", "决定US13移至Sprint 2。\n\nUS14 开始开发。");
        insertStory(PROJECT, storyId, "规划测试故事", 1, 1);
        insertStory(OTHER_PROJECT, storyId, "其他项目规划故事", 3, 0);

        String body = """
                {"client_request_id":"planning-1","result":{"schema_version":"1.0","meeting_id":"%s",
                "meeting_type":"sprint_planning","summary":"登录调整排期","open_questions":[],"proposed_actions":[
                {"proposal_id":"p1","action":"update_story_sprint","story_id":"%s","expected":{"sprint":1},
                "changes":{"sprint":2},"reason":"确认Sprint调整","evidence":[{"segment_id":"S1","quote":"决定US13移至Sprint 2。"}]}]}}
                """.formatted(meetingId, storyId);

        String id = planningAnalyses.save(meetingId, mapper.readTree(body), 1).path("id").asText();
        assertShadow("planning", id, "pending", "not_started");
        planningReviews.review(meetingId, id, mapper.readTree(
                "{\"proposal_id\":\"p1\",\"decision\":\"approve\",\"reason\":\"确认调整\"}"), 1);
        assertShadow("planning", id, "approved", "not_started");
        planningExecutions.execute(meetingId, id, mapper.readTree("{\"proposal_id\":\"p1\"}"), 1);
        assertShadow("planning", id, "approved", "succeeded");

        assertEquals(2, jdbc.queryForObject("SELECT sprint FROM stories WHERE project_id=? AND code=?",
                Integer.class, PROJECT, storyId));
        assertEquals(3, jdbc.queryForObject("SELECT sprint FROM stories WHERE project_id=? AND code=?",
                Integer.class, OTHER_PROJECT, storyId));

        Map<String, Object> log = jdbc.queryForMap(
                "SELECT * FROM story_logs WHERE project_id=? AND story_id=?", PROJECT, storyId);
        assertEquals(PROJECT, log.get("project_id"));
        assertEquals("edit", log.get("log_type"));
        assertEquals(storyId, log.get("story_code_snapshot"));
        assertEquals(internalId(PROJECT, storyId), ((Number) log.get("story_internal_id")).longValue());
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM story_logs WHERE story_id=?",
                Integer.class, storyId) - 1, "只应有一条 P002 审计日志");
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM story_logs WHERE project_id=? AND story_id=?",
                Integer.class, OTHER_PROJECT, storyId));
    }

    // ------------------------------------------------------------------
    // Review: update_story_status
    // ------------------------------------------------------------------

    @Test
    void reviewProposalUpdatesOnlyOwnProjectStory() throws Exception {
        String meetingId = newMeetingId();
        String storyId = newStoryCode(meetingId);
        insertMeeting(meetingId, "验收隔离测试", "US13已验收通过。\n\nUS14 开始开发。");
        insertStory(PROJECT, storyId, "验收测试故事", 1, 1);
        insertStory(OTHER_PROJECT, storyId, "其他项目验收故事", 1, 0);

        String body = """
                {"client_request_id":"review-1","result":{"schema_version":"1.0","meeting_id":"%s",
                "meeting_type":"sprint_review","summary":"登录验收通过","open_questions":[],"proposed_actions":[
                {"proposal_id":"p1","action":"update_story_status","story_id":"%s","expected":{"status":1},
                "changes":{"status":2},"reason":"确认验收通过","evidence":[{"segment_id":"S1","quote":"US13已验收通过。"}]}]}}
                """.formatted(meetingId, storyId);

        String id = reviewAnalyses.save(meetingId, mapper.readTree(body), 1).path("id").asText();
        assertShadow("review", id, "pending", "not_started");
        reviewReviews.review(meetingId, id, mapper.readTree(
                "{\"proposal_id\":\"p1\",\"decision\":\"approve\",\"reason\":\"确认验收范围\"}"), 1);
        assertShadow("review", id, "approved", "not_started");
        reviewExecutions.execute(meetingId, id, mapper.readTree("{\"proposal_id\":\"p1\"}"), 1);
        assertShadow("review", id, "approved", "succeeded");

        assertEquals(2, jdbc.queryForObject("SELECT status FROM stories WHERE project_id=? AND code=?",
                Integer.class, PROJECT, storyId));
        assertEquals(0, jdbc.queryForObject("SELECT status FROM stories WHERE project_id=? AND code=?",
                Integer.class, OTHER_PROJECT, storyId));

        Map<String, Object> log = jdbc.queryForMap(
                "SELECT * FROM story_logs WHERE project_id=? AND story_id=?", PROJECT, storyId);
        assertEquals(PROJECT, log.get("project_id"));
        assertEquals("move", log.get("log_type"));
        assertEquals(storyId, log.get("story_code_snapshot"));
        assertEquals(internalId(PROJECT, storyId), ((Number) log.get("story_internal_id")).longValue());
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM story_logs WHERE project_id=? AND story_id=?",
                Integer.class, OTHER_PROJECT, storyId));
    }

    // ------------------------------------------------------------------
    // Refinement: create_story(编号按项目隔离分配)
    // ------------------------------------------------------------------

    @Test
    void refinementProposalCreatesStoryInOwnProjectOnly() throws Exception {
        String meetingId = newMeetingId();
        String relatedCode = newStoryCode(meetingId);
        insertMeeting(meetingId, "精化隔离测试", "决定新增周报导出。\n\nUS14 开始开发。");
        insertStory(PROJECT, relatedCode, "既有精化故事", 1, 0);
        // 其他项目存在同名故事:同名冲突校验必须是项目内校验,否则本用例会被错误地判为冲突。
        insertStory(OTHER_PROJECT, relatedCode, "导出周报CSV", 1, 0);

        String body = """
                {"client_request_id":"refinement-1","result":{"schema_version":"1.0","meeting_id":"%s",
                "meeting_type":"backlog_refinement","summary":"确认新增需求","open_questions":[],"proposed_actions":[
                {"proposal_id":"p1","action":"create_story",
                "changes":{"title":"导出周报CSV","description":null,"acceptance":null,"priority":null,"sprint":null,"activity":null},
                "reason":"确认新增","evidence":[{"segment_id":"S1","quote":"决定新增周报导出。"}]}]}}
                """.formatted(meetingId);

        String id = refinementAnalyses.save(meetingId, mapper.readTree(body), 1).path("id").asText();
        assertShadow("refinement", id, "pending", "not_started");
        refinementReviews.review(meetingId, id, mapper.readTree("""
                {"proposal_id":"p1","decision":"modify_and_approve","reason":"人工确认",
                "changes":{"title":"导出周报CSV","description":"导出本周故事","acceptance":"CSV含本周已完成故事",
                "priority":"Should","sprint":3,"activity":4}}
                """), 1);
        assertShadow("refinement", id, "approved", "not_started");
        String createdCode = refinementExecutions.execute(meetingId, id,
                mapper.readTree("{\"proposal_id\":\"p1\"}"), 1).path("story_id").asText();
        storyCodes.add(createdCode);
        assertShadow("refinement", id, "approved", "succeeded");

        Map<String, Object> created = jdbc.queryForMap(
                "SELECT * FROM stories WHERE project_id=? AND code=?", PROJECT, createdCode);
        assertEquals("导出周报CSV", created.get("title"));
        assertEquals("Should", created.get("priority"));
        assertEquals(3, created.get("sprint"));
        assertEquals(4, created.get("activity"));
        assertEquals(0, created.get("status"));
        assertNull(created.get("owner_id"));
        // 其他项目不得被写入同名新故事。
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM stories WHERE project_id=? AND code=?",
                Integer.class, OTHER_PROJECT, createdCode));
        Map<String, Object> log = jdbc.queryForMap(
                "SELECT * FROM story_logs WHERE story_id=?", createdCode);
        assertEquals(PROJECT, log.get("project_id"));
        assertEquals("create", log.get("log_type"));
        assertEquals(internalId(PROJECT, createdCode), ((Number) log.get("story_internal_id")).longValue());
    }

    // ------------------------------------------------------------------
    // Assignment: 负责人分配
    // ------------------------------------------------------------------

    @Test
    void assignmentExecutionChangesOnlyOwnProjectOwner() throws Exception {
        String meetingId = newMeetingId();
        String storyId = newStoryCode(meetingId);
        insertMeeting(meetingId, "分配隔离测试", "分配建议已确认。");
        insertStory(PROJECT, storyId, "分配测试故事", 2, 0);
        insertStory(OTHER_PROJECT, storyId, "其他项目分配故事", 2, 0);

        String body = """
                {"client_request_id":"assign-%s","input":{"story_ids":["%s"],"target_sprint":2,
                "requirements":[{"dimension":"tech_stack","name":"Python","minimum_level":3}]},
                "result":{"rule_version":"assignment-skills-v1","status":"provisional","requirements_source":"caller_supplied",
                "writes_performed":false,"excluded_viewer_ids":[],
                "context":{"meeting_id":"%s","target_sprint":2,"scope":"read_only_preparation","snapshot_consistency":"sequential_reads",
                "selected_stories":[{"id":"%s","title":"分配测试故事","status":0,"sprint":2,"owner_id":null}],
                "members":[{"profile":{"user_id":%d,"display_name":"孙秋实","role":"member"}}],"tasks":[],"gaps":[]},
                "candidates":[{"member_id":%d,"display_name":"孙秋实","rank":1,"matched_requirements":1,"total_requirements":1,
                "capacity_check":"unknown","suitability":"requires_human_review",
                "matches":[{"requirement":{"dimension":"tech_stack","name":"Python","minimum_level":3},
                "recorded_level":3,"meets_requirement":true}]}]}}
                """.formatted(UUID.randomUUID().toString(), storyId, meetingId, storyId,
                MEMBER_USER_ID, MEMBER_USER_ID);

        String id = assignments.save(meetingId, mapper.readTree(body), 1).path("id").asText();
        assertShadow("assignment", id, "pending", "not_started");
        assignments.review(meetingId, id, mapper.readTree("""
                {"decision":"approve","member_id":%d,"reason":"已人工核对分工；容量仍待执行前确认","capacity_acknowledged":true}
                """.formatted(MEMBER_USER_ID)), 1);
        assertShadow("assignment", id, "approved", "not_started");
        assignments.execute(meetingId, id, mapper.createObjectNode(), 1);
        assertShadow("assignment", id, "approved", "succeeded");

        assertEquals(MEMBER_USER_ID, jdbc.queryForObject(
                "SELECT owner_id FROM stories WHERE project_id=? AND code=?", Integer.class, PROJECT, storyId));
        assertNull(jdbc.queryForObject("SELECT owner_id FROM stories WHERE project_id=? AND code=?",
                Integer.class, OTHER_PROJECT, storyId));

        Map<String, Object> log = jdbc.queryForMap(
                "SELECT * FROM story_logs WHERE project_id=? AND story_id=?", PROJECT, storyId);
        assertEquals(PROJECT, log.get("project_id"));
        assertEquals("edit", log.get("log_type"));
        assertEquals(storyId, log.get("story_code_snapshot"));
        assertEquals(internalId(PROJECT, storyId), ((Number) log.get("story_internal_id")).longValue());
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM story_logs WHERE project_id=? AND story_id=?",
                Integer.class, OTHER_PROJECT, storyId));
    }
}
