package com.aicap.contract;

import com.aicap.service.StatusProposalExecutionService;
import com.aicap.service.StatusProposalReviewService;
import com.aicap.security.AuthContext;
import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.Test;
import java.util.concurrent.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;

/** Includes the 17 storage/review regressions using the same isolated transaction harness. */
class StatusProposalExecutionTest extends StatusProposalReviewTest {
    String executionPath(String id) { return path+"/"+id+"/proposal-executions"; }
    JsonNode executionBody() { return mapper.createObjectNode().put("proposal_id","p1"); }
    org.springframework.mock.web.MockHttpServletResponse execute(String id,JsonNode body) throws Exception {
        return mvc.perform(post(executionPath(id)).contentType("application/json").content(body.toString())).andReturn().getResponse();
    }
    String approved() throws Exception {
        String id=savedAnalysis(); role("admin");
        assertEquals(200,review(id,reviewBody("approve")).getStatus()); return id;
    }
    int executions() { return jdbc.queryForObject("SELECT COUNT(*) FROM meeting_status_proposal_executions",Integer.class); }
    int logs() { return jdbc.queryForObject("SELECT COUNT(*) FROM story_logs",Integer.class); }
    int storyStatus() { return jdbc.queryForObject("SELECT status FROM stories WHERE project_id='P001' AND code='US13'",Integer.class); }

    @Test void executionUpdatesStoryAndAuditWithoutChangingOriginalAnalysis() throws Exception {
        String id=approved(); var original=service.get("m1",id);
        var response=execute(id,executionBody()); assertEquals(200,response.getStatus(),response.getContentAsString());
        var result=mapper.readTree(response.getContentAsByteArray());
        assertEquals(2,storyStatus()); assertEquals(1,executions()); assertEquals(1,logs());
        assertEquals(1,result.path("previous_status").asInt()); assertEquals(2,result.path("new_status").asInt());
        assertEquals(1,result.path("executed_by").asInt()); assertFalse(result.path("executed_at").asText().isBlank());
        var log=jdbc.queryForMap("SELECT * FROM story_logs WHERE id=?",result.path("story_log_id").asInt());
        assertEquals("US13",log.get("story_id")); assertEquals("move",log.get("log_type")); assertEquals(1,log.get("user_id"));
        assertTrue(log.get("detail").toString().contains(id));
        assertEquals(original,service.get("m1",id));
        assertEquals("succeeded",context.getBean(StatusProposalReviewService.class).list("m1",id).at("/proposals/0/execution_status").asText());
        var listing=mapper.readTree(mvc.perform(get(executionPath(id))).andReturn().getResponse().getContentAsByteArray());
        assertEquals(result,listing.get(0));
    }

    @Test void executesHumanModifiedTarget() throws Exception {
        String id=savedAnalysis(); role("owner"); var input=reviewBody("modify_and_approve");
        input.putObject("changes").put("status",0); assertEquals(200,review(id,input).getStatus());
        assertEquals(200,execute(id,executionBody()).getStatus()); assertEquals(0,storyStatus());
    }

    @Test void correctedStoryAndMultipleFieldsExecuteTogetherWithAudit() throws Exception {
        String id=savedAnalysis(); role("admin"); var input=reviewBody("modify_and_approve");
        input.put("story_id","US14"); input.putObject("changes").put("status",1);
        input.putObject("story_patch").put("sprint",3).put("owner_id",2).put("title","权限审核");
        var reviewed=review(id,input); assertEquals(200,reviewed.getStatus(),reviewed.getContentAsString());
        var saved=mapper.readTree(reviewed.getContentAsByteArray());
        assertEquals("US13",saved.at("/original_proposal/story_id").asText());
        assertEquals("US14",saved.at("/approved_proposal/story_id").asText());
        assertEquals(0,saved.at("/approved_proposal/expected/status").asInt());
        assertEquals(200,execute(id,executionBody()).getStatus());
        var story=jdbc.queryForMap("SELECT title,status,sprint,owner_id FROM stories WHERE project_id='P001' AND code='US14'");
        assertEquals("权限审核",story.get("title")); assertEquals(1,story.get("status"));
        assertEquals(3,story.get("sprint")); assertEquals(2,story.get("owner_id"));
        assertEquals(1,storyStatus()); assertEquals(1,logs());
        assertEquals("US14",jdbc.queryForObject("SELECT story_id FROM meeting_status_proposal_executions",String.class));
    }

    @Test void staleSnapshotBlocksAllApprovedStoryFieldChanges() throws Exception {
        String id=savedAnalysis(); role("admin"); var input=reviewBody("modify_and_approve");
        input.putObject("changes").put("status",2);
        input.putObject("story_patch").put("sprint",3);
        assertEquals(200,review(id,input).getStatus());
        jdbc.update("UPDATE stories SET sprint=4 WHERE project_id='P001' AND code='US13'");
        assertEquals(409,execute(id,executionBody()).getStatus());
        assertEquals(1,storyStatus()); assertEquals(0,logs()); assertEquals(0,executions());
    }

    @Test void sprintOnlyAmendmentCanExecuteWithoutStatusMovement() throws Exception {
        String id=savedAnalysis(); role("admin"); var input=reviewBody("modify_and_approve");
        input.putObject("changes").put("status",1);
        input.putObject("story_patch").put("sprint",3);
        assertEquals(200,review(id,input).getStatus());
        var response=execute(id,executionBody()); assertEquals(200,response.getStatus(),response.getContentAsString());
        var result=mapper.readTree(response.getContentAsByteArray());
        assertEquals(1,result.path("previous_status").asInt());
        assertEquals(1,result.path("new_status").asInt());
        assertEquals(3,jdbc.queryForObject("SELECT sprint FROM stories WHERE project_id='P001' AND code='US13'",Integer.class));
        assertEquals("edit",jdbc.queryForObject("SELECT log_type FROM story_logs",String.class));
    }

    @Test void pendingRejectedAndStaleOrDeletedTargetsHaveNoSideEffects() throws Exception {
        String id=savedAnalysis(); role("admin");
        assertEquals(409,execute(id,executionBody()).getStatus());
        assertEquals(200,review(id,reviewBody("reject")).getStatus());
        assertEquals(409,execute(id,executionBody()).getStatus());
        var input=body(); input.put("client_request_id","second");
        String second=mapper.readTree(submit(input).getContentAsByteArray()).path("id").asText();
        assertEquals(200,review(second,reviewBody("approve")).getStatus());
        jdbc.update("UPDATE stories SET status=2 WHERE project_id='P001' AND code='US13'");
        // Already at desired status without an execution record is a conflict, not a successful retry.
        assertEquals(409,execute(second,executionBody()).getStatus());
        jdbc.update("DELETE FROM stories WHERE project_id='P001' AND code='US13'");
        assertEquals(409,execute(second,executionBody()).getStatus()); assertEquals(0,logs()); assertEquals(0,executions());
    }

    @Test void retryReturnsDurableFirstResultDespiteLaterChanges() throws Exception {
        String id=approved(); var first=execute(id,executionBody()); assertEquals(200,first.getStatus());
        jdbc.update("UPDATE stories SET status=0 WHERE project_id='P001' AND code='US13'");
        role("owner"); AuthContext.currentUser().setId(2);
        assertEquals(first.getContentAsString(),execute(id,executionBody()).getContentAsString());
        assertEquals(0,storyStatus()); assertEquals(1,logs()); assertEquals(1,executions());
    }

    @Test void executionChecksCurrentPermissionsAndResourceScopeAndRejectsSpoofing() throws Exception {
        String id=approved();
        for(String role:java.util.List.of("member","viewer")) {
            role(role); assertEquals(403,execute(id,executionBody()).getStatus());
            assertEquals(200,mvc.perform(get(executionPath(id))).andReturn().getResponse().getStatus());
        }
        AuthContext.clear(); assertEquals(401,execute(id,executionBody()).getStatus());
        assertEquals(401,mvc.perform(get(executionPath(id))).andReturn().getResponse().getStatus());
        role("admin");
        for(String body:java.util.List.of("{}","{\"proposal_id\":1}","{\"proposal_id\":\"p1\",\"changes\":{\"status\":0}}","{\"proposal_id\":\"p1\",\"executed_by\":2}"))
            assertEquals(422,execute(id,mapper.readTree(body)).getStatus());
        assertEquals(404,execute(id,mapper.createObjectNode().put("proposal_id","P1")).getStatus());
        String wrong=executionPath(id).replace("/m1/","/m2/");
        assertEquals(404,mvc.perform(post(wrong).contentType("application/json").content(executionBody().toString())).andReturn().getResponse().getStatus());
        assertEquals(404,mvc.perform(get(wrong)).andReturn().getResponse().getStatus());
        assertEquals(0,logs()); assertEquals(0,executions()); assertEquals(1,storyStatus());
    }

    @Test void auditFailureRollsBackStoryAndCanBeRetried() throws Exception {
        String id=approved();
        // Fail the final execution insert after both the story update and log insert happened.
        jdbc.execute("ALTER TABLE meeting_status_proposal_executions ADD CONSTRAINT force_failure CHECK(new_status=-1)");
        assertEquals(500,execute(id,executionBody()).getStatus());
        assertEquals(1,storyStatus()); assertEquals(0,logs()); assertEquals(0,executions());
        assertEquals("not_started",jdbc.queryForObject("SELECT execution_status FROM meeting_status_proposal_reviews",String.class));
        jdbc.execute("ALTER TABLE meeting_status_proposal_executions DROP CONSTRAINT force_failure");
        assertEquals(200,execute(id,executionBody()).getStatus()); assertEquals(1,logs()); assertEquals(1,executions());
    }

    @Test void concurrentDuplicateExecutionsHaveOneSideEffect() throws Exception {
        String id=approved(); var service=context.getBean(StatusProposalExecutionService.class); var start=new CountDownLatch(1);
        try(var executor=Executors.newFixedThreadPool(2)) {
            Callable<JsonNode> task=()->{start.await(); return service.execute("m1",id,executionBody(),1);};
            var a=executor.submit(task); var b=executor.submit(task); start.countDown();
            assertEquals(a.get(10,TimeUnit.SECONDS),b.get(10,TimeUnit.SECONDS));
            assertEquals(2,storyStatus()); assertEquals(1,logs()); assertEquals(1,executions());
        }
    }

    @Test void competingApprovedAnalysesCannotOverwriteChangedStory() throws Exception {
        String first=approved(); var input=body(); input.put("client_request_id","second");
        String second=mapper.readTree(submit(input).getContentAsByteArray()).path("id").asText();
        assertEquals(200,review(second,reviewBody("approve")).getStatus());
        var service=context.getBean(StatusProposalExecutionService.class); var start=new CountDownLatch(1);
        try(var executor=Executors.newFixedThreadPool(2)) {
            java.util.function.Function<String,Callable<Integer>> task=id->()->{start.await(); try { service.execute("m1",id,executionBody(),1); return 200; } catch(com.aicap.common.ApiException e) { return e.getStatus(); }};
            var a=executor.submit(task.apply(first)); var b=executor.submit(task.apply(second)); start.countDown();
            assertEquals(java.util.Set.of(200,409),new java.util.HashSet<>(java.util.List.of(a.get(10,TimeUnit.SECONDS),b.get(10,TimeUnit.SECONDS))));
            assertEquals(1,logs()); assertEquals(1,executions());
        }
    }
}
