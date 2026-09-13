package com.aicap.service;

import com.aicap.common.ApiException;
import com.aicap.dto.MeetingAudioDtos;
import com.aicap.entity.MeetingAudio;
import com.aicap.entity.User;
import com.aicap.mapper.MeetingAudioMapper;
import com.aicap.mapper.MeetingMapper;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;

/**
 * 会议音频服务:接收网页麦克风录音(前端已编码为 mp3)或本地 .mp3 文件。
 * - 只接受真实 mp3(扩展名 + 魔数双重校验),大小可配(默认 25MB)
 * - 字节落盘,元数据入库;回放/下载走带鉴权的读取接口
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class MeetingAudioService {

    private static final Set<String> SOURCES = Set.of("recorder", "upload");
    private static final DateTimeFormatter MONTH_DIR = DateTimeFormatter.ofPattern("yyyy/MM");
    /** 6 小时上限,防前端上报异常时长 */
    private static final int MAX_DURATION_MS = 6 * 60 * 60 * 1000;

    private final MeetingAudioMapper audioMapper;
    private final MeetingMapper meetingMapper;

    @Value("${aicap.audio.dir:./data/audio}")
    private String audioDir;

    @Value("${aicap.audio.max-bytes:26214400}")
    private long maxBytes;

    /** 上传:写盘 + 入库,任一步失败都不留半份数据 */
    public MeetingAudioDtos.AudioOut store(String meetingId, MultipartFile file, String source,
                                           Integer durationMs, User actor) {
        if (meetingMapper.selectById(meetingId) == null) {
            throw ApiException.notFound("会议不存在");
        }
        if (file == null || file.isEmpty()) {
            throw ApiException.unprocessable("请选择或录制一段音频后再提交");
        }
        if (file.getSize() > maxBytes) {
            throw new ApiException(413, "音频超过上限 " + (maxBytes / 1024 / 1024) + "MB");
        }
        String original = file.getOriginalFilename() == null ? "" : file.getOriginalFilename();
        if (!original.toLowerCase(Locale.ROOT).endsWith(".mp3")) {
            throw ApiException.unprocessable("仅支持 .mp3 文件（网页录音会由浏览器编码为 mp3）");
        }
        String normalizedSource = source == null || source.isBlank() ? "upload" : source.trim();
        if (!SOURCES.contains(normalizedSource)) {
            throw ApiException.unprocessable("source 只能是 recorder 或 upload");
        }
        if (durationMs != null && (durationMs < 0 || durationMs > MAX_DURATION_MS)) {
            throw ApiException.unprocessable("duration_ms 超出合理范围");
        }

        final byte[] bytes;
        try {
            bytes = file.getBytes();
        } catch (IOException e) {
            throw ApiException.badRequest("音频读取失败，请重试");
        }
        if (!looksLikeMp3(bytes)) {
            throw ApiException.unprocessable("文件不是有效的 MP3（缺少 ID3 或帧同步头）");
        }

        String id = UUID.randomUUID().toString();
        String relative = LocalDateTime.now().format(MONTH_DIR) + "/" + id + ".mp3";
        Path target = resolveInsideRoot(relative);
        try {
            Files.createDirectories(target.getParent());
            Files.write(target, bytes, StandardOpenOption.CREATE_NEW);
        } catch (IOException e) {
            throw new ApiException(500, "音频落盘失败：" + e.getMessage());
        }

        MeetingAudio audio = new MeetingAudio();
        audio.setId(id);
        audio.setMeetingId(meetingId);
        audio.setFilename(original.isBlank() ? "recording.mp3" : original.substring(0, Math.min(255, original.length())));
        audio.setContentType("audio/mpeg");
        audio.setByteSize(bytes.length);
        audio.setDurationMs(durationMs);
        audio.setSha256(sha256(bytes));
        audio.setStoragePath(relative);
        audio.setSource(normalizedSource);
        audio.setUploadedBy(actor.getId());
        audio.setCreatedAt(LocalDateTime.now());
        try {
            audioMapper.insert(audio);
        } catch (RuntimeException e) {
            try {
                Files.deleteIfExists(target);
            } catch (IOException ignored) {
                // 落盘文件清理失败不影响对外错误语义
            }
            throw e;
        }
        return toOut(audio);
    }

    public List<MeetingAudioDtos.AudioOut> list(String meetingId) {
        if (meetingMapper.selectById(meetingId) == null) {
            throw ApiException.notFound("会议不存在");
        }
        List<MeetingAudio> rows = audioMapper.selectList(new QueryWrapper<MeetingAudio>()
                .eq("meeting_id", meetingId).orderByDesc("created_at").orderByDesc("id"));
        List<MeetingAudioDtos.AudioOut> out = new ArrayList<>();
        for (MeetingAudio row : rows) {
            out.add(toOut(row));
        }
        return out;
    }

    /** 读取音频字节(回放/下载) */
    public LoadedAudio load(String audioId) {
        MeetingAudio audio = audioMapper.selectById(audioId);
        if (audio == null) throw ApiException.notFound("音频不存在");
        Path path = resolveInsideRoot(audio.getStoragePath());
        if (!Files.exists(path)) throw ApiException.notFound("音频文件已丢失，请重新上传");
        try {
            return new LoadedAudio(audio, Files.readAllBytes(path));
        } catch (IOException e) {
            throw new ApiException(500, "音频读取失败：" + e.getMessage());
        }
    }

    /** 删除:仅 admin/owner;先删库再删盘,盘上缺失不报错 */
    public void delete(String audioId, User actor) {
        if (!Set.of("admin", "owner").contains(actor.getRole())) {
            throw ApiException.forbidden("仅管理员/负责人可删除会议音频");
        }
        MeetingAudio audio = audioMapper.selectById(audioId);
        if (audio == null) throw ApiException.notFound("音频不存在");
        audioMapper.deleteById(audioId);
        try {
            Files.deleteIfExists(resolveInsideRoot(audio.getStoragePath()));
        } catch (IOException e) {
            log.warn("音频文件删除失败 id={} path={}", audioId, audio.getStoragePath());
        }
    }

    public record LoadedAudio(MeetingAudio meta, byte[] bytes) {
    }

    // ---------- 内部 ----------

    /** 只接受库中生成的相对路径,并阻断 ../ 越权访问 */
    private Path resolveInsideRoot(String relative) {
        Path root = Paths.get(audioDir).toAbsolutePath().normalize();
        Path resolved = root.resolve(relative).normalize();
        if (!resolved.startsWith(root)) {
            throw ApiException.badRequest("音频路径非法");
        }
        return resolved;
    }

    /** mp3 判定:ID3v2 头("ID3")或 MPEG 帧同步(0xFFEx/0xFFFx) */
    private boolean looksLikeMp3(byte[] bytes) {
        if (bytes.length < 4) return false;
        if (bytes[0] == 'I' && bytes[1] == 'D' && bytes[2] == '3') return true;
        return (bytes[0] & 0xFF) == 0xFF && (bytes[1] & 0xE0) == 0xE0;
    }

    private String sha256(byte[] bytes) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private MeetingAudioDtos.AudioOut toOut(MeetingAudio a) {
        return new MeetingAudioDtos.AudioOut(a.getId(), a.getMeetingId(), a.getFilename(),
                a.getContentType(), a.getByteSize(), a.getDurationMs(), a.getSha256(), a.getSource(),
                a.getUploadedBy(), a.getCreatedAt(), "/api/audio/" + a.getId());
    }
}
