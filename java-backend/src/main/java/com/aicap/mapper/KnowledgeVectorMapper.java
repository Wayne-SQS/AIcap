package com.aicap.mapper;

import com.aicap.entity.KnowledgeVector;
import com.aicap.rag.VectorRow;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;

public interface KnowledgeVectorMapper extends BaseMapper<KnowledgeVector> {

    /**
     * 取候选向量(暴力检索):余弦相似度在应用层算,SQL 只负责<b>把不该看见的挡掉</b>。
     *
     * <p>ACL 必须在 SQL 里过滤,不能捞回来再筛 —— 否则 top-k 会被无权结果占满,
     * 有权结果反而挤不进来(设计文档 A7)。{@code acl_role} 语义是「最低可见角色」,
     * 比的是它的数值化 {@code acl_rank}:{@code member=1 < owner=2 < admin=3},
     * 调用者等级 >= chunk 要求等级 才可见。
     *
     * <p>这里<b>曾经</b>写的是 {@code FIELD(c.acl_role,'member','owner','admin') <= roleRank},
     * 而 {@code FIELD} 对未识别的角色字符串返回 <b>0</b> —— {@code 0 <= roleRank} 对任何
     * 调用者都成立,即<b>默认放行</b>;Qdrant 侧读的是 {@code rankOf} 写进 payload 的
     * {@code acl_rank},对未识别角色给的是「最高要求」,即<b>默认拒绝</b>。
     * 同一个块于是能在 MySQL 上人人可见、在 Qdrant 上只有 admin 可见,而当时的测试
     * 用的全是合法角色名,两边都绿。改成比 {@code acl_rank} 后 ladder 只剩 Java 一处,
     * {@code acl_rank IS NOT NULL} 又让"没写等级"同样落到不可见,方向不会再有分歧。
     *
     * @param sourceTypesCsv 逗号分隔的源类型白名单;null = 不限(用 FIND_IN_SET 避免动态 SQL)
     */
    @Select("""
            SELECT v.`chunk_id`      AS chunkId,
                   v.`dimension`     AS dimension,
                   v.`vector`        AS vector,
                   c.`source_type`   AS sourceType,
                   c.`source_id`     AS sourceId,
                   c.`metadata_json` AS metadataJson
            FROM `knowledge_vectors` v
            JOIN `knowledge_chunks` c ON c.`id` = v.`chunk_id`
            WHERE v.`model` = #{model}
              AND c.`acl_rank` IS NOT NULL
              AND c.`acl_rank` <= #{roleRank}
              AND (#{sourceTypesCsv,jdbcType=VARCHAR} IS NULL
                   OR FIND_IN_SET(c.`source_type`, #{sourceTypesCsv,jdbcType=VARCHAR}) > 0)
            LIMIT #{limit}
            """)
    List<VectorRow> selectCandidates(@Param("model") String model,
                                     @Param("roleRank") int roleRank,
                                     @Param("sourceTypesCsv") String sourceTypesCsv,
                                     @Param("limit") int limit);

    /** 幂等写入:同一 chunk 重算后覆盖,不留旧向量 */
    @Insert("""
            INSERT INTO `knowledge_vectors` (`chunk_id`, `model`, `dimension`, `vector`, `created_at`)
            VALUES (#{chunkId}, #{model}, #{dimension}, #{vector}, NOW())
            ON DUPLICATE KEY UPDATE `model` = VALUES(`model`),
                                    `dimension` = VALUES(`dimension`),
                                    `vector` = VALUES(`vector`),
                                    `created_at` = VALUES(`created_at`)
            """)
    int upsert(KnowledgeVector vector);

    /** 按源删向量:源记录被删时调用,避免留下检索得到却已不存在的「幽灵 chunk」 */
    @Delete("""
            DELETE v FROM `knowledge_vectors` v
            JOIN `knowledge_chunks` c ON c.`id` = v.`chunk_id`
            WHERE c.`source_type` = #{sourceType} AND c.`source_id` = #{sourceId}
            """)
    int deleteBySource(@Param("sourceType") String sourceType, @Param("sourceId") String sourceId);

    /** 按 chunk id 精确删除(源的块数变少时,只删掉消失的那些) */
    @Delete("""
            <script>
            DELETE FROM `knowledge_vectors` WHERE `chunk_id` IN
            <foreach collection="ids" item="id" open="(" separator="," close=")">#{id}</foreach>
            </script>
            """)
    int deleteByIds(@Param("ids") List<String> ids);

    @Select("SELECT COUNT(*) FROM `knowledge_vectors` WHERE `model` = #{model}")
    long countByModel(@Param("model") String model);

    /** 当前模型已落库的向量维度(去重)。只查维度而非整表,校验不该把向量读进内存。 */
    @Select("SELECT DISTINCT `dimension` FROM `knowledge_vectors` WHERE `model` = #{model}")
    List<Integer> selectDimensions(@Param("model") String model);
}
