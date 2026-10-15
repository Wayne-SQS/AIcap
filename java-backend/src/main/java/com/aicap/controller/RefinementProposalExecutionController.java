package com.aicap.controller;

import com.aicap.security.Roles;
import com.aicap.service.RefinementProposalExecutionService;
import com.fasterxml.jackson.databind.JsonNode;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

@RestController
@RequiredArgsConstructor
@RequestMapping("/api/meetings/{meetingId}/refinement-analyses/{analysisId}/proposal-executions")
public class RefinementProposalExecutionController {
    private final RefinementProposalExecutionService service;
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
