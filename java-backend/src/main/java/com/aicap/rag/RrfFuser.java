package com.aicap.rag;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * RRF(Reciprocal Rank Fusion)融合(设计文档 A5)。
 *
 * <p><b>为什么是 RRF 而不是三路分数加权求和</b> —— 这是本模块最常被追问的一点:
 * 三路的分数量纲根本不可比。向量路是余弦相似度 ∈ [-1,1],关键词路是 MySQL 的
 * BM25 相关度(无上界,取值随语料与查询长度漂移),图路是"跳数决定的定值"。
 * 把它们归一化到 [0,1] 再加权,需要先假定每路分数的分布 —— 而这个假定没有任何依据,
 * 归一化后的权重纯粹是在拟合当前测试集,换一批数据就失效。
 *
 * <p>RRF 只用<b>排名</b>:{@code score = Σ 1/(K + rank_i)}。排名是序数量,天然免疫量纲。
 * 代价是丢掉了"第一名领先第二名多少"的信息 —— 但在没有可靠分数可比的前提下,
 * 那份信息本来就是噪声。
 *
 * <p>常数 K(默认 60)的作用是<b>压平头部</b>:K 越小,第 1 名与第 2 名的差距越大。
 * K=60 源于原论文的经验值,它让"多路都排第 5"能胜过"单路排第 1"——
 * 这正是融合想要的:多路一致认可比单路极端自信更可信。
 */
public final class RrfFuser {

    private final int k;

    public RrfFuser(int k) {
        // K 必须为正:0 会让 rank=0 的项除以 0,表现为"融合直接抛异常"而不是"结果不准"
        this.k = Math.max(1, k);
    }

    /**
     * 融合多路排名,返回按 RRF 分数降序的前 {@code topK} 条。
     *
     * <p>同一个 chunk 在多路出现时分数<b>累加</b> —— 这是 RRF 的核心:
     * 三路都认同的结果会被推上去。{@code byId} 用 {@code putIfAbsent} 保留首次出现的
     * 那份 Hit(它带着某一路的原文与元数据),后续出现只贡献分数。
     */
    public List<Hit> fuse(List<List<Hit>> rankedLists, int topK) {
        Map<String, Double> score = new HashMap<>();
        Map<String, Hit> byId = new HashMap<>();
        for (List<Hit> list : rankedLists) {
            if (list == null) {
                continue;
            }
            for (int rank = 0; rank < list.size(); rank++) {
                Hit h = list.get(rank);
                score.merge(h.id(), 1.0 / (k + rank + 1), Double::sum);
                byId.putIfAbsent(h.id(), h);
            }
        }
        List<Map.Entry<String, Double>> ranked = new ArrayList<>(score.entrySet());
        // 同分时按 id 兜底排序:HashMap 的迭代顺序不保证稳定,不兜底的话
        // "同一个查询两次跑出不同顺序"会一直出现,调试时分不清是融合不稳还是数据变了
        ranked.sort(Map.Entry.<String, Double>comparingByValue().reversed()
                .thenComparing(Map.Entry.comparingByKey()));
        List<Hit> out = new ArrayList<>();
        for (Map.Entry<String, Double> e : ranked) {
            if (out.size() >= topK) {
                break;
            }
            out.add(byId.get(e.getKey()).withScore(e.getValue(), Hit.STAGE_RRF));
        }
        return out;
    }
}
