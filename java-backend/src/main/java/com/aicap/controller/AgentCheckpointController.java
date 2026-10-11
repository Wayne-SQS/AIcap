package com.aicap.controller;

import com.aicap.common.ApiException;
import com.aicap.entity.User;
import com.aicap.security.Roles;
import com.aicap.service.AgentCheckpointService;
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
 * 主管有界循环的检查点(S4′.2，契约 §4.5)。
 *
 * <p>编排层经 HTTP 写检查点——它不持有 DB 连接(结构性约束见 agent-orchestrator 的
 * {@code test_no_db})。{@code GET /runs/unfinished} 是惰性恢复的触发源：客户端重连时
 * 编排层问它"这个会话有没有没跑完的 run"，有就从 {@code state} 恢复。
 */
@RestController
@RequestMapping("/api/agents")
@RequiredArgsConstructor
public class AgentCheckpointController {

    private final AgentCheckpointService service;

    /** upsert 检查点，返回 {@code {"id": ...}} */
    @PostMapping("/checkpoints")
    public Map<String, Object> putCheckpoint(@RequestBody JsonNode body) {
        User caller = Roles.any();
        String sessionId = required(body, "session_id");
        String runId = required(body, "run_id");
        String nodeName = required(body, "node_name");
        JsonNode state = body.path("state");
        if (!state.isObject()) {
            throw ApiException.unprocessable("state 必须是 JSON 对象");
        }
        long id = service.putCheckpoint(caller, sessionId, runId, nodeName, state);
        return Map.of("id", id);
    }

    /** 读某 run 的全部检查点 */
    @GetMapping("/checkpoints")
    public List<AgentCheckpointService.CheckpointView> listCheckpoints(@RequestParam("run_id") String runId) {
        return service.listCheckpoints(Roles.any(), runId);
    }

    /** 列该会话未完成的 run(含最新 state)，供编排层惰性恢复 */
    @GetMapping("/runs/unfinished")
    public List<AgentCheckpointService.UnfinishedRunView> listUnfinished(
            @RequestParam("session_id") String sessionId) {
        return service.listUnfinished(Roles.any(), sessionId);
    }

    private static String required(JsonNode body, String field) {
        JsonNode n = body == null ? null : body.path(field);
        String v = n == null || n.isMissingNode() || n.isNull() ? null : n.asText();
        if (v == null || v.isBlank()) {
            throw ApiException.unprocessable(field + " 必填");
        }
        return v;
    }
}
