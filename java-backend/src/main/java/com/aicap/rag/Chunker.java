package com.aicap.rag;

import com.aicap.agent.AnalysisValidator;
import com.aicap.entity.Meeting;
import com.aicap.entity.MemberProfile;
import com.aicap.entity.PoolItem;
import com.aicap.entity.Story;
import com.aicap.entity.Task;
import com.aicap.entity.User;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * 切分器:把各类数据源切成知识块(设计文档 A1)。
 *
 * <p><b>为什么每类源切法不同 —— 这是本层最该被讲清楚的地方</b>:
 * <ul>
 *   <li>结构化单行(story/pool_item/task/profile)自己就是一个语义单元,再切只会丢上下文;</li>
 *   <li>会议转写复用 {@link AnalysisValidator#segmentsFor},因为切分结果与 Agent 证据引用的
 *       {@code seg-N} 天然对齐 —— 检索命中的块可以直接当证据用,不用再做一次映射。
 *       <b>这是免费拿到的一致性</b>,自己另写一套切分反而会把这条链断掉;</li>
 *   <li>Markdown 文档按 {@code ##} 切,标题就是天然的语义边界。</li>
 * </ul>
 *
 * <p><b>上下文增强前缀</b>:结构化行单独成块时信息不足 —— 向量检索只看内容,
 * 不加前缀的话「Must」「Sprint 2」这些字段在语义上等于不存在,按「高优先级需求」检索必然漏。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class Chunker {

    private static final String STORY_STATUS_0 = "待办";
    private static final String STORY_STATUS_1 = "进行中";
    private static final String STORY_STATUS_2 = "完成";
    private static final String[] TASK_STATUS = {"待办", "进行中", "完成", "已取消"};

    private final RagProperties props;
    private final AnalysisValidator analysisValidator;

    private int maxChars() {
        return Math.max(200, props.getEmbedding().getMaxChars());
    }

    // ------------------------------------------------------------------
    // 用户故事
    // ------------------------------------------------------------------

    public List<Chunk> chunkStory(Story s) {
        StringBuilder raw = new StringBuilder();
        raw.append("标题：").append(nz(s.getTitle())).append('\n');
        raw.append("描述：").append(nz(s.getDescription())).append('\n');
        raw.append("验收标准：").append(nz(s.getAcceptance()));

        StringBuilder prefix = new StringBuilder();
        prefix.append("【用户故事 ").append(s.getId()).append("】\n");
        prefix.append("标题：").append(nz(s.getTitle())).append('\n');
        prefix.append("优先级：").append(nz(s.getPriority()))
                .append(" ｜ Sprint：").append(s.getSprint() == null ? "未定" : s.getSprint())
                .append(" ｜ 状态：").append(storyStatus(s.getStatus()))
                .append(" ｜ 负责人：").append(s.getOwnerId() == null ? "未分配" : ("#" + s.getOwnerId()))
                .append('\n');

        Chunk chunk = Chunk.of(Chunk.SRC_STORY, nz(s.getId()), 0,
                prefix + raw.toString(), raw.toString(), props.aclRoleFor(Chunk.SRC_STORY));
        chunk.withMeta("story_id", s.getId())
                .withMeta("title", s.getTitle())
                .withMeta("priority", s.getPriority())
                .withMeta("sprint", s.getSprint())
                .withMeta("status", storyStatus(s.getStatus()));
        return List.of(chunk);
    }

    // ------------------------------------------------------------------
    // 需求池
    // ------------------------------------------------------------------

    public List<Chunk> chunkPoolItem(PoolItem p) {
        StringBuilder raw = new StringBuilder();
        raw.append("标题：").append(nz(p.getTitle())).append('\n');
        raw.append("描述：").append(nz(p.getDescription()));

        StringBuilder prefix = new StringBuilder();
        prefix.append("【需求池 ").append(p.getId()).append("】\n");
        prefix.append("标题：").append(nz(p.getTitle())).append('\n');
        prefix.append("来源：").append(nz(p.getSource()))
                .append(" ｜ 优先级：").append(nz(p.getPriority())).append('\n');

        Chunk chunk = Chunk.of(Chunk.SRC_POOL_ITEM, nz(p.getId()), 0,
                prefix + raw.toString(), raw.toString(), props.aclRoleFor(Chunk.SRC_POOL_ITEM));
        chunk.withMeta("pool_item_id", p.getId())
                .withMeta("title", p.getTitle())
                .withMeta("priority", p.getPriority());
        return List.of(chunk);
    }

    // ------------------------------------------------------------------
    // 任务
    // ------------------------------------------------------------------

    /** @param storyTitles 卡 id → 故事标题,用于把 {@code kanban_card_id} 展开成人看得懂的名字 */
    public List<Chunk> chunkTask(Task t, java.util.Map<String, String> storyTitles) {
        String status = t.getStatus() == null || t.getStatus() < 0 || t.getStatus() >= TASK_STATUS.length
                ? "未知" : TASK_STATUS[t.getStatus()];
        String card = t.getKanbanCardId();
        String cardTitle = card == null ? null : storyTitles.get(card);

        StringBuilder raw = new StringBuilder();
        raw.append("任务名称：").append(nz(t.getName())).append('\n');
        raw.append("工时：").append(t.getHours() == null ? 0 : t.getHours()).append(" 小时\n");
        raw.append("周期：第 ").append(t.getWeekStart() == null ? "?" : t.getWeekStart())
                .append(" - ").append(t.getWeekEnd() == null ? "?" : t.getWeekEnd()).append(" 周");

        StringBuilder prefix = new StringBuilder();
        prefix.append("【任务 ").append(t.getId()).append("】\n");
        prefix.append("任务名称：").append(nz(t.getName())).append('\n');
        prefix.append("负责人：").append(t.getOwnerId() == null ? "未分配" : ("#" + t.getOwnerId()))
                .append(" ｜ 类型：").append(nz(t.getTaskType()))
                .append(" ｜ 状态：").append(status)
                .append(" ｜ 工时：").append(t.getHours() == null ? 0 : t.getHours()).append("h")
                .append(" ｜ 进度：").append(t.getProgress() == null ? 0 : t.getProgress()).append("%")
                .append('\n');
        if (card != null) {
            prefix.append("关联卡片：").append(card);
            if (cardTitle != null) {
                prefix.append("（").append(cardTitle).append("）");
            }
            prefix.append('\n');
        }
        if (t.getStoryRef() != null) {
            prefix.append("关联故事：").append(t.getStoryRef()).append('\n');
        }

        Chunk chunk = Chunk.of(Chunk.SRC_TASK, nz(t.getId()), 0,
                prefix + raw.toString(), raw.toString(), props.aclRoleFor(Chunk.SRC_TASK));
        chunk.withMeta("task_id", t.getId())
                .withMeta("name", t.getName())
                .withMeta("status", status)
                .withMeta("kanban_card_id", card)
                .withMeta("story_ref", t.getStoryRef());
        return List.of(chunk);
    }

    // ------------------------------------------------------------------
    // 会议转写 —— 复用 AnalysisValidator.segmentsFor,与证据引用 seg-N 对齐
    // ------------------------------------------------------------------

    public List<Chunk> chunkMeeting(Meeting m) {
        List<AnalysisValidator.Segment> segments = analysisValidator.segmentsFor(m.getTranscript());
        List<Chunk> chunks = new ArrayList<>(segments.size());
        for (int i = 0; i < segments.size(); i++) {
            AnalysisValidator.Segment seg = segments.get(i);
            String prefix = "【会议 " + nz(m.getTitle()) + "】片段 " + seg.id() + "\n";
            // chunkIndex = seg 号 - 1,保证 seg-7 恒为 chunk_index 6
            Chunk chunk = Chunk.of(Chunk.SRC_MEETING, nz(m.getId()), i,
                    prefix + seg.text(), seg.text(), props.aclRoleFor(Chunk.SRC_MEETING));
            chunk.withMeta("meeting_id", m.getId())
                    .withMeta("meeting_title", m.getTitle())
                    // 关键:检索命中后可直接把这个 id 当作 evidence.segment_id 回填
                    .withMeta("seg_id", seg.id());
            chunks.add(chunk);
        }
        return chunks;
    }

    // ------------------------------------------------------------------
    // 成员画像
    // ------------------------------------------------------------------

    public List<Chunk> chunkProfile(MemberProfile p, User u) {
        String who = u == null ? ("#" + p.getUserId()) : u.getDisplayName();

        StringBuilder raw = new StringBuilder();
        raw.append("岗位：").append(nz(p.getTitle())).append('\n');
        raw.append("摘要：").append(nz(p.getSummary())).append('\n');
        raw.append("技术栈：").append(nz(p.getTechStack())).append('\n');
        raw.append("工作能力：").append(nz(p.getCapabilities())).append('\n');
        raw.append("熟悉的开发流程领域：").append(nz(p.getProcessDomains()));

        StringBuilder prefix = new StringBuilder();
        prefix.append("【成员画像 ").append(who).append("】\n");
        prefix.append("岗位：").append(nz(p.getTitle()))
                .append(" ｜ 项目经验：").append(p.getYearsExperience() == null ? 0 : p.getYearsExperience()).append(" 年\n");

        Chunk chunk = Chunk.of(Chunk.SRC_PROFILE, String.valueOf(p.getUserId()), 0,
                prefix + raw.toString(), raw.toString(), props.aclRoleFor(Chunk.SRC_PROFILE));
        chunk.withMeta("user_id", p.getUserId())
                .withMeta("display_name", who)
                .withMeta("title", p.getTitle());
        return List.of(chunk);
    }

    // ------------------------------------------------------------------
    // 项目文档:按 ## 标题切,超长再按段落切
    // ------------------------------------------------------------------

    public List<Chunk> chunkDoc(String relativePath, String markdown) {
        List<Chunk> chunks = new ArrayList<>();
        if (markdown == null || markdown.isBlank()) {
            return chunks;
        }
        // 先按行扫描出「## 起始的章节」;## 之前的引言单独成块(它常含文档定位信息,丢了可惜)
        record Section(String title, String body) {
        }
        List<Section> sections = new ArrayList<>();
        String currentTitle = null;
        StringBuilder buf = new StringBuilder();
        for (String line : markdown.split("\n", -1)) {
            if (line.startsWith("## ")) {
                if (buf.length() > 0 && !buf.toString().isBlank()) {
                    sections.add(new Section(currentTitle, buf.toString()));
                }
                currentTitle = line.substring(3).trim();
                buf.setLength(0);
                buf.append(line).append('\n');
            } else {
                buf.append(line).append('\n');
            }
        }
        if (!buf.toString().isBlank()) {
            sections.add(new Section(currentTitle, buf.toString()));
        }

        int index = 0;
        for (Section section : sections) {
            String title = section.title() == null ? "(引言)" : section.title();
            List<String> pieces = splitLong(section.body().strip());
            for (String piece : pieces) {
                String prefix = "【项目文档 " + relativePath + "】章节：" + title + "\n";
                Chunk chunk = Chunk.of(Chunk.SRC_DOC, relativePath, index++,
                        prefix + piece, piece, props.aclRoleFor(Chunk.SRC_DOC));
                chunk.withMeta("doc_path", relativePath).withMeta("section", title);
                chunks.add(chunk);
            }
        }
        return chunks;
    }

    /** 超长章节按空行分段累加,尽量不切开一段话。单块上限与 embedding 单条上限一致,保证不被上游截断。 */
    private List<String> splitLong(String text) {
        int max = maxChars();
        List<String> out = new ArrayList<>();
        if (text.length() <= max) {
            out.add(text);
            return out;
        }
        StringBuilder buf = new StringBuilder();
        for (String para : text.split("\n\n")) {
            if (buf.length() > 0 && buf.length() + para.length() + 2 > max) {
                out.add(buf.toString().strip());
                buf.setLength(0);
            }
            if (para.length() > max) {
                // 单个段落就超限:只能硬切
                for (int i = 0; i < para.length(); i += max) {
                    out.add(para.substring(i, Math.min(i + max, para.length())));
                }
                continue;
            }
            buf.append(para).append("\n\n");
        }
        if (!buf.toString().isBlank()) {
            out.add(buf.toString().strip());
        }
        return out;
    }

    // ------------------------------------------------------------------
    // 小的格式化helper
    // ------------------------------------------------------------------

    private static String storyStatus(Integer s) {
        if (s == null) return STORY_STATUS_0;
        return switch (s) {
            case 1 -> STORY_STATUS_1;
            case 2 -> STORY_STATUS_2;
            default -> STORY_STATUS_0;
        };
    }

    private static String nz(String s) {
        return s == null ? "" : s;
    }
}
