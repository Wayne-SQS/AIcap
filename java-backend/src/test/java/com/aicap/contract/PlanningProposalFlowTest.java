package com.aicap.contract;

import com.aicap.service.PlanningProposalExecutionService;
import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.Test;
import java.util.concurrent.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;

class PlanningProposalFlowTest extends PlanningAnalysisStorageTest {
    String saved() throws Exception { return mapper.readTree(submit(body()).getContentAsByteArray()).path("id").asText(); }
    org.springframework.mock.web.MockHttpServletResponse review(String id, String decision, Integer sprint) throws Exception {
        var input = mapper.createObjectNode().put("proposal_id","p1").put("decision",decision).put("reason","人工核对");
        if(sprint != null) input.putObject("changes").put("sprint",sprint);
        return mvc.perform(post(path+"/"+id+"/proposal-reviews").contentType("application/json").content(input.toString())).andReturn().getResponse();
    }
    org.springframework.mock.web.MockHttpServletResponse execute(String id) throws Exception {
        return mvc.perform(post(path+"/"+id+"/proposal-executions").contentType("application/json")
            .content("{\"proposal_id\":\"p1\"}")).andReturn().getResponse();
    }
    int sprint() { return jdbc.queryForObject("SELECT sprint FROM stories WHERE id='US13'",Integer.class); }
    int logs() { return jdbc.queryForObject("SELECT COUNT(*) FROM story_logs",Integer.class); }

    @Test void modifiedApprovalExecutionAndRetryPreserveOriginal() throws Exception {
        String id=saved(); var original=service.get("m1",id); role("owner");
        assertEquals(200,review(id,"modify_and_approve",4).getStatus());
        assertEquals(1,sprint());
        var first=execute(id); assertEquals(200,first.getStatus(),first.getContentAsString());
        assertEquals(4,sprint()); assertEquals(1,logs());
        assertEquals(1,jdbc.queryForObject("SELECT status FROM stories WHERE id='US13'",Integer.class));
        var result=mapper.readTree(first.getContentAsByteArray());
        assertEquals(1,result.path("previous_sprint").asInt()); assertEquals(4,result.path("new_sprint").asInt());
        assertTrue(result.path("story_log_id").asInt()>0);
        jdbc.update("UPDATE stories SET sprint=3 WHERE id='US13'");
        assertEquals(first.getContentAsString(),execute(id).getContentAsString());
        assertEquals(3,sprint()); assertEquals(1,logs()); assertEquals(original,service.get("m1",id));
        assertEquals(409,review(id,"reject",null).getStatus());
    }

    @Test void permissionsPendingRejectionAndStaleExecutionCannotWrite() throws Exception {
        String id=saved();
        for(String role:java.util.List.of("member","viewer")) {
            role(role); assertEquals(403,review(id,"approve",null).getStatus()); assertEquals(403,execute(id).getStatus());
        }
        role("admin"); assertEquals(409,execute(id).getStatus());
        assertEquals(200,review(id,"approve",null).getStatus());
        jdbc.update("UPDATE stories SET sprint=3 WHERE id='US13'");
        assertEquals(409,execute(id).getStatus()); assertEquals(0,logs());
        jdbc.update("UPDATE stories SET sprint=1 WHERE id='US13'");
        var input=body();input.put("client_request_id","other");
        String other=mapper.readTree(submit(input).getContentAsByteArray()).path("id").asText();
        assertEquals(200,review(other,"reject",null).getStatus());
        assertEquals(409,execute(other).getStatus()); assertEquals(0,logs());
    }

    @Test void invalidEditsAndStaleApprovalRejected() throws Exception {
        String id=saved(); role("admin");
        for(int target:new int[]{0,1,2,5}) assertEquals(422,review(id,"modify_and_approve",target).getStatus());
        assertEquals(422,review(id,"approve",3).getStatus());
        jdbc.update("UPDATE stories SET sprint=3 WHERE id='US13'");
        assertEquals(409,review(id,"approve",null).getStatus());
        assertEquals(200,review(id,"reject",null).getStatus());
    }

    @Test void auditInsertFailureRollsBackSprintAndLog() throws Exception {
        String id=saved(); role("admin"); assertEquals(200,review(id,"approve",null).getStatus());
        jdbc.execute("ALTER TABLE meeting_planning_proposal_executions ADD CONSTRAINT force_failure CHECK(new_sprint=-1)");
        assertEquals(500,execute(id).getStatus()); assertEquals(1,sprint()); assertEquals(0,logs());
        jdbc.execute("ALTER TABLE meeting_planning_proposal_executions DROP CONSTRAINT force_failure");
        assertEquals(200,execute(id).getStatus()); assertEquals(2,sprint()); assertEquals(1,logs());
    }

    @Test void concurrentDuplicateExecutionHasOneSideEffect() throws Exception {
        String id=saved(); role("admin"); assertEquals(200,review(id,"approve",null).getStatus());
        var executorService=context.getBean(PlanningProposalExecutionService.class);
        var start=new CountDownLatch(1);
        try(var pool=Executors.newFixedThreadPool(2)) {
            Callable<JsonNode> run=()->{start.await();return executorService.execute("m1",id,mapper.readTree("{\"proposal_id\":\"p1\"}"),1);};
            var a=pool.submit(run);var b=pool.submit(run);start.countDown();
            assertEquals(a.get(10,TimeUnit.SECONDS),b.get(10,TimeUnit.SECONDS));
        }
        assertEquals(2,sprint());assertEquals(1,logs());
    }
}
