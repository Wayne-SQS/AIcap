package com.aicap.rag;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@code aicap.rag.acl} 取值校验。
 *
 * <p>钉住的方向:值不在 ladder 里必须<b>启动失败</b>,而不是继续跑下去。
 * 后者曾真实成立 —— {@code reviewer} 是本系统合法角色({@code Roles.reviewer()})
 * 却不在 {@code RetrievalContext.LADDER} 里,配成某个源的级别后该源会静默变成
 * <b>只有 admin 可见</b>;{@code viewer} 更拧,它读起来是"viewer 及以上",
 * 实际正好相反。这类误配当时既不报错也不写日志,只能靠人猜到。
 *
 * <p>用 {@link RagProperties#logEffectiveAcl()} 触发校验(校验就挂在那个
 * {@code @PostConstruct} 上),因此不需要起 Spring 上下文。
 *
 * <p>注意校验只覆盖<b>值</b>:源类型名(键)写错仍落到 {@code aclRoleFor} 的默认
 * {@code member},方向是放行 —— 那是另一条路径,见 {@code logEffectiveAcl} 的说明。
 */
class RagPropertiesAclValidationTest {

    @Test
    void unknownRoleFailsStartupInsteadOfSilentlyRequiringAdmin() {
        RagProperties props = new RagProperties();
        props.getAcl().put("doc", "reviewer");

        IllegalStateException e = assertThrows(IllegalStateException.class, props::logEffectiveAcl);

        assertTrue(e.getMessage().contains("reviewer"),
                "报错必须点名具体取值,否则运维得自己一行行比对配置: " + e.getMessage());
        assertTrue(e.getMessage().contains("admin"),
                "报错必须说清后果(只有 admin 可见),否则看不出这是收紧而非放行: " + e.getMessage());
    }

    @Test
    void roleNameIsCaseSensitive() {
        RagProperties props = new RagProperties();
        props.getAcl().put("doc", "MEMBER");

        assertThrows(IllegalStateException.class, props::logEffectiveAcl);
    }

    @Test
    void ladderRolesAreAccepted() {
        RagProperties props = new RagProperties();
        props.getAcl().put("doc", "admin");
        props.getAcl().put("activity", "owner");
        props.getAcl().put("task", "member");

        assertDoesNotThrow(props::logEffectiveAcl, "三个梯子角色都必须放行");
    }
}
