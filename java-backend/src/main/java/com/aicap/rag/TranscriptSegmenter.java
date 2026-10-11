package com.aicap.rag;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

import org.springframework.stereotype.Component;

/**
 * 转写文本切分:按句末标点/换行切段,单段超 2000 字再硬切,编号 {@code seg-1}、{@code seg-2}…
 *
 * <p><b>编号方案是一个跨域契约,不是实现细节</b>:知识库切块
 * ({@link Chunker#chunkMeeting}) 用它把 {@code seg-N} 对齐成 {@code chunk_index N-1},
 * 会议 Agent 用它做证据逐字核对。两边必须切得一模一样,否则"检索命中的块"与
 * "模型引用的证据"会对不上。故从 {@code com.aicap.agent.AnalysisValidator} 拆出来共用。
 *
 * <p>切分逻辑与 Python 侧 {@code runner.segments_for} 逐行一致,改动需同步。
 */
@Component
public class TranscriptSegmenter {

    private static final Pattern SENTENCE_SPLIT =
            Pattern.compile("(?<=[。！？!?])|[\\r\\n]+");

    public record Segment(String id, String text) {
    }

    public List<Segment> segmentsFor(String transcript) {
        List<Segment> segments = new ArrayList<>();
        String[] lines = SENTENCE_SPLIT.split(transcript == null ? "" : transcript, -1);
        for (String rawLine : lines) {
            String line = rawLine.trim();
            if (line.isEmpty()) continue;
            for (int i = 0; i < line.length(); i += 2000) {
                int end = Math.min(i + 2000, line.length());
                segments.add(new Segment("seg-" + (segments.size() + 1), line.substring(i, end)));
            }
        }
        return segments;
    }
}
