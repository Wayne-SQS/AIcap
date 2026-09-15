package com.aicap.controller;

import com.aicap.dto.ProfileAgentDtos;
import com.aicap.entity.User;
import com.aicap.profile.GitHubActivitySyncService;
import com.aicap.security.Roles;
import com.aicap.service.ProfileAgentService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;

/**
 * AI 任务提交与成员能力画像智能体接口。
 *
 * 读:任何登录用户;录入活动/修正难度:登录成员;触发分析:admin/owner(member 可看本人分析)。
 * 与会议智能体一致的 REST 风格;失败留痕于 profile_agent_runs。
 */
@RestController
@RequestMapping("/api/profile-agent")
@RequiredArgsConstructor
public class ProfileAgentController {

    private final ProfileAgentService service;
    private final GitHubActivitySyncService githubSync;

    /** 录入一条活动事实(commit/pr/review/bugfix/task_done/note) */
    @PostMapping("/activities")
    public ProfileAgentDtos.ActivityOut addActivity(@Valid @RequestBody ProfileAgentDtos.ActivityIn in) {
        User actor = Roles.writer();
        return service.addActivity(in, actor);
    }

    /** 批量导入活动事实(单次 ≤200 条;source=import) */
    @PostMapping("/activities/import")
    public List<ProfileAgentDtos.ActivityOut> importActivities(
            @Valid @RequestBody List<ProfileAgentDtos.ActivityIn> items) {
        User actor = Roles.writer();
        return service.importActivities(items, actor);
    }

    /** 贡献活动热力图(按日聚合,前端画绿格子;文档 4.10) */
    @GetMapping("/heatmap")
    public List<Map<String, Object>> heatmap(
            @RequestParam(required = false) Integer userId,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate start,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate end) {
        Roles.any();
        return service.heatmap(userId, start, end);
    }

    /** 活动列表(可按成员/时间范围过滤) */
    @GetMapping("/activities")
    public List<ProfileAgentDtos.ActivityOut> activities(
            @RequestParam(required = false) Integer userId,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate start,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate end) {
        Roles.any();
        return service.listActivities(userId, start, end);
    }

    /** 任务难度评估(AI 评估 + 人工覆盖优先) */
    @GetMapping("/difficulty")
    public List<ProfileAgentDtos.DifficultyOut> difficulty() {
        Roles.any();
        return service.assessDifficulty(null, null);
    }

    /** 人工修正任务难度(admin/owner) */
    @PatchMapping("/difficulty/{taskId}")
    public ProfileAgentDtos.DifficultyOut overrideDifficulty(@PathVariable String taskId,
                                                             @Valid @RequestBody ProfileAgentDtos.DifficultyIn in) {
        Roles.reviewer();
        return service.overrideDifficulty(taskId, in);
    }

    /** 时间范围对比分析(本期 vs 上期,文档 4.9) */
    @GetMapping("/analysis/compare")
    public Map<String, Object> compare(@RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate prevStart,
                                       @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate prevEnd,
                                       @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate start,
                                       @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate end) {
        Roles.any();
        return service.comparePeriods(prevStart, prevEnd, start, end);
    }

    /** 只读预览分析结果(不落运行记录;正式分析用 POST /analysis/run) */
    @GetMapping("/analysis")
    public Map<String, Object> analysis(@RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate start,
                                        @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate end) {
        Roles.any();
        return service.analyze(start, end);
    }

    /**
     * 触发整包分析(工作事实/贡献/画像/团队风险),结果留痕可重试。
     *
     * <p><b>userId 可选</b>:只评估这一名成员(成员完成任务后由前端传入,避免每次都把全团队
     * 5 个人重新 LLM 评估一遍)。不传 = 全团队正式分析(人工点「运行分析」/风险送审走这里)。
     * 单人范围下不调 LLM 团队风险归因 —— 喂不完整的团队事实会得出错的团队结论,改为规则引擎,
     * 结果里以 scope 字段如实标出本次范围。
     */
    @PostMapping("/analysis/run")
    public Map<String, Object> runAnalysis(@RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate start,
                                           @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate end,
                                           @RequestParam(required = false) Integer userId) {
        // 触发分析会落库(运行记录/画像快照)并消耗 LLM 额度 → 只读角色 viewer 必须拒绝。
        // 此前 Roles.any() 允许 viewer,与本类注释"触发分析:admin/owner(member 可看本人分析)"及 US02 只读约束冲突。
        User actor = Roles.writer();
        return service.runAnalysis(start, end, actor, userId);
    }

    /** 分析运行记录(最近 20 条,失败留痕) */
    @GetMapping("/runs")
    public List<ProfileAgentDtos.RunOut> runs() {
        Roles.any();
        return service.listRuns();
    }

    /** 把团队风险提交为待审建议(进统一 AI 建议审核中心,admin/owner 审核后才执行) */
    @PostMapping("/risks/submit-suggestions")
    public List<String> submitRiskSuggestions(
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate start,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate end,
            @RequestParam(required = false) String agent) {
        User actor = Roles.writer();
        return service.submitRiskSuggestions(start, end, agent, actor);
    }

    /** 能力画像快照(按时间查看画像变化,文档 4.7) */
    @GetMapping("/snapshots")
    public List<Map<String, Object>> snapshots(@RequestParam(required = false) Integer userId) {
        Roles.any();
        return service.snapshots(userId);
    }

    /** 画像快照趋势(相邻快照对比,给出画像变化原因;文档 4.7) */
    @GetMapping("/snapshots/trend")
    public List<Map<String, Object>> snapshotTrend(@RequestParam(required = false) Integer userId) {
        Roles.any();
        return service.snapshotTrend(userId);
    }

    /** 提交画像纠正(本人或 admin/owner;文档 4.7 允许成员纠正错误信息) */
    @PostMapping("/corrections")
    public Map<String, Object> addCorrection(@RequestParam Integer userId,
                                             @RequestParam String field,
                                             @RequestParam String correctedValue,                                             @RequestParam(required = false) String reason) {
        User actor = Roles.writer();
        return service.addCorrection(userId, field, correctedValue, reason, actor);
    }

    /** 画像纠正历史(最新在前) */
    @GetMapping("/corrections")
    public List<Map<String, Object>> corrections(@RequestParam(required = false) Integer userId) {
        Roles.any();
        return service.corrections(userId);
    }

    /** GitHub 同步状态(US34:仓库绑定与成员映射的当前配置状态) */
    @GetMapping("/github/status")
    public Map<String, Object> githubStatus() {
        Roles.any();
        return githubSync.status();
    }

    /**
     * 手动触发 GitHub 活动同步(未配置时返回未配置状态,不发起外部请求)。
     *
     * <p>权限与其余写接口一致(Roles.writer():admin/owner/member),不是 reviewer():
     * 「四人成员中任意人完成任务 → 在自己任务上点已完成 → 触发智能体读仓库」这条链上,
     * 触发者就是普通成员(member),卡在 admin/owner 会让成员点完后同步不动。
     * 只读查看者(viewer)仍然 403。
     */
    @PostMapping("/github/sync")
    public Map<String, Object> githubSync(
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate start,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate end) {
        Roles.writer();
        return githubSync.sync(start, end);
    }
}
