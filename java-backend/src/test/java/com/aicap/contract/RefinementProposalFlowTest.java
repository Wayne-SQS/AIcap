package com.aicap.contract;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.aicap.security.AuthContext;
import com.aicap.service.RefinementProposalExecutionService;
import org.junit.jupiter.api.Test;
import java.util.concurrent.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;

class RefinementProposalFlowTest extends RefinementAnalysisStorageTest {
    String saved() throws Exception { return mapper.readTree(submit(body()).getContentAsByteArray()).path("id").asText(); }
    ObjectNode decision(String value) { return mapper.createObjectNode().put("proposal_id","p1").put("decision",value).put("reason","人工确认"); }
    ObjectNode amended() throws Exception {
        var value=decision("modify_and_approve");
        value.set("changes",mapper.readTree("""
          {"title":"导出周报CSV","description":"导出本周故事","acceptance":"CSV含本周已完成故事",
           "priority":"Should","sprint":3,"activity":4}
          """)); return value;
    }
    org.springframework.mock.web.MockHttpServletResponse review(String id,JsonNode input) throws Exception {
        return mvc.perform(post(path+"/"+id+"/proposal-reviews").contentType("application/json").content(input.toString())).andReturn().getResponse();
    }
    org.springframework.mock.web.MockHttpServletResponse execute(String id) throws Exception {
        return mvc.perform(post(path+"/"+id+"/proposal-executions").contentType("application/json").content("{\"proposal_id\":\"p1\"}")).andReturn().getResponse();
    }
    int rows(String table) { return jdbc.queryForObject("SELECT COUNT(*) FROM "+table,Integer.class); }

    @Test void completeHumanApprovalCreatesExactlyOneStoryAndAudit() throws Exception {
        String id=saved(); var original=service.get("m1",id); role("owner");
        assertEquals(422,review(id,decision("approve")).getStatus());
        assertEquals(200,review(id,amended()).getStatus());
        assertEquals(200,review(id,amended()).getStatus());
        assertEquals(2,rows("stories")); assertEquals(0,rows("story_logs"));
        var response=execute(id); assertEquals(200,response.getStatus(),response.getContentAsString());
        var execution=mapper.readTree(response.getContentAsByteArray());
        assertEquals("US15",execution.path("story_id").asText());
        var story=jdbc.queryForMap("SELECT * FROM stories WHERE id='US15'");
        assertEquals("导出周报CSV",story.get("title")); assertEquals("Should",story.get("priority"));
        assertEquals(3,story.get("sprint")); assertEquals(4,story.get("activity")); assertEquals(0,story.get("status")); assertNull(story.get("owner_id"));
        var log=jdbc.queryForMap("SELECT * FROM story_logs WHERE story_id='US15'");
        assertEquals(execution.path("story_log_id").intValue(),((Number)log.get("id")).intValue());
        assertEquals(amended().get("changes"),mapper.readTree(log.get("detail").toString()).get("changes"));
        assertEquals(original,service.get("m1",id));
        jdbc.update("UPDATE stories SET title='后续人工修改' WHERE id='US15'");
        assertEquals(execution,mapper.readTree(execute(id).getContentAsByteArray()));
        assertEquals(3,rows("stories")); assertEquals(1,rows("story_logs"));
        assertEquals(409,review(id,decision("reject")).getStatus());
    }

    @Test void fullySpecifiedOriginalCanBeApprovedWithoutEdits() throws Exception {
        var input=body(); ((ObjectNode)input.at("/result/proposed_actions/0")).set("changes",amended().get("changes"));
        var response=submit(input); assertEquals(200,response.getStatus());
        String id=mapper.readTree(response.getContentAsByteArray()).path("id").asText(); role("admin");
        assertEquals(200,review(id,decision("approve")).getStatus()); assertEquals(200,execute(id).getStatus());
    }

    @Test void pendingRejectedAndUnauthorizedCannotCreateStories() throws Exception {
        String id=saved();
        for(String role:java.util.List.of("member","viewer")) {
            role(role); assertEquals(403,review(id,amended()).getStatus()); assertEquals(403,execute(id).getStatus());
        }
        AuthContext.clear(); assertEquals(401,execute(id).getStatus()); assertEquals(401,review(id,amended()).getStatus());
        role("admin"); assertEquals(409,execute(id).getStatus());
        assertEquals(200,review(id,decision("reject")).getStatus()); assertEquals(409,execute(id).getStatus());
        assertEquals(2,rows("stories"));
    }

    @Test void missingFieldsUnchangedEditsAndExecutionOverridesAreRejected() throws Exception {
        String id=saved(); role("admin");
        for(String field:java.util.List.of("description","acceptance","priority","sprint","activity")) {
            var input=amended(); ((ObjectNode)input.get("changes")).putNull(field);
            assertEquals(422,review(id,input).getStatus(),field);
        }
        var same=decision("modify_and_approve"); same.set("changes",body().at("/result/proposed_actions/0/changes"));
        assertEquals(422,review(id,same).getStatus());
        var input=amended(); input.put("reason"," "); assertEquals(422,review(id,input).getStatus());
        input=amended(); ((ObjectNode)input.get("changes")).put("owner_id",1); assertEquals(422,review(id,input).getStatus());
        assertEquals(422,mvc.perform(post(path+"/"+id+"/proposal-executions").contentType("application/json").content("{\"proposal_id\":\"p1\",\"title\":\"绕过\"}")).andReturn().getResponse().getStatus());
        assertEquals(0,rows("meeting_refinement_proposal_reviews"));
    }

    @Test void duplicateTitleCheckedAtReviewAndAgainAtExecution() throws Exception {
        String id=saved(); role("admin"); var input=amended(); ((ObjectNode)input.get("changes")).put("title"," 登录 ");
        assertEquals(409,review(id,input).getStatus());
        assertEquals(200,review(id,amended()).getStatus());
        jdbc.update("UPDATE stories SET title='导出周报CSV' WHERE id='US13'");
        assertEquals(409,execute(id).getStatus()); assertEquals(0,rows("story_logs")); assertEquals(2,rows("stories"));
    }

    @Test void ledgerFailureRollsBackStoryLogAndExecutionState() throws Exception {
        String id=saved(); role("admin"); assertEquals(200,review(id,amended()).getStatus());
        jdbc.execute("ALTER TABLE meeting_refinement_proposal_executions ADD CONSTRAINT fail_write CHECK(executed_by=-1)");
        assertEquals(500,execute(id).getStatus()); assertEquals(2,rows("stories")); assertEquals(0,rows("story_logs"));
        assertEquals("not_started",jdbc.queryForObject("SELECT execution_status FROM meeting_refinement_proposal_reviews",String.class));
        jdbc.execute("ALTER TABLE meeting_refinement_proposal_executions DROP CONSTRAINT fail_write");
        assertEquals(200,execute(id).getStatus()); assertEquals(3,rows("stories"));
    }

    @Test void concurrentDuplicateExecutionReturnsFirstResult() throws Exception {
        String id=saved(); role("admin"); assertEquals(200,review(id,amended()).getStatus());
        var executor=context.getBean(RefinementProposalExecutionService.class);
        var pool=Executors.newFixedThreadPool(2); var barrier=new CyclicBarrier(2);
        try {
            Callable<ObjectNode> job=()->{barrier.await(5,TimeUnit.SECONDS);return executor.execute("m1",id,mapper.readTree("{\"proposal_id\":\"p1\"}"),1);};
            var a=pool.submit(job); var b=pool.submit(job);
            assertEquals(a.get(10,TimeUnit.SECONDS),b.get(10,TimeUnit.SECONDS));
            assertEquals(3,rows("stories")); assertEquals(1,rows("story_logs")); assertEquals(1,rows("meeting_refinement_proposal_executions"));
        } finally { pool.shutdownNow(); }
    }

    @Test void queriesAreReadOnlyAndBoundToMeeting() throws Exception {
        String id=saved(); role("admin"); assertEquals(200,review(id,amended()).getStatus()); assertEquals(200,execute(id).getStatus());
        role("viewer");
        for(String resource:java.util.List.of("proposal-reviews","proposal-executions")) {
            assertEquals(200,mvc.perform(get(path+"/"+id+"/"+resource)).andReturn().getResponse().getStatus());
            assertEquals(404,mvc.perform(get("/api/meetings/other/refinement-analyses/"+id+"/"+resource)).andReturn().getResponse().getStatus());
        }
    }
}
