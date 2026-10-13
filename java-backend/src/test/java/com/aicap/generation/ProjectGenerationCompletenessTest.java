package com.aicap.generation;

import com.aicap.llm.LlmProperties;
import com.aicap.common.AgentError;
import com.aicap.llm.ModelClient;
import com.aicap.entity.ProjectGenerationRun;
import com.aicap.entity.User;
import com.aicap.mapper.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class ProjectGenerationCompletenessTest {
    private final ObjectMapper mapper = new ObjectMapper();
    private final UserMapper users = mock(UserMapper.class);
    private final StoryMapper stories = mock(StoryMapper.class);
    private final TaskMapper tasks = mock(TaskMapper.class);
    private ProjectGenerationValidator validator() {
        User a=new User(); a.setId(1); a.setDisplayName("后端成员"); a.setRole("member"); a.setCapacityHours(40);
        User b=new User(); b.setId(2); b.setDisplayName("前端成员"); b.setRole("member"); b.setCapacityHours(40);
        when(users.selectList(any())).thenReturn(List.of(a,b));
        when(users.selectById(any())).thenAnswer(i -> {
            Object id = i.getArgument(0);
            if (!(id instanceof Number)) return null;
            return ((Number) id).intValue() == 1 ? a : b;
        });
        when(stories.selectList(any())).thenReturn(List.of()); when(tasks.selectList(any())).thenReturn(List.of());
        return new ProjectGenerationValidator(stories,tasks,users,mapper);
    }

    @Test void thirtyStoriesWithCoarseCompositeTasksRetainRecognizedCoverage() {
        ObjectNode draft = mapper.createObjectNode();
        for (int i = 1; i <= 30; i++) {
            String id = String.format("US%02d", i);
            draft.withArray("stories").add(mapper.createObjectNode().put("id", id).put("title", "活动详情查看" + i));
            draft.withArray("tasks").add(mapper.createObjectNode().put("id", "T" + i)
                    .put("name", "活动详情接口及页面" + i).put("story_ref", id));
        }
        for (int i = 31; i <= 35; i++) draft.withArray("tasks").add(mapper.createObjectNode()
                .put("id", "T" + i).put("name", "项目基础配置" + i).put("story_ref", ""));

        ObjectNode coverage = validator().detailedTaskStageCoverage(draft);

        assertEquals(0, coverage.path("stageCoverageGapStoryCount").asInt());
        assertEquals(30, coverage.path("coarseCompositeTaskCount").asInt());
        assertEquals(30, coverage.path("storiesWithOneTask").asInt());
    }

    @Test void detailedSupplementAcceptsOnlyRequestedMissingStageAndSkipsWhenCovered() throws Exception {
        var v = validator();
        var jobs = new ProjectGenerationJobs(mock(ProjectGenerationRunMapper.class), mock(ProjectGenerationEventMapper.class),
                mock(ProjectGenerationRunner.class), v, mock(ModelClient.class), mock(LlmProperties.class), mapper);
        ObjectNode draft = mapper.createObjectNode();
        draft.withArray("stories").add(mapper.createObjectNode().put("id", "US01").put("title", "活动详情查看"));
        draft.withArray("tasks").add(mapper.createObjectNode().put("id", "T01")
                .put("name", "活动详情后端接口").put("story_ref", "US01"));
        ObjectNode raw = draft.deepCopy();
        java.lang.reflect.Method planMethod = ProjectGenerationJobs.class.getDeclaredMethod(
                "detailedSupplementPlan", ObjectNode.class, ObjectNode.class);
        planMethod.setAccessible(true);
        Object plan = planMethod.invoke(jobs, draft, raw);
        java.lang.reflect.Method budgetMethod = plan.getClass().getDeclaredMethod("budget");
        budgetMethod.setAccessible(true);
        assertEquals(1, budgetMethod.invoke(plan));

        ObjectNode supplement = mapper.createObjectNode();
        supplement.withArray("tasks")
                .add(mapper.createObjectNode().put("id", "T01").put("name", "活动详情前端页面").put("story_ref", "US01"))
                .add(mapper.createObjectNode().put("id", "T02").put("name", "活动详情后端接口补充").put("story_ref", "US01"));
        java.lang.reflect.Method filter = ProjectGenerationJobs.class.getDeclaredMethod(
                "filterDetailedSupplement", ObjectNode.class, ObjectNode.class, plan.getClass());
        filter.setAccessible(true);
        filter.invoke(jobs, raw, supplement, plan);
        assertEquals(1, supplement.path("tasks").size());
        assertTrue(supplement.path("tasks").get(0).path("name").asText().contains("前端页面"));

        draft.withArray("tasks").add(mapper.createObjectNode().put("id", "T02")
                .put("name", "活动详情前端页面").put("story_ref", "US01"));
        Object coveredPlan = planMethod.invoke(jobs, draft, draft);
        assertEquals(0, budgetMethod.invoke(coveredPlan));
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(strings = {"story_ref", "story_refs", "storyCode", "story_code", "story"})
    void supplementFilterUsesCanonicalNumericAliases(String field) throws Exception {
        var jobs = new ProjectGenerationJobs(mock(ProjectGenerationRunMapper.class), mock(ProjectGenerationEventMapper.class),
                mock(ProjectGenerationRunner.class), validator(), mock(ModelClient.class), mock(LlmProperties.class), mapper);
        ObjectNode draft = mapper.createObjectNode();
        draft.putArray("stories").add(mapper.createObjectNode().put("id", 42).put("title", "活动详情查看"));
        draft.putArray("tasks").add(mapper.createObjectNode().put("id", "T1")
                .put("name", "活动详情后端接口").put("story_ref", "42"));
        var planning = ProjectGenerationJobs.class.getDeclaredMethod("detailedSupplementPlan", ObjectNode.class, ObjectNode.class);
        planning.setAccessible(true);
        Object plan = planning.invoke(jobs, draft, draft);
        ObjectNode supplement = mapper.createObjectNode();
        supplement.putArray("tasks").add(mapper.createObjectNode().put("id", "T2")
                .put("name", "活动详情前端页面").put(field, 42));
        var filter = ProjectGenerationJobs.class.getDeclaredMethod("filterDetailedSupplement", ObjectNode.class, ObjectNode.class, plan.getClass());
        filter.setAccessible(true);
        filter.invoke(jobs, draft, supplement, plan);
        assertEquals(1, supplement.path("tasks").size());
        assertEquals("42", supplement.path("tasks").get(0).path("story_ref").asText());
        assertEquals("42", supplement.path("tasks").get(0).path("storyCode").asText());
        assertEquals("NUMBER", supplement.path("tasks").get(0).path(TaskStoryReferences.AUDIT).get(0).path("jsonType").asText());
    }

    @Test void failedDetailedSupplementKeepsTwoAttemptAuditAndRemainingHardGap() throws Exception {
        var v = validator();
        var jobs = new ProjectGenerationJobs(mock(ProjectGenerationRunMapper.class), mock(ProjectGenerationEventMapper.class),
                mock(ProjectGenerationRunner.class), v, mock(ModelClient.class), mock(LlmProperties.class), mapper);
        ObjectNode draft = mapper.createObjectNode();
        draft.withArray("stories").add(mapper.createObjectNode().put("id", "US01").put("title", "活动列表查看"));
        draft.withArray("tasks").add(mapper.createObjectNode().put("id", "T01")
                .put("name", "活动列表前端页面").put("story_ref", "US01"));
        draft.set("qualityAdjustments", mapper.createObjectNode().set("taskGeneration", mapper.createObjectNode()));
        ObjectNode raw = draft.deepCopy();
        java.lang.reflect.Method planMethod = ProjectGenerationJobs.class.getDeclaredMethod(
                "detailedSupplementPlan", ObjectNode.class, ObjectNode.class);
        planMethod.setAccessible(true);
        Object plan = planMethod.invoke(jobs, draft, raw);
        java.lang.reflect.Method initialize = ProjectGenerationJobs.class.getDeclaredMethod(
                "initializeTaskGenerationAudit", ObjectNode.class, plan.getClass());
        initialize.setAccessible(true);
        initialize.invoke(jobs, raw, plan);
        ObjectNode failureAudit = mapper.createObjectNode().put("supplementTriggered", true)
                .put("supplementAttemptCount", 2).put("supplementRetryCount", 1)
                .put("attempt1ParsedTasks", 0).put("attempt1ErrorCode", "TASKS_STAGE_EMPTY")
                .put("attempt2ParsedTasks", 0).put("attempt2ErrorCode", "TASKS_STAGE_EMPTY");
        var failure = new ProjectGenerationRunner.DetailedSupplementFailure(
                "TASKS_STAGE_EMPTY", "tasks 数组为空", failureAudit);
        java.lang.reflect.Method preserve = ProjectGenerationJobs.class.getDeclaredMethod(
                "preserveFailedSupplementAudit", ObjectNode.class, ObjectNode.class, plan.getClass(), Exception.class);
        preserve.setAccessible(true);

        preserve.invoke(jobs, raw, draft, plan, failure);

        JsonNode audit = draft.path("qualityAdjustments").path("taskGeneration");
        assertTrue(audit.path("supplementTriggered").asBoolean());
        assertEquals(2, audit.path("supplementAttemptCount").asInt());
        assertEquals(1, audit.path("supplementRetryCount").asInt());
        assertEquals("TASKS_STAGE_EMPTY", audit.path("attempt1ErrorCode").asText());
        assertEquals("TASKS_STAGE_EMPTY", audit.path("attempt2ErrorCode").asText());
        assertEquals(1, audit.path("plannedGapStoryCount").asInt());
        assertEquals(1, audit.path("remainingGapStoryCount").asInt());
        assertEquals(1, audit.path("remainingStageGapCount").asInt());
        assertTrue(v.completenessReport(draft).path("hardBlockingIssues").toString().contains("实现阶段缺口"));
    }

    @Test void detailedSupplementBatchesThreeStoriesAndContinuesAfterOneBatchFails() throws Exception {
        var v = validator();
        var runner = mock(ProjectGenerationRunner.class);
        var jobs = new ProjectGenerationJobs(mock(ProjectGenerationRunMapper.class), mock(ProjectGenerationEventMapper.class),
                runner, v, mock(ModelClient.class), mock(LlmProperties.class), mapper);
        ObjectNode draft = mapper.createObjectNode();
        for (int i = 1; i <= 7; i++) {
            String story = String.format("US%02d", i);
            draft.withArray("stories").add(mapper.createObjectNode().put("id", story).put("title", "活动详情查看" + i));
            draft.withArray("tasks").add(mapper.createObjectNode().put("id", "T" + i)
                    .put("name", "活动详情后端接口" + i).put("story_ref", story));
        }
        ObjectNode raw = draft.deepCopy();
        java.lang.reflect.Method planMethod = ProjectGenerationJobs.class.getDeclaredMethod(
                "detailedSupplementPlan", ObjectNode.class, ObjectNode.class);
        planMethod.setAccessible(true);
        Object plan = planMethod.invoke(jobs, draft, raw);
        java.util.concurrent.atomic.AtomicInteger calls = new java.util.concurrent.atomic.AtomicInteger();
        java.util.List<java.util.Set<String>> requestedGaps = new java.util.ArrayList<>();
        doAnswer(invocation -> {
            int call = calls.incrementAndGet();
            @SuppressWarnings("unchecked") java.util.Map<String, java.util.Set<String>> gaps = invocation.getArgument(2);
            requestedGaps.add(new java.util.LinkedHashSet<>(gaps.keySet()));
            if (call == 1) {
                ObjectNode audit = mapper.createObjectNode().put("supplementAttemptCount", 2)
                        .put("supplementRetryCount", 1).put("attempt1ParsedTasks", 0)
                        .put("attempt1ErrorCode", "TASKS_STAGE_EMPTY").put("attempt2ParsedTasks", 0)
                        .put("attempt2ErrorCode", "TASKS_STAGE_EMPTY");
                throw new ProjectGenerationRunner.DetailedSupplementFailure("TASKS_STAGE_EMPTY", "empty", audit);
            }
            ObjectNode result = mapper.createObjectNode();
            int task = 1;
            for (String story : gaps.keySet()) result.withArray("tasks").add(mapper.createObjectNode()
                    .put("id", "T" + task++).put("name", "活动详情前端页面").put("story_ref", story));
            result.putObject("_task_generation_audit").put("supplementAttemptCount", 1)
                    .put("supplementRetryCount", 0).put("attempt1ParsedTasks", gaps.size())
                    .put("attempt1ErrorCode", "").put("attempt2ParsedTasks", 0)
                    .put("attempt2ErrorCode", "NOT_ATTEMPTED").put("supplementGeneratedTaskCount", gaps.size());
            return result;
        }).when(runner).generateDetailedTaskSupplement(anyString(), any(), anyMap(), anyInt(), any(), any(), any());
        ProjectGenerationRun run = new ProjectGenerationRun();
        run.setProjectId("NEW-batch");
        java.lang.reflect.Method generateBatches = ProjectGenerationJobs.class.getDeclaredMethod(
                "generateDetailedSupplementBatches", ProjectGenerationRun.class, ObjectNode.class, plan.getClass());
        generateBatches.setAccessible(true);

        ObjectNode result = (ObjectNode) generateBatches.invoke(jobs, run, raw, plan);

        JsonNode audit = result.path("_task_generation_audit");
        assertEquals(3, audit.path("supplementBatchCount").asInt());
        assertEquals(2, audit.path("supplementSuccessfulBatchCount").asInt());
        assertEquals(1, audit.path("supplementFailedBatchCount").asInt());
        assertEquals(4, result.path("tasks").size());
        assertEquals(4, audit.path("supplementedStageTaskCount").asInt());
        assertEquals(3, audit.path("supplementBatches").get(0).path("storyCodes").size());
        assertEquals(3, audit.path("supplementBatches").get(1).path("storyCodes").size());
        assertEquals(1, audit.path("supplementBatches").get(2).path("storyCodes").size());
        assertEquals("TASKS_STAGE_EMPTY", audit.path("supplementBatches").get(0).path("errorCode").asText());
        assertTrue(result.path("tasks").size() <= audit.path("remainingBudgetBeforeSupplement").asInt());
        assertEquals(java.util.Set.of("US01", "US02", "US03"), requestedGaps.get(0));
        assertEquals(java.util.Set.of("US04", "US05", "US06"), requestedGaps.get(1));
        assertEquals(java.util.Set.of("US07"), requestedGaps.get(2));
    }
    private ObjectNode raw() throws Exception {
        return (ObjectNode)mapper.readTree("""
          {"project":{"total_sprints":3},"activities":[{"id":1,"name":"登录"},{"id":2,"name":"订单"}],
           "stories":[{"id":"US01","title":"登录","sprint":1,"owner_id":2}],
           "tasks":[{"id":"T01","name":"登录后端接口","story_ref":"US01","hours":8,"week_start":1,"week_end":1}],
           "milestones":[]}
          """);
    }
    @Test void conciseThreeSprintPlanIsMarkedIncompleteWithScaleTargets() throws Exception {
        var v=validator(); var d=v.normalize(raw(),"merge","NEW-test"); var report=v.completenessReport(d);
        assertFalse(report.path("sufficient").asBoolean()); assertEquals(25,report.path("storyTarget").asInt()); assertEquals(22,report.path("storyMinimum").asInt());
        assertTrue(report.path("min_task_count").asInt()>=20); assertTrue(d.path("warnings").toString().contains("规划完整度不足"));
    }

    private ObjectNode storiesForSprintCounts(int... counts) throws Exception {
        ObjectNode d = raw();
        var stories = d.withArray("stories"); stories.removeAll();
        int n = 1;
        for (int sprint = 1; sprint <= counts.length; sprint++) {
            for (int i = 0; i < counts[sprint - 1]; i++) {
                stories.add(mapper.createObjectNode().put("id", String.format("US%02d", n++))
                        .put("title", "功能" + n).put("description", "作为用户，希望完成该功能")
                        .put("acceptance", "验收通过").put("sprint", sprint).put("_user_sprint_explicit", true).put("activity", (i % 2) + 1)
                        .put("owner_id", 1));
            }
        }
        return d;
    }

    @Test void sprintBalanceAcceptsNaturalEightEightNineSplit() throws Exception {
        var report = validator().completenessReport(validator().normalize(storiesForSprintCounts(8, 8, 9), "merge", "NEW-test"));
        assertEquals("[8,8,9]", report.path("storyCountsBySprint").toString());
        assertTrue(report.path("sprintBalanceSufficient").asBoolean());
        assertTrue(report.path("sprintBalanceIssues").isEmpty());
    }

    @Test void sprintBalanceFlagsThinSprintAndExposesIssues() throws Exception {
        var report = validator().completenessReport(validator().normalize(storiesForSprintCounts(7, 7, 4), "merge", "NEW-test"));
        assertEquals("[7,7,4]", report.path("storyCountsBySprint").toString());
        assertFalse(report.path("sprintBalanceSufficient").asBoolean());
        assertFalse(report.path("sprintBalanceIssues").isEmpty());
        assertTrue(report.path("gaps").toString().contains("Sprint 分布失衡"));
    }

    @Test void activityDistributionFlagsSprintWithOnlyOneActivity() throws Exception {
        var d = storiesForSprintCounts(8, 8, 9);
        for (var story : d.path("stories")) if (story.path("sprint").asInt() == 2) ((ObjectNode) story).put("activity", 1);
        var report = validator().completenessReport(validator().normalize(d, "merge", "NEW-test"));
        assertFalse(report.path("activityDistributionIssues").isEmpty());
        assertTrue(report.path("activityDistributionIssues").toString().contains("Sprint 2"));
    }

    private ObjectNode activityDraft(int declaredActivities, int usedActivities, int dominantCount) {
        ObjectNode draft = mapper.createObjectNode();
        draft.putObject("project").put("total_sprints", 3);
        ArrayNode activities = draft.putArray("activities");
        for (int i = 1; i <= declaredActivities; i++)
            activities.add(mapper.createObjectNode().put("id", i).put("name", "域" + i));
        ArrayNode sprints = draft.putArray("sprints");
        sprints.add(mapper.createObjectNode().put("sprint", 1).put("week_start", 1).put("week_end", 2));
        sprints.add(mapper.createObjectNode().put("sprint", 2).put("week_start", 3).put("week_end", 4));
        sprints.add(mapper.createObjectNode().put("sprint", 3).put("week_start", 5).put("week_end", 6));
        ArrayNode stories = draft.putArray("stories");
        for (int i = 0; i < 30; i++) {
            int activity = i < dominantCount ? 1 : ((i - dominantCount) % Math.max(1, usedActivities)) + 1;
            stories.add(mapper.createObjectNode().put("id", String.format("US%02d", i + 1))
                    .put("title", "业务 Story " + (i + 1)).put("description", "业务描述")
                    .put("acceptance", "验收通过").put("sprint", i / 10 + 1)
                    .put("activity", activity).put("owner_id", 1));
        }
        ArrayNode tasks = draft.putArray("tasks");
        for (int i = 0; i < 40; i++) tasks.add(mapper.createObjectNode().put("id", String.format("T%02d", i + 1))
                .put("name", "实现任务").put("story_ref", i < 30 ? String.format("US%02d", i + 1) : "US01")
                .put("owner_id", 1).put("hours", 4).put("week_start", (i % 6) + 1).put("week_end", (i % 6) + 1));
        draft.putArray("milestones").add(mapper.createObjectNode().put("id", "M1").put("related_task_ids", "T01"))
                .add(mapper.createObjectNode().put("id", "M2").put("related_task_ids", "T15"))
                .add(mapper.createObjectNode().put("id", "M3").put("related_task_ids", "T30"));
        return draft;
    }

    @Test
    void activityCompletenessUsesNonEmptyDeclaredDomainsAndCoverage() {
        var fiveDomains = validator().completenessReport(activityDraft(5, 5, 0));
        assertTrue(fiveDomains.path("sufficient").asBoolean(), fiveDomains.toString());
        assertEquals(5, fiveDomains.path("nonEmptyActivityCount").asInt());
        assertEquals(1.0, fiveDomains.path("activityStoryCoverage").asDouble());

        var emptyDomains = validator().completenessReport(activityDraft(6, 4, 0));
        assertTrue(emptyDomains.path("sufficient").asBoolean(), emptyDomains.toString());
        assertEquals(4, emptyDomains.path("nonEmptyActivityCount").asInt());
    }

    @Test
    void dominantOrDanglingActivityIsARealBlockingIssue() {
        var dominant = validator().completenessReport(activityDraft(2, 2, 27));
        assertFalse(dominant.path("sufficient").asBoolean());
        assertTrue(dominant.path("hardBlockingIssues").toString().contains("功能域覆盖不足"));

        var danglingDraft = activityDraft(4, 4, 0);
        ((ObjectNode) danglingDraft.path("stories").get(0)).put("activity", 99);
        var dangling = validator().completenessReport(danglingDraft);
        assertFalse(dangling.path("sufficient").asBoolean());
        assertTrue(dangling.path("hardBlockingIssues").toString().contains("不存在的 Activity"));
    }

    @Test
    void slightlyFewDomainsRemainAWarningWhenCoverageIsHealthy() {
        var report = validator().completenessReport(activityDraft(3, 2, 0));
        assertTrue(report.path("sufficient").asBoolean(), report.toString());
        assertTrue(report.path("warnings").toString().contains("功能域数量略少"));
    }

    @Test void twentyTwoStoriesNeedBalanceCheckButBalancedBoundaryIsReported() throws Exception {
        var report = validator().completenessReport(validator().normalize(storiesForSprintCounts(7, 7, 8), "merge", "NEW-test"));
        assertEquals(22, report.path("story_count").asInt());
        assertEquals(25, report.path("storyTarget").asInt());
        assertEquals(22, report.path("storyMinimum").asInt());
        assertTrue(report.path("sprintBalanceSufficient").asBoolean());
    }
    @Test void explicitOwnerAndSprintArePreserved() throws Exception {
        var v=validator(); var r=raw(); ((ObjectNode)r.path("stories").get(0)).put("sprint",2).put("_user_sprint_explicit",true);
        var d=v.normalize(r,"merge","NEW-test");
        assertEquals(2,d.path("stories").get(0).path("owner_id").asInt());
        assertEquals(2,d.path("stories").get(0).path("sprint").asInt());
    }
    @Test void missingOwnersUseCapacityBalanceInsteadOfFirstMemberForEveryTask() throws Exception {
        var v=validator(); var r=raw();
        r.withArray("tasks").add(mapper.createObjectNode().put("id","T02").put("name","登录页面").put("story_ref","US01").put("hours",8));
        var d=v.normalize(r,"merge","NEW-test");
        assertNotEquals(d.path("tasks").get(0).path("owner_id"),d.path("tasks").get(1).path("owner_id"));
    }
    @Test void multiStoryRefsAndMilestoneTaskRefsSurviveNormalization() throws Exception {
        var v=validator(); var r=raw(); r.withArray("stories").add(mapper.createObjectNode().put("id","US02").put("title","订单"));
        var task=(ObjectNode)r.path("tasks").get(0);task.remove("story_ref");task.withArray("story_refs").add("US01").add("US02");task.put("priority","Must");
        r.withArray("milestones").add(mapper.createObjectNode().put("id","M1").put("name","交付").put("related_task_ids","T01"));
        var d=v.normalize(r,"merge","NEW-test");
        assertEquals("US01,US02",d.path("tasks").get(0).path("story_ref").asText());
        assertEquals("Must",d.path("tasks").get(0).path("priority").asText());
        assertEquals("T01",d.path("milestones").get(0).path("related_task_ids").asText());
    }
    @Test void supplementFillsOnlyMissingFieldsAndDoesNotDuplicateRows() throws Exception {
        var first=raw();var second=raw();((ObjectNode)second.path("stories").get(0)).put("owner_id",1).put("description","作为用户，我希望登录，以便使用服务");
        second.withArray("stories").add(mapper.createObjectNode().put("id","US02").put("title","订单"));
        var merged=ProjectGenerationJobs.mergeDrafts(first,second);
        assertEquals(2,merged.path("stories").size());assertEquals(1,merged.path("tasks").size());
        assertEquals(2,merged.path("stories").get(0).path("owner_id").asInt());assertFalse(merged.path("stories").get(0).path("description").asText().isBlank());
    }
    @Test void supplementMergesRepeatedTaskDependenciesAsNormalizedSet() {
        var first=mapper.createObjectNode();
        first.withArray("tasks").add(mapper.createObjectNode().put("id","T02").put("name","页面")
                .put("depends_on","t01,T01"));
        first.withArray("dependencies").add(mapper.createObjectNode().put("from","T01").put("to","T02"));
        var second=mapper.createObjectNode();
        second.withArray("tasks").add(mapper.createObjectNode().put("id","t02").put("name","页面")
                .put("depends_on","T01,T03"));
        second.withArray("dependencies")
                .add(mapper.createObjectNode().put("from","t01").put("to","t02"))
                .add(mapper.createObjectNode().put("from","T03").put("to","T02"));

        var merged=ProjectGenerationJobs.mergeDrafts(first,second);

        assertEquals("T01,T03",merged.path("tasks").get(0).path("depends_on").asText());
        assertEquals(2,merged.path("dependencies").size());
    }
    @Test void supplementMergesOnlySameActivityNearDuplicateAndRemapsTaskReference() {
        var first=mapper.createObjectNode();
        first.withArray("stories").add(mapper.createObjectNode().put("id","US01").put("title","预约审核").put("activity",2));
        var addition=mapper.createObjectNode();
        addition.withArray("stories").add(mapper.createObjectNode().put("id","US36").put("title","预约审核功能").put("activity",2));
        addition.withArray("stories").add(mapper.createObjectNode().put("id","US37").put("title","预约审核功能").put("activity",3));
        addition.withArray("tasks").add(mapper.createObjectNode().put("id","T36").put("name","审核接口").put("story_ref","US36"));

        var merged=ProjectGenerationJobs.mergeDrafts(first,addition);

        assertEquals(2,merged.path("stories").size());
        assertEquals("US01",merged.path("tasks").get(0).path("story_ref").asText());
    }
    @Test void taskAliasRemapDoesNotDuplicateDependencies() {
        var first=mapper.createObjectNode();
        first.withArray("tasks").add(mapper.createObjectNode().put("id","T01").put("name","接口"));
        first.withArray("tasks").add(mapper.createObjectNode().put("id","T02").put("name","页面").put("depends_on","T01"));
        var addition=mapper.createObjectNode();
        addition.withArray("tasks").add(mapper.createObjectNode().put("id","T90").put("name","接口"));
        addition.withArray("tasks").add(mapper.createObjectNode().put("id","T02").put("name","页面").put("depends_on","T90,T01"));

        var merged=ProjectGenerationJobs.mergeDrafts(first,addition);

        assertEquals("T01",merged.path("tasks").get(1).path("depends_on").asText());
    }

    @Test void detailedSupplementUsesIndependentTaskNamespaceAndRewritesInternalDependencies() {
        var first=mapper.createObjectNode();
        first.withArray("tasks").add(mapper.createObjectNode().put("id","T01").put("name","登录接口"));
        first.withArray("tasks").add(mapper.createObjectNode().put("id","T02").put("name","登录页面").put("depends_on","T01"));
        var supplement=mapper.createObjectNode();
        supplement.withArray("tasks").add(mapper.createObjectNode().put("id","T01").put("name","报名接口"));
        supplement.withArray("tasks").add(mapper.createObjectNode().put("id","T02").put("name","报名页面").put("depends_on","T01"));
        supplement.withArray("milestones").add(mapper.createObjectNode().put("id", "M2")
                .put("related_task_ids", "T01,T02"));

        var merged=ProjectGenerationJobs.mergeDetailedStages(first,supplement,"supplement");

        assertEquals(4,merged.path("tasks").size());
        assertEquals(java.util.Set.of("T01","T02","T03","T04"),
                java.util.stream.StreamSupport.stream(merged.path("tasks").spliterator(),false)
                        .map(row->row.path("id").asText()).collect(java.util.stream.Collectors.toSet()));
        assertEquals("T03",merged.path("tasks").get(3).path("depends_on").asText());
        assertEquals("supplement",merged.path("tasks").get(2).path("generation_stage").asText());
        assertEquals("T01",merged.path("tasks").get(2).path("model_task_code").asText());
        assertEquals("supplement#0#T01",merged.path("tasks").get(2).path("generation_identity").asText());
        assertEquals("T03,T04", merged.path("milestones").get(0).path("related_task_ids").asText());
    }

    @Test void detailedSupplementDoesNotGuessUnknownCrossStageReference() {
        var first=mapper.createObjectNode();
        first.withArray("tasks").add(mapper.createObjectNode().put("id","T01").put("name","登录接口"));
        var supplement=mapper.createObjectNode();
        supplement.withArray("tasks").add(mapper.createObjectNode().put("id","T02").put("name","报名页面")
                .put("depends_on","T99"));

        var merged=ProjectGenerationJobs.mergeDetailedStages(first,supplement,"supplement");
        var added=merged.path("tasks").get(1);

        assertEquals("",added.path("depends_on").asText());
        assertEquals("T99",added.path("unresolved_cross_stage_references").get(0).asText());
    }
    @Test void generatedDraftAutoCompletesDescriptionsStoryLinksAndMilestones() throws Exception {
        var r=raw();
        ((ObjectNode)r.path("stories").get(0)).put("description","").put("acceptance","");
        r.withArray("stories").add(mapper.createObjectNode().put("id","US02").put("title","订单查询")
                .put("description","").put("acceptance","").put("sprint",1).put("activity",2));
        r.withArray("milestones").add(mapper.createObjectNode().put("id","M1").put("name","首轮交付").put("week",1));

        var draft=validator().normalize(r,"create","NEW-auto-complete");

        assertFalse(draft.path("stories").get(0).path("description").asText().isBlank());
        assertFalse(draft.path("stories").get(0).path("acceptance").asText().isBlank());
        assertTrue(java.util.stream.StreamSupport.stream(draft.path("tasks").spliterator(),false)
                .anyMatch(task->java.util.Arrays.asList(task.path("story_ref").asText().split(",")).contains("US02")));
        assertFalse(draft.path("milestones").get(0).path("related_task_ids").asText().isBlank());
        assertTrue(draft.path("completeness").path("autoCompletedItems").size() >= 2);
    }
    @Test void completenessNamesConcreteHardIssuesAndSeparatesWarnings() throws Exception {
        var v=validator();var draft=v.normalize(raw(),"merge","NEW-hard-issues");
        ((ObjectNode)draft.path("stories").get(0)).put("description","").put("acceptance","");
        ((ObjectNode)draft.path("tasks").get(0)).put("story_ref","");
        draft.withArray("milestones").add(mapper.createObjectNode().put("id","M1").put("related_task_ids",""));

        var report=v.completenessReport(draft);

        assertFalse(report.path("sufficient").asBoolean());
        assertTrue(report.path("hardBlockingIssues").toString().contains("US01") || report.path("hardBlockingIssues").toString().contains("Story"));
        assertTrue(report.path("hardBlockingIssues").toString().contains("Task 未关联 Story"));
        assertTrue(report.path("hardBlockingIssues").toString().contains("Milestone"));
        assertTrue(report.path("warnings").isArray());
    }
    @Test void warningsOnlyPlanIsSufficientForWaitingConfirmation() throws Exception {
        var draft=validator().normalize(storiesForSprintCounts(8,8,9),"create","NEW-warnings-only");
        draft.withArray("tasks").removeAll();
        int taskNumber=1;
        for(var story:draft.path("stories")) {
            String id=String.format("T%02d",taskNumber++);
            draft.withArray("tasks").add(mapper.createObjectNode().put("id",id).put("name","实现"+story.path("id").asText())
                    .put("owner_id",1).put("hours",8).put("week_start",story.path("sprint").asInt()*2-1)
                    .put("week_end",story.path("sprint").asInt()*2-1).put("story_ref",story.path("id").asText()).put("depends_on",""));
        }
        while(draft.path("tasks").size()<32) draft.withArray("tasks").add(mapper.createObjectNode()
                .put("id",String.format("T%02d",taskNumber++)).put("name","并行补充任务").put("owner_id",2)
                .put("hours",4).put("week_start",1).put("week_end",1).put("story_ref","US01").put("depends_on",""));
        draft.withArray("milestones").removeAll();
        draft.withArray("milestones").add(mapper.createObjectNode().put("id","M1").put("related_task_ids","T01"));
        draft.withArray("milestones").add(mapper.createObjectNode().put("id","M2").put("related_task_ids","T16"));

        var report=validator().completenessReport(draft);

        assertTrue(report.path("sufficient").asBoolean());
        assertTrue(report.path("hardBlockingIssues").isEmpty());
        assertTrue(report.path("warnings").toString().contains("任务依赖关系异常缺失"));
    }
    @Test void excelUserFieldsWinAndCodeBasedRowsAreNotDuplicated() {
        var imported=mapper.createObjectNode();
        var story=mapper.createObjectNode().put("code","US01").put("title","用户登录")
                .put("sprint",2).put("priority","Must").put("description","用户填写的描述")
                .put("acceptance","用户填写的验收").put("activity",3).put("owner_id",2);
        var task=mapper.createObjectNode().put("code","T01").put("name","登录接口")
                .put("owner_id",2).put("hours",12).put("week_start",3).put("week_end",4)
                .put("story_ref","US01").put("depends_on","T00");
        imported.withArray("stories").add(story);imported.withArray("tasks").add(task);
        var ai=mapper.createObjectNode();
        ai.withArray("stories").add(mapper.createObjectNode().put("code","US01").put("title","AI标题")
                .put("sprint",1).put("priority","Could").put("description","AI描述")
                .put("acceptance","AI验收").put("activity",1).put("owner_id",1));
        ai.withArray("tasks").add(mapper.createObjectNode().put("code","T01").put("name","AI任务")
                .put("owner_id",1).put("hours",2).put("week_start",1).put("week_end",1)
                .put("story_ref","US99").put("depends_on",""));

        var merged=ProjectGenerationJobs.mergeDrafts(imported,ai);

        assertEquals(1,merged.path("stories").size());assertEquals(1,merged.path("tasks").size());
        var keptStory=merged.path("stories").get(0);var keptTask=merged.path("tasks").get(0);
        assertEquals("用户登录",keptStory.path("title").asText());assertEquals(2,keptStory.path("sprint").asInt());
        assertEquals("Must",keptStory.path("priority").asText());assertEquals("用户填写的描述",keptStory.path("description").asText());
        assertEquals("用户填写的验收",keptStory.path("acceptance").asText());assertEquals(3,keptStory.path("activity").asInt());
        assertEquals(2,keptStory.path("owner_id").asInt());assertEquals("登录接口",keptTask.path("name").asText());
        assertEquals(12,keptTask.path("hours").asInt());assertEquals(3,keptTask.path("week_start").asInt());
        assertEquals(4,keptTask.path("week_end").asInt());assertEquals("US01",keptTask.path("story_ref").asText());
        assertEquals("T00",keptTask.path("depends_on").asText());assertEquals(2,keptTask.path("owner_id").asInt());
    }
    @Test void insufficientFirstResultTriggersExactlyOneSupplementBeforeConfirmation() throws Exception {
        var v=validator();var runs=mock(ProjectGenerationRunMapper.class);var events=mock(ProjectGenerationEventMapper.class);
        var runner=mock(ProjectGenerationRunner.class);var client=mock(ModelClient.class);var props=mock(LlmProperties.class);
        var run=new ProjectGenerationRun();run.setId("r1");run.setProjectId("NEW-test");run.setMode("overview");run.setStrategy("create");run.setRequestText("三Sprint交易平台");run.setAttempt(1);
        when(runs.selectOne(any())).thenReturn(run);when(runs.update(any(),any())).thenReturn(1);
        when(runner.generate(anyString(),anyString(),anyString(),eq(client),anyString(),any())).thenReturn(raw());
        var jobs=new ProjectGenerationJobs(runs,events,runner,v,client,props,mapper);
        assertTrue(jobs.runNext());verify(runner,times(2)).generate(anyString(),anyString(),anyString(),eq(client),anyString(),any());
        assertEquals("waiting_confirmation",run.getStatus()); assertTrue(run.getDraftJson().contains("规划完整度不足"));
    }
    @Test void fortyTwoIndependentStoriesWithSoftTargetReachWaitingConfirmationWithWarning() throws Exception {
        var v=mock(ProjectGenerationValidator.class);var runs=mock(ProjectGenerationRunMapper.class);var events=mock(ProjectGenerationEventMapper.class);
        var runner=mock(ProjectGenerationRunner.class);var client=mock(ModelClient.class);var props=mock(LlmProperties.class);
        var run=new ProjectGenerationRun();run.setId("soft-42");run.setProjectId("NEW-soft-42");run.setMode("overview");
        run.setStrategy("create");run.setRequestText("用户故事数量希望在30条左右");run.setAttempt(1);run.setStatus("queued");
        ObjectNode generated=mapper.createObjectNode();
        for(int i=1;i<=42;i++)generated.withArray("stories").add(mapper.createObjectNode()
                .put("id",String.format("US%02d",i)).put("title","独立业务功能"+i)
                .put("description","功能描述"+i).put("acceptance","验收条件"+i));
        generated.withArray("tasks");
        ObjectNode report=mapper.createObjectNode().put("sufficient",true);
        report.set("hardBlockingIssues",mapper.createArrayNode());report.set("warnings",mapper.createArrayNode());
        when(runs.selectOne(any())).thenReturn(run);when(runs.update(any(),any())).thenReturn(1);
        when(runner.generate(anyString(),anyString(),anyString(),eq(client),anyString(),any())).thenReturn(generated);
        when(v.normalize(any(),anyString(),anyString())).thenReturn(generated);
        when(v.completenessReport(generated)).thenReturn(report);
        var jobs=new ProjectGenerationJobs(runs,events,runner,v,client,props,mapper);

        assertTrue(jobs.runNext());

        assertEquals("waiting_confirmation",run.getStatus());
        JsonNode completeness=mapper.readTree(run.getDraftJson()).path("completeness");
        assertTrue(completeness.path("hardBlockingIssues").isEmpty());
        assertEquals("SOFT_TARGET",completeness.path("constraintType").asText());
        assertTrue(completeness.path("storyCountWarning").asText().contains("Story 数量 42"));
    }
    @Test void documentedSimpleScopeCanHaveSmallerTarget() throws Exception {
        var v=validator();var r=raw();r.withObject("project").put("complexity","simple").put("complexity_note","单一离线计算功能，无账号和协作流程");r.withArray("activities").remove(1);
        assertTrue(v.completenessReport(v.normalize(r,"merge","NEW-test")).path("min_story_count").asInt()<16);
    }
    @Test void importWithoutModelTreatsAiPlanningGapsAsWarnings() throws Exception {
        var v=validator();var props=mock(LlmProperties.class);var client=mock(ModelClient.class);
        var jobs=new ProjectGenerationJobs(mock(ProjectGenerationRunMapper.class),mock(ProjectGenerationEventMapper.class),mock(ProjectGenerationRunner.class),v,client,props,mapper);
        var run=new ProjectGenerationRun();run.setProjectId("NEW-test");run.setStrategy("create");
        var d=jobs.prepareImport(run,raw());
        assertTrue(d.path("completeness").path("sufficient").asBoolean());
        assertTrue(d.path("completeness").path("warnings").toString().contains("Task 数量"));
        verifyNoInteractions(client);
    }

    @Test void completeExcelSizedDraftDoesNotTriggerAiExpansion() throws Exception {
        var v=validator();var props=mock(LlmProperties.class);var client=mock(ModelClient.class);
        var jobs=new ProjectGenerationJobs(mock(ProjectGenerationRunMapper.class),mock(ProjectGenerationEventMapper.class),mock(ProjectGenerationRunner.class),v,client,props,mapper);
        var run=new ProjectGenerationRun();run.setProjectId("NEW-excel-sized");run.setStrategy("create");run.setMode("excel");

        ObjectNode source=excelSizedRaw();
        var draft=jobs.prepareImport(run,source);

        assertEquals(30,draft.path("stories").size());
        assertEquals(35,draft.path("tasks").size());
        assertTrue(draft.path("completeness").path("sufficient").asBoolean());
        assertTrue(draft.path("completeness").path("hardBlockingIssues").isEmpty());
        assertTrue(draft.path("completeness").path("warnings").toString().contains("Milestone"));
        verifyNoInteractions(client);
    }

    @Test void importDanglingTaskStoryReferenceIsHardBlocking() throws Exception {
        var v=validator();
        ObjectNode source=raw();
        ((ObjectNode) source.path("tasks").get(0)).put("story_ref","US99");

        ObjectNode report=v.normalizeImport(source,"create","NEW-import-dangling").path("completeness").deepCopy();

        assertFalse(report.path("sufficient").asBoolean());
        assertTrue(report.path("hardBlockingIssues").toString().contains("US99"));
    }

    @Test void importMilestoneDanglingTaskReferenceIsHardBlocking() throws Exception {
        var v=validator();
        ObjectNode source=raw();
        source.withArray("milestones").add(mapper.createObjectNode().put("id","M1").put("name","交付").put("related_task_ids","T99"));

        ObjectNode report=v.normalizeImport(source,"create","NEW-import-milestone").path("completeness").deepCopy();

        assertFalse(report.path("sufficient").asBoolean());
        assertTrue(report.path("hardBlockingIssues").toString().contains("T99"));
    }

    private ObjectNode excelSizedRaw() {
        ObjectNode source=mapper.createObjectNode();
        source.set("project",mapper.createObjectNode().put("total_sprints",3));
        source.set("activities",mapper.createArrayNode().add(mapper.createObjectNode().put("id",1).put("name","基础")));
        var stories=source.withArray("stories");
        for(int i=1;i<=30;i++) stories.add(mapper.createObjectNode()
                .put("id",String.format("US%02d",i)).put("title","导入功能"+i)
                .put("description","用户提供的功能描述").put("acceptance","用户提供的验收条件")
                .put("priority","Should").put("sprint",(i-1)%3+1).put("activity",1).put("owner_id",1)
                .put("_import_sprint_explicit",true).put("_import_owner_explicit",true));
        var tasks=source.withArray("tasks");
        for(int i=1;i<=35;i++) tasks.add(mapper.createObjectNode()
                .put("id",String.format("T%02d",i)).put("name","导入任务"+i).put("owner_id",1)
                .put("hours",4).put("week_start",(i-1)%6+1).put("week_end",(i-1)%6+1)
                .put("story_ref",String.format("US%02d",(i-1)%30+1)).put("depends_on","")
                .put("_import_owner_explicit",true).put("_import_schedule_explicit",true)
                .put("_import_hours_explicit",true));
        source.set("milestones",mapper.createArrayNode());
        return source;
    }
    @Test void failedExcelAiSupplementPreservesImportedDraftForManualReview() throws Exception {
        var v=validator();var props=mock(LlmProperties.class);var client=mock(ModelClient.class);
        var runner=mock(ProjectGenerationRunner.class);var events=mock(ProjectGenerationEventMapper.class);
        when(props.settingsReady()).thenReturn(true);
        when(runner.generate(anyString(),anyString(),eq("excel"),eq(client),anyString(),any()))
                .thenThrow(new AgentError("incomplete_response","模型补全输出再次被截断"));
        var jobs=new ProjectGenerationJobs(mock(ProjectGenerationRunMapper.class),events,runner,v,client,props,mapper);
        var run=new ProjectGenerationRun();run.setId("excel-run");run.setProjectId("NEW-test");run.setMode("excel");
        run.setModel("deepseek-v4-flash");run.setStrategy("create");run.setAttempt(1);
        var source=raw();String title=source.path("stories").get(0).path("title").asText();
        int owner=source.path("stories").get(0).path("owner_id").asInt();

        var draft=jobs.prepareImport(run,source);

        assertEquals(title,draft.path("stories").get(0).path("title").asText());
        assertEquals(owner,draft.path("stories").get(0).path("owner_id").asInt());
        assertEquals(1,draft.path("tasks").size());
        assertFalse(draft.path("completeness").path("aiSupplementCompleted").asBoolean(true));
        assertEquals("ai_supplement_incomplete",run.getErrorCode());
        assertTrue(run.getErrorMessage().contains("原始导入草案"));
        verify(runner,times(1)).generate(anyString(),anyString(),eq("excel"),eq(client),anyString(),any());
    }
}
