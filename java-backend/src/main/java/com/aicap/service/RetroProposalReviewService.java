package com.aicap.service;

import com.aicap.common.ApiException;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.node.ObjectNode;
import jakarta.validation.Valid;
import jakarta.validation.Validator;
import jakarta.validation.constraints.*;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.util.*;

/** Final per-proposal review decisions; execution is a separate operation. */
@Service
@RequiredArgsConstructor
public class RetroProposalReviewService {
    private final JdbcTemplate jdbc;
    private final ObjectMapper mapper;
    private final Validator validator;



    public record Review(@NotBlank @Size(max=80) @JsonProperty("proposal_id") String proposalId,
                         @NotNull @Pattern(regexp="approve|modify_and_approve|reject") String decision,
                         @Valid com.aicap.dto.RetroAnalysisDtos.Changes changes,
                         @NotNull @Size(max=1000) String reason) {}

    private Review parse(JsonNode body) {
        try {
            var strict = mapper.copy().disable(MapperFeature.ALLOW_COERCION_OF_SCALARS)
                    .disable(DeserializationFeature.ACCEPT_FLOAT_AS_INT)
                    .enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES);
            var input = strict.treeToValue(body, Review.class);
            if (input == null || !validator.validate(input).isEmpty()) throw new IllegalArgumentException();
            boolean edit = input.decision().equals("modify_and_approve");
            if (edit != (input.changes() != null) || (edit && input.reason().isBlank())) throw new IllegalArgumentException();
            return input;
        } catch (Exception e) { throw ApiException.unprocessable("审核载荷无效；仅修改后接受可带 changes，且必须说明修改原因"); }
    }

    private JsonNode json(Object text) {
        try { return mapper.readTree(text.toString()); }
        catch (Exception e) { throw new IllegalStateException("Stored review JSON is invalid", e); }
    }

    private Map<String,Object> analysis(String meetingId, String analysisId, boolean lock) {
        var rows = jdbc.queryForList("SELECT * FROM meeting_retro_analyses WHERE meeting_id=? AND id=?" + (lock ? " FOR UPDATE" : ""), meetingId, analysisId);
        if (rows.isEmpty()) throw ApiException.notFound("复盘分析记录不存在");
        return rows.getFirst();
    }

    private ObjectNode out(Map<String,Object> row) {
        var out = mapper.createObjectNode();
        for (String key : List.of("analysis_id","proposal_id","decision","reason","execution_status"))
            out.put(key,row.get(key).toString());
        out.put("status",row.get("decision").equals("reject") ? "rejected" : "approved");
        out.put("reviewed_by",((Number)row.get("reviewed_by")).intValue());
        out.put("reviewed_at",row.get("reviewed_at").toString());
        out.set("original_proposal",json(row.get("original_json")));
        out.set("approved_proposal",row.get("approved_json") == null ? null : json(row.get("approved_json")));
        out.put("approved_owner_name",(String)row.get("approved_owner_name"));
        return out;
    }

    private String validateApproval(Map<String,Object> record, JsonNode original, ObjectNode approved, Review input) {
        if (approved==null) return null;
        if (input.changes()!=null && approved.get("changes").equals(original.get("changes")))
            throw ApiException.unprocessable("修改后接受必须改变行动项内容");
        var owner=approved.at("/changes/owner_id");
        if (owner.isNull()) return null;
        var users=jdbc.queryForList("SELECT id,display_name FROM users ORDER BY id FOR UPDATE");
        var user=users.stream().filter(u -> ((Number)u.get("id")).intValue()==owner.intValue()).findFirst();
        if (user.isEmpty()) throw ApiException.conflict("负责人不存在，请重新审核");
        String name=user.get().get("display_name").toString();
        if (input.changes()==null) {
            var snapshots=json(record.get("snapshots_json"));
            boolean matched=false;
            for(var snapshot:snapshots) if(snapshot.path("user_id").asInt()==owner.intValue()
                    && snapshot.path("display_name").asText().equals(name)) matched=true;
            if (!matched || users.stream().filter(u -> name.equals(u.get("display_name"))).count()!=1)
                throw ApiException.conflict("负责人资料已变化，请修改后重新确认");
        }
        return name;
    }

    @Transactional
    public ObjectNode review(String meetingId, String analysisId, JsonNode body, int reviewerId) {
        var input = parse(body);
        var record = analysis(meetingId,analysisId,true);
        var proposals = json(record.get("result_json")).path("proposed_actions");
        int index = -1;
        for (int i=0; i<proposals.size(); i++)
            if (proposals.get(i).path("proposal_id").asText().equals(input.proposalId())) { index=i; break; }
        if (index < 0) throw ApiException.notFound("提案不存在");
        var original = proposals.get(index);
        ObjectNode approved = input.decision().equals("reject") ? null : original.deepCopy();
        if (input.changes()!=null) approved.set("changes",mapper.valueToTree(input.changes()));
        var existing = jdbc.queryForList("SELECT * FROM meeting_retro_proposal_reviews WHERE analysis_id=? AND proposal_index=?", analysisId,index);
        if (!existing.isEmpty()) {
            var previous = existing.getFirst();
            var previousApproved = previous.get("approved_json") == null ? null : json(previous.get("approved_json"));
            if (((Number)previous.get("reviewed_by")).intValue() != reviewerId
                    || !previous.get("decision").equals(input.decision()) || !previous.get("reason").equals(input.reason())
                    || !Objects.equals(previousApproved,approved))
                throw ApiException.conflict("该提案已经审核，不能覆盖原决定");
            return out(previous);
        }
        String ownerName = validateApproval(record, original, approved, input);
        jdbc.update("INSERT INTO meeting_retro_proposal_reviews (analysis_id,proposal_index,proposal_id,decision,reason,reviewed_by,reviewed_at,original_json,approved_json,approved_owner_name,execution_status) VALUES (?,?,?,?,?,?,CURRENT_TIMESTAMP,?,?,?,?)",
                analysisId,index,input.proposalId(),input.decision(),input.reason(),reviewerId,
                original.toString(),approved==null ? null : approved.toString(),ownerName,approved==null ? "not_applicable" : "not_started");
        return out(jdbc.queryForMap("SELECT * FROM meeting_retro_proposal_reviews WHERE analysis_id=? AND proposal_index=?",analysisId,index));
    }

    @Transactional(readOnly=true, isolation=org.springframework.transaction.annotation.Isolation.REPEATABLE_READ)
    public ObjectNode list(String meetingId,String analysisId) {
        var proposals=json(analysis(meetingId,analysisId,false).get("result_json")).path("proposed_actions");
        var saved=jdbc.queryForList("SELECT * FROM meeting_retro_proposal_reviews WHERE analysis_id=?",analysisId);
        Map<Integer,Map<String,Object>> byIndex=new HashMap<>();
        for(var row:saved) byIndex.put(((Number)row.get("proposal_index")).intValue(),row);
        var items=mapper.createArrayNode();
        for(int i=0;i<proposals.size();i++) {
            if(byIndex.containsKey(i)) items.add(out(byIndex.get(i)));
            else {
                var pending=items.addObject();
                pending.put("analysis_id",analysisId).put("proposal_id",proposals.get(i).path("proposal_id").asText()).put("status","pending");
                pending.set("original_proposal",proposals.get(i));
                pending.putNull("approved_proposal").putNull("decision").putNull("reason").putNull("reviewed_by").putNull("reviewed_at");
                pending.put("execution_status","not_started");
            }
        }
        var result=mapper.createObjectNode();
        result.put("analysis_id",analysisId).put("review_status",proposals.isEmpty() ? "no_changes" : saved.isEmpty() ? "pending" : saved.size()==proposals.size() ? "reviewed" : "partially_reviewed");
        result.set("proposals",items);
        return result;
    }
}
