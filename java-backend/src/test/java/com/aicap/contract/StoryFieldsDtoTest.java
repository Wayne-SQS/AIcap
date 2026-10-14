package com.aicap.contract;

import com.aicap.dto.StoryDtos;
import com.aicap.entity.Story;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class StoryFieldsDtoTest {
    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    void epicPatchDistinguishesMissingFromExplicitNull() throws Exception {
        StoryDtos.StoryPatch missing = mapper.readValue("{}", StoryDtos.StoryPatch.class);
        StoryDtos.StoryPatch cleared = mapper.readValue("{\"epic_id\":null}", StoryDtos.StoryPatch.class);
        StoryDtos.StoryPatch selected = mapper.readValue("{\"epic_id\":5}", StoryDtos.StoryPatch.class);
        assertNull(missing.epicId());
        assertNotNull(cleared.epicId());
        assertTrue(cleared.epicId().isNull());
        assertEquals(5L, selected.epicId().asLong());
    }

    @Test
    void storyOutputKeepsDistinctFieldsAndSnakeCaseEpicId() throws Exception {
        Story story = new Story();
        story.setId("US03");
        story.setRole("负责人");
        story.setGoal("维护故事");
        story.setValue("形成基线");
        story.setEpicId(7L);
        var json = mapper.valueToTree(StoryDtos.toOut(story));
        assertEquals("负责人", json.path("role").asText());
        assertEquals("维护故事", json.path("goal").asText());
        assertEquals("形成基线", json.path("value").asText());
        assertEquals(7L, json.path("epic_id").asLong());
        assertFalse(json.has("epicId"));
    }
}
