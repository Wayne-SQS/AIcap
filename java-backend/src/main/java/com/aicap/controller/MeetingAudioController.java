package com.aicap.controller;

import com.aicap.dto.MeetingAudioDtos;
import com.aicap.entity.User;
import com.aicap.security.Roles;
import com.aicap.service.MeetingAudioService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

/**
 * 会议音频接口:网页麦克风录音(前端编码为 mp3)与本地 .mp3 文件提交、列表、回放、删除。
 * 上传需写角色(admin/owner/member),回放任意登录用户,删除仅 admin/owner。
 */
@RestController
@RequestMapping("/api")
@RequiredArgsConstructor
public class MeetingAudioController {

    private final MeetingAudioService audioService;

    @PostMapping("/meetings/{meetingId}/audio")
    public MeetingAudioDtos.AudioOut upload(@PathVariable String meetingId,
                                            @RequestParam("file") MultipartFile file,
                                            @RequestParam(value = "source", required = false) String source,
                                            @RequestParam(value = "duration_ms", required = false) Integer durationMs) {
        User actor = Roles.writer();
        return audioService.store(meetingId, file, source, durationMs, actor);
    }

    @GetMapping("/meetings/{meetingId}/audio")
    public List<MeetingAudioDtos.AudioOut> list(@PathVariable String meetingId) {
        Roles.any();
        return audioService.list(meetingId);
    }

    /** 回放/下载:返回 mp3 字节流 */
    @GetMapping("/audio/{audioId}")
    public ResponseEntity<byte[]> play(@PathVariable String audioId,
                                       @RequestParam(value = "download", required = false) String download) {
        Roles.any();
        MeetingAudioService.LoadedAudio loaded = audioService.load(audioId);
        boolean asAttachment = "1".equals(download) || "true".equalsIgnoreCase(download);
        ContentDisposition disposition = (asAttachment ? ContentDisposition.attachment() : ContentDisposition.inline())
                .filename(loaded.meta().getFilename(), StandardCharsets.UTF_8).build();
        return ResponseEntity.ok()
                .contentType(MediaType.parseMediaType("audio/mpeg"))
                .header(HttpHeaders.CONTENT_DISPOSITION, disposition.toString())
                .header(HttpHeaders.CONTENT_LENGTH, String.valueOf(loaded.bytes().length))
                .header("X-Audio-Sha256", loaded.meta().getSha256())
                .body(loaded.bytes());
    }

    @DeleteMapping("/audio/{audioId}")
    public Map<String, Object> delete(@PathVariable String audioId) {
        User actor = Roles.any();
        audioService.delete(audioId, actor);
        return Map.of("ok", true);
    }
}
