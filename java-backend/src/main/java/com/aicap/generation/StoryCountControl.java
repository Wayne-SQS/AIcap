package com.aicap.generation;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.databind.node.TextNode;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Bounds explicitly requested Story counts before scheduling and validation. */
final class StoryCountControl {
    private static final String SUBJECT = "(?:用户故事|Story|stories)";
    private static final Pattern COUNT = Pattern.compile("(?i)(?:" + SUBJECT + ")[\\p{IsHan}\\s]{0,18}?(至少|不少于|最多|不超过|约|大约)?\\s*(\\d{1,3})(?:\\s*[-~至到—]\\s*(\\d{1,3}))?\\s*条\\s*(左右|上下)?|(至少|不少于|最多|不超过|约|大约)?\\s*(\\d{1,3})(?:\\s*[-~至到—]\\s*(\\d{1,3}))?\\s*条\\s*(左右|上下)?\\s*(?:的)?\\s*(?:" + SUBJECT + ")");
    private static final Pattern HARD_RANGE = Pattern.compile("(?i)(?:必须|严格|恰好)?\\s*(?:控制在|范围(?:为|是)|限定为)\\s*(\\d{1,3})\\s*[-~至到—]\\s*(\\d{1,3})\\s*条");
    private static final Pattern IMPLIED_SOFT_COUNT = Pattern.compile("(?:希望|尽量|最好|目标|约|大约)\\D{0,8}?(\\d{1,3})\\s*条(?:\\s*(?:左右|上下))?|(\\d{1,3})\\s*条\\s*(?:左右|上下)");
    private static final Pattern PUNCTUATION = Pattern.compile("[\\p{P}\\p{Z}\\s]+");
    private static final Pattern ACTION = Pattern.compile("登录|权限校验|提交|取消|确认|拒绝|创建|新增|修改|编辑|删除|查看|查询|浏览|导出|统计|提醒|通知|处理|维护|记录|测试|验收");
    private static final Pattern HARD_WORDING = Pattern.compile("必须|严格|恰好|不得|至少|不少于|最多|不超过|范围(?:为|是)?|控制在");
    private static final Pattern SOFT_WORDING = Pattern.compile("约|大约|左右|上下|希望|尽量|最好|目标");

    enum ConstraintType { SOFT_TARGET, HARD_CONSTRAINT }
    record Limit(int target, int minimum, int maximum, ConstraintType constraintType) {
        Limit(int target, int minimum, int maximum) { this(target, minimum, maximum, ConstraintType.HARD_CONSTRAINT); }
        boolean hard() { return constraintType == ConstraintType.HARD_CONSTRAINT; }
        String hint() { return hard()
                ? "Story硬约束 " + minimum + "-" + maximum + " 条"
                : "Story软目标约 " + target + " 条，推荐 " + minimum + "-" + maximum + " 条"; }
    }
    record Result(int before, int after, int merged, int duplicates, List<String> notes) {
        Result followedBy(Result next) {
            List<String> combined = new ArrayList<>(notes);
            combined.addAll(next.notes());
            return new Result(Math.max(before, next.before()), next.after(), merged + next.merged(), duplicates + next.duplicates(), combined);
        }
        void append(ObjectNode report, Limit limit) {
            if (limit == null) return;
            report.put("requestedStoryTarget", limit.target());
            report.put("constraintType", limit.constraintType().name());
            report.put("preferredMin", limit.minimum());
            report.put("preferredMax", limit.maximum());
            report.put("requestedStoryMinimum", limit.minimum());
            report.put("requestedStoryMaximum", limit.maximum());
            report.put("actualStoryCount", after);
            report.put("storyCountDeviation", after - limit.target());
            report.put("storyCountBeforeCompression", before);
            report.put("storyCountAfterCompression", after);
            report.put("mergedStoryCount", merged);
            report.put("removedDuplicateStoryCount", duplicates);
            ArrayNode messages = report.putArray("storyCompressionNotes");
            notes.forEach(messages::add);
            if (!limit.hard() && (after < limit.minimum() || after > limit.maximum())) {
                String direction = after > limit.maximum() ? "高于" : "低于";
                String warning = "Story 数量 " + after + "，" + direction + "用户期望约 " + limit.target()
                        + " 条；已去除 " + duplicates + " 条完全重复 Story，并因保留独立业务功能未继续压缩";
                report.put("storyCountWarning", warning);
                ArrayNode warnings = report.withArray("warnings");
                boolean exists = false;
                for (JsonNode item : warnings) if (warning.equals(item.asText())) { exists = true; break; }
                if (!exists) warnings.add(warning);
            } else report.putNull("storyCountWarning");
        }
    }

    static Limit parse(String request) {
        if (request == null) return null;
        Matcher hardRange = HARD_RANGE.matcher(request);
        if (hardRange.find()) {
            int minimum = Integer.parseInt(hardRange.group(1)), maximum = Integer.parseInt(hardRange.group(2));
            if (minimum >= 1 && maximum >= minimum && maximum <= 100)
                return new Limit((minimum + maximum) / 2, minimum, maximum, ConstraintType.HARD_CONSTRAINT);
        }
        Matcher matcher = COUNT.matcher(request);
        while (matcher.find()) {
            boolean subjectFirst = matcher.group(2) != null;
            String qualifier = subjectFirst ? matcher.group(1) : matcher.group(5);
            if (subjectFirst && qualifier == null) {
                String prefix = matcher.group().substring(0, matcher.start(2) - matcher.start());
                for (String word : List.of("不少于", "不超过", "大约", "至少", "最多", "约"))
                    if (prefix.endsWith(word)) { qualifier = word; break; }
            }
            int number = Integer.parseInt(subjectFirst ? matcher.group(2) : matcher.group(6));
            String upper = subjectFirst ? matcher.group(3) : matcher.group(7);
            String approximate = subjectFirst ? matcher.group(4) : matcher.group(8);
            if (number < 1 || number > 100) continue;
            String matched = matcher.group();
            if (upper != null) {
                int max = Integer.parseInt(upper);
                if (max >= number && max <= 100) return new Limit((number + max) / 2, number, max, ConstraintType.HARD_CONSTRAINT);
            }
            if ("至少".equals(qualifier) || "不少于".equals(qualifier)) return new Limit(number, number, 100, ConstraintType.HARD_CONSTRAINT);
            if ("最多".equals(qualifier) || "不超过".equals(qualifier)) return new Limit(number, 1, number, ConstraintType.HARD_CONSTRAINT);
            boolean hard = HARD_WORDING.matcher(matched).find();
            boolean soft = SOFT_WORDING.matcher(matched).find() || qualifier != null || approximate != null;
            if (soft && !hard) {
                int tolerance = Math.max(2, Math.round(number * 0.07f));
                return new Limit(number, Math.max(1, number - tolerance), Math.min(100, number + tolerance), ConstraintType.SOFT_TARGET);
            }
            return new Limit(number, number, number, ConstraintType.HARD_CONSTRAINT);
        }
        Matcher implied = IMPLIED_SOFT_COUNT.matcher(request);
        if (implied.find()) {
            int number = Integer.parseInt(implied.group(1) != null ? implied.group(1) : implied.group(2));
            if (number >= 1 && number <= 100) {
                int tolerance = Math.max(2, Math.round(number * 0.07f));
                return new Limit(number, Math.max(1, number - tolerance), Math.min(100, number + tolerance), ConstraintType.SOFT_TARGET);
            }
        }
        return null;
    }

    static Result compress(ObjectNode raw, Limit limit, String request, Set<String> importedIds) {
        ArrayNode stories = raw.withArray("stories");
        int before = stories.size();
        if (limit == null) return new Result(before, before, 0, 0, List.of());
        Set<String> protectedIds = new HashSet<>(importedIds);
        for (JsonNode story : stories) {
            String title = story.path("title").asText(story.path("name").asText(""));
            if (request != null && title.length() >= 4 && request.contains(title)) protectedIds.add(id(story));
        }
        Map<String, Integer> linked = new HashMap<>();
        for (JsonNode task : raw.path("tasks")) for (String ref : task.path("story_ref").asText("").split(","))
            linked.merge(ref.trim(), 1, Integer::sum);
        Map<String, String> aliases = new HashMap<>();
        List<String> notes = new ArrayList<>();
        int duplicates = 0;
        // Re-evaluate after each merge; functional area and Sprint are never proof of equivalence.
        while (!stories.isEmpty() && (!limit.hard() || stories.size() > limit.minimum())) {
            List<Candidate> candidates = new ArrayList<>();
            for (int i = 0; i < stories.size(); i++) {
                JsonNode source = stories.get(i);
                if (protectedIds.contains(id(source))) continue;
                for (int j = 0; j < stories.size(); j++) {
                    if (i == j) continue;
                    JsonNode target = stories.get(j);
                    String a = canonical(source.path("title").asText(source.path("name").asText("")));
                    String b = canonical(target.path("title").asText(target.path("name").asText("")));
                    boolean duplicate = !a.isEmpty() && a.equals(b)
                            && equivalentEvidence(source, target, 0.95);
                    boolean highlySimilar = stories.size() > limit.maximum()
                            && sameAction(source, target) && similarity(a, b) >= 0.80
                            && hasComparableEvidence(source, target)
                            && equivalentEvidence(source, target, 0.85);
                    if (!duplicate && !highlySimilar) continue;
                    int score = (duplicate ? 1000 : 0) + (int) (similarity(a, b) * 150)
                            + (protectedIds.contains(id(target)) ? 30 : 0)
                            + linked.getOrDefault(id(target), 0) * 4 - linked.getOrDefault(id(source), 0) * 4
                            - ("Must".equalsIgnoreCase(source.path("priority").asText()) ? 40 : 0);
                    candidates.add(new Candidate(i, j, score, duplicate));
                }
            }
            if (candidates.isEmpty()) {
                if (stories.size() > limit.maximum()) notes.add("仍有 " + stories.size() + " 条 Story：没有语义高度重合的安全合并对象，需人工审查");
                break;
            }
            Candidate chosen = candidates.stream().max(Comparator.comparingInt(Candidate::score)).orElseThrow();
            ObjectNode source = (ObjectNode) stories.get(chosen.source());
            ObjectNode target = (ObjectNode) stories.get(chosen.target());
            String oldId = id(source), keptId = id(target);
            merge(target, source);
            aliases.put(oldId, keptId);
            linked.merge(keptId, linked.getOrDefault(oldId, 0), Integer::sum);
            if (chosen.duplicate()) duplicates++;
            notes.add(oldId + " 合并至 " + keptId + (chosen.duplicate() ? "（完全重复）" : "（业务动作与验收高度重合）"));
            stories.remove(chosen.source());
        }
        remap(raw, aliases);
        if (before > limit.maximum() && stories.size() > limit.maximum())
            notes.add("超出目标范围的独立需求未强制删除，避免丢失功能域");
        return new Result(before, stories.size(), before - stories.size(), duplicates, notes);
    }

    /** Final detailed-mode bound: keep semantic information while preventing an
     * approximate request from growing without limit when no near-duplicate pair exists. */
    static Result enforceMaximum(ObjectNode raw, Limit limit, Set<String> importedIds) {
        if (limit == null || limit.hard() || raw.path("stories").size() <= limit.maximum())
            return new Result(raw.path("stories").size(), raw.path("stories").size(), 0, 0, List.of());
        ArrayNode stories = raw.withArray("stories");
        int before = stories.size(), merged = 0;
        List<String> notes = new ArrayList<>();
        Set<String> protectedIds = new HashSet<>(importedIds);
        while (stories.size() > limit.maximum()) {
            Map<String,Integer> linked = new HashMap<>();
            for (JsonNode task : raw.path("tasks")) for (String ref : task.path("story_ref").asText("").split(","))
                if (!ref.isBlank()) linked.merge(ref.trim(), 1, Integer::sum);
            Candidate best = null;
            for (int i = 0; i < stories.size(); i++) for (int j = 0; j < stories.size(); j++) {
                if (i == j) continue;
                JsonNode a = stories.get(i), b = stories.get(j);
                if (protectedIds.contains(id(a)) && protectedIds.contains(id(b))) continue;
                String at = canonical(a.path("title").asText(a.path("name").asText("")));
                String bt = canonical(b.path("title").asText(b.path("name").asText("")));
                int score = similarity(at, bt) * 1000 > 0 ? (int)(similarity(at, bt) * 1000) : 0;
                if (a.path("activity").asText("").equals(b.path("activity").asText(""))) score += 120;
                if (a.path("sprint").asInt() == b.path("sprint").asInt()) score += 40;
                score += Math.min(20, linked.getOrDefault(id(a), 0) + linked.getOrDefault(id(b), 0));
                if (best == null || score > best.score()) best = new Candidate(i, j, score, false);
            }
            if (best == null) break;
            ObjectNode source = (ObjectNode) stories.get(best.source());
            ObjectNode target = (ObjectNode) stories.get(best.target());
            String oldId = id(source), keptId = id(target);
            merge(target, source);
            remap(raw, Map.of(oldId, keptId));
            stories.remove(best.source());
            merged++;
            notes.add(oldId + " 合并至 " + keptId + "（最终数量收口）");
        }
        return new Result(before, stories.size(), merged, 0, notes);
    }

    static void requireValidCount(ObjectNode raw, Limit limit, Set<String> importedIds) {
        if (limit == null || !limit.hard()) return;
        int count = raw.path("stories").size();
        if (count < limit.minimum() || (count > limit.maximum() && importedIds.isEmpty()))
            throw new IllegalStateException("Story 数量 " + count + " 不在用户要求的 "
                    + limit.minimum() + "-" + limit.maximum() + " 条范围内；需补全独立功能或人工审查，不能进入待确认");
        Map<String, Integer> taskCounts = new HashMap<>();
        int totalTasks = raw.path("tasks").size();
        for (JsonNode task : raw.path("tasks")) for (String ref : task.path("story_ref").asText("").split(","))
            if (!ref.isBlank()) taskCounts.merge(ref.trim(), 1, Integer::sum);
        int threshold = Math.max(8, (totalTasks + 3) / 4);
        for (Map.Entry<String, Integer> entry : taskCounts.entrySet())
            if (entry.getValue() > threshold)
                throw new IllegalStateException("Story " + entry.getKey() + " 关联 " + entry.getValue()
                        + " 个 Task，超过规划一致性阈值 " + threshold + "；需人工审查，不能进入待确认");
    }

    private record Candidate(int source, int target, int score, boolean duplicate) {}
    private static String id(JsonNode story) { return story.path("id").asText(story.path("code").asText("")); }
    private static String canonical(String value) { return PUNCTUATION.matcher(value.toLowerCase(Locale.ROOT)).replaceAll(""); }
    private static boolean hasComparableEvidence(JsonNode left, JsonNode right) {
        for (String field : List.of("description", "acceptance"))
            if (!canonical(left.path(field).asText("")).isEmpty()
                    && !canonical(right.path(field).asText("")).isEmpty()) return true;
        return false;
    }
    private static boolean equivalentEvidence(JsonNode left, JsonNode right, double threshold) {
        for (String field : List.of("description", "acceptance")) {
            String a = canonical(left.path(field).asText("")), b = canonical(right.path(field).asText(""));
            if (a.isEmpty() != b.isEmpty() || (!a.isEmpty() && similarity(a, b) < threshold)) return false;
        }
        return true;
    }
    private static boolean sameAction(JsonNode left, JsonNode right) {
        String a = action(left.path("title").asText(left.path("name").asText("")));
        String b = action(right.path("title").asText(right.path("name").asText("")));
        return !a.isEmpty() && a.equals(b);
    }
    private static String action(String title) {
        Matcher match = ACTION.matcher(title);
        if (!match.find()) return "";
        return switch (match.group()) { case "查询", "浏览" -> "查看"; case "新增" -> "创建"; case "编辑" -> "修改"; case "通知" -> "提醒"; default -> match.group(); };
    }
    private static double similarity(String a, String b) {
        if (a.isEmpty() || b.isEmpty()) return 0;
        if (a.equals(b)) return 1;
        if (a.length() < 3 || b.length() < 3) return 0;
        if (a.contains(b) || b.contains(a)) return (double) Math.min(a.length(), b.length()) / Math.max(a.length(), b.length());
        Set<String> grams = new HashSet<>();
        for (int i = 0; i < a.length() - 1; i++) grams.add(a.substring(i, i + 2));
        int overlap = 0;
        for (int i = 0; i < b.length() - 1; i++) if (grams.contains(b.substring(i, i + 2))) overlap++;
        return 2d * overlap / (a.length() + b.length() - 2);
    }
    private static void merge(ObjectNode target, ObjectNode source) {
        for (String field : List.of("description", "acceptance")) {
            String previous = target.path(field).asText("").trim();
            String addition = source.path(field).asText("").trim();
            if ("description".equals(field) && !target.path("title").asText().equals(source.path("title").asText()))
                addition = source.path("title").asText() + (addition.isEmpty() ? "" : "：" + addition);
            if (!addition.isEmpty() && !previous.contains(addition)) target.put(field, previous.isEmpty() ? addition : previous + "；" + addition);
        }
        if (rank(source.path("priority").asText()) > rank(target.path("priority").asText()))
            target.set("priority", source.path("priority").deepCopy());
        if (target.path("owner_id").isNull() || target.path("owner_id").isMissingNode())
            if (source.hasNonNull("owner_id")) target.set("owner_id", source.path("owner_id").deepCopy());
    }
    private static int rank(String priority) { return switch (priority.toLowerCase(Locale.ROOT)) { case "must" -> 3; case "should" -> 2; case "could" -> 1; default -> 0; }; }
    private static void remap(ObjectNode raw, Map<String, String> aliases) {
        for (String key : List.of("tasks", "milestones")) for (JsonNode row : raw.path(key)) {
            if (!(row instanceof ObjectNode object)) continue;
            if (raw.path(TaskStoryReferences.MARKER).asBoolean(false)) TaskStoryReferences.remapAudit(object, aliases);
            for (String field : raw.path(TaskStoryReferences.MARKER).asBoolean(false)
                    ? List.of("story_ref", "story_refs", "storyCode") : List.of("story_ref", "story_refs")) {
                JsonNode refs = object.get(field);
                if (refs == null) continue;
                if (refs.isArray()) {
                    JsonNode original = refs.deepCopy();
                    ArrayNode updated = ((ArrayNode) refs).removeAll();
                    Set<String> seen = new HashSet<>();
                    for (JsonNode ref : original) {
                        String resolved = resolve(ref.asText(), aliases);
                        if (seen.add(resolved)) updated.add(resolved);
                    }
                } else if (refs.isTextual()) {
                    Set<String> updated = new java.util.LinkedHashSet<>();
                    for (String ref : refs.asText().split(",")) if (!ref.isBlank()) updated.add(resolve(ref.trim(), aliases));
                    object.set(field, TextNode.valueOf(String.join(",", updated)));
                }
            }
        }
    }
    private static String resolve(String ref, Map<String, String> aliases) {
        Set<String> visited = new HashSet<>();
        while (aliases.containsKey(ref) && visited.add(ref)) ref = aliases.get(ref);
        return ref;
    }
}
