package com.aicap.generation;

import com.aicap.entity.User;
import com.aicap.mapper.StoryMapper;
import com.aicap.mapper.TaskMapper;
import com.aicap.mapper.UserMapper;
import com.baomidou.mybatisplus.core.conditions.Wrapper;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.nullable;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ProjectGenerationImportSprintTest {
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
        member.setCapacityHours(80);
        when(stories.selectList(nullable(Wrapper.class))).thenReturn(List.of());
        when(tasks.selectList(nullable(Wrapper.class))).thenReturn(List.of());
        when(users.selectList(any(Wrapper.class))).thenReturn(List.of(member));
        lenient().when(users.selectById(1)).thenReturn(member);
        validator = new ProjectGenerationValidator(stories, tasks, users, mapper);
    }

    @Test
    void thirtyStoriesFollowTasksAcrossThreeSprintsInsteadOfDefaultingToOne() {
        ObjectNode raw = base();
        for (int i = 1; i <= 30; i++) {
            String story = "S" + i;
            story(raw, story, "业务功能" + i, null);
            int week = (i - 1) / 5 + 1;
            task(raw, "K" + i, story, week, week, 8);
        }

        ObjectNode draft = validator.normalizeImport(raw, "create", "NEW-import");

        assertEquals("[10,10,10]", draft.path("completeness").path("storyCountsBySprint").toString());
        assertEquals(30, draft.path("completeness").path("inferredStorySprintCount").asInt());
        assertFalse(draft.path("risks").path("sprintRisk").elements().hasNext());
    }

    @Test
    void explicitSprintIsPreservedAndRealConflictRemainsVisible() {
        ObjectNode raw = base();
        ObjectNode story = story(raw, "S1", "用户明确排期", 1);
        story.put("_import_sprint_explicit", true);
        task(raw, "K1", "S1", 5, 5, 8).put("_import_schedule_explicit", true);

        ObjectNode draft = validator.normalizeImport(raw, "create", "NEW-import");

        assertEquals(1, draft.path("stories").get(0).path("sprint").asInt());
        assertTrue(draft.path("stories").get(0).path("sprint_explicit").asBoolean());
        assertEquals(1, draft.path("completeness").path("explicitStorySprintCount").asInt());
        assertEquals(1, draft.path("completeness").path("storySprintConflicts").size());
    }

    @Test
    void crossWeekTaskUsesTheSprintWithMostWorkOverlap() {
        ObjectNode raw = base();
        story(raw, "S1", "跨周核心实现", null);
        task(raw, "K1", "S1", 2, 4, 12);

        ObjectNode draft = validator.normalizeImport(raw, "create", "NEW-import");

        assertEquals(2, draft.path("stories").get(0).path("sprint").asInt());
        assertTrue(draft.path("sprintInferenceIssues").get(0).asText().contains("跨越多个 Sprint"));
    }

    @Test
    void multipleTasksUseTheMainHoursAndExecutionStage() {
        ObjectNode raw = base();
        story(raw, "S1", "多任务功能", null);
        task(raw, "K1", "S1", 1, 1, 4);
        task(raw, "K2", "S1", 3, 3, 10);
        task(raw, "K3", "S1", 4, 4, 8);

        ObjectNode draft = validator.normalizeImport(raw, "create", "NEW-import");

        assertEquals(2, draft.path("stories").get(0).path("sprint").asInt());
    }

    @Test
    void storiesWithoutTasksAreStablyBalancedAcrossConfiguredSprints() {
        ObjectNode raw = base();
        for (int i = 1; i <= 12; i++) story(raw, "S" + i, "中性业务功能" + i, null);

        ObjectNode first = validator.normalizeImport(raw.deepCopy(), "create", "NEW-import");
        ObjectNode second = validator.normalizeImport(raw.deepCopy(), "create", "NEW-import");
        ArrayNode counts = (ArrayNode) first.path("completeness").path("storyCountsBySprint");
        int min = 12, max = 0, total = 0;
        for (var count : counts) { min = Math.min(min, count.asInt()); max = Math.max(max, count.asInt()); total += count.asInt(); }
        assertEquals(12, total);
        assertTrue(max - min <= 2, counts.toString());
        assertEquals(first.path("stories").toString(), second.path("stories").toString());
    }

    @Test
    void semanticHintsDoNotOverrideOverallSprintBalance() {
        ObjectNode raw = base();
        for (int i = 1; i <= 12; i++) story(raw, "S" + i, "用户权限功能" + i, null);

        ObjectNode draft = validator.normalizeImport(raw, "create", "NEW-import");

        ArrayNode counts = (ArrayNode) draft.path("completeness").path("storyCountsBySprint");
        int min = 99, max = 0;
        for (var count : counts) { min = Math.min(min, count.asInt()); max = Math.max(max, count.asInt()); }
        assertTrue(max - min <= 2);
    }

    @Test
    void oneTaskCanInferSprintForMultipleStoriesWithoutDuplicateWarnings() {
        ObjectNode raw = base();
        story(raw, "S1", "查询功能", null);
        story(raw, "S2", "筛选功能", null);
        ObjectNode task = task(raw, "K1", "S1,S2", 5, 6, 16);
        task.put("story_refs", "S1,S2");

        ObjectNode draft = validator.normalizeImport(raw, "create", "NEW-import");

        assertEquals(3, draft.path("stories").get(0).path("sprint").asInt());
        assertEquals(3, draft.path("stories").get(1).path("sprint").asInt());
        assertEquals(0, draft.path("completeness").path("storySprintConflicts").size());
    }

    private ObjectNode base() {
        ObjectNode raw = mapper.createObjectNode();
        raw.set("project", mapper.createObjectNode().put("total_sprints", 3).put("duration_weeks", 6));
        raw.set("stories", mapper.createArrayNode());
        raw.set("tasks", mapper.createArrayNode());
        return raw;
    }

    private ObjectNode story(ObjectNode raw, String id, String title, Integer sprint) {
        ObjectNode story = mapper.createObjectNode().put("id", id).put("title", title)
                .put("description", "完整需求描述").put("acceptance", "验收通过")
                .put("priority", "Should").put("owner_id", 1).put("_import_sprint_explicit", sprint != null);
        if (sprint != null) story.put("sprint", sprint);
        ((ArrayNode) raw.path("stories")).add(story);
        return story;
    }

    private ObjectNode task(ObjectNode raw, String id, String storyRef, int start, int end, int hours) {
        ObjectNode task = mapper.createObjectNode().put("id", id).put("name", "实现 " + id)
                .put("owner_id", 1).put("hours", hours).put("week_start", start)
                .put("week_end", end).put("story_ref", storyRef).put("depends_on", "");
        ((ArrayNode) raw.path("tasks")).add(task);
        return task;
    }
}
