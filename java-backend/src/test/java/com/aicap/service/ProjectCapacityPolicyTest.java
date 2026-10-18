package com.aicap.service;

import com.aicap.entity.Project;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

class ProjectCapacityPolicyTest {

    @Test
    void explicitWeeklyCapacityScalesAcrossSixWeekProject() {
        Project project = project(null, null, 3);
        assertEquals(90, ProjectCapacityPolicy.totalCapacity(15, 60, 1, project));
        assertEquals(84, ProjectCapacityPolicy.totalCapacity(14, 48, 2, project));
        assertEquals(96, ProjectCapacityPolicy.totalCapacity(16, 60, 3, project));
        assertEquals(78, ProjectCapacityPolicy.totalCapacity(13, 54, 4, project));
    }

    @Test
    void projectWeeksFollowDatesAndAlsoSupportFourAndEightWeekSprintPlans() {
        assertEquals(4, ProjectCapacityPolicy.projectWeeks(project(null, null, 2)));
        assertEquals(8, ProjectCapacityPolicy.projectWeeks(project(null, null, 4)));
        assertEquals(4, ProjectCapacityPolicy.projectWeeks(project("2026-01-01", "2026-01-28", 3)));
    }

    @Test
    void legacyP003AndP004MembersUseStableDefaultsInsteadOfHistoricalTotals() {
        Project sixWeeks = project(null, null, 3);
        Project fourWeeks = project(null, null, 2);
        Project eightWeeks = project(null, null, 4);
        assertEquals(15, ProjectCapacityPolicy.weeklyCapacity(null, 60, 1));
        assertEquals(14, ProjectCapacityPolicy.weeklyCapacity(null, 48, 2));
        assertEquals(16, ProjectCapacityPolicy.weeklyCapacity(null, 60, 3));
        assertEquals(13, ProjectCapacityPolicy.weeklyCapacity(null, 54, 4));
        assertEquals(90, ProjectCapacityPolicy.totalCapacity(null, 60, 1, sixWeeks));
        assertEquals(84, ProjectCapacityPolicy.totalCapacity(null, 48, 2, sixWeeks));
        assertEquals(96, ProjectCapacityPolicy.totalCapacity(null, 60, 3, sixWeeks));
        assertEquals(78, ProjectCapacityPolicy.totalCapacity(null, 54, 4, sixWeeks));
        assertEquals(56, ProjectCapacityPolicy.totalCapacity(null, 48, 2, fourWeeks));
        assertEquals(128, ProjectCapacityPolicy.totalCapacity(null, 60, 3, eightWeeks));
    }

    @Test
    void explicitWeeklyCapacityWinsAndLegacyIsOnlyNoIdentityFallback() {
        assertEquals(18, ProjectCapacityPolicy.weeklyCapacity(18, 60, 1));
        assertEquals(0, ProjectCapacityPolicy.weeklyCapacity(0, 60, 1));
        assertEquals(10, ProjectCapacityPolicy.weeklyCapacity(null, 60, null));
    }

    @Test
    void missingCapacityUsesStableMemberSpecificDefaultsAndOneHundredPercentUsesTotal() {
        double first = ProjectCapacityPolicy.weeklyCapacity(null, null, 1);
        double second = ProjectCapacityPolicy.weeklyCapacity(null, null, 2);
        assertNotEquals(first, second);
        double total = ProjectCapacityPolicy.totalCapacity(null, null, 1, project(null, null, 3));
        assertEquals(100, total / total * 100);
    }

    private Project project(String start, String end, int sprints) {
        Project project = new Project();
        project.setTotalSprints(sprints);
        if (start != null) project.setStartDate(LocalDate.parse(start));
        if (end != null) project.setEndDate(LocalDate.parse(end));
        return project;
    }
}
