package com.aicap.arch;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 结构性守卫:老会议 Agent 包({@code com.aicap.agent})必须自给自足。
 *
 * <p>"删除 {@code agent/} 目录 = 删掉老会议 Agent,不牵动任何现行功能" —— 这句话原本只是约定,
 * 靠人记着。本测试把它变成机器每次都会验的事实:<b>除了 {@code agent/} 自己的文件,
 * 任何主代码都不得 {@code import com.aicap.agent.*}</b>。
 *
 * <p>判据是<b>编译期</b>的:运行期的 Bean / 端点 / 后台线程依赖不在扫描范围里 —— 那部分由
 * 各域自注册的 {@link com.aicap.worker.WorkerTask} 承担({@code AgentWorker} 已不认识任何具体域)。
 *
 * <p>这个模式不是新发明的:编排层的 {@code agent-orchestrator/tests/test_no_db.py} 就是这么
 * 钉住"本层不得持有 DB 连接"的。
 */
class AgentPackageIsSelfContainedTest {

    /** 可删除包的目录名(在 source root 下的相对路径片段)。 */
    private static final String DELETABLE_PACKAGE = "com/aicap/agent/";

    private static final String FORBIDDEN_IMPORT = "import com.aicap.agent.";

    @Test
    void noMainCodeOutsideTheAgentPackageImportsIt() throws IOException {
        Path sourceRoot = Path.of("src", "main", "java");
        assertTrue(Files.isDirectory(sourceRoot),
                "找不到 src/main/java,测试的工作目录可能不是 java-backend");

        List<String> offenders = new ArrayList<>();
        try (Stream<Path> files = Files.walk(sourceRoot)) {
            for (Path file : files.filter(p -> p.toString().endsWith(".java")).toList()) {
                // 只排除 agent/ 自己的文件;其余任何文件出现该 import 都算越界。
                String normalized = file.toString().replace('\\', '/');
                if (normalized.contains("/" + DELETABLE_PACKAGE)) {
                    continue;
                }
                for (String line : Files.readAllLines(file, StandardCharsets.UTF_8)) {
                    if (line.strip().startsWith(FORBIDDEN_IMPORT)) {
                        offenders.add(normalized + " -> " + line.strip());
                    }
                }
            }
        }

        assertTrue(offenders.isEmpty(),
                "老会议 Agent 包不再是可删除的了 —— 以下文件从外部依赖它,"
                        + "删掉 agent/ 时它们会编译不过。请把被依赖的类搬到它真正所属的域包"
                        + "(配置/传输 → com.aicap.llm,域错误 → com.aicap.common,"
                        + "平台工具 → com.aicap.platform,切分 → com.aicap.rag),"
                        + "或让该域自注册一个 WorkerTask:\n  " + String.join("\n  ", offenders));
    }
}
