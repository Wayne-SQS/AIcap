package com.aicap.worker;

import com.aicap.llm.LlmProperties;
import jakarta.annotation.PreDestroy;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 后台工作线程调度器:定时轮询注册进来的 {@link WorkerTask},每步之间短暂休眠,
 * DB 故障退避重试,任务自身的错误记录在 run 上而非热循环。
 *
 * <p><b>本类不认识任何具体域</b> —— 会议 / planning / project-generation 各自实现
 * {@link WorkerTask} 并注册成 Bean,这里只按 {@code name()} 起线程、出状态。
 * 这样 {@code com.aicap.agent} 整体退役时只会少一个任务,
 * 不会连累另外两条现行功能的后台消费。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class AgentWorker {

    private final LlmProperties props;
    private final List<WorkerTask> tasks;

    private volatile boolean running;
    private final Map<String, Thread> threads = new ConcurrentHashMap<>();
    private final Map<String, WorkerState> states = new ConcurrentHashMap<>();

    @EventListener(ApplicationReadyEvent.class)
    public synchronized void startOnReady() {
        if (!props.isAgentWorkerEnabled()) {
            log.info("Agent workers 未启用(AICAP_AGENT_WORKER_ENABLED=false);run 将保持 queued");
            return;
        }
        if (running) return;
        running = true;
        for (WorkerTask task : orderedTasks()) {
            startWorker(task);
        }
        log.info("Agent workers 已启动: {}", orderedTasks().stream().map(WorkerTask::name).toList());
    }

    private void startWorker(WorkerTask task) {
        String name = task.name();
        WorkerState state = new WorkerState();
        states.put(name, state);
        Thread thread = new Thread(() -> loop(name, task::runNext, state), name);
        thread.setDaemon(true);
        threads.put(name, thread);
        thread.start();
    }

    private void loop(String name, java.util.function.BooleanSupplier step, WorkerState state) {
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
        for (WorkerTask task : orderedTasks()) {
            String name = task.name();
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

    /** 按 name 排序:注入顺序由 Spring 决定,而状态快照的键序应当稳定 */
    private List<WorkerTask> orderedTasks() {
        return tasks.stream().sorted(Comparator.comparing(WorkerTask::name)).toList();
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
