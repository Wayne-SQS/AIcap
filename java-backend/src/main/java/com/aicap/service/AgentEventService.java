package com.aicap.service;

import com.aicap.common.ApiException;
import com.aicap.entity.AgentEvent;
import com.aicap.entity.AgentSession;
import com.aicap.entity.User;
import com.aicap.mapper.AgentEventMapper;
import com.aicap.mapper.AgentSessionMapper;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * 主管会话与事件流(S4′.2，契约 §2.7 / §4.1 / §4.2)。
 *
 * <p>两个关键语义：
 * <ul>
 *   <li><b>归属</b>：写/读事件的会话都必须属于调用者。会话不存在<b>或</b>不属于调用者一律
 *       404(不用 403 区分)——否则 403/404 的差异会变成"会话 id 是否存在"的枚举探针。
 *       校验只放在这里的 {@link #requireOwnedSession}，写/读两条路径共用，避免只补一半
 *       (PLAT-16 的教训：两处分支只修一处，泄露照样发生)。</li>
 *   <li><b>seq</b>：{@code agent_events.id} 自增主键即事件序号。写入后回填并返回，
 *       这是全系统唯一产生 seq 的地方。</li>
 * </ul>
 */
@Service
@RequiredArgsConstructor
public class AgentEventService {

    /** 单次增量读的事件上限；防一个长会话把内存拉爆 */
    private static final int MAX_LIMIT = 1000;
    private static final int DEFAULT_LIMIT = 500;

    private final AgentSessionMapper sessionMapper;
    private final AgentEventMapper eventMapper;
    private final ObjectMapper mapper;

    // ------------------------------------------------------------------
    // 会话
    // ------------------------------------------------------------------

    /** 建会话，归属调用者。返回的 {@code id} 即 {@code session_id} */
    public AgentSession createSession(User caller, String title) {
        AgentSession s = new AgentSession();
        s.setId(UUID.randomUUID().toString());
        s.setUserId(caller.getId());
        s.setTitle(title);
        LocalDateTime now = LocalDateTime.now();
        s.setCreatedAt(now);
        s.setUpdatedAt(now);
        sessionMapper.insert(s);
        return s;
    }

    /** 列<b>当前用户</b>的会话，最近更新的在前 */
    public List<SessionView> listSessions(User caller) {
        List<AgentSession> rows = sessionMapper.selectList(
                new QueryWrapper<AgentSession>().eq("user_id", caller.getId()).orderByDesc("updated_at"));
        List<SessionView> out = new ArrayList<>(rows.size());
        for (AgentSession s : rows) {
            out.add(new SessionView(s.getId(), s.getTitle(), str(s.getCreatedAt()), str(s.getUpdatedAt())));
        }
        return out;
    }

    // ------------------------------------------------------------------
    // 事件
    // ------------------------------------------------------------------

    /** 落一条事件，返回自增 id(即 seq)。归属校验先于写入 */
    public long appendEvent(User caller, String sessionId, String runId, String type, JsonNode detail) {
        requireOwnedSession(caller, sessionId);
        AgentEvent e = new AgentEvent();
        e.setSessionId(sessionId);
        e.setRunId(runId);
        e.setType(type);
        // 列是 JSON NOT NULL：空对象兜底，绝不让 null 落库
        e.setDetailJson(write(detail == null || detail.isNull() ? mapper.createObjectNode() : detail));
        e.setCreatedAt(LocalDateTime.now());
        eventMapper.insert(e);

        // 顶一下会话的 updated_at，让 idx_user_updated 有意义(会话列表按它排序)
        AgentSession touch = new AgentSession();
        touch.setId(sessionId);
        touch.setUpdatedAt(LocalDateTime.now());
        sessionMapper.updateById(touch);

        return e.getId();
    }

    /**
     * 增量读事件，供 {@code Last-Event-ID} 续传。
     *
     * <p>{@code after_seq} 语义是<b>严格大于</b>：调用者表示"我已收到该 id，要它之后的"。
     * 写成 {@code >=} 会让重连时重复收到已确认的那条。
     */
    public List<EventView> listEvents(User caller, String sessionId, long afterSeq, Integer limit) {
        requireOwnedSession(caller, sessionId);
        long from = Math.max(afterSeq, 0L);
        int cap = limit == null || limit <= 0 ? DEFAULT_LIMIT : Math.min(limit, MAX_LIMIT);
        List<AgentEvent> rows = eventMapper.selectList(new QueryWrapper<AgentEvent>()
                .eq("session_id", sessionId)
                .gt("id", from)
                .orderByAsc("id")
                .last("LIMIT " + cap));
        List<EventView> out = new ArrayList<>(rows.size());
        for (AgentEvent e : rows) {
            out.add(new EventView(e.getId(), e.getSessionId(), e.getRunId(), e.getType(),
                    read(e.getDetailJson()), str(e.getCreatedAt())));
        }
        return out;
    }

    /** 该会话是否属于调用者；不是(或不存在)一律 404 */
    AgentSession requireOwnedSession(User caller, String sessionId) {
        AgentSession s = sessionId == null ? null : sessionMapper.selectById(sessionId);
        if (s == null || !s.getUserId().equals(caller.getId())) {
            throw ApiException.notFound("会话不存在");
        }
        return s;
    }

    // ------------------------------------------------------------------
    // 出参：Jackson 未配全局命名策略，record 必须显式 @JsonProperty 才吐 snake_case
    // ------------------------------------------------------------------

    public record SessionView(@JsonProperty("session_id") String sessionId, String title,
                             @JsonProperty("created_at") String createdAt,
                             @JsonProperty("updated_at") String updatedAt) {
    }

    public record EventView(long id,
                            @JsonProperty("session_id") String sessionId,
                            @JsonProperty("run_id") String runId,
                            String type, JsonNode detail,
                            @JsonProperty("created_at") String createdAt) {
    }

    private String write(JsonNode node) {
        try {
            return mapper.writeValueAsString(node);
        } catch (Exception e) {
            throw ApiException.unprocessable("事件载荷不是合法 JSON");
        }
    }

    /** 解析成 JsonNode 再回，不能原样吐字符串——否则编排层拿到的 detail 会是字符串而非对象 */
    JsonNode read(String json) {
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
