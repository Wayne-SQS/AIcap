package com.aicap.contract;

import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.springframework.boot.test.context.SpringBootTest;

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

    @Test
    void viewer_readEndpoints_200() {
        String t = token(USER_VIEWER);
        assertEquals(200, get("/api/stories", t).status());
        assertEquals(200, get("/api/tasks", t).status());
        assertEquals(200, get("/api/pool", t).status());
        assertEquals(200, get("/api/dashboard", t).status());
        assertEquals(200, get("/api/auth/me", t).status());
        assertEquals(200, get("/api/meetings", t).status());
    }

    @Test
    void viewer_postStory_403() {
        ApiResponse r = post("/api/stories", token(USER_VIEWER),
                json(map("title", uniq("CT越权"))));
        assertEquals(403, r.status(), r.body());
        assertTrue(r.json().has("detail"), "403 应为 {\"detail\":...}: " + r.body());
    }

    @Test
    void viewer_patchTask_403() {
        ApiResponse r = patch("/api/tasks/T01", token(USER_VIEWER), json(map("status", 1)));
        assertEquals(403, r.status(), r.body());
    }

    @Test
    void viewer_postPool_403() {
        ApiResponse r = post("/api/pool", token(USER_VIEWER),
                json(map("title", uniq("CT越权池"))));
        assertEquals(403, r.status(), r.body());
    }

    @Test
    void viewer_deleteStory_403_storyUntouched() {
        ApiResponse r = delete("/api/stories/US01?undone=keep", token(USER_VIEWER));
        assertEquals(403, r.status(), r.body());
        // 种子故事必须原封不动
        ApiResponse list = get("/api/stories", token(USER_VIEWER));
        boolean present = false;
        for (JsonNode it : list.json()) {
            if ("US01".equals(it.path("id").asText())) {
                present = true;
            }
        }
        assertTrue(present, "viewer 删除被拒后 US01 应仍在: " + r.body());
    }

    @Test
    void viewer_deletePool_403() {
        ApiResponse r = delete("/api/pool/R99", token(USER_VIEWER));
        assertEquals(403, r.status(), r.body());
        assertEquals(0, get("/api/pool", token(USER_ADMIN)).json().size(),
                "viewer 操作不应产生池副作用");
    }

    @Test
    void viewer_submitSuggestion_403() {
        String body = json(map(
                "meeting_id", "no-such-meeting",
                "client_request_id", uniq("cr"),
                "evidence", EVIDENCE_FRAGMENT,
                "changes", map("title", uniq("CT越权建议"))));
        ApiResponse r = post("/api/suggestions", token(USER_VIEWER), body);
        assertEquals(403, r.status(), r.body());
    }

    @Test
    void member_canCreateStory_butDeleteForbidden_adminCleansUp() {
        String id = null;
        try {
            // member 属 writer:POST story 可
            ApiResponse created = post("/api/stories", token(USER_MEMBER),
                    json(map("title", uniq("CT成员建卡"), "owner_id", 3)));
            assertEquals(200, created.status(), created.body());
            id = created.json().path("id").asText();
            // member 不属 reviewer:DELETE story → 403
            ApiResponse denied = delete("/api/stories/" + id + "?undone=keep", token(USER_MEMBER));
            assertEquals(403, denied.status(), denied.body());
            // admin(reviewer)可删
            ApiResponse adminDel = delete("/api/stories/" + id + "?undone=keep", token(USER_ADMIN));
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
            ApiResponse m = post("/api/meetings", token(USER_ADMIN),
                    json(map("title", uniq("CT角色会议"),
                            "transcript", "议题含 " + EVIDENCE_FRAGMENT + "。")));
            assertEquals(201, m.status(), m.body());
            mid = m.json().path("id").asText();
            ApiResponse s = post("/api/suggestions", token(USER_ADMIN), json(map(
                    "meeting_id", mid,
                    "client_request_id", uniq("cr"),
                    "evidence", EVIDENCE_FRAGMENT,
                    "changes", map("title", uniq("CT成员审核"), "priority", "Should"))));
            assertEquals(200, s.status(), s.body());
            sid = s.json().path("id").asText();
            // member 不属 reviewer → review 403
            ApiResponse denied = post("/api/suggestions/" + sid + "/review",
                    token(USER_MEMBER), json(map("decision", "approve")));
            assertEquals(403, denied.status(), denied.body());
            // 建议仍 pending
            ApiResponse after = get("/api/suggestions/" + sid, token(USER_ADMIN));
            assertEquals("pending", after.json().path("status").asText());
        } finally {
            // 若中途被批准落池则清掉(正常流程 member 被拒,pending 无池副作用)
        }
    }
}
