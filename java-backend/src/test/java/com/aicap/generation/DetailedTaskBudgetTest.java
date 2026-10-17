package com.aicap.generation;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DetailedTaskBudgetTest {
    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    void thirtyTwoStoriesCannotRetainOneHundredSeventyTasks() {
        ObjectNode draft = draft(32, 170);
        int budget = DetailedTaskBudget.forStories(32);

        DetailedTaskBudget.enforce(draft, budget);

        assertEquals(104, budget);
        assertTrue(draft.path("tasks").size() <= budget);
        assertTrue(draft.path("tasks").size() < 150);
    }

    @Test
    void stableSeventySixTaskDraftIsNotOverTrimmed() {
        ObjectNode draft = draft(30, 76);

        int rejected = DetailedTaskBudget.enforce(draft, DetailedTaskBudget.forStories(30));

        assertEquals(0, rejected);
        assertEquals(76, draft.path("tasks").size());
    }

    @Test
    void supplementBudgetIsBoundedByThreeExplicitStoryGaps() {
        assertEquals(9, DetailedTaskBudget.supplementBudget(40, 3, 0));
        assertEquals(4, DetailedTaskBudget.supplementBudget(4, 3, 0));
        assertEquals(0, DetailedTaskBudget.supplementBudget(40, 0, 0));
    }

    @Test
    void sourceAwareSupplementReferencesResolveWithinTheirOwnNamespace() {
        ObjectNode first = draft(1, 1);
        first.withArray("tasks").get(0).deepCopy();
        ObjectNode supplement = mapper.createObjectNode();
        supplement.withArray("tasks").add(task("T01", "US01", "补充接口"));
        supplement.withArray("tasks").add(task("T02", "US01", "补充页面").put("depends_on", "T01"));
        supplement.withArray("dependencies").add(mapper.createObjectNode().put("from", "T01").put("to", "T02"));
        supplement.withArray("milestones").add(mapper.createObjectNode().put("id", "M2")
                .put("related_task_ids", "T01,T02"));

        ObjectNode merged = ProjectGenerationJobs.mergeDetailedStages(first, supplement, "supplement");

        assertEquals(3, merged.path("tasks").size());
        assertEquals("T02", merged.path("tasks").get(2).path("depends_on").asText());
        assertTrue(merged.path("tasks").get(1).path("generation_identity").asText().startsWith("supplement#"));
        assertEquals(0, merged.path("_task_generation_audit")
                .path("crossStageReferenceUnresolvedCount").asInt());
    }

    private ObjectNode draft(int stories, int tasks) {
        ObjectNode draft = mapper.createObjectNode();
        for (int index = 1; index <= stories; index++)
            draft.withArray("stories").add(mapper.createObjectNode().put("id", "US" + index));
        for (int index = 1; index <= tasks; index++) {
            int story = stories == 0 ? 1 : ((index - 1) % stories) + 1;
            draft.withArray("tasks").add(task(String.format("T%03d", index), "US" + story,
                    "业务" + index + (index % 2 == 0 ? "接口" : "页面")));
        }
        draft.withArray("dependencies");
        draft.withArray("milestones");
        return draft;
    }

    private ObjectNode task(String id, String story, String name) {
        return mapper.createObjectNode().put("id", id).put("name", name).put("story_ref", story)
                .put("depends_on", "").put("hours", 8).put("owner_id", 1)
                .put("week_start", 1).put("week_end", 1);
    }
}
