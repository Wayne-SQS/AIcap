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

import java.util.HashSet;
import java.util.Locale;
import java.util.Set;

/** A durable, per-user save request that can be resumed from another browser. */
@RestController
@RequiredArgsConstructor
@RequestMapping("/api/meetings/{meetingId}/assignment-suggestions/save-intent")
public class AssignmentSaveIntentController {
    private final JdbcTemplate jdbc;
    private final ObjectMapper mapper;

    private void require(boolean valid) {
        if (!valid) throw ApiException.unprocessable("分配保存意图无效");
    }

    private void fields(JsonNode node, String... names) {
        require(node != null && node.isObject());
        var expected = Set.of(names);
        require(node.size() == expected.size());
        node.fieldNames().forEachRemaining(name -> require(expected.contains(name)));
    }

    private void validate(JsonNode body) {
        fields(body, "client_request_id", "story_ids", "target_sprint", "requirements");
        require(body.toString().length() <= 16 * 1024);
        require(body.path("client_request_id").isTextual()
                && body.path("client_request_id").asText().matches("[a-z0-9-]{1,80}"));
        var stories = body.path("story_ids");
        require(stories.isArray() && stories.size() == 1 && stories.get(0).isTextual()
                && stories.get(0).asText().matches("US[0-9]{1,8}"));
        var sprint = body.path("target_sprint");
        require(sprint.isNull() || (sprint.isInt() && sprint.asInt() >= 1 && sprint.asInt() <= 4));
        var requirements = body.path("requirements");
        require(requirements.isArray() && !requirements.isEmpty() && requirements.size() <= 20);
        var keys = new HashSet<String>();
        for (var item : requirements) {
            fields(item, "dimension", "name", "minimum_level");
            require(Set.of("tech_stack", "capabilities", "process_domains").contains(item.path("dimension").asText()));
            require(item.path("name").isTextual() && !item.path("name").asText().isBlank()
                    && item.path("name").asText().length() <= 50);
            require(item.path("minimum_level").isInt() && item.path("minimum_level").asInt() >= 1
                    && item.path("minimum_level").asInt() <= 5);
            require(keys.add(item.path("dimension").asText() + ":"
                    + item.path("name").asText().strip().toLowerCase(Locale.ROOT)));
        }
    }

    private ObjectNode response(JsonNode intent) {
        var result = mapper.createObjectNode();
        result.set("intent", intent);
        return result;
    }

    private JsonNode stored(String meetingId, int userId) {
        var rows = jdbc.queryForList("SELECT client_request_id,input_json FROM meeting_assignment_save_intents "
                + "WHERE meeting_id=? AND user_id=?", meetingId, userId);
        if (rows.isEmpty()) return null;
        try {
            var intent = (ObjectNode) mapper.readTree(rows.getFirst().get("input_json").toString());
            intent.put("client_request_id", rows.getFirst().get("client_request_id").toString());
            return intent;
        } catch (Exception error) {
            throw new IllegalStateException("Invalid stored assignment save intent", error);
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
            if (!current.equals(body)) throw ApiException.conflict("已有未确认的分配保存请求，请先恢复原请求");
            return response(current);
        }
        var input = body.deepCopy();
        ((ObjectNode) input).remove("client_request_id");
        jdbc.update("INSERT INTO meeting_assignment_save_intents (meeting_id,user_id,client_request_id,input_json) "
                + "VALUES (?,?,?,?)", meetingId, userId, body.path("client_request_id").asText(), input.toString());
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
        jdbc.update("DELETE FROM meeting_assignment_save_intents WHERE meeting_id=? AND user_id=? "
                + "AND client_request_id=?", meetingId, userId, key);
        var result = mapper.createObjectNode();
        result.put("cleared", true);
        return result;
    }
}
