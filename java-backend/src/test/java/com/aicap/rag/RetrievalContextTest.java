package com.aicap.rag;

import org.junit.jupiter.api.Test;

import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 检索 ACL 逻辑测试。
 *
 * <p>这段逻辑的失效方式是"静默多给"——不会报错,只会让低权限用户多查到东西。
 * 因此边界必须逐条钉死:尤其是<b>未知角色必须默认拒绝</b>,
 * 否则将来新增一个角色,它在 ACL 里会被当成最高权限。
 */
class RetrievalContextTest {

    @Test
    void roleRankFollowsLadder() {
        assertEquals(1, new RetrievalContext(1, "member").roleRank());
        assertEquals(2, new RetrievalContext(1, "owner").roleRank());
        assertEquals(3, new RetrievalContext(1, "admin").roleRank());
        // viewer 只读,可见性与 member 相同 —— 差别在"能不能写",不在"能看什么"
        assertEquals(1, new RetrievalContext(1, "viewer").roleRank());
        // 未知/空角色按最低权限处理(默认拒绝)
        assertEquals(1, new RetrievalContext(1, "auditor").roleRank());
        assertEquals(1, new RetrievalContext(1, null).roleRank());
    }

    @Test
    void higherRolesSeeEverythingAtOrBelowTheirLevel() {
        RetrievalContext admin = new RetrievalContext(1, "admin");
        assertTrue(admin.canSee("member"));
        assertTrue(admin.canSee("owner"));
        assertTrue(admin.canSee("admin"));

        RetrievalContext owner = new RetrievalContext(2, "owner");
        assertTrue(owner.canSee("member"));
        assertTrue(owner.canSee("owner"));
        assertFalse(owner.canSee("admin"), "owner 不应看到 admin 级内容");

        for (String role : new String[]{"member", "viewer"}) {
            RetrievalContext ctx = new RetrievalContext(3, role);
            assertTrue(ctx.canSee("member"));
            assertFalse(ctx.canSee("owner"), role + " 不应看到 owner 级内容");
            assertFalse(ctx.canSee("admin"), role + " 不应看到 admin 级内容");
        }
    }

    @Test
    void unknownAclRoleIsDeniedToEveryoneButIsNotSilentlyOpened() {
        // acl_role 列里出现没见过的值时,不能当成"无限制";管理员仍可见(等级 3 覆盖它)
        assertEquals(RetrievalContext.rankOf("member"), 1);
        assertEquals(RetrievalContext.rankOf("owner"), 2);
        assertEquals(RetrievalContext.rankOf("admin"), 3);
        assertEquals(RetrievalContext.rankOf(null), 3, "空 acl_role 视为要求最高等级");
        assertEquals(RetrievalContext.rankOf("whatever"), 3);

        assertFalse(new RetrievalContext(3, "member").canSee("whatever"));
        assertTrue(new RetrievalContext(1, "admin").canSee("whatever"));
    }

    @Test
    void sourceTypeFilterIsOptionalAndCsvEncodedForSql() {
        assertNull(new RetrievalContext(1, "member").sourceTypesCsv(), "未限定源类型时应为 null");
        assertNull(new RetrievalContext(1, "member", Set.of()).sourceTypesCsv(), "空集合等同于不限定");
        assertEquals("story", new RetrievalContext(1, "member", Set.of("story")).sourceTypesCsv());
        assertEquals("pool_item,story",
                new RetrievalContext(1, "member", Set.of("story", "pool_item")).sourceTypesCsv());
    }
}
