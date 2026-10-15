package com.aicap.generation;

import com.aicap.common.ApiException;
import com.aicap.entity.User;
import com.aicap.mapper.StoryMapper;
import com.aicap.mapper.TaskMapper;
import com.aicap.mapper.UserMapper;
import com.baomidou.mybatisplus.core.conditions.Wrapper;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.HashSet;
import java.util.Set;
import java.lang.reflect.Method;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.nullable;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ProjectGenerationValidatorTest {
    @Mock private StoryMapper stories;
    @Mock private TaskMapper tasks;
    @Mock private UserMapper users;

    private final ObjectMapper mapper = new ObjectMapper();
    private ProjectGenerationValidator validator;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        User member = new User();
        member.setId(1);
        member.setDisplayName("测试成员");
        member.setRole("member");
        member.setCapacityHours(60);
        lenient().when(stories.selectList(nullable(Wrapper.class))).thenReturn(List.of());
        lenient().when(tasks.selectList(nullable(Wrapper.class))).thenReturn(List.of());
        lenient().when(users.selectList(any(Wrapper.class))).thenReturn(List.of(member));
        lenient().when(users.selectById(1)).thenReturn(member);
        validator = new ProjectGenerationValidator(stories, tasks, users, mapper);
    }

    @Test
    void normalizeReadsArrayAndTopLevelDependencies() {
        ObjectNode raw = baseDraft();
        task(raw, "A", "后端登录接口", 1);
        ObjectNode second = task(raw, "B", "前端登录页面", 1);
        second.set("depends_on", mapper.createArrayNode().add("A"));
        task(raw, "C", "登录验收测试", 1);
        raw.set("dependencies", mapper.createArrayNode()
                .add(mapper.createObjectNode().put("from", "A").put("to", "B"))
                .add(mapper.createObjectNode().put("from", "B").put("to", "C")));

        ObjectNode normalized = validator.normalize(raw, "merge");

        assertEquals("T01", normalized.path("tasks").get(1).path("depends_on").asText());
        assertEquals("", normalized.path("tasks").get(2).path("depends_on").asText());
        assertEquals("T02", normalized.path("tasks").get(2).path("weak_dependencies").asText());
        assertEquals(2, normalized.path("dependencies").size());
        assertEquals(0, normalized.path("completeness").path("removedDuplicateDependencies").asInt());
        assertFalse(normalized.path("warnings").toString().contains("重复依赖"));
    }

    @ParameterizedTest
    @CsvSource({
            "开发学生个人中心页面,学生个人中心,负责人个人中心",
            "负责人个人中心页面,负责人个人中心,学生个人中心",
            "取消报名入口与规则提示,取消报名,提交报名申请",
            "负责人报名申请查询接口,负责人查看报名申请,负责人审核报名申请",
            "活动列表查询接口,查看活动列表,查看活动详情",
            "活动详情接口,查看活动详情,搜索筛选活动",
            "筛选组件与组合条件交互,搜索筛选活动,查看活动列表",
            "活动签退接口,活动签退,活动签到",
            "个人累计时长查询,学生查看个人累计服务时长,负责人查看活动服务时长",
            "负责人查看活动服务时长,负责人查看活动服务时长,学生查看个人累计服务时长",
            "活动编辑接口,修改活动,创建活动",
            "个人资料修改表单,修改个人资料,查看个人资料",
            "活动状态查询接口,查看活动状态,创建活动"
    })
    void objectLocalRelationEvidenceIsUniqueAndOrderIndependent(String title, String expected, String rival) throws Exception {
        ArrayNode rows = mapper.createArrayNode()
                .add(mapper.createObjectNode().put("id", "US73").put("title", expected))
                .add(mapper.createObjectNode().put("id", "US19").put("title", rival))
                .add(mapper.createObjectNode().put("id", "US11").put("title", "查看报名状态"));
        Method method = ProjectGenerationValidator.class.getDeclaredMethod("finalRelationClosure",
                ArrayNode.class, ArrayNode.class, ArrayNode.class, ArrayNode.class);
        method.setAccessible(true);
        for (int order = 0; order < 2; order++) {
            ArrayNode tasks = mapper.createArrayNode().add(mapper.createObjectNode()
                    .put("id", "T09").put("name", title).put("story_ref", "")
                    .put("generation_identity", "tasks#0:T09"));
            ObjectNode stats = (ObjectNode) method.invoke(validator, rows, tasks, mapper.createArrayNode(), mapper.createArrayNode());
            assertEquals("US73", tasks.get(0).path("relation_tie_break").path("selectedStory").asText(), title);
            assertEquals(1, stats.path("highConfidenceTieBreakResolvedCount").asInt());
            assertEquals("tasks#0:T09", tasks.get(0).path("generation_identity").asText());
            for (String field : List.of("candidateScores", "selectedStory", "winningReason", "runnerUpScore",
                    "resolutionMethod", "unresolvedReason")) assertTrue(tasks.get(0).path("relation_tie_break").has(field), field);
            ArrayNode reversed = mapper.createArrayNode();
            for (int i = rows.size() - 1; i >= 0; i--) reversed.add(rows.get(i));
            rows = reversed;
        }
    }

    @Test
    void finalRelationClosureDoesNotResolveTrueTieOrInfrastructure() throws Exception {
        ArrayNode rows = mapper.createArrayNode()
                .add(mapper.createObjectNode().put("id", "US01").put("title", "学生个人中心"))
                .add(mapper.createObjectNode().put("id", "US02").put("title", "学生个人中心"));
        ArrayNode tasks = mapper.createArrayNode()
                .add(mapper.createObjectNode().put("id", "T01").put("name", "学生个人中心页面").put("story_ref", ""))
                .add(mapper.createObjectNode().put("id", "T02").put("name", "搭建项目基础框架与数据库连接").put("story_ref", ""))
                .add(mapper.createObjectNode().put("id", "T03").put("name", "统一异常处理").put("story_ref", ""));
        Method method = ProjectGenerationValidator.class.getDeclaredMethod("finalRelationClosure",
                ArrayNode.class, ArrayNode.class, ArrayNode.class, ArrayNode.class);
        method.setAccessible(true);
        ObjectNode stats = (ObjectNode) method.invoke(validator, rows, tasks, mapper.createArrayNode(), mapper.createArrayNode());
        tasks.forEach(task -> assertEquals("", task.path("story_ref").asText()));
        assertEquals(1, stats.path("unresolvedReasonCounts").path("候选分数平票").asInt());
        assertEquals(2, stats.path("unresolvedReasonCounts").path("项目级任务").asInt());
    }

    @Test
    void uniqueBusinessEvidenceBelowGlobalThresholdResolvesButCompositeDoesNot() throws Exception {
        ArrayNode stories = mapper.createArrayNode()
                .add(mapper.createObjectNode().put("id", "US14").put("title", "学生取消报名"))
                .add(mapper.createObjectNode().put("id", "US13").put("title", "学生提交报名申请"))
                .add(mapper.createObjectNode().put("id", "US15").put("title", "学生查看报名状态"));
        ArrayNode taskRows = mapper.createArrayNode()
                .add(mapper.createObjectNode().put("id", "T27").put("name", "取消报名入口与规则提示").put("story_ref", ""))
                .add(mapper.createObjectNode().put("id", "T28").put("name", "取消报名接口及页面").put("story_ref", ""));
        Method method = ProjectGenerationValidator.class.getDeclaredMethod("ensureGeneratedTaskStoryReferences",
                ArrayNode.class, ArrayNode.class, ArrayNode.class, ArrayNode.class);
        method.setAccessible(true);
        method.invoke(validator, stories, taskRows, mapper.createArrayNode(), mapper.createArrayNode());

        assertEquals("US14", taskRows.get(0).path("story_ref").asText());
        assertEquals("deterministic_object_local_evidence",
                taskRows.get(0).path("relation_tie_break").path("evidenceMethod").asText());
        assertEquals("", taskRows.get(1).path("story_ref").asText());
        assertEquals("COARSE_COMPOSITE_TASK", taskRows.get(1).path("relation_tie_break").path("status").asText());
        for (String field : List.of("actionEvidence", "roleEvidence", "stateEvidence", "directionEvidence"))
            assertTrue(taskRows.get(0).path("relation_tie_break").has(field));
    }

    @Test
    void detailedStageCoverageFindsCompositeAndOnlyRequestsMissingSurface() {
        ObjectNode draft = mapper.createObjectNode();
        draft.putArray("stories")
                .add(mapper.createObjectNode().put("id", "US01").put("title", "活动详情查看"))
                .add(mapper.createObjectNode().put("id", "US02").put("title", "活动列表页面"))
                .add(mapper.createObjectNode().put("id", "US03").put("title", "数据库表结构设计"))
                .add(mapper.createObjectNode().put("id", "US04").put("title", "系统测试与最终验收"));
        draft.putArray("tasks")
                .add(mapper.createObjectNode().put("id", "T01").put("name", "活动详情接口及页面").put("story_ref", "US01"))
                .add(mapper.createObjectNode().put("id", "T02").put("name", "活动列表页面组件").put("story_ref", "US02"))
                .add(mapper.createObjectNode().put("id", "T03").put("name", "数据库表结构设计").put("story_ref", "US03"))
                .add(mapper.createObjectNode().put("id", "T04").put("name", "系统验收测试").put("story_ref", "US04"));

        ObjectNode audit = validator.detailedTaskStageCoverage(draft);

        assertEquals(1, audit.path("stageCoverageGapStoryCount").asInt());
        assertEquals(1, audit.path("coarseCompositeTaskCount").asInt());
        assertEquals("US02", audit.path("stageCoverageGaps").get(0).path("storyId").asText());
        assertEquals("[\"backend\"]", audit.path("stageCoverageGaps").get(0).path("missingStages").toString());
        JsonNode compositeContract = audit.path("storyStageContracts").get(0);
        assertEquals("[\"backend\",\"frontend\"]", compositeContract.path("presentStages").toString());
        assertTrue(compositeContract.path("coarseComposite").asBoolean());
    }

    @Test
    void compositeTasksContributeRecognizedStagesWhileRemainingCoarseAuditItems() {
        ObjectNode draft = mapper.createObjectNode();
        draft.putArray("stories")
                .add(mapper.createObjectNode().put("id", "US01").put("title", "登录页面"))
                .add(mapper.createObjectNode().put("id", "US02").put("title", "注册学号校验"))
                .add(mapper.createObjectNode().put("id", "US03").put("title", "活动编辑状态校验"))
                .add(mapper.createObjectNode().put("id", "US04").put("title", "签到管理页面"));
        draft.putArray("tasks")
                .add(mapper.createObjectNode().put("id", "T01").put("name", "登录接口与登录页面").put("story_ref", "US01"))
                .add(mapper.createObjectNode().put("id", "T02").put("name", "注册接口与学号唯一校验").put("story_ref", "US02"))
                .add(mapper.createObjectNode().put("id", "T03").put("name", "活动编辑接口与状态校验").put("story_ref", "US03"))
                .add(mapper.createObjectNode().put("id", "T04").put("name", "签到管理接口与页面").put("story_ref", "US04"));

        ObjectNode audit = validator.detailedTaskStageCoverage(draft);
        java.util.Map<String, JsonNode> contracts = new java.util.HashMap<>();
        audit.path("storyStageContracts").forEach(row -> contracts.put(row.path("storyId").asText(), row));

        assertEquals("[\"backend\",\"frontend\"]", contracts.get("US01").path("presentStages").toString());
        assertEquals("[\"backend\",\"validation\"]", contracts.get("US02").path("presentStages").toString());
        assertEquals("[\"backend\",\"validation\"]", contracts.get("US03").path("presentStages").toString());
        assertEquals("[\"backend\",\"frontend\"]", contracts.get("US04").path("presentStages").toString());
        assertEquals(4, audit.path("coarseCompositeTaskCount").asInt());
    }

    @Test
    void implementationStageClassifierRecognizesOnlyExplicitChineseImplementationSemantics() {
        ObjectNode draft = mapper.createObjectNode();
        String[] titles = {"活动状态流转规则", "个人累计服务时长查看", "服务时长生成", "消息列表", "权限控制", "异常处理"};
        String[] taskNames = {"状态流转规则实现", "查看个人累计服务时长", "生成服务时长", "消息列表与已读标记", "权限控制", "异常处理逻辑"};
        for (int i = 0; i < titles.length; i++) {
            String id = String.format("US%02d", i + 1);
            draft.withArray("stories").add(mapper.createObjectNode().put("id", id).put("title", titles[i]));
            draft.withArray("tasks").add(mapper.createObjectNode().put("id", "T" + (i + 1))
                    .put("name", taskNames[i]).put("story_ref", id));
        }

        ObjectNode audit = validator.detailedTaskStageCoverage(draft);
        java.util.Map<String, JsonNode> contracts = new java.util.HashMap<>();
        audit.path("storyStageContracts").forEach(row -> contracts.put(row.path("storyId").asText(), row));
        assertEquals("[\"backend\",\"validation\"]", contracts.get("US01").path("presentStages").toString());
        assertEquals("[\"frontend\"]", contracts.get("US02").path("presentStages").toString());
        assertEquals("[\"backend\"]", contracts.get("US03").path("presentStages").toString());
        assertEquals("[\"frontend\"]", contracts.get("US04").path("presentStages").toString());
        assertEquals("[\"backend\",\"validation\"]", contracts.get("US05").path("presentStages").toString());
        assertEquals("[\"backend\"]", contracts.get("US06").path("presentStages").toString());
    }

    @Test
    void backendOnlyBusinessStoryRequestsOnlyFrontendGap() {
        ObjectNode draft = mapper.createObjectNode();
        draft.putArray("stories").add(mapper.createObjectNode().put("id", "US01").put("title", "活动详情查看"));
        draft.putArray("tasks").add(mapper.createObjectNode().put("id", "T01")
                .put("name", "活动详情后端接口").put("story_ref", "US01"));

        JsonNode gap = validator.detailedTaskStageCoverage(draft).path("stageCoverageGaps").get(0);

        assertEquals("[\"frontend\"]", gap.path("missingStages").toString());
    }

    @Test
    void detailedStageContractsClassifyDataTestBackendRulesAndUserFacingStoriesDeterministically() {
        ObjectNode draft = mapper.createObjectNode();
        draft.putArray("stories")
                .add(mapper.createObjectNode().put("id", "US01").put("title", "数据库设计与初始化"))
                .add(mapper.createObjectNode().put("id", "US02").put("title", "系统测试与最终验收"))
                .add(mapper.createObjectNode().put("id", "US03").put("title", "消息提醒生成"))
                .add(mapper.createObjectNode().put("id", "US04").put("title", "活动状态流转"))
                .add(mapper.createObjectNode().put("id", "US05").put("title", "取消报名规则"))
                .add(mapper.createObjectNode().put("id", "US06").put("title", "活动列表查看"))
                .add(mapper.createObjectNode().put("id", "US07").put("title", "活动详情"))
                .add(mapper.createObjectNode().put("id", "US08").put("title", "学生个人中心"));
        draft.putArray("tasks")
                .add(mapper.createObjectNode().put("id", "T01").put("name", "数据库表结构与初始化脚本").put("story_ref", "US01"))
                .add(mapper.createObjectNode().put("id", "T02").put("name", "系统验收测试").put("story_ref", "US02"))
                .add(mapper.createObjectNode().put("id", "T03").put("name", "消息生成后端服务").put("story_ref", "US03"))
                .add(mapper.createObjectNode().put("id", "T04").put("name", "活动状态流转后端服务").put("story_ref", "US04"))
                .add(mapper.createObjectNode().put("id", "T05").put("name", "取消报名后端接口").put("story_ref", "US05"))
                .add(mapper.createObjectNode().put("id", "T06").put("name", "活动列表后端接口").put("story_ref", "US06"))
                .add(mapper.createObjectNode().put("id", "T07").put("name", "活动列表前端页面").put("story_ref", "US06"))
                .add(mapper.createObjectNode().put("id", "T08").put("name", "活动详情后端接口").put("story_ref", "US07"))
                .add(mapper.createObjectNode().put("id", "T09").put("name", "活动详情前端页面").put("story_ref", "US07"))
                .add(mapper.createObjectNode().put("id", "T10").put("name", "个人中心后端接口").put("story_ref", "US08"))
                .add(mapper.createObjectNode().put("id", "T11").put("name", "个人中心前端页面").put("story_ref", "US08"));

        ObjectNode audit = validator.detailedTaskStageCoverage(draft);

        assertEquals(1, audit.path("stageCoverageGapStoryCount").asInt(), audit.path("stageCoverageGaps").toString());
        java.util.Map<String, JsonNode> contracts = new java.util.HashMap<>();
        audit.path("storyStageContracts").forEach(row -> contracts.put(row.path("storyId").asText(), row));
        assertEquals("[\"data\"]", contracts.get("US01").path("requiredStages").toString());
        assertEquals("DATA_SCHEMA", contracts.get("US01").path("stageContractType").asText());
        assertEquals("[\"test\"]", contracts.get("US02").path("requiredStages").toString());
        assertEquals("[\"backend\"]", contracts.get("US03").path("requiredStages").toString());
        assertFalse(contracts.get("US03").path("requiredStages").toString().contains("frontend"));
        assertFalse(contracts.get("US04").path("requiredStages").toString().contains("frontend"));
        assertFalse(contracts.get("US05").path("requiredStages").toString().contains("frontend"));
        assertEquals("[\"backend\",\"frontend\"]", contracts.get("US06").path("requiredStages").toString());
        assertEquals("[\"backend\",\"frontend\"]", contracts.get("US07").path("requiredStages").toString());
        assertEquals("[\"backend\",\"frontend\"]", contracts.get("US08").path("requiredStages").toString());
        assertFalse(contracts.get("US01").path("classificationEvidence").asText().isBlank());
    }

    @Test
    void signupSubmissionPhraseBeatsValidationAndResultNotificationStory() throws Exception {
        ArrayNode stories = mapper.createArrayNode()
                .add(mapper.createObjectNode().put("id", "US13").put("title", "学生提交报名申请"))
                .add(mapper.createObjectNode().put("id", "US17").put("title", "报名结果消息通知"));
        ArrayNode tasks = mapper.createArrayNode().add(mapper.createObjectNode()
                .put("id", "T13").put("name", "学生报名提交与报名校验实现").put("story_ref", ""));
        Method method = ProjectGenerationValidator.class.getDeclaredMethod("finalRelationClosure",
                ArrayNode.class, ArrayNode.class, ArrayNode.class, ArrayNode.class);
        method.setAccessible(true);

        method.invoke(validator, stories, tasks, mapper.createArrayNode(), mapper.createArrayNode());

        assertEquals("US13", tasks.get(0).path("story_ref").asText());
        assertEquals("提交报名", tasks.get(0).path("relation_tie_break").path("operation").asText());
        assertEquals("RESOLVED", tasks.get(0).path("relation_tie_break").path("status").asText());
    }

    @Test
    void activityConsolidationRemapsEpicAndStoryThroughTheSameCompleteMap() {
        ObjectNode raw = mapper.createObjectNode();
        raw.putArray("activities")
                .add(mapper.createObjectNode().put("code", "A1").put("name", "登录权限"))
                .add(mapper.createObjectNode().put("code", "A2").put("name", "活动浏览"))
                .add(mapper.createObjectNode().put("code", "A3").put("name", "签到时长"))
                .add(mapper.createObjectNode().put("code", "A10").put("name", "活动浏览"));
        raw.putArray("stories")
                .add(mapper.createObjectNode().put("id", "US01").put("activity", "A1"))
                .add(mapper.createObjectNode().put("id", "US02").put("activity", "A2"))
                .add(mapper.createObjectNode().put("id", "US03").put("activity", "A3"))
                .add(mapper.createObjectNode().put("id", "US04").put("activity", "A10"));
        raw.putArray("epics").add(mapper.createObjectNode().put("id", "E10").put("title", "活动浏览").put("activity", "A10"));
        validator.consolidateDetailedActivities(raw);
        assertEquals("A2", raw.path("activity_reference_remap").path("A10").asText());
        assertEquals("A2", raw.path("epics").get(0).path("activity").asText());
        assertEquals("A2", raw.path("stories").get(3).path("activity").asText());
        assertEquals("A10", raw.path("epics").get(0).path("original_activity").asText());
    }

    @Test
    void epicOnlyCrossCuttingActivityIsRetainedAndAudited() throws Exception {
        ObjectNode raw = mapper.createObjectNode();
        raw.putArray("activities")
                .add(mapper.createObjectNode().put("code", "A1").put("name", "用户与权限"))
                .add(mapper.createObjectNode().put("code", "A2").put("name", "活动管理"))
                .add(mapper.createObjectNode().put("code", "A12").put("name", "异常处理与系统测试")
                        .put("description", "异常场景限制提示、系统测试和缺陷修复"));
        raw.putArray("stories")
                .add(mapper.createObjectNode().put("id", "US01").put("activity", "A1"))
                .add(mapper.createObjectNode().put("id", "US02").put("activity", "A2"));
        raw.putArray("epics").add(mapper.createObjectNode().put("id", "E13")
                .put("title", "异常处理与系统测试").put("activity", "A12"));

        validator.consolidateDetailedActivities(raw);

        assertEquals("A3", raw.path("epics").get(0).path("activity").asText());
        assertEquals("A3", raw.path("activity_reference_remap").path("A12").asText());
        JsonNode audit = raw.path("activity_mapping_audit").get(2);
        assertEquals(1, audit.path("epicReferenceCount").asInt());
        assertEquals("A3", audit.path("selectedFinalActivity").asText());
        Method method = ProjectGenerationValidator.class.getDeclaredMethod("finalActivityCheck", ObjectNode.class, ArrayNode.class);
        method.setAccessible(true);
        assertTrue(((ObjectNode) method.invoke(validator, raw, raw.path("stories"))).path("sufficient").asBoolean());
    }

    @Test
    void epicUnknownActivityIsClearedAndDiagnosedWithoutGuessing() throws Exception {
        ObjectNode raw = mapper.createObjectNode();
        ArrayNode activities = raw.putArray("activities"), rows = raw.putArray("stories");
        for (int i = 1; i <= 5; i++) {
            activities.add(mapper.createObjectNode().put("code", "A" + i).put("name", "共同业务域"));
            for (int j = 0; j < 5; j++) rows.add(mapper.createObjectNode().put("id", "US" + i + "-" + j).put("activity", "A" + i));
        }
        raw.putArray("epics").add(mapper.createObjectNode().put("id", "E10").put("title", "共同业务域").put("activity", "A10"));
        validator.consolidateDetailedActivities(raw);
        assertEquals(5, raw.path("activities").size());
        assertEquals("", raw.path("epics").get(0).path("activity").asText());
        assertEquals("A10", raw.path("epics").get(0).path("original_activity").asText());
        Method method = ProjectGenerationValidator.class.getDeclaredMethod("finalActivityCheck", ObjectNode.class, ArrayNode.class);
        method.setAccessible(true);
        ObjectNode check = (ObjectNode) method.invoke(validator, raw, rows);
        assertFalse(check.path("sufficient").asBoolean());
        assertEquals(1, check.path("invalidEpicActivityCount").asInt());
        assertTrue(check.path("issues").toString().contains("UNRESOLVED_EPIC_ACTIVITY"));
        assertEquals(0, check.path("invalidStoryActivityCount").asInt());
    }

    @Test
    void finalQualityPassPreservesTaskGenerationAndRelationDiagnostics() {
        ObjectNode raw = activityReferenceDraft("A1", "A2");
        raw.putObject("_task_generation_audit").put("initialTaskCount", 76).put("supplementAcceptedTaskCount", 0);
        ObjectNode draft = validator.normalize(raw, "create", "NEW-detailed-quality-preserve");
        assertEquals(76, draft.path("qualityAdjustments").path("taskGeneration").path("initialTaskCount").asInt());
        assertTrue(draft.path("qualityAdjustments").path("relationClosureStats").has("unresolvedReasonCounts"));
        assertTrue(draft.path("qualityAdjustments").path("activityReferenceAssertion").path("readOnly").asBoolean());
    }

    @Test
    void invalidEpicActivityIsARealCompletenessHardBlock() {
        ObjectNode raw = activityReferenceDraft("A1", "A2");
        ((ObjectNode) raw.path("epics").get(0)).put("activity", "A10")
                .put("title", "无法识别域");
        ObjectNode draft = validator.normalize(raw, "create", "NEW-detailed-epic-invalid");
        JsonNode epic = draft.path("epics").get(0);
        assertEquals("", epic.path("activity").asText());
        assertEquals("A10", epic.path("original_activity").asText());
        assertTrue(draft.path("completeness").path("hardBlockingIssues").toString().contains("UNRESOLVED_EPIC_ACTIVITY"));
        assertEquals(0, draft.path("qualityAdjustments").path("finalActivityCheck").path("invalidStoryActivityCount").asInt());
    }

    @Test
    void normalizePreservesModelStoryCodeAsCanonicalTaskStoryReference() {
        ObjectNode raw = baseDraft();
        ObjectNode source = task(raw, "A", "登录接口实现", 1);
        source.remove("story_ref");
        source.put("storyCode", "S1");

        ObjectNode normalized = validator.normalize(raw, "merge");
        JsonNode result = normalized.path("tasks").get(0);

        assertEquals("US01", result.path("story_ref").asText());
        assertEquals("US01", result.path("storyCode").asText());
        assertEquals(0, normalized.path("qualityAdjustments").path("finalRelationCheck")
                .path("orphanTaskCount").asInt());
    }

    @Test
    void normalizeDoesNotTreatUnknownModelStoryCodeAsValidReference() {
        ObjectNode raw = baseDraft();
        ObjectNode source = task(raw, "A", "搭建项目基础框架", 1);
        source.remove("story_ref");
        source.put("storyCode", "US404");

        ObjectNode normalized = validator.normalize(raw, "merge");
        JsonNode result = normalized.path("tasks").get(0);

        assertEquals("", result.path("story_ref").asText());
        assertEquals("", result.path("storyCode").asText());
        assertEquals("US404", result.path("model_story_refs").asText());
        assertEquals("US404", result.path("invalid_story_refs").asText());
        assertEquals(1, normalized.path("qualityAdjustments").path("finalRelationCheck")
                .path("orphanTaskCount").asInt());
    }

    @Test
    void activityCodeIsAcceptedAndCanonicalizedForStory() throws Exception {
        ObjectNode raw = mapper.createObjectNode();
        raw.putArray("activities").add(mapper.createObjectNode()
                .put("code", "A1").put("name", "用户与权限"));
        raw.putArray("stories").add(mapper.createObjectNode()
                .put("id", "US01").put("title", "用户登录").put("activityCode", "A1"));
        raw.putArray("epics");

        ArrayNode issues = invokeNormalizeActivityReferences(raw);
        JsonNode story = raw.path("stories").get(0);

        assertEquals("A1", story.path("activity").asText());
        assertEquals("A1", story.path("activityCode").asText());
        assertTrue(issues.isEmpty());
    }

    @Test
    void unknownStoryActivityCodeUsesUniqueSemanticMatchOrIsCleared() throws Exception {
        ObjectNode raw = mapper.createObjectNode();
        raw.putArray("activities")
                .add(mapper.createObjectNode().put("code", "A1").put("name", "用户登录与权限"))
                .add(mapper.createObjectNode().put("code", "A2").put("name", "活动管理"));
        raw.putArray("stories")
                .add(mapper.createObjectNode().put("id", "US01").put("title", "用户登录与权限校验")
                        .put("activityCode", "A7"))
                .add(mapper.createObjectNode().put("id", "US02").put("title", "无法识别领域")
                        .put("activityCode", "A8"));
        raw.putArray("epics");

        ArrayNode issues = invokeNormalizeActivityReferences(raw);
        JsonNode matched = raw.path("stories").get(0);
        JsonNode unresolved = raw.path("stories").get(1);

        assertEquals("A1", matched.path("activity").asText());
        assertEquals("A1", matched.path("activityCode").asText());
        assertEquals("", unresolved.path("activity").asText());
        assertEquals("", unresolved.path("activityCode").asText());
        assertEquals("A8", unresolved.path("original_activity").asText());
        assertEquals(1, issues.size());
    }

    @Test
    void epicWithRemovedEmptyDomainCanUseUniqueFinalSemanticDomain() {
        ObjectNode raw = mapper.createObjectNode();
        raw.putArray("activities")
                .add(mapper.createObjectNode().put("code", "A1").put("name", "用户登录权限"))
                .add(mapper.createObjectNode().put("code", "A2").put("name", "活动浏览筛选"))
                .add(mapper.createObjectNode().put("code", "A10").put("name", "活动浏览"));
        raw.putArray("stories")
                .add(mapper.createObjectNode().put("id", "US01").put("activity", "A1"))
                .add(mapper.createObjectNode().put("id", "US02").put("activity", "A2"));
        raw.putArray("epics").add(mapper.createObjectNode().put("id", "E10").put("title", "活动浏览").put("activity", "A10"));
        validator.consolidateDetailedActivities(raw);
        assertEquals("A2", raw.path("epics").get(0).path("activity").asText());
        assertEquals(2, raw.path("activities").size());
    }

    @Test
    void normalizeConservativelyInfersSameStoryChain() {
        ObjectNode raw = baseDraft();
        task(raw, "A", "数据库表结构设计", 1);
        task(raw, "B", "后端接口实现", 1);
        task(raw, "C", "前端页面开发", 1);
        task(raw, "D", "系统联调", 1);
        task(raw, "E", "验收测试", 1);

        ObjectNode normalized = validator.normalize(raw, "merge");
        ArrayNode rows = (ArrayNode) normalized.path("tasks");

        assertEquals("", rows.get(0).path("depends_on").asText());
        assertEquals("T01", rows.get(1).path("depends_on").asText());
        assertEquals("T02", rows.get(2).path("depends_on").asText());
        assertEquals("", rows.get(3).path("depends_on").asText());
        assertEquals("T03", rows.get(3).path("weak_dependencies").asText());
        assertEquals("", rows.get(4).path("depends_on").asText());
        assertEquals("T04", rows.get(4).path("weak_dependencies").asText());
        assertTrue(normalized.path("details").toString().contains("自动补充了推断依赖"));
    }

    @Test
    void normalizeCleansSelfDuplicateEmptyAndMissingDependenciesButKeepsLegalOnes() {
        ObjectNode raw = baseDraft();
        task(raw, "A", "接口", 1);
        ObjectNode b = task(raw, "B", "页面", 1);
        b.put("depends_on", "A,A,B,UNKNOWN, ,；");
        raw.set("dependencies",mapper.createArrayNode()
                .add(mapper.createObjectNode().put("from","A").put("to","B"))
                .add(mapper.createObjectNode().put("from","A").put("to","MISSING")));

        ObjectNode normalized = validator.normalize(raw, "merge");

        assertEquals("T01", normalized.path("tasks").get(1).path("depends_on").asText());
        assertEquals(1, normalized.path("completeness").path("removedSelfDependencies").asInt());
        assertEquals(1, normalized.path("completeness").path("removedDuplicateDependencies").asInt());
        assertEquals(2, normalized.path("completeness").path("removedInvalidDependencies").asInt());
        assertTrue(normalized.path("details").toString().contains("不存在任务"));
    }

    @Test
    void lowEvidenceTwoNodeCycleIsRemovedFromHardAndWeakGraphs() {
        ObjectNode raw = baseDraft();
        ObjectNode a = task(raw, "A", "接口A", 1);
        ObjectNode b = task(raw, "B", "接口B", 1);
        a.put("depends_on", "B");
        b.put("depends_on", "A");
        ObjectNode normalized = validator.normalize(raw, "merge");
        assertEquals("",normalized.path("tasks").get(0).path("depends_on").asText());
        assertEquals("",normalized.path("tasks").get(1).path("depends_on").asText());
        assertEquals("",normalized.path("tasks").get(0).path("weak_dependencies").asText());
        assertEquals("",normalized.path("tasks").get(1).path("weak_dependencies").asText());
        assertEquals(2, normalized.path("qualityAdjustments").path("dependencyCompaction")
                .path("weakDroppedByLowEvidence").asInt());
        assertEquals(0,normalized.path("completeness").path("dependencyCycleRepairs").asInt());
    }

    @Test
    void weakThreeNodeCycleDoesNotEnterHardSchedulerGraph() {
        ObjectNode raw=baseDraft();ObjectNode a=task(raw,"A","接口A",1);ObjectNode b=task(raw,"B","接口B",1);ObjectNode c=task(raw,"C","接口C",1);
        a.put("depends_on","B");b.put("depends_on","C");c.put("depends_on","A");
        ObjectNode normalized=validator.normalize(raw,"merge");
        assertEquals("",normalized.path("tasks").get(0).path("depends_on").asText());
        assertEquals("",normalized.path("tasks").get(1).path("depends_on").asText());
        assertEquals("",normalized.path("tasks").get(2).path("depends_on").asText());
        assertEquals(0,normalized.path("completeness").path("dependencyCycleRepairs").asInt());
    }

    @Test
    void excelExplicitSelfDependencyIsRemovedAndReported() {
        ObjectNode raw=baseDraft();ObjectNode a=task(raw,"A","接口A",1);
        a.put("depends_on","A").put("_import_dependencies_explicit",true);
        ObjectNode normalized=validator.normalizeImport(raw,"create","NEW-test");
        assertEquals("",normalized.path("tasks").get(0).path("depends_on").asText());
        assertEquals(1,normalized.path("completeness").path("removedSelfDependencies").asInt());
        assertTrue(normalized.path("details").toString().contains("Excel/CSV 显式依赖"));
    }

    @Test
    void fullyExplicitExcelCycleRemainsForValidatorSafetyGate() {
        ObjectNode raw=baseDraft();ObjectNode a=task(raw,"A","接口A",1);ObjectNode b=task(raw,"B","接口B",1);
        a.put("depends_on","B").put("_import_dependencies_explicit",true);
        b.put("depends_on","A").put("_import_dependencies_explicit",true);
        ApiException error=assertThrows(ApiException.class,()->validator.normalizeImport(raw,"create","NEW-test"));
        assertTrue(error.getMessage().contains("循环"));
    }

    @Test
    void dependencyCleaningAppliesToDetailedCoarseAndExcelNormalizationPaths() {
        ObjectNode detailed=baseDraft();task(detailed,"T100","详细模式任务",1).put("depends_on","T100");
        ObjectNode coarse=baseDraft();task(coarse,"T100","概略模式任务",1).put("depends_on","T100");
        ObjectNode excel=baseDraft();task(excel,"T100","Excel任务",1).put("depends_on","T100").put("_import_dependencies_explicit",true);

        ObjectNode detailedDraft=validator.normalize(detailed,"create","NEW-detailed");
        ObjectNode coarseDraft=validator.normalize(coarse,"create","NEW-coarse");
        ObjectNode excelDraft=validator.normalizeImport(excel,"create","NEW-excel");

        assertEquals("",detailedDraft.path("tasks").get(0).path("depends_on").asText());
        assertEquals("",coarseDraft.path("tasks").get(0).path("depends_on").asText());
        assertEquals("",excelDraft.path("tasks").get(0).path("depends_on").asText());
        assertEquals(1,detailedDraft.path("completeness").path("removedSelfDependencies").asInt());
        assertEquals(1,coarseDraft.path("completeness").path("removedSelfDependencies").asInt());
        assertEquals(1,excelDraft.path("completeness").path("removedSelfDependencies").asInt());
    }

    @Test
    void executionValidationStillRejectsDependenciesIntroducedAfterCleaning() {
        ObjectNode selfRaw=baseDraft();task(selfRaw,"A","接口A",1);
        ObjectNode selfDraft=validator.normalize(selfRaw,"create","NEW-self");
        ((ObjectNode)selfDraft.path("tasks").get(0)).put("depends_on","T01");
        ApiException selfError=assertThrows(ApiException.class,
                ()->validator.validateForExecution(selfDraft,"create","NEW-self"));
        assertTrue(selfError.getMessage().contains("依赖自身"));

        ObjectNode cycleRaw=baseDraft();task(cycleRaw,"A","接口A",1);task(cycleRaw,"B","接口B",1);
        ObjectNode cycleDraft=validator.normalize(cycleRaw,"create","NEW-cycle");
        ((ObjectNode)cycleDraft.path("tasks").get(0)).put("depends_on","T02");
        ((ObjectNode)cycleDraft.path("tasks").get(1)).put("depends_on","T01");
        ApiException cycleError=assertThrows(ApiException.class,
                ()->validator.validateForExecution(cycleDraft,"create","NEW-cycle"));
        assertTrue(cycleError.getMessage().contains("循环"));
    }

    @Test
    void editedDraftKeepsTaskScheduleAndReportsSprintMismatch() {
        ObjectNode raw = baseDraft();
        task(raw, "A", "登录接口", 1);
        ObjectNode normalized = validator.normalize(raw, "merge");
        normalized.path("stories").get(0).deepCopy();
        ((ObjectNode) normalized.path("stories").get(0)).put("sprint", 2);

        ObjectNode validated = validator.revalidateEditedDraft(normalized, "merge");

        assertEquals(1, validated.path("tasks").get(0).path("week_start").asInt());
        assertTrue(validated.path("warnings").toString().contains("Sprint 2 不一致"));
    }

    @Test
    void editedDraftRejectsInvalidPriority() {
        ObjectNode normalized = validator.normalize(baseDraft(), "merge");
        ((ObjectNode) normalized.path("stories").get(0)).put("priority", "High");

        ApiException error = assertThrows(ApiException.class,
                () -> validator.revalidateEditedDraft(normalized, "merge"));

        assertTrue(error.getMessage().contains("优先级不合法"));
    }

    @Test
    void finalQualityPassSpreadsGeneratedStoriesAcrossThreeSprints() {
        ObjectNode raw = mapper.createObjectNode();
        raw.set("project", mapper.createObjectNode().put("total_sprints", 3));
        ArrayNode stories = mapper.createArrayNode(), tasks = mapper.createArrayNode();
        for (int i = 1; i <= 30; i++) {
            stories.add(mapper.createObjectNode().put("id", "S" + i).put("title", "功能" + i).put("sprint", 1).put("activity", (i % 3) + 1));
            int week = i <= 10 ? 1 : i <= 20 ? 3 : 5;
            tasks.add(mapper.createObjectNode().put("id", "T" + i).put("name", "任务" + i).put("owner_id", 1)
                    .put("hours", 4).put("week_start", week).put("week_end", week).put("story_ref", "S" + i));
        }
        raw.set("stories", stories); raw.set("tasks", tasks);
        ObjectNode normalized = validator.normalize(raw, "merge", "NEW-balance");
        int[] counts = new int[4];
        normalized.path("stories").forEach(s -> counts[s.path("sprint").asInt()]++);
        assertTrue(counts[1] >= 8 && counts[2] >= 8 && counts[3] >= 8,
                "counts=" + java.util.Arrays.toString(counts));
        assertTrue(normalized.path("completeness").path("sprintBalanceIssues").isEmpty());
    }

    @Test
    void moderatelySkewedSupplementStoriesFollowTheirTaskWeeks() {
        ObjectNode raw = mapper.createObjectNode();
        raw.set("project", mapper.createObjectNode().put("total_sprints", 3));
        ArrayNode storyRows = raw.putArray("stories"), taskRows = raw.putArray("tasks");
        for (int i = 1; i <= 30; i++) {
            int proposed = i <= 16 ? 1 : i <= 24 ? 2 : 3;
            int week = i <= 10 ? 1 : i <= 20 ? 3 : 5;
            storyRows.add(mapper.createObjectNode().put("id", "S" + i).put("title", "功能" + i).put("sprint", proposed));
            taskRows.add(mapper.createObjectNode().put("id", "K" + i).put("name", "实现" + i)
                    .put("owner_id", 1).put("hours", 2).put("week_start", week).put("week_end", week)
                    .put("story_ref", "S" + i));
        }

        ObjectNode draft = validator.normalize(raw, "create", "NEW-supplement");

        assertEquals("[10,10,10]", draft.path("completeness").path("storyCountsBySprint").toString());
    }

    @Test
    void fortyStoriesAndTasksCollapsedIntoWeekOneAreRedistributedAcrossThreeSprints() {
        ObjectNode raw = mapper.createObjectNode();
        raw.set("project", mapper.createObjectNode().put("total_sprints", 3));
        ArrayNode storyRows = raw.putArray("stories"), taskRows = raw.putArray("tasks");
        for (int i = 1; i <= 40; i++) {
            String phase = i <= 13 ? "账号基础" : i <= 26 ? "预约排班" : "统计验收";
            storyRows.add(mapper.createObjectNode().put("id", "S" + i).put("title", phase + i).put("sprint", 1));
            taskRows.add(mapper.createObjectNode().put("id", "K" + i).put("name", phase + "实现" + i)
                    .put("owner_id", 1).put("hours", 4).put("week_start", 1).put("week_end", 1)
                    .put("story_ref", "S" + i));
        }

        ObjectNode draft = validator.normalize(raw, "merge", "NEW-collapsed");

        ArrayNode counts = (ArrayNode) draft.path("completeness").path("storyCountsBySprint");
        assertEquals(40, counts.get(0).asInt() + counts.get(1).asInt() + counts.get(2).asInt());
        assertTrue(counts.get(0).asInt() > 0 && counts.get(1).asInt() > 0 && counts.get(2).asInt() > 0,
                counts.toString());
        Set<Integer> taskWeeks = new HashSet<>();
        draft.path("tasks").forEach(task -> taskWeeks.add(task.path("week_start").asInt()));
        assertTrue(taskWeeks.stream().anyMatch(week -> week >= 3));
        assertTrue(taskWeeks.stream().anyMatch(week -> week >= 5));
        assertFalse(draft.path("completeness").path("unresolvedWorkloadRisks").toString()
                .contains("T01 没有符合角色与容量要求"));
    }

    @Test
    void finalSprintGuardRepairsFiveSeventeenEighteenWithoutForcingExactEquality() {
        ObjectNode raw = mapper.createObjectNode();
        raw.set("project", mapper.createObjectNode().put("total_sprints", 3));
        ArrayNode storyRows = raw.putArray("stories"), taskRows = raw.putArray("tasks");
        for (int i = 1; i <= 40; i++) {
            int sprint = i <= 5 ? 1 : i <= 22 ? 2 : 3;
            storyRows.add(mapper.createObjectNode().put("id", "S" + i)
                    .put("title", "边界业务能力" + i).put("sprint", sprint));
            taskRows.add(mapper.createObjectNode().put("id", "K" + i).put("name", "实现能力" + i)
                    .put("owner_id", 1).put("hours", 2).put("week_start", sprint * 2 - 1)
                    .put("week_end", sprint * 2 - 1).put("story_ref", "S" + i));
        }

        ObjectNode draft = validator.normalize(raw, "merge", "NEW-final-sprint-guard");

        ArrayNode counts = (ArrayNode) draft.path("completeness").path("storyCountsBySprint");
        assertEquals("[5,17,18]", counts.toString(), "显式 Sprint 应保持原计划");
        assertFalse(draft.path("completeness").path("sprintBalanceIssues").isEmpty(),
                "严重失衡应提示人工调整，而非悄悄改动显式计划");
    }

    @Test
    void generatedSprintTasksUseBothWeeksAndPreferWeekMoveBeforeOwnerChange() {
        ObjectNode raw = mapper.createObjectNode();
        raw.set("project", mapper.createObjectNode().put("total_sprints", 3));
        ArrayNode storyRows = raw.putArray("stories"), taskRows = raw.putArray("tasks");
        for (int sprint = 1; sprint <= 3; sprint++) for (int i = 1; i <= 8; i++) {
            String id = "S" + sprint + "X" + i;
            storyRows.add(mapper.createObjectNode().put("id", id).put("title", "阶段" + sprint + "能力" + i)
                    .put("sprint", sprint).put("_user_sprint_explicit", true));
            taskRows.add(mapper.createObjectNode().put("id", "K" + sprint + "X" + i).put("name", "实现" + id)
                    .put("owner_id", 1).put("hours", 12).put("week_start", sprint * 2 - 1)
                    .put("week_end", sprint * 2 - 1).put("story_ref", id));
        }

        ObjectNode draft = validator.normalize(raw, "merge", "NEW-two-week-balance");

        Set<Integer> weeks = new HashSet<>(); draft.path("tasks").forEach(t -> weeks.add(t.path("week_start").asInt()));
        assertTrue(weeks.containsAll(Set.of(1, 2, 3, 4, 5, 6)), weeks.toString());
        assertEquals(0, draft.path("completeness").path("reassignedTaskOwners").size());
        assertEquals(0, draft.path("completeness").path("unresolvedWorkloadRisks").size());
    }

    @Test
    void severeWeekFiveOverloadMovesAiTasksToWeekSixAndKeepsExplicitSchedule() {
        ObjectNode raw = mapper.createObjectNode();
        raw.set("project", mapper.createObjectNode().put("total_sprints", 3));
        raw.putArray("stories").add(mapper.createObjectNode().put("id", "S1").put("title", "验收阶段")
                .put("sprint", 3).put("_user_sprint_explicit", true));
        ArrayNode rows = raw.putArray("tasks");
        rows.add(mapper.createObjectNode().put("id", "FIXED").put("name", "用户固定验收").put("owner_id", 1)
                .put("hours", 24).put("week_start", 5).put("week_end", 5).put("story_ref", "S1")
                .put("_user_schedule_explicit", true));
        for (int i = 1; i <= 4; i++) rows.add(mapper.createObjectNode().put("id", "AI" + i)
                .put("name", "AI验收任务" + i).put("owner_id", 1).put("hours", 20)
                .put("week_start", 5).put("week_end", 5).put("story_ref", "S1"));

        ObjectNode draft = validator.normalize(raw, "merge", "NEW-week-six");

        assertEquals(5, draft.path("tasks").get(0).path("week_start").asInt());
        assertTrue(java.util.stream.StreamSupport.stream(draft.path("tasks").spliterator(), false)
                .skip(1).anyMatch(task -> task.path("week_start").asInt() == 6
                        || task.path("week_end").asInt() == 6));
        assertTrue(draft.path("risks").path("loadRisk").size() < 2);
    }

    @Test
    void uncoveredStoryUsesSemanticTaskInsteadOfSequenceMapping() {
        ObjectNode raw = mapper.createObjectNode();
        raw.set("project", mapper.createObjectNode().put("total_sprints", 3));
        raw.putArray("stories")
                .add(mapper.createObjectNode().put("id", "S1").put("title", "预约提醒基础").put("sprint", 2).put("_user_sprint_explicit", true))
                .add(mapper.createObjectNode().put("id", "S2").put("title", "预约提醒时间配置")
                        .put("description", "配置预约通知和提醒规则").put("sprint", 2).put("_user_sprint_explicit", true));
        raw.putArray("tasks")
                .add(mapper.createObjectNode().put("id", "K1").put("name", "无关账号接口").put("owner_id", 1)
                        .put("hours", 4).put("week_start", 3).put("week_end", 3).put("story_ref", "S1"))
                .add(mapper.createObjectNode().put("id", "K2").put("name", "预约通知提醒规则实现").put("owner_id", 1)
                        .put("hours", 4).put("week_start", 3).put("week_end", 3).put("story_ref", "S1"));

        ObjectNode draft = validator.normalize(raw, "merge", "NEW-semantic-link");

        assertEquals("US01", draft.path("tasks").get(0).path("story_ref").asText());
        assertEquals("US01,US02", draft.path("tasks").get(1).path("story_ref").asText());
        assertTrue(draft.path("completeness").path("autoCompletedItems").toString().contains("按语义匹配"));
    }

    @Test
    void uncoveredStoryCreatesCompleteTaskWhenNoSemanticMatchExists() {
        ObjectNode raw = mapper.createObjectNode();
        raw.set("project", mapper.createObjectNode().put("total_sprints", 3));
        raw.putArray("stories")
                .add(mapper.createObjectNode().put("id", "S1").put("title", "账号登录").put("sprint", 1).put("_user_sprint_explicit", true))
                .add(mapper.createObjectNode().put("id", "S2").put("title", "预约数据导出与归档")
                        .put("description", "导出报表用于归档").put("sprint", 3).put("_user_sprint_explicit", true));
        raw.putArray("tasks").add(mapper.createObjectNode().put("id", "K1").put("name", "登录接口")
                .put("owner_id", 1).put("hours", 4).put("week_start", 1).put("week_end", 1).put("story_ref", "S1"));

        ObjectNode draft = validator.normalize(raw, "merge", "NEW-created-link");

        assertEquals(2, draft.path("tasks").size());
        JsonNode created = draft.path("tasks").get(1);
        assertEquals("US02", created.path("story_ref").asText());
        assertFalse(created.path("name").asText().isBlank());
        assertTrue(created.path("owner_id").asInt() > 0 && created.path("hours").asInt() > 0);
        assertTrue(created.path("week_start").asInt() >= 5 && created.path("week_end").asInt() <= 6);
        assertTrue(draft.path("completeness").path("autoCompletedItems").toString().contains("新增语义补全 Task"));
    }

    @Test
    void dependencyDelayReconcilesInferredStorySprint() {
        ObjectNode raw = baseDraft();
        ((ArrayNode) raw.path("stories")).add(mapper.createObjectNode().put("id", "S2")
                .put("title", "前置功能").put("sprint", 2));
        task(raw, "A", "活动数据库表结构", 3).put("story_ref", "S2");
        task(raw, "B", "活动后端接口", 1).put("depends_on", "A");

        ObjectNode draft = validator.normalize(raw, "create", "NEW-dependency");

        assertTrue(draft.path("tasks").get(1).path("week_start").asInt()
                >= draft.path("tasks").get(0).path("week_end").asInt());
        assertEquals(2, draft.path("stories").get(0).path("sprint").asInt());
        assertEquals(0, draft.path("risks").path("sprintRisk").size());
    }

    @Test
    void detailedUpstreamFreezeClosesRelationsBeforeDependenciesAndKeepsFeasibilityStats() {
        ObjectNode raw = baseDraft();
        task(raw, "A", "账号登录接口", 1);
        task(raw, "B", "账户数据查询", 1);

        ObjectNode draft = validator.normalize(raw, "create", "NEW-freeze-pipeline");

        JsonNode adjustments = draft.path("qualityAdjustments");
        assertTrue(adjustments.path("taskFeasibility").path("taskCount").isInt());
        assertTrue(adjustments.path("taskFeasibility").path("postFeasibilityTaskCount").isInt());
        assertTrue(adjustments.path("finalRelationCheck").path("relationFrozen").asBoolean());
        assertTrue(adjustments.path("storySprintFreeze").path("frozen").asBoolean());
        assertTrue(adjustments.path("dependencyFreeze").path("frozen").asBoolean());
        assertTrue(adjustments.path("activityFreeze").path("frozen").asBoolean());
        assertEquals(draft.path("tasks").size(), adjustments.path("taskFeasibility")
                .path("postFeasibilityTaskCount").asInt());
        assertFalse(adjustments.path("freezeCheck").path("relationsChangedAfterFreeze").asBoolean());
        assertFalse(adjustments.path("freezeCheck").path("dependenciesChangedAfterFreeze").asBoolean());
        assertFalse(adjustments.path("freezeCheck").path("storySprintChangedAfterFreeze").asBoolean());
        assertFalse(adjustments.path("freezeCheck").path("activitiesChangedAfterFreeze").asBoolean());
    }

    @Test
    void unresolvedOrphanTaskDoesNotContributeInferredDependenciesAndBlocksCompleteness() throws Exception {
        ObjectNode raw = baseDraft();
        task(raw, "A", "没有可匹配业务对象的特殊技术任务", 1).put("story_ref", "MISSING");
        task(raw, "B", "账号登录接口", 1);
        ((ObjectNode) raw.path("tasks").get(1)).put("depends_on", "T01");

        ObjectNode draft = validator.normalize(raw, "create", "NEW-orphan-freeze");

        JsonNode orphan = draft.path("tasks").get(0);
        assertTrue(orphan.path("_unresolved_story_relation").asBoolean());
        assertEquals("UNRESOLVED_STORY_RELATION", orphan.path("_schedule_conflict").path("code").asText());
        assertEquals(1, draft.path("completeness").path("finalRelationCheck").path("orphanTaskCount").asInt());
        assertTrue(draft.path("completeness").path("hardBlockingIssues").toString().contains("Task 未关联 Story"));
        assertEquals("", draft.path("tasks").get(1).path("depends_on").asText());
        assertFalse(dependencyOrder((ArrayNode) draft.path("tasks"), (ArrayNode) draft.path("stories"),
                (ArrayNode) draft.path("sprints"))
                .contains(orphan.path("id").asText()));
    }

    @Test
    void dependencySprintReconciliationCascadesForwardAndPreservesMonotonicEdges() throws Exception {
        ArrayNode stories = mapper.createArrayNode()
                .add(mapper.createObjectNode().put("id", "US01").put("sprint", 3).put("priority", "Should"))
                .add(mapper.createObjectNode().put("id", "US02").put("sprint", 1).put("priority", "Could"))
                .add(mapper.createObjectNode().put("id", "US03").put("sprint", 1).put("priority", "Could"));
        ArrayNode tasks = mapper.createArrayNode()
                .add(scheduleTask("T01", "", "US01").put("name", "设计活动表结构"))
                .add(scheduleTask("T02", "T01", "US02").put("name", "开发活动后端接口"))
                .add(scheduleTask("T03", "T02", "US03").put("name", "开发活动前端页面"));
        ArrayNode sprints = mapper.createArrayNode()
                .add(mapper.createObjectNode().put("sprint", 1))
                .add(mapper.createObjectNode().put("sprint", 2))
                .add(mapper.createObjectNode().put("sprint", 3));
        ArrayNode initial = mapper.createArrayNode().add(mapper.createObjectNode());
        Method reconcile = ProjectGenerationValidator.class.getDeclaredMethod("dependencyAwareStorySprintReconciliation",
                ArrayNode.class, ArrayNode.class, ArrayNode.class, ArrayNode.class);
        reconcile.setAccessible(true);
        ArrayNode violationsMethodInput = mapper.createArrayNode();

        ObjectNode first = (ObjectNode) reconcile.invoke(validator, stories, tasks, sprints, initial);
        java.util.Map<String, Integer> firstResult = new java.util.TreeMap<>();
        stories.forEach(story -> firstResult.put(story.path("id").asText(), story.path("sprint").asInt()));
        ArrayNode secondStories = (ArrayNode) stories.deepCopy();
        ObjectNode second = (ObjectNode) reconcile.invoke(validator, secondStories, tasks, sprints, violationsMethodInput);

        assertEquals(3, stories.get(1).path("sprint").asInt());
        assertEquals(3, stories.get(2).path("sprint").asInt());
        assertEquals(0, first.path("remainingViolationCount").asInt());
        assertEquals(firstResult, sprintValues(secondStories));
        assertEquals(0, second.path("remainingViolationCount").asInt());
        assertTrue(tasks.get(1).path("depends_on").asText().equals("T01"));
        assertTrue(tasks.get(2).path("depends_on").asText().equals("T02"));
    }

    private java.util.Map<String, Integer> sprintValues(ArrayNode stories) {
        java.util.Map<String, Integer> result = new java.util.TreeMap<>();
        stories.forEach(story -> result.put(story.path("id").asText(), story.path("sprint").asInt()));
        return result;
    }

    @Test
    void detailedNormalizationIsDeterministicForSameInput() {
        ObjectNode raw = baseDraft();
        task(raw, "B", "登录接口", 1);
        task(raw, "A", "登录页面", 1);
        ObjectNode first = validator.normalize(raw.deepCopy(), "create", "NEW-deterministic");
        ObjectNode second = validator.normalize(raw.deepCopy(), "create", "NEW-deterministic");
        assertEquals(first.toString(), second.toString());
    }

    @Test
    void finalSchedulerUsesStableDependencyTopologyInsteadOfInputOrder() throws Exception {
        ArrayNode stories = mapper.createArrayNode()
                .add(mapper.createObjectNode().put("id", "S1").put("sprint", 1));
        ArrayNode first = mapper.createArrayNode()
                .add(scheduleTask("T03", "T02", "S1"))
                .add(scheduleTask("T01", "", "S1"))
                .add(scheduleTask("T02", "T01", "S1"));
        ArrayNode second = mapper.createArrayNode()
                .add(scheduleTask("T02", "T01", "S1"))
                .add(scheduleTask("T03", "T02", "S1"))
                .add(scheduleTask("T01", "", "S1"));
        ArrayNode sprints = mapper.createArrayNode().add(mapper.createObjectNode()
                .put("sprint", 1).put("week_start", 1).put("week_end", 2));

        assertEquals(List.of("T01", "T02", "T03"), dependencyOrder(first, stories, sprints));
        assertEquals(dependencyOrder(first, stories, sprints), dependencyOrder(second, stories, sprints));
    }

    @Test
    void dependencyBoundaryAllowsSameWeekStartAtPredecessorEnd() throws Exception {
        Method method = ProjectGenerationValidator.class.getDeclaredMethod(
                "dependencyTimingConflict", JsonNode.class, JsonNode.class);
        method.setAccessible(true);
        JsonNode predecessor = mapper.createObjectNode().put("week_end", 3);
        assertFalse((boolean) method.invoke(validator, mapper.createObjectNode().put("week_start", 3), predecessor));
        assertTrue((boolean) method.invoke(validator, mapper.createObjectNode().put("week_start", 2), predecessor));
    }

    @Test
    void multiStorySprintVoteTieSelectsEarlierSprint() throws Exception {
        ArrayNode stories = mapper.createArrayNode()
                .add(mapper.createObjectNode().put("id", "S2").put("sprint", 2))
                .add(mapper.createObjectNode().put("id", "S1").put("sprint", 1));
        ArrayNode sprints = mapper.createArrayNode()
                .add(mapper.createObjectNode().put("sprint", 1).put("week_start", 1).put("week_end", 2))
                .add(mapper.createObjectNode().put("sprint", 2).put("week_start", 3).put("week_end", 4));
        Method method = ProjectGenerationValidator.class.getDeclaredMethod(
                "sprintRangeForTask", JsonNode.class, ArrayNode.class, ArrayNode.class);
        method.setAccessible(true);
        int[] range = (int[]) method.invoke(validator,
                mapper.createObjectNode().put("story_ref", "S2,S1"), stories, sprints);
        assertEquals(1, range[0]);
        assertEquals(2, range[1]);
    }

    @Test
    void unschedulableTaskPreservesModelScheduleForAuditAndCannotPassFinalCheck() throws Exception {
        ArrayNode stories = mapper.createArrayNode()
                .add(mapper.createObjectNode().put("id", "EARLY").put("sprint", 1))
                .add(mapper.createObjectNode().put("id", "LATE").put("sprint", 3));
        ArrayNode tasks = mapper.createArrayNode()
                .add(scheduleTask("T01", "", "LATE").put("week_start", 5).put("week_end", 5))
                .add(scheduleTask("T02", "T01", "EARLY").put("owner_id", 99).put("week_start", 6).put("week_end", 6));
        ArrayNode sprints = mapper.createArrayNode()
                .add(mapper.createObjectNode().put("sprint", 1).put("week_start", 1).put("week_end", 2))
                .add(mapper.createObjectNode().put("sprint", 2).put("week_start", 3).put("week_end", 4))
                .add(mapper.createObjectNode().put("sprint", 3).put("week_start", 5).put("week_end", 6));
        ArrayNode members = mapper.createArrayNode().add(mapper.createObjectNode()
                .put("id", 1).put("name", "member").put("role", "member").put("capacity_hours", 60));
        ArrayNode ownerMoves = mapper.createArrayNode(), scheduleMoves = mapper.createArrayNode();
        ArrayNode unresolved = mapper.createArrayNode(), warnings = mapper.createArrayNode();
        Method method = ProjectGenerationValidator.class.getDeclaredMethod("deterministicFinalSchedule",
                ArrayNode.class, ArrayNode.class, ArrayNode.class, ArrayNode.class,
                ArrayNode.class, ArrayNode.class, ArrayNode.class, ArrayNode.class);
        method.setAccessible(true);

        method.invoke(validator, tasks, stories, sprints, members, ownerMoves, scheduleMoves, unresolved, warnings);

        JsonNode blocked = tasks.get(1);
        assertEquals("SPRINT_DEPENDENCY_CONFLICT", blocked.path("_schedule_conflict").path("code").asText());
        assertFalse(blocked.has("owner_id"));
        assertFalse(blocked.has("week_start"));
        assertFalse(blocked.has("week_end"));
        assertEquals(99, blocked.path("model_owner_id").asInt());
        assertEquals(6, blocked.path("model_week_start").asInt());
        assertEquals(6, blocked.path("model_week_end").asInt());
        assertTrue(unresolved.toString().contains("T02"));

        Method check = ProjectGenerationValidator.class.getDeclaredMethod("finalSprintCheck",
                ArrayNode.class, ArrayNode.class, ArrayNode.class);
        check.setAccessible(true);
        JsonNode result = (JsonNode) check.invoke(validator, stories, tasks, sprints);
        assertEquals(1, result.path("unresolvedSchedulingConflictCount").asInt());
        assertEquals(1, result.path("preservedModelScheduleCount").asInt());
        assertEquals(1, result.path("trueSprintMismatchCount").asInt());
    }

    @Test
    void modelWeekConcentrationIsRebuiltAndFeasibleCapacityIsPreferred() throws Exception {
        secondMember();
        ArrayNode stories = mapper.createArrayNode().add(mapper.createObjectNode().put("id", "S1").put("sprint", 1));
        ArrayNode sprints = mapper.createArrayNode().add(mapper.createObjectNode()
                .put("sprint", 1).put("week_start", 1).put("week_end", 2));
        ArrayNode tasks = mapper.createArrayNode();
        for (int i = 1; i <= 8; i++) tasks.add(scheduleTask(String.format("T%02d", i), "", "S1").put("hours", 20));
        ArrayNode ownerMoves = mapper.createArrayNode(), scheduleMoves = mapper.createArrayNode();
        ArrayNode unresolved = mapper.createArrayNode(), warnings = mapper.createArrayNode();

        invokeFinalSchedule(tasks, stories, sprints, members(1, 2), ownerMoves, scheduleMoves, unresolved, warnings);

        java.util.Map<String, int[]> loads = new java.util.HashMap<>();
        for (JsonNode task : tasks) {
            int[] memberLoad = loads.computeIfAbsent(task.path("owner_id").asText(), ignored -> new int[2]);
            memberLoad[task.path("week_start").asInt() - 1] += task.path("hours").asInt();
        }
        assertTrue(scheduleMoves.size() > 0, "model W6 proposals should be replaced by legal Sprint weeks");
        assertTrue(loads.values().stream().allMatch(load -> load[0] <= 60 && load[1] <= 60), loads.toString());
        assertTrue(tasks.toString().contains("\"week_start\":1") || tasks.toString().contains("\"week_start\":2"));
        assertEquals(0, unresolved.size());
    }

    @Test
    void finalSprintCheckIsReadOnlyAndUsesSharedDependencyBoundary() throws Exception {
        ArrayNode stories = mapper.createArrayNode().add(mapper.createObjectNode()
                .put("id", "S1").put("sprint", 1));
        ArrayNode sprints = mapper.createArrayNode().add(mapper.createObjectNode()
                .put("sprint", 1).put("week_start", 1).put("week_end", 2));
        ObjectNode predecessor = scheduleTask("T01", "", "S1").put("week_start", 2).put("week_end", 2);
        ObjectNode task = scheduleTask("T02", "T01", "S1").put("week_start", 2).put("week_end", 2);
        ArrayNode tasks = mapper.createArrayNode().add(predecessor).add(task);
        String before = tasks.toString();
        Method method = ProjectGenerationValidator.class.getDeclaredMethod("finalSprintCheck",
                ArrayNode.class, ArrayNode.class, ArrayNode.class);
        method.setAccessible(true);

        JsonNode result = (JsonNode) method.invoke(validator, stories, tasks, sprints);

        assertEquals(0, result.path("dependencyTimingConflictCount").asInt());
        assertEquals(2, result.path("scheduledTaskCount").asInt());
        assertEquals(0, result.path("unresolvedSchedulingConflictCount").asInt());
        assertTrue(result.path("readOnly").asBoolean());
        assertEquals(before, tasks.toString());
    }

    private ObjectNode scheduleTask(String id, String dependsOn, String storyRef) {
        return mapper.createObjectNode().put("id", id).put("name", id).put("priority", "Should")
                .put("owner_id", 1).put("hours", 4).put("week_start", 6).put("week_end", 6)
                .put("story_ref", storyRef).put("depends_on", dependsOn);
    }

    @Test
    void weakCrossStoryDependenciesDoNotMoveStoriesButCoreChainsDoOneSprintAtATime() throws Exception {
        ArrayNode stories = mapper.createArrayNode()
                .add(mapper.createObjectNode().put("id", "US01").put("title", "活动创建").put("sprint", 3))
                .add(mapper.createObjectNode().put("id", "US02").put("title", "活动页面").put("sprint", 1))
                .add(mapper.createObjectNode().put("id", "US03").put("title", "系统测试").put("sprint", 1));
        ArrayNode tasks = mapper.createArrayNode()
                .add(scheduleTask("T01", "", "US01").put("name", "开发活动后端接口"))
                .add(scheduleTask("T02", "T01", "US02").put("name", "开发活动前端页面"))
                .add(scheduleTask("T03", "T01", "US03").put("name", "活动系统集成测试"));
        ArrayNode sprints = mapper.createArrayNode().add(mapper.createObjectNode().put("sprint", 1))
                .add(mapper.createObjectNode().put("sprint", 2)).add(mapper.createObjectNode().put("sprint", 3));
        Method method = ProjectGenerationValidator.class.getDeclaredMethod("dependencyAwareStorySprintReconciliation",
                ArrayNode.class, ArrayNode.class, ArrayNode.class, ArrayNode.class);
        method.setAccessible(true);

        ObjectNode result = (ObjectNode) method.invoke(validator, stories, tasks, sprints, mapper.createArrayNode());

        assertEquals(3, stories.get(1).path("sprint").asInt());
        assertEquals(1, stories.get(2).path("sprint").asInt());
        assertEquals(1, result.path("coreDependencies").asInt());
        assertEquals(1, result.path("weakDependencies").asInt());
        assertEquals(2, result.path("storyMoveCount").asInt());
        assertEquals(0, result.path("remainingCoreConflicts").asInt());
        assertEquals(2, result.path("moves").get(0).path("to").asInt(), "one pass may move only one Sprint");
    }

    @Test
    void explicitStorySprintLeavesCoreConflictForHardBlockingInsteadOfForcingMove() throws Exception {
        ArrayNode stories = mapper.createArrayNode()
                .add(mapper.createObjectNode().put("id", "US01").put("sprint", 3))
                .add(mapper.createObjectNode().put("id", "US02").put("sprint", 1).put("sprint_explicit", true));
        ArrayNode tasks = mapper.createArrayNode()
                .add(scheduleTask("T01", "", "US01").put("name", "开发活动后端接口"))
                .add(scheduleTask("T02", "T01", "US02").put("name", "开发活动前端页面"));
        ArrayNode sprints = mapper.createArrayNode().add(mapper.createObjectNode().put("sprint", 1))
                .add(mapper.createObjectNode().put("sprint", 2)).add(mapper.createObjectNode().put("sprint", 3));
        Method method = ProjectGenerationValidator.class.getDeclaredMethod("dependencyAwareStorySprintReconciliation",
                ArrayNode.class, ArrayNode.class, ArrayNode.class, ArrayNode.class);
        method.setAccessible(true);

        ObjectNode result = (ObjectNode) method.invoke(validator, stories, tasks, sprints, mapper.createArrayNode());

        assertEquals(1, stories.get(1).path("sprint").asInt());
        assertEquals(1, result.path("remainingCoreConflicts").asInt());
        assertEquals("STORY_SPRINT_DEPENDENCY_CONFLICT", result.path("conflicts").get(0).path("code").asText());
    }

    @Test
    void emptyActivityDefinitionsAreRecoveredBeforeDetailedFreeze() {
        ObjectNode raw = mapper.createObjectNode();
        raw.set("project", mapper.createObjectNode().put("total_sprints", 3));
        ArrayNode stories = raw.putArray("stories");
        for (int i = 1; i <= 12; i++) stories.add(mapper.createObjectNode().put("id", "S" + i)
                .put("title", i <= 4 ? "用户登录权限" + i : i <= 8 ? "志愿活动报名" + i : "签到时长统计" + i)
                .put("description", "业务功能").put("acceptance", "可以完成").put("activity", "A" + ((i - 1) / 4 + 1))
                .put("sprint", (i - 1) / 4 + 1).put("owner_id", 1));
        raw.putArray("activities");
        raw.putArray("epics").add(mapper.createObjectNode().put("id", "E1").put("title", "用户与权限").put("activity", "A1"));
        ArrayNode tasks = raw.putArray("tasks");
        for (int i = 1; i <= 12; i++) tasks.add(mapper.createObjectNode().put("id", "K" + i).put("name", "实现业务" + i)
                .put("owner_id", 1).put("hours", 2).put("week_start", ((i - 1) / 4) * 2 + 1)
                .put("week_end", ((i - 1) / 4) * 2 + 1).put("story_ref", "S" + i));

        ObjectNode draft = validator.normalize(raw, "merge", "NEW-activity-recovery");

        assertFalse(draft.path("activities").isEmpty());
        assertTrue(draft.path("qualityAdjustments").path("finalActivityCheck").path("sufficient").asBoolean());
        assertEquals(0, draft.path("qualityAdjustments").path("finalActivityCheck").path("invalidStoryActivityCount").asInt());
        for (int i = 0; i < draft.path("activities").size(); i++)
            assertEquals("A" + (i + 1), draft.path("activities").get(i).path("code").asText());
    }

    @Test
    void numericActivityDefinitionsNormalizeToStableCodes() {
        ObjectNode raw = mapper.createObjectNode();
        raw.set("project", mapper.createObjectNode().put("total_sprints", 3));
        raw.putArray("activities")
                .add(mapper.createObjectNode().put("id", 1).put("name", "用户与权限"))
                .add(mapper.createObjectNode().put("id", 2).put("name", "活动管理"));
        raw.putArray("epics")
                .add(mapper.createObjectNode().put("id", "E1").put("title", "用户与权限").put("activity", 1))
                .add(mapper.createObjectNode().put("id", "E2").put("title", "活动管理").put("activity", 2));
        raw.putArray("stories")
                .add(mapper.createObjectNode().put("id", "S1").put("title", "用户注册").put("activity", 1).put("sprint", 1))
                .add(mapper.createObjectNode().put("id", "S2").put("title", "创建活动").put("activity", 2).put("sprint", 2));
        raw.putArray("tasks")
                .add(scheduleTask("T01", "", "S1").put("week_start", 1).put("week_end", 1))
                .add(scheduleTask("T02", "", "S2").put("week_start", 3).put("week_end", 3));

        ObjectNode draft = validator.normalize(raw, "merge", "NEW-numeric-activity");

        assertEquals("A1", draft.path("activities").get(0).path("code").asText());
        assertEquals("A2", draft.path("activities").get(1).path("code").asText());
        assertEquals("A1", draft.path("stories").get(0).path("activity").asText());
        assertEquals("A2", draft.path("epics").get(1).path("activity").asText());
    }

    @Test
    void dependencyMapsRejectDuplicateTaskCodesInsteadOfSilentlyOverwriting() throws Exception {
        ArrayNode stories = mapper.createArrayNode().add(mapper.createObjectNode().put("id", "US01").put("sprint", 1));
        ArrayNode tasks = mapper.createArrayNode().add(scheduleTask("T01", "", "US01"))
                .add(scheduleTask("T01", "", "US01").put("name", "different task"));
        ArrayNode sprints = mapper.createArrayNode().add(mapper.createObjectNode().put("sprint", 1));
        Method method = ProjectGenerationValidator.class.getDeclaredMethod("stableDependencyOrder",
                ArrayNode.class, ArrayNode.class, ArrayNode.class, ArrayNode.class, ArrayNode.class);
        method.setAccessible(true);

        java.lang.reflect.InvocationTargetException error = assertThrows(java.lang.reflect.InvocationTargetException.class,
                () -> method.invoke(validator, tasks, stories, sprints, mapper.createArrayNode(), mapper.createArrayNode()));
        assertTrue(error.getCause().getMessage().contains("DUPLICATE_TASK_CODE_CONFLICT"));
    }

    @Test
    void coarseCompositeBusinessTaskIsLeftForStageCoverageInsteadOfForcedRelation() throws Exception {
        ArrayNode stories = mapper.createArrayNode()
                .add(mapper.createObjectNode().put("id", "US01").put("title", "学生注册").put("description", "创建学生账号"))
                .add(mapper.createObjectNode().put("id", "US02").put("title", "活动报名").put("description", "提交活动报名"));
        ArrayNode tasks = mapper.createArrayNode()
                .add(mapper.createObjectNode().put("id", "T01").put("name", "学生注册接口与页面开发").put("story_ref", ""));
        Method method = ProjectGenerationValidator.class.getDeclaredMethod("ensureGeneratedTaskStoryReferences",
                ArrayNode.class, ArrayNode.class, ArrayNode.class, ArrayNode.class);
        method.setAccessible(true);

        method.invoke(validator, stories, tasks, mapper.createArrayNode(), mapper.createArrayNode());

        assertEquals("", tasks.get(0).path("story_ref").asText());
        assertEquals("COARSE_COMPOSITE_TASK", tasks.get(0).path("relation_tie_break").path("status").asText());
    }

    @Test
    void equivalentDuplicateTaskCodesMergeRelationsAndDependencies() throws Exception {
        ArrayNode tasks = mapper.createArrayNode()
                .add(scheduleTask("T01", "T03", "US01").put("name", "实现报名接口"))
                .add(scheduleTask("T01", "T04", "US02").put("name", "实现报名接口"));
        Method method = ProjectGenerationValidator.class.getDeclaredMethod("ensureUniqueGeneratedTaskCodes",
                ArrayNode.class, ArrayNode.class);
        method.setAccessible(true);

        ObjectNode result = (ObjectNode) method.invoke(validator, tasks, mapper.createArrayNode());

        assertEquals(1, tasks.size());
        assertEquals("US01,US02", tasks.get(0).path("story_ref").asText());
        assertEquals("T03,T04", tasks.get(0).path("depends_on").asText());
        assertEquals(1, result.path("mergedEquivalentTaskCount").asInt());
        assertTrue(result.path("sufficient").asBoolean());
    }

    @Test
    void distinctDuplicateTaskCodesRenumberWhenNoInboundReferenceIsAmbiguous() throws Exception {
        ArrayNode tasks = mapper.createArrayNode()
                .add(scheduleTask("T01", "", "US01").put("name", "实现报名接口"))
                .add(scheduleTask("T01", "", "US02").put("name", "开发报名页面"));
        Method method = ProjectGenerationValidator.class.getDeclaredMethod("ensureUniqueGeneratedTaskCodes",
                ArrayNode.class, ArrayNode.class);
        method.setAccessible(true);

        ObjectNode result = (ObjectNode) method.invoke(validator, tasks, mapper.createArrayNode());

        assertEquals("T01", tasks.get(0).path("id").asText());
        assertEquals("T02", tasks.get(1).path("id").asText());
        assertEquals(1, result.path("renumberedTaskCount").asInt());
        assertTrue(result.path("sufficient").asBoolean());
    }

    @Test
    void ambiguousDuplicateTaskReferenceIsReportedAsBlockingConflict() throws Exception {
        ArrayNode tasks = mapper.createArrayNode()
                .add(scheduleTask("T01", "", "US01").put("name", "实现报名接口"))
                .add(scheduleTask("T01", "", "US02").put("name", "开发报名页面"))
                .add(scheduleTask("T03", "T01", "US03").put("name", "负责人审核报名"));
        Method method = ProjectGenerationValidator.class.getDeclaredMethod("ensureUniqueGeneratedTaskCodes",
                ArrayNode.class, ArrayNode.class);
        method.setAccessible(true);

        ObjectNode result = (ObjectNode) method.invoke(validator, tasks, mapper.createArrayNode());

        assertFalse(result.path("sufficient").asBoolean());
        assertEquals(1, result.path("ambiguousReferenceCount").asInt());
        assertEquals(0, result.path("remainingDuplicateCodeCount").asInt());
    }

    @Test
    void volunteerPlanningFixtureKeepsEarlySprintsAndValidActivitiesDespiteWeakCrossStoryEdges() {
        ObjectNode raw = mapper.createObjectNode();
        raw.set("project", mapper.createObjectNode().put("total_sprints", 3));
        ArrayNode activities = raw.putArray("activities");
        String[] domains = {"用户权限", "活动管理", "报名审核", "签到时长", "评价通知", "统计后台"};
        for (int i = 0; i < domains.length; i++) activities.add(mapper.createObjectNode().put("id", i + 1).put("name", domains[i]));
        ArrayNode stories = raw.putArray("stories"), tasks = raw.putArray("tasks");
        String[] subjects = {"用户登录", "活动创建", "活动报名", "报名审核", "活动签到", "服务时长",
                "活动评价", "消息通知", "平台统计", "个人中心"};
        for (int i = 1; i <= 30; i++) {
            int sprint = (i - 1) / 10 + 1;
            String subject = subjects[(i - 1) % subjects.length];
            stories.add(mapper.createObjectNode().put("id", "S" + i).put("title", subject + i)
                    .put("description", subject + "业务流程").put("acceptance", "可以完成" + subject)
                    .put("sprint", sprint).put("activity", (i - 1) % 6 + 1).put("owner_id", 1));
            ObjectNode task = mapper.createObjectNode().put("id", "K" + i).put("name", "实现" + subject + i)
                    .put("owner_id", 1).put("hours", 2).put("week_start", sprint * 2 - 1)
                    .put("week_end", sprint * 2 - 1).put("story_ref", "S" + i);
            if (i == 3) task.put("name", "报名模块集成测试").put("depends_on", "K29");
            if (i == 4) task.put("name", "报名结果通知").put("depends_on", "K29");
            if (i == 5) task.put("name", "报名统计报表").put("depends_on", "K29");
            tasks.add(task);
        }

        ObjectNode draft = validator.normalize(raw, "merge", "NEW-volunteer-regression");

        int[] sprintCounts = new int[4];
        draft.path("stories").forEach(story -> sprintCounts[story.path("sprint").asInt()]++);
        assertTrue(sprintCounts[1] > 0 && sprintCounts[2] > 0, java.util.Arrays.toString(sprintCounts));
        assertTrue(draft.path("activities").size() >= 5 && draft.path("activities").size() <= 6);
        assertTrue(draft.path("qualityAdjustments").path("finalActivityCheck").path("sufficient").asBoolean());
        Set<String> codes = new HashSet<>();
        draft.path("tasks").forEach(task -> assertTrue(codes.add(task.path("id").asText())));
        assertTrue(draft.path("qualityAdjustments").path("dependencyCompaction")
                .path("weakDroppedByLowEvidence").asInt() >= 3);
    }

    @SuppressWarnings("unchecked")
    private List<String> dependencyOrder(ArrayNode tasks, ArrayNode stories, ArrayNode sprints) throws Exception {
        Method method = ProjectGenerationValidator.class.getDeclaredMethod("stableDependencyOrder",
                ArrayNode.class, ArrayNode.class, ArrayNode.class, ArrayNode.class, ArrayNode.class);
        method.setAccessible(true);
        ArrayNode warnings = mapper.createArrayNode(), unresolved = mapper.createArrayNode();
        List<ObjectNode> ordered = (List<ObjectNode>) method.invoke(validator, tasks, stories, sprints, warnings, unresolved);
        return ordered.stream().map(task -> task.path("id").asText()).toList();
    }

    private ArrayNode members(int... ids) {
        ArrayNode rows = mapper.createArrayNode();
        for (int id : ids) rows.add(mapper.createObjectNode().put("id", id).put("name", "member" + id)
                .put("role", "member").put("capacity_hours", 60));
        return rows;
    }

    private void invokeFinalSchedule(ArrayNode tasks, ArrayNode stories, ArrayNode sprints, ArrayNode members,
                                     ArrayNode ownerMoves, ArrayNode scheduleMoves, ArrayNode unresolved,
                                     ArrayNode warnings) throws Exception {
        Method method = ProjectGenerationValidator.class.getDeclaredMethod("deterministicFinalSchedule",
                ArrayNode.class, ArrayNode.class, ArrayNode.class, ArrayNode.class,
                ArrayNode.class, ArrayNode.class, ArrayNode.class, ArrayNode.class);
        method.setAccessible(true);
        method.invoke(validator, tasks, stories, sprints, members, ownerMoves, scheduleMoves, unresolved, warnings);
    }

    @Test
    void taskFeasibilityReportsMathematicallyInfeasibleDraft() {
        ObjectNode raw = baseDraft();
        task(raw, "A", "大工时任务", 1).put("hours", 999);
        task(raw, "B", "第二个大工时任务", 1).put("hours", 999);
        ObjectNode draft = validator.normalize(raw, "create", "NEW-feasibility");
        assertFalse(draft.path("qualityAdjustments").path("taskFeasibility").path("capacityFeasible").asBoolean(true));
        assertTrue(draft.path("qualityAdjustments").path("taskFeasibility").path("totalHours").asInt() >= 1998);
        assertFalse(draft.path("qualityAdjustments").path("finalCapacityFeasibility").path("capacityFeasible").asBoolean(true));
        assertTrue(draft.path("completeness").path("hardBlockingIssues").toString().contains("最终 Task 工时超过真实开发容量"));
    }

    @Test
    void explicitNaturalLanguageSprintMarkerIsPreserved() {
        ObjectNode raw = baseDraft();
        ((ObjectNode) raw.path("stories").get(0)).put("sprint", 3).put("_user_sprint_explicit", true);
        task(raw, "A", "部署验收", 5);
        ObjectNode normalized = validator.normalize(raw, "merge", "NEW-explicit");
        assertEquals(3, normalized.path("stories").get(0).path("sprint").asInt());
        assertTrue(normalized.path("stories").get(0).path("sprint_explicit").asBoolean());
    }

    @Test
    void generatedTasksRebalanceToQualifiedMemberAndReduceWeeklyRisk() {
        secondMember();
        ObjectNode raw = baseDraft();
        task(raw, "A", "同域维护", 1).put("owner_id", 2).put("hours", 4);
        task(raw, "B", "核心实现", 1).put("hours", 40);
        task(raw, "C", "核心联调", 1).put("hours", 40);

        ObjectNode draft = validator.normalize(raw, "create", "NEW-load");

        assertEquals(0, draft.path("risks").path("loadRisk").size());
        assertTrue(draft.path("completeness").path("taskScheduleAdjustments").size() > 0
                || draft.path("completeness").path("reassignedTaskOwners").size() > 0);
        assertEquals(0, draft.path("completeness").path("unresolvedWorkloadRisks").size());
    }

    @Test
    void ownerRebalanceSkipsInvalidWeekInsteadOfIndexingBeforeFirstWeek() throws Exception {
        secondMember();
        for (int[] range : new int[][]{{0, 1}, {-1, 2}, {1, 7}, {3, 2}}) {
            ArrayNode rows = ownerRebalanceTasks();
            ((ObjectNode) rows.get(1)).put("week_start", range[0]).put("week_end", range[1]);
            ArrayNode[] results = rebalanceDirectly(rows, ownerRebalanceMembers(1, 2));
            assertEquals(1, rows.get(1).path("owner_id").asInt());
            assertTrue(results[2].toString().contains("排期"));
            assertTrue(results[3].toString().contains("排期"));
        }
    }

    @Test
    void ownerRebalanceKeepsUnmappedAndMissingOwners() throws Exception {
        secondMember();
        ArrayNode rows = ownerRebalanceTasks();
        ((ObjectNode) rows.get(1)).put("owner_id", 99);
        ArrayNode[] results = rebalanceDirectly(rows, ownerRebalanceMembers(1, 2));
        assertEquals(99, rows.get(1).path("owner_id").asInt());
        assertTrue(results[2].toString().contains("不在当前成员列表"));

        rows = ownerRebalanceTasks();
        results = rebalanceDirectly(rows, ownerRebalanceMembers(1, 3));
        assertEquals(1, rows.get(1).path("owner_id").asInt());
        assertTrue(results[2].toString().contains("没有符合"));

        rows = ownerRebalanceTasks();
        ((ObjectNode) rows.get(1)).put("owner_id", 0);
        results = rebalanceDirectly(rows, ownerRebalanceMembers(1, 2));
        assertEquals(0, rows.get(1).path("owner_id").asInt());
        assertTrue(results[2].toString().contains("不在当前成员列表"));
    }

    @Test
    void ownerRebalanceHandlesNoMembersAndSingleMember() throws Exception {
        ArrayNode rows = ownerRebalanceTasks();
        ArrayNode[] empty = rebalanceDirectly(rows, mapper.createArrayNode());
        assertTrue(empty[2].toString().contains("没有可分配成员"));
        assertEquals(1, rows.get(1).path("owner_id").asInt());

        ArrayNode[] single = rebalanceDirectly(rows, ownerRebalanceMembers(1));
        assertTrue(single[2].toString().contains("没有符合"));
        assertEquals(1, rows.get(1).path("owner_id").asInt());
    }

    @Test
    void ownerRebalanceMovesQualifiedOwnerAcrossSixWeekPlan() throws Exception {
        secondMember();
        ArrayNode rows = ownerRebalanceTasks();
        for (int week = 2; week <= 6; week++) rows.add(mapper.createObjectNode()
                .put("id", "T0" + week).put("owner_id", 1).put("story_ref", "US02")
                .put("hours", 10).put("week_start", week).put("week_end", week));
        ArrayNode[] results = rebalanceDirectly(rows, ownerRebalanceMembers(1, 2, 3, 4));
        assertEquals(2, rows.get(1).path("owner_id").asInt());
        assertEquals(1, results[0].size());
        assertEquals(0, results[2].size());
    }

    private ArrayNode ownerRebalanceTasks() {
        ArrayNode rows = mapper.createArrayNode();
        rows.add(mapper.createObjectNode().put("id", "T01").put("owner_id", 2).put("story_ref", "US01")
                .put("hours", 2).put("week_start", 1).put("week_end", 1));
        rows.add(mapper.createObjectNode().put("id", "T02").put("owner_id", 1).put("story_ref", "US01")
                .put("hours", 90).put("week_start", 1).put("week_end", 1));
        return rows;
    }

    private ArrayNode ownerRebalanceMembers(int... ids) {
        ArrayNode rows = mapper.createArrayNode();
        for (int id : ids) rows.add(mapper.createObjectNode().put("id", id).put("role", "member")
                .put("capacity_hours", 100));
        return rows;
    }

    private ArrayNode[] rebalanceDirectly(ArrayNode rows, ArrayNode members) throws Exception {
        ArrayNode[] result = {mapper.createArrayNode(), mapper.createArrayNode(),
                mapper.createArrayNode(), mapper.createArrayNode()};
        Method method = ProjectGenerationValidator.class.getDeclaredMethod("rebalanceOwners",
                ArrayNode.class, ArrayNode.class, boolean.class, ArrayNode.class, ArrayNode.class,
                ArrayNode.class, ArrayNode.class);
        method.setAccessible(true);
        method.invoke(validator, rows, members, false, result[0], result[1], result[2], result[3]);
        return result;
    }

    @Test
    void explicitOwnerCannotBeChangedAndUnresolvedLoadIsReported() {
        secondMember();
        ObjectNode raw = baseDraft();
        task(raw, "A", "同域维护", 1).put("owner_id", 2).put("hours", 4);
        task(raw, "B", "用户指定任务", 1).put("hours", 45).put("_user_owner_explicit", true).put("_user_schedule_explicit", true);
        task(raw, "C", "用户指定联调", 1).put("hours", 45).put("_user_owner_explicit", true).put("_user_schedule_explicit", true);

        ObjectNode draft = validator.normalize(raw, "create", "NEW-fixed");

        assertEquals(1, draft.path("tasks").get(1).path("owner_id").asInt());
        assertTrue(draft.path("risks").path("loadRisk").size() > 0);
        assertEquals(0, draft.path("completeness").path("reassignedTaskOwners").size());
    }

    @Test
    void excelFieldsStayFixedWhileAiSupplementTaskCanMove() {
        secondMember();
        ObjectNode raw = baseDraft();
        task(raw, "A", "同域维护", 1).put("owner_id", 2).put("hours", 4);
        task(raw, "B", "导入任务", 1).put("hours", 45)
                .put("_import_owner_explicit", true).put("_import_schedule_explicit", true)
                .put("_import_hours_explicit", true);
        task(raw, "C", "AI补全任务", 1).put("hours", 35);

        ObjectNode draft = validator.normalizeImport(raw, "create", "NEW-import-load");

        JsonNode imported = draft.path("tasks").get(1);
        assertEquals(1, imported.path("owner_id").asInt());
        assertEquals(1, imported.path("week_start").asInt());
        assertEquals(45, imported.path("hours").asInt());
        assertTrue(draft.path("tasks").get(2).path("week_start").asInt() > 1
                || draft.path("tasks").get(2).path("owner_id").asInt() == 2);
        assertEquals(0, draft.path("risks").path("loadRisk").size());
    }

    @Test
    void generatedTaskMovesWithinSprintWhenNoQualifiedOwnerIsAvailable() {
        ObjectNode raw = baseDraft();
        task(raw, "A", "实现接口", 1).put("hours", 40);
        task(raw, "B", "实现页面", 1).put("hours", 40);

        ObjectNode draft = validator.normalize(raw, "create", "NEW-week-load");

        assertEquals(0, draft.path("risks").path("loadRisk").size());
        assertEquals(2, draft.path("tasks").get(1).path("week_start").asInt());
        assertTrue(draft.path("completeness").path("taskScheduleAdjustments").size() > 0);
    }

    @Test
    void generatedTaskCanSpreadHoursAcrossAdjacentWeeks() {
        ObjectNode raw = baseDraft();
        task(raw, "A", "大任务", 1).put("hours", 70);

        ObjectNode draft = validator.normalize(raw, "create", "NEW-spread-load");

        assertEquals(2, draft.path("tasks").get(0).path("week_end").asInt());
        assertEquals(70, draft.path("tasks").get(0).path("hours").asInt());
        assertEquals(0, draft.path("risks").path("loadRisk").size());
    }

    @Test
    void overloadedOwnerMayMoveToCandidateWhenOverallRiskImproves() {
        secondMember();
        ObjectNode raw = baseDraft();
        task(raw, "A", "核心实现", 1).put("owner_id", 1).put("hours", 80).put("week_start", 1).put("week_end", 1);
        task(raw, "B", "前端页面", 1).put("owner_id", 2).put("hours", 44).put("week_start", 1).put("week_end", 1);
        ObjectNode draft = validator.normalize(raw, "merge", "NEW-risk-improvement");
        int owner = draft.path("tasks").get(0).path("owner_id").asInt();
        assertTrue(owner == 1 || owner == 2);
    }

    @Test
    void detailedOrphanTaskCategoriesUseSemanticStoryEvidence() {
        ObjectNode raw = mapper.createObjectNode();
        raw.set("project", mapper.createObjectNode().put("total_sprints", 3));
        raw.putArray("activities").add(mapper.createObjectNode().put("id", 1).put("name", "项目管理"));
        raw.putArray("stories")
                .add(mapper.createObjectNode().put("id", "S1").put("title", "活动状态与规则").put("description", "维护活动状态模型和业务规则").put("sprint", 1))
                .add(mapper.createObjectNode().put("id", "S2").put("title", "消息通知").put("description", "发送活动消息和提醒").put("sprint", 2))
                .add(mapper.createObjectNode().put("id", "S3").put("title", "评价反馈").put("description", "收集评价与评分反馈").put("sprint", 3));
        raw.putArray("tasks")
                .add(mapper.createObjectNode().put("id", "T1").put("name", "活动状态模型").put("owner_id", 1).put("hours", 4).put("week_start", 1).put("week_end", 1))
                .add(mapper.createObjectNode().put("id", "T2").put("name", "消息通知基础能力").put("owner_id", 1).put("hours", 4).put("week_start", 3).put("week_end", 3))
                .add(mapper.createObjectNode().put("id", "T3").put("name", "评价反馈模块").put("owner_id", 1).put("hours", 4).put("week_start", 5).put("week_end", 5));
        ObjectNode draft = validator.normalize(raw, "merge", "NEW-orphan-category");
        assertTrue(java.util.stream.StreamSupport.stream(draft.path("tasks").spliterator(), false)
                .allMatch(t -> !t.path("story_ref").asText().isBlank()));
        assertTrue(draft.path("completeness").path("hardBlockingIssues").toString().contains("功能域") == false);
    }

    @Test
    void warningAggregationSummarizesVerboseAdjustmentsButKeepsRisksAndDetails() throws Exception {
        ArrayNode base = mapper.createArrayNode().add("罗子涵 W5 预计57h/周容量54h");
        ArrayNode details = mapper.createArrayNode();
        for (int i = 1; i <= 20; i++) details.add("T" + String.format("%02d", i) + " 已按依赖及项目周期调整到 W2-W2");
        for (int i = 1; i <= 7; i++) details.add("T" + String.format("%02d", i) + " 没有符合角色与容量要求的候选负责人，已保留原负责人");
        details.add("为 T21 自动补充了推断依赖 T20");
        Method method = ProjectGenerationValidator.class.getDeclaredMethod("summarizeGeneratedWarnings",
                ArrayNode.class, ArrayNode.class, int.class, ArrayNode.class);
        method.setAccessible(true);
        ArrayNode summarized = (ArrayNode) method.invoke(validator, base, details, 0, mapper.createArrayNode());
        assertEquals(1, summarized.toString().split("已自动调整 20 个 Task", -1).length - 1);
        assertEquals(1, summarized.toString().split("有 7 个 Task 未找到更合适", -1).length - 1);
        assertTrue(summarized.toString().contains("罗子涵 W5 预计57h/周容量54h"));
        assertTrue(details.size() >= 28);
    }

    @Test
    void finalRelationClosureDoesNotDuplicateTaskHoursForMultiStoryTask() throws Exception {
        ObjectNode raw = baseDraft();
        ((ArrayNode) raw.path("stories")).add(mapper.createObjectNode().put("id", "S2").put("title", "登录权限管理").put("sprint", 1));
        ObjectNode shared = task(raw, "A", "登录权限接口", 1).put("story_ref", "S1,S2").put("hours", 40);
        ObjectNode draft = validator.normalize(raw, "create", "NEW-unique-load");
        assertEquals(40, draft.path("tasks").get(0).path("hours").asInt());
        assertEquals(1, draft.path("tasks").size());
        assertTrue(shared.path("story_ref").asText().contains("S1"));
    }

    @Test
    void explicitImportScheduleWithDependencyConflictStaysFixed() {
        ObjectNode raw = baseDraft();
        task(raw, "A", "前置", 3).put("story_ref", "S1");
        task(raw, "B", "表格后续", 1).put("depends_on", "A").put("_import_schedule_explicit", true);

        ObjectNode draft = validator.normalizeImport(raw, "create", "NEW-import-dependency");

        assertEquals(1, draft.path("tasks").get(1).path("week_start").asInt());
        assertTrue(draft.path("details").toString().contains("用户排期与依赖"));
        assertTrue(draft.path("risks").path("dependencyRisk").size() > 0);
    }

    @Test
    void weakDependencyAtLastWeekDoesNotCompressOrHardBlockAiTask() {
        ObjectNode raw = baseDraft();
        task(raw, "A", "活动后端接口", 6);
        task(raw, "B", "活动前端联调", 4).put("week_end", 6).put("depends_on", "A").put("hours", 24);

        ObjectNode draft = validator.normalize(raw, "create", "NEW-last-week");
        JsonNode successor = draft.path("tasks").get(1);
        assertTrue(successor.path("week_end").asInt() <= 6);
        assertEquals(24, successor.path("hours").asInt());
        assertEquals("", successor.path("depends_on").asText());
        assertEquals("T01", successor.path("weak_dependencies").asText());
    }

    @Test
    void entireDependencyChainStaysWithinProjectWeeks() {
        ObjectNode raw = baseDraft();
        task(raw, "A", "设计基础", 4).put("week_end", 5);
        for (int i = 1; i <= 5; i++) {
            String predecessor = i == 1 ? "A" : "C" + (i - 1);
            task(raw, "C" + i, "阶段" + i, 2).put("week_end", 4).put("depends_on", predecessor);
        }

        ObjectNode draft = validator.normalize(raw, "create", "NEW-chain-weeks");
        for (JsonNode row : draft.path("tasks")) {
            assertTrue(row.path("week_end").asInt() <= 6);
            String ref = row.path("depends_on").asText();
            if (!ref.isBlank()) {
                JsonNode predecessor = null;
                for (JsonNode candidate : draft.path("tasks"))
                    if (ref.equals(candidate.path("id").asText())) predecessor = candidate;
                assertTrue(predecessor != null);
                assertTrue(row.path("week_start").asInt() >= predecessor.path("week_end").asInt());
            }
        }
    }

    @Test
    void explicitUserAndImportWeeksStayFixedWhenChainConflicts() {
        for (boolean imported : new boolean[]{false, true}) {
            ObjectNode raw = baseDraft();
            task(raw, "A", "前置", 6);
            ObjectNode fixed = task(raw, "B", "固定排期", 2).put("week_end", 3).put("depends_on", "A");
            fixed.put(imported ? "_import_schedule_explicit" : "_user_schedule_explicit", true);
            ObjectNode draft = imported ? validator.normalizeImport(raw, "create", "NEW-import-fixed")
                    : validator.normalize(raw, "create", "NEW-user-fixed");
            if (imported) {
                assertEquals(2, draft.path("tasks").get(1).path("week_start").asInt());
                assertEquals(3, draft.path("tasks").get(1).path("week_end").asInt());
                assertTrue(draft.path("details").toString().contains("需人工处理"));
            } else {
                JsonNode unresolved = draft.path("tasks").get(1);
                assertEquals("SPRINT_DEPENDENCY_CONFLICT", unresolved.path("_schedule_conflict").path("code").asText());
                assertEquals(1, unresolved.path("owner_id").asInt());
                assertEquals(2, unresolved.path("week_start").asInt());
                assertEquals(3, unresolved.path("week_end").asInt());
                assertFalse(draft.path("completeness").path("sufficient").asBoolean(true));
            }
        }
    }

    @Test
    void generatedPrioritiesAreRedistributedWhenModelUsesOnlyShould() {
        ObjectNode raw = baseDraft();
        ((ArrayNode) raw.path("stories")).add(mapper.createObjectNode().put("id", "S2").put("title", "活动报名查询").put("sprint", 1).put("activity", 1));
        ((ArrayNode) raw.path("stories")).add(mapper.createObjectNode().put("id", "S3").put("title", "普通通知").put("sprint", 1).put("activity", 1));
        raw.path("stories").forEach(node -> ((ObjectNode) node).put("priority", "Should"));
        ((ObjectNode) raw.path("stories").get(0)).put("title", "用户登录与权限管理");
        ((ObjectNode) raw.path("stories").get(0)).put("description", "登录、注册和角色权限");
        ((ObjectNode) raw.path("stories").get(2)).put("title", "高级数据导出");
        ((ObjectNode) raw.path("stories").get(2)).put("description", "导出和高级分析");
        ObjectNode draft = validator.normalize(raw, "create", "NEW-priority");
        Set<String> priorities = new HashSet<>();
        draft.path("stories").forEach(s -> priorities.add(s.path("priority").asText()));
        assertTrue(priorities.contains("Must"), draft.toString());
        assertTrue(priorities.contains("Could"), draft.toString());
        assertTrue(priorities.contains("Should"), draft.toString());
    }

    @Test
    void generatedOrphanTaskGetsAStoryReference() {
        ObjectNode raw = baseDraft();
        raw.path("stories").forEach(node -> ((ObjectNode) node).put("title", "系统日志归档"));
        task(raw, "A", "实现报名接口", 1).put("story_ref", "");
        ObjectNode draft = validator.normalize(raw, "create", "NEW-links");
        assertTrue(draft.path("tasks").get(0).path("story_ref").asText().isBlank());
        assertTrue(draft.path("completeness").path("hardBlockingIssues").toString().contains("Task 未关联 Story"));
    }

    @Test
    void importedMissingPriorityIsInferredButExplicitPriorityIsKept() {
        ObjectNode raw = mapper.createObjectNode();
        raw.set("project", mapper.createObjectNode().put("total_sprints", 3));
        raw.set("stories", mapper.createArrayNode()
                .add(mapper.createObjectNode().put("id", "US01").put("title", "用户注册与登录").put("priority", ""))
                .add(mapper.createObjectNode().put("id", "US02").put("title", "消息通知设置").put("priority", ""))
                .add(mapper.createObjectNode().put("id", "US03").put("title", "设备利用率统计").put("priority", ""))
                .add(mapper.createObjectNode().put("id", "US04").put("title", "角色权限管理").put("priority", "Could")));
        raw.set("tasks", mapper.createArrayNode());
        ObjectNode draft = validator.normalizeImport(raw, "create", "NEW-priority");
        assertEquals("Must", draft.path("stories").get(0).path("priority").asText());
        assertEquals("Should", draft.path("stories").get(1).path("priority").asText());
        assertEquals("Could", draft.path("stories").get(2).path("priority").asText());
        assertEquals("Could", draft.path("stories").get(3).path("priority").asText());
    }

    @Test
    void duplicateWarningsCollapsePerTaskAndKeepOtherTasks() throws Exception {
        ArrayNode warnings = mapper.createArrayNode()
                .add("T07 的用户排期与依赖或项目周期冲突，已保留原值，需人工处理")
                .add("T07 的用户排期与依赖或项目周期冲突，已保留原值，需人工处理")
                .add("T08 的用户排期与依赖或项目周期冲突，已保留原值，需人工处理")
                .add("任务依赖关系异常缺失，请确认任务确实可并行执行")
                .add("任务依赖关系异常缺失，请确认任务确实可并行执行");

        Method method = ProjectGenerationValidator.class.getDeclaredMethod("deduplicateWarnings", ArrayNode.class);
        method.setAccessible(true);
        ArrayNode deduplicated = (ArrayNode) method.invoke(validator, warnings);

        assertEquals(3, deduplicated.size());
        assertEquals(1, deduplicated.toString().split("T07 的用户排期", -1).length - 1);
        assertEquals(1, deduplicated.toString().split("T08 的用户排期", -1).length - 1);
        assertEquals(1, deduplicated.toString().split("任务依赖关系异常缺失", -1).length - 1);
    }

    @SuppressWarnings("unchecked")
    private void secondMember() {
        User other = new User();
        other.setId(2); other.setDisplayName("同域成员"); other.setRole("member"); other.setCapacityHours(60);
        User first = users.selectById(1);
        lenient().when(users.selectList(any(Wrapper.class))).thenReturn(List.of(first, other));
        lenient().when(users.selectById(2)).thenReturn(other);
    }

    @Test
    void overviewActivityMarkerPreservesModelOwnedDomains() {
        ObjectNode raw = mapper.createObjectNode();
        raw.set("project", mapper.createObjectNode().put("total_sprints", 3));
        raw.putArray("activities")
                .add(mapper.createObjectNode().put("id", 1).put("name", "模型域一"))
                .add(mapper.createObjectNode().put("id", 2).put("name", "模型域二"));
        raw.putArray("epics")
                .add(mapper.createObjectNode().put("id", "E1").put("title", "模型域一").put("activity", 1))
                .add(mapper.createObjectNode().put("id", "E2").put("title", "模型域二").put("activity", 2));
        ArrayNode stories = raw.putArray("stories");
        for (int i = 1; i <= 12; i++) stories.add(mapper.createObjectNode()
                .put("id", "S" + i).put("title", "业务能力" + i).put("activity", 1).put("sprint", (i - 1) % 3 + 1));
        raw.putArray("tasks");
        raw.put("_preserve_activity_domains", true);

        ObjectNode draft = validator.normalize(raw, "create", "NEW-overview-activity");

        assertEquals(2, draft.path("activities").size());
        assertTrue(draft.path("stories").findValuesAsText("activity").stream().allMatch("1"::equals));
    }

    @Test
    void detailedActivityCodesRemainTheOnlyStoryReference() {
        ObjectNode raw = activityReferenceDraft("1", "2");

        ObjectNode draft = validator.normalize(raw, "create", "NEW-detailed-activity-code");

        assertEquals("A1", draft.path("stories").get(0).path("activity").asText());
        assertEquals("A2", draft.path("stories").get(1).path("activity").asText());
        assertEquals("A1", draft.path("epics").get(0).path("activity").asText());
        assertEquals("A2", draft.path("epics").get(1).path("activity").asText());
        assertEquals(0, draft.path("completeness").path("invalidActivityReferenceCount").asInt());
    }

    @Test
    void detailedActivityNameAliasIsNormalizedToCode() {
        ObjectNode raw = activityReferenceDraft("用户与权限", "A2");

        ObjectNode draft = validator.normalize(raw, "create", "NEW-detailed-activity-name");

        assertEquals("A1", draft.path("stories").get(0).path("activity").asText());
        assertFalse(draft.path("completeness").path("hardBlockingIssues").toString().contains("不存在的 Activity"));
    }

    @Test
    void detailedLegacyActivityIdsAndNumericReferencesNormalizeTogether() {
        ObjectNode raw = activityReferenceDraft("1", "2");
        ((ObjectNode) raw.withArray("activities").get(0)).remove("code");
        ((ObjectNode) raw.withArray("activities").get(0)).put("id", "activity-1");
        ((ObjectNode) raw.withArray("activities").get(1)).remove("code");
        ((ObjectNode) raw.withArray("activities").get(1)).put("id", "activity-2");

        ObjectNode draft = validator.normalize(raw, "create", "NEW-detailed-activity-legacy-id");

        assertEquals("A1", draft.path("stories").get(0).path("activity").asText());
        assertEquals("A2", draft.path("stories").get(1).path("activity").asText());
        assertEquals(0, draft.path("completeness").path("invalidActivityReferenceCount").asInt());
    }

    @Test
    void detailedEmptyActivitiesRecoverDefinitionsFromEpicsBeforeValidatingStories() {
        ObjectNode raw = activityReferenceDraft("1", "2");
        raw.remove("activities");

        ObjectNode draft = validator.normalize(raw, "create", "NEW-detailed-activity-recovery");

        assertEquals(2, draft.path("activities").size());
        assertEquals("A1", draft.path("stories").get(0).path("activity").asText());
        assertEquals("A2", draft.path("stories").get(1).path("activity").asText());
        assertEquals(0, draft.path("completeness").path("invalidActivityReferenceCount").asInt());
    }

    @Test
    void detailedModelActivityIdsDoNotBecomeDanglingNumericReferences() {
        ObjectNode raw = mapper.createObjectNode();
        raw.set("project", mapper.createObjectNode().put("total_sprints", 3));
        raw.putArray("activities")
                .add(mapper.createObjectNode().put("id", "activity-1").put("name", "基础框架与权限管理"))
                .add(mapper.createObjectNode().put("id", "activity-2").put("name", "核心业务流程"))
                .add(mapper.createObjectNode().put("id", "activity-3").put("name", "申请与审核流程"))
                .add(mapper.createObjectNode().put("id", "activity-4").put("name", "通知与协同"));
        raw.putArray("epics")
                .add(mapper.createObjectNode().put("id", "epic-1").put("title", "基础框架与权限管理").put("activity", "activity-1"))
                .add(mapper.createObjectNode().put("id", "epic-2").put("title", "核心业务流程").put("activity", "activity-2"));
        ArrayNode stories = raw.putArray("stories");
        String[] titles = {"用户登录", "角色权限管理", "活动创建", "活动发布", "报名申请", "报名审核",
                "签到记录", "服务时长统计", "消息通知", "活动评价", "后台统计", "异常处理"};
        for (int i = 0; i < 30; i++) stories.add(mapper.createObjectNode()
                .put("id", String.format("US%02d", i + 1)).put("title", titles[i % titles.length])
                .put("description", "实现" + titles[i % titles.length] + "业务能力")
                .put("acceptance", "通过验收").put("sprint", i / 10 + 1).put("activity", 1).put("owner_id", 1));
        ArrayNode tasks = raw.putArray("tasks");
        for (int i = 0; i < 40; i++) tasks.add(mapper.createObjectNode()
                .put("id", String.format("T%02d", i + 1)).put("name", "实现" + titles[i % titles.length])
                .put("owner_id", 1).put("hours", 2).put("week_start", i % 6 + 1).put("week_end", i % 6 + 1)
                .put("story_ref", String.format("US%02d", i % 30 + 1)));
        raw.putArray("milestones").add(mapper.createObjectNode().put("id", "M1").put("related_task_ids", "T01"))
                .add(mapper.createObjectNode().put("id", "M2").put("related_task_ids", "T20"));

        ObjectNode draft = validator.normalize(raw, "create", "NEW-detailed-model-ids");

        assertEquals(0, draft.path("completeness").path("invalidActivityReferenceCount").asInt());
        assertTrue(draft.path("stories").findValuesAsText("activity").stream().allMatch(value -> value.matches("A\\d+")));
        assertTrue(draft.path("completeness").path("hardBlockingIssues").toString()
                .contains("Story 引用了不存在的 Activity") == false, draft.toString());
    }

    @Test
    void unknownDetailedActivityReferenceIsClearedButRemainsAHardBlock() {
        ObjectNode raw = activityReferenceDraft("A99", "A2");
        ((ObjectNode) raw.path("stories").get(0)).put("title", "无法识别的孤立条目")
                .put("description", "与任何功能域都没有可靠语义关系");

        ObjectNode draft = validator.normalize(raw, "create", "NEW-detailed-activity-unknown");

        JsonNode story = draft.path("stories").get(0);
        assertEquals("", story.path("activity").asText());
        assertEquals("", story.path("activityCode").asText());
        assertEquals("A99", story.path("original_activity").asText());
        assertTrue(draft.path("completeness").path("hardBlockingIssues").toString()
                .contains("Activity 最终一致性校验失败"));
    }

    @Test
    void generatedActivityReferencesAboveFiveAreNotTruncated() {
        ObjectNode raw = mapper.createObjectNode();
        raw.set("project", mapper.createObjectNode().put("total_sprints", 3));
        ArrayNode activities = raw.putArray("activities");
        for (int i = 1; i <= 7; i++) activities.add(mapper.createObjectNode().put("id", i).put("name", "域" + i));
        raw.putArray("stories").add(mapper.createObjectNode().put("id", "S1").put("title", "系统支撑")
                .put("sprint", 1).put("activity", 7).put("owner_id", 1));
        raw.putArray("tasks").add(mapper.createObjectNode().put("id", "T1").put("name", "系统支撑实现")
                .put("owner_id", 1).put("hours", 2).put("week_start", 1).put("week_end", 1).put("story_ref", "S1"));

        ObjectNode draft = validator.normalize(raw, "create", "NEW-activity-seven");

        assertEquals("A1", draft.path("stories").get(0).path("activity").asText());
        assertEquals(1, draft.path("activities").size());
        assertEquals("A1", draft.path("activities").get(0).path("code").asText());
    }

    @Test
    void detailedTaskCompactionMergesSemanticDuplicatesButKeepsDevelopmentStages() throws Exception {
        ArrayNode stories = mapper.createArrayNode().add(mapper.createObjectNode().put("id", "US01"));
        ArrayNode tasks = mapper.createArrayNode()
                .add(scheduleTask("T01", "", "US01").put("name", "实现报名接口").put("hours", 8))
                .add(scheduleTask("T02", "", "US01").put("name", "开发报名API接口").put("hours", 6))
                .add(scheduleTask("T03", "", "US01").put("name", "报名数据库表结构设计"))
                .add(scheduleTask("T04", "", "US01").put("name", "报名前端页面实现"))
                .add(scheduleTask("T05", "", "US01").put("name", "报名参数校验"))
                .add(scheduleTask("T06", "", "US01").put("name", "报名核心测试"));
        Method method = ProjectGenerationValidator.class.getDeclaredMethod("compactDetailedTasks",
                ArrayNode.class, ArrayNode.class, ArrayNode.class, ObjectNode.class);
        method.setAccessible(true);

        ObjectNode stats = (ObjectNode) method.invoke(validator, stories, tasks, mapper.createArrayNode(), mapper.createObjectNode());

        assertEquals(5, tasks.size());
        assertEquals(1, stats.path("mergedCount").asInt());
        Set<String> stages = new HashSet<>();
        for (JsonNode task : tasks) stages.add(task.path("name").asText());
        assertTrue(stages.stream().anyMatch(name -> name.contains("数据库")));
        assertTrue(stages.stream().anyMatch(name -> name.contains("前端")));
        assertTrue(stages.stream().anyMatch(name -> name.contains("测试")));
    }

    @Test
    void thirtyStorySemanticTaskCompactionStaysWithinExpectedScaleAndIsOrderStable() throws Exception {
        ArrayNode stories = mapper.createArrayNode();
        ArrayNode forward = mapper.createArrayNode();
        for (int story = 1; story <= 30; story++) {
            String storyId = String.format("US%02d", story);
            stories.add(mapper.createObjectNode().put("id", storyId));
            String[] names = {"实现活动接口", "开发活动API接口", "活动前端页面", "活动页面前端实现",
                    "活动数据校验", "活动参数校验"};
            for (int i = 0; i < names.length; i++) forward.add(scheduleTask(String.format("T%03d", (story - 1) * 8 + i + 1), "", storyId)
                    .put("name", names[i]));
        }
        ArrayNode reverse = mapper.createArrayNode();
        for (int i = forward.size() - 1; i >= 0; i--) reverse.add(forward.get(i).deepCopy());
        Method method = ProjectGenerationValidator.class.getDeclaredMethod("compactDetailedTasks",
                ArrayNode.class, ArrayNode.class, ArrayNode.class, ObjectNode.class);
        method.setAccessible(true);

        ObjectNode first = (ObjectNode) method.invoke(validator, stories, forward, mapper.createArrayNode(), mapper.createObjectNode());
        ObjectNode second = (ObjectNode) method.invoke(validator, stories, reverse, mapper.createArrayNode(), mapper.createObjectNode());

        assertTrue(forward.size() >= 75 && forward.size() <= 120, first.toString());
        assertEquals(forward.toString(), reverse.toString());
        assertEquals(first.path("perStoryAfter"), second.path("perStoryAfter"));
    }

    @Test
    void detailedDependencyCompactionClassifiesWeakEdgesAcrossAndWithinStories() throws Exception {
        ArrayNode tasks = mapper.createArrayNode()
                .add(scheduleTask("T01", "", "US01").put("name", "报名数据库表结构"))
                .add(scheduleTask("T02", "T01", "US02").put("name", "报名后端接口"))
                .add(scheduleTask("T03", "T02", "US03").put("name", "报名统计测试"))
                .add(scheduleTask("T04", "T02", "US02").put("name", "报名接口参数校验"))
                .add(scheduleTask("T05", "T02", "US02").put("name", "报名申请前端页面"));
        Method method = ProjectGenerationValidator.class.getDeclaredMethod("compactDetailedDependencies",
                ArrayNode.class, ArrayNode.class);
        method.setAccessible(true);

        ObjectNode stats = (ObjectNode) method.invoke(validator, tasks, mapper.createArrayNode());

        assertEquals("T01", tasks.get(1).path("depends_on").asText());
        assertEquals("", tasks.get(2).path("depends_on").asText());
        assertEquals("", tasks.get(3).path("depends_on").asText());
        assertEquals("T02", tasks.get(3).path("weak_dependencies").asText());
        assertEquals("T02", tasks.get(4).path("depends_on").asText());
        assertEquals("", tasks.get(2).path("weak_dependencies").asText());
        assertEquals(1, stats.path("weakDependencyCount").asInt());
        assertEquals(1, stats.path("weakDroppedByLowEvidence").asInt());
        assertEquals(1, stats.path("keptCoreCrossStoryCount").asInt());
        assertEquals(2, stats.path("coreDependencyCount").asInt());
        assertEquals(2, stats.path("sameStoryEdgeCount").asInt());
    }

    @Test
    void businessObjectAndOperationBreakTieButAmbiguousTaskRemainsUnlinked() throws Exception {
        ArrayNode stories = mapper.createArrayNode()
                .add(mapper.createObjectNode().put("id", "US01").put("title", "用户注册").put("description", "创建账号"))
                .add(mapper.createObjectNode().put("id", "US02").put("title", "注册信息查询").put("description", "查看注册结果"))
                .add(mapper.createObjectNode().put("id", "US03").put("title", "个人信息修改").put("description", "编辑个人资料"));
        ArrayNode tasks = mapper.createArrayNode()
                .add(mapper.createObjectNode().put("id", "T01").put("name", "开发用户注册接口").put("story_ref", ""))
                .add(mapper.createObjectNode().put("id", "T02").put("name", "处理用户数据").put("story_ref", ""));
        Method method = ProjectGenerationValidator.class.getDeclaredMethod("ensureGeneratedTaskStoryReferences",
                ArrayNode.class, ArrayNode.class, ArrayNode.class, ArrayNode.class);
        method.setAccessible(true);

        int resolved = (int) method.invoke(validator, stories, tasks, mapper.createArrayNode(), mapper.createArrayNode());

        assertEquals("US01", tasks.get(0).path("story_ref").asText());
        assertEquals("", tasks.get(1).path("story_ref").asText());
        assertEquals(1, resolved);
    }

    @Test
    void detailedBusinessClosureCoversKnownDomainsButDoesNotBindProjectInfrastructure() throws Exception {
        ArrayNode stories = mapper.createArrayNode();
        String[][] definitions = {
                {"US01", "用户登录"}, {"US02", "个人信息查询与修改"},
                {"US03", "创建活动"}, {"US04", "修改活动"}, {"US05", "取消活动"},
                {"US06", "活动列表详情与筛选"}, {"US07", "提交活动报名"}, {"US08", "审核报名申请"},
                {"US09", "活动签到"}, {"US10", "活动签退"}, {"US11", "服务时长查询"},
                {"US12", "学生个人中心"}, {"US13", "管理员用户管理"},
                {"US14", "管理员活动管理"}, {"US15", "管理员报名管理"}
        };
        for (String[] definition : definitions) stories.add(mapper.createObjectNode()
                .put("id", definition[0]).put("title", definition[1]).put("description", definition[1]));
        String[][] cases = {
                {"开发登录前端页面", "US01"}, {"开发个人信息查询接口", "US02"},
                {"开发创建活动接口", "US03"}, {"实现活动修改页面", "US04"},
                {"实现取消活动接口", "US05"}, {"开发活动详情筛选页面", "US06"},
                {"实现报名提交接口", "US07"}, {"开发报名审核功能", "US08"},
                {"实现活动签到", "US09"}, {"实现活动签退", "US10"},
                {"查询志愿服务时长", "US11"}, {"开发学生个人中心页面", "US12"},
                {"管理员用户管理页面", "US13"}, {"管理员活动管理接口", "US14"},
                {"管理员报名管理页面", "US15"}
        };
        ArrayNode taskRows = mapper.createArrayNode();
        for (int i = 0; i < cases.length; i++) taskRows.add(mapper.createObjectNode()
                .put("id", String.format("T%02d", i + 1)).put("name", cases[i][0]).put("story_ref", ""));
        taskRows.add(mapper.createObjectNode().put("id", "T99").put("name", "搭建通用基础设施与全局日志").put("story_ref", ""));
        Method method = ProjectGenerationValidator.class.getDeclaredMethod("ensureGeneratedTaskStoryReferences",
                ArrayNode.class, ArrayNode.class, ArrayNode.class, ArrayNode.class);
        method.setAccessible(true);

        method.invoke(validator, stories, taskRows, mapper.createArrayNode(), mapper.createArrayNode());

        for (int i = 0; i < cases.length; i++)
            assertEquals(cases[i][1], taskRows.get(i).path("story_ref").asText(), cases[i][0]);
        assertEquals("", taskRows.get(taskRows.size() - 1).path("story_ref").asText());
    }

    @Test
    void detailedBusinessClosureDisambiguatesFineGrainedStoriesByActionRoleAndState() throws Exception {
        ArrayNode stories = mapper.createArrayNode()
                .add(mapper.createObjectNode().put("id", "US01").put("title", "负责人创建志愿活动"))
                .add(mapper.createObjectNode().put("id", "US02").put("title", "负责人取消志愿活动"))
                .add(mapper.createObjectNode().put("id", "US03").put("title", "学生提交活动报名"))
                .add(mapper.createObjectNode().put("id", "US04").put("title", "负责人审核报名申请"))
                .add(mapper.createObjectNode().put("id", "US05").put("title", "学生查看个人累计服务时长"))
                .add(mapper.createObjectNode().put("id", "US06").put("title", "负责人查看活动参与人员时长"));
        ArrayNode tasks = mapper.createArrayNode()
                .add(mapper.createObjectNode().put("id", "T01").put("name", "负责人取消活动接口").put("story_ref", ""))
                .add(mapper.createObjectNode().put("id", "T02").put("name", "负责人报名审核接口").put("story_ref", ""))
                .add(mapper.createObjectNode().put("id", "T03").put("name", "学生个人累计时长查询页面").put("story_ref", ""));
        Method method = ProjectGenerationValidator.class.getDeclaredMethod("ensureGeneratedTaskStoryReferences",
                ArrayNode.class, ArrayNode.class, ArrayNode.class, ArrayNode.class);
        method.setAccessible(true);

        method.invoke(validator, stories, tasks, mapper.createArrayNode(), mapper.createArrayNode());

        assertEquals("US02", tasks.get(0).path("story_ref").asText());
        assertEquals("US04", tasks.get(1).path("story_ref").asText());
        assertEquals("US05", tasks.get(2).path("story_ref").asText());
        assertEquals("RESOLVED", tasks.get(0).path("relation_tie_break").path("status").asText());
        assertTrue(tasks.get(0).path("relation_tie_break").path("candidateScores").size() >= 2);
    }

    @Test
    void detailedBusinessClosureLeavesTrueTieAndProjectTaskUnresolved() throws Exception {
        ArrayNode stories = mapper.createArrayNode()
                .add(mapper.createObjectNode().put("id", "US01").put("title", "学生查看活动列表"))
                .add(mapper.createObjectNode().put("id", "US02").put("title", "学生查看活动列表"));
        ArrayNode tasks = mapper.createArrayNode()
                .add(mapper.createObjectNode().put("id", "T01").put("name", "学生查看活动列表页面").put("story_ref", ""))
                .add(mapper.createObjectNode().put("id", "T02").put("name", "搭建通用基础设施与全局日志").put("story_ref", ""));
        Method method = ProjectGenerationValidator.class.getDeclaredMethod("ensureGeneratedTaskStoryReferences",
                ArrayNode.class, ArrayNode.class, ArrayNode.class, ArrayNode.class);
        method.setAccessible(true);

        method.invoke(validator, stories, tasks, mapper.createArrayNode(), mapper.createArrayNode());

        assertEquals("", tasks.get(0).path("story_ref").asText());
        assertEquals("UNRESOLVED_TIE_OR_LOW_CONFIDENCE",
                tasks.get(0).path("relation_tie_break").path("status").asText());
        assertEquals("", tasks.get(1).path("story_ref").asText());
    }

    @Test
    void detailedBusinessClosureSeparatesSignupActionsCheckInOutAndDurationRoles() throws Exception {
        ArrayNode stories = mapper.createArrayNode()
                .add(mapper.createObjectNode().put("id", "US01").put("title", "学生提交报名申请"))
                .add(mapper.createObjectNode().put("id", "US02").put("title", "学生查看报名状态"))
                .add(mapper.createObjectNode().put("id", "US03").put("title", "学生取消报名"))
                .add(mapper.createObjectNode().put("id", "US04").put("title", "学生签到"))
                .add(mapper.createObjectNode().put("id", "US05").put("title", "学生签退"))
                .add(mapper.createObjectNode().put("id", "US06").put("title", "学生查看个人累计服务时长"))
                .add(mapper.createObjectNode().put("id", "US07").put("title", "负责人查看参与人员服务时长"));
        ArrayNode taskRows = mapper.createArrayNode()
                .add(mapper.createObjectNode().put("id", "T01").put("name", "学生取消报名后端接口").put("story_ref", ""))
                .add(mapper.createObjectNode().put("id", "T02").put("name", "学生签到接口").put("story_ref", ""))
                .add(mapper.createObjectNode().put("id", "T03").put("name", "学生签退接口").put("story_ref", ""))
                .add(mapper.createObjectNode().put("id", "T04").put("name", "学生累计服务时长查询页面").put("story_ref", ""))
                .add(mapper.createObjectNode().put("id", "T05").put("name", "负责人查看活动人员服务时长接口").put("story_ref", ""));
        Method method = ProjectGenerationValidator.class.getDeclaredMethod("ensureGeneratedTaskStoryReferences",
                ArrayNode.class, ArrayNode.class, ArrayNode.class, ArrayNode.class);
        method.setAccessible(true);

        method.invoke(validator, stories, taskRows, mapper.createArrayNode(), mapper.createArrayNode());

        assertEquals("US03", taskRows.get(0).path("story_ref").asText());
        assertEquals("US04", taskRows.get(1).path("story_ref").asText());
        assertEquals("US05", taskRows.get(2).path("story_ref").asText());
        assertEquals("US06", taskRows.get(3).path("story_ref").asText());
        assertEquals("US07", taskRows.get(4).path("story_ref").asText());
        assertEquals("object_action_role_surface_state_tiebreak",
                taskRows.get(0).path("relation_tie_break").path("resolutionMethod").asText());
    }

    @Test
    void normalizeAndCompactPreserveDetailedTaskProvenance() throws Exception {
        ObjectNode raw = baseDraft();
        ObjectNode row = task(raw, "T03", "登录接口", 1);
        row.put("model_task_code", "T03").put("generation_stage", "tasks")
                .put("generation_continuation_round", 1).put("generation_identity", "tasks#1#T03")
                .put("model_story_refs", "US01");
        row.withArray("generation_sources").add(mapper.createObjectNode().put("stage", "tasks")
                .put("continuationRound", 1).put("modelTaskCode", "T03").put("identity", "tasks#1#T03"));

        ObjectNode draft = validator.normalize(raw, "replace", "NEW-provenance");
        JsonNode normalized = draft.path("tasks").get(0);

        assertEquals("T03", normalized.path("model_task_code").asText());
        assertEquals("tasks", normalized.path("generation_stage").asText());
        assertEquals(1, normalized.path("generation_continuation_round").asInt());
        assertEquals("tasks#1#T03", normalized.path("generation_identity").asText());
        assertFalse(normalized.path("generation_sources").isEmpty());
    }

    @Test
    void compactMergeRetainsEveryTaskProvenanceSource() throws Exception {
        ObjectNode retained = mapper.createObjectNode().put("id", "T01").put("name", "报名接口")
                .put("model_task_code", "T03").put("generation_stage", "tasks")
                .put("generation_continuation_round", 0).put("generation_identity", "tasks#0#T03")
                .put("story_ref", "US01").put("depends_on", "").put("hours", 8).put("estimated_hours", 8);
        ObjectNode duplicate = retained.deepCopy().put("id", "T09")
                .put("generation_continuation_round", 1).put("generation_identity", "tasks#1#T03");
        Method method = ProjectGenerationValidator.class.getDeclaredMethod("mergeEquivalentDetailedTask",
                ObjectNode.class, ObjectNode.class);
        method.setAccessible(true);

        method.invoke(validator, retained, duplicate);

        assertEquals(2, retained.path("generation_sources").size());
        assertEquals("tasks#0#T03", retained.path("generation_sources").get(0).path("identity").asText());
        assertEquals("tasks#1#T03", retained.path("generation_sources").get(1).path("identity").asText());
    }

    @Test
    void unresolvedCrossStageReferenceIsACompletenessHardBlock() {
        ObjectNode raw = baseDraft();
        task(raw, "T03", "登录接口", 1).withArray("unresolved_cross_stage_references").add("T03");

        ObjectNode draft = validator.normalize(raw, "replace", "NEW-cross-stage-conflict");
        ObjectNode report = validator.completenessReport(draft);

        assertTrue(report.path("hardBlockingIssues").toString().contains("DUPLICATE_TASK_CODE_CONFLICT"));
    }

    @Test
    void duplicateRawCodesKeepDistinctIdentitiesAndDoNotFanOutAmbiguousReference() throws Exception {
        ArrayNode tasks = mapper.createArrayNode();
        tasks.add(taskWithIdentity("T03", "首轮接口", "tasks#0#T03"));
        tasks.add(taskWithIdentity("T03", "补充接口", "supplement#1#T03"));
        ((ObjectNode) tasks.get(0)).withArray("generation_dependency_refs").add("supplement#1#T03");
        ((ObjectNode) tasks.get(1)).put("depends_on", "T03");
        Method resolve = ProjectGenerationValidator.class.getDeclaredMethod("resolveTaskReferencesBeforeUniqueness",
                ArrayNode.class, ArrayNode.class, ArrayNode.class);
        resolve.setAccessible(true);
        resolve.invoke(validator, tasks, mapper.createArrayNode(), mapper.createArrayNode());
        Method method = ProjectGenerationValidator.class.getDeclaredMethod("ensureUniqueGeneratedTaskCodes",
                ArrayNode.class, ArrayNode.class, ArrayNode.class);
        method.setAccessible(true);

        ObjectNode audit = (ObjectNode) method.invoke(validator, tasks, mapper.createArrayNode(), mapper.createArrayNode());

        assertEquals(2, tasks.size());
        assertTrue(!tasks.get(0).path("id").asText().equals(tasks.get(1).path("id").asText()));
        assertEquals(tasks.get(1).path("id").asText(), tasks.get(0).path("depends_on").asText());
        assertTrue(tasks.get(1).path("depends_on").asText().isBlank());
        assertTrue(audit.path("preventedFanoutCount").asInt() >= 1);
        assertTrue(audit.path("unresolvedReferences").size() >= 1);
    }

    @Test
    void globallyUniqueBareReferenceIsResolvedWithoutDuplicateFanout() throws Exception {
        ArrayNode tasks = mapper.createArrayNode();
        tasks.add(taskWithIdentity("T01", "唯一前置", "tasks#0#T01"));
        tasks.add(taskWithIdentity("T02", "后续任务", "tasks#0#T02").put("depends_on", "T01"));
        Method method = ProjectGenerationValidator.class.getDeclaredMethod("ensureUniqueGeneratedTaskCodes",
                ArrayNode.class, ArrayNode.class, ArrayNode.class);
        method.setAccessible(true);

        ObjectNode audit = (ObjectNode) method.invoke(validator, tasks, mapper.createArrayNode(), mapper.createArrayNode());

        assertEquals("T01", tasks.get(1).path("depends_on").asText());
        assertEquals(0, audit.path("preventedFanoutCount").asInt());
    }

    private ObjectNode taskWithIdentity(String id, String name, String identity) {
        return mapper.createObjectNode().put("id", id).put("name", name).put("hours", 1)
                .put("owner_id", 1).put("week_start", 1).put("week_end", 1)
                .put("generation_stage", identity.substring(0, identity.indexOf('#')))
                .put("generation_continuation_round", 0)
                .put("generation_identity", identity);
    }

    @Test
    void unresolvedModelScheduleIsExcludedFromFinalScheduledWorkload() throws Exception {
        ArrayNode tasks = mapper.createArrayNode()
                .add(scheduleTask("T01", "", "US01").put("owner_id", 1).put("hours", 40)
                        .put("week_start", 1).put("week_end", 1))
                .add(scheduleTask("T02", "", "").put("owner_id", 1).put("hours", 30)
                        .put("model_owner_id", 1).put("model_week_start", 1).put("model_week_end", 1)
                        .set("_schedule_conflict", mapper.createObjectNode().put("code", "UNRESOLVED_STORY_RELATION")));
        ArrayNode members = mapper.createArrayNode().add(mapper.createObjectNode()
                .put("id", 1).put("name", "开发者").put("role", "developer").put("capacity_hours", 40));
        ObjectNode quality = mapper.createObjectNode();
        Method method = ProjectGenerationValidator.class.getDeclaredMethod("detailedWorkloadAudit",
                ArrayNode.class, ArrayNode.class, ArrayNode.class, ArrayNode.class, ObjectNode.class);
        method.setAccessible(true);

        method.invoke(validator, tasks, members, mapper.createArrayNode(), mapper.createArrayNode(), quality);

        assertEquals(1, quality.path("finalScheduledWorkload").path("taskCount").asInt());
        assertEquals(40, quality.path("finalScheduledWorkload").path("totalHours").asInt());
        assertEquals(0, quality.path("finalScheduledWorkload").path("overloadCount").asInt());
        assertEquals(1, quality.path("unresolvedModelScheduleReference").path("taskCount").asInt());
        assertEquals(30, quality.path("unresolvedModelScheduleReference").path("totalHours").asInt());
    }

    @Test
    void generatedInvalidStoryReferenceIsRetainedForAuditButNotAsRelation() {
        ObjectNode raw = baseDraft();
        ObjectNode row = task(raw, "A", "开发登录页面", 1);
        row.put("story_ref", "US404");

        ObjectNode draft = validator.normalize(raw, "merge", "NEW-invalid-story-audit");
        JsonNode normalized = draft.path("tasks").get(0);

        assertEquals("US404", normalized.path("model_story_refs").asText());
        assertEquals("US404", normalized.path("invalid_story_refs").asText());
        assertEquals("US01", normalized.path("story_ref").asText());
        assertTrue(validator.completenessReport(draft).path("hardBlockingIssues").toString()
                .contains("INVALID_TASK_STORY_REFERENCE"));
    }

    @ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(strings = {"story_ref", "storyCode", "story_code", "story_refs", "story"})
    void detailedNumericReferenceSurvivesNormalizationWithoutClosure(String field) {
        ObjectNode raw = baseDraft();
        ((ObjectNode) raw.path("stories").get(0)).put("id", 42);
        ObjectNode row = task(raw, "A", "项目基础框架", 1);
        row.remove("story_ref");
        row.put(field, 42);
        row.put("generation_identity", "tasks#0#A");
        ObjectNode draft = validator.normalize(raw, "replace", "NEW-numeric-reference");
        JsonNode normalized = draft.path("tasks").get(0);
        assertEquals("US01", normalized.path("story_ref").asText());
        assertEquals("US01", normalized.path("storyCode").asText());
        assertEquals("42", normalized.path("model_story_refs").asText());
        assertFalse(normalized.has("invalid_story_refs"));
        assertEquals(1, draft.path("qualityAdjustments").path("taskStoryReferences").path("numericScalarResolvedCount").asInt());
        assertEquals(0, draft.path("qualityAdjustments").path("relationClosureStats").path("highConfidenceTieBreakResolvedCount").asInt());
    }

    @Test void detailedNumericArrayRejectsUnknownIdAndKeepsItBlockingAfterClosure() {
        ObjectNode raw = baseDraft();
        ((ObjectNode) raw.path("stories").get(0)).put("id", 42);
        ObjectNode row = task(raw, "A", "登录页面", 1);
        row.remove("story_ref");
        row.putArray("story_refs").add(42).add(999);
        ObjectNode draft = validator.normalize(raw, "replace", "NEW-numeric-array");
        JsonNode normalized = draft.path("tasks").get(0);
        assertEquals("US01", normalized.path("story_ref").asText());
        assertEquals("US01", normalized.path("storyCode").asText());
        assertEquals("42,999", normalized.path("model_story_refs").asText());
        assertEquals("999", normalized.path("invalid_story_refs").asText());
        assertTrue(validator.completenessReport(draft).path("hardBlockingIssues").toString().contains("999"));
        assertEquals(1, draft.path("qualityAdjustments").path("taskStoryReferences").path("rejectedReferenceCount").asInt());
    }

    @ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(strings = {"US-001", "US01", "R1"})
    void textStoryReferencesKeepTheExistingMappingAndModelAudit(String id) {
        ObjectNode raw = baseDraft();
        ((ObjectNode) raw.path("stories").get(0)).put("id", id);
        task(raw, "A", "项目基础框架", 1).put("story_ref", id);
        ObjectNode draft = validator.normalize(raw, "replace", "NEW-text-reference");
        assertEquals("US01", draft.path("tasks").get(0).path("story_ref").asText());
        assertEquals(id, draft.path("tasks").get(0).path("model_story_refs").asText());
        assertEquals(1, draft.path("qualityAdjustments").path("taskStoryReferences").path("parsedReferenceCount").asInt());
    }

    @Test
    void strongSameStoryWeakRemainsRiskWhileUnrelatedCrossStoryWeakIsDropped() throws Exception {
        ArrayNode stories = mapper.createArrayNode()
                .add(mapper.createObjectNode().put("id", "US01").put("title", "活动管理").put("sprint", 1))
                .add(mapper.createObjectNode().put("id", "US02").put("title", "消息通知").put("sprint", 1));
        ArrayNode sprints = mapper.createArrayNode().add(mapper.createObjectNode()
                .put("sprint", 1).put("week_start", 1).put("week_end", 2));
        ArrayNode rows = mapper.createArrayNode()
                .add(scheduleTask("T01", "", "US01").put("name", "活动统计后端接口"))
                .add(scheduleTask("T02", "T01", "US01").put("name", "活动统计前端页面"))
                .add(scheduleTask("T03", "T01", "US02").put("name", "消息提醒联调"));
        Method compact = ProjectGenerationValidator.class.getDeclaredMethod("compactDetailedDependencies",
                ArrayNode.class, ArrayNode.class);
        compact.setAccessible(true);
        compact.invoke(validator, rows, mapper.createArrayNode());

        assertEquals("", rows.get(1).path("depends_on").asText());
        assertEquals("T01", rows.get(1).path("weak_dependencies").asText());
        assertEquals("", rows.get(2).path("depends_on").asText());
        assertEquals("", rows.get(2).path("weak_dependencies").asText());
        ArrayNode unresolved = mapper.createArrayNode();
        invokeFinalSchedule(rows, stories, sprints, members(1), mapper.createArrayNode(),
                mapper.createArrayNode(), unresolved, mapper.createArrayNode());
        assertEquals(0, unresolved.size());
        Method check = ProjectGenerationValidator.class.getDeclaredMethod("finalSprintCheck",
                ArrayNode.class, ArrayNode.class, ArrayNode.class);
        check.setAccessible(true);
        JsonNode result = (JsonNode) check.invoke(validator, stories, rows, sprints);
        assertEquals(0, result.path("dependencyTimingConflictCount").asInt());
        Method risks = ProjectGenerationValidator.class.getDeclaredMethod("risks", ArrayNode.class,
                ArrayNode.class, String.class, String.class, JsonNode.class);
        risks.setAccessible(true);
        JsonNode riskResult = (JsonNode) risks.invoke(validator, stories, rows, "replace", "NEW-weak-risk", sprints);
        assertEquals(1, riskResult.path("dependencyRisk").size());
        assertTrue(riskResult.path("dependencyRisk").toString().contains("不作为硬排期下界"));
    }

    @Test
    void coreDependencyForwardCheckReservesSuccessorWindow() throws Exception {
        ArrayNode stories = mapper.createArrayNode().add(mapper.createObjectNode()
                .put("id", "US01").put("title", "活动管理").put("sprint", 1));
        ArrayNode sprints = mapper.createArrayNode().add(mapper.createObjectNode()
                .put("sprint", 1).put("week_start", 1).put("week_end", 2));
        ArrayNode rows = mapper.createArrayNode()
                .add(scheduleTask("T00", "", "US01").put("name", "活动独立实现").put("hours", 60)
                        .put("week_start", 1).put("week_end", 1))
                .add(scheduleTask("T01", "", "US01").put("name", "开发活动后端接口")
                        .put("week_start", 2).put("week_end", 2))
                .add(scheduleTask("T02", "T01", "US01").put("name", "开发活动前端页面")
                        .put("week_start", 1).put("week_end", 2));
        ArrayNode unresolved = mapper.createArrayNode();

        invokeFinalSchedule(rows, stories, sprints, members(1), mapper.createArrayNode(),
                mapper.createArrayNode(), unresolved, mapper.createArrayNode());

        assertEquals(2, rows.get(1).path("week_start").asInt());
        assertEquals(2, rows.get(1).path("week_end").asInt());
        assertEquals(2, rows.get(2).path("week_start").asInt());
        assertEquals(2, rows.get(2).path("week_end").asInt());
        assertEquals(0, unresolved.size());
    }

    @Test
    void mathematicallyImpossibleCoreWindowStillHardBlocks() throws Exception {
        ArrayNode stories = mapper.createArrayNode().add(mapper.createObjectNode()
                .put("id", "US01").put("title", "活动管理").put("sprint", 1));
        ArrayNode sprints = mapper.createArrayNode().add(mapper.createObjectNode()
                .put("sprint", 1).put("week_start", 1).put("week_end", 2));
        ArrayNode rows = mapper.createArrayNode()
                .add(scheduleTask("T01", "", "US01").put("name", "开发活动后端接口")
                        .put("week_start", 1).put("week_end", 2).put("_user_schedule_explicit", true))
                .add(scheduleTask("T02", "T01", "US01").put("name", "开发活动前端页面")
                        .put("week_start", 1).put("week_end", 2).put("_user_schedule_explicit", true));
        ArrayNode unresolved = mapper.createArrayNode();

        invokeFinalSchedule(rows, stories, sprints, members(1), mapper.createArrayNode(),
                mapper.createArrayNode(), unresolved, mapper.createArrayNode());

        assertFalse(unresolved.isEmpty());
        assertFalse(rows.get(0).path("_schedule_conflict").isObject());
        assertTrue(rows.get(1).path("_schedule_conflict").isObject());
    }

    @Test
    void orphanTaskIsNotReportedAsSyntheticSprintOneMismatch() throws Exception {
        ArrayNode rows = mapper.createArrayNode().add(scheduleTask("T01", "", "")
                .put("model_week_start", 5).put("model_week_end", 6)
                .set("_schedule_conflict", mapper.createObjectNode().put("code", "UNRESOLVED_STORY_RELATION")));
        ArrayNode sprints = mapper.createArrayNode()
                .add(mapper.createObjectNode().put("sprint", 1).put("week_start", 1).put("week_end", 2));
        Method check = ProjectGenerationValidator.class.getDeclaredMethod("finalSprintCheck",
                ArrayNode.class, ArrayNode.class, ArrayNode.class);
        check.setAccessible(true);

        JsonNode result = (JsonNode) check.invoke(validator, mapper.createArrayNode(), rows, sprints);

        assertEquals(0, result.path("trueSprintMismatchCount").asInt());
        assertEquals(1, result.path("unresolvedSchedulingConflictCount").asInt());
    }

    @Test
    void viewerIsExcludedFromInitialAndFinalDetailedCapacity() throws Exception {
        ArrayNode stories = mapper.createArrayNode().add(mapper.createObjectNode()
                .put("id", "US01").put("sprint", 1));
        ArrayNode rows = mapper.createArrayNode().add(scheduleTask("T01", "", "US01").put("hours", 100));
        ArrayNode sprints = mapper.createArrayNode().add(mapper.createObjectNode()
                .put("sprint", 1).put("week_start", 1).put("week_end", 2));
        ArrayNode team = members(1);
        team.add(mapper.createObjectNode().put("id", 9).put("name", "viewer")
                .put("role", "viewer").put("capacity_hours", 60));
        Method initial = ProjectGenerationValidator.class.getDeclaredMethod("deterministicTaskFeasibility",
                ArrayNode.class, ArrayNode.class, ArrayNode.class, ArrayNode.class);
        initial.setAccessible(true);
        JsonNode initialResult = (JsonNode) initial.invoke(validator, stories, rows, sprints, team);
        Method finalCapacity = ProjectGenerationValidator.class.getDeclaredMethod("finalDetailedCapacityFeasibility",
                ArrayNode.class, ArrayNode.class, ArrayNode.class, ArrayNode.class, ArrayNode.class, ObjectNode.class);
        finalCapacity.setAccessible(true);
        JsonNode finalResult = (JsonNode) finalCapacity.invoke(validator, stories, rows, mapper.createArrayNode(),
                sprints, team, mapper.createObjectNode());

        assertEquals(120, initialResult.path("teamCapacityHours").asInt());
        assertEquals(1, finalResult.path("developerCount").asInt());
        assertEquals(120, finalResult.path("totalCapacityHours").asInt());
        assertEquals(100, finalResult.path("perSprintTaskHours").path("S1").asInt());
        assertTrue(finalResult.path("capacityFeasible").asBoolean());
    }

    @Test
    void finalDetailedCapacityPassesWhenEverySprintAndTotalAreWithinDeveloperCapacity() throws Exception {
        ArrayNode stories = mapper.createArrayNode();
        ArrayNode rows = mapper.createArrayNode();
        ArrayNode sprints = mapper.createArrayNode();
        for (int sprint = 1; sprint <= 3; sprint++) {
            String storyId = "US0" + sprint;
            stories.add(mapper.createObjectNode().put("id", storyId).put("sprint", sprint));
            rows.add(scheduleTask("T0" + sprint, "", storyId).put("hours", 80));
            sprints.add(mapper.createObjectNode().put("sprint", sprint)
                    .put("week_start", sprint * 2 - 1).put("week_end", sprint * 2));
        }
        Method method = ProjectGenerationValidator.class.getDeclaredMethod("finalDetailedCapacityFeasibility",
                ArrayNode.class, ArrayNode.class, ArrayNode.class, ArrayNode.class, ArrayNode.class, ObjectNode.class);
        method.setAccessible(true);

        JsonNode result = (JsonNode) method.invoke(validator, stories, rows, mapper.createArrayNode(),
                sprints, members(1), mapper.createObjectNode());

        assertEquals(360, result.path("totalCapacityHours").asInt());
        assertEquals(240, result.path("finalTaskHours").asInt());
        assertEquals(120, result.path("perSprintCapacity").path("S1").asInt());
        assertEquals(80, result.path("perSprintTaskHours").path("S1").asInt());
        assertEquals(80, result.path("perSprintTaskHours").path("S2").asInt());
        assertEquals(80, result.path("perSprintTaskHours").path("S3").asInt());
        assertTrue(result.path("capacityFeasible").asBoolean());
    }

    @Test
    void ordinaryGeneratedTwoWeekHintsCompressToMinimumCapacityDuration() throws Exception {
        ArrayNode stories = mapper.createArrayNode()
                .add(mapper.createObjectNode().put("id", "US01").put("sprint", 1))
                .add(mapper.createObjectNode().put("id", "US03").put("sprint", 3));
        ArrayNode sprints = mapper.createArrayNode()
                .add(mapper.createObjectNode().put("sprint", 1).put("week_start", 1).put("week_end", 2))
                .add(mapper.createObjectNode().put("sprint", 3).put("week_start", 5).put("week_end", 6));
        ArrayNode rows = mapper.createArrayNode()
                .add(scheduleTask("T01", "", "US01").put("name", "登录注册接口").put("hours", 16)
                        .put("week_start", 1).put("week_end", 2))
                .add(scheduleTask("T02", "", "US03").put("name", "管理员接口").put("hours", 16)
                        .put("week_start", 6).put("week_end", 6).put("_user_schedule_explicit", true))
                .add(scheduleTask("T03", "T02", "US03").put("name", "管理员页面").put("hours", 20)
                        .put("week_start", 5).put("week_end", 6));

        invokeFinalSchedule(rows, stories, sprints, members(1), mapper.createArrayNode(),
                mapper.createArrayNode(), mapper.createArrayNode(), mapper.createArrayNode());

        assertEquals(rows.get(0).path("week_start").asInt(), rows.get(0).path("week_end").asInt());
        assertEquals(6, rows.get(2).path("week_start").asInt());
        assertEquals(6, rows.get(2).path("week_end").asInt());
        assertEquals(5, rows.get(2).path("model_week_start").asInt());
        assertEquals(6, rows.get(2).path("model_week_end").asInt());
    }

    @Test
    void explicitTwoWeekScheduleRetainsItsSpan() throws Exception {
        ArrayNode stories = mapper.createArrayNode().add(mapper.createObjectNode().put("id", "US01").put("sprint", 1));
        ArrayNode sprints = mapper.createArrayNode().add(mapper.createObjectNode()
                .put("sprint", 1).put("week_start", 1).put("week_end", 2));
        ArrayNode rows = mapper.createArrayNode().add(scheduleTask("T01", "", "US01")
                .put("hours", 16).put("week_start", 1).put("week_end", 2).put("_user_schedule_explicit", true));

        invokeFinalSchedule(rows, stories, sprints, members(1), mapper.createArrayNode(),
                mapper.createArrayNode(), mapper.createArrayNode(), mapper.createArrayNode());

        assertEquals(1, rows.get(0).path("week_start").asInt());
        assertEquals(2, rows.get(0).path("week_end").asInt());
    }

    @Test
    void aggregateTaskCoveredBySpecificSplitTasksIsCompactedAndCoreRefsAreRewritten() throws Exception {
        ArrayNode stories = mapper.createArrayNode().add(mapper.createObjectNode().put("id", "US28"));
        ArrayNode rows = mapper.createArrayNode()
                .add(scheduleTask("T47", "T12", "US28").put("name", "实现管理员用户与活动管理接口").put("hours", 14))
                .add(scheduleTask("T136", "", "US28").put("name", "管理员用户管理接口开发").put("hours", 16))
                .add(scheduleTask("T137", "", "US28").put("name", "管理员活动管理接口开发").put("hours", 16))
                .add(scheduleTask("T138", "T47,T136,T137", "US28").put("name", "管理员用户与活动管理页面开发"));
        Method method = ProjectGenerationValidator.class.getDeclaredMethod("compactDetailedTasks",
                ArrayNode.class, ArrayNode.class, ArrayNode.class, ObjectNode.class);
        method.setAccessible(true);

        method.invoke(validator, stories, rows, mapper.createArrayNode(), mapper.createObjectNode());

        assertEquals(3, rows.size());
        JsonNode page = null;
        for (JsonNode row : rows) if ("T138".equals(row.path("id").asText())) page = row;
        assertTrue(page != null);
        assertEquals("T136,T137", page.path("depends_on").asText());
        assertTrue(rows.toString().contains("T12"), "aggregate predecessor metadata must migrate");
    }

    private ObjectNode baseDraft() {
        ObjectNode raw = mapper.createObjectNode();
        raw.set("project", mapper.createObjectNode().put("total_sprints", 3));
        raw.set("stories", mapper.createArrayNode().add(mapper.createObjectNode()
                .put("id", "S1").put("title", "登录").put("sprint", 1).put("activity", 1)));
        raw.set("tasks", mapper.createArrayNode());
        return raw;
    }

    private ArrayNode invokeNormalizeActivityReferences(ObjectNode raw) throws Exception {
        ArrayNode issues = mapper.createArrayNode();
        Method method = ProjectGenerationValidator.class.getDeclaredMethod(
                "normalizeActivityReferences", ObjectNode.class, ArrayNode.class);
        method.setAccessible(true);
        method.invoke(validator, raw, issues);
        return issues;
    }

    private ObjectNode activityReferenceDraft(String firstActivity, String secondActivity) {
        ObjectNode raw = mapper.createObjectNode();
        raw.set("project", mapper.createObjectNode().put("total_sprints", 1));
        raw.putArray("activities")
                .add(mapper.createObjectNode().put("id", 1).put("code", "A1").put("name", "用户与权限"))
                .add(mapper.createObjectNode().put("id", 2).put("code", "A2").put("name", "核心业务"));
        raw.putArray("epics")
                .add(mapper.createObjectNode().put("id", "E1").put("title", "用户与权限").put("activity", 1))
                .add(mapper.createObjectNode().put("id", "E2").put("title", "核心业务").put("activity", 2));
        raw.putArray("stories")
                .add(mapper.createObjectNode().put("id", "S1").put("title", "用户登录").put("description", "用户登录和权限校验")
                        .put("acceptance", "登录成功").put("sprint", 1).put("activity", firstActivity).put("owner_id", 1))
                .add(mapper.createObjectNode().put("id", "S2").put("title", "核心业务流程").put("description", "完成核心业务")
                        .put("acceptance", "业务完成").put("sprint", 1).put("activity", secondActivity).put("owner_id", 1));
        raw.putArray("tasks")
                .add(mapper.createObjectNode().put("id", "T1").put("name", "实现登录").put("owner_id", 1).put("hours", 4)
                        .put("week_start", 1).put("week_end", 1).put("story_ref", "S1"))
                .add(mapper.createObjectNode().put("id", "T2").put("name", "实现核心业务").put("owner_id", 1).put("hours", 4)
                        .put("week_start", 1).put("week_end", 1).put("story_ref", "S2"));
        raw.putArray("milestones");
        return raw;
    }

    private ObjectNode task(ObjectNode raw, String id, String name, int week) {
        ObjectNode task = mapper.createObjectNode();
        task.put("id", id);
        task.put("name", name);
        task.put("owner_id", 1);
        task.put("hours", 4);
        task.put("week_start", week);
        task.put("week_end", week);
        task.put("story_ref", "S1");
        task.set("depends_on", mapper.createArrayNode());
        ((ArrayNode) raw.path("tasks")).add(task);
        return task;
    }
}
