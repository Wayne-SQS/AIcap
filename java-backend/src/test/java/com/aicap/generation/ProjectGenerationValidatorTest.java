package com.aicap.generation;

import com.aicap.common.ApiException;
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
        when(stories.selectList(nullable(Wrapper.class))).thenReturn(List.of());
        when(tasks.selectList(nullable(Wrapper.class))).thenReturn(List.of());
        when(users.selectList(any(Wrapper.class))).thenReturn(List.of(member));
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
        assertEquals("T02", normalized.path("tasks").get(2).path("depends_on").asText());
        assertEquals(2, normalized.path("dependencies").size());
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
        assertEquals("T03", rows.get(3).path("depends_on").asText());
        assertEquals("T04", rows.get(4).path("depends_on").asText());
        assertTrue(normalized.path("warnings").toString().contains("自动补充了推断依赖"));
    }

    @Test
    void normalizeRejectsDuplicateAndSelfDependencies() {
        ObjectNode duplicate = baseDraft();
        task(duplicate, "A", "接口", 1);
        ObjectNode b = task(duplicate, "B", "页面", 1);
        b.put("depends_on", "A,A");
        assertThrows(ApiException.class, () -> validator.normalize(duplicate, "merge"));

        ObjectNode self = baseDraft();
        ObjectNode a = task(self, "A", "接口", 1);
        a.put("depends_on", "A");
        assertThrows(ApiException.class, () -> validator.normalize(self, "merge"));
    }

    @Test
    void normalizeRejectsCycles() {
        ObjectNode raw = baseDraft();
        ObjectNode a = task(raw, "A", "接口A", 1);
        ObjectNode b = task(raw, "B", "接口B", 1);
        a.put("depends_on", "B");
        b.put("depends_on", "A");
        ApiException error = assertThrows(ApiException.class, () -> validator.normalize(raw, "merge"));
        assertFalse(error.getMessage().isBlank());
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

    private ObjectNode baseDraft() {
        ObjectNode raw = mapper.createObjectNode();
        raw.set("project", mapper.createObjectNode().put("total_sprints", 3));
        raw.set("stories", mapper.createArrayNode().add(mapper.createObjectNode()
                .put("id", "S1").put("title", "登录").put("sprint", 1).put("activity", 1)));
        raw.set("tasks", mapper.createArrayNode());
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
