package com.aicap.controller;

import com.aicap.agent.MeetingAgentService;
import com.aicap.entity.User;
import com.aicap.security.Roles;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

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

    @GetMapping("/agent/config")
    public Map<String, Object> agentConfig() {
        Roles.any();
        return agentService.config();
    }

    @PostMapping("/meetings/{meetingId}/runs")
    public Map<String, Object> startRun(@PathVariable String meetingId) {
        User user = Roles.writer();
        return agentService.createRun(meetingId, user);
    }

    @GetMapping("/meetings/{meetingId}/runs")
    public List<Map<String, Object>> meetingRuns(@PathVariable String meetingId) {
        Roles.any();
        return agentService.listRuns(meetingId);
    }

    @GetMapping("/agent-runs/{runId}")
    public Map<String, Object> getRun(@PathVariable String runId) {
        Roles.any();
        return agentService.getRun(runId);
    }

    @PostMapping("/agent-runs/{runId}/retry")
    public Map<String, Object> retryRun(@PathVariable String runId) {
        User user = Roles.writer();
        return agentService.retryRun(runId, user);
    }
}
