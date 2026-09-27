package com.aicap.controller;

import com.aicap.security.Roles;
import com.aicap.service.PlanningAnalysisService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;
import java.util.List;

@RestController
@RequiredArgsConstructor
@RequestMapping("/api/meetings/{meetingId}/planning-analyses")
public class PlanningAnalysisController {
    private final PlanningAnalysisService service;

    @PostMapping
    public ObjectNode save(@PathVariable String meetingId, @RequestBody JsonNode body) {
        return service.save(meetingId, body, Roles.writer().getId());
    }

    @GetMapping
    public List<ObjectNode> list(@PathVariable String meetingId) {
        Roles.any();
        return service.list(meetingId);
    }

    @GetMapping("/by-request/{clientRequestId}")
    public ObjectNode findByRequest(@PathVariable String meetingId, @PathVariable String clientRequestId) {
        return service.findByRequest(meetingId, clientRequestId, Roles.writer().getId());
    }

    @GetMapping("/{analysisId}")
    public ObjectNode get(@PathVariable String meetingId, @PathVariable String analysisId) {
        Roles.any();
        return service.get(meetingId, analysisId);
    }
}

