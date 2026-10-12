package com.aicap.rag;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 一个待索引的知识块。
 *
 * @param sourceType  见本类的 {@code SRC_*} 常量
 * @param sourceId    源记录主键
 * @param chunkIndex  同源内序号,从 0 起
 * @param content     <b>增强前缀 + 原文</b>:向量化与关键词检索都用它
 * @param rawContent  <b>原文</b>:展示与证据引用用它
 * @param aclRole     最低可见角色
 * @param metadata    结构化字段(title/priority/sprint/seg_id…)落 metadata_json
 */
public record Chunk(String sourceType,
                    String sourceId,
                    int chunkIndex,
                    String content,
                    String rawContent,
                    String aclRole,
                    Map<String, Object> metadata) {

    public static final String SRC_STORY = "story";
    public static final String SRC_POOL_ITEM = "pool_item";
    public static final String SRC_TASK = "task";
    public static final String SRC_MEETING = "meeting";
    public static final String SRC_DOC = "doc";
    public static final String SRC_PROFILE = "profile";

    /** 落库列宽上限(与 schema.sql 的 varchar(64) 一致) */
    private static final int MAX_ID_CHARS = 64;

    /**
     * chunk id:{@code source_type:source_id:chunk_index}。
     *
     * <p>可读 id 对调试的价值很高(日志里一眼看出是哪条),但 {@code source_id} 长度不可控
     * (doc 源是中文路径)。超长时退化为「类型 + 源 id 摘要 + 序号」——
     * 摘要仍由源 id 唯一决定,所以稳定性(重索引得到同一个 id)不受影响。
     */
    public String id() {
        String raw = sourceType + ":" + sourceId + ":" + chunkIndex;
        if (raw.length() <= MAX_ID_CHARS) {
            return raw;
        }
        return sourceType + ":" + sha256Hex(sourceId).substring(0, 32) + ":" + chunkIndex;
    }

    /** 增量索引的比对依据:内容没变就不重新调 embedding(省钱,也让重建变得廉价) */
    public String contentHash() {
        return sha256Hex(content == null ? "" : content);
    }

    /** 带元数据的便捷构造(元数据保持插入顺序,便于日志与调试) */
    public static Chunk of(String sourceType, String sourceId, int chunkIndex,
                           String content, String rawContent, String aclRole) {
        return new Chunk(sourceType, sourceId, chunkIndex, content, rawContent, aclRole, new LinkedHashMap<>());
    }

    public Chunk withMeta(String key, Object value) {
        if (value != null) {
            metadata.put(key, value);
        }
        return this;
    }

    public static String sha256Hex(String s) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] digest = md.digest(s.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder(digest.length * 2);
            for (byte b : digest) {
                sb.append(Character.forDigit((b >> 4) & 0xF, 16)).append(Character.forDigit(b & 0xF, 16));
            }
            return sb.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 不可用", e);
        }
    }
}
