package com.aicap.contract;

import com.aicap.service.ReviewQueueIndexService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/** Manual benchmark: mvn -Dtest=ReviewQueueCapacityBenchmark test. Test transaction rolls back. */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE, properties = {
        "spring.datasource.url=jdbc:mysql://127.0.0.1:3307/aicap_java_test?useUnicode=true&characterEncoding=utf8&serverTimezone=Asia/Shanghai&allowPublicKeyRetrieval=true&useSSL=false",
        "spring.datasource.username=aiguanli",
        "spring.datasource.password=aiguanli-2026",
        "aicap.llm.agent-worker-enabled=false"
})
class ReviewQueueCapacityBenchmark {
    @Autowired private JdbcTemplate jdbc;
    @Autowired private ReviewQueueIndexService index;

    @Test
    @Transactional
    void sevenSourcePageAndSummary() {
        int perSource = Integer.getInteger("aicap.benchmark.rows", 3000);
        assertTrue(perSource >= 1 && perSource <= 10000);
        jdbc.execute("SET SESSION cte_max_recursion_depth = 11000");
        String meetingId = UUID.randomUUID().toString();
        String suggestionPrefix = "B" + UUID.randomUUID().toString().substring(0, 3);
        jdbc.update("INSERT INTO meetings(id,title,transcript,created_by,created_at) VALUES(?,?,?,1,NOW())",
                meetingId, "七来源容量基准", "synthetic");
        long pendingBefore = index.summary().pendingCount();

        for (String source : List.of("status", "planning", "review", "retro", "refinement")) {
            String sql = "INSERT INTO meeting_" + source + "_analyses " +
                    "(id,meeting_id,submitted_by,client_request_id,status,transcript,result_json,snapshots_json,created_at) " +
                    "WITH RECURSIVE seq(n) AS (SELECT 1 UNION ALL SELECT n+1 FROM seq WHERE n<?) " +
                    "SELECT UUID(),?,1,CONCAT('benchmark-" + source + "-',n),'completed','synthetic'," +
                    "CONCAT('{\"proposed_actions\":[{\"proposal_id\":\"p-',n,'-a\"}," +
                    "{\"proposal_id\":\"p-',n,'-b\"}]}'),'[]'," +
                    "TIMESTAMPADD(SECOND,n,'2025-01-01 00:00:00') FROM seq";
            assertEquals(perSource, jdbc.update(sql, perSource, meetingId));
        }
        String assignmentSql = "INSERT INTO meeting_assignment_analyses " +
                "(id,meeting_id,submitted_by,client_request_id,input_json,result_json,created_at) " +
                "WITH RECURSIVE seq(n) AS (SELECT 1 UNION ALL SELECT n+1 FROM seq WHERE n<?) " +
                "SELECT UUID(),?,1,CONCAT('benchmark-assignment-',n),'{}','{}'," +
                "TIMESTAMPADD(SECOND,n,'2025-01-01 00:00:00') FROM seq";
        assertEquals(perSource, jdbc.update(assignmentSql, perSource, meetingId));

        String suggestionSql = "INSERT INTO suggestions " +
                "(id,agent,kind,evidence,affected,note,change_json,status,created_at) " +
                "WITH RECURSIVE seq(n) AS (SELECT 1 UNION ALL SELECT n+1 FROM seq WHERE n<?) " +
                "SELECT CONCAT(?,LPAD(n,6,'0')),'meeting','meeting','synthetic','US01'," +
                "'synthetic','{}','pending',TIMESTAMPADD(SECOND,n,'2025-01-01 00:00:00') FROM seq";
        assertEquals(perSource, jdbc.update(suggestionSql, perSource, suggestionPrefix));
        String recordsSql = "INSERT INTO meeting_suggestion_records " +
                "(suggestion_id,meeting_id,client_request_id,request_hash,submitted_by,origin,reason,execution_status) " +
                "SELECT id,?,CONCAT('benchmark-',id),'benchmark',1,'manual','','not_started' " +
                "FROM suggestions WHERE id>=? AND id<=?";
        assertEquals(perSource, jdbc.update(recordsSql, meetingId,
                suggestionPrefix + "000001", suggestionPrefix + String.format("%06d", perSource)));

        long start = System.nanoTime();
        ReviewQueueIndexService.Page page = index.page("pending", "all", null, null, null, null, 50);
        long pageMillis = (System.nanoTime() - start) / 1_000_000;
        start = System.nanoTime();
        ReviewQueueIndexService.Summary summary = index.summary();
        long summaryMillis = (System.nanoTime() - start) / 1_000_000;
        assertEquals(50, page.items().size());
        assertNotNull(page.nextCursor());
        assertEquals(pendingBefore + 12L * perSource, summary.pendingCount());
        System.out.printf("SEVEN_SOURCE_BENCHMARK rows_per_source=%d proposals=%d page_ms=%d summary_ms=%d%n",
                perSource, 12 * perSource, pageMillis, summaryMillis);
    }
}
