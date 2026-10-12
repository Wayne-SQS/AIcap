package com.aicap.rag;

import com.aicap.mapper.KnowledgeChunkMapper;
import com.aicap.mapper.RetrievalGraphMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 图路召回(设计文档 A5):沿故事 ↔ 任务的结构边展开一跳。
 *
 * <p><b>这条边是不对称的,收益也只在一个方向上</b> —— 实测后的结论,不是设计时的假设:
 * <ul>
 *   <li>{@code task} 块的增强前缀里写了 {@code 关联故事：US01,US03,US04},因此
 *       「US03 → 挂它的任务」这个方向<b>关键词路本来就能做到</b>,图路在此只是
 *       把结果按结构关系补齐(不依赖 BM25 排序恰好把任务排进 top-k),属于锦上添花;</li>
 *   <li>{@code story} 块的内容里<b>完全不提任何任务编号</b> —— 故事不知道哪些任务在实现它。
 *       所以「T03 → 它实现的故事 US01/US02」这个方向,关键词路与向量路<b>都做不到</b>:
 *       搜 "T03" 永远搜不到 US01。这是图路真正独有的召回。</li>
 * </ul>
 * 换句话说,这一路的价值集中在<b>反向展开</b>(任务 → 故事),而不是正向。把它写成
 * "两个方向都能补关键词路的漏"会是一个经不起 git log 与实测追问的说法。
 *
 * <p>种子怎么来:查询词里出现 {@code US\d+}/{@code T\d+} 这类实体编号时直接用编号定位
 * (精确,且不花一次全文检索);没有编号时退回一次只在 story/task 内做的关键词命中。
 * 有编号就走编号,是因为"用户已经明确说了是哪条"的情况下再去做模糊匹配只会引入噪声。
 *
 * <p>关于 {@code score}:RRF 只用排名,这条路上的分数值<b>不影响融合结果</b>,
 * 只影响本路内部的先后。定值(种子 1.0 / 邻居 0.5)足以表达"种子优先于邻居",
 * 而记进日志时比一串伪造的相似度更诚实。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class GraphRetriever implements Retriever {

    /** 实体编号:US01(故事)/ T04(任务)。边界符防止在 SetLamp 这类词里误匹配 */
    private static final Pattern STORY_ID = Pattern.compile("\\bUS\\d+\\b", Pattern.CASE_INSENSITIVE);
    private static final Pattern TASK_ID = Pattern.compile("\\bT\\d+\\b", Pattern.CASE_INSENSITIVE);

    private static final double SEED_SCORE = 1.0;
    private static final double NEIGHBOR_SCORE = 0.5;

    /** 无编号时,先做一次关键词命中拿种子的条数 */
    private static final int FALLBACK_SEED_LIMIT = 3;

    /** 图路的种子只来自"有结构关系的实体":故事与任务 */
    private static final String SEED_SOURCE_TYPES = Chunk.SRC_STORY + "," + Chunk.SRC_TASK;

    private final KnowledgeChunkMapper chunkMapper;
    private final RetrievalGraphMapper graphMapper;

    @Override
    public String name() {
        return Hit.STAGE_GRAPH;
    }

    @Override
    public List<Hit> retrieve(String query, int topK, RetrievalContext ctx) {
        if (query == null || query.isBlank() || topK <= 0) {
            return List.of();
        }
        try {
            List<Hit> seeds = seedsOf(query, ctx);
            if (seeds.isEmpty()) {
                return List.of();
            }
            List<Hit> neighbors = neighborsOf(seeds, ctx);

            // 种子在前、邻居在后,按 id 去重 —— 顺序即本路的排名,RRF 只读排名
            Map<String, Hit> merged = new LinkedHashMap<>();
            for (Hit h : seeds) {
                merged.putIfAbsent(h.id(), h);
            }
            for (Hit h : neighbors) {
                merged.putIfAbsent(h.id(), h);
            }
            List<Hit> out = new ArrayList<>(merged.values());
            return out.size() > topK ? List.copyOf(out.subList(0, topK)) : List.copyOf(out);
        } catch (RuntimeException e) {
            log.warn("图路召回失败,本次检索降级为向量路 + 关键词路: {}", e.getMessage());
            return List.of();
        }
    }

    // ------------------------------------------------------------------

    /** 种子:查询里写明的实体编号优先;一个都没有时才做关键词兜底 */
    private List<Hit> seedsOf(String query, RetrievalContext ctx) {
        Set<String> storyIds = match(query, STORY_ID);
        Set<String> taskIds = match(query, TASK_ID);
        if (storyIds.isEmpty() && taskIds.isEmpty()) {
            return keywordSeeds(query, ctx);
        }
        return fetch(storyIds, taskIds, ctx);
    }

    /** 按实体编号取块:故事与任务分别查,合并后是「故事在前、任务在后」的稳定顺序 */
    private List<Hit> fetch(Set<String> storyIds, Set<String> taskIds, RetrievalContext ctx) {
        List<Hit> hits = new ArrayList<>();
        if (!storyIds.isEmpty()) {
            hits.addAll(toHits(chunkMapper.selectBySourceRefs(Chunk.SRC_STORY, csv(storyIds), ctx.roleRank()),
                    Hit.STAGE_GRAPH, SEED_SCORE));
        }
        if (!taskIds.isEmpty()) {
            hits.addAll(toHits(chunkMapper.selectBySourceRefs(Chunk.SRC_TASK, csv(taskIds), ctx.roleRank()),
                    Hit.STAGE_GRAPH, SEED_SCORE));
        }
        return hits;
    }

    /**
     * id 集合 → 逗号串,供 SQL 里的 {@code FIND_IN_SET} 使用。
     *
     * <p>{@link LinkedHashSet} 的迭代顺序是插入顺序(实体编号按在查询里出现的先后),
     * 因此拼出来的串是稳定的 —— 否则 SQL 的返回顺序会在两次相同的检索之间抖动。
     */
    private static String csv(Set<String> ids) {
        return String.join(",", ids);
    }

    /**
     * 关键词兜底:只在 story/task 里找种子,再走同样的展开。
     *
     * <p>复用关键词路那条语句,把 {@code sourceTypesCsv} 传成 {@link #SEED_SOURCE_TYPES}:
     * 源类型过滤写在 SQL 的 WHERE 里(不是捞回来再筛),LIMIT 作用在过滤之后,
     * 结果集与一条独立语句完全一致。
     *
     * <p>这里曾经有一条独立的 {@code searchGraphSeeds},javadoc 给的理由是"复用会把 doc
     * 类的大段文档排到前面、LIMIT 被无关源吃掉"。那个理由是错的(它描述的是后置过滤),
     * 而代价是把 ACL 判定又抄了一份 —— ACL 是本项目红线③,多一份就多一处将来可能改漏的地方。
     */
    private List<Hit> keywordSeeds(String query, RetrievalContext ctx) {
        List<ChunkHitRow> rows = chunkMapper.searchByKeyword(
                query, ctx.roleRank(), SEED_SOURCE_TYPES, FALLBACK_SEED_LIMIT);
        return toHits(rows, Hit.STAGE_GRAPH, SEED_SCORE);
    }

    /**
     * 一跳展开:故事种子 → 挂它的任务;任务种子 → 它引用的故事。
     *
     * <p>只展开一跳。两跳会迅速把整个项目图拉进来(故事→任务→其它故事→…),
     * 结果集里全是"和原问题只有间接关系"的块,反而把真正的命中挤出 top-k。
     */
    private List<Hit> neighborsOf(List<Hit> seeds, RetrievalContext ctx) {
        Set<String> stories = new LinkedHashSet<>();
        Set<String> tasks = new LinkedHashSet<>();
        for (Hit h : seeds) {
            if (Chunk.SRC_STORY.equals(h.sourceType())) {
                stories.add(h.sourceId());
            } else if (Chunk.SRC_TASK.equals(h.sourceType())) {
                tasks.add(h.sourceId());
            }
        }

        Set<String> neighborTaskIds = new LinkedHashSet<>();
        for (String storyId : stories) {
            neighborTaskIds.addAll(graphMapper.findTaskIdsOfStory(storyId));
        }
        Set<String> neighborStoryIds = new LinkedHashSet<>();
        for (String taskId : tasks) {
            RetrievalGraphMapper.TaskEdges edges = graphMapper.findStoryRefsOfTask(taskId);
            neighborStoryIds.addAll(splitRefs(edges));
        }
        // 种子自身不算邻居,否则会出现"自己展开到自己"
        neighborTaskIds.removeAll(tasks);
        neighborStoryIds.removeAll(stories);

        List<Hit> out = new ArrayList<>();
        if (!neighborStoryIds.isEmpty()) {
            out.addAll(toHits(chunkMapper.selectBySourceRefs(Chunk.SRC_STORY, csv(neighborStoryIds), ctx.roleRank()),
                    Hit.STAGE_GRAPH, NEIGHBOR_SCORE));
        }
        if (!neighborTaskIds.isEmpty()) {
            out.addAll(toHits(chunkMapper.selectBySourceRefs(Chunk.SRC_TASK, csv(neighborTaskIds), ctx.roleRank()),
                    Hit.STAGE_GRAPH, NEIGHBOR_SCORE));
        }
        return out;
    }

    /** {@code story_ref} 是逗号列表,{@code kanban_card_id} 是单值 —— 合并成去重集合 */
    private static Set<String> splitRefs(RetrievalGraphMapper.TaskEdges edges) {
        Set<String> ids = new LinkedHashSet<>();
        if (edges == null) {
            return ids;
        }
        if (edges.storyRef() != null && !edges.storyRef().isBlank()) {
            for (String part : edges.storyRef().split(",")) {
                String id = part.trim();
                if (!id.isEmpty()) {
                    ids.add(id);
                }
            }
        }
        if (edges.kanbanCardId() != null && !edges.kanbanCardId().isBlank()) {
            ids.add(edges.kanbanCardId().trim());
        }
        return ids;
    }

    private static List<Hit> toHits(List<ChunkHitRow> rows, String stage, double score) {
        List<Hit> hits = new ArrayList<>(rows.size());
        for (ChunkHitRow r : rows) {
            hits.add(new Hit(r.getChunkId(), r.getSourceType(), r.getSourceId(),
                    r.getRawContent(), score, stage, null));
        }
        return hits;
    }

    private static Set<String> match(String query, Pattern p) {
        Set<String> ids = new LinkedHashSet<>();
        Matcher m = p.matcher(query);
        while (m.find()) {
            ids.add(m.group().toUpperCase());
        }
        return ids;
    }
}
