package com.aicap.service;

import com.aicap.common.ApiException;
import com.aicap.dto.StatusAnalysisDtos;
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
public class StatusProposalReviewService {
    private final JdbcTemplate jdbc;
    private final ReviewQueueShadowIndexService shadow;
    private final ObjectMapper mapper;
    private final Validator validator;

    public record Review(@NotBlank @Size(max=80) @JsonProperty("proposal_id") String proposalId,
                         @NotNull @Pattern(regexp="approve|modify_and_approve|reject") String decision,
                         @Pattern(regexp="US[0-9]+") @Size(max=10) @JsonProperty("story_id") String storyId,
                         @Valid StatusAnalysisDtos.Status changes,
                         @JsonProperty("story_patch") JsonNode storyPatch,
                         @NotNull @Size(max=1000) String reason) {}

    private Review parse(JsonNode body) {
        try {
            var strict = mapper.copy().disable(MapperFeature.ALLOW_COERCION_OF_SCALARS)
                    .disable(DeserializationFeature.ACCEPT_FLOAT_AS_INT)
                    .enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES);
            var input = strict.treeToValue(body, Review.class);
            if (input == null || !validator.validate(input).isEmpty()) throw new IllegalArgumentException();
            boolean edit = input.decision().equals("modify_and_approve");
            if (edit != (input.changes() != null) || (!edit && (input.storyId() != null || input.storyPatch() != null)) || (edit && input.reason().isBlank())) throw new IllegalArgumentException();
            if (input.storyPatch() != null) validatePatch(input.storyPatch());
            return input;
        } catch (Exception e) { throw ApiException.unprocessable("审核载荷无效；仅修改后接受可带 changes，且必须说明修改原因"); }
    }

    private void validatePatch(JsonNode patch) {
        if (!patch.isObject() || patch.isEmpty()) throw new IllegalArgumentException();
        for (var fields=patch.fieldNames();fields.hasNext();) {
            String key=fields.next(); JsonNode value=patch.get(key);
            boolean valid=switch(key) {
                case "title" -> value.isTextual() && !value.asText().isBlank() && value.asText().length()<=200;
                case "description", "acceptance" -> value.isTextual();
                case "priority" -> value.isTextual() && Set.of("Must","Should","Could").contains(value.asText());
                case "sprint" -> value.isIntegralNumber() && value.canConvertToInt() && value.intValue()>=1 && value.intValue()<=4;
                case "activity" -> value.isIntegralNumber() && value.canConvertToInt() && value.intValue()>=1 && value.intValue()<=5;
                case "owner_id" -> value.isNull() || value.isIntegralNumber() && value.canConvertToInt() && value.intValue()>0;
                default -> false;
            };
            if (!valid) throw new IllegalArgumentException();
        }
    }

    private JsonNode json(Object text) {
        try { return mapper.readTree(text.toString()); }
        catch (Exception e) { throw new IllegalStateException("Stored review JSON is invalid", e); }
    }

    private Map<String,Object> analysis(String meetingId, String analysisId, boolean lock) {
        var rows = jdbc.queryForList("SELECT * FROM meeting_status_analyses WHERE meeting_id=? AND id=?" + (lock ? " FOR UPDATE" : ""), meetingId, analysisId);
        if (rows.isEmpty()) throw ApiException.notFound("状态分析记录不存在");
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
        return out;
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
        if (input.changes() != null) approved.set("changes",mapper.valueToTree(input.changes()));
        if (input.storyPatch() != null) approved.set("story_patch",input.storyPatch().deepCopy());
        String targetId = input.storyId() == null ? original.path("story_id").asText() : input.storyId();
        boolean retargeted = !targetId.equals(original.path("story_id").asText());
        if (approved != null) approved.put("story_id",targetId);
        var existing = jdbc.queryForList("SELECT * FROM meeting_status_proposal_reviews WHERE analysis_id=? AND proposal_index=?", analysisId,index);
        if (!existing.isEmpty()) {
            var previous = existing.getFirst();
            var previousApproved = previous.get("approved_json") == null ? null : json(previous.get("approved_json"));
            if (retargeted && approved != null && previousApproved != null)
                approved.set("expected",previousApproved.path("expected"));
            if (approved != null && previousApproved != null && previousApproved.has("expected_story"))
                approved.set("expected_story",previousApproved.path("expected_story"));
            if (((Number)previous.get("reviewed_by")).intValue() != reviewerId
                    || !previous.get("decision").equals(input.decision()) || !previous.get("reason").equals(input.reason())
                    || !Objects.equals(previousApproved,approved))
                throw ApiException.conflict("该提案已经审核，不能覆盖原决定");
            return out(previous);
        }
        if (approved != null) {
            int expected=original.path("expected").path("status").asInt();
            int desired=approved.path("changes").path("status").asInt();
            if (!retargeted && input.changes()!=null && input.storyPatch()==null && desired==original.path("changes").path("status").asInt())
                throw ApiException.unprocessable("修改后接受必须改变目标状态或目标故事");
            var stories=jdbc.queryForList("SELECT title,description,acceptance,priority,sprint,activity,status,owner_id FROM stories WHERE project_id=COALESCE((SELECT project_id FROM meetings WHERE id=?),'P001') AND code=? FOR UPDATE",meetingId,targetId);
            if (stories.isEmpty()) throw ApiException.conflict("目标故事不存在，请重新选择");
            var story=stories.getFirst();
            int current=((Number)story.get("status")).intValue();
            if (!retargeted && current!=expected)
                throw ApiException.conflict("故事状态已变化，请重新分析");
            if (retargeted) { expected=current; ((ObjectNode)approved.path("expected")).put("status",current); }
            if (input.storyPatch()!=null && input.storyPatch().has("owner_id") && !input.storyPatch().path("owner_id").isNull()) {
                Integer owner=input.storyPatch().path("owner_id").intValue();
                if (jdbc.queryForList("SELECT id FROM users WHERE id=?",owner).isEmpty())
                    throw ApiException.unprocessable("目标负责人不存在");
            }
            if (input.storyPatch()!=null) {
                var snapshot=mapper.createObjectNode();
                for (String field:List.of("title","description","acceptance","priority","sprint","activity","status","owner_id"))
                    snapshot.set(field,mapper.valueToTree(story.get(field)));
                approved.set("expected_story",snapshot);
            }
            boolean patchChanges=false;
            if (input.storyPatch()!=null) for (var fields=input.storyPatch().fieldNames();fields.hasNext();) {
                String field=fields.next();
                if (!input.storyPatch().get(field).equals(mapper.valueToTree(story.get(field)))) patchChanges=true;
            }
            if (input.changes()!=null && ((expected==desired && !patchChanges)
                    || (!retargeted && desired==original.path("changes").path("status").asInt() && !patchChanges))
               )
                throw ApiException.unprocessable("修改后接受必须改变目标故事或批准内容");
        }
        jdbc.update("INSERT INTO meeting_status_proposal_reviews (analysis_id,proposal_index,proposal_id,decision,reason,reviewed_by,reviewed_at,original_json,approved_json,execution_status) VALUES (?,?,?,?,?,?,CURRENT_TIMESTAMP,?,?,?)",
                analysisId,index,input.proposalId(),input.decision(),input.reason(),reviewerId,
                original.toString(),approved==null ? null : approved.toString(),approved==null ? "not_applicable" : "not_started");
        shadow.sync("daily", analysisId);
        return out(jdbc.queryForMap("SELECT * FROM meeting_status_proposal_reviews WHERE analysis_id=? AND proposal_index=?",analysisId,index));
    }

    @Transactional(readOnly=true, isolation=org.springframework.transaction.annotation.Isolation.REPEATABLE_READ)
    public ObjectNode list(String meetingId,String analysisId) {
        var proposals=json(analysis(meetingId,analysisId,false).get("result_json")).path("proposed_actions");
        var saved=jdbc.queryForList("SELECT * FROM meeting_status_proposal_reviews WHERE analysis_id=?",analysisId);
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
