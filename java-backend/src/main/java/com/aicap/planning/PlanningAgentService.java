package com.aicap.planning;

import com.aicap.agent.AgentProperties;
import com.aicap.common.ApiException;
import com.aicap.entity.PlanningAgentEvent;
import com.aicap.entity.PlanningAgentRun;
import com.aicap.entity.User;
import com.aicap.mapper.PlanningAgentEventMapper;
import com.aicap.mapper.PlanningAgentRunMapper;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.UpdateWrapper;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.transaction.PlatformTransactionManager;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Service
@RequiredArgsConstructor
public class PlanningAgentService {
    private final PlanningAgentJobs jobs;
    private final PlanningAgentRunMapper runs;
    private final PlanningAgentEventMapper events;
    private final PlanningAgentExecutionService execution;
    private final AgentProperties props;
    private final ObjectMapper mapper;
    private final PlatformTransactionManager transactionManager;

    public Map<String, Object> create(String text, User user) {
        if (text == null || text.isBlank()) throw ApiException.badRequest("规划请求不能为空");
        if (!props.settingsReady()) throw ApiException.server("服务端尚未配置模型密钥");
        return out(jobs.queue(text.trim(), user.getId()));
    }

    public Map<String, Object> get(String id) {
        PlanningAgentRun run = runs.selectById(id);
        if (run == null) throw ApiException.notFound("规划运行不存在");
        Map<String, Object> result = out(run);
        List<Map<String, Object>> eventList = new ArrayList<>();
        for (PlanningAgentEvent event : events.selectList(new QueryWrapper<PlanningAgentEvent>()
                .eq("run_id", id).orderByAsc("id"))) {
            eventList.add(Map.of("kind", event.getKind(), "detail", event.getDetailJson(),
                    "created_at", event.getCreatedAt()));
        }
        result.put("events", eventList);
        return result;
    }

    public Map<String, Object> confirm(String id, User user) {
        PlanningAgentRun run = runs.selectById(id);
        if (run == null) throw ApiException.notFound("规划运行不存在");
        if (!"waiting_confirmation".equals(run.getStatus())) {
            throw ApiException.conflict("该规划不在待确认状态");
        }
        if (claimForExecution(id) != 1) throw ApiException.conflict("该规划已被处理，请刷新结果");
        jobs.event(run, "confirmed", "用户确认执行规划");
        try {
            JsonNode plan = mapper.readTree(run.getResultJson());
            for (JsonNode action : plan.path("actions")) {
                jobs.event(run, "action_started", "开始执行 " + action.path("type").asText()
                        + " " + actionTarget(action));
            }
            execution.validateAndExecute(run.getResultJson(), user);
            for (JsonNode action : plan.path("actions")) {
                jobs.event(run, "action_completed", "已完成 " + action.path("type").asText()
                        + " " + actionTarget(action));
            }
            run.setStatus("completed");
            run.setUpdatedAt(LocalDateTime.now());
            transaction(() -> {
                runs.updateById(run);
                jobs.event(run, "completed", "规划执行完成，业务数据已更新");
                return null;
            });
            return out(run);
        } catch (ApiException e) {
            markFailed(run, "execution_failed", e.getMessage());
            throw e;
        } catch (Exception e) {
            String message = e.getMessage() == null ? "确认执行失败，未完成规划修改" : e.getMessage();
            markFailed(run, "execution_failed", message);
            throw ApiException.conflict(message);
        }
    }

    public Map<String, Object> cancel(String id) {
        PlanningAgentRun run = runs.selectById(id);
        if (run == null) throw ApiException.notFound("规划运行不存在");
        if (cancelWaiting(id) != 1) throw ApiException.conflict("该规划已被处理，请刷新结果");
        run.setStatus("cancelled");
        run.setUpdatedAt(LocalDateTime.now());
        jobs.event(run, "cancelled", "用户取消规划，未修改业务数据");
        return out(run);
    }

    private void markFailed(PlanningAgentRun run, String code, String message) {
        transaction(() -> {
            PlanningAgentRun current = runs.selectById(run.getId());
            if (current == null) return null;
            current.setStatus("failed");
            current.setErrorCode(code);
            current.setErrorMessage(message);
            current.setUpdatedAt(LocalDateTime.now());
            runs.updateById(current);
            jobs.event(current, "failed", message);
            return null;
        });
    }

    private int claimForExecution(String id) {
        return transaction(() -> runs.update(null, new UpdateWrapper<PlanningAgentRun>()
                .set("status", "executing").set("updated_at", LocalDateTime.now())
                .eq("id", id).eq("status", "waiting_confirmation")));
    }

    private int cancelWaiting(String id) {
        return transaction(() -> runs.update(null, new UpdateWrapper<PlanningAgentRun>()
                .set("status", "cancelled").set("updated_at", LocalDateTime.now())
                .eq("id", id).eq("status", "waiting_confirmation")));
    }

    private <T> T transaction(java.util.function.Supplier<T> work) {
        TransactionTemplate template = new TransactionTemplate(transactionManager);
        return template.execute(status -> work.get());
    }

    private String actionTarget(JsonNode action) {
        if (action.hasNonNull("task_id")) return action.path("task_id").asText();
        if (action.hasNonNull("milestone_id")) return action.path("milestone_id").asText();
        return action.path("story_id").asText();
    }

    private Map<String, Object> out(PlanningAgentRun run) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("id", run.getId());
        result.put("request_text", run.getRequestText());
        result.put("status", run.getStatus());
        result.put("attempt", run.getAttempt());
        result.put("model", run.getModel());
        try { result.put("result", run.getResultJson() == null ? null : mapper.readTree(run.getResultJson())); }
        catch (Exception e) { result.put("result", null); }
        result.put("error_code", run.getErrorCode());
        result.put("error_message", run.getErrorMessage());
        result.put("created_at", run.getCreatedAt());
        result.put("updated_at", run.getUpdatedAt());
        return result;
    }
}
