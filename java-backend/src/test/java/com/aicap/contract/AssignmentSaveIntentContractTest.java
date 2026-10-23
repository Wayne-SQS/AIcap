package com.aicap.contract;

import com.aicap.common.GlobalExceptionHandler;
import com.aicap.controller.AssignmentSaveIntentController;
import com.aicap.entity.User;
import com.aicap.security.AuthContext;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.nio.charset.StandardCharsets;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;

class AssignmentSaveIntentContractTest {
    private DriverManagerDataSource dataSource;
    private JdbcTemplate jdbc;
    private MockMvc mvc;
    private final ObjectMapper mapper = new ObjectMapper();
    private final String path = "/api/meetings/m1/assignment-suggestions/save-intent";

    @BeforeEach void setup() throws Exception {
        dataSource = new DriverManagerDataSource("jdbc:h2:mem:" + UUID.randomUUID()
                + ";MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1", "sa", "");
        jdbc = new JdbcTemplate(dataSource);
        jdbc.execute("CREATE TABLE users(id int primary key)");
        jdbc.execute("CREATE TABLE meetings(id varchar(36) primary key)");
        try (var stream = getClass().getResourceAsStream("/db/assignment-save-intents.sql")) {
            assertNotNull(stream);
            var sql = new String(stream.readAllBytes(), StandardCharsets.UTF_8)
                    .replace(" ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci", "");
            jdbc.execute(sql);
        }
        jdbc.update("INSERT INTO users VALUES (1),(2),(3)");
        jdbc.update("INSERT INTO meetings VALUES ('m1'),('m2')");
        mvc = MockMvcBuilders.standaloneSetup(new AssignmentSaveIntentController(jdbc, mapper))
                .setControllerAdvice(new GlobalExceptionHandler()).build();
        role(1, "member");
    }

    @AfterEach void close() {
        AuthContext.clear();
        jdbc.execute("SHUTDOWN");
    }

    private void role(int id, String name) {
        var user = new User();
        user.setId(id);
        user.setRole(name);
        AuthContext.set(user);
    }

    private String intent(String key) {
        return """
                {"client_request_id":"%s","story_ids":["US13"],"target_sprint":2,
                "requirements":[{"dimension":"tech_stack","name":"Python","minimum_level":3}]}
                """.formatted(key);
    }

    @Test void intentSurvivesNewBrowserAndCannotBeReplacedSilently() throws Exception {
        assertEquals(200, mvc.perform(put(path).contentType("application/json").content(intent("r1")))
                .andReturn().getResponse().getStatus());
        assertEquals(200, mvc.perform(put(path).contentType("application/json").content(intent("r1")))
                .andReturn().getResponse().getStatus());
        var recovered = mapper.readTree(mvc.perform(get(path)).andReturn().getResponse().getContentAsString());
        assertEquals(mapper.readTree(intent("r1")), recovered.path("intent"));
        assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM meeting_assignment_save_intents", Integer.class));
        assertEquals(409, mvc.perform(put(path).contentType("application/json").content(intent("r2")))
                .andReturn().getResponse().getStatus());
        assertEquals(409, mvc.perform(delete(path).param("client_request_id", "r2"))
                .andReturn().getResponse().getStatus());
        assertEquals(200, mvc.perform(delete(path).param("client_request_id", "r1"))
                .andReturn().getResponse().getStatus());
        assertTrue(mapper.readTree(mvc.perform(get(path)).andReturn().getResponse().getContentAsString())
                .path("intent").isNull());
    }

    @Test void requestsStayWithinOriginalAccountAndMeeting() throws Exception {
        mvc.perform(put(path).contentType("application/json").content(intent("r1")));
        role(2, "member");
        assertTrue(mapper.readTree(mvc.perform(get(path)).andReturn().getResponse().getContentAsString())
                .path("intent").isNull());
        assertEquals(200, mvc.perform(put(path).contentType("application/json").content(intent("r2")))
                .andReturn().getResponse().getStatus());
        role(1, "member");
        assertTrue(mapper.readTree(mvc.perform(get(path.replace("m1", "m2")))
                .andReturn().getResponse().getContentAsString()).path("intent").isNull());
        role(3, "viewer");
        assertEquals(403, mvc.perform(get(path)).andReturn().getResponse().getStatus());
        assertEquals(403, mvc.perform(put(path).contentType("application/json").content(intent("r3")))
                .andReturn().getResponse().getStatus());
    }

    @Test void malformedIntentCannotBeStored() throws Exception {
        for (var body : new String[] { intent("BAD"), intent("r1").replace("US13", "../m2"),
                intent("r1").replace("\"minimum_level\":3", "\"minimum_level\":9") }) {
            assertEquals(422, mvc.perform(put(path).contentType("application/json").content(body))
                    .andReturn().getResponse().getStatus());
        }
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM meeting_assignment_save_intents", Integer.class));
    }
}
