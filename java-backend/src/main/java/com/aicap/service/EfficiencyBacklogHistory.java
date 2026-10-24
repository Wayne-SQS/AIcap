package com.aicap.service;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/** Reconstructs weekly open-task snapshots only where task creation/status history supports it. */
final class EfficiencyBacklogHistory {
    record Change(LocalDateTime at, Integer oldStatus, int newStatus) {}
    record TaskState(String code, int currentStatus, LocalDateTime createdAt, List<Change> changes) {}
    record Snapshot(LocalDate date, int openTasks, int knownTasks, int totalTasks) {
        double coverage() { return totalTasks == 0 ? 1d : knownTasks / (double) totalTasks; }
    }

    private EfficiencyBacklogHistory() {}

    static List<Snapshot> weekly(List<TaskState> tasks, LocalDate today, int weeks) {
        List<Snapshot> result = new ArrayList<>();
        for (int offset = weeks - 1; offset >= 0; offset--) {
            LocalDate date = today.minusWeeks(offset);
            LocalDateTime end = date.atTime(23, 59, 59);
            int open = 0, known = 0, total = 0;
            for (TaskState task : tasks) {
                if (task.createdAt() == null) { total++; continue; }
                if (task.createdAt().isAfter(end)) continue;
                total++;
                List<Change> changes = task.changes().stream().sorted(Comparator.comparing(Change::at)).toList();
                Change before = null, after = null;
                for (Change change : changes) {
                    if (!change.at().isAfter(end)) before = change;
                    else if (after == null) after = change;
                }
                Integer status = date.equals(today) ? task.currentStatus() : before != null ? before.newStatus()
                    : after != null ? after.oldStatus()
                    : null;
                if (status == null) continue;
                known++;
                if (status < 2) open++;
            }
            result.add(new Snapshot(date, open, known, total));
        }
        return result;
    }

    static List<String> newlyOpenTaskCodes(List<TaskState> tasks, LocalDate from, LocalDate to) {
        LocalDateTime start = from.atTime(23, 59, 59), end = to.atTime(23, 59, 59);
        List<String> result = new ArrayList<>();
        for (TaskState task : tasks) {
            if (task.createdAt() == null || task.createdAt().isAfter(end) || task.currentStatus() >= 2) continue;
            if (task.createdAt().isAfter(start)) { result.add(task.code()); continue; }
            Integer beforeStatus = null;
            for (Change change : task.changes().stream().sorted(Comparator.comparing(Change::at)).toList())
                if (!change.at().isAfter(start)) beforeStatus = change.newStatus();
            if (beforeStatus == null) {
                var firstAfter = task.changes().stream().filter(change -> change.at().isAfter(start))
                    .min(Comparator.comparing(Change::at)).orElse(null);
                if (firstAfter != null) beforeStatus = firstAfter.oldStatus();
            }
            if (beforeStatus != null && beforeStatus >= 2) result.add(task.code());
        }
        return List.copyOf(result);
    }
}
