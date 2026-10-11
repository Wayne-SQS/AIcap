package com.aicap.agent;

import com.aicap.generation.ProjectGenerationJobs;
import com.aicap.planning.PlanningAgentJobs;
import org.junit.jupiter.api.Test;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class AgentWorkerTest {
    @Test
    void blockedMeetingWorkerDoesNotStopPlanningOrGeneration() throws Exception {
        AgentJobs meeting = mock(AgentJobs.class);
        PlanningAgentJobs planning = mock(PlanningAgentJobs.class);
        ProjectGenerationJobs generation = mock(ProjectGenerationJobs.class);
        AgentProperties properties = new AgentProperties();
        properties.setAgentWorkerEnabled(true);

        CountDownLatch releaseMeeting = new CountDownLatch(1);
        AtomicInteger planningPolls = new AtomicInteger();
        AtomicInteger generationPolls = new AtomicInteger();
        when(meeting.runNext()).thenAnswer(invocation -> {
            releaseMeeting.await(5, TimeUnit.SECONDS);
            return false;
        });
        when(planning.runNext()).thenAnswer(invocation -> {
            planningPolls.incrementAndGet();
            return false;
        });
        when(generation.runNext()).thenAnswer(invocation -> {
            generationPolls.incrementAndGet();
            return false;
        });

        AgentWorker worker = new AgentWorker(meeting, properties, planning, generation);
        try {
            worker.startOnReady();
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(3);
            while ((planningPolls.get() == 0 || generationPolls.get() == 0) && System.nanoTime() < deadline) {
                Thread.sleep(20);
            }
            assertTrue(planningPolls.get() > 0, "planning worker should poll independently");
            assertTrue(generationPolls.get() > 0, "generation worker should poll independently");
        } finally {
            releaseMeeting.countDown();
            worker.stop();
        }
    }
}
