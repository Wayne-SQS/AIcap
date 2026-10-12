package com.aicap.rag;

/**
 * 精排未能完成。{@code getMessage()} 会被写进 {@code retrieval_logs.degraded},
 * 因此消息要写成<b>给人看的原因</b>,而不是堆栈摘要。
 */
public class RerankException extends RuntimeException {
    public RerankException(String message) {
        super(message);
    }
}
