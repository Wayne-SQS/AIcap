package com.aicap.controller;

import com.aicap.security.Roles;
import com.aicap.service.TranscriptVersionService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;
import java.util.List;

@RestController @RequiredArgsConstructor
@RequestMapping("/api/meetings/{meetingId}/transcript-versions")
public class TranscriptVersionController {
    private final TranscriptVersionService service;
    @PostMapping public ObjectNode save(@PathVariable String meetingId, @RequestBody JsonNode body) { return service.save(meetingId, body, Roles.writer().getId()); }
    @GetMapping public List<ObjectNode> list(@PathVariable String meetingId) { Roles.any(); return service.list(meetingId); }
    @PostMapping("/{id}/confirm") public ObjectNode confirm(@PathVariable String meetingId, @PathVariable String id, @RequestBody JsonNode body) { return service.confirm(meetingId, id, body, Roles.writer().getId()); }
}
