package com.aicap.service;

import com.aicap.common.ApiException;
import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.node.ObjectNode;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.util.*;

/** Immutable caller-submitted candidate snapshots. Approval records intent, never changes a story. */
@Service @RequiredArgsConstructor
public class AssignmentSuggestionService {
    private final JdbcTemplate jdbc;
    private final ObjectMapper mapper;

    private void require(boolean valid) { if (!valid) throw ApiException.unprocessable("分配建议载荷或匹配依据无效"); }
    private void fields(JsonNode node, String... names) {
        require(node != null && node.isObject());
        var expected = Set.of(names);
        node.fieldNames().forEachRemaining(key -> require(expected.contains(key)));
        require(node.size() == expected.size());
    }
    private JsonNode json(Object value) {
        try { return mapper.readTree(value.toString()); }
        catch (Exception e) { throw new IllegalStateException("Invalid stored assignment JSON", e); }
    }
    private ObjectNode out(Map<String,Object> row) {
        var result = mapper.createObjectNode();
        for (var key : List.of("id","meeting_id","client_request_id","created_at")) result.put(key,row.get(key).toString());
        result.put("submitted_by",((Number)row.get("submitted_by")).intValue());
        result.set("input",json(row.get("input_json")));
        result.set("result",json(row.get("result_json")));
        result.set("review",row.get("review_json") == null ? null : json(row.get("review_json")));
        return result;
    }
    private Map<String,Object> row(String meetingId,String id,boolean lock) {
        var rows=jdbc.queryForList("SELECT * FROM meeting_assignment_analyses WHERE meeting_id=? AND id=?"+(lock?" FOR UPDATE":""),meetingId,id);
        if(rows.isEmpty()) throw ApiException.notFound("分配建议不存在");
        return rows.getFirst();
    }
    private void validate(String meetingId,JsonNode body) {
        fields(body,"client_request_id","input","result");
        require(body.path("client_request_id").isTextual() && body.path("client_request_id").asText().matches("[a-z0-9-]{1,80}"));
        require(body.toString().length() <= 512*1024);
        var input=body.path("input"); fields(input,"story_ids","target_sprint","requirements");
        require(input.path("story_ids").isArray() && input.path("story_ids").size()==1);
        require(input.path("story_ids").get(0).isTextual() && input.path("story_ids").get(0).asText().matches("US[0-9]{1,8}"));
        var sprint=input.path("target_sprint");
        require(sprint.isNull() || (sprint.isInt() && sprint.asInt()>=1 && sprint.asInt()<=4));
        var requirements=input.path("requirements");
        require(requirements.isArray() && requirements.size()<=20);
        var keys=new HashSet<String>();
        for(var req:requirements) {
            fields(req,"dimension","name","minimum_level");
            require(Set.of("tech_stack","capabilities","process_domains").contains(req.path("dimension").asText()));
            require(req.path("name").isTextual() && !req.path("name").asText().isBlank() && req.path("name").asText().length()<=50);
            require(req.path("minimum_level").isInt() && req.path("minimum_level").asInt()>=1 && req.path("minimum_level").asInt()<=5);
            require(keys.add(req.path("dimension").asText()+":"+req.path("name").asText().strip().toLowerCase(Locale.ROOT)));
        }
        var result=body.path("result");
        fields(result,"rule_version","status","requirements_source","context","candidates","excluded_viewer_ids","writes_performed");
        require(result.path("rule_version").asText().equals("assignment-skills-v1") && result.path("requirements_source").asText().equals("caller_supplied"));
        require(result.path("writes_performed").isBoolean() && !result.path("writes_performed").asBoolean());
        var context=result.path("context");
        require(context.path("meeting_id").asText().equals(meetingId) && context.path("target_sprint").equals(sprint));
        require(context.path("scope").asText().equals("read_only_preparation") && context.path("snapshot_consistency").asText().equals("sequential_reads"));
        require(context.path("selected_stories").isArray() && context.path("selected_stories").size()==1);
        require(context.path("selected_stories").get(0).path("id").equals(input.path("story_ids").get(0)));
        require(context.path("members").isArray() && context.path("tasks").isArray() && context.path("gaps").isArray());
        require(result.path("candidates").isArray() && result.path("excluded_viewer_ids").isArray());
        var members=new HashMap<Integer,JsonNode>();
        for(var member:context.path("members")) {
            var profile=member.path("profile");
            require(profile.path("user_id").isInt() && profile.path("user_id").asInt()>0);
            require(members.put(profile.path("user_id").asInt(),profile)==null);
        }
        int index=0, previousScore=Integer.MAX_VALUE, previousId=0, rank=0;
        var seen=new HashSet<Integer>();
        for(var candidate:result.path("candidates")) {
            fields(candidate,"member_id","display_name","rank","matched_requirements","total_requirements","matches","capacity_check","suitability");
            int id=candidate.path("member_id").asInt();
            require(candidate.path("member_id").isInt() && seen.add(id) && members.containsKey(id));
            var profile=members.get(id);
            require(Set.of("admin","owner","member").contains(profile.path("role").asText()));
            require(candidate.path("display_name").isTextual() && candidate.path("display_name").equals(profile.path("display_name")));
            require(candidate.path("capacity_check").asText().equals("unknown") && candidate.path("suitability").asText().equals("requires_human_review"));
            require(candidate.path("total_requirements").isInt() && candidate.path("total_requirements").asInt()==requirements.size());
            var matches=candidate.path("matches"); require(matches.isArray() && matches.size()==requirements.size());
            int score=0;
            for(int n=0;n<matches.size();n++) {
                var match=matches.get(n); fields(match,"requirement","recorded_level","meets_requirement");
                require(match.path("requirement").equals(requirements.get(n)));
                var level=match.path("recorded_level");
                require(level.isNull() || (level.isInt() && level.asInt()>=1 && level.asInt()<=5));
                boolean meets=!level.isNull() && level.asInt()>=requirements.get(n).path("minimum_level").asInt();
                require(match.path("meets_requirement").isBoolean() && match.path("meets_requirement").asBoolean()==meets);
                if(meets) score++;
            }
            require(candidate.path("matched_requirements").isInt() && candidate.path("matched_requirements").asInt()==score);
            require(score<=previousScore && (score!=previousScore || id>previousId));
            if(score!=previousScore) rank=index+1;
            require(candidate.path("rank").isInt() && candidate.path("rank").asInt()==rank);
            previousScore=score; previousId=id; index++;
        }
        String status=requirements.isEmpty()?"requirements_needed":index==0?"no_eligible_members":result.path("candidates").get(0).path("matched_requirements").asInt()==0?"no_recorded_skill_match":"provisional";
        require(result.path("status").asText().equals(status) && (!requirements.isEmpty() || index==0));
    }
    @Transactional public ObjectNode save(String meetingId,JsonNode body,int userId) {
        validate(meetingId,body);
        if(jdbc.queryForList("SELECT id FROM meetings WHERE id=? FOR UPDATE",meetingId).isEmpty()) throw ApiException.notFound("会议不存在");
        var existing=jdbc.queryForList("SELECT * FROM meeting_assignment_analyses WHERE meeting_id=? AND submitted_by=? AND client_request_id=?",meetingId,userId,body.path("client_request_id").asText());
        if(!existing.isEmpty()) {
            var old=out(existing.getFirst());
            if(!old.path("input").equals(body.path("input")) || !old.path("result").equals(body.path("result"))) throw ApiException.conflict("同一请求编号不能提交不同分配建议");
            return old;
        }
        var snapshot=body.path("result").path("context").path("selected_stories").get(0);
        var stories=jdbc.queryForList("SELECT id,title,status,sprint,owner_id FROM stories WHERE id=? FOR UPDATE",snapshot.path("id").asText());
        if(stories.isEmpty()) throw ApiException.conflict("目标故事不存在");
        var current=mapper.valueToTree(stories.getFirst());
        for(var field:List.of("id","title","status","sprint","owner_id"))
            if(!current.path(field).equals(snapshot.path(field))) throw ApiException.conflict("故事快照已变化，请重新查询候选");
        var id=UUID.randomUUID().toString();
        jdbc.update("INSERT INTO meeting_assignment_analyses (id,meeting_id,submitted_by,client_request_id,input_json,result_json) VALUES (?,?,?,?,?,?)",id,meetingId,userId,body.path("client_request_id").asText(),body.path("input").toString(),body.path("result").toString());
        return get(meetingId,id);
    }
    public ObjectNode get(String meetingId,String id) { return out(row(meetingId,id,false)); }
    public List<ObjectNode> list(String meetingId) {
        if(jdbc.queryForList("SELECT id FROM meetings WHERE id=?",meetingId).isEmpty()) throw ApiException.notFound("会议不存在");
        return jdbc.queryForList("SELECT * FROM meeting_assignment_analyses WHERE meeting_id=? ORDER BY created_at DESC,id DESC",meetingId).stream().map(this::out).toList();
    }
    public ObjectNode find(String meetingId,String key,int userId) {
        var rows=jdbc.queryForList("SELECT * FROM meeting_assignment_analyses WHERE meeting_id=? AND submitted_by=? AND client_request_id=?",meetingId,userId,key);
        if(rows.isEmpty()) throw ApiException.notFound("分配建议不存在");
        return out(rows.getFirst());
    }
    @Transactional public ObjectNode review(String meetingId,String id,JsonNode body,int userId) {
        fields(body,"decision","member_id","reason","capacity_acknowledged");
        String decision=body.path("decision").asText();
        require(Set.of("approve","reject").contains(decision));
        require(body.path("reason").isTextual() && !body.path("reason").asText().isBlank() && body.path("reason").asText().length()<=1000);
        require(body.path("capacity_acknowledged").isBoolean());
        require(decision.equals("approve") ? body.path("member_id").isInt() && body.path("member_id").asInt()>0 && body.path("capacity_acknowledged").asBoolean() : body.path("member_id").isNull());
        var record=row(meetingId,id,true);
        if(record.get("review_json")!=null) {
            var old=json(record.get("review_json"));
            if(old.path("reviewed_by").asInt()!=userId || !old.path("input").equals(body)) throw ApiException.conflict("该建议已经审核，不能覆盖原决定");
            return (ObjectNode)old;
        }
        if(decision.equals("approve")) {
            var saved=json(record.get("result_json"));
            boolean candidate=false;
            for(var c:saved.path("candidates")) if(c.path("member_id").equals(body.path("member_id"))) candidate=true;
            require(candidate);
            var users=jdbc.queryForList("SELECT role FROM users WHERE id=? FOR UPDATE",body.path("member_id").asInt());
            if(users.isEmpty() || !Set.of("admin","owner","member").contains(users.getFirst().get("role").toString())) throw ApiException.conflict("所选成员已不可分配，请重新查询");
            var snapshot=saved.path("context").path("selected_stories").get(0);
            var stories=jdbc.queryForList("SELECT title,status,sprint,owner_id FROM stories WHERE id=? FOR UPDATE",snapshot.path("id").asText());
            if(stories.isEmpty()) throw ApiException.conflict("目标故事不存在");
            var current=mapper.valueToTree(stories.getFirst());
            for(var field:List.of("title","status","sprint","owner_id")) if(!current.path(field).equals(snapshot.path(field))) throw ApiException.conflict("故事已变化，请重新查询候选");
        }
        var review=mapper.createObjectNode(); review.set("input",body.deepCopy());
        review.put("reviewed_by",userId); review.put("reviewed_at",java.time.Instant.now().toString());
        review.put("status",decision.equals("approve")?"approved":"rejected");
        review.put("execution_status",decision.equals("approve")?"not_started":"not_applicable");
        jdbc.update("UPDATE meeting_assignment_analyses SET review_json=? WHERE id=?",review.toString(),id);
        return review;
    }

    @Transactional public JsonNode execute(String meetingId,String id,JsonNode body,int executorId) {
        // No client-supplied target or owner. The locked record is the sole authority.
        fields(body);
        var record=row(meetingId,id,true);
        if(record.get("review_json")==null) throw ApiException.conflict("分配建议尚未审核");
        ObjectNode review=(ObjectNode)json(record.get("review_json"));
        if(!review.path("status").asText().equals("approved")) throw ApiException.conflict("仅已批准分配可以执行");
        if(review.path("execution_status").asText().equals("succeeded")) return review.path("execution");
        if(!review.path("execution_status").asText().equals("not_started")) throw ApiException.conflict("执行状态无效");
        int owner=review.path("input").path("member_id").asInt();
        var users=jdbc.queryForList("SELECT role FROM users WHERE id=? FOR UPDATE",owner);
        if(users.isEmpty() || !Set.of("admin","owner","member").contains(users.getFirst().get("role").toString())) throw ApiException.conflict("所选成员已不可分配，请重新查询并审核");
        var snapshot=json(record.get("result_json")).path("context").path("selected_stories").get(0);
        String storyId=snapshot.path("id").asText();
        var stories=jdbc.queryForList("SELECT title,status,sprint,owner_id FROM stories WHERE id=? FOR UPDATE",storyId);
        if(stories.isEmpty()) throw ApiException.conflict("目标故事不存在");
        var current=mapper.valueToTree(stories.getFirst());
        for(var field:List.of("title","status","sprint","owner_id"))
            if(!current.path(field).equals(snapshot.path(field))) throw ApiException.conflict("故事已变化，请重新查询并审核");
        if(current.path("owner_id").isInt() && current.path("owner_id").asInt()==owner) throw ApiException.conflict("负责人未变化，无需执行");
        if(jdbc.update("UPDATE stories SET owner_id=? WHERE id=?",owner,storyId)!=1) throw ApiException.conflict("故事已变化");
        var keys=new org.springframework.jdbc.support.GeneratedKeyHolder();
        String detail="会议分配建议执行："+current.path("owner_id")+" → "+owner+"；meeting="+meetingId+"；suggestion="+id;
        jdbc.update(connection -> {
            var statement=connection.prepareStatement("INSERT INTO story_logs (story_id,log_type,detail,user_id,created_at) VALUES (?,'edit',?,?,CURRENT_TIMESTAMP)",java.sql.Statement.RETURN_GENERATED_KEYS);
            statement.setString(1,storyId); statement.setString(2,detail); statement.setInt(3,executorId); return statement;
        },keys);
        var execution=mapper.createObjectNode();
        execution.put("suggestion_id",id).put("meeting_id",meetingId).put("story_id",storyId).put("execution_status","succeeded");
        execution.set("previous_owner_id",current.path("owner_id")); execution.put("new_owner_id",owner);
        execution.put("story_log_id",Objects.requireNonNull(keys.getKey()).intValue()).put("executed_by",executorId).put("executed_at",java.time.Instant.now().toString());
        // Input/reviewer audit stays unchanged. Execution audit commits with the story and log.
        review.put("execution_status","succeeded"); review.set("execution",execution);
        jdbc.update("UPDATE meeting_assignment_analyses SET review_json=? WHERE id=?",review.toString(),id);
        return execution;
    }
}
