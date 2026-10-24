package com.aicap.planning;

import com.aicap.llm.ModelClient;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class PlanningAgentScheduleTest {
    @Test void scheduleUsesCompleteReadOnlySnapshotAndOneStructuredModelCall() throws Exception {
        ObjectMapper mapper = new ObjectMapper();
        PlanningAgentTools tools = mock(PlanningAgentTools.class);
        PlanningAnalysisValidator validator = mock(PlanningAnalysisValidator.class);
        ModelClient model = mock(ModelClient.class);
        when(tools.execute(eq("P001"), eq("get_tasks"), any())).thenReturn(mapper.readTree("""
                {"items":[{"id":"T1","status":0,"priority":"Must","depends_on":"T0"}]}
                """));
        when(tools.execute(eq("P001"), eq("get_members"), any())).thenReturn(mapper.readTree("""
                {"items":[{"id":2,"weekly_capacity_hours":16}]}
                """));
        when(tools.execute(eq("P001"), eq("get_sprints"), any())).thenReturn(mapper.readTree("""
                {"items":[{"sprint":1,"week_start":1,"week_end":2}]}
                """));
        when(tools.execute(eq("P001"), eq("get_member_load"), any())).thenReturn(mapper.readTree("""
                {"owner_id":2,"weekly_hours":[8,0]}
                """));
        when(tools.execute(eq("P001"), eq("get_task_dependencies"), any())).thenReturn(mapper.readTree("""
                {"task_id":"T1","predecessors":[{"id":"T0","status":2}],"dependents":[]}
                """));
        ObjectNode reply = mapper.createObjectNode().put("role", "assistant")
                .put("content", "{\"summary\":\"排期依据\",\"actions\":[]}");
        when(model.complete(anyList(), anyList(), eq(true)))
                .thenReturn(new ModelClient.Completion(reply, Map.of()));
        ObjectNode checked = mapper.createObjectNode().put("summary", "排期依据");
        when(validator.validate(eq("P001"), anyString())).thenReturn(checked);

        List<String> events = new ArrayList<>();
        ObjectNode result = new PlanningAgentRunner(tools, validator, mapper)
                .analyze("P001", "智能排期建议", model, (kind, detail) -> events.add(detail));

        assertSame(checked, result);
        assertEquals(5, events.size());
        verify(tools).execute(eq("P001"), eq("get_member_load"), argThat(a -> a.path("owner_id").asInt() == 2));
        verify(tools).execute(eq("P001"), eq("get_task_dependencies"), argThat(a -> "T1".equals(a.path("task_id").asText())));
        verify(model, times(1)).complete(argThat(messages -> {
            JsonNode input = messages.get(1);
            String text = input.path("content").asText();
            return text.contains("weekly_hours") && text.contains("predecessors") && text.contains("Must");
        }), eq(List.of()), eq(true));
        verify(model, never()).complete(anyList(), anyList(), eq(false));
    }
}
