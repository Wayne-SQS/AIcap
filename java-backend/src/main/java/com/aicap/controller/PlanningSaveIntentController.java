package com.aicap.controller;

import com.aicap.common.ApiException;
import com.aicap.security.Roles;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;

import java.util.Set;

/** Durable request identity for an uncertain Sprint Planning analysis save. */
@RestController
@RequiredArgsConstructor
@RequestMapping("/api/meetings/{meetingId}/planning-analyses/save-intent")
public class PlanningSaveIntentController {
    private final JdbcTemplate jdbc;
    private final ObjectMapper mapper;

    private void require(boolean valid) {
        if (!valid) throw ApiException.unprocessable("Planning 保存意图无效");
    }

    private void validate(JsonNode body) {
        require(body != null && body.isObject() && body.size() == 4 && body.toString().length() <= 2048);
        var expected = Set.of("client_request_id", "meeting_type", "current_sprint", "target_sprint");
        body.fieldNames().forEachRemaining(name -> require(expected.contains(name)));
        require(body.path("client_request_id").isTextual()
                && body.path("client_request_id").asText().matches("[a-z0-9-]{1,80}"));
        require(body.path("meeting_type").isTextual()
                && "sprint_planning".equals(body.path("meeting_type").asText()));
        for (String key : new String[] { "current_sprint", "target_sprint" }) {
            var value = body.path(key);
            require(value.isNull() || (value.isInt() && value.asInt() >= 1 && value.asInt() <= 4));
        }
    }

    private ObjectNode response(JsonNode intent) {
        var result = mapper.createObjectNode();
        result.set("intent", intent);
        return result;
    }

    private JsonNode stored(String meetingId, int userId) {
        var rows = jdbc.queryForList("SELECT client_request_id,input_json FROM meeting_planning_save_intents "
                + "WHERE meeting_id=? AND user_id=?", meetingId, userId);
        if (rows.isEmpty()) return null;
        try {
            var intent = (ObjectNode) mapper.readTree(rows.getFirst().get("input_json").toString());
            intent.put("client_request_id", rows.getFirst().get("client_request_id").toString());
            return intent;
        } catch (Exception error) {
            throw new IllegalStateException("Invalid stored Planning save intent", error);
        }
    }

    @GetMapping
    public ObjectNode get(@PathVariable String meetingId) {
        int userId = Roles.writer().getId();
        if (jdbc.queryForList("SELECT id FROM meetings WHERE id=?", meetingId).isEmpty())
            throw ApiException.notFound("会议不存在");
        return response(stored(meetingId, userId));
    }

    @PutMapping
    @Transactional
    public ObjectNode put(@PathVariable String meetingId, @RequestBody JsonNode body) {
        int userId = Roles.writer().getId();
        validate(body);
        if (jdbc.queryForList("SELECT id FROM meetings WHERE id=? FOR UPDATE", meetingId).isEmpty())
            throw ApiException.notFound("会议不存在");
        var current = stored(meetingId, userId);
        if (current != null) {
            if (!current.equals(body)) throw ApiException.conflict("已有待确认的 Planning 请求，请先恢复或放弃原请求");
            return response(current);
        }
        var input = body.deepCopy();
        ((ObjectNode) input).remove("client_request_id");
        jdbc.update("INSERT INTO meeting_planning_save_intents "
                + "(meeting_id,user_id,client_request_id,input_json) VALUES (?,?,?,?)",
                meetingId, userId, body.path("client_request_id").asText(), input.toString());
        return response(body);
    }

    @DeleteMapping
    @Transactional
    public ObjectNode clear(@PathVariable String meetingId, @RequestParam("client_request_id") String key) {
        int userId = Roles.writer().getId();
        require(key.matches("[a-z0-9-]{1,80}"));
        var current = stored(meetingId, userId);
        if (current != null && !current.path("client_request_id").asText().equals(key))
            throw ApiException.conflict("待恢复请求已变化");
        jdbc.update("DELETE FROM meeting_planning_save_intents WHERE meeting_id=? AND user_id=? "
                + "AND client_request_id=?", meetingId, userId, key);
        var result = mapper.createObjectNode();
        result.put("cleared", true);
        return result;
    }
}
