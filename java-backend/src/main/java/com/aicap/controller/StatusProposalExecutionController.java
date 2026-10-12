package com.aicap.controller;

import com.aicap.security.Roles;
import com.aicap.service.StatusProposalExecutionService;
import com.fasterxml.jackson.databind.JsonNode;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

@RestController
@RequiredArgsConstructor
@RequestMapping("/api/meetings/{meetingId}/status-analyses/{analysisId}/proposal-executions")
public class StatusProposalExecutionController {
    private final StatusProposalExecutionService service;
    @PostMapping
    public JsonNode execute(@PathVariable String meetingId,@PathVariable String analysisId,@RequestBody JsonNode body) {
        return service.execute(meetingId,analysisId,body,Roles.reviewer().getId());
    }
    @GetMapping
    public JsonNode list(@PathVariable String meetingId,@PathVariable String analysisId) {
        Roles.any();
        return service.list(meetingId,analysisId);
    }
}
