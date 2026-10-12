package com.aicap.generation;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;

import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

class StoryCountControlTest {
    private final ObjectMapper mapper = new ObjectMapper();

    @Test void parsesBoundedAndOneSidedRequests() {
        assertEquals(new StoryCountControl.Limit(30, 28, 32, StoryCountControl.ConstraintType.SOFT_TARGET), StoryCountControl.parse("用户故事数量希望在30条左右"));
        assertEquals(new StoryCountControl.Limit(30, 28, 32, StoryCountControl.ConstraintType.SOFT_TARGET), StoryCountControl.parse("约30条Story"));
        assertEquals(new StoryCountControl.Limit(30, 28, 32), StoryCountControl.parse("Story 28-32条"));
        assertEquals(100, StoryCountControl.parse("至少30条用户故事").maximum());
        assertEquals(100, StoryCountControl.parse("Story至少30条").maximum());
        assertEquals(30, StoryCountControl.parse("最多30条Story").maximum());
        assertEquals(25, StoryCountControl.parse("大约25条用户故事").target());
        assertNull(StoryCountControl.parse("计划六周，三次迭代"));
        assertEquals(new StoryCountControl.Limit(30, 28, 32, StoryCountControl.ConstraintType.SOFT_TARGET), StoryCountControl.parse(
                "自动补全用户故事、开发任务、负责人、工时、W1-W6排期。用户故事数量希望在30条左右"));
        assertEquals(StoryCountControl.ConstraintType.HARD_CONSTRAINT,
                StoryCountControl.parse("Story必须控制在28-32条").constraintType());
        assertEquals(StoryCountControl.ConstraintType.HARD_CONSTRAINT,
                StoryCountControl.parse("最多32条Story").constraintType());
        assertEquals(StoryCountControl.ConstraintType.HARD_CONSTRAINT,
                StoryCountControl.parse("至少25条用户故事").constraintType());
    }

    private ObjectNode draft(int count) {
        ObjectNode raw = mapper.createObjectNode();
        var stories = raw.putArray("stories");
        var tasks = raw.putArray("tasks");
        for (int i = 1; i <= count; i++) {
            String id = String.format("US%02d", i);
            stories.add(mapper.createObjectNode().put("id", id).put("title", "消息功能" + i)
                    .put("description", "第" + i + "项需求").put("acceptance", "验收" + i)
                    .put("activity", (i % 3) + 1).put("sprint", (i % 3) + 1)
                    .put("priority", i == 1 ? "Must" : "Should"));
            tasks.add(mapper.createObjectNode().put("id", "T" + i).put("story_ref", id));
        }
        return raw;
    }

    @Test void consolidatesFortyTwoWithoutOrphaningTasksOrCoreFunction() {
        ObjectNode raw = draft(42);
        for (int i = 30; i < 42; i++) {
            var source = raw.path("stories").get(i - 29);
            var duplicate = (ObjectNode) raw.path("stories").get(i);
            duplicate.put("title", source.path("title").asText());
            duplicate.put("description", source.path("description").asText());
            duplicate.put("acceptance", source.path("acceptance").asText());
        }
        var result = StoryCountControl.compress(raw, StoryCountControl.parse("用户故事约30条"), "必须实现消息功能1", Set.of());
        assertEquals(42, result.before());
        assertEquals(30, result.after());
        assertEquals(12, result.merged());
        assertEquals(12, result.duplicates());
        assertTrue(raw.path("stories").toString().contains("消息功能1"));
        Set<String> ids = new java.util.HashSet<>();
        raw.path("stories").forEach(s -> ids.add(s.path("id").asText()));
        raw.path("tasks").forEach(t -> assertTrue(ids.contains(t.path("story_ref").asText()), t.toString()));
        var report = mapper.createObjectNode();
        result.append(report, StoryCountControl.parse("约30条Story"));
        assertEquals(42, report.path("storyCountBeforeCompression").asInt());
    }

    @Test void mergesSimilarStoriesAndPreservesAcceptanceAndTaskReferences() {
        ObjectNode raw = draft(3);
        ((ObjectNode) raw.path("stories").get(0)).put("title", "学生查看预约状态");
        ((ObjectNode) raw.path("stories").get(1)).put("title", "学生查看预约状态");
        ((ObjectNode) raw.path("stories").get(2)).put("title", "学生查看预约状态");
        raw.path("stories").forEach(s -> { ((ObjectNode) s).put("activity", 1); ((ObjectNode) s).put("sprint", 1); });
        ((ObjectNode) raw.path("stories").get(1)).put("description", "学生查看预约的当前状态").put("acceptance", "显示预约状态");
        ((ObjectNode) raw.path("stories").get(0)).put("description", "学生查看预约的当前状态").put("acceptance", "显示预约状态");
        ((ObjectNode) raw.path("stories").get(2)).put("description", "学生查看预约的当前状态").put("acceptance", "显示预约状态");
        var result = StoryCountControl.compress(raw, new StoryCountControl.Limit(1, 1, 1), "", Set.of());
        assertEquals(1, result.after());
        assertTrue(result.duplicates() > 0);
        assertTrue(raw.path("stories").get(0).path("acceptance").asText().contains("显示预约状态"));
        String retained = raw.path("stories").get(0).path("id").asText();
        raw.path("tasks").forEach(t -> assertEquals(retained, t.path("story_ref").asText()));
    }

    @Test void importedStoriesAreNeverRemovedEvenWhenTheyExceedLimit() {
        ObjectNode raw = draft(4);
        var result = StoryCountControl.compress(raw, new StoryCountControl.Limit(2, 1, 2), "", Set.of("US01", "US02", "US03", "US04"));
        assertEquals(4, result.after());
        assertFalse(result.notes().isEmpty());
    }

    @Test void aiStoryCanMergeIntoImportedStoryButNotViceVersa() {
        ObjectNode raw = draft(3);
        ((ObjectNode) raw.path("stories").get(2)).put("activity", raw.path("stories").get(0).path("activity").asInt());
        ((ObjectNode) raw.path("stories").get(2)).put("sprint", raw.path("stories").get(0).path("sprint").asInt());
        var imported = raw.path("stories").get(0);
        ((ObjectNode) raw.path("stories").get(2)).put("title", imported.path("title").asText())
                .put("description", imported.path("description").asText())
                .put("acceptance", imported.path("acceptance").asText());
        var result = StoryCountControl.compress(raw, new StoryCountControl.Limit(2, 1, 2), "", Set.of("US01", "US02"));
        assertEquals(2, result.after());
        assertTrue(raw.path("stories").toString().contains("US01"));
        assertTrue(raw.path("stories").toString().contains("US02"));
    }

    @Test void reportsPeakCountAcrossInitialAndSupplementPasses() {
        var first = new StoryCountControl.Result(28, 28, 0, 0, java.util.List.of());
        var second = new StoryCountControl.Result(42, 30, 12, 2, java.util.List.of("补全后合并"));
        var combined = first.followedBy(second);
        assertEquals(42, combined.before());
        assertEquals(30, combined.after());
        assertEquals(12, combined.merged());
    }

    @Test void independentStoriesStayDistinctAndSoftTargetAllowsOutOfRangeDraft() {
        ObjectNode raw = draft(42);
        var limit = StoryCountControl.parse("用户故事数量希望在30条左右");
        assertEquals(42, StoryCountControl.compress(raw, limit, "", Set.of()).after());
        assertDoesNotThrow(() -> StoryCountControl.requireValidCount(raw, limit, Set.of()));
        assertEquals(30, StoryCountControl.compress(draft(30), limit, "", Set.of()).after());
    }

    @Test void softTargetAllowsThirtyThirtyFiveAndFortyTwoWithWarningsOnlyWhenOutsidePreference() {
        var limit=StoryCountControl.parse("希望30条左右的用户故事");
        for(int count:new int[]{30,35,42}) {
            ObjectNode raw=draft(count);
            var result=StoryCountControl.compress(raw,limit,"",Set.of());
            assertDoesNotThrow(()->StoryCountControl.requireValidCount(raw,limit,Set.of()));
            ObjectNode report=mapper.createObjectNode();result.append(report,limit);
            assertEquals("SOFT_TARGET",report.path("constraintType").asText());
            assertEquals(count,report.path("actualStoryCount").asInt());
            if(count==30) assertTrue(report.path("storyCountWarning").isNull());
            else assertTrue(report.path("storyCountWarning").asText().contains("保留独立业务功能"));
        }
    }

    @Test void softTargetRemovesDuplicatesButDoesNotForceIndependentStoriesDownToPreferredMaximum() {
        ObjectNode raw=draft(42);
        ObjectNode duplicate=(ObjectNode)raw.path("stories").get(41);
        JsonNode original=raw.path("stories").get(0);
        duplicate.put("title",original.path("title").asText())
                .put("description",original.path("description").asText())
                .put("acceptance",original.path("acceptance").asText());
        var result=StoryCountControl.compress(raw,StoryCountControl.parse("希望30条左右"),"",Set.of());
        assertEquals(41,result.after());
        assertEquals(1,result.duplicates());
    }

    @Test void explicitHardConstraintsStillRejectOutOfRangeCounts() {
        ObjectNode range=draft(42);var rangeLimit=StoryCountControl.parse("Story必须控制在28-32条");
        assertThrows(IllegalStateException.class,()->StoryCountControl.requireValidCount(range,rangeLimit,Set.of()));
        ObjectNode maximum=draft(33);var maximumLimit=StoryCountControl.parse("最多32条Story");
        assertThrows(IllegalStateException.class,()->StoryCountControl.requireValidCount(maximum,maximumLimit,Set.of()));
        ObjectNode minimum=draft(20);var minimumLimit=StoryCountControl.parse("至少25条用户故事");
        assertThrows(IllegalStateException.class,()->StoryCountControl.requireValidCount(minimum,minimumLimit,Set.of()));
    }

    @Test void distinctBusinessActionsDoNotMergeWithinOneActivity() {
        ObjectNode raw = draft(4);
        String[] titles = {"提交预约", "取消预约", "排班创建", "排班修改"};
        for (int i = 0; i < 4; i++) {
            ObjectNode story = (ObjectNode) raw.path("stories").get(i);
            story.put("title", titles[i]).put("description", "预约排班流程").put("acceptance", "操作应成功")
                    .put("activity", 1).put("sprint", 1);
        }
        assertEquals(4, StoryCountControl.compress(raw, new StoryCountControl.Limit(2, 2, 2), "", Set.of()).after());
    }

    @Test void similarTitlesWithoutBusinessEvidenceStaySeparate() {
        ObjectNode raw = draft(3);
        String[] titles = {"学生查看预约状态", "学生查看预约状态记录", "学生查看预约状态详情"};
        for (int i = 0; i < titles.length; i++) {
            ObjectNode story = (ObjectNode) raw.path("stories").get(i);
            story.put("title", titles[i]).put("description", "").put("acceptance", "")
                    .put("activity", 1).put("sprint", 1);
        }
        assertEquals(3, StoryCountControl.compress(raw, new StoryCountControl.Limit(2, 2, 2), "", Set.of()).after());
    }

    @Test void reachingMaximumStopsSimilarityMergesAndNeverDropsBelowMinimum() {
        ObjectNode raw = draft(33);
        ObjectNode duplicate = (ObjectNode) raw.path("stories").get(32);
        var original = raw.path("stories").get(0);
        duplicate.put("title", original.path("title").asText())
                .put("description", original.path("description").asText())
                .put("acceptance", original.path("acceptance").asText());
        var limit = new StoryCountControl.Limit(30, 28, 32);
        assertEquals(32, StoryCountControl.compress(raw, limit, "", Set.of()).after());
        assertEquals(32, StoryCountControl.compress(raw, limit, "", Set.of()).after());
        StoryCountControl.requireValidCount(raw, limit, Set.of());
    }
}
