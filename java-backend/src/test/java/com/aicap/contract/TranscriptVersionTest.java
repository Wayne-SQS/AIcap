package com.aicap.contract;

import com.aicap.common.*;
import com.aicap.controller.TranscriptVersionController;
import com.aicap.entity.User;
import com.aicap.security.AuthContext;
import com.aicap.service.TranscriptVersionService;
import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.*;
import org.springframework.context.annotation.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.*;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.EnableTransactionManagement;
import javax.sql.DataSource;
import java.nio.charset.StandardCharsets;
import java.util.UUID;
import java.util.concurrent.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;

class TranscriptVersionTest {
    @Configuration @EnableTransactionManagement @Import(TranscriptVersionService.class)
    static class Config {
        @Bean DataSource ds() { return new DriverManagerDataSource("jdbc:h2:mem:"+UUID.randomUUID()+";MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1","sa",""); }
        @Bean JdbcTemplate jdbc(DataSource ds) { return new JdbcTemplate(ds); }
        @Bean PlatformTransactionManager tx(DataSource ds) { return new DataSourceTransactionManager(ds); }
        @Bean ObjectMapper mapper() { return new ObjectMapper(); }
    }
    AnnotationConfigApplicationContext context;
    JdbcTemplate jdbc; ObjectMapper mapper; TranscriptVersionService service; MockMvc mvc;
    String path="/api/meetings/m1/transcript-versions";
    @BeforeEach void setup() throws Exception {
        context=new AnnotationConfigApplicationContext(Config.class);
        jdbc=context.getBean(JdbcTemplate.class); mapper=context.getBean(ObjectMapper.class); service=context.getBean(TranscriptVersionService.class);
        jdbc.execute("CREATE TABLE users(id int primary key)");
        jdbc.execute("CREATE TABLE meetings(id varchar(36) primary key,title varchar(200),transcript text,created_by int,created_at timestamp)");
        jdbc.execute("CREATE TABLE meeting_audio(id varchar(36) primary key,meeting_id varchar(36),sha256 varchar(64))");
        try(var in=getClass().getResourceAsStream("/db/transcript-versions.sql")) {
            var sql=new String(in.readAllBytes(),StandardCharsets.UTF_8).replace(" ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci","");
            jdbc.execute(sql); jdbc.execute(sql);
        }
        jdbc.update("INSERT INTO users VALUES (1),(2)");
        jdbc.update("INSERT INTO meetings(id,title,transcript) VALUES ('m1','来源会议','原始文本'),('m2','另一会议','另一原文')");
        jdbc.update("INSERT INTO meeting_audio VALUES ('a1','m1',?)","a".repeat(64));
        mvc=MockMvcBuilders.standaloneSetup(new TranscriptVersionController(service)).setControllerAdvice(new GlobalExceptionHandler()).build(); role("member");
    }
    @AfterEach void close() { AuthContext.clear(); jdbc.execute("SHUTDOWN"); context.close(); }
    void role(String role) { var u=new User(); u.setId(1); u.setRole(role); AuthContext.set(u); }
    ObjectNode body() throws Exception {
        return (ObjectNode)mapper.readTree("""
          {"client_request_id":"r1","draft":{"audio_id":"a1","sha256":"%s","duration_ms":1000,"language":"zh","text":"原始草稿","segments":[{"segment_id":"S1","start_ms":0,"end_ms":900,"text":"原始草稿","speaker_id":null}]}}
          """.formatted("a".repeat(64)));
    }
    ObjectNode bodyWithPreview() throws Exception {
        var result=body(); ((ObjectNode)result.path("draft")).set("diarization",mapper.readTree("""
          {"engine":"sherpa-onnx-pyannote3-eres2net","duration_ms":1000,"requested_num_speakers":2,"speaker_count":2,
          "turns":[{"start_ms":0,"end_ms":600,"speaker_id":"SPK1"},{"start_ms":500,"end_ms":900,"speaker_id":"SPK2"}],
          "identity_status":"anonymous_only"}
          """)); return result;
    }
    ObjectNode confirmation() throws Exception { return (ObjectNode)mapper.readTree("{\"title\":\"已核对会议\",\"text\":\"修正后的文本\",\"acknowledged\":true}"); }
    ObjectNode alignedConfirmation() throws Exception {
        var input=confirmation(); input.set("speaker_alignment",mapper.readTree("""
          {"engine":"sherpa-onnx-pyannote3-eres2net","audio_sha256":"%s","duration_ms":1000,"speaker_count":2,
          "turns":[{"start_ms":0,"end_ms":600,"speaker_id":"SPK1"},{"start_ms":500,"end_ms":900,"speaker_id":"SPK2"}],
          "assignments":[{"segment_id":"S1","speaker_id":"SPK2"}]}
          """.formatted("a".repeat(64)))); return input;
    }
    ObjectNode alignedConfirmationV2() throws Exception {
        var input=confirmation(); input.set("speaker_alignment",mapper.readTree("""
          {"alignment_version":2,"engine":"sherpa-onnx-pyannote3-eres2net","audio_sha256":"%s","duration_ms":1000,"speaker_count":2,
          "turns":[{"start_ms":0,"end_ms":600,"speaker_id":"SPK1"},{"start_ms":500,"end_ms":900,"speaker_id":"SPK2"}],
          "assignments":[{"segment_id":"S1","speaker_id":"SPK2","overlapping_speakers":["SPK1","SPK2"]}]}
          """.formatted("a".repeat(64)))); return input;
    }
    @Test void immutableSaveRetryAndMeetingScope() throws Exception {
        var b=body(); var row=service.save("m1",b,1);
        assertEquals(row,service.save("m1",b,1)); assertEquals(1,service.list("m1").size()); assertTrue(service.list("m2").isEmpty());
        assertEquals("caller_submitted",row.path("provenance").asText());
        ((ObjectNode)b.path("draft")).put("language","en");
        assertEquals(409,assertThrows(ApiException.class,()->service.save("m1",b,1)).getStatus());
        assertEquals(404,assertThrows(ApiException.class,()->service.save("m2",b,1)).getStatus());
    }
    @Test void validatesHashTimelineTextAndUnknownSpeaker() throws Exception {
        var b=body(); ((ObjectNode)b.path("draft")).put("sha256","b".repeat(64));
        assertEquals(409,assertThrows(ApiException.class,()->service.save("m1",b,1)).getStatus());
        for(var field:java.util.List.of("text","start_ms","end_ms","speaker_id")) {
            var invalid=body(); var s=(ObjectNode)invalid.path("draft").path("segments").get(0);
            if(field.equals("start_ms")) s.put(field,-1); else if(field.equals("end_ms")) s.put(field,1001); else s.put(field,"伪造");
            assertEquals(422,assertThrows(ApiException.class,()->service.save("m1",invalid,1)).getStatus());
        }
        assertTrue(service.list("m1").isEmpty());
    }
    @Test void validatesOptionalWordTimestampsAndKeepsOldDraftCompatibility() throws Exception {
        var valid=body(); valid.put("client_request_id","words-ok"); var segment=(ObjectNode)valid.path("draft").path("segments").get(0);
        segment.set("words",mapper.readTree("""
          [{"word_id":"S1W1","start_ms":0,"end_ms":400,"text":"原始"},{"word_id":"S1W2","start_ms":400,"end_ms":900,"text":"草稿"}]
          """));
        assertEquals(2,service.save("m1",valid,1).path("draft").path("segments").get(0).path("words").size());
        var invalid=body(); invalid.put("client_request_id","words-bad"); ((ObjectNode)invalid.path("draft").path("segments").get(0)).set("words",mapper.readTree("""
          [{"word_id":"S1W1","start_ms":0,"end_ms":400,"text":"错误"}]
          """));
        assertEquals(422,assertThrows(ApiException.class,()->service.save("m1",invalid,1)).getStatus());
    }
    @Test void confirmationCreatesSeparateMeetingExactlyOnceAndPreservesDraft() throws Exception {
        var b=body(); var row=service.save("m1",b,1); var id=row.path("id").asText(); var input=confirmation();
        var confirmed=service.confirm("m1",id,input,1);
        assertEquals(confirmed,service.confirm("m1",id,input,1)); assertEquals(b.path("draft"),confirmed.path("draft"));
        var target=confirmed.path("confirmation").path("analysis_meeting_id").asText();
        assertEquals("修正后的文本",jdbc.queryForObject("SELECT transcript FROM meetings WHERE id=?",String.class,target));
        assertEquals("原始文本",jdbc.queryForObject("SELECT transcript FROM meetings WHERE id='m1'",String.class));
        assertEquals(3,jdbc.queryForObject("SELECT COUNT(*) FROM meetings",Integer.class));
        input.put("text","不同修订"); assertEquals(409,assertThrows(ApiException.class,()->service.confirm("m1",id,input,1)).getStatus());
        assertEquals(409,assertThrows(ApiException.class,()->service.confirm("m1",id,confirmation(),2)).getStatus());
        assertEquals(404,assertThrows(ApiException.class,()->service.confirm("m2",id,confirmation(),1)).getStatus());
    }
    @Test void concurrentConfirmationCreatesOneMeeting() throws Exception {
        var id=service.save("m1",body(),1).path("id").asText(); var input=confirmation(); var pool=Executors.newFixedThreadPool(2);
        try {
            var a=pool.submit(()->service.confirm("m1",id,input,1)); var b=pool.submit(()->service.confirm("m1",id,input,1));
            assertEquals(a.get(5,TimeUnit.SECONDS),b.get(5,TimeUnit.SECONDS));
            assertEquals(3,jdbc.queryForObject("SELECT COUNT(*) FROM meetings",Integer.class));
        } finally { pool.shutdownNow(); }
    }
    @Test void persistsValidatedAnonymousAlignmentAndRejectsFalseIdentityOrAudio() throws Exception {
        var id=service.save("m1",body(),1).path("id").asText(); var input=alignedConfirmation();
        var result=service.confirm("m1",id,input,1);
        assertEquals(input.path("speaker_alignment"),result.path("confirmation").path("input").path("speaker_alignment"));
        var otherId=service.save("m1",(ObjectNode)mapper.readTree(body().toString().replace("\"r1\"","\"r2\"")),1).path("id").asText();
        for(var fault:java.util.List.of("hash","label","segment","engine")) {
            var invalid=alignedConfirmation(); var alignment=(ObjectNode)invalid.path("speaker_alignment");
            if(fault.equals("hash")) alignment.put("audio_sha256","b".repeat(64));
            if(fault.equals("label")) ((ObjectNode)alignment.path("assignments").get(0)).put("speaker_id","Alice");
            if(fault.equals("segment")) ((ObjectNode)alignment.path("assignments").get(0)).put("segment_id","S2");
            if(fault.equals("engine")) alignment.put("engine","unknown");
            assertEquals(422,assertThrows(ApiException.class,()->service.confirm("m1",otherId,invalid,1)).getStatus());
        }
    }
    @Test void persistsValidatedDiarizationPreviewAndPinsConfirmationToIt() throws Exception {
        var body=bodyWithPreview(); var row=service.save("m1",body,1);
        assertEquals(body.path("draft").path("diarization"),row.path("draft").path("diarization"));
        var confirmed=service.confirm("m1",row.path("id").asText(),alignedConfirmationV2(),1);
        assertEquals(body.path("draft").path("diarization").path("turns"),confirmed.path("confirmation").path("input").path("speaker_alignment").path("turns"));
        var second=bodyWithPreview(); second.put("client_request_id","r2"); var secondId=service.save("m1",second,1).path("id").asText();
        var changed=alignedConfirmation(); ((ObjectNode)changed.path("speaker_alignment").path("turns").get(1)).put("end_ms",850);
        assertEquals(422,assertThrows(ApiException.class,()->service.confirm("m1",secondId,changed,1)).getStatus());
        var third=bodyWithPreview(); third.put("client_request_id","r3"); var thirdId=service.save("m1",third,1).path("id").asText();
        var falseOverlap=alignedConfirmationV2(); ((ObjectNode)falseOverlap.path("speaker_alignment").path("assignments").get(0)).putArray("overlapping_speakers").add("SPK2");
        assertEquals(422,assertThrows(ApiException.class,()->service.confirm("m1",thirdId,falseOverlap,1)).getStatus());
    }
    @Test void rejectsMalformedDiarizationPreview() throws Exception {
        for(var fault:java.util.List.of("engine","duration","identity","hint","label")) {
            var invalid=bodyWithPreview(); var preview=(ObjectNode)invalid.path("draft").path("diarization");
            if(fault.equals("engine")) preview.put("engine","unknown");
            if(fault.equals("duration")) preview.put("duration_ms",999);
            if(fault.equals("identity")) preview.put("identity_status","identified");
            if(fault.equals("hint")) preview.put("requested_num_speakers",9);
            if(fault.equals("label")) ((ObjectNode)preview.path("turns").get(0)).put("speaker_id","Alice");
            assertEquals(422,assertThrows(ApiException.class,()->service.save("m1",invalid,1)).getStatus());
        }
    }
    @Test void failedConfirmationRollsBackNewMeeting() throws Exception {
        var id=service.save("m1",body(),1).path("id").asText();
        jdbc.execute("ALTER TABLE meeting_transcript_versions ADD CONSTRAINT refuse_confirmation CHECK(confirmation_json IS NULL)");
        assertThrows(org.springframework.dao.DataIntegrityViolationException.class,()->service.confirm("m1",id,confirmation(),1));
        assertEquals(2,jdbc.queryForObject("SELECT COUNT(*) FROM meetings",Integer.class));
        assertTrue(service.list("m1").getFirst().path("confirmation").isNull());
    }
    @Test void foreignKeysRetainSourceAudioAndBothMeetings() throws Exception {
        var row=service.save("m1",body(),1); var confirmed=service.confirm("m1",row.path("id").asText(),confirmation(),1);
        for(var id:java.util.List.of("m1",confirmed.path("confirmation").path("analysis_meeting_id").asText()))
            assertThrows(org.springframework.dao.DataIntegrityViolationException.class,()->jdbc.update("DELETE FROM meetings WHERE id=?",id));
        assertThrows(org.springframework.dao.DataIntegrityViolationException.class,()->jdbc.update("DELETE FROM meeting_audio WHERE id='a1'"));
    }
    @Test void permissionsAndExplicitAcknowledgement() throws Exception {
        role("viewer"); assertEquals(403,mvc.perform(post(path).contentType("application/json").content(body().toString())).andReturn().getResponse().getStatus());
        var id=service.save("m1",body(),1).path("id").asText();
        assertEquals(403,mvc.perform(post(path+"/"+id+"/confirm").contentType("application/json").content(confirmation().toString())).andReturn().getResponse().getStatus());
        var invalid=confirmation(); invalid.put("acknowledged",false);
        assertEquals(422,assertThrows(ApiException.class,()->service.confirm("m1",id,invalid,1)).getStatus());
        role("member"); assertEquals(200,mvc.perform(post(path+"/"+id+"/confirm").contentType("application/json").content(confirmation().toString())).andReturn().getResponse().getStatus());
        AuthContext.clear(); assertEquals(401,mvc.perform(get(path)).andReturn().getResponse().getStatus());
    }
}
