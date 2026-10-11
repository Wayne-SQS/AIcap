package com.aicap.planning;

import com.aicap.llm.LlmProperties;
import com.aicap.llm.ModelClient;
import com.aicap.common.AgentError;
import com.aicap.entity.PlanningAgentEvent;
import com.aicap.entity.PlanningAgentRun;
import com.aicap.mapper.PlanningAgentEventMapper;
import com.aicap.mapper.PlanningAgentRunMapper;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.UpdateWrapper;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

/** Planning Agent 的排队、推理和审计事件生命周期。 */
@Component
@RequiredArgsConstructor
public class PlanningAgentJobs {
    private final PlanningAgentRunMapper runs;
    private final PlanningAgentEventMapper events;
    private final PlanningAgentRunner runner;
    private final PlanningAgentTools tools;
    private final ModelClient client;
    private final LlmProperties props;
    private final ObjectMapper mapper;
    private final TransactionTemplate tx;

    /**
     * 租约时长(秒):必须大于最坏运行时间(MAX_STEPS 6 × 单次模型超时 45s = 270s),
     * 对齐会议侧的 300s。租约短于实际运行时间会让别的进程把还在跑的 run 抢走。
     */
    private static final long LEASE_SECONDS = 300;

    private static final String INTERRUPTED_MESSAGE = "分析进程中断或超时，可重试";

    public PlanningAgentRun queue(String text, int user) {
        PlanningAgentRun run = new PlanningAgentRun();
        run.setId(UUID.randomUUID().toString());
        run.setRequestedBy(user);
        run.setRequestText(text);
        run.setStatus("queued");
        run.setAttempt(1);
        run.setModel(props.getModel());
        run.setPromptVersion("planning-owner-v1");
        run.setContextJson(tools.contextSnapshot().toString());
        run.setCreatedAt(LocalDateTime.now());
        run.setUpdatedAt(LocalDateTime.now());
        tx.executeWithoutResult(status -> {
            runs.insert(run);
            event(run, "queued", "规划请求已入队");
        });
        return run;
    }

    /**
     * 过期 running(租约超时)→ failed + interrupted 事件(读/写路由进入时也会先调用)。
     *
     * <p>判定分两种,缺一不可:① 有租约且已到期;② <b>没有租约</b>且 {@code updated_at} 也已陈旧 ——
     * 后者是补列之前留下的孤儿(run 已卡死很久),而 {@code NULL < now} 不成立,
     * 只按到期判定会永远漏掉它们;但也不能把 NULL 一律当过期,
     * 否则会把正在进行的非 worker 路径(如 generation 的表格导入)误判成中断,
     * 故对 NULL 加一个租约时长的宽限。
     */
    public void expireRuns() {
        LocalDateTime now = LocalDateTime.now();
        List<PlanningAgentRun> stale = runs.selectList(new QueryWrapper<PlanningAgentRun>()
                .eq("status", "running")
                .and(w -> w.lt("lease_until", now)
                        .or()
                        .isNull("lease_until")
                        .lt("updated_at", now.minusSeconds(LEASE_SECONDS))));
        for (PlanningAgentRun run : stale) {
            int claimed = runs.update(null, new UpdateWrapper<PlanningAgentRun>()
                    .set("status", "failed")
                    .set("worker_token", null)
                    .set("lease_until", null)
                    .set("error_code", "interrupted")
                    .set("error_message", INTERRUPTED_MESSAGE)
                    .set("updated_at", now)
                    .eq("id", run.getId())
                    .eq("status", "running")
                    .and(w -> w.lt("lease_until", now)
                            .or()
                            .isNull("lease_until")
                            .lt("updated_at", now.minusSeconds(LEASE_SECONDS))));
            if (claimed > 0) {
                event(run, "failed", INTERRUPTED_MESSAGE);
            }
        }
    }

    /**
     * 触发型工具({@code start_plan_draft})的幂等判定(契约 §2.4):
     * 同一 {@code request_text} 是否已有未完成 run。
     *
     * <p>{@code planning_agent_runs} 没有唯一键,所以"同一请求重复触发"只能这样判;
     * 未完成含 {@code waiting_confirmation} —— 草案还在等人确认时再触发一次没有意义。
     */
    public PlanningAgentRun findUnfinishedByRequest(String requestText) {
        if (requestText == null || requestText.isBlank()) {
            return null;
        }
        return runs.selectOne(new QueryWrapper<PlanningAgentRun>()
                .eq("request_text", requestText)
                .in("status", "queued", "running", "waiting_confirmation")
                .orderByDesc("created_at").last("LIMIT 1"));
    }

    public boolean runNext() {
        expireRuns();
        PlanningAgentRun run = runs.selectOne(new QueryWrapper<PlanningAgentRun>()
                .eq("status", "queued").orderByAsc("created_at").last("LIMIT 1"));
        if (run == null) return false;
        String token = UUID.randomUUID().toString();
        int claimed = runs.update(null, new UpdateWrapper<PlanningAgentRun>()
                .set("status", "running")
                .set("worker_token", token)
                .set("lease_until", LocalDateTime.now().plusSeconds(LEASE_SECONDS))
                .set("updated_at", LocalDateTime.now())
                .eq("id", run.getId()).eq("status", "queued"));
        if (claimed == 0) return true;
        event(run, "running", "画图智能体开始读取项目数据并分析");
        try {
            client.setModelOverride(run.getModel());
            ObjectNode result = runner.analyze(run.getRequestText(), client,
                    (kind, detail) -> event(run, kind, detail));
            run.setResultJson(result.toString());
            event(run, "plan_generated", "已生成结构化修改方案");
            event(run, "impact_analyzed", "已完成成员负载、依赖、Sprint、延期及后续任务影响分析");
            boolean requiresConfirmation = result.path("requires_confirmation").asBoolean(false);
            run.setStatus(requiresConfirmation ? "waiting_confirmation" : "completed");
            // 租约若已过期且被别的实例认领,这里必须放弃收尾,不能用陈旧结果覆盖新 owner
            if (!finish(run, token, null)) return true;
            event(run, requiresConfirmation ? "waiting_confirmation" : "completed",
                    requiresConfirmation ? "规划已生成，等待人工确认" : "查询结果无需修改项目数据");
        } catch (AgentError error) {
            fail(run, token, error);
        } catch (Exception error) {
            fail(run, token, new AgentError("internal_error", "规划分析失败，请稍后重试"));
        } finally {
            client.clearModelOverride();
        }
        return true;
    }

    private void fail(PlanningAgentRun run, String token, AgentError error) {
        run.setStatus("failed");
        if (!finish(run, token, error)) return;
        event(run, "failed", error.getMessage());
    }

    /**
     * 收尾写库:按 {@code worker_token} 守卫 —— 租约过期且已被他人认领时放弃写入。
     * 成功时清空租约列,让 run 回到"无 owner"的正常终态。
     */
    private boolean finish(PlanningAgentRun run, String token, AgentError error) {
        UpdateWrapper<PlanningAgentRun> update = new UpdateWrapper<PlanningAgentRun>()
                .set("status", run.getStatus())
                .set("worker_token", null)
                .set("lease_until", null)
                .set("updated_at", LocalDateTime.now())
                .eq("id", run.getId())
                .eq("status", "running")
                .eq("worker_token", token);
        if (error != null) {
            update.set("error_code", error.getCode()).set("error_message", error.getMessage());
        } else {
            update.set("result_json", run.getResultJson());
        }
        return runs.update(null, update) > 0;
    }

    public void event(PlanningAgentRun run, String kind, String message) {
        PlanningAgentEvent event = new PlanningAgentEvent();
        event.setRunId(run.getId());
        event.setAttempt(run.getAttempt());
        event.setKind(kind);
        ObjectNode detail = mapper.createObjectNode();
        detail.put("message", message == null ? "" : message);
        event.setDetailJson(detail.toString());
        event.setCreatedAt(LocalDateTime.now());
        events.insert(event);
    }
}
