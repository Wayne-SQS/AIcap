package com.aicap.service;

import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * US22 代码质量评分器:对一批源文件做**可计算、可解释、可复现**的静态评分。
 *
 * <p>设计原则(相对"只陈述事实"的上一版):
 * <ul>
 *   <li>每个维度都给出实际值、阈值、权重与<b>扣分证据</b>(文件 + 行号 + 脱敏片段),不只给结论;</li>
 *   <li>不调用 LLM、不做语义猜测 —— 同一批文件任何时候算出同一个分,可被第三方复算;</li>
 *   <li>语言规则显式声明,对 Java / JS / TS / Python 分别应用,未识别扩展名不计入分母;</li>
 *   <li>数据不足时明确降级(例如源文件少于 3 个 → sufficient=false),不硬凑分数。</li>
 * </ul>
 *
 * <p>已知边界:这是<b>静态启发式</b>评分,不替代人工 Code Review;注释密度、命名等指标
 * 存在语言与风格偏好,因此每项都给证据,便于人工复核。
 */
@Service
public class RepoQualityScorer {

    /** 一个待评分的源文件。path 用于识别测试文件与输出证据,content 为完整文本。 */
    public record SourceFile(String path, String content) {}

    // 10 维；合计 100。「密度」看有没有，「内容质量」看写得怎么样，两者分开避免用数量掩盖质量
    private static final int WEIGHT_TEST = 20;            // 测试密度（有没有）
    private static final int WEIGHT_TEST_QUALITY = 10;    // 测试内容质量（断言/空测试/异常路径/flaky）
    private static final int WEIGHT_DOC = 10;             // 文档完备度（README 有无 + 注释密度）
    private static final int WEIGHT_DOC_QUALITY = 8;      // 文档内容质量（必需章节/结构）
    private static final int WEIGHT_NAMING = 12;
    private static final int WEIGHT_COMPLEXITY = 13;
    private static final int WEIGHT_FUNCTION_LEN = 9;
    private static final int WEIGHT_DUPLICATION = 8;
    private static final int WEIGHT_ERROR_HANDLING = 5;
    private static final int WEIGHT_SECURITY = 5;

    /** 源文件扩展名 → 语言 */
    private static final Map<String, String> EXT_LANG = Map.of(
            "java", "java", "js", "js", "jsx", "js", "ts", "ts", "tsx", "ts", "mjs", "js", "py", "py");

    /** 测试方法：JUnit / pytest / jest 三类写法 */
    private static final Pattern TEST_METHOD = Pattern.compile(
            "@Test\\b|\\bdef\\s+test_|\\bit\\s*\\(|\\btest\\s*\\(");
    /** 断言调用：JUnit 断言 + Hamcrest + MockMvc 的 andExpect(status().isOk()) + 前端 expect */
    private static final Pattern ASSERT_CALL = Pattern.compile(
            "\\bassert[A-Z]?\\w*\\s*\\(|\\bassertThat\\s*\\(|\\.should\\b|\\bexpect\\s*\\("
                    + "|\\bandExpect\\s*\\(|\\bstatus\\(\\)\\.is\\w+");
    /** 空测试体：@Test 后紧跟空块 */
    private static final Pattern EMPTY_TEST = Pattern.compile(
            "@Test\\b[^\\{]*\\{\\s*\\}");
    /** 异常/错误路径断言 */
    private static final Pattern ERROR_PATH = Pattern.compile(
            "assertThrows|expectThrows|toThrow|pytest\\.raises|assertRaises|should\\s*\\(\\s*throw");
    /** 测试里的固定等待，flaky 风险 */
    private static final Pattern SLEEP_CALL = Pattern.compile("Thread\\.sleep|time\\.sleep|setTimeout\\s*\\(");

    private static final Pattern TEST_PATH = Pattern.compile(
            "(^|/)(test|tests|__tests__|spec)/|(Test|Tests|Spec|Specs)\\.(java|js|jsx|ts|tsx)$|" +
            "[._](test|spec)\\.(java|js|jsx|ts|tsx|py)$|^test_.*\\.py$|_test\\.py$");

    private static final Pattern COMMENT_LINE = Pattern.compile("^\\s*(//|#|/\\*|\\*|<!--)");

    private static final Pattern BRANCH = Pattern.compile(
            "\\b(if|else\\s+if|for|while|case|catch|switch)\\b|&&|\\|\\||\\?\\?");

    /** 无意义标识符:单字母(排除常见循环变量)与临时名 */
    private static final Pattern SHORT_DECL_JAVA = Pattern.compile(
            "\\b(?:int|long|double|float|boolean|char|String|var|Object|List<[^>]*>|Map<[^>]*>)\\s+([a-z]{1,2})\\s*[=;,)]");
    private static final Pattern SHORT_DECL_JS = Pattern.compile(
            "\\b(?:let|const|var)\\s+([a-z]{1,2})\\s*[=;]");
    private static final Pattern SHORT_DECL_PY = Pattern.compile("^\\s*([a-z]{1,2})\\s*=\\s*[^=]");
    private static final Pattern LAZY_NAME = Pattern.compile(
            "\\b(tmp|temp|data1|data2|foo|bar|baz|test1|test2|aaa|bbb|xxx|asdf)\\b");

    private static final Pattern EMPTY_CATCH_JAVA = Pattern.compile("catch\\s*\\([^)]*\\)\\s*\\{\\s*\\}");
    private static final Pattern EMPTY_CATCH_JS = Pattern.compile("catch\\s*\\([^)]*\\)\\s*\\{\\s*\\}");
    private static final Pattern BARE_EXCEPT_PY = Pattern.compile("except[^:\\n]*:\\s*(pass\\s*)?(\\n|$)");

    /** 每个维度对应的改进动作(可执行,不是"请优化") */
    private static final Map<String, String> ACTIONS = Map.of(
            "test_density", "为关键服务与工具类补单元测试，目标「测试文件/源文件 ≥ 0.5」，优先覆盖金额、权限、状态机",
            "documentation", "补 README（启动方式 + 模块划分 + 环境变量）；公共方法与对外接口补一句话注释",
            "naming", "把单字母与临时名换成语义名（o→order、tmp→normalizedText、data1→headerRow）",
            "complexity", "用卫语句提前 return 拆掉深层嵌套；单函数分支超过 5 个就抽子方法",
            "function_length", "按职责拆分超 50 行的函数，单函数控制在 30 行内",
            "duplication", "把重复块抽成公共方法或工具类，参数化差异部分",
            "error_handling", "空 catch 至少记录日志并向上抛业务异常，禁止静默吞掉",
            "security", "凭据移到环境变量；SQL 改 PreparedStatement；移除 eval/exec；TLS 校验不得关闭");

    /** 该维度不达标会造成的业务/工程后果(回答"为什么这算问题") */
    private static final Map<String, String> IMPACTS = Map.of(
            "test_density", "缺测试时重构与回归风险不可控，缺陷只能靠人工点页面发现",
            "documentation", "新人接手成本高，接口语义只能靠读代码猜",
            "naming", "可读性差，Code Review 与线上排障效率下降",
            "complexity", "圈复杂度高，分支组合难覆盖，改动容易引入回归",
            "function_length", "长函数职责混淆，无法单测，复用性差",
            "duplication", "同一逻辑要改多处，漏改会产生数据不一致",
            "error_handling", "异常被吞会让故障静默，出问题查不到根因",
            "security", "凭据泄露、SQL 注入、远程代码执行等直接安全风险");

    private static final List<Map.Entry<Pattern, String>> SECURITY_RULES = List.of(
            Map.entry(Pattern.compile("(?i)(password|passwd|pwd|secret|api[_-]?key|token)\\s*[=:]\\s*\"[^\"]{4,}\""), "硬编码凭据"),
            Map.entry(Pattern.compile("\\beval\\s*\\("), "eval 执行"),
            Map.entry(Pattern.compile("\\bexec\\s*\\("), "exec 执行"),
            Map.entry(Pattern.compile("(?i)\"(select|insert|update|delete)\\b[^\"]*\"\\s*\\+"), "SQL 字符串拼接"),
            Map.entry(Pattern.compile("(?i)f\"[^\"]*(select|insert|update|delete)\\b[^\"]*\\{[^}]+\\}"), "SQL f-string 拼接"),
            Map.entry(Pattern.compile("chmod\\s+777"), "权限过宽"),
            Map.entry(Pattern.compile("verify\\s*=\\s*False|rejectUnauthorized\\s*:\\s*false"), "关闭 TLS 校验"));

    /** 路径是否为测试文件(fetcher 用它分池,避免两边各写一套规则) */
    public static boolean looksLikeTestPath(String path) {
        return path != null && TEST_PATH.matcher(path).find();
    }

    /** 评分入口:返回 total / grade / dimensions / top_issues / basis / limitations。 */
    public Map<String, Object> score(List<SourceFile> files) {
        List<SourceFile> sourceFiles = new ArrayList<>();
        List<SourceFile> testFiles = new ArrayList<>();
        for (SourceFile f : files) {
            if (f == null || f.path() == null || f.content() == null) continue;
            if (!EXT_LANG.containsKey(ext(f.path()))) continue;
            if (TEST_PATH.matcher(f.path()).find()) testFiles.add(f); else sourceFiles.add(f);
        }
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("scorer", "repo-quality-v1");
        if (sourceFiles.size() < 3) {
            result.put("sufficient", false);
            result.put("reason", "有效源文件不足 3 个（当前 " + sourceFiles.size() + " 个），不给出总分");
            result.put("files_scanned", files.size());
            return result;
        }

        List<Map<String, Object>> dims = new ArrayList<>();
        dims.add(dimTest(sourceFiles, testFiles));
        dims.add(dimTestQuality(testFiles));
        dims.add(dimDoc(files, sourceFiles));
        dims.add(dimDocQuality(files));
        dims.add(dimNaming(sourceFiles));
        dims.add(dimComplexity(sourceFiles));
        dims.add(dimFunctionLength(sourceFiles));
        dims.add(dimDuplication(sourceFiles));
        dims.add(dimErrorHandling(sourceFiles));
        dims.add(dimSecurity(sourceFiles));

        double weighted = 0;
        int weightSum = 0;
        for (Map<String, Object> d : dims) {
            weighted += ((Number) d.get("score")).doubleValue() * ((Number) d.get("weight")).intValue();
            weightSum += ((Number) d.get("weight")).intValue();
        }
        int total = (int) Math.round(weighted / Math.max(1, weightSum));

        List<Map<String, Object>> issues = new ArrayList<>();
        List<Map<String, Object>> findings = new ArrayList<>();
        List<String> strengths = new ArrayList<>();
        int blocking = 0, high = 0;
        for (Map<String, Object> d : dims) {
            String key = String.valueOf(d.get("key"));
            int sc = ((Number) d.get("score")).intValue();
            int weight = ((Number) d.get("weight")).intValue();
            double lost = (100 - sc) * weight / 100.0;
            @SuppressWarnings("unchecked")
            List<Map<String, Object>> ev = (List<Map<String, Object>>) d.get("evidence");

            if (sc >= 85) { strengths.add(String.valueOf(d.get("name"))); continue; }
            if (lost < 1) continue;

            Map<String, Object> issue = new LinkedHashMap<>();
            issue.put("dimension", key);
            issue.put("name", d.get("name"));
            issue.put("lost_points", Math.round(lost * 10) / 10.0);
            issue.put("detail", d.get("detail"));
            issue.put("evidence", ev.size() > 3 ? ev.subList(0, 3) : ev);
            issues.add(issue);

            String severity = severityOf(key, sc, ev);
            if ("blocking".equals(severity)) blocking++;
            else if ("high".equals(severity)) high++;
            Map<String, Object> f = new LinkedHashMap<>();
            f.put("severity", severity);
            f.put("category", key);
            f.put("title", String.valueOf(d.get("name")) + "：" + d.get("detail"));
            f.put("metric", d.get("value"));
            f.put("impact", IMPACTS.getOrDefault(key, "影响代码可维护性"));
            f.put("evidence", ev.size() > 3 ? ev.subList(0, 3) : ev);
            f.put("action", ACTIONS.getOrDefault(key, "按维度定义修正"));
            // 修好这一项能拿回多少分 = 权重 × 失分比例
            f.put("recoverable_points", Math.round(lost * 10) / 10.0);
            findings.add(f);
        }
        issues.sort(Comparator.comparingDouble(i -> -((Number) i.get("lost_points")).doubleValue()));
        findings.sort(Comparator.comparingInt((Map<String, Object> f) -> sevRank(String.valueOf(f.get("severity")))).reversed()
                .thenComparingDouble(f -> -((Number) f.get("recoverable_points")).doubleValue()));

        result.put("sufficient", true);
        // 顶层保留 total/grade，兼容按旧结构读取的调用方
        result.put("total", total);
        result.put("grade", grade(total));
        result.put("score", Map.of("total", total, "grade", grade(total),
                "benchmark", "成熟开源项目基准 80–90；课程/团队项目建议 ≥ 70",
                "gap_to_target", Math.max(0, 70 - total)));
        String risk;
        if (blocking > 0) risk = "blocking";
        else if (total >= 85) risk = "low";
        else if (total >= 70) risk = "medium";
        else risk = "high";
        result.put("risk_level", risk);
        result.put("verdict", verdict(total, blocking, high, dims));
        result.put("findings", findings);
        result.put("strengths", strengths);
        result.put("files_scanned", files.size());
        result.put("source_files", sourceFiles.size());
        result.put("test_files", testFiles.size());
        result.put("dimensions", dims);
        result.put("top_issues", issues.size() > 5 ? issues.subList(0, 5) : issues);
        result.put("basis", "静态启发式:测试密度/文档/命名/分支密度+嵌套深度/函数长度/重复行/异常处理/安全模式,全部规则可复算");
        result.put("limitations", "不读运行期行为与业务语义,不能替代 Code Review;命名与注释存在风格偏好,所有扣分均附证据供人工复核。");
        return result;
    }

    /**
     * 严重度分级。
     * 安全维度要再分一层:硬编码凭据 / eval / exec / 关闭 TLS 是明确阻断项;
     * SQL 拼接这类静态无法判断可利用性的模式降为 high,要求人工确认,避免误报把正常项目卡住。
     */
    private static String severityOf(String key, int score, List<Map<String, Object>> evidence) {
        if ("security".equals(key) && score < 100) {
            boolean critical = evidence.stream().anyMatch(e -> {
                String sn = String.valueOf(e.get("snippet"));
                return sn.contains("硬编码凭据") || sn.contains("eval 执行")
                        || sn.contains("exec 执行") || sn.contains("关闭 TLS");
            });
            return critical ? "blocking" : "high";
        }
        if ("test_density".equals(key) && score == 0) return "blocking";
        if ("error_handling".equals(key) && score < 70) return "high";
        if (score < 40) return "high";
        if (score < 70) return "medium";
        return "low";
    }

    private static int sevRank(String severity) {
        return switch (severity) {
            case "blocking" -> 3;
            case "high" -> 2;
            case "medium" -> 1;
            default -> 0;
        };
    }

    /** 一句话诊断:说清「现在什么水平 / 卡在哪 / 先做什么」 */
    private static String verdict(int total, int blocking, int high, List<Map<String, Object>> dims) {
        String worst = dims.stream()
                .min(Comparator.comparingInt(d -> ((Number) d.get("score")).intValue()))
                .map(d -> String.valueOf(d.get("name"))).orElse("—");
        StringBuilder sb = new StringBuilder();
        sb.append("整体「").append(grade(total)).append("」（").append(total).append(" 分），最短板是").append(worst).append("。");
        if (blocking > 0) {
            sb.append("存在 ").append(blocking).append(" 个发布阻断项（安全命中或零测试），必须先处理。");
        } else if (high > 0) {
            sb.append("有 ").append(high).append(" 个高优项，建议本轮修复。");
        } else if (total >= 70) {
            sb.append("无阻断项，可发布；剩余为可读性优化。");
        }
        sb.append("优先顺序：先修阻断项，再补短板维度，最后处理风格类扣分。");
        return sb.toString();
    }

    // ---------- 各维度 ----------

    private Map<String, Object> dimTest(List<SourceFile> src, List<SourceFile> test) {
        double ratio = src.isEmpty() ? 0 : (double) test.size() / src.size();
        int score = clamp((int) Math.round(Math.min(1.0, ratio / 0.5) * 100));
        List<Map<String, Object>> ev = new ArrayList<>();
        if (ratio < 0.5) {
            ev.add(ev(null, 0, "测试文件 " + test.size() + " 个 / 源文件 " + src.size() + " 个，比值 " + pct(ratio) + "（满分线 50%）"));
        }
        return dim("test_density", "测试密度", WEIGHT_TEST, score, pct(ratio) + "（测试/源）",
                ratio >= 0.5 ? "达到满分线" : "测试文件相对源文件偏少", ev);
    }

    /** 测试内容质量：断言密度、空测试、异常路径覆盖、固定等待（flaky） */
    private Map<String, Object> dimTestQuality(List<SourceFile> test) {
        List<Map<String, Object>> ev = new ArrayList<>();
        if (test.isEmpty()) {
            // 「没有测试」本身就是结论，也要给证据，不能让扣分项空着
            ev.add(ev(null, 0, "仓库内未发现任何测试文件"));
            return dim("test_quality", "测试内容质量", WEIGHT_TEST_QUALITY, 0, "无测试文件",
                    "没有测试文件，无从评估测试质量", ev);
        }
        int methods = 0, asserts = 0, empty = 0, errorPath = 0, sleeps = 0;
        boolean anyContent = false;
        for (SourceFile f : test) {
            if (f.content() == null || f.content().isBlank()) continue;
            anyContent = true;
            methods += count(f.content(), TEST_METHOD);
            asserts += count(f.content(), ASSERT_CALL);
            empty += count(f.content(), EMPTY_TEST);
            errorPath += count(f.content(), ERROR_PATH);
            sleeps += count(f.content(), SLEEP_CALL);
            Matcher m = SLEEP_CALL.matcher(f.content());
            while (m.find() && ev.size() < 2) {
                int line = f.content().substring(0, m.start()).split("\\R", -1).length;
                ev.add(ev(f.path(), line, "测试里使用固定等待：" + truncate(m.group().trim(), 40)));
            }
        }
        if (!anyContent) {
            // 只登记了路径、没拉到正文：按中性计分，不能因为没取到内容就判差
            return dim("test_quality", "测试内容质量", WEIGHT_TEST_QUALITY, 60, "未取到测试文件正文",
                    "测试文件正文未拉取，本维度按中性计分", ev);
        }
        if (methods == 0) {
            return dim("test_quality", "测试内容质量", WEIGHT_TEST_QUALITY, 20, "有测试文件但未识别到测试方法",
                    "测试文件里没有 @Test / def test_ / it() 等用例声明", ev);
        }
        double assertPerTest = (double) asserts / methods;
        int base = clamp((int) Math.round(Math.min(1.0, assertPerTest / 2.0) * 100));
        int emptyPenalty = (int) Math.round(Math.min(40, (double) empty / methods * 200));
        int sleepPenalty = Math.min(20, sleeps * 10);
        int errorBonus = ((double) errorPath / methods >= 0.10) ? 5 : 0;
        int score = clamp(base - emptyPenalty - sleepPenalty + errorBonus);

        if (assertPerTest < 2) {
            ev.add(ev(null, 0, "断言 " + asserts + " / 用例 " + methods + " = " + pct(assertPerTest) + "（满分线 2 断言/用例）"));
        }
        if (empty > 0) ev.add(ev(null, 0, "空测试体 " + empty + " 个（只有空块，没有断言）"));
        return dim("test_quality", "测试内容质量", WEIGHT_TEST_QUALITY, score,
                "断言 " + asserts + "/" + methods + " 用例；空测试 " + empty + "；异常路径 " + errorPath + "；固定等待 " + sleeps,
                score >= 85 ? "断言密度与异常路径覆盖达标"
                        : (assertPerTest < 2 ? "断言密度不足，存在只跑不断言的用例" : "存在空测试或固定等待，测试可信度打折"), ev);
    }

    /** 文档内容质量：README 必需章节覆盖率 + 结构（标题/代码块），不评文笔 */
    private Map<String, Object> dimDocQuality(List<SourceFile> all) {
        List<Map<String, Object>> ev = new ArrayList<>();
        SourceFile readme = all.stream()
                .filter(f -> f.path().toLowerCase().matches("(^|.*/)readme\\.(md|txt|rst)$"))
                .findFirst().orElse(null);
        if (readme == null || readme.content() == null || readme.content().isBlank()) {
            ev.add(ev(null, 0, "仓库内未发现 README（或正文为空）"));
            return dim("doc_quality", "文档内容质量", WEIGHT_DOC_QUALITY, 0, "无 README 正文",
                    "缺少 README，无法评估文档内容", ev);
        }
        String[] patterns = {"install|安装|getting started|quick start|快速开始",
                "run|运行|启动|start", "config|配置|env|环境变量",
                "example|示例|usage|使用", "module|模块|结构|architecture|架构"};
        String[] names = {"安装 / 快速开始", "运行 / 启动", "配置 / 环境变量", "使用示例", "模块 / 架构说明"};
        int covered = 0;
        List<String> missing = new ArrayList<>();
        for (int i = 0; i < patterns.length; i++) {
            if (Pattern.compile(patterns[i], Pattern.CASE_INSENSITIVE).matcher(readme.content()).find()) covered++;
            else missing.add(names[i]);
        }
        int headings = count(readme.content(), Pattern.compile("(?m)^#{1,3} "));
        int codeBlocks = count(readme.content(), Pattern.compile("```")) / 2;
        double structure = Math.min(1.0, (Math.min(headings, 6) / 6.0) * 0.5 + (Math.min(codeBlocks, 4) / 4.0) * 0.5);
        int score = clamp((int) Math.round((covered / (double) patterns.length) * 70 + structure * 30));
        if (!missing.isEmpty()) ev.add(ev(readme.path(), 0, "缺少章节：" + String.join("、", missing)));
        return dim("doc_quality", "文档内容质量", WEIGHT_DOC_QUALITY, score,
                "必需章节 " + covered + "/" + patterns.length + "；标题 " + headings + "；代码块 " + codeBlocks,
                missing.isEmpty() ? "README 覆盖安装/运行/配置/示例/架构" : "README 缺少关键章节", ev);
    }

    private static int count(String text, Pattern p) {
        Matcher m = p.matcher(text);
        int n = 0;
        while (m.find()) n++;
        return n;
    }

    private Map<String, Object> dimDoc(List<SourceFile> all, List<SourceFile> src) {
        boolean hasReadme = all.stream().anyMatch(f -> f.path().toLowerCase().matches("(^|.*/)readme\\.(md|txt|rst)$"));
        long commentLines = 0, codeLines = 0;
        List<Map<String, Object>> ev = new ArrayList<>();
        for (SourceFile f : src) {
            String[] lines = f.content().split("\\R", -1);
            for (int i = 0; i < lines.length; i++) {
                String t = lines[i].trim();
                if (t.isEmpty()) continue;
                codeLines++;
                if (COMMENT_LINE.matcher(lines[i]).find()) commentLines++;
            }
            if (!hasReadme && ev.size() < 1) ev.add(ev(f.path(), 0, "仓库根目录未发现 README"));
        }
        double ratio = codeLines == 0 ? 0 : (double) commentLines / codeLines;
        int readmeScore = hasReadme ? 100 : 0;
        int commentScore = clamp((int) Math.round(Math.min(1.0, ratio / 0.12) * 100));
        int score = clamp((int) Math.round(readmeScore * 0.4 + commentScore * 0.6));
        if (ratio < 0.12) ev.add(ev(null, 0, "注释行占比 " + pct(ratio) + "（满分线 12%）"));
        return dim("documentation", "文档完备度", WEIGHT_DOC, score,
                "README " + (hasReadme ? "有" : "无") + "；注释 " + pct(ratio),
                hasReadme && ratio >= 0.12 ? "README 与注释均达标" : "README 缺失或注释密度不足", ev);
    }

    private Map<String, Object> dimNaming(List<SourceFile> src) {
        int decls = 0, bad = 0;
        List<Map<String, Object>> ev = new ArrayList<>();
        for (SourceFile f : src) {
            String lang = EXT_LANG.get(ext(f.path()));
            String[] lines = f.content().split("\\R", -1);
            for (int i = 0; i < lines.length; i++) {
                String line = lines[i];
                if (COMMENT_LINE.matcher(line).find()) continue;
                Matcher m = switch (lang) {
                    case "java" -> SHORT_DECL_JAVA.matcher(line);
                    case "py" -> SHORT_DECL_PY.matcher(line);
                    default -> SHORT_DECL_JS.matcher(line);
                };
                while (m.find()) {
                    decls++;
                    String name = m.group(1);
                    if (name.matches("[i-nxy]")) continue;   // 允许 i/j/k/x/y 等循环变量
                    bad++;
                    if (ev.size() < 3) ev.add(ev(f.path(), i + 1, "短名声明 `" + name + "`"));
                }
                Matcher lazy = LAZY_NAME.matcher(line);
                while (lazy.find()) {
                    decls++;
                    bad++;
                    if (ev.size() < 3) ev.add(ev(f.path(), i + 1, "临时名 `" + lazy.group(1) + "`"));
                }
            }
        }
        double ratio = decls == 0 ? 0 : (double) bad / decls;
        int score = clamp((int) Math.round((1 - Math.min(1.0, ratio / 0.2)) * 100));
        return dim("naming", "命名规范", WEIGHT_NAMING, score, "可疑命名 " + bad + "/" + decls,
                bad == 0 ? "未发现无意义标识符" : "存在单字母/临时名声明", ev);
    }

    private Map<String, Object> dimComplexity(List<SourceFile> src) {
        int branches = 0, lines = 0;
        // 记录每行分支数，密度超标时用「分支最密集的行」当证据，避免扣分无依据
        record Hot(String path, int line, int count, String snippet) {}
        List<Hot> hot = new ArrayList<>();
        for (SourceFile f : src) {
            String[] arr = f.content().split("\\R", -1);
            for (int i = 0; i < arr.length; i++) {
                String t = arr[i].trim();
                if (t.isEmpty() || COMMENT_LINE.matcher(t).find()) continue;
                lines++;
                Matcher m = BRANCH.matcher(t);
                int c = 0;
                while (m.find()) c++;
                branches += c;
                if (c > 0) hot.add(new Hot(f.path(), i + 1, c, truncate(t, 60)));
            }
        }
        double density = lines == 0 ? 0 : (double) branches / lines;
        // 1) 分支密度:阈值 15% 内满分,到 35% 归零(线性衰减,避免 15.2% 判 0 分的断崖)
        int score;
        if (density <= 0.15) score = 100;
        else if (density >= 0.35) score = 0;
        else score = clamp((int) Math.round((1 - (density - 0.15) / 0.20) * 100));

        List<Map<String, Object>> ev = new ArrayList<>();
        if (density > 0.15) {
            hot.sort(Comparator.comparingInt(Hot::count).reversed());
            for (int i = 0; i < Math.min(3, hot.size()); i++) {
                Hot h = hot.get(i);
                ev.add(ev(h.path(), h.line(), "单行 " + h.count() + " 个分支：" + h.snippet()));
            }
        }
        // 2) 嵌套深度:行内分支数抓不到"每行只有一个 if、但套了 7 层"的写法,单独扣
        int maxDepth = 0;
        String deepPath = null;
        int deepLine = 0;
        for (SourceFile f : src) {
            String lang = EXT_LANG.get(ext(f.path()));
            Map.Entry<Integer, Integer> d = "py".equals(lang) ? pythonMaxIndent(f.content()) : braceMaxDepth(f.content());
            if (d.getKey() > maxDepth) {
                maxDepth = d.getKey();
                deepPath = f.path();
                deepLine = d.getValue();
            }
        }
        if (maxDepth > 5) {
            int cap = clamp(100 - (maxDepth - 5) * 20);
            if (cap < score) score = cap;
            ev.add(ev(deepPath, deepLine, "最大嵌套 " + maxDepth + " 层（阈值 5 层）"));
        }
        return dim("complexity", "控制流复杂度", WEIGHT_COMPLEXITY, score,
                "分支 " + branches + "/" + lines + " = " + pct(density) + "；最大嵌套 " + maxDepth + " 层",
                (density <= 0.15 && maxDepth <= 5) ? "分支密度与嵌套深度均在阈值内"
                        : (density > 0.15 ? "分支密度偏高（阈值 15%，35% 归零）" : "嵌套过深（阈值 5 层）"), ev);
    }

    private Map<String, Object> dimFunctionLength(List<SourceFile> src) {
        int blocks = 0, longBlocks = 0, maxLines = 0;
        List<Map<String, Object>> ev = new ArrayList<>();
        for (SourceFile f : src) {
            String lang = EXT_LANG.get(ext(f.path()));
            List<int[]> spans = "py".equals(lang) ? pythonBlocks(f.content()) : braceBlocks(f.content());
            for (int[] span : spans) {
                int len = span[1] - span[0] + 1;
                if (len < 4) continue;   // 忽略 getter 等极短块
                blocks++;
                maxLines = Math.max(maxLines, len);
                if (len > 50) {
                    longBlocks++;
                    if (ev.size() < 3) ev.add(ev(f.path(), span[0], "块长度 " + len + " 行"));
                }
            }
        }
        double ratio = blocks == 0 ? 0 : (double) longBlocks / blocks;
        int score = clamp((int) Math.round((1 - Math.min(1.0, ratio / 0.2)) * 100));
        return dim("function_length", "函数长度", WEIGHT_FUNCTION_LEN, score, "超 50 行块 " + longBlocks + "/" + blocks + "，最长 " + maxLines,
                longBlocks == 0 ? "未发现超长函数" : "存在超长函数，建议拆分", ev);
    }

    private Map<String, Object> dimDuplication(List<SourceFile> src) {
        Map<String, Integer> seen = new HashMap<>();
        int total = 0, dup = 0;
        List<Map<String, Object>> ev = new ArrayList<>();
        for (SourceFile f : src) {
            String[] arr = f.content().split("\\R", -1);
            for (int i = 0; i < arr.length; i++) {
                String t = normalize(arr[i]);
                if (t.isEmpty() || t.length() < 12) continue;
                total++;
                int c = seen.merge(t, 1, Integer::sum);
                // 从第 3 次出现才计重复：两处同名方法签名是巧合，三份才是复制粘贴
                if (c == 3) dup++;
                if (c == 3 && ev.size() < 3) ev.add(ev(f.path(), i + 1, "重复 3 次：" + truncate(arr[i].trim(), 60)));
            }
        }
        double ratio = total == 0 ? 0 : (double) dup / total;
        // 5% 内满分（样板与 getter 难免），到 15% 归零；文案与评分口径保持一致
        int score = ratio <= 0.05 ? 100 : clamp((int) Math.round((1 - Math.min(1.0, (ratio - 0.05) / 0.10)) * 100));
        return dim("duplication", "重复代码", WEIGHT_DUPLICATION, score, "重复行 " + dup + "/" + total + " = " + pct(ratio),
                ratio <= 0.05 ? "重复率在阈值内（≤5%）" : "重复率超过 5% 阈值", ev);
    }

    private Map<String, Object> dimErrorHandling(List<SourceFile> src) {
        int catches = 0, empty = 0;
        List<Map<String, Object>> ev = new ArrayList<>();
        Pattern catchAny = Pattern.compile("\\bcatch\\s*\\(|\\bexcept\\b");
        for (SourceFile f : src) {
            String lang = EXT_LANG.get(ext(f.path()));
            String content = f.content();
            Matcher all = catchAny.matcher(content);
            while (all.find()) catches++;
            Pattern p = "py".equals(lang) ? BARE_EXCEPT_PY : EMPTY_CATCH_JAVA;
            Matcher m = p.matcher(content);
            while (m.find()) {
                empty++;
                int line = content.substring(0, m.start()).split("\\R", -1).length;
                if (ev.size() < 3) ev.add(ev(f.path(), line, "空异常处理：" + truncate(m.group().trim(), 50)));
            }
        }
        double ratio = catches == 0 ? 0 : (double) empty / catches;
        int score = clamp((int) Math.round((1 - Math.min(1.0, ratio / 0.3)) * 100));
        return dim("error_handling", "异常处理", WEIGHT_ERROR_HANDLING, score, "空 catch " + empty + "/" + catches,
                empty == 0 ? "未发现空异常处理" : "存在吞异常", ev);
    }

    private Map<String, Object> dimSecurity(List<SourceFile> src) {
        int hits = 0;
        List<Map<String, Object>> ev = new ArrayList<>();
        for (SourceFile f : src) {
            String[] arr = f.content().split("\\R", -1);
            for (int i = 0; i < arr.length; i++) {
                for (var rule : SECURITY_RULES) {
                    Matcher m = rule.getKey().matcher(arr[i]);
                    if (m.find()) {
                        hits++;
                        if (ev.size() < 3) ev.add(ev(f.path(), i + 1, rule.getValue() + "：" + mask(arr[i].trim())));
                        break;
                    }
                }
            }
        }
        int score = clamp(100 - Math.min(100, hits * 25));
        return dim("security", "安全模式", WEIGHT_SECURITY, score, "命中危险模式 " + hits + " 处",
                hits == 0 ? "未命中硬编码凭据 / eval / SQL 拼接等模式" : "命中危险模式，需人工确认", ev);
    }

    // ---------- 工具 ----------

    private Map<String, Object> dim(String key, String name, int weight, int score, String value, String detail,
                                    List<Map<String, Object>> evidence) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("key", key);
        m.put("name", name);
        m.put("weight", weight);
        m.put("score", score);
        m.put("value", value);
        m.put("detail", detail);
        m.put("evidence", evidence);
        return m;
    }

    private Map<String, Object> ev(String path, int line, String snippet) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("path", path);
        m.put("line", line);
        m.put("snippet", snippet);
        return m;
    }

    private static String grade(int total) {
        if (total >= 85) return "优";
        if (total >= 70) return "良";
        if (total >= 55) return "中";
        return "差";
    }

    private static int clamp(int v) { return Math.max(0, Math.min(100, v)); }

    private static String ext(String path) {
        int i = path.lastIndexOf('.');
        return i < 0 ? "" : path.substring(i + 1).toLowerCase();
    }

    private static String pct(double v) { return Math.round(v * 1000) / 10.0 + "%"; }

    private static String truncate(String s, int n) { return s.length() <= n ? s : s.substring(0, n) + "…"; }

    /** 凭据类片段脱敏:保留前 6 字符 */
    private static String mask(String s) {
        String t = s.replaceAll("(\"[^\"]{4,}\")", "\"****\"");
        return t.length() <= 80 ? t : t.substring(0, 80) + "…";
    }

    private static String normalize(String line) {
        String t = line.trim();
        if (t.isEmpty()) return "";
        // 样板行不算重复：package / import / 注解 / 纯括号 / 方法签名
        if (t.startsWith("package ") || t.startsWith("import ") || t.startsWith("@")
                || t.startsWith("//") || t.startsWith("#") || t.startsWith("*") || t.startsWith("/*")
                || t.equals("{") || t.equals("}") || t.equals("});") || t.equals("};")
                || t.equals(") {") || t.equals("},")) return "";
        t = t.replaceAll("\\s+", " ");
        t = t.replaceAll("\"[^\"]*\"", "\"S\"");   // 抹平字符串字面量差异
        return t.length() < 20 ? "" : t;              // 短行天然重复（return null; 等），不计
    }

    /** 大括号配平找代码块,返回 [startLine, endLine] 列表(1-based) */
    private static List<int[]> braceBlocks(String content) {
        List<int[]> out = new ArrayList<>();
        String[] lines = content.split("\\R", -1);
        int depth = 0, start = -1;
        for (int i = 0; i < lines.length; i++) {
            String t = stripStringsAndComments(lines[i]);
            for (int c = 0; c < t.length(); c++) {
                char ch = t.charAt(c);
                if (ch == '{') {
                    if (depth == 0) start = i + 1;
                    depth++;
                } else if (ch == '}') {
                    depth--;
                    if (depth == 0 && start > 0) out.add(new int[]{start, i + 1});
                    if (depth < 0) depth = 0;
                }
            }
        }
        return out;
    }

    /** Python 按缩进找 def / class 块 */
    private static List<int[]> pythonBlocks(String content) {
        List<int[]> out = new ArrayList<>();
        String[] lines = content.split("\\R", -1);
        for (int i = 0; i < lines.length; i++) {
            String t = lines[i];
            if (!t.matches("^\\s*(def|class)\\s+.*")) continue;
            int indent = t.indexOf(t.trim());
            int end = i + 1;
            for (int j = i + 1; j < lines.length; j++) {
                String l = lines[j];
                if (l.trim().isEmpty()) continue;
                int ind = l.indexOf(l.trim());
                if (ind <= indent) break;
                end = j + 1;
            }
            out.add(new int[]{i + 1, end});
        }
        return out;
    }

    /** 大括号最大深度,返回 [最大深度, 出现行号] */
    private static Map.Entry<Integer, Integer> braceMaxDepth(String content) {
        String[] lines = content.split("\\R", -1);
        int depth = 0, max = 0, at = 0;
        for (int i = 0; i < lines.length; i++) {
            String t = stripStringsAndComments(lines[i]);
            for (int c = 0; c < t.length(); c++) {
                char ch = t.charAt(c);
                if (ch == '{') {
                    depth++;
                    if (depth > max) { max = depth; at = i + 1; }
                } else if (ch == '}') {
                    if (depth > 0) depth--;
                }
            }
        }
        return Map.entry(max, at);
    }

    /** Python 最大缩进层数(按 4 空格一层估算),返回 [层数, 行号] */
    private static Map.Entry<Integer, Integer> pythonMaxIndent(String content) {
        String[] lines = content.split("\\R", -1);
        int max = 0, at = 0;
        for (int i = 0; i < lines.length; i++) {
            String l = lines[i];
            if (l.trim().isEmpty()) continue;
            int indent = l.indexOf(l.trim());
            int level = indent / 4;
            if (level > max) { max = level; at = i + 1; }
        }
        return Map.entry(max, at);
    }

    private static String stripStringsAndComments(String line) {
        String t = line;
        int idx = t.indexOf("//");
        if (idx >= 0) t = t.substring(0, idx);
        return t.replaceAll("\"[^\"]*\"", "\"\"").replaceAll("'[^']*'", "''");
    }
}
