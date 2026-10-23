package com.aicap.contract;

import com.aicap.common.GlobalExceptionHandler;
import com.aicap.controller.PlanningSaveIntentController;
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

class PlanningSaveIntentContractTest {
    private JdbcTemplate jdbc;
    private MockMvc mvc;
    private final ObjectMapper mapper = new ObjectMapper();
    private final String path = "/api/meetings/m1/planning-analyses/save-intent";

    @BeforeEach void setup() throws Exception {
        var dataSource = new DriverManagerDataSource("jdbc:h2:mem:" + UUID.randomUUID()
                + ";MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1", "sa", "");
        jdbc = new JdbcTemplate(dataSource);
        jdbc.execute("CREATE TABLE users(id int primary key)");
        jdbc.execute("CREATE TABLE meetings(id varchar(36) primary key)");
        try (var stream = getClass().getResourceAsStream("/db/planning-save-intents.sql")) {
            assertNotNull(stream);
            var sql = new String(stream.readAllBytes(), StandardCharsets.UTF_8)
                    .replace(" ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci", "");
            jdbc.execute(sql);
        }
        jdbc.update("INSERT INTO users VALUES (1),(2),(3)");
        jdbc.update("INSERT INTO meetings VALUES ('m1'),('m2')");
        mvc = MockMvcBuilders.standaloneSetup(new PlanningSaveIntentController(jdbc, mapper))
                .setControllerAdvice(new GlobalExceptionHandler()).build();
        role(1, "member");
    }

    @AfterEach void close() { AuthContext.clear(); jdbc.execute("SHUTDOWN"); }

    private void role(int id, String name) {
        var user = new User(); user.setId(id); user.setRole(name); AuthContext.set(user);
    }

    private String intent(String key) {
        return """
                {"client_request_id":"%s","meeting_type":"sprint_planning","current_sprint":null,"target_sprint":3}
                """.formatted(key);
    }

    @Test void pendingRequestSurvivesAnotherBrowserAndRejectsReplacement() throws Exception {
        assertEquals(200, mvc.perform(put(path).contentType("application/json").content(intent("r1")))
                .andReturn().getResponse().getStatus());
        assertEquals(200, mvc.perform(put(path).contentType("application/json").content(intent("r1")))
                .andReturn().getResponse().getStatus());
        var recovered = mapper.readTree(mvc.perform(get(path)).andReturn().getResponse().getContentAsString());
        assertEquals(mapper.readTree(intent("r1")), recovered.path("intent"));
        assertEquals(409, mvc.perform(put(path).contentType("application/json").content(intent("r2")))
                .andReturn().getResponse().getStatus());
        assertEquals(409, mvc.perform(delete(path).param("client_request_id", "r2"))
                .andReturn().getResponse().getStatus());
        assertEquals(200, mvc.perform(delete(path).param("client_request_id", "r1"))
                .andReturn().getResponse().getStatus());
        assertTrue(mapper.readTree(mvc.perform(get(path)).andReturn().getResponse().getContentAsString())
                .path("intent").isNull());
    }

    @Test void intentIsScopedToAccountAndMeeting() throws Exception {
        mvc.perform(put(path).contentType("application/json").content(intent("r1")));
        role(2, "member");
        assertTrue(mapper.readTree(mvc.perform(get(path)).andReturn().getResponse().getContentAsString())
                .path("intent").isNull());
        role(1, "member");
        assertTrue(mapper.readTree(mvc.perform(get(path.replace("m1", "m2"))).andReturn()
                .getResponse().getContentAsString()).path("intent").isNull());
        role(3, "viewer");
        assertEquals(403, mvc.perform(get(path)).andReturn().getResponse().getStatus());
        assertEquals(403, mvc.perform(put(path).contentType("application/json").content(intent("r3")))
                .andReturn().getResponse().getStatus());
    }

    @Test void invalidSprintOrRequestCannotBeStored() throws Exception {
        for (var body : new String[] { intent("BAD"), intent("r1").replace("\"target_sprint\":3", "\"target_sprint\":5"),
                intent("r1").replace("sprint_planning", "daily_standup"), intent("r1").replace("\"current_sprint\":null,", "") }) {
            assertEquals(422, mvc.perform(put(path).contentType("application/json").content(body))
                    .andReturn().getResponse().getStatus());
        }
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM meeting_planning_save_intents", Integer.class));
    }

    @Test void deletingMeetingRemovesItsPendingRequest() throws Exception {
        mvc.perform(put(path).contentType("application/json").content(intent("r1")));
        jdbc.update("DELETE FROM meetings WHERE id='m1'");
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM meeting_planning_save_intents", Integer.class));
    }
}
