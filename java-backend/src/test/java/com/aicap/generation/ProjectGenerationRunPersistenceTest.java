package com.aicap.generation;

import com.aicap.entity.ProjectGenerationRun;
import com.aicap.mapper.ProjectGenerationRunMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;

@SpringBootTest(properties = {
        "aicap.seed-on-start=false",
        "aicap.llm.agent-worker-enabled=false"
})
@Transactional
class ProjectGenerationRunPersistenceTest {

    @Autowired
    private ProjectGenerationRunMapper runs;

    @Test
    void persistsDetailedRequestLongerThanLegacyVarcharLimitWithoutTruncation() {
        String requestText = "详细需求：" + "校园志愿服务管理、报名审核、签到签退和统计验收。".repeat(300);
        ProjectGenerationRun run = new ProjectGenerationRun();
        run.setId(UUID.randomUUID().toString());
        run.setRequestedBy(1);
        run.setProjectId("NEW-LONGTEXTTEST");
        run.setMode("detailed");
        run.setStrategy("create");
        run.setSourceName("detailed_natural_language");
        run.setRequestText(requestText);
        run.setStatus("queued");
        run.setAttempt(1);
        run.setModel("test-model");
        run.setPromptVersion("project-generator-v1");
        run.setCreatedAt(LocalDateTime.now());
        run.setUpdatedAt(LocalDateTime.now());

        assertEquals(1, runs.insert(run));
        assertEquals(requestText, runs.selectById(run.getId()).getRequestText());
    }
}
