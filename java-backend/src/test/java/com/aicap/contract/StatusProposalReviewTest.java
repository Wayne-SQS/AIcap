package com.aicap.contract;

import com.aicap.entity.User;
import com.aicap.security.AuthContext;
import com.aicap.service.StatusProposalReviewService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;
import java.util.concurrent.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;

/** Inherits the nine storage regressions and their isolated transactional H2 harness. */
class StatusProposalReviewTest extends StatusAnalysisStorageTest {
    String savedAnalysis() throws Exception { return mapper.readTree(submit(body()).getContentAsByteArray()).path("id").asText(); }
    String reviewPath(String id) { return path+"/"+id+"/proposal-reviews"; }
    ObjectNode reviewBody(String decision) {
        var input=mapper.createObjectNode();
        input.put("proposal_id","p1").put("decision",decision).put("reason","人工确认");
        return input;
    }
    org.springframework.mock.web.MockHttpServletResponse review(String id,JsonNode body) throws Exception {
        return mvc.perform(post(reviewPath(id)).contentType("application/json").content(body.toString())).andReturn().getResponse();
    }
    int reviewCount() { return jdbc.queryForObject("SELECT COUNT(*) FROM meeting_status_proposal_reviews",Integer.class); }

    @Test void approveRecordsReviewerWithoutChangingStoryOrOriginalAnalysis() throws Exception {
        String id=savedAnalysis(); var original=service.get("m1",id); role("admin");
        var response=review(id,reviewBody("approve")); assertEquals(200,response.getStatus(),response.getContentAsString());
        var result=mapper.readTree(response.getContentAsByteArray());
        assertEquals("approved",result.path("status").asText());
        assertEquals(1,result.path("reviewed_by").asInt());
        assertEquals("not_started",result.path("execution_status").asText());
        assertEquals(result.get("original_proposal"),result.get("approved_proposal"));
        assertEquals(1,jdbc.queryForObject("SELECT status FROM stories WHERE id='US13'",Integer.class));
        assertEquals(original,service.get("m1",id));
        var listing=mapper.readTree(mvc.perform(get(reviewPath(id))).andReturn().getResponse().getContentAsByteArray());
        assertEquals("reviewed",listing.path("review_status").asText());
    }

    @Test void modifiedApprovalRetainsOriginalAndHumanExplanation() throws Exception {
        String id=savedAnalysis(); role("owner"); var input=reviewBody("modify_and_approve");
        input.putObject("changes").put("status",0);
        var response=review(id,input); assertEquals(200,response.getStatus(),response.getContentAsString());
        var result=mapper.readTree(response.getContentAsByteArray());
        assertEquals(2,result.at("/original_proposal/changes/status").asInt());
        assertEquals(0,result.at("/approved_proposal/changes/status").asInt());
        assertEquals("人工确认",result.path("reason").asText());
        assertEquals(result.at("/original_proposal/evidence"),result.at("/approved_proposal/evidence"));
        assertEquals(1,jdbc.queryForObject("SELECT status FROM stories WHERE id='US13'",Integer.class));
    }

    @Test void staleOrDeletedStoryCannotBeApprovedButCanBeRejected() throws Exception {
        String id=savedAnalysis(); role("admin");
        jdbc.update("UPDATE stories SET status=0 WHERE id='US13'");
        assertEquals(409,review(id,reviewBody("approve")).getStatus()); assertEquals(0,reviewCount());
        jdbc.update("DELETE FROM stories WHERE id='US13'");
        assertEquals(409,review(id,reviewBody("approve")).getStatus());
        var rejected=review(id,reviewBody("reject")); assertEquals(200,rejected.getStatus());
        var result=mapper.readTree(rejected.getContentAsByteArray());
        assertTrue(result.get("approved_proposal").isNull());
        assertEquals("not_applicable",result.path("execution_status").asText());
    }

    @Test void reviewRetryIsImmutableAndPreservesFirstReviewer() throws Exception {
        String id=savedAnalysis(); role("admin"); var input=reviewBody("approve");
        var first=review(id,input);
        jdbc.update("UPDATE stories SET status=0 WHERE id='US13'");
        assertEquals(first.getContentAsString(),review(id,input).getContentAsString());
        assertEquals(409,review(id,reviewBody("reject")).getStatus());
        input.put("reason","changed"); assertEquals(409,review(id,input).getStatus());
        User second=new User(); second.setId(2); second.setRole("owner"); AuthContext.set(second);
        assertEquals(409,review(id,reviewBody("approve")).getStatus()); assertEquals(1,reviewCount());
    }

    @Test void reviewRejectsInvalidEditsAndAuthorityFields() throws Exception {
        String id=savedAnalysis(); role("admin");
        for(String decision:java.util.List.of("approve","reject")) {
            var input=reviewBody(decision); input.putObject("changes").put("status",0);
            assertEquals(422,review(id,input).getStatus());
        }
        assertEquals(422,review(id,reviewBody("modify_and_approve")).getStatus());
        for(JsonNode value:java.util.List.of(mapper.readTree("3"),mapper.readTree("1"),mapper.readTree("2"),mapper.readTree("\"0\""),mapper.readTree("true"),mapper.readTree("0.0"))) {
            var input=reviewBody("modify_and_approve"); input.putObject("changes").set("status",value);
            assertEquals(422,review(id,input).getStatus());
        }
        var input=reviewBody("modify_and_approve"); input.putObject("changes").put("status",0); input.put("reason"," ");
        assertEquals(422,review(id,input).getStatus());
        for(String field:java.util.List.of("reviewed_by","story_id","evidence","execution_status")) {
            input=reviewBody("approve"); input.put(field,"forged"); assertEquals(422,review(id,input).getStatus());
        }
        assertEquals(0,reviewCount());
    }

    @Test void reviewPermissionsAndProposalScopeAreEnforced() throws Exception {
        String id=savedAnalysis();
        for(String role:java.util.List.of("member","viewer")) {
            role(role); assertEquals(403,review(id,reviewBody("approve")).getStatus());
            assertEquals(200,mvc.perform(get(reviewPath(id))).andReturn().getResponse().getStatus());
        }
        AuthContext.clear(); assertEquals(401,review(id,reviewBody("approve")).getStatus());
        assertEquals(401,mvc.perform(get(reviewPath(id))).andReturn().getResponse().getStatus());
        role("admin"); var input=reviewBody("approve"); input.put("proposal_id","missing");
        assertEquals(404,review(id,input).getStatus());
        assertEquals(404,mvc.perform(post("/api/meetings/m2/status-analyses/"+id+"/proposal-reviews").contentType("application/json").content(reviewBody("approve").toString())).andReturn().getResponse().getStatus());
        assertEquals(0,reviewCount());
    }

    @Test void reviewListingShowsPartialPendingAndNoChangeAnalyses() throws Exception {
        var input=body(); var proposals=(com.fasterxml.jackson.databind.node.ArrayNode)input.at("/result/proposed_actions");
        var second=(ObjectNode)proposals.get(0).deepCopy(); second.put("proposal_id","p2").put("story_id","US14");
        second.putObject("expected").put("status",0); second.putObject("changes").put("status",1);
        second.putArray("evidence").addObject().put("segment_id","S3").put("quote","US14 开始开发。");
        proposals.add(second);
        String id=mapper.readTree(submit(input).getContentAsByteArray()).path("id").asText(); role("owner");
        var reviews=context.getBean(StatusProposalReviewService.class);
        assertEquals("pending",reviews.list("m1",id).path("review_status").asText());
        assertEquals(200,review(id,reviewBody("reject")).getStatus());
        var result=reviews.list("m1",id);
        assertEquals("partially_reviewed",result.path("review_status").asText());
        assertEquals("pending",result.path("proposals").get(1).path("status").asText());
        input.put("client_request_id","empty-analysis"); proposals.removeAll();
        String emptyId=mapper.readTree(submit(input).getContentAsByteArray()).path("id").asText();
        assertEquals("no_changes",reviews.list("m1",emptyId).path("review_status").asText());
        assertEquals(404,review(emptyId,reviewBody("approve")).getStatus());
    }

    @Test void concurrentConflictingReviewsProduceOneDecision() throws Exception {
        String id=savedAnalysis(); var start=new CountDownLatch(1);
        var reviews=context.getBean(StatusProposalReviewService.class);
        try(var executor=Executors.newFixedThreadPool(2)) {
            Callable<Integer> approve=()->{start.await(); try { reviews.review("m1",id,reviewBody("approve"),1); return 200; } catch(com.aicap.common.ApiException e) { return e.getStatus(); }};
            Callable<Integer> reject=()->{start.await(); try { reviews.review("m1",id,reviewBody("reject"),1); return 200; } catch(com.aicap.common.ApiException e) { return e.getStatus(); }};
            var a=executor.submit(approve); var b=executor.submit(reject); start.countDown();
            var statuses=new java.util.HashSet<>(java.util.List.of(a.get(10,TimeUnit.SECONDS),b.get(10,TimeUnit.SECONDS)));
            assertEquals(java.util.Set.of(200,409),statuses); assertEquals(1,reviewCount());
        }
    }
}
