package com.aicap.controller;

import com.aicap.common.ApiException;
import com.aicap.entity.AgentSession;
import com.aicap.entity.User;
import com.aicap.security.Roles;
import com.aicap.service.AgentEventService;
import com.fasterxml.jackson.databind.JsonNode;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

/**
 * 主管会话与事件流(S4′.2，契约 §2.7 定稿)。
 *
 * <p>四个端点，门槛一律 {@code Roles.any()}(任何登录用户)——建自己的会话、读写自己的会话，
 * 与"主管借提问者工牌"的产品意图一致。真正的隔离靠<b>会话归属校验</b>(在
 * {@link AgentEventService} 里，写/读共用一处)，他人的会话一律 404。
 *
 * <p>{@code POST /events} 是全系统<b>唯一产生 seq</b> 的地方：事件先落库拿到自增 id，
 * 再返回给编排层推给前端。
 */
@RestController
@RequestMapping("/api/agents")
@RequiredArgsConstructor
public class AgentSessionController {

    private final AgentEventService service;

    /** 建会话，返回 {@code {"session_id": ...}} */
    @PostMapping("/sessions")
    public Map<String, Object> createSession(@RequestBody(required = false) JsonNode body) {
        User caller = Roles.any();
        String title = body == null ? null : text(body, "title", false);
        AgentSession s = service.createSession(caller, title);
        return Map.of("session_id", s.getId());
    }

    /** 列当前用户的会话 */
    @GetMapping("/sessions")
    public List<AgentEventService.SessionView> listSessions() {
        return service.listSessions(Roles.any());
    }

    /** 落一条事件，返回 {@code {"id": <seq>}} */
    @PostMapping("/events")
    public Map<String, Object> appendEvent(@RequestBody JsonNode body) {
        User caller = Roles.any();
        String sessionId = text(body, "session_id", true);
        String runId = text(body, "run_id", true);
        String type = text(body, "type", true);
        if (type.length() > 32) {
            throw ApiException.unprocessable("type 过长");
        }
        JsonNode detail = body.path("detail");
        if (!detail.isObject()) {
            throw ApiException.unprocessable("detail 必须是 JSON 对象");
        }
        long seq = service.appendEvent(caller, sessionId, runId, type, detail);
        return Map.of("id", seq);
    }

    /** 增量读事件：{@code id > after_seq}，升序。供 Last-Event-ID 续传 */
    @GetMapping("/events")
    public List<AgentEventService.EventView> listEvents(
            @RequestParam("session_id") String sessionId,
            @RequestParam(name = "after_seq", defaultValue = "0") long afterSeq,
            @RequestParam(name = "limit", required = false) Integer limit) {
        return service.listEvents(Roles.any(), sessionId, afterSeq, limit);
    }

    private static String text(JsonNode body, String field, boolean required) {
        JsonNode n = body == null ? null : body.path(field);
        String v = n == null || n.isMissingNode() || n.isNull() ? null : n.asText();
        if (v == null || v.isBlank()) {
            if (required) {
                throw ApiException.unprocessable(field + " 必填");
            }
            return null;
        }
        return v;
    }
}
