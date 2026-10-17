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
     * 角色的等级化。<b>这是 ladder 的唯一出处</b>:索引时由它算出
     * {@code knowledge_chunks.acl_rank}(同一个数也进 Qdrant 的 payload),
     * 检索时两套实现比的就是这个数 —— Qdrant 用 {@code range.lte},
     * MySQL 用 {@code acl_rank <= roleRank}。SQL 里不再出现角色名字面量。
     *
     * <p>未识别角色按最高要求处理(默认拒绝,而不是默认放行)。这一点必须与 SQL 侧同向:
     * 早先 MySQL 用 {@code FIELD(c.acl_role,...)} 比字符串,而 {@code FIELD} 对认不出的
     * 值返回 0,{@code 0 <= 任何等级} 恒成立 ——「认不出」在 MySQL 上等于「人人可见」,
     * 在 Qdrant 上却等于「只有 admin 可见」,同一个块两套实现结论相反。
     */
    public static int rankOf(String aclRole) {
        int idx = LADDER.indexOf(aclRole == null ? "" : aclRole);
        return idx < 0 ? LADDER.size() : idx + 1;
    }

    /**
     * 是否为 ladder 中已定义的角色 —— 供配置校验复用。
     *
     * <p>必须由 ladder 的持有者回答,而不是让调用方自己列一遍角色名:那正是"第二份 ladder"
     * 的开端,而两份编码迟早会在「认不出的角色」上分叉。
     */
    public static boolean isKnownRole(String role) {
        return role != null && LADDER.contains(role);
    }

    /** ladder 本身(只读),用于在报错信息里列出合法取值。 */
    public static List<String> knownRoles() {
        return LADDER;
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
