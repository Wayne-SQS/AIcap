package com.aicap.rag;

import java.util.List;
import java.util.Set;

/**
 * 检索的调用者上下文 —— 决定「谁能看见什么」(设计文档 A7)。
 *
 * <p>存在的理由:现有 {@code AgentTools} 在工具执行前校验 {@code WRITER_ROLES},
 * {@code ResourceController} 限制非管理员只能看已发布内容。检索层若不校验,
 * 成员就能用一句语义查询<b>绕过</b>这些限制把无权内容捞出来 —— 这是新增能力引入的新攻击面。
 *
 * @param userId              调用者 id(仅用于日志与 retrieval_logs)
 * @param role                调用者角色
 * @param allowedSourceTypes  允许检索的源类型;null = 全部
 */
public record RetrievalContext(int userId, String role, Set<String> allowedSourceTypes) {

    /** 角色等级,与 {@code knowledge_chunks.acl_role} 的「最低可见角色」语义配套 */
    private static final List<String> LADDER = List.of("member", "owner", "admin");

    public RetrievalContext(int userId, String role) {
        this(userId, role, null);
    }

    /**
     * 调用者等级。viewer 与 member 同为 1:两者都只能看 member 级内容
     * —— 差别在「能不能写」,那是 {@code Roles.writer()} 的事,与可见性无关。
     * 未识别的角色一律按最低等级处理(默认拒绝,而不是默认放行)。
     */
    public int roleRank() {
        int idx = LADDER.indexOf(role == null ? "" : role);
        return idx < 0 ? 1 : idx + 1;
    }

    /** 调用者能否看见要求 {@code aclRole} 最低角色的知识块 */
    public boolean canSee(String aclRole) {
        return rankOf(aclRole) <= roleRank();
    }

    /**
     * 角色的等级化,供向量库侧的数值过滤使用(Qdrant 用 range.lte,MySQL 用 FIELD())。
     * 未识别角色按最高要求处理(默认拒绝,而不是默认放行)。
     */
    public static int rankOf(String aclRole) {
        int idx = LADDER.indexOf(aclRole == null ? "" : aclRole);
        return idx < 0 ? LADDER.size() : idx + 1;
    }

    /**
     * 供 SQL {@code FIND_IN_SET} 使用的逗号分隔源类型;null = 不限。
     *
     * <p>排序输出:集合的迭代顺序不保证,不排序的话同一个查询每次生成不同的 SQL 文本,
     * 既不利于比对日志,也让"同一个查询应该产生同一条 SQL"这件事不再成立。
     */
    public String sourceTypesCsv() {
        if (allowedSourceTypes == null || allowedSourceTypes.isEmpty()) {
            return null;
        }
        return allowedSourceTypes.stream().sorted().collect(java.util.stream.Collectors.joining(","));
    }
}
