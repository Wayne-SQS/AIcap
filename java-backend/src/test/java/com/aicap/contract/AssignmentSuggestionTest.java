package com.aicap.contract;

import com.aicap.common.*;
import com.aicap.controller.AssignmentSuggestionController;
import com.aicap.entity.User;
import com.aicap.security.AuthContext;
import com.aicap.service.AssignmentSuggestionService;
import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.*;
import org.springframework.context.annotation.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.*;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.EnableTransactionManagement;
import javax.sql.DataSource;
import java.nio.charset.StandardCharsets;
import java.util.UUID;
import java.util.concurrent.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;

class AssignmentSuggestionTest {
    @Configuration @EnableTransactionManagement @Import(AssignmentSuggestionService.class)
    static class Config {
        @Bean DataSource ds() { return new DriverManagerDataSource("jdbc:h2:mem:"+UUID.randomUUID()+";MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1","sa",""); }
        @Bean JdbcTemplate jdbc(DataSource ds) { return new JdbcTemplate(ds); }
        @Bean PlatformTransactionManager tx(DataSource ds) { return new DataSourceTransactionManager(ds); }
        @Bean ObjectMapper mapper() { return new ObjectMapper(); }
    }
    AnnotationConfigApplicationContext context;
    JdbcTemplate jdbc; ObjectMapper mapper; AssignmentSuggestionService service; MockMvc mvc;
    String path="/api/meetings/m1/assignment-suggestions";
    @BeforeEach void setup() throws Exception {
        context=new AnnotationConfigApplicationContext(Config.class);
        jdbc=context.getBean(JdbcTemplate.class); mapper=context.getBean(ObjectMapper.class); service=context.getBean(AssignmentSuggestionService.class);
        jdbc.execute("CREATE TABLE users(id int primary key,role varchar(20))");
        jdbc.execute("CREATE TABLE meetings(id varchar(36) primary key)");
        jdbc.execute("CREATE TABLE stories(id varchar(10) primary key,title varchar(200),status int,sprint int,owner_id int)");
        jdbc.execute("CREATE TABLE story_logs(id int auto_increment primary key,story_id varchar(10),log_type varchar(20),detail text,user_id int,created_at timestamp)");
        try(var in=getClass().getResourceAsStream("/db/assignment-analyses.sql")) {
            var sql=new String(in.readAllBytes(),StandardCharsets.UTF_8).replace(" ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci","");
            jdbc.execute(sql); jdbc.execute(sql);
        }
        jdbc.update("INSERT INTO users VALUES (1,'admin'),(7,'member'),(8,'viewer')");
        jdbc.update("INSERT INTO meetings VALUES ('m1'),('m2')");
        jdbc.update("INSERT INTO stories VALUES ('US13','登录',0,2,NULL)");
        mvc=MockMvcBuilders.standaloneSetup(new AssignmentSuggestionController(service)).setControllerAdvice(new GlobalExceptionHandler()).build();
        role("member");
    }
    @AfterEach void close() { AuthContext.clear(); jdbc.execute("SHUTDOWN"); context.close(); }
    void role(String role) { var user=new User(); user.setId(1); user.setRole(role); AuthContext.set(user); }
    ObjectNode body() throws Exception {
        return (ObjectNode)mapper.readTree("""
          {"client_request_id":"r1","input":{"story_ids":["US13"],"target_sprint":2,
          "requirements":[{"dimension":"tech_stack","name":"Python","minimum_level":3}]},
          "result":{"rule_version":"assignment-skills-v1","status":"provisional","requirements_source":"caller_supplied",
          "writes_performed":false,"excluded_viewer_ids":[],
          "context":{"meeting_id":"m1","target_sprint":2,"scope":"read_only_preparation","snapshot_consistency":"sequential_reads",
          "selected_stories":[{"id":"US13","title":"登录","status":0,"sprint":2,"owner_id":null}],
          "members":[{"profile":{"user_id":7,"display_name":"成员甲","role":"member"}}],"tasks":[],"gaps":[]},
          "candidates":[{"member_id":7,"display_name":"成员甲","rank":1,"matched_requirements":1,"total_requirements":1,
          "capacity_check":"unknown","suitability":"requires_human_review",
          "matches":[{"requirement":{"dimension":"tech_stack","name":"Python","minimum_level":3},"recorded_level":3,"meets_requirement":true}]}]}}
          """);
    }
    ObjectNode decision(boolean approve) throws Exception {
        return (ObjectNode)mapper.readTree(approve ? """
          {"decision":"approve","member_id":7,"reason":"已人工核对分工；容量仍待执行前确认","capacity_acknowledged":true}
          """ : """
          {"decision":"reject","member_id":null,"reason":"暂不分配","capacity_acknowledged":false}
          """);
    }
    int postStatus(String url,JsonNode data) throws Exception { return mvc.perform(post(url).contentType("application/json").content(data.toString())).andReturn().getResponse().getStatus(); }
    @Test void savesListsAndRetriesOriginalSnapshotAfterBusinessChanges() throws Exception {
        var input=body(); var saved=service.save("m1",input,1);
        jdbc.update("UPDATE stories SET owner_id=8 WHERE id='US13'");
        assertEquals(saved,service.save("m1",input,1));
        assertEquals(saved,service.find("m1","r1",1));
        assertEquals(1,service.list("m1").size());
        assertTrue(service.list("m2").isEmpty());
        assertEquals(404,assertThrows(ApiException.class,()->service.get("m2",saved.path("id").asText())).getStatus());
        assertEquals(404,assertThrows(ApiException.class,()->service.find("m1","r1",7)).getStatus());
        ((ObjectNode)input.path("input")).put("target_sprint",3);
        ((ObjectNode)input.path("result").path("context")).put("target_sprint",3);
        assertEquals(409,assertThrows(ApiException.class,()->service.save("m1",input,1)).getStatus());
    }
    @Test void concurrentSavesProduceOneRecord() throws Exception {
        var input=body(); var pool=Executors.newFixedThreadPool(2);
        try {
            var a=pool.submit(()->service.save("m1",input,1)); var b=pool.submit(()->service.save("m1",input,1));
            assertEquals(a.get(5,TimeUnit.SECONDS),b.get(5,TimeUnit.SECONDS));
            assertEquals(1,service.list("m1").size());
        } finally { pool.shutdownNow(); }
    }
    @Test void roleGuardsAndUnauthenticatedAccess() throws Exception {
        role("viewer"); assertEquals(403,postStatus(path,body()));
        role("member"); var saved=service.save("m1",body(),1); String url=path+"/"+saved.path("id").asText()+"/review";
        assertEquals(403,postStatus(url,decision(true)));
        role("owner"); assertEquals(200,postStatus(url,decision(true)));
        AuthContext.clear(); assertEquals(401,mvc.perform(get(path)).andReturn().getResponse().getStatus());
    }
    @Test void approvalIsImmutableIdempotentAndDoesNotAssignOwner() throws Exception {
        String id=service.save("m1",body(),1).path("id").asText(); var input=decision(true);
        var approved=service.review("m1",id,input,1);
        assertEquals("approved",approved.path("status").asText());
        assertEquals("not_started",approved.path("execution_status").asText());
        assertEquals(approved,service.review("m1",id,input,1));
        assertEquals(approved,service.get("m1",id).path("review"));
        assertNull(jdbc.queryForMap("SELECT owner_id FROM stories WHERE id='US13'").get("owner_id"));
        assertEquals(409,assertThrows(ApiException.class,()->service.review("m1",id,decision(false),1)).getStatus());
        assertEquals(409,assertThrows(ApiException.class,()->service.review("m1",id,input,7)).getStatus());
    }
    @Test void rejectionWorksAfterStoryRemovedAndCannotBecomeApproval() throws Exception {
        String id=service.save("m1",body(),1).path("id").asText();
        jdbc.update("DELETE FROM stories");
        assertEquals("not_applicable",service.review("m1",id,decision(false),1).path("execution_status").asText());
        assertEquals(409,assertThrows(ApiException.class,()->service.review("m1",id,decision(true),1)).getStatus());
    }
    @Test void staleStoryOrIneligibleMemberBlocksApprovalWithoutDecision() throws Exception {
        String id=service.save("m1",body(),1).path("id").asText();
        jdbc.update("UPDATE stories SET owner_id=8");
        assertEquals(409,assertThrows(ApiException.class,()->service.review("m1",id,decision(true),1)).getStatus());
        jdbc.update("UPDATE stories SET owner_id=NULL"); jdbc.update("UPDATE users SET role='viewer' WHERE id=7");
        assertEquals(409,assertThrows(ApiException.class,()->service.review("m1",id,decision(true),1)).getStatus());
        assertTrue(service.get("m1",id).path("review").isNull());
    }
    @Test void approvalNeedsCandidateReasonAndUnknownCapacityAcknowledgement() throws Exception {
        String id=service.save("m1",body(),1).path("id").asText();
        for(String fault:java.util.List.of("member","reason","capacity","extra")) {
            var input=decision(true);
            switch(fault) { case "member" -> input.put("member_id",8); case "reason" -> input.put("reason"," "); case "capacity" -> input.put("capacity_acknowledged",false); default -> input.put("owner_id",7); }
            assertEquals(422,assertThrows(ApiException.class,()->service.review("m1",id,input,1)).getStatus());
        }
        assertTrue(service.get("m1",id).path("review").isNull());
    }
    @Test void malformedEvidenceRankOrMeetingNeverPersists() throws Exception {
        for(String fault:java.util.List.of("rank","level","meeting","extra")) {
            var input=body();
            switch(fault) {
                case "rank" -> ((ObjectNode)input.path("result").path("candidates").get(0)).put("rank",2);
                case "level" -> ((ObjectNode)input.path("result").path("candidates").get(0).path("matches").get(0)).put("recorded_level",1);
                case "meeting" -> ((ObjectNode)input.path("result").path("context")).put("meeting_id","m2");
                default -> input.put("approved",true);
            }
            assertEquals(422,postStatus(path,input));
        }
        assertTrue(service.list("m1").isEmpty());
    }
    @Test void changedStoryCannotBeSavedAndMeetingForeignKeyRetainsAudit() throws Exception {
        var input=body(); jdbc.update("UPDATE stories SET status=1");
        assertEquals(409,assertThrows(ApiException.class,()->service.save("m1",input,1)).getStatus());
        jdbc.update("UPDATE stories SET status=0"); service.save("m1",input,1);
        assertThrows(org.springframework.dao.DataIntegrityViolationException.class,()->jdbc.update("DELETE FROM meetings WHERE id='m1'"));
    }
    @Test void executionChangesOnlyOwnerAndRetryCreatesOneLog() throws Exception {
        String id=service.save("m1",body(),1).path("id").asText();
        service.review("m1",id,decision(true),1);
        var executed=service.execute("m1",id,mapper.createObjectNode(),1);
        assertEquals(7,jdbc.queryForObject("SELECT owner_id FROM stories WHERE id='US13'",Integer.class));
        assertEquals(0,jdbc.queryForObject("SELECT status FROM stories WHERE id='US13'",Integer.class));
        assertEquals(executed,service.execute("m1",id,mapper.createObjectNode(),1));
        assertEquals(1,jdbc.queryForObject("SELECT COUNT(*) FROM story_logs",Integer.class));
        assertEquals(executed,service.get("m1",id).path("review").path("execution"));
        assertEquals("succeeded",service.review("m1",id,decision(true),1).path("execution_status").asText());
    }
    @Test void concurrentExecutionProducesOneAudit() throws Exception {
        String id=service.save("m1",body(),1).path("id").asText(); service.review("m1",id,decision(true),1);
        var pool=Executors.newFixedThreadPool(2);
        try {
            var a=pool.submit(()->service.execute("m1",id,mapper.createObjectNode(),1));
            var b=pool.submit(()->service.execute("m1",id,mapper.createObjectNode(),1));
            assertEquals(a.get(5,TimeUnit.SECONDS),b.get(5,TimeUnit.SECONDS));
            assertEquals(1,jdbc.queryForObject("SELECT COUNT(*) FROM story_logs",Integer.class));
        } finally { pool.shutdownNow(); }
    }
    @Test void unapprovedRejectedWrongScopeAndClientOverridesCannotExecute() throws Exception {
        String id=service.save("m1",body(),1).path("id").asText();
        assertEquals(409,assertThrows(ApiException.class,()->service.execute("m1",id,mapper.createObjectNode(),1)).getStatus());
        service.review("m1",id,decision(false),1);
        assertEquals(409,assertThrows(ApiException.class,()->service.execute("m1",id,mapper.createObjectNode(),1)).getStatus());
        assertEquals(404,assertThrows(ApiException.class,()->service.execute("m2",id,mapper.createObjectNode(),1)).getStatus());
        assertEquals(422,assertThrows(ApiException.class,()->service.execute("m1",id,mapper.createObjectNode().put("owner_id",8),1)).getStatus());
        role("member"); assertEquals(403,postStatus(path+"/"+id+"/execute",mapper.createObjectNode()));
        assertEquals(0,jdbc.queryForObject("SELECT COUNT(*) FROM story_logs",Integer.class));
    }
    @Test void postApprovalBusinessOrRoleChangeBlocksExecution() throws Exception {
        String id=service.save("m1",body(),1).path("id").asText(); service.review("m1",id,decision(true),1);
        jdbc.update("UPDATE stories SET owner_id=8");
        assertEquals(409,assertThrows(ApiException.class,()->service.execute("m1",id,mapper.createObjectNode(),1)).getStatus());
        jdbc.update("UPDATE stories SET owner_id=NULL"); jdbc.update("UPDATE users SET role='viewer' WHERE id=7");
        assertEquals(409,assertThrows(ApiException.class,()->service.execute("m1",id,mapper.createObjectNode(),1)).getStatus());
        assertEquals("not_started",service.get("m1",id).path("review").path("execution_status").asText());
    }
    @Test void logFailureRollsBackOwnerAndExecutionRecord() throws Exception {
        String id=service.save("m1",body(),1).path("id").asText(); service.review("m1",id,decision(true),1);
        jdbc.execute("ALTER TABLE story_logs ADD CONSTRAINT reject_assignment CHECK (user_id < 0)");
        assertThrows(org.springframework.dao.DataIntegrityViolationException.class,()->service.execute("m1",id,mapper.createObjectNode(),1));
        assertNull(jdbc.queryForMap("SELECT owner_id FROM stories").get("owner_id"));
        assertEquals("not_started",service.get("m1",id).path("review").path("execution_status").asText());
    }
}
