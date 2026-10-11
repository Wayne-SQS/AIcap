package com.aicap.service;

import com.aicap.mapper.AgentCheckpointMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;

/**
 * 检查点保留期清理(S4′.2 待办:检查点含用户话语与项目数据,表会随 run 数无界增长)。
 *
 * <p>两个刻意的选择:
 * <ul>
 *   <li><b>判定用终局事件的时间戳</b>,不用检查点的 {@code created_at} ——
 *       每步 upsert 都会刷新 {@code created_at},按它做保留期等于永不命中;</li>
 *   <li><b>不在 run 终局时立即删</b>:检查点同时是"循环跑到哪了"的可观测面
 *       (测试与排障都读它),终局即删会把这条观察通道一起删掉。留一个窗口更稳。</li>
 * </ul>
 *
 * <p>未终局的 run 一律不动:它们的检查点是唯一的恢复现场。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class AgentCheckpointRetention {

    private final AgentCheckpointMapper checkpointMapper;

    /** 终局后保留多久(小时)。检查点只用于恢复未完成的 run,跑完之后的唯一用途是排障。 */
    @Value("${aicap.agents.checkpoint-retention-hours:24}")
    private int retentionHours;

    /** 每小时跑一次;清理是维护动作,失败只记日志,不影响任何用户链路 */
    @Scheduled(cron = "${aicap.agents.checkpoint-sweep-cron:0 15 * * * *}")
    public void sweep() {
        LocalDateTime cutoff = LocalDateTime.now().minusHours(Math.max(1, retentionHours));
        try {
            int deleted = checkpointMapper.deleteFinishedBefore(cutoff);
            if (deleted > 0) {
                log.info("已清理终局 run 的检查点 {} 行(cutoff={})", deleted, cutoff);
            }
        } catch (Exception e) {
            log.warn("检查点清理失败,下次再试: {}", e.toString());
        }
    }
}
