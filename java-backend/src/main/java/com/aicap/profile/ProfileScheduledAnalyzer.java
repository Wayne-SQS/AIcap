package com.aicap.profile;

import com.aicap.entity.User;
import com.aicap.mapper.UserMapper;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.util.List;

/**
 * 画像智能体定时触发(任务2·自主性缺口之一):每周一 09:00 自动对最近 7 天做一次正式分析。
 *
 * 默认关闭(profile-agent.scheduled.enabled=false),原因:
 * 1. 未配置 PROFILE_LLM_API_KEY 时跑的是规则引擎,意义有限;
 * 2. 正式分析会调用 LLM 产生费用,默认静默开启不符合"克制"原则。
 * 开启方式:环境变量 PROFILE_AGENT_SCHEDULED_ENABLED=true(或 application.yml 覆盖)。
 * 触发者:取库中第一个 admin/owner 作为 requested_by(无人可触发时跳过并告警)。
 *
 * 事件触发(任务完成/PR 合并/连续 N 天无活动)与主动澄清(askMember)依赖
 * 外部数据源(GitHub 同步,US34)与 IM 通道,当前不可用,预留扩展位,不造假。
 */
@Component
@RequiredArgsConstructor
public class ProfileScheduledAnalyzer {

    private static final Logger log = LoggerFactory.getLogger(ProfileScheduledAnalyzer.class);

    private final com.aicap.service.ProfileAgentService service;
    private final UserMapper userMapper;

    @Value("${profile-agent.scheduled.enabled:false}")
    private boolean enabled;

    /** Spring 6 段 cron:每周一 09:00(可被 profile-agent.scheduled.cron 覆盖) */
    @Scheduled(cron = "${profile-agent.scheduled.cron:0 0 9 * * MON}")
    public void weeklyAutoAnalysis() {
        if (!enabled) return;
        User actor = actor();
        if (actor == null) {
            log.warn("[profile-agent] 定时分析未执行:库中无 admin/owner 用户可作为触发者");
            return;
        }
        LocalDate today = LocalDate.now();
        LocalDate start = today.minusDays(6);
        try {
            service.runAnalysis(start, today, actor);
            log.info("[profile-agent] 每周自动分析完成: {} ~ {}", start, today);
        } catch (Exception e) {
            log.error("[profile-agent] 每周自动分析失败: {}", e == null || e.getMessage() == null
                    ? "unknown" : e.getMessage());
        }
    }

    private User actor() {
        List<User> admins = userMapper.selectList(new QueryWrapper<User>()
                .in("role", List.of("admin", "owner")).last("LIMIT 1"));
        return admins.isEmpty() ? null : admins.get(0);
    }
}
