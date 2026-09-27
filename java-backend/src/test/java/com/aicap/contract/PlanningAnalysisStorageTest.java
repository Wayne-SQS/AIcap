package com.aicap.contract;

import com.aicap.common.GlobalExceptionHandler;
import com.aicap.controller.PlanningAnalysisController;
import com.aicap.controller.PlanningProposalReviewController;
import com.aicap.controller.PlanningProposalExecutionController;
import com.aicap.service.PlanningProposalReviewService;
import com.aicap.service.PlanningProposalExecutionService;
import com.aicap.entity.User;
import com.aicap.security.AuthContext;
import com.aicap.service.PlanningAnalysisService;
import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.node.ObjectNode;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
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

/** Real JDBC transactions in isolated H2 MySQL mode; no production datasource or seed/reset scripts. */
class PlanningAnalysisStorageTest {
    @Configuration @EnableTransactionManagement @Import({PlanningAnalysisService.class,PlanningProposalReviewService.class,PlanningProposalExecutionService.class})
    static class Config {
        @Bean DataSource dataSource() {
            return new DriverManagerDataSource("jdbc:h2:mem:" + UUID.randomUUID() + ";MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1", "sa", "");
        }
        @Bean JdbcTemplate jdbc(DataSource ds) { return new JdbcTemplate(ds); }
        @Bean PlatformTransactionManager tx(DataSource ds) { return new DataSourceTransactionManager(ds); }
        @Bean ObjectMapper mapper() { return new ObjectMapper(); }
        @Bean Validator validator() { return Validation.buildDefaultValidatorFactory().getValidator(); }
    }
    AnnotationConfigApplicationContext context;
    JdbcTemplate jdbc;
    PlanningAnalysisService service;
    ObjectMapper mapper;
    MockMvc mvc;
    String path = "/api/meetings/m1/planning-analyses";

    @BeforeEach void setup() throws Exception {
        context = new AnnotationConfigApplicationContext(Config.class);
        jdbc = context.getBean(JdbcTemplate.class);
        service = context.getBean(PlanningAnalysisService.class);
        mapper = context.getBean(ObjectMapper.class);
        jdbc.execute("CREATE TABLE users(id int primary key)");
        jdbc.execute("CREATE TABLE meetings(id varchar(36) primary key,transcript longtext)");
        jdbc.execute("CREATE TABLE stories(id varchar(10) primary key,title varchar(200),status int,sprint int,owner_id int)");
        String ddl;
        try (var in = getClass().getResourceAsStream("/db/planning-analyses.sql")) {
            ddl = new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
        // Only MySQL-specific table encoding suffix differs; test the actual migration body twice.
        ddl = ddl.replace(" ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci", "");
        jdbc.execute(ddl);
        jdbc.execute(ddl);
        for (String resource : java.util.List.of("planning-proposal-reviews", "planning-proposal-executions")) {
            try (var in = getClass().getResourceAsStream("/db/" + resource + ".sql")) {
                jdbc.execute(new String(in.readAllBytes(), StandardCharsets.UTF_8)
                    .replace(" ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci", ""));
            }
        }
        jdbc.execute("CREATE TABLE story_logs(id int auto_increment primary key,story_id varchar(10),log_type varchar(20),detail text,user_id int,created_at timestamp)");
        jdbc.update("INSERT INTO users VALUES (1)");
        jdbc.update("INSERT INTO users VALUES (2)");
        jdbc.update("INSERT INTO meetings VALUES ('m1',?)", "决定US13移至Sprint 2。\n\nUS14 开始开发。");
        jdbc.update("INSERT INTO stories VALUES ('US13','登录',1,1,NULL)");
        jdbc.update("INSERT INTO stories VALUES ('US14','权限',0,2,NULL)");
        mvc = MockMvcBuilders.standaloneSetup(new PlanningAnalysisController(service),
                new PlanningProposalReviewController(context.getBean(PlanningProposalReviewService.class)),
                new PlanningProposalExecutionController(context.getBean(PlanningProposalExecutionService.class)))
                .setControllerAdvice(new GlobalExceptionHandler()).build();
        role("member");
    }

    @AfterEach void cleanup() {
        AuthContext.clear();
        if (jdbc != null) jdbc.execute("SHUTDOWN");
        if (context != null) context.close();
    }

    void role(String role) { User user = new User(); user.setId(1); user.setRole(role); AuthContext.set(user); }
    ObjectNode body() throws Exception {
        return (ObjectNode)mapper.readTree("""
            {"client_request_id":"request-1","result":{"schema_version":"1.0","meeting_id":"m1",
            "meeting_type":"sprint_planning","summary":"登录调整排期","open_questions":[],"proposed_actions":[
            {"proposal_id":"p1","action":"update_story_sprint","story_id":"US13","expected":{"sprint":1},
            "changes":{"sprint":2},"reason":"确认Sprint调整","evidence":[{"segment_id":"S1","quote":"决定US13移至Sprint 2。"}]}]}}
            """);
    }
    org.springframework.mock.web.MockHttpServletResponse submit(JsonNode body) throws Exception {
        return mvc.perform(post(path).contentType("application/json").content(body.toString())).andReturn().getResponse();
    }
    int count() { return jdbc.queryForObject("SELECT COUNT(*) FROM meeting_planning_analyses", Integer.class); }

    @Test void persistsAndQueriesOriginalResultSnapshotAndActorWithoutMovingStory() throws Exception {
        var input = body(); var response = submit(input);
        assertEquals(200,response.getStatus(),response.getContentAsString());
        var saved = mapper.readTree(response.getContentAsByteArray());
        assertEquals("pending",saved.path("status").asText());
        assertEquals(1,saved.path("submitted_by").asInt());
        assertEquals(input.get("result"),saved.get("result"));
        assertTrue(saved.get("story_snapshots").get(0).get("owner_id").isNull());
        jdbc.update("UPDATE stories SET title='changed',sprint=2 WHERE id='US13'");
        // Fresh JDBC-backed service read retains the original submission, not current project state.
        var fresh = new PlanningAnalysisService(jdbc, mapper, context.getBean(Validator.class));
        assertEquals(1,fresh.get("m1",saved.path("id").asText()).path("story_snapshots").get(0).path("sprint").asInt());
        assertEquals(200,mvc.perform(get(path+"/"+saved.path("id").asText())).andReturn().getResponse().getStatus());
        assertEquals(1,mapper.readTree(mvc.perform(get(path)).andReturn().getResponse().getContentAsByteArray()).size());
    }

    @Test void retryIsIdempotentEvenAfterLiveStateChangesButChangedPayloadConflicts() throws Exception {
        var input=body(); var first=submit(input);
        jdbc.update("UPDATE stories SET sprint=2 WHERE id='US13'");
        assertEquals(first.getContentAsString(),submit(input).getContentAsString());
        ((ObjectNode)input.get("result")).put("summary","changed");
        assertEquals(409,submit(input).getStatus()); assertEquals(1,count());
    }

    @Test void rejectsStaleMissingAndNoopTargets() throws Exception {
        var input=body(); ((ObjectNode)input.at("/result/proposed_actions/0/expected")).put("sprint",3);
        assertEquals(409,submit(input).getStatus());
        input=body(); ((ObjectNode)input.at("/result/proposed_actions/0")).put("story_id","US99");
        assertEquals(409,submit(input).getStatus());
        input=body(); ((ObjectNode)input.at("/result/proposed_actions/0/changes")).put("sprint",1);
        assertEquals(422,submit(input).getStatus()); assertEquals(0,count());
    }

    @Test void rejectsWrongEvidenceUnknownFieldsAndScalarCoercion() throws Exception {
        var input=body(); ((ObjectNode)input.at("/result/proposed_actions/0/evidence/0")).put("segment_id","S3");
        assertEquals(422,submit(input).getStatus());
        for (JsonNode status : java.util.List.of(mapper.readTree("\"2\""),mapper.readTree("true"),mapper.readTree("2.0"))) {
            input=body(); ((ObjectNode)input.at("/result/proposed_actions/0/changes")).set("sprint",status);
            assertEquals(422,submit(input).getStatus());
        }
        input=body(); input.put("submitted_by",99); assertEquals(422,submit(input).getStatus());
        input=body(); ((ObjectNode)input.get("result")).put("approved",true); assertEquals(422,submit(input).getStatus());
        assertEquals(0,count());
    }

    @Test void invalidLaterProposalRollsBackEntireBatch() throws Exception {
        var input=body(); var proposals=(com.fasterxml.jackson.databind.node.ArrayNode)input.at("/result/proposed_actions");
        var second=proposals.get(0).deepCopy(); ((ObjectNode)second).put("proposal_id","p2").put("story_id","US14");
        proposals.add(second); assertEquals(409,submit(input).getStatus()); assertEquals(0,count());
        assertEquals(1,jdbc.queryForObject("SELECT status FROM stories WHERE id='US13'",Integer.class));
    }

    @Test void permissionsAndMeetingScopedQueries() throws Exception {
        role("viewer"); assertEquals(403,submit(body()).getStatus());
        assertEquals(200,mvc.perform(get(path)).andReturn().getResponse().getStatus());
        AuthContext.clear(); assertEquals(401,submit(body()).getStatus());
        assertEquals(401,mvc.perform(get(path)).andReturn().getResponse().getStatus());
        role("owner"); var saved=mapper.readTree(submit(body()).getContentAsByteArray());
        assertEquals(404,mvc.perform(get("/api/meetings/m2/planning-analyses/"+saved.path("id").asText())).andReturn().getResponse().getStatus());
    }

    @Test void emptyAnalysisIsStoredAsNoChanges() throws Exception {
        var input=body(); ((ObjectNode)input.get("result")).putArray("proposed_actions");
        var response=submit(input); assertEquals(200,response.getStatus());
        assertEquals("no_changes",mapper.readTree(response.getContentAsByteArray()).path("status").asText());
    }

    @Test void requestLookupIsScopedToCurrentUserAndRequiresWriter() throws Exception {
        var saved=mapper.readTree(submit(body()).getContentAsByteArray());
        var response=mvc.perform(get(path+"/by-request/request-1")).andReturn().getResponse();
        assertEquals(200,response.getStatus());
        assertEquals(saved.path("id"),mapper.readTree(response.getContentAsByteArray()).path("id"));
        User another = new User(); another.setId(2); another.setRole("member"); AuthContext.set(another);
        assertEquals(404,mvc.perform(get(path+"/by-request/request-1")).andReturn().getResponse().getStatus());
        role("viewer");
        assertEquals(403,mvc.perform(get(path+"/by-request/request-1")).andReturn().getResponse().getStatus());
    }

    @Test void concurrentRetriesCreateOnlyOneRecord() throws Exception {
        var input=body(); var start=new CountDownLatch(1);
        try (var executor=Executors.newFixedThreadPool(2)) {
            Callable<String> task=()->{ start.await(); return service.save("m1",input,1).path("id").asText(); };
            var a=executor.submit(task); var b=executor.submit(task); start.countDown();
            assertEquals(a.get(10,TimeUnit.SECONDS),b.get(10,TimeUnit.SECONDS)); assertEquals(1,count());
        }
    }
}


