package com.aicap.rag;

import com.aicap.agent.AnalysisValidator;
import com.aicap.entity.Meeting;
import com.aicap.entity.Story;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 切分器逻辑测试(不需要 Spring 上下文与数据库:切分是纯函数)。
 *
 * <p>重点覆盖两条最容易被改坏的约定:
 * <ol>
 *   <li><b>会议块的 chunk_index 与证据引用的 seg-N 必须对齐</b> —— 断了这条,
 *       检索命中的块就没法直接当证据用,整个"白拿的一致性"作废;</li>
 *   <li><b>content 带前缀、raw_content 不带</b> —— 混用会让证据引用里出现
 *       「优先级：Must」这种不是原文的内容,而证据校验只认原文逐字匹配。</li>
 * </ol>
 */
class ChunkerTest {

    private final RagProperties props = new RagProperties();
    private final Chunker chunker = new Chunker(props, new AnalysisValidator(new ObjectMapper()));

    private static Meeting meeting(String id, String title, String transcript) {
        Meeting m = new Meeting();
        m.setId(id);
        m.setTitle(title);
        m.setTranscript(transcript);
        return m;
    }

    private static Story story(String id, String title, String desc, String acceptance) {
        Story s = new Story();
        s.setId(id);
        s.setTitle(title);
        s.setDescription(desc);
        s.setAcceptance(acceptance);
        s.setPriority("Must");
        s.setSprint(2);
        s.setActivity(3);
        s.setStatus(1);
        s.setOwnerId(3);
        return s;
    }

    // ------------------------------------------------------------------
    // 会议:与 seg-N 对齐
    // ------------------------------------------------------------------

    @Test
    void meetingChunkIndexAlignsWithEvidenceSegmentIds() {
        List<Chunk> chunks = chunker.chunkMeeting(meeting("m-1", "周会",
                "第一句话讨论导入功能。第二句话确认字段映射。第三句话要求整批回滚。"));

        assertEquals(3, chunks.size(), "三句话应切出三个片段");
        for (int i = 0; i < chunks.size(); i++) {
            Chunk c = chunks.get(i);
            assertEquals(i, c.chunkIndex(), "chunkIndex 必须从 0 起且与段序一致");
            // 这是整条链的关键:seg-1 ↔ chunkIndex 0,seg-3 ↔ chunkIndex 2
            assertEquals("seg-" + (i + 1), c.metadata().get("seg_id"),
                    "第 " + i + " 块的 seg_id 必须是 seg-" + (i + 1));
            assertTrue(c.id().equals(Chunk.SRC_MEETING + ":m-1:" + i), "chunk id 应为 meeting:m-1:" + i);
            assertEquals(Chunk.SRC_MEETING, c.sourceType());
            assertEquals("m-1", c.sourceId());
        }
    }

    @Test
    void meetingRawContentStaysVerbatimForEvidenceChecks() {
        String sentence = "增加成员批量导入功能以降低管理员操作成本。";
        List<Chunk> chunks = chunker.chunkMeeting(meeting("m-2", "需求评审", sentence));

        assertEquals(1, chunks.size());
        Chunk c = chunks.get(0);
        // raw_content 必须与原文逐字一致 —— 证据校验用 segText.contains(quote) 核对
        assertTrue(c.rawContent().contains(sentence), "raw_content 应包含原文整句");
        assertEquals(sentence, c.rawContent());
        // content 是"前缀 + 原文",前缀里带会议标题
        assertTrue(c.content().startsWith("【会议 需求评审】片段 seg-1"), "content 应带增强前缀:" + c.content());
        assertTrue(c.content().endsWith(sentence));
    }

    // ------------------------------------------------------------------
    // 结构化行:前缀与原文分离
    // ------------------------------------------------------------------

    @Test
    void storyPrefixCarriesStructuredFieldsThatRawContentDoesNot() {
        List<Chunk> chunks = chunker.chunkStory(story("US12", "会议录音自动转写",
                "把会议录音转成文字。", "转写准确率不低于 90%。"));

        assertEquals(1, chunks.size(), "结构化行应单行成块");
        Chunk c = chunks.get(0);

        // 前缀必须把结构化字段"说进内容里",否则按「Must」「Sprint 2」检索会漏
        assertTrue(c.content().contains("优先级：Must"), "content 应含优先级:" + c.content());
        assertTrue(c.content().contains("Sprint：2"), "content 应含 Sprint:" + c.content());
        assertTrue(c.content().contains("状态：进行中"), "content 应含状态:" + c.content());
        assertTrue(c.content().contains("【用户故事 US12】"));

        // raw_content 不得含前缀字段,否则它会混进证据引用,而证据只认原文
        assertFalse(c.rawContent().contains("优先级："), "raw_content 不应含前缀:" + c.rawContent());
        assertFalse(c.rawContent().contains("Sprint："), "raw_content 不应含前缀:" + c.rawContent());
        assertTrue(c.rawContent().contains("把会议录音转成文字。"));
        assertTrue(c.rawContent().contains("转写准确率不低于 90%。"));
    }

    // ------------------------------------------------------------------
    // 文档:按标题切
    // ------------------------------------------------------------------

    @Test
    void docChunksSplitByHeading() {
        String markdown = """
                # 需求说明

                本文档描述批量导入。
                ## 概述
                管理员需要批量导入成员。
                ## 验收标准
                失败必须整批回滚。
                """;

        List<Chunk> chunks = chunker.chunkDoc("需求说明.md", markdown);

        assertEquals(3, chunks.size(), "引言 + 两个 ## 章节 = 3 块");
        assertEquals(0, chunks.get(0).chunkIndex());
        assertEquals("(引言)", chunks.get(0).metadata().get("section"));
        assertEquals("概述", chunks.get(1).metadata().get("section"));
        assertEquals("验收标准", chunks.get(2).metadata().get("section"));
        assertTrue(chunks.get(1).content().contains("管理员需要批量导入成员。"));
        assertTrue(chunks.get(1).content().startsWith("【项目文档 需求说明.md】章节：概述"));
        assertEquals("需求说明.md", chunks.get(1).sourceId());
    }

    @Test
    void docChunkIdStaysWithinColumnWidthForLongPaths() {
        String longPath = "很长的目录/".repeat(12) + "文档.md";
        List<Chunk> chunks = chunker.chunkDoc(longPath, "# 标题\n## 章节\n内容");

        assertTrue(chunks.size() >= 1);
        for (Chunk c : chunks) {
            assertTrue(c.id().length() <= 64, "chunk id 不能超过列宽 64,实际 " + c.id().length() + ": " + c.id());
        }
        // 摘要形式仍然可复现:同一路径再次切分得到同一个 id
        List<Chunk> again = chunker.chunkDoc(longPath, "# 标题\n## 章节\n内容");
        assertEquals(chunks.get(0).id(), again.get(0).id(), "同一源应得到稳定的 chunk id");
    }

    // ------------------------------------------------------------------
    // 内容摘要
    // ------------------------------------------------------------------

    @Test
    void contentHashTracksContentChanges() {
        Chunk a = chunker.chunkStory(story("US01", "标题", "描述", "验收")).get(0);
        Chunk b = chunker.chunkStory(story("US01", "标题", "描述", "验收")).get(0);
        Chunk c = chunker.chunkStory(story("US01", "标题改了", "描述", "验收")).get(0);

        assertEquals(a.contentHash(), b.contentHash(), "同内容应得同摘要(增量索引据此跳过)");
        assertNotEquals(a.contentHash(), c.contentHash(), "内容变了摘要必须变,否则永远不重新索引");
        assertEquals(64, a.contentHash().length(), "SHA-256 十六进制为 64 位");
    }

    @Test
    void aclRoleComesFromConfigPerSourceType() {
        props.getAcl().put(Chunk.SRC_DOC, "admin");

        assertEquals("admin", chunker.chunkDoc("a.md", "# t\n## s\n正文").get(0).aclRole());
        // 未配置的源默认 member —— 不能因为加了个 doc 配置就把别处也收紧
        assertEquals("member", chunker.chunkStory(story("US01", "t", "d", "a")).get(0).aclRole());
    }
}
