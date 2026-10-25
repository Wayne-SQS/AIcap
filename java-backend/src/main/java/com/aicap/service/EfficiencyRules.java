package com.aicap.service;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.Duration;
import java.time.temporal.ChronoUnit;
import java.util.*;

/** Deterministic, evidence-first US23 rules. AI may explain these results but never supplies metrics. */
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
    public record ProcessFact(String id, String type, String status, LocalDateTime createdAt, LocalDateTime reviewedAt) {}

    public static List<Map<String,Object>> analyze(List<TaskFact> tasks, List<MemberFact> members,
                                                   List<ActivityFact> activities, LocalDate today) {
        return analyze(tasks, members, activities, List.of(), today);
    }
    public static List<Map<String,Object>> analyze(List<TaskFact> tasks, List<MemberFact> members,
                                                   List<ActivityFact> activities, List<ProcessFact> processes,
                                                   LocalDate today) {
        List<Map<String,Object>> out = new ArrayList<>();
        for (TaskFact t : tasks) {
            if (t.status() >= 2) continue;
            var successors = tasks.stream().filter(n -> n.status() == 0 && n.corePredecessors().contains(t.code())).toList();
            var impacted = downstream(tasks, t.code());
            long overdue = t.dueDate() == null ? 0 : ChronoUnit.DAYS.between(t.dueDate(), today);
            if (t.blocked() || (overdue > 0 && !successors.isEmpty())) {
                var affected = new ArrayList<TaskFact>(); affected.add(t); affected.addAll(impacted);
                var row = add(out, "TASK_BLOCKING", overdue >= OVERDUE_HIGH_DAYS ? "HIGH" : "MEDIUM",
                    t.code() + " 逾期且阻塞核心后续任务", "任务执行瓶颈",
                    List.of("tasks:" + t.code() + ".blocked=" + t.blocked(), "tasks:" + t.code() + ".due=" + t.dueDate(),
                        "overdue_days=" + overdue, "progress=" + t.progress() + "%",
                        "core_successors=" + successors.stream().map(TaskFact::code).toList()), affected, List.of(t.ownerId()),
                    "前置任务尚未交付；具体阻塞原因需与负责人核实",
                    "与 " + t.code() + " 负责人确认解除条件，优先交付后续任务所需最小接口/产物，再评估受影响任务排期",
                    impacted.isEmpty() ? null : Map.of("affectedSuccessorCount", impacted.size()), .9, true);
                row.put("impactChainLength", impacted.size());
            }
            if (t.statusSince() != null && t.status() == 1 && t.progress() < 100
                    && ChronoUnit.DAYS.between(t.statusSince(), today) >= WAIT_DAYS
                    && contains(t.title(), "审核", "测试", "验收", "review", "test")) {
                add(out, "PROCESS_DELAY", "MEDIUM", t.code() + " 测试/审核阶段持续时间偏长", "流程等待/审核积压",
                    List.of("task_logs:" + t.code() + ".status_since=" + t.statusSince(),
                        "days_in_status=" + ChronoUnit.DAYS.between(t.statusSince(), today), "progress=" + t.progress() + "%"),
                    List.of(t), List.of(t.ownerId()), "状态持续时间较长是复核信号，不能证明实际没有开展工作",
                    "核对 " + t.code() + " 等待的审核人、测试环境或输入，明确解除条件和下一次检查时间", null, .7, true);
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
                m.name() + " 计划负载超过当前项目容量", "成员负载不均",
                List.of("member=" + m.id(), "capacity=weeklyCapacity×projectWeeks=" + m.capacity(),
                    "allocated_hours=" + m.assigned(), "load_percent=" + Math.round(m.assigned() / m.capacity() * 100) + "%"),
                owned, List.of(m.id()), candidates.isEmpty() ? "没有同时满足技能/历史匹配及剩余容量的候选，不强行转移" : "存在低于70%负载且具有技能或历史依据的候选",
                candidates.isEmpty() ? "与 " + m.name() + " 复核未完成任务优先级和可拆分范围，先确认范围或排期调整" : "复核候选人的技能、历史经验与容量后，再申请转移列出的任务",
                candidates.isEmpty() ? null : Map.of("candidateCount", candidates.size()), .85, true);
            row.put("candidate_assignments", candidates);
            row.put("load", Map.of("allocatedHours", m.assigned(), "capacityHours", m.capacity(), "rate", m.assigned() / m.capacity()));
        }
        for (TaskFact t : tasks) {
            var linked = activities.stream().filter(a -> t.code().equals(a.taskCode())).toList();
            long fixes = linked.stream().filter(a -> "bugfix".equals(a.type()) || contains(a.title(), "修复", "fix", "回退")).count();
            long commits = linked.stream().filter(a -> "commit".equals(a.type())).count();
            if (t.regressions() < 2 && fixes < REWORK_EVENTS && commits < REWORK_EVENTS) continue;
            boolean corroborated = t.regressions() >= 2 || fixes >= REWORK_EVENTS;
            add(out, "REWORK_SIGNAL", corroborated ? "MEDIUM" : "LOW",
                t.code() + (corroborated ? " 存在重复回退/修复信号" : " 可能存在返工迹象（证据不足）"), "返工/重复修改信号",
                List.of("task_logs.status_regressions=" + t.regressions(), "bugfix_or_fix_events=" + fixes, "commit_events=" + commits,
                    "activity_record_ids=" + linked.stream().map(ActivityFact::id).toList()), List.of(t), List.of(t.ownerId()),
                corroborated ? "存在状态回退或修复类活动，原因仍需人工复核" : "只有提交次数，不能断言返工或需求变更原因",
                "抽样复核 " + t.code() + " 的关联修复、提交与状态历史，确认同一问题是否重复出现，再申请补充验收或回归检查", null,
                corroborated ? .75 : .3, corroborated);
        }
        if (!processes.isEmpty()) {
            var completed = processes.stream().filter(p -> p.createdAt() != null && p.reviewedAt() != null
                && !p.reviewedAt().isBefore(p.createdAt())).toList();
            if (!completed.isEmpty()) {
                double averageDays = completed.stream().mapToDouble(p -> Duration.between(p.createdAt(), p.reviewedAt()).toSeconds() / 86400.0).average().orElse(0);
                add(out, "PROCESS_REVIEW_CYCLE", averageDays >= 7 ? "MEDIUM" : "LOW", "已完成审核流程的平均等待时间", "流程等待/审核周期",
                    List.of("completed_review_records=" + completed.size(), "average_review_wait_days=" + String.format(Locale.ROOT, "%.1f", averageDays)),
                    List.of(), List.of(), "该值来自记录的创建与审核时间，不代表审核时间之外的全部流程耗时",
                    "对照项目约定的审核时限复核该周期，并优先处理超过时限的待办", Map.of("averageWaitDays", averageDays, "sampleCount", completed.size()), .85, true);
            }
            var waiting = processes.stream().filter(p -> p.reviewedAt() == null && p.createdAt() != null).toList();
            if (!waiting.isEmpty()) {
                var ages = waiting.stream().mapToDouble(p -> Math.max(0, Duration.between(p.createdAt(), today.atTime(23,59,59)).toSeconds()) / 86400.0).summaryStatistics();
                add(out, "PROCESS_QUEUE", ages.getAverage() >= 7 ? "MEDIUM" : "LOW", "存在待审核流程记录", "流程等待/审核积压",
                    List.of("pending_records=" + waiting.size(), "average_wait_days=" + String.format(Locale.ROOT, "%.1f", ages.getAverage())),
                    List.of(), List.of(), "存在已创建但尚未记录审核完成时间的流程记录", "确认审核负责人和处理时限，优先清理长期未处理记录",
                    Map.of("pendingCount", waiting.size(), "averageWaitDays", ages.getAverage()), .8, true);
            }
        }
        return out;
    }
    private static List<TaskFact> downstream(List<TaskFact> tasks, String code) {
        Map<String,TaskFact> byCode = new HashMap<>(); tasks.forEach(task -> byCode.put(task.code(), task));
        Set<String> visited = new LinkedHashSet<>(); Deque<String> queue = new ArrayDeque<>(); queue.add(code);
        while (!queue.isEmpty()) {
            String predecessor = queue.removeFirst();
            for (TaskFact task : tasks) if (task.status() < 2 && task.corePredecessors().contains(predecessor)
                    && !task.code().equals(code) && visited.add(task.code())) queue.addLast(task.code());
        }
        return visited.stream().map(byCode::get).filter(Objects::nonNull).toList();
    }
    private static boolean contains(String text, String... words) { for (String w : words) if (text != null && text.toLowerCase().contains(w.toLowerCase())) return true; return false; }
    public static Map<String,Object> backlogGrowth(int firstOpen, int latestOpen, LocalDate firstDate, LocalDate latestDate,
            double coverage, List<String> affectedTaskCodes) {
        int increase = latestOpen - firstOpen;
        var row = new LinkedHashMap<String,Object>();
        row.put("id", "BACKLOG_TREND"); row.put("type", "TASK_BACKLOG_GROWTH");
        row.put("severity", increase >= 5 ? "HIGH" : "MEDIUM");
        row.put("title", "历史待办积压上升"); row.put("summary", "按可还原的状态历史比较周期内未完成任务数");
        row.put("description", "历史待办任务从 " + firstOpen + " 项上升到 " + latestOpen + " 项");
        row.put("evidence", List.of("snapshot_start=" + firstDate + ":open_tasks=" + firstOpen,
            "snapshot_end=" + latestDate + ":open_tasks=" + latestOpen, "history_coverage=" + Math.round(coverage * 100) + "%"));
        row.put("reasoning", "可还原的历史状态显示未完成任务增加，但不单独证明具体原因");
        row.put("possibleCauses", List.of("需要结合阻塞、负载和依赖证据人工确认"));
        row.put("affectedStoryCodes", List.of()); row.put("affectedTaskCodes", affectedTaskCodes); row.put("affectedMemberIds", List.of());
        row.put("confidence", coverage >= .9 ? .8 : .65); row.put("confidenceLabel", coverage >= .9 ? "HIGH" : "MEDIUM");
        row.put("requiresHumanConfirmation", true); row.put("evidence_sufficient", coverage >= .7);
        row.put("quantifiedImpact", Map.of("backlogIncrease", increase, "fromOpenTasks", firstOpen, "toOpenTasks", latestOpen));
        row.put("recheckMetric", Map.of("metric", "open_task_count_change_over_28_days", "baseline", increase,
            "target", 0, "reviewWindowDays", 14, "reviewAt", "建议执行后14天复查"));
        row.put("recommendations", List.of(new LinkedHashMap<>(Map.of("id", "R_BACKLOG_TREND", "actionType", "REVIEW_OPTIMIZATION",
            "description", "逐项复核新增积压任务，确认阻塞原因并制定清理顺序", "reason", "历史待办快照显示积压增加",
            "expectedImpact", Map.of("backlogReductionTarget", increase), "targetTaskIds", affectedTaskCodes, "targetMemberIds", List.of(),
            "requiresApproval", true, "status", "suggested", "recheckMetric", row.get("recheckMetric")))));
        return row;
    }
    private static Map<String,Object> add(List<Map<String,Object>> out, String type, String severity, String title, String summary,
            List<String> evidence, List<TaskFact> tasks, List<Integer> members, String reasoning, String action,
            Map<String,Object> quantifiedImpact, double confidence, boolean confirmedEvidence) {
        var row = new LinkedHashMap<String,Object>();
        row.put("id", "B" + (out.size()+1)); row.put("type", type); row.put("severity", severity); row.put("title", title);
        row.put("summary", summary); row.put("description", title); row.put("evidence", evidence); row.put("reasoning", reasoning);
        row.put("possibleCauses", List.of(reasoning));
        row.put("affectedStoryCodes", tasks.stream().map(TaskFact::storyCode).filter(Objects::nonNull).filter(s -> !s.isBlank()).distinct().toList());
        row.put("affectedTaskCodes", tasks.stream().map(TaskFact::code).distinct().toList()); row.put("affectedMemberIds", members);
        row.put("confidence", confidence); row.put("confidenceLabel", confidence >= .75 ? "HIGH" : confidence >= .5 ? "MEDIUM" : "LOW");
        row.put("requiresHumanConfirmation", true); row.put("quantifiedImpact", quantifiedImpact);
        row.put("evidence_sufficient", confirmedEvidence);
        Map<String,Object> metric = recheckMetric(type, quantifiedImpact);
        row.put("recheckMetric", metric);
        row.put("recommendations", List.of(new LinkedHashMap<>(Map.of("id", "R" + (out.size()+1), "actionType", "REVIEW_OPTIMIZATION",
            "description", action, "reason", String.join("; ", evidence), "expectedImpact", quantifiedImpact == null ? "无法量化" : quantifiedImpact,
            "targetTaskIds", tasks.stream().map(TaskFact::code).distinct().toList(), "targetMemberIds", members,
            "requiresApproval", true, "status", "suggested", "recheckMetric", metric))));
        out.add(row); return row;
    }
    private static Map<String,Object> recheckMetric(String type, Map<String,Object> impact) {
        var metric = new LinkedHashMap<String,Object>();
        metric.put("reviewWindowDays", 14);
        metric.put("metric", "same_bottleneck_rule_active");
        metric.put("baseline", 1);
        metric.put("baselineEvidence", impact == null ? "该规则已触发" : impact);
        metric.put("target", 0);
        metric.put("reviewAt", "建议执行后14天复查");
        return metric;
    }
    public static Map<String,Object> actualVariance(Integer plannedHours, Integer actualHours) {
        if (plannedHours == null || actualHours == null) return Map.of("available", false, "varianceHours", 0, "varianceRate", 0,
            "reason", "缺少计划或实际工时，无法计算偏差");
        int variance = actualHours - plannedHours;
        double rate = plannedHours == 0 ? 0 : variance / (double) plannedHours;
        return Map.of("available", true, "plannedHours", plannedHours, "actualHours", actualHours,
            "varianceHours", variance, "varianceRate", rate);
    }
}
