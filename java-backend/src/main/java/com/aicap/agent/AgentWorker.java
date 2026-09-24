package com.aicap.agent;

import jakarta.annotation.PreDestroy;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;
import com.aicap.planning.PlanningAgentJobs;
import com.aicap.generation.ProjectGenerationJobs;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.BooleanSupplier;

/**
 * 会议 Agent 后台工作线程(对齐 FastAPI meeting_agent/jobs.py Worker):
 * 定时消费 queued run;每步之间短暂休眠,DB 故障退避重试,模型/分析错误记录在 run 上而非热循环。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class AgentWorker {

    private final AgentJobs jobs;
    private final AgentProperties props;
    private final PlanningAgentJobs planningJobs;
    private final ProjectGenerationJobs projectGenerationJobs;

    private volatile boolean running;
    private final Map<String, Thread> threads = new ConcurrentHashMap<>();
    private final Map<String, WorkerState> states = new ConcurrentHashMap<>();

    @EventListener(ApplicationReadyEvent.class)
    public synchronized void startOnReady() {
        if (!props.isAgentWorkerEnabled()) {
            log.info("会议 Agent worker 未启用(AICAP_AGENT_WORKER_ENABLED=false);run 将保持 queued");
            return;
        }
        if (running) return;
        running = true;
        startWorker("meeting-agent", jobs::runNext);
        startWorker("planning-agent", planningJobs::runNext);
        startWorker("project-generation", projectGenerationJobs::runNext);
        log.info("Agent workers 已启动: meeting-agent, planning-agent, project-generation");
    }

    private void startWorker(String name, BooleanSupplier step) {
        WorkerState state = new WorkerState();
        states.put(name, state);
        Thread thread = new Thread(() -> loop(name, step, state), name);
        thread.setDaemon(true);
        threads.put(name, thread);
        thread.start();
    }

    private void loop(String name, BooleanSupplier step, WorkerState state) {
        state.active = true;
        while (running) {
            boolean worked = false;
            try {
                state.lastPollEpochMs = System.currentTimeMillis();
                worked = step.getAsBoolean();
                if (worked) state.lastWorkEpochMs = System.currentTimeMillis();
                state.lastError = null;
            } catch (Exception e) {
                state.lastError = e.getClass().getSimpleName() + ": " + String.valueOf(e.getMessage());
                state.lastErrorEpochMs = System.currentTimeMillis();
                log.warn("{} worker step error: {}", name, e.toString());
            }
            try {
                Thread.sleep(worked ? 200 : 1000);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                if (running) break;
            }
        }
        state.active = false;
    }

    public Map<String, Object> healthSnapshot() {
        Map<String, Object> workers = new LinkedHashMap<>();
        for (String name : java.util.List.of("meeting-agent", "planning-agent", "project-generation")) {
            WorkerState state = states.get(name);
            Map<String, Object> detail = new LinkedHashMap<>();
            Thread thread = threads.get(name);
            detail.put("active", running && thread != null && thread.isAlive() && state != null && state.active);
            if (state != null && state.lastPollEpochMs > 0) detail.put("last_poll", Instant.ofEpochMilli(state.lastPollEpochMs).toString());
            if (state != null && state.lastWorkEpochMs > 0) detail.put("last_work", Instant.ofEpochMilli(state.lastWorkEpochMs).toString());
            if (state != null && state.lastError != null) {
                detail.put("last_error", state.lastError);
                detail.put("last_error_at", Instant.ofEpochMilli(state.lastErrorEpochMs).toString());
            }
            workers.put(name, detail);
        }
        return workers;
    }

    @PreDestroy
    public synchronized void stop() {
        running = false;
        for (Thread thread : threads.values()) thread.interrupt();
        for (Thread thread : threads.values()) {
            try {
                thread.join(2000);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            }
        }
        threads.clear();
    }

    private static final class WorkerState {
        private volatile boolean active;
        private volatile long lastPollEpochMs;
        private volatile long lastWorkEpochMs;
        private volatile long lastErrorEpochMs;
        private volatile String lastError;
    }
}
