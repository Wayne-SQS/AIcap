package com.aicap.contract;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** HTTP contract for project membership on meeting child routes without a projectId parameter. */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "spring.datasource.url=jdbc:mysql://127.0.0.1:3307/aicap_java_test?useUnicode=true&characterEncoding=utf8&serverTimezone=Asia/Shanghai&allowPublicKeyRetrieval=true&useSSL=false",
        "spring.datasource.username=aiguanli",
        "spring.datasource.password=aiguanli-2026",
        "aicap.llm.agent-worker-enabled=false",
        "spring.sql.init.data-locations=classpath:db/reset_test_data.sql"
})
class MeetingProjectAccessContractTest extends ContractTestSupport {
    @Autowired private JdbcTemplate jdbc;

    @Test
    void meetingChildrenUseProjectRoleForReadWriteAndReview() {
        String projectId = "P" + (100000 + Math.floorMod(UUID.randomUUID().hashCode(), 800000));
        String meetingId = UUID.randomUUID().toString();
        int adminId = userId(USER_ADMIN);
        int memberId = userId(USER_MEMBER);
        String admin = token(USER_ADMIN);
        String member = token(USER_MEMBER);
        String outsider = token(USER_OWNER);
        jdbc.update("INSERT INTO projects(id,name,description,status,total_sprints,created_at,updated_at) " +
                "VALUES (?,'会议权限契约','','ACTIVE',1,NOW(),NOW())", projectId);
        try {
            jdbc.update("INSERT INTO project_members(project_id,user_id,project_role,capacity_hours,joined_at) " +
                    "VALUES (?,?,'member',60,NOW()),(?,?,'owner',60,NOW())",
                    projectId, adminId, projectId, memberId);
            jdbc.update("INSERT INTO meetings(id,project_id,title,transcript,created_by,created_at) " +
                    "VALUES (?,?,'权限契约会议','测试原文',?,NOW())", meetingId, projectId, adminId);

            String base = "/api/meetings/" + meetingId;
            for (String child : new String[]{"planning-analyses", "review-analyses", "refinement-analyses",
                    "status-analyses", "retro-analyses", "assignment-suggestions"}) {
                assertStatus(get(base + "/" + child, outsider), 403);
            }
            assertStatus(get(base + "/action-items", outsider), 403);
            assertStatus(get(base + "/planning-analyses?projectId=P002", admin), 404);
            assertStatus(get(base + "/planning-analyses", admin), 200);

            jdbc.update("UPDATE project_members SET project_role='viewer' WHERE project_id=? AND user_id=?",
                    projectId, adminId);
            assertStatus(get(base + "/planning-analyses", admin), 200);
            assertStatus(post(base + "/status-analyses", admin, "{}"), 403);
            assertStatus(post(base + "/status-analyses", member, "{}"), 422);
            jdbc.update("UPDATE project_members SET project_role='member' WHERE project_id=? AND user_id=?",
                    projectId, adminId);

            String reviewPath = base + "/planning-analyses/absent/proposal-reviews";
            String validReview = "{\"proposal_id\":\"p1\",\"decision\":\"reject\",\"reason\":\"不采用\"}";
            assertStatus(post(reviewPath, admin, validReview), 403);
            assertStatus(post(reviewPath, member, validReview), 403);
            String executionPath = base + "/planning-analyses/absent/proposal-executions";
            assertStatus(post(executionPath, admin, "{\"proposal_id\":\"p1\"}"), 403);
            assertStatus(post(executionPath, member, "{\"proposal_id\":\"p1\"}"), 403);
            assertStatus(post(base + "/assignment-suggestions/absent/review", admin, "{}"), 403);
            assertStatus(delete(base + "?projectId=" + projectId, admin), 403);
            assertStatus(delete(base + "?projectId=" + projectId, member), 403);
            jdbc.update("UPDATE project_members SET project_role='owner' WHERE project_id=? AND user_id=?",
                    projectId, adminId);
            assertStatus(post(reviewPath, admin, validReview), 404);
            assertStatus(post(executionPath, admin, "{\"proposal_id\":\"p1\"}"), 404);
            assertStatus(delete(base + "?projectId=" + projectId, admin), 200);
            assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM meetings WHERE id=?", Integer.class, meetingId));
        } finally {
            jdbc.update("DELETE FROM meetings WHERE id=?", meetingId);
            jdbc.update("DELETE FROM project_members WHERE project_id=? AND user_id IN (?,?)",
                    projectId, adminId, memberId);
            jdbc.update("DELETE FROM projects WHERE id=?", projectId);
        }
    }

    private int userId(String username) {
        return jdbc.queryForObject("SELECT id FROM users WHERE username=?", Integer.class, username);
    }
}
