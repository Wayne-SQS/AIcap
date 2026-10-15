package com.aicap.controller;

import com.aicap.agent.MeetingAgentService;
import com.aicap.entity.User;
import com.aicap.security.Roles;
import com.aicap.service.ProjectAccessService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;
import com.fasterxml.jackson.databind.JsonNode;

import java.util.List;
import java.util.Map;

/**
 * 会议 AI Agent 接口(对齐 FastAPI routers/agent.py)。
 * 前端契约:agent.js/meetings.js + AgentRunPanel.vue 轮询 refreshRun/startRun/reanalyze。
 */
@RestController
@RequestMapping("/api")
@RequiredArgsConstructor
public class AgentController {

    private final MeetingAgentService agentService;
    private final ProjectAccessService projectAccess;

    @GetMapping("/agent/config")
    public Map<String, Object> agentConfig() {
        Roles.any();
        return agentService.config();
    }

    @PostMapping("/meetings/{meetingId}/runs")
    public Map<String, Object> startRun(@PathVariable String meetingId, @RequestParam String projectId) {
        User user = Roles.writer();
        projectAccess.requireWriter(projectId);
        return agentService.createRun(projectId, meetingId, user);
    }

    @GetMapping("/meetings/{meetingId}/runs")
    public List<Map<String, Object>> meetingRuns(@PathVariable String meetingId, @RequestParam String projectId) {
        Roles.any();
        return agentService.listRuns(projectId, meetingId);
    }

    @GetMapping("/agent-runs/{runId}")
    public Map<String, Object> getRun(@PathVariable String runId, @RequestParam String projectId) {
        Roles.any();
        return agentService.getRun(projectId, runId);
    }

    @PostMapping("/agent-runs/{runId}/retry")
    public Map<String, Object> retryRun(@PathVariable String runId, @RequestParam String projectId) {
        User user = Roles.writer();
        projectAccess.requireWriter(projectId);
        return agentService.retryRun(projectId, runId, user);
    }

    @PatchMapping("/agent-runs/{runId}/minutes")
    public Map<String, Object> editMinutes(@PathVariable String runId, @RequestParam String projectId,
                                           @RequestBody JsonNode edits) {
        User user = Roles.writer();
        projectAccess.requireWriter(projectId);
        return agentService.editMinutes(projectId, runId, edits, user);
    }
}
