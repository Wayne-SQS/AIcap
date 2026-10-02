package com.aicap.service;

import com.aicap.common.ApiException;
import com.aicap.dto.RefinementAnalysisDtos;
import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.node.ObjectNode;
import jakarta.validation.Validator;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.*;

/** Immutable submissions. No approval or project mutation lives in this service. */
@Service
@RequiredArgsConstructor
public class RefinementAnalysisService {
    private final JdbcTemplate jdbc;
    private final ObjectMapper mapper;
    private final Validator validator;

    private RefinementAnalysisDtos.Submit parse(JsonNode body) {
        try {
            ObjectMapper strict = mapper.copy().disable(MapperFeature.ALLOW_COERCION_OF_SCALARS)
                    .disable(DeserializationFeature.ACCEPT_FLOAT_AS_INT)
                    .enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES);
            var input = strict.treeToValue(body, RefinementAnalysisDtos.Submit.class);
            if (input == null || !validator.validate(input).isEmpty()) throw new IllegalArgumentException();
            return input;
        } catch (Exception e) {
            throw ApiException.unprocessable("需求细化分析载荷无效");
        }
    }

    private JsonNode readJson(String text) {
        try { return mapper.readTree(text); }
        catch (Exception e) { throw new IllegalStateException("Stored analysis JSON is invalid", e); }
    }

    private ObjectNode out(Map<String,Object> row) {
        ObjectNode out = mapper.createObjectNode();
        for (String key : List.of("id", "meeting_id", "client_request_id", "status"))
            out.put(key, row.get(key).toString());
        out.put("submitted_by", ((Number)row.get("submitted_by")).intValue());
        out.put("created_at", row.get("created_at").toString());
        out.set("result", readJson(row.get("result_json").toString()));
        out.set("story_snapshots", readJson(row.get("snapshots_json").toString()));
        out.put("transcript", row.get("transcript").toString());
        return out;
    }

    @Transactional
    public ObjectNode save(String meetingId, JsonNode body, int userId) {
        var input = parse(body);
        if (!meetingId.equals(input.result().meetingId())) throw ApiException.unprocessable("会议 ID 不一致");
        // A meeting row lock serializes same-meeting submissions, including concurrent retries.
        var meetings = jdbc.queryForList("SELECT transcript FROM meetings WHERE id=? FOR UPDATE", meetingId);
        if (meetings.isEmpty()) throw ApiException.notFound("会议不存在");
        JsonNode result = mapper.valueToTree(input.result());
        var existing = jdbc.queryForList("SELECT * FROM meeting_refinement_analyses WHERE meeting_id=? AND submitted_by=? AND client_request_id=?",
                meetingId, userId, input.clientRequestId());
        if (!existing.isEmpty()) {
            if (!readJson(existing.getFirst().get("result_json").toString()).equals(result))
                throw ApiException.conflict("同一请求编号不能提交不同分析");
            return out(existing.getFirst()); // Preserve original snapshots even if live stories changed.
        }
        String transcript = meetings.getFirst().get("transcript").toString();
        // Same splitlines boundaries as Python; blank lines retain their ordinal.
        String[] lines = transcript.split("\\r\\n|[\\n\\r\\u000B\\u000C\\u001C-\\u001E\\u0085\\u2028\\u2029]", -1);
        Set<String> ids = new HashSet<>(), titles = new HashSet<>();
        var snapshots = mapper.createArrayNode();
        // New stories have no target ID. Preserve the basic catalog used for duplicate checking.
        var catalog = jdbc.queryForList("SELECT id,title,status,sprint,owner_id FROM stories ORDER BY id FOR UPDATE");
        if (catalog.size()>1000) throw ApiException.conflict("故事目录过大，无法保存分析快照");
        for (var story : catalog) {
            titles.add(story.get("title").toString().strip().toLowerCase(Locale.ROOT));
            snapshots.add(mapper.valueToTree(story));
        }
        var ordered = input.result().proposedActions();
        for (var proposal : ordered) {
            if (!ids.add(proposal.proposalId())) throw ApiException.unprocessable("重复提案编号");
            if (!titles.add(proposal.changes().title().strip().toLowerCase(Locale.ROOT)))
                throw ApiException.conflict("已有或本批次包含同名故事，请核对后重新分析");
            for (var evidence : proposal.evidence()) {
                int index;
                try {
                    if (!evidence.segmentId().matches("S[1-9][0-9]*")) throw new IllegalArgumentException();
                    index = Integer.parseInt(evidence.segmentId().substring(1)) - 1;
                } catch (Exception e) { throw ApiException.unprocessable("证据片段编号无效"); }
                if (index >= lines.length || !lines[index].contains(evidence.quote()))
                    throw ApiException.unprocessable("证据必须来自对应会议原文片段");
            }
        }
        String id = UUID.randomUUID().toString();
        String status = ordered.isEmpty() ? "no_changes" : "pending";
        jdbc.update("INSERT INTO meeting_refinement_analyses (id,meeting_id,submitted_by,client_request_id,status,transcript,result_json,snapshots_json,created_at) VALUES (?,?,?,?,?,?,?,?,CURRENT_TIMESTAMP)",
                id, meetingId, userId, input.clientRequestId(), status, transcript, result.toString(), snapshots.toString());
        return get(meetingId, id);
    }

    public List<ObjectNode> list(String meetingId) {
        if (jdbc.queryForList("SELECT id FROM meetings WHERE id=?", meetingId).isEmpty())
            throw ApiException.notFound("会议不存在");
        return jdbc.queryForList("SELECT * FROM meeting_refinement_analyses WHERE meeting_id=? ORDER BY created_at DESC,id DESC", meetingId)
                .stream().map(this::out).toList();
    }

    public ObjectNode findByRequest(String meetingId, String requestId, int userId) {
        var rows = jdbc.queryForList("SELECT * FROM meeting_refinement_analyses WHERE meeting_id=? AND submitted_by=? AND client_request_id=?",
                meetingId, userId, requestId);
        if (rows.isEmpty()) throw ApiException.notFound("需求细化分析记录不存在");
        return out(rows.getFirst());
    }

    public ObjectNode get(String meetingId, String id) {
        var rows = jdbc.queryForList("SELECT * FROM meeting_refinement_analyses WHERE meeting_id=? AND id=?", meetingId, id);
        if (rows.isEmpty()) throw ApiException.notFound("需求细化分析记录不存在");
        return out(rows.getFirst());
    }
}
