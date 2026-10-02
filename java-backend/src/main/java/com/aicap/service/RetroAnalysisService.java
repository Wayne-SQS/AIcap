package com.aicap.service;

import com.aicap.common.ApiException;
import com.aicap.dto.RetroAnalysisDtos;
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
public class RetroAnalysisService {
    private final JdbcTemplate jdbc;
    private final ObjectMapper mapper;
    private final Validator validator;

    private RetroAnalysisDtos.Submit parse(JsonNode body) {
        try {
            ObjectMapper strict = mapper.copy().disable(MapperFeature.ALLOW_COERCION_OF_SCALARS)
                    .disable(DeserializationFeature.ACCEPT_FLOAT_AS_INT)
                    .enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES);
            var input = strict.treeToValue(body, RetroAnalysisDtos.Submit.class);
            if (input == null || !validator.validate(input).isEmpty()) throw new IllegalArgumentException();
            return input;
        } catch (Exception e) {
            throw ApiException.unprocessable("复盘分析载荷无效");
        }
    }

    private JsonNode readJson(String text) {
        try { return mapper.readTree(text); }
        catch (Exception e) { throw new IllegalStateException("Stored analysis JSON is invalid", e); }
    }

    private void evidence(List<RetroAnalysisDtos.Evidence> items, String[] lines) {
        for (var item : items) {
            int index;
            try {
                if (!item.segmentId().matches("S[1-9][0-9]*")) throw new IllegalArgumentException();
                index = Integer.parseInt(item.segmentId().substring(1)) - 1;
            } catch (Exception e) { throw ApiException.unprocessable("证据片段编号无效"); }
            if (index >= lines.length || !lines[index].contains(item.quote()))
                throw ApiException.unprocessable("证据必须来自对应会议原文片段");
        }
    }

    private JsonNode validateCandidates(RetroAnalysisDtos.Result result, String[] lines) {
        for (var decision : result.decisions()) evidence(decision.evidence(),lines);
        // Lock the name directory in stable order only when an owner is specified.
        // Exact Java name equality matches the Python projection, not DB collation.
        var members = result.proposedActions().stream().anyMatch(p -> p.changes().ownerId()!=null)
            ? jdbc.queryForList("SELECT id,display_name FROM users ORDER BY id FOR UPDATE")
            : List.<Map<String,Object>>of();
        var snapshots = mapper.createArrayNode();
        Set<Integer> owners = new HashSet<>();
        Set<String> ids = new HashSet<>();
        Set<RetroAnalysisDtos.Changes> contents = new HashSet<>();
        for (var proposal : result.proposedActions()) {
            evidence(proposal.evidence(),lines);
            var change = proposal.changes();
            var normalized = new RetroAnalysisDtos.Changes(change.title().strip(),change.description().strip(),change.ownerId(),change.deadlineText());
            if (!ids.add(proposal.proposalId()) || !contents.add(normalized))
                throw ApiException.unprocessable("重复提案或行动项");
            if (change.deadlineText()!=null && proposal.evidence().stream().noneMatch(e -> e.quote().contains(change.deadlineText())))
                throw ApiException.unprocessable("截止时间必须保留引用原文");
            if (change.ownerId()!=null) {
                var member = members.stream().filter(m -> ((Number)m.get("id")).intValue()==change.ownerId()).findFirst();
                if (member.isEmpty()) throw ApiException.conflict("负责人不存在，请重新分析");
                String name = member.get().get("display_name").toString();
                if (name.isBlank() || members.stream().filter(m -> name.equals(m.get("display_name"))).count()!=1
                        || proposal.evidence().stream().noneMatch(e -> e.quote().contains(name)))
                    throw ApiException.conflict("负责人姓名不唯一或引用未明确负责人，请重新分析");
                if (owners.add(change.ownerId())) snapshots.addObject().put("user_id",change.ownerId()).put("display_name",name);
            }
        }
        return snapshots;
    }

    private ObjectNode out(Map<String,Object> row) {
        ObjectNode out = mapper.createObjectNode();
        for (String key : List.of("id", "meeting_id", "client_request_id", "status"))
            out.put(key, row.get(key).toString());
        out.put("submitted_by", ((Number)row.get("submitted_by")).intValue());
        out.put("created_at", row.get("created_at").toString());
        out.set("result", readJson(row.get("result_json").toString()));
        out.set("member_snapshots", readJson(row.get("snapshots_json").toString()));
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
        var existing = jdbc.queryForList("SELECT * FROM meeting_retro_analyses WHERE meeting_id=? AND submitted_by=? AND client_request_id=?",
                meetingId, userId, input.clientRequestId());
        if (!existing.isEmpty()) {
            if (!readJson(existing.getFirst().get("result_json").toString()).equals(result))
                throw ApiException.conflict("同一请求编号不能提交不同分析");
            return out(existing.getFirst()); // Preserve original member names even after later edits.
        }
        String transcript = meetings.getFirst().get("transcript").toString();
        // Same splitlines boundaries as Python; blank lines retain their ordinal.
        String[] lines = transcript.split("\\r\\n|[\\n\\r\\u000B\\u000C\\u001C-\\u001E\\u0085\\u2028\\u2029]", -1);
        var snapshots = validateCandidates(input.result(), lines);
        String id = UUID.randomUUID().toString();
        String status = input.result().proposedActions().isEmpty() ? "no_changes" : "pending";
        jdbc.update("INSERT INTO meeting_retro_analyses (id,meeting_id,submitted_by,client_request_id,status,transcript,result_json,snapshots_json,created_at) VALUES (?,?,?,?,?,?,?,?,CURRENT_TIMESTAMP)",
                id, meetingId, userId, input.clientRequestId(), status, transcript, result.toString(), snapshots.toString());
        return get(meetingId, id);
    }

    public List<ObjectNode> list(String meetingId) {
        if (jdbc.queryForList("SELECT id FROM meetings WHERE id=?", meetingId).isEmpty())
            throw ApiException.notFound("会议不存在");
        return jdbc.queryForList("SELECT * FROM meeting_retro_analyses WHERE meeting_id=? ORDER BY created_at DESC,id DESC", meetingId)
                .stream().map(this::out).toList();
    }

    public ObjectNode findByRequest(String meetingId, String requestId, int userId) {
        var rows = jdbc.queryForList("SELECT * FROM meeting_retro_analyses WHERE meeting_id=? AND submitted_by=? AND client_request_id=?",
                meetingId, userId, requestId);
        if (rows.isEmpty()) throw ApiException.notFound("复盘分析记录不存在");
        return out(rows.getFirst());
    }

    public ObjectNode get(String meetingId, String id) {
        var rows = jdbc.queryForList("SELECT * FROM meeting_retro_analyses WHERE meeting_id=? AND id=?", meetingId, id);
        if (rows.isEmpty()) throw ApiException.notFound("复盘分析记录不存在");
        return out(rows.getFirst());
    }
}
