package com.aicap.mapper;

import com.aicap.entity.AgentCheckpoint;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;

public interface AgentCheckpointMapper extends BaseMapper<AgentCheckpoint> {

    /**
     * 幂等 upsert：同 {@code (run_id, node_name)} 覆盖。
     *
     * <p>{@code uk_run_node} 唯一键本就排除了"同 run 同 node 多行"，插入第二次必撞键，
     * 覆盖才是该键的用途。写法沿用 {@link KnowledgeVectorMapper#upsert}。
     * 注意 upsert <b>不改 {@code id}</b>，所以取"最新状态"只能按 {@code node_name} 直取，
     * 不能 {@code ORDER BY id}。
     */
    @Insert("""
            INSERT INTO `agent_checkpoints` (`run_id`, `node_name`, `state_json`, `created_at`)
            VALUES (#{runId}, #{nodeName}, #{stateJson}, NOW())
            ON DUPLICATE KEY UPDATE `state_json` = VALUES(`state_json`),
                                    `created_at` = VALUES(`created_at`)
            """)
    int upsert(AgentCheckpoint checkpoint);

    /**
     * 该会话里「未完成」的 run：<b>有检查点</b>且<b>无 {@code run.end}/{@code run.error} 事件</b>。
     *
     * <p>契约 §4 没有 run 表，run 只以 {@code run_id} 出现在事件与检查点上，故"未完成"只能这样判定。
     * 用"有检查点"而非"有事件"来确认该 run 真正开始过——否则一个刚写了首条事件就崩的 run
     * 会被当成未完成却没有可恢复状态(编排层保证：run 开始必先写 checkpoint #0)。
     *
     * <p>按 {@code session_id} 限定：检查点表没有 session_id 列，只能借事件表 scoped。
     */
    @Select("""
            SELECT e.`run_id` AS runId, MAX(e.`id`) AS lastEventId
            FROM `agent_events` e
            WHERE e.`session_id` = #{sessionId}
              AND EXISTS (SELECT 1 FROM `agent_checkpoints` c WHERE c.`run_id` = e.`run_id`)
              AND NOT EXISTS (SELECT 1 FROM `agent_events` t
                              WHERE t.`run_id` = e.`run_id`
                                AND t.`type` IN ('run.end','run.error'))
            GROUP BY e.`run_id`
            ORDER BY lastEventId ASC
            """)
    List<UnfinishedRunRow> selectUnfinishedRunIds(@Param("sessionId") String sessionId);

    record UnfinishedRunRow(String runId, Long lastEventId) {
    }
}
