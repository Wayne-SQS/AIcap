package com.aicap.contract;

import com.aicap.dto.MeetingDtos;
import com.aicap.entity.User;
import com.aicap.service.MeetingService;
import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

import static org.junit.jupiter.api.Assertions.*;

/** Meeting suggestions must survive background submission and stay in their own project. */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "spring.datasource.url=jdbc:mysql://127.0.0.1:3307/aicap_java_test?useUnicode=true&characterEncoding=utf8&serverTimezone=Asia/Shanghai&allowPublicKeyRetrieval=true&useSSL=false",
        "spring.datasource.username=aiguanli",
        "spring.datasource.password=aiguanli-2026",
        "aicap.llm.agent-worker-enabled=false",
        "spring.sql.init.data-locations=classpath:db/reset_test_data.sql"
})
class MeetingSuggestionProjectContractTest extends ContractTestSupport {
    @Autowired JdbcTemplate jdbc;
    @Autowired MeetingService meetings;

    @Test
    void backgroundSubmissionAndReviewAreProjectScoped() {
        String admin = token(USER_ADMIN);
        int adminId = jdbc.queryForObject("SELECT id FROM users WHERE username=?", Integer.class, USER_ADMIN);
        jdbc.update("INSERT IGNORE INTO projects(id,name,description,status,total_sprints,created_at,updated_at) "
                + "VALUES ('P099','测试建议隔离','','ACTIVE',1,NOW(),NOW())");
        jdbc.update("INSERT IGNORE INTO project_members(project_id,user_id,project_role,capacity_hours,joined_at) "
                + "VALUES ('P099',?,'owner',60,NOW())", adminId);
        String meetingA = null, meetingB = null, poolId = null;
        try {
            ApiResponse a = post("/api/meetings", admin, json(map("project_id", "P002",
                    "title", uniq("会议建议 A"), "transcript", EVIDENCE_FRAGMENT)));
            ApiResponse b = post("/api/meetings", admin, json(map("project_id", "P099",
                    "title", uniq("会议建议 B"), "transcript", EVIDENCE_FRAGMENT)));
            assertStatus(a, 201);
            assertStatus(b, 201);
            meetingA = a.json().path("id").asText();
            meetingB = b.json().path("id").asText();

            // AgentJobs calls stage on a worker thread without request AuthContext.
            User initiator = new User();
            initiator.setId(adminId);
            String suggestionA = meetings.stage("P002", new MeetingDtos.SuggestionIn(meetingA,
                    uniq("agent-run"), "pool.create", "agent", EVIDENCE_FRAGMENT, "待审核",
                    new MeetingDtos.PoolChanges("项目 A 建议", "", "Could")), initiator).suggestion().getId();
            ApiResponse other = post("/api/suggestions?projectId=P099", admin,
                    json(map("meeting_id", meetingB, "client_request_id", uniq("manual"),
                            "evidence", EVIDENCE_FRAGMENT, "changes", map("title", "项目 B 建议"))));
            assertStatus(other, 200);
            String suggestionB = other.json().path("id").asText();

            ApiResponse listA = get("/api/suggestions?projectId=P002", admin);
            ApiResponse listB = get("/api/suggestions?projectId=P099", admin);
            assertStatus(listA, 200);
            assertStatus(listB, 200);
            assertTrue(containsId(listA.json(), suggestionA));
            assertFalse(containsId(listA.json(), suggestionB));
            assertTrue(containsId(listB.json(), suggestionB));
            assertFalse(containsId(listB.json(), suggestionA));
            assertStatus(get("/api/suggestions/" + suggestionB + "?projectId=P002", admin), 404);
            assertStatus(post("/api/suggestions/" + suggestionB + "/review?projectId=P002", admin,
                    json(map("decision", "approve"))), 404);
            assertEquals("pending", jdbc.queryForObject("SELECT status FROM suggestions WHERE id=?",
                    String.class, suggestionB));

            ApiResponse approved = post("/api/suggestions/" + suggestionB + "/review?projectId=P099",
                    admin, json(map("decision", "approve")));
            assertStatus(approved, 200);
            poolId = approved.json().path("pool_item_id").asText();
            assertEquals("P099", jdbc.queryForObject("SELECT project_id FROM pool_items WHERE id=?",
                    String.class, poolId));
        } finally {
            if (meetingA != null) assertStatus(delete("/api/meetings/" + meetingA + "?projectId=P002", admin), 200);
            if (meetingB != null) assertStatus(delete("/api/meetings/" + meetingB + "?projectId=P099", admin), 200);
            if (poolId != null) jdbc.update("DELETE FROM pool_items WHERE id=?", poolId);
            jdbc.update("DELETE FROM project_members WHERE project_id='P099'");
            jdbc.update("DELETE FROM projects WHERE id='P099'");
        }
    }

    private boolean containsId(JsonNode rows, String id) {
        for (JsonNode row : rows) if (id.equals(row.path("id").asText())) return true;
        return false;
    }
}
