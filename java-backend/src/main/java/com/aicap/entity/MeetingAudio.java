package com.aicap.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 会议音频:网页麦克风录音(浏览器端编码为 mp3)或本地上传的 mp3。
 * 音频字节落盘({@code aicap.audio.dir}),本表只存元数据。
 */
@Data
@TableName("meeting_audio")
public class MeetingAudio {
    @TableId(type = IdType.INPUT)
    private String id;
    private String meetingId;
    /** 原始文件名(网页录音为 录音-时间戳.mp3) */
    private String filename;
    private String contentType;
    private Integer byteSize;
    /** 时长(毫秒),由前端录音上报,可为空 */
    private Integer durationMs;
    private String sha256;
    /** 相对音频根目录的落盘路径,如 2026/09/<uuid>.mp3 */
    private String storagePath;
    /** recorder=网页录音 / upload=本地文件 */
    private String source;
    private Integer uploadedBy;
    private LocalDateTime createdAt;
}
