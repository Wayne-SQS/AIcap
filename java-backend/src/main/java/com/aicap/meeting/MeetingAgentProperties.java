package com.aicap.meeting;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * 会议智能体(ai-service,Python 8090)的接入配置。
 *
 * <p>为什么 Java 侧要专门有一份:平台层的触发型工具 {@code start_meeting_analysis} 是
 * <b>唯一</b>由 Java 主动反向调用 ai-service 的地方 —— 其余全部是 ai-service 调 Java。
 * 地址与超时若写死,换端口就得改代码;故照本仓惯例走
 * {@code @ConfigurationProperties} + {@code ${ENV:默认值}}。
 *
 * <p>{@code enabled=false} 时 {@link MeetingAnalysisTriggerImpl} 不注册,清单里那枚工具
 * 自动消失(与 {@code PlatformToolCatalog} 的"按实现发布"是同一套口径)。
 */
@Data
@Component
@ConfigurationProperties(prefix = "aicap.meeting-agent")
public class MeetingAgentProperties {

    /** 是否接入会议智能体;false 时触发型工具不发布 */
    private boolean enabled = true;

    /** ai-service 基址(与前端 Vite/nginx 反代的 /meeting-ai 指向同一个进程) */
    private String baseUrl = "http://127.0.0.1:8090";

    /** 建连超时(秒) */
    private int connectTimeoutSeconds = 5;

    /**
     * 单次分析超时(秒)。一次五类分析实测 1.8~2.6s,含模型往返;
     * 给到 120s 是给"上游慢/模型排队"留余量,同时不让主管的一次工具调用无限期挂住。
     */
    private int readTimeoutSeconds = 120;
}
