package com.aicap.generation;

import com.aicap.llm.ModelClient;
import com.aicap.common.AgentError;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.times;
import org.mockito.ArgumentCaptor;

class ProjectGenerationRunnerTest {
    private final ObjectMapper mapper = new ObjectMapper();
    private final ProjectGenerationRunner runner = new ProjectGenerationRunner(null, null, mapper);

    @Test
    void parsesPlainAndFencedJsonObjectsWithoutChangingTheirContents() {
        assertTrue(runner.parseContent("{\"x\":1}").success());
        var jsonFence = runner.parseContent("```json\n{\"x\":1}\n```");
        assertTrue(jsonFence.success());
        assertTrue(jsonFence.jsonCleaningApplied());
        var plainFence = runner.parseContent("```\n{\"x\":1}\n```");
        assertTrue(plainFence.success());
        assertEquals(1, plainFence.value().path("x").asInt());
    }

    @Test
    void extractsOneBalancedObjectAroundExplanatoryTextAndIgnoresBracesInsideStrings() {
        var result = runner.parseContent("结果如下： {\"text\":\"字面量 { 和 }，以及 \\\" 引号\",\"nested\":{\"ok\":true}} 以上为 JSON。");
        assertTrue(result.success());
        assertEquals("字面量 { 和 }，以及 \" 引号", result.value().path("text").asText());
        assertTrue(result.jsonCleaningApplied());
    }

    @Test
    void classifiesArraysMissingObjectsAndInvalidJsonWithoutRepairingSyntax() {
        assertEquals("NON_OBJECT_TOP_LEVEL", runner.parseContent("[]").errorType());
        assertEquals("NO_JSON_OBJECT_FOUND", runner.parseContent("没有 JSON").errorType());
        assertEquals("EMPTY_CONTENT", runner.parseContent("  ").errorType());
        assertEquals("INVALID_JSON_SYNTAX", runner.parseContent("{\"x\":1,}").errorType());
        assertFalse(runner.parseContent("{\"x\":1,}").success());
    }

    @Test
    void retriesACompleteMalformedStageExactlyOnceAndRecordsSafeDiagnostics() throws Exception {
        ModelClient client = mock(ModelClient.class);
        when(client.completeAllowIncomplete(anyList(), eq(List.of()), eq(true), eq(8192)))
                .thenReturn(completion("not json", "stop"))
                .thenReturn(completion(detailedStoryStage(1).toString(), "stop"))
                .thenReturn(completion(validTaskStage().toString(), "stop"))
                .thenReturn(completion(validMilestoneStage().toString(), "stop"));
        List<ProjectGenerationRunner.ResponseDiagnostic> diagnostics = new java.util.ArrayList<>();

        runner.generate("NEW-test", "详细项目需求 ".repeat(400) + "约30条Story", "detailed", client, "", ignored -> { }, diagnostics::add);

        verify(client, times(4)).completeAllowIncomplete(anyList(), eq(List.of()), eq(true), eq(8192));
        var firstAttempt = diagnostics.stream().filter(d -> d.stageName().equals("stories") && d.attempt() == 1).findFirst().orElseThrow();
        var secondAttempt = diagnostics.stream().filter(d -> d.stageName().equals("stories") && d.attempt() == 2).findFirst().orElseThrow();
        assertEquals("NO_JSON_OBJECT_FOUND", firstAttempt.firstParseResult());
        assertEquals(1, firstAttempt.retryCount());
        assertEquals(1, secondAttempt.retryCount());
        assertEquals("RETRY_SUCCEEDED", secondAttempt.status());
        assertEquals(64, secondAttempt.responseSha256().length());
        assertFalse(diagnostics.toString().contains("Authorization"));
    }

    @Test
    void storyCountPhrasedAsApproximateThirtyEnablesStagedGeneration() throws Exception {
        ModelClient client = mock(ModelClient.class);
        when(client.completeAllowIncomplete(anyList(), eq(List.of()), eq(true), eq(8192)))
                .thenReturn(completion(detailedStoryStage(30).toString(), "stop"))
                .thenReturn(completion(validTaskStage().toString(), "stop"))
                .thenReturn(completion(validMilestoneStage().toString(), "stop"));

        ObjectNode result = runner.generate("NEW-test", "请生成项目规划，用户故事数量希望在30条左右。",
                "detailed", client, "");

        assertEquals(30, result.path("stories").size());
        verify(client, times(3)).completeAllowIncomplete(anyList(), eq(List.of()), eq(true), eq(8192));
    }

    @Test
    void failedRepairIsBoundedAndReportedAsRetryExhausted() {
        ModelClient client = mock(ModelClient.class);
        when(client.completeAllowIncomplete(anyList(), eq(List.of()), eq(true), eq(8192)))
                .thenReturn(completion("not json", "stop"))
                .thenReturn(completion("[]", "stop"));
        List<ProjectGenerationRunner.ResponseDiagnostic> diagnostics = new java.util.ArrayList<>();

        AgentError error = assertThrows(AgentError.class, () -> runner.generate("NEW-test",
                "详细项目需求 ".repeat(400) + "约30条Story", "detailed", client, "", ignored -> { }, diagnostics::add));

        assertEquals("retry_exhausted", error.getCode());
        verify(client, times(2)).completeAllowIncomplete(anyList(), eq(List.of()), eq(true), eq(8192));
        assertTrue(diagnostics.stream().anyMatch(d -> "RETRY_EXHAUSTED".equals(d.errorType())));
    }

    @Test
    void incompleteStageStillUsesContinuationInsteadOfFormatRetry() throws Exception {
        ModelClient client = mock(ModelClient.class);
        ObjectNode storyStage = detailedStoryStage(1);
        when(client.completeAllowIncomplete(anyList(), eq(List.of()), eq(true), eq(8192)))
                .thenReturn(completion("{\"stories\":[", "length"))
                .thenReturn(completion(storyStage.toString(), "stop"))
                .thenReturn(completion(validTaskStage().toString(), "stop"))
                .thenReturn(completion(validMilestoneStage().toString(), "stop"));
        List<ProjectGenerationRunner.ResponseDiagnostic> diagnostics = new java.util.ArrayList<>();

        runner.generate("NEW-test", "详细项目需求 ".repeat(400) + "约30条Story", "detailed", client, "", ignored -> { }, diagnostics::add);

        verify(client, times(4)).completeAllowIncomplete(anyList(), eq(List.of()), eq(true), eq(8192));
        assertTrue(diagnostics.stream().anyMatch(d -> "TRUNCATED_OR_INCOMPLETE".equals(d.errorType())));
        assertFalse(diagnostics.stream().anyMatch(d -> d.status().equals("PARSE_FAILED_RETRYING")));
    }

    @Test
    void explicitTaskOwnerAndWeeksOverrideModelOnlyForUniquelyMatchedTask() {
        ObjectNode draft = mapper.createObjectNode();
        ArrayNode tasks = draft.putArray("tasks");
        tasks.add(mapper.createObjectNode().put("id", "T10").put("name", "咨询预约接口")
                .put("owner_id", 1).put("week_start", 1).put("week_end", 1));
        tasks.add(mapper.createObjectNode().put("id", "T11").put("name", "排班后台")
                .put("owner_id", 1).put("week_start", 1).put("week_end", 1));

        runner.applyExplicitTaskConstraints(draft, "T10 咨询预约接口，负责人: 2，W3-W4\n排班后台后续完成");

        assertEquals(2, tasks.get(0).path("owner_id").asInt());
        assertEquals(3, tasks.get(0).path("week_start").asInt());
        assertEquals(4, tasks.get(0).path("week_end").asInt());
        assertTrue(tasks.get(0).path("_user_owner_explicit").asBoolean());
        assertTrue(tasks.get(0).path("_user_schedule_explicit").asBoolean());
        assertEquals(1, tasks.get(1).path("owner_id").asInt());
        assertEquals(1, tasks.get(1).path("week_start").asInt());
    }

    @Test
    void explicitSprintSectionsOverridePartiallyWrongModelAssignments() {
        ObjectNode draft = mapper.createObjectNode();
        draft.set("project", mapper.createObjectNode().put("total_sprints", 3));
        ArrayNode stories = mapper.createArrayNode();
        stories.add(story("US01", "需求分析与项目规划", 1));
        stories.add(story("US08", "商品查询接口开发", 1));
        stories.add(story("US10", "商品详情页面开发", 2));
        stories.add(story("US13", "前后端功能联调", 1));
        stories.add(story("US15", "系统性能优化", 1));
        stories.add(story("US20", "项目验收和上线准备", 1));
        draft.set("stories", stories);

        String request = """
                Sprint 1：完成基础功能建设。
                1. 需求分析和项目规划
                Sprint 2：完成交易核心功能。
                1. 商品查询接口开发
                2. 商品详情页面开发
                3. 前后端功能联调
                Sprint 3：完成系统优化和上线准备。
                1. 系统性能优化
                2. 项目验收和上线准备
                """;

        runner.applyExplicitSprintDistribution(draft, request);

        assertEquals(1, stories.get(0).path("sprint").asInt());
        assertEquals(2, stories.get(1).path("sprint").asInt());
        assertEquals(2, stories.get(2).path("sprint").asInt());
        assertEquals(2, stories.get(3).path("sprint").asInt());
        assertEquals(3, stories.get(4).path("sprint").asInt());
        assertEquals(3, stories.get(5).path("sprint").asInt());
    }

    @Test
    void explicitDetailedPlanAssignsAllTwentyStoriesToRequestedSprints() {
        ObjectNode draft = mapper.createObjectNode();
        draft.set("project", mapper.createObjectNode().put("total_sprints", 3));
        ArrayNode stories = mapper.createArrayNode();
        String[] titles = {
                "需求分析与项目规划", "数据库结构设计", "用户注册登录功能", "用户登录注册页面", "商品数据模型设计",
                "商品发布页面", "Sprint 1功能测试", "商品查询接口开发", "商品搜索功能", "商品详情页面开发",
                "订单创建与状态管理", "用户交流功能", "前后端功能联调", "交易流程测试", "系统性能优化",
                "页面交互优化", "系统安全检查", "完整功能测试", "缺陷修复跟踪", "项目验收和上线准备"
        };
        for (int i = 0; i < titles.length; i++) stories.add(story(String.format("US%02d", i + 1), titles[i], 1));
        draft.set("stories", stories);
        String request = """
                Sprint 1：
                1. 需求分析和项目规划
                2. 数据库结构设计
                3. 用户注册登录功能开发
                4. 用户登录注册页面开发
                5. 商品数据模型设计
                6. 商品发布页面开发
                7. Sprint 1功能测试

                Sprint 2：
                1. 商品查询接口开发
                2. 商品搜索功能开发
                3. 商品详情页面开发
                4. 订单创建与状态管理开发
                5. 用户交流功能开发
                6. 前后端功能联调
                7. 交易流程测试

                Sprint 3：
                1. 系统性能优化
                2. 页面交互优化
                3. 系统安全检查
                4. 完整功能测试
                5. 缺陷修复跟踪
                6. 项目验收和上线准备
                """;

        runner.applyExplicitSprintDistribution(draft, request);

        for (int i = 0; i < stories.size(); i++) {
            assertEquals(i < 7 ? 1 : i < 14 ? 2 : 3, stories.get(i).path("sprint").asInt(), titles[i]);
        }
    }

    @Test
    void promptFormattingKeepsLiteralPercentageWithoutTreatingItAsAConversion() throws Exception {
        ModelClient client = mock(ModelClient.class);
        ObjectNode message = mapper.createObjectNode().put("role", "assistant").put("content", "{}");
        when(client.complete(anyList(), eq(List.of()), anyBoolean()))
                .thenReturn(new ModelClient.Completion(message, java.util.Map.of()));

        runner.generate("NEW-test", "详细需求", "detailed", client);

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<com.fasterxml.jackson.databind.JsonNode>> messages = ArgumentCaptor.forClass(List.class);
        verify(client).complete(messages.capture(), eq(List.of()), eq(true));
        assertTrue(messages.getValue().get(0).path("content").asText().contains("25%"));
    }

    @Test
    void longDetailedPlanRetriesOnlyTruncatedStageAndBuildsValidDeduplicatedDraft() throws Exception {
        ModelClient client = mock(ModelClient.class);
        ObjectNode storyStage = detailedStoryStage(30);
        ObjectNode taskStage = emptyStage();
        taskStage.withArray("tasks").add(task("T99", "US01").put("name", "任务T01"));
        taskStage.withArray("tasks").add(task("T01", "US01"));
        taskStage.withArray("tasks").add(task("T02", "US02"));
        taskStage.withArray("dependencies").add(mapper.createObjectNode().put("from", "T01").put("to", "T02"));
        ObjectNode milestoneStage = emptyStage();
        milestoneStage.withArray("milestones").add(mapper.createObjectNode().put("id", "M1")
                .put("name", "核心功能完成").put("week", 4).put("related_task_ids", "T01,T02"));
        when(client.completeAllowIncomplete(anyList(), eq(List.of()), eq(true), eq(8192)))
                .thenReturn(completion("{\"stories\":[{\"id\":\"US01\",\"title\":\"功能1\",\"sprint\":1},{", "length"))
                .thenReturn(completion(storyStage.toString(), "stop"))
                .thenReturn(completion(taskStage.toString(), "stop"))
                .thenReturn(completion(milestoneStage.toString(), "stop"));
        java.util.List<ProjectGenerationRunner.TruncationInfo> truncations = new java.util.ArrayList<>();

        ObjectNode result = runner.generate("NEW-test", "详细项目需求 ".repeat(220) + "约30条Story",
                "detailed", client, "", truncations::add);

        assertEquals(30, result.path("stories").size());
        assertEquals(2, result.path("tasks").size());
        assertEquals(1, result.path("milestones").size());
        assertEquals(1, truncations.size());
        assertTrue(truncations.get(0).continuationTriggered());
        verify(client, times(4)).completeAllowIncomplete(anyList(), eq(List.of()), eq(true), eq(8192));
    }

    @Test
    void truncatedTasksContinuationPreservesAllStoriesAndReportsMergeCounts() throws Exception {
        ModelClient client = mock(ModelClient.class);
        ObjectNode stories = detailedStoryStage(30);
        ObjectNode remainingTasks = validTaskStage();
        remainingTasks.withArray("tasks").removeAll();
        remainingTasks.withArray("tasks").add(task("T01", "US01"));
        ObjectNode milestones = validMilestoneStage();
        String partialTasks = "{\"tasks\":[{\"id\":\"T30\",\"name\":\"续写前任务\",\"story_ref\":\"US30\",\"depends_on\":\"\"},{";
        when(client.completeAllowIncomplete(anyList(), eq(List.of()), eq(true), eq(8192)))
                .thenReturn(completion(stories.toString(), "stop"))
                .thenReturn(completion(partialTasks, "length"))
                .thenReturn(completion(remainingTasks.toString(), "stop"))
                .thenReturn(completion(milestones.toString(), "stop"));
        List<ProjectGenerationRunner.ResponseDiagnostic> diagnostics = new java.util.ArrayList<>();

        ObjectNode result = runner.generate("NEW-test", "详细项目需求 ".repeat(220) + "约30条Story",
                "detailed", client, "", ignored -> { }, diagnostics::add);

        assertEquals(30, result.path("stories").size());
        assertEquals(2, result.path("tasks").size());
        var merge = diagnostics.stream().filter(d -> "staged_merge".equals(d.stageName())).findFirst().orElseThrow();
        assertEquals(30, merge.beforeMergeStories());
        assertEquals(30, merge.afterMergeStories());
        assertEquals(2, merge.afterMergeTasks());
        assertTrue(diagnostics.stream().anyMatch(d -> "tasks".equals(d.stageName())
                && d.continuationRound() == 1 && d.parsedTasks() == 2));
    }

    @Test
    void detailedTasksCanonicalizeNumericStoryCodeBeforeBudgetAndStageMerge() throws Exception {
        ModelClient client = mock(ModelClient.class);
        ObjectNode stories = detailedStoryStage(1);
        ((ObjectNode) stories.path("stories").get(0)).put("id", 42);
        ObjectNode tasks = validTaskStage();
        ObjectNode task = (ObjectNode) tasks.path("tasks").get(0);
        task.remove("story_ref");
        task.put("storyCode", 42);
        when(client.completeAllowIncomplete(anyList(), eq(List.of()), eq(true), eq(8192)))
                .thenReturn(completion(stories.toString(), "stop"))
                .thenReturn(completion(tasks.toString(), "stop"))
                .thenReturn(completion(validMilestoneStage().toString(), "stop"));
        ObjectNode result = runner.generate("NEW-test", "详细项目需求 ".repeat(400) + "约30条Story",
                "detailed", client, "", ignored -> { }, ignored -> { });
        assertEquals("42", result.path("tasks").get(0).path("story_ref").asText());
        assertEquals("42", result.path("tasks").get(0).path("storyCode").asText());
        assertEquals("NUMBER", result.path("tasks").get(0).path(TaskStoryReferences.AUDIT).get(0).path("jsonType").asText());
        verify(client, times(3)).completeAllowIncomplete(anyList(), eq(List.of()), eq(true), eq(8192));
    }

    @Test
    void emptyStoriesStageRetriesOnceThenPreservesAllStoriesForTasks() throws Exception {
        ModelClient client = mock(ModelClient.class);
        ObjectNode validStories = detailedStoryStage(30);
        when(client.completeAllowIncomplete(anyList(), eq(List.of()), eq(true), eq(8192)))
                .thenReturn(completion(emptyStage().toString(), "stop"))
                .thenReturn(completion(validStories.toString(), "stop"))
                .thenReturn(completion(validTaskStage().toString(), "stop"))
                .thenReturn(completion(validMilestoneStage().toString(), "stop"));
        List<ProjectGenerationRunner.ResponseDiagnostic> diagnostics = new java.util.ArrayList<>();

        ObjectNode result = runner.generate("NEW-test", "详细项目需求 ".repeat(220) + "约30条Story",
                "detailed", client, "", ignored -> { }, diagnostics::add);

        assertEquals(30, result.path("stories").size());
        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<com.fasterxml.jackson.databind.JsonNode>> prompts = ArgumentCaptor.forClass(List.class);
        verify(client, times(4)).completeAllowIncomplete(prompts.capture(), eq(List.of()), eq(true), eq(8192));
        String retryPrompt = prompts.getAllValues().get(1).get(0).path("content").asText();
        assertTrue(retryPrompt.contains("必须包含非空 stories 数组"));
        assertTrue(retryPrompt.contains("project、members、sprints、activities、epics、stories、uml"));
        assertTrue(retryPrompt.contains("tasks、dependencies、milestones 必须为空数组"));
        var audit = diagnostics.stream().filter(d -> "STORIES_STAGE_RETRY_SUCCEEDED".equals(d.status())).findFirst().orElseThrow();
        assertEquals(2, audit.storiesStageAttemptCount());
        assertEquals(1, audit.storiesStageRetryCount());
        assertEquals(30, audit.retryParsedStories());
        assertTrue(audit.retrySucceeded());
    }

    @Test
    void validStoriesStageDoesNotAddSemanticRetry() throws Exception {
        ModelClient client = mock(ModelClient.class);
        when(client.completeAllowIncomplete(anyList(), eq(List.of()), eq(true), eq(8192)))
                .thenReturn(completion(detailedStoryStage(30).toString(), "stop"))
                .thenReturn(completion(validTaskStage().toString(), "stop"))
                .thenReturn(completion(validMilestoneStage().toString(), "stop"));
        List<ProjectGenerationRunner.ResponseDiagnostic> diagnostics = new java.util.ArrayList<>();

        runner.generate("NEW-test", "详细项目需求 ".repeat(220) + "约30条Story",
                "detailed", client, "", ignored -> { }, diagnostics::add);

        verify(client, times(3)).completeAllowIncomplete(anyList(), eq(List.of()), eq(true), eq(8192));
        assertFalse(diagnostics.stream().anyMatch(d -> "STORIES_STAGE_SEMANTIC_RETRYING".equals(d.status())));
    }

    @Test
    void emptyStoriesArrayFailsBeforeTasksStage() {
        assertInvalidStoriesStage(emptyStage(), "STORIES_STAGE_EMPTY");
    }

    @Test
    void missingStoriesFieldFailsBeforeTasksStage() {
        ObjectNode stage = emptyStage();
        stage.remove("stories");
        assertInvalidStoriesStage(stage, "STORIES_STAGE_EMPTY");
    }

    @Test
    void nonArrayStoriesFieldFailsBeforeTasksStage() {
        ObjectNode stage = emptyStage();
        stage.put("stories", "not-an-array");
        assertInvalidStoriesStage(stage, "STORIES_STAGE_EMPTY");
    }

    @Test
    void emptyStoriesArrayRetriesAndAcceptsValidRetry() throws Exception {
        assertStoriesSemanticRetrySucceeds(emptyStage());
    }

    @Test
    void missingStoriesFieldRetriesAndAcceptsValidRetry() throws Exception {
        ObjectNode stage = emptyStage();
        stage.remove("stories");
        assertStoriesSemanticRetrySucceeds(stage);
    }

    @Test
    void nonArrayStoriesFieldRetriesAndAcceptsValidRetry() throws Exception {
        ObjectNode stage = emptyStage();
        stage.put("stories", "not-an-array");
        assertStoriesSemanticRetrySucceeds(stage);
    }

    @Test
    void normalNonTruncatedDetailedStagesRemainUnchanged() throws Exception {
        ModelClient client = mock(ModelClient.class);
        when(client.completeAllowIncomplete(anyList(), eq(List.of()), eq(true), eq(8192)))
                .thenReturn(completion(detailedStoryStage(2).toString(), "stop"))
                .thenReturn(completion(validTaskStage().toString(), "stop"))
                .thenReturn(completion(validMilestoneStage().toString(), "stop"));

        ObjectNode result = runner.generate("NEW-test", "详细项目需求 ".repeat(220) + "约30条Story",
                "detailed", client);

        assertEquals(2, result.path("stories").size());
        assertEquals(1, result.path("tasks").size());
        assertEquals(1, result.path("milestones").size());
        verify(client, times(3)).completeAllowIncomplete(anyList(), eq(List.of()), eq(true), eq(8192));
    }

    @Test
    void secondTruncationFailsWithExplicitIncompleteResponse() throws Exception {
        ModelClient client = mock(ModelClient.class);
        when(client.completeAllowIncomplete(anyList(), eq(List.of()), eq(true), eq(8192)))
                .thenReturn(completion("{\"stories\":[", "length"))
                .thenReturn(completion("{\"stories\":[{", "length"));
        java.util.List<ProjectGenerationRunner.TruncationInfo> truncations = new java.util.ArrayList<>();

        AgentError error = assertThrows(AgentError.class, () -> runner.generate("NEW-test",
                "详细项目需求 ".repeat(220) + "约30条Story", "detailed", client, "", truncations::add));

        assertEquals("incomplete_response", error.getCode());
        assertEquals(2, truncations.size());
        assertTrue(truncations.get(0).continuationTriggered());
        assertTrue(!truncations.get(1).continuationTriggered());
        verify(client, times(2)).completeAllowIncomplete(anyList(), eq(List.of()), eq(true), eq(8192));
    }

    @Test
    void overviewModeUsesResilientSingleCallPath() throws Exception {
        ModelClient client = mock(ModelClient.class);
        ObjectNode message = mapper.createObjectNode().put("role", "assistant").put("content", "{}");
        when(client.completeAllowIncomplete(anyList(), eq(List.of()), eq(true), eq(8192)))
                .thenReturn(new ModelClient.Completion(message, java.util.Map.of()));

        runner.generate("NEW-test", "概略需求 ".repeat(500), "overview", client);

        verify(client).completeAllowIncomplete(anyList(), eq(List.of()), eq(true), eq(8192));
        verify(client, times(0)).complete(anyList(), eq(List.of()), eq(true));
    }

    @Test
    void overviewTruncationCompletesMissingRowsAndDeduplicatesExistingRows() throws Exception {
        ModelClient client = mock(ModelClient.class);
        ObjectNode remainder = emptyStage();
        remainder.set("project", mapper.createObjectNode().put("total_sprints", 3));
        remainder.withArray("stories").add(story("US01", "登录", 1));
        remainder.withArray("stories").add(story("US02", "查询", 2));
        remainder.withArray("tasks").add(task("T01", "US01"));
        remainder.withArray("tasks").add(task("T02", "US02"));
        String partial = "{\"stories\":[{\"id\":\"US01\",\"title\":\"登录\",\"sprint\":1}],"
                + "\"tasks\":[{\"id\":\"T01\",\"name\":\"任务T01\",\"story_ref\":\"US01\"},{";
        when(client.completeAllowIncomplete(anyList(), eq(List.of()), eq(true), eq(8192)))
                .thenReturn(completion(partial, "length"))
                .thenReturn(completion(remainder.toString(), "stop"));
        java.util.List<ProjectGenerationRunner.TruncationInfo> truncations = new java.util.ArrayList<>();

        ObjectNode result = runner.generate("NEW-test", "做一个三 Sprint 工单系统", "overview",
                client, "", truncations::add);

        assertEquals(2, result.path("stories").size());
        assertEquals(2, result.path("tasks").size());
        assertEquals(1, truncations.size());
        assertEquals(Boolean.TRUE, truncations.get(0).continuationSucceeded());
        verify(client, times(2)).completeAllowIncomplete(anyList(), eq(List.of()), eq(true), eq(8192));
    }

    @Test
    void overviewTruncationMergesRepeatedTaskDependenciesAsSet() throws Exception {
        ModelClient client = mock(ModelClient.class);
        ObjectNode remainder = emptyStage();
        remainder.withArray("tasks").add(task("T02", "US01").put("depends_on", "T01,T03"));
        String partial = "{\"tasks\":[{\"id\":\"T02\",\"name\":\"任务T02\",\"story_ref\":\"US01\",\"depends_on\":\"t01,T01\"},{";
        when(client.completeAllowIncomplete(anyList(), eq(List.of()), eq(true), eq(8192)))
                .thenReturn(completion(partial, "length"))
                .thenReturn(completion(remainder.toString(), "stop"));

        ObjectNode result = runner.generate("NEW-test", "做一个三 Sprint 工单系统", "overview",
                client, "", ignored -> { });

        assertEquals(1, result.path("tasks").size());
        assertEquals("T01,T03", result.path("tasks").get(0).path("depends_on").asText());
    }

    @Test
    void overviewSecondTruncationFailsClearly() throws Exception {
        ModelClient client = mock(ModelClient.class);
        when(client.completeAllowIncomplete(anyList(), eq(List.of()), eq(true), eq(8192)))
                .thenReturn(completion("{\"stories\":[", "length"))
                .thenReturn(completion("{\"stories\":[{", "length"));
        java.util.List<ProjectGenerationRunner.TruncationInfo> truncations = new java.util.ArrayList<>();

        AgentError error = assertThrows(AgentError.class, () -> runner.generate("NEW-test",
                "做一个三 Sprint 工单系统", "overview", client, "", truncations::add));

        assertEquals("incomplete_response", error.getCode());
        assertEquals(Boolean.FALSE, truncations.get(0).continuationSucceeded());
    }

    @Test
    void excelSupplementTruncationOnlyAddsMissingRows() throws Exception {
        ModelClient client = mock(ModelClient.class);
        ObjectNode remainder = emptyStage();
        remainder.withArray("stories").add(story("US10", "补充验收", 3));
        remainder.withArray("stories").add(story("US11", "异常处理", 3));
        String partial = "{\"stories\":[{\"id\":\"US10\",\"title\":\"补充验收\",\"sprint\":3},{";
        when(client.completeAllowIncomplete(anyList(), eq(List.of()), eq(true), eq(8192)))
                .thenReturn(completion(partial, "length"))
                .thenReturn(completion(remainder.toString(), "stop"));

        ObjectNode result = runner.generate("NEW-test", "只补充导入草案缺失项", "excel", client,
                "导入草案中的用户字段不得覆盖", ignored -> { });

        assertEquals(2, result.path("stories").size());
        verify(client, times(2)).completeAllowIncomplete(anyList(), eq(List.of()), eq(true), eq(8192));
    }

    @Test
    void detailedTaskSupplementRetriesOneEmptyTaskResponseAndKeepsTheSameGapBudget() throws Exception {
        ModelClient client = mock(ModelClient.class);
        ObjectNode current = emptyStage();
        current.withArray("stories").add(story("US13", "学生提交报名申请", 2));
        ObjectNode valid = emptyStage();
        valid.withArray("tasks").add(task("T01", "US13").put("name", "报名提交后端接口"));
        when(client.completeAllowIncomplete(anyList(), eq(List.of()), eq(true), eq(8192)))
                .thenReturn(completion(emptyStage().toString(), "stop"))
                .thenReturn(completion(valid.toString(), "stop"));

        ObjectNode result = runner.generateDetailedTaskSupplement("P001", current,
                java.util.Map.of("US13", java.util.Set.of("backend")), 1, client,
                ignored -> { }, ignored -> { });

        assertEquals(1, result.path("tasks").size());
        assertEquals(2, result.path("_task_generation_audit").path("supplementAttemptCount").asInt());
        assertEquals(1, result.path("_task_generation_audit").path("supplementRetryCount").asInt());
        assertEquals("TASKS_STAGE_EMPTY", result.path("_task_generation_audit").path("attempt1ErrorCode").asText());
        assertEquals(1, result.path("_task_generation_audit").path("attempt2ParsedTasks").asInt());
        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<com.fasterxml.jackson.databind.JsonNode>> prompts = ArgumentCaptor.forClass(List.class);
        verify(client, times(2)).completeAllowIncomplete(prompts.capture(), eq(List.of()), eq(true), eq(8192));
        String retryPrompt = prompts.getAllValues().get(1).get(0).path("content").asText();
        assertTrue(retryPrompt.contains("US13"));
        assertTrue(retryPrompt.contains("学生提交报名申请"));
        assertTrue(retryPrompt.contains("硬上限为 1"));
    }

    @Test
    void detailedTaskSupplementStopsAfterTwoEmptyResponsesAndCarriesFailureAudit() {
        ModelClient client = mock(ModelClient.class);
        ObjectNode current = emptyStage();
        current.withArray("stories").add(story("US13", "学生提交报名申请", 2));
        when(client.completeAllowIncomplete(anyList(), eq(List.of()), eq(true), eq(8192)))
                .thenReturn(completion(emptyStage().toString(), "stop"))
                .thenReturn(completion(emptyStage().toString(), "stop"));

        ProjectGenerationRunner.DetailedSupplementFailure error = assertThrows(
                ProjectGenerationRunner.DetailedSupplementFailure.class,
                () -> runner.generateDetailedTaskSupplement("P001", current,
                        java.util.Map.of("US13", java.util.Set.of("backend")), 1, client,
                        ignored -> { }, ignored -> { }));

        assertEquals("TASKS_STAGE_EMPTY", error.getCode());
        assertEquals(2, error.audit().path("supplementAttemptCount").asInt());
        assertEquals(1, error.audit().path("supplementRetryCount").asInt());
        assertEquals("TASKS_STAGE_EMPTY", error.audit().path("attempt1ErrorCode").asText());
        assertEquals("TASKS_STAGE_EMPTY", error.audit().path("attempt2ErrorCode").asText());
        verify(client, times(2)).completeAllowIncomplete(anyList(), eq(List.of()), eq(true), eq(8192));
    }

    private ModelClient.Completion completion(String content, String finishReason) {
        ObjectNode message = mapper.createObjectNode().put("role", "assistant").put("content", content);
        return new ModelClient.Completion(message, java.util.Map.of(), finishReason, content.length());
    }

    private ObjectNode detailedStoryStage(int count) {
        ObjectNode stage = emptyStage();
        stage.set("project", mapper.createObjectNode().put("total_sprints", 3));
        for (int i = 1; i <= count; i++) stage.withArray("stories").add(story(String.format("US%02d", i), "功能" + i, (i - 1) / 10 + 1));
        return stage;
    }

    private void assertInvalidStoriesStage(ObjectNode stage, String expectedCode) {
        ModelClient client = mock(ModelClient.class);
        when(client.completeAllowIncomplete(anyList(), eq(List.of()), eq(true), eq(8192)))
                .thenReturn(completion(stage.toString(), "stop"))
                .thenReturn(completion(stage.toString(), "stop"));
        List<ProjectGenerationRunner.ResponseDiagnostic> diagnostics = new java.util.ArrayList<>();

        AgentError error = assertThrows(AgentError.class, () -> runner.generate("NEW-test",
                "详细项目需求 ".repeat(220) + "约30条Story", "detailed", client, "",
                ignored -> { }, diagnostics::add));

        assertEquals(expectedCode, error.getCode());
        assertTrue(error.getMessage().contains(expectedCode));
        verify(client, times(2)).completeAllowIncomplete(anyList(), eq(List.of()), eq(true), eq(8192));
        assertTrue(diagnostics.stream().anyMatch(d -> "stories".equals(d.stageName()) && d.parsedStories() == 0));
        assertTrue(diagnostics.stream().anyMatch(d -> "STORIES_STAGE_RETRY_FAILED".equals(d.status())));
    }

    private void assertStoriesSemanticRetrySucceeds(ObjectNode invalidStage) throws Exception {
        ModelClient client = mock(ModelClient.class);
        when(client.completeAllowIncomplete(anyList(), eq(List.of()), eq(true), eq(8192)))
                .thenReturn(completion(invalidStage.toString(), "stop"))
                .thenReturn(completion(detailedStoryStage(30).toString(), "stop"))
                .thenReturn(completion(validTaskStage().toString(), "stop"))
                .thenReturn(completion(validMilestoneStage().toString(), "stop"));
        List<ProjectGenerationRunner.ResponseDiagnostic> diagnostics = new java.util.ArrayList<>();
        ObjectNode result = runner.generate("NEW-test", "详细项目需求 ".repeat(220) + "约30条Story",
                "detailed", client, "", ignored -> { }, diagnostics::add);
        assertEquals(30, result.path("stories").size());
        verify(client, times(4)).completeAllowIncomplete(anyList(), eq(List.of()), eq(true), eq(8192));
        var audit = diagnostics.stream().filter(d -> "STORIES_STAGE_RETRY_SUCCEEDED".equals(d.status())).findFirst().orElseThrow();
        assertEquals(0, audit.firstParsedStories());
        assertEquals(30, audit.retryParsedStories());
    }

    private ObjectNode validTaskStage() {
        ObjectNode stage = emptyStage();
        stage.withArray("tasks").add(task("T01", "US01"));
        return stage;
    }

    private ObjectNode validMilestoneStage() {
        ObjectNode stage = emptyStage();
        stage.withArray("milestones").add(mapper.createObjectNode().put("id", "M1")
                .put("name", "交付").put("week", 2).put("related_task_ids", "T01"));
        return stage;
    }

    private ObjectNode emptyStage() {
        ObjectNode stage = mapper.createObjectNode();
        stage.set("project", mapper.createObjectNode());
        for (String key : List.of("members", "sprints", "activities", "epics", "stories", "tasks", "dependencies", "milestones"))
            stage.set(key, mapper.createArrayNode());
        stage.set("uml", mapper.createObjectNode());
        return stage;
    }

    private ObjectNode task(String id, String storyRef) {
        return mapper.createObjectNode().put("id", id).put("name", "任务" + id).put("owner_id", 1)
                .put("hours", 8).put("week_start", 1).put("week_end", 1)
                .put("story_ref", storyRef).put("depends_on", "");
    }

    private ObjectNode story(String id, String title, int sprint) {
        return mapper.createObjectNode().put("id", id).put("title", title).put("sprint", sprint);
    }
}
