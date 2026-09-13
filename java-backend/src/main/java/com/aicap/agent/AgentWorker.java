package com.aicap.agent;

import jakarta.annotation.PreDestroy;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

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

    private volatile boolean running;
    private Thread thread;

    @EventListener(ApplicationReadyEvent.class)
    public synchronized void startOnReady() {
        if (!props.isAgentWorkerEnabled()) {
            log.info("会议 Agent worker 未启用(AICAP_AGENT_WORKER_ENABLED=false);run 将保持 queued");
            return;
        }
        if (running) return;
        running = true;
        thread = new Thread(this::loop, "meeting-agent");
        thread.setDaemon(true);
        thread.start();
        log.info("会议 Agent worker 已启动");
    }

    private void loop() {
        while (running) {
            boolean worked = false;
            try {
                worked = jobs.runNext();
            } catch (Exception e) {
                // DB 抖动重试;提供方/分析错误已记录到 run 行,不热循环
                log.warn("meeting-agent worker step error: {}", e.toString());
            }
            try {
                Thread.sleep(worked ? 200 : 1000);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                if (running) break;
            }
        }
    }

    @PreDestroy
    public synchronized void stop() {
        running = false;
        if (thread != null) {
            thread.interrupt();
            try {
                thread.join(2000);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            thread = null;
        }
    }
}
