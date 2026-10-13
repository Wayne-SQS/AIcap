package com.aicap.generation;

import com.aicap.mapper.StoryMapper;
import com.aicap.mapper.TaskMapper;
import com.aicap.mapper.UserMapper;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.lang.reflect.Method;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.mock;

class ProjectGenerationWeakDependencyTest {
    private final ObjectMapper mapper = new ObjectMapper();
    private final ProjectGenerationValidator validator = new ProjectGenerationValidator(
            mock(StoryMapper.class), mock(TaskMapper.class), mock(UserMapper.class), mapper);

    private ObjectNode task(String id, String story, String name, String predecessors) {
        return mapper.createObjectNode().put("id", id).put("story_ref", story).put("name", name)
                .put("depends_on", predecessors).put("generation_identity", "test:" + id);
    }

    private ObjectNode compact(ArrayNode rows) throws Exception {
        Method method = ProjectGenerationValidator.class.getDeclaredMethod("compactDetailedDependencies",
                ArrayNode.class, ArrayNode.class);
        method.setAccessible(true);
        return (ObjectNode) method.invoke(validator, rows, mapper.createArrayNode());
    }

    @Test
    void sameStoryBackendToFrontendWeakEdgeIsRetainedAndDeduplicated() throws Exception {
        ArrayNode rows = mapper.createArrayNode().add(task("A", "S1", "活动统计后端接口", ""))
                .add(task("B", "S1", "活动统计前端页面", "A,a,A"));
        ObjectNode audit = compact(rows);
        assertEquals("A", rows.get(1).path("weak_dependencies").asText());
        assertEquals("", rows.get(1).path("depends_on").asText());
        assertEquals(1, audit.path("weakBeforeCompaction").asInt());
        assertEquals(1, audit.path("weakSameStoryCount").asInt());
        assertEquals("test:B", rows.get(1).path("generation_identity").asText());
    }

    @Test
    void explicitCrossStoryLifecyclesRemainWeak() throws Exception {
        for (List<String> names : List.of(
                List.of("报名提交结果通知", "报名审核结果通知"),
                List.of("签到异常处理", "签退异常处理"),
                List.of("服务时长生成统计", "服务时长查看统计"),
                List.of("报名数据准备日志", "报名数据读取页面"),
                List.of("报名统计后端接口", "报名统计前端展示页面"))) {
            ArrayNode rows = mapper.createArrayNode().add(task("A", "S1", names.get(0), ""))
                    .add(task("B", "S2", names.get(1), "A"));
            ObjectNode audit = compact(rows);
            assertEquals("A", rows.get(1).path("weak_dependencies").asText(), names.toString());
            assertEquals("", rows.get(1).path("depends_on").asText(), names.toString());
            assertEquals(1, audit.path("weakCrossStoryCount").asInt());
        }
    }

    @Test
    void genericKeywordsAndUnrelatedBusinessDomainsDoNotCreateWeakEdges() throws Exception {
        for (List<String> names : List.of(
                List.of("报名状态查询接口", "活动状态管理页面"),
                List.of("活动统计查询接口", "活动统计管理接口"),
                List.of("后端状态管理接口", "前端状态查看页面测试"),
                List.of("报名统计后端接口", "消息统计前端页面"),
                List.of("活动列表统计后端接口", "活动取消统计前端页面"),
                List.of("报名及服务时长统计后端接口", "服务时长统计前端页面"))) {
            ArrayNode rows = mapper.createArrayNode().add(task("A", "S1", names.get(0), ""))
                    .add(task("B", "S2", names.get(1), "A"));
            ObjectNode audit = compact(rows);
            assertEquals("", rows.get(1).path("weak_dependencies").asText(), names.toString());
            assertEquals(1, audit.path("weakDroppedByLowEvidence").asInt(), names.toString());
            assertEquals(0, rows.get(1).path("dependency_classes").size());
        }
    }

    @Test
    void sameStoryStageAdjacencyUsesPresentStagesAndShortcutIsCompressed() throws Exception {
        ArrayNode rows = mapper.createArrayNode().add(task("A", "S1", "登录后端接口", ""))
                .add(task("B", "S1", "登录前端页面", "A"))
                .add(task("C", "S1", "登录验收测试", "A,B"));
        ObjectNode audit = compact(rows);
        assertEquals("A", rows.get(1).path("depends_on").asText());
        assertEquals("B", rows.get(2).path("weak_dependencies").asText());
        assertEquals(1, audit.path("weakDroppedByLowEvidence").asInt());
        assertEquals(1, audit.path("weakSameStoryCount").asInt());
    }

    @ParameterizedTest
    @ValueSource(ints = {5, 6})
    void ordinaryCrossStoryHubIsLimitedButSameStoryEdgeSurvives(int consumers) throws Exception {
        ArrayNode rows = mapper.createArrayNode().add(task("A", "S0", "服务时长生成统计接口", ""))
                .add(task("LOCAL", "S0", "服务时长统计前端页面", "A"));
        for (int i = 1; i <= consumers; i++) rows.add(task("B" + i, "S" + i, "服务时长查看统计页面", "A"));
        ObjectNode audit = compact(rows);
        assertEquals(consumers, audit.path("weakDroppedByHubLimit").asInt());
        assertEquals("A", rows.get(1).path("weak_dependencies").asText());
        for (int i = 2; i < rows.size(); i++) assertEquals("", rows.get(i).path("weak_dependencies").asText());
        assertEquals(1, audit.path("weakAfterCompaction").asInt());
    }

    @Test
    void fourStoryHubRemainsWhenEvidenceIsStrong() throws Exception {
        ArrayNode rows = mapper.createArrayNode().add(task("A", "S0", "服务时长生成统计接口", ""));
        for (int i = 1; i <= 4; i++) rows.add(task("B" + i, "S" + i, "服务时长查看统计页面", "A"));
        ObjectNode audit = compact(rows);
        assertEquals(4, audit.path("weakAfterCompaction").asInt());
        assertEquals(0, audit.path("weakDroppedByHubLimit").asInt());
    }

    @Test
    void explicitCommonCapabilitiesAreNotMistakenForOrdinaryHubs() throws Exception {
        for (List<String> names : List.of(
                List.of("公共数据初始化", "活动数据查询页面"),
                List.of("基础设施部署", "报名后端接口接入"),
                List.of("统一认证权限服务", "报名权限校验联调"),
                List.of("通用服务初始化", "报名接入通用服务联调"))) {
            ArrayNode rows = mapper.createArrayNode().add(task("A", "S0", names.get(0), ""));
            for (int i = 1; i <= 6; i++) rows.add(task("B" + i, "S" + i, names.get(1), "A"));
            ObjectNode audit = compact(rows);
            assertEquals(6, audit.path("weakAfterCompaction").asInt(), names.toString());
            assertEquals(0, audit.path("weakDroppedByHubLimit").asInt());
        }
    }

    @Test
    void hubCountsDistinctStoriesRatherThanNumberOfTasks() throws Exception {
        ArrayNode rows = mapper.createArrayNode().add(task("A", "S0", "服务时长生成统计接口", ""));
        for (int i = 1; i <= 6; i++) rows.add(task("B" + i, "S1", "服务时长查看统计页面", "A"));
        assertEquals(6, compact(rows).path("weakAfterCompaction").asInt());
    }

    @Test
    void sameStoryWeakShortcutIsCompressedUsingSurvivingChain() throws Exception {
        ArrayNode rows = mapper.createArrayNode().add(task("A", "S1", "服务时长生成统计接口", ""))
                .add(task("B", "S1", "服务时长查询统计接口", "A"))
                .add(task("C", "S1", "服务时长查看统计页面", "A,B"));
        ObjectNode audit = compact(rows);
        assertEquals("A", rows.get(1).path("weak_dependencies").asText());
        assertEquals("B", rows.get(2).path("weak_dependencies").asText());
        assertEquals(1, rows.get(2).path("dependency_classes").size());
        assertEquals(1, audit.path("weakDroppedByRedundantChain").asInt());
        assertEquals(3, audit.path("weakBeforeCompaction").asInt());
        assertEquals(2, audit.path("weakAfterCompaction").asInt());
        assertEquals(0, compact(rows).path("weakDroppedByRedundantChain").asInt());
    }

    @Test
    void coreEdgesAreUnaffectedByHubLimitAndTransitiveCompression() throws Exception {
        ArrayNode rows = mapper.createArrayNode().add(task("A", "S0", "报名数据库表结构", ""))
                .add(task("B", "S0", "报名后端接口", "A"))
                .add(task("C", "S0", "报名前端页面服务", "A,B"));
        for (int i = 1; i <= 6; i++) rows.add(task("D" + i, "S" + i, "报名后端接口", "A"));
        ObjectNode audit = compact(rows);
        assertEquals("A,B", rows.get(2).path("depends_on").asText());
        for (int i = 3; i < rows.size(); i++) assertEquals("A", rows.get(i).path("depends_on").asText());
        assertEquals(0, audit.path("weakDroppedByHubLimit").asInt());
        assertEquals(0, audit.path("weakDroppedByRedundantChain").asInt());
    }

    @Test
    void riskDetailsAreCappedWithoutTruncatingApiDependenciesOrHardRisks() throws Exception {
        ArrayNode rows = mapper.createArrayNode().add(task("A", "S0", "公共数据初始化", ""));
        for (int i = 1; i <= 20; i++) rows.add(task("B" + i, "S" + i, "活动数据查看页面", "A"));
        compact(rows);
        rows.add(task("HARD", "S0", "硬依赖任务", "A").put("week_start", 1).put("week_end", 1));
        ((ObjectNode) rows.get(0)).put("week_start", 2).put("week_end", 2);
        JsonNode before = rows.deepCopy();
        Method risks = ProjectGenerationValidator.class.getDeclaredMethod("risks", ArrayNode.class,
                ArrayNode.class, String.class, String.class, JsonNode.class);
        risks.setAccessible(true);
        JsonNode report = (JsonNode) risks.invoke(validator, mapper.createArrayNode(), rows, "replace", "test", null);
        int details = 0, hard = 0, summaries = 0;
        for (JsonNode risk : report.path("dependencyRisk")) {
            switch (risk.path("kind").asText()) {
                case "weak_dependency" -> details++;
                case "dependency" -> hard++;
                case "weak_dependency_summary" -> {
                    summaries++;
                    assertEquals(5, risk.path("omittedCount").asInt());
                    assertEquals("其余 5 条弱依赖已省略，仅供参考，不作为硬排期下界", risk.path("message").asText());
                }
            }
        }
        assertEquals(15, details);
        assertEquals(1, hard);
        assertEquals(1, summaries);
        assertEquals(before, rows);
        Method dependencies = ProjectGenerationValidator.class.getDeclaredMethod("dependencies", ArrayNode.class);
        dependencies.setAccessible(true);
        ArrayNode edges = (ArrayNode) dependencies.invoke(validator, rows);
        assertEquals(21, edges.size());
        for (int i = 1; i <= 20; i++) assertEquals("A", rows.get(i).path("weak_dependencies").asText());
    }
}
