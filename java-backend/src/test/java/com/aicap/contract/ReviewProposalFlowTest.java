package com.aicap.contract;

import com.aicap.security.AuthContext;
import com.aicap.service.ReviewProposalExecutionService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;
import java.util.concurrent.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;

class ReviewProposalFlowTest extends ReviewAnalysisStorageTest {
    String saved() throws Exception { return mapper.readTree(submit(body()).getContentAsByteArray()).path("id").asText(); }
    ObjectNode decision(String type, String amendment) {
        var input=mapper.createObjectNode().put("proposal_id","p1").put("decision",type).put("reason","人工核对验收范围");
        if(amendment!=null) input.putObject("changes").put("reason",amendment);
        return input;
    }
    org.springframework.mock.web.MockHttpServletResponse review(String id, JsonNode input) throws Exception {
        return mvc.perform(post(path+"/"+id+"/proposal-reviews").contentType("application/json").content(input.toString())).andReturn().getResponse();
    }
    org.springframework.mock.web.MockHttpServletResponse execute(String id) throws Exception {
        return mvc.perform(post(path+"/"+id+"/proposal-executions").contentType("application/json")
            .content("{\"proposal_id\":\"p1\"}")).andReturn().getResponse();
    }
    int status() { return jdbc.queryForObject("SELECT status FROM stories WHERE id='US13'",Integer.class); }
    int logs() { return jdbc.queryForObject("SELECT COUNT(*) FROM story_logs",Integer.class); }
    int executions() { return jdbc.queryForObject("SELECT COUNT(*) FROM meeting_review_proposal_executions",Integer.class); }

    @Test void modifiedReasonApprovalExecutionAndRetryPreserveOriginal() throws Exception {
        String id=saved(); var original=service.get("m1",id); role("owner");
        var input=decision("modify_and_approve","核对原文后确认US13整体验收通过");
        var response=review(id,input); assertEquals(200,response.getStatus(),response.getContentAsString());
        var reviewed=mapper.readTree(response.getContentAsByteArray());
        assertEquals("确认验收通过",reviewed.at("/original_proposal/reason").asText());
        assertEquals(input.at("/changes/reason"),reviewed.at("/approved_proposal/reason"));
        assertEquals(reviewed.at("/original_proposal/evidence"),reviewed.at("/approved_proposal/evidence"));
        assertEquals(1,status()); assertEquals(0,logs());
        assertEquals(response.getContentAsString(),review(id,input).getContentAsString());
        var first=execute(id); assertEquals(200,first.getStatus(),first.getContentAsString());
        assertEquals(2,status()); assertEquals(1,logs()); assertEquals(1,executions());
        assertEquals(1,jdbc.queryForObject("SELECT sprint FROM stories WHERE id='US13'",Integer.class));
        var result=mapper.readTree(first.getContentAsByteArray());
        assertEquals(1,result.path("previous_status").asInt()); assertEquals(2,result.path("new_status").asInt());
        assertEquals(jdbc.queryForObject("SELECT id FROM story_logs",Integer.class),result.path("story_log_id").asInt());
        assertEquals("move",jdbc.queryForObject("SELECT log_type FROM story_logs",String.class));
        assertTrue(jdbc.queryForObject("SELECT detail FROM story_logs",String.class).contains("analysis="+id));
        jdbc.update("UPDATE stories SET status=0 WHERE id='US13'");
        assertEquals(first.getContentAsString(),execute(id).getContentAsString());
        assertEquals(0,status()); assertEquals(1,logs()); assertEquals(original,service.get("m1",id));
        assertEquals(409,review(id,decision("reject",null)).getStatus());
    }

    @Test void pendingRejectedAndUnauthorizedRequestsCannotWrite() throws Exception {
        String id=saved();
        for(String name:java.util.List.of("member","viewer")) {
            role(name); assertEquals(403,review(id,decision("approve",null)).getStatus()); assertEquals(403,execute(id).getStatus());
        }
        AuthContext.clear(); assertEquals(401,review(id,decision("approve",null)).getStatus()); assertEquals(401,execute(id).getStatus());
        role("admin"); assertEquals(409,execute(id).getStatus());
        assertEquals(200,review(id,decision("reject",null)).getStatus());
        assertEquals(409,execute(id).getStatus()); assertEquals(1,status()); assertEquals(0,logs());
    }

    @Test void staleApprovalAndStaleExecutionAreConflicts() throws Exception {
        String id=saved(); role("admin");
        jdbc.update("UPDATE stories SET status=0 WHERE id='US13'");
        assertEquals(409,review(id,decision("approve",null)).getStatus());
        jdbc.update("UPDATE stories SET status=1 WHERE id='US13'");
        assertEquals(200,review(id,decision("approve",null)).getStatus());
        jdbc.update("UPDATE stories SET status=0 WHERE id='US13'");
        assertEquals(409,execute(id).getStatus()); assertEquals(0,logs()); assertEquals(0,executions());
    }

    @Test void invalidAmendmentsAndPayloadOverridesAreRejected() throws Exception {
        String id=saved(); role("admin");
        for(String text:java.util.List.of("", " ", "确认验收通过"))
            assertEquals(422,review(id,decision("modify_and_approve",text)).getStatus());
        assertEquals(422,review(id,decision("modify_and_approve",null)).getStatus());
        assertEquals(422,review(id,decision("approve","不应有修改")).getStatus());
        var input=decision("modify_and_approve","说明范围"); input.put("reason"," ");
        assertEquals(422,review(id,input).getStatus());
        for(String field:java.util.List.of("status","story_id","expected","evidence")) {
            input=decision("modify_and_approve","说明范围"); ((ObjectNode)input.get("changes")).put(field,2);
            assertEquals(422,review(id,input).getStatus());
        }
        var execution=mapper.createObjectNode().put("proposal_id","p1").put("status",2);
        assertEquals(422,mvc.perform(post(path+"/"+id+"/proposal-executions").contentType("application/json")
            .content(execution.toString())).andReturn().getResponse().getStatus());
        assertEquals(0,logs());
    }

    @Test void auditFailureRollsBackStoryLogAndReviewState() throws Exception {
        String id=saved(); role("admin"); assertEquals(200,review(id,decision("approve",null)).getStatus());
        jdbc.execute("ALTER TABLE meeting_review_proposal_executions ADD CONSTRAINT force_failure CHECK(new_status=-1)");
        assertEquals(500,execute(id).getStatus()); assertEquals(1,status()); assertEquals(0,logs()); assertEquals(0,executions());
        assertEquals("not_started",jdbc.queryForObject("SELECT execution_status FROM meeting_review_proposal_reviews",String.class));
        jdbc.execute("ALTER TABLE meeting_review_proposal_executions DROP CONSTRAINT force_failure");
        assertEquals(200,execute(id).getStatus()); assertEquals(2,status()); assertEquals(1,logs());
    }

    @Test void concurrentDuplicateExecutionWritesOnce() throws Exception {
        String id=saved(); role("admin"); assertEquals(200,review(id,decision("approve",null)).getStatus());
        var executor=context.getBean(ReviewProposalExecutionService.class); var start=new CountDownLatch(1);
        try(var pool=Executors.newFixedThreadPool(2)) {
            Callable<JsonNode> run=()->{start.await(); return executor.execute("m1",id,mapper.readTree("{\"proposal_id\":\"p1\"}"),1);};
            var a=pool.submit(run); var b=pool.submit(run); start.countDown();
            assertEquals(a.get(10,TimeUnit.SECONDS),b.get(10,TimeUnit.SECONDS));
        }
        assertEquals(2,status()); assertEquals(1,logs()); assertEquals(1,executions());
    }

    @Test void queriesReflectProgressAndEnforceMeetingScope() throws Exception {
        String id=saved(); role("admin");
        var reviewPath=path+"/"+id+"/proposal-reviews";
        var executionPath=path+"/"+id+"/proposal-executions";
        var pending=mapper.readTree(mvc.perform(get(reviewPath)).andReturn().getResponse().getContentAsByteArray());
        assertEquals("pending",pending.path("review_status").asText());
        assertEquals(404,mvc.perform(post(reviewPath.replace("/m1/","/m2/")).contentType("application/json")
            .content(decision("approve",null).toString())).andReturn().getResponse().getStatus());
        assertEquals(200,review(id,decision("approve",null)).getStatus()); assertEquals(200,execute(id).getStatus());
        role("viewer");
        var reviewed=mapper.readTree(mvc.perform(get(reviewPath)).andReturn().getResponse().getContentAsByteArray());
        assertEquals("reviewed",reviewed.path("review_status").asText());
        assertEquals("succeeded",reviewed.at("/proposals/0/execution_status").asText());
        assertEquals(1,mapper.readTree(mvc.perform(get(executionPath)).andReturn().getResponse().getContentAsByteArray()).size());
        assertEquals(404,mvc.perform(get(executionPath.replace("/m1/","/m2/"))).andReturn().getResponse().getStatus());
        AuthContext.clear(); assertEquals(401,mvc.perform(get(reviewPath)).andReturn().getResponse().getStatus());
        assertEquals(401,mvc.perform(get(executionPath)).andReturn().getResponse().getStatus());
    }
}
