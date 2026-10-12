package com.aicap.service;

import com.aicap.common.ApiException;
import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.node.ObjectNode;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.time.LocalDateTime;
import java.util.*;

/** Immutable caller-submitted drafts; confirmation creates a separately traceable analysis input. */
@Service @RequiredArgsConstructor
public class TranscriptVersionService {
    private final JdbcTemplate jdbc;
    private final ObjectMapper mapper;
    private void require(boolean ok) { if (!ok) throw ApiException.unprocessable("转写版本载荷无效"); }
    private void fields(JsonNode node, String... names) {
        require(node != null && node.isObject()); var keys=Set.of(names);
        require(node.size()==keys.size()); node.fieldNames().forEachRemaining(k -> require(keys.contains(k)));
    }
    private void text(JsonNode node,int max) { require(node.isTextual() && !node.asText().isBlank() && node.asText().length()<=max); }
    private JsonNode json(Object value) {
        try { return mapper.readTree(value.toString()); } catch(Exception e) { throw new IllegalStateException("Invalid transcript JSON",e); }
    }
    private ObjectNode out(Map<String,Object> row) {
        var result=mapper.createObjectNode();
        for(var key:List.of("id","meeting_id","audio_id","client_request_id","created_at")) result.put(key,row.get(key).toString());
        result.put("submitted_by",((Number)row.get("submitted_by")).intValue());
        result.put("provenance","caller_submitted");
        result.set("draft",json(row.get("draft_json")));
        result.set("confirmation",row.get("confirmation_json")==null ? null : json(row.get("confirmation_json")));
        return result;
    }
    private void meeting(String id) {
        if(jdbc.queryForList("SELECT id FROM meetings WHERE id=? FOR UPDATE",id).isEmpty()) throw ApiException.notFound("会议不存在");
    }
    private void validate(JsonNode body) {
        fields(body,"client_request_id","draft");
        require(body.path("client_request_id").isTextual() && body.path("client_request_id").asText().matches("[a-z0-9-]{1,80}"));
        require(body.toString().length()<=256*1024);
        var d=body.path("draft");
        require(d.isObject() && d.size()>=6 && d.size()<=7);
        d.fieldNames().forEachRemaining(key->require(Set.of("audio_id","sha256","duration_ms","language","text","segments","diarization").contains(key)));
        for(var key:List.of("audio_id","sha256","duration_ms","language","text","segments")) require(d.has(key));
        text(d.path("audio_id"),36); text(d.path("language"),20); text(d.path("text"),16000);
        require(d.path("sha256").isTextual() && d.path("sha256").asText().matches("[a-f0-9]{64}"));
        require(d.path("duration_ms").isInt() && d.path("duration_ms").asInt()>0 && d.path("duration_ms").asInt()<=600000);
        var segments=d.path("segments"); require(segments.isArray() && !segments.isEmpty() && segments.size()<=2000);
        var lines=new ArrayList<String>(); int previous=0,index=0;
        for(var s:segments) {
            require(s.isObject() && s.size()>=5 && s.size()<=6);
            s.fieldNames().forEachRemaining(key->require(Set.of("segment_id","start_ms","end_ms","text","speaker_id","words").contains(key)));
            for(var key:List.of("segment_id","start_ms","end_ms","text","speaker_id")) require(s.has(key));
            require(s.path("segment_id").asText().equals("S"+(++index)) && s.path("speaker_id").isNull());
            require(s.path("start_ms").isInt() && s.path("end_ms").isInt());
            int start=s.path("start_ms").asInt(),end=s.path("end_ms").asInt();
            require(start>=previous && start<end && end<=d.path("duration_ms").asInt()); previous=start;
            text(s.path("text"),4000); lines.add(s.path("text").asText());
            if(s.has("words") && !s.path("words").isNull()) {
                var words=s.path("words"); require(words.isArray() && !words.isEmpty() && words.size()<=1000);
                int wordPrevious=start,wordIndex=0; var wordText=new StringBuilder();
                for(var word:words) {
                    fields(word,"word_id","start_ms","end_ms","text");
                    require(word.path("word_id").asText().equals(s.path("segment_id").asText()+"W"+(++wordIndex)));
                    require(word.path("start_ms").isInt() && word.path("end_ms").isInt());
                    int wordStart=word.path("start_ms").asInt(),wordEnd=word.path("end_ms").asInt();
                    require(wordStart>=wordPrevious && wordStart>=start && wordStart<wordEnd && wordEnd<=end); wordPrevious=wordStart;
                    text(word.path("text"),200); wordText.append(word.path("text").asText());
                }
                require(wordText.toString().strip().equals(s.path("text").asText()));
            }
        }
        require(String.join("\n",lines).equals(d.path("text").asText()));
        validateDiarization(d.get("diarization"),d);
    }
    private LinkedHashSet<String> validateTurns(JsonNode turns, int duration, int speakerCount) {
        var labels=new LinkedHashSet<String>(); int previous=0;
        require(turns.isArray() && !turns.isEmpty() && turns.size()<=4000);
        for(var turn:turns) {
            fields(turn,"start_ms","end_ms","speaker_id");
            require(turn.path("start_ms").isInt() && turn.path("end_ms").isInt());
            int start=turn.path("start_ms").asInt(),end=turn.path("end_ms").asInt();
            require(start>=previous && start<end && end<=duration); previous=start;
            String label=turn.path("speaker_id").asText(); require(label.matches("SPK(?:[1-9]|[12][0-9]|3[0-2])"));
            if(labels.add(label)) require(label.equals("SPK"+labels.size()));
        }
        require(labels.size()==speakerCount); return labels;
    }
    private void validateDiarization(JsonNode preview,JsonNode draft) {
        if(preview==null || preview.isNull()) return;
        fields(preview,"engine","duration_ms","requested_num_speakers","speaker_count","turns","identity_status");
        require(preview.path("engine").asText().equals("sherpa-onnx-pyannote3-eres2net"));
        require(preview.path("identity_status").asText().equals("anonymous_only"));
        require(preview.path("duration_ms").isInt() && preview.path("duration_ms").asInt()==draft.path("duration_ms").asInt());
        var requested=preview.get("requested_num_speakers");
        require(requested.isNull() || (requested.isInt() && requested.asInt()>=1 && requested.asInt()<=8));
        require(preview.path("speaker_count").isInt() && preview.path("speaker_count").asInt()>=1 && preview.path("speaker_count").asInt()<=32);
        validateTurns(preview.path("turns"),preview.path("duration_ms").asInt(),preview.path("speaker_count").asInt());
    }
    private void validateOverlaps(JsonNode overlaps,int start,int end,JsonNode turns) {
        var expected=new LinkedHashSet<String>();
        for(var turn:turns)
            if(Math.min(end,turn.path("end_ms").asInt())>Math.max(start,turn.path("start_ms").asInt()))
                expected.add(turn.path("speaker_id").asText());
        require(overlaps.isArray() && overlaps.size()==expected.size());
        int index=0; for(var label:expected) require(overlaps.get(index++).asText().equals(label));
    }
    private void validateAlignment(JsonNode alignment, JsonNode draft, JsonNode confirmedText) {
        if (alignment == null || alignment.isNull()) return;
        int version=alignment.has("alignment_version") ? alignment.path("alignment_version").asInt(-1) : 1;
        if(version==1) fields(alignment,"engine","audio_sha256","duration_ms","speaker_count","turns","assignments");
        else {
            require(version==2 || version==3 || version==4);
            fields(alignment,"alignment_version","engine","audio_sha256","duration_ms","speaker_count","turns","assignments");
        }
        require(alignment.path("engine").asText().equals("sherpa-onnx-pyannote3-eres2net"));
        require(alignment.path("audio_sha256").equals(draft.path("sha256")));
        require(alignment.path("duration_ms").isInt() && alignment.path("duration_ms").asInt()==draft.path("duration_ms").asInt());
        require(alignment.path("speaker_count").isInt() && alignment.path("speaker_count").asInt()>=1 && alignment.path("speaker_count").asInt()<=32);
        var labels=validateTurns(alignment.path("turns"),alignment.path("duration_ms").asInt(),alignment.path("speaker_count").asInt());
        var stored= draft.get("diarization");
        if(stored!=null && !stored.isNull()) {
            require(alignment.path("engine").equals(stored.path("engine")));
            require(alignment.path("duration_ms").equals(stored.path("duration_ms")));
            require(alignment.path("speaker_count").equals(stored.path("speaker_count")));
            require(alignment.path("turns").equals(stored.path("turns")));
        }
        var assignments=alignment.path("assignments");
        var segments=draft.path("segments");
        if(version==3 || version==4) {
            require(assignments.isArray() && !assignments.isEmpty() && assignments.size()<=20000);
            int assignmentIndex=0; var correctedLines=new ArrayList<String>();
            for(var segment:segments) {
                var words=segment.path("words"); require(words.isArray() && !words.isEmpty());
                int wordIndex=0,partIndex=0; var correctedLine=new StringBuilder();
                while(assignmentIndex<assignments.size() && assignments.get(assignmentIndex).path("segment_id").equals(segment.path("segment_id"))) {
                    var item=assignments.get(assignmentIndex++);
                    if(version==3) fields(item,"assignment_id","segment_id","word_ids","text","speaker_id","overlapping_speakers");
                    else fields(item,"assignment_id","segment_id","word_ids","text","corrected_text","speaker_id","overlapping_speakers");
                    require(item.path("assignment_id").asText().equals(segment.path("segment_id").asText()+"A"+(++partIndex)));
                    require(item.path("speaker_id").isNull() || (item.path("speaker_id").isTextual() && labels.contains(item.path("speaker_id").asText())));
                    var wordIds=item.path("word_ids"); require(wordIds.isArray() && !wordIds.isEmpty());
                    var combined=new StringBuilder(); int start=-1,end=-1;
                    for(var wordId:wordIds) {
                        require(wordIndex<words.size() && wordId.equals(words.get(wordIndex).path("word_id")));
                        var word=words.get(wordIndex++); if(start<0) start=word.path("start_ms").asInt();
                        end=word.path("end_ms").asInt(); combined.append(word.path("text").asText());
                    }
                    require(item.path("text").isTextual() && item.path("text").asText().equals(combined.toString()));
                    if(version==4) {
                        require(item.path("corrected_text").isTextual() && item.path("corrected_text").asText().length()<=4000);
                        correctedLine.append(item.path("corrected_text").asText());
                    }
                    validateOverlaps(item.path("overlapping_speakers"),start,end,alignment.path("turns"));
                }
                require(partIndex>0 && wordIndex==words.size());
                if(version==4 && !correctedLine.isEmpty()) correctedLines.add(correctedLine.toString());
            }
            require(assignmentIndex==assignments.size());
            if(version==4) require(confirmedText.isTextual() && String.join("\n",correctedLines).equals(confirmedText.asText()));
            return;
        }
        require(assignments.isArray() && assignments.size()==segments.size());
        for(int i=0;i<assignments.size();i++) {
            var item=assignments.get(i);
            if(version==1) fields(item,"segment_id","speaker_id");
            else fields(item,"segment_id","speaker_id","overlapping_speakers");
            require(item.path("segment_id").equals(segments.get(i).path("segment_id")));
            require(item.path("speaker_id").isNull() || (item.path("speaker_id").isTextual() && labels.contains(item.path("speaker_id").asText())));
            if(version==2) {
                var segment=segments.get(i);
                validateOverlaps(item.path("overlapping_speakers"),segment.path("start_ms").asInt(),segment.path("end_ms").asInt(),alignment.path("turns"));
            }
        }
    }
    @Transactional public ObjectNode save(String meetingId,JsonNode body,int actor) {
        validate(body); meeting(meetingId);
        var rows=jdbc.queryForList("SELECT * FROM meeting_transcript_versions WHERE meeting_id=? AND submitted_by=? AND client_request_id=?",meetingId,actor,body.path("client_request_id").asText());
        if(!rows.isEmpty()) {
            var old=out(rows.getFirst());
            if(!old.path("draft").equals(body.path("draft"))) throw ApiException.conflict("同一请求不能保存不同转写；请恢复原请求或创建新版本");
            return old;
        }
        var draft=body.path("draft");
        var audio=jdbc.queryForList("SELECT sha256 FROM meeting_audio WHERE id=? AND meeting_id=? FOR UPDATE",draft.path("audio_id").asText(),meetingId);
        if(audio.isEmpty()) throw ApiException.notFound("音频不属于当前会议或已删除");
        if(!audio.getFirst().get("sha256").equals(draft.path("sha256").asText())) throw ApiException.conflict("音频内容标识不一致");
        String id=UUID.randomUUID().toString();
        jdbc.update("INSERT INTO meeting_transcript_versions(id,meeting_id,audio_id,submitted_by,client_request_id,draft_json) VALUES(?,?,?,?,?,?)",id,meetingId,draft.path("audio_id").asText(),actor,body.path("client_request_id").asText(),draft.toString());
        return out(jdbc.queryForMap("SELECT * FROM meeting_transcript_versions WHERE id=?",id));
    }
    public List<ObjectNode> list(String meetingId) {
        if(jdbc.queryForList("SELECT id FROM meetings WHERE id=?",meetingId).isEmpty()) throw ApiException.notFound("会议不存在");
        return jdbc.queryForList("SELECT * FROM meeting_transcript_versions WHERE meeting_id=? ORDER BY created_at DESC,id DESC",meetingId).stream().map(this::out).toList();
    }
    @Transactional public ObjectNode confirm(String meetingId,String id,JsonNode body,int actor) {
        require(body!=null && body.isObject() && body.has("title") && body.has("text") && body.has("acknowledged") && body.size()>=3 && body.size()<=4);
        body.fieldNames().forEachRemaining(key->require(Set.of("title","text","acknowledged","speaker_alignment").contains(key)));
        text(body.path("title"),200); text(body.path("text"),16000);
        require(body.path("acknowledged").isBoolean() && body.path("acknowledged").asBoolean());
        meeting(meetingId);
        var rows=jdbc.queryForList("SELECT * FROM meeting_transcript_versions WHERE meeting_id=? AND id=? FOR UPDATE",meetingId,id);
        if(rows.isEmpty()) throw ApiException.notFound("转写版本不存在");
        var row=rows.getFirst();
        validateAlignment(body.get("speaker_alignment"),json(row.get("draft_json")),body.path("text"));
        if(row.get("confirmation_json")!=null) {
            var old=json(row.get("confirmation_json"));
            if(old.path("confirmed_by").asInt()!=actor || !old.path("input").equals(body)) throw ApiException.conflict("此版本已经确认；请读取已保存结果");
            return out(row);
        }
        String target=UUID.randomUUID().toString(); var now=LocalDateTime.now();
        jdbc.update("INSERT INTO meetings(id,title,transcript,created_by,created_at) VALUES(?,?,?,?,?)",target,body.path("title").asText(),body.path("text").asText(),actor,now);
        var confirmation=mapper.createObjectNode(); confirmation.set("input",body.deepCopy());
        confirmation.put("confirmed_by",actor).put("confirmed_at",now.toString()).put("analysis_meeting_id",target);
        jdbc.update("UPDATE meeting_transcript_versions SET confirmation_json=?,analysis_meeting_id=? WHERE id=?",confirmation.toString(),target,id);
        return out(jdbc.queryForMap("SELECT * FROM meeting_transcript_versions WHERE id=?",id));
    }
}
