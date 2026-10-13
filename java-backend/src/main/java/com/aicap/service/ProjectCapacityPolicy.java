package com.aicap.service;

import com.aicap.entity.Project;

import java.time.temporal.ChronoUnit;

/** Capacity policy: explicit weekly capacity, then member default, then legacy compatibility. */
public final class ProjectCapacityPolicy {
    private static final int LEGACY_WEEKS = 6;
    private static final int[] DEFAULT_WEEKLY_CAPACITIES = {15, 14, 16, 13, 12};

    private ProjectCapacityPolicy() { }

    public static int projectWeeks(Project project) {
        if (project != null && project.getStartDate() != null && project.getEndDate() != null
                && !project.getEndDate().isBefore(project.getStartDate())) {
            long days = ChronoUnit.DAYS.between(project.getStartDate(), project.getEndDate()) + 1;
            return Math.max(1, (int) Math.ceil(days / 7.0));
        }
        if (project != null && project.getTotalSprints() != null && project.getTotalSprints() > 0) {
            return project.getTotalSprints() * 2;
        }
        return LEGACY_WEEKS;
    }

    public static int defaultWeeklyCapacity(Integer userId) {
        if (userId == null || userId <= 0) return 0;
        int index = Math.floorMod(userId - 1, DEFAULT_WEEKLY_CAPACITIES.length);
        return DEFAULT_WEEKLY_CAPACITIES[index];
    }

    public static double weeklyCapacity(Integer explicitWeeklyCapacity, Integer legacySixWeekCapacity, Integer userId) {
        // Zero is an intentional unavailable week, not a missing value.
        if (explicitWeeklyCapacity != null && explicitWeeklyCapacity >= 0) return explicitWeeklyCapacity;
        int fallback = defaultWeeklyCapacity(userId);
        if (fallback > 0) return fallback;
        if (legacySixWeekCapacity != null && legacySixWeekCapacity > 0) return legacySixWeekCapacity / (double) LEGACY_WEEKS;
        return 0;
    }

    public static double totalCapacity(Integer explicitWeeklyCapacity, Integer legacySixWeekCapacity,
                                       Integer userId, Project project) {
        return weeklyCapacity(explicitWeeklyCapacity, legacySixWeekCapacity, userId) * projectWeeks(project);
    }
}
