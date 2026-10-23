package com.aicap.service;

import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.*;

/** Evidence-first US23 rules. Never writes project data or treats weak edges as blockers. */
public final class EfficiencyRules {
    public static final int OVERDUE_HIGH_DAYS = 7, WAIT_DAYS = 14, REWORK_EVENTS = 3;
    public static final double OVERLOAD = 1.10, UNDERLOAD = .70;
    private EfficiencyRules() {}
    public record TaskFact(String code, String title, String storyCode, Integer ownerId, int status,
                           int progress, int hours, boolean blocked, List<String> corePredecessors, LocalDate dueDate,
                           LocalDate statusSince, int regressions) {}
    public record MemberFact(Integer id, String name, double capacity, double assigned,
                             List<String> skills, List<String> history) {}
    public record ActivityFact(String id, String taskCode, String module, String type, String source, String title) {}

    public static List<Map<String,Object>> analyze(List<TaskFact> tasks, List<MemberFact> members,
                                                  List<ActivityFact> activities, LocalDate today) {
        List<Map<String,Object>> out = new ArrayList<>();
        for (TaskFact t : tasks) {
            if (t.status() >= 2) continue;
            var successors = tasks.stream().filter(n -> n.status() == 0 && n.corePredecessors().contains(t.code())).toList();
            long overdue = t.dueDate() == null ? 0 : ChronoUnit.DAYS.between(t.dueDate(), today);
            if (t.blocked() || (overdue > 0 && !successors.isEmpty())) {
                var affected = new ArrayList<TaskFact>(); affected.add(t); affected.addAll(successors);
                add(out, "TASK_BLOCKING", overdue >= OVERDUE_HIGH_DAYS ? "HIGH" : "MEDIUM",
                    t.code() + " 逾期且阻塞核心后续任务",
                    List.of("tasks:" + t.code() + ".blocked=" + t.blocked(), "tasks:" + t.code() + ".due=" + t.dueDate(),
                        "overdue_days=" + overdue, "progress=" + t.progress() + "%",
                        "core_successors=" + successors.stream().map(TaskFact::code).toList()),
                    affected, List.of(t.ownerId()), "前置任务尚未交付；具体阻塞原因需与负责人核实",
                    "与 " + t.code() + " 负责人确认解除条件，优先交付后续任务所需最小接口/产物，再评估受影响任务排期",
                    "减少核心依赖等待；调整排期前仍需审批", .9);
            }
            if (t.statusSince() != null && t.status() == 1 && t.progress() < 100
                    && ChronoUnit.DAYS.between(t.statusSince(), today) >= WAIT_DAYS
                    && contains(t.title(), "审核", "测试", "验收", "review", "test")) {
                add(out, "PROCESS_DELAY", "MEDIUM", t.code() + " 测试/审核阶段持续时间偏长",
                    List.of("task_logs:" + t.code() + ".status_since=" + t.statusSince(),
                        "days_in_status=" + ChronoUnit.DAYS.between(t.statusSince(), today), "progress=" + t.progress() + "%"),
                    List.of(t), List.of(t.ownerId()), "状态持续时间较长是复核信号，不能证明实际没有开展工作",
                    "核对 " + t.code() + " 等待的审核人、测试环境或输入，明确解除条件和下一次检查时间",
                    "减少审核/测试等待，不能仅凭日志认定成员低效", .7);
            }
        }
        for (MemberFact m : members) {
            if (m.capacity() <= 0 || m.assigned() / m.capacity() <= OVERLOAD) continue;
            var owned = tasks.stream().filter(t -> m.id().equals(t.ownerId()) && t.status() < 2).toList();
            List<Map<String,Object>> candidates = new ArrayList<>();
            for (TaskFact t : owned) for (MemberFact c : members) {
                if (c.id().equals(m.id()) || c.capacity() <= 0 || c.assigned() / c.capacity() >= UNDERLOAD
                        || c.capacity() - c.assigned() < t.hours()) continue;
                var matched = c.skills().stream().filter(s -> s.length() >= 2 && t.title().toLowerCase().contains(s.toLowerCase())).toList();
                var history = c.history().stream().filter(s -> s.length() >= 2 && t.title().toLowerCase().contains(s.toLowerCase())).toList();
                if (!matched.isEmpty() || !history.isEmpty()) candidates.add(Map.of("task_code", t.code(), "member_id", c.id(),
                    "member_name", c.name(), "matched_skills", matched, "history_evidence", history,
                    "remaining_hours", c.capacity() - c.assigned()));
            }
            var row = add(out, "WORKLOAD_IMBALANCE", m.assigned() / m.capacity() >= 1.3 ? "HIGH" : "MEDIUM",
                m.name() + " 计划负载超过当前项目容量",
                List.of("member=" + m.id(), "capacity=weeklyCapacity×projectWeeks=" + m.capacity(),
                    "assigned_hours=" + m.assigned(), "utilization_percent=" + Math.round(m.assigned() / m.capacity() * 100)),
                owned, List.of(m.id()), candidates.isEmpty() ? "没有同时满足技能/历史匹配及剩余容量的候选，不强行转移" : "存在低于70%负载且具有技能或历史依据的候选",
                candidates.isEmpty() ? "与 " + m.name() + " 复核未完成任务优先级和可拆分范围，先确认范围或排期调整"
                    : "复核候选人的技能、历史经验与容量后，再申请转移列出的任务",
                "缓解超额计划；100%表示容量用满，不自动调人", .85);
            row.put("candidate_assignments", candidates);
            if (!candidates.isEmpty()) {
                @SuppressWarnings("unchecked")
                Map<String,Object> recommendation = (Map<String,Object>) ((List<?>)row.get("recommendations")).getFirst();
                recommendation.put("targetTaskIds", candidates.stream().map(c -> c.get("task_code")).distinct().toList());
                recommendation.put("targetMemberIds", candidates.stream().map(c -> c.get("member_id")).distinct().toList());
            }
        }
        for (TaskFact t : tasks) {
            var linked = activities.stream().filter(a -> t.code().equals(a.taskCode())).toList();
            long fixes = linked.stream().filter(a -> "bugfix".equals(a.type())).count();
            long commits = linked.stream().filter(a -> "commit".equals(a.type())).count();
            if (t.regressions() < 2 && fixes < REWORK_EVENTS && commits < REWORK_EVENTS) continue;
            boolean corroborated = t.regressions() >= 2 || fixes >= REWORK_EVENTS;
            var row = add(out, "REWORK_SIGNAL", corroborated ? "MEDIUM" : "LOW",
                t.code() + (corroborated ? " 存在重复回退/修复信号" : " 可能存在返工迹象（证据不足）"),
                List.of("task_logs.status_regressions=" + t.regressions(), "bugfix_events=" + fixes, "commit_events=" + commits,
                    "activity_record_ids=" + linked.stream().map(ActivityFact::id).toList()),
                List.of(t), List.of(t.ownerId()), corroborated ? "可确认重复回退或修复记录，具体原因仍需复核" : "只有提交次数，不能断言返工或需求变更原因",
                "抽样复核 " + t.code() + " 的关联修复、提交与状态历史，确认同一问题是否重复出现，再申请补充验收或回归检查",
                "减少重复修复；不因提交次数直接调整负责人", corroborated ? .75 : .3);
            row.put("evidence_sufficient", corroborated);
        }
        return out;
    }
    private static boolean contains(String text, String... words) {
        for (String w : words) if (text != null && text.toLowerCase().contains(w.toLowerCase())) return true;
        return false;
    }
    private static Map<String,Object> add(List<Map<String,Object>> out, String type, String severity,
            String title, List<String> evidence, List<TaskFact> tasks, List<Integer> members,
            String cause, String action, String impact, double confidence) {
        var row = new LinkedHashMap<String,Object>();
        row.put("id", "B" + (out.size()+1)); row.put("type", type); row.put("severity", severity);
        row.put("title", title); row.put("description", title); row.put("evidence", evidence);
        row.put("affectedStoryCodes", tasks.stream().map(TaskFact::storyCode).filter(Objects::nonNull).filter(s -> !s.isBlank()).distinct().toList());
        row.put("affectedTaskCodes", tasks.stream().map(TaskFact::code).distinct().toList());
        row.put("affectedMemberIds", members); row.put("possibleCauses", List.of(cause)); row.put("confidence", confidence); row.put("status", "OPEN");
        row.put("recommendations", List.of(new LinkedHashMap<>(Map.of("id", "R" + (out.size()+1),
            "actionType", "REVIEW_OPTIMIZATION", "description", action, "reason", String.join("; ", evidence),
            "expectedImpact", impact, "targetTaskIds", tasks.stream().map(TaskFact::code).distinct().toList(),
            "targetMemberIds", members, "requiresApproval", true, "status", "suggested"))));
        out.add(row); return row;
    }
}
