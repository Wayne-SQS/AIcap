package com.aicap.agent;

import com.aicap.common.ApiException;
import com.aicap.dto.MeetingDtos;
import com.aicap.entity.Meeting;
import com.aicap.entity.MeetingAgentEvent;
import com.aicap.entity.MeetingAgentRun;
import com.aicap.entity.PoolItem;
import com.aicap.entity.Story;
import com.aicap.entity.Suggestion;
import com.aicap.entity.User;
import com.aicap.mapper.MeetingAgentEventMapper;
import com.aicap.mapper.MeetingAgentRunMapper;
import com.aicap.mapper.MeetingMapper;
import com.aicap.mapper.PoolItemMapper;
import com.aicap.mapper.StoryMapper;
import com.aicap.mapper.SuggestionMapper;
import com.aicap.mapper.UserMapper;
import com.aicap.service.MeetingService;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.UpdateWrapper;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;

/**
 * 会议 Agent 数据库队列(对齐 FastAPI meeting_agent/jobs.py):
 * - Web 入口:queueRun / retryClaim(幂等 + 唯一键并发兜底)
 * - Worker 入口:runNext(原子认领 + 租约 + 事件回灌 + 建议与结果全有或全无落库)
 * 所有写操作以独立事务提交,不跨长时间占用连接(LLM 调用期间无持库事务)。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class AgentJobs {

    private final MeetingAgentRunMapper runMapper;
    private final MeetingAgentEventMapper eventMapper;
    private final MeetingMapper meetingMapper;
    private final UserMapper userMapper;
    private final StoryMapper storyMapper;
    private final PoolItemMapper poolItemMapper;
    private final SuggestionMapper suggestionMapper;
    private final MeetingService meetingService;
    private final AgentRunner runner;
    private final ModelClient client;
    private final AgentProperties props;
    private final ObjectMapper mapper;
    private final TransactionTemplate tx;

    // ============================================================
    // Web 入口(幂等排队 / 失败重试)
    // ============================================================

    /** POST /meetings/{id}/runs:无既有 run 则创建 queued 并回事件;并发撞唯一键 → 重查返回既有 */
    public MeetingAgentRun queueRun(String meetingId, int requesterId) {
        MeetingAgentRun run = newRun(meetingId, requesterId, 1);
        try {
            tx.executeWithoutResult(status -> {
                runMapper.insert(run);
                event(run, "queued", queuedDetail(requesterId, false));
            });
            return run;
        } catch (DuplicateKeyException e) {
            MeetingAgentRun existing = runMapper.selectOne(new QueryWrapper<MeetingAgentRun>()
                    .eq("meeting_id", meetingId));
            if (existing != null) return existing;
            throw ApiException.conflict("创建分析任务冲突，请重试");
        }
    }

    /** POST /agent-runs/{id}/retry:原子认领 failed → queued;认领失败返回 null(409 由上层给出) */
    public MeetingAgentRun retryClaim(String runId, int requesterId) {
        LocalDateTime now = LocalDateTime.now();
        MeetingAgentRun run = runMapper.selectById(runId);
        if (run == null) return null;
        int attempt = run.getAttempt() == null ? 0 : run.getAttempt();
        int claimed = runMapper.update(null, new UpdateWrapper<MeetingAgentRun>()
                .set("status", "queued")
                .set("requested_by", requesterId)
                .setSql("attempt = attempt + 1")
                .set("model", props.getModel())
                .set("prompt_version", AgentProperties.PROMPT_VERSION)
                .set("error_code", null)
                .set("error_message", null)
                .set("worker_token", null)
                .set("lease_until", null)
                .set("updated_at", now)
                .eq("id", runId)
                .eq("status", "failed"));
        if (claimed == 0) return null;
        MeetingAgentRun refreshed = runMapper.selectById(runId);
        tx.executeWithoutResult(status -> event(refreshed, "queued", queuedDetail(requesterId, true)));
        return refreshed;
    }

    /** 过期 running(租约超时)→ failed + interrupted 事件(读/写路由进入时也会先调用) */
    public void expireRuns() {
        LocalDateTime now = LocalDateTime.now();
        List<MeetingAgentRun> expired = runMapper.selectList(new QueryWrapper<MeetingAgentRun>()
                .eq("status", "running")
                .lt("lease_until", now));
        if (expired.isEmpty()) return;
        for (MeetingAgentRun run : expired) {
            int claimed = runMapper.update(null, new UpdateWrapper<MeetingAgentRun>()
                    .set("status", "failed")
                    .set("worker_token", null)
                    .set("error_code", "interrupted")
                    .set("error_message", "分析进程中断或超时，可重试")
                    .set("updated_at", now)
                    .eq("id", run.getId())
                    .eq("status", "running")
                    .lt("lease_until", now));
            if (claimed > 0) {
                tx.executeWithoutResult(status -> {
                    ObjectNode detail = mapper.createObjectNode();
                    detail.put("code", "interrupted");
                    detail.put("message", "分析进程中断或超时，可重试");
                    event(run, "failed", detail);
                });
            }
        }
    }

    // ============================================================
    // Worker 单步(队列消费)
    // ============================================================

    /** 消费一步:认领最老 queued → running(带租约)→ 分析 → 建议+结果全有或全无落库;返回是否有事可做 */
    public boolean runNext() {
        expireRuns();
        ClaimOutcome claim = claimNext();
        if (claim.kind == ClaimKind.NONE) return false;
        if (claim.kind == ClaimKind.LOST) return true;
        String runId = claim.runId;
        String token = claim.token;
        try {
            MeetingAgentRun run = runMapper.selectById(runId);
            if (run == null || !"running".equals(run.getStatus()) || !token.equals(run.getWorkerToken())) {
                return true; // 状态已被并发改写,不再处理
            }
            Meeting meeting = meetingMapper.selectById(run.getMeetingId());
            if (meeting == null) {
                throw new AgentError("internal_error", "会议不存在，无法分析");
            }
            emit(runId, token, "started", startedDetail(run));
            client.setModelOverride(run.getModel());
            ObjectNode analysis = runner.analyze(meeting.getTranscript(), run.getRequestedBy(), client,
                    (kind, detail) -> emit(runId, token, kind, detail),
                    () -> requireActive(runId, token));
            complete(runId, token, analysis);
        } catch (AgentError e) {
            fail(runId, token, e);
        } catch (Exception e) {
            log.warn("meeting-agent run {} failed internally: {}", runId, e.toString());
            fail(runId, token, new AgentError("internal_error",
                    "分析失败，未保存建议；请稍后重试或检查服务端"));
        }
        return true;
    }

    private enum ClaimKind {
        NONE, LOST, CLAIMED
    }

    private static final class ClaimOutcome {
        final ClaimKind kind;
        final String runId;
        final String token;

        ClaimOutcome(ClaimKind kind, String runId, String token) {
            this.kind = kind;
            this.runId = runId;
            this.token = token;
        }
    }

    private ClaimOutcome claimNext() {
        final String[] runIdAndToken = new String[2];
        tx.executeWithoutResult(status -> {
            MeetingAgentRun candidate = runMapper.selectOne(new QueryWrapper<MeetingAgentRun>()
                    .eq("status", "queued").orderByAsc("created_at").last("LIMIT 1"));
            if (candidate == null) return;
            String token = UUID.randomUUID().toString();
            LocalDateTime now = LocalDateTime.now();
            int rows = runMapper.update(null, new UpdateWrapper<MeetingAgentRun>()
                    .set("status", "running")
                    .set("worker_token", token)
                    .set("lease_until", now.plusSeconds(props.LEASE_SECONDS))
                    .set("updated_at", now)
                    .eq("id", candidate.getId())
                    .eq("status", "queued"));
            if (rows > 0) {
                runIdAndToken[0] = candidate.getId();
                runIdAndToken[1] = token;
            }
        });
        if (runIdAndToken[0] == null) return new ClaimOutcome(ClaimKind.NONE, null, null);
        return new ClaimOutcome(ClaimKind.CLAIMED, runIdAndToken[0], runIdAndToken[1]);
    }

    // ============================================================
    // 事件 / 存活 / 完成 / 失败
    // ============================================================

    private MeetingAgentRun requireActive(String runId, String token) throws AgentError {
        MeetingAgentRun run = runMapper.selectById(runId);
        if (run == null || !"running".equals(run.getStatus()) || token == null || !token.equals(run.getWorkerToken())) {
            throw new AgentError("interrupted", "分析已中断，可重试");
        }
        if (run.getLeaseUntil() == null || run.getLeaseUntil().isBefore(LocalDateTime.now())) {
            throw new AgentError("interrupted", "分析租约超时，可重试");
        }
        return run;
    }

    /** 每次事件独立事务提交(先校验存活,再落库),便于前端轮询看到过程 */
    private void emit(String runId, String token, String kind, JsonNode detail) throws AgentError {
        tx.executeWithoutResult(status -> {
            MeetingAgentRun run = requireActive(runId, token);
            event(run, kind, detail);
        });
    }

    /** 最终提交:建议逐条去重落库 + run 收尾 + 事件,单事务全有或全无 */
    private void complete(String runId, String token, ObjectNode analysis) throws AgentError {
        tx.executeWithoutResult(status -> {
            MeetingAgentRun run = requireActive(runId, token);
            LocalDateTime now = LocalDateTime.now();
            int claimed = runMapper.update(null, new UpdateWrapper<MeetingAgentRun>()
                    .set("updated_at", now)
                    .eq("id", runId)
                    .eq("status", "running")
                    .eq("worker_token", token)
                    .ge("lease_until", now));
            if (claimed == 0) {
                throw new AgentError("interrupted", "分析已中断，可重试");
            }
            User user = userMapper.selectById(run.getRequestedBy());
            if (user == null || !AgentTools.WRITER_ROLES.contains(user.getRole())) {
                throw new AgentError("permission_changed", "发起人已失去提交权限");
            }
            Set<String> existingTitles = existingTitlePool();
            List<String> ids = new ArrayList<>();
            List<ObjectNode> skipped = new ArrayList<>();
            ArrayNode proposals = (ArrayNode) analysis.path("proposals");
            for (int i = 0; i < proposals.size(); i++) {
                ObjectNode proposal = (ObjectNode) proposals.get(i);
                String title = proposal.path("title").asText().trim();
                if (existingTitles.contains(title.toLowerCase(Locale.ROOT))) {
                    ObjectNode skip = mapper.createObjectNode();
                    skip.put("title", title);
                    skip.put("reason", "已有同名故事、需求或待审建议，请人工核对");
                    skipped.add(skip);
                    continue;
                }
                existingTitles.add(title.toLowerCase(Locale.ROOT));
                MeetingDtos.PoolChanges changes = new MeetingDtos.PoolChanges(title,
                        proposal.path("description").asText(""), "Could");
                MeetingDtos.SuggestionIn in = new MeetingDtos.SuggestionIn(
                        run.getMeetingId(), "agent:" + runId + ":" + i, "pool.create", "agent",
                        proposal.path("evidence").path("quote").asText(""),
                        proposal.path("note").asText("") + "\n初始优先级 Could 为系统默认，待审核确认。",
                        changes);
                Suggestion suggestion = meetingService.stage(in, user).suggestion();
                ids.add(suggestion.getId());
            }
            ObjectNode result = analysis.deepCopy();
            ArrayNode suggestionIds = mapper.createArrayNode();
            ids.forEach(suggestionIds::add);
            result.set("suggestion_ids", suggestionIds);
            ArrayNode skippedArr = mapper.createArrayNode();
            skippedArr.addAll(skipped);
            result.set("skipped_proposals", skippedArr);
            ArrayNode limitations = mapper.createArrayNode();
            limitations.add("未配置真实成员容量/任务状态/当前Sprint日期；不自动修改任务和故事");
            result.set("limitations", limitations);

            run.setResultJson(write(result));
            run.setStatus(ids.isEmpty() ? "completed" : "awaiting_review");
            run.setWorkerToken(null);
            run.setLeaseUntil(null);
            run.setUpdatedAt(now);
            runMapper.updateById(run);
            ObjectNode doneDetail = mapper.createObjectNode();
            doneDetail.set("suggestion_ids", suggestionIds);
            doneDetail.set("skipped", skippedArr);
            event(run, "completed", doneDetail);
        });
    }

    private void fail(String runId, String token, AgentError error) {
        try {
            tx.executeWithoutResult(status -> {
                MeetingAgentRun run = runMapper.selectById(runId);
                if (run == null) return;
                int claimed = runMapper.update(null, new UpdateWrapper<MeetingAgentRun>()
                        .set("status", "failed")
                        .set("worker_token", null)
                        .set("lease_until", null)
                        .set("error_code", error.getCode())
                        .set("error_message", error.getMessage())
                        .set("updated_at", LocalDateTime.now())
                        .eq("id", runId)
                        .eq("status", "running")
                        .eq("worker_token", token));
                if (claimed > 0) {
                    ObjectNode detail = mapper.createObjectNode();
                    detail.put("code", error.getCode());
                    detail.put("message", error.getMessage());
                    event(run, "failed", detail);
                }
            });
        } catch (Exception e) {
            log.error("mark run {} failed errored: {}", runId, e.toString());
        }
    }

    // ============================================================
    // 内部工具
    // ============================================================

    private MeetingAgentRun newRun(String meetingId, int requesterId, int attempt) {
        LocalDateTime now = LocalDateTime.now();
        MeetingAgentRun run = new MeetingAgentRun();
        run.setId(UUID.randomUUID().toString());
        run.setMeetingId(meetingId);
        run.setRequestedBy(requesterId);
        run.setStatus("queued");
        run.setAttempt(attempt);
        run.setModel(props.getModel());
        run.setPromptVersion(AgentProperties.PROMPT_VERSION);
        run.setCreatedAt(now);
        run.setUpdatedAt(now);
        return run;
    }

    private ObjectNode queuedDetail(int requesterId, boolean retry) {
        ObjectNode detail = mapper.createObjectNode();
        detail.put("requested_by", requesterId);
        if (retry) detail.put("retry", true);
        return detail;
    }

    private ObjectNode startedDetail(MeetingAgentRun run) {
        ObjectNode detail = mapper.createObjectNode();
        detail.put("model", run.getModel());
        detail.put("prompt_version", run.getPromptVersion());
        return detail;
    }

    /** 事件插入(须处于调用方事务内) */
    private void event(MeetingAgentRun run, String kind, JsonNode detail) {
        MeetingAgentEvent e = new MeetingAgentEvent();
        e.setRunId(run.getId());
        e.setAttempt(run.getAttempt() == null ? 1 : run.getAttempt());
        e.setKind(kind);
        e.setDetailJson(write(detail));
        e.setCreatedAt(LocalDateTime.now());
        eventMapper.insert(e);
    }

    /** 已有标题池(故事 + 需求池 + 待审建议的 title),用于去重(对齐 jobs.py final 段) */
    private Set<String> existingTitlePool() {
        Set<String> titles = new LinkedHashSet<>();
        for (Story s : storyMapper.selectList(null)) {
            if (s.getTitle() != null) titles.add(s.getTitle().trim().toLowerCase(Locale.ROOT));
        }
        for (PoolItem p : poolItemMapper.selectList(null)) {
            if (p.getTitle() != null) titles.add(p.getTitle().trim().toLowerCase(Locale.ROOT));
        }
        for (Suggestion s : suggestionMapper.selectList(new QueryWrapper<Suggestion>().eq("status", "pending"))) {
            try {
                JsonNode data = mapper.readTree(s.getChangeJson());
                if (data.isObject() && data.path("title").isTextual()) {
                    titles.add(data.path("title").asText().trim().toLowerCase(Locale.ROOT));
                }
            } catch (Exception ignored) {
                // 解析失败视为无 title
            }
        }
        return titles;
    }

    private String write(JsonNode node) {
        try {
            return mapper.writeValueAsString(node);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }
}
