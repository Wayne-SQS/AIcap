package com.aicap.controller;

import com.aicap.security.Roles;
import com.aicap.service.AssignmentSuggestionService;
import com.aicap.service.MeetingAccessGuard;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;
import java.util.List;

@RestController @RequiredArgsConstructor
@RequestMapping("/api/meetings/{meetingId}/assignment-suggestions")
public class AssignmentSuggestionController {
    private final AssignmentSuggestionService service;
    private final MeetingAccessGuard guard;

    /** 分配建议含成员画像与故事快照:先过项目权限,再按全局角色提交(US16)。 */
    @PostMapping public ObjectNode save(@PathVariable String meetingId,@RequestBody JsonNode body) {
        int userId = Roles.writer().getId();
        guard.requireWriter(meetingId);
        return service.save(meetingId,body,userId);
    }
    @GetMapping public List<ObjectNode> list(@PathVariable String meetingId) {
        Roles.any();
        guard.requireMember(meetingId);
        return service.list(meetingId);
    }
    @GetMapping("/by-request/{key}") public ObjectNode find(@PathVariable String meetingId,@PathVariable String key) {
        int userId = Roles.writer().getId();
        guard.requireWriter(meetingId);
        return service.find(meetingId,key,userId);
    }
    @GetMapping("/{id}") public ObjectNode get(@PathVariable String meetingId,@PathVariable String id) {
        Roles.any();
        guard.requireMember(meetingId);
        return service.get(meetingId,id);
    }
    @PostMapping("/{id}/review") public ObjectNode review(@PathVariable String meetingId,@PathVariable String id,@RequestBody JsonNode body) {
        int userId = Roles.reviewer().getId();
        guard.requireWriter(meetingId);
        return service.review(meetingId,id,body,userId);
    }
    @PostMapping("/{id}/execute") public JsonNode execute(@PathVariable String meetingId,@PathVariable String id,@RequestBody JsonNode body) {
        int userId = Roles.reviewer().getId();
        guard.requireWriter(meetingId);
        return service.execute(meetingId,id,body,userId);
    }
}
