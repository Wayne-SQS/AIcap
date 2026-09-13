package com.aicap.contract;

import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;

import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 会议音频契约(网页录音 mp3 / 本地 mp3 提交):
 * - POST /api/meetings/{id}/audio(multipart) → 200,返回字节数/sha256/回放地址;
 * - GET  /api/meetings/{id}/audio → 列表可见;GET /api/audio/{id} → 字节与上传完全一致(可回放);
 * - 边界:非 .mp3 文件名、非 mp3 魔数、viewer 上传、会议不存在、缺文件部件、删除权限;
 * - 删除:member 403,admin 200 且删除后读取 404。
 * 音频落盘目录指向 target/test-audio,不污染运行目录 data/audio。
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "spring.datasource.url=jdbc:mysql://127.0.0.1:3307/aicap_java_test?useUnicode=true&characterEncoding=utf8&serverTimezone=Asia/Shanghai&allowPublicKeyRetrieval=true&useSSL=false",
        "spring.datasource.username=aiguanli",
        "spring.datasource.password=aiguanli-2026",
        "aicap.llm.agent-worker-enabled=false",
        "aicap.audio.dir=target/test-audio",
        "spring.sql.init.data-locations=classpath:db/reset_test_data.sql"
})
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class MeetingAudioContractTest extends ContractTestSupport {

    /** 结构合法的最小 mp3:ID3v2 头 + 一帧 MPEG 帧同步(0xFFFB90 64) */
    private static byte[] tinyMp3() {
        byte[] b = new byte[2048];
        b[0] = 'I';
        b[1] = 'D';
        b[2] = '3';
        b[3] = 3;
        b[10] = (byte) 0xFF;
        b[11] = (byte) 0xFB;
        b[12] = (byte) 0x90;
        b[13] = 0x64;
        return b;
    }

    private ApiResponse toApi(ResponseEntity<byte[]> resp) {
        byte[] raw = resp.getBody();
        String body = raw == null ? "" : new String(raw, StandardCharsets.UTF_8);
        JsonNode json = null;
        if (!body.isBlank()) {
            try {
                json = om.readTree(body);
            } catch (Exception ignored) {
                // 非 JSON(音频字节)响应
            }
        }
        return new ApiResponse(resp.getStatusCode().value(), json, body);
    }

    private ApiResponse upload(String meetingId, String token, String filename, byte[] bytes,
                               String source, Integer durationMs) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.MULTIPART_FORM_DATA);
        if (token != null) headers.setBearerAuth(token);
        MultiValueMap<String, Object> body = new LinkedMultiValueMap<>();
        if (filename != null || bytes != null) {
            byte[] payload = bytes == null ? new byte[0] : bytes;
            body.add("file", new ByteArrayResource(payload) {
                @Override
                public String getFilename() {
                    return filename;
                }
            });
        }
        if (source != null) body.add("source", source);
        if (durationMs != null) body.add("duration_ms", String.valueOf(durationMs));
        HttpEntity<MultiValueMap<String, Object>> entity = new HttpEntity<>(body, headers);
        return toApi(rest.exchange("/api/meetings/" + meetingId + "/audio", HttpMethod.POST, entity, byte[].class));
    }

    private ResponseEntity<byte[]> fetchBytes(String audioId, String token) {
        HttpHeaders headers = new HttpHeaders();
        if (token != null) headers.setBearerAuth(token);
        return rest.exchange("/api/audio/" + audioId, HttpMethod.GET, new HttpEntity<>(headers), byte[].class);
    }

    private String createMeeting(String title) {
        ApiResponse r = post("/api/meetings", token(USER_ADMIN),
                json(map("title", title, "transcript", "音频契约测试会议转写。")));
        assertStatus(r, 201);
        return r.json().path("id").asText();
    }

    @Test
    void upload_ok_listed_playbackIdenticalBytes_thenDelete() {
        String meetingId = createMeeting(uniq("音频契约会议"));
        byte[] mp3 = tinyMp3();

        ApiResponse up = upload(meetingId, token(USER_ADMIN), "录音-契约.mp3", mp3, "recorder", 3000);
        assertStatus(up, 200);
        String id = up.json().path("id").asText();
        assertEquals(mp3.length, up.json().path("byte_size").asInt());
        assertEquals("audio/mpeg", up.json().path("content_type").asText());
        assertEquals("recorder", up.json().path("source").asText());
        assertEquals(3000, up.json().path("duration_ms").asInt());
        assertTrue(up.json().path("url").asText().contains(id), up.body());

        ApiResponse list = get("/api/meetings/" + meetingId + "/audio", token(USER_MEMBER));
        assertStatus(list, 200);
        assertEquals(1, list.json().size(), list.body());

        // 回放字节与上传完全一致
        ResponseEntity<byte[]> play = fetchBytes(id, token(USER_MEMBER));
        assertEquals(200, play.getStatusCode().value());
        assertArrayEquals(mp3, play.getBody(), "回放字节必须与上传字节一致");
        assertEquals("audio/mpeg", play.getHeaders().getContentType().toString());

        // 删除后不可读
        assertStatus(delete("/api/audio/" + id, token(USER_ADMIN)), 200);
        assertEquals(404, fetchBytes(id, token(USER_ADMIN)).getStatusCode().value());
    }

    @Test
    void upload_localFileWithChineseName_ok() {
        String meetingId = createMeeting(uniq("音频契约会议-本地"));
        ApiResponse up = upload(meetingId, token(USER_MEMBER), "本地已有录音.mp3", tinyMp3(), "upload", null);
        assertStatus(up, 200);
        assertEquals("upload", up.json().path("source").asText());
        assertTrue(up.json().path("filename").asText().endsWith(".mp3"), up.body());
        assertStatus(delete("/api/audio/" + up.json().path("id").asText(), token(USER_ADMIN)), 200);
    }

    @Test
    void upload_wrongExtension_422() {
        String meetingId = createMeeting(uniq("音频契约会议-扩展名"));
        ApiResponse r = upload(meetingId, token(USER_ADMIN), "audio.wav", tinyMp3(), "upload", null);
        assertStatus(r, 422);
        assertTrue(detail(r).contains(".mp3"), r.body());
    }

    @Test
    void upload_badMagicBytes_422() {
        String meetingId = createMeeting(uniq("音频契约会议-魔数"));
        byte[] notMp3 = "definitely not an mp3".getBytes(StandardCharsets.UTF_8);
        ApiResponse r = upload(meetingId, token(USER_ADMIN), "fake.mp3", notMp3, "upload", null);
        assertStatus(r, 422);
        assertTrue(detail(r).contains("MP3"), r.body());
    }

    @Test
    void upload_invalidSource_422_andDurationOutOfRange_422() {
        String meetingId = createMeeting(uniq("音频契约会议-参数"));
        assertStatus(upload(meetingId, token(USER_ADMIN), "a.mp3", tinyMp3(), "hacker", null), 422);
        assertStatus(upload(meetingId, token(USER_ADMIN), "a.mp3", tinyMp3(), "recorder", 99_999_999), 422);
    }

    @Test
    void upload_viewer_403_andMissingPart_422() {
        String meetingId = createMeeting(uniq("音频契约会议-权限"));
        assertStatus(upload(meetingId, token(USER_VIEWER), "a.mp3", tinyMp3(), "upload", null), 403);
        // 不带 file 部件 → 422(而非 500)
        assertStatus(upload(meetingId, token(USER_ADMIN), null, null, "upload", null), 422);
    }

    @Test
    void upload_meetingNotFound_404() {
        ApiResponse r = upload("00000000-0000-0000-0000-000000000000", token(USER_ADMIN),
                "a.mp3", tinyMp3(), "upload", null);
        assertStatus(r, 404);
        assertTrue(detail(r).contains("会议不存在"), r.body());
    }

    @Test
    void delete_memberForbidden_adminOk() {
        String meetingId = createMeeting(uniq("音频契约会议-删除"));
        ApiResponse up = upload(meetingId, token(USER_ADMIN), "a.mp3", tinyMp3(), "upload", null);
        assertStatus(up, 200);
        String id = up.json().path("id").asText();

        ApiResponse byMember = delete("/api/audio/" + id, token(USER_MEMBER));
        assertStatus(byMember, 403);
        assertEquals(200, fetchBytes(id, token(USER_ADMIN)).getStatusCode().value(), "越权删除后音频仍应存在");

        assertStatus(delete("/api/audio/" + id, token(USER_ADMIN)), 200);
        assertEquals(404, fetchBytes(id, token(USER_ADMIN)).getStatusCode().value());
    }

    @Test
    void audioEndpoints_requireAuth_401() {
        String meetingId = createMeeting(uniq("音频契约会议-鉴权"));
        assertStatus(get("/api/meetings/" + meetingId + "/audio", null), 401);
        assertEquals(401, fetchBytes("whatever", null).getStatusCode().value());
        assertNotNull(meetingId);
    }
}
