package com.aicap.contract;

import com.aicap.security.JwtUtil;
import com.aicap.service.ReviewQueueIndexService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

import static org.junit.jupiter.api.Assertions.*;

/** Manual HTTP benchmark. Synthetic rows are committed for HTTP visibility and removed in finally. */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "spring.datasource.url=jdbc:mysql://127.0.0.1:3307/aicap_java_test?useUnicode=true&characterEncoding=utf8&serverTimezone=Asia/Shanghai&allowPublicKeyRetrieval=true&useSSL=false",
        "spring.datasource.username=aiguanli",
        "spring.datasource.password=aiguanli-2026",
        "aicap.llm.agent-worker-enabled=false"
})
class ReviewQueueHttpCapacityBenchmark {
    @Autowired private JdbcTemplate jdbc;
    @Autowired private PlatformTransactionManager transactionManager;
    @Autowired private JwtUtil jwt;
    @Autowired private ReviewQueueIndexService index;
    @Autowired private ObjectMapper mapper;
    @LocalServerPort private int port;

    @Test
    void authenticatedHttpWithSevenSources() throws Exception {
        int rows = Integer.getInteger("aicap.benchmark.rows", 3000);
        int samples = Integer.getInteger("aicap.benchmark.samples", 8);
        assertTrue(rows >= 5 && rows <= 10000);
        assertTrue(samples >= 2 && samples <= 30);
        String meetingId = UUID.randomUUID().toString();
        String suggestionPrefix = "B" + UUID.randomUUID().toString().substring(0, 3);
        long pendingBefore = index.summary().pendingCount();
        TransactionTemplate transaction = new TransactionTemplate(transactionManager);
        transaction.executeWithoutResult(status -> seed(rows, meetingId, suggestionPrefix));
        try {
            HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
            String token = jwt.createToken(1);
            sample(client, token, "/api/review-queue?status=pending&source=all&limit=50", rows, samples,
                    "items", 50);
            sample(client, token, "/api/review-queue/summary", rows, samples,
                    "pendingCount", pendingBefore + 12L * rows);
        } finally {
            transaction.executeWithoutResult(status -> cleanup(meetingId, suggestionPrefix, rows));
        }
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM meetings WHERE id=?", Integer.class, meetingId));
    }

    private void seed(int rows, String meetingId, String prefix) {
        jdbc.execute("SET SESSION cte_max_recursion_depth=11000");
        jdbc.update("INSERT INTO meetings(id,title,transcript,created_by,created_at) VALUES(?,?,?,1,NOW())",
                meetingId, "七来源 HTTP 容量基准", "synthetic");
        for (String source : List.of("status", "planning", "review", "retro", "refinement")) {
            String sql = "INSERT INTO meeting_" + source + "_analyses " +
                    "(id,meeting_id,submitted_by,client_request_id,status,transcript,result_json,snapshots_json,created_at) " +
                    "WITH RECURSIVE seq(n) AS (SELECT 1 UNION ALL SELECT n+1 FROM seq WHERE n<?) " +
                    "SELECT UUID(),?,1,CONCAT('benchmark-http-" + source + "-',n),'completed','synthetic'," +
                    "CONCAT('{\"proposed_actions\":[{\"proposal_id\":\"p-',n,'-a\"}," +
                    "{\"proposal_id\":\"p-',n,'-b\"}]}'),'[]'," +
                    "TIMESTAMPADD(SECOND,n,'2025-01-01 00:00:00') FROM seq";
            assertEquals(rows, jdbc.update(sql, rows, meetingId));
        }
        String assignment = "INSERT INTO meeting_assignment_analyses " +
                "(id,meeting_id,submitted_by,client_request_id,input_json,result_json,created_at) " +
                "WITH RECURSIVE seq(n) AS (SELECT 1 UNION ALL SELECT n+1 FROM seq WHERE n<?) " +
                "SELECT UUID(),?,1,CONCAT('benchmark-http-assignment-',n),'{}','{}'," +
                "TIMESTAMPADD(SECOND,n,'2025-01-01 00:00:00') FROM seq";
        assertEquals(rows, jdbc.update(assignment, rows, meetingId));
        String suggestions = "INSERT INTO suggestions " +
                "(id,agent,kind,evidence,affected,note,change_json,status,created_at) " +
                "WITH RECURSIVE seq(n) AS (SELECT 1 UNION ALL SELECT n+1 FROM seq WHERE n<?) " +
                "SELECT CONCAT(?,LPAD(n,6,'0')),'meeting','meeting','synthetic','US01'," +
                "'synthetic','{}','pending',TIMESTAMPADD(SECOND,n,'2025-01-01 00:00:00') FROM seq";
        assertEquals(rows, jdbc.update(suggestions, rows, prefix));
        String records = "INSERT INTO meeting_suggestion_records " +
                "(suggestion_id,meeting_id,client_request_id,request_hash,submitted_by,origin,reason,execution_status) " +
                "SELECT id,?,CONCAT('benchmark-http-',id),'benchmark',1,'manual','','not_started' " +
                "FROM suggestions WHERE id>=? AND id<=?";
        assertEquals(rows, jdbc.update(records, meetingId, prefix + "000001", prefix + String.format("%06d", rows)));
    }

    private void cleanup(String meetingId, String prefix, int rows) {
        jdbc.update("DELETE FROM meeting_suggestion_records WHERE meeting_id=?", meetingId);
        jdbc.update("DELETE FROM suggestions WHERE id>=? AND id<=?",
                prefix + "000001", prefix + String.format("%06d", rows));
        for (String source : List.of("status", "planning", "review", "retro", "refinement"))
            jdbc.update("DELETE FROM meeting_" + source + "_analyses WHERE meeting_id=?", meetingId);
        jdbc.update("DELETE FROM meeting_assignment_analyses WHERE meeting_id=?", meetingId);
        jdbc.update("DELETE FROM meetings WHERE id=?", meetingId);
    }

    private void sample(HttpClient client, String token, String path, int rows, int samples,
                        String expectedField, long expectedValue) throws Exception {
        HttpRequest request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path))
                .header("Authorization", "Bearer " + token).timeout(Duration.ofSeconds(30)).GET().build();
        Response warmup = fetch(client, request);
        assertResponse(warmup, expectedField, expectedValue);
        List<Long> sequential = new ArrayList<>();
        for (int i = 0; i < samples; i++) {
            Response response = fetch(client, request);
            assertResponse(response, expectedField, expectedValue);
            sequential.add(response.millis());
        }
        List<CompletableFuture<Response>> futures = new ArrayList<>();
        for (int i = 0; i < samples; i++) {
            long start = System.nanoTime();
            futures.add(client.sendAsync(request, HttpResponse.BodyHandlers.ofString())
                    .thenApply(response -> new Response(response.statusCode(), response.body(),
                            (System.nanoTime() - start) / 1_000_000)));
        }
        List<Long> concurrent = new ArrayList<>();
        for (CompletableFuture<Response> future : futures) {
            Response response = future.get();
            assertResponse(response, expectedField, expectedValue);
            concurrent.add(response.millis());
        }
        System.out.printf("SEVEN_SOURCE_HTTP_BENCHMARK rows_per_source=%d path=%s samples=%d " +
                        "sequential_p50_ms=%d sequential_p95_ms=%d concurrent_p50_ms=%d concurrent_p95_ms=%d%n",
                rows, path, samples, percentile(sequential, 50), percentile(sequential, 95),
                percentile(concurrent, 50), percentile(concurrent, 95));
    }

    private Response fetch(HttpClient client, HttpRequest request) throws Exception {
        long start = System.nanoTime();
        HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
        return new Response(response.statusCode(), response.body(), (System.nanoTime() - start) / 1_000_000);
    }

    private void assertResponse(Response response, String expectedField, long expectedValue) throws Exception {
        assertEquals(200, response.status());
        JsonNode body = mapper.readTree(response.body());
        if (expectedField.equals("items")) assertEquals(expectedValue, body.path("items").size());
        else assertEquals(expectedValue, body.path(expectedField).longValue());
    }

    private long percentile(List<Long> values, int percentile) {
        List<Long> ordered = values.stream().sorted(Comparator.naturalOrder()).toList();
        return ordered.get(Math.max(0, (percentile * ordered.size() + 99) / 100 - 1));
    }

    private record Response(int status, String body, long millis) {}
}
