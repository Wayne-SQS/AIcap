package com.aicap.controller;

import com.aicap.security.Roles;
import com.aicap.service.RefinementProposalReviewService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

@RestController
@RequiredArgsConstructor
@RequestMapping("/api/meetings/{meetingId}/refinement-analyses/{analysisId}/proposal-reviews")
public class RefinementProposalReviewController {
    private final RefinementProposalReviewService service;
    @PostMapping
    public ObjectNode review(@PathVariable String meetingId,@PathVariable String analysisId,@RequestBody JsonNode body) {
        return service.review(meetingId,analysisId,body,Roles.reviewer().getId());
    }
    @GetMapping
    public ObjectNode list(@PathVariable String meetingId,@PathVariable String analysisId) {
        Roles.any();
        return service.list(meetingId,analysisId);
    }
}
