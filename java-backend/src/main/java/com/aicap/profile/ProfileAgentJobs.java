package com.aicap.profile;

import com.aicap.entity.ProfileAgentRun;
import com.aicap.mapper.ProfileAgentRunMapper;
import com.aicap.service.ProfileAgentService;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.UpdateWrapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

/**
 * 画像智能体的队列与后台消费(S5′ 触发型工具 {@code start_profile_analysis} 的兑现面)。
 *
 * <p>为什么需要它:原先画像分析只有<b>同步</b>入口 —— {@code runAnalysis} 在 HTTP 请求
 * 线程里跑完 6 次 LLM 调用才返回。这在触发型工具下是行不通的:编排层的有界循环有总预算,
 * 一次全团队画像会把预算吃穿。{@code PlatformToolCatalog} 因此把第三枚触发工具标了"暂缓"。
 * 本类把"建 run"和"跑 run"拆开,让工具只建 run 就返回,执行交给 {@link ProfileWorkerTask}。
 *
 * <p>租约口径与 planning/generation 同形:认领时写 {@code worker_token} + {@code lease_until},
 * 收尾按 token 守卫。没有租约时进程崩溃会让 run 永久停在 {@code running},谁也不回收。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ProfileAgentJobs {

    private final ProfileAgentRunMapper runs;
    private final ProfileAgentService service;

    /**
     * 租约时长(秒):一次全团队画像是「N 名成员各一次画像 + 1 次团队风险」,
     * 单人评估则只有 1 次。按最坏值估:{@code ProfileLlmProperties.maxSteps}(默认 8)
     * × {@code timeoutSeconds}(默认 60s)已接近 480s,再叠加多成员的串行往返,
     * 取 900s 留足余量 —— 租约短于实际运行时间会让另一个实例把还在跑的 run 抢走,
     * 表现为同一份分析被做两遍、快照被覆盖。
     */
    private static final long LEASE_SECONDS = 900;

    private static final String INTERRUPTED_MESSAGE = "分析进程中断或超时，可重试";

    /**
     * 过期 running(租约超时)→ failed。
     *
     * <p>判定含"没有租约且 updated_at 也已陈旧"这第二种情形:补列之前留下的孤儿 run
     * {@code lease_until} 是 NULL,而 {@code NULL < now} 不成立,只按到期判定会永远漏掉它们。
     * 但也不能把 NULL 一律当过期 —— 那会把刚建好还没被认领的 run 误判成中断,故加一个租约时长的宽限。
     */
    public void expireRuns() {
        LocalDateTime now = LocalDateTime.now();
        List<ProfileAgentRun> stale = runs.selectList(new QueryWrapper<ProfileAgentRun>()
                .eq("status", "running")
                .and(w -> w.lt("lease_until", now)
                        .or()
                        .isNull("lease_until")
                        .lt("updated_at", now.minusSeconds(LEASE_SECONDS))));
        for (ProfileAgentRun run : stale) {
            runs.update(null, new UpdateWrapper<ProfileAgentRun>()
                    .set("status", "failed")
                    .set("worker_token", null)
                    .set("lease_until", null)
                    .set("error_message", INTERRUPTED_MESSAGE)
                    .set("updated_at", now)
                    .eq("id", run.getId())
                    .eq("status", "running")
                    .and(w -> w.lt("lease_until", now)
                            .or()
                            .isNull("lease_until")
                            .lt("updated_at", now.minusSeconds(LEASE_SECONDS))));
            log.warn("[profile-agent] run {} 租约过期,已标记 interrupted", run.getId());
        }
    }

    /**
     * 幂等判定(契约 §2.4):同一项目、同一范围、同一目标成员是否已有未完成 run。
     *
     * <p>与 planning 一样靠"查同参数未完成 run"去重 —— {@code profile_agent_runs} 没有唯一键,
     * 而同一范围重复触发没有意义(分析结论只取决于范围与当时的数据)。
     */
    public ProfileAgentRun findUnfinished(String projectId, java.time.LocalDate start,
                                          java.time.LocalDate end, Integer onlyUserId) {
        QueryWrapper<ProfileAgentRun> query = new QueryWrapper<ProfileAgentRun>()
                .eq("project_id", projectId)
                .eq("range_start", start)
                .eq("range_end", end)
                .in("status", "queued", "running");
        if (onlyUserId == null) {
            query.isNull("only_user_id");
        } else {
            query.eq("only_user_id", onlyUserId);
        }
        return runs.selectOne(query.orderByDesc("created_at").last("LIMIT 1"));
    }

    /**
     * 跑一条排队中的 run。
     *
     * @return 有活干返回 true(供 {@code AgentWorker} 决定休眠长短)
     */
    public boolean runNext() {
        expireRuns();
        ProfileAgentRun run = runs.selectOne(new QueryWrapper<ProfileAgentRun>()
                .eq("status", "queued").orderByAsc("created_at").last("LIMIT 1"));
        if (run == null) return false;

        String token = UUID.randomUUID().toString();
        int claimed = runs.update(null, new UpdateWrapper<ProfileAgentRun>()
                .set("status", "running")
                .set("worker_token", token)
                .set("lease_until", LocalDateTime.now().plusSeconds(LEASE_SECONDS))
                .set("updated_at", LocalDateTime.now())
                .eq("id", run.getId()).eq("status", "queued"));
        if (claimed == 0) return true;   // 被别的实例抢走:这一轮就算"有活",下轮再看

        try {
            service.executeRun(run);
        } catch (Exception e) {
            // executeRun 内部已经把 run 置为 failed 并落库分类文案;这里只兜住
            // "连 run 都更新不回去"这种更糟的情况,避免异常冒到 AgentWorker 的热循环里
            log.warn("[profile-agent] run {} 执行失败", run.getId(), e);
            runs.update(null, new UpdateWrapper<ProfileAgentRun>()
                    .set("status", "failed")
                    .set("error_message", "分析失败，运行记录已留痕，可重试")
                    .set("updated_at", LocalDateTime.now())
                    .eq("id", run.getId())
                    .eq("worker_token", token));
        }
        release(run.getId(), token);
        return true;
    }

    /**
     * 归还租约。按 {@code worker_token} 守卫:租约已过期并被他人认领时这里会是空更新,
     * 不会去动新 owner 的租约字段。
     */
    private void release(String runId, String token) {
        runs.update(null, new UpdateWrapper<ProfileAgentRun>()
                .set("worker_token", null)
                .set("lease_until", null)
                .eq("id", runId)
                .eq("worker_token", token));
    }
}
