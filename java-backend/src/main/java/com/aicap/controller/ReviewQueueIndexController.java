package com.aicap.controller;

import com.aicap.security.Roles;
import com.aicap.service.ReviewQueueHydrationService;
import com.aicap.service.ReviewQueueIndexService;
import com.fasterxml.jackson.databind.JsonNode;
import lombok.RequiredArgsConstructor;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/review-queue")
@RequiredArgsConstructor
public class ReviewQueueIndexController {
    private final ReviewQueueIndexService service;
    private final ReviewQueueHydrationService hydration;

    /**
     * 响应在原有字段(items / nextCursor、pendingCount / ...)之上**追加** details,
     * 让一次请求拿到整页详情;老客户端忽略该字段即可,行为不变。
     */
    public record PageOut(List<ReviewQueueIndexService.Item> items, String nextCursor, Map<String, JsonNode> details) {}

    public record SummaryOut(long pendingCount, long executionCount, ReviewQueueIndexService.Item latest,
                             ReviewQueueIndexService.RecentMeeting recentMeeting, Map<String, JsonNode> details) {}

    @GetMapping("/summary")
    public SummaryOut summary() {
        Roles.any();
        var summary = service.summary();
        Map<String, JsonNode> details = summary.latest() == null
                ? Map.of() : hydration.hydrate(List.of(summary.latest()));
        return new SummaryOut(summary.pendingCount(), summary.executionCount(), summary.latest(),
                summary.recentMeeting(), details);
    }

    @GetMapping
    public PageOut page(
            @RequestParam(required = false) String status,
            @RequestParam(required = false) String source,
            @RequestParam(required = false) String search,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
            @RequestParam(required = false) String cursor,
            @RequestParam(required = false) Integer limit) {
        Roles.any();
        var page = service.page(status, source, search, from, to, cursor, limit);
        return new PageOut(page.items(), page.nextCursor(), hydration.hydrate(page.items()));
    }
}
