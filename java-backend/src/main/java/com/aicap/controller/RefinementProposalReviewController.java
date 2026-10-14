package com.aicap.controller;

import com.aicap.security.Roles;
import com.aicap.service.ProjectAccessService;
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
    private final ProjectAccessService projectAccess;

    /** 审核是写操作:先按项目判权限(非成员 403、viewer 403),再走服务。 */
    @PostMapping
    public ObjectNode review(@PathVariable String meetingId, @PathVariable String analysisId,
                             @RequestParam String projectId, @RequestBody JsonNode body) {
        projectAccess.requireWriter(projectId);
        return service.review(meetingId, analysisId, body, Roles.reviewer().getId());
    }

    @GetMapping
    public ObjectNode list(@PathVariable String meetingId, @PathVariable String analysisId,
                           @RequestParam String projectId) {
        projectAccess.requireMember(projectId);
        return service.list(meetingId, analysisId);
    }
}
