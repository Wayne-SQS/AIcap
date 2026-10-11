package com.aicap.controller;

import com.aicap.entity.RoutingLog;
import com.aicap.security.Roles;
import com.aicap.service.RoutingLogService;
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
 * 主管路由留痕的读写(S5′，契约 §2.7 / §4.3)。
 *
 * <p><b>写</b>：编排层在 {@code plan()} 之后、派活之前调用（它不持有 DB 连接）。
 * 反问的样本与派活的样本都要写，否则 S7 的"澄清率"没有分母。
 *
 * <p><b>读</b>：表里有**用户原话**，属隐私，故限 admin/owner
 * （{@link Roles#reviewer()} 就是那道"敏感操作 admin/owner"的门槛），
 * 与 {@code /api/admin/knowledge/logs} 同口径。
 */
@RestController
@RequestMapping("/api/agents")
@RequiredArgsConstructor
public class RoutingLogController {

    private final RoutingLogService service;

    @PostMapping("/routing-logs")
    public Map<String, Object> append(@RequestBody JsonNode body) {
        return Map.of("id", service.append(Roles.any(), body));
    }

    @GetMapping("/routing-logs")
    public List<RoutingLog> list(@RequestParam(name = "limit", required = false) Integer limit) {
        Roles.reviewer();
        return service.recent(limit);
    }
}
