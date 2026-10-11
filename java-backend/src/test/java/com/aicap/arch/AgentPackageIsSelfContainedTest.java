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

    /** 不限行首的全限定名引用:不 import 也能用 com.aicap.agent.Xxx */
    private static final String FORBIDDEN_QUALIFIER = "com.aicap.agent.";

    @Test
    void noMainCodeOutsideTheAgentPackageImportsIt() throws IOException {
        List<String> offenders = scanOutsideAgentPackage(line -> line.strip().startsWith(FORBIDDEN_IMPORT));

        assertTrue(offenders.isEmpty(),
                "老会议 Agent 包不再是可删除的了 —— 以下文件从外部依赖它,"
                        + "删掉 agent/ 时它们会编译不过。请把被依赖的类搬到它真正所属的域包"
                        + "(配置/传输 → com.aicap.llm,域错误 → com.aicap.common,"
                        + "平台工具 → com.aicap.platform,切分 → com.aicap.rag),"
                        + "或让该域自注册一个 WorkerTask:\n  " + String.join("\n  ", offenders));
    }

    /**
     * 同一条保证的第二个缺口:{@code import} 不是唯一的依赖写法。
     *
     * <p>不写 import、直接在代码里用 {@code com.aicap.agent.Xxx} 全限定名,同样是编译期依赖,
     * 删 {@code agent/} 一样会挂 —— 而只查行首 {@code import} 的写法看不到它。
     *
     * <p>判定前必须<b>先剥注释</b>:{@code llm/LlmProperties} 的 javadoc 里就写着
     * "原先住在 com.aicap.agent.AgentProperties",那是史实说明,不是依赖。
     */
    @Test
    void noMainCodeOutsideTheAgentPackageUsesItsFullyQualifiedName() throws IOException {
        List<String> offenders = scanOutsideAgentPackage(
                line -> line.contains(FORBIDDEN_QUALIFIER), true);

        assertTrue(offenders.isEmpty(),
                "以下文件在代码里用了 com.aicap.agent.* 全限定名(不是 import,但同样是编译期依赖),"
                        + "删掉 agent/ 时会编译不过:\n  " + String.join("\n  ", offenders));
    }

    /** 去掉块注释与行注释:注释里提该包名是史实说明,不构成依赖 */
    private static String stripComments(String src) {
        return src.replaceAll("(?s)/\\*.*?\\*/", " ").replaceAll("(?m)//.*$", " ");
    }

    private List<String> scanOutsideAgentPackage(java.util.function.Predicate<String> hit) throws IOException {
        return scanOutsideAgentPackage(hit, false);
    }

    /**
     * @param stripCommentsFirst 是否先剥注释。逐行判定时无法可靠剥块注释,
     *                           故该模式改为整文件剥注释后按行返回命中处
     */
    private List<String> scanOutsideAgentPackage(java.util.function.Predicate<String> hit,
                                                 boolean stripCommentsFirst) throws IOException {
        Path sourceRoot = Path.of("src", "main", "java");
        assertTrue(Files.isDirectory(sourceRoot),
                "找不到 src/main/java,测试的工作目录可能不是 java-backend");

        List<String> offenders = new ArrayList<>();
        try (Stream<Path> files = Files.walk(sourceRoot)) {
            for (Path file : files.filter(p -> p.toString().endsWith(".java")).toList()) {
                // 只排除 agent/ 自己的文件;其余任何文件命中都算越界。
                String normalized = file.toString().replace('\\', '/');
                if (normalized.contains("/" + DELETABLE_PACKAGE)) {
                    continue;
                }
                List<String> lines = Files.readAllLines(file, StandardCharsets.UTF_8);
                if (stripCommentsFirst) {
                    lines = List.of(stripComments(String.join("\n", lines)).split("\n", -1));
                }
                for (String line : lines) {
                    if (hit.test(line)) {
                        offenders.add(normalized + " -> " + line.strip());
                    }
                }
            }
        }
        return offenders;
    }
}
