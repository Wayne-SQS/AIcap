package com.aicap.mapper;

import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;

/**
 * 图路展开用的结构关系查询(设计文档 A5)。
 *
 * <p>AIcap 里唯一真实存在的跨实体边是 <b>故事 ↔ 任务</b>:
 * <ul>
 *   <li>{@code tasks.kanban_card_id} —— 单值外键,指向所属看板卡(故事);</li>
 *   <li>{@code tasks.story_ref} —— <b>逗号分隔的故事 id 列表</b>(如 {@code US01,US03,US04}),
 *       不是单值。</li>
 * </ul>
 * 第二条容易被当成单值处理 —— 那样 {@code US03} 就永远展开不到 T01,
 * 而 T01 的正文里确实写着 US03。{@code FIND_IN_SET} 是这里唯一正确的匹配方式。
 *
 * <p>故事线索 {@code story_logs} 不参与:它不是 6 类知识源之一,没有对应的 chunk,
 * 展开过去也拿不到可检索的内容。
 */
public interface RetrievalGraphMapper {

    /** 任务行上的两条边;{@code storyRef} 需按逗号再切一次 */
    record TaskEdges(String storyRef, String kanbanCardId) {
    }

    /**
     * 故事 → 挂载它的任务。返回任务<b>业务编号</b>(用于再取 task 类 chunk)。
     *
     * <p>取 {@code code} 而不是 {@code id}:多项目改造后 {@code tasks} 的主键是
     * {@code internal_id},业务编号挪到了 {@code code},旧 {@code id} 列被降级成可空普通列 ——
     * 之后新写的数据那一列是 NULL,按它查会**一行都查不到**,而图路会静默返回空
     * (不报错,只是"检索结果里少了图路那一跳")。这类失效只能靠契约测试发现。
     */
    @Select("""
            SELECT DISTINCT t.`code`
            FROM `tasks` t
            WHERE t.`kanban_card_id` = #{storyId}
               OR FIND_IN_SET(#{storyId}, t.`story_ref`) > 0
            """)
    List<String> findTaskIdsOfStory(@Param("storyId") String storyId);

    /** 任务 → 它引用的故事(逗号列表 + 看板卡外键),交给调用方切分合并 */
    @Select("""
            SELECT t.`story_ref` AS storyRef, t.`kanban_card_id` AS kanbanCardId
            FROM `tasks` t
            WHERE t.`code` = #{taskId}
            """)
    TaskEdges findStoryRefsOfTask(@Param("taskId") String taskId);
}
