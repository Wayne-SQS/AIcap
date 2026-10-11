package com.aicap.contract;

import com.aicap.planning.PlanningAgentJobs;
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
 * 队列租约回收契约。
 *
 * <p>背景:{@code planning/} 与 {@code generation/} 两个队列原先没有租约 ——
 * 进程崩溃(或在 event 插入处抛错)后 run 永久停在 {@code running},任何路径都不回收,
 * 任务等于永久丢单。本类钉住回收判定,尤其是两个容易写错的边界:
 * ① 补列之前留下的 run,{@code lease_until} 为 NULL,而 {@code NULL < now} 不成立,
 * 只按"小于现在"判定会永远漏掉这些孤儿;
 * ② 但也不能把 NULL 一律当过期 —— generation 的表格导入在<b>请求线程</b>里也会置
 * {@code running},一律当过期会把正在进行的导入误判成中断。
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "spring.datasource.url=jdbc:mysql://127.0.0.1:3307/aicap_java_test?useUnicode=true&characterEncoding=utf8&serverTimezone=Asia/Shanghai&allowPublicKeyRetrieval=true&useSSL=false",
        "spring.datasource.username=aiguanli",
        "spring.datasource.password=aiguanli-2026",
        "aicap.llm.agent-worker-enabled=false",
        "spring.sql.init.data-locations=classpath:db/reset_test_data.sql"
})
class AgentQueueLeaseContractTest {

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private PlanningAgentJobs planningJobs;

    private final List<String> runIds = new ArrayList<>();

    @AfterEach
    void cleanup() {
        for (String id : runIds) {
            jdbc.update("DELETE FROM planning_agent_events WHERE run_id=?", id);
            jdbc.update("DELETE FROM planning_agent_runs WHERE id=?", id);
        }
        runIds.clear();
    }

    /** 直接插一条指定租约/时间戳的 running run;列名包含本轮补的 worker_token/lease_until */
    private String insertRunningRun(LocalDateTime leaseUntil, LocalDateTime updatedAt) {
        String id = UUID.randomUUID().toString();
        runIds.add(id);
        jdbc.update("INSERT INTO planning_agent_runs (id,requested_by,request_text,status,attempt,model,"
                        + "prompt_version,context_json,result_json,error_code,error_message,worker_token,"
                        + "lease_until,created_at,updated_at) VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)",
                id, 1, "测试请求", "running", 1, "deepseek-v4-flash", "planning-owner-v1",
                "{}", null, null, null, "worker-token",
                leaseUntil == null ? null : Timestamp.valueOf(leaseUntil),
                Timestamp.valueOf(updatedAt), Timestamp.valueOf(updatedAt));
        return id;
    }

    private String statusOf(String id) {
        return jdbc.queryForObject("SELECT status FROM planning_agent_runs WHERE id=?", String.class, id);
    }

    @Test
    void staleLeaseIsReclaimedAsInterrupted() {
        String id = insertRunningRun(LocalDateTime.now().minusMinutes(1), LocalDateTime.now().minusMinutes(10));
        planningJobs.expireRuns();
        assertEquals("failed", statusOf(id));
        assertEquals("interrupted", jdbc.queryForObject(
                "SELECT error_code FROM planning_agent_runs WHERE id=?", String.class, id));
        // 必须留事件:否则用户点开只看到 failed,不知道发生了什么、能不能重试
        assertEquals(1, jdbc.queryForObject(
                "SELECT COUNT(*) FROM planning_agent_events WHERE run_id=? AND kind='failed'",
                Integer.class, id));
    }

    @Test
    void freshLeaseIsLeftAlone() {
        String id = insertRunningRun(LocalDateTime.now().plusMinutes(5), LocalDateTime.now());
        planningJobs.expireRuns();
        assertEquals("running", statusOf(id));
    }

    @Test
    void legacyOrphanWithoutLeaseIsAlsoReclaimed() {
        String id = insertRunningRun(null, LocalDateTime.now().minusMinutes(30));
        planningJobs.expireRuns();
        assertEquals("failed", statusOf(id));
    }

    @Test
    void runningWithoutLeaseButFreshTimestampIsNotKilled() {
        String id = insertRunningRun(null, LocalDateTime.now());
        planningJobs.expireRuns();
        assertEquals("running", statusOf(id));
    }
}
