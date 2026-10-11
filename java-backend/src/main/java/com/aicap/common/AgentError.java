package com.aicap.common;

import lombok.Getter;

/**
 * Agent 域错误(对齐 FastAPI meeting_agent/schemas.AgentError):
 * code 为机器码(落 error_code),message 为用户可读文案(落 error_message)。
 *
 * <p>与同包的 {@link ApiException} 的区别:ApiException 携带 HTTP status,由
 * GlobalExceptionHandler 直接翻成响应;AgentError 只有机器码,由调用方决定
 * 落进哪里(会议 run 的 error_code、编排层工具结果的 ok=false)。两者语义相反,
 * 不要合并。
 *
 * <p>原先住在 {@code com.aicap.agent},但 planning / generation / platform / rag
 * 都在用——它从来不是会议专属,故移到共享层。
 */
@Getter
public class AgentError extends RuntimeException {

    private final String code;

    public AgentError(String code, String message) {
        super(message);
        this.code = code;
    }
}
