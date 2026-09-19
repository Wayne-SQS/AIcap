package com.aicap.rag;

/**
 * 向量化失败。索引任务捕获后决定重试或跳过,不向上炸掉整个重建流程
 * —— 单条文本失败不该让整次索引白跑。
 */
public class EmbeddingException extends RuntimeException {

    public EmbeddingException(String message) {
        super(message);
    }

    public EmbeddingException(String message, Throwable cause) {
        super(message, cause);
    }
}
