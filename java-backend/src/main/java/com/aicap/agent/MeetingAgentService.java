package com.aicap.agent;

import com.aicap.common.ApiException;
import com.aicap.entity.Meeting;
import com.aicap.entity.MeetingAgentEvent;
import com.aicap.entity.MeetingAgentRun;
import com.aicap.entity.User;
import com.aicap.mapper.MeetingAgentEventMapper;
import com.aicap.mapper.MeetingAgentRunMapper;
import com.aicap.mapper.MeetingMapper;
import com.aicap.service.MeetingService;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
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
    private final MeetingService meetingService;

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
    public Map<String, Object> createRun(String projectId, String meetingId, User requester) {
        Meeting meeting = meetingService.requireMeeting(projectId, meetingId);
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
    public List<Map<String, Object>> listRuns(String projectId, String meetingId) {
        meetingService.requireMeeting(projectId, meetingId);
        jobs.expireRuns();
        List<MeetingAgentRun> runs = runMapper.selectList(new QueryWrapper<MeetingAgentRun>()
                .eq("meeting_id", meetingId).orderByAsc("created_at").orderByAsc("id"));
        List<Map<String, Object>> out = new ArrayList<>();
        for (MeetingAgentRun run : runs) out.add(runOut(run));
        return out;
    }

    /** GET /agent-runs/{runId}:含事件明细 */
    public Map<String, Object> getRun(String projectId, String runId) {
        jobs.expireRuns();
        MeetingAgentRun run = runMapper.selectById(runId);
        if (run != null) meetingService.requireMeeting(projectId, run.getMeetingId());
        if (run == null) throw ApiException.notFound("分析记录不存在");
        meetingService.requireMeeting(projectId, run.getMeetingId());
        Map<String, Object> out = runOut(run);
        List<MeetingAgentEvent> events = eventMapper.selectList(new QueryWrapper<MeetingAgentEvent>()
                .eq("run_id", runId).orderByAsc("id"));
        List<Map<String, Object>> eventOuts = new ArrayList<>();
        for (MeetingAgentEvent e : events) eventOuts.add(eventOut(e));
        out.put("events", eventOuts);
        return out;
    }

    /** POST /agent-runs/{runId}/retry:仅 failed 可重试,原子认领 */
    public Map<String, Object> retryRun(String projectId, String runId, User requester) {
        jobs.expireRuns();
        MeetingAgentRun run = runMapper.selectById(runId);
        if (run != null) meetingService.requireMeeting(projectId, run.getMeetingId());
        if (run == null) throw ApiException.notFound("分析记录不存在");
        meetingService.requireMeeting(projectId, run.getMeetingId());
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

    /** Keep the model output immutable while storing a human-reviewed minutes draft. */
    @Transactional
    public Map<String, Object> editMinutes(String projectId, String runId, JsonNode edits, User editor) {
        MeetingAgentRun run = runMapper.selectById(runId);
        if (run == null) throw ApiException.notFound("分析记录不存在");
        meetingService.requireMeeting(projectId, run.getMeetingId());
        if (!List.of("awaiting_review", "completed").contains(run.getStatus()) || run.getResultJson() == null) {
            throw ApiException.conflict("分析完成后才能修订会议纪要");
        }
        if (edits == null || !edits.isObject()) throw ApiException.unprocessable("纪要修订格式无效");
        try {
            JsonNode base = objectMapper.readTree(run.getEditedResultJson() == null
                    ? run.getResultJson() : run.getEditedResultJson());
            if (!(base instanceof ObjectNode draft)) throw ApiException.unprocessable("原始分析结果格式无效");
            boolean changed = false;
            if (edits.has("summary")) {
                draft.put("summary", requiredText(edits.get("summary"), 4000));
                changed = true;
            }
            for (String field : List.of("decisions", "action_items")) {
                if (!edits.has(field)) continue;
                JsonNode original = draft.path(field);
                JsonNode replacement = edits.get(field);
                if (!(original instanceof ArrayNode rows) || !replacement.isArray()
                        || replacement.size() != rows.size()) {
                    throw ApiException.unprocessable(field + " 的条目数必须与原始分析一致");
                }
                for (int i = 0; i < rows.size(); i++) {
                    if (!(rows.get(i) instanceof ObjectNode item)) {
                        throw ApiException.unprocessable(field + " 的原始条目格式无效");
                    }
                    item.put("text", requiredText(replacement.get(i), 2000));
                }
                changed = true;
            }
            if (edits.has("unresolved_questions")) {
                JsonNode items = edits.get("unresolved_questions");
                if (!items.isArray() || items.size() > 30) {
                    throw ApiException.unprocessable("未决问题须为不超过 30 条的列表");
                }
                ArrayNode updated = objectMapper.createArrayNode();
                for (JsonNode item : items) updated.add(requiredText(item, 2000));
                draft.set("unresolved_questions", updated);
                changed = true;
            }
            if (!changed) throw ApiException.unprocessable("没有可保存的纪要修订");
            run.setEditedResultJson(objectMapper.writeValueAsString(draft));
            run.setEditedBy(editor.getId());
            run.setEditedAt(LocalDateTime.now());
            runMapper.updateById(run);
            MeetingAgentEvent event = new MeetingAgentEvent();
            event.setRunId(runId);
            event.setAttempt(run.getAttempt());
            event.setKind("minutes_edited");
            event.setDetailJson(objectMapper.writeValueAsString(Map.of(
                    "edited_by", editor.getId(), "edited_result", draft)));
            event.setCreatedAt(run.getEditedAt());
            eventMapper.insert(event);
            return getRun(projectId, runId);
        } catch (ApiException e) {
            throw e;
        } catch (Exception e) {
            throw ApiException.unprocessable("纪要修订无法解析");
        }
    }

    private String requiredText(JsonNode value, int limit) {
        if (value == null || !value.isTextual() || value.asText().isBlank()
                || value.asText().length() > limit) {
            throw ApiException.unprocessable("纪要文字不能为空且不得超过 " + limit + " 字");
        }
        return value.asText().trim();
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
        out.put("edited_result", parseResult(run.getEditedResultJson()));
        out.put("edited_by", run.getEditedBy());
        out.put("edited_at", run.getEditedAt());
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
