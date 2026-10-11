package com.aicap.contract;

import com.aicap.service.AgentCheckpointRetention;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * 检查点保留期清理契约(S4′.2 待办)。
 *
 * <p>检查点里装着用户话语与项目数据,run 一旦终局它就没有保留理由,不清理会无界增长。
 * 本类钉住三件事:
 * <ol>
 *   <li>已终局且超过保留期的 → 删;</li>
 *   <li>刚终局的 → 留(检查点同时是"循环跑到哪了"的可观测面);</li>
 *   <li><b>未终局的 → 一行都不能删</b> —— 那是唯一的恢复现场。</li>
 * </ol>
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "spring.datasource.url=jdbc:mysql://127.0.0.1:3307/aicap_java_test?useUnicode=true&characterEncoding=utf8&serverTimezone=Asia/Shanghai&allowPublicKeyRetrieval=true&useSSL=false",
        "spring.datasource.username=aiguanli",
        "spring.datasource.password=aiguanli-2026",
        "aicap.llm.agent-worker-enabled=false",
        "spring.sql.init.data-locations=classpath:db/reset_test_data.sql"
})
class AgentCheckpointRetentionContractTest {

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private AgentCheckpointRetention retention;

    private final List<String> runIds = new ArrayList<>();

    @AfterEach
    void cleanup() {
        for (String id : runIds) {
            jdbc.update("DELETE FROM agent_checkpoints WHERE run_id=?", id);
            jdbc.update("DELETE FROM agent_events WHERE run_id=?", id);
        }
        runIds.clear();
    }

    /** 造一个 run:一条事件(终局或非终局)+ 一个检查点 */
    private String seedRun(String eventType, LocalDateTime eventAt, LocalDateTime checkpointAt) {
        String runId = UUID.randomUUID().toString();
        runIds.add(runId);
        // session_id 是 VARCHAR(36):直接复用 uuid 当会话号,不要拼前缀(会超长)
        jdbc.update("INSERT INTO agent_events (session_id,run_id,type,detail_json,created_at) VALUES (?,?,?,?,?)",
                runId, runId, eventType, "{\"k\":1}", Timestamp.valueOf(eventAt));
        jdbc.update("INSERT INTO agent_checkpoints (run_id,node_name,state_json,created_at) VALUES (?,?,?,?)",
                runId, "loop", "{\"step\":1}", Timestamp.valueOf(checkpointAt));
        return runId;
    }

    private int checkpointsOf(String runId) {
        Integer n = jdbc.queryForObject("SELECT COUNT(*) FROM agent_checkpoints WHERE run_id=?",
                Integer.class, runId);
        return n == null ? 0 : n;
    }

    @Test
    void finishedRunPastRetentionIsSwept() {
        String runId = seedRun("run.end", LocalDateTime.now().minusHours(48), LocalDateTime.now().minusHours(48));
        retention.sweep();
        assertEquals(0, checkpointsOf(runId), "已终局且超过保留期的检查点应被清理");
    }

    @Test
    void justFinishedRunKeepsItsCheckpoint() {
        String runId = seedRun("run.end", LocalDateTime.now(), LocalDateTime.now());
        retention.sweep();
        assertEquals(1, checkpointsOf(runId), "刚终局的检查点不应被立刻删掉(它同时是可观测面)");
    }

    @Test
    void unfinishedRunIsNeverSweptEvenWhenItsCheckpointLooksOld() {
        // 关键安全性质:检查点的 created_at 已经很旧,但该 run 没有终局事件 ——
        // 仍是"未完成、可恢复",它的检查点是唯一的恢复现场。
        // 也正因为如此,保留期判定绝不能看检查点自己的 created_at。
        String runId = seedRun("model.step", LocalDateTime.now().minusHours(72), LocalDateTime.now().minusHours(72));
        retention.sweep();
        assertEquals(1, checkpointsOf(runId), "未终局的 run 一行都不能删");
    }
}
