package com.aicap.generation;

import com.aicap.llm.LlmProperties;
import com.aicap.llm.ModelClient;
import com.aicap.common.AgentError;
import com.aicap.entity.ProjectGenerationEvent;
import com.aicap.entity.ProjectGenerationRun;
import com.aicap.mapper.ProjectGenerationEventMapper;
import com.aicap.mapper.ProjectGenerationRunMapper;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.UpdateWrapper;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

@Slf4j
@Component
@RequiredArgsConstructor
public class ProjectGenerationJobs {
    private final ProjectGenerationRunMapper runs;
    private final ProjectGenerationEventMapper events;
    private final ProjectGenerationRunner runner;
    private final ProjectGenerationValidator validator;
    private final ModelClient client;
    private final LlmProperties props;
    private final ObjectMapper mapper;
    private volatile String currentStage = "idle";
    private volatile String currentStageName = "";
    private final LocalDateTime applicationStartedAt = LocalDateTime.now();

    /**
     * 租约时长(秒):须大于最坏运行时间(MAX_STEPS 6 × 单次模型超时 45s = 270s),
     * 对齐 planning / 会议侧的 300s。租约短于实际运行时间会让别的进程抢走还在跑的 run。
     */
    public static final long LEASE_SECONDS = 300;
    private static final String INTERRUPTED_MESSAGE = "生成进程中断或超时，可重试";

    /**
     * 过期 running(租约超时)→ failed + interrupted 事件(读/写路由进入时也会先调用)。
     * 判定:① 有租约且已到期;② 无租约且 updated_at 也已陈旧(补列前留下的孤儿)。
     * 对无租约要留宽限期 —— 导入路径在请求线程里也会置 running,一律当过期会误杀进行中的导入
     * (见 {@code ProjectGenerationService#importFile})。
     */
    public void expireRuns() {
        LocalDateTime now = LocalDateTime.now();
        List<ProjectGenerationRun> stale = runs.selectList(new QueryWrapper<ProjectGenerationRun>()
                .eq("status", "running")
                .and(w -> w.lt("lease_until", now)
                        .or()
                        .isNull("lease_until")
                        .lt("updated_at", now.minusSeconds(LEASE_SECONDS))));
        for (ProjectGenerationRun run : stale) {
            int claimed = runs.update(null, new UpdateWrapper<ProjectGenerationRun>()
                    .set("status", "failed")
                    .set("worker_token", null)
                    .set("lease_until", null)
                    .set("error_code", "interrupted")
                    .set("error_message", INTERRUPTED_MESSAGE)
                    .set("updated_at", now)
                    .eq("id", run.getId())
                    .eq("status", "running")
                    .and(w -> w.lt("lease_until", now)
                            .or()
                            .isNull("lease_until")
                            .lt("updated_at", now.minusSeconds(LEASE_SECONDS))));
            if (claimed > 0) event(run, "failed", INTERRUPTED_MESSAGE);
        }
    }

    public boolean runNext() {
        expireRuns();
        ProjectGenerationRun run = runs.selectOne(new QueryWrapper<ProjectGenerationRun>()
                .eq("status", "queued").orderByAsc("created_at").last("LIMIT 1"));
        if (run == null) return false;
        String token = UUID.randomUUID().toString();
        int claimed = runs.update(null, new UpdateWrapper<ProjectGenerationRun>()
                .set("status", "running")
                .set("worker_token", token)
                .set("lease_until", LocalDateTime.now().plusSeconds(LEASE_SECONDS))
                .set("updated_at", LocalDateTime.now())
                .eq("id", run.getId()).eq("status", "queued"));
        if (claimed == 0) return true;
        stage(run, "running", "开始生成项目规划草案");
        try {
            currentStageName = "";
            stage(run, "generation_started", "开始调用模型生成规划");
            event(run, "build_marker", buildMarker());
            client.setModelOverride(run.getModel());
            CreateMetadata metadata=createMetadata(run.getRequestText());
            String request=metadata==null?run.getRequestText():metadata.request();
            var storyLimit=StoryCountControl.parse(request);
            stage(run, "model_stage_started", "开始生成模型规划");
            var raw=generate(run,request,"");
            if ("detailed".equalsIgnoreCase(run.getMode())) {
                annotateDetailedTaskSource(raw, "initial");
                TaskStoryReferences.canonicalize(raw);
            }
            stage(run, "model_stage_completed", "模型规划生成完成");
            event(run,"story_count_observed","首轮（含截断续写合并）Story 数量："+raw.path("stories").size());
            if(metadata!=null) {
                var project=raw.withObject("project");
                project.put("name",metadata.name());
                project.put("description",metadata.description());
                project.put("total_sprints",metadata.totalSprints());
            }
            var compression=StoryCountControl.compress(raw,storyLimit,request,java.util.Set.of());
            if ("detailed".equalsIgnoreCase(run.getMode()) && storyLimit != null)
                compression=compression.followedBy(StoryCountControl.enforceMaximum(raw, storyLimit, java.util.Set.of()));
            stage(run, "normalization_started", "开始规范化生成草案");
            var draft=normalizeGeneratedDraft(raw, run);
            stage(run, "normalization_completed", "生成草案规范化完成");
            stage(run, "completeness_started", "开始检查规划完整度");
            var report=validator.completenessReport(draft);
            stage(run, "completeness_completed", "规划完整度检查完成");
            DetailedSupplementPlan supplementPlan = "detailed".equalsIgnoreCase(run.getMode())
                    ? detailedSupplementPlan(draft, raw) : null;
            boolean shouldSupplement = !report.path("sufficient").asBoolean(true)
                    && !"excel".equalsIgnoreCase(run.getMode())
                    && (supplementPlan == null || supplementPlan.budget() > 0);
            if (!shouldSupplement && supplementPlan != null)
                event(run, "completeness_check", "没有明确 Task 缺口，跳过 Task 补全，避免重复生成整套 Tasks");
            if (shouldSupplement) {
                if (supplementPlan != null) initializeTaskGenerationAudit(raw, supplementPlan);
                event(run,"completeness_check","首轮规划完整度不足，自动执行一次补全");
                try {
                String taskGapFeedback = supplementPlan == null ? "" : supplementPlan.prompt();
                var secondRaw=supplementPlan == null ? generate(run,request,taskGapFeedback + "\n完整度报告：" + report + "\n当前已有 " + raw.path("stories").size()
                        + " 条 Story。" + (storyLimit==null?"按项目动态规模补全":storyLimit.hint())
                        + (storyLimit!=null && raw.path("stories").size()>=storyLimit.minimum()?"；已达到最低数量，不得为补 acceptance、owner、Sprint、Task、Milestone 而新增 Story，仅真正缺失的功能域可新增。":"；只补真正缺失的功能域。")
                        + "不得重复现有 Story，不要移动用户明确指定 Sprint 的 Story。原始草案（原有非空字段不得覆盖；新行使用全新编号）：" + raw)
                        : generateDetailedSupplementBatches(run, raw, supplementPlan);
                event(run,"story_count_observed","补全轮（含截断续写合并）Story 数量："+secondRaw.path("stories").size());
                var supplementAudit = secondRaw.path("_task_generation_audit").deepCopy();
                raw="detailed".equalsIgnoreCase(run.getMode())
                        ? mergeDetailedStages(raw, secondRaw, "supplement") : mergeDrafts(raw, secondRaw);
                if (supplementPlan != null) finishTaskGenerationAudit(raw, supplementPlan, supplementAudit);
                event(run,"story_count_observed","首轮与补全轮合并去重后 Story 数量："+raw.path("stories").size());
                runner.applyExplicitSprintDistribution(raw,request);
                if(metadata!=null) { var project=raw.withObject("project"); project.put("name",metadata.name()); project.put("description",metadata.description()); project.put("total_sprints",metadata.totalSprints()); }
                compression=compression.followedBy(StoryCountControl.compress(raw,storyLimit,request,java.util.Set.of()));
                if ("detailed".equalsIgnoreCase(run.getMode()) && storyLimit != null)
                    compression=compression.followedBy(StoryCountControl.enforceMaximum(raw, storyLimit, java.util.Set.of()));
                draft=normalizeGeneratedDraft(raw, run);
                report=validator.completenessReport(draft);
                } catch(Exception e) {
                    if (supplementPlan != null) preserveFailedSupplementAudit(raw, draft, supplementPlan, e);
                    log.error("Project generation supplement failed: runId={}, mode={}, exceptionType={}",
                            run.getId(), run.getMode(), e.getClass().getName(), e);
                    event(run,"completion_warning","自动补全未成功，保留首轮草案并明确提示人工补充");
                }
            }
            StoryCountControl.requireValidCount(raw,storyLimit,java.util.Set.of());
            compression.append(report,storyLimit);
            draft.set("completeness",report);
            stage(run, "draft_persist_started", "开始保存可确认草案");
            run.setDraftJson(draft.toString());run.setStatus("waiting_confirmation");run.setUpdatedAt(LocalDateTime.now());
            // 租约守卫:租约过期且已被别的实例接管时放弃收尾,不用陈旧草案覆盖新 owner
            if (!finish(run, token, null, null)) return true;
            stage(run,"draft_persist_completed","可确认草案保存完成");stage(run,"waiting_confirmation","草案已生成，等待人工确认");
        }
        catch(AgentError e){
            fail(run, token, e.getCode(), e);
        }
        catch(Exception e){
            fail(run, token, "generation_failed", e);
        }
        finally { client.clearModelOverride(); }
        return true;
    }

    public void event(ProjectGenerationRun run,String kind,String message){ProjectGenerationEvent e=new ProjectGenerationEvent();e.setRunId(run.getId());e.setAttempt(run.getAttempt());e.setKind(kind);e.setDetailJson(mapper.createObjectNode().put("message",message==null?"":message).toString());e.setCreatedAt(LocalDateTime.now());events.insert(e);}

    /** 收尾写库:按 worker_token 守卫;成功时清空租约列,让 run 回到"无 owner"的终态 */
    private boolean finish(ProjectGenerationRun run,String token,String code,String message){
        UpdateWrapper<ProjectGenerationRun> update=new UpdateWrapper<ProjectGenerationRun>().set("status",run.getStatus()).set("worker_token",null).set("lease_until",null).set("updated_at",LocalDateTime.now()).eq("id",run.getId()).eq("status","running").eq("worker_token",token);
        if(code!=null)update.set("error_code",code).set("error_message",message);else update.set("draft_json",run.getDraftJson());
        return runs.update(null,update)>0;
    }

    private void event(ProjectGenerationRun run, String kind, ProjectGenerationRunner.ResponseDiagnostic diagnostic) {
        var detail = mapper.valueToTree(diagnostic);
        ProjectGenerationEvent e = new ProjectGenerationEvent();
        e.setRunId(run.getId()); e.setAttempt(run.getAttempt()); e.setKind(kind);
        e.setDetailJson(detail.toString()); e.setCreatedAt(LocalDateTime.now()); events.insert(e);
    }
    private void stage(ProjectGenerationRun run, String stage, String message) { currentStage = stage; event(run, stage, message); }
    public com.fasterxml.jackson.databind.node.ObjectNode prepareImport(ProjectGenerationRun run, com.fasterxml.jackson.databind.node.ObjectNode raw) {
        var importedIds=new java.util.HashSet<String>();
        for(var story:raw.path("stories")) importedIds.add(story.path("id").asText(story.path("code").asText("")));
        var storyLimit=StoryCountControl.parse(run.getRequestText());
        var compression=StoryCountControl.compress(raw,storyLimit,run.getRequestText(),importedIds);
        var draft=validator.normalizeImport(raw,run.getStrategy(),run.getProjectId());
        var report=validator.completenessReport(draft,true);
        boolean supplementAttempted=false;
        boolean supplementCompleted=true;
        if(report.path("supplementRequired").asBoolean(false) && props.settingsReady()) {
            supplementAttempted=true;
            event(run,"completeness_check","导入草案完整度不足，保留原字段并执行一次补全");
            try {
                client.setModelOverride(run.getModel());
                var addition=runner.generate(run.getProjectId(),"只补充下列导入草案缺失项；已填写的编号、内容、负责人、Sprint 和排期不可修改。", "excel",client,"缺失项："+report.path("gaps")+"\n导入草案："+raw,
                        info -> logTruncation(run,info));
                var combined=mergeDrafts(raw,addition);
                compression=compression.followedBy(StoryCountControl.compress(combined,storyLimit,run.getRequestText(),importedIds));
                draft=validator.normalizeImport(combined,run.getStrategy(),run.getProjectId());
                report=validator.completenessReport(draft,true);
                event(run,"completion_succeeded","Excel/CSV 草案 AI 补全完成并已重新校验");
            } catch(Exception e) {
                supplementCompleted=false;
                log.error("Project import supplement failed: runId={}, mode={}, exceptionType={}",
                        run.getId(), run.getMode(), e.getClass().getName(), e);
                run.setErrorCode("ai_supplement_incomplete");
                run.setErrorMessage("Excel/CSV 已解析成功，但 AI 补全未完成；已保留原始导入草案供人工审查");
                event(run,"completion_warning",run.getErrorMessage());
            }
            finally {client.clearModelOverride();}
        }
        if(supplementAttempted) {
            report.put("aiSupplementCompleted",supplementCompleted);
            if(!supplementCompleted) report.withArray("gaps").add("AI 补全未完成，当前内容为原始导入草案，需要人工补充");
        }
        StoryCountControl.requireValidCount(draft,storyLimit,importedIds);
        compression.append(report,storyLimit);
        draft.set("completeness",report);
        return draft;
    }
    /** 失败收尾:既有 LHWAN 的阶段/异常类型诊断,又有本分支的 worker_token 租约守卫与分类文案 */
    private void fail(ProjectGenerationRun run,String token,String code,Throwable error){
        String type=error==null?"java.lang.RuntimeException":error.getClass().getName();
        String message=error==null?"项目规划生成失败":(error.getMessage()==null||error.getMessage().isBlank()?"项目规划生成失败":error.getMessage());
        log.error("Project generation failed: runId={}, mode={}, currentStage={}, exceptionType={}, message={}, firstFrame={}",
                run.getId(),run.getMode(),currentStage,type,message,firstStackFrame(error),error);
        /* 原始异常可能带 SQL/约束名/片段 SQL:诊断细节只进服务端日志,
           回显用分类文案(与 GlobalExceptionHandler 同一条原则);AgentError 是我们自己的错误分类,原样回显。 */
        String clientMessage="stage="+currentStage+"; exceptionType="+type+"; message="
                +(error instanceof AgentError?message:"项目规划生成失败，请稍后重试");
        run.setStatus("failed");run.setErrorCode(code);
        run.setErrorMessage(clientMessage.length()>500?clientMessage.substring(0,500):clientMessage);
        run.setUpdatedAt(LocalDateTime.now());
        // 租约守卫:若已被别的实例接管,放弃收尾,不用陈旧状态覆盖新 owner
        if (!finish(run, token, code, run.getErrorMessage())) return;
        var failure = mapper.createObjectNode().put("message", clientMessage).put("stageName", currentStageName)
                .put("errorCode", code).put("errorType", errorType(error));
        event(run, "failed", failure);
    }
    private String errorType(Throwable error) {
        String message = error == null ? "" : String.valueOf(error.getMessage());
        for (String type : java.util.List.of("EMPTY_CONTENT", "INVALID_JSON_SYNTAX", "NON_OBJECT_TOP_LEVEL",
                "NO_JSON_OBJECT_FOUND", "TRUNCATED_OR_INCOMPLETE", "RETRY_EXHAUSTED",
                "STORIES_STAGE_EMPTY", "TASKS_STAGE_EMPTY", "MILESTONES_STAGE_EMPTY"))
            if (message.contains(type)) return type;
        if (error instanceof AgentError agentError && "incomplete_response".equals(agentError.getCode()))
            return "TRUNCATED_OR_INCOMPLETE";
        return error instanceof AgentError agentError ? agentError.getCode() : "GENERATION_ERROR";
    }
    private void event(ProjectGenerationRun run, String kind, com.fasterxml.jackson.databind.node.ObjectNode detail) {
        ProjectGenerationEvent e=new ProjectGenerationEvent();e.setRunId(run.getId());e.setAttempt(run.getAttempt());
        e.setKind(kind);e.setDetailJson(detail.toString());e.setCreatedAt(LocalDateTime.now());events.insert(e);
    }

    private com.fasterxml.jackson.databind.node.ObjectNode buildMarker() {
        var marker = mapper.createObjectNode();
        Package appPackage = ProjectGenerationJobs.class.getPackage();
        marker.put("applicationVersion", appPackage == null || appPackage.getImplementationVersion() == null
                ? "unknown" : appPackage.getImplementationVersion());
        marker.put("applicationStartedAt", applicationStartedAt.toString());
        marker.put("buildTimestamp", "unknown");
        try {
            Path location = Path.of(ProjectGenerationJobs.class.getProtectionDomain()
                    .getCodeSource().getLocation().toURI());
            if (Files.exists(location)) marker.put("buildTimestamp", Files.getLastModifiedTime(location).toString());
        } catch (Exception ignored) {
            marker.put("buildTimestamp", "unknown");
        }
        Path root = findGitRoot(Path.of(System.getProperty("user.dir", ".")).toAbsolutePath());
        if (root == null) {
            marker.put("gitCommit", "unavailable").put("gitDirty", "unknown");
            return marker;
        }
        marker.put("gitCommit", gitValue(root, "rev-parse", "--short=12", "HEAD"));
        String status = gitValue(root, "status", "--porcelain", "--untracked-files=no");
        marker.put("gitDirty", "unavailable".equals(status) ? "unknown" : Boolean.toString(!status.isBlank()));
        return marker;
    }

    private Path findGitRoot(Path start) {
        Path current = start;
        for (int i = 0; current != null && i < 8; i++, current = current.getParent())
            if (Files.exists(current.resolve(".git"))) return current;
        return null;
    }

    private String gitValue(Path root, String... args) {
        try {
            var command = new java.util.ArrayList<String>();
            command.add("git"); command.add("-C"); command.add(root.toString());
            command.addAll(java.util.List.of(args));
            Process process = new ProcessBuilder(command).redirectErrorStream(true).start();
            if (!process.waitFor(2, TimeUnit.SECONDS)) {
                process.destroyForcibly();
                return "unavailable";
            }
            String value = new String(process.getInputStream().readAllBytes(), java.nio.charset.StandardCharsets.UTF_8).trim();
            return process.exitValue() == 0 ? value : "unavailable";
        } catch (Exception ignored) {
            return "unavailable";
        }
    }
    private String firstStackFrame(Throwable error) {
        if (error==null || error.getStackTrace().length==0) return "unknown";
        return error.getStackTrace()[0].toString();
    }
    private com.fasterxml.jackson.databind.node.ObjectNode generate(ProjectGenerationRun run,String request,String feedback) {
        if (!"detailed".equalsIgnoreCase(run.getMode()))
            return runner.generate(run.getProjectId(), request, run.getMode(), client, feedback,
                    info -> logTruncation(run, info));
        return runner.generate(run.getProjectId(),request,run.getMode(),client,feedback,
                info -> logTruncation(run,info), diagnostic -> {
                    currentStageName = diagnostic.stageName();
                    currentStage = "model_stage_" + diagnostic.stageName();
                    event(run, "model_response_diagnostic", diagnostic);
                });
    }

    private DetailedSupplementPlan detailedSupplementPlan(
            com.fasterxml.jackson.databind.node.ObjectNode draft,
            com.fasterxml.jackson.databind.node.ObjectNode raw) {
        java.util.Map<String, java.util.Set<String>> gaps = new java.util.TreeMap<>(String.CASE_INSENSITIVE_ORDER);
        for (var gap : validator.detailedTaskStageCoverage(draft).path("stageCoverageGaps")) {
            String id = gap.path("storyId").asText("");
            java.util.Set<String> stages = new java.util.TreeSet<>(String.CASE_INSENSITIVE_ORDER);
            gap.path("missingStages").forEach(stage -> stages.add(stage.asText("")));
            if (!id.isBlank() && !stages.isEmpty()) gaps.put(id, stages);
        }
        int totalBudget = DetailedTaskBudget.forStories(draft.path("stories").size());
        int remaining = Math.max(0, totalBudget - raw.path("tasks").size());
        int requestedStages = gaps.values().stream().mapToInt(java.util.Set::size).sum();
        int budget = DetailedTaskBudget.supplementBudget(remaining, gaps.size(), 0);
        budget = Math.min(budget, requestedStages);
        return new DetailedSupplementPlan(gaps, totalBudget, budget, raw.path("tasks").size(), remaining);
    }

    private com.fasterxml.jackson.databind.node.ObjectNode generateDetailedSupplementBatches(
            ProjectGenerationRun run, com.fasterxml.jackson.databind.node.ObjectNode raw,
            DetailedSupplementPlan plan) {
        var combined = mapper.createObjectNode();
        combined.set("tasks", mapper.createArrayNode());
        combined.set("dependencies", mapper.createArrayNode());
        for (String key : java.util.List.of("members", "sprints", "activities", "epics", "stories", "milestones"))
            combined.set(key, mapper.createArrayNode());
        combined.set("project", mapper.createObjectNode());
        combined.set("uml", mapper.createObjectNode());

        var batchAudits = mapper.createArrayNode();
        int remainingBudget = plan.budget();
        int successful = 0, failed = 0, attempts = 0, retries = 0;
        int generated = 0, accepted = 0, rejected = 0, semanticMerged = 0;
        int attempt1Parsed = 0, attempt2Parsed = 0;
        var attempt1Errors = new java.util.TreeSet<String>();
        var attempt2Errors = new java.util.TreeSet<String>();
        var working = raw.deepCopy();
        var failedStories = new java.util.TreeSet<String>(String.CASE_INSENSITIVE_ORDER);
        int index = 0;
        while (remainingBudget > 0) {
            var currentGaps = stageGapsFromCoverage(validator.detailedTaskStageCoverage(working));
            currentGaps.entrySet().removeIf(entry -> failedStories.contains(entry.getKey()));
            if (currentGaps.isEmpty()) break;
            var batchGaps = new java.util.LinkedHashMap<String, java.util.Set<String>>();
            for (var entry : currentGaps.entrySet()) {
                batchGaps.put(entry.getKey(), new java.util.TreeSet<>(entry.getValue()));
                if (batchGaps.size() >= 3) break;
            }
            index++;
            int plannedStages = batchGaps.values().stream().mapToInt(java.util.Set::size).sum();
            var beforeCoverage = validator.detailedTaskStageCoverage(working);
            var batchAudit = mapper.createObjectNode().put("batchIndex", index)
                    .put("plannedStageCount", plannedStages).put("attemptCount", 0)
                    .put("retryCount", 0).put("parsedTasks", 0).put("acceptedTasks", 0)
                    .put("rejectedTasks", 0).put("firstParsedTasks", 0)
                    .put("retryParsedTasks", 0).put("errorCode", "");
            batchAudit.set("storyCodes", mapper.valueToTree(batchGaps.keySet()));
            batchAudit.set("coverageBeforeBatch", beforeCoverage.deepCopy());
            if (remainingBudget <= 0) {
                batchAudit.put("errorCode", "TASK_BUDGET_EXHAUSTED");
                failed++;
                batchAudits.add(batchAudit);
                continue;
            }
            int batchBudget = Math.min(remainingBudget, plannedStages);
            var batchPlan = new DetailedSupplementPlan(batchGaps, plan.totalBudget(), batchBudget,
                    plan.initialTaskCount(), remainingBudget);
            var promptBase = working.deepCopy();
            try {
                var batchResult = runner.generateDetailedTaskSupplement(run.getProjectId(), promptBase, batchGaps,
                        batchBudget, client, info -> logTruncation(run, info), diagnostic -> {
                            currentStageName = diagnostic.stageName();
                            currentStage = "model_stage_" + diagnostic.stageName();
                            event(run, "model_response_diagnostic", diagnostic);
                        });
                int batchGenerated = batchResult.path("_task_generation_audit")
                        .path("supplementGeneratedTaskCount").asInt(batchResult.path("tasks").size());
                filterDetailedSupplement(promptBase, batchResult, batchPlan);
                var runnerAudit = batchResult.path("_task_generation_audit");
                int batchAccepted = batchResult.path("tasks").size();
                int batchRejected = Math.max(0, batchGenerated - batchAccepted);
                batchAudit.put("attemptCount", runnerAudit.path("supplementAttemptCount").asInt(1))
                        .put("retryCount", runnerAudit.path("supplementRetryCount").asInt(0))
                        .put("parsedTasks", batchGenerated).put("acceptedTasks", batchAccepted)
                        .put("firstParsedTasks", runnerAudit.path("attempt1ParsedTasks").asInt(0))
                        .put("retryParsedTasks", runnerAudit.path("attempt2ParsedTasks").asInt(0))
                        .put("rejectedTasks", batchRejected)
                        .put("errorCode", runnerAudit.path("attempt1ErrorCode").asText(""));
                qualifySupplementBatchTasks(batchResult, index);
                for (var task : batchResult.path("tasks")) combined.withArray("tasks").add(task.deepCopy());
                for (var dependency : batchResult.path("dependencies")) combined.withArray("dependencies").add(dependency.deepCopy());
                for (var task : batchResult.path("tasks")) working.withArray("tasks").add(task.deepCopy());
                remainingBudget -= batchAccepted;
                generated += batchGenerated;
                accepted += batchAccepted;
                rejected += batchRejected;
                semanticMerged += runnerAudit.path("semanticDuplicateMergedCount").asInt(0);
                attempts += batchAudit.path("attemptCount").asInt();
                retries += batchAudit.path("retryCount").asInt();
                attempt1Parsed += runnerAudit.path("attempt1ParsedTasks").asInt(0);
                attempt2Parsed += runnerAudit.path("attempt2ParsedTasks").asInt(0);
                if (!runnerAudit.path("attempt1ErrorCode").asText("").isBlank())
                    attempt1Errors.add(runnerAudit.path("attempt1ErrorCode").asText());
                if (!runnerAudit.path("attempt2ErrorCode").asText("").isBlank()
                        && !"NOT_ATTEMPTED".equals(runnerAudit.path("attempt2ErrorCode").asText()))
                    attempt2Errors.add(runnerAudit.path("attempt2ErrorCode").asText());
                var afterCoverage = validator.detailedTaskStageCoverage(working);
                batchAudit.set("coverageAfterBatch", afterCoverage.deepCopy());
                batchAudit.put("remainingGapStoryCount", afterCoverage.path("stageCoverageGapStoryCount").asInt())
                        .put("remainingStageGapCount", countMissingStages(afterCoverage.path("stageCoverageGaps")));
                successful++;
                if (batchAccepted == 0) failedStories.addAll(batchGaps.keySet());
            } catch (ProjectGenerationRunner.DetailedSupplementFailure failure) {
                var failureAudit = failure.audit();
                batchAudit.put("attemptCount", failureAudit.path("supplementAttemptCount").asInt(1))
                        .put("retryCount", failureAudit.path("supplementRetryCount").asInt(0))
                        .put("parsedTasks", failureAudit.path("attempt1ParsedTasks").asInt(0)
                                + failureAudit.path("attempt2ParsedTasks").asInt(0))
                        .put("firstParsedTasks", failureAudit.path("attempt1ParsedTasks").asInt(0))
                        .put("retryParsedTasks", failureAudit.path("attempt2ParsedTasks").asInt(0))
                        .put("errorCode", failure.getCode());
                attempts += batchAudit.path("attemptCount").asInt();
                retries += batchAudit.path("retryCount").asInt();
                attempt1Parsed += failureAudit.path("attempt1ParsedTasks").asInt(0);
                attempt2Parsed += failureAudit.path("attempt2ParsedTasks").asInt(0);
                if (!failureAudit.path("attempt1ErrorCode").asText("").isBlank())
                    attempt1Errors.add(failureAudit.path("attempt1ErrorCode").asText());
                if (!failureAudit.path("attempt2ErrorCode").asText("").isBlank()
                        && !"NOT_ATTEMPTED".equals(failureAudit.path("attempt2ErrorCode").asText()))
                    attempt2Errors.add(failureAudit.path("attempt2ErrorCode").asText());
                batchAudit.set("coverageAfterBatch", beforeCoverage.deepCopy());
                batchAudit.put("remainingGapStoryCount", beforeCoverage.path("stageCoverageGapStoryCount").asInt())
                        .put("remainingStageGapCount", countMissingStages(beforeCoverage.path("stageCoverageGaps")));
                failed++;
            }
            if (batchAudit.path("errorCode").asText("").equals("TASKS_STAGE_EMPTY"))
                failedStories.addAll(batchGaps.keySet());
            batchAudits.add(batchAudit);
        }
        var audit = combined.with("_task_generation_audit");
        var finalCoverage = validator.detailedTaskStageCoverage(working);
        audit.put("supplementTriggered", true).put("supplementBatchCount", batchAudits.size())
                .put("supplementSuccessfulBatchCount", successful).put("supplementFailedBatchCount", failed)
                .put("supplementAttemptCount", attempts).put("supplementRetryCount", retries)
                .put("attempt1ParsedTasks", attempt1Parsed)
                .put("attempt1ErrorCode", String.join(",", attempt1Errors))
                .put("attempt2ParsedTasks", attempt2Parsed)
                .put("attempt2ErrorCode", attempt2Errors.isEmpty() ? "NOT_ATTEMPTED" : String.join(",", attempt2Errors))
                .put("plannedGapStoryCount", plan.stageGaps().size())
                .put("plannedStageCount", plan.stageGaps().values().stream().mapToInt(java.util.Set::size).sum())
                .put("remainingBudgetBeforeSupplement", plan.remainingTaskBudget())
                .put("supplementGeneratedTaskCount", generated).put("generatedSupplementTaskCount", generated)
                .put("supplementAcceptedTaskCount", accepted).put("acceptedSupplementTaskCount", accepted)
                .put("rejectedSupplementTaskCount", rejected)
                .put("supplementRejectedByBudgetCount", 0)
                .put("semanticDuplicateMergedCount", semanticMerged);
        audit.set("supplementBatches", batchAudits);
        audit.put("supplementedStageTaskCount", countSupplementedStageTasks(combined.path("tasks"), plan.stageGaps()));
        audit.put("remainingGapStoryCount", finalCoverage.path("stageCoverageGapStoryCount").asInt())
                .put("remainingStageGapCount", countMissingStages(finalCoverage.path("stageCoverageGaps")));
        return combined;
    }

    private java.util.Map<String, java.util.Set<String>> stageGapsFromCoverage(
            com.fasterxml.jackson.databind.JsonNode coverage) {
        var gaps = new java.util.TreeMap<String, java.util.Set<String>>(String.CASE_INSENSITIVE_ORDER);
        for (var gap : coverage.path("stageCoverageGaps")) {
            String story = gap.path("storyId").asText("");
            if (story.isBlank()) continue;
            var stages = new java.util.TreeSet<String>(String.CASE_INSENSITIVE_ORDER);
            gap.path("missingStages").forEach(node -> {
                if (!node.asText("").isBlank()) stages.add(node.asText());
            });
            if (!stages.isEmpty()) gaps.put(story, stages);
        }
        return gaps;
    }

    private void qualifySupplementBatchTasks(com.fasterxml.jackson.databind.node.ObjectNode batch, int batchIndex) {
        var aliases = new java.util.LinkedHashMap<String, String>();
        for (var node : batch.path("tasks")) {
            if (!(node instanceof com.fasterxml.jackson.databind.node.ObjectNode task)) continue;
            String modelCode = task.path("model_task_code").asText(task.path("id").asText(""));
            String oldIdentity = task.path("generation_identity").asText("");
            String identity = "tasks#" + batchIndex + "#" + modelCode;
            if (!oldIdentity.isBlank()) aliases.put(oldIdentity, identity);
            task.put("model_task_code", modelCode).put("generation_continuation_round", batchIndex)
                    .put("generation_identity", identity);
        }
        for (var node : batch.path("tasks")) if (node instanceof com.fasterxml.jackson.databind.node.ObjectNode task) {
            rewriteIdentityArray(task, "generation_dependency_refs", aliases);
            rewriteIdentityArray(task, "generation_weak_dependency_refs", aliases);
            rewriteIdentityArray(task, "generation_related_task_refs", aliases);
        }
        for (var node : batch.path("milestones")) if (node instanceof com.fasterxml.jackson.databind.node.ObjectNode milestone)
            rewriteIdentityArray(milestone, "generation_related_task_refs", aliases);
        for (var node : batch.path("dependencies")) if (node instanceof com.fasterxml.jackson.databind.node.ObjectNode edge) {
            for (String field : java.util.List.of("generation_from_ref", "generation_to_ref")) {
                String old = edge.path(field).asText("");
                if (aliases.containsKey(old)) edge.put(field, aliases.get(old));
            }
        }
    }

    private java.util.List<java.util.Map<String, java.util.Set<String>>> detailedSupplementBatches(
            java.util.Map<String, java.util.Set<String>> gaps) {
        var entries = new java.util.ArrayList<>(gaps.entrySet());
        var batches = new java.util.ArrayList<java.util.Map<String, java.util.Set<String>>>();
        for (int offset = 0; offset < entries.size();) {
            int remaining = entries.size() - offset;
            int size = Math.min(3, remaining);
            var batch = new java.util.LinkedHashMap<String, java.util.Set<String>>();
            for (int i = 0; i < size; i++) {
                var entry = entries.get(offset + i);
                batch.put(entry.getKey(), new java.util.TreeSet<>(entry.getValue()));
            }
            batches.add(batch);
            offset += size;
        }
        return batches;
    }

    private void filterDetailedSupplement(com.fasterxml.jackson.databind.node.ObjectNode existing,
                                          com.fasterxml.jackson.databind.node.ObjectNode supplement,
                                          DetailedSupplementPlan plan) {
        TaskStoryReferences.canonicalize(supplement, existing.path("stories"));
        int generated = supplement.path("tasks").size();
        java.util.List<com.fasterxml.jackson.databind.node.ObjectNode> candidates = new java.util.ArrayList<>();
        for (var node : supplement.path("tasks")) {
            if (!(node instanceof com.fasterxml.jackson.databind.node.ObjectNode task)) continue;
            java.util.Set<String> refs = new java.util.TreeSet<>(String.CASE_INSENSITIVE_ORDER);
            refs.addAll(referenceValues(com.fasterxml.jackson.databind.node.TextNode.valueOf(TaskStoryReferences.text(task))));
            boolean targetsGap = false;
            for (String ref : refs) {
                java.util.Set<String> expected = plan.stageGaps().get(ref);
                java.util.Set<String> actual = taskImplementationStages(task);
                if (expected != null && !java.util.Collections.disjoint(expected, actual)) {
                    targetsGap = true;
                    break;
                }
            }
            if (targetsGap) candidates.add(task);
        }
        candidates.sort(java.util.Comparator.comparing(DetailedTaskBudget::storyKey)
                .thenComparing(task -> task.path("name").asText(""), String.CASE_INSENSITIVE_ORDER)
                .thenComparing(task -> task.path("generation_identity").asText(task.path("id").asText("")),
                        String.CASE_INSENSITIVE_ORDER));
        var accepted = mapper.createArrayNode();
        int semanticMerged = 0;
        for (var candidate : candidates) {
            com.fasterxml.jackson.databind.node.ObjectNode duplicate = null;
            for (var old : existing.path("tasks"))
                if (DetailedTaskBudget.highConfidenceDuplicate(old, candidate)) { duplicate = (com.fasterxml.jackson.databind.node.ObjectNode) old; break; }
            if (duplicate == null) for (var old : accepted)
                if (DetailedTaskBudget.highConfidenceDuplicate(old, candidate)) { duplicate = (com.fasterxml.jackson.databind.node.ObjectNode) old; break; }
            if (duplicate != null) {
                mergeTaskProvenance(duplicate, candidate);
                semanticMerged++;
                continue;
            }
            if (accepted.size() < plan.budget()) accepted.add(candidate);
        }
        supplement.set("tasks", accepted);
        DetailedTaskBudget.enforce(supplement, plan.budget());
        var audit = supplement.with("_task_generation_audit");
        audit.put("taskBudget", plan.totalBudget()).put("supplementGapCount", plan.stageGaps().size())
                .put("supplementGeneratedTaskCount", generated).put("supplementAcceptedTaskCount", accepted.size())
                .put("supplementRejectedByBudgetCount", Math.max(0, generated - accepted.size() - semanticMerged))
                .put("semanticDuplicateMergedCount", semanticMerged)
                .put("supplementedStageTaskCount", accepted.size());
    }

    private void finishTaskGenerationAudit(com.fasterxml.jackson.databind.node.ObjectNode merged,
                                           DetailedSupplementPlan plan,
                                           com.fasterxml.jackson.databind.JsonNode supplementAudit) {
        var audit = merged.with("_task_generation_audit");
        audit.put("taskBudget", plan.totalBudget()).put("initialTaskCount", plan.initialTaskCount())
                .put("continuationMergedTaskCount", plan.initialTaskCount())
                .put("supplementGapCount", plan.stageGaps().size())
                .put("finalTaskCount", merged.path("tasks").size());
        if (supplementAudit.isObject()) for (var fields = supplementAudit.fields(); fields.hasNext();) {
            var field = fields.next();
            audit.set(field.getKey(), field.getValue().deepCopy());
        }
        if (!audit.has("supplementGeneratedTaskCount")) audit.put("supplementGeneratedTaskCount", 0);
        if (!audit.has("supplementAcceptedTaskCount")) audit.put("supplementAcceptedTaskCount", 0);
        if (!audit.has("supplementRejectedByBudgetCount")) audit.put("supplementRejectedByBudgetCount", 0);
        if (!audit.has("semanticDuplicateMergedCount")) audit.put("semanticDuplicateMergedCount", 0);
        audit.put("generatedSupplementTaskCount", audit.path("supplementGeneratedTaskCount").asInt(0));
        audit.put("acceptedSupplementTaskCount", audit.path("supplementAcceptedTaskCount").asInt(0));
        audit.put("rejectedSupplementTaskCount", Math.max(0,
                audit.path("supplementGeneratedTaskCount").asInt(0)
                        - audit.path("supplementAcceptedTaskCount").asInt(0)));
    }

    private void initializeTaskGenerationAudit(com.fasterxml.jackson.databind.node.ObjectNode raw,
                                               DetailedSupplementPlan plan) {
        var audit = raw.with("_task_generation_audit");
        int plannedStages = plan.stageGaps().values().stream().mapToInt(java.util.Set::size).sum();
        audit.put("supplementTriggered", true).put("supplementAttemptCount", 0)
                .put("supplementRetryCount", 0).put("plannedGapStoryCount", plan.stageGaps().size())
                .put("plannedStageCount", plannedStages).put("remainingBudgetBeforeSupplement", plan.remainingTaskBudget())
                .put("supplementGapCount", plan.stageGaps().size())
                .put("attempt1ParsedTasks", 0).put("attempt1ErrorCode", "NOT_ATTEMPTED")
                .put("attempt2ParsedTasks", 0).put("attempt2ErrorCode", "NOT_ATTEMPTED")
                .put("generatedSupplementTaskCount", 0).put("acceptedSupplementTaskCount", 0)
                .put("rejectedSupplementTaskCount", 0).put("supplementedStageTaskCount", 0)
                .put("supplementBatchCount", 0).put("supplementSuccessfulBatchCount", 0)
                .put("supplementFailedBatchCount", 0);
        audit.set("supplementBatches", mapper.createArrayNode());
    }

    private void preserveFailedSupplementAudit(com.fasterxml.jackson.databind.node.ObjectNode raw,
                                               com.fasterxml.jackson.databind.node.ObjectNode normalizedDraft,
                                               DetailedSupplementPlan plan, Exception error) {
        var audit = raw.with("_task_generation_audit");
        if (error instanceof ProjectGenerationRunner.DetailedSupplementFailure failure)
            failure.audit().fields().forEachRemaining(field -> audit.set(field.getKey(), field.getValue().deepCopy()));
        else {
            audit.put("supplementAttemptCount", Math.max(1, audit.path("supplementAttemptCount").asInt()));
            audit.put("attempt1ErrorCode", error instanceof AgentError agent ? agent.getCode()
                    : error.getClass().getSimpleName());
        }
        audit.put("generatedSupplementTaskCount", 0).put("acceptedSupplementTaskCount", 0)
                .put("rejectedSupplementTaskCount", 0).put("supplementedStageTaskCount", 0);
        ObjectNode coverage = validator.detailedTaskStageCoverage(normalizedDraft);
        audit.put("remainingGapStoryCount", coverage.path("stageCoverageGapStoryCount").asInt())
                .put("remainingStageGapCount", countMissingStages(coverage.path("stageCoverageGaps")));
        ObjectNode finalAudit = normalizedDraft.with("qualityAdjustments").with("taskGeneration");
        audit.fields().forEachRemaining(field -> finalAudit.set(field.getKey(), field.getValue().deepCopy()));
        finalAudit.set("stageCoverageGaps", coverage.path("stageCoverageGaps").deepCopy());
        finalAudit.set("storyStageContracts", coverage.path("storyStageContracts").deepCopy());
    }

    private int countMissingStages(com.fasterxml.jackson.databind.JsonNode gaps) {
        int count = 0;
        for (var gap : gaps) count += gap.path("missingStages").size();
        return count;
    }

    private java.util.Set<String> taskImplementationStages(com.fasterxml.jackson.databind.JsonNode task) {
        String text = (task.path("name").asText("") + " " + task.path("title").asText("") + " "
                + task.path("description").asText("") + " " + task.path("type").asText(""))
                .toLowerCase(java.util.Locale.ROOT);
        java.util.Set<String> stages = new java.util.TreeSet<>(String.CASE_INSENSITIVE_ORDER);
        if (containsAny(text, "数据库", "数据表", "表结构", "schema", "dao", "repository")) stages.add("data");
        if (containsAny(text, "接口", "api", "后端", "service", "服务层", "生成", "流转", "控制", "记录", "处理", "管理逻辑", "查询逻辑", "消息生成", "时长生成")) stages.add("backend");
        if (containsAny(text, "页面", "前端", "界面", "组件", "表单", "交互", "列表", "详情页", "个人中心", "展示", "统计看板", "消息列表")
                || (text.contains("查看") && containsAny(text, "个人", "学生", "负责人", "详情", "服务时长", "累计时长"))) stages.add("frontend");
        if (text.contains("异常") && containsAny(text, "处理", "限制", "拦截")) stages.add("backend");
        if (containsAny(text, "校验", "验证", "权限", "越权", "规则", "限制", "状态流转", "异常拦截", "异常限制")) stages.add("validation");
        if (containsAny(text, "测试", "验收", "验证用例")) stages.add("test");
        if (containsAny(text, "文档", "说明书")) stages.add("documentation");
        if (containsAny(text, "配置", "基础框架", "工程搭建")) stages.add("configuration");
        return stages;
    }

    private int countSupplementedStageTasks(com.fasterxml.jackson.databind.JsonNode tasks,
                                            java.util.Map<String, java.util.Set<String>> gaps) {
        int count = 0;
        for (var task : tasks) {
            var actual = taskImplementationStages(task);
            boolean fillsGap = false;
            for (String ref : referenceValues(task.path("story_ref"))) {
                var expected = gaps.get(ref);
                if (expected != null && !java.util.Collections.disjoint(expected, actual)) {
                    fillsGap = true;
                    break;
                }
            }
            if (fillsGap) count++;
        }
        return count;
    }

    private boolean containsAny(String text, String... tokens) {
        for (String token : tokens) if (text.contains(token)) return true;
        return false;
    }

    private record DetailedSupplementPlan(java.util.Map<String, java.util.Set<String>> stageGaps, int totalBudget,
                                          int budget, int initialTaskCount, int remainingTaskBudget) {
        String prompt() {
            return "TASK_SUPPLEMENT_BUDGET=" + budget + "\n只允许补充以下 Story 的指定缺失实现阶段："
                    + stageGaps + "。不得重新生成已有阶段或整套 Tasks；每个缺失阶段最多生成1个核心 Task。";
        }
    }

    /**
     * Activity recovery is intentionally limited to the detailed natural
     * language mode.  Overview/coarse drafts keep their existing model-owned
     * Activity/Epic output and still use the same normalization contract.
     * The marker is transient and is removed before the draft can be persisted
     * or merged into a continuation.
     */
    private com.fasterxml.jackson.databind.node.ObjectNode normalizeGeneratedDraft(
            com.fasterxml.jackson.databind.node.ObjectNode raw, ProjectGenerationRun run) {
        boolean detailed = "detailed".equalsIgnoreCase(run.getMode());
        if (!detailed) raw.put("_preserve_activity_domains", true);
        try {
            var normalized = validator.normalize(raw, run.getStrategy(), run.getProjectId());
            return normalized;
        } finally {
            raw.remove("_preserve_activity_domains");
        }
    }
    private void logTruncation(ProjectGenerationRun run,ProjectGenerationRunner.TruncationInfo info) {
        log.warn("Project generation output truncated: runId={}, mode={}, model={}, stage={}, finishReason={}, responseLength={}, continuationTriggered={}, continuationSucceeded={}, recoveredStoryCount={}, mergedStoryCount={}",
                run.getId(),run.getMode(),run.getModel(),info.stage(),info.finishReason(),
                info.responseLength(),info.continuationTriggered(),info.continuationSucceeded(),
                info.recoveredStoryCount(),info.mergedStoryCount());
    }
    private CreateMetadata createMetadata(String request){
        String prefix="CREATE_PROJECT_METADATA:";
        if(request==null||!request.startsWith(prefix))return null;
        int newline=request.indexOf('\n');
        String json=newline<0?request.substring(prefix.length()):request.substring(prefix.length(),newline);
        String prompt=newline<0?"":request.substring(newline+1);
        try {
            var node=mapper.readTree(json);
            return new CreateMetadata(node.path("name").asText(),node.path("description").asText(""),Math.max(1,Math.min(6,node.path("total_sprints").asInt(3))),prompt);
        } catch(Exception e) { throw new IllegalArgumentException("新建项目基本信息无法解析"); }
    }
    static com.fasterxml.jackson.databind.node.ObjectNode mergeDrafts(com.fasterxml.jackson.databind.node.ObjectNode first, com.fasterxml.jackson.databind.node.ObjectNode second) {
        boolean canonicalReferences = first.path(TaskStoryReferences.MARKER).asBoolean(false)
                || second.path(TaskStoryReferences.MARKER).asBoolean(false);
        if (canonicalReferences) {
            TaskStoryReferences.canonicalize(first);
            TaskStoryReferences.canonicalize(second, first.path("stories"));
        }
        com.fasterxml.jackson.databind.node.ObjectNode merged=first.deepCopy();
        java.util.Map<String,String> aliases=new java.util.HashMap<>();
        for(String key:new String[]{"stories","tasks","milestones","activities","epics"}) {
            if(!second.path(key).isArray()) continue;
            var target=merged.withArray(key);
            for(var incoming:second.path(key)) {
                com.fasterxml.jackson.databind.JsonNode existing=null;
                String id=incoming.path("id").asText(incoming.path("code").asText(""));
                String title=incoming.path("title").asText(incoming.path("name").asText(""));
                for(var row:target) {
                    String existingId=row.path("id").asText(row.path("code").asText(""));
                    String existingTitle=row.path("title").asText(row.path("name").asText(""));
                    boolean equivalentStory = "stories".equals(key)
                            && row.path("activity").asText().equals(incoming.path("activity").asText())
                            && !canonicalStoryTitle(title).isBlank()
                            && canonicalStoryTitle(title).equals(canonicalStoryTitle(existingTitle));
                    boolean identityCompatible = !"tasks".equals(key) || sameTaskIdentity(row, incoming);
                    if(identityCompatible && ((!id.isBlank() && existingId.equalsIgnoreCase(id))
                            || (!title.isBlank() && existingTitle.equalsIgnoreCase(title)) || equivalentStory)) {
                        existing=row;
                        break;
                    }
                }
                if(existing==null) target.add(incoming.deepCopy());
                else {
                    if(("stories".equals(key)||"tasks".equals(key)) && !id.isBlank()) aliases.put(id,existing.path("id").asText(existing.path("code").asText()));
                    var original=(com.fasterxml.jackson.databind.node.ObjectNode)existing;
                    incoming.fields().forEachRemaining(field -> {
                        var value=original.get(field.getKey());
                        if(value==null || value.isNull() || (value.isTextual() && value.asText().isBlank()) || (value.isArray() && value.isEmpty())) original.set(field.getKey(),field.getValue().deepCopy());
                    });
                    if ("tasks".equals(key)) {
                        mergeTaskDependencies(original, incoming);
                        TaskStoryReferences.mergeAudit(original, incoming);
                        original.put("invalid_story_refs", TaskStoryReferences.union(
                                original.path("invalid_story_refs").asText(""), incoming.path("invalid_story_refs").asText("")));
                    }
                    if ("tasks".equals(key)) mergeTaskProvenance(original, incoming);
                }
            }
        }
        // A repeated title under another ID must not leave newly added rows pointing to that discarded ID.
        for(String key:new String[]{"tasks","milestones"}) for(var row:merged.path(key)) {
            var object=(com.fasterxml.jackson.databind.node.ObjectNode)row;
            if (canonicalReferences) TaskStoryReferences.remapAudit(object, aliases);
            for(String field: canonicalReferences ? new String[]{"story_ref","storyCode","story_refs","depends_on","related_task_ids"}
                    : new String[]{"story_ref","story_refs","depends_on","related_task_ids"}) {
                var value=object.get(field); if(value==null)continue;
                if(value.isArray()) {var refs=mapperArray(value); for(int i=0;i<refs.size();i++) if(refs.get(i).isTextual()) refs.set(i,com.fasterxml.jackson.databind.node.TextNode.valueOf(aliases.getOrDefault(refs.get(i).asText(),refs.get(i).asText())));}
                else if(value.isTextual()) object.put(field,java.util.Arrays.stream(value.asText().split(",")).map(String::trim).map(v->aliases.getOrDefault(v,v)).distinct().collect(java.util.stream.Collectors.joining(",")));
            }
        }
        mergeTopLevelDependencies(merged, second, aliases);
        return merged;
    }

    private static boolean sameTaskIdentity(com.fasterxml.jackson.databind.JsonNode left,
                                            com.fasterxml.jackson.databind.JsonNode right) {
        String a = left.path("generation_identity").asText("").trim();
        String b = right.path("generation_identity").asText("").trim();
        return a.isBlank() || b.isBlank() || a.equalsIgnoreCase(b);
    }

    /** Detailed supplement rounds use an independent Task namespace before generic merge logic runs. */
    static com.fasterxml.jackson.databind.node.ObjectNode mergeDetailedStages(
            com.fasterxml.jackson.databind.node.ObjectNode first,
            com.fasterxml.jackson.databind.node.ObjectNode second, String stage) {
        annotateDetailedTaskSource(first, "initial");
        annotateDetailedTaskSource(second, stage);
        qualifyDetailedStageIdentities(second, stage);
        TaskIdentityReferences.resolve((com.fasterxml.jackson.databind.node.ArrayNode) second.path("tasks"),
                second.path("milestones").isArray() ? (com.fasterxml.jackson.databind.node.ArrayNode) second.path("milestones") : com.fasterxml.jackson.databind.node.JsonNodeFactory.instance.arrayNode(),
                second.path("dependencies").isArray() ? (com.fasterxml.jackson.databind.node.ArrayNode) second.path("dependencies") : com.fasterxml.jackson.databind.node.JsonNodeFactory.instance.arrayNode(), true);
        int next = maxTaskNumber(first);
        java.util.Map<String,java.util.List<String>> localCodes = new java.util.LinkedHashMap<>();
        java.util.Map<String,String> identityCodes = new java.util.LinkedHashMap<>();
        for (var taskNode : second.path("tasks")) {
            var task=(com.fasterxml.jackson.databind.node.ObjectNode)taskNode;
            String oldCode=task.path("id").asText("").trim().toUpperCase(java.util.Locale.ROOT);
            String modelCode=task.path("model_task_code").asText(task.path("id").asText(""))
                    .trim().toUpperCase(java.util.Locale.ROOT);
            String finalCode=String.format("T%02d",++next);
            task.put("model_task_code",modelCode).put("id",finalCode);
            identityCodes.put(task.path("generation_identity").asText(""), finalCode);
            localCodes.computeIfAbsent(modelCode,ignored->new java.util.ArrayList<>()).add(finalCode);
            if (!oldCode.isBlank() && !oldCode.equalsIgnoreCase(modelCode))
                localCodes.computeIfAbsent(oldCode,ignored->new java.util.ArrayList<>()).add(finalCode);
        }
        java.util.Set<String> firstCodes=new java.util.TreeSet<>(String.CASE_INSENSITIVE_ORDER);
        for(var task:first.path("tasks")) firstCodes.add(task.path("id").asText(""));
        for(var taskNode:second.path("tasks")) {
            var task=(com.fasterxml.jackson.databind.node.ObjectNode)taskNode;
            rewriteDetailedTaskRefs(task,"depends_on","generation_dependency_refs",identityCodes,localCodes,firstCodes);
            rewriteDetailedTaskRefs(task,"weak_dependencies","generation_weak_dependency_refs",identityCodes,localCodes,firstCodes);
            rewriteDetailedTaskRefs(task,"related_task_ids","generation_related_task_refs",identityCodes,localCodes,firstCodes);
        }
        for(var milestoneNode:second.path("milestones"))
            rewriteDetailedTaskRefs((com.fasterxml.jackson.databind.node.ObjectNode)milestoneNode,
                    "related_task_ids","generation_related_task_refs",identityCodes,localCodes,firstCodes);
        if(second.path("dependencies").isArray()) for(var edgeNode:second.path("dependencies")) {
            if(!(edgeNode instanceof com.fasterxml.jackson.databind.node.ObjectNode edge))continue;
            rewriteDetailedEdgeRef(edge,"from","generation_from_ref",identityCodes,localCodes,firstCodes);
            rewriteDetailedEdgeRef(edge,"to","generation_to_ref",identityCodes,localCodes,firstCodes);
        }
        var merged = mergeDrafts(first,second);
        int resolved = first.path("_task_generation_audit").path("crossStageReferenceResolvedCount").asInt(0)
                + countSourceAwareReferences(second);
        int unresolved = first.path("_task_generation_audit").path("crossStageReferenceUnresolvedCount").asInt(0)
                + countUnresolvedReferences(second);
        merged.with("_task_generation_audit").put("crossStageReferenceResolvedCount", resolved)
                .put("crossStageReferenceUnresolvedCount", unresolved);
        return merged;
    }

    private static void qualifyDetailedStageIdentities(com.fasterxml.jackson.databind.node.ObjectNode draft, String stage) {
        java.util.Map<String,String> aliases = new java.util.LinkedHashMap<>();
        for (var node : draft.path("tasks")) {
            if (!(node instanceof com.fasterxml.jackson.databind.node.ObjectNode task)) continue;
            String old = task.path("generation_identity").asText("");
            int round = task.path("generation_continuation_round").asInt(0);
            String code = task.path("model_task_code").asText(task.path("id").asText(""));
            String qualified = stage + "#" + round + "#" + code;
            if (!old.isBlank()) aliases.put(old, qualified);
            task.put("generation_stage", stage).put("generation_identity", qualified);
        }
        annotateDetailedTaskSource(draft, stage);
        for (var node : draft.path("tasks")) if (node instanceof com.fasterxml.jackson.databind.node.ObjectNode task) {
            rewriteIdentityArray(task, "generation_dependency_refs", aliases);
            rewriteIdentityArray(task, "generation_weak_dependency_refs", aliases);
            rewriteIdentityArray(task, "generation_related_task_refs", aliases);
        }
        for (var node : draft.path("milestones")) if (node instanceof com.fasterxml.jackson.databind.node.ObjectNode milestone)
            rewriteIdentityArray(milestone, "generation_related_task_refs", aliases);
        for (var node : draft.path("dependencies")) if (node instanceof com.fasterxml.jackson.databind.node.ObjectNode edge) {
            for (String field : java.util.List.of("generation_from_ref", "generation_to_ref")) {
                String old = edge.path(field).asText("");
                if (aliases.containsKey(old)) edge.put(field, aliases.get(old));
            }
        }
    }

    private static void rewriteIdentityArray(com.fasterxml.jackson.databind.node.ObjectNode row, String field,
                                             java.util.Map<String,String> aliases) {
        if (!row.path(field).isArray()) return;
        var rewritten = com.fasterxml.jackson.databind.node.JsonNodeFactory.instance.arrayNode();
        for (var value : row.path(field)) rewritten.add(aliases.getOrDefault(value.asText(""), value.asText("")));
        row.set(field, rewritten);
    }

    private static void annotateDetailedTaskSource(com.fasterxml.jackson.databind.node.ObjectNode draft,String stage) {
        if(!draft.path("tasks").isArray())return;
        for(var node:draft.path("tasks")) {
            var task=(com.fasterxml.jackson.databind.node.ObjectNode)node;
            if(!task.hasNonNull("model_task_code"))task.put("model_task_code",task.path("id").asText(""));
            if(!task.hasNonNull("generation_stage"))task.put("generation_stage",stage);
            if(!task.has("generation_continuation_round"))task.put("generation_continuation_round",0);
            if (!task.hasNonNull("generation_identity")) task.put("generation_identity", task.path("generation_stage").asText(stage) + "#"
                    + task.path("generation_continuation_round").asInt(0) + "#"
                    + task.path("model_task_code").asText(task.path("id").asText("")));
            var source=task.withArray("generation_sources");
            var identity=task.path("generation_identity").asText("");
            boolean known=false;
            for(var item:source) if(identity.equalsIgnoreCase(item.path("identity").asText(""))) { known=true; break; }
            if(!known) source.add(com.fasterxml.jackson.databind.node.JsonNodeFactory.instance.objectNode()
                    .put("stage",task.path("generation_stage").asText(stage))
                    .put("continuationRound",task.path("generation_continuation_round").asInt(0))
                    .put("modelTaskCode",task.path("model_task_code").asText(task.path("id").asText("")))
                    .put("identity",identity));
        }
    }

    private static int maxTaskNumber(com.fasterxml.jackson.databind.node.ObjectNode draft) {
        int max=draft.path("tasks").size();
        for(var task:draft.path("tasks")) {
            var matcher=java.util.regex.Pattern.compile("(?i)^T?(\\d+)$").matcher(task.path("id").asText(""));
            if(matcher.matches())max=Math.max(max,Integer.parseInt(matcher.group(1)));
        }
        return max;
    }

    private static void rewriteDetailedTaskRefs(com.fasterxml.jackson.databind.node.ObjectNode row,String field,
                                                String identityField, java.util.Map<String,String> identityCodes,
                                                java.util.Map<String,java.util.List<String>> localCodes,
                                                java.util.Set<String> firstCodes) {
        var values=new java.util.LinkedHashSet<String>();
        var unresolved=row.withArray("unresolved_cross_stage_references");
        for(String identity:referenceValues(row.path(identityField))) {
            String code=identityCodes.get(identity);
            if(code!=null)values.add(code); else unresolved.add(identity);
        }
        if(row.has(identityField)) {
            if(unresolved.isEmpty())row.remove("unresolved_cross_stage_references");
            row.put(field,String.join(",",values));
            return;
        }
        for(String ref:referenceValues(row.path(field))) {
            var local=localCodes.get(ref.toUpperCase(java.util.Locale.ROOT));
            if(local!=null&&local.size()==1) values.add(local.get(0));
            else if(local==null&&firstCodes.contains(ref)) values.add(ref);
            else unresolved.add(ref);
        }
        if(unresolved.isEmpty())row.remove("unresolved_cross_stage_references");
        row.put(field,String.join(",",values));
    }

    private static void rewriteDetailedEdgeRef(com.fasterxml.jackson.databind.node.ObjectNode edge,String field,
                                               String identityField, java.util.Map<String,String> identityCodes,
                                               java.util.Map<String,java.util.List<String>> localCodes,
                                               java.util.Set<String> firstCodes) {
        String identity=edge.path(identityField).asText("");
        if(identityCodes.containsKey(identity)) {edge.put(field,identityCodes.get(identity));return;}
        String ref=edge.path(field).asText("").trim();
        var local=localCodes.get(ref.toUpperCase(java.util.Locale.ROOT));
        if(local!=null&&local.size()==1)edge.put(field,local.get(0));
        else if(local==null&&firstCodes.contains(ref))edge.put(field,ref);
        else edge.put("unresolved_"+field+"_reference",ref).put(field,"");
    }

    private static int countSourceAwareReferences(com.fasterxml.jackson.databind.JsonNode draft) {
        int count=0;
        for(var task:draft.path("tasks"))count+=task.path("generation_dependency_refs").size();
        for(var milestone:draft.path("milestones"))count+=milestone.path("generation_related_task_refs").size();
        for(var edge:draft.path("dependencies")) {
            if(edge.hasNonNull("generation_from_ref"))count++;
            if(edge.hasNonNull("generation_to_ref"))count++;
        }
        return count;
    }

    private static int countUnresolvedReferences(com.fasterxml.jackson.databind.JsonNode draft) {
        int count=0;
        for(var task:draft.path("tasks"))count+=task.path("unresolved_cross_stage_references").size();
        for(var milestone:draft.path("milestones"))count+=milestone.path("unresolved_cross_stage_references").size();
        for(var edge:draft.path("dependencies")) {
            if(edge.hasNonNull("unresolved_from_reference"))count++;
            if(edge.hasNonNull("unresolved_to_reference"))count++;
        }
        return count;
    }

    private static java.util.List<String> referenceValues(com.fasterxml.jackson.databind.JsonNode value) {
        var result=new java.util.ArrayList<String>();
        if(value.isArray())value.forEach(node->{if(!node.asText("").isBlank())result.add(node.asText().trim());});
        else for(String ref:value.asText("").split("[,，;；\\s]+"))if(!ref.isBlank())result.add(ref.trim());
        return result;
    }

    private static void mergeTaskProvenance(com.fasterxml.jackson.databind.node.ObjectNode target,
                                            com.fasterxml.jackson.databind.JsonNode incoming) {
        var sources=target.withArray("generation_sources");
        if(sources.isEmpty())sources.add(mapperSource(target));
        sources.add(mapperSource(incoming));
    }

    private static com.fasterxml.jackson.databind.node.ObjectNode mapperSource(com.fasterxml.jackson.databind.JsonNode task) {
        return com.fasterxml.jackson.databind.node.JsonNodeFactory.instance.objectNode()
                .put("stage",task.path("generation_stage").asText("unknown"))
                .put("continuationRound",task.path("generation_continuation_round").asInt(0))
                .put("modelTaskCode",task.path("model_task_code").asText(task.path("id").asText("")))
                .put("identity",task.path("generation_identity").asText(""));
    }
    private static void mergeTaskDependencies(com.fasterxml.jackson.databind.node.ObjectNode target,
                                              com.fasterxml.jackson.databind.JsonNode incoming) {
        java.util.LinkedHashSet<String> dependencies=new java.util.LinkedHashSet<>();
        addDependencyValues(target.path("depends_on"),dependencies,java.util.Map.of());
        addDependencyValues(incoming.path("depends_on"),dependencies,java.util.Map.of());
        target.put("depends_on",dependencies.stream().map(String::toUpperCase)
                .collect(java.util.stream.Collectors.joining(",")));
    }
    private static void mergeTopLevelDependencies(com.fasterxml.jackson.databind.node.ObjectNode target,
                                                  com.fasterxml.jackson.databind.node.ObjectNode source,
                                                  java.util.Map<String,String> aliases) {
        var rows=target.withArray("dependencies");
        java.util.LinkedHashSet<String> seen=new java.util.LinkedHashSet<>();
        for(var edge:rows) seen.add(dependencyEdgeKey(edge,aliases));
        if(source.path("dependencies").isArray()) for(var edge:source.path("dependencies")) {
            String key=dependencyEdgeKey(edge,aliases);
            if(key.isBlank()||!seen.add(key))continue;
            var copy=edge.deepCopy();
            if(copy instanceof com.fasterxml.jackson.databind.node.ObjectNode object) {
                String from=object.path("from").asText("").trim();
                String to=object.path("to").asText("").trim();
                if(!from.isBlank())object.put("from",aliases.getOrDefault(from,from));
                if(!to.isBlank())object.put("to",aliases.getOrDefault(to,to));
            }
            rows.add(copy);
        }
    }
    private static String dependencyEdgeKey(com.fasterxml.jackson.databind.JsonNode edge,
                                            java.util.Map<String,String> aliases) {
        String from=edge.path("from").asText(edge.path("source").asText("")).trim();
        String to=edge.path("to").asText(edge.path("target").asText("")).trim();
        from=aliases.getOrDefault(from,from).toUpperCase(java.util.Locale.ROOT);
        to=aliases.getOrDefault(to,to).toUpperCase(java.util.Locale.ROOT);
        return from.isBlank()||to.isBlank()?"":from+">"+to;
    }
    private static void addDependencyValues(com.fasterxml.jackson.databind.JsonNode value,
                                            java.util.Set<String> target,
                                            java.util.Map<String,String> aliases) {
        if(value==null||value.isNull())return;
        if(value.isArray()){value.forEach(v->addDependencyValues(v,target,aliases));return;}
        for(String part:value.asText("").split("[,，;；\\s]+")) {
            String normalized=aliases.getOrDefault(part.trim(),part.trim()).toUpperCase(java.util.Locale.ROOT);
            if(!normalized.isBlank())target.add(normalized);
        }
    }
    private static String canonicalStoryTitle(String title) {
        String normalized = title.toLowerCase(java.util.Locale.ROOT).replaceAll("[\\p{P}\\p{Z}\\s]+", "");
        normalized = normalized.replaceFirst("^us\\d+", "");
        normalized = normalized.replaceAll("(?:功能|模块|开发|实现)$", "");
        return normalized.length() >= 4 ? normalized : "";
    }
    private static com.fasterxml.jackson.databind.node.ArrayNode mapperArray(com.fasterxml.jackson.databind.JsonNode value) {return (com.fasterxml.jackson.databind.node.ArrayNode)value;}
    private record CreateMetadata(String name,String description,int totalSprints,String request) {}
}
