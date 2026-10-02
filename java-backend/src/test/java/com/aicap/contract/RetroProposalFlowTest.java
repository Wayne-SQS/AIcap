package com.aicap.contract;

import com.aicap.security.AuthContext;
import com.aicap.service.RetroProposalExecutionService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;
import java.util.concurrent.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;

class RetroProposalFlowTest extends RetroAnalysisStorageTest {
    String saved() throws Exception { return mapper.readTree(submit(body()).getContentAsByteArray()).path("id").asText(); }
    ObjectNode decision(String type) { return mapper.createObjectNode().put("proposal_id","p1").put("decision",type).put("reason","人工核对"); }
    ObjectNode amended() throws Exception {
        var input=decision("modify_and_approve"); input.set("changes",body().at("/result/proposed_actions/0/changes").deepCopy());
        ((ObjectNode)input.get("changes")).put("title","完善发布检查表").put("owner_id",2).put("deadline_text","下月底前"); return input;
    }
    org.springframework.mock.web.MockHttpServletResponse review(String id,JsonNode body) throws Exception {
        return mvc.perform(post(path+"/"+id+"/proposal-reviews").contentType("application/json").content(body.toString())).andReturn().getResponse();
    }
    org.springframework.mock.web.MockHttpServletResponse execute(String id) throws Exception {
        return mvc.perform(post(path+"/"+id+"/proposal-executions").contentType("application/json").content("{\"proposal_id\":\"p1\"}")).andReturn().getResponse();
    }
    int rows(String table) { return jdbc.queryForObject("SELECT COUNT(*) FROM "+table,Integer.class); }

    @Test void modifiedApprovalCreatesOneDurableActionAndAuditWithoutChangingOriginal() throws Exception {
        String id=saved(); var original=service.get("m1",id); role("owner"); var input=amended();
        var approved=review(id,input); assertEquals(200,approved.getStatus(),approved.getContentAsString());
        assertEquals(approved.getContentAsString(),review(id,input).getContentAsString());
        assertEquals(0,rows("action_items"));
        var executed=execute(id); assertEquals(200,executed.getStatus(),executed.getContentAsString());
        var result=mapper.readTree(executed.getContentAsByteArray());
        var item=jdbc.queryForMap("SELECT * FROM action_items");
        assertEquals("完善发布检查表",item.get("title")); assertEquals(2,((Number)item.get("owner_id")).intValue());
        assertEquals("下月底前",item.get("deadline_text")); assertEquals("open",item.get("status"));
        assertEquals(item.get("id"),result.path("action_item_id").asText());
        assertEquals(jdbc.queryForObject("SELECT id FROM action_item_logs",Integer.class),result.path("action_item_log_id").asInt());
        var audit=mapper.readTree(jdbc.queryForObject("SELECT approved_proposal_json FROM action_item_logs",String.class));
        assertEquals(input.get("changes"),audit.get("changes"));
        assertEquals(original,service.get("m1",id)); assertEquals(0,rows("story_logs"));
        assertEquals(1,jdbc.queryForObject("SELECT status FROM stories WHERE id='US13'",Integer.class));
        jdbc.update("UPDATE users SET display_name='新姓名' WHERE id=2");
        assertEquals(executed.getContentAsString(),execute(id).getContentAsString());
        assertEquals(1,rows("action_items")); assertEquals(1,rows("action_item_logs"));
        assertEquals(409,review(id,decision("reject")).getStatus());
    }

    @Test void approvePreservesExplicitUnknowns() throws Exception {
        var input=body(); ((ObjectNode)input.at("/result/proposed_actions/0/changes")).putNull("owner_id").putNull("deadline_text");
        String id=mapper.readTree(submit(input).getContentAsByteArray()).path("id").asText(); role("admin");
        assertEquals(200,review(id,decision("approve")).getStatus()); assertEquals(200,execute(id).getStatus());
        var item=jdbc.queryForMap("SELECT * FROM action_items"); assertNull(item.get("owner_id")); assertNull(item.get("deadline_text"));
    }

    @Test void permissionsPendingAndRejectionCannotCreateActions() throws Exception {
        String id=saved();
        for(String name:java.util.List.of("member","viewer")) {
            role(name); assertEquals(403,review(id,decision("approve")).getStatus()); assertEquals(403,execute(id).getStatus());
        }
        AuthContext.clear(); assertEquals(401,execute(id).getStatus()); assertEquals(401,review(id,decision("approve")).getStatus());
        role("admin"); assertEquals(409,execute(id).getStatus());
        assertEquals(200,review(id,decision("reject")).getStatus()); assertEquals(409,execute(id).getStatus()); assertEquals(0,rows("action_items"));
    }

    @Test void changedOrMissingOwnerBlocksApprovalAndExecution() throws Exception {
        String id=saved(); role("admin");
        jdbc.update("UPDATE users SET display_name='改名' WHERE id=1");
        assertEquals(409,review(id,decision("approve")).getStatus());
        var input=amended(); ((ObjectNode)input.get("changes")).put("owner_id",99);
        assertEquals(409,review(id,input).getStatus());
        assertEquals(200,review(id,amended()).getStatus());
        jdbc.update("DELETE FROM users WHERE id=2");
        assertEquals(409,execute(id).getStatus()); assertEquals(0,rows("action_items"));
    }

    @Test void invalidEditsAndExecutionPayloadOverridesRejected() throws Exception {
        String id=saved(); role("admin");
        var input=decision("modify_and_approve"); assertEquals(422,review(id,input).getStatus());
        input.set("changes",body().at("/result/proposed_actions/0/changes")); assertEquals(422,review(id,input).getStatus());
        input=amended(); input.put("reason"," "); assertEquals(422,review(id,input).getStatus());
        input=amended(); ((ObjectNode)input.get("changes")).put("approved",true); assertEquals(422,review(id,input).getStatus());
        input=amended(); ((ObjectNode)input.get("changes")).put("owner_id","2"); assertEquals(422,review(id,input).getStatus());
        assertEquals(422,mvc.perform(post(path+"/"+id+"/proposal-executions").contentType("application/json")
            .content("{\"proposal_id\":\"p1\",\"owner_id\":2}")).andReturn().getResponse().getStatus());
        assertEquals(0,rows("action_items"));
    }

    @Test void executionAuditFailureRollsBackActionAndLogThenRetryWorks() throws Exception {
        String id=saved(); role("admin"); assertEquals(200,review(id,decision("approve")).getStatus());
        jdbc.execute("ALTER TABLE meeting_retro_proposal_executions ADD CONSTRAINT force_failure CHECK(executed_by=-1)");
        assertEquals(500,execute(id).getStatus()); assertEquals(0,rows("action_items")); assertEquals(0,rows("action_item_logs"));
        assertEquals("not_started",jdbc.queryForObject("SELECT execution_status FROM meeting_retro_proposal_reviews",String.class));
        jdbc.execute("ALTER TABLE meeting_retro_proposal_executions DROP CONSTRAINT force_failure");
        assertEquals(200,execute(id).getStatus()); assertEquals(1,rows("action_items"));
    }

    @Test void concurrentDuplicateExecutionCreatesOneAction() throws Exception {
        String id=saved(); role("admin"); assertEquals(200,review(id,decision("approve")).getStatus());
        var executor=context.getBean(RetroProposalExecutionService.class);var start=new CountDownLatch(1);
        try(var pool=Executors.newFixedThreadPool(2)) {
            Callable<JsonNode> run=()->{start.await();return executor.execute("m1",id,mapper.readTree("{\"proposal_id\":\"p1\"}"),1);};
            var a=pool.submit(run);var b=pool.submit(run);start.countDown();assertEquals(a.get(10,TimeUnit.SECONDS),b.get(10,TimeUnit.SECONDS));
        }
        assertEquals(1,rows("action_items"));assertEquals(1,rows("action_item_logs"));assertEquals(1,rows("meeting_retro_proposal_executions"));
    }

    @Test void readOnlyActionQueriesAndMeetingScope() throws Exception {
        String id=saved();role("admin");assertEquals(200,review(id,decision("approve")).getStatus());
        var result=mapper.readTree(execute(id).getContentAsByteArray());
        String items="/api/meetings/m1/action-items";String logs=items+"/"+result.path("action_item_id").asText()+"/logs";
        role("viewer");assertEquals(200,mvc.perform(get(items)).andReturn().getResponse().getStatus());
        assertEquals(1,mapper.readTree(mvc.perform(get(logs)).andReturn().getResponse().getContentAsByteArray()).size());
        jdbc.update("INSERT INTO meetings VALUES ('m2','另一会议')");
        assertEquals(404,mvc.perform(get(logs.replace("/m1/","/m2/"))).andReturn().getResponse().getStatus());
        assertEquals(404,mvc.perform(get(path.replace("/m1/","/m2/")+"/"+id+"/proposal-executions")).andReturn().getResponse().getStatus());
        AuthContext.clear();assertEquals(401,mvc.perform(get(items)).andReturn().getResponse().getStatus());
        assertEquals(401,mvc.perform(get(logs)).andReturn().getResponse().getStatus());
    }
}
