package com.aicap.contract;

import com.aicap.entity.ProjectMember;
import com.aicap.mapper.ProjectMemberMapper;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.beans.factory.annotation.Autowired;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 角色门契约(US02「角色与权限边界」):
 * - viewer(成员5)读 200、写(POST/PATCH/DELETE story、PATCH task、POST pool、
 *   DELETE pool、POST suggestions)一律 403;
 * - member(孙秋实)属 writer:可 POST story、POST suggestions;
 *   但不属 reviewer:DELETE story / POST suggestions/{id}/review → 403;
 * - 403 不产生副作用(故事仍在、池为空等)。
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "spring.datasource.url=jdbc:mysql://127.0.0.1:3307/aicap_java_test?useUnicode=true&characterEncoding=utf8&serverTimezone=Asia/Shanghai&allowPublicKeyRetrieval=true&useSSL=false",
        "spring.datasource.username=aiguanli",
        "spring.datasource.password=aiguanli-2026",
        "aicap.llm.agent-worker-enabled=false",
        "spring.sql.init.data-locations=classpath:db/reset_test_data.sql"
})
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class RoleContractTest extends ContractTestSupport {

    @Autowired
    private ProjectMemberMapper projectMembers;
    private final List<Long> fixtureMemberIds = new ArrayList<>();

    @BeforeAll
    void addRoleFixtures() {
        addProjectMember(3, "member");
        addProjectMember(5, "viewer");
    }

    private void addProjectMember(int userId, String role) {
        if (projectMembers.selectCount(new QueryWrapper<ProjectMember>()
                .eq("project_id", TEST_PROJECT_ID).eq("user_id", userId)) > 0) return;
        ProjectMember member = new ProjectMember();
        member.setProjectId(TEST_PROJECT_ID);
        member.setUserId(userId);
        member.setProjectRole(role);
        member.setCapacityHours(60);
        member.setJoinedAt(LocalDateTime.now());
        projectMembers.insert(member);
        fixtureMemberIds.add(member.getId());
    }

    @AfterAll
    void removeRoleFixtures() {
        fixtureMemberIds.forEach(projectMembers::deleteById);
    }

    @Test
    void viewer_readEndpoints_200() {
        String t = token(USER_VIEWER);
        assertEquals(200, getProject("/api/stories", t).status());
        assertEquals(200, getProject("/api/tasks", t).status());
        assertEquals(200, getProject("/api/pool", t).status());
        assertEquals(200, getProject("/api/dashboard", t).status());
        assertEquals(200, get("/api/auth/me", t).status());
        assertEquals(200, getProject("/api/meetings", t).status());
    }

    @Test
    void memberProfiles_projectProjectionExcludesOtherUsers() {
        ApiResponse rows = getProject("/api/members/profiles", token(USER_VIEWER));
        assertStatus(rows, 200);
        assertTrue(rows.json().isArray(), rows.body());
        assertTrue(java.util.stream.StreamSupport.stream(rows.json().spliterator(), false)
                .noneMatch(row -> row.path("user_id").asInt() == 4), rows.body());
        assertTrue(java.util.stream.StreamSupport.stream(rows.json().spliterator(), false)
                .anyMatch(row -> row.path("user_id").asInt() == 5
                        && "viewer".equals(row.path("role").asText())), rows.body());
    }

    @Test
    void viewer_postStory_403() {
        ApiResponse r = postProject("/api/stories", token(USER_VIEWER),
                json(map("title", uniq("CT越权"))));
        assertEquals(403, r.status(), r.body());
        assertTrue(r.json().has("detail"), "403 应为 {\"detail\":...}: " + r.body());
    }

    @Test
    void viewer_patchTask_403() {
        ApiResponse r = patchProject("/api/tasks/T01", token(USER_VIEWER), json(map("status", 1)));
        assertEquals(403, r.status(), r.body());
    }

    @Test
    void viewer_postPool_403() {
        ApiResponse r = postProject("/api/pool", token(USER_VIEWER),
                json(map("title", uniq("CT越权池"))));
        assertEquals(403, r.status(), r.body());
    }

    @Test
    void viewer_deleteStory_403_storyUntouched() {
        ApiResponse created = postProject("/api/stories", token(USER_ADMIN),
                json(map("title", uniq("CT只读保护"))));
        assertStatus(created, 200);
        String id = created.json().path("id").asText();
        try {
            ApiResponse r = deleteProject("/api/stories/" + id + "?undone=keep", token(USER_VIEWER));
            assertEquals(403, r.status(), r.body());
            ApiResponse list = getProject("/api/stories", token(USER_VIEWER));
            boolean present = false;
            for (JsonNode it : list.json()) {
                if (id.equals(it.path("id").asText())) present = true;
            }
            assertTrue(present, "viewer 删除被拒后故事应仍在: " + r.body());
        } finally {
            deleteStory(id);
        }
    }

    @Test
    void viewer_deletePool_403() {
        ApiResponse r = deleteProject("/api/pool/R99", token(USER_VIEWER));
        assertEquals(403, r.status(), r.body());
        assertEquals(0, getProject("/api/pool", token(USER_ADMIN)).json().size(),
                "viewer 操作不应产生池副作用");
    }

    @Test
    void viewer_submitSuggestion_403() {
        String body = json(map(
                "meeting_id", "no-such-meeting",
                "client_request_id", uniq("cr"),
                "evidence", EVIDENCE_FRAGMENT,
                "changes", map("title", uniq("CT越权建议"))));
        ApiResponse r = postProject("/api/suggestions", token(USER_VIEWER), body);
        assertEquals(403, r.status(), r.body());
    }

    @Test
    void member_canCreateStory_butDeleteForbidden_adminCleansUp() {
        String id = null;
        try {
            // member 属 writer:POST story 可
            ApiResponse created = postProject("/api/stories", token(USER_MEMBER),
                    json(map("title", uniq("CT成员建卡"), "owner_id", 3)));
            assertEquals(200, created.status(), created.body());
            id = created.json().path("id").asText();
            // member 不属 reviewer:DELETE story → 403
            ApiResponse denied = deleteProject("/api/stories/" + id + "?undone=keep", token(USER_MEMBER));
            assertEquals(403, denied.status(), denied.body());
            // admin(reviewer)可删
            ApiResponse adminDel = deleteProject("/api/stories/" + id + "?undone=keep", token(USER_ADMIN));
            assertEquals(200, adminDel.status(), adminDel.body());
            id = null; // 已删
        } finally {
            if (id != null) {
                deleteStory(id);
            }
        }
    }

    @Test
    void member_cannotReviewSuggestion_403() {
        String mid = null;
        String sid = null;
        try {
            ApiResponse m = postProject("/api/meetings", token(USER_ADMIN),
                    json(map("project_id", TEST_PROJECT_ID, "title", uniq("CT角色会议"),
                            "transcript", "议题含 " + EVIDENCE_FRAGMENT + "。")));
            assertEquals(201, m.status(), m.body());
            mid = m.json().path("id").asText();
            ApiResponse s = postProject("/api/suggestions", token(USER_ADMIN), json(map(
                    "meeting_id", mid,
                    "client_request_id", uniq("cr"),
                    "evidence", EVIDENCE_FRAGMENT,
                    "changes", map("title", uniq("CT成员审核"), "priority", "Should"))));
            assertEquals(200, s.status(), s.body());
            sid = s.json().path("id").asText();
            // member 不属 reviewer → review 403
            ApiResponse denied = postProject("/api/suggestions/" + sid + "/review",
                    token(USER_MEMBER), json(map("decision", "approve")));
            assertEquals(403, denied.status(), denied.body());
            // 建议仍 pending
            ApiResponse after = getProject("/api/suggestions/" + sid, token(USER_ADMIN));
            assertEquals("pending", after.json().path("status").asText());
        } finally {
            if (mid != null) {
                assertStatus(deleteProject("/api/meetings/" + mid, token(USER_ADMIN)), 200);
            }
        }
    }
}
