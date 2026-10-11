package com.aicap.service;

import com.aicap.common.ApiException;
import com.aicap.entity.RoutingLog;
import com.aicap.entity.User;
import com.aicap.mapper.RoutingLogMapper;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.fasterxml.jackson.databind.JsonNode;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDateTime;
import java.util.List;

/**
 * 主管路由留痕(S5′，契约 §2.7 / §4.3)。
 *
 * <p>编排层不持有 DB 连接({@code test_no_db} 结构性钉住)，所以留痕只能经 HTTP 写 ——
 * 与事件、检查点同一条路。
 */
@Service
@RequiredArgsConstructor
public class RoutingLogService {

    private static final int UTTERANCE_MAX = 1000;
    private static final int REASON_MAX = 500;
    private static final int DEFAULT_LIMIT = 50;
    private static final int MAX_LIMIT = 500;

    private final RoutingLogMapper mapper;
    private final AgentEventService eventService;

    /** 落一行留痕，返回自增 id。会话必须属于调用者(与事件/检查点同源) */
    public long append(User caller, JsonNode body) {
        String sessionId = required(body, "session_id");
        eventService.requireOwnedSession(caller, sessionId);

        RoutingLog row = new RoutingLog();
        row.setRunId(required(body, "run_id"));
        row.setSessionId(sessionId);
        // 调用者身份只来自 JWT:body 里即使带了 user_id 也不采信(契约 §2.5)
        row.setUserId(caller.getId());
        row.setUtterance(clip(required(body, "utterance"), UTTERANCE_MAX));
        row.setIntent(clip(required(body, "intent"), 64));
        row.setRoute(clip(text(body, "route"), 64));
        row.setConfidence(confidence(body.path("confidence")));
        row.setReason(clip(text(body, "reason"), REASON_MAX));
        row.setAskedClarify(body.path("asked_clarify").asBoolean(false));
        JsonNode candidates = body.path("candidates");
        row.setCandidatesJson(candidates.isMissingNode() || candidates.isNull() ? null : candidates.toString());
        row.setCreatedAt(LocalDateTime.now());
        mapper.insert(row);
        return row.getId() == null ? 0L : row.getId();
    }

    /** 最近的留痕(含 utterance 原话 —— 调用方必须先过 admin/owner 门槛) */
    public List<RoutingLog> recent(Integer limit) {
        int size = limit == null || limit <= 0 ? DEFAULT_LIMIT : Math.min(limit, MAX_LIMIT);
        return mapper.selectList(new QueryWrapper<RoutingLog>().orderByDesc("id").last("LIMIT " + size));
    }

    /**
     * confidence 收口到 [0,1]。
     *
     * <p>列是 {@code DECIMAL(4,3)}：模型若返回 1.5 这类越界值，直接写会以
     * {@code DataIntegrityViolationException}(500)炸出来，而它只是"模型多说了个数" ——
     * 这类输入该在边界收口，不该变成服务端的 500。
     */
    private static BigDecimal confidence(JsonNode node) {
        if (node == null || !node.isNumber()) {
            return null;
        }
        BigDecimal value = node.decimalValue();
        if (value.compareTo(BigDecimal.ZERO) < 0) {
            return BigDecimal.ZERO.setScale(3, RoundingMode.HALF_UP);
        }
        if (value.compareTo(BigDecimal.ONE) > 0) {
            return BigDecimal.ONE.setScale(3, RoundingMode.HALF_UP);
        }
        return value.setScale(3, RoundingMode.HALF_UP);
    }

    private static String required(JsonNode body, String field) {
        String value = text(body, field);
        if (value == null) {
            throw ApiException.unprocessable(field + " 必填");
        }
        return value;
    }

    private static String text(JsonNode body, String field) {
        JsonNode node = body == null ? null : body.path(field);
        if (node == null || node.isMissingNode() || node.isNull()) {
            return null;
        }
        String value = node.asText();
        return value == null || value.isBlank() ? null : value;
    }

    private static String clip(String value, int max) {
        if (value == null) {
            return null;
        }
        return value.length() <= max ? value : value.substring(0, max);
    }
}
