package com.aicap.generation;

import com.aicap.common.ApiException;
import com.aicap.entity.Story;
import com.aicap.entity.Task;
import com.aicap.entity.User;
import com.aicap.mapper.StoryMapper;
import com.aicap.mapper.TaskMapper;
import com.aicap.mapper.UserMapper;
import com.aicap.mapper.ProjectMemberMapper;
import com.aicap.service.ProjectAccessService;
import com.aicap.service.ProjectScopedResolver;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.PriorityQueue;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Converts model/import output into the existing Story/Task contract. */
@Service
@Slf4j
public class ProjectGenerationValidator {
    private static final Pattern US = Pattern.compile("^US(\\d+)$", Pattern.CASE_INSENSITIVE);
    private static final Pattern T = Pattern.compile("^T(\\d+)$", Pattern.CASE_INSENSITIVE);
    private final StoryMapper stories;
    private final TaskMapper tasks;
    private final UserMapper users;
    private final ProjectMemberMapper projectMembers;
    private final ObjectMapper mapper;
    private final ProjectScopedResolver resolver;

    @Autowired
    public ProjectGenerationValidator(StoryMapper stories, TaskMapper tasks, UserMapper users,
                                      ProjectMemberMapper projectMembers, ObjectMapper mapper,
                                      ProjectScopedResolver resolver) {
        this.stories = stories;
        this.tasks = tasks;
        this.users = users;
        this.projectMembers = projectMembers;
        this.mapper = mapper;
        this.resolver = resolver;
    }

    public ProjectGenerationValidator(StoryMapper stories, TaskMapper tasks, UserMapper users, ObjectMapper mapper) {
        this.stories = stories;
        this.tasks = tasks;
        this.users = users;
        this.projectMembers = null;
        this.mapper = mapper;
        this.resolver = null;
    }

    /** Backward-compatible draft-only overload used by import/tests; execution still requires a real scope. */
    public ObjectNode normalize(JsonNode input, String strategy) { return normalize(input, strategy, "NEW-LOCAL"); }

    public ObjectNode normalize(JsonNode input, String strategy, String projectId) {
        return normalizeInternal(input, strategy, projectId, false);
    }

    public ObjectNode normalizeImport(JsonNode input, String strategy, String projectId) {
        return normalizeInternal(input, strategy, projectId, true);
    }

    /** Consolidates only detailed generated domains after stories and references are stable. */
    public ObjectNode consolidateDetailedActivities(ObjectNode draft) {
        ArrayNode activities = array(draft, "activities");
        ArrayNode stories = array(draft, "stories");
        if (stories.isEmpty()) return draft;
        if (activities.isEmpty()) {
            recoverDetailedActivityDefinitions(draft);
            normalizeActivityReferences(draft, mapper.createArrayNode());
            activities = array(draft, "activities");
        }
        if (activities.isEmpty()) return draft;
        if (activities.size() <= 1 && stories.size() >= 8) {
            ProjectEpicPlanner.normalize(draft, mapper);
            activities = array(draft, "activities");
            normalizeActivityReferences(draft, mapper.createArrayNode());
        }
        int target = stories.size() <= 12 ? 3 : stories.size() <= 20 ? 4
                : stories.size() <= 35 ? 6 : stories.size() <= 50 ? 7 : Math.min(10, (stories.size() + 5) / 6);
        Map<String, String> aliases = new HashMap<>();
        ArrayNode originalActivities = activities.deepCopy();
        Map<String, Integer> originalStoryRefs = activityReferenceCounts(stories);
        Map<String, Integer> originalEpicRefs = activityReferenceCounts(array(draft, "epics"));
        Set<String> storyUsedBeforeMerge = new HashSet<>();
        stories.forEach(s -> storyUsedBeforeMerge.add(s.path("activity").asText("")));
        Set<String> epicUsedBeforeMerge = new HashSet<>();
        array(draft, "epics").forEach(e -> epicUsedBeforeMerge.add(e.path("activity").asText("")));
        // Collapse an Epic-only synonym into a uniquely matching Story-backed domain even
        // when the target count has not been exceeded. Distinct cross-cutting domains stay.
        for (int i = activities.size() - 1; i >= 0; i--) {
            JsonNode source = activities.get(i);
            String sourceCode = source.path("code").asText("");
            if (storyUsedBeforeMerge.contains(sourceCode) || !epicUsedBeforeMerge.contains(sourceCode)) continue;
            int best = 0, second = 0; String winner = "";
            for (JsonNode candidate : activities) {
                String candidateCode = candidate.path("code").asText("");
                if (!storyUsedBeforeMerge.contains(candidateCode)) continue;
                int score = activitySemanticScore(activitySemanticText(source), activitySemanticText(candidate));
                if (score > best) { second = best; best = score; winner = candidateCode; }
                else if (score > second) second = score;
            }
            if (best >= 2 && best >= second + 2) {
                aliases.put(sourceCode, winner);
                activities.remove(i);
            }
        }
        while (activities.size() > target) {
            int bestI = -1, bestJ = -1, bestScore = Integer.MIN_VALUE;
            for (int i = 0; i < activities.size(); i++) for (int j = i + 1; j < activities.size(); j++) {
                String a = activitySemanticText(activities.get(i)), b = activitySemanticText(activities.get(j));
                int score = activitySemanticScore(a, b);
                String ca = activityCluster(a), cb = activityCluster(b);
                if (!ca.isBlank() && ca.equals(cb)) score += 100;
                String codeI = activities.get(i).path("code").asText("");
                String codeJ = activities.get(j).path("code").asText("");
                boolean protectedEpicOnly = (epicUsedBeforeMerge.contains(codeI) && !storyUsedBeforeMerge.contains(codeI))
                        || (epicUsedBeforeMerge.contains(codeJ) && !storyUsedBeforeMerge.contains(codeJ));
                if (protectedEpicOnly && activitySemanticScore(a, b) < 2
                        && (ca.isBlank() || !ca.equals(cb))) continue;
                int storyOverlap = 0;
                for (JsonNode story : stories) {
                    String ref = story.path("activity").asText("");
                    if (ref.equalsIgnoreCase(activities.get(i).path("code").asText(""))) storyOverlap++;
                    if (ref.equalsIgnoreCase(activities.get(j).path("code").asText(""))) storyOverlap++;
                }
                score += storyOverlap;
                if (score > bestScore) { bestScore = score; bestI = i; bestJ = j; }
            }
            if (bestI < 0) break;
            ObjectNode keep = (ObjectNode) activities.get(bestI);
            ObjectNode drop = (ObjectNode) activities.get(bestJ);
            String keepCode = keep.path("code").asText(""), dropCode = drop.path("code").asText("");
            String keepName = keep.path("name").asText(keep.path("title").asText(""));
            String dropName = drop.path("name").asText(drop.path("title").asText(""));
            if (!dropName.isBlank() && !keepName.contains(dropName)) keep.put("name", keepName + "与" + dropName);
            aliases.put(dropCode, keepCode);
            activities.remove(bestJ);
        }
        for (JsonNode story : stories) {
            String code = story.path("activity").asText("");
            String resolved = resolveActivityAlias(code, aliases, null);
            ((ObjectNode) story).put("activity", resolved).put("activityCode", resolved);
        }
        // Drop domains left empty after merges, then assign stable contiguous codes.
        Set<String> used = new HashSet<>();
        stories.forEach(s -> { if (!s.path("activity").asText("").isBlank()) used.add(s.path("activity").asText()); });
        // Epic-only domains must participate in retention before empty domains are dropped.
        // Resolve their aliases first so a source such as A12 -> A8 is still counted.
        for (JsonNode epic : array(draft, "epics")) {
            String code = epic.path("activity").asText("");
            code = resolveActivityAlias(code, aliases, null);
            if (!code.isBlank()) used.add(code);
        }
        ArrayNode compact = mapper.createArrayNode();
        Map<String, String> renumber = new HashMap<>();
        int next = 1;
        for (JsonNode activity : activities) {
            String old = activity.path("code").asText("");
            if (!used.contains(old)) continue;
            ObjectNode copy = activity.deepCopy();
            String fresh = "A" + next++;
            renumber.put(old, fresh);
            copy.put("code", fresh).put("id", fresh);
            compact.add(copy);
        }
        for (JsonNode story : stories) {
            String code = story.path("activity").asText("");
            String finalCode = renumber.getOrDefault(code, code);
            ((ObjectNode) story).put("activity", finalCode).put("activityCode", finalCode);
        }
        draft.set("activities", compact);
        ObjectNode finalMapping = mapper.createObjectNode();
        for (JsonNode original : originalActivities) {
            String old = original.path("code").asText("");
            String resolved = resolveActivityAlias(old, aliases, null);
            if (renumber.containsKey(resolved)) finalMapping.put(old, renumber.get(resolved));
        }
        draft.set("activity_reference_remap", finalMapping);
        ArrayNode mappingAudit = mapper.createArrayNode();
        for (JsonNode original : originalActivities) {
            String old = original.path("code").asText("");
            ObjectNode audit = mapper.createObjectNode().put("originalActivity", old)
                    .put("originalName", original.path("name").asText(original.path("title").asText("")))
                    .put("storyReferenceCount", originalStoryRefs.getOrDefault(old, 0))
                    .put("epicReferenceCount", originalEpicRefs.getOrDefault(old, 0));
            ArrayNode chain = audit.putArray("aliasChain");
            resolveActivityAlias(old, aliases, chain);
            String resolved = finalMapping.path(old).asText("");
            ArrayNode candidates = audit.putArray("candidateFinalActivities");
            if (!resolved.isBlank()) candidates.add(resolved);
            audit.put("selectedFinalActivity", resolved)
                    .put("resolutionMethod", resolved.isBlank() ? "unresolved" : "alias_and_stable_renumber")
                    .put("unresolvedReason", resolved.isBlank() ? "原 Activity 未能唯一映射" : "");
            mappingAudit.add(audit);
        }
        draft.set("activity_mapping_audit", mappingAudit);
        remapDetailedEpicActivities(draft, originalActivities, finalMapping);
        return draft;
    }

    private String resolveActivityAlias(String code, Map<String, String> aliases, ArrayNode chain) {
        String resolved = code;
        Set<String> seen = new HashSet<>();
        if (chain != null) chain.add(resolved);
        while (aliases.containsKey(resolved) && seen.add(resolved)) {
            resolved = aliases.get(resolved);
            if (chain != null) chain.add(resolved);
        }
        return resolved;
    }

    private Map<String, Integer> activityReferenceCounts(ArrayNode rows) {
        Map<String, Integer> counts = new TreeMap<>(String.CASE_INSENSITIVE_ORDER);
        for (JsonNode row : rows) {
            String code = row.path("activity").asText("");
            if (!code.isBlank()) counts.merge(code, 1, Integer::sum);
        }
        return counts;
    }

    // Apply the complete old -> final mapping once; old and new A codes may overlap.
    private void remapDetailedEpicActivities(ObjectNode draft, ArrayNode originals, ObjectNode mapping) {
        Set<String> finalIds = new HashSet<>();
        array(draft, "activities").forEach(a -> finalIds.add(a.path("code").asText()));
        for (JsonNode node : array(draft, "epics")) {
            if (!(node instanceof ObjectNode epic)) continue;
            String old = epic.path("activity").asText("");
            String resolved = mapping.path(old).asText("");
            ObjectNode audit = mapper.createObjectNode().put("originalActivity", old)
                    .put("originalName", firstText(epic, "title", "name"));
            ArrayNode candidates = audit.putArray("candidateFinalActivities");
            String method = resolved.isBlank() ? "unresolved" : "activity_alias_map";
            boolean knownOriginal = false;
            for (JsonNode original : originals)
                if (old.equals(original.path("code").asText())) knownOriginal = true;
            if (resolved.isBlank() && !knownOriginal && finalIds.contains(old)) resolved = old;
            if (resolved.isBlank()) {
                JsonNode source = epic;
                for (JsonNode original : originals)
                    if (old.equals(original.path("code").asText())) { source = original; break; }
                int best = 0, second = 0;
                String candidate = "";
                for (JsonNode activity : array(draft, "activities")) {
                    int score = activitySemanticScore(activitySemanticText(source), activitySemanticText(activity));
                    candidates.add(mapper.createObjectNode().put("activity", activity.path("code").asText())
                            .put("score", score));
                    if (score > best) { second = best; best = score; candidate = activity.path("code").asText(); }
                    else if (score > second) second = score;
                }
                if (best >= 2 && best >= second + 2) {
                    resolved = candidate;
                    method = "unique_epic_semantic_fallback";
                }
            }
            if (!old.equals(resolved) && !epic.has("original_activity")) epic.put("original_activity", old);
            epic.put("activity", resolved).put("activityCode", resolved);
            audit.put("selectedFinalActivity", resolved).put("resolutionMethod", method)
                    .put("unresolvedReason", resolved.isBlank() ? "多个候选同等合理或没有语义候选" : "");
            epic.set("activity_mapping", audit);
            if (resolved.isBlank()) epic.put("activity_reference_error",
                    "UNRESOLVED_EPIC_ACTIVITY: 原 Activity 无法唯一映射到最终 Activity");
            else epic.remove("activity_reference_error");
        }
    }

    private void recoverDetailedActivityDefinitions(ObjectNode draft) {
        if (!array(draft, "activities").isEmpty()) return;
        LinkedHashMap<String, String> definitions = new LinkedHashMap<>();
        for (JsonNode epic : array(draft, "epics")) {
            String ref = canonicalActivityCode(epic.path("activity").asText(""));
            if (!ref.isBlank()) definitions.putIfAbsent(ref,
                    firstText(epic, "title", "name", "description"));
        }
        for (JsonNode story : array(draft, "stories")) {
            String ref = canonicalActivityCode(story.path("activity").asText(""));
            if (!ref.isBlank()) definitions.putIfAbsent(ref,
                    firstText(story, "title", "name", "description"));
        }
        if (definitions.isEmpty()) {
            ProjectEpicPlanner.normalize(draft, mapper);
            return;
        }
        ArrayNode recovered = mapper.createArrayNode();
        definitions.entrySet().stream()
                .sorted(Comparator.comparingInt(entry -> activityNumber(entry.getKey())))
                .forEach(entry -> recovered.add(mapper.createObjectNode()
                        .put("id", entry.getKey()).put("code", entry.getKey())
                        .put("name", entry.getValue().isBlank() ? entry.getKey() : entry.getValue())));
        draft.set("activities", recovered);
    }

    private String canonicalActivityCode(String value) {
        String normalized = value == null ? "" : value.trim().toUpperCase(Locale.ROOT);
        if (normalized.matches("A\\d+")) return normalized;
        if (normalized.matches("\\d+")) return "A" + Integer.parseInt(normalized);
        return "";
    }

    private int activityNumber(String code) {
        try { return Integer.parseInt(code.substring(1)); }
        catch (RuntimeException ignored) { return Integer.MAX_VALUE; }
    }

    private String activityCluster(String text) {
        if (containsAny(text, "权限", "用户", "角色", "登录", "身份")) return "identity";
        if (containsAny(text, "活动", "报名", "申请", "审核", "浏览", "搜索")) return "workflow";
        if (containsAny(text, "签到", "签退", "时长", "记录", "状态")) return "tracking";
        if (containsAny(text, "评价", "消息", "通知", "提醒", "个人中心")) return "communication";
        if (containsAny(text, "后台", "管理", "统计", "报表", "异常", "测试")) return "administration";
        if (containsAny(text, "基础", "框架", "数据库", "初始化", "公共")) return "foundation";
        return "";
    }

    private ObjectNode normalizeInternal(JsonNode input, String strategy, String projectId, boolean importMode) {
        String scopedProjectId = ProjectAccessService.normalize(projectId);
        if (!(input instanceof ObjectNode raw)) throw ApiException.badRequest("生成结果必须是 JSON 对象");
        boolean preserveActivityDomains = raw.path("_preserve_activity_domains").asBoolean(false);
        boolean detailedActivityMode = !importMode && !preserveActivityDomains;
        ArrayNode activityNormalizationIssues = mapper.createArrayNode();
        if (importMode) ProjectEpicPlanner.normalizeImportedActivities(raw, mapper);
        else if (detailedActivityMode && needsGeneratedActivityNormalization(raw))
            ProjectEpicPlanner.normalize(raw, mapper);
        if (detailedActivityMode) normalizeActivityReferences(raw, activityNormalizationIssues);
        ArrayNode rawStories = array(raw, "stories");
        if (detailedActivityMode) TaskStoryReferences.canonicalize(raw);
        int generatedTaskBudget = DetailedTaskBudget.forStories(rawStories.size());
        int validatorRejectedByBudget = detailedActivityMode
                ? DetailedTaskBudget.enforce(raw, generatedTaskBudget) : 0;
        ArrayNode rawTasks = array(raw, "tasks");
        ArrayNode scheduleWarnings = mapper.createArrayNode();
        ObjectNode dependencyRepairs = mapper.createObjectNode()
                .put("removedSelfDependencies", 0).put("removedInvalidDependencies", 0)
                .put("removedDuplicateDependencies", 0).put("dependencyCycleRepairs", 0);
        if (detailedActivityMode) {
            Map<String, String> legacyCodes = new LinkedHashMap<>();
            for (JsonNode task : rawTasks) {
                String code = task.path("id").asText(task.path("code").asText(""));
                legacyCodes.put(code.toUpperCase(Locale.ROOT), code);
            }
            Map<String, List<String>> legacyEdges = topLevelDependencies(raw.path("dependencies"));
            for (String successor : legacyEdges.keySet()) if (!legacyCodes.containsKey(successor)) {
                dependencyRepairs.put("removedInvalidDependencies", dependencyRepairs.path("removedInvalidDependencies").asInt() + 1);
                scheduleWarnings.add("已删除不存在任务的依赖: " + successor);
            }
            for (JsonNode node : rawTasks) if (node instanceof ObjectNode task && !task.hasNonNull("generation_identity")) {
                List<String> refs = dependencyRefs(task);
                for (String ref : legacyEdges.getOrDefault(task.path("id").asText().toUpperCase(Locale.ROOT), List.of()))
                    if (!refs.contains(ref)) refs.add(ref);
                task.put("depends_on", cleanDependencyRefs(refs, legacyCodes, task.path("id").asText(),
                        scheduleWarnings, dependencyRepairs, false));
            }
        }
        ObjectNode incomingTaskCodeResolution = null;
        if (detailedActivityMode) {
            // Resolve in the input namespace before public Txx aliases are added.
            // Numeric model IDs and already-numbered supplement IDs can otherwise
            // collide with the public codes created by normalization.
            resolveTaskReferencesBeforeUniqueness(rawTasks, array(raw, "milestones"), array(raw, "dependencies"));
            incomingTaskCodeResolution = ensureUniqueGeneratedTaskCodes(rawTasks,
                    array(raw, "milestones"), array(raw, "dependencies"));
        }
        int originalRelationCount = uniqueRelationCount(rawStories, rawTasks);
        if (rawStories.isEmpty()) throw ApiException.badRequest("至少需要生成一个用户故事");
        if (rawStories.size() > 100 || rawTasks.size() > 300) throw ApiException.badRequest("生成规模超过上限");
        int sprintCount = sprintCount(raw);
        if (importMode) configureImportSprintRanges(raw, rawTasks, sprintCount);

        ObjectNode draft = mapper.createObjectNode();
        draft.put("schema_version", 1);
        draft.set("project", object(raw, "project"));
        draft.set("members", members(scopedProjectId));
        draft.set("sprints", sprints(sprintCount));
        if (raw.path("sprints").isArray()) {
            for (JsonNode supplied : raw.path("sprints")) {
                int number = supplied.path("sprint").asInt(supplied.path("id").asInt(0));
                if (number > 0 && number <= sprintCount && supplied.isObject()) ((ObjectNode) draft.path("sprints").get(number - 1)).setAll((ObjectNode) supplied);
            }
        }
        draft.set("activities", array(raw, "activities"));
        draft.set("epics", array(raw, "epics"));
        draft.set("milestones", array(raw, "milestones"));
        ArrayNode outStories = mapper.createArrayNode();
        ArrayNode outTasks = mapper.createArrayNode();
        Map<String, String> storyIds = new HashMap<>();
        Map<Integer, Integer> suggestedLoad = new HashMap<>();
        ArrayNode availableMembers = members(scopedProjectId);
        sortByStableId(availableMembers, "id");
        int nextStory = "replace".equals(strategy) ? 1 : nextNumber(stories.selectList(new QueryWrapper<Story>().eq("project_id", scopedProjectId)).stream().map(Story::getId).toList(), US);
        Set<String> sourceStoryIds = new HashSet<>();
        Set<String> duplicateStoryIds = new HashSet<>();
        for (int i = 0; i < rawStories.size(); i++) {
            JsonNode source = rawStories.get(i);
            if (!source.isObject()) throw ApiException.badRequest("stories 中存在无效行");
            ObjectNode s = mapper.createObjectNode();
            String rawId = text(source, "id", text(source, "code", "story" + i));
            String rawStoryKey = rawId.trim().toUpperCase(Locale.ROOT);
            boolean duplicateStoryId = !sourceStoryIds.add(rawStoryKey);
            if (duplicateStoryId && !importMode) throw ApiException.badRequest("Story ID 重复: " + rawId);
            if (duplicateStoryId) duplicateStoryIds.add(rawStoryKey);
            String id = String.format("US%02d", nextStory++);
            storyIds.putIfAbsent(rawStoryKey, id);
            storyIds.put(id, id);
            String title = text(source, "title", text(source, "name", "未命名故事"));
            if (importMode) title = splitImportedLabel(title, "US");
            storyIds.put(canonicalTitle(title), id);
            if (title.isBlank()) throw ApiException.badRequest("故事标题不能为空");
            String description = text(source, "description", "");
            String acceptance = text(source, "acceptance", "");
            if (!importMode && description.isBlank()) description = "实现“" + title + "”相关业务能力，并覆盖正常与异常流程。";
            if (!importMode && acceptance.isBlank()) acceptance = "“" + title + "”按需求完成并通过功能验收。";
            s.put("id", id); s.put("title", title); s.put("description", description);
            s.put("acceptance", acceptance.isBlank() ? "待补充验收条件" : acceptance);
            String sourcePriority = text(source, "priority", "");
            s.put("priority", importMode && sourcePriority.isBlank()
                    ? inferImportedPriority(title, description, acceptance)
                    : priority(sourcePriority));
            boolean explicitSprint = source.path("_user_sprint_explicit").asBoolean(false)
                    || source.path("_import_sprint_explicit").asBoolean(false);
            int storySprint;
            if (explicitSprint) {
                if (!source.path("sprint").isIntegralNumber()
                        || source.path("sprint").asInt() < 1 || source.path("sprint").asInt() > sprintCount) {
                    throw ApiException.badRequest(rawId + " 的 Sprint 超出项目范围 1-" + sprintCount);
                }
                storySprint = source.path("sprint").asInt();
            } else {
                storySprint = boundedInt(source, "sprint", 1, 1, sprintCount);
            }
            s.put("sprint", storySprint);
            s.put("sprint_explicit", explicitSprint);
            s.put("sprint_inferred", !explicitSprint);
            if (detailedActivityMode) {
                String activity = firstText(source, "activity", "activityCode", "activity_code");
                s.put("activity", activity).put("activityCode", activity);
                if (source.has("original_activity")) s.put("original_activity", source.path("original_activity").asText());
            }
            else s.put("activity", boundedInt(source, "activity", 1, 1, activityUpperBound(raw)));
            s.put("status", boundedInt(source, "status", 0, 0, 2));
            Integer owner = nullableInt(source, "owner_id", "ownerId");
            boolean missingImportOwner = importMode && owner == null
                    && source.path("owner_name").asText("").isBlank()
                    && source.path("owner").asText("").isBlank();
            if (owner == null) owner = suggestOwner(source, availableMembers, suggestedLoad);
            if (owner != null && users.selectById(owner) == null) {
                if (!importMode) throw ApiException.badRequest("故事负责人不存在: " + owner);
                s.put("_import_invalid_owner", owner);
            }
            if (owner == null) s.putNull("owner_id"); else s.put("owner_id", owner);
            if (owner != null) suggestedLoad.merge(owner, 1, Integer::sum);
            if (duplicateStoryId) s.put("_import_duplicate_id", rawId);
            if (missingImportOwner) s.put("_import_missing_owner", true);
            outStories.add(s);
        }

        Map<String, String> taskIds = new HashMap<>();
        int nextTask = "replace".equals(strategy) ? 1 : nextNumber(tasks.selectList(new QueryWrapper<Task>().eq("project_id", scopedProjectId)).stream().map(Task::getId).toList(), T);
        Set<String> sourceTaskIds = new HashSet<>();
        Set<String> duplicateTaskIds = new HashSet<>();
        for (int i = 0; i < rawTasks.size(); i++) {
            JsonNode source = rawTasks.get(i);
            if (!source.isObject()) throw ApiException.badRequest("tasks 中存在无效行");
            String rawId = text(source, "id", text(source, "code", "task" + i));
            String rawTaskKey = rawId.trim().toUpperCase(Locale.ROOT);
            boolean duplicateTaskId = !sourceTaskIds.add(rawTaskKey);
            if (duplicateTaskId && !importMode) throw ApiException.badRequest("Task ID 重复: " + rawId);
            if (duplicateTaskId) duplicateTaskIds.add(rawTaskKey);
            String id = String.format("T%02d", nextTask++);
            taskIds.putIfAbsent(rawTaskKey, id);
            taskIds.put(id, id);
        }
        Map<String, List<String>> topLevelDependencies = topLevelDependencies(raw.path("dependencies"));
        Set<String> explicitImportDependencyTasks = new HashSet<>();
        for(String successor:topLevelDependencies.keySet())if(!taskIds.containsKey(successor)){
            int removed=topLevelDependencies.get(successor).size();
            dependencyRepairs.put("removedInvalidDependencies",dependencyRepairs.path("removedInvalidDependencies").asInt()+removed);
            scheduleWarnings.add("已删除 "+removed+" 条指向不存在任务 "+successor+" 的顶层依赖");
        }
        int importWeekLimit = totalWeeks(draft.path("sprints"));
        nextTask = "replace".equals(strategy) ? 1 : nextNumber(tasks.selectList(new QueryWrapper<Task>().eq("project_id", scopedProjectId)).stream().map(Task::getId).toList(), T);
        for (int i = 0; i < rawTasks.size(); i++) {
            JsonNode source = rawTasks.get(i);
            if (!source.isObject()) throw ApiException.badRequest("tasks 中存在无效行");
            ObjectNode t = mapper.createObjectNode();
            String rawId = text(source, "id", text(source, "code", "task" + i));
            String id = taskIds.get(rawId.trim().toUpperCase(Locale.ROOT));
            nextTask++;
            String name = text(source, "name", text(source, "title", "未命名任务"));
            if (importMode) name = splitImportedLabel(name, "T");
            if (name.isBlank()) throw ApiException.badRequest("任务名称不能为空");
            Integer explicitOwner = nullableInt(source, "owner_id", "ownerId");
            boolean missingImportOwner = importMode && explicitOwner == null
                    && source.path("owner_name").asText("").isBlank()
                    && source.path("owner").asText("").isBlank();
            boolean missingImportHours = importMode
                    && !source.path("hours").isIntegralNumber()
                    && !source.path("estimated_hours").isIntegralNumber();
            boolean missingImportWeek = importMode
                    && (!source.path("week_start").isIntegralNumber() || !source.path("week_end").isIntegralNumber());
            int owner = explicitOwner == null ? suggestOwner(source, availableMembers, suggestedLoad) : explicitOwner;
            suggestedLoad.merge(owner, Math.max(1, source.path("hours").asInt(1)), Integer::sum);
            boolean invalidOwner = users.selectById(owner) == null;
            if (invalidOwner && !importMode) throw ApiException.badRequest("任务负责人不存在: " + owner);
            int start = boundedInt(source, "week_start", 1, 1, 6);
            int end = boundedInt(source, "week_end", start, 1, 6);
            boolean invalidWeek = importMode && hasInvalidImportWeek(source, importWeekLimit);
            if (invalidWeek) {
                JsonNode rawStart = source.get("week_start"), rawEnd = source.get("week_end");
                if (rawStart != null && rawStart.isIntegralNumber()) start = rawStart.asInt();
                if (rawEnd != null && rawEnd.isIntegralNumber()) end = rawEnd.asInt();
            } else if (start > end) {
                if (!importMode) throw ApiException.badRequest("任务开始周不能晚于结束周: " + id);
                invalidWeek = true;
            }
            String rawStoryRefs = referenceText(source);
            String storyRef = normalizeStoryRefs(rawStoryRefs, storyIds);
            List<String> dependencyRefs = dependencyRefs(source);
            for(String topLevelRef:topLevelDependencies.getOrDefault(rawId.toUpperCase(Locale.ROOT),List.of()))
                if (!containsMappedDependency(dependencyRefs, topLevelRef, taskIds)) dependencyRefs.add(topLevelRef);
            boolean explicitImportDependencies = importMode && source.path("_import_dependencies_explicit").asBoolean(false);
            String depends = cleanDependencyRefs(dependencyRefs, taskIds, id, scheduleWarnings, dependencyRepairs,
                    explicitImportDependencies);
            if(explicitImportDependencies) explicitImportDependencyTasks.add(id);
            t.put("id", id); t.put("name", name); t.put("owner_id", owner);
            t.put("description", text(source, "description", text(source, "detail", "")));
            t.put("priority", priority(text(source, "priority", "Should")));
            t.put("hours", Math.max(0, boundedInt(source, "hours", boundedInt(source, "estimated_hours", 1, 0, 999), 0, 999)));
            t.put("week_start", start); t.put("week_end", end); t.put("story_ref", storyRef).put("storyCode", storyRef);
            t.put("kanban_card_id", t.path("story_ref").asText().split(",")[0]);
            t.put("estimated_hours", t.path("hours").asInt()); t.put("task_type", "feature");
            t.put("depends_on", depends); t.put("status", 0); t.put("progress", 0); t.put("blocked", false);
            if (source.path("_import_owner_explicit").asBoolean(false)) t.put("_import_owner_explicit", true);
            if (source.path("_user_owner_explicit").asBoolean(false)) t.put("_user_owner_explicit", true);
            if (source.path("_import_schedule_explicit").asBoolean(false)) t.put("_import_schedule_explicit", true);
            if (source.path("_user_schedule_explicit").asBoolean(false)) t.put("_user_schedule_explicit", true);
            if (source.path("_import_hours_explicit").asBoolean(false)) t.put("_import_hours_explicit", true);
            if (duplicateTaskIds.contains(rawId.trim().toUpperCase(Locale.ROOT))) t.put("_import_duplicate_id", rawId);
            if (invalidOwner) t.put("_import_invalid_owner", explicitOwner == null ? owner : explicitOwner);
            if (invalidWeek) t.put("_import_invalid_week", true);
            if (missingImportOwner) t.put("_import_missing_owner", true);
            if (missingImportHours) t.put("_import_missing_hours", true);
            if (missingImportWeek) t.put("_import_missing_week", true);
            String invalidStoryRefs = unknownStoryRefs(rawStoryRefs, storyIds);
            if (!rawStoryRefs.isBlank()) t.put("model_story_refs", rawStoryRefs);
            if (!invalidStoryRefs.isBlank()) {
                if (importMode) t.put("_import_invalid_story_refs", invalidStoryRefs);
                else t.put("invalid_story_refs", invalidStoryRefs);
            }
            if (!importMode) copyGeneratedTaskProvenance(t, source);
            if (detailedActivityMode) {
                t.put("invalid_story_refs", TaskStoryReferences.union(t.path("invalid_story_refs").asText(""), invalidStoryRefs));
                if (t.path("invalid_story_refs").asText().isBlank()) t.remove("invalid_story_refs");
                for (JsonNode entryNode : t.path(TaskStoryReferences.AUDIT)) {
                    ObjectNode entry = (ObjectNode) entryNode;
                    if (entry.path("mapped").asBoolean(false)
                            || "PENDING_NORMALIZATION".equals(entry.path("failureReason").asText())) {
                        String parsed = normalizeStoryRefs(entry.path("parsedReference").asText(), storyIds);
                        entry.put("parsedReference", parsed).put("mapped", !parsed.isBlank())
                                .put("failureReason", parsed.isBlank() ? "UNKNOWN_STORY_REFERENCE" : "");
                        if (parsed.isBlank()) t.put("invalid_story_refs", TaskStoryReferences.union(
                                t.path("invalid_story_refs").asText(""), entry.path("reference").asText()));
                    }
                }
            }
            outTasks.add(t);
        }
        ArrayNode autoCompletedItems = mapper.createArrayNode();
        ArrayNode sprintInferenceIssues = mapper.createArrayNode();
        ObjectNode taskFeasibility = mapper.createObjectNode();
        ArrayNode normalizedMilestones = mapper.createArrayNode();
        for (JsonNode milestone : array(raw, "milestones")) {
            ObjectNode m = milestone.deepCopy();
            JsonNode related = m.path("related_task_ids");
            String codes = related.isArray() ? java.util.stream.StreamSupport.stream(related.spliterator(), false).map(JsonNode::asText).collect(java.util.stream.Collectors.joining(",")) : related.asText("");
            String invalidTaskRefs = importMode ? unknownReferences(codes, taskIds) : "";
            if (!invalidTaskRefs.isBlank()) m.put("_import_invalid_task_refs", invalidTaskRefs);
            m.put("related_task_ids", importMode ? refsIgnoringUnknown(codes, taskIds) : refs(codes, taskIds));
            normalizedMilestones.add(m);
        }

        ObjectNode qualityAdjustments;
        if (!importMode && !preserveActivityDomains) {
            draft.set("stories", outStories);
            draft.set("tasks", outTasks);
            consolidateDetailedActivities(draft);
            completeGeneratedStoryTaskLinks(outStories, outTasks, availableMembers,
                    (ArrayNode) draft.path("sprints"), scheduleWarnings, autoCompletedItems);
            normalizeGeneratedPriorities(outStories, scheduleWarnings, autoCompletedItems);
            completeGeneratedMilestones(normalizedMilestones, outTasks, scheduleWarnings, autoCompletedItems);

            taskFeasibility = deterministicTaskFeasibility(outStories, outTasks,
                    (ArrayNode) draft.path("sprints"), availableMembers);
            ObjectNode taskCompaction = compactDetailedTasks(outStories, outTasks, normalizedMilestones, taskFeasibility);
            resolveTaskReferencesBeforeUniqueness(outTasks, normalizedMilestones,
                    array(draft, "dependencies"));
            ObjectNode taskCodeUniqueness = ensureUniqueGeneratedTaskCodes(outTasks, normalizedMilestones,
                    array(draft, "dependencies"));
            if (incomingTaskCodeResolution != null) {
                for (String field : List.of("duplicateRawCodes", "rawCodeCandidateIdentities",
                        "uniqueBareCodeResolvedReferenceCount", "sourceAwareResolvedReferenceCount"))
                    taskCodeUniqueness.set(field, incomingTaskCodeResolution.path(field).deepCopy());
                ArrayNode unresolved = (ArrayNode) incomingTaskCodeResolution.path("unresolvedReferences").deepCopy();
                for (JsonNode item : taskCodeUniqueness.path("unresolvedReferences"))
                    if (!java.util.stream.StreamSupport.stream(unresolved.spliterator(), false).anyMatch(item::equals)) unresolved.add(item.deepCopy());
                taskCodeUniqueness.set("unresolvedReferences", unresolved);
                taskCodeUniqueness.put("unresolvedDuplicateReferenceCount", unresolved.size())
                        .put("preventedFanoutCount", Math.max(taskCodeUniqueness.path("preventedFanoutCount").asInt(),
                                incomingTaskCodeResolution.path("preventedFanoutCount").asInt()))
                        .put("sufficient", taskCodeUniqueness.path("sufficient").asBoolean()
                                && incomingTaskCodeResolution.path("sufficient").asBoolean());
            }
            if (taskCodeUniqueness.path("remainingDuplicateCodeCount").asInt() != 0)
                throw ApiException.badRequest("DUPLICATE_TASK_CODE_CONFLICT: duplicate codes remain after normalization");
            qualityAdjustments = mapper.createObjectNode();
            ObjectNode taskGenerationAudit = raw.path("_task_generation_audit").isObject()
                    ? (ObjectNode) raw.path("_task_generation_audit").deepCopy() : mapper.createObjectNode();
            ObjectNode stageCoverageAudit = detailedTaskStageCoverage(draft);
            taskGenerationAudit.put("taskBudget", generatedTaskBudget)
                    .put("validatorRejectedByBudgetCount", validatorRejectedByBudget)
                    .put("finalTaskCount", outTasks.size())
                    .put("remainingGapStoryCount", stageCoverageAudit.path("stageCoverageGapStoryCount").asInt())
                    .put("remainingStageGapCount", countStageGaps(stageCoverageAudit.path("stageCoverageGaps")));
            taskGenerationAudit.put("initialCrossStageReferenceUnresolvedCount",
                    taskGenerationAudit.path("crossStageReferenceUnresolvedCount").asInt(0));
            taskGenerationAudit.put("crossStageReferenceUnresolvedCount",
                    taskCodeUniqueness.path("unresolvedReferences").size());
            taskGenerationAudit.put("crossStageReferenceResolvedCount",
                    taskCodeUniqueness.path("sourceAwareResolvedReferenceCount").asInt(0));
            for (String field : List.of("storyCount", "taskCount", "storiesWithOneTask", "storiesWithTwoTasks",
                    "storiesWithThreeOrMoreTasks", "averageTasksPerStory", "stageCoverageGapStoryCount",
                    "coarseCompositeTaskCount"))
                taskGenerationAudit.set(field, stageCoverageAudit.path(field).deepCopy());
            taskGenerationAudit.put("supplementedStageTaskCount", Math.max(
                    taskGenerationAudit.path("supplementedStageTaskCount").asInt(0),
                    stageCoverageAudit.path("supplementedStageTaskCount").asInt(0)));
            taskGenerationAudit.set("stageCoverageGaps", stageCoverageAudit.path("stageCoverageGaps").deepCopy());
            taskGenerationAudit.set("storyStageContracts", stageCoverageAudit.path("storyStageContracts").deepCopy());
            for (String field : List.of("initialTaskCount", "continuationMergedTaskCount", "supplementGapCount",
                    "supplementGeneratedTaskCount", "supplementAcceptedTaskCount",
                    "supplementRejectedByBudgetCount", "semanticDuplicateMergedCount",
                    "crossStageReferenceResolvedCount", "crossStageReferenceUnresolvedCount",
                    "supplementAttemptCount", "supplementRetryCount", "plannedGapStoryCount", "plannedStageCount",
                    "remainingBudgetBeforeSupplement", "attempt1ParsedTasks", "attempt2ParsedTasks",
                    "generatedSupplementTaskCount", "acceptedSupplementTaskCount", "rejectedSupplementTaskCount",
                    "supplementBatchCount", "supplementSuccessfulBatchCount", "supplementFailedBatchCount"))
                if (!taskGenerationAudit.has(field)) taskGenerationAudit.put(field, 0);
            if (!taskGenerationAudit.has("supplementBatches"))
                taskGenerationAudit.set("supplementBatches", mapper.createArrayNode());
            if (!taskGenerationAudit.has("supplementTriggered")) taskGenerationAudit.put("supplementTriggered", false);
            if (!taskGenerationAudit.has("attempt1ErrorCode")) taskGenerationAudit.put("attempt1ErrorCode", "NOT_ATTEMPTED");
            if (!taskGenerationAudit.has("attempt2ErrorCode")) taskGenerationAudit.put("attempt2ErrorCode", "NOT_ATTEMPTED");
            qualityAdjustments.set("taskGeneration", taskGenerationAudit);
            qualityAdjustments.set("taskCompaction", taskCompaction);
            qualityAdjustments.set("taskCodeUniqueness", taskCodeUniqueness);
            qualityAdjustments.set("taskCodeResolution", taskCodeUniqueness.deepCopy());
            ObjectNode relationStats = finalRelationClosure(outStories, outTasks, scheduleWarnings, autoCompletedItems);
            relationStats.put("modelRelationCount", originalRelationCount);
            relationStats.put("finalUniqueRelationCount", uniqueRelationCount(outStories, outTasks));
            relationStats.put("actualNewUniqueRelationCount",
                    Math.max(0, uniqueRelationCount(outStories, outTasks) - originalRelationCount));
            qualityAdjustments.set("relationClosureStats", relationStats);
            ObjectNode finalRelationCheck = finalRelationCheck(outStories, outTasks);
            qualityAdjustments.set("finalRelationCheck", finalRelationCheck);
            logStage("finalRelationClosure", outStories, outTasks, availableMembers);

            markUnresolvedStoryRelations(outStories, outTasks, finalRelationCheck);
            Map<String, Integer> beforeSprintInference = storySprintSnapshot(outStories);
            inferFinalStorySprints(outStories, outTasks, (ArrayNode) draft.path("sprints"), sprintInferenceIssues);
            ArrayNode initialStoryMoves = storySprintMoves(beforeSprintInference, outStories);

            // Story Sprint is now stable enough to perform the final, developer-only capacity gate.
            // Any safe semantic compaction must happen before dependencies are classified and frozen.
            ObjectNode finalCapacityFeasibility = finalDetailedCapacityFeasibility(
                    outStories, outTasks, normalizedMilestones, (ArrayNode) draft.path("sprints"),
                    availableMembers, taskFeasibility);
            relationStats.put("finalUniqueRelationCount", uniqueRelationCount(outStories, outTasks));
            relationStats.put("actualNewUniqueRelationCount",
                    Math.max(0, uniqueRelationCount(outStories, outTasks) - originalRelationCount));
            finalRelationCheck = finalRelationCheck(outStories, outTasks);

            excludeUnresolvedTasksFromDependencies(outTasks, scheduleWarnings);
            inferDependencies(outTasks, scheduleWarnings);
            ObjectNode dependencyCompaction = compactDetailedDependencies(outTasks, scheduleWarnings);
            repairDependencyCycles(outTasks, explicitImportDependencyTasks, scheduleWarnings, dependencyRepairs);
            validateDependencyGraph(outTasks, false);
            ArrayNode initialViolations = coreSprintOrderViolations(outStories, outTasks);
            ObjectNode reconciliation = dependencyAwareStorySprintReconciliation(
                    outStories, outTasks, (ArrayNode) draft.path("sprints"), initialViolations);
            ArrayNode finalViolations = coreSprintOrderViolations(outStories, outTasks);
            reconciliation.set("remainingViolations", finalViolations.deepCopy());

            // Preserve generation/relation audit fields already collected by this final pass.
            // Mutating the same object avoids silently dropping taskGeneration diagnostics.
            qualityAdjustments.set("taskCodeUniqueness", taskCodeUniqueness);
            qualityAdjustments.set("taskCodeResolution", taskCodeUniqueness.deepCopy());
            qualityAdjustments.set("taskCompaction", taskCompaction);
            qualityAdjustments.set("dependencyCompaction", dependencyCompaction);
            qualityAdjustments.set("taskFeasibility", taskFeasibility);
            qualityAdjustments.set("finalCapacityFeasibility", finalCapacityFeasibility);
            qualityAdjustments.set("autoCompletedItems", autoCompletedItems);
            qualityAdjustments.set("relationClosureStats", relationStats);
            qualityAdjustments.set("finalRelationCheck", finalRelationCheck);
            qualityAdjustments.set("initialStorySprintAdjustments", initialStoryMoves);
            qualityAdjustments.set("sprintOrderViolations", initialViolations);
            qualityAdjustments.set("dependencyAwareSprintReconciliation", reconciliation);
            qualityAdjustments.set("reassignedStorySprints", reconciliation.path("moves").deepCopy());
            qualityAdjustments.set("sprintBalanceAdjustments", reconciliation.path("moves").deepCopy());
            ObjectNode preScheduleCheck = preScheduleConsistencyCheck(
                    outStories, outTasks, finalRelationCheck, finalViolations);
            qualityAdjustments.set("preScheduleConsistencyCheck", preScheduleCheck);
            applyPreScheduleConflicts(outTasks, finalViolations);

            Map<String, Integer> frozenStorySprints = storySprintSnapshot(outStories);
            Map<String, String> frozenDependencies = dependencySnapshot(outTasks);
            Map<String, String> frozenRelations = relationSnapshot(outTasks);
            qualityAdjustments.set("activityReferenceRemap", draft.path("activity_reference_remap").deepCopy());
            qualityAdjustments.set("activityMappingAudit", draft.path("activity_mapping_audit").deepCopy());
            qualityAdjustments.set("activityReferenceAssertion", finalActivityCheck(draft, outStories));
            String frozenActivities = activitySnapshot(draft, outStories);
            qualityAdjustments.set("storySprintFreeze", mapper.createObjectNode().put("frozen", true));
            qualityAdjustments.set("dependencyFreeze", mapper.createObjectNode().put("frozen", true));
            qualityAdjustments.set("activityFreeze", mapper.createObjectNode().put("frozen", true));
            qualityAdjustments.putArray("taskScheduleAdjustments");
            qualityAdjustments.putArray("workloadBalanceAdjustments");
            qualityAdjustments.putArray("reassignedTaskOwners");
            qualityAdjustments.putArray("unresolvedWorkloadRisks");
            finalCapacityBalance(outTasks, outStories, (ArrayNode) draft.path("sprints"), availableMembers,
                    (ArrayNode) qualityAdjustments.path("taskScheduleAdjustments"),
                    (ArrayNode) qualityAdjustments.path("workloadBalanceAdjustments"),
                    (ArrayNode) qualityAdjustments.path("reassignedTaskOwners"),
                    (ArrayNode) qualityAdjustments.path("unresolvedWorkloadRisks"), scheduleWarnings, true,
                    qualityAdjustments);
            ObjectNode freezeCheck = mapper.createObjectNode()
                    .put("storySprintChangedAfterFreeze", !frozenStorySprints.equals(storySprintSnapshot(outStories)))
                    .put("dependenciesChangedAfterFreeze", !frozenDependencies.equals(dependencySnapshot(outTasks)))
                    .put("relationsChangedAfterFreeze", !frozenRelations.equals(relationSnapshot(outTasks)))
                    .put("activitiesChangedAfterFreeze", !frozenActivities.equals(activitySnapshot(draft, outStories)))
                    .put("readOnly", true);
            qualityAdjustments.set("freezeCheck", freezeCheck);
            if (freezeCheck.path("storySprintChangedAfterFreeze").asBoolean()
                    || freezeCheck.path("dependenciesChangedAfterFreeze").asBoolean()
                    || freezeCheck.path("relationsChangedAfterFreeze").asBoolean()
                    || freezeCheck.path("activitiesChangedAfterFreeze").asBoolean()) {
                preScheduleCheck.withArray("blockingIssues").add("冻结后 Story Sprint、依赖、Story 关系或 Activity 发生变化");
                preScheduleCheck.put("sufficient", false);
            }
            qualityAdjustments.set("finalSprintCheck", finalSprintCheck(outStories, outTasks, (ArrayNode) draft.path("sprints")));
            qualityAdjustments.set("finalActivityCheck", finalActivityCheck(draft, outStories));
            logStage("finalCapacityBalance", outStories, outTasks, availableMembers);
        } else {
            if (!importMode) completeGeneratedMilestones(normalizedMilestones, outTasks, scheduleWarnings, autoCompletedItems);
            Map<String, Integer> initialStorySprints = storySprintSnapshot(outStories);
            inferFinalStorySprints(outStories, outTasks, (ArrayNode) draft.path("sprints"), sprintInferenceIssues);
            if (!importMode) {
                completeGeneratedStoryTaskLinks(outStories, outTasks, availableMembers,
                        (ArrayNode) draft.path("sprints"), scheduleWarnings, autoCompletedItems);
                normalizeGeneratedPriorities(outStories, scheduleWarnings, autoCompletedItems);
            }
            inferDependencies(outTasks, scheduleWarnings);
            repairDependencyCycles(outTasks, explicitImportDependencyTasks, scheduleWarnings, dependencyRepairs);
            validateDependencyGraph(outTasks, false);
            int totalWeeks = totalWeeks(draft.path("sprints"));
            repairDependencySchedule(outTasks, totalWeeks, scheduleWarnings);
            qualityAdjustments = finalQualityPass(outStories, outTasks,
                    (ArrayNode) draft.path("sprints"), availableMembers, importMode, scheduleWarnings);
            qualityAdjustments.set("taskFeasibility", taskFeasibility);
            qualityAdjustments.set("autoCompletedItems", autoCompletedItems);
            repairDependencySchedule(outTasks, totalWeeks, scheduleWarnings);
            reconcileDelayedStories(outStories, outTasks, (ArrayNode) draft.path("sprints"), qualityAdjustments);
            rebalanceStorySprintDistribution(outStories, outTasks, (ArrayNode) draft.path("sprints"), qualityAdjustments);
            rebalanceSchedules(outTasks, outStories, (ArrayNode) draft.path("sprints"), availableMembers,
                    (ArrayNode) qualityAdjustments.path("taskScheduleAdjustments"),
                    (ArrayNode) qualityAdjustments.path("workloadBalanceAdjustments"));
            ArrayNode finalStoryMoves = storySprintMoves(initialStorySprints, outStories);
            qualityAdjustments.set("reassignedStorySprints", finalStoryMoves);
            qualityAdjustments.set("sprintBalanceAdjustments", finalStoryMoves.deepCopy());
            workloadWarnings(outTasks, availableMembers, (ArrayNode) qualityAdjustments.path("unresolvedWorkloadRisks"));
            validateDependencySchedule(outTasks, false);
        }
        draft.set("stories", outStories);
        draft.set("tasks", outTasks);
        draft.set("sprintInferenceIssues", sprintInferenceIssues);
        if (!importMode && !preserveActivityDomains) {
            ObjectNode finalCoverage = detailedTaskStageCoverage(draft);
            ObjectNode taskGeneration = qualityAdjustments.with("taskGeneration");
            taskGeneration.put("remainingGapStoryCount", finalCoverage.path("stageCoverageGapStoryCount").asInt())
                    .put("remainingStageGapCount", countStageGaps(finalCoverage.path("stageCoverageGaps")));
            taskGeneration.set("stageCoverageGaps", finalCoverage.path("stageCoverageGaps").deepCopy());
            taskGeneration.set("storyStageContracts", finalCoverage.path("storyStageContracts").deepCopy());
        }
        draft.set("qualityAdjustments", qualityAdjustments);
        if (detailedActivityMode) qualityAdjustments.set("taskStoryReferences", TaskStoryReferences.summary(outTasks));
        draft.set("milestones", normalizedMilestones);
        draft.set("dependencies", dependencies(outTasks));
        draft.set("dependencyRepairs", dependencyRepairs);
        draft.set("uml", uml(outStories, members(scopedProjectId)));
        ObjectNode risks = risks(outStories, outTasks, strategy, scopedProjectId,
                importMode ? draft.path("sprints") : null);
        draft.set("risks", risks);
        ArrayNode allWarnings = warnings(risks);
        sprintInferenceIssues.forEach(allWarnings::add);
        ArrayNode details = mapper.createArrayNode();
        scheduleWarnings.forEach(details::add);
        activityNormalizationIssues.forEach(details::add);
        draft.set("details", details);
        draft.set("warnings", summarizeGeneratedWarnings(allWarnings, scheduleWarnings, 0, autoCompletedItems, draft.path("qualityAdjustments").path("relationClosureStats")));
        draft.put("story_count", outStories.size()); draft.put("task_count", outTasks.size());
        ObjectNode completeness = completenessReport(draft, importMode);
        dependencyRepairs.fields().forEachRemaining(entry -> completeness.set(entry.getKey(),entry.getValue()));
        completeness.set("details", details.deepCopy());
        completeness.set("warnings", summarizeGeneratedWarnings(completeness.path("warnings").isArray()
                ? (ArrayNode) completeness.path("warnings") : mapper.createArrayNode(), details, 0, autoCompletedItems,
                draft.path("qualityAdjustments").path("relationClosureStats")));
        draft.set("completeness", completeness);
        if (!completeness.path("sufficient").asBoolean()) {
            completeness.path("hardBlockingIssues").forEach(issue -> allWarnings.add(issue.asText()));
            allWarnings.add("规划完整度不足，需要人工补充");
            draft.set("warnings", deduplicateWarnings(allWarnings));
        }
        return draft;
    }

    public void validateForExecution(ObjectNode draft, String strategy) { validateForExecution(draft, strategy, "NEW-LOCAL"); }

    public void validateForExecution(ObjectNode draft, String strategy, String projectId) {
        String scopedProjectId = ProjectAccessService.normalize(projectId);
        if (!draft.path("schema_version").isInt() || !draft.path("stories").isArray() || !draft.path("tasks").isArray()) throw ApiException.conflict("生成草案格式已失效，请重新生成");
        List<String> invalidActivityStories = invalidActivityStoryReferences(draft);
        if (!invalidActivityStories.isEmpty())
            throw ApiException.conflict("草案包含无效 Activity 引用：" + String.join(",", invalidActivityStories));
        if (duplicateActivityCodeCount(draft) > 0)
            throw ApiException.conflict("草案 Activity code 重复，无法安全执行");
        Set<String> storyIds = new HashSet<>();
        for (JsonNode s : draft.path("stories")) { String id = s.path("id").asText(); if (!storyIds.add(id) || existingStory(scopedProjectId, id) && !"replace".equals(strategy)) throw ApiException.conflict("草案编号已被占用，请重新生成"); }
        Set<String> taskIds = new HashSet<>();
        for (JsonNode t : draft.path("tasks")) { String id = t.path("id").asText(); if (!taskIds.add(id) || existingTask(scopedProjectId, id) && !"replace".equals(strategy)) throw ApiException.conflict("草案任务编号已被占用，请重新生成"); if (users.selectById(t.path("owner_id").asInt()) == null) throw ApiException.conflict("草案负责人已不存在"); }
        validateDependencyGraph(draft.path("tasks"), true);
        validateDependencySchedule(draft.path("tasks"), true);
    }

    /** Draft-only quality gate. It reports gaps without rejecting a user's editable draft. */
    public ObjectNode completenessReport(ObjectNode draft) {
        return completenessReport(draft, false);
    }

    /** Deterministic implementation-stage coverage for generated detailed drafts. */
    public ObjectNode detailedTaskStageCoverage(ObjectNode draft) {
        ArrayNode stories = array(draft, "stories");
        ArrayNode tasks = array(draft, "tasks");
        Map<String, List<JsonNode>> byStory = new TreeMap<>(String.CASE_INSENSITIVE_ORDER);
        for (JsonNode task : tasks) for (String ref : task.path("story_ref").asText("").split("[,，;；\\s]+"))
            if (!ref.isBlank()) byStory.computeIfAbsent(ref.trim(), ignored -> new ArrayList<>()).add(task);
        int one = 0, two = 0, threePlus = 0, coarse = 0;
        ArrayNode gaps = mapper.createArrayNode();
        ArrayNode contracts = mapper.createArrayNode();
        for (JsonNode story : stories) {
            String id = story.path("id").asText("");
            List<JsonNode> rows = byStory.getOrDefault(id, List.of());
            if (rows.size() == 1) one++; else if (rows.size() == 2) two++; else if (rows.size() >= 3) threePlus++;
            StageContract contract = implementationStageContract(story);
            Set<String> required = contract.requiredStages();
            Set<String> present = new TreeSet<>();
            boolean coarseStory = false;
            for (JsonNode task : rows) {
                String text = taskSemanticText(task).toLowerCase(Locale.ROOT);
                Set<String> stages = implementationStages(text);
                // A composite task is coarse-grained, but its explicitly detected
                // implementation surfaces still count toward story coverage.
                present.addAll(stages);
                if (stages.size() >= 2 && isCoarseCompositeTask(text)) {
                    coarse++; coarseStory = true;
                }
            }
            Set<String> missing = new TreeSet<>(required);
            missing.removeAll(present);
            if (!rows.isEmpty() && present.isEmpty() && !coarseStory) missing.clear();
            ObjectNode contractAudit = mapper.createObjectNode().put("storyId", id)
                    .put("storyTitle", firstText(story, "title", "name"))
                    .put("stageContractType", contract.type())
                    .put("classificationEvidence", contract.evidence())
                    .put("coarseComposite", coarseStory);
            contractAudit.set("requiredStages", mapper.valueToTree(required));
            contractAudit.set("presentStages", mapper.valueToTree(present));
            contractAudit.set("missingStages", mapper.valueToTree(missing));
            contracts.add(contractAudit);
            if (!missing.isEmpty()) {
                gaps.add(contractAudit.deepCopy());
            }
        }
        ObjectNode result = mapper.createObjectNode().put("storyCount", stories.size()).put("taskCount", tasks.size())
                .put("storiesWithOneTask", one).put("storiesWithTwoTasks", two)
                .put("storiesWithThreeOrMoreTasks", threePlus)
                .put("averageTasksPerStory", stories.isEmpty() ? 0.0 : (double) tasks.size() / stories.size())
                .put("stageCoverageGapStoryCount", gaps.size()).put("coarseCompositeTaskCount", coarse)
                .put("supplementedStageTaskCount", draft.path("_task_generation_audit")
                        .path("supplementedStageTaskCount").asInt(0));
        result.set("stageCoverageGaps", gaps);
        result.set("storyStageContracts", contracts);
        return result;
    }

    private Set<String> requiredImplementationStages(JsonNode story) {
        return implementationStageContract(story).requiredStages();
    }

    private StageContract implementationStageContract(JsonNode story) {
        String title = firstText(story, "title", "name").toLowerCase(Locale.ROOT);
        String description = firstText(story, "description", "acceptance").toLowerCase(Locale.ROOT);
        Set<String> required = new TreeSet<>();
        boolean explicitBackend = containsAny(title, "接口", "api", "后端", "service", "服务层");
        boolean explicitFrontend = containsAny(title, "页面", "界面", "入口", "表单", "组件", "前端", "交互");
        boolean explicitDocumentation = containsAny(title, "文档", "说明书", "交付文档")
                || containsAny(description, "编写文档", "说明书", "交付文档");
        if (containsAny(title, "数据库设计", "数据库表", "表结构", "schema", "数据模型", "migration", "迁移", "数据库初始化", "初始化脚本")) {
            required.add("data");
            if (explicitBackend) required.add("backend");
            if (explicitFrontend) required.add("frontend");
            return new StageContract("DATA_SCHEMA", required,
                    "标题命中数据库/schema/表结构/初始化；仅显式接口或页面扩展实现面");
        }
        if (containsAny(title, "系统测试", "验收", "验收测试", "缺陷修复", "回归测试", "测试")) {
            required.add("test");
            if (explicitDocumentation) required.add("documentation");
            return new StageContract("TEST_ACCEPTANCE", required,
                    explicitDocumentation ? "标题命中测试/验收且明确要求文档" : "标题命中测试/验收/缺陷修复");
        }
        if (containsAny(title, "文档", "说明书", "交付文档") && !explicitBackend && !explicitFrontend)
            return new StageContract("DOCUMENTATION", Set.of("documentation"), "标题明确属于文档交付");
        if (containsAny(title, "项目配置", "环境配置", "基础框架", "工程搭建")
                && !explicitBackend && !explicitFrontend)
            return new StageContract("CONFIGURATION", Set.of("configuration"), "标题明确属于配置或工程搭建");
        // Synthetic/import-like labels such as "业务 Story 1" do not provide enough
        // semantic evidence to infer implementation surfaces.
        if (!containsAny(title, "用户", "学生", "负责人", "管理员", "活动", "报名", "登录", "注册", "签到", "签退",
                "时长", "个人中心", "权限", "统计", "异常", "评价", "消息", "通知", "提醒",
                "接口", "页面", "表单", "功能", "管理"))
            return new StageContract("UNCLASSIFIED", Set.of(), "标题缺少可确定实现面的业务语义");
        boolean visible = containsAny(title, "页面", "界面", "入口", "表单", "组件", "前端", "交互", "列表", "详情",
                "个人中心", "展示", "查看", "查询", "搜索", "筛选", "看板");
        boolean backendService = containsAny(title, "消息生成", "提醒生成", "通知生成", "服务时长生成", "生成服务时长",
                "状态流转", "状态管理", "异常处理", "权限校验", "访问控制")
                || (title.contains("规则") && containsAny(title, "审核", "取消", "权限", "报名", "状态"));
        boolean ruleOnly = containsAny(title, "校验", "限制", "规则", "触发场景", "异常处理")
                && !visible && !explicitFrontend;
        String type;
        String evidence;
        if (visible || explicitFrontend) {
            required.add("backend");
            required.add("frontend");
            type = "USER_FACING";
            evidence = "标题明确包含页面/列表/详情/查看/展示等用户可见能力";
        } else if (ruleOnly || backendService || explicitBackend) {
            required.add("backend");
            type = "BACKEND_SERVICE_RULE";
            evidence = ruleOnly ? "标题明确为校验/限制/规则/异常处理且无用户可见界面语义"
                    : "标题明确属于后台生成、状态/规则/异常/权限或接口能力";
        } else {
            required.add("backend");
            required.add("frontend");
            type = "USER_WORKFLOW";
            evidence = "标题属于用户发起的业务流程且未限定单一实现面";
        }
        if (containsAny(title, "权限", "越权", "状态流转", "校验", "验证", "规则", "限制", "冲突", "截止", "人数上限"))
            required.add("validation");
        return new StageContract(type, required, evidence);
    }

    private record StageContract(String type, Set<String> requiredStages, String evidence) {}

    private int countStageGaps(JsonNode gaps) {
        int count = 0;
        for (JsonNode gap : gaps) count += gap.path("missingStages").size();
        return count;
    }

    private Set<String> implementationStages(String text) {
        Set<String> stages = new TreeSet<>();
        if (containsAny(text, "数据库", "数据表", "表结构", "schema", "dao", "repository")) stages.add("data");
        if (containsAny(text, "接口", "api", "后端", "service", "服务层", "生成", "流转", "控制", "记录", "处理", "管理逻辑", "查询逻辑", "消息生成", "时长生成")) stages.add("backend");
        boolean visibleView = containsAny(text, "页面", "前端", "界面", "组件", "表单", "交互", "列表", "详情页", "个人中心", "展示", "统计看板", "消息列表")
                || (text.contains("查看") && containsAny(text, "个人", "学生", "负责人", "详情", "服务时长", "累计时长"));
        if (visibleView) stages.add("frontend");
        if (text.contains("异常") && containsAny(text, "处理", "限制", "拦截")) stages.add("backend");
        if (containsAny(text, "校验", "验证", "权限", "越权", "规则", "限制", "状态流转", "异常拦截", "异常限制")) stages.add("validation");
        if (containsAny(text, "测试", "验收", "验证用例")) stages.add("test");
        if (containsAny(text, "文档", "说明书")) stages.add("documentation");
        if (containsAny(text, "配置", "基础框架", "工程搭建")) stages.add("configuration");
        return stages;
    }

    private boolean isCoarseCompositeTask(String text) {
        return (containsAny(text, "接口与页面", "接口及页面", "接口和页面", "接口与前端", "接口及前端", "接口和前端")
                || (containsAny(text, "接口", "api") && containsAny(text, "页面", "前端", "界面"))
                || (containsAny(text, "接口", "api") && containsAny(text, "校验", "验证", "规则")));
    }

    /**
     * Reports completeness with a separate policy for user-owned Excel/CSV drafts.
     * Import mode treats planning-quality recommendations as warnings and reserves
     * hard blocking for data integrity errors that would make confirmation unsafe.
     */
    public ObjectNode completenessReport(ObjectNode draft, boolean importMode) {
        int stories = draft.path("stories").isArray() ? draft.path("stories").size() : 0;
        int tasks = draft.path("tasks").isArray() ? draft.path("tasks").size() : 0;
        int sprints = draft.path("sprints").isArray() ? draft.path("sprints").size() : 1;
        int domains = draft.path("activities").isArray() ? draft.path("activities").size() : 0;
        int members = draft.path("members").isArray() ? draft.path("members").size() : 0;
        boolean simple = "simple".equals(draft.path("project").path("complexity").asText()) && !draft.path("project").path("complexity_note").asText("").isBlank() && domains <= 1;
        int storyTarget = sprints == 3 && !simple && domains >= 2 ? 25 : (sprints >= 3 && !simple ? Math.min(30, Math.max(18, sprints * 5 + domains)) : Math.max(3, sprints * 3));
        int minStories = sprints == 3 && !simple && domains >= 2 ? 22 : (sprints >= 3 && !simple ? Math.min(30, Math.max(16, sprints * 4 + domains * 2 + Math.max(0, members - 4))) : Math.max(3, sprints * 3));
        int minTasks = sprints >= 3 && !simple ? Math.min(40, Math.max(20, minStories + Math.max(4, domains * 2))) : Math.max(4, minStories + 2);
        int emptyDescription = 0, emptyAcceptance = 0, emptyOwner = 0, storiesWithTask = 0, relationCount = 0;
        List<String> emptyDescriptionIds = new ArrayList<>(), emptyAcceptanceIds = new ArrayList<>(), emptyOwnerIds = new ArrayList<>();
        Set<String> covered = new HashSet<>();
        for (JsonNode s : draft.path("stories")) {
            String storyId=s.path("id").asText("未知 Story");
            if (s.path("description").asText("").isBlank()) { emptyDescription++; emptyDescriptionIds.add(storyId); }
            if (s.path("acceptance").asText("").isBlank() || "待补充验收条件".equals(s.path("acceptance").asText())) { emptyAcceptance++; emptyAcceptanceIds.add(storyId); }
            if (s.path("owner_id").isMissingNode() || s.path("owner_id").isNull()) { emptyOwner++; emptyOwnerIds.add(storyId); }
        }
        for (JsonNode t : draft.path("tasks")) {
            String refs = t.path("story_ref").asText("");
            if (!refs.isBlank()) { relationCount += refs.split(",").length; for (String ref : refs.split(",")) covered.add(ref.trim()); }
        }
        storiesWithTask = covered.size();
        Set<Integer> sprintCoverage = new HashSet<>();
        Map<Integer, Integer> distribution = new HashMap<>();
        Map<String, Integer> storySprint = new HashMap<>();
        Map<Integer, Set<String>> activitiesBySprint = new HashMap<>();
        for (JsonNode s : draft.path("stories")) { int sp=s.path("sprint").asInt(0); sprintCoverage.add(sp); distribution.merge(sp,1,Integer::sum); storySprint.put(s.path("id").asText(),sp); activitiesBySprint.computeIfAbsent(sp,k->new HashSet<>()).add(s.path("activity").asText("")); }
        Set<Integer> taskSprintCoverage = new HashSet<>();
        int unlinkedTasks=0; List<String> unlinkedTaskIds=new ArrayList<>();
        for(JsonNode t:draft.path("tasks")) { boolean linked=false; for(String ref:t.path("story_ref").asText("").split(",")) { Integer sp=storySprint.get(ref.trim()); if(sp!=null) {linked=true;taskSprintCoverage.add(sp);} } if(!linked){unlinkedTasks++;unlinkedTaskIds.add(t.path("id").asText("未知 Task"));} }
        for (JsonNode t : draft.path("tasks")) if (t.path("_final_unlinked_story").asBoolean(false)
                && !unlinkedTaskIds.contains(t.path("id").asText())) { unlinkedTasks++; unlinkedTaskIds.add(t.path("id").asText()); }
        for (JsonNode s : draft.path("stories")) if (s.path("_final_uncovered_story").asBoolean(false)
                && !covered.contains(s.path("id").asText())) storiesWithTask--;
        int dependencyCount = 0;
        for (JsonNode t : draft.path("tasks")) if (!t.path("depends_on").asText("").isBlank()) dependencyCount++;
        int milestoneCount = draft.path("milestones").isArray() ? draft.path("milestones").size() : 0;
        ArrayNode hardBlockingIssues = mapper.createArrayNode();
        ArrayNode completenessWarnings = mapper.createArrayNode();
        ArrayNode autoCompletableIssues = mapper.createArrayNode();
        ObjectNode stageCoverage = importMode ? mapper.createObjectNode() : detailedTaskStageCoverage(draft);
        if (!importMode) {
            if (stories < minStories) hardBlockingIssues.add("Story 只有 " + stories + " 条，至少需要 " + minStories + " 条，目标约 " + storyTarget + " 条");
            if (stories == 0 || stageCoverage.path("stageCoverageGapStoryCount").asInt() > 0)
                hardBlockingIssues.add("Task 数量或拆分粒度不足，存在 "
                        + stageCoverage.path("stageCoverageGapStoryCount").asInt() + " 个 Story 实现阶段缺口");
            if (sprints > 1 && (sprintCoverage.size() < sprints || taskSprintCoverage.size() < sprints)) hardBlockingIssues.add("存在空 Sprint，需要补充每个 Sprint 的 Story 和 Task");
            int populatedActivities = validNonEmptyActivityCount(draft);
            double activityCoverage = stories == 0 ? 0 : (double) validStoriesWithActivity(draft) / stories;
            List<String> invalidActivityStories = invalidActivityStoryReferences(draft);
            int duplicateActivityCodes = duplicateActivityCodeCount(draft);
            boolean activityCollapsed = stories >= 8 && (populatedActivities <= 1
                    || largestValidActivityCount(draft) > Math.ceil(stories * 0.85));
            boolean activityStructureInvalid = !invalidActivityStories.isEmpty() || duplicateActivityCodes > 0;
            boolean activityCoverageSeverelyLow = stories > 0 && activityCoverage < 0.90;
            if (!activityStructureInvalid && (activityCoverageSeverelyLow || activityCollapsed))
                hardBlockingIssues.add("功能域覆盖不足");
            else if (!activityStructureInvalid && stories >= 20 && populatedActivities < 4)
                completenessWarnings.add("功能域数量略少，但已覆盖主要 Story");
            if (!invalidActivityStories.isEmpty())
                hardBlockingIssues.add("Story 引用了不存在的 Activity：" + String.join(",", invalidActivityStories));
            if (duplicateActivityCodes > 0)
                hardBlockingIssues.add("Activity code 重复，无法唯一解析 Story 引用");
            if (stories > 0 && emptyDescription > 0) hardBlockingIssues.add("Story 缺少 description：" + String.join(",",emptyDescriptionIds));
            if (stories > 0 && emptyAcceptance > 0) hardBlockingIssues.add("Story 缺少 acceptance：" + String.join(",",emptyAcceptanceIds));
            if (stories > 0 && storiesWithTask < stories) {
                List<String> uncovered=storySprint.keySet().stream().filter(id->!covered.contains(id)).sorted().toList();
                hardBlockingIssues.add("Story 没有对应 Task：" + String.join(",",uncovered));
            }
            if (unlinkedTasks > 0) hardBlockingIssues.add("Task 未关联 Story：" + String.join(",",unlinkedTaskIds));
            for (JsonNode task : draft.path("tasks")) if (task.path(TaskStoryReferences.AUDIT).isArray()
                    && !task.path("invalid_story_refs").asText("").isBlank())
                hardBlockingIssues.add("INVALID_TASK_STORY_REFERENCE：Task " + task.path("id").asText()
                        + " 引用了不存在或无法唯一映射的 Story：" + task.path("invalid_story_refs").asText());
            List<String> unresolvedSchedules = new ArrayList<>();
            for (JsonNode task : draft.path("tasks")) if (task.path("_schedule_conflict").isObject())
                unresolvedSchedules.add(task.path("id").asText("未知 Task") + "(" + task.path("_schedule_conflict").path("code").asText("UNKNOWN") + ")");
            if (!unresolvedSchedules.isEmpty())
                hardBlockingIssues.add("Task 排期未解决：" + String.join(",", unresolvedSchedules));
            JsonNode finalScheduleCheck = draft.path("qualityAdjustments").path("finalSprintCheck");
            if (finalScheduleCheck.has("dependencyTimingConflictCount")) {
                int timingConflicts = finalScheduleCheck.path("dependencyTimingConflictCount").asInt();
                int sprintConflicts = finalScheduleCheck.path("mismatchCount").asInt();
                if (timingConflicts > 0)
                    hardBlockingIssues.add("最终排期存在 " + timingConflicts + " 条依赖时序冲突");
                if (sprintConflicts > 0)
                    hardBlockingIssues.add("最终排期存在 " + sprintConflicts + " 个 Story Sprint 窗口冲突");
            }
            JsonNode taskCodes = draft.path("qualityAdjustments").path("taskCodeUniqueness");
            if (!taskCodes.path("sufficient").asBoolean(true))
                hardBlockingIssues.add("DUPLICATE_TASK_CODE_CONFLICT：重复 Task code 无法安全消歧");
            int unresolvedCrossStage = 0;
            for (JsonNode task : draft.path("tasks"))
                unresolvedCrossStage += task.path("unresolved_cross_stage_references").isArray()
                        ? task.path("unresolved_cross_stage_references").size() : 0;
            for (JsonNode milestone : draft.path("milestones"))
                unresolvedCrossStage += milestone.path("unresolved_cross_stage_references").isArray()
                        ? milestone.path("unresolved_cross_stage_references").size() : 0;
            unresolvedCrossStage = Math.max(unresolvedCrossStage, draft.path("qualityAdjustments")
                    .path("taskGeneration").path("crossStageReferenceUnresolvedCount").asInt(0));
            if (unresolvedCrossStage > 0)
                hardBlockingIssues.add("DUPLICATE_TASK_CODE_CONFLICT：存在 " + unresolvedCrossStage
                        + " 条无法唯一解析的跨阶段 Task 引用");
            JsonNode activityCheck = draft.path("qualityAdjustments").path("finalActivityCheck");
            if (!activityCheck.path("sufficient").asBoolean(true)) {
                hardBlockingIssues.add("Activity 最终一致性校验失败");
                for (JsonNode issue : activityCheck.path("issues"))
                    hardBlockingIssues.add(issue.path("reason").asText() + ": "
                            + issue.path("entityType").asText() + " " + issue.path("id").asText()
                            + " original_activity=" + issue.path("original_activity").asText(issue.path("activity").asText()));
            }
            JsonNode finalCapacity = draft.path("qualityAdjustments").path("finalCapacityFeasibility");
            if (finalCapacity.isObject() && !finalCapacity.path("capacityFeasible").asBoolean(true))
                hardBlockingIssues.add("最终 Task 工时超过真实开发容量，无法安全排期");
        } else {
            if (stories < minStories) completenessWarnings.add("导入 Story 数量低于 AI 规划建议（" + stories + "/" + minStories + "），保留用户原始数据");
            if (tasks < minTasks || stories == 0 || (stories > 0 && ((double) tasks / stories) < 1.25))
                completenessWarnings.add("导入 Task 数量或拆分粒度低于 AI 规划建议（当前 " + tasks + " 条），不影响用户数据确认");
            if (sprints > 1 && (sprintCoverage.size() < sprints || taskSprintCoverage.size() < sprints))
                completenessWarnings.add("导入草案的 Sprint 覆盖不完整，保留用户提供的 Story/Task 排期");
            if (domains < 2 && sprints >= 3) completenessWarnings.add("导入草案功能域覆盖较少，不按 AI 规划规则扩张数据");
            if (stories > 0 && emptyDescription > 0) {
                completenessWarnings.add("Story 缺少 description：" + String.join(",",emptyDescriptionIds));
                emptyDescriptionIds.forEach(id -> autoCompletableIssues.add("Story " + id + " 缺少 description"));
            }
            if (stories > 0 && emptyAcceptance > 0) {
                completenessWarnings.add("Story 缺少 acceptance：" + String.join(",",emptyAcceptanceIds));
                emptyAcceptanceIds.forEach(id -> autoCompletableIssues.add("Story " + id + " 缺少 acceptance"));
            }
            if (stories > 0 && storiesWithTask < stories) {
                List<String> uncovered=storySprint.keySet().stream().filter(id->!covered.contains(id)).sorted().toList();
                hardBlockingIssues.add("Story 没有对应 Task：" + String.join(",",uncovered));
            }
            if (unlinkedTasks > 0) hardBlockingIssues.add("Task 未关联 Story：" + String.join(",",unlinkedTaskIds));
        }
        if (emptyOwner > 0) {
            completenessWarnings.add("Story 未提供负责人建议：" + String.join(",",emptyOwnerIds));
            if (importMode) emptyOwnerIds.forEach(id -> autoCompletableIssues.add("Story " + id + " 缺少 owner"));
        }
        if (importMode) {
            for (JsonNode story : draft.path("stories")) {
                if (story.path("_import_missing_owner").asBoolean(false)) {
                    String id = story.path("id").asText("未知 Story");
                    addUniqueText(autoCompletableIssues, "Story " + id + " 缺少 owner");
                    addUniqueText(completenessWarnings, "Story 未提供负责人建议：" + id);
                }
            }
        }
        ArrayNode storyCountsBySprint = mapper.createArrayNode();
        ArrayNode sprintBalanceIssues = mapper.createArrayNode();
        ArrayNode activityDistributionIssues = mapper.createArrayNode();
        boolean sprintBalanceSufficient = true;
        boolean sprintBalanceSevere = false;
        for (int sprint = 1; sprint <= sprints; sprint++) storyCountsBySprint.add(distribution.getOrDefault(sprint, 0));
        if (sprints == 3 && stories > 0) {
            int minCount = Integer.MAX_VALUE, maxCount = 0;
            for (int sprint=1;sprint<=3;sprint++) { int count=distribution.getOrDefault(sprint,0); minCount=Math.min(minCount,count); maxCount=Math.max(maxCount,count); if(count * 4 < stories) sprintBalanceIssues.add("Sprint "+sprint+" 仅有 "+count+" 条 Story，低于总量约 25%" ); if(count==0) sprintBalanceIssues.add("Sprint "+sprint+" 没有 Story"); }
            // Sprint balance is a soft quality goal. Only an empty/collapsed
            // Sprint is structural; ordinary spread remains a warning.
            sprintBalanceSevere = minCount == 0 || minCount <= 4
                    || minCount < Math.floor(stories * 0.15)
                    || maxCount > Math.ceil(stories * 0.60)
                    || maxCount - minCount >= Math.ceil(stories * 0.33);
            if (maxCount - minCount >= Math.max(3, (int)Math.floor(stories * 0.15))) sprintBalanceIssues.add("Sprint 间 Story 数量差异过大");
            sprintBalanceSufficient = !sprintBalanceSevere;
        }
        for (int sprint=1;sprint<=sprints;sprint++) if (distribution.getOrDefault(sprint,0)>0 && activitiesBySprint.getOrDefault(sprint,Set.of()).size() <= 1 && domains >= 2) activityDistributionIssues.add("Sprint "+sprint+" 几乎只有一个 Activity");
        if (!activityDistributionIssues.isEmpty()) completenessWarnings.add("Activity/Epic 跨 Sprint 分布不均：" + activityDistributionIssues);
        if (!sprintBalanceIssues.isEmpty() && !sprintBalanceSevere)
            completenessWarnings.add("Sprint Story 分布略有差异（" + distribution.getOrDefault(1, 0) + "/"
                    + distribution.getOrDefault(2, 0) + "/" + distribution.getOrDefault(3, 0)
                    + "），但仍在可接受范围内。");
        if (sprintBalanceSevere) {
            if (importMode) completenessWarnings.add("导入草案 Sprint 分布存在明显差异：" + sprintBalanceIssues);
            else hardBlockingIssues.add("Sprint 分布失衡：" + sprintBalanceIssues);
        }
        if (tasks >= 4 && dependencyCount == 0) completenessWarnings.add("任务依赖关系异常缺失，请确认任务确实可并行执行");
        if (sprints >= 3 && milestoneCount < 2) {
            if (importMode) completenessWarnings.add("导入草案 Milestone 数量较少，当前 " + milestoneCount + " 个");
            else hardBlockingIssues.add("Milestone 数量不足，当前 " + milestoneCount + " 个");
        }
        List<String> milestonesWithoutTasks=new ArrayList<>();
        for(JsonNode m:draft.path("milestones")) if(m.path("related_task_ids").asText("").isBlank())
            milestonesWithoutTasks.add(m.path("id").asText(m.path("name").asText("未知 Milestone")));
        if(!milestonesWithoutTasks.isEmpty()) {
            if (importMode) completenessWarnings.add("Milestone 缺少真实 Task 关联：" + String.join(",",milestonesWithoutTasks));
            else hardBlockingIssues.add("Milestone 缺少真实 Task 关联：" + String.join(",",milestonesWithoutTasks));
        }
        if (importMode) collectImportCompletableTaskGaps(draft, autoCompletableIssues, completenessWarnings);
        if (importMode) collectImportIntegrityIssues(draft, hardBlockingIssues, completenessWarnings);
        ObjectNode report = mapper.createObjectNode();
        report.put("sufficient", hardBlockingIssues.isEmpty()); report.put("importMode", importMode); report.put("story_count", stories); report.put("task_count", tasks);
        report.put("storyTarget", storyTarget); report.put("storyMinimum", minStories); report.put("min_story_count", minStories); report.put("min_task_count", minTasks); report.put("member_count", members);
        int reportPopulatedActivities = importMode ? nonEmptyActivityCount(draft) : validNonEmptyActivityCount(draft);
        int reportStoriesWithActivity = importMode ? storiesWithActivity(draft) : validStoriesWithActivity(draft);
        int reportLargestActivityCount = importMode ? largestActivityCount(draft) : largestValidActivityCount(draft);
        report.put("nonEmptyActivityCount", reportPopulatedActivities);
        report.put("activityStoryCoverage", stories == 0 ? 0 : (double) reportStoriesWithActivity / stories);
        report.put("largestActivityStoryCount", reportLargestActivityCount);
        report.put("activityCodeDuplicateCount", importMode ? 0 : duplicateActivityCodeCount(draft));
        if (!importMode) {
            List<String> invalidActivityStories = invalidActivityStoryReferences(draft);
            report.put("invalidActivityReferenceCount", invalidActivityStories.size());
            report.set("invalidActivityStoryIds", mapper.valueToTree(invalidActivityStories));
        }
        report.put("empty_description_count", emptyDescription); report.put("empty_acceptance_count", emptyAcceptance); report.put("empty_owner_count", emptyOwner);
        report.put("stories_with_task_count", storiesWithTask); report.put("task_story_relation_count", relationCount); report.put("dependency_count", dependencyCount); report.put("milestone_count", milestoneCount);
        if (!importMode) report.set("taskStageCoverage", stageCoverage);
        report.set("storyCountsBySprint", storyCountsBySprint); report.put("sprintBalanceSufficient", sprintBalanceSufficient); report.put("sprintBalanceSevere", sprintBalanceSevere); report.set("sprintBalanceIssues", sprintBalanceIssues); report.set("activityDistributionIssues", activityDistributionIssues);
        if (draft.has("sprintInferenceIssues")) {
            int explicitStorySprintCount = 0, inferredStorySprintCount = 0;
            for (JsonNode story : draft.path("stories")) {
                if (story.path("sprint_explicit").asBoolean(false)) explicitStorySprintCount++;
                if (story.path("sprint_inferred").asBoolean(false)) inferredStorySprintCount++;
            }
            report.put("explicitStorySprintCount", explicitStorySprintCount);
            report.put("inferredStorySprintCount", inferredStorySprintCount);
            report.set("storySprintConflicts", draft.path("risks").path("sprintRisk").isArray()
                    ? draft.path("risks").path("sprintRisk").deepCopy() : mapper.createArrayNode());
            report.set("sprintInferenceIssues", draft.path("sprintInferenceIssues").deepCopy());
        }
        if (draft.path("dependencyRepairs").isObject()) {
            draft.path("dependencyRepairs").fields().forEachRemaining(entry -> report.set(entry.getKey(),entry.getValue().deepCopy()));
        }
        if (draft.path("qualityAdjustments").isObject()) {
            draft.path("qualityAdjustments").fields().forEachRemaining(entry -> report.set(entry.getKey(), entry.getValue().deepCopy()));
            JsonNode preSchedule = draft.path("qualityAdjustments").path("preScheduleConsistencyCheck");
            if (preSchedule.isObject() && preSchedule.path("blockingIssues").isArray()) {
                for (JsonNode issue : preSchedule.path("blockingIssues"))
                    addUniqueText(hardBlockingIssues, issue.asText());
            }
        }
        report.set("hardBlockingIssues", hardBlockingIssues);
        report.set("warnings", deduplicateWarnings(completenessWarnings));
        report.set("autoCompletableIssues", autoCompletableIssues);
        report.put("supplementRequired", importMode && hardBlockingIssues.isEmpty() && !autoCompletableIssues.isEmpty());
        report.set("gaps", importMode && hardBlockingIssues.isEmpty() ? autoCompletableIssues.deepCopy() : hardBlockingIssues.deepCopy());
        return report;
    }

    private void collectImportCompletableTaskGaps(ObjectNode draft, ArrayNode gaps, ArrayNode warnings) {
        for (JsonNode task : draft.path("tasks")) {
            String id = task.path("id").asText("未知 Task");
            if (task.path("_import_missing_owner").asBoolean(false)
                    || !task.has("owner_id") || task.path("owner_id").isNull() || task.path("owner_id").asInt(0) <= 0) {
                addUniqueText(gaps, "Task " + id + " 缺少 owner");
                addUniqueText(warnings, "Task 缺少负责人：" + id);
            }
            if (task.path("_import_missing_hours").asBoolean(false)
                    || !task.has("hours") || !task.path("hours").isIntegralNumber()) {
                addUniqueText(gaps, "Task " + id + " 缺少 hours");
                addUniqueText(warnings, "Task 缺少工时：" + id);
            }
            if (task.path("_import_missing_week").asBoolean(false)
                    || !task.has("week_start") || !task.path("week_start").isIntegralNumber()
                    || !task.has("week_end") || !task.path("week_end").isIntegralNumber()) {
                addUniqueText(gaps, "Task " + id + " 缺少 week");
                addUniqueText(warnings, "Task 缺少排期周次：" + id);
            }
        }
    }

    private void addUniqueText(ArrayNode values, String value) {
        for (JsonNode existing : values) if (existing.asText().equals(value)) return;
        values.add(value);
    }

    /**
     * Detailed natural-language drafts use an explicit, stable Activity code
     * (A1, A2, ...).  The persisted Story contract is still numeric, so this
     * boundary deliberately remains inside draft normalization/execution: old
     * ids, array positions, names and model aliases are accepted as input, but
     * the detailed draft exposes one canonical code to every validator stage.
     */
    private void normalizeActivityReferences(ObjectNode raw, ArrayNode issues) {
        ArrayNode supplied = array(raw, "activities");
        if (supplied.isEmpty() && raw.path("epics").isArray() && !raw.path("epics").isEmpty()) {
            // Recover definitions from real Epic rows before considering any
            // Story value.  Do not manufacture an empty Activity from a bare
            // Story reference.
            ArrayNode recovered = mapper.createArrayNode();
            int ordinal = 1;
            for (JsonNode epic : raw.path("epics")) {
                ObjectNode activity = mapper.createObjectNode();
                String legacy = epic.path("activity").asText("").trim();
                activity.put("id", legacy.isBlank() ? "activity-" + ordinal : legacy);
                activity.put("name", firstText(epic, "title", "name", "activity"));
                String description = firstText(epic, "description", "detail");
                if (!description.isBlank()) activity.put("description", description);
                recovered.add(activity);
                ordinal++;
            }
            raw.set("activities", recovered);
            supplied = recovered;
        }

        ActivityIndex index = canonicalizeActivities(raw, supplied);
        normalizeEpicReferences(raw, index, issues);
        for (JsonNode storyNode : raw.path("stories")) {
            if (!(storyNode instanceof ObjectNode story)) continue;
            String original = firstText(story, "activity", "activityCode", "activity_code").trim();
            String resolved = resolveActivity(original, index);
            if (resolved == null) resolved = semanticActivity(story, index.entries());
            if (resolved == null) {
                String storyId = story.path("id").asText(story.path("code").asText("未知 Story"));
                issues.add("Story " + storyId + " 的 Activity 引用无法唯一映射到当前 Activity");
                if (!original.isBlank()) story.put("original_activity", original);
                story.put("activity", "").put("activityCode", "");
            } else {
                story.put("activity", resolved).put("activityCode", resolved);
            }
        }
    }

    private ActivityIndex canonicalizeActivities(ObjectNode raw, ArrayNode supplied) {
        ArrayNode normalized = mapper.createArrayNode();
        Set<String> usedCodes = new LinkedHashSet<>();
        int ordinal = 1;
        for (JsonNode source : supplied) {
            ObjectNode activity = source.isObject() ? ((ObjectNode) source).deepCopy() : mapper.createObjectNode();
            if (!source.isObject()) activity.put("name", source.asText(""));
            String requested = activity.path("code").asText("").trim().toUpperCase(Locale.ROOT);
            String code = requested.matches("A\\d+") && !usedCodes.contains(requested)
                    ? requested : nextActivityCode(usedCodes, ordinal);
            usedCodes.add(code);
            if (activity.path("id").asText("").isBlank()) activity.put("id", "activity-" + ordinal);
            activity.put("code", code);
            if (activity.path("name").asText("").isBlank() && !activity.path("title").asText("").isBlank())
                activity.put("name", activity.path("title").asText());
            normalized.add(activity);
            ordinal++;
        }
        raw.set("activities", normalized);
        LinkedHashMap<String, String> aliases = new LinkedHashMap<>();
        List<ActivityEntry> entries = new ArrayList<>();
        for (int i = 0; i < normalized.size(); i++) {
            ObjectNode activity = (ObjectNode) normalized.get(i);
            String code = activity.path("code").asText();
            entries.add(new ActivityEntry(code, activity));
            addActivityAlias(aliases, code, code);
            addActivityAlias(aliases, activity.path("id").asText(""), code);
            addActivityAlias(aliases, String.valueOf(i + 1), code);
            addActivityAlias(aliases, "activity-" + (i + 1), code);
            addActivityAlias(aliases, "activity_" + (i + 1), code);
            addActivityAlias(aliases, "A" + (i + 1), code);
            addActivityAlias(aliases, activity.path("name").asText(""), code);
            addActivityAlias(aliases, activity.path("title").asText(""), code);
        }
        return new ActivityIndex(aliases, entries);
    }

    private String nextActivityCode(Set<String> usedCodes, int ordinal) {
        int candidate = Math.max(1, ordinal);
        while (usedCodes.contains("A" + candidate)) candidate++;
        return "A" + candidate;
    }

    private void normalizeEpicReferences(ObjectNode raw, ActivityIndex index, ArrayNode issues) {
        if (!raw.path("epics").isArray()) return;
        for (JsonNode epicNode : raw.path("epics")) {
            if (!(epicNode instanceof ObjectNode epic)) continue;
            String original = epic.path("activity").asText("").trim();
            String resolved = resolveActivity(original, index);
            if (resolved == null) resolved = uniqueSemanticEpicActivity(epic, index.entries());
            if (resolved == null) {
                String epicId = epic.path("id").asText(epic.path("code").asText("未知 Epic"));
                issues.add("Epic " + epicId + " 的 Activity 引用无法唯一映射到当前 Activity");
            } else {
                if (!original.equals(resolved) && !epic.has("original_activity")) epic.put("original_activity", original);
                epic.put("activity", resolved).put("activityCode", resolved);
            }
        }
    }

    private String resolveActivity(String value, ActivityIndex index) {
        String key = normalizeActivityAlias(value);
        if (key.isBlank() || !index.aliases().containsKey(key)) return null;
        return index.aliases().get(key);
    }

    private String uniqueSemanticEpicActivity(JsonNode epic, List<ActivityEntry> entries) {
        int best = 0, second = 0;
        String winner = null;
        for (ActivityEntry entry : entries) {
            int score = activitySemanticScore(activitySemanticText(epic), activitySemanticText(entry.node()));
            if (score > best) { second = best; best = score; winner = entry.code(); }
            else if (score > second) second = score;
        }
        return best >= 2 && best >= second + 2 ? winner : null;
    }

    private void addActivityAlias(Map<String, String> aliases, String value, String code) {
        String key = normalizeActivityAlias(value);
        if (key.isBlank()) return;
        if (!aliases.containsKey(key)) aliases.put(key, code);
        else if (aliases.get(key) != null && !aliases.get(key).equals(code)) aliases.put(key, null);
        String compact = compactActivityAlias(value);
        if (!compact.isBlank()) {
            if (!aliases.containsKey(compact)) aliases.put(compact, code);
            else if (aliases.get(compact) != null && !aliases.get(compact).equals(code)) aliases.put(compact, null);
        }
    }

    private String normalizeActivityAlias(String value) {
        return value == null ? "" : value.trim().toLowerCase(Locale.ROOT);
    }

    private String compactActivityAlias(String value) {
        return normalizeActivityAlias(value).replaceAll("[\\p{P}\\p{Z}\\s]+", "");
    }

    private String semanticActivity(JsonNode source, List<ActivityEntry> entries) {
        if (entries.isEmpty()) return null;
        String sourceText = activitySemanticText(source);
        int bestScore = 0, secondScore = 0;
        String best = null;
        for (ActivityEntry entry : entries) {
            int score = activitySemanticScore(sourceText, entry.node() == null ? entry.code() : activitySemanticText(entry.node()));
            if (score > bestScore) { secondScore = bestScore; bestScore = score; best = entry.code(); }
            else if (score > secondScore) secondScore = score;
        }
        return bestScore >= 2 && (bestScore >= secondScore + 2 || bestScore >= 4) ? best : null;
    }

    private String activitySemanticText(JsonNode node) {
        return String.join(" ", node.path("title").asText(""), node.path("name").asText(""),
                node.path("description").asText(""), node.path("acceptance").asText(""),
                node.path("epic").asText(""), node.path("detail").asText("")).toLowerCase(Locale.ROOT);
    }

    private int activitySemanticScore(String source, String candidate) {
        if (source.isBlank() || candidate.isBlank()) return 0;
        Set<String> tokens = new LinkedHashSet<>();
        for (String token : candidate.split("[^\\p{L}\\p{N}]+")) {
            if (token.length() >= 2) tokens.add(token);
            if (token.length() > 2 && token.chars().allMatch(c -> c >= 0x3400 && c <= 0x9fff))
                for (int i = 0; i + 1 < token.length(); i++) tokens.add(token.substring(i, i + 2));
        }
        int score = 0;
        for (String token : tokens) if (source.contains(token)) score += token.length() >= 3 ? 2 : 1;
        return score;
    }

    private record ActivityEntry(String code, ObjectNode node) { }
    private record ActivityIndex(Map<String, String> aliases, List<ActivityEntry> entries) { }

    private int nonEmptyActivityCount(ObjectNode draft) {
        Set<String> ids = new HashSet<>();
        for (JsonNode story : draft.path("stories")) {
            String id = story.path("activity").asText("").trim();
            if (!id.isBlank()) ids.add(id);
        }
        return ids.size();
    }

    /** Canonical Activity keys declared by the current draft. */
    private Set<String> declaredActivityIds(ObjectNode draft) {
        return new LinkedHashSet<>(activityIndex(draft).entries().stream().map(ActivityEntry::code).toList());
    }

    private int validStoriesWithActivity(ObjectNode draft) {
        ActivityIndex index = activityIndex(draft);
        int count = 0;
        for (JsonNode story : draft.path("stories")) {
            if (resolveActivity(story.path("activity").asText(""), index) != null) count++;
        }
        return count;
    }

    private int validNonEmptyActivityCount(ObjectNode draft) {
        ActivityIndex index = activityIndex(draft);
        Set<String> used = new HashSet<>();
        for (JsonNode story : draft.path("stories")) {
            String resolved = resolveActivity(story.path("activity").asText(""), index);
            if (resolved != null) used.add(resolved);
        }
        return used.size();
    }

    private int largestValidActivityCount(ObjectNode draft) {
        ActivityIndex index = activityIndex(draft);
        Map<String, Integer> counts = new HashMap<>();
        for (JsonNode story : draft.path("stories")) {
            String resolved = resolveActivity(story.path("activity").asText(""), index);
            if (resolved != null) counts.merge(resolved, 1, Integer::sum);
        }
        return counts.values().stream().mapToInt(Integer::intValue).max().orElse(0);
    }

    private List<String> invalidActivityStoryReferences(ObjectNode draft) {
        ActivityIndex index = activityIndex(draft);
        List<String> invalid = new ArrayList<>();
        for (JsonNode story : draft.path("stories")) {
            String activity = story.path("activity").asText("").trim();
            if (activity.isBlank() || resolveActivity(activity, index) == null)
                invalid.add(story.path("id").asText("未知 Story"));
        }
        return invalid;
    }

    private ActivityIndex activityIndex(ObjectNode draft) {
        LinkedHashMap<String, String> aliases = new LinkedHashMap<>();
        List<ActivityEntry> entries = new ArrayList<>();
        int ordinal = 1;
        for (JsonNode source : draft.path("activities")) {
            ObjectNode activity = source.isObject() ? (ObjectNode) source : null;
            String canonical = activity == null ? source.asText("").trim()
                    : firstText(activity, "code", "id");
            if (canonical.isBlank()) canonical = String.valueOf(ordinal);
            entries.add(new ActivityEntry(canonical, activity));
            addActivityAlias(aliases, canonical, canonical);
            if (activity != null) {
                addActivityAlias(aliases, activity.path("code").asText(""), canonical);
                addActivityAlias(aliases, activity.path("id").asText(""), canonical);
                addActivityAlias(aliases, activity.path("name").asText(""), canonical);
                addActivityAlias(aliases, activity.path("title").asText(""), canonical);
            }
            addActivityAlias(aliases, String.valueOf(ordinal), canonical);
            addActivityAlias(aliases, "activity-" + ordinal, canonical);
            addActivityAlias(aliases, "activity_" + ordinal, canonical);
            addActivityAlias(aliases, "A" + ordinal, canonical);
            ordinal++;
        }
        return new ActivityIndex(aliases, entries);
    }

    private int duplicateActivityCodeCount(ObjectNode draft) {
        Set<String> seen = new HashSet<>();
        int duplicates = 0;
        for (JsonNode activity : draft.path("activities")) {
            String code = activity.path("code").asText("").trim();
            if (code.isBlank()) code = activity.path("id").asText("").trim();
            if (!code.isBlank() && !seen.add(code.toLowerCase(Locale.ROOT))) duplicates++;
        }
        return duplicates;
    }

    /**
     * Re-establishes the generated Task identity boundary after continuation,
     * merge and completion have all run. Equivalent rows are folded; distinct
     * rows receive deterministic fresh codes. An inbound reference to a code
     * shared by distinct rows is inherently ambiguous and is retained against
     * the first row while being reported as a blocking conflict.
     */
    private ObjectNode ensureUniqueGeneratedTaskCodes(ArrayNode tasks, ArrayNode milestones) {
        // Legacy callers have no source identity boundary. Preserve equivalent
        // row merging, but remove ambiguous inbound codes before renumbering.
        Map<String, List<ObjectNode>> groups = new LinkedHashMap<>();
        for (JsonNode node : tasks) groups.computeIfAbsent(node.path("id").asText(""), ignored -> new ArrayList<>()).add((ObjectNode) node);
        int ambiguous = 0;
        for (var entry : groups.entrySet()) if (entry.getValue().size() > 1
                && entry.getValue().stream().anyMatch(row -> !equivalentGeneratedTask(entry.getValue().get(0), row))) {
            for (JsonNode node : tasks) {
                ObjectNode row = (ObjectNode) node;
                List<String> refs = TaskIdentityReferences.values(row.path("depends_on"));
                if (refs.removeIf(ref -> ref.equalsIgnoreCase(entry.getKey()))) {
                    row.withArray("unresolved_cross_stage_references").add(entry.getKey());
                    row.put("depends_on", String.join(",", refs));
                    ambiguous++;
                }
            }
        }
        ObjectNode result = ensureUniqueGeneratedTaskCodes(tasks, milestones, mapper.createArrayNode());
        result.put("ambiguousReferenceCount", ambiguous);
        if (ambiguous > 0) result.put("sufficient", false);
        return result;
    }

    private ObjectNode ensureUniqueGeneratedTaskCodes(ArrayNode tasks, ArrayNode milestones, ArrayNode dependencies) {
        ObjectNode result = mapper.createObjectNode();
        ArrayNode renumbered = mapper.createArrayNode();
        Map<String, List<ObjectNode>> groups = new LinkedHashMap<>();
        Map<String, List<String>> candidateIdentities = new LinkedHashMap<>();
        int max = 0;
        for (JsonNode node : tasks) {
            ObjectNode task = (ObjectNode) node;
            String id = task.path("id").asText("").trim().toUpperCase(Locale.ROOT);
            groups.computeIfAbsent(id, ignored -> new ArrayList<>()).add(task);
            String identity = task.path("generation_identity").asText("");
            if (!id.isBlank() && !identity.isBlank())
                candidateIdentities.computeIfAbsent(id, ignored -> new ArrayList<>()).add(identity);
            Matcher matcher = T.matcher(id);
            if (matcher.matches()) max = Math.max(max, Integer.parseInt(matcher.group(1)));
        }
        int duplicateGroups = 0, merged = 0, conflicts = 0;
        Set<ObjectNode> removed = java.util.Collections.newSetFromMap(new java.util.IdentityHashMap<>());
        for (Map.Entry<String, List<ObjectNode>> entry : groups.entrySet()) {
            List<ObjectNode> rows = entry.getValue();
            if (rows.size() <= 1) continue;
            duplicateGroups++;
            ObjectNode first = rows.get(0);
            for (int index = 1; index < rows.size(); index++) {
                ObjectNode duplicate = rows.get(index);
                if (equivalentGeneratedTask(first, duplicate)
                        && sameGeneratedIdentity(first, duplicate)) {
                    first.put("story_ref", unionRefs(first.path("story_ref").asText(""), duplicate.path("story_ref").asText("")));
                    first.put("depends_on", unionRefs(first.path("depends_on").asText(""), duplicate.path("depends_on").asText("")));
                    removed.add(duplicate);
                    merged++;
                    continue;
                }
                String fresh;
                do fresh = String.format("T%02d", ++max); while (groups.containsKey(fresh));
                duplicate.put("id", fresh);
                duplicate.put("kanban_card_id", firstRef(duplicate.path("story_ref").asText("")));
                renumbered.add(mapper.createObjectNode().put("from", entry.getKey()).put("to", fresh));
                // Source-aware references are rewritten below. Repeated display
                // codes alone are not a conflict; only unresolved references block.
            }
        }
        if (!removed.isEmpty()) {
            ArrayNode compact = mapper.createArrayNode();
            for (JsonNode task : tasks) if (!removed.contains(task)) compact.add(task);
            tasks.removeAll();
            compact.forEach(tasks::add);
        }
        rewriteEquivalentTaskReferences(tasks, milestones, groups, removed);
        int publicOrdinal = 1;
        Map<String, String> uniqueOldCodes = new LinkedHashMap<>();
        for (JsonNode task : tasks) {
            String old = task.path("id").asText("");
            String finalCode = String.format("T%02d", publicOrdinal++);
            uniqueOldCodes.put(old, finalCode);
        }
        rewriteCompactedTaskReferences(tasks, milestones, uniqueOldCodes);
        for (JsonNode node : tasks) ((ObjectNode) node).put("id", uniqueOldCodes.get(node.path("id").asText("")));
        Map<String, String> identityToCode = new LinkedHashMap<>();
        for (JsonNode task : tasks) {
            String identity = task.path("generation_identity").asText("");
            if (!identity.isBlank()) identityToCode.put(identity, task.path("id").asText(""));
        }
        for (JsonNode task : tasks) for (JsonNode source : task.path("generation_sources")) {
            String sourceIdentity = source.path("identity").asText("");
            if (!sourceIdentity.isBlank()) identityToCode.putIfAbsent(sourceIdentity, task.path("id").asText(""));
        }
        rewriteIdentityReferences(tasks, milestones, dependencies, identityToCode);
        Set<String> unique = new HashSet<>();
        int remainingDuplicates = 0;
        for (JsonNode task : tasks)
            if (!unique.add(task.path("id").asText("").toUpperCase(Locale.ROOT))) remainingDuplicates++;
        result.put("duplicateGroupCount", duplicateGroups).put("mergedEquivalentTaskCount", merged)
                .put("renumberedTaskCount", renumbered.size()).put("ambiguousReferenceCount", conflicts)
                .put("remainingDuplicateCodeCount", remainingDuplicates)
                .put("sufficient", conflicts == 0 && remainingDuplicates == 0).set("renumberedTasks", renumbered);
        ArrayNode duplicateCodes = mapper.createArrayNode();
        ObjectNode candidates = mapper.createObjectNode();
        for (Map.Entry<String, List<String>> entry : candidateIdentities.entrySet()) {
            if (entry.getValue().size() > 1) duplicateCodes.add(entry.getKey());
            candidates.set(entry.getKey(), mapper.valueToTree(entry.getValue()));
        }
        result.set("duplicateRawCodes", duplicateCodes);
        result.set("rawCodeCandidateIdentities", candidates);
        result.put("finalIdentityToCodeMapSize", identityToCode.size());
        result.put("sourceAwareResolvedReferenceCount",
                countResolvedIdentityReferences(tasks, milestones, dependencies));
        result.put("uniqueBareCodeResolvedReferenceCount",
                countUniqueBareResolvedReferences(tasks, milestones, dependencies));
        ArrayNode unresolvedReferences = mapper.createArrayNode();
        int preventedFanout = 0;
        for (JsonNode node : tasks) {
            if (!(node instanceof ObjectNode row)) continue;
            appendUnresolvedReferenceAudit(unresolvedReferences, row, "depends_on", candidateIdentities);
            appendUnresolvedReferenceAudit(unresolvedReferences, row, "weak_dependencies", candidateIdentities);
            appendUnresolvedReferenceAudit(unresolvedReferences, row, "related_task_ids", candidateIdentities);
            preventedFanout += countAmbiguousUnresolved(row, candidateIdentities);
        }
        for (JsonNode node : milestones) {
            if (!(node instanceof ObjectNode row)) continue;
            appendUnresolvedReferenceAudit(unresolvedReferences, row, "related_task_ids", candidateIdentities);
            preventedFanout += countAmbiguousUnresolved(row, candidateIdentities);
        }
        for (JsonNode node : dependencies) {
            if (!(node instanceof ObjectNode edge)) continue;
            appendEdgeUnresolvedReferenceAudit(unresolvedReferences, edge, "from", candidateIdentities);
            appendEdgeUnresolvedReferenceAudit(unresolvedReferences, edge, "to", candidateIdentities);
        }
        result.set("unresolvedReferences", unresolvedReferences);
        result.put("unresolvedDuplicateReferenceCount", unresolvedReferences.size());
        result.put("preventedFanoutCount", preventedFanout);
        result.put("sufficient", conflicts == 0 && remainingDuplicates == 0 && unresolvedReferences.isEmpty());
        return result;
    }

    private void appendUnresolvedReferenceAudit(ArrayNode out, ObjectNode row, String referenceType,
                                                Map<String, List<String>> candidates) {
        JsonNode refs = "depends_on".equals(referenceType)
                ? row.path("unresolved_cross_stage_references")
                : row.path("unresolved_" + referenceType + "_references");
        if (!refs.isArray()) return;
        for (JsonNode refNode : refs) {
            String raw = refNode.asText("").trim();
            ObjectNode item = mapper.createObjectNode().put("referenceType", referenceType)
                    .put("rawCode", raw)
                    .put("sourceIdentity", row.path("generation_identity").asText(""))
                    .put("sourceStage", row.path("generation_stage").asText(""))
                    .put("sourceRound", row.path("generation_continuation_round").asInt(0));
            ArrayNode identities = mapper.createArrayNode();
            for (String identity : candidates.getOrDefault(raw.toUpperCase(Locale.ROOT), List.of())) identities.add(identity);
            item.set("candidateIdentities", identities);
            item.put("reason", identities.size() > 1 ? "AMBIGUOUS_DUPLICATE_RAW_CODE" : "UNRESOLVED_REFERENCE");
            out.add(item);
        }
    }

    private void appendEdgeUnresolvedReferenceAudit(ArrayNode out, ObjectNode edge, String field,
                                                    Map<String, List<String>> candidates) {
        String raw = edge.path("unresolved_" + field + "_reference").asText("").trim();
        if (raw.isBlank()) return;
        ObjectNode item = mapper.createObjectNode().put("referenceType", "top_level_dependency_" + field)
                .put("rawCode", raw)
                .put("sourceIdentity", edge.path("generation_identity").asText(""))
                .put("sourceStage", edge.path("generation_stage").asText(""))
                .put("sourceRound", edge.path("generation_continuation_round").asInt(0));
        ArrayNode identities = mapper.createArrayNode();
        for (String identity : candidates.getOrDefault(raw.toUpperCase(Locale.ROOT), List.of())) identities.add(identity);
        item.set("candidateIdentities", identities);
        item.put("reason", identities.size() > 1 ? "AMBIGUOUS_DUPLICATE_RAW_CODE" : "UNRESOLVED_REFERENCE");
        out.add(item);
    }

    private int countAmbiguousUnresolved(ObjectNode row, Map<String, List<String>> candidates) {
        int count = 0;
        JsonNode refs = row.path("unresolved_cross_stage_references");
        if (refs.isArray()) for (JsonNode ref : refs)
            if (candidates.getOrDefault(ref.asText("").toUpperCase(Locale.ROOT), List.of()).size() > 1) count++;
        JsonNode weakRefs = row.path("unresolved_weak_dependency_references");
        if (weakRefs.isArray()) for (JsonNode ref : weakRefs)
            if (candidates.getOrDefault(ref.asText("").toUpperCase(Locale.ROOT), List.of()).size() > 1) count++;
        return count;
    }

    private boolean sameGeneratedIdentity(JsonNode left, JsonNode right) {
        String a = left.path("generation_identity").asText("");
        String b = right.path("generation_identity").asText("");
        return !a.isBlank() && a.equalsIgnoreCase(b) || a.isBlank() && b.isBlank();
    }

    private void resolveTaskReferencesBeforeUniqueness(ArrayNode tasks, ArrayNode milestones, ArrayNode dependencies) {
        TaskIdentityReferences.resolve(tasks, milestones, dependencies, false);
    }


    private void rewriteIdentityReferences(ArrayNode tasks, ArrayNode milestones, ArrayNode dependencies,
                                           Map<String, String> identityToCode) {
        for (JsonNode node : tasks) if (node instanceof ObjectNode task) {
            rewriteIdentityArrayToCodes(task, "depends_on", "generation_dependency_refs", identityToCode);
            rewriteIdentityArrayToCodes(task, "weak_dependencies", "generation_weak_dependency_refs", identityToCode);
            rewriteIdentityArrayToCodes(task, "related_task_ids", "generation_related_task_refs", identityToCode);
        }
        for (JsonNode node : milestones) if (node instanceof ObjectNode milestone)
            rewriteIdentityArrayToCodes(milestone, "related_task_ids", "generation_related_task_refs", identityToCode);
        for (JsonNode node : dependencies) if (node instanceof ObjectNode edge) {
            String from = edge.path("generation_from_ref").asText("");
            String to = edge.path("generation_to_ref").asText("");
            if (!from.isBlank() && identityToCode.containsKey(from)) edge.put("from", identityToCode.get(from));
            if (!to.isBlank() && identityToCode.containsKey(to)) edge.put("to", identityToCode.get(to));
        }
    }

    private void rewriteIdentityArrayToCodes(ObjectNode row, String publicField, String identityField,
                                             Map<String, String> identityToCode) {
        if (!row.path(identityField).isArray()) return;
        LinkedHashSet<String> refs = new LinkedHashSet<>();
        for (JsonNode identity : row.path(identityField)) {
            String code = identityToCode.get(identity.asText(""));
            if (code != null && !code.equals(row.path("id").asText(""))) refs.add(code);
        }
        row.put(publicField, String.join(",", refs));
    }

    private int countResolvedIdentityReferences(ArrayNode tasks, ArrayNode milestones, ArrayNode dependencies) {
        int count = 0;
        for (JsonNode task : tasks) {
            count += task.path("generation_dependency_refs").size();
            count += task.path("generation_weak_dependency_refs").size();
        }
        for (JsonNode milestone : milestones) count += milestone.path("generation_related_task_refs").size();
        for (JsonNode edge : dependencies) {
            if (edge.hasNonNull("generation_from_ref")) count++;
            if (edge.hasNonNull("generation_to_ref")) count++;
        }
        return count;
    }

    private int countUniqueBareResolvedReferences(ArrayNode tasks, ArrayNode milestones, ArrayNode dependencies) {
        int count = 0;
        for (JsonNode task : tasks) count += task.path("unique_bare_task_references").size();
        for (JsonNode milestone : milestones) count += milestone.path("unique_bare_task_references").size();
        for (JsonNode edge : dependencies) {
            if (edge.hasNonNull("unique_bare_from_reference")) count++;
            if (edge.hasNonNull("unique_bare_to_reference")) count++;
        }
        return count;
    }

    private void rewriteEquivalentTaskReferences(ArrayNode tasks, ArrayNode milestones,
                                                 Map<String, List<ObjectNode>> groups, Set<ObjectNode> removed) {
        Set<String> equivalentCodes = new HashSet<>();
        for (Map.Entry<String, List<ObjectNode>> entry : groups.entrySet()) {
            List<ObjectNode> rows = entry.getValue();
            if (rows.size() > 1 && rows.stream().skip(1).allMatch(removed::contains))
                equivalentCodes.add(entry.getKey());
        }
        if (equivalentCodes.isEmpty()) return;
        for (JsonNode taskNode : tasks) {
            ObjectNode task = (ObjectNode) taskNode;
            task.put("depends_on", stableTaskRefs(task.path("depends_on").asText("")));
        }
        for (JsonNode milestoneNode : milestones) {
            ObjectNode milestone = (ObjectNode) milestoneNode;
            milestone.put("related_task_ids", stableTaskRefs(milestone.path("related_task_ids").asText("")));
        }
    }

    private String stableTaskRefs(String value) {
        LinkedHashSet<String> refs = new LinkedHashSet<>();
        for (String ref : value.split("[,，;；\\s]+")) if (!ref.isBlank()) refs.add(ref.trim());
        return String.join(",", refs);
    }

    private boolean equivalentGeneratedTask(JsonNode left, JsonNode right) {
        return normalizedText(left.path("name").asText("")).equals(normalizedText(right.path("name").asText("")))
                && left.path("owner_id").asInt() == right.path("owner_id").asInt()
                && left.path("hours").asInt() == right.path("hours").asInt()
                && left.path("week_start").asInt() == right.path("week_start").asInt()
                && left.path("week_end").asInt() == right.path("week_end").asInt();
    }

    private String normalizedText(String value) {
        return value == null ? "" : value.replaceAll("[\\s\\p{Punct}]+", "").toLowerCase(Locale.ROOT);
    }

    private String unionRefs(String left, String right) {
        LinkedHashSet<String> refs = new LinkedHashSet<>();
        for (String value : (left + "," + right).split("[,，;；\\s]+")) if (!value.isBlank()) refs.add(value.trim());
        return String.join(",", refs);
    }

    private String firstRef(String refs) {
        return java.util.Arrays.stream(refs.split("[,，;；\\s]+")).filter(value -> !value.isBlank()).findFirst().orElse("");
    }

    private boolean hasTaskReference(ArrayNode tasks, ArrayNode milestones, String taskId) {
        for (JsonNode task : tasks) for (String ref : task.path("depends_on").asText("").split(","))
            if (taskId.equalsIgnoreCase(ref.trim())) return true;
        for (JsonNode milestone : milestones) for (String ref : milestone.path("related_task_ids").asText("").split(","))
            if (taskId.equalsIgnoreCase(ref.trim())) return true;
        return false;
    }

    /** Re-clusters only genuinely collapsed generated domains; valid user/model
     * Activity/Epic assignments are preserved. */
    private boolean needsGeneratedActivityNormalization(ObjectNode draft) {
        // The overview/coarse generator historically accepts the model's own
        // activity contract.  ProjectGenerationJobs marks those drafts while
        // normalizing so this detailed-only recovery never rewrites them.
        if (draft.path("_preserve_activity_domains").asBoolean(false)) return false;
        if (!draft.path("stories").isArray() || draft.path("stories").isEmpty()) return false;
        Set<String> declared = declaredActivityIds(draft);
        if (declared.isEmpty()) return true;
        int stories = draft.path("stories").size();
        // Very small plans may legitimately use a single supplied domain; do
        // not rewrite a user's two-domain draft merely because activity fields
        // are absent. Completeness still reports any dangling/missing reference.
        if (stories < 8) return false;
        int valid = validStoriesWithActivity(draft);
        int populated = validNonEmptyActivityCount(draft);
        int largest = largestValidActivityCount(draft);
        return valid * 100 < stories * 90
                || (stories >= 8 && (populated <= 1 || largest * 100 > stories * 85));
    }

    private int storiesWithActivity(ObjectNode draft) {
        int count = 0;
        for (JsonNode story : draft.path("stories")) if (!story.path("activity").asText("").isBlank()) count++;
        return count;
    }

    private int largestActivityCount(ObjectNode draft) {
        Map<String, Integer> counts = new HashMap<>();
        for (JsonNode story : draft.path("stories")) {
            String id = story.path("activity").asText("").trim();
            if (!id.isBlank()) counts.merge(id, 1, Integer::sum);
        }
        return counts.values().stream().mapToInt(Integer::intValue).max().orElse(0);
    }

    /** Keeps warning output readable without changing any validation result. */
    private ArrayNode deduplicateWarnings(ArrayNode input) {
        ArrayNode output = mapper.createArrayNode();
        Set<String> seen = new LinkedHashSet<>();
        for (JsonNode node : input) {
            String message = node.asText("").trim();
            if (message.isBlank()) continue;
            if (seen.add(warningKey(message))) output.add(message);
        }
        return output;
    }

    private String warningKey(String message) {
        Matcher task = Pattern.compile("\\bT\\d+\\b", Pattern.CASE_INSENSITIVE).matcher(message);
        if (!task.find()) return "text:" + message;
        String taskCode = task.group().toUpperCase(Locale.ROOT);
        String type = warningType(message);
        if (type != null) return "task:" + taskCode + ":" + type;
        type = message
                .replaceFirst("(?i)T\\d+", "TASK")
                .replaceAll("W\\d+(?:-W\\d+)?", "WEEK")
                .replaceAll("\\d+", "N")
                .trim();
        return "task:" + taskCode + ":" + type;
    }

    private String warningType(String message) {
        if (message.contains("用户排期与依赖或项目周期冲突")) return "schedule_dependency_period_conflict";
        if (message.contains("用户排期与 Story Sprint 不一致")) return "schedule_story_sprint_conflict";
        if (message.contains("没有符合角色与容量要求的候选负责人")) return "owner_capacity_no_candidate";
        if (message.contains("无法安全再平衡")) return "owner_rebalance_failed";
        if (message.contains("已按依赖及项目周期调整")) return "schedule_dependency_adjustment";
        if (message.contains("重复依赖")) return "duplicate_dependency";
        if (message.contains("引用的不存在任务")) return "missing_dependency";
        if (message.contains("对自身的非法依赖")) return "self_dependency";
        return null;
    }

    private void collectImportIntegrityIssues(ObjectNode draft, ArrayNode hard, ArrayNode warnings) {
        Set<String> seenIssues = new LinkedHashSet<>();
        Set<String> storyIds = new LinkedHashSet<>();
        Set<String> taskIds = new LinkedHashSet<>();
        for (JsonNode story : draft.path("stories")) {
            String id = story.path("id").asText("").trim();
            if (id.isBlank()) addImportIssue(hard, seenIssues, "Story ID 为空");
            else if (!storyIds.add(id.toUpperCase(Locale.ROOT))) addImportIssue(hard, seenIssues, "重复 Story ID：" + id);
            if (story.has("_import_duplicate_id")) addImportIssue(hard, seenIssues,
                    "重复 Story ID：" + story.path("_import_duplicate_id").asText(id));
            if (story.has("_import_invalid_owner")) addImportIssue(hard, seenIssues,
                    "Story " + id + " 的负责人不存在：" + story.path("_import_invalid_owner").asText());
            if (story.path("owner_id").isIntegralNumber() && story.path("owner_id").asInt() > 0
                    && !importOwnerExists(draft, story.path("owner_id").asInt())) {
                addImportIssue(hard, seenIssues, "Story " + id + " 的负责人不存在：" + story.path("owner_id").asInt());
            }
        }
        for (JsonNode task : draft.path("tasks")) {
            String id = task.path("id").asText("").trim();
            String key = id.toUpperCase(Locale.ROOT);
            if (id.isBlank()) addImportIssue(hard, seenIssues, "Task ID 为空");
            else if (!taskIds.add(key)) addImportIssue(hard, seenIssues, "重复 Task ID：" + id);
            if (task.has("_import_duplicate_id")) addImportIssue(hard, seenIssues,
                    "重复 Task ID：" + task.path("_import_duplicate_id").asText(id));
            if (task.has("_import_invalid_owner")) addImportIssue(hard, seenIssues,
                    "Task " + id + " 的负责人不存在：" + task.path("_import_invalid_owner").asText());
            if (task.path("owner_id").isIntegralNumber() && task.path("owner_id").asInt() > 0
                    && !importOwnerExists(draft, task.path("owner_id").asInt())) {
                addImportIssue(hard, seenIssues, "Task " + id + " 的负责人不存在：" + task.path("owner_id").asInt());
            }
            if (task.has("_import_invalid_week")) addImportIssue(hard, seenIssues, "Task " + id + " 的周次超出项目范围");
            JsonNode start = task.get("week_start"), end = task.get("week_end");
            int weekLimit = totalWeeks(draft.path("sprints"));
            if ((start != null && start.isIntegralNumber() && (start.asInt() < 1 || start.asInt() > weekLimit))
                    || (end != null && end.isIntegralNumber() && (end.asInt() < 1 || end.asInt() > weekLimit))) {
                addImportIssue(hard, seenIssues, "Task " + id + " 的周次超出项目范围 1-" + weekLimit);
            }
            if (start != null && end != null && start.isIntegralNumber() && end.isIntegralNumber()
                    && start.asInt() > end.asInt()) {
                addImportIssue(hard, seenIssues, "Task " + id + " 的开始周晚于结束周");
            }
            String rawStoryRefs = task.path("story_ref").asText("");
            if (task.has("_import_invalid_story_refs")) addImportIssue(hard, seenIssues,
                    "Task " + id + " 引用了不存在的 Story：" + task.path("_import_invalid_story_refs").asText());
            boolean linked = false;
            for (String ref : referenceTokens(rawStoryRefs)) {
                if (storyIds.contains(ref.toUpperCase(Locale.ROOT))) linked = true;
                else addImportIssue(hard, seenIssues, "Task " + id + " 引用了不存在的 Story：" + ref);
            }
            if (!rawStoryRefs.isBlank() && !linked) addImportIssue(hard, seenIssues,
                    "Task " + id + " 的 Story 关联无效");

            Set<String> dependencies = new LinkedHashSet<>();
            for (String dependency : dependencyRefs(task)) {
                String dependencyKey = dependency.trim().toUpperCase(Locale.ROOT);
                if (!dependencies.add(dependencyKey)) {
                    warnings.add("Task " + id + " 存在重复依赖：" + dependency);
                    continue;
                }
                if (dependencyKey.equals(key)) addImportIssue(hard, seenIssues, "Task " + id + " 存在自依赖");
                else if (!taskIds.contains(dependencyKey)) addImportIssue(hard, seenIssues,
                        "Task " + id + " 引用了不存在的 Task：" + dependency);
            }
        }
        JsonNode repairs = draft.path("dependencyRepairs");
        if (repairs.path("removedInvalidDependencies").asInt(0) > 0)
            addImportIssue(hard, seenIssues, "存在非法 Task 依赖");
        if (repairs.path("removedSelfDependencies").asInt(0) > 0)
            addImportIssue(hard, seenIssues, "存在 Task 自依赖");
        if (repairs.path("dependencyCycleRepairs").asInt(0) > 0)
            addImportIssue(hard, seenIssues, "存在循环 Task 依赖");
        if (draft.path("tasks").isArray()) {
            List<String> cycle = findDependencyCycle((ArrayNode) draft.path("tasks"));
            if (!cycle.isEmpty()) addImportIssue(hard, seenIssues, "存在循环 Task 依赖：" + String.join(" -> ", cycle));
        }
        for (JsonNode milestone : draft.path("milestones")) {
            String milestoneId = milestone.path("id").asText(milestone.path("name").asText("未知 Milestone"));
            if (milestone.has("_import_invalid_task_refs")) addImportIssue(hard, seenIssues,
                    "Milestone " + milestoneId + " 引用了不存在的 Task：" + milestone.path("_import_invalid_task_refs").asText());
            for (String ref : referenceTokens(milestone.path("related_task_ids").asText(""))) {
                if (!taskIds.contains(ref.toUpperCase(Locale.ROOT))) addImportIssue(hard, seenIssues,
                        "Milestone " + milestoneId + " 引用了不存在的 Task：" + ref);
            }
        }
    }

    private void addImportIssue(ArrayNode issues, Set<String> seen, String message) {
        if (seen.add(message)) issues.add(message);
    }

    private boolean importOwnerExists(ObjectNode draft, int ownerId) {
        if (draft.path("members").isArray() && draft.path("members").size() > 0) {
            for (JsonNode member : draft.path("members")) if (member.path("id").asInt() == ownerId) return true;
            return false;
        }
        return users.selectById(ownerId) != null;
    }

    /** Revalidates a user-edited draft without renumbering rows or moving task schedules. */
    public ObjectNode revalidateEditedDraft(ObjectNode draft, String strategy) { return revalidateEditedDraft(draft, strategy, "NEW-LOCAL"); }

    public ObjectNode revalidateEditedDraft(ObjectNode draft, String strategy, String projectId) {
        if (!draft.path("schema_version").isInt() || !draft.path("stories").isArray()
                || !draft.path("tasks").isArray() || !draft.path("sprints").isArray()) {
            throw ApiException.conflict("生成草案格式已失效，请重新生成");
        }

        Set<Integer> sprintIds = new HashSet<>();
        for (JsonNode sprint : draft.path("sprints")) {
            if (sprint.path("sprint").isIntegralNumber()) sprintIds.add(sprint.path("sprint").asInt());
        }
        if (sprintIds.isEmpty()) throw ApiException.badRequest("草案中没有可用 Sprint");

        Set<String> storyIds = new HashSet<>();
        for (JsonNode story : draft.path("stories")) {
            String id = story.path("id").asText("").trim();
            String title = story.path("title").asText("").trim();
            String priority = story.path("priority").asText("");
            if (id.isBlank() || !storyIds.add(id)) throw ApiException.badRequest("Story ID 重复或为空: " + id);
            if (title.isBlank()) throw ApiException.badRequest(id + " 的故事标题不能为空");
            if (title.length() > 200) throw ApiException.badRequest(id + " 的故事标题不能超过 200 个字符");
            if (!Set.of("Must", "Should", "Could").contains(priority)) throw ApiException.badRequest(id + " 的优先级不合法");
            if (!story.path("sprint").isIntegralNumber() || !sprintIds.contains(story.path("sprint").asInt())) {
                throw ApiException.badRequest(id + " 引用了不存在的 Sprint");
            }
            JsonNode owner = story.path("owner_id");
            if (!owner.isNull() && owner.isIntegralNumber() && users.selectById(owner.asInt()) == null) {
                throw ApiException.badRequest(id + " 的负责人不存在");
            }
        }

        Set<String> taskIds = new HashSet<>();
        for (JsonNode task : draft.path("tasks")) {
            String id = task.path("id").asText("").trim();
            if (id.isBlank() || !taskIds.add(id)) throw ApiException.badRequest("Task ID 重复或为空: " + id);
            if (users.selectById(task.path("owner_id").asInt()) == null) throw ApiException.badRequest(id + " 的负责人不存在");
            int start = task.path("week_start").asInt(0), end = task.path("week_end").asInt(0);
            if (start < 1 || end > 6 || start > end) throw ApiException.badRequest(id + " 的任务周次不合法");
            for (String storyRef : task.path("story_ref").asText("").split(",")) {
                if (!storyRef.isBlank() && !storyIds.contains(storyRef.trim())) {
                    throw ApiException.badRequest(id + " 引用了不存在的 Story: " + storyRef.trim());
                }
            }
        }
        validateDependencyGraph(draft.path("tasks"), false);
        validateDependencySchedule(draft.path("tasks"), false);

        ArrayNode storiesNode = (ArrayNode) draft.path("stories");
        ArrayNode tasksNode = (ArrayNode) draft.path("tasks");
        draft.set("dependencies", dependencies(tasksNode));
        draft.set("uml", uml(storiesNode, members(projectId)));
        ObjectNode riskReport = risks(storiesNode, tasksNode, strategy, projectId);
        draft.set("risks", riskReport);
        draft.set("warnings", warnings(riskReport));
        draft.put("story_count", storiesNode.size());
        draft.put("task_count", tasksNode.size());
        return draft;
    }

    private ArrayNode members(String projectId) {
        ArrayNode a = mapper.createArrayNode();
        if (isNewScope(projectId)) {
            users.selectList(new QueryWrapper<User>().orderByAsc("id")).forEach(u -> {
                ObjectNode n = mapper.createObjectNode();
                n.put("id", u.getId()); n.put("name", u.getDisplayName()); n.put("role", u.getRole()); n.put("capacity_hours", u.getCapacityHours()); a.add(n);
            });
            return a;
        }
        if (projectMembers == null) {
            users.selectList(new QueryWrapper<User>().orderByAsc("id")).forEach(u -> {
                ObjectNode n = mapper.createObjectNode();
                n.put("id", u.getId()); n.put("name", u.getDisplayName()); n.put("role", u.getRole()); n.put("capacity_hours", u.getCapacityHours()); a.add(n);
            });
            return a;
        }
        projectMembers.selectList(new QueryWrapper<com.aicap.entity.ProjectMember>().eq("project_id", projectId).orderByAsc("user_id"))
                .stream().map(member -> users.selectById(member.getUserId())).filter(java.util.Objects::nonNull).forEach(u -> {
                    ObjectNode n = mapper.createObjectNode(); n.put("id", u.getId()); n.put("name", u.getDisplayName()); n.put("role", u.getRole()); n.put("capacity_hours", u.getCapacityHours()); a.add(n);
                });
        return a;
    }
    private int sprintCount(JsonNode raw) {
        JsonNode project = raw.path("project");
        if (project.path("total_sprints").isIntegralNumber()) return Math.max(1, Math.min(6, project.path("total_sprints").asInt()));
        if (raw.path("sprints").isArray() && !raw.path("sprints").isEmpty()) return Math.max(1, Math.min(6, raw.path("sprints").size()));
        int weeks = project.path("duration_weeks").isIntegralNumber() ? project.path("duration_weeks").asInt() : 6;
        return Math.max(1, Math.min(6, (weeks + 1) / 2));
    }

    /** Preserve generated numeric Activity IDs beyond the historical 1..5 cap. */
    private int activityUpperBound(JsonNode raw) {
        int maximum = 5;
        if (raw.path("activities").isArray()) {
            for (JsonNode activity : raw.path("activities")) {
                JsonNode id = activity.isValueNode() ? activity : activity.get("id");
                if (id != null && (id.isIntegralNumber() || id.isTextual())) {
                    int parsed = id.asInt(0);
                    if (parsed > 0) maximum = Math.max(maximum, parsed);
                }
            }
        }
        return Math.max(1, Math.min(100, maximum));
    }
    private void configureImportSprintRanges(ObjectNode raw, ArrayNode rawTasks, int sprintCount) {
        JsonNode supplied = raw.path("sprints");
        boolean complete = supplied.isArray() && supplied.size() >= sprintCount;
        if (complete) {
            for (int i = 0; i < sprintCount; i++) {
                JsonNode sprint = supplied.get(i);
                if (!sprint.path("week_start").isIntegralNumber() || !sprint.path("week_end").isIntegralNumber()) {
                    complete = false;
                    break;
                }
            }
        }
        if (complete) return;

        int configuredWeeks = raw.path("project").path("duration_weeks").asInt(0);
        int lastTaskWeek = 0;
        for (JsonNode task : rawTasks) {
            lastTaskWeek = Math.max(lastTaskWeek,
                    Math.max(task.path("week_start").asInt(0), task.path("week_end").asInt(0)));
        }
        int totalWeeks = configuredWeeks > 0 ? configuredWeeks : Math.max(lastTaskWeek, sprintCount * 2);
        totalWeeks = Math.max(sprintCount, Math.min(6, totalWeeks));
        ArrayNode ranges = mapper.createArrayNode();
        for (int sprint = 1; sprint <= sprintCount; sprint++) {
            int start = ((sprint - 1) * totalWeeks) / sprintCount + 1;
            int end = (sprint * totalWeeks) / sprintCount;
            ranges.add(mapper.createObjectNode().put("sprint", sprint)
                    .put("week_start", start).put("week_end", Math.max(start, end)));
        }
        raw.set("sprints", ranges);
    }
    private ArrayNode sprints(int count) { ArrayNode a=mapper.createArrayNode(); for(int i=1;i<=count;i++){int start=(i-1)*2+1; a.add(mapper.createObjectNode().put("sprint",i).put("week_start",start).put("week_end",Math.min(6,start+1)));} return a; }
    private int[] storyWeeks(ArrayNode stories, String storyRef, int sprintCount) {
        if (storyRef == null || storyRef.isBlank()) return null;
        JsonNode story = find(stories, storyRef.split(",")[0]);
        if (story == null) return null;
        int sprint = Math.max(1, Math.min(sprintCount, story.path("sprint").asInt(1)));
        return new int[]{(sprint - 1) * 2 + 1, Math.min(6, sprint * 2)};
    }
    private ObjectNode uml(ArrayNode stories, ArrayNode members) { ObjectNode u=mapper.createObjectNode(); ArrayNode cases=mapper.createArrayNode(); stories.forEach(s -> cases.add(mapper.createObjectNode().put("id", s.path("id").asText()).put("name", s.path("title").asText()))); u.set("use_cases", cases); u.set("participants", members); return u; }
    private ArrayNode dependencies(ArrayNode tasks) {
        ArrayNode result = mapper.createArrayNode();
        tasks.forEach(task -> {
            for (String dependency : task.path("depends_on").asText("").split(","))
                if (!dependency.isBlank()) result.add(mapper.createObjectNode().put("from", dependency)
                        .put("to", task.path("id").asText()).put("class", "CORE"));
            for (String dependency : task.path("weak_dependencies").asText("").split(","))
                if (!dependency.isBlank()) result.add(mapper.createObjectNode().put("from", dependency)
                        .put("to", task.path("id").asText()).put("class", "WEAK"));
        });
        return result;
    }
    private ObjectNode risks(ArrayNode stories, ArrayNode draftTasks, String strategy, String projectId) {
        return risks(stories, draftTasks, strategy, projectId, null);
    }
    private ObjectNode risks(ArrayNode stories, ArrayNode draftTasks, String strategy, String projectId, JsonNode sprintDefinitions) {
        ObjectNode r=mapper.createObjectNode(); ArrayNode load=mapper.createArrayNode(), dep=mapper.createArrayNode(), sprint=mapper.createArrayNode(); Map<Integer,int[]> loads=new HashMap<>();
        if(!"replace".equals(strategy))for(Task t:tasks.selectList(new QueryWrapper<Task>().eq("project_id", projectId)))addLoad(loads,t.getOwnerId(),t.getHours(),t.getWeekStart(),t.getWeekEnd());
        for(JsonNode t:draftTasks)addLoad(loads,t.path("owner_id").asInt(),t.path("hours").asInt(),t.path("week_start").asInt(),t.path("week_end").asInt());
        for(var e:loads.entrySet()){User u=users.selectById(e.getKey()); if(u==null)continue; for(int w=0;w<6;w++) if(e.getValue()[w]>u.getCapacityHours()) load.add(mapper.createObjectNode().put("kind","load").put("message",u.getDisplayName()+" W"+(w+1)+" 预计 "+e.getValue()[w]+"h / 周容量 "+u.getCapacityHours()+"h"));}
        Map<String,JsonNode> byTask=new HashMap<>(); draftTasks.forEach(t->byTask.put(t.path("id").asText(),t));
        int weakRiskCount = 0;
        for(JsonNode t:draftTasks){
            for(String id:t.path("depends_on").asText("").split(",")){JsonNode p=byTask.get(id);if(p!=null&&dependencyTimingConflict(t,p))dep.add(mapper.createObjectNode().put("kind","dependency").put("message",t.path("id").asText()+" 早于前置任务 "+id+" 完成"));}
            for(String id:t.path("weak_dependencies").asText("").split(",")) if(!id.isBlank()) {
                if (weakRiskCount++ < 15)
                    dep.add(mapper.createObjectNode().put("kind","weak_dependency").put("dependencyClass","WEAK")
                            .put("message",t.path("id").asText()+" 与 "+id.trim()+" 存在弱依赖提示，不作为硬排期下界"));
            }
            int primarySprint = stablePrimaryStorySprint(t, stories);
            int[] primary = primarySprint > 0 ? sprintRange(sprintDefinitions, primarySprint) : null;
            if(primary != null && (t.path("week_end").asInt()<primary[0] || t.path("week_start").asInt()>primary[1])
                    && !dependencyRequiresCrossSprint(t, draftTasks, primary))
                sprint.add(mapper.createObjectNode().put("kind","sprint").put("taskId",t.path("id").asText())
                        .put("storySprint", primarySprint)
                        .put("message",t.path("id").asText()+" 的执行周与主要 Story Sprint "+primarySprint+" 不一致"));
        }
        if (weakRiskCount > 15) dep.add(mapper.createObjectNode().put("kind", "weak_dependency_summary")
                .put("dependencyClass", "WEAK").put("omittedCount", weakRiskCount - 15)
                .put("message", "其余 " + (weakRiskCount - 15) + " 条弱依赖已省略，仅供参考，不作为硬排期下界"));
        r.set("loadRisk",load); r.set("dependencyRisk",dep); r.set("sprintRisk",sprint); return r;
    }
    private int[] sprintRange(JsonNode definitions, int sprint) {
        if (definitions != null && definitions.isArray()) for (JsonNode definition : definitions) {
            if (definition.path("sprint").asInt() == sprint) return new int[]{definition.path("week_start").asInt(), definition.path("week_end").asInt()};
        }
        if (sprint < 1 || sprint > 3) return null;
        return new int[]{(sprint - 1) * 2 + 1, Math.min(6, sprint * 2)};
    }
    private int[] primaryStorySprintRange(JsonNode task, ArrayNode stories, JsonNode sprintDefinitions) {
        int primary = primaryStorySprint(task, stories);
        return primary > 0 ? sprintRange(sprintDefinitions, primary) : null;
    }
    private int primaryStorySprint(JsonNode task, ArrayNode stories) {
        Map<Integer,Integer> counts = new HashMap<>();
        for(String ref : task.path("story_ref").asText("").split(",")) {
            JsonNode story = find(stories, ref.trim());
            if(story != null) counts.merge(story.path("sprint").asInt(1), 1, Integer::sum);
        }
        return counts.isEmpty() ? 0 : counts.entrySet().stream().max(Map.Entry.comparingByValue()).orElseThrow().getKey();
    }
    private int stablePrimaryStorySprint(JsonNode task, ArrayNode stories) {
        Map<Integer,Integer> counts = new java.util.TreeMap<>();
        for (String ref : task.path("story_ref").asText("").split(",")) {
            JsonNode story = find(stories, ref.trim());
            if (story != null) counts.merge(story.path("sprint").asInt(1), 1, Integer::sum);
        }
        return counts.entrySet().stream().max(Map.Entry.<Integer,Integer>comparingByValue()
                .thenComparing(entry -> -entry.getKey())).map(Map.Entry::getKey).orElse(0);
    }
    /** Final, mode-independent Sprint inference. Explicit user/import values are never moved. */
    private void inferFinalStorySprints(ArrayNode stories, ArrayNode importedTasks,
                                           ArrayNode sprintDefinitions, ArrayNode issues) {
        // Only provenance markers lock a Sprint. Model-supplied numbers are
        // suggestions, including a defaulted Sprint 1 in a continuation.
        Map<Integer,Integer> counts=new HashMap<>();
        for(JsonNode story:stories) if(story.path("sprint_explicit").asBoolean(false)) counts.merge(story.path("sprint").asInt(),1,Integer::sum);
        boolean collapsedGeneratedSchedule = generatedScheduleCollapsed(importedTasks, sprintDefinitions);
        if (collapsedGeneratedSchedule)
            issues.add("检测到模型原始 Task 周次分布较集中，系统已自动按业务阶段、Sprint 分布和成员容量重新调整排期。");
        for(JsonNode storyNode:stories){
            ObjectNode story=(ObjectNode)storyNode;
            if(story.path("sprint_explicit").asBoolean(false)) continue;
            String storyId=story.path("id").asText();
            double[] scores=new double[sprintDefinitions.size()+1]; int linked=0; Set<Integer> touched=new LinkedHashSet<>();
            for(JsonNode task:importedTasks){
                boolean related=false; for(String ref:task.path("story_ref").asText("").split(",")) if(storyId.equals(ref.trim())) {related=true;break;}
                if(!related)continue; linked++;
                int start=task.path("week_start").asInt(),end=task.path("week_end").asInt(start),duration=Math.max(1,end-start+1),hours=Math.max(1,task.path("hours").asInt(1));
                double stageWeight = storySprintStageWeight(task);
                for(JsonNode definition:sprintDefinitions){
                    int number=definition.path("sprint").asInt(),overlap=Math.max(0,Math.min(end,definition.path("week_end").asInt())-Math.max(start,definition.path("week_start").asInt())+1);
                    if(overlap>0){scores[number]+=((double)hours*overlap/duration+1.0)*stageWeight;touched.add(number);}
                }
            }
            int selected;
            if(linked>0 && !collapsedGeneratedSchedule){
                selected=bestScoredSprint(scores,counts,storyId);
                if(touched.size()>1 && coreSprintDominates(storyId, importedTasks, sprintDefinitions))
                    issues.add(storyId+" 的核心实现 Task 跨越多个 Sprint，已按主要工时与执行阶段推断为 Sprint "+selected);
            }else selected=balancedSemanticSprint(story,sprintDefinitions.size(),counts);
            story.put("sprint",selected); story.put("sprint_inferred",true); counts.merge(selected,1,Integer::sum);
        }
    }

    private boolean generatedScheduleCollapsed(ArrayNode tasks, ArrayNode sprints) {
        int movable = 0;
        Map<Integer,Integer> distribution = new HashMap<>();
        for (JsonNode task : tasks) {
            if (task.path("_import_schedule_explicit").asBoolean(false)
                    || task.path("_user_schedule_explicit").asBoolean(false)) continue;
            movable++;
            int week = task.path("week_start").asInt(1);
            int sprint = 1;
            for (JsonNode definition : sprints)
                if (week >= definition.path("week_start").asInt()
                        && week <= definition.path("week_end").asInt()) sprint = definition.path("sprint").asInt(1);
            distribution.merge(sprint, 1, Integer::sum);
        }
        int largest = distribution.values().stream().mapToInt(Integer::intValue).max().orElse(0);
        return sprints.size() > 1 && movable >= Math.max(6, sprints.size() * 2)
                && largest * 100 >= movable * 80;
    }

    /**
     * Performs the last safe planning adjustments before risks/completeness are
     * calculated. It deliberately changes only AI-owned fields (the import
     * markers are carried through by ProjectGenerationImportService).
     */
    private ObjectNode finalQualityPass(ArrayNode stories, ArrayNode tasks, ArrayNode sprints,
                                        ArrayNode members, boolean importMode, ArrayNode warnings) {
        ObjectNode result = mapper.createObjectNode();
        ArrayNode storyMoves = mapper.createArrayNode(), scheduleMoves = mapper.createArrayNode(),
                ownerMoves = mapper.createArrayNode(), loadMoves = mapper.createArrayNode(), unresolved = mapper.createArrayNode();
        Map<Integer, Integer> counts = new HashMap<>();
        for (JsonNode story : stories) counts.merge(story.path("sprint").asInt(1), 1, Integer::sum);
        // A second balancing pass is only needed when a generated plan is visibly
        // skewed. Explicit stories remain fixed and are included in the counts.
        int sprintCount = Math.max(1, sprints.size());
        for (JsonNode node : stories) {
            ObjectNode story = (ObjectNode) node;
            if (story.path("sprint_explicit").asBoolean(false)) continue;
            int current = story.path("sprint").asInt(1);
            int target = storySprintFromTasks(story, tasks, sprints, counts);
            if (target != current && (counts.getOrDefault(current, 0) - counts.getOrDefault(target, 0) > 1
                    || counts.getOrDefault(current, 0) * 4 > stories.size() * 2)) {
                story.put("sprint", target);
                counts.merge(current, -1, Integer::sum); counts.merge(target, 1, Integer::sum);
                storyMoves.add(mapper.createObjectNode().put("storyId", story.path("id").asText())
                        .put("from", current).put("to", target));
            }
        }
        // Align AI-owned task windows with their Story's dominant Sprint. User
        // supplied Excel windows are preserved and surfaced as warnings.
        for (JsonNode node : tasks) {
            ObjectNode task = (ObjectNode) node;
            int target = taskTargetSprint(task, stories, sprints);
            int[] range = sprintRange(sprints, target);
            if (range == null) continue;
            int start = task.path("week_start").asInt(1), end = task.path("week_end").asInt(start);
            if (end >= range[0] && start <= range[1]) continue;
            if (task.path("_import_schedule_explicit").asBoolean(false) || task.path("_user_schedule_explicit").asBoolean(false)) {
                warnings.add(task.path("id").asText() + " 的用户排期与 Story Sprint 不一致，已保留原值");
                continue;
            }
            int duration = Math.max(0, end - start);
            int nextStart = Math.max(range[0], Math.min(start, range[1]));
            int nextEnd = Math.min(range[1], nextStart + duration);
            if (nextEnd < nextStart) { nextStart = range[0]; nextEnd = range[0]; }
            if (nextStart != start || nextEnd != end) {
                task.put("week_start", nextStart); task.put("week_end", nextEnd);
                scheduleMoves.add(mapper.createObjectNode().put("taskId", task.path("id").asText())
                        .put("from", "W" + start + "-W" + end).put("to", "W" + nextStart + "-W" + nextEnd));
            }
        }
        result.set("reassignedStorySprints", storyMoves);
        result.set("taskScheduleAdjustments", scheduleMoves);
        result.set("reassignedTaskOwners", ownerMoves);
        result.set("workloadBalanceAdjustments", loadMoves);
        result.set("unresolvedWorkloadRisks", unresolved);
        result.set("sprintBalanceAdjustments", storyMoves.deepCopy());
        return result;
    }

    private int storySprintFromTasks(JsonNode story, ArrayNode tasks, ArrayNode sprints, Map<Integer,Integer> counts) {
        if (generatedScheduleCollapsed(tasks, sprints))
            return balancedSemanticSprint(story, sprints.size(), counts);
        double[] scores = new double[sprints.size() + 1];
        for (JsonNode task : tasks) {
            boolean linked = false;
            for (String ref : task.path("story_ref").asText("").split(",")) if (story.path("id").asText().equals(ref.trim())) linked = true;
            if (!linked) continue;
            int start = task.path("week_start").asInt(1), end = task.path("week_end").asInt(start);
            int hours = Math.max(1, task.path("hours").asInt(1));
            for (JsonNode sprint : sprints) {
                int no = sprint.path("sprint").asInt(), overlap = Math.max(0,
                        Math.min(end, sprint.path("week_end").asInt()) - Math.max(start, sprint.path("week_start").asInt()) + 1);
                scores[no] += overlap * (double) hours;
            }
        }
        List<Integer> candidates = new ArrayList<>(); double best = 0;
        for (int i = 1; i < scores.length; i++) { if (scores[i] > best + .001) { best = scores[i]; candidates.clear(); candidates.add(i); } else if (Math.abs(scores[i] - best) < .001) candidates.add(i); }
        if (candidates.isEmpty()) return balancedSemanticSprint(story, sprints.size(), counts);
        return candidates.stream().min(Comparator.comparingInt(s -> counts.getOrDefault(s, 0))).orElse(candidates.get(0));
    }

    private int taskTargetSprint(JsonNode task, ArrayNode stories, ArrayNode sprints) {
        Map<Integer,Integer> counts = new HashMap<>();
        for (String ref : task.path("story_ref").asText("").split(",")) {
            JsonNode story = find(stories, ref.trim());
            if (story != null) counts.merge(story.path("sprint").asInt(1), 1, Integer::sum);
        }
        return counts.entrySet().stream().max(Map.Entry.comparingByValue())
                .map(Map.Entry::getKey).orElse(1);
    }

    /** Final soft distribution guard for generated plans. Explicit Story Sprints remain fixed. */
    private void rebalanceStorySprintDistribution(ArrayNode stories, ArrayNode tasks, ArrayNode sprints,
                                                   ObjectNode adjustments) {
        if (sprints.size() != 3 || stories.size() < 12) return;
        Map<Integer,Integer> counts = new HashMap<>();
        stories.forEach(story -> counts.merge(story.path("sprint").asInt(1), 1, Integer::sum));
        ArrayNode moves = (ArrayNode) adjustments.path("reassignedStorySprints");
        int minimum = Math.max(1, (int) Math.ceil(stories.size() * 0.25));
        int maximum = Math.max(minimum, (int) Math.floor(stories.size() * 0.40));
        for (int target = 1; target <= 3; target++) {
            while (counts.getOrDefault(target, 0) < minimum) {
                int source = overfullSprint(counts, maximum, target);
                if (source == 0) break;
                ObjectNode candidate = chooseSprintMove(stories, source, target, counts);
                if (candidate == null) break;
                int from = candidate.path("sprint").asInt(source);
                candidate.put("sprint", target).put("sprint_inferred", true);
                counts.merge(from, -1, Integer::sum); counts.merge(target, 1, Integer::sum);
                moves.add(mapper.createObjectNode().put("storyId", candidate.path("id").asText())
                        .put("from", from).put("to", target).put("reason", "软分布质量调整"));
            }
        }
    }

    private int overfullSprint(Map<Integer,Integer> counts, int maximum, int excluded) {
        return counts.entrySet().stream()
                .filter(entry -> entry.getKey() != excluded && entry.getValue() > maximum)
                .max(Map.Entry.comparingByValue()).map(Map.Entry::getKey).orElseGet(() ->
                        counts.entrySet().stream().filter(entry -> entry.getKey() != excluded)
                                .max(Map.Entry.comparingByValue()).map(Map.Entry::getKey).orElse(0));
    }

    private ObjectNode chooseSprintMove(ArrayNode stories, int source, int target, Map<Integer,Integer> counts) {
        ObjectNode best = null; int bestScore = Integer.MIN_VALUE;
        for (JsonNode node : stories) {
            ObjectNode story = (ObjectNode) node;
            if (story.path("sprint").asInt() != source || story.path("sprint_explicit").asBoolean(false)) continue;
            int preferred = preferredSemanticSprint(story, 3);
            if (preferred == source && preferred != 0) continue;
            int score = preferred == target ? 100 : preferred == 0 ? 40 : 0;
            score -= Math.abs(story.path("sprint").asInt() - target);
            if (score > bestScore) { bestScore = score; best = story; }
        }
        return best;
    }

    private void reconcileDelayedStories(ArrayNode stories, ArrayNode tasks, ArrayNode sprints, ObjectNode adjustments) {
        Map<Integer, Integer> counts = new HashMap<>();
        stories.forEach(story -> counts.merge(story.path("sprint").asInt(1), 1, Integer::sum));
        ArrayNode moves = (ArrayNode) adjustments.path("reassignedStorySprints");
        for (JsonNode node : stories) {
            ObjectNode story = (ObjectNode) node;
            if (story.path("sprint_explicit").asBoolean(false)) continue;
            int from = story.path("sprint").asInt(1);
            int to = storySprintFromTasks(story, tasks, sprints, counts);
            if (from == to) continue;
            story.put("sprint", to);
            counts.merge(from, -1, Integer::sum);
            counts.merge(to, 1, Integer::sum);
            moves.add(mapper.createObjectNode().put("storyId", story.path("id").asText()).put("from", from).put("to", to));
        }
        adjustments.set("sprintBalanceAdjustments", moves.deepCopy());
    }

    private void rebalanceOwners(ArrayNode tasks, ArrayNode members, boolean importMode, ArrayNode ownerMoves,
                                 ArrayNode loadMoves, ArrayNode unresolved, ArrayNode warnings) {
        if (members == null || members.isEmpty()) {
            String message = "没有可分配成员，无法安全再平衡";
            warnings.add(message); unresolved.add(message);
            return;
        }
        for (JsonNode node : tasks) {
            ObjectNode task = (ObjectNode) node;
            if (task.path("_import_owner_explicit").asBoolean(false) || task.path("_user_owner_explicit").asBoolean(false)) continue;
            int owner = task.path("owner_id").asInt();
            int start = task.path("week_start").asInt(1), end = task.path("week_end").asInt(start);
            User currentOwner = users.selectById(owner);
            boolean ownerListed = java.util.stream.StreamSupport.stream(members.spliterator(), false)
                    .anyMatch(member -> member.path("id").asInt() == owner);
            if (!ownerListed) {
                String message = task.path("id").asText() + " 无法安全再平衡：负责人 " + owner + " 不在当前成员列表中，已保留原负责人";
                warnings.add(message); unresolved.add(message); continue;
            }
            if (currentOwner == null || start < 1 || end > 6 || start > end) {
                String issue = (start < 1 || end > 6 || start > end)
                        ? "排期 W" + start + "-W" + end + " 超出有效周次"
                        : "负责人 " + owner + " 不在当前成员列表或不存在";
                String message = task.path("id").asText() + " 无法安全再平衡：" + issue + "，已保留原负责人";
                warnings.add(message); unresolved.add(message);
                continue;
            }
            Map<Integer, int[]> loads = weeklyLoads(tasks);
            boolean overloaded = false;
            for (int w = start; w <= end; w++) overloaded |= loads.getOrDefault(owner, new int[6])[w - 1] > currentOwner.getCapacityHours();
            if (!overloaded) continue;
            long beforeRisk = capacityRisk(tasks, members);
            int best = owner; double bestScore = Double.POSITIVE_INFINITY; long bestRisk = beforeRisk;
            int hours = Math.max(0, task.path("hours").asInt());
            for (JsonNode member : members) {
                int candidate = member.path("id").asInt();
                if (candidate <= 0 || candidate == owner || users.selectById(candidate) == null
                        || Set.of("viewer", "readonly", "read_only").contains(member.path("role").asText())) continue;
                double roleFit = responsibilityFit(task, member);
                if (roleFit < 0) continue; // genuine permission/capability exclusion only
                task.put("owner_id", candidate);
                long afterRisk = capacityRisk(tasks, members);
                int[] projected = weeklyLoads(tasks).getOrDefault(candidate, new int[6]);
                int cap = Math.max(1, member.path("capacity_hours").asInt(40));
                double utilization = 0;
                for (int w = start; w <= end; w++) utilization += (double) projected[w - 1] / cap;
                double score = afterRisk * 1000.0 + utilization - roleFit
                        - (sameResponsibility(task, candidate, tasks) ? 0.15 : 0);
                task.put("owner_id", owner);
                // Accept any move that lowers total risk; a small residual overload is
                // preferable to retaining a materially worse peak on the original owner.
                if (afterRisk < bestRisk || (afterRisk == bestRisk && score < bestScore)) {
                    best = candidate; bestRisk = afterRisk; bestScore = score;
                }
            }
            if (best != owner && (bestRisk < beforeRisk || (bestRisk == beforeRisk && bestScore < Double.POSITIVE_INFINITY))) {
                task.put("owner_id", best);
                ownerMoves.add(mapper.createObjectNode().put("taskId", task.path("id").asText()).put("from", owner).put("to", best));
                loadMoves.add(task.path("id").asText());
            } else {
                String message = task.path("id").asText() + " 没有符合容量与能力要求、且能降低整体容量风险的候选负责人，已保留原负责人";
                warnings.add(message); unresolved.add(message);
            }
        }
    }

    private long capacityRisk(ArrayNode tasks, ArrayNode members) {
        Map<Integer, int[]> loads = weeklyLoads(tasks);
        long risk = 0;
        for (JsonNode member : members) {
            int cap = Math.max(1, member.path("capacity_hours").asInt(40));
            int[] weekly = loads.getOrDefault(member.path("id").asInt(), new int[6]);
            for (int w = 0; w < weekly.length; w++) {
                long over = Math.max(0, weekly[w] - cap);
                risk += over * over;
            }
        }
        return risk;
    }

    /**
     * Read-only feasibility gate for detailed drafts.  It deliberately does
     * not delete or rewrite tasks; it records the deterministic facts that
     * later relation, dependency, and capacity phases must use.
     */
    private ObjectNode deterministicTaskFeasibility(ArrayNode stories, ArrayNode tasks,
                                                    ArrayNode sprints, ArrayNode members) {
        ObjectNode result = mapper.createObjectNode();
        long totalHours = 0;
        Map<String, Integer> perStory = new java.util.TreeMap<>();
        Map<Integer, Integer> sprintHours = new java.util.TreeMap<>();
        Set<String> seenTaskCodes = new HashSet<>();
        int duplicateTaskCodeCount = 0;
        for (JsonNode story : stories) perStory.put(story.path("id").asText(), 0);
        for (JsonNode task : tasks) {
            String code = task.path("id").asText();
            if (!seenTaskCodes.add(code)) duplicateTaskCodeCount++;
            int hours = Math.max(0, task.path("hours").asInt(task.path("estimated_hours").asInt(0)));
            totalHours += hours;
            LinkedHashSet<String> refs = new LinkedHashSet<>();
            for (String ref : task.path("story_ref").asText("").split(",")) if (!ref.isBlank()) refs.add(ref.trim());
            for (String ref : refs) if (perStory.containsKey(ref)) perStory.merge(ref, 1, Integer::sum);
            int week = Math.max(1, task.path("week_start").asInt(1));
            int sprint = sprintForWeek(sprints, week);
            sprintHours.merge(sprint, hours, Integer::sum);
        }
        long weeklyCapacity = 0;
        for (JsonNode member : members) if (isAssignableDeveloper(member))
            weeklyCapacity += Math.max(0, member.path("capacity_hours").asInt(0));
        int weeks = Math.max(1, totalWeeks(sprints));
        result.put("taskCount", tasks.size());
        result.put("totalHours", totalHours);
        result.put("teamCapacityHours", weeklyCapacity * weeks);
        result.put("capacityFeasible", totalHours <= weeklyCapacity * weeks);
        result.put("duplicateTaskCodeCount", duplicateTaskCodeCount);
        ObjectNode storyCounts = mapper.createObjectNode();
        perStory.forEach(storyCounts::put);
        result.set("taskCountByStory", storyCounts);
        ObjectNode sprintTotals = mapper.createObjectNode();
        sprintHours.forEach((s, h) -> sprintTotals.put("S" + s, h));
        result.set("hoursBySprint", sprintTotals);
        result.put("storyCount", stories.size());
        return result;
    }

    private int sprintForWeek(ArrayNode sprints, int week) {
        for (JsonNode sprint : sprints) {
            if (week >= sprint.path("week_start").asInt(1)
                    && week <= sprint.path("week_end").asInt(week)) return sprint.path("sprint").asInt(1);
        }
        return Math.max(1, (week + 1) / 2);
    }
    private void rebalanceSchedules(ArrayNode tasks, ArrayNode stories, ArrayNode sprints, ArrayNode members,
                                    ArrayNode scheduleMoves, ArrayNode loadMoves) {
        for (JsonNode node : tasks) {
            ObjectNode task = (ObjectNode) node;
            if (task.path("_import_schedule_explicit").asBoolean(false)
                    || task.path("_user_schedule_explicit").asBoolean(false)) continue;
            int start = task.path("week_start").asInt(), end = task.path("week_end").asInt();
            int lower = 1, upper = 6;
            for (String ref : task.path("story_ref").asText("").split(",")) {
                JsonNode story = find(stories, ref.trim());
                if (story == null) continue;
                int[] range = sprintRange(sprints, story.path("sprint").asInt());
                if (range != null) { lower = Math.max(lower, range[0]); upper = Math.min(upper, range[1]); }
            }
            for (String ref : task.path("depends_on").asText("").split(",")) {
                JsonNode predecessor = find(tasks, ref.trim());
                if (predecessor != null) lower = Math.max(lower, predecessor.path("week_end").asInt());
            }
            for (JsonNode other : tasks) {
                if (other == task) continue;
                for (String ref : other.path("depends_on").asText("").split(","))
                    if (ref.trim().equals(task.path("id").asText())) upper = Math.min(upper, other.path("week_start").asInt());
            }
            int bestStart = start, bestEnd = end;
            int bestScore = overloadScore(tasks, members);
            int originalDuration = Math.max(0, end-start);
            int maxDuration = Math.min(upper-lower,
                    Math.max(originalDuration, task.path("hours").asInt() >= 16 ? 1 : originalDuration));
            for (int duration = originalDuration; duration <= maxDuration; duration++) {
                for (int candidate = lower; candidate+duration <= upper; candidate++) {
                    task.put("week_start", candidate); task.put("week_end", candidate+duration);
                    int score = overloadScore(tasks, members);
                    if (score < bestScore) { bestScore = score; bestStart = candidate; bestEnd = candidate+duration; }
                }
            }
            task.put("week_start", bestStart); task.put("week_end", bestEnd);
            if (bestStart != start || bestEnd != end) {
                scheduleMoves.add(mapper.createObjectNode().put("taskId", task.path("id").asText())
                        .put("from", "W"+start+"-W"+end).put("to", "W"+bestStart+"-W"+bestEnd));
                loadMoves.add(task.path("id").asText()+" 周次再平衡");
            }
        }
    }
    private int overloadScore(ArrayNode tasks, ArrayNode members) {
        Map<Integer,int[]> loads = new HashMap<>();
        tasks.forEach(task -> addLoad(loads, task.path("owner_id").asInt(), task.path("hours").asInt(),
                task.path("week_start").asInt(), task.path("week_end").asInt()));
        long score = 0;
        for (JsonNode member : members) {
            int[] weekly = loads.getOrDefault(member.path("id").asInt(), new int[6]);
            int capacity=Math.max(1,member.path("capacity_hours").asInt(40));
            for (int hours : weekly) {
                int utilization=hours*100/capacity;
                int overload=Math.max(0,utilization-100);
                score += (long)overload*overload*1000L + (long)utilization*utilization;
            }
        }
        return (int)Math.min(Integer.MAX_VALUE,score);
    }

    private void rebalanceOverloadedSprintWeeks(ArrayNode tasks, ArrayNode stories, ArrayNode sprints,
                                                ArrayNode members, ArrayNode scheduleMoves, ArrayNode loadMoves) {
        for (int pass = 0; pass < tasks.size(); pass++) {
            Map<Integer,int[]> loads = weeklyLoads(tasks);
            boolean moved = false;
            for (JsonNode node : tasks) {
                ObjectNode task = (ObjectNode) node;
                if (task.path("_import_schedule_explicit").asBoolean(false)
                        || task.path("_user_schedule_explicit").asBoolean(false)
                        || task.path("week_start").asInt() != task.path("week_end").asInt()) continue;
                int week = task.path("week_start").asInt();
                int[] range = sprintRangeForTask(task, stories, sprints);
                if (range == null || week < range[0] || week > range[1]) continue;
                int owner = task.path("owner_id").asInt(), hours = task.path("hours").asInt();
                User member = users.selectById(owner); if (member == null) continue;
                int capacity = Math.max(1, member.getCapacityHours());
                int alternative = week == range[0] ? range[1] : range[0];
                if (loads.getOrDefault(owner, new int[6])[week - 1] <= capacity * 1.10) continue;
                if (!canMoveTaskToWeek(task, alternative, tasks)) continue;
                int current = loads.getOrDefault(owner, new int[6])[week - 1];
                int target = loads.getOrDefault(owner, new int[6])[alternative - 1];
                if (target + hours > capacity * 1.20 && target >= current) continue;
                task.put("week_start", alternative).put("week_end", alternative);
                scheduleMoves.add(mapper.createObjectNode().put("taskId", task.path("id").asText())
                        .put("from", "W" + week + "-W" + week).put("to", "W" + alternative + "-W" + alternative)
                        .put("reason", "同 Sprint 联合容量回移"));
                loadMoves.add(task.path("id").asText() + " 同 Sprint 周次回移");
                moved = true; break;
            }
            if (!moved) break;
        }
    }

    private Map<Integer,int[]> weeklyLoads(ArrayNode tasks) {
        Map<Integer,int[]> loads = new HashMap<>();
        tasks.forEach(task -> addLoad(loads, task.path("owner_id").asInt(), task.path("hours").asInt(),
                task.path("week_start").asInt(), task.path("week_end").asInt()));
        return loads;
    }

    private int[] sprintRangeForTask(JsonNode task, ArrayNode stories, ArrayNode sprints) {
        int sprint = stablePrimaryStorySprint(task, stories);
        if (sprint <= 0) sprint = 1;
        return sprintRange(sprints, sprint);
    }

    private int[] legacySprintRangeForTask(JsonNode task, ArrayNode stories, ArrayNode sprints) {
        int sprint = primaryStorySprint(task, stories);
        if (sprint <= 0) sprint = 1;
        return sprintRange(sprints, sprint);
    }

    private boolean canMoveTaskToWeek(JsonNode task, int week, ArrayNode tasks) {
        if (week < 1 || week > 6) return false;
        for (String ref : task.path("depends_on").asText("").split(",")) {
            JsonNode predecessor = find(tasks, ref.trim());
            if (predecessor != null && predecessor.path("week_end").asInt() > week) return false;
        }
        for (JsonNode other : tasks) {
            if (other == task) continue;
            if (java.util.Arrays.stream(other.path("depends_on").asText("").split(","))
                    .map(String::trim).anyMatch(task.path("id").asText()::equals)
                    && other.path("week_start").asInt() < week) return false;
        }
        return true;
    }

    private void synchronizeTaskStorySprintWeeks(ArrayNode tasks, ArrayNode stories, ArrayNode sprints,
                                                 ArrayNode scheduleMoves) {
        for (JsonNode node : tasks) {
            ObjectNode task = (ObjectNode) node;
            if (task.path("_import_schedule_explicit").asBoolean(false)
                    || task.path("_user_schedule_explicit").asBoolean(false)) continue;
            int[] range = legacySprintRangeForTask(task, stories, sprints);
            if (range == null) continue;
            int start = task.path("week_start").asInt(), end = task.path("week_end").asInt();
            if (start >= range[0] && end <= range[1]) continue;
            int duration = Math.max(0, end - start);
            int nextStart = Math.max(range[0], Math.min(start, range[1]));
            int nextEnd = Math.min(range[1], nextStart + duration);
            if (nextEnd < nextStart) { nextStart = range[0]; nextEnd = range[0]; }
            if (!canMoveTaskToWeek(task, nextStart, tasks)) continue;
            task.put("week_start", nextStart).put("week_end", nextEnd);
            scheduleMoves.add(mapper.createObjectNode().put("taskId", task.path("id").asText())
                    .put("from", "W" + start + "-W" + end).put("to", "W" + nextStart + "-W" + nextEnd)
                    .put("reason", "Story Sprint 最终一致性同步"));
        }
    }

    private void finalSprintConsistencyClosure(ArrayNode tasks, ArrayNode stories, ArrayNode sprints,
                                               int totalWeeks, ArrayNode scheduleMoves, ArrayNode warnings) {
        for (int pass = 0; pass < 3; pass++) {
            String before = scheduleSignature(tasks);
            repairDependencySchedule(tasks, totalWeeks, warnings);
            synchronizeTaskStorySprintWeeks(tasks, stories, sprints, scheduleMoves);
            repairDependencySchedule(tasks, totalWeeks, warnings);
            if (before.equals(scheduleSignature(tasks))) break;
            if (pass == 2) warnings.add("最终 Sprint/周次收口达到最大迭代次数，已保留依赖合法的稳定排期");
        }
    }

    private String scheduleSignature(ArrayNode tasks) {
        StringBuilder signature = new StringBuilder();
        for (JsonNode task : tasks) signature.append(task.path("id").asText()).append(':')
                .append(task.path("week_start").asInt()).append('-')
                .append(task.path("week_end").asInt()).append(';');
        return signature.toString();
    }

    private boolean dependencyRequiresCrossSprint(JsonNode task, ArrayNode tasks, int[] primaryRange) {
        if (task.path("depends_on").asText("").isBlank()) return false;
        for (String ref : task.path("depends_on").asText("").split(",")) {
            JsonNode predecessor = find(tasks, ref.trim());
            if (predecessor != null && predecessor.path("week_end").asInt() > primaryRange[1]) return true;
        }
        return false;
    }
    private void workloadWarnings(ArrayNode tasks, ArrayNode members, ArrayNode unresolved) {
        Map<Integer,int[]> loads = new HashMap<>();
        tasks.forEach(task -> addLoad(loads, task.path("owner_id").asInt(), task.path("hours").asInt(),
                task.path("week_start").asInt(), task.path("week_end").asInt()));
        for (JsonNode member : members) {
            int[] weekly = loads.getOrDefault(member.path("id").asInt(), new int[6]);
            for (int w=0; w<weekly.length; w++) if (weekly[w] > member.path("capacity_hours").asInt(40))
                unresolved.add(member.path("name").asText()+" W"+(w+1)+" 预计 "+weekly[w]
                        +"h / 容量 "+member.path("capacity_hours").asInt(40)+"h，无法安全再平衡");
        }
    }
    private boolean sameResponsibility(JsonNode task, int candidate, ArrayNode tasks) {
        String refs = task.path("story_ref").asText("");
        String type = task.path("task_type").asText("");
        for (JsonNode other : tasks) {
            if (other == task || other.path("owner_id").asInt() != candidate) continue;
            if (!refs.isBlank() && !other.path("story_ref").asText("").isBlank()) {
                for (String ref : refs.split(","))
                    if (java.util.Arrays.asList(other.path("story_ref").asText("").split(",")).contains(ref)) return true;
            }
            if (!type.isBlank() && !"feature".equals(type) && type.equals(other.path("task_type").asText())) return true;
        }
        return false;
    }
    private int bestScoredSprint(double[] scores,Map<Integer,Integer> counts,String stableKey){
        double best=-1; List<Integer> candidates=new ArrayList<>();
        for(int sprint=1;sprint<scores.length;sprint++){
            if(scores[sprint]>best+0.0001){best=scores[sprint];candidates.clear();candidates.add(sprint);}
            else if(Math.abs(scores[sprint]-best)<0.0001)candidates.add(sprint);
        }
        int min=candidates.stream().mapToInt(s->counts.getOrDefault(s,0)).min().orElse(0);
        List<Integer> balanced=candidates.stream().filter(s->counts.getOrDefault(s,0)==min).toList();
        return balanced.stream().min(Integer::compareTo).orElse(1);
    }
    private int balancedSemanticSprint(JsonNode story,int sprintCount,Map<Integer,Integer> counts){
        int preferred=preferredSemanticSprint(story,sprintCount);
        int minimum=Integer.MAX_VALUE;
        for(int sprint=1;sprint<=sprintCount;sprint++)minimum=Math.min(minimum,counts.getOrDefault(sprint,0));
        if(preferred>0&&counts.getOrDefault(preferred,0)<=minimum+1)return preferred;
        List<Integer> candidates=new ArrayList<>(); int best=Integer.MAX_VALUE;
        for(int sprint=1;sprint<=sprintCount;sprint++){int count=counts.getOrDefault(sprint,0);if(count<best){best=count;candidates.clear();candidates.add(sprint);}else if(count==best)candidates.add(sprint);}
        return candidates.stream().min(Integer::compareTo).orElse(1);
    }
    private int preferredSemanticSprint(JsonNode story,int sprintCount){
        String text=(story.path("title").asText("")+" "+story.path("description").asText("")+" "+story.path("activity").asText("")).toLowerCase(Locale.ROOT);
        int preferred=0;
        if(containsAny(text,"基础账号","权限","登录","注册","基础信息","数据结构","数据库","核心流程入口","setup","foundation","auth"))preferred=1;
        else if(containsAny(text,"统计","审计","安全增强","异常处理","测试","验收","优化","上线","部署","帮助说明","导出","归档","移动端","report","audit","test","deploy"))preferred=sprintCount;
        else if(sprintCount>=2&&containsAny(text,"核心业务","排班","预约状态","通知","提醒","请假","不可用时间","工作台","联调","integration"))preferred=Math.min(2,sprintCount);
        return preferred;
    }
    private void addLoad(Map<Integer,int[]> loads,Integer owner,Integer hours,Integer start,Integer end){if(owner==null||start==null||end==null)return;int h=hours==null?0:hours,c=Math.max(1,end-start+1);int[] x=loads.computeIfAbsent(owner,k->new int[6]);for(int w=Math.max(1,start);w<=Math.min(6,end);w++)x[w-1]+=h/c+(w-start<h%c?1:0);}
    private JsonNode find(ArrayNode rows,String id){for(JsonNode row:rows)if(id.equals(row.path("id").asText()))return row;return null;}
    private Map<String,List<String>> topLevelDependencies(JsonNode dependencies){
        Map<String,List<String>> result=new LinkedHashMap<>();
        if(!dependencies.isArray())return result;
        for(JsonNode edge:dependencies){
            if(!edge.isObject())continue;
            String from=firstText(edge,"from","source","predecessor","depends_on");
            String to=firstText(edge,"to","target","successor","task_id");
            if(!from.isBlank()&&!to.isBlank())result.computeIfAbsent(to.toUpperCase(Locale.ROOT),k->new ArrayList<>()).add(from);
        }
        return result;
    }
    private boolean containsMappedDependency(List<String> values,String candidate,Map<String,String> ids){
        String candidateKey=candidate==null?"":candidate.trim().toUpperCase(Locale.ROOT);
        String candidateMapped=ids.getOrDefault(candidateKey,candidateKey);
        for(String value:values){
            String key=value==null?"":value.trim().toUpperCase(Locale.ROOT);
            if(candidateMapped.equals(ids.getOrDefault(key,key)))return true;
        }
        return false;
    }
    private void completeGeneratedStoryTaskLinks(ArrayNode stories,ArrayNode tasks,ArrayNode members,
                                                 ArrayNode sprints,ArrayNode warnings,ArrayNode autoCompleted){
        Set<String> covered=new HashSet<>();
        for(JsonNode task:tasks)for(String ref:task.path("story_ref").asText("").split(","))if(!ref.isBlank())covered.add(ref.trim());
        for(JsonNode story:stories){
            String storyId=story.path("id").asText();
            if(covered.contains(storyId))continue;
            int sprint=story.path("sprint").asInt(1);
            ObjectNode best=null;int bestScore=0;
            for(JsonNode node:tasks){
                ObjectNode candidate=(ObjectNode)node;
                int taskSprint=linkedStorySprint(candidate,stories,sprints);
                if(taskSprint!=sprint) continue;
                int score=semanticLinkScore(story,candidate);
                if(score>bestScore){best=candidate;bestScore=score;}
            }
            String message;
            if(best != null && bestScore >= 2){
                LinkedHashSet<String> refs=new LinkedHashSet<>();
                for(String ref:best.path("story_ref").asText("").split(","))if(!ref.isBlank())refs.add(ref.trim());
                refs.add(storyId);best.put("story_ref",String.join(",",refs));
                message="已按语义匹配将 "+storyId+" 关联到 "+best.path("id").asText();
            } else {
                int next=1; for(JsonNode task:tasks){Matcher m=T.matcher(task.path("id").asText());if(m.matches())next=Math.max(next,Integer.parseInt(m.group(1))+1);}
                int[] range=sprintRange(sprints,sprint); if(range==null) range=new int[]{1,1};
                ObjectNode created=mapper.createObjectNode().put("id",String.format("T%02d",next))
                        .put("name","补充："+story.path("title").asText("业务能力实现"))
                        .put("description","补充实现"+story.path("title").asText("该用户故事")+"并完成正常与异常流程")
                        .put("owner_id",suggestGeneratedOwner(members,tasks,range[0])).put("priority",story.path("priority").asText("Should"))
                        .put("hours",8).put("week_start",range[0]).put("week_end",range[0])
                        .put("story_ref",storyId).put("kanban_card_id",storyId).put("estimated_hours",8)
                        .put("task_type","feature").put("depends_on","").put("status",0).put("progress",0).put("blocked",false);
                tasks.add(created); covered.add(storyId);
                message="已为 "+storyId+" 新增语义补全 Task "+created.path("id").asText();
                autoCompleted.add(message); warnings.add(message); continue;
            }
            warnings.add(message);autoCompleted.add(message);covered.add(storyId);
        }
        ensureGeneratedTaskStoryReferences(stories, tasks, warnings, autoCompleted);
    }

    private int ensureGeneratedTaskStoryReferences(ArrayNode stories, ArrayNode tasks,
                                                    ArrayNode warnings, ArrayNode autoCompleted) {
        int tieBreakResolved = 0;
        for (JsonNode node : tasks) {
            ObjectNode task = (ObjectNode) node;
            LinkedHashSet<String> refs = new LinkedHashSet<>();
            for (String ref : task.path("story_ref").asText("").split(",")) {
                JsonNode story = find(stories, ref.trim());
                if (story != null) refs.add(story.path("id").asText());
            }
            if (!refs.isEmpty()) {
                task.put("story_ref", String.join(",", refs));
                task.put("storyCode", String.join(",", refs));
                task.put("kanban_card_id", refs.iterator().next());
                continue;
            }
            if (isProjectLevelTask(taskSemanticText(task))) {
                task.set("relation_tie_break", uniqueHighConfidenceBusinessStory(stories, task).audit());
                String message = "待补充：" + task.path("id").asText() + " 属于项目级 Task，不强绑业务 Story";
                warnings.add(message); autoCompleted.add(message);
                continue;
            }
            BusinessStoryDecision businessDecision = uniqueHighConfidenceBusinessStory(stories, task);
            task.set("relation_tie_break", businessDecision.audit());
            ObjectNode business = businessDecision.winner();
            if (business != null) {
                task.put("story_ref", business.path("id").asText()).put("storyCode", business.path("id").asText())
                        .put("kanban_card_id", business.path("id").asText());
                tieBreakResolved++;
                String message = "已按高置信业务对象与操作将 " + task.path("id").asText()
                        + " 关联到 " + business.path("id").asText();
                warnings.add(message); autoCompleted.add(message);
                continue;
            }
            // A known business object with ambiguous actions must not be rebound by generic tokens.
            if (!businessObject(taskSemanticText(task)).isBlank()) continue;
            ObjectNode best = null;
            int bestScore = 0, secondScore = 0;
            List<JsonNode> orderedStories = storyList(stories);
            orderedStories.sort(Comparator.comparing(story -> story.path("id").asText(""), String.CASE_INSENSITIVE_ORDER));
            for (JsonNode story : orderedStories) {
                int score = semanticLinkScore(story, task);
                if (story.path("activity").asText("").equals(task.path("activity").asText(""))) score++;
                if (score > bestScore) {
                    secondScore = bestScore;
                    bestScore = score;
                    best = (ObjectNode) story;
                } else if (score > secondScore) secondScore = score;
            }
            if (best != null && bestScore >= 2 && bestScore >= secondScore + 2) {
                String storyId = best.path("id").asText();
                task.put("story_ref", storyId).put("storyCode", storyId).put("kanban_card_id", storyId);
                String message = "已按语义匹配将 " + task.path("id").asText() + " 关联到 " + storyId;
                warnings.add(message); autoCompleted.add(message);
            } else {
                ObjectNode technical = technicalStoryForTask(storyList(stories), task);
                if (technical != null) {
                    task.put("story_ref", technical.path("id").asText()).put("storyCode", technical.path("id").asText())
                            .put("kanban_card_id", technical.path("id").asText());
                    String message = "已按项目级语义将 " + task.path("id").asText() + " 关联到 " + technical.path("id").asText();
                    warnings.add(message); autoCompleted.add(message);
                } else {
                    String message = "待补充：" + task.path("id").asText() + " 尚未找到高置信 Story 关联";
                    warnings.add(message); autoCompleted.add(message);
                }
            }
        }
        return tieBreakResolved;
    }

    private BusinessStoryDecision uniqueHighConfidenceBusinessStory(ArrayNode stories, JsonNode task) {
        String taskText = taskSemanticText(task);
        ObjectNode audit = mapper.createObjectNode();
        ArrayNode candidateScores = audit.putArray("candidateScores");
        audit.put("selectedStory", "").put("runnerUpScore", 0).put("unresolvedReason", "")
                .put("resolutionMethod", "high_confidence_only");
        if (isProjectLevelTask(taskText))
            return new BusinessStoryDecision(null, audit.put("status", "PROJECT_LEVEL")
                    .put("winningReason", "项目级任务不绑定业务 Story").put("unresolvedReason", "项目级任务"));
        String object = namedBusinessObject(task);
        String operation = businessOperation(businessFeatureText(task));
        String role = businessRole(businessFeatureText(task));
        if (role.isBlank()) role = businessRole(taskText);
        Set<String> taskSignals = businessSignals(businessFeatureText(task));
        String surface = implementationSurface(taskText);
        String direction = businessDirection(taskText);
        Set<String> stateEvidence = new TreeSet<>(taskSignals);
        stateEvidence.retainAll(Set.of("状态", "结果", "异常", "累计", "参与人员"));
        audit.put("businessObject", object).put("operation", operation).put("role", role)
                .put("actionEvidence", operation).put("roleEvidence", role)
                .put("stateEvidence", String.join(",", stateEvidence)).put("directionEvidence", direction);
        if (object.isBlank()) return new BusinessStoryDecision(null,
                audit.put("status", "NO_BUSINESS_OBJECT").put("winningReason", "未识别业务对象")
                        .put("unresolvedReason", "未识别业务对象"));
        if (isCoarseCompositeTask(taskText)) return new BusinessStoryDecision(null,
                audit.put("status", "COARSE_COMPOSITE_TASK")
                        .put("winningReason", "跨实现阶段综合 Task 交由 stage coverage 处理")
                        .put("unresolvedReason", "跨接口/页面/校验的综合 Task 不强制绑定"));
        ObjectNode match = null; int best = Integer.MIN_VALUE, second = Integer.MIN_VALUE;
        ObjectNode evidenceWinner = null;
        int bestEvidence = Integer.MIN_VALUE, secondEvidence = Integer.MIN_VALUE;
        boolean winnerSupported = false;
        List<JsonNode> orderedStories = storyList(stories);
        orderedStories.sort(Comparator.comparing(story -> story.path("id").asText(""), String.CASE_INSENSITIVE_ORDER));
        for (JsonNode storyNode : orderedStories) {
            String storyText = taskSemanticText(storyNode);
            String storyObject = namedBusinessObject(storyNode);
            if (!object.equals(storyObject)) continue;
            int score = 100;
            String storyOperation = businessOperation(businessFeatureText(storyNode));
            String storyRole = businessRole(businessFeatureText(storyNode));
            if (storyRole.isBlank()) storyRole = businessRole(storyText);
            Set<String> storySignals = businessSignals(businessFeatureText(storyNode));
            String storySurface = implementationSurface(storyText);
            boolean operationMatch = !operation.isBlank()
                    && businessOperations(businessFeatureText(storyNode)).contains(operation);
            boolean roleMatch = !role.isBlank() && role.equals(storyRole);
            Set<String> sharedSignals = new java.util.TreeSet<>(taskSignals);
            sharedSignals.retainAll(storySignals);
            if (operationMatch) score += 55;
            else if ("校验".equals(operation) && Set.of("创建", "提交", "登录").contains(storyOperation)) score += 30;
            else if (!operation.isBlank() && !storyOperation.isBlank()) score -= 20;
            if (roleMatch) score += 35;
            else if (!role.isBlank() && !storyRole.isBlank()) score -= 30;
            score += sharedSignals.size() * 30;
            if (!taskSignals.isEmpty() && sharedSignals.isEmpty()) score -= 10;
            if (!surface.isBlank() && surface.equals(storySurface)) score += 5;
            score += Math.min(15, semanticLinkScore(storyNode, task));
            // Only object-local business evidence can decide this ranking. Surface and generic
            // token overlap are deliberately excluded, so two equivalent Stories remain tied.
            boolean roleConflict = !role.isBlank() && !storyRole.isBlank() && !role.equals(storyRole);
            int evidence = (operationMatch ? 60 : (!operation.isBlank() && !storyOperation.isBlank() ? -60 : 0))
                    + (roleMatch ? 50 : roleConflict ? -100 : 0) + sharedSignals.size() * 25;
            boolean supported = !roleConflict && (operationMatch || (!taskSignals.isEmpty() && !sharedSignals.isEmpty()));
            if (evidence > bestEvidence) {
                secondEvidence = bestEvidence; bestEvidence = evidence;
                evidenceWinner = (ObjectNode) storyNode; winnerSupported = supported;
            } else if (evidence > secondEvidence) secondEvidence = evidence;
            candidateScores.add(mapper.createObjectNode().put("storyId", storyNode.path("id").asText())
                    .put("score", score).put("businessEvidenceScore", evidence).put("operation", storyOperation).put("role", storyRole)
                    .put("operationMatched", operationMatch).put("roleMatched", roleMatch)
                    .put("matchedSignals", String.join(",", sharedSignals)));
            if (score > best) { second = best; best = score; match = (ObjectNode) storyNode; }
            else if (score > second) second = score;
        }
        if (evidenceWinner != null && winnerSupported && bestEvidence >= 50
                && (secondEvidence == Integer.MIN_VALUE || bestEvidence >= secondEvidence + 25)) {
            return new BusinessStoryDecision(evidenceWinner, audit.put("status", "RESOLVED")
                    .put("selectedStory", evidenceWinner.path("id").asText())
                    .put("winningReason", "同业务对象内动作、角色、状态/方向证据唯一支持该 Story；页面/API仅辅助")
                    .put("resolutionMethod", "object_action_role_surface_state_tiebreak")
                    .put("evidenceMethod", "deterministic_object_local_evidence")
                    .put("winningScore", bestEvidence)
                    .put("runnerUpScore", secondEvidence == Integer.MIN_VALUE ? 0 : secondEvidence));
        }
        boolean decisive = match != null && ((!operation.isBlank() && operation.equals(businessOperation(businessFeatureText(match))))
                || (!role.isBlank() && role.equals(businessRole(taskSemanticText(match))))
                || !java.util.Collections.disjoint(taskSignals, businessSignals(taskSemanticText(match))));
        if (best >= 125 && best > second && decisive && bestEvidence > secondEvidence && winnerSupported) {
            return new BusinessStoryDecision(match, audit.put("status", "RESOLVED")
                    .put("selectedStory", match.path("id").asText())
                    .put("winningReason", "业务对象一致，并由动作/角色/状态语义形成唯一最高分")
                    .put("resolutionMethod", "object_action_role_surface_state_tiebreak")
                    .put("winningScore", best).put("runnerUpScore", second == Integer.MIN_VALUE ? 0 : second));
        }
        return new BusinessStoryDecision(null, audit.put("status", "UNRESOLVED_TIE_OR_LOW_CONFIDENCE")
                .put("winningReason", "同业务对象候选仍平票或缺少动作、角色、状态语义证据")
                .put("resolutionMethod", "high_confidence_only")
                .put("unresolvedReason", best == Integer.MIN_VALUE ? "未找到同业务对象候选" :
                        (best == second ? "候选分数平票" : "动作/角色/状态证据不足"))
                .put("winningScore", best == Integer.MIN_VALUE ? 0 : best)
                .put("runnerUpScore", second == Integer.MIN_VALUE ? 0 : second));
    }

    private void copyGeneratedTaskProvenance(ObjectNode target, JsonNode source) {
        for (String field : List.of("model_task_code", "generation_stage", "generation_continuation_round",
                "generation_identity", "model_story_refs", "invalid_story_refs", "unresolved_cross_stage_references",
                "generation_dependency_refs", "generation_weak_dependency_refs", "generation_related_task_refs",
                "unresolved_weak_dependencies_references", "unresolved_related_task_ids_references", "unique_bare_task_references",
                TaskStoryReferences.AUDIT)) {
            JsonNode value = source.get(field);
            if (value != null && !value.isNull()) target.set(field, value.deepCopy());
        }
        JsonNode sources = source.get("generation_sources");
        if (sources != null && !sources.isNull()) target.set("generation_sources", sources.deepCopy());
    }

    private record BusinessStoryDecision(ObjectNode winner, ObjectNode audit) {}

    private String businessFeatureText(JsonNode node) {
        String title = firstText(node, "name", "title").toLowerCase(Locale.ROOT);
        // Descriptions often enumerate adjacent capabilities. Prefer the named capability.
        return title.isBlank() ? taskSemanticText(node) : title;
    }

    private String namedBusinessObject(JsonNode node) {
        String object = businessObject(businessFeatureText(node));
        return object.isBlank() ? businessObject(taskSemanticText(node)) : object;
    }

    private Set<String> businessOperations(String text) {
        Set<String> result = new HashSet<>();
        result.add(businessOperation(text));
        // Explicitly combined capabilities can support either named action, but not an
        // unrelated action merely mentioned in acceptance criteria or implementation notes.
        for (String part : text.split("[与和及、/]+")) result.add(businessOperation(part));
        result.remove("");
        return result;
    }

    private String businessRole(String text) {
        if (containsAny(text, "管理员", "后台管理", "管理后台")) return "管理员";
        if (containsAny(text, "负责人", "组织者", "活动发起人")) return "负责人";
        if (containsAny(text, "学生", "志愿者")) return "学生";
        return "";
    }

    private Set<String> businessSignals(String text) {
        Set<String> signals = new java.util.TreeSet<>();
        if (containsAny(text, "创建", "新增", "发布")) signals.add("创建");
        if (containsAny(text, "修改", "编辑", "更新")) signals.add("修改");
        if (containsAny(text, "取消", "删除", "撤销")) signals.add("取消");
        if (containsAny(text, "状态", "流转")) signals.add("状态");
        if (containsAny(text, "列表", "分页")) signals.add("列表");
        if (containsAny(text, "详情", "明细")) signals.add("详情");
        if (containsAny(text, "搜索", "关键字")) signals.add("搜索");
        if (containsAny(text, "筛选", "过滤")) signals.add("筛选");
        if (containsAny(text, "审核", "审批")) signals.add("审核");
        if (containsAny(text, "结果", "回显")) signals.add("结果");
        if (containsAny(text, "通知", "提醒", "消息")) signals.add("通知");
        if (containsAny(text, "异常", "容错")) signals.add("异常");
        if (containsAny(text, "生成", "计算")) signals.add("生成");
        if (containsAny(text, "累计", "个人时长")) signals.add("累计");
        if (containsAny(text, "时间", "开始时间", "结束时间", "截止时间")) signals.add("时间");
        if (containsAny(text, "人数", "名额", "容量")) signals.add("人数");
        if (containsAny(text, "截止", "过期")) signals.add("截止");
        if (containsAny(text, "权限", "越权", "访问控制")) signals.add("权限");
        if (containsAny(text, "参与人员", "人员时长")) signals.add("参与人员");
        if (containsAny(text, "评价", "评分", "反馈")) signals.add("评价");
        if (containsAny(text, "统计", "报表", "图表", "分析")) signals.add("统计");
        return signals;
    }

    private String implementationSurface(String text) {
        if (containsAny(text, "表结构", "数据表", "schema")) return "表结构";
        if (containsAny(text, "接口", "api", "后端")) return "接口";
        if (containsAny(text, "页面", "前端", "界面")) return "页面";
        return "";
    }

    private String businessDirection(String text) {
        if (containsAny(text, "提交", "创建", "发布", "写入", "录入")) return "write";
        if (containsAny(text, "查看", "查询", "列表", "详情", "展示", "统计")) return "read";
        if (containsAny(text, "审核", "审批", "确认")) return "review";
        if (containsAny(text, "取消", "撤销", "删除")) return "cancel";
        return "";
    }

    private String businessObject(String text) {
        if (containsAny(text, "个人中心", "我的主页")) return "个人中心";
        boolean admin = containsAny(text, "管理员", "后台管理", "管理后台");
        if (!admin && containsAny(text, "个人信息", "用户信息", "用户资料", "个人资料")) return "个人信息";
        if (containsAny(text, "角色权限", "权限管理", "角色管理", "越权", "访问控制")) return "角色权限";
        if (admin && containsAny(text, "用户", "账号", "学生")) return "后台用户";
        if (admin && containsAny(text, "报名", "申请", "审核")) return "后台报名";
        if (admin && text.contains("活动")) return "后台活动";
        if (admin) return "管理员后台";
        if (containsAny(text, "服务时长", "志愿时长", "时长记录", "累计时长", "个人时长")) return "服务时长";
        if (containsAny(text, "活动报名", "报名申请")) return "报名";
        if (containsAny(text, "参与人员名单", "参与名单", "报名名单")) return "报名";
        if (text.contains("注册")) return "注册";
        if (text.contains("登录")) return "登录";
        if (text.contains("签退")) return "签退";
        if (text.contains("签到")) return "签到";
        if (containsAny(text, "报名", "申请记录")) return "报名";
        if (containsAny(text, "评价", "评分", "反馈")) return "评价";
        if (containsAny(text, "消息", "通知", "提醒")) return "消息";
        if (text.contains("活动")) return "活动";
        if (containsAny(text, "筛选组件", "组合条件", "搜索组件")) return "活动";
        if (containsAny(text, "平台统计", "数据统计")) return "统计";
        return "";
    }

    private String businessOperation(String text) {
        if (containsAny(text, "报名申请查询", "查询报名申请", "查看报名申请", "查看申请", "查询申请", "报名查询")) return "查看申请";
        if (containsAny(text, "参与名单", "参与人员名单", "报名名单")) return "查看参与名单";
        if (containsAny(text, "取消报名", "撤销报名")) return "取消报名";
        if (containsAny(text, "报名状态", "查看报名状态", "查询报名状态")) return "报名状态";
        if (containsAny(text, "审核报名", "审批报名", "审核申请", "报名审核", "报名审批")) return "审核报名";
        if ((text.contains("报名") && containsAny(text, "结果通知", "结果消息", "消息通知", "审核结果"))
                || containsAny(text, "通知报名结果")) return "报名结果通知";
        if (containsAny(text, "报名提交", "提交报名", "活动报名提交", "提交活动报名", "报名申请", "申请报名")) return "提交报名";
        if (containsAny(text, "活动状态", "状态流转")) return "活动状态";
        if (containsAny(text, "查看详情", "详情")) return "查看详情";
        if (containsAny(text, "搜索", "筛选", "过滤")) return "搜索筛选";
        if (containsAny(text, "列表", "查询列表", "列表查询")) return "查询列表";
        if (containsAny(text, "签退", "结束签到")) return "签退";
        if (containsAny(text, "签到", "开始签到")) return "签到";
        if (containsAny(text, "时长记录", "记录服务时长", "服务记录")) return "记录时长";
        if (containsAny(text, "累计时长", "累计服务时长", "个人时长")) return "查看累计时长";
        if (containsAny(text, "负责人", "组织者") && containsAny(text, "查看", "查询") && text.contains("时长")) return "查看人员时长";
        if (containsAny(text, "参与人员服务时长", "人员服务时长", "负责人查看时长")) return "查看人员时长";
        if (containsAny(text, "生成时长", "计算时长", "生成服务时长", "服务时长生成", "计算服务时长")) return "生成时长";
        if (containsAny(text, "个人中心", "个人主页")) return "个人中心聚合";
        if (containsAny(text, "用户管理", "活动管理", "报名管理")) return "管理";
        if (containsAny(text, "权限校验", "越权", "访问控制", "权限拦截")) return "权限校验";
        if (containsAny(text, "校验", "验证", "唯一性")) return "校验";
        if (containsAny(text, "审核", "审批")) return "审核";
        if (containsAny(text, "取消", "删除", "撤销")) return "取消";
        if (containsAny(text, "修改", "编辑", "更新")) return "修改";
        if (containsAny(text, "统计", "报表", "分析")) return "统计";
        if (containsAny(text, "查看", "查询", "列表", "详情", "展示")) return "查看";
        if (containsAny(text, "登录", "认证")) return "登录";
        if (containsAny(text, "提交", "申请", "报名")) return "提交";
        if (containsAny(text, "创建", "新增", "注册", "发布")) return "创建";
        return "";
    }

    private boolean isProjectLevelTask(String text) {
        return containsAny(text, "项目初始化", "工程搭建", "环境搭建", "基础框架", "通用基础设施",
                "全局日志", "操作日志", "全局参数", "统一参数校验", "统一异常处理", "统一响应", "限流", "部署流水线");
    }

    private ObjectNode finalRelationClosure(ArrayNode stories, ArrayNode tasks, ArrayNode warnings, ArrayNode autoCompleted) {
        int semanticLinks = 0, technicalLinks = 0, unresolved = 0;
        int highConfidenceTieBreakResolved = ensureGeneratedTaskStoryReferences(stories, tasks, warnings, autoCompleted);
        Set<String> covered = new HashSet<>();
        for (JsonNode task : tasks) for (String ref : task.path("story_ref").asText("").split(","))
            if (find(stories, ref.trim()) != null) covered.add(ref.trim());
        for (JsonNode story : stories) {
            String id = story.path("id").asText();
            if (covered.contains(id)) continue;
            ObjectNode best = null; int score = 0;
            for (JsonNode task : tasks) {
                if (isProjectLevelTask(taskSemanticText(task))) continue;
                // Business tasks were already resolved by the object/action/role closure above.
                // Do not broaden a high-confidence winner merely to cover another same-object Story.
                if (!businessObject(taskSemanticText(task)).isBlank()) continue;
                int candidate = semanticLinkScore(story, task);
                if (candidate > score) { score = candidate; best = (ObjectNode) task; }
            }
            JsonNode relatedTask = best;
            if (best != null && score >= 2) {
                LinkedHashSet<String> refs = new LinkedHashSet<>();
                for (String ref : best.path("story_ref").asText("").split(",")) if (!ref.isBlank()) refs.add(ref.trim());
                refs.add(id); best.put("story_ref", String.join(",", refs));
                String message = "已按最终语义收口将 " + id + " 关联到 " + best.path("id").asText();
                warnings.add(message); autoCompleted.add(message);
                semanticLinks++;
            } else {
                ObjectNode technicalStory = null;
                for (JsonNode candidateTask : tasks) {
                    if (candidateTask.path("story_ref").asText("").isBlank()
                            && !isProjectLevelTask(taskSemanticText(candidateTask))
                            && businessObject(taskSemanticText(candidateTask)).isBlank()) {
                        technicalStory = technicalStoryForTask(storyList(stories), candidateTask);
                        if (technicalStory != null) { relatedTask = candidateTask; break; }
                    }
                }
                if (technicalStory != null) {
                    LinkedHashSet<String> refs = new LinkedHashSet<>();
                    for (String ref : relatedTask.path("story_ref").asText("").split(",")) if (!ref.isBlank()) refs.add(ref.trim());
                    refs.add(technicalStory.path("id").asText());
                    ((ObjectNode) relatedTask).put("story_ref", String.join(",", refs));
                    ((ObjectNode) relatedTask).put("kanban_card_id", refs.iterator().next());
                    technicalLinks++;
                    autoCompleted.add("项目级 Task " + relatedTask.path("id").asText() + " 受控关联到 " + technicalStory.path("id").asText());
                }
            }
        }
        for (JsonNode task : tasks) {
            boolean linked = false;
            for (String ref : task.path("story_ref").asText("").split(",")) if (find(stories, ref.trim()) != null) { linked = true; break; }
            if (!linked) unresolved++;
        }
        ObjectNode stats = mapper.createObjectNode().put("semanticLinks", semanticLinks)
                .put("technicalLinks", technicalLinks).put("unresolvedTasks", unresolved)
                .put("highConfidenceTieBreakResolvedCount", highConfidenceTieBreakResolved)
                .put("unresolvedBusinessTaskCount", countUnresolvedBusinessTasks(stories, tasks));
        ObjectNode reasons = stats.putObject("unresolvedReasonCounts");
        int resolvedTotal = 0;
        for (JsonNode task : tasks) {
            JsonNode audit = task.path("relation_tie_break");
            if ("RESOLVED".equals(audit.path("status").asText())) resolvedTotal++;
            if (task.path("story_ref").asText("").isBlank()) {
                String reason = audit.path("unresolvedReason").asText("未提供有效关系证据");
                reasons.put(reason, reasons.path(reason).asInt() + 1);
            }
        }
        stats.put("highConfidenceTieBreakResolvedCount", resolvedTotal);
        stats.set("details", mapper.createArrayNode());
        return stats;
    }

    private int countUnresolvedBusinessTasks(ArrayNode stories, ArrayNode tasks) {
        Set<String> storyIds = new HashSet<>();
        stories.forEach(story -> storyIds.add(story.path("id").asText().toUpperCase(Locale.ROOT)));
        int count = 0;
        for (JsonNode task : tasks)
            if (!hasLegalStoryReference(task, storyIds) && !businessObject(taskSemanticText(task)).isBlank()) count++;
        return count;
    }

    private List<JsonNode> storyList(ArrayNode stories) {
        List<JsonNode> result = new ArrayList<>();
        stories.forEach(result::add);
        return result;
    }

    private ObjectNode technicalStoryForTask(List<JsonNode> stories, JsonNode task) {
        String text = (task.path("name").asText("") + " " + task.path("description").asText("")).toLowerCase(Locale.ROOT);
        String category = technicalCategory(text);
        if (category.isBlank()) return null;
        ObjectNode best = null; int score = 0;
        for (JsonNode node : stories) {
            String storyText = (node.path("title").asText("") + " " + node.path("description").asText("") + " " + node.path("acceptance").asText("")).toLowerCase(Locale.ROOT);
            int candidate = categoryScore(category, storyText);
            if (candidate > score) { score = candidate; best = (ObjectNode) node; }
        }
        return score >= 2 ? best : null;
    }

    private String technicalCategory(String text) {
        if (technicalContains(text, "部署", "上线", "发布", "验收", "交付", "演示", "准备")) return "release";
        if (technicalContains(text, "统计", "报表", "分析", "指标", "看板", "导出")) return "analytics";
        if (technicalContains(text, "异常", "容错", "边界", "冲突", "超时", "失败")) return "anomaly";
        if (technicalContains(text, "测试", "缺陷", "修复", "回归", "质量", "异常")) return "test";
        if (technicalContains(text, "数据库", "数据表", "基础框架", "项目初始化", "公共接口", "基础设施", "初始化", "骨架", "目录结构", "版本管理", "实体关系", "核心实体")) return "foundation";
        if (technicalContains(text, "联调", "接口对接", "集成")) return "integration";
        if (technicalContains(text, "状态模型", "领域模型", "业务规则", "规则模型", "数据模型")) return "foundation";
        if (technicalContains(text, "消息", "通知", "提醒")) return "integration";
        if (technicalContains(text, "评价", "评分", "反馈", "评论")) return "analytics";
        return "";
    }

    private int categoryScore(String category, String storyText) {
        return switch (category) {
            case "foundation" -> (technicalContains(storyText, "基础", "框架", "初始化", "数据库", "数据", "模型", "状态", "规则", "活动", "系统", "目录", "版本", "实体", "关系", "用户", "角色") ? 2 : 0);
            case "test" -> (technicalContains(storyText, "测试", "异常", "缺陷", "质量", "校验", "验证") ? 2 : 0);
            case "release" -> (technicalContains(storyText, "发布", "上线", "验收", "交付", "部署", "系统") ? 2 : 0);
            case "integration" -> (technicalContains(storyText, "联调", "接口", "集成", "协同", "消息", "通知", "提醒") ? 2 : 0);
            case "analytics" -> (technicalContains(storyText, "统计", "报表", "分析", "指标", "看板", "导出", "评价", "评分", "反馈") ? 2 : 0);
            case "anomaly" -> (technicalContains(storyText, "异常", "容错", "边界", "冲突", "超时", "失败", "故障") ? 2 : 0);
            default -> 0;
        };
    }

    private boolean technicalContains(String text, String... terms) {
        for (String term : terms) if (text.contains(term)) return true;
        return false;
    }

    private ObjectNode finalRelationCheck(ArrayNode stories, ArrayNode tasks) {
        Set<String> storyIds = new HashSet<>(); stories.forEach(s -> storyIds.add(s.path("id").asText()));
        Set<String> covered = new HashSet<>();
        int orphan = 0, invalid = 0;
        for (JsonNode task : tasks) for (String ref : task.path("story_ref").asText("").split(","))
            if (!ref.isBlank()) { if (storyIds.contains(ref.trim())) covered.add(ref.trim()); else invalid++; }
        for (JsonNode task : tasks) {
            boolean valid = false;
            for (String ref : task.path("story_ref").asText("").split(",")) if (storyIds.contains(ref.trim())) valid = true;
            if (!valid) orphan++;
        }
        int uncovered = 0;
        for (JsonNode story : stories) if (!covered.contains(story.path("id").asText())) uncovered++;
        return mapper.createObjectNode().put("orphanTaskCount", orphan).put("uncoveredStoryCount", uncovered)
                .put("invalidReferenceCount", invalid).put("relationFrozen", true);
    }

    private void markUnresolvedStoryRelations(ArrayNode stories, ArrayNode tasks, ObjectNode relationCheck) {
        if (relationCheck.path("orphanTaskCount").asInt() == 0) return;
        Set<String> storyIds = new HashSet<>();
        stories.forEach(story -> storyIds.add(story.path("id").asText().toUpperCase(Locale.ROOT)));
        relationCheck.put("unresolvedStoryRelationCount", relationCheck.path("orphanTaskCount").asInt());
        for (JsonNode taskNode : tasks) {
            ObjectNode task = (ObjectNode) taskNode;
            if (hasLegalStoryReference(task, storyIds)) continue;
            task.put("_unresolved_story_relation", true);
            task.set("_schedule_conflict", mapper.createObjectNode()
                    .put("code", "UNRESOLVED_STORY_RELATION")
                    .put("message", "Task 未能高置信关联 Story，不参与依赖推断和最终排期"));
        }
    }

    private boolean hasLegalStoryReference(JsonNode task, Set<String> storyIds) {
        for (String ref : task.path("story_ref").asText("").split(",")) {
            String id = ref.trim();
            if (!id.isBlank() && findCurrentStoryId(id, storyIds)) return true;
        }
        return false;
    }

    private boolean findCurrentStoryId(String id, Set<String> storyIds) {
        return storyIds.contains(id.trim().toUpperCase(Locale.ROOT));
    }

    private void excludeUnresolvedTasksFromDependencies(ArrayNode tasks, ArrayNode details) {
        Set<String> unresolved = new HashSet<>();
        for (JsonNode node : tasks) if (node.path("_unresolved_story_relation").asBoolean())
            unresolved.add(node.path("id").asText().toUpperCase(Locale.ROOT));
        if (unresolved.isEmpty()) return;
        for (JsonNode node : tasks) {
            ObjectNode task = (ObjectNode) node;
            String id = task.path("id").asText();
            if (unresolved.contains(id.toUpperCase(Locale.ROOT))) {
                if (!task.path("depends_on").asText("").isBlank())
                    details.add(id + " 因缺少合法 Story 关系而从依赖图排除");
                task.put("depends_on", "");
                continue;
            }
            List<String> retained = new ArrayList<>();
            for (String dependency : task.path("depends_on").asText("").split(",")) {
                String ref = dependency.trim();
                if (ref.isBlank()) continue;
                if (unresolved.contains(ref.toUpperCase(Locale.ROOT))) {
                    details.add(id + " -> " + ref + " 因前置 Task 关系未解析而从依赖图排除");
                } else retained.add(ref);
            }
            task.put("depends_on", String.join(",", new LinkedHashSet<>(retained)));
        }
    }

    private Map<String, Integer> storySprintSnapshot(ArrayNode stories) {
        Map<String, Integer> snapshot = new java.util.TreeMap<>(String.CASE_INSENSITIVE_ORDER);
        for (JsonNode story : stories) snapshot.put(story.path("id").asText(), story.path("sprint").asInt());
        return snapshot;
    }

    private ArrayNode storySprintMoves(Map<String, Integer> before, ArrayNode stories) {
        ArrayNode moves = mapper.createArrayNode();
        for (JsonNode story : stories) {
            String id = story.path("id").asText();
            int from = before.getOrDefault(id, story.path("sprint").asInt());
            int to = story.path("sprint").asInt();
            if (from != to) moves.add(mapper.createObjectNode().put("storyId", id).put("from", from).put("to", to));
        }
        return moves;
    }

    private Map<String, String> dependencySnapshot(ArrayNode tasks) {
        Map<String, String> snapshot = new java.util.TreeMap<>(String.CASE_INSENSITIVE_ORDER);
        for (JsonNode task : tasks) snapshot.put(task.path("id").asText(), task.path("depends_on").asText(""));
        return snapshot;
    }

    private Map<String, String> relationSnapshot(ArrayNode tasks) {
        Map<String, String> snapshot = new java.util.TreeMap<>(String.CASE_INSENSITIVE_ORDER);
        for (JsonNode task : tasks) snapshot.put(task.path("id").asText(), task.path("story_ref").asText(""));
        return snapshot;
    }

    private String activitySnapshot(ObjectNode draft, ArrayNode stories) {
        StringBuilder value = new StringBuilder();
        for (JsonNode activity : draft.path("activities"))
            value.append(activity.path("code").asText("")).append('=').append(activity.path("name").asText("")).append(';');
        value.append('|');
        for (JsonNode story : stories)
            value.append(story.path("id").asText("")).append('=').append(story.path("activity").asText("")).append(';');
        value.append('|');
        for (JsonNode epic : draft.path("epics"))
            value.append(epic.path("id").asText("")).append('=').append(epic.path("activity").asText("")).append(';');
        return value.toString();
    }

    private int primaryStoryIdSprint(JsonNode task, ArrayNode stories) {
        return stablePrimaryStorySprint(task, stories);
    }

    private ObjectNode primaryStoryForTask(JsonNode task, ArrayNode stories, int sprint) {
        List<ObjectNode> candidates = new ArrayList<>();
        for (String ref : task.path("story_ref").asText("").split(",")) {
            JsonNode story = find(stories, ref.trim());
            if (story instanceof ObjectNode object && story.path("sprint").asInt() == sprint)
                candidates.add(object);
        }
        return candidates.stream().sorted(Comparator
                .comparingInt((ObjectNode story) -> priorityRank(story.path("priority").asText("")))
                .reversed().thenComparing(story -> story.path("id").asText(), String.CASE_INSENSITIVE_ORDER))
                .findFirst().orElse(null);
    }

    private ArrayNode sprintOrderViolations(ArrayNode stories, ArrayNode tasks) {
        Map<String, JsonNode> byId = new java.util.TreeMap<>(String.CASE_INSENSITIVE_ORDER);
        for (JsonNode task : tasks) byId.put(task.path("id").asText(), task);
        ArrayNode violations = mapper.createArrayNode();
        List<JsonNode> ordered = new ArrayList<>();
        tasks.forEach(ordered::add);
        ordered.sort(Comparator.comparing(task -> task.path("id").asText(), String.CASE_INSENSITIVE_ORDER));
        for (JsonNode task : ordered) {
            if (task.path("_unresolved_story_relation").asBoolean()) continue;
            int currentSprint = primaryStoryIdSprint(task, stories);
            for (String dependency : task.path("depends_on").asText("").split(",")) {
                String predecessorId = dependency.trim();
                JsonNode predecessor = byId.get(predecessorId);
                if (predecessor == null || predecessor.path("_unresolved_story_relation").asBoolean()) continue;
                int predecessorSprint = primaryStoryIdSprint(predecessor, stories);
                if (predecessorSprint > 0 && currentSprint > 0 && predecessorSprint > currentSprint) {
                    violations.add(mapper.createObjectNode()
                            .put("predecessorTaskId", predecessorId)
                            .put("currentTaskId", task.path("id").asText())
                            .put("predecessorSprint", predecessorSprint)
                            .put("currentSprint", currentSprint));
                }
            }
        }
        return violations;
    }

    private ObjectNode dependencyAwareStorySprintReconciliation(ArrayNode stories, ArrayNode tasks,
                                                                   ArrayNode sprints, ArrayNode initialViolations) {
        ObjectNode result = mapper.createObjectNode();
        ArrayNode moves = mapper.createArrayNode();
        int maxSprint = Math.max(1, sprints.size());
        int maxIterations = Math.max(1, maxSprint - 1);
        int iterations = 0;
        boolean changed = false;
        Map<String, JsonNode> byId = uniqueTaskMap(tasks, "dependency-aware Sprint reconciliation");
        List<ObjectNode> orderedTasks = new ArrayList<>();
        for (JsonNode task : tasks) if (!task.path("_unresolved_story_relation").asBoolean())
            orderedTasks.add((ObjectNode) task);
        orderedTasks.sort(Comparator.comparing(task -> task.path("id").asText(), String.CASE_INSENSITIVE_ORDER));

        int totalCrossStory = 0, coreDependencies = 0, weakDependencies = 0;
        for (ObjectNode task : orderedTasks) {
            for (String dependency : task.path("depends_on").asText("").split(",")) {
                JsonNode predecessor = byId.get(dependency.trim());
                if (predecessor == null || sameStoryRelation(task, predecessor)) continue;
                totalCrossStory++;
                if (isCoreStoryDependency(predecessor, task)) coreDependencies++; else weakDependencies++;
            }
        }
        for (int pass = 0; pass < maxIterations; pass++) {
            iterations = pass + 1;
            changed = false;
            Set<String> movedThisPass = new HashSet<>();
            for (ObjectNode task : orderedTasks) {
                int currentSprint = primaryStoryIdSprint(task, stories);
                if (currentSprint <= 0) continue;
                for (String dependency : task.path("depends_on").asText("").split(",")) {
                    JsonNode predecessor = byId.get(dependency.trim());
                    if (predecessor == null || predecessor.path("_unresolved_story_relation").asBoolean()) continue;
                    // Story movement is reserved for deterministic core business chains.
                    // Tests, reporting, notifications and other support dependencies stay
                    // Task-level scheduling constraints and never move an entire Story.
                    if (sameStoryRelation(task, predecessor) || !isCoreStoryDependency(predecessor, task)) continue;
                    int predecessorSprint = primaryStoryIdSprint(predecessor, stories);
                    if (predecessorSprint <= currentSprint) continue;
                    ObjectNode anchor = primaryStoryForTask(task, stories, currentSprint);
                    if (anchor == null || anchor.path("sprint_explicit").asBoolean()
                            || predecessorSprint > maxSprint || !movedThisPass.add(anchor.path("id").asText())) continue;
                    int from = anchor.path("sprint").asInt();
                    int to = Math.min(predecessorSprint, from + 1);
                    anchor.put("sprint", to).put("sprint_inferred", true);
                    moves.add(mapper.createObjectNode().put("storyId", anchor.path("id").asText())
                            .put("from", from).put("to", to)
                            .put("reason", "core_dependency_order").put("priority", anchor.path("priority").asText("")));
                    changed = true;
                    break;
                }
            }
            if (!changed) break;
        }
        ArrayNode remaining = coreSprintOrderViolations(stories, tasks);
        ArrayNode conflicts = mapper.createArrayNode();
        for (JsonNode violation : remaining) {
            ObjectNode conflict = violation.deepCopy();
            conflict.put("code", "STORY_SPRINT_DEPENDENCY_CONFLICT");
            conflicts.add(conflict);
        }
        result.set("moves", moves);
        result.set("conflicts", conflicts);
        LinkedHashSet<String> movedStories = new LinkedHashSet<>();
        moves.forEach(move -> movedStories.add(move.path("storyId").asText()));
        result.put("initialViolationCount", initialViolations.size());
        result.put("totalCrossStoryDependencies", totalCrossStory);
        result.put("coreDependencies", coreDependencies);
        result.put("weakDependencies", weakDependencies);
        result.put("storyMoveCount", moves.size());
        result.set("movedStories", mapper.valueToTree(movedStories));
        result.put("remainingCoreConflicts", remaining.size());
        result.put("remainingViolationCount", remaining.size());
        result.put("iterations", iterations);
        result.put("converged", remaining.isEmpty() || !changed);
        result.put("iterationLimit", maxIterations);
        return result;
    }

    private Map<String, JsonNode> uniqueTaskMap(ArrayNode tasks, String stage) {
        Map<String, JsonNode> byId = new java.util.TreeMap<>(String.CASE_INSENSITIVE_ORDER);
        for (JsonNode task : tasks) {
            String id = task.path("id").asText("").trim();
            if (id.isBlank() || byId.putIfAbsent(id, task) != null)
                throw ApiException.badRequest("DUPLICATE_TASK_CODE_CONFLICT: " + stage + " cannot uniquely resolve " + id);
        }
        return byId;
    }

    private Map<String, ObjectNode> uniqueObjectTaskMap(ArrayNode tasks, String stage) {
        Map<String, ObjectNode> result = new java.util.TreeMap<>(String.CASE_INSENSITIVE_ORDER);
        for (Map.Entry<String, JsonNode> entry : uniqueTaskMap(tasks, stage).entrySet())
            result.put(entry.getKey(), (ObjectNode) entry.getValue());
        return result;
    }

    private boolean sameStoryRelation(JsonNode left, JsonNode right) {
        Set<String> refs = new HashSet<>();
        for (String ref : left.path("story_ref").asText("").split(",")) if (!ref.isBlank()) refs.add(ref.trim().toUpperCase(Locale.ROOT));
        for (String ref : right.path("story_ref").asText("").split(","))
            if (refs.contains(ref.trim().toUpperCase(Locale.ROOT))) return true;
        return false;
    }

    private boolean isCoreStoryDependency(JsonNode predecessor, JsonNode successor) {
        return isCoreSchedulingDependency(predecessor, successor, false);
    }

    private String taskSemanticText(JsonNode task) {
        return (task.path("name").asText("") + " " + task.path("title").asText("") + " "
                + task.path("description").asText("") + " " + task.path("acceptance").asText(""))
                .toLowerCase(Locale.ROOT);
    }

    private boolean isWeakStoryDriver(String text) {
        return containsAny(text, "测试", "联调", "验收", "异常", "统计", "通知", "提醒", "日志", "导出",
                "个人中心", "文档", "环境", "基础框架", "通用", "公共", "基础设施", "容错", "重试",
                "聚合", "后台综合", "综合页面",
                "test", "integration", "acceptance", "report", "notification", "log", "export", "document");
    }

    private boolean sharesBusinessDomain(String left, String right) {
        for (String term : List.of("用户", "账号", "权限", "活动", "报名", "申请", "审核", "签到", "签退",
                "时长", "评价", "消息", "角色", "profile", "user", "event", "apply", "approval"))
            if (left.contains(term) && right.contains(term)) return true;
        return false;
    }

    /** Final detailed-only dependency pass. Only deterministic CORE edges stay in
     * depends_on; WEAK edges are retained for audit/risk display and never constrain scheduling. */
    private ObjectNode compactDetailedDependencies(ArrayNode tasks, ArrayNode warnings) {
        Map<String, ObjectNode> byId = new java.util.TreeMap<>(String.CASE_INSENSITIVE_ORDER);
        for (JsonNode node : tasks) {
            String id = node.path("id").asText("");
            if (byId.putIfAbsent(id, (ObjectNode) node) != null)
                throw ApiException.badRequest("DUPLICATE_TASK_CODE_CONFLICT: dependency compaction cannot uniquely resolve " + id);
        }
        int before = 0, after = 0, weakCount = 0, weakCrossStory = 0, weakBefore = 0, weakCrossStoryBefore = 0;
        int droppedLowEvidence = 0, droppedHub = 0, droppedChain = 0;
        int coreCount = 0, coreCrossStory = 0, sameStoryEdges = 0;
        int maxBefore = 0, maxAfter = 0;
        Map<String, Integer> beforeIn = new HashMap<>(), afterIn = new HashMap<>();
        // Count distinct cross-Story consumers before filtering, so a noisy hub cannot
        // evade the limit just because some of its candidates lack semantic evidence.
        Map<String, Set<String>> weakConsumerStories = new java.util.TreeMap<>(String.CASE_INSENSITIVE_ORDER);
        for (JsonNode task : tasks) for (String ref : dependencyRefs(task)) {
            ObjectNode predecessor = byId.get(ref.trim());
            if (predecessor == null || predecessor == task || sameStoryRelation(predecessor, task)
                    || isCoreSchedulingDependency(predecessor, task, false)) continue;
            Set<String> consumers = weakConsumerStories.computeIfAbsent(predecessor.path("id").asText(), k -> new HashSet<>());
            for (String story : task.path("story_ref").asText("").split(","))
                if (!story.isBlank()) consumers.add(story.trim().toUpperCase(Locale.ROOT));
        }
        for (JsonNode node : tasks) {
            ObjectNode task = (ObjectNode) node;
            Set<String> uniqueRefs = new TreeSet<>(String.CASE_INSENSITIVE_ORDER);
            dependencyRefs(task).forEach(ref -> uniqueRefs.add(ref.trim()));
            List<String> refs = new ArrayList<>(uniqueRefs);
            refs.sort(String.CASE_INSENSITIVE_ORDER);
            before += refs.size();
            LinkedHashSet<String> core = new LinkedHashSet<>(), weak = new LinkedHashSet<>();
            ArrayNode classes = mapper.createArrayNode();
            for (String ref : refs) {
                ObjectNode predecessor = byId.get(ref.trim());
                if (predecessor == null || predecessor == task || predecessor.path("id").asText().equalsIgnoreCase(task.path("id").asText())) continue;
                beforeIn.merge(task.path("id").asText(), 1, Integer::sum);
                boolean sharedStory = sameStoryRelation(task, predecessor);
                if (sharedStory) sameStoryEdges++;
                String predecessorId = predecessor.path("id").asText();
                boolean coreDependency = isCoreSchedulingDependency(predecessor, task, sharedStory);
                if (coreDependency) {
                    coreCount++; if (!sharedStory) coreCrossStory++; core.add(predecessorId);
                } else {
                    weakBefore++;
                    if (!sharedStory) weakCrossStoryBefore++;
                    if (!sharedStory && weakConsumerStories.getOrDefault(predecessorId, Set.of()).size() >= 5
                            && !isExplicitCommonWeakCapability(predecessor)) {
                        droppedHub++;
                        continue;
                    }
                    if (!hasStrongWeakEvidence(predecessor, task, sharedStory, tasks)) {
                        droppedLowEvidence++;
                        continue;
                    }
                    weakCount++; if (!sharedStory) weakCrossStory++; weak.add(predecessorId);
                }
                classes.add(mapper.createObjectNode().put("predecessor", predecessorId)
                        .put("class", coreDependency ? "CORE" : "WEAK"));
            }
            List<String> stableDependencies = new ArrayList<>(core);
            stableDependencies.sort(String.CASE_INSENSITIVE_ORDER);
            List<String> stableWeakDependencies = new ArrayList<>(weak);
            stableWeakDependencies.sort(String.CASE_INSENSITIVE_ORDER);
            task.put("depends_on", String.join(",", stableDependencies));
            task.put("weak_dependencies", String.join(",", stableWeakDependencies));
            task.set("dependency_classes", classes);
            after += stableDependencies.size();
            afterIn.put(task.path("id").asText(), stableDependencies.size());
        }
        // Only remove weak shortcuts. Each removal checks a surviving path in the
        // same shared Story; core edges and cross-Story paths are never compressed.
        for (ObjectNode task : byId.values()) {
            List<String> retained = new ArrayList<>();
            for (String ref : task.path("weak_dependencies").asText("").split(","))
                if (!ref.isBlank()) retained.add(ref);
            for (String ref : new ArrayList<>(retained)) {
                ObjectNode predecessor = byId.get(ref);
                if (hasSameStoryAlternateDependencyPath(predecessor, task, byId)) {
                    droppedChain++; weakCount--;
                    retained.remove(ref);
                    task.put("weak_dependencies", String.join(",", retained));
                }
            }
            task.put("weak_dependencies", String.join(",", retained));
        }
        weakCrossStory = 0;
        for (ObjectNode task : byId.values()) {
            ArrayNode classes = mapper.createArrayNode();
            for (String ref : task.path("depends_on").asText("").split(",")) if (!ref.isBlank())
                classes.add(mapper.createObjectNode().put("predecessor", ref).put("class", "CORE"));
            for (String ref : task.path("weak_dependencies").asText("").split(",")) if (!ref.isBlank()) {
                if (!sameStoryRelation(byId.get(ref), task)) weakCrossStory++;
                classes.add(mapper.createObjectNode().put("predecessor", ref).put("class", "WEAK"));
            }
            task.set("dependency_classes", classes);
        }
        for (int value : beforeIn.values()) maxBefore = Math.max(maxBefore, value);
        for (int value : afterIn.values()) maxAfter = Math.max(maxAfter, value);
        return mapper.createObjectNode().put("beforeEdgeCount", before).put("afterEdgeCount", after)
                .put("removedWeakCrossStoryCount", weakCrossStoryBefore).put("weakDependencyCount", weakCount)
                .put("weakBeforeCompaction", weakBefore).put("weakAfterCompaction", weakCount)
                .put("weakSameStoryCount", weakCount - weakCrossStory).put("weakCrossStoryCount", weakCrossStory)
                .put("weakDroppedByLowEvidence", droppedLowEvidence).put("weakDroppedByHubLimit", droppedHub)
                .put("weakDroppedByRedundantChain", droppedChain)
                .put("keptCoreCrossStoryCount", coreCrossStory).put("coreDependencyCount", coreCount)
                .put("sameStoryEdgeCount", sameStoryEdges).put("maxInDegreeBefore", maxBefore)
                .put("maxInDegreeAfter", maxAfter).put("readOnlyForScheduler", false);
    }

    private boolean isExplicitCommonWeakCapability(JsonNode task) {
        String text = (task.path("name").asText("") + " " + task.path("title").asText("")).toLowerCase(Locale.ROOT);
        return containsAny(text, "基础设施", "基础框架", "认证权限", "统一认证", "访问控制", "公共数据初始化",
                "公共字典", "通用服务", "公共服务", "infrastructure", "authentication", "authorization", "shared service");
    }

    private boolean hasStrongWeakEvidence(JsonNode predecessor, JsonNode successor, boolean sameStory, ArrayNode tasks) {
        String before = taskSemanticText(predecessor), after = taskSemanticText(successor);
        int priorStage = taskStage(predecessor.path("name").asText(""));
        int nextStage = taskStage(successor.path("name").asText(""));
        if (sameStory && priorStage >= 0 && nextStage > priorStage) {
            boolean intermediateStage = false;
            for (JsonNode candidate : tasks) {
                int stage = taskStage(candidate.path("name").asText(""));
                if (stage > priorStage && stage < nextStage && sameStoryRelation(predecessor, candidate)
                        && sameStoryRelation(successor, candidate)) intermediateStage = true;
            }
            if (!intermediateStage) return true;
        }
        if (sameStory && containsAny(before, "接口", "api", "后端")
                && containsAny(after, "参数校验", "输入校验", "接口校验")) return true;
        if (isExplicitCommonWeakCapability(predecessor)) {
            if (containsAny(before, "基础设施", "基础框架", "infrastructure")
                    && containsAny(after, "接入", "部署", "后端", "接口", "页面", "integration")) return true;
            if (containsAny(before, "认证", "权限", "访问控制", "authentication", "authorization")
                    && containsAny(after, "认证", "鉴权", "权限", "访问控制", "authentication", "authorization")) return true;
            if (containsAny(before, "公共数据初始化", "公共字典")
                    && containsAny(after, "读取", "查询", "查看", "展示", "read", "query")) return true;
            if (containsAny(before, "通用服务", "公共服务", "shared service")
                    && containsAny(after, "接入通用服务", "调用公共服务", "shared service")) return true;
        }
        // Use the named object, not incidental words in acceptance/description.
        String priorObject = weakDependencyBusinessObject(predecessor), nextObject = weakDependencyBusinessObject(successor);
        if (priorObject.isBlank() || !priorObject.equals(nextObject)) return false;
        boolean lifecycle = (priorObject.equals("报名") && containsAny(before, "提交", "申请提交")
                && containsAny(after, "审核", "审批"))
                || (priorObject.equals("考勤") && containsAny(before, "签到") && !before.contains("签退") && after.contains("签退"))
                || (priorObject.equals("服务时长") && containsAny(before, "生成", "计算", "结算")
                && containsAny(after, "查看", "查询", "展示", "读取"));
        boolean prepareToRead = containsAny(before, "数据初始化", "数据准备", "数据导入", "生成", "写入", "data preparation", "data import")
                && containsAny(after, "读取", "查询", "查看", "展示", "read", "query");
        boolean backendToFrontend = containsAny(before, "后端", "接口", "api", "backend")
                && containsAny(after, "前端", "页面", "界面", "frontend")
                && weakDependencyOperations(predecessor).equals(weakDependencyOperations(successor));
        return lifecycle || prepareToRead || backendToFrontend;
    }

    private Set<String> weakDependencyOperations(JsonNode task) {
        Set<String> signals = businessSignals((task.path("name").asText("") + " " + task.path("title").asText("")).toLowerCase(Locale.ROOT));
        signals.removeAll(List.of("结果", "通知", "异常", "时间", "人数", "截止", "权限"));
        return signals;
    }

    private String weakDependencyBusinessObject(JsonNode task) {
        String text = (task.path("name").asText("") + " " + task.path("title").asText("")).toLowerCase(Locale.ROOT);
        int domains = 0;
        for (List<String> terms : List.of(List.of("报名", "申请记录"),
                List.of("服务时长", "志愿时长", "时长记录", "累计时长", "个人时长"),
                List.of("签到", "签退"), List.of("评价", "评分", "反馈"),
                List.of("个人资料", "用户资料", "个人信息", "用户信息"),
                List.of("角色权限", "权限管理", "角色管理"), List.of("登录"), List.of("注册")))
            if (containsAny(text, terms.toArray(String[]::new))) domains++;
        if (domains > 1) return "";
        if (containsAny(text, "签到", "签退")) return "考勤";
        return businessObject(text);
    }

    private boolean hasSameStoryAlternateDependencyPath(JsonNode predecessor, JsonNode successor,
                                                       Map<String, ObjectNode> byId) {
        Set<String> sharedStories = new TreeSet<>(String.CASE_INSENSITIVE_ORDER);
        for (String story : predecessor.path("story_ref").asText("").split(",")) if (!story.isBlank()) sharedStories.add(story.trim());
        Set<String> successorStories = new TreeSet<>(String.CASE_INSENSITIVE_ORDER);
        for (String story : successor.path("story_ref").asText("").split(",")) if (!story.isBlank()) successorStories.add(story.trim());
        sharedStories.retainAll(successorStories);
        for (String story : sharedStories) {
            Set<String> visited = new TreeSet<>(String.CASE_INSENSITIVE_ORDER);
            List<JsonNode> pending = new ArrayList<>();
            pending.add(successor);
            for (int i = 0; i < pending.size(); i++) {
                JsonNode current = pending.get(i);
                if (!visited.add(current.path("id").asText())) continue;
                for (String ref : (current.path("depends_on").asText("") + ","
                        + current.path("weak_dependencies").asText("")).split(",")) {
                    JsonNode candidate = byId.get(ref.trim());
                    if (candidate == null || candidate == successor) continue;
                    if (current == successor && candidate == predecessor) continue;
                    boolean inStory = false;
                    for (String candidateStory : candidate.path("story_ref").asText("").split(","))
                        if (story.equalsIgnoreCase(candidateStory.trim())) inStory = true;
                    if (!inStory) continue;
                    if (candidate == predecessor) return true;
                    pending.add(candidate);
                }
            }
        }
        return false;
    }

    private boolean isCoreSchedulingDependency(JsonNode predecessor, JsonNode successor, boolean sameStory) {
        String before = taskSemanticText(predecessor), after = taskSemanticText(successor);
        if (isWeakStoryDriver(before) || isWeakStoryDriver(after)) return false;
        boolean correspondingDomain = sameStory || sharesBusinessDomain(before, after);
        boolean schemaToApi = containsAny(before, "schema", "表结构", "数据表", "数据模型", "数据库设计")
                && containsAny(after, "接口", "api", "service", "服务") && correspondingDomain;
        boolean apiToUi = containsAny(before, "接口", "api", "后端", "service", "服务")
                && containsAny(after, "前端", "页面", "界面", "表单", "组件") && correspondingDomain;
        boolean submitToReview = containsAny(before, "提交", "申请", "报名")
                && containsAny(after, "审核", "审批") && correspondingDomain;
        boolean createToManage = containsAny(before, "创建", "新增", "发布")
                && containsAny(after, "修改", "编辑", "取消", "查看", "管理") && correspondingDomain;
        boolean attendanceChain = containsAny(before, "签到") && containsAny(after, "签退", "时长", "服务记录");
        return schemaToApi || apiToUi || submitToReview || createToManage || attendanceChain;
    }

    private ArrayNode coreSprintOrderViolations(ArrayNode stories, ArrayNode tasks) {
        Map<String, JsonNode> byId = uniqueTaskMap(tasks, "core Sprint conflict check");
        ArrayNode violations = mapper.createArrayNode();
        List<JsonNode> ordered = new ArrayList<>();
        tasks.forEach(ordered::add);
        ordered.sort(Comparator.comparing(task -> task.path("id").asText(), String.CASE_INSENSITIVE_ORDER));
        for (JsonNode task : ordered) {
            if (task.path("_unresolved_story_relation").asBoolean()) continue;
            int currentSprint = primaryStoryIdSprint(task, stories);
            for (String dependency : task.path("depends_on").asText("").split(",")) {
                JsonNode predecessor = byId.get(dependency.trim());
                if (predecessor == null || sameStoryRelation(task, predecessor)
                        || !isCoreStoryDependency(predecessor, task)) continue;
                int predecessorSprint = primaryStoryIdSprint(predecessor, stories);
                if (predecessorSprint > currentSprint) violations.add(mapper.createObjectNode()
                        .put("predecessorTaskId", dependency.trim()).put("currentTaskId", task.path("id").asText())
                        .put("predecessorSprint", predecessorSprint).put("currentSprint", currentSprint));
            }
        }
        return violations;
    }

    private ObjectNode preScheduleConsistencyCheck(ArrayNode stories, ArrayNode tasks,
                                                   ObjectNode relationCheck, ArrayNode sprintViolations) {
        ObjectNode result = mapper.createObjectNode();
        ArrayNode blocking = mapper.createArrayNode();
        Set<String> ids = new HashSet<>();
        tasks.forEach(task -> ids.add(task.path("id").asText().toUpperCase(Locale.ROOT)));
        Set<String> storyIds = new HashSet<>();
        stories.forEach(story -> storyIds.add(story.path("id").asText().toUpperCase(Locale.ROOT)));
        int invalidDependencies = 0, selfDependencies = 0, duplicates = 0, tasksWithoutSprint = 0;
        for (JsonNode task : tasks) {
            String taskId = task.path("id").asText();
            if (!task.path("_unresolved_story_relation").asBoolean()
                    && stablePrimaryStorySprint(task, stories) <= 0) tasksWithoutSprint++;
            Set<String> seen = new HashSet<>();
            for (String dependency : task.path("depends_on").asText("").split(",")) {
                String ref = dependency.trim();
                if (ref.isBlank()) continue;
                if (!seen.add(ref.toUpperCase(Locale.ROOT))) duplicates++;
                if (ref.equalsIgnoreCase(taskId)) selfDependencies++;
                if (!ids.contains(ref.toUpperCase(Locale.ROOT))) invalidDependencies++;
            }
        }
        List<String> cycle = findDependencyCycle(tasks);
        int orphanTasks = relationCheck.path("orphanTaskCount").asInt();
        int uncoveredStories = relationCheck.path("uncoveredStoryCount").asInt();
        int invalidRelations = relationCheck.path("invalidReferenceCount").asInt();
        if (orphanTasks > 0) blocking.add("Task 未关联 Story：" + orphanTaskIds(tasks));
        if (uncoveredStories > 0) blocking.add("Story 没有对应 Task：" + uncoveredStoryIds(stories, tasks));
        if (invalidRelations > 0) blocking.add("存在 " + invalidRelations + " 条非法 Story 引用");
        if (invalidDependencies > 0) blocking.add("存在 " + invalidDependencies + " 条非法依赖引用");
        if (selfDependencies > 0) blocking.add("存在 " + selfDependencies + " 条自依赖");
        if (duplicates > 0) blocking.add("存在 " + duplicates + " 条重复依赖");
        if (!cycle.isEmpty()) blocking.add("依赖图仍有循环：" + String.join(" -> ", cycle));
        if (tasksWithoutSprint > 0) blocking.add("存在 " + tasksWithoutSprint + " 个没有合法主 Sprint 的 Task");
        if (!sprintViolations.isEmpty()) blocking.add("存在 " + sprintViolations.size() + " 条 STORY_SPRINT_DEPENDENCY_CONFLICT");
        result.put("orphanTaskCount", orphanTasks);
        result.put("uncoveredStoryCount", uncoveredStories);
        result.put("invalidStoryReferenceCount", invalidRelations);
        result.put("invalidDependencyCount", invalidDependencies);
        result.put("selfDependencyCount", selfDependencies);
        result.put("duplicateDependencyCount", duplicates);
        result.put("cycleCount", cycle.isEmpty() ? 0 : 1);
        result.put("sprintOrderViolationCount", sprintViolations.size());
        result.put("tasksWithoutPrimarySprintCount", tasksWithoutSprint);
        result.set("blockingIssues", blocking);
        result.put("sufficient", blocking.isEmpty());
        result.put("readOnly", true);
        return result;
    }

    private String orphanTaskIds(ArrayNode tasks) {
        List<String> ids = new ArrayList<>();
        for (JsonNode task : tasks) if (task.path("_unresolved_story_relation").asBoolean())
            ids.add(task.path("id").asText());
        ids.sort(String.CASE_INSENSITIVE_ORDER);
        return String.join(",", ids);
    }

    private String uncoveredStoryIds(ArrayNode stories, ArrayNode tasks) {
        Set<String> covered = new HashSet<>();
        for (JsonNode task : tasks) for (String ref : task.path("story_ref").asText("").split(","))
            if (!ref.isBlank()) covered.add(ref.trim().toUpperCase(Locale.ROOT));
        return stories.findValuesAsText("id").stream()
                .filter(id -> !covered.contains(id.toUpperCase(Locale.ROOT)))
                .sorted(String.CASE_INSENSITIVE_ORDER).collect(java.util.stream.Collectors.joining(","));
    }

    private void applyPreScheduleConflicts(ArrayNode tasks, ArrayNode violations) {
        Set<String> conflicted = new HashSet<>();
        for (JsonNode violation : violations) {
            conflicted.add(violation.path("currentTaskId").asText().toUpperCase(Locale.ROOT));
            conflicted.add(violation.path("predecessorTaskId").asText().toUpperCase(Locale.ROOT));
        }
        for (JsonNode node : tasks) {
            ObjectNode task = (ObjectNode) node;
            if (conflicted.contains(task.path("id").asText().toUpperCase(Locale.ROOT)))
                task.set("_schedule_conflict", mapper.createObjectNode()
                        .put("code", "STORY_SPRINT_DEPENDENCY_CONFLICT")
                        .put("message", "Sprint 依赖逆序无法在项目 Sprint 范围内修复"));
        }
    }

    private ObjectNode compactDetailedTasks(ArrayNode stories, ArrayNode tasks, ArrayNode milestones, ObjectNode feasibility) {
        int before = tasks.size();
        ObjectNode perStoryBefore = taskCountsByStory(tasks);
        List<ObjectNode> ordered = new ArrayList<>();
        tasks.forEach(node -> ordered.add((ObjectNode) node));
        ordered.sort(Comparator.comparing((ObjectNode task) -> stableTaskRefKey(task.path("story_ref").asText("")))
                .thenComparing(this::taskSemanticStage)
                .thenComparing(this::compactTaskSemanticKey)
                .thenComparing(task -> task.path("id").asText(""), String.CASE_INSENSITIVE_ORDER));

        Map<String, ObjectNode> retained = new LinkedHashMap<>();
        Map<String, String> aliases = new LinkedHashMap<>();
        for (ObjectNode task : ordered) {
            String relationKey = stableTaskRefKey(task.path("story_ref").asText(""));
            String semanticKey = compactTaskSemanticKey(task);
            String key = relationKey + "|" + taskSemanticStage(task) + "|" + semanticKey;
            if (relationKey.isBlank() || semanticKey.isBlank()) {
                retained.put("unique|" + task.path("id").asText("") + "|" + retained.size(), task);
                continue;
            }
            ObjectNode prior = retained.get(key);
            if (prior == null) {
                retained.put(key, task);
                continue;
            }
            mergeEquivalentDetailedTask(prior, task);
            aliases.put(task.path("id").asText(), prior.path("id").asText());
        }

        // A Story with more than six distinct rows is over-decomposed. Consolidate
        // within the same development stage; never discard rows by code position.
        compactOverDecomposedStories(retained, aliases);
        compactCoveredAggregateTasks(retained, aliases);

        tasks.removeAll();
        retained.values().stream()
                .sorted(Comparator.comparing(task -> task.path("id").asText(""), String.CASE_INSENSITIVE_ORDER))
                .forEach(tasks::add);
        rewriteCompactedTaskReferences(tasks, milestones, aliases);

        int projectLevel = 0;
        for (JsonNode task : tasks) if (stableTaskRefKey(task.path("story_ref").asText("")).isBlank()) projectLevel++;
        ObjectNode stats = mapper.createObjectNode().put("beforeCount", before).put("afterCount", tasks.size())
                .put("mergedCount", before - tasks.size()).put("projectLevelTaskCount", projectLevel);
        stats.set("perStoryBefore", perStoryBefore);
        stats.set("perStoryAfter", taskCountsByStory(tasks));
        feasibility.put("postFeasibilityTaskCount", tasks.size());
        feasibility.put("mergedDuplicateTaskCount", before - tasks.size());
        return stats;
    }

    /** Removes a broad same-Story implementation row only when two or more stable,
     * more-specific rows cover all of its named business objects in the same stage. */
    private void compactCoveredAggregateTasks(Map<String, ObjectNode> retained, Map<String, String> aliases) {
        Map<String, List<Map.Entry<String, ObjectNode>>> byStoryAndStage = new java.util.TreeMap<>(String.CASE_INSENSITIVE_ORDER);
        for (Map.Entry<String, ObjectNode> entry : retained.entrySet()) {
            ObjectNode task = entry.getValue();
            String relation = stableTaskRefKey(task.path("story_ref").asText(""));
            if (!relation.isBlank()) byStoryAndStage.computeIfAbsent(relation + "|" + taskSemanticStage(task), ignored -> new ArrayList<>()).add(entry);
        }
        for (List<Map.Entry<String, ObjectNode>> rows : byStoryAndStage.values()) {
            rows.sort(Comparator.comparing(entry -> entry.getValue().path("id").asText(""), String.CASE_INSENSITIVE_ORDER));
            for (Map.Entry<String, ObjectNode> aggregateEntry : new ArrayList<>(rows)) {
                if (!retained.containsKey(aggregateEntry.getKey())) continue;
                ObjectNode aggregate = aggregateEntry.getValue();
                Set<String> aggregateObjects = compactBusinessObjects(taskSemanticText(aggregate));
                if (aggregateObjects.size() < 2) continue;
                List<Map.Entry<String, ObjectNode>> covering = rows.stream()
                        .filter(entry -> entry != aggregateEntry && retained.containsKey(entry.getKey()))
                        .filter(entry -> {
                            Set<String> objects = compactBusinessObjects(taskSemanticText(entry.getValue()));
                            return !objects.isEmpty() && aggregateObjects.containsAll(objects) && objects.size() < aggregateObjects.size();
                        }).toList();
                Set<String> covered = new java.util.TreeSet<>(String.CASE_INSENSITIVE_ORDER);
                covering.forEach(entry -> covered.addAll(compactBusinessObjects(taskSemanticText(entry.getValue()))));
                if (covering.size() < 2 || !covered.containsAll(aggregateObjects)) continue;
                Map.Entry<String, ObjectNode> anchor = covering.get(0);
                mergeEquivalentDetailedTask(anchor.getValue(), aggregate);
                aliases.put(aggregate.path("id").asText(), anchor.getValue().path("id").asText());
                retained.remove(aggregateEntry.getKey());
            }
        }
    }

    private Set<String> compactBusinessObjects(String text) {
        Set<String> result = new java.util.TreeSet<>(String.CASE_INSENSITIVE_ORDER);
        if (containsAny(text, "用户", "账号", "学生")) result.add("用户");
        if (text.contains("活动")) result.add("活动");
        if (containsAny(text, "报名", "申请")) result.add("报名");
        if (containsAny(text, "签到", "签退", "时长")) result.add("考勤");
        if (containsAny(text, "消息", "通知", "提醒")) result.add("消息");
        if (containsAny(text, "评价", "评分", "反馈")) result.add("评价");
        return result;
    }

    private ObjectNode finalDetailedCapacityFeasibility(ArrayNode stories, ArrayNode tasks, ArrayNode milestones,
                                                        ArrayNode sprints, ArrayNode members, ObjectNode feasibility) {
        int beforeHours = totalTaskHours(tasks), iterations = 0;
        ObjectNode current = capacitySnapshot(stories, tasks, sprints, members);
        while (!current.path("capacityFeasible").asBoolean() && iterations < 3) {
            int beforeCount = tasks.size(), hours = totalTaskHours(tasks);
            compactDetailedTasks(stories, tasks, milestones, feasibility);
            iterations++;
            current = capacitySnapshot(stories, tasks, sprints, members);
            if (tasks.size() == beforeCount && totalTaskHours(tasks) == hours) break;
        }
        current.put("compactionIterations", iterations);
        current.put("hoursReduced", Math.max(0, beforeHours - totalTaskHours(tasks)));
        current.put("finalTaskHours", totalTaskHours(tasks));
        return current;
    }

    private ObjectNode capacitySnapshot(ArrayNode stories, ArrayNode tasks, ArrayNode sprints, ArrayNode members) {
        int developerCount = 0, weeklyCapacity = 0;
        for (JsonNode member : members) if (isAssignableDeveloper(member)) {
            developerCount++;
            weeklyCapacity += Math.max(0, member.path("capacity_hours").asInt());
        }
        int totalHours = totalTaskHours(tasks), weeks = Math.max(1, totalWeeks(sprints));
        Map<Integer, Integer> sprintHours = new java.util.TreeMap<>();
        for (JsonNode task : tasks) {
            int sprint = stablePrimaryStorySprint(task, stories);
            if (sprint > 0) sprintHours.merge(sprint, taskHours(task), Integer::sum);
        }
        ObjectNode perSprintCapacity = mapper.createObjectNode(), perSprintTaskHours = mapper.createObjectNode();
        boolean feasible = totalHours <= weeklyCapacity * weeks;
        for (JsonNode sprint : sprints) {
            int number = sprint.path("sprint").asInt();
            int span = Math.max(1, sprint.path("week_end").asInt() - sprint.path("week_start").asInt() + 1);
            int capacity = weeklyCapacity * span, hours = sprintHours.getOrDefault(number, 0);
            perSprintCapacity.put("S" + number, capacity);
            perSprintTaskHours.put("S" + number, hours);
            feasible &= hours <= capacity;
        }
        ObjectNode result = mapper.createObjectNode().put("developerCount", developerCount)
                .put("totalCapacityHours", weeklyCapacity * weeks).put("finalTaskHours", totalHours)
                .put("capacityFeasible", feasible);
        result.set("perSprintCapacity", perSprintCapacity);
        result.set("perSprintTaskHours", perSprintTaskHours);
        return result;
    }

    private int totalTaskHours(ArrayNode tasks) {
        int total = 0;
        for (JsonNode task : tasks) total += taskHours(task);
        return total;
    }

    private int taskHours(JsonNode task) {
        return Math.max(0, task.path("estimated_hours").asInt(task.path("hours").asInt()));
    }

    private boolean isAssignableDeveloper(JsonNode member) {
        return member.path("id").asInt() > 0 && !Set.of("viewer", "readonly", "read_only")
                .contains(member.path("role").asText("").toLowerCase(Locale.ROOT));
    }

    private String compactTaskSemanticKey(JsonNode task) {
        String text = taskSemanticText(task)
                .replaceAll("(负责|进行|完成|实现|开发|编写|构建|制作|处理|相关|功能|逻辑|模块)", "")
                .replaceAll("(后端api|api接口|后端接口|服务接口|接口服务)", "接口")
                .replaceAll("(前端页面|页面前端|web页面|界面|组件页面)", "页面")
                .replaceAll("(参数校验|表单校验|数据校验|合法性验证|有效性验证)", "校验")
                .replaceAll("[\\p{Punct}\\s，。；：、（）]+", "");
        return text;
    }

    private String taskSemanticStage(JsonNode task) {
        String text = taskSemanticText(task);
        if (containsAny(text, "数据库", "数据表", "表结构", "schema", "实体模型")) return "01-data";
        if (containsAny(text, "接口", "api", "service", "后端")) return "02-api";
        if (containsAny(text, "页面", "前端", "界面", "表单", "组件")) return "03-ui";
        if (containsAny(text, "校验", "验证", "唯一性", "权限控制")) return "04-validation";
        if (containsAny(text, "测试", "联调", "验收", "回归")) return "05-test";
        return "06-business";
    }

    private String stableTaskRefKey(String refs) {
        List<String> values = new ArrayList<>();
        for (String ref : refs.split("[,，;；\\s]+")) if (!ref.isBlank()) values.add(ref.trim().toUpperCase(Locale.ROOT));
        values.sort(String.CASE_INSENSITIVE_ORDER);
        return String.join(",", new LinkedHashSet<>(values));
    }

    private void mergeEquivalentDetailedTask(ObjectNode retained, ObjectNode duplicate) {
        TaskStoryReferences.mergeAudit(retained, duplicate);
        preserveMergedScheduleAudit(retained, retained);
        preserveMergedScheduleAudit(retained, duplicate);
        retained.put("story_ref", unionRefs(retained.path("story_ref").asText(""), duplicate.path("story_ref").asText("")));
        if (retained.path(TaskStoryReferences.AUDIT).isArray()) retained.put("storyCode", retained.path("story_ref").asText());
        retained.put("depends_on", unionRefs(retained.path("depends_on").asText(""), duplicate.path("depends_on").asText("")));
        String modelStoryRefs = unionRefs(retained.path("model_story_refs").asText(""), duplicate.path("model_story_refs").asText(""));
        String invalidStoryRefs = unionRefs(retained.path("invalid_story_refs").asText(""), duplicate.path("invalid_story_refs").asText(""));
        if (!modelStoryRefs.isBlank()) retained.put("model_story_refs", modelStoryRefs);
        if (!invalidStoryRefs.isBlank()) retained.put("invalid_story_refs", invalidStoryRefs);
        for (String field : List.of("model_task_code", "generation_stage", "generation_continuation_round", "generation_identity")) {
            if (!retained.has(field) && duplicate.has(field)) retained.set(field, duplicate.path(field).deepCopy());
        }
        mergeProvenanceSources(retained, duplicate);
        mergeStringArrayField(retained, duplicate, "generation_dependency_refs");
        mergeStringArrayField(retained, duplicate, "generation_weak_dependency_refs");
        mergeStringArrayField(retained, duplicate, "generation_related_task_refs");
        mergeStringArrayField(retained, duplicate, "unresolved_cross_stage_references");
        int retainedHours = Math.max(retained.path("hours").asInt(), retained.path("estimated_hours").asInt());
        int duplicateHours = Math.max(duplicate.path("hours").asInt(), duplicate.path("estimated_hours").asInt());
        int mergedHours = Math.max(retainedHours, duplicateHours);
        retained.put("hours", mergedHours).put("estimated_hours", mergedHours);
        retained.put("_merged_hours_total", retained.path("_merged_hours_total").asInt(retainedHours) + duplicateHours);
        ArrayNode mergedCodes = retained.withArray("_merged_task_codes");
        if (mergedCodes.isEmpty()) mergedCodes.add(retained.path("id").asText());
        mergedCodes.add(duplicate.path("id").asText());
    }

    private void mergeProvenanceSources(ObjectNode retained, JsonNode duplicate) {
        ArrayNode sources = retained.withArray("generation_sources");
        if (sources.isEmpty()) sources.add(provenanceSource(retained));
        JsonNode incoming = duplicate.get("generation_sources");
        if (incoming != null && incoming.isArray()) {
            for (JsonNode source : incoming) if (!containsProvenanceSource(sources, source)) sources.add(source.deepCopy());
        } else {
            JsonNode source = provenanceSource(duplicate);
            if (!containsProvenanceSource(sources, source)) sources.add(source);
        }
    }

    private ObjectNode provenanceSource(JsonNode task) {
        return mapper.createObjectNode().put("stage", task.path("generation_stage").asText("unknown"))
                .put("continuationRound", task.path("generation_continuation_round").asInt(0))
                .put("modelTaskCode", task.path("model_task_code").asText(task.path("id").asText("")))
                .put("identity", task.path("generation_identity").asText(""));
    }

    private boolean containsProvenanceSource(ArrayNode sources, JsonNode candidate) {
        String identity = candidate.path("identity").asText("");
        for (JsonNode source : sources) {
            if (!identity.isBlank() && identity.equalsIgnoreCase(source.path("identity").asText(""))) return true;
            if (identity.isBlank() && candidate.path("stage").asText("").equalsIgnoreCase(source.path("stage").asText(""))
                    && candidate.path("continuationRound").asInt(0) == source.path("continuationRound").asInt(0)
                    && candidate.path("modelTaskCode").asText("").equalsIgnoreCase(source.path("modelTaskCode").asText(""))) return true;
        }
        return false;
    }

    private void mergeStringArrayField(ObjectNode retained, JsonNode duplicate, String field) {
        JsonNode incoming = duplicate.get(field);
        if (incoming == null) return;
        ArrayNode values = retained.withArray(field);
        if (incoming.isArray()) for (JsonNode value : incoming) if (!containsText(values, value.asText(""))) values.add(value.asText(""));
        else if (!incoming.asText("").isBlank() && !containsText(values, incoming.asText(""))) values.add(incoming.asText(""));
    }

    private boolean containsText(ArrayNode values, String text) {
        for (JsonNode value : values) if (value.asText("").equalsIgnoreCase(text)) return true;
        return false;
    }

    private void preserveMergedScheduleAudit(ObjectNode retained, JsonNode source) {
        ArrayNode audit = retained.withArray("_merged_model_schedules");
        String code = source.path("id").asText("");
        for (JsonNode row : audit) if (code.equalsIgnoreCase(row.path("taskCode").asText())) return;
        ObjectNode row = mapper.createObjectNode().put("taskCode", code);
        for (String field : List.of("owner_id", "week_start", "week_end", "model_owner_id", "model_week_start", "model_week_end"))
            if (source.has(field)) row.set(field, source.path(field).deepCopy());
        audit.add(row);
    }

    private void compactOverDecomposedStories(Map<String, ObjectNode> retained, Map<String, String> aliases) {
        Map<String, List<Map.Entry<String, ObjectNode>>> byStory = new java.util.TreeMap<>(String.CASE_INSENSITIVE_ORDER);
        for (Map.Entry<String, ObjectNode> entry : retained.entrySet()) {
            String relation = stableTaskRefKey(entry.getValue().path("story_ref").asText(""));
            if (!relation.isBlank()) byStory.computeIfAbsent(relation, ignored -> new ArrayList<>()).add(entry);
        }
        for (List<Map.Entry<String, ObjectNode>> rows : byStory.values()) {
            if (rows.size() <= 6) continue;
            rows.sort(Comparator.comparing((Map.Entry<String, ObjectNode> entry) -> taskSemanticStage(entry.getValue()))
                    .thenComparing(entry -> compactTaskSemanticKey(entry.getValue()))
                    .thenComparing(entry -> entry.getValue().path("id").asText(""), String.CASE_INSENSITIVE_ORDER));
            Map<String, Map.Entry<String, ObjectNode>> stageAnchors = new LinkedHashMap<>();
            for (Map.Entry<String, ObjectNode> entry : rows) {
                String stage = taskSemanticStage(entry.getValue());
                Map.Entry<String, ObjectNode> anchor = stageAnchors.get(stage);
                if (anchor == null) {
                    stageAnchors.put(stage, entry);
                    continue;
                }
                mergeEquivalentDetailedTask(anchor.getValue(), entry.getValue());
                aliases.put(entry.getValue().path("id").asText(), anchor.getValue().path("id").asText());
                retained.remove(entry.getKey());
            }
        }
    }

    private void rewriteCompactedTaskReferences(ArrayNode tasks, ArrayNode milestones, Map<String, String> aliases) {
        if (aliases.isEmpty()) return;
        for (JsonNode node : tasks) {
            ObjectNode task = (ObjectNode) node;
            task.put("depends_on", remapTaskRefs(task.path("depends_on").asText(""), aliases,
                    task.path("id").asText("")));
        }
        for (JsonNode node : milestones) {
            ObjectNode milestone = (ObjectNode) node;
            milestone.put("related_task_ids", remapTaskRefs(milestone.path("related_task_ids").asText(""), aliases, ""));
        }
    }

    private String remapTaskRefs(String refs, Map<String, String> aliases, String self) {
        LinkedHashSet<String> result = new LinkedHashSet<>();
        for (String ref : refs.split("[,，;；\\s]+")) {
            if (ref.isBlank()) continue;
            String mapped = aliases.getOrDefault(ref.trim(), ref.trim());
            if (!mapped.equalsIgnoreCase(self)) result.add(mapped);
        }
        return String.join(",", result);
    }

    private ObjectNode taskCountsByStory(ArrayNode tasks) {
        Map<String, Integer> counts = new java.util.TreeMap<>(String.CASE_INSENSITIVE_ORDER);
        for (JsonNode task : tasks) {
            String key = stableTaskRefKey(task.path("story_ref").asText(""));
            counts.merge(key.isBlank() ? "_project" : key, 1, Integer::sum);
        }
        return mapper.valueToTree(counts);
    }

    private ObjectNode finalSprintCheck(ArrayNode stories, ArrayNode tasks, ArrayNode sprints) {
        int mismatch = 0, dependencyConflicts = 0, unresolved = 0, scheduled = 0, preserved = 0;
        ArrayNode details = mapper.createArrayNode();
        Map<String, JsonNode> byId = uniqueTaskMap(tasks, "final Sprint check");
        for (JsonNode task : tasks) {
            int primarySprint = stablePrimaryStorySprint(task, stories);
            // Missing Story is a relation failure, not a synthetic Sprint-1 mismatch.
            int[] range = primarySprint > 0 ? sprintRange(sprints, primarySprint) : null;
            if (task.path("_schedule_conflict").isObject()) {
                unresolved++;
                if (task.has("model_week_start") || task.has("model_owner_id")) preserved++;
                details.add(task.path("id").asText() + " " + task.path("_schedule_conflict").path("code").asText());
            } else if (task.has("week_start") && task.has("week_end") && task.has("owner_id")) scheduled++;
            Integer auditStart = scheduleValue(task, "week_start", "model_week_start");
            Integer auditEnd = scheduleValue(task, "week_end", "model_week_end");
            if (range != null && auditStart != null && auditEnd != null
                    && (auditStart < range[0] || auditEnd > range[1])) mismatch++;
            for (String dep : task.path("depends_on").asText("").split(",")) {
                JsonNode predecessor = byId.get(dep.trim());
                Integer predecessorEnd = predecessor == null ? null
                        : scheduleValue(predecessor, "week_end", "model_week_end");
                if (!dep.isBlank() && predecessorEnd != null && auditStart != null && auditStart < predecessorEnd) {
                    dependencyConflicts++;
                    details.add(task.path("id").asText() + " 早于前置任务 " + dep.trim() + " 完成");
                }
            }
        }
        ObjectNode result = mapper.createObjectNode().put("mismatchCount", mismatch)
                .put("trueSprintMismatchCount", mismatch).put("scheduledTaskCount", scheduled)
                .put("dependencyTimingConflictCount", dependencyConflicts)
                .put("unresolvedSchedulingConflictCount", unresolved)
                .put("preservedModelScheduleCount", preserved).put("readOnly", true);
        result.set("details", details);
        return result;
    }

    private Integer scheduleValue(JsonNode task, String finalField, String modelField) {
        JsonNode value = task.get(finalField);
        if (value != null && value.isIntegralNumber()) return value.asInt();
        value = task.get(modelField);
        return value != null && value.isIntegralNumber() ? value.asInt() : null;
    }

    private ObjectNode legacyFinalSprintCheck(ArrayNode stories, ArrayNode tasks, ArrayNode sprints) {
        int mismatch = 0;
        for (JsonNode task : tasks) {
            int[] range = primaryStorySprintRange(task, stories, sprints);
            if (range != null && (task.path("week_start").asInt() < range[0]
                    || task.path("week_end").asInt() > range[1])) mismatch++;
        }
        return mapper.createObjectNode().put("mismatchCount", mismatch).put("readOnly", true);
    }

    private ObjectNode finalActivityCheck(ObjectNode draft, ArrayNode stories) {
        ArrayNode issues = mapper.createArrayNode();
        Set<String> codes = new HashSet<>(); int empty = 0, duplicates = 0, expected = 1;
        boolean continuous = true;
        for (JsonNode activity : draft.path("activities")) {
            String code = activity.path("code").asText("");
            if (!codes.add(code)) duplicates++;
            if (!("A" + expected++).equals(code)) continuous = false;
        }
        Set<String> used = new HashSet<>(); int invalid = 0, invalidEpics = 0;
        for (JsonNode story : stories) {
            String code = story.path("activity").asText("");
            if (code.isBlank()) empty++;
            else if (!codes.contains(code)) invalid++;
            else used.add(code);
            if (!codes.contains(code)) issues.add(mapper.createObjectNode().put("entityType", "Story")
                    .put("id", story.path("id").asText()).put("activity", code)
                    .put("reason", "INVALID_STORY_ACTIVITY"));
        }
        for (JsonNode epic : draft.path("epics")) {
            String code = epic.path("activity").asText("");
            if (!codes.contains(code)) {
                invalidEpics++;
                issues.add(mapper.createObjectNode().put("entityType", "Epic")
                        .put("id", epic.path("id").asText()).put("activity", code)
                        .put("original_activity", epic.path("original_activity").asText())
                        .put("reason", epic.path("activity_reference_error").asText("INVALID_EPIC_ACTIVITY")));
            } else used.add(code);
        }
        int emptyActivities = Math.max(0, codes.size() - used.size());
        ObjectNode check = mapper.createObjectNode().put("activityCount", codes.size()).put("emptyStoryActivityCount", empty)
                .put("invalidStoryActivityCount", invalid).put("invalidEpicActivityCount", invalidEpics)
                .put("duplicateActivityCodeCount", duplicates).put("continuousCodes", continuous)
                .put("emptyActivityCount", emptyActivities)
                .put("sufficient", !codes.isEmpty() && empty == 0 && invalid == 0 && invalidEpics == 0
                        && duplicates == 0 && continuous && emptyActivities == 0)
                .put("readOnly", true);
        check.set("issues", issues);
        return check;
    }

    private void finalCapacityBalance(ArrayNode tasks, ArrayNode stories, ArrayNode sprints, ArrayNode members,
                                      ArrayNode scheduleMoves, ArrayNode loadMoves, ArrayNode ownerMoves,
                                      ArrayNode unresolved, ArrayNode warnings, boolean detailedMode,
                                      ObjectNode qualityAdjustments) {
        if (detailedMode) deterministicFinalSchedule(tasks, stories, sprints, members, ownerMoves, scheduleMoves, unresolved, warnings);
        else legacyFinalSchedule(tasks, stories, sprints, members, ownerMoves, scheduleMoves, unresolved, warnings);
        if (detailedMode) detailedWorkloadAudit(tasks, members, unresolved, warnings, qualityAdjustments);
        else workloadWarnings(tasks, members, unresolved);
    }

    private void detailedWorkloadAudit(ArrayNode tasks, ArrayNode members, ArrayNode finalRisks,
                                       ArrayNode warnings, ObjectNode qualityAdjustments) {
        ObjectNode scheduled = workloadSnapshot(tasks, members, false);
        ObjectNode unresolvedReference = workloadSnapshot(tasks, members, true);
        qualityAdjustments.set("finalScheduledWorkload", scheduled);
        qualityAdjustments.set("unresolvedModelScheduleReference", unresolvedReference);
        for (JsonNode overload : scheduled.path("overloads"))
            finalRisks.add(overload.path("memberName").asText() + " W" + overload.path("week").asInt()
                    + " 预计 " + overload.path("hours").asInt() + "h / 容量 "
                    + overload.path("capacityHours").asInt() + "h，无法安全再平衡");
        int unresolvedCount = unresolvedReference.path("taskCount").asInt();
        if (unresolvedCount > 0) warnings.add("存在" + unresolvedCount
                + "个未解决 Task；其模型负责人/周次仅供审计参考，不计入最终成员负载");
    }

    private ObjectNode workloadSnapshot(ArrayNode tasks, ArrayNode members, boolean unresolvedOnly) {
        Map<Integer,int[]> loads = new java.util.TreeMap<>();
        int taskCount = 0, totalHours = 0;
        for (JsonNode task : tasks) {
            boolean unresolved = task.path("_schedule_conflict").isObject();
            if (unresolved != unresolvedOnly) continue;
            int owner = unresolvedOnly ? task.path("model_owner_id").asInt(task.path("owner_id").asInt())
                    : task.path("owner_id").asInt();
            int start = unresolvedOnly ? task.path("model_week_start").asInt(task.path("week_start").asInt())
                    : task.path("week_start").asInt();
            int end = unresolvedOnly ? task.path("model_week_end").asInt(task.path("week_end").asInt(start))
                    : task.path("week_end").asInt(start);
            if (owner <= 0 || start <= 0 || end < start) continue;
            int hours = taskHours(task);
            addLoad(loads, owner, hours, start, end);
            taskCount++; totalHours += hours;
        }
        ArrayNode memberWeeks = mapper.createArrayNode(), overloads = mapper.createArrayNode();
        for (JsonNode member : members) {
            if (!isAssignableDeveloper(member)) continue;
            int id = member.path("id").asInt(), capacity = member.path("capacity_hours").asInt(40);
            int[] weekly = loads.getOrDefault(id, new int[6]);
            ObjectNode row = mapper.createObjectNode().put("memberId", id)
                    .put("memberName", member.path("name").asText()).put("capacityHours", capacity);
            ArrayNode weeks = row.putArray("weeklyHours");
            for (int week = 0; week < weekly.length; week++) {
                weeks.add(weekly[week]);
                if (weekly[week] > capacity) overloads.add(mapper.createObjectNode()
                        .put("memberId", id).put("memberName", member.path("name").asText())
                        .put("week", week + 1).put("hours", weekly[week]).put("capacityHours", capacity));
            }
            memberWeeks.add(row);
        }
        ObjectNode snapshot = mapper.createObjectNode().put("taskCount", taskCount).put("totalHours", totalHours)
                .put("population", unresolvedOnly ? "UNRESOLVED_MODEL_SCHEDULE_REFERENCE" : "FINAL_SCHEDULED_ONLY")
                .put("overloadCount", overloads.size());
        snapshot.set("memberWeeks", memberWeeks);
        snapshot.set("overloads", overloads);
        return snapshot;
    }

    private void legacyFinalSchedule(ArrayNode tasks, ArrayNode stories, ArrayNode sprints,
                                     ArrayNode members, ArrayNode ownerMoves, ArrayNode scheduleMoves,
                                     ArrayNode unresolved, ArrayNode warnings) {
        sortByStableId(tasks, "id");
        sortByStableId(members, "id");
        Map<Integer, int[]> loads = new HashMap<>();
        Map<String, ObjectNode> byId = uniqueObjectTaskMap(tasks, "deterministic final schedule");
        List<ObjectNode> ordered = new ArrayList<>();
        tasks.forEach(node -> ordered.add((ObjectNode) node));
        ordered.sort(Comparator.comparingInt((ObjectNode task) -> linkedStorySprint(task, stories, sprints))
                .thenComparing(ObjectNode::toString));
        for (ObjectNode task : ordered) {
            int oldOwner = task.path("owner_id").asInt();
            int oldStart = task.path("week_start").asInt(1), oldEnd = task.path("week_end").asInt(oldStart);
            boolean ownerFixed = task.path("_import_owner_explicit").asBoolean(false) || task.path("_user_owner_explicit").asBoolean(false);
            boolean scheduleFixed = task.path("_import_schedule_explicit").asBoolean(false) || task.path("_user_schedule_explicit").asBoolean(false);
            int[] range = legacySprintRangeForTask(task, stories, sprints);
            if (range == null) range = new int[]{1, Math.max(1, totalWeeks(sprints))};
            int duration = Math.max(0, oldEnd - oldStart);
            int bestWeek = range[0], bestOwner = oldOwner; double best = Double.POSITIVE_INFINITY;
            for (int week = range[0]; week + duration <= range[1]; week++) {
                int floor = range[0];
                for (String dep : task.path("depends_on").asText("").split(",")) {
                    ObjectNode predecessor = byId.get(dep.trim());
                    if (predecessor != null) floor = Math.max(floor, predecessor.path("week_end").asInt() + 1);
                }
                if (week < floor) continue;
                for (JsonNode member : members) {
                    int candidate = member.path("id").asInt();
                    if ((ownerFixed && candidate != oldOwner) || candidate <= 0
                            || Set.of("viewer", "readonly", "read_only").contains(member.path("role").asText().toLowerCase(Locale.ROOT))) continue;
                    int cap = Math.max(1, member.path("capacity_hours").asInt(40));
                    int projected = loads.getOrDefault(candidate, new int[6])[week - 1];
                    double role = responsibilityFit(task, member);
                    if (role < 0 || (scheduleFixed && week != oldStart)) continue;
                    double score = week * 1000.0 + projected * 10.0 / cap - role * 20.0 + (candidate == oldOwner ? -2 : 0);
                    if (score < best || (score == best && candidate < bestOwner)) { best = score; bestWeek = week; bestOwner = candidate; }
                }
            }
            if (best == Double.POSITIVE_INFINITY) {
                unresolved.add(task.path("id").asText() + " 无法在依赖与 Story Sprint 窗口内安排");
                warnings.add(task.path("id").asText() + " 存在 Sprint/依赖容量冲突");
                continue;
            }
            task.put("owner_id", bestOwner).put("week_start", bestWeek).put("week_end", bestWeek + duration);
            addLoad(loads, bestOwner, task.path("hours").asInt(), bestWeek, bestWeek + duration);
            if (bestOwner != oldOwner) ownerMoves.add(mapper.createObjectNode().put("taskId", task.path("id").asText()).put("from", oldOwner).put("to", bestOwner));
            if (bestWeek != oldStart || bestWeek + duration != oldEnd) scheduleMoves.add(mapper.createObjectNode().put("taskId", task.path("id").asText()).put("from", "W" + oldStart + "-W" + oldEnd).put("to", "W" + bestWeek + "-W" + (bestWeek + duration)));
        }
    }

    private void deterministicFinalSchedule(ArrayNode tasks, ArrayNode stories, ArrayNode sprints,
                                            ArrayNode members, ArrayNode ownerMoves, ArrayNode scheduleMoves,
                                            ArrayNode unresolved, ArrayNode warnings) {
        sortByStableId(tasks, "id");
        sortByStableId(members, "id");
        Map<Integer, int[]> loads = new HashMap<>();
        Map<String, ObjectNode> byId = uniqueObjectTaskMap(tasks, "deterministic final schedule");
        Map<String, List<ObjectNode>> coreSuccessors = coreSuccessors(byId);
        for (JsonNode node : tasks) preserveModelSchedule((ObjectNode) node);
        List<ObjectNode> ordered = stableDependencyOrder(tasks, stories, sprints, warnings, unresolved);
        for (ObjectNode task : ordered) {
            if (task.path("_schedule_conflict").isObject()) continue;
            int oldOwner = task.path("owner_id").asInt();
            int oldStart = task.path("week_start").asInt(1), oldEnd = task.path("week_end").asInt(oldStart);
            boolean ownerFixed = task.path("_import_owner_explicit").asBoolean(false) || task.path("_user_owner_explicit").asBoolean(false);
            boolean scheduleFixed = task.path("_import_schedule_explicit").asBoolean(false) || task.path("_user_schedule_explicit").asBoolean(false);
            int[] range = sprintRangeForTask(task, stories, sprints);
            if (range == null) range = new int[]{1, Math.max(1, totalWeeks(sprints))};
            // Model weeks are audit hints, not a duration constraint. Only explicit user/import
            // schedules retain their span; generated Tasks use the shortest capacity-feasible span.
            int duration = scheduleFixed ? Math.max(0, oldEnd - oldStart)
                    : deterministicMinimumDuration(task, members, ownerFixed ? oldOwner : null);
            int dependencyFloor = earliestDependencyWeek(task, byId, range[0]);
            boolean missingOrUnscheduledPredecessor = false;
            for (String dep : task.path("depends_on").asText("").split(",")) {
                if (dep.isBlank()) continue;
                ObjectNode predecessor = byId.get(dep.trim());
                if (predecessor == null || !predecessor.has("week_end")
                        || predecessor.path("_schedule_conflict").isObject()) {
                    missingOrUnscheduledPredecessor = true;
                    break;
                }
            }
            if (missingOrUnscheduledPredecessor || dependencyFloor + duration > range[1]) {
                markSchedulingConflict(task, "SPRINT_DEPENDENCY_CONFLICT",
                        "依赖最早开始周超出 Story Sprint 合法窗口");
                unresolved.add(task.path("id").asText() + " SPRINT_DEPENDENCY_CONFLICT");
                warnings.add(task.path("id").asText() + " 排期未解决：依赖与 Story Sprint 窗口冲突");
                continue;
            }
            if (scheduleFixed && (oldStart < dependencyFloor || oldStart < range[0]
                    || oldStart + duration > range[1])) {
                markSchedulingConflict(task, "SPRINT_DEPENDENCY_CONFLICT",
                        "显式排期与依赖最早周或 Story Sprint 合法窗口冲突", true);
                unresolved.add(task.path("id").asText() + " SPRINT_DEPENDENCY_CONFLICT");
                warnings.add(task.path("id").asText() + " 显式排期与依赖或 Sprint 窗口冲突，已保留并需人工处理");
                continue;
            }

            List<ScheduleChoice> choices = new ArrayList<>();
            boolean hasForwardFeasibleWeek = false;
            for (int week = dependencyFloor; week + duration <= range[1]; week++)
                if ((!scheduleFixed || week == oldStart)
                        && preservesDirectSuccessorWindow(task, week, duration, coreSuccessors, stories, sprints, members)) {
                    hasForwardFeasibleWeek = true;
                    break;
                }
            for (int week = dependencyFloor; week + duration <= range[1]; week++) {
                if (scheduleFixed && week != oldStart) continue;
                if (hasForwardFeasibleWeek
                        && !preservesDirectSuccessorWindow(task, week, duration, coreSuccessors, stories, sprints, members)) continue;
                for (JsonNode member : members) {
                    int candidate = member.path("id").asInt();
                    if (ownerFixed && candidate != oldOwner) continue;
                    if (candidate <= 0 || Set.of("viewer", "readonly", "read_only")
                            .contains(member.path("role").asText().toLowerCase(Locale.ROOT))) continue;
                    double role = responsibilityFit(task, member);
                    if (role < 0) continue;
                    int[] memberLoads = loads.getOrDefault(candidate, new int[6]);
                    int cap = Math.max(1, member.path("capacity_hours").asInt(40));
                    int maximumWeekOverload = 0;
                    double maximumWeekUtilization = 0;
                    for (int scheduledWeek = week; scheduledWeek <= week + duration; scheduledWeek++) {
                        int projectedWeek = memberLoads[scheduledWeek - 1]
                                + allocatedHoursForWeek(task, week, duration, scheduledWeek);
                        maximumWeekOverload = Math.max(maximumWeekOverload, Math.max(0, projectedWeek - cap));
                        maximumWeekUtilization = Math.max(maximumWeekUtilization, projectedWeek / (double) cap);
                    }
                    int projectedSprint = 0, totalLoad = 0;
                    int sprintStart = range[0], sprintEnd = range[1];
                    for (int w = 1; w <= 6; w++) {
                        totalLoad += memberLoads[w - 1];
                        if (w >= sprintStart && w <= sprintEnd) projectedSprint += memberLoads[w - 1];
                    }
                    projectedSprint += allocatedHoursInRange(task, week, duration, sprintStart, sprintEnd);
                    choices.add(new ScheduleChoice(candidate, week, duration, cap,
                            maximumWeekOverload, maximumWeekUtilization,
                            projectedSprint / (double) (cap * Math.max(1, sprintEnd - sprintStart + 1)),
                            (totalLoad + task.path("hours").asInt()) / (double) (cap * 6), role,
                            candidate == oldOwner, week == oldStart));
                }
            }
            if (choices.isEmpty()) {
                String code = "CAPACITY_OR_COMPATIBILITY_CONFLICT";
                markSchedulingConflict(task, code, "Story Sprint 窗口内没有符合硬约束的成员-周次组合",
                        scheduleFixed || ownerFixed);
                unresolved.add(task.path("id").asText() + " " + code);
                warnings.add(task.path("id").asText() + (scheduleFixed || ownerFixed
                        ? " 显式排期/负责人约束与依赖或 Sprint 窗口冲突，已保留并需人工处理"
                        : " 排期未解决：没有可用成员-周次组合"));
                continue;
            }
            boolean hasCapacityFeasibleChoice = choices.stream().anyMatch(choice -> choice.weekOverload() == 0);
            if (!hasCapacityFeasibleChoice) {
                markSchedulingConflict(task, "CAPACITY_CONFLICT",
                        "Story Sprint 窗口内所有兼容成员-周次组合都会超过周容量",
                        scheduleFixed || ownerFixed);
                unresolved.add(task.path("id").asText() + " CAPACITY_CONFLICT");
                warnings.add(task.path("id").asText() + " 排期未解决：所有可用成员-周次组合都会超过周容量");
                continue;
            }
            ScheduleChoice best = choices.stream()
                    .filter(choice -> choice.weekOverload() == 0)
                    .min(this::compareScheduleChoices).orElseThrow();
            task.remove("_capacity_conflict");
            int bestEnd = best.week() + duration;
            task.put("owner_id", best.ownerId()).put("week_start", best.week()).put("week_end", bestEnd);
            addLoad(loads, best.ownerId(), task.path("hours").asInt(), best.week(), bestEnd);
            if (best.ownerId() != oldOwner) ownerMoves.add(mapper.createObjectNode().put("taskId", task.path("id").asText()).put("from", oldOwner).put("to", best.ownerId()));
            if (best.week() != oldStart || bestEnd != oldEnd) scheduleMoves.add(mapper.createObjectNode().put("taskId", task.path("id").asText()).put("from", "W" + oldStart + "-W" + oldEnd).put("to", "W" + best.week() + "-W" + bestEnd));
        }
    }

    private int deterministicMinimumDuration(JsonNode task, ArrayNode members, Integer fixedOwner) {
        int bestWeeklyCapacity = 0;
        for (JsonNode member : members) {
            if (!isAssignableDeveloper(member)) continue;
            if (fixedOwner != null && member.path("id").asInt() != fixedOwner) continue;
            if (responsibilityFit(task, member) < 0) continue;
            bestWeeklyCapacity = Math.max(bestWeeklyCapacity, Math.max(0, member.path("capacity_hours").asInt()));
        }
        if (bestWeeklyCapacity <= 0) return 0;
        int weeks = Math.max(1, (taskHours(task) + bestWeeklyCapacity - 1) / bestWeeklyCapacity);
        return weeks - 1;
    }

    private Map<String, List<ObjectNode>> coreSuccessors(Map<String, ObjectNode> byId) {
        Map<String, List<ObjectNode>> result = new java.util.TreeMap<>(String.CASE_INSENSITIVE_ORDER);
        byId.keySet().forEach(id -> result.put(id, new ArrayList<>()));
        for (ObjectNode successor : byId.values()) {
            for (String predecessor : successor.path("depends_on").asText("").split(",")) {
                List<ObjectNode> rows = result.get(predecessor.trim());
                if (rows != null) rows.add(successor);
            }
        }
        result.values().forEach(rows -> rows.sort(Comparator.comparing(
                task -> task.path("id").asText(""), String.CASE_INSENSITIVE_ORDER)));
        return result;
    }

    /** One-hop deterministic forward checking. With closed week intervals a successor may
     * start in the predecessor's ending week, but enough room for its full duration must remain. */
    private boolean preservesDirectSuccessorWindow(ObjectNode task, int candidateStart, int duration,
                                                   Map<String, List<ObjectNode>> successors,
                                                   ArrayNode stories, ArrayNode sprints, ArrayNode members) {
        int candidateEnd = candidateStart + duration;
        for (ObjectNode successor : successors.getOrDefault(task.path("id").asText(), List.of())) {
            if (successor.path("_unresolved_story_relation").asBoolean()) continue;
            int[] successorRange = sprintRangeForTask(successor, stories, sprints);
            if (successorRange == null) continue;
            int originalStart = successor.path("week_start").asInt(successorRange[0]);
            int originalEnd = successor.path("week_end").asInt(originalStart);
            boolean successorScheduleFixed = successor.path("_import_schedule_explicit").asBoolean(false)
                    || successor.path("_user_schedule_explicit").asBoolean(false);
            boolean successorOwnerFixed = successor.path("_import_owner_explicit").asBoolean(false)
                    || successor.path("_user_owner_explicit").asBoolean(false);
            int successorDuration = successorScheduleFixed ? Math.max(0, originalEnd - originalStart)
                    : deterministicMinimumDuration(successor, members,
                    successorOwnerFixed ? successor.path("owner_id").asInt() : null);
            int latestStart = successorRange[1] - successorDuration;
            if (successor.path("_import_schedule_explicit").asBoolean(false)
                    || successor.path("_user_schedule_explicit").asBoolean(false))
                latestStart = Math.min(latestStart, originalStart);
            if (candidateEnd > latestStart) return false;
        }
        return true;
    }

    private record ScheduleChoice(int ownerId, int week, int duration, int capacity, int weekOverload,
                                  double weekUtilization, double sprintUtilization, double totalUtilization,
                                  double roleFit, boolean originalOwner, boolean originalWeek) {}

    private int compareScheduleChoices(ScheduleChoice left, ScheduleChoice right) {
        int c = Integer.compare(left.weekOverload(), right.weekOverload());
        if (c != 0) return c;
        c = Double.compare(left.weekUtilization(), right.weekUtilization()); if (c != 0) return c;
        c = Double.compare(left.sprintUtilization(), right.sprintUtilization()); if (c != 0) return c;
        c = Double.compare(left.totalUtilization(), right.totalUtilization()); if (c != 0) return c;
        c = Integer.compare(left.week(), right.week()); if (c != 0) return c;
        c = Double.compare(right.roleFit(), left.roleFit()); if (c != 0) return c;
        c = Boolean.compare(right.originalOwner(), left.originalOwner()); if (c != 0) return c;
        c = Boolean.compare(right.originalWeek(), left.originalWeek()); if (c != 0) return c;
        return Integer.compare(left.ownerId(), right.ownerId());
    }

    private List<ObjectNode> stableDependencyOrder(ArrayNode tasks, ArrayNode stories, ArrayNode sprints,
                                                   ArrayNode warnings, ArrayNode unresolved) {
        Comparator<ObjectNode> stableOrder = Comparator
                .comparingInt((ObjectNode task) -> stablePrimaryStorySprint(task, stories))
                .thenComparingInt(task -> priorityRank(task.path("priority").asText("")))
                .thenComparing(task -> task.path("id").asText(""), String.CASE_INSENSITIVE_ORDER);
        Map<String, ObjectNode> allTasks = uniqueObjectTaskMap(tasks, "dependency topological order");
        Map<String, ObjectNode> byId = new java.util.TreeMap<>(String.CASE_INSENSITIVE_ORDER);
        allTasks.forEach((id, task) -> { if (!task.path("_unresolved_story_relation").asBoolean()) byId.put(id, task); });
        Map<String, Integer> indegree = new java.util.TreeMap<>(String.CASE_INSENSITIVE_ORDER);
        Map<String, Set<String>> successors = new java.util.TreeMap<>(String.CASE_INSENSITIVE_ORDER);
        byId.keySet().forEach(id -> { indegree.put(id, 0); successors.put(id, new java.util.TreeSet<>(String.CASE_INSENSITIVE_ORDER)); });
        for (ObjectNode task : byId.values()) {
            for (String predecessorId : task.path("depends_on").asText("").split(",")) {
                String predecessor = predecessorId.trim();
                if (predecessor.isBlank() || !byId.containsKey(predecessor)) continue;
                if (successors.get(predecessor).add(task.path("id").asText()))
                    indegree.merge(task.path("id").asText(), 1, Integer::sum);
            }
        }
        PriorityQueue<ObjectNode> ready = new PriorityQueue<>(stableOrder);
        for (Map.Entry<String, Integer> entry : indegree.entrySet())
            if (entry.getValue() == 0) ready.add(byId.get(entry.getKey()));
        List<ObjectNode> ordered = new ArrayList<>();
        while (!ready.isEmpty()) {
            ObjectNode predecessor = ready.remove();
            ordered.add(predecessor);
            for (String successor : successors.get(predecessor.path("id").asText())) {
                int remaining = indegree.merge(successor, -1, Integer::sum);
                if (remaining == 0) ready.add(byId.get(successor));
            }
        }
        if (ordered.size() != byId.size()) {
            Set<String> scheduled = new HashSet<>();
            ordered.forEach(task -> scheduled.add(task.path("id").asText()));
            byId.values().stream().filter(task -> !scheduled.contains(task.path("id").asText()))
                    .sorted(stableOrder).forEach(task -> {
                        markSchedulingConflict(task, "DEPENDENCY_CYCLE", "依赖图在最终调度时仍存在环");
                        unresolved.add(task.path("id").asText() + " DEPENDENCY_CYCLE");
                        warnings.add(task.path("id").asText() + " 排期未解决：依赖图存在循环");
                    });
        }
        return ordered;
    }

    private int priorityRank(String priority) {
        return switch (priority.toLowerCase(Locale.ROOT)) {
            case "must", "critical", "high" -> 0;
            case "should", "medium" -> 1;
            case "could", "low" -> 2;
            default -> 3;
        };
    }

    private void markSchedulingConflict(ObjectNode task, String code, String message) {
        markSchedulingConflict(task, code, message, false);
    }

    private void markSchedulingConflict(ObjectNode task, String code, String message, boolean preserveExplicitSchedule) {
        preserveModelSchedule(task);
        if (!preserveExplicitSchedule) task.remove(List.of("owner_id", "week_start", "week_end"));
        task.set("_schedule_conflict", mapper.createObjectNode().put("code", code).put("message", message));
    }

    private void preserveModelSchedule(ObjectNode task) {
        if (task.has("owner_id") && !task.has("model_owner_id")) task.set("model_owner_id", task.path("owner_id").deepCopy());
        if (task.has("week_start") && !task.has("model_week_start")) task.set("model_week_start", task.path("week_start").deepCopy());
        if (task.has("week_end") && !task.has("model_week_end")) task.set("model_week_end", task.path("week_end").deepCopy());
    }

    private int allocatedHoursInRange(JsonNode task, int start, int duration, int rangeStart, int rangeEnd) {
        int total = 0;
        for (int week = start; week <= start + duration; week++)
            if (week >= rangeStart && week <= rangeEnd) total += allocatedHoursForWeek(task, start, duration, week);
        return total;
    }

    private int allocatedHoursForWeek(JsonNode task, int start, int duration, int week) {
        int span = Math.max(1, duration + 1), hours = Math.max(0, task.path("hours").asInt());
        int offset = week - start;
        return hours / span + (offset < hours % span ? 1 : 0);
    }

    private void sortByStableId(ArrayNode rows, String field) {
        List<JsonNode> sorted = new ArrayList<>();
        rows.forEach(sorted::add);
        sorted.sort(Comparator.comparing(node -> node.path(field).asText(""), String.CASE_INSENSITIVE_ORDER));
        rows.removeAll();
        sorted.forEach(rows::add);
    }

    private void rebalanceSprintCapacityByOwner(ArrayNode tasks, ArrayNode stories, ArrayNode sprints,
                                                ArrayNode members, ArrayNode ownerMoves, ArrayNode loadMoves,
                                                ArrayNode unresolved, ArrayNode warnings) {
        Map<Integer, int[]> loads = weeklyLoads(tasks);
        for (JsonNode node : tasks) {
            ObjectNode task = (ObjectNode) node;
            if (task.path("_import_owner_explicit").asBoolean(false) || task.path("_user_owner_explicit").asBoolean(false)) continue;
            int owner = task.path("owner_id").asInt(), start = task.path("week_start").asInt(), end = task.path("week_end").asInt();
            int[] range = sprintRangeForTask(task, stories, sprints);
            if (range == null) continue;
            int sprintTotal = 0;
            for (int w = range[0]; w <= range[1]; w++) sprintTotal += loads.getOrDefault(owner, new int[6])[w - 1];
            User current = users.selectById(owner);
            if (current == null || sprintTotal <= current.getCapacityHours() * (range[1] - range[0] + 1)) continue;
            int best = owner; double bestScore = Double.MAX_VALUE;
            for (JsonNode member : members) {
                int candidate = member.path("id").asInt();
                if (candidate <= 0 || candidate == owner || users.selectById(candidate) == null
                        || Set.of("viewer", "readonly", "read_only").contains(member.path("role").asText())) continue;
                int projected = 0;
                for (int w = range[0]; w <= range[1]; w++) projected += loads.getOrDefault(candidate, new int[6])[w - 1];
                int capacity = Math.max(1, member.path("capacity_hours").asInt(40)) * (range[1] - range[0] + 1);
                int hours = Math.max(0, task.path("hours").asInt());
                boolean preferred = sameResponsibility(task, candidate, tasks);
                double roleFit = responsibilityFit(task, member);
                if (roleFit < 0) continue;
                double currentRisk = Math.max(0, sprintTotal - current.getCapacityHours() * (range[1] - range[0] + 1));
                double projectedRisk = Math.max(0, projected + hours - capacity);
                double score = projectedRisk * 4.0 + (double) (projected + hours) / capacity
                        - (preferred ? 0.20 : 0) - roleFit;
                if (score < bestScore) { bestScore = score; best = candidate; }
            }
            if (best != owner) {
                task.put("owner_id", best);
                loads = weeklyLoads(tasks);
                ownerMoves.add(mapper.createObjectNode().put("taskId", task.path("id").asText()).put("from", owner).put("to", best));
                loadMoves.add(task.path("id").asText() + " Sprint 容量再平衡");
            }
        }
    }

    private void logStage(String stage, ArrayNode stories, ArrayNode tasks, ArrayNode members) {
        if (!log.isDebugEnabled()) return;
        int orphanTasks = 0; Set<String> covered = new HashSet<>();
        for (JsonNode task : tasks) {
            boolean linked = false;
            for (String ref : task.path("story_ref").asText("").split(","))
                if (find(stories, ref.trim()) != null) { linked = true; covered.add(ref.trim()); }
            if (!linked) orphanTasks++;
        }
        log.debug("project generation stage={} stories={} tasks={} orphanTasks={} uncoveredStories={} members={}",
                stage, stories.size(), tasks.size(), orphanTasks, Math.max(0, stories.size() - covered.size()), members.size());
    }

    /** Returns a soft role/skill preference; only viewers and explicit incompatibilities are hard exclusions. */
    private double responsibilityFit(JsonNode task, JsonNode member) {
        String required = (task.path("required_role").asText("") + " " + task.path("owner_role").asText(""))
                .toLowerCase(Locale.ROOT).trim();
        String role = member.path("role").asText("").toLowerCase(Locale.ROOT).trim();
        if (Set.of("viewer", "readonly", "read_only").contains(role)) return -1;
        if (required.isBlank()) return 0.05;
        if (role.contains(required) || required.contains(role)) return 0.30;
        if (containsAny(required, "backend", "后端", "接口", "服务") && containsAny(role, "backend", "后端", "fullstack", "全栈")) return 0.18;
        if (containsAny(required, "frontend", "前端", "页面", "ui") && containsAny(role, "frontend", "前端", "fullstack", "全栈")) return 0.18;
        if (containsAny(required, "test", "测试", "qa") && containsAny(role, "test", "测试", "qa", "backend", "后端")) return 0.12;
        return 0.02;
    }

    private double storySprintStageWeight(JsonNode task) {
        String text = (task.path("name").asText("") + " " + task.path("description").asText("") + " " + task.path("task_type").asText(""))
                .toLowerCase(Locale.ROOT);
        if (containsAny(text, "测试", "回归", "缺陷", "验收", "部署", "发布", "上线", "联调", "集成", "test", "acceptance", "deploy", "integration")) return 0.30;
        if (containsAny(text, "初始化", "环境", "数据库设计", "基础框架", "基础服务", "数据模型", "foundation", "setup")) return 0.55;
        return 1.0;
    }

    private boolean coreSprintDominates(String storyId, ArrayNode tasks, ArrayNode sprints) {
        double core = 0, support = 0;
        for (JsonNode task : tasks) {
            boolean linked = false;
            for (String ref : task.path("story_ref").asText("").split(",")) if (storyId.equals(ref.trim())) linked = true;
            if (!linked) continue;
            double weight = storySprintStageWeight(task);
            if (weight >= 0.9) core += task.path("hours").asDouble(1) * weight;
            else support += task.path("hours").asDouble(1) * weight;
        }
        return core >= support && core > 0;
    }

    private void normalizeGeneratedPriorities(ArrayNode stories, ArrayNode warnings, ArrayNode autoCompleted) {
        int should = 0;
        for (JsonNode story : stories) if ("Should".equalsIgnoreCase(story.path("priority").asText(""))) should++;
        if (stories.isEmpty() || should * 100 <= stories.size() * 80) return;
        int changed = 0;
        for (JsonNode node : stories) {
            ObjectNode story = (ObjectNode) node;
            String text = (story.path("title").asText("") + " " + story.path("description").asText("") + " " + story.path("acceptance").asText("")).toLowerCase(Locale.ROOT);
            String target = priorityForGeneratedStory(text);
            if (!target.equalsIgnoreCase(story.path("priority").asText(""))) {
                story.put("priority", target); changed++;
            }
        }
        if (changed > 0) {
            String message = "AI Story 优先级过度集中，已按通用业务闭环语义重新分布 Must/Should/Could";
            warnings.add(message); autoCompleted.add(message);
        }
    }

    private String priorityForGeneratedStory(String text) {
        if (containsAny(text, "登录", "注册", "权限", "角色", "创建", "发布", "报名申请", "提交申请", "审批", "签到", "签退", "服务时长", "核心流程")) return "Must";
        if (containsAny(text, "导出", "高级分析", "趋势", "性能", "优化", "帮助", "部署", "发布准备")) return "Could";
        return "Should";
    }

    private int linkedStorySprint(JsonNode task,ArrayNode stories,ArrayNode sprints){
        int linkedSprint = primaryStorySprint(task, stories);
        if (linkedSprint > 0) return linkedSprint;
        int week=task.path("week_start").asInt(1);
        for(JsonNode sprint:sprints)if(week>=sprint.path("week_start").asInt()&&week<=sprint.path("week_end").asInt())return sprint.path("sprint").asInt(1);
        return 1;
    }

    private int semanticLinkScore(JsonNode story, JsonNode task) {
        String storyText=(story.path("title").asText("")+" "+story.path("description").asText("")+" "+story.path("acceptance").asText("")).toLowerCase(Locale.ROOT);
        String taskText=(task.path("name").asText("")+" "+task.path("description").asText("")).toLowerCase(Locale.ROOT);
        int score=0;
        for(String keyword:List.of("活动","报名","签到","签退","状态","模型","规则","评价","评分","反馈","消息","通知","统计","导出","归档","权限","登录","数据库","测试","异常","公告","帮助","移动端","接口","页面","联调","审核","审批","申请","服务时长","目录","版本","实体","关系","用户信息","角色","搜索","筛选","首页","基础配置","核心表"))
            if(storyText.contains(keyword)&&taskText.contains(keyword)) score+=2;
        Set<String> storyChars=new HashSet<>(), taskChars=new HashSet<>();
        for(int i=0;i+1<storyText.length();i++) storyChars.add(storyText.substring(i,i+2));
        for(int i=0;i+1<taskText.length();i++) taskChars.add(taskText.substring(i,i+2));
        storyChars.retainAll(taskChars); score+=Math.min(4,storyChars.size()/2);
        return score;
    }

    private int suggestGeneratedOwner(ArrayNode members,ArrayNode tasks,int week){
        int best=members.isEmpty()?1:members.get(0).path("id").asInt(1); double bestRatio=Double.MAX_VALUE;
        Map<Integer,int[]> loads=new HashMap<>();
        tasks.forEach(task->addLoad(loads,task.path("owner_id").asInt(),task.path("hours").asInt(),
                task.path("week_start").asInt(),task.path("week_end").asInt()));
        for(JsonNode member:members){ if(Set.of("viewer","readonly","read_only").contains(member.path("role").asText())) continue;
            int id=member.path("id").asInt(), capacity=Math.max(1,member.path("capacity_hours").asInt(40));
            double ratio=(loads.getOrDefault(id,new int[6])[Math.max(1,Math.min(6,week))-1]+8.0)/capacity;
            if(ratio<bestRatio){bestRatio=ratio;best=id;} }
        return best;
    }
    private void completeGeneratedMilestones(ArrayNode milestones,ArrayNode tasks,ArrayNode warnings,
                                             ArrayNode autoCompleted){
        if(tasks.isEmpty())return;
        Set<String> used=new HashSet<>();
        for(JsonNode milestone:milestones){
            if(!milestone.path("related_task_ids").asText("").isBlank())continue;
            int week=milestone.path("week").asInt(0);
            ObjectNode best=null;int bestDistance=Integer.MAX_VALUE;
            for(JsonNode node:tasks){
                String taskId=node.path("id").asText();
                int distance=week>0?Math.abs(node.path("week_end").asInt()-week):0;
                if((best==null||distance<bestDistance)&&!used.contains(taskId)){best=(ObjectNode)node;bestDistance=distance;}
            }
            if(best==null)best=(ObjectNode)tasks.get(0);
            String taskId=best.path("id").asText();
            ((ObjectNode)milestone).put("related_task_ids",taskId);used.add(taskId);
            String milestoneId=milestone.path("id").asText(milestone.path("name").asText("Milestone"));
            String message="已自动将 "+milestoneId+" 关联到 "+taskId;
            warnings.add(message);autoCompleted.add(message);
        }
    }
    private List<String> dependencyRefs(JsonNode source){
        List<String> result=new ArrayList<>();
        for(String key:List.of("depends_on","dependsOn","dependencies","weak_dependencies","weakDependencies"))
            addDependencyValues(source.get(key),result);
        return result;
    }
    private void addDependencyValues(JsonNode value,List<String> result){
        if(value==null||value.isNull())return;
        if(value.isArray()){value.forEach(v->addDependencyValues(v,result));return;}
        if(value.isObject()){
            String id=firstText(value,"id","task_id","from","predecessor");
            if(!id.isBlank())result.add(id);
            return;
        }
        if(value.isTextual())for(String part:value.asText().split("[,，;；\\s]+"))if(!part.isBlank())result.add(part);
    }
    private String firstText(JsonNode node,String...keys){for(String key:keys){JsonNode value=node.get(key);if(value!=null&&value.isValueNode()&&!value.isNull()&&!value.asText().isBlank())return value.asText().trim();}return "";}
    private void inferDependencies(ArrayNode rows,ArrayNode warnings){
        Map<String,List<ObjectNode>> byStory=new LinkedHashMap<>();
        for(JsonNode node:rows){ObjectNode task=(ObjectNode)node;if(task.path("_unresolved_story_relation").asBoolean())continue;for(String story:task.path("story_ref").asText("").split(","))if(!story.isBlank())byStory.computeIfAbsent(story,k->new ArrayList<>()).add(task);}
        for(List<ObjectNode> group:byStory.values()){
            for(ObjectNode task:group){
                if(!task.path("depends_on").asText("").isBlank())continue;
                int targetStage=taskStage(task.path("name").asText(""));
                if(targetStage<=0)continue;
                ObjectNode best=null;int bestStage=-1;
                for(ObjectNode candidate:group){
                    if(candidate==task)continue;
                    int candidateStage=taskStage(candidate.path("name").asText(""));
                    if(candidateStage>=0&&candidateStage<targetStage&&candidateStage>bestStage){best=candidate;bestStage=candidateStage;}
                }
                if (best != null && dependencyEvidence(task, best)) {
                    task.put("depends_on",best.path("id").asText());
                    warnings.add("为 "+task.path("id").asText()+" 自动补充了推断依赖 "+best.path("id").asText());
                }
            }
        }
    }
    private int taskStage(String name){
        String text=name.toLowerCase(Locale.ROOT);
        if(containsAny(text,"测试","验收","回归","qa","test"))return 4;
        if(containsAny(text,"联调","集成","integration","端到端"))return 3;
        if(containsAny(text,"数据库","数据模型","表结构","接口设计","api设计","架构","原型","方案设计"))return 0;
        if(containsAny(text,"前端","页面","界面","ui","看板","可视化","接入"))return 2;
        if(containsAny(text,"后端","服务","接口","api","controller","service"))return 1;
        return -1;
    }
    private boolean dependencyEvidence(JsonNode successor, JsonNode predecessor) {
        String next = successor.path("name").asText("").toLowerCase(Locale.ROOT);
        String prior = predecessor.path("name").asText("").toLowerCase(Locale.ROOT);
        boolean explicit = containsAny(next, "完成后", "基于", "之后", "完成接口", "接口完成")
                || (containsAny(next, "联调", "集成", "测试", "验收") && containsAny(prior, "数据库", "框架", "接口", "服务", "后端"))
                || (containsAny(prior, "数据库", "数据模型", "表结构", "基础框架") && containsAny(next, "后端", "服务", "接口"))
                || (containsAny(prior, "后端", "服务", "接口") && containsAny(next, "前端", "页面", "联调", "测试"));
        return explicit || (taskStage(next) > taskStage(prior) && taskStage(prior) >= 0
                && containsAny(next, "联调", "集成", "测试", "验收"));
    }
    private ArrayNode summarizeGeneratedWarnings(ArrayNode base, ArrayNode details, int dependencyBefore, ArrayNode autoCompleted, JsonNode draftRelationStats) {
        ArrayNode visibleBase = mapper.createArrayNode();
        for (JsonNode item : base) {
            String text = item.asText("");
            if (text.contains("重复依赖") || text.contains("已删除 ") || text.contains("关联到")
                    || text.contains("自动补充了推断依赖")) continue;
            visibleBase.add(item);
        }
        ArrayNode output = deduplicateWarnings(visibleBase);
        Set<String> relationMessages = new HashSet<>();
        int links = 0, addedTasks = 0, dependencies = 0, duplicateDependencies = 0, scheduleAdjustments = 0, ownerFallbacks = 0;
        for (JsonNode item : autoCompleted) {
            String text = item.asText("");
            if (text.contains("关联到") && relationMessages.add(text)) links++;
            if (text.contains("新增语义补全 Task")) addedTasks++;
        }
        for (JsonNode item : details) {
            String text = item.asText("");
            if (text.contains("自动补充了推断依赖")) dependencies++;
            if (text.contains("重复依赖")) duplicateDependencies++;
            if (isScheduleAdjustment(text)) scheduleAdjustments++;
            if (text.contains("没有符合角色与容量要求的候选负责人")) ownerFallbacks++;
        }
        for (JsonNode item : details) {
            String text = item.asText("");
            if (!text.contains("自动补充了推断依赖") && !text.contains("关联到")
                    && !text.contains("重复依赖") && !text.contains("已删除 ")
                    && !text.contains("新增语义补全 Task") && !text.startsWith("待补充：")
                    && !isScheduleAdjustment(text) && !text.contains("没有符合角色与容量要求的候选负责人")) output.add(text);
        }
        // Relation summaries use the final unique delta, not the number of
        // processing messages accumulated across phases.
        int actualNewRelations = 0;
        if (draftRelationStats != null) actualNewRelations = draftRelationStats.path("actualNewUniqueRelationCount").asInt(0);
        if (actualNewRelations > 0) output.add("已自动补全 " + actualNewRelations + " 个 Story-Task 关系");
        if (addedTasks > 0) output.add("已新增 " + addedTasks + " 个必要补全 Task");
        if (dependencies > 0) output.add("已补全 " + dependencies + " 条高置信依赖");
        if (duplicateDependencies > 0) output.add("已清理 " + duplicateDependencies + " 条重复依赖");
        if (scheduleAdjustments > 0) output.add("已自动调整 " + scheduleAdjustments + " 个 Task 的执行周次");
        if (ownerFallbacks > 0) output.add("有 " + ownerFallbacks + " 个 Task 未找到更合适的候选负责人，已保留原负责人");
        return deduplicateWarnings(output);
    }

    /** Backward-compatible reflective/test overload. */
    @SuppressWarnings("unused")
    private ArrayNode summarizeGeneratedWarnings(ArrayNode base, ArrayNode details, int dependencyBefore, ArrayNode autoCompleted) {
        return summarizeGeneratedWarnings(base, details, dependencyBefore, autoCompleted, null);
    }

    private int uniqueRelationCount(ArrayNode stories, ArrayNode tasks) {
        Set<String> storyIds = new HashSet<>();
        stories.forEach(story -> storyIds.add(story.path("id").asText().trim().toUpperCase(Locale.ROOT)));
        Set<String> relations = new HashSet<>();
        tasks.forEach(task -> {
            String taskId = task.path("id").asText("").trim().toUpperCase(Locale.ROOT);
            for (String ref : task.path("story_ref").asText("").split(",")) {
                String storyId = ref.trim().toUpperCase(Locale.ROOT);
                if (!taskId.isBlank() && storyIds.contains(storyId)) relations.add(taskId + "|" + storyId);
            }
        });
        return relations.size();
    }
    private boolean isScheduleAdjustment(String text) {
        return text.matches(".*T\\d+.*(已按依赖及项目周期调整到|已调整到|执行周次调整到).*W\\d+.*");
    }
    private boolean containsAny(String value,String...terms){for(String term:terms)if(value.contains(term))return true;return false;}
    private String cleanDependencyRefs(List<String> values,Map<String,String> ids,String currentId,
                                       ArrayNode warnings,ObjectNode repairs,boolean userExplicit){
        LinkedHashSet<String> normalized=new LinkedHashSet<>();
        Set<String> seenInput=new HashSet<>();
        for(String value:values){
            String raw=value==null?"":value.trim();
            String key=raw.toUpperCase(Locale.ROOT);
            if(key.isBlank())continue;
            String mapped=ids.get(key);
            String duplicateKey=mapped==null?key:mapped;
            if(!seenInput.add(duplicateKey)){
                increment(repairs,"removedDuplicateDependencies");
                warnings.add("已删除 "+currentId+" 的重复依赖 "+raw);
                continue;
            }
            if(mapped==null){
                increment(repairs,"removedInvalidDependencies");
                warnings.add("已删除 "+currentId+" 引用的不存在任务 "+raw+(userExplicit?"（Excel/CSV 显式依赖）":""));
                continue;
            }
            if(mapped.equals(currentId)){
                increment(repairs,"removedSelfDependencies");
                warnings.add("已删除 "+currentId+" 对自身的非法依赖"+(userExplicit?"（Excel/CSV 显式依赖）":""));
                continue;
            }
            normalized.add(mapped);
        }
        return String.join(",",normalized);
    }
    private void increment(ObjectNode counters,String field){counters.put(field,counters.path(field).asInt()+1);}
    private void repairDependencyCycles(ArrayNode rows,Set<String> protectedTasks,ArrayNode warnings,ObjectNode repairs){
        Map<String,Integer> order=new HashMap<>();
        for(int i=0;i<rows.size();i++)order.put(rows.get(i).path("id").asText(),i);
        for(int pass=0;pass<rows.size()*rows.size();pass++){
            List<String> cycle=findDependencyCycle(rows);
            if(cycle.isEmpty())return;
            String successor=null,predecessor=null;int latest=-1;
            for(int i=0;i<cycle.size()-1;i++){
                String task=cycle.get(i),dependency=cycle.get(i+1);
                if(protectedTasks.contains(task))continue;
                int position=order.getOrDefault(task,-1);
                if(position>=latest){latest=position;successor=task;predecessor=dependency;}
            }
            if(successor==null){
                warnings.add("检测到用户显式依赖循环，未自动改写："+String.join(" -> ",cycle));
                return;
            }
            ObjectNode task=(ObjectNode)find(rows,successor);
            if(task==null)return;
            String removed=predecessor;
            String cleaned=java.util.Arrays.stream(task.path("depends_on").asText("").split(","))
                    .map(String::trim).filter(v->!v.isBlank()&&!v.equals(removed)).distinct()
                    .collect(java.util.stream.Collectors.joining(","));
            task.put("depends_on",cleaned);
            increment(repairs,"dependencyCycleRepairs");
            warnings.add("为修复依赖循环，已删除 "+removed+" -> "+successor+"（优先删除后生成的 AI 依赖边）");
        }
    }
    private List<String> findDependencyCycle(ArrayNode rows){
        Map<String,List<String>> deps=new LinkedHashMap<>();
        rows.forEach(t->deps.put(t.path("id").asText(),java.util.Arrays.stream(t.path("depends_on").asText("").split(","))
                .map(String::trim).filter(v->!v.isBlank()).toList()));
        Set<String> visited=new HashSet<>();LinkedHashSet<String> active=new LinkedHashSet<>();
        for(String id:deps.keySet()){List<String> cycle=findDependencyCycle(id,deps,visited,active);if(!cycle.isEmpty())return cycle;}
        return List.of();
    }
    private List<String> findDependencyCycle(String current,Map<String,List<String>> deps,Set<String> visited,LinkedHashSet<String> active){
        if(active.contains(current)){
            List<String> path=new ArrayList<>(active);int start=path.indexOf(current);
            List<String> cycle=new ArrayList<>(path.subList(start,path.size()));cycle.add(current);return cycle;
        }
        if(!visited.add(current))return List.of();
        active.add(current);
        for(String dependency:deps.getOrDefault(current,List.of())){
            List<String> cycle=findDependencyCycle(dependency,deps,visited,active);if(!cycle.isEmpty())return cycle;
        }
        active.remove(current);return List.of();
    }
    private void validateDependencyGraph(JsonNode rows,boolean execution){
        Set<String> ids=new HashSet<>();rows.forEach(t->ids.add(t.path("id").asText()));
        for(JsonNode task:rows){
            String id=task.path("id").asText();Set<String> seen=new HashSet<>();
            for(String dependency:task.path("depends_on").asText("").split(",")){
                if(dependency.isBlank())continue;
                if(!seen.add(dependency))throw dependencyError("任务依赖重复: "+id+" -> "+dependency,execution);
                if(id.equals(dependency))throw dependencyError("任务不能依赖自身: "+id,execution);
                if(!ids.contains(dependency))throw dependencyError("任务依赖不存在: "+id+" -> "+dependency,execution);
            }
        }
        detectCycles(rows,execution);
    }
    private int totalWeeks(JsonNode sprints) {
        int last = 0;
        for (JsonNode sprint : sprints) last = Math.max(last, sprint.path("week_end").asInt(0));
        return last > 0 ? last : 6;
    }

    private void repairDependencySchedule(ArrayNode rows, int totalWeeks, ArrayNode warnings) {
        Map<String, ObjectNode> byId = new HashMap<>();
        rows.forEach(n -> byId.put(n.path("id").asText(), (ObjectNode) n));
        Set<String> completed = new HashSet<>();
        for (JsonNode node : rows) scheduleAfterDependencies((ObjectNode) node, byId, completed, totalWeeks, warnings);
    }

    private void scheduleAfterDependencies(ObjectNode task, Map<String, ObjectNode> byId, Set<String> completed,
                                           int totalWeeks, ArrayNode warnings) {
        String id = task.path("id").asText();
        if (completed.contains(id)) return;
        int required = 1;
        for (String ref : task.path("depends_on").asText("").split(",")) {
            ObjectNode predecessor = byId.get(ref);
            if (predecessor == null) continue;
            scheduleAfterDependencies(predecessor, byId, completed, totalWeeks, warnings);
            required = Math.max(required, predecessor.path("week_end").asInt());
        }
        int start = task.path("week_start").asInt(), end = task.path("week_end").asInt();
        if (task.path("_import_schedule_explicit").asBoolean(false)
                || task.path("_user_schedule_explicit").asBoolean(false)) {
            if (start < required || end > totalWeeks)
                warnings.add(id + " 的用户排期与依赖或项目周期冲突，已保留原值，需人工处理");
        } else {
            int nextStart = earliestDependencyWeek(task, byId, Math.max(start, required));
            int nextEnd = nextStart + end - start;
            if (nextEnd > totalWeeks) {
                // First close any gap before the predecessor, then shorten only the AI-owned duration.
                nextStart = required;
                nextEnd = Math.min(totalWeeks, nextStart + end - start);
            }
            if (nextStart > totalWeeks || nextEnd < nextStart)
                throw ApiException.badRequest("依赖链无法排入当前项目周期：" + id + " 需要在 W" + required + " 之后，项目仅到 W" + totalWeeks);
            if (start != nextStart || end != nextEnd) {
                task.put("week_start", nextStart);
                task.put("week_end", nextEnd);
                warnings.add(id + " 已按依赖及项目周期调整到 W" + nextStart + "-W" + nextEnd);
            }
        }
        completed.add(id);
    }
    private void validateDependencySchedule(JsonNode rows, boolean execution) {
        Map<String, JsonNode> byId = new HashMap<>();
        rows.forEach(task -> byId.put(task.path("id").asText(), task));
        for (JsonNode task : rows) for (String dependency : task.path("depends_on").asText("").split(",")) {
            if (dependency.isBlank()) continue;
            JsonNode predecessor = byId.get(dependency);
            if (predecessor == null || !dependencyTimingConflict(task, predecessor)) continue;
            if (!execution && (task.path("_import_schedule_explicit").asBoolean(false)
                    || task.path("_user_schedule_explicit").asBoolean(false))) continue;
            throw dependencyError(task.path("id").asText()+" 的开始周早于前置任务 "+dependency+" 的结束周", execution);
        }
    }

    private boolean dependencyTimingConflict(JsonNode task, JsonNode predecessor) {
        return task.has("week_start") && predecessor.has("week_end")
                && task.path("week_start").asInt() < predecessor.path("week_end").asInt();
    }

    private int earliestDependencyWeek(JsonNode task, Map<String, ObjectNode> byId, int storySprintStart) {
        int earliest = storySprintStart;
        for (String ref : task.path("depends_on").asText("").split(",")) {
            if (ref.isBlank()) continue;
            ObjectNode predecessor = byId.get(ref.trim());
            if (predecessor != null && predecessor.has("week_end"))
                earliest = Math.max(earliest, predecessor.path("week_end").asInt());
        }
        return earliest;
    }
    private ArrayNode warnings(ObjectNode risks){ArrayNode a=mapper.createArrayNode(); risks.fields().forEachRemaining(e->e.getValue().forEach(n->a.add(n.path("message").asText()))); return a;}
    private void detectCycles(JsonNode rows,boolean execution){Map<String,String> deps=new HashMap<>(); rows.forEach(t->deps.put(t.path("id").asText(),t.path("depends_on").asText(""))); for(String id:deps.keySet()) if(cycle(id,id,deps,new HashSet<>())) throw dependencyError("任务依赖存在循环: "+id,execution);}
    private boolean cycle(String current,String target,Map<String,String> deps,Set<String> seen){for(String d:deps.getOrDefault(current,"").split(",")){if(d.isBlank())continue; if(d.equals(target))return true; if(seen.add(d)&&cycle(d,target,deps,seen))return true;}return false;}
    private ApiException dependencyError(String message,boolean execution){return execution?ApiException.conflict(message):ApiException.badRequest(message);}
    private String refs(List<String> values,Map<String,String> ids,String currentId){LinkedHashSet<String> normalized=new LinkedHashSet<>();for(String value:values){String key=value.trim().toUpperCase(Locale.ROOT);if(key.isBlank())continue;String mapped=ids.get(key);if(mapped==null)throw ApiException.badRequest("引用不存在: "+value.trim());if(mapped.equals(currentId))throw ApiException.badRequest("任务不能依赖自身: "+currentId);if(!normalized.add(mapped))throw ApiException.badRequest("任务依赖重复: "+currentId+" -> "+mapped);}return String.join(",",normalized);}
    private String refs(String value,Map<String,String> ids){LinkedHashSet<String> out=new LinkedHashSet<>(); for(String p:value.split("[,，;；\\s]+")){String key=p.trim().toUpperCase(Locale.ROOT);if(key.isBlank())continue; String mapped=ids.get(key); if(mapped==null) throw ApiException.badRequest("引用不存在: "+p.trim()); out.add(mapped);}return String.join(",",out);}
    private String refsIgnoringUnknown(String value,Map<String,String> ids){
        LinkedHashSet<String> out=new LinkedHashSet<>();
        for(String token:referenceTokens(value)){
            String key=token.trim().toUpperCase(Locale.ROOT);
            String mapped=ids.get(key);
            if(mapped!=null) out.add(mapped);
        }
        return String.join(",",out);
    }
    private String unknownStoryRefs(String value,Map<String,String> ids){return unknownReferences(value,ids);}
    private String unknownReferences(String value,Map<String,String> ids){
        LinkedHashSet<String> unknown=new LinkedHashSet<>();
        for(String token:referenceTokens(value)){
            String key=token.trim().toUpperCase(Locale.ROOT);
            if(ids.containsKey(key) || ids.containsKey(canonicalTitle(token))) continue;
            unknown.add(token.trim());
        }
        return String.join(",",unknown);
    }
    private List<String> referenceTokens(String value){
        if(value==null||value.isBlank()) return List.of();
        Matcher matcher=Pattern.compile("(?i)(?:US|T)\\d+").matcher(value);
        LinkedHashSet<String> codes=new LinkedHashSet<>();
        while(matcher.find()) codes.add(matcher.group().toUpperCase(Locale.ROOT));
        if(!codes.isEmpty()) return new ArrayList<>(codes);
        return java.util.Arrays.stream(value.split("[,，;；\\s]+"))
                .map(String::trim).filter(v->!v.isBlank()).distinct().toList();
    }
    private boolean hasInvalidImportWeek(JsonNode source,int weekLimit){
        JsonNode start=source.get("week_start"), end=source.get("week_end");
        if(start!=null && start.isIntegralNumber() && (start.asInt()<1 || start.asInt()>weekLimit)) return true;
        if(end!=null && end.isIntegralNumber() && (end.asInt()<1 || end.asInt()>weekLimit)) return true;
        return start!=null && end!=null && start.isIntegralNumber() && end.isIntegralNumber()
                && start.asInt()>end.asInt();
    }
    private String text(JsonNode n,String key,String fallback){JsonNode v=n.get(key);return v==null||v.isNull()?fallback:v.asText(fallback).trim();}
    private int boundedInt(JsonNode n,String key,int fallback,int min,int max){JsonNode v=n.get(key);int x=v!=null&&v.isIntegralNumber()?v.asInt():fallback;return Math.max(min,Math.min(max,x));}
    private Integer nullableInt(JsonNode n,String... keys){for(String k:keys){JsonNode v=n.get(k);if(v!=null&&!v.isNull()&&v.isIntegralNumber())return v.asInt();}return null;}
    private String priority(String p){return Set.of("Must","Should","Could").contains(p)?p:"Should";}

    private String inferImportedPriority(String title, String description, String acceptance) {
        String text = (title + " " + description + " " + acceptance).toLowerCase(Locale.ROOT);
        if (containsAnyPriority(text, "注册", "登录", "权限", "角色", "申请", "审批", "借用", "预约", "下单", "支付",
                "基础信息", "分类", "维护", "新增", "创建", "归还", "提交", "核心流程")) return "Must";
        if (containsAnyPriority(text, "查询", "搜索", "查看", "提醒", "通知", "消息", "记录", "基础统计", "列表", "详情", "状态")) return "Should";
        if (containsAnyPriority(text, "高级统计", "数据分析", "利用率", "导出", "报表", "归档", "性能", "优化", "大屏", "仪表盘", "发布", "部署", "帮助")) return "Could";
        return "Should";
    }

    private boolean containsAnyPriority(String text, String... terms) {
        for (String term : terms) if (text.contains(term)) return true;
        return false;
    }
    private String referenceText(JsonNode source) {
        if (source.path(TaskStoryReferences.AUDIT).isArray()) return TaskStoryReferences.text(source);
        LinkedHashSet<String> values = new LinkedHashSet<>();
        for (String key : List.of("story_ref", "story_refs", "story", "storyCode", "story_code")) {
            JsonNode value = source.path(key);
            if (value.isArray()) value.forEach(v -> values.add(v.asText()));
            else if (value.isTextual()) for (String part : value.asText().split("[,，;；]")) if (!part.isBlank()) values.add(part.trim());
        }
        return String.join(",", values);
    }

    private String splitImportedLabel(String value, String prefix) {
        String text = value == null ? "" : value.trim();
        java.util.regex.Matcher matcher = Pattern.compile("^" + prefix + "\\d+\\s*(?:[-:：、.]\\s*|\\s+)(.+)$", Pattern.CASE_INSENSITIVE).matcher(text);
        return matcher.matches() ? matcher.group(1).trim() : text;
    }

    private String normalizeStoryRefs(String value, Map<String,String> storyIds) {
        LinkedHashSet<String> normalized = new LinkedHashSet<>();
        for (String part : (value == null ? "" : value).split("[,，;；\\s]+")) {
            String token = part.trim();
            if (token.isBlank()) continue;
            java.util.regex.Matcher code = Pattern.compile("^(US\\d+)", Pattern.CASE_INSENSITIVE).matcher(token);
            String key = code.find() ? code.group(1).toUpperCase(Locale.ROOT) : token.toUpperCase(Locale.ROOT);
            String mapped = storyIds.get(key);
            if (mapped == null) {
                String canonical = canonicalTitle(token);
                mapped = storyIds.get(canonical);
            }
            if (mapped != null) normalized.add(mapped);
        }
        return String.join(",", normalized);
    }

    private String canonicalTitle(String value) {
        return value == null ? "" : value.toLowerCase(Locale.ROOT).replaceAll("[\\p{P}\\p{Z}\\s]+", "");
    }
    private Integer suggestOwner(JsonNode source, ArrayNode candidates, Map<Integer, Integer> loads) {
        String name = firstText(source, "owner_name", "owner");
        for (JsonNode member : candidates) if (!name.isBlank() && name.equals(member.path("name").asText())) return member.path("id").asInt();
        // Never invent a technical speciality from permission roles such as admin/member.
        String responsibility = firstText(source, "owner_role", "responsibility").toLowerCase(Locale.ROOT);
        JsonNode best = null; double bestScore = Double.MAX_VALUE;
        for (JsonNode member : candidates) {
            if (Set.of("viewer", "readonly", "read_only").contains(member.path("role").asText())) continue;
            double score = (double) loads.getOrDefault(member.path("id").asInt(), 0) / Math.max(1, member.path("capacity_hours").asInt(40));
            if (!responsibility.isBlank() && member.path("role").asText("").toLowerCase(Locale.ROOT).contains(responsibility)) score -= 100;
            if (score < bestScore) { best = member; bestScore = score; }
        }
        if (best == null) throw ApiException.badRequest("当前没有可分配开发成员");
        return best.path("id").asInt();
    }
    private ArrayNode array(JsonNode n,String key){return n.path(key).isArray()?(ArrayNode)n.path(key):mapper.createArrayNode();}
    private ObjectNode object(JsonNode n,String key){return n.path(key).isObject()?(ObjectNode)n.path(key):mapper.createObjectNode();}
    private int firstMemberId(String projectId){
        User u;
        if (isNewScope(projectId)) u = users.selectList(new QueryWrapper<User>().orderByAsc("id")).stream().findFirst().orElse(null);
        else if (projectMembers == null) u = users.selectList(new QueryWrapper<User>().orderByAsc("id")).stream().findFirst().orElse(null);
        else u = projectMembers.selectList(new QueryWrapper<com.aicap.entity.ProjectMember>().eq("project_id", projectId).orderByAsc("user_id").last("LIMIT 1")).stream().map(member -> users.selectById(member.getUserId())).filter(java.util.Objects::nonNull).findFirst().orElse(null);
        if(u==null)throw ApiException.badRequest("当前没有可分配成员");return u.getId();
    }
    private int nextNumber(java.util.List<String> ids,Pattern pattern){int max=0;for(String id:ids){Matcher m=pattern.matcher(id==null?"":id);if(m.matches())max=Math.max(max,Integer.parseInt(m.group(1)));}return max+1;}
    private boolean existingStory(String projectId, String id) { return resolver == null ? stories.selectOne(new QueryWrapper<Story>().eq("project_id", projectId).eq("code", id)) != null : resolver.findStory(projectId, id) != null; }
    private boolean existingTask(String projectId, String id) { return resolver == null ? tasks.selectOne(new QueryWrapper<Task>().eq("project_id", projectId).eq("code", id)) != null : resolver.findTask(projectId, id) != null; }
    private boolean isNewScope(String projectId) { return projectId != null && projectId.startsWith("NEW-"); }
}
