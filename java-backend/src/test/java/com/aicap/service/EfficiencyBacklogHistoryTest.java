package com.aicap.service;

import org.junit.jupiter.api.Test;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

class EfficiencyBacklogHistoryTest {
    @Test void weeklySnapshotsReplayOnlyRecordedCreationAndStatusTransitions() {
        LocalDate today = LocalDate.of(2026, 10, 24);
        var task = new EfficiencyBacklogHistory.TaskState("T01", 2, today.minusWeeks(5).atTime(9, 0), List.of(
            new EfficiencyBacklogHistory.Change(today.minusWeeks(5).atTime(9, 0), null, 0),
            new EfficiencyBacklogHistory.Change(today.minusWeeks(2).atTime(9, 0), 0, 1),
            new EfficiencyBacklogHistory.Change(today.minusWeeks(1).atTime(9, 0), 1, 2)));
        var snapshots = EfficiencyBacklogHistory.weekly(List.of(task), today, 5);
        assertEquals(List.of(1, 1, 1, 0, 0), snapshots.stream().map(EfficiencyBacklogHistory.Snapshot::openTasks).toList());
        assertTrue(snapshots.stream().allMatch(snapshot -> snapshot.coverage() == 1d));
    }

    @Test void missingCreationHistoryCountsAsUnknownInsteadOfGuessing() {
        var untracked = new EfficiencyBacklogHistory.TaskState("T02", 0, null, List.of());
        var snapshots = EfficiencyBacklogHistory.weekly(List.of(untracked), LocalDate.of(2026, 10, 24), 5);
        assertTrue(snapshots.stream().allMatch(snapshot -> snapshot.openTasks() == 0 && snapshot.coverage() == 0d));
    }
}
