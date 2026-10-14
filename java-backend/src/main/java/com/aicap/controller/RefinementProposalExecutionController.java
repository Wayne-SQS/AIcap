package com.aicap.controller;

import com.aicap.security.Roles;
import com.aicap.service.ProjectAccessService;
import com.aicap.service.RefinementProposalExecutionService;
import com.fasterxml.jackson.databind.JsonNode;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

@RestController
@RequiredArgsConstructor
@RequestMapping("/api/meetings/{meetingId}/refinement-analyses/{analysisId}/proposal-executions")
public class RefinementProposalExecutionController {
    private final RefinementProposalExecutionService service;
    private final ProjectAccessService projectAccess;

    /**
     * 执行(建故事)必须带项目:故事编号是"项目内唯一",落到别的项目上就是写脏数据。
     * 调用方拼 {@code ?projectId=},与其余业务接口一致。
     */
    @PostMapping
    public JsonNode execute(@PathVariable String meetingId, @PathVariable String analysisId,
                            @RequestParam String projectId, @RequestBody JsonNode body) {
        projectAccess.requireWriter(projectId);
        return service.execute(ProjectAccessService.normalize(projectId), meetingId, analysisId, body,
                Roles.reviewer().getId());
    }

    @GetMapping
    public JsonNode list(@PathVariable String meetingId, @PathVariable String analysisId,
                         @RequestParam String projectId) {
        projectAccess.requireMember(projectId);
        return service.list(meetingId, analysisId);
    }
}
