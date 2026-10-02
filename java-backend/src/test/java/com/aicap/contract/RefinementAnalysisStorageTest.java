package com.aicap.contract;

import com.aicap.common.GlobalExceptionHandler;
import com.aicap.controller.RefinementAnalysisController;
import com.aicap.entity.User;
import com.aicap.security.AuthContext;
import com.aicap.service.RefinementAnalysisService;
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
class RefinementAnalysisStorageTest {
    @Configuration @EnableTransactionManagement @Import({RefinementAnalysisService.class,
        com.aicap.service.RefinementProposalReviewService.class, com.aicap.service.RefinementProposalExecutionService.class})
    static class Config {
        @Bean DataSource dataSource() {
            return new DriverManagerDataSource("jdbc:h2:mem:" + UUID.randomUUID() + ";MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1", "sa", "");
        }
        @Bean JdbcTemplate jdbc(DataSource ds) { return new JdbcTemplate(ds); }
        @Bean PlatformTransactionManager tx(DataSource ds) { return new DataSourceTransactionManager(ds); }
        @Bean ObjectMapper mapper() { return new ObjectMapper(); }
        @Bean Validator validator() { return Validation.buildDefaultValidatorFactory().getValidator(); }
        @Bean com.aicap.service.IdAllocator allocator(JdbcTemplate jdbc) {
            // Real allocator; its two mapper reads use this same isolated JDBC datasource.
            var stories=(com.aicap.mapper.StoryMapper)java.lang.reflect.Proxy.newProxyInstance(
                getClass().getClassLoader(),new Class[]{com.aicap.mapper.StoryMapper.class},(proxy,method,args)->{
                    if(method.getName().equals("selectList")) return jdbc.queryForList("SELECT id FROM stories").stream().map(row->{
                        var story=new com.aicap.entity.Story(); story.setId(row.get("id").toString()); return story;
                    }).toList();
                    if(method.getName().equals("selectById")) {
                        var rows=jdbc.queryForList("SELECT id FROM stories WHERE id=?",args[0]);
                        if(rows.isEmpty()) return null;
                        var story=new com.aicap.entity.Story(); story.setId(rows.getFirst().get("id").toString()); return story;
                    }
                    throw new UnsupportedOperationException(method.getName());
                });
            return new com.aicap.service.IdAllocator(stories,null);
        }
    }
    AnnotationConfigApplicationContext context;
    JdbcTemplate jdbc;
    RefinementAnalysisService service;
    ObjectMapper mapper;
    MockMvc mvc;
    String path = "/api/meetings/m1/refinement-analyses";

    @BeforeEach void setup() throws Exception {
        context = new AnnotationConfigApplicationContext(Config.class);
        jdbc = context.getBean(JdbcTemplate.class);
        service = context.getBean(RefinementAnalysisService.class);
        mapper = context.getBean(ObjectMapper.class);
        jdbc.execute("CREATE TABLE users(id int primary key)");
        jdbc.execute("CREATE TABLE meetings(id varchar(36) primary key,transcript longtext)");
        jdbc.execute("CREATE TABLE stories(id varchar(10) primary key,title varchar(200),status int,sprint int,owner_id int)");
        String ddl;
        try (var in = getClass().getResourceAsStream("/db/refinement-analyses.sql")) {
            ddl = new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
        // Only MySQL-specific table encoding suffix differs; test the actual migration body twice.
        ddl = ddl.replace(" ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci", "");
        jdbc.execute(ddl);
        jdbc.execute(ddl);
        jdbc.execute("CREATE TABLE story_logs(id int auto_increment primary key,story_id varchar(10),log_type varchar(20),detail text,user_id int,created_at timestamp)");
        jdbc.update("INSERT INTO users VALUES (1)");
        jdbc.update("INSERT INTO users VALUES (2)");
        jdbc.update("INSERT INTO meetings VALUES ('m1',?)", "决定新增周报导出。\n\nUS14 开始开发。");
        jdbc.update("INSERT INTO stories VALUES ('US13','登录',1,1,NULL)");
        jdbc.update("INSERT INTO stories VALUES ('US14','权限',0,2,NULL)");
        for (String column : java.util.List.of("description text", "acceptance text", "priority varchar(10)", "activity int", "created_at timestamp"))
            jdbc.execute("ALTER TABLE stories ADD " + column);
        for(String resource:java.util.List.of("refinement-proposal-reviews","refinement-proposal-executions")) {
            try(var in=getClass().getResourceAsStream("/db/"+resource+".sql")) {
                var sql=new String(in.readAllBytes(),StandardCharsets.UTF_8).replace(" ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci","");
                jdbc.execute(sql); jdbc.execute(sql);
            }
        }
        mvc = MockMvcBuilders.standaloneSetup(new RefinementAnalysisController(service),
                new com.aicap.controller.RefinementProposalReviewController(context.getBean(com.aicap.service.RefinementProposalReviewService.class)),
                new com.aicap.controller.RefinementProposalExecutionController(context.getBean(com.aicap.service.RefinementProposalExecutionService.class)))
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
            "meeting_type":"backlog_refinement","summary":"确认新增需求","open_questions":[],"proposed_actions":[
            {"proposal_id":"p1","action":"create_story",
            "changes":{"title":"周报导出","description":null,"acceptance":null,"priority":null,"sprint":null,"activity":null},"reason":"确认新增","evidence":[{"segment_id":"S1","quote":"决定新增周报导出。"}]}]}}
            """);
    }
    org.springframework.mock.web.MockHttpServletResponse submit(JsonNode body) throws Exception {
        return mvc.perform(post(path).contentType("application/json").content(body.toString())).andReturn().getResponse();
    }
    int count() { return jdbc.queryForObject("SELECT COUNT(*) FROM meeting_refinement_analyses", Integer.class); }

    @Test void savesUnknownsAndOriginalCatalogWithoutCreatingStory() throws Exception {
        var input=body(); var response=submit(input);
        assertEquals(200,response.getStatus(),response.getContentAsString());
        var saved=mapper.readTree(response.getContentAsByteArray());
        assertEquals(input.get("result"),saved.get("result"));
        assertEquals("pending",saved.path("status").asText());
        assertTrue(saved.at("/result/proposed_actions/0/changes/sprint").isNull());
        assertEquals(2,saved.path("story_snapshots").size());
        assertEquals(2,jdbc.queryForObject("SELECT COUNT(*) FROM stories",Integer.class));
        assertEquals(0,jdbc.queryForObject("SELECT COUNT(*) FROM story_logs",Integer.class));
        assertEquals(200,mvc.perform(get(path+"/"+saved.path("id").asText())).andReturn().getResponse().getStatus());
        assertEquals(200,mvc.perform(get(path)).andReturn().getResponse().getStatus());
    }

    @Test void exactRetryPreservesSnapshotAndChangedPayloadConflicts() throws Exception {
        var input=body(); var first=mapper.readTree(submit(input).getContentAsByteArray());
        jdbc.update("UPDATE stories SET title='周报导出' WHERE id='US13'");
        assertEquals(first,mapper.readTree(submit(input).getContentAsByteArray()));
        ((ObjectNode)input.get("result")).put("summary","不同结果");
        assertEquals(409,submit(input).getStatus()); assertEquals(1,count());
    }

    @Test void rejectsMissingUnknownFieldsAndStrictTypes() throws Exception {
        for(String field:java.util.List.of("description","acceptance","priority","sprint","activity")) {
            var input=body(); ((ObjectNode)input.at("/result/proposed_actions/0/changes")).remove(field);
            assertEquals(422,submit(input).getStatus(),field);
        }
        for(String patch:java.util.List.of("{\"sprint\":true}","{\"sprint\":\"2\"}","{\"activity\":6}","{\"priority\":\"Urgent\"}","{\"acceptance\":\" \"}","{\"owner_id\":1}","{\"status\":0}")) {
            var input=body(); ((ObjectNode)input.at("/result/proposed_actions/0/changes")).setAll((ObjectNode)mapper.readTree(patch));
            assertEquals(422,submit(input).getStatus(),patch);
        }
        assertEquals(0,count());
    }

    @Test void duplicatesAndInvalidLaterEvidenceRejectWholeSubmission() throws Exception {
        var existing=body(); ((ObjectNode)existing.at("/result/proposed_actions/0/changes")).put("title"," 登录 ");
        assertEquals(409,submit(existing).getStatus());
        for(String kind:java.util.List.of("id","title","evidence")) {
            var input=body(); var actions=(com.fasterxml.jackson.databind.node.ArrayNode)input.at("/result/proposed_actions");
            ObjectNode second=actions.get(0).deepCopy();
            if(!kind.equals("id")) second.put("proposal_id","p2");
            if(!kind.equals("title")) ((ObjectNode)second.get("changes")).put("title","另一需求");
            if(kind.equals("evidence")) ((ObjectNode)second.at("/evidence/0")).put("quote","伪造引用");
            actions.add(second);
            assertEquals(kind.equals("title")?409:422,submit(input).getStatus(),kind);
        }
        assertEquals(0,count());
    }

    @Test void emptyProposalsSavedAndExplicitValuesPreserved() throws Exception {
        var input=body(); ((ObjectNode)input.at("/result/proposed_actions/0/changes")).put("priority","Should").put("sprint",3).put("activity",4);
        assertEquals(200,submit(input).getStatus());
        input=body(); input.put("client_request_id","empty"); ((com.fasterxml.jackson.databind.node.ArrayNode)input.at("/result/proposed_actions")).removeAll();
        var response=submit(input); assertEquals(200,response.getStatus());
        assertEquals("no_changes",mapper.readTree(response.getContentAsByteArray()).path("status").asText());
    }

    @Test void permissionsMeetingAndRequestOwnerAreEnforced() throws Exception {
        role("viewer"); assertEquals(403,submit(body()).getStatus());
        AuthContext.clear(); assertEquals(401,submit(body()).getStatus());
        role("member"); var saved=mapper.readTree(submit(body()).getContentAsByteArray());
        assertEquals(200,mvc.perform(get(path+"/by-request/request-1")).andReturn().getResponse().getStatus());
        AuthContext.currentUser().setId(2);
        assertEquals(404,mvc.perform(get(path+"/by-request/request-1")).andReturn().getResponse().getStatus());
        assertEquals(200,submit(body()).getStatus());
        role("viewer"); assertEquals(200,mvc.perform(get(path)).andReturn().getResponse().getStatus());
        assertEquals(403,mvc.perform(get(path+"/by-request/request-1")).andReturn().getResponse().getStatus());
        assertEquals(404,mvc.perform(get("/api/meetings/other/refinement-analyses/"+saved.path("id").asText())).andReturn().getResponse().getStatus());
        role("member"); var input=body(); ((ObjectNode)input.get("result")).put("meeting_id","other");
        assertEquals(422,submit(input).getStatus());
        assertEquals(404,mvc.perform(post("/api/meetings/other/refinement-analyses").contentType("application/json").content(input.toString())).andReturn().getResponse().getStatus());
    }

    @Test void concurrentSameRequestCreatesOneRecord() throws Exception {
        var input=body(); var pool=Executors.newFixedThreadPool(2); var barrier=new CyclicBarrier(2);
        try {
            Callable<ObjectNode> job=()->{barrier.await(5,TimeUnit.SECONDS); return service.save("m1",input,1);};
            var first=pool.submit(job); var second=pool.submit(job);
            assertEquals(first.get(10,TimeUnit.SECONDS),second.get(10,TimeUnit.SECONDS));
            assertEquals(1,count());
        } finally { pool.shutdownNow(); }
    }

}
