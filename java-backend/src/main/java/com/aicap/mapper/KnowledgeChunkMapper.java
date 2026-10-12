package com.aicap.mapper;

import com.aicap.entity.KnowledgeChunk;
import com.aicap.rag.ChunkHitRow;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;

public interface KnowledgeChunkMapper extends BaseMapper<KnowledgeChunk> {

    /**
     * 关键词路召回(S2,设计文档 A5):MySQL ngram 全文索引。
     *
     * <p>为什么不能省掉这一路:AIcap 的文本里全是 {@code US01}/{@code T12}/{@code SetLamp}
     * 这类专有名词,向量模型对它们的区分能力接近噪声 —— 用户搜「US01」时向量路返回的
     * 前几名基本是随机的高频词块。ngram 分词把中文切成 2 字组合,恰好让这类短标识符
     * 能被精确命中。
     *
     * <p>{@code MATCH} 在 SELECT 与 WHERE 各写一次不是冗余:MySQL 只在出现
     * {@code MATCH} 的表达式处计算相关度,SELECT 里那个提供 {@code score},
     * WHERE 里那个负责过滤 —— 少了 WHERE 就是全表算分再排序。
     *
     * <p>ACL 与源类型过滤都在 SQL 里完成,理由同 {@code KnowledgeVectorMapper.selectCandidates}:
     * 捞回来再筛会让 top-k 被无权结果占满。比的是 {@code acl_rank}(角色的数值化)而不是
     * 角色字符串 —— {@code acl_rank} 只在 Java 侧由 {@code RetrievalContext.rankOf} 求出,
     * 两套向量库实现因此过滤同一个数,不会出现同一个块一处可见、一处不可见。
     * {@code acl_rank IS NOT NULL} 与 {@code rankOf} 对未识别角色的"默认拒绝"同向。
     *
     * <p>{@code sourceTypesCsv} 有两个调用方:关键词路传调用者允许的源类型
     * (见 {@link com.aicap.rag.RetrievalContext#sourceTypesCsv()}),图路的种子兜底传
     * {@code story,task}。两者能共用一条语句,关键就在于源类型过滤写在 WHERE 里 ——
     * LIMIT 作用在过滤之后,不会被无关源吃掉。
     */
    @Select("""
            SELECT c.`id`          AS chunkId,
                   c.`source_type` AS sourceType,
                   c.`source_id`   AS sourceId,
                   c.`raw_content` AS rawContent,
                   MATCH(c.`content`) AGAINST (#{query} IN NATURAL LANGUAGE MODE) AS score
            FROM `knowledge_chunks` c
            WHERE MATCH(c.`content`) AGAINST (#{query} IN NATURAL LANGUAGE MODE)
              AND c.`acl_rank` IS NOT NULL
              AND c.`acl_rank` <= #{roleRank}
              AND (#{sourceTypesCsv,jdbcType=VARCHAR} IS NULL
                   OR FIND_IN_SET(c.`source_type`, #{sourceTypesCsv,jdbcType=VARCHAR}) > 0)
            ORDER BY score DESC
            LIMIT #{limit}
            """)
    List<ChunkHitRow> searchByKeyword(@Param("query") String query,
                                      @Param("roleRank") int roleRank,
                                      @Param("sourceTypesCsv") String sourceTypesCsv,
                                      @Param("limit") int limit);

    /**
     * 按「源类型 + 源 id 集合」批量取块(图路展开用)。
     *
     * <p>一次查回整批而不是逐个查:图路一次展开的邻居是十几个,逐个查就是十几次往返。
     * 块按 {@code chunk_index} 排序 —— 会议源切成 N 段时,顺序错乱的片段读起来是乱的。
     *
     * <p><b>为什么是裸 {@code @Select} 而不是 {@code QueryWrapper}</b>:ACL 判定
     * ({@code c.acl_rank <= #{roleRank}})没法用 QueryWrapper 的流式 API 表达,只能靠
     * {@code apply} 塞一段裸 SQL。与其把一条语句拆成「流式几个条件 + 一段裸 SQL」,
     * 不如整条保持裸 SQL:这样 ACL 子句与旁边的源类型过滤能一眼看全,形态也与
     * {@link #searchByKeyword} 一致。
     *
     * <p><b>为什么用 {@code FIND_IN_SET} 而不是 {@code <foreach>}</b>:{@code <foreach>}
     * 要求整条语句包进 {@code <script>},而一旦进入 XML 解析,语句里的 {@code <=} 就成了
     * 非法 XML 字符,必须写成 {@code &lt;=}。仓库里确有 {@code <script>} 的先例
     * ({@code KnowledgeVectorMapper.deleteByIds},那条没有 {@code <=});
     * 但"同一条语句里一半是 SQL、一半要迁就 XML 转义"读起来是负担,能避开就避开。
     *
     * <p><b>约束</b>:{@code sourceIdsCsv} 里的 id 不能含逗号。图路只传
     * {@code US\d+}/{@code T\d+} 这类编号,天然满足;若将来要让 doc 源(路径可能含逗号)
     * 走这里,必须先换成 {@code <foreach>} 并处理上述转义。
     */
    @Select("""
            SELECT c.`id`          AS chunkId,
                   c.`source_type` AS sourceType,
                   c.`source_id`   AS sourceId,
                   c.`raw_content` AS rawContent
            FROM `knowledge_chunks` c
            WHERE c.`source_type` = #{sourceType}
              AND FIND_IN_SET(c.`source_id`, #{sourceIdsCsv}) > 0
              AND c.`acl_rank` IS NOT NULL
              AND c.`acl_rank` <= #{roleRank}
            ORDER BY c.`source_id`, c.`chunk_index`
            """)
    List<ChunkHitRow> selectBySourceRefs(@Param("sourceType") String sourceType,
                                         @Param("sourceIdsCsv") String sourceIdsCsv,
                                         @Param("roleRank") int roleRank);
}
