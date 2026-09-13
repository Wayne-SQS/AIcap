package com.aicap.agent;

import com.aicap.common.ApiException;
import com.aicap.entity.Meeting;
import com.aicap.entity.MeetingAgentEvent;
import com.aicap.entity.MeetingAgentRun;
import com.aicap.entity.User;
import com.aicap.mapper.MeetingAgentEventMapper;
import com.aicap.mapper.MeetingAgentRunMapper;
import com.aicap.mapper.MeetingMapper;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 会议 Agent Web 语义 + 输出组装(对齐 FastAPI routers/agent.py)。
 * 写路径:先过期清理 → 幂等(每会议至多一个 run)→ 原子入队/重试;worker 负责消费。
 */
@Service
@RequiredArgsConstructor
public class MeetingAgentService {

    private final MeetingMapper meetingMapper;
    private final MeetingAgentRunMapper runMapper;
    private final MeetingAgentEventMapper eventMapper;
    private final AgentJobs jobs;
    private final AgentProperties props;
    private final ObjectMapper objectMapper;

    // ---------- 配置 ----------

    public Map<String, Object> config() {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("configured", props.settingsReady());
        out.put("model", props.getModel());
        out.put("worker_enabled", props.isAgentWorkerEnabled());
        out.put("supported_actions", List.of("pool.create"));
        out.put("prompt_version", AgentProperties.PROMPT_VERSION);
        return out;
    }

    // ---------- runs ----------

    /** POST /meetings/{id}/runs:已存在则直接返回既有 run(不重复排队) */
    public Map<String, Object> createRun(String meetingId, User requester) {
        Meeting meeting = meetingMapper.selectById(meetingId);
        if (meeting == null) throw ApiException.notFound("会议不存在");
        jobs.expireRuns();
        MeetingAgentRun existing = runMapper.selectOne(new QueryWrapper<MeetingAgentRun>()
                .eq("meeting_id", meetingId));
        if (existing != null) return runOut(existing);
        if (!props.settingsReady()) {
            throw ApiException.server("服务端尚未配置 DeepSeek 密钥，手动建议仍可使用");
        }
        return runOut(jobs.queueRun(meetingId, requester.getId()));
    }

    /** GET /meetings/{id}/runs */
    public List<Map<String, Object>> listRuns(String meetingId) {
        if (meetingMapper.selectById(meetingId) == null) throw ApiException.notFound("会议不存在");
        jobs.expireRuns();
        List<MeetingAgentRun> runs = runMapper.selectList(new QueryWrapper<MeetingAgentRun>()
                .eq("meeting_id", meetingId).orderByAsc("created_at").orderByAsc("id"));
        List<Map<String, Object>> out = new ArrayList<>();
        for (MeetingAgentRun run : runs) out.add(runOut(run));
        return out;
    }

    /** GET /agent-runs/{runId}:含事件明细 */
    public Map<String, Object> getRun(String runId) {
        jobs.expireRuns();
        MeetingAgentRun run = runMapper.selectById(runId);
        if (run == null) throw ApiException.notFound("分析记录不存在");
        Map<String, Object> out = runOut(run);
        List<MeetingAgentEvent> events = eventMapper.selectList(new QueryWrapper<MeetingAgentEvent>()
                .eq("run_id", runId).orderByAsc("id"));
        List<Map<String, Object>> eventOuts = new ArrayList<>();
        for (MeetingAgentEvent e : events) eventOuts.add(eventOut(e));
        out.put("events", eventOuts);
        return out;
    }

    /** POST /agent-runs/{runId}/retry:仅 failed 可重试,原子认领 */
    public Map<String, Object> retryRun(String runId, User requester) {
        jobs.expireRuns();
        MeetingAgentRun run = runMapper.selectById(runId);
        if (run == null) throw ApiException.notFound("分析记录不存在");
        if (!"failed".equals(run.getStatus())) {
            throw ApiException.conflict("只能重试失败的分析；已生成的建议不会再次生成");
        }
        if (!props.settingsReady()) {
            throw ApiException.server("服务端尚未配置模型密钥");
        }
        MeetingAgentRun updated = jobs.retryClaim(runId, requester.getId());
        if (updated == null) throw ApiException.conflict("任务状态已变化，请刷新");
        return runOut(updated);
    }

    // ---------- 组装 ----------

    private Map<String, Object> runOut(MeetingAgentRun run) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("id", run.getId());
        out.put("meeting_id", run.getMeetingId());
        out.put("requested_by", run.getRequestedBy());
        out.put("status", run.getStatus());
        out.put("attempt", run.getAttempt());
        out.put("model", run.getModel());
        out.put("prompt_version", run.getPromptVersion());
        out.put("error_code", run.getErrorCode());
        out.put("error_message", run.getErrorMessage());
        out.put("created_at", run.getCreatedAt());
        out.put("updated_at", run.getUpdatedAt());
        out.put("result", parseResult(run.getResultJson()));
        return out;
    }

    private Map<String, Object> eventOut(MeetingAgentEvent e) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("id", e.getId());
        out.put("attempt", e.getAttempt());
        out.put("kind", e.getKind());
        out.put("detail", parseDetail(e.getDetailJson()));
        out.put("created_at", e.getCreatedAt());
        return out;
    }

    private JsonNode parseResult(String json) {
        if (json == null || json.isBlank()) return null;
        try {
            return objectMapper.readTree(json);
        } catch (Exception ignored) {
            return null;
        }
    }

    private JsonNode parseDetail(String json) {
        try {
            return objectMapper.readTree(json == null ? "{}" : json);
        } catch (Exception ignored) {
            return objectMapper.createObjectNode();
        }
    }
}
