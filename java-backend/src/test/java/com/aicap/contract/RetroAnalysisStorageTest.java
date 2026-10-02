package com.aicap.contract;

import com.aicap.common.GlobalExceptionHandler;
import com.aicap.controller.RetroAnalysisController;
import com.aicap.controller.RetroProposalReviewController;
import com.aicap.controller.RetroProposalExecutionController;
import com.aicap.controller.ActionItemController;
import com.aicap.service.RetroProposalReviewService;
import com.aicap.service.RetroProposalExecutionService;
import com.aicap.entity.User;
import com.aicap.security.AuthContext;
import com.aicap.service.RetroAnalysisService;
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
class RetroAnalysisStorageTest {
    @Configuration @EnableTransactionManagement @Import({RetroAnalysisService.class,RetroProposalReviewService.class,RetroProposalExecutionService.class})
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
    RetroAnalysisService service;
    ObjectMapper mapper;
    MockMvc mvc;
    String path = "/api/meetings/m1/retro-analyses";

    @BeforeEach void setup() throws Exception {
        context = new AnnotationConfigApplicationContext(Config.class);
        jdbc = context.getBean(JdbcTemplate.class);
        service = context.getBean(RetroAnalysisService.class);
        mapper = context.getBean(ObjectMapper.class);
        jdbc.execute("CREATE TABLE users(id int primary key,display_name varchar(50))");
        jdbc.execute("CREATE TABLE meetings(id varchar(36) primary key,transcript longtext)");
        jdbc.execute("CREATE TABLE stories(id varchar(10) primary key,title varchar(200),status int,sprint int,owner_id int)");
        String ddl;
        try (var in = getClass().getResourceAsStream("/db/retro-analyses.sql")) {
            ddl = new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
        // Only MySQL-specific table encoding suffix differs; test the actual migration body twice.
        ddl = ddl.replace(" ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci", "");
        jdbc.execute(ddl);
        jdbc.execute(ddl);
        for(String resource:java.util.List.of("retro-proposal-reviews","action-items","retro-proposal-executions")) {
            try(var in=getClass().getResourceAsStream("/db/"+resource+".sql")) {
                String sql=new String(in.readAllBytes(),StandardCharsets.UTF_8)
                    .replace(" ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci","");
                for(String statement:sql.split(";")) if(!statement.isBlank()) { jdbc.execute(statement); jdbc.execute(statement); }
            }
        }
        jdbc.execute("CREATE TABLE story_logs(id int auto_increment primary key,story_id varchar(10),log_type varchar(20),detail text,user_id int,created_at timestamp)");
        jdbc.update("INSERT INTO users VALUES (1,'张敏')");
        jdbc.update("INSERT INTO users VALUES (2,'李明')");
        jdbc.update("INSERT INTO meetings VALUES ('m1',?)", "复盘发布过程。\n\n决定由张敏下周五前补充发布检查表。");
        jdbc.update("INSERT INTO stories VALUES ('US13','登录',1,1,NULL)");
        jdbc.update("INSERT INTO stories VALUES ('US14','权限',0,2,NULL)");
        mvc = MockMvcBuilders.standaloneSetup(new RetroAnalysisController(service),
                new RetroProposalReviewController(context.getBean(RetroProposalReviewService.class)),
                new RetroProposalExecutionController(context.getBean(RetroProposalExecutionService.class)),
                new ActionItemController(jdbc))
                .setControllerAdvice(new GlobalExceptionHandler()).build();
        role("member");
    }

    @AfterEach void cleanup() {
        AuthContext.clear();
        if (jdbc != null) jdbc.execute("SHUTDOWN");
        if (context != null) context.close();
    }

    void role(String role) { User user=new User(); user.setId(1); user.setRole(role); AuthContext.set(user); }
    ObjectNode body() throws Exception {
        return (ObjectNode)mapper.readTree("""
            {"client_request_id":"retro-1","result":{"schema_version":"1.0","meeting_id":"m1",
            "meeting_type":"sprint_retrospective","summary":"改进发布过程","open_questions":[],
            "decisions":[{"text":"补充检查表","evidence":[{"segment_id":"S3","quote":"决定由张敏下周五前补充发布检查表。"}]}],
            "proposed_actions":[{"proposal_id":"p1","action":"create_action_item",
            "changes":{"title":"补充发布检查表","description":"整理步骤","owner_id":1,"deadline_text":"下周五前"},
            "reason":"复盘决定","evidence":[{"segment_id":"S3","quote":"决定由张敏下周五前补充发布检查表。"}]}]}}
            """);
    }
    org.springframework.mock.web.MockHttpServletResponse submit(JsonNode input) throws Exception {
        return mvc.perform(post(path).contentType("application/json").content(input.toString())).andReturn().getResponse();
    }
    int count() { return jdbc.queryForObject("SELECT COUNT(*) FROM meeting_retro_analyses",Integer.class); }

    @Test void savesOriginalDecisionsActionsAndMemberSnapshotWithoutProjectWrites() throws Exception {
        var input=body(); var response=submit(input); assertEquals(200,response.getStatus(),response.getContentAsString());
        var saved=mapper.readTree(response.getContentAsByteArray());
        assertEquals(input.get("result"),saved.get("result"));
        assertEquals("pending",saved.path("status").asText());
        assertEquals("张敏",saved.at("/member_snapshots/0/display_name").asText());
        assertEquals(1,jdbc.queryForObject("SELECT status FROM stories WHERE id='US13'",Integer.class));
        assertEquals(0,jdbc.queryForObject("SELECT COUNT(*) FROM story_logs",Integer.class));
        jdbc.update("UPDATE users SET display_name='改名' WHERE id=1");
        assertEquals(saved,service.get("m1",saved.path("id").asText()));
        assertEquals(response.getContentAsString(),submit(input).getContentAsString());
        assertEquals(1,service.list("m1").size());
        ((ObjectNode)input.get("result")).put("summary","不同结果");
        assertEquals(409,submit(input).getStatus()); assertEquals(1,count());
    }

    @Test void nullUnknownsAndDecisionOnlyAnalysisAreSupported() throws Exception {
        var input=body(); var changes=(ObjectNode)input.at("/result/proposed_actions/0/changes");
        changes.putNull("owner_id").putNull("deadline_text");
        var saved=mapper.readTree(submit(input).getContentAsByteArray());
        assertEquals(0,saved.path("member_snapshots").size());
        input=body(); input.put("client_request_id","decisions-only"); ((ObjectNode)input.get("result")).putArray("proposed_actions");
        saved=mapper.readTree(submit(input).getContentAsByteArray());
        assertEquals("no_changes",saved.path("status").asText());
        assertEquals(1,saved.at("/result/decisions").size());
    }

    @Test void rejectsInvalidDecisionAndActionEvidenceAndInferredDeadline() throws Exception {
        for(String pointer:java.util.List.of("/result/decisions/0/evidence/0","/result/proposed_actions/0/evidence/0")) {
            var input=body(); ((ObjectNode)input.at(pointer)).put("segment_id","S2");
            assertEquals(422,submit(input).getStatus());
        }
        var input=body(); ((ObjectNode)input.at("/result/proposed_actions/0/changes")).put("deadline_text","2026-10-02");
        assertEquals(422,submit(input).getStatus()); assertEquals(0,count());
    }

    @Test void missingAmbiguousAndUnquotedOwnersConflict() throws Exception {
        for(int owner: new int[]{99,2}) {
            var input=body(); ((ObjectNode)input.at("/result/proposed_actions/0/changes")).put("owner_id",owner);
            assertEquals(409,submit(input).getStatus());
        }
        jdbc.update("UPDATE users SET display_name='张敏' WHERE id=2");
        assertEquals(409,submit(body()).getStatus()); assertEquals(0,count());
    }

    @Test void strictTypesRequiredUnknownsAndExtraFieldsRejected() throws Exception {
        for(String scalar:java.util.List.of("true","1.0","\"1\"")) {
            var input=body(); ((ObjectNode)input.at("/result/proposed_actions/0/changes")).set("owner_id",mapper.readTree(scalar));
            assertEquals(422,submit(input).getStatus());
        }
        for(String field:java.util.List.of("owner_id","deadline_text")) {
            var input=body(); ((ObjectNode)input.at("/result/proposed_actions/0/changes")).remove(field);
            assertEquals(422,submit(input).getStatus());
        }
        var input=body(); ((ObjectNode)input.get("result")).put("approved",true);
        assertEquals(422,submit(input).getStatus()); assertEquals(0,count());
    }

    @Test void duplicateOrInvalidLaterActionRejectsWholeBatch() throws Exception {
        for(boolean duplicate: new boolean[]{true,false}) {
            var input=body(); var actions=(com.fasterxml.jackson.databind.node.ArrayNode)input.at("/result/proposed_actions");
            var second=(ObjectNode)actions.get(0).deepCopy(); second.put("proposal_id","p2");
            if(!duplicate) ((ObjectNode)second.get("changes")).put("title","另一个行动").put("owner_id",99);
            actions.add(second); assertEquals(duplicate?422:409,submit(input).getStatus());
            assertEquals(0,count());
        }
    }

    @Test void rolesMeetingScopeAndRequestOwnerAreEnforced() throws Exception {
        role("viewer"); assertEquals(403,submit(body()).getStatus());
        assertEquals(200,mvc.perform(get(path)).andReturn().getResponse().getStatus());
        AuthContext.clear(); assertEquals(401,submit(body()).getStatus());
        role("owner"); var saved=mapper.readTree(submit(body()).getContentAsByteArray());
        assertEquals(404,mvc.perform(get(path.replace("/m1/","/m2/")+"/"+saved.path("id").asText())).andReturn().getResponse().getStatus());
        assertEquals(200,mvc.perform(get(path+"/by-request/retro-1")).andReturn().getResponse().getStatus());
        User another=new User(); another.setId(2); another.setRole("member"); AuthContext.set(another);
        assertEquals(404,mvc.perform(get(path+"/by-request/retro-1")).andReturn().getResponse().getStatus());
    }

    @Test void concurrentRetriesCreateOneCandidate() throws Exception {
        var input=body(); var start=new CountDownLatch(1);
        try(var pool=Executors.newFixedThreadPool(2)) {
            Callable<String> run=()->{start.await();return service.save("m1",input,1).path("id").asText();};
            var a=pool.submit(run); var b=pool.submit(run); start.countDown();
            assertEquals(a.get(10,TimeUnit.SECONDS),b.get(10,TimeUnit.SECONDS));
        }
        assertEquals(1,count());
    }
}
