package com.aicap.service;

import com.aicap.common.ApiException;
import com.aicap.entity.AgentCheckpoint;
import com.aicap.entity.AgentEvent;
import com.aicap.entity.User;
import com.aicap.mapper.AgentCheckpointMapper;
import com.aicap.mapper.AgentEventMapper;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

/**
 * 主管有界循环的检查点(S4′.2，契约 §4.5 / §2.7)。
 *
 * <p>编排层是<b>无状态进程</b>：每步循环末尾把循环状态 upsert 进来，进程被杀后由携带新
 * JWT 的客户端重连触发恢复。表里<b>没有 {@code session_id} 列</b>(照抄契约，不加列)，
 * 所以归属校验只能借 {@code agent_events} 反查该 run 属于哪个会话。
 */
@Service
@RequiredArgsConstructor
public class AgentCheckpointService {

    /** 编织层固定用这一个 node_name，滚动覆盖 */
    public static final String LOOP_NODE = "loop";

    private final AgentCheckpointMapper checkpointMapper;
    private final AgentEventMapper eventMapper;
    private final AgentEventService eventService;
    private final ObjectMapper mapper;

    /**
     * 写检查点(按 {@code run_id + node_name} upsert 覆盖)。
     *
     * <p>两道校验：(1) 会话归属；(2) 该 {@code run_id} 若已有事件，必须与传入
     * {@code session_id} 同会话——否则 A 拿<b>自己的</b> session_id + <b>别人的</b> run_id
     * 就能写进来。run_id 是 UUID 难猜，但不该只靠难猜。
     */
    public long putCheckpoint(User caller, String sessionId, String runId, String nodeName, JsonNode state) {
        eventService.requireOwnedSession(caller, sessionId);
        Long foreign = eventMapper.selectCount(new QueryWrapper<AgentEvent>()
                .eq("run_id", runId).ne("session_id", sessionId));
        if (foreign != null && foreign > 0) {
            throw ApiException.notFound("会话不存在");
        }
        AgentCheckpoint c = new AgentCheckpoint();
        c.setRunId(runId);
        c.setNodeName(nodeName);
        c.setStateJson(write(state));
        c.setCreatedAt(LocalDateTime.now());
        checkpointMapper.upsert(c);
        // upsert 走的是自定义 @Insert，不回填主键；读回来拿 id(uk_run_node 保证唯一)
        AgentCheckpoint saved = checkpointMapper.selectOne(new QueryWrapper<AgentCheckpoint>()
                .eq("run_id", runId).eq("node_name", nodeName));
        return saved == null ? 0L : saved.getId();
    }

    /** 读某 run 的全部检查点(需先由事件反查出所属会话并校验归属) */
    public List<CheckpointView> listCheckpoints(User caller, String runId) {
        eventService.requireOwnedSession(caller, resolveSessionOf(runId));
        List<AgentCheckpoint> rows = checkpointMapper.selectList(
                new QueryWrapper<AgentCheckpoint>().eq("run_id", runId).orderByAsc("node_name"));
        List<CheckpointView> out = new ArrayList<>(rows.size());
        for (AgentCheckpoint c : rows) {
            out.add(new CheckpointView(c.getRunId(), c.getNodeName(), read(c.getStateJson()), str(c.getCreatedAt())));
        }
        return out;
    }

    /**
     * 列该会话「未完成」的 run：有检查点且无 {@code run.end}/{@code run.error} 事件。
     * 交给编排层判断要不要恢复(惰性恢复的触发源)。
     */
    public List<UnfinishedRunView> listUnfinished(User caller, String sessionId) {
        eventService.requireOwnedSession(caller, sessionId);
        List<AgentCheckpointMapper.UnfinishedRunRow> runs =
                checkpointMapper.selectUnfinishedRunIds(sessionId);
        List<UnfinishedRunView> out = new ArrayList<>(runs.size());
        for (AgentCheckpointMapper.UnfinishedRunRow r : runs) {
            AgentCheckpoint cp = checkpointMapper.selectOne(new QueryWrapper<AgentCheckpoint>()
                    .eq("run_id", r.runId()).eq("node_name", LOOP_NODE));
            if (cp == null) {
                continue;
            }
            out.add(new UnfinishedRunView(r.runId(), sessionId, cp.getNodeName(),
                    read(cp.getStateJson()), str(cp.getCreatedAt())));
        }
        return out;
    }

    /** 该 run 的事件所属会话；一条事件都没有 → 404(有检查点却无事件，理论上不会发生) */
    private String resolveSessionOf(String runId) {
        List<AgentEvent> any = eventMapper.selectList(new QueryWrapper<AgentEvent>()
                .eq("run_id", runId).orderByAsc("id").last("LIMIT 1"));
        if (any.isEmpty()) {
            throw ApiException.notFound("会话不存在");
        }
        return any.get(0).getSessionId();
    }

    public record CheckpointView(@JsonProperty("run_id") String runId,
                                 @JsonProperty("node_name") String nodeName,
                                 JsonNode state,
                                 @JsonProperty("created_at") String createdAt) {
    }

    public record UnfinishedRunView(@JsonProperty("run_id") String runId,
                                    @JsonProperty("session_id") String sessionId,
                                    @JsonProperty("node_name") String nodeName,
                                    JsonNode state,
                                    @JsonProperty("created_at") String createdAt) {
    }

    private String write(JsonNode node) {
        try {
            return mapper.writeValueAsString(node == null || node.isNull() ? mapper.createObjectNode() : node);
        } catch (Exception e) {
            throw ApiException.unprocessable("检查点状态不是合法 JSON");
        }
    }

    private JsonNode read(String json) {
        if (json == null || json.isBlank()) {
            return mapper.createObjectNode();
        }
        try {
            return mapper.readTree(json);
        } catch (Exception e) {
            return mapper.createObjectNode();
        }
    }

    private static String str(LocalDateTime t) {
        return t == null ? null : t.toString();
    }
}
