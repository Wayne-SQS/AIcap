package com.aicap.profile;

import com.aicap.entity.Task;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 画像智能体的大脑:三个核心方法(难度评估/成员分析/团队风险)由 LLM+工具循环生成。
 * - LLM 未配置或调用失败/输出不合法 → 返回 null,由 ProfileAgentService 降级到原规则引擎(备胎)。
 * - 结论必须带 confidence 与 evidence_refs(锚定工具调用),满足"可解释/幻觉检测"验收。
 * - startRecording/collectSteps 供运行留痕:每次 run 的 thought/action/observation/tokens 全记录。
 */
@Service
public class ProfileAgentBrain {

    private final ProfileAgentRuntime runtime;
    private final ProfileMemoryStore memoryStore;
    private final ObjectMapper objectMapper;

    /** 线程级录制:仅在 runAnalysis 时开启,GET 预览不留痕也不泄漏 */
    private static final ThreadLocal<List<Map<String, Object>>> STEP_LOG = new ThreadLocal<>() {
        @Override
        protected List<Map<String, Object>> initialValue() {
            return new ArrayList<>();
        }
    };
    private static final ThreadLocal<Boolean> RECORDING = ThreadLocal.withInitial(() -> false);
    private static final ThreadLocal<Integer> TOKENS = ThreadLocal.withInitial(() -> 0);

    public ProfileAgentBrain(ProfileAgentRuntime runtime, ProfileMemoryStore memoryStore,
                             ObjectMapper objectMapper) {
        this.runtime = runtime;
        this.memoryStore = memoryStore;
        this.objectMapper = objectMapper;
    }

    public boolean available() {
        return runtime.available();
    }

    // ---------- 步骤录制(任务 #7 的可观测基础) ----------

    /** 开始录制(请求线程内);结束调用 collectSteps */
    public void startRecording() {
        STEP_LOG.get().clear();
        TOKENS.set(0);
        RECORDING.set(true);
    }

    /** 收集并结束录制;返回 steps 结构 + tokens 总量 */
    public Map<String, Object> collectSteps() {
        List<Map<String, Object>> steps = new ArrayList<>(STEP_LOG.get());
        Map<String, Object> out = new java.util.LinkedHashMap<>();
        out.put("steps", steps);
        out.put("total_tokens", TOKENS.get());
        out.put("model", runtime.available() ? "llm" : "rules_fallback");
        RECORDING.set(false);
        STEP_LOG.get().clear();
        return out;
    }

    private void record(AgentRunOutcome o) {
        if (Boolean.TRUE.equals(RECORDING.get())) {
            for (var s : o.outcome().steps()) {
                STEP_LOG.get().add(linkedStep(o.phase(), s));
            }
            TOKENS.set(TOKENS.get() + o.outcome().totalTokens());
        }
    }

    private Map<String, Object> linkedStep(String phase, ProfileAgentRuntime.Step s) {
        Map<String, Object> m = new java.util.LinkedHashMap<>();
        m.put("phase", phase);
        m.put("index", s.index());
        m.put("thought", s.thought());
        m.put("action_tool", s.actionTool());
        m.put("action_args", s.actionArgs());
        m.put("observation", s.observation());
        m.put("observation_ok", s.observationOk());
        m.put("tokens", s.promptTokens() + s.completionTokens());
        m.put("model", s.model());
        return m;
    }

    /** 一次大脑运行的包装:结论 + 运行元数据 */
    public record BrainRun(AgentRunOutcome outcome) {
    }

    private record AgentRunOutcome(String phase, ProfileAgentRuntime.AgentOutcome outcome) {
    }

    // ---------- 1. 难度评估(LLM 内核) ----------

    /** 结构化难度结论(level/score/basis/confidence/evidence_refs) */
    public record DifficultyConclusion(String level, Integer score, List<String> basis,
                                       Double confidence, List<String> evidenceRefs) {
    }

    private static final String DIFF_SYSTEM = """
            你是"AI 任务提交与成员能力画像智能体"的任务难度评估内核。
            你的职责:基于给定的任务事实与可用工具,通过推理评估该任务的难度。
            禁止使用关键字匹配式判断(如任务名包含"权限"就加分)——必须综合工时、依赖链、
            被后续任务依赖数(影响后续任务的程度)、阻塞状态、历史同类任务的难度记忆、人工修正先验,
            给出有因果论证的结论。
            需要更多上下文时可调用工具(如查依赖任务详情、查该任务历史评估)。
            收敛时输出 JSON:
            {"level":"low|medium|high|extreme","score":0-100,"basis":["依据1(+N分原因)","依据2",...],
             "confidence":0.0-1.0,"evidence_refs":["引用的工具调用或事实,如 query_tasks:T01"]}
            依据必须逐条可追溯;信息不足时降低 confidence 并说明缺什么,禁止编造。
            """;

    /** LLM 难度评估;不可用/失败/输出不合法 → null(降级规则引擎) */
    public DifficultyConclusion assessDifficulty(Task t, int downstreamCount) {
        if (!available() || t == null) return null;
        try {
            ProfileMemoryStore.Memory mem = memoryStore.difficultyMemory(t.getId());
            String facts = "## 待评估任务\n" + objectMapper.writeValueAsString(Map.of(
                    "task_id", t.getId(),
                    "name", t.getName() == null ? "" : t.getName(),
                    "status", t.getStatus() == null ? "" : t.getStatus(),
                    "hours", t.getHours() == null ? 0 : t.getHours(),
                    "depends_on", t.getDependsOn() == null ? "" : t.getDependsOn(),
                    "downstream_tasks", downstreamCount,   // 4.5: 被多少后续任务依赖(影响后续任务程度)
                    "week_start", t.getWeekStart() == null ? 0 : t.getWeekStart(),
                    "week_end", t.getWeekEnd() == null ? 0 : t.getWeekEnd(),
                    "blocked", t.getBlocked() != null && t.getBlocked() == 1));
            AgentRunOutcome o = runPhase("difficulty", DIFF_SYSTEM, facts + "\n\n" + mem.text());
            JsonNode f = objectMapper.readTree(o.outcome().finalJson());
            String level = f.path("level").asText("");
            if (!List.of("low", "medium", "high", "extreme").contains(level)) return null;
            int score = f.path("score").asInt(-1);
            if (score < 0 || score > 100) return null;
            List<String> basis = toStringList(f.path("basis"));
            if (basis.isEmpty()) return null;
            return new DifficultyConclusion(level, score, basis,
                    f.path("confidence").asDouble(0.5), toStringList(f.path("evidence_refs")));
        } catch (Exception e) {
            return null;   // 降级
        }
    }

    // ---------- 2. 成员分析(LLM 内核) ----------

    private static final String MEMBER_SYSTEM = """
            你是"AI 任务提交与成员能力画像智能体"的成员分析内核。
            输入是该成员在本周期的客观事实(活动记录/任务/负载)与历史记忆(画像快照/成员纠正)。
            你的职责:归纳成员的工作方向、能力画像与风险信号。红线:
            - 不得把提交次数等同于工作量或工作质量;
            - 不得输出"该成员工作质量低"之类对人的评判,异常只描述模式并给建议;
            - 每条推断带依据;与成员本人纠正记录冲突时以纠正为准;
            - 新成员/数据不足时建议"先观察",不要硬编结论。
            需要核实疑点(如长期无活动)应调用工具主动查证,而不是直接报警。
            dynamic_profile 字段(如数据不足用"待观察/暂无足够数据"):
            good_at=擅长方向, difficulty_capacity=难度承受能力, delivery_timeliness=交付及时性,
            code_stability=代码稳定性(返工/缺陷少则较高), rework_rate=返工情况,
            defect_fix_capability=缺陷修复能力, review_participation=Code Review参与度,
            risk_flags=正常|负载偏高|负载过高, recommended_task_types=[推荐任务类型]
            收敛时输出 JSON:
            {"ai_inference":["推断1(依据:...)","推断2",...],
             "dynamic_profile":{...上述字段...},
             "confidence":0.0-1.0,"evidence_refs":["..."]}
            """;

    /** LLM 成员分析;不可用/失败/输出不合法 → null(降级规则引擎) */
    public Map<String, Object> memberAnalysis(Integer userId, Map<String, Object> objectiveFacts) {
        if (!available() || userId == null) return null;
        try {
            ProfileMemoryStore.Memory mem = memoryStore.memberMemory(userId);
            String facts = "## 本周期客观事实(由系统统计,可信)\n"
                    + objectMapper.writeValueAsString(objectiveFacts)
                    + "\n\n## 成员记忆\n" + mem.text();
            AgentRunOutcome o = runPhase("member", MEMBER_SYSTEM, facts);
            JsonNode f = objectMapper.readTree(o.outcome().finalJson());
            if (!f.has("ai_inference") || !f.has("dynamic_profile")) return null;

            // M3 修复:逐字段结构校验——ai_inference 只取字符串数组(前 10 条,每条限长),
            // dynamic_profile 只保留白名单字段,confidence 收敛到 0-1,防止 LLM 输出任意结构污染前端/下游。
            List<String> inference = new ArrayList<>();
            JsonNode inf = f.path("ai_inference");
            if (inf.isArray()) {
                for (JsonNode n : inf) {
                    if (n.isTextual() && !n.asText().isBlank()) {
                        String s = n.asText().trim();
                        if (s.length() > 200) s = s.substring(0, 200) + "…";
                        inference.add(s);
                        if (inference.size() >= 10) break;
                    }
                }
            }
            Map<String, Object> profile = new java.util.LinkedHashMap<>();
            JsonNode dp = f.path("dynamic_profile");
            if (dp.isObject()) {
                for (String key : List.of("good_at", "difficulty_capacity", "delivery_timeliness",
                        "code_stability", "rework_rate", "defect_fix_capability", "review_participation",
                        "risk_flags", "recommended_task_types")) {
                    JsonNode v = dp.path(key);
                    if (v.isTextual() && !v.asText().isBlank()) {
                        profile.put(key, v.asText().trim());
                    } else if (v.isArray()) {
                        profile.put(key, objectMapper.convertValue(v, new TypeReference<List<String>>() {
                        }));
                    }
                }
            }
            if (inference.isEmpty() && profile.isEmpty()) return null;   // 结构不合规 → 降级规则引擎
            double confidence = f.path("confidence").asDouble(0.5);
            confidence = Math.max(0.0, Math.min(1.0, confidence));       // clamp 0-1

            Map<String, Object> out = new java.util.LinkedHashMap<>();
            out.put("ai_inference", inference);
            out.put("dynamic_profile", profile);
            out.put("confidence", confidence);
            out.put("evidence_refs", toStringList(f.path("evidence_refs")));
            out.put("memory_stats", memoryStore.stats(mem));
            return out;
        } catch (Exception e) {
            return null;
        }
    }

    // ---------- 3. 团队风险归因(LLM 内核) ----------

    private static final String RISK_SYSTEM = """
            你是"AI 任务提交与成员能力画像智能体"的团队风险归因内核。
            输入是团队各成员的负载/任务/活动摘要与历史记忆。你的职责:识别团队级风险并归因。
            已知风险类型(kind): overload(负载过高)/single_point(关键单点依赖)/stalled(长期无进展)/
            missing_review(高难度缺Review)/missing_test(任务缺测试活动记录)/
            module_concentration(某模块工作集中在少数人)/milestone_risk(里程碑延期);
            也可基于证据提出其他类型。
            规则:每条风险必须有具体证据与可执行的建议行动;宁可少报不可编造;
            对可疑点(如某任务无活动)先调用工具核实再下结论。
            收敛时输出 JSON:
            {"risks":[{"kind":"...","user_id":1,"task_id":"可选","member":"可选","task_name":"可选",
              "evidence":"具体证据","inference":"归因推断","suggested_action":"建议行动","confidence":0.0-1.0,
              "evidence_refs":["..."]}],
             "confidence":0.0-1.0}
            """;

    /** LLM 团队风险;不可用/失败/输出不合法 → null(降级规则引擎) */
    public List<Map<String, Object>> teamRisks(Map<String, Object> teamFacts) {
        if (!available()) return null;
        try {
            String facts = "## 团队事实摘要(由系统统计,可信)\n" + objectMapper.writeValueAsString(teamFacts);
            AgentRunOutcome o = runPhase("team", RISK_SYSTEM, facts);
            JsonNode f = objectMapper.readTree(o.outcome().finalJson());
            List<Map<String, Object>> risks = objectMapper.convertValue(f.path("risks"),
                    new TypeReference<List<Map<String, Object>>>() {
                    });
            if (risks == null) return null;
            // 逐条校验:必须有 kind 与 evidence,否则丢弃该条(防幻觉)
            List<Map<String, Object>> ok = new ArrayList<>();
            for (Map<String, Object> r : risks) {
                String kind = String.valueOf(r.get("kind"));
                String evidence = String.valueOf(r.get("evidence"));
                if (!kind.isBlank() && !"null".equals(kind) && !evidence.isBlank() && !"null".equals(evidence)) {
                    r.putIfAbsent("confidence", f.path("confidence").asDouble(0.5));
                    ok.add(r);
                }
            }
            return ok;
        } catch (Exception e) {
            return null;
        }
    }

    // ---------- 内部 ----------

    private AgentRunOutcome runPhase(String phase, String systemPrompt, String userPrompt) {
        ProfileAgentRuntime.AgentOutcome o = runtime.run(systemPrompt, userPrompt);
        AgentRunOutcome wrap = new AgentRunOutcome(phase, o);
        record(wrap);
        return wrap;
    }

    private List<String> toStringList(JsonNode node) {
        List<String> out = new ArrayList<>();
        if (node.isArray()) {
            node.forEach(n -> out.add(n.asText()));
        } else if (node.isTextual() && !node.asText().isBlank()) {
            out.add(node.asText());
        }
        return out;
    }
}
