package com.aicap.profile;

import com.aicap.entity.DifficultyAssessment;
import com.aicap.entity.ProfileCorrection;
import com.aicap.entity.ProfileSnapshot;
import com.aicap.mapper.DifficultyAssessmentMapper;
import com.aicap.mapper.ProfileCorrectionMapper;
import com.aicap.mapper.ProfileSnapshotMapper;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 画像智能体记忆层(独立于会议智能体):
 * - 短期记忆:由 ProfileAgentRuntime 的对话消息列表承担(当前分析会话上下文);
 * - 长期记忆:本类负责——分析前检索历史画像快照 + 人工修正反馈 + 成员纠正,组装成 prompt 注入块;
 * - 反馈回灌(验收标准 5 闭环):人工修正过的难度结论作为"必须尊重的先验"注入,
 *   使下次同类任务评估吸收人工判断,而不是重复犯错。
 */
@Component
public class ProfileMemoryStore {

    private final ProfileSnapshotMapper snapshotMapper;
    private final DifficultyAssessmentMapper difficultyMapper;
    private final ProfileCorrectionMapper correctionMapper;
    private final ObjectMapper objectMapper;

    public ProfileMemoryStore(ProfileSnapshotMapper snapshotMapper,
                              DifficultyAssessmentMapper difficultyMapper,
                              ProfileCorrectionMapper correctionMapper,
                              ObjectMapper objectMapper) {
        this.snapshotMapper = snapshotMapper;
        this.difficultyMapper = difficultyMapper;
        this.correctionMapper = correctionMapper;
        this.objectMapper = objectMapper;
    }

    /** 记忆上下文(结构化,供写入 prompt) */
    public record Memory(String text, int snapshotCount, int feedbackCount, int correctionCount) {
    }

    /** 组装某成员的记忆上下文:历史画像轨迹 + 成员纠正 */
    public Memory memberMemory(Integer userId) {
        StringBuilder sb = new StringBuilder();
        List<ProfileSnapshot> snaps = snapshotMapper.selectList(new QueryWrapper<ProfileSnapshot>()
                .eq("user_id", userId).orderByDesc("id").last("LIMIT 5"));
        if (!snaps.isEmpty()) {
            sb.append("### 历史画像记忆(最近 ").append(snaps.size()).append(" 次快照,倒序)\n");
            for (ProfileSnapshot s : snaps) {
                sb.append("- [").append(s.getRangeStart()).append(" ~ ").append(s.getRangeEnd()).append("] ")
                        .append(compact(s.getPayloadJson())).append('\n');
            }
        }
        List<ProfileCorrection> corrections = correctionMapper.selectList(new QueryWrapper<ProfileCorrection>()
                .eq("user_id", userId).orderByDesc("id").last("LIMIT 10"));
        if (!corrections.isEmpty()) {
            sb.append("### 成员本人纠正记录(成员确认的事实,必须优先采信)\n");
            for (ProfileCorrection c : corrections) {
                sb.append("- [").append(c.getField()).append("] ").append(c.getCorrectedValue())
                        .append(c.getReason() == null || c.getReason().isEmpty() ? "" : "(理由: " + c.getReason() + ")")
                        .append('\n');
            }
        }
        if (sb.isEmpty()) {
            sb.append("### 记忆\n- 该成员无历史画像与纠正记录(新成员场景:如信息确实不足,建议先观察,不要硬下结论)\n");
        }
        return new Memory(sb.toString(), snaps.size(), 0, corrections.size());
    }

    /** 难度评估的记忆:同类任务的历史评估 + 人工修正先验(反馈回灌) */
    public Memory difficultyMemory(String taskId) {
        StringBuilder sb = new StringBuilder();
        List<DifficultyAssessment> history = difficultyMapper.selectList(new QueryWrapper<DifficultyAssessment>()
                .orderByDesc("id").last("LIMIT 50"));
        // M1 修复:人工修正只取最近 10 条,并如实标注"来自其他任务、仅作校准参考",
        // 避免把项目内全部人工修正当成"同类任务先验"注入,防止任务 A 的修正干扰任务 B 的评估。
        List<DifficultyAssessment> manual = history.stream()
                .filter(a -> "manual".equals(a.getAssessedBy())).limit(10).toList();
        if (!manual.isEmpty()) {
            sb.append("### 项目级人工修正参考(项目负责人确认过的难度结论,均来自其他任务,仅作校准参考,不要直接套用到本任务)\n");
            for (DifficultyAssessment a : manual) {
                sb.append("- 任务 ").append(a.getTaskId()).append(": ").append(a.getLevel())
                        .append("(").append(a.getScore()).append("分) 依据: ").append(compact(a.getBasis())).append('\n');
            }
        }
        List<DifficultyAssessment> sameTask = history.stream()
                .filter(a -> taskId != null && taskId.equals(a.getTaskId())).toList();
        if (!sameTask.isEmpty()) {
            sb.append("### 该任务的历史评估(避免无理由推翻)\n");
            for (DifficultyAssessment a : sameTask) {
                sb.append("- [").append(a.getAssessedBy()).append("] ").append(a.getLevel())
                        .append("(").append(a.getScore()).append("分) 依据: ").append(compact(a.getBasis())).append('\n');
            }
        }
        if (sb.isEmpty()) {
            sb.append("### 难度记忆\n- 暂无历史评估与人工修正,按当前证据独立判断\n");
        }
        return new Memory(sb.toString(), 0, manual.size(), 0);
    }

    /** 记忆使用情况统计(进可观测输出) */
    public Map<String, Object> stats(Memory m) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("snapshot_count", m.snapshotCount());
        out.put("human_feedback_count", m.feedbackCount());
        out.put("correction_count", m.correctionCount());
        return out;
    }

    private String compact(String json) {
        if (json == null) return "";
        try {
            Object o = objectMapper.readValue(json, Object.class);
            String s = objectMapper.writeValueAsString(o);
            return s.length() <= 400 ? s : s.substring(0, 400) + "…";
        } catch (Exception e) {
            return json.length() <= 400 ? json : json.substring(0, 400) + "…";
        }
    }
}
