package com.aicap.controller;

import com.aicap.security.Roles;
import com.aicap.service.ReviewQueueIndexService;
import com.aicap.service.ProjectAccessService;
import lombok.RequiredArgsConstructor;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDate;

@RestController
@RequestMapping("/api/review-queue")
@RequiredArgsConstructor
public class ReviewQueueIndexController {
    private final ReviewQueueIndexService service;
    private final ProjectAccessService projectAccess;

    @GetMapping("/summary")
    public ReviewQueueIndexService.Summary summary(@RequestParam String projectId) {
        Roles.any();
        projectAccess.requireMember(projectId);
        return service.summary(projectId);
    }

    @GetMapping
    public ReviewQueueIndexService.Page page(
            @RequestParam String projectId,
            @RequestParam(required = false) String status,
            @RequestParam(required = false) String source,
            @RequestParam(required = false) String search,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
            @RequestParam(required = false) String cursor,
            @RequestParam(required = false) Integer limit) {
        Roles.any();
        projectAccess.requireMember(projectId);
        return service.page(projectId, status, source, search, from, to, cursor, limit);
    }
}
