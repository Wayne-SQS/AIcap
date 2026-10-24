package com.aicap.service;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * US22 代码质量评分:方向性与可解释性验证。
 * 关键断言 —— 同一套规则下，「规范样本」必须显著高于「故意写烂的对照项目」。
 */
class RepoQualityScorerTest {
    private final RepoQualityScorer scorer = new RepoQualityScorer();

    private static final String GOOD_SERVICE = """
            package demo;

            import java.util.List;

            /** 订单查询服务。所有金额以分为单位，避免浮点误差。 */
            public class OrderService {
                private final OrderRepository orderRepository;

                public OrderService(OrderRepository orderRepository) {
                    this.orderRepository = orderRepository;
                }

                /** 按订单号查询；未找到返回 null，由上层决定提示文案。 */
                public OrderView findByCode(String orderCode) {
                    if (orderCode == null || orderCode.isBlank()) {
                        return null;
                    }
                    return orderRepository.findByCode(orderCode);
                }

                /** 统计指定状态的订单数量，仅用于运营看板。 */
                public int countByStatus(String status) {
                    if (status == null) {
                        return 0;
                    }
                    return orderRepository.countByStatus(status);
                }
            }
            """;

    private static final String GOOD_REPOSITORY = """
            package demo;

            import java.util.ArrayList;
            import java.util.List;

            /** 订单仓储的内存实现，生产环境替换为 JDBC 实现。 */
            public class OrderRepository {
                private final List<OrderView> store = new ArrayList<>();

                /** 按编号查找，命中第一条即返回。 */
                public OrderView findByCode(String orderCode) {
                    for (OrderView view : store) {
                        if (view.code().equals(orderCode)) {
                            return view;
                        }
                    }
                    return null;
                }

                /** 按状态计数。 */
                public int countByStatus(String status) {
                    int matched = 0;
                    for (OrderView view : store) {
                        if (status.equals(view.status())) {
                            matched++;
                        }
                    }
                    return matched;
                }
            }
            """;

    private static final String GOOD_VIEW = """
            package demo;

            /** 订单只读视图，字段与前端契约一致。 */
            public record OrderView(String code, String status, long amountInCents) {
            }
            """;

    private static final String GOOD_TEST = """
            package demo;

            import org.junit.jupiter.api.Test;

            import static org.junit.jupiter.api.Assertions.assertEquals;
            import static org.junit.jupiter.api.Assertions.assertNull;

            /** OrderService 的单元测试，覆盖命中与边界。 */
            class OrderServiceTest {
                private final OrderRepository repository = new OrderRepository();
                private final OrderService service = new OrderService(repository);

                @Test
                void returnsNullWhenCodeIsBlank() {
                    assertNull(service.findByCode(" "));
                }

                @Test
                void countsZeroWhenStatusUnknown() {
                    assertEquals(0, service.countByStatus("UNKNOWN"));
                }
            }
            """;

    @Test
    void wellStructuredProjectScoresHigh() {
        List<RepoQualityScorer.SourceFile> files = List.of(
                new RepoQualityScorer.SourceFile("README.md", "# demo\n订单服务示例，含单元测试。"),
                new RepoQualityScorer.SourceFile("src/main/java/demo/OrderService.java", GOOD_SERVICE),
                new RepoQualityScorer.SourceFile("src/main/java/demo/OrderRepository.java", GOOD_REPOSITORY),
                new RepoQualityScorer.SourceFile("src/main/java/demo/OrderView.java", GOOD_VIEW),
                new RepoQualityScorer.SourceFile("src/test/java/demo/OrderServiceTest.java", GOOD_TEST));
        Map<String, Object> r = scorer.score(files);
        assertEquals(true, r.get("sufficient"), "3 个以上源文件应给出总分");
        int total = (int) r.get("total");
        assertTrue(total >= 70, "规范样本应达到「良」以上，实际 " + total + " -> " + r.get("dimensions"));
        assertEquals(3, r.get("source_files"));
        assertEquals(1, r.get("test_files"));
    }

    @Test
    void deliberatelyBadProjectScoresLowWithEvidence() throws Exception {
        Path root = Path.of(System.getProperty("user.dir")).resolve("../qa/fixtures/bad-quality-project").normalize();
        assertTrue(Files.isDirectory(root), "对照项目目录不存在: " + root);
        List<RepoQualityScorer.SourceFile> files = new ArrayList<>();
        try (var walk = Files.walk(root)) {
            for (Path p : walk.filter(Files::isRegularFile).toList()) {
                if (!p.toString().endsWith(".java")) continue;
                files.add(new RepoQualityScorer.SourceFile(root.relativize(p).toString().replace('\\', '/'),
                        Files.readString(p)));
            }
        }
        Map<String, Object> r = scorer.score(files);
        int total = (int) r.get("total");
        assertTrue(total <= 45, "故意写烂的项目应「差」，实际 " + total);
        assertEquals(0, r.get("test_files"), "对照项目没有任何测试");
        assertFalse(((List<?>) r.get("top_issues")).isEmpty(), "扣分项不能为空");

        // 每个扣分项必须带证据，否则只是黑箱打分
        for (Object o : (List<?>) r.get("top_issues")) {
            Map<?, ?> issue = (Map<?, ?>) o;
            assertNotNull(issue.get("dimension"));
            assertTrue(((Number) issue.get("lost_points")).doubleValue() > 0);
            assertFalse(((List<?>) issue.get("evidence")).isEmpty(),
                    issue.get("dimension") + " 扣分但没给证据");
        }
        // 安全维度必须命中硬编码凭据
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> dims = (List<Map<String, Object>>) r.get("dimensions");
        Map<String, Object> security = dims.stream().filter(d -> "security".equals(d.get("key"))).findFirst().orElseThrow();
        assertTrue(((Number) security.get("score")).intValue() < 100, "应命中硬编码凭据等危险模式");
    }

    @Test
    void gapBetweenGoodAndBadIsLarge() throws Exception {
        Path root = Path.of(System.getProperty("user.dir")).resolve("../qa/fixtures/bad-quality-project").normalize();
        List<RepoQualityScorer.SourceFile> bad = new ArrayList<>();
        try (var walk = Files.walk(root)) {
            for (Path p : walk.filter(Files::isRegularFile).toList()) {
                if (!p.toString().endsWith(".java")) continue;
                bad.add(new RepoQualityScorer.SourceFile(root.relativize(p).toString().replace('\\', '/'), Files.readString(p)));
            }
        }
        int badTotal = (int) scorer.score(bad).get("total");
        int goodTotal = (int) scorer.score(List.of(
                new RepoQualityScorer.SourceFile("README.md", "# demo"),
                new RepoQualityScorer.SourceFile("src/main/java/demo/OrderService.java", GOOD_SERVICE),
                new RepoQualityScorer.SourceFile("src/main/java/demo/OrderRepository.java", GOOD_REPOSITORY),
                new RepoQualityScorer.SourceFile("src/main/java/demo/OrderView.java", GOOD_VIEW),
                new RepoQualityScorer.SourceFile("src/test/java/demo/OrderServiceTest.java", GOOD_TEST))).get("total");
        assertTrue(goodTotal - badTotal >= 30,
                "好/烂项目分差应 ≥30 才有区分度：好 " + goodTotal + " vs 烂 " + badTotal);
    }

    @Test
    void refusesToScoreWhenTooFewFiles() {
        Map<String, Object> r = scorer.score(List.of(
                new RepoQualityScorer.SourceFile("src/main/java/demo/Only.java", "class Only {}")));
        assertEquals(false, r.get("sufficient"));
        assertNull(r.get("total"), "数据不足时不能硬给分");
        assertNotNull(r.get("reason"));
    }
}
