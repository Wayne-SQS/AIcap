package com.aicap.service;

import com.aicap.common.ApiException;
import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.node.*;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.util.*;

/** Creates only the durable human-approved action item and its audit atomically. */
@Service @RequiredArgsConstructor
public class RetroProposalExecutionService {
    private final JdbcTemplate jdbc;
    private final ObjectMapper mapper;

    private JsonNode json(Object value) {
        try { return mapper.readTree(value.toString()); }
        catch(Exception e) { throw new IllegalStateException("Invalid stored proposal",e); }
    }
    private Map<String,Object> analysis(String meetingId,String id,boolean lock) {
        var rows=jdbc.queryForList("SELECT * FROM meeting_retro_analyses WHERE meeting_id=? AND id=?"+(lock?" FOR UPDATE":""),meetingId,id);
        if(rows.isEmpty()) throw ApiException.notFound("复盘分析记录不存在");
        return rows.getFirst();
    }
    private ObjectNode out(Map<String,Object> row) {
        var result=mapper.createObjectNode().put("execution_status","succeeded");
        for(String key:List.of("analysis_id","proposal_id","action_item_id","executed_at")) result.put(key,row.get(key).toString());
        for(String key:List.of("action_item_log_id","executed_by")) result.put(key,((Number)row.get(key)).intValue());
        return result;
    }
    @Transactional
    public ObjectNode execute(String meetingId,String analysisId,JsonNode body,int executorId) {
        if(body==null || !body.isObject() || body.size()!=1 || !body.path("proposal_id").isTextual()
                || body.path("proposal_id").asText().isBlank() || body.path("proposal_id").asText().length()>80)
            throw ApiException.unprocessable("执行请求只能包含proposal_id");
        String id=body.path("proposal_id").asText();
        var proposals=json(analysis(meetingId,analysisId,true).get("result_json")).path("proposed_actions");
        int index=-1;
        for(int i=0;i<proposals.size();i++) if(proposals.get(i).path("proposal_id").asText().equals(id)) { index=i;break; }
        if(index<0) throw ApiException.notFound("提案不存在");
        var reviews=jdbc.queryForList("SELECT * FROM meeting_retro_proposal_reviews WHERE analysis_id=? AND proposal_index=? FOR UPDATE",analysisId,index);
        if(reviews.isEmpty() || reviews.getFirst().get("approved_json")==null
                || !Set.of("approve","modify_and_approve").contains(reviews.getFirst().get("decision")))
            throw ApiException.conflict("仅已批准提案可以执行");
        var existing=jdbc.queryForList("SELECT * FROM meeting_retro_proposal_executions WHERE analysis_id=? AND proposal_index=?",analysisId,index);
        if(!existing.isEmpty()) return out(existing.getFirst());
        var review=reviews.getFirst();
        if(!"not_started".equals(review.get("execution_status"))) throw ApiException.conflict("执行状态无效");
        var approved=json(review.get("approved_json"));
        if(!approved.path("action").asText().equals("create_action_item")) throw ApiException.conflict("批准动作无效");
        var changes=approved.path("changes");
        Integer owner=changes.path("owner_id").isNull()?null:changes.path("owner_id").intValue();
        if(owner!=null) {
            var users=jdbc.queryForList("SELECT display_name FROM users WHERE id=? FOR UPDATE",owner);
            if(users.isEmpty() || !Objects.equals(users.getFirst().get("display_name"),review.get("approved_owner_name")))
                throw ApiException.conflict("负责人资料已变化，请重新分析审核");
        }
        String actionId=UUID.randomUUID().toString();
        jdbc.update("INSERT INTO action_items(id,meeting_id,analysis_id,proposal_index,title,description,owner_id,deadline_text,status,created_by) VALUES (?,?,?,?,?,?,?,?,'open',?)",
            actionId,meetingId,analysisId,index,changes.path("title").asText(),changes.path("description").asText(),owner,
            changes.path("deadline_text").isNull()?null:changes.path("deadline_text").asText(),executorId);
        var key=new GeneratedKeyHolder();
        jdbc.update(connection -> {
            var statement=connection.prepareStatement("INSERT INTO action_item_logs(action_item_id,log_type,approved_proposal_json,user_id) VALUES (?,'create',?,?)",new String[]{"id"});
            statement.setString(1,actionId);statement.setString(2,approved.toString());statement.setInt(3,executorId);return statement;
        },key);
        int logId=Objects.requireNonNull(key.getKey()).intValue();
        jdbc.update("INSERT INTO meeting_retro_proposal_executions(analysis_id,proposal_index,proposal_id,action_item_id,action_item_log_id,executed_by) VALUES (?,?,?,?,?,?)",
            analysisId,index,id,actionId,logId,executorId);
        jdbc.update("UPDATE meeting_retro_proposal_reviews SET execution_status='succeeded' WHERE analysis_id=? AND proposal_index=?",analysisId,index);
        return out(jdbc.queryForMap("SELECT * FROM meeting_retro_proposal_executions WHERE analysis_id=? AND proposal_index=?",analysisId,index));
    }
    @Transactional(readOnly=true)
    public ArrayNode list(String meetingId,String analysisId) {
        analysis(meetingId,analysisId,false);
        var result=mapper.createArrayNode();
        for(var row:jdbc.queryForList("SELECT * FROM meeting_retro_proposal_executions WHERE analysis_id=? ORDER BY proposal_index",analysisId)) result.add(out(row));
        return result;
    }
}
