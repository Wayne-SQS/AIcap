package com.aicap.controller;

import com.aicap.security.Roles;
import com.aicap.service.AssignmentSuggestionService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;
import java.util.List;

@RestController @RequiredArgsConstructor
@RequestMapping("/api/meetings/{meetingId}/assignment-suggestions")
public class AssignmentSuggestionController {
    private final AssignmentSuggestionService service;
    @PostMapping public ObjectNode save(@PathVariable String meetingId,@RequestBody JsonNode body) { return service.save(meetingId,body,Roles.writer().getId()); }
    @GetMapping public List<ObjectNode> list(@PathVariable String meetingId) { Roles.any(); return service.list(meetingId); }
    @GetMapping("/by-request/{key}") public ObjectNode find(@PathVariable String meetingId,@PathVariable String key) { return service.find(meetingId,key,Roles.writer().getId()); }
    @GetMapping("/{id}") public ObjectNode get(@PathVariable String meetingId,@PathVariable String id) { Roles.any(); return service.get(meetingId,id); }
    @PostMapping("/{id}/review") public ObjectNode review(@PathVariable String meetingId,@PathVariable String id,@RequestBody JsonNode body) { return service.review(meetingId,id,body,Roles.reviewer().getId()); }
    @PostMapping("/{id}/execute") public JsonNode execute(@PathVariable String meetingId,@PathVariable String id,@RequestBody JsonNode body) { return service.execute(meetingId,id,body,Roles.reviewer().getId()); }
}
