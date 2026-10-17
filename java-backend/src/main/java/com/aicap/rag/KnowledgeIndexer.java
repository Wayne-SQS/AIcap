package com.aicap.rag;

import com.aicap.entity.KnowledgeChunk;
import com.aicap.mapper.KnowledgeChunkMapper;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 知识库索引任务(设计文档 A8)。
 *
 * <p><b>重建为什么廉价</b>:每个块带 {@code content_hash},只有内容真正变了的块才会
 * 重新调 embedding。重复执行重建几乎没有 API 成本,因此可以放心挂到定时任务/按钮上。
 *
 * <p><b>为什么不做成"全有或全无"</b>:会议建议落库要求全有或全无(半落库的建议会误导人);
 * 索引不同 —— 它幂等且可重跑,部分成功比整体失败更有用(1000 块里错了 3 块,
 * 剩下 997 块立即可检索,重跑一次就补齐)。所以这里按批容错,失败批次计入报告。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class KnowledgeIndexer {

    private final KnowledgeChunkMapper chunkMapper;
    private final KnowledgeSourceScanner scanner;
    private final EmbeddingModel embeddingModel;
    private final VectorStore vectorStore;
    private final RagProperties props;
    private final ObjectMapper objectMapper;

    /** 一次索引的结果;字段直接进管理接口响应,前端据此展示进度与告警 */
    public record IndexReport(boolean enabled,
                              boolean configured,
                              String vectorStore,
                              String embeddingModel,
                              int scanned,
                              int created,
                              int updated,
                              int unchanged,
                              /** 其中因 embedding 模型变更而重算的块数(内容未变) */
                              int stale,
                              /** 其中因 aicap.rag.acl 配置变更而重算的块数(内容与模型均未变) */
                              int aclSynced,
                              int deleted,
                              int embedded,
                              int failed,
                              long latencyMs) {
    }

    // ------------------------------------------------------------------

    /** 全量重建。{@code force=true} 时无视 content_hash 全部重算(换 embedding 模型后用)。 */
    public IndexReport rebuild(boolean force) {
        if (!props.isEnabled()) {
            return skipped("RAG 未启用(aicap.rag.enabled=false)");
        }
        if (!props.getEmbedding().settingsReady()) {
            return skipped("未配置 embedding 密钥/模型,索引未执行");
        }
        return sync(scanner.scanAll(), force, "全量重建");
    }

    /** 单源重建:源记录写操作后调用,只重算这一条,不触发全量扫描。 */
    public IndexReport reindexOne(String sourceType, String sourceId) {
        if (!props.isEnabled() || !props.getEmbedding().settingsReady()) {
            return skipped("未配置 embedding 密钥/模型,索引未执行");
        }
        return sync(scanner.scanOne(sourceType, sourceId), false, sourceType + ":" + sourceId);
    }

    // ------------------------------------------------------------------

    private IndexReport sync(List<Chunk> chunks, boolean force, String what) {
        long started = System.currentTimeMillis();
        int dimension = props.getEmbedding().getDimension();
        vectorStore.ensureCollection(dimension);

        Map<String, KnowledgeChunk> existing = new HashMap<>();
        for (KnowledgeChunk row : chunkMapper.selectList(null)) {
            existing.put(row.getId(), row);
        }

        int created = 0;
        int updated = 0;
        int unchanged = 0;
        int stale = 0;
        int aclSynced = 0;
        String currentModel = embeddingModel.name();
        List<KnowledgeChunk> pending = new ArrayList<>();

        for (Chunk chunk : chunks) {
            String id = chunk.id();
            String hash = chunk.contentHash();
            KnowledgeChunk old = existing.remove(id);
            if (old == null) {
                KnowledgeChunk row = toRow(chunk, hash);
                chunkMapper.insert(row);
                pending.add(row);
                created++;
            } else {
                boolean contentChanged = !hash.equals(old.getContentHash());
                // 向量由别的模型产出:旧向量与新查询向量不在同一空间,留着比没有更糟 ——
                // 它会被当成有效结果返回且不报错。embedding_model 为空(早于本列存在的行)
                // 同样按过期处理:来历不明的向量不值得信任,重算一次的代价远小于静默错结果。
                boolean modelChanged = !currentModel.equals(old.getEmbeddingModel());
                // ACL 与 content_hash 无关(它是配置,不是内容),内容一字未改时收紧某个源的
                // 可见性,若不单独比对就会出现「配置已收紧、库里仍是旧角色」——
                // 权限静默失效,比检索结果不准严重得多。
                boolean aclChanged = !java.util.Objects.equals(old.getAclRole(), chunk.aclRole());

                if (force || contentChanged || modelChanged || aclChanged || old.getEmbeddedAt() == null) {
                    // 无条件重写字段:进这个分支就意味着某处不一致,而这里只是几个字段赋值,
                    // 省它没有收益,漏它就会留下上面那类「配置说 A、库里是 B」的静默错位
                    applyContent(old, chunk, hash);
                    if (modelChanged && old.getEmbeddedAt() != null) {
                        stale++;
                    }
                    if (aclChanged && !contentChanged && !modelChanged) {
                        aclSynced++;
                    }
                    // 向量作废就置空 embedded_at,让 stats 的 pending 数如实反映"还没算完";
                    // 万一本次 embedding 失败,下次重建也仍会判它过期。
                    // ACL 变更同样要重算:acl_rank 要进 Qdrant 的 payload,而 payload 只在
                    // upsert 向量时一起写,不重写向量就改不掉。MySQL 侧的 acl_rank 是表列,
                    // updateById 本可单独搞定 —— 但两套实现的行为必须一致,所以一并重算。
                    old.setEmbeddedAt(null);
                    chunkMapper.updateById(old);
                    pending.add(old);
                    updated++;
                } else {
                    unchanged++;
                }
            }
        }

        // 源记录已消失的块:连块带向量一起清,否则检索会命中"已不存在的东西"
        int deleted = 0;
        if (!existing.isEmpty()) {
            List<String> gone = new ArrayList<>(existing.keySet());
            vectorStore.deleteByIds(gone);
            chunkMapper.delete(new QueryWrapper<KnowledgeChunk>().in("id", gone));
            deleted = gone.size();
        }

        int embedded = 0;
        int failed = 0;
        int batch = Math.max(1, props.getEmbedding().getBatchSize());
        for (int i = 0; i < pending.size(); i += batch) {
            List<KnowledgeChunk> group = pending.subList(i, Math.min(i + batch, pending.size()));
            List<String> texts = new ArrayList<>(group.size());
            for (KnowledgeChunk c : group) {
                texts.add(c.getContent());
            }
            final List<float[]> vectors;
            try {
                vectors = embeddingModel.embed(texts);
            } catch (EmbeddingException e) {
                // 单批失败不该让整次重建白跑:留待下次重跑即可(有 content_hash,重跑很便宜)
                failed += group.size();
                log.warn("向量化失败,本批 {} 块留待下次重建: {}", group.size(), e.getMessage());
                continue;
            }
            List<VectorStore.Entry> entries = new ArrayList<>(group.size());
            for (int j = 0; j < group.size(); j++) {
                KnowledgeChunk c = group.get(j);
                entries.add(new VectorStore.Entry(c.getId(), vectors.get(j), payloadOf(c)));
            }
            // 向量库写入失败不吞:存储不可用是整体故障,要让调用方看见而不是静默半成品
            vectorStore.upsertBatch(entries);

            LocalDateTime now = LocalDateTime.now();
            for (KnowledgeChunk c : group) {
                c.setEmbeddedAt(now);
                // 记下是哪个模型算的:换模型后靠这一列识别出需要重算的旧向量
                c.setEmbeddingModel(currentModel);
                chunkMapper.updateById(c);
            }
            embedded += group.size();
        }

        long latency = System.currentTimeMillis() - started;
        log.info("知识库索引[{}]:扫描 {} 新建 {} 更新 {} 未变 {} 删除 {} 已向量化 {} 失败 {} 耗时 {}ms",
                what, chunks.size(), created, updated, unchanged, deleted, embedded, failed, latency);
        if (stale > 0) {
            log.warn("其中 {} 块的向量由旧模型产出,已按当前模型 {} 重算", stale, currentModel);
        }
        if (aclSynced > 0) {
            log.warn("其中 {} 块的可见角色与当前 aicap.rag.acl 配置不一致,已同步", aclSynced);
        }
        return new IndexReport(true, true, vectorStore.name(), embeddingModel.name(),
                chunks.size(), created, updated, unchanged, stale, aclSynced, deleted, embedded, failed, latency);
    }

    private IndexReport skipped(String reason) {
        log.info("知识库索引跳过:{}", reason);
        return new IndexReport(props.isEnabled(), props.getEmbedding().settingsReady(),
                vectorStore.name(), embeddingModel.name(), 0, 0, 0, 0, 0, 0, 0, 0, 0, 0);
    }

    // ------------------------------------------------------------------

    private KnowledgeChunk toRow(Chunk chunk, String hash) {
        KnowledgeChunk row = new KnowledgeChunk();
        row.setId(chunk.id());
        applyContent(row, chunk, hash);
        row.setCreatedAt(LocalDateTime.now());
        return row;
    }

    private void applyContent(KnowledgeChunk row, Chunk chunk, String hash) {
        row.setSourceType(chunk.sourceType());
        row.setSourceId(chunk.sourceId());
        row.setChunkIndex(chunk.chunkIndex());
        row.setContent(chunk.content());
        row.setRawContent(chunk.rawContent());
        row.setContentHash(hash);
        row.setAclRole(chunk.aclRole());
        // 角色与它的数值化在同一个地方写入,两者不可能对不上;SQL 侧只读 acl_rank,
        // 因此 MySQL 与 Qdrant 过滤的是同一个数(见 KnowledgeChunk.aclRank)
        row.setAclRank(RetrievalContext.rankOf(chunk.aclRole()));
        row.setMetadataJson(writeJson(chunk.metadata()));
    }

    /**
     * 进向量库的 payload。
     *
     * <p>{@code acl_rank} 取的是落库那一列,不在这里重算:它与 MySQL 侧 SQL 过滤的是
     * 同一个数(该列由 {@link #applyContent} 写入),两套实现因此不可能对同一个块
     * 得出不同的可见性。
     */
    private Map<String, Object> payloadOf(KnowledgeChunk c) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("chunk_id", c.getId());
        payload.put("source_type", c.getSourceType());
        payload.put("source_id", c.getSourceId());
        payload.put("acl_role", c.getAclRole());
        payload.put("acl_rank", c.getAclRank());
        if (c.getMetadataJson() != null && !c.getMetadataJson().isBlank()) {
            try {
                Map<?, ?> meta = objectMapper.readValue(c.getMetadataJson(), Map.class);
                meta.forEach((k, v) -> payload.putIfAbsent(String.valueOf(k), v));
            } catch (Exception e) {
                // 元数据解析失败不该阻断索引:它只是附加信息
                log.debug("metadata 解析失败 {}: {}", c.getId(), e.getMessage());
            }
        }
        return payload;
    }

    private String writeJson(Map<String, Object> metadata) {
        if (metadata == null || metadata.isEmpty()) {
            return null;
        }
        try {
            return objectMapper.writeValueAsString(metadata);
        } catch (Exception e) {
            log.warn("metadata 序列化失败,落空: {}", e.getMessage());
            return null;
        }
    }

    // ------------------------------------------------------------------

    /** 管理接口用:知识库现状 */
    public Map<String, Object> stats() {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("enabled", props.isEnabled());
        out.put("configured", props.getEmbedding().settingsReady());
        out.put("vector_store", vectorStore.name());
        out.put("embedding_model", embeddingModel.name());
        out.put("embedding_dimension", props.getEmbedding().getDimension());

        Map<String, Object> bySource = new LinkedHashMap<>();
        long total = 0;
        for (String type : List.of(Chunk.SRC_STORY, Chunk.SRC_POOL_ITEM, Chunk.SRC_TASK,
                Chunk.SRC_MEETING, Chunk.SRC_DOC, Chunk.SRC_PROFILE)) {
            long n = chunkMapper.selectCount(new QueryWrapper<KnowledgeChunk>().eq("source_type", type));
            bySource.put(type, n);
            total += n;
        }
        out.put("by_source", bySource);
        out.put("total_chunks", total);
        out.put("pending_chunks",
                chunkMapper.selectCount(new QueryWrapper<KnowledgeChunk>().isNull("embedded_at")));
        // 已索引但向量出自别的模型:不是"待索引",但检索结果同样不可信,单列出来避免被 pending=0 掩盖
        out.put("stale_vectors", chunkMapper.selectCount(new QueryWrapper<KnowledgeChunk>()
                .isNotNull("embedded_at")
                .and(w -> w.ne("embedding_model", embeddingModel.name()).or().isNull("embedding_model"))));
        out.put("total_vectors", vectorStore.count());
        // 角色与当前 aicap.rag.acl 配置不一致的块数。
        // 单独暴露的理由:ACL 是配置而非内容,改配置不会自动重索引 ——
        // 没有这个数,"收紧可见性"这件事是否真的落地了就只能靠翻日志。
        out.put("acl_mismatch", aclMismatchCount());
        return out;
    }

    /** 与当前 {@code aicap.rag.acl} 配置不一致的块数(按源逐类比对,不逐行扫库) */
    private long aclMismatchCount() {
        long n = 0;
        for (String type : List.of(Chunk.SRC_STORY, Chunk.SRC_POOL_ITEM, Chunk.SRC_TASK,
                Chunk.SRC_MEETING, Chunk.SRC_DOC, Chunk.SRC_PROFILE)) {
            n += chunkMapper.selectCount(new QueryWrapper<KnowledgeChunk>()
                    .eq("source_type", type)
                    .ne("acl_role", props.aclRoleFor(type)));
        }
        return n;
    }
}
