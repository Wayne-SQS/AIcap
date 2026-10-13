package com.aicap.generation;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.junit.jupiter.api.Assertions.*;

class TaskStoryReferencesTest {
    private final ObjectMapper mapper = new ObjectMapper();

    @ParameterizedTest
    @ValueSource(strings = {"story_ref", "storyCode", "story_code", "story_refs", "story"})
    void allAliasesResolveNumericScalarsOnlyAgainstRawIds(String field) {
        ObjectNode draft = draft();
        ObjectNode task = (ObjectNode) draft.path("tasks").get(0);
        task.put(field, 42);
        TaskStoryReferences.canonicalize(draft);
        assertEquals("42", task.path("story_ref").asText());
        assertEquals("42", task.path("storyCode").asText());
        assertEquals("42", task.path("model_story_refs").asText());
        assertTrue(task.path(TaskStoryReferences.AUDIT).get(0).path("mapped").asBoolean());
        assertEquals(field, task.path(TaskStoryReferences.AUDIT).get(0).path("field").asText());
        assertEquals(1, TaskStoryReferences.summary(draft.path("tasks")).path("numericScalarResolvedCount").asInt());
    }

    @Test void numericArraysAndStringArraysUseTheSameExtractor() {
        ObjectNode draft = draft();
        ObjectNode task = (ObjectNode) draft.path("tasks").get(0);
        task.putArray("story_refs").add(42).add("US-001");
        TaskStoryReferences.canonicalize(draft);
        assertEquals("42,US-001", TaskStoryReferences.text(task));
        assertEquals(2, task.path(TaskStoryReferences.AUDIT).size());
        assertEquals(0, TaskStoryReferences.summary(draft.path("tasks")).path("numericScalarResolvedCount").asInt());
    }

    @Test void unknownNumericIdsAreInvalidEvenWhenTheirValueLooksLikeAnArrayPosition() {
        ObjectNode draft = draft();
        ObjectNode task = (ObjectNode) draft.path("tasks").get(0);
        task.put("story_ref", 1);
        TaskStoryReferences.canonicalize(draft);
        assertEquals("", task.path("story_ref").asText());
        assertEquals("1", task.path("invalid_story_refs").asText());
        assertEquals("1", task.path("model_story_refs").asText());
        assertEquals("UNKNOWN_RAW_STORY_ID", task.path(TaskStoryReferences.AUDIT).get(0).path("failureReason").asText());
        assertEquals(1, TaskStoryReferences.summary(draft.path("tasks")).path("numericScalarRejectedCount").asInt());
    }

    @Test void rawCodeIsAlsoAnExplicitMappingButAmbiguousCodesAreRejected() {
        ObjectNode draft = draft();
        ((ObjectNode) draft.path("stories").get(0)).put("code", 99);
        ObjectNode task = (ObjectNode) draft.path("tasks").get(0);
        task.put("storyCode", 99);
        TaskStoryReferences.canonicalize(draft);
        assertEquals("42", task.path("story_ref").asText());
        ObjectNode ambiguous = draft();
        ambiguous.withArray("stories").add(mapper.createObjectNode().put("id", "other").put("code", 42));
        ((ObjectNode) ambiguous.path("tasks").get(0)).put("story_ref", 42);
        TaskStoryReferences.canonicalize(ambiguous);
        assertEquals("42", ambiguous.path("tasks").get(0).path("invalid_story_refs").asText());
    }

    @Test void objectsObjectArraysAndBooleansNeverGuessAStory() {
        ObjectNode draft = draft();
        ObjectNode task = (ObjectNode) draft.path("tasks").get(0);
        task.putObject("story").put("id", 42);
        task.putArray("story_refs").add(mapper.createObjectNode().put("id", 42)).add(true);
        task.put("storyCode", false);
        TaskStoryReferences.canonicalize(draft);
        assertEquals("", task.path("story_ref").asText());
        assertEquals(4, task.path(TaskStoryReferences.AUDIT).size());
        assertEquals(4, TaskStoryReferences.summary(draft.path("tasks")).path("rejectedReferenceCount").asInt());
        assertEquals(1, TaskStoryReferences.summary(draft.path("tasks")).path("rejectedByFieldAndType").path("story:OBJECT").asInt());
        assertFalse(task.path(TaskStoryReferences.AUDIT).toString().contains("\"id\":42"));
    }

    @Test void canonicalizationIsIdempotentAndKeepsTheOriginalFieldAndType() {
        ObjectNode draft = draft();
        ObjectNode task = (ObjectNode) draft.path("tasks").get(0);
        task.put("storyCode", 42);
        TaskStoryReferences.canonicalize(draft);
        ObjectNode first = draft.deepCopy();
        TaskStoryReferences.canonicalize(draft);
        assertEquals(first, draft);
        assertEquals("NUMBER", task.path(TaskStoryReferences.AUDIT).get(0).path("jsonType").asText());
        assertEquals("storyCode", task.path(TaskStoryReferences.AUDIT).get(0).path("field").asText());
    }

    @Test void budgetAndMergeConsumeCanonicalAliasesAndPreserveRejectedAudit() {
        ObjectNode first = draft();
        ((ObjectNode) first.path("tasks").get(0)).put("storyCode", 42);
        TaskStoryReferences.canonicalize(first);
        assertEquals("42", DetailedTaskBudget.storyKey(first.path("tasks").get(0)));
        ObjectNode second = draft();
        ((ObjectNode) second.path("tasks").get(0)).put("story_code", 999);
        TaskStoryReferences.canonicalize(second);
        ObjectNode merged = ProjectGenerationJobs.mergeDrafts(first, second);
        assertEquals("42", merged.path("tasks").get(0).path("story_ref").asText());
        assertEquals("999", merged.path("tasks").get(0).path("invalid_story_refs").asText());
        assertEquals(2, merged.path("tasks").get(0).path(TaskStoryReferences.AUDIT).size());
    }

    @Test void storyCompressionRemapsCanonicalRefsAndAuditTargetsTogether() throws Exception {
        ObjectNode draft = draft();
        ((ObjectNode) draft.path("tasks").get(0)).put("storyCode", 42);
        TaskStoryReferences.canonicalize(draft);
        var remap = StoryCountControl.class.getDeclaredMethod("remap", ObjectNode.class, java.util.Map.class);
        remap.setAccessible(true);
        remap.invoke(null, draft, java.util.Map.of("42", "US-001"));
        assertCanonicalRefs(draft.path("tasks").get(0), "US-001");
        assertEquals("42", draft.path("tasks").get(0).path(TaskStoryReferences.AUDIT).get(0).path("reference").asText());
        assertEquals("US-001", draft.path("tasks").get(0).path(TaskStoryReferences.AUDIT).get(0).path("parsedReference").asText());
    }

    @Test void storyAliasMergeRewritesBothCanonicalFieldsAndKeepsOriginalAudit() {
        ObjectNode first = draft();
        first.withArray("tasks").removeAll();
        TaskStoryReferences.canonicalize(first);
        ObjectNode second = mapper.createObjectNode();
        second.putArray("stories").add(mapper.createObjectNode().put("id", "R1").put("title", "用户登录"));
        second.putArray("tasks").add(mapper.createObjectNode().put("id", "T2").put("story", "R1"));
        TaskStoryReferences.canonicalize(second);
        ObjectNode merged = ProjectGenerationJobs.mergeDrafts(first, second);
        assertCanonicalRefs(merged.path("tasks").get(0), "42");
        assertEquals("42", merged.path("tasks").get(0).path(TaskStoryReferences.AUDIT).get(0).path("parsedReference").asText());
        assertEquals("R1", merged.path("tasks").get(0).path("model_story_refs").asText());
    }

    @Test void duplicateLocalCodesRemainSourceAwareAndDoNotFanOut() {
        var tasks = mapper.createArrayNode();
        tasks.add(mapper.createObjectNode().put("id", "T03").put("generation_stage", "tasks")
                .put("generation_identity", "tasks#0#T03"));
        tasks.add(mapper.createObjectNode().put("id", "T03").put("generation_stage", "supplement")
                .put("generation_identity", "supplement#1#T03"));
        tasks.add(mapper.createObjectNode().put("id", "T04").put("depends_on", "T03")
                .put("generation_stage", "continuation").put("generation_identity", "continuation#1#T04"));
        TaskIdentityReferences.resolve(tasks, mapper.createArrayNode(), mapper.createArrayNode(), false);
        assertEquals("", tasks.get(2).path("depends_on").asText());
        assertEquals(1, tasks.get(2).path("unresolved_cross_stage_references").size());
    }

    @Test void uniqueBareCodeResolvesToOneIdentityAndNeverCreatesSelfDependency() {
        var tasks = mapper.createArrayNode();
        tasks.add(mapper.createObjectNode().put("id", "T01").put("generation_stage", "tasks")
                .put("generation_identity", "tasks#0#T01"));
        tasks.add(mapper.createObjectNode().put("id", "T02").put("depends_on", "T01")
                .put("generation_stage", "tasks").put("generation_identity", "tasks#0#T02"));
        TaskIdentityReferences.resolve(tasks, mapper.createArrayNode(), mapper.createArrayNode(), false);
        assertEquals("tasks#0#T01", tasks.get(1).path("generation_dependency_refs").get(0).asText());
        assertEquals("T01", tasks.get(1).path("depends_on").asText());
        assertFalse(tasks.get(1).path("depends_on").asText().equals(tasks.get(1).path("id").asText()));
    }

    private void assertCanonicalRefs(com.fasterxml.jackson.databind.JsonNode task, String expected) {
        assertEquals(expected, task.path("story_ref").asText());
        assertEquals(expected, task.path("storyCode").asText());
    }

    private ObjectNode draft() {
        ObjectNode draft = mapper.createObjectNode();
        draft.putArray("stories").add(mapper.createObjectNode().put("id", 42).put("title", "用户登录"))
                .add(mapper.createObjectNode().put("id", "US-001").put("title", "查看资料"));
        draft.putArray("tasks").add(mapper.createObjectNode().put("id", "T1").put("name", "接口")
                .put("model_task_code", "T1").put("generation_identity", "tasks#0#T1"));
        return draft;
    }
}
