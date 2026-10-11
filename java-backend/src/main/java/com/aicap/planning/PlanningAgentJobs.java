package com.aicap.planning;

import com.aicap.agent.AgentError;
import com.aicap.agent.AgentProperties;
import com.aicap.agent.ModelClient;
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
    private final AgentProperties props;
    private final ObjectMapper mapper;
    private final TransactionTemplate tx;

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

    public boolean runNext() {
        PlanningAgentRun run = runs.selectOne(new QueryWrapper<PlanningAgentRun>()
                .eq("status", "queued").orderByAsc("created_at").last("LIMIT 1"));
        if (run == null) return false;
        int claimed = runs.update(null, new UpdateWrapper<PlanningAgentRun>()
                .set("status", "running").set("updated_at", LocalDateTime.now())
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
            run.setUpdatedAt(LocalDateTime.now());
            runs.updateById(run);
            event(run, requiresConfirmation ? "waiting_confirmation" : "completed",
                    requiresConfirmation ? "规划已生成，等待人工确认" : "查询结果无需修改项目数据");
        } catch (AgentError error) {
            fail(run, error);
        } catch (Exception error) {
            fail(run, new AgentError("internal_error", "规划分析失败，请稍后重试"));
        } finally {
            client.clearModelOverride();
        }
        return true;
    }

    private void fail(PlanningAgentRun run, AgentError error) {
        run.setStatus("failed");
        run.setErrorCode(error.getCode());
        run.setErrorMessage(error.getMessage());
        run.setUpdatedAt(LocalDateTime.now());
        runs.updateById(run);
        event(run, "failed", error.getMessage());
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
