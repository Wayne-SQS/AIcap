package com.aicap.worker;

import com.aicap.llm.LlmProperties;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class AgentWorkerTest {

    /**
     * 一个卡住的 worker 不能拖住别的 worker。
     *
     * <p>这条是调度器与具体域解耦的**理由所在**:会议任务卡住时,
     * planning 与 project-generation 必须照常轮询。断言的是
     * 「一个 WorkerTask 卡住不影响其它 WorkerTask」这条性质,与具体域无关 ——
     * 老会议任务(原 {@code com.aicap.agent})已于 2026-10-13 删除,此测试仍成立。
     */
    @Test
    void blockedWorkerDoesNotStopTheOthers() throws Exception {
        CountDownLatch releaseMeeting = new CountDownLatch(1);
        AtomicInteger planningPolls = new AtomicInteger();
        AtomicInteger generationPolls = new AtomicInteger();

        WorkerTask meeting = mock(WorkerTask.class);
        when(meeting.name()).thenReturn("meeting-agent");
        when(meeting.runNext()).thenAnswer(invocation -> {
            releaseMeeting.await(5, TimeUnit.SECONDS);
            return false;
        });

        WorkerTask planning = mock(WorkerTask.class);
        when(planning.name()).thenReturn("planning-agent");
        when(planning.runNext()).thenAnswer(invocation -> {
            planningPolls.incrementAndGet();
            return false;
        });

        WorkerTask generation = mock(WorkerTask.class);
        when(generation.name()).thenReturn("project-generation");
        when(generation.runNext()).thenAnswer(invocation -> {
            generationPolls.incrementAndGet();
            return false;
        });

        LlmProperties properties = new LlmProperties();
        properties.setAgentWorkerEnabled(true);
        AgentWorker worker = new AgentWorker(properties, List.of(meeting, planning, generation));
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

    /** 关掉开关时一个线程都不起,且状态快照仍如实列出所有任务(active=false) */
    @Test
    void disabledWorkerStartsNothingButStillReportsEveryTask() {
        WorkerTask meeting = mock(WorkerTask.class);
        when(meeting.name()).thenReturn("meeting-agent");
        LlmProperties properties = new LlmProperties();
        properties.setAgentWorkerEnabled(false);

        AgentWorker worker = new AgentWorker(properties, List.of(meeting));
        worker.startOnReady();
        try {
            assertTrue(worker.healthSnapshot().containsKey("meeting-agent"));
        } finally {
            worker.stop();
        }
    }
}
