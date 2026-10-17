package com.aicap.rag;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * RRF 融合的纯逻辑测试(无 Spring、无 DB)。
 *
 * <p>为什么值得单独测:{@link RrfFuser} 的公式一旦写错(例如把 {@code rank} 当成从 1 起、
 * 或忘了 {@code +1}),产出的排序<b>仍然是"看起来合理"的</b> —— 结果不会报错,
 * 只是悄悄变差,靠端到端测试几乎发现不了。唯一能钉住它的是对分数本身的断言。
 */
class RrfFuserTest {

    private static Hit hit(String id) {
        return new Hit(id, "story", id, "内容 " + id, 0.0, Hit.STAGE_VECTOR, null);
    }

    private static List<Hit> list(String... ids) {
        return java.util.Arrays.stream(ids).map(RrfFuserTest::hit).toList();
    }

    private static double expected(int rank, int k) {
        return 1.0 / (k + rank + 1);
    }

    @Test
    void singleListScoreIsReciprocalOfRankPlusKPlusOne() {
        List<Hit> fused = new RrfFuser(60).fuse(List.of(list("A", "B", "C")), 10);

        assertEquals(List.of("A", "B", "C"), fused.stream().map(Hit::id).toList(), "单路应保持原序");
        assertEquals(expected(0, 60), fused.get(0).score(), 1e-12);
        assertEquals(expected(1, 60), fused.get(1).score(), 1e-12);
        assertEquals(expected(2, 60), fused.get(2).score(), 1e-12);
    }

    /**
     * 融合的核心主张:<b>多路都排前</b>胜过<b>单路排第一</b>。
     *
     * <p>这正是"不能直接加权求和"之外 RRF 值得存在的理由 —— 三路独立实现都认可的结果,
     * 比某一路极端自信的结果更可信。K 越大这个效应越强。
     */
    @Test
    void multiRouteConsensusBeatsSingleRouteTopRank() {
        List<Hit> fused = new RrfFuser(60).fuse(List.of(
                list("A", "B"),
                list("B", "A"),
                list("B", "C")), 10);

        assertEquals("B", fused.get(0).id(), "B 在三路都靠前,应排第一");
        assertEquals(expected(0, 60) * 2 + expected(1, 60), fused.get(0).score(), 1e-12,
                "同一 id 在多路出现时分数应累加");
        // A 只在两路出现(且其中一路是第 1),分数必须低于三路都出现的 B
        assertTrue(fused.stream().filter(h -> h.id().equals("A")).findFirst().orElseThrow().score()
                        < fused.get(0).score(),
                "两路出现的 A 不应超过三路出现的 B");
    }

    @Test
    void sameIdAcrossRoutesIsDeduplicatedNotDuplicated() {
        List<Hit> fused = new RrfFuser(60).fuse(List.of(list("A", "B"), list("A"), list("A")), 10);

        assertEquals(2, fused.size(), "A 出现三次也只应占一条");
        assertEquals("A", fused.get(0).id());
    }

    @Test
    void truncatesToTopK() {
        List<Hit> fused = new RrfFuser(60).fuse(List.of(list("A", "B", "C", "D", "E")), 2);
        assertEquals(List.of("A", "B"), fused.stream().map(Hit::id).toList());
    }

    /** 同分时必须按 id 兜底排序,否则 HashMap 迭代顺序会让同一查询两次跑出不同结果 */
    @Test
    void tiesAreBrokenDeterministicallyById() {
        List<Hit> first = new RrfFuser(60).fuse(List.of(list("B"), list("A")), 10);
        List<Hit> second = new RrfFuser(60).fuse(List.of(list("A"), list("B")), 10);

        assertEquals(first.stream().map(Hit::id).toList(), second.stream().map(Hit::id).toList(),
                "同分结果不应随输入顺序变化");
        assertEquals(List.of("A", "B"), first.stream().map(Hit::id).toList(), "同分按 id 升序");
    }

    /** K=0 时若直接代入会让分母退化为 rank+1,极端情况下放大到失真;夹到 1 后仍要除以安全的数 */
    @Test
    void zeroKIsClampedInsteadOfDividingByZero() {
        List<Hit> fused = new RrfFuser(0).fuse(List.of(list("A", "B")), 10);
        // K 被夹到 1:A = 1/(1+0+1) = 0.5,B = 1/(1+1+1) = 1/3
        assertEquals(0.5, fused.get(0).score(), 1e-12);
        assertEquals(1.0 / 3.0, fused.get(1).score(), 1e-12);
    }

    @Test
    void emptyInputYieldsEmptyOutput() {
        assertTrue(new RrfFuser(60).fuse(List.of(), 10).isEmpty());
        assertTrue(new RrfFuser(60).fuse(List.of(List.of(), List.of()), 10).isEmpty());
    }

    /** 融合结果的 stage 必须被改写成 rrf:日志里靠它区分"这是哪一阶段的分数" */
    @Test
    void fusedHitsCarryRrfStage() {
        List<Hit> fused = new RrfFuser(60).fuse(List.of(list("A")), 10);
        assertEquals(Hit.STAGE_RRF, fused.get(0).stage());
    }
}
