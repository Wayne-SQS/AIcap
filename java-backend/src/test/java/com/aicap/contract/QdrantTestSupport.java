package com.aicap.contract;

import com.aicap.rag.RagProperties;

import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;

/**
 * 跑 {@code vector-store=qdrant} 那一遍契约测试前的清理工具。
 *
 * <p>与 MySQL 侧由 {@code reset_test_data.sql} 清库是同一件事,但 Qdrant 的点不归那个脚本管 ——
 * 上一轮留下的点会一直躺在集合里。这比"脏数据"严重:<b>陈旧点会被当成有效结果返回</b>。
 * {@link com.aicap.rag.QdrantVectorStore#search} 的命中直接由 payload 拼出,不回查 MySQL,
 * 因此早已不存在的 chunk 照样能被检索到,断言就会看到本该不存在的块。
 *
 * <p>两条硬约束:
 * <ul>
 *   <li><b>只许动 {@code _test} 结尾的集合。</b>删错集合等于删掉开发库的向量,而这一步不可逆 ——
 *       加这道闸是因为代价与"手改一行配置"完全不对称。</li>
 *   <li>用 JDK {@code HttpClient} 直连 REST,不引 Qdrant 客户端:与主代码
 *       {@link com.aicap.rag.QdrantVectorStore} 同一套做法,测试不该为省几行引入新依赖。</li>
 * </ul>
 */
final class QdrantTestSupport {

    private QdrantTestSupport() {
    }

    /**
     * 删除测试集合。集合本来就不存在也视为成功 ——
     * 紧随其后的 {@code ensureCollection} 是幂等的,会按当前维度重建。
     */
    static void resetCollection(RagProperties props) {
        String collection = props.getQdrant().getCollection();
        if (collection == null || !collection.endsWith("_test")) {
            throw new IllegalStateException(
                    "拒绝删除非测试集合 '" + collection + "':契约测试只允许动 *_test 集合,"
                            + "请检查 aicap.rag.qdrant.collection 是否配错");
        }
        String base = props.getQdrant().getBaseUrl().replaceAll("/+$", "");
        String url = base + "/collections/" + URLEncoder.encode(collection, StandardCharsets.UTF_8);
        HttpRequest request = HttpRequest.newBuilder(URI.create(url))
                .timeout(Duration.ofSeconds(props.getQdrant().getTimeoutSeconds()))
                .DELETE()
                .build();
        try {
            HttpResponse<String> response = HttpClient.newHttpClient()
                    .send(request, HttpResponse.BodyHandlers.ofString());
            // 404 与 200 等价:集合不存在时删无可删,而清理的目的是"保证是空的",不是"保证删掉过"
            if (response.statusCode() != 200 && response.statusCode() != 404) {
                throw new IllegalStateException("清空 Qdrant 测试集合失败,HTTP "
                        + response.statusCode() + ": " + response.body());
            }
        } catch (IOException e) {
            throw new IllegalStateException(
                    "无法连接 Qdrant(" + base + "):跑 vector-store=qdrant 这一遍需要容器已启动"
                            + "(cd backend && docker compose up -d qdrant)", e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("清空 Qdrant 测试集合被中断");
        }
    }
}
