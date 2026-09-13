package com.aicap.agent;

import lombok.Getter;

/**
 * Agent 域错误(对齐 FastAPI meeting_agent/schemas.AgentError):
 * code 为机器码(落 error_code),message 为用户可读文案(落 error_message)。
 */
@Getter
public class AgentError extends RuntimeException {

    private final String code;

    public AgentError(String code, String message) {
        super(message);
        this.code = code;
    }
}
