package com.aicap.generation;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/** Builds project-specific Epic domains from the project context and Story corpus. */
final class ProjectEpicPlanner {
    private static final List<String> GENERIC_NAMES = List.of(
            "进入与组织项目", "梳理需求", "安排与执行", "观察与协同", "复盘改进");
    private static final Set<String> STOP_WORDS = Set.of(
            "the", "and", "for", "with", "from", "into", "that", "this", "用户", "系统", "功能",
            "实现", "开发", "设计", "管理", "支持", "进行", "完成", "一个", "以及", "通过", "可以", "需要",
            "实现了", "能够", "查看", "新增", "创建", "维护", "使用");
    private static final Pattern TOKEN_SPLIT = Pattern.compile("[^\\p{L}\\p{N}]+|(?<=[a-z])(?=[A-Z])");
    private static final List<Domain> IMPORT_DOMAINS = List.of(
            // Do not use the generic word “用户” as the sole signal: it is
            // present in almost every natural-language Story description and
            // would collapse the whole corpus into the identity domain.
            new Domain("用户与权限管理", "账号", "注册", "登录", "权限", "角色", "认证", "账户", "身份", "user", "account", "login", "role", "permission", "auth"),
            new Domain("基础信息与资源管理", "基础信息", "分类", "档案", "目录", "库存", "设备", "商品", "资源", "资料", "信息维护", "活动信息", "catalog", "inventory", "asset", "profile"),
            new Domain("核心业务流程", "申请", "报名", "审批", "审核", "预约", "借用", "订单", "办理", "出库", "归还", "支付", "进度", "冲突", "活动", "参与", "签到", "签退", "服务", "时长", "记录", "状态", "流程", "apply", "approval", "booking", "order", "return", "workflow"),
            new Domain("维护与异常管理", "维修", "维护", "损坏", "报废", "异常", "超期", "故障", "取消", "申诉", "缺陷", "纠错", "repair", "maintenance", "exception", "fault"),
            new Domain("通知与协同管理", "通知", "提醒", "消息", "公告", "沟通", "反馈", "评价", "评分", "协同", "notification", "message", "reminder", "feedback"),
            new Domain("统计与系统管理", "统计", "分析", "报表", "导出", "日志", "审计", "安全", "配置", "测试", "验收", "部署", "发布", "后台", "优化", "帮助", "report", "analytics", "audit", "config", "test", "deploy")
    );
    /** Generic semantic vocabulary used only for detailed natural-language drafts. */
    private static final List<Domain> GENERATED_DOMAINS = List.of(
            new Domain("用户与权限管理", "注册", "登录", "权限", "角色", "账号", "账户", "认证", "身份", "个人信息", "个人中心", "user", "account", "login", "role", "permission", "auth"),
            new Domain("业务资源与活动管理", "活动", "创建", "发布", "编辑", "状态", "列表", "详情", "搜索", "筛选", "取消", "资源", "目录", "booking", "event"),
            new Domain("申请与审核流程", "报名", "报名状态", "报名申请", "申请", "审核", "审批", "通过", "拒绝", "进度", "预约", "申请记录", "apply", "approval"),
            new Domain("签到与服务记录", "签到", "签退", "签到管理", "签退管理", "服务时长", "累计时长", "考勤", "出勤", "工时", "attendance", "timesheet"),
            new Domain("通知与评价协同", "消息", "通知", "提醒", "公告", "评价", "活动评价", "评分", "反馈", "沟通", "协同", "notification", "message", "feedback"),
            new Domain("统计与后台管理", "统计", "数据统计", "分析", "报表", "导出", "数据", "后台", "管理员", "用户管理", "系统管理", "系统测试", "测试", "验收", "report", "analytics", "admin", "test"),
            new Domain("异常与系统支撑", "异常", "异常处理", "重复", "冲突", "缺陷", "缺陷修复", "纠错", "故障", "错误", "部署", "发布支撑", "系统支撑", "exception", "fault", "deploy")
    );

    private ProjectEpicPlanner() { }

    static void normalize(ObjectNode raw, ObjectMapper mapper) {
        ArrayNode stories = raw.path("stories").isArray() ? (ArrayNode) raw.path("stories") : mapper.createArrayNode();
        List<String> supplied = suppliedNames(raw);
        boolean generic = supplied.isEmpty() || isGenericTemplate(supplied) || supplied.size() <= 1
                || assignmentsCollapsed(stories, supplied);
        List<Cluster> clusters = buildClusters(raw, stories, supplied, generic);
        if (clusters.isEmpty()) clusters = List.of(new Cluster(projectFallback(raw), new ArrayList<>()));

        ArrayNode activities = mapper.createArrayNode();
        ArrayNode epics = mapper.createArrayNode();
        Map<JsonNode, String> assignments = new HashMap<>();
        for (int i = 0; i < clusters.size(); i++) {
            Cluster cluster = clusters.get(i);
            String activity = "A" + (i + 1);
            activities.add(mapper.createObjectNode().put("id", i + 1).put("code", activity).put("name", cluster.name()));
            epics.add(mapper.createObjectNode().put("id", "E" + (i + 1)).put("title", cluster.name()).put("activity", i + 1));
            for (JsonNode story : cluster.stories()) assignments.put(story, String.valueOf(i + 1));
        }
        for (JsonNode story : stories) {
            ObjectNode node = (ObjectNode) story;
            node.put("activity", assignments.getOrDefault(story, String.valueOf(nearestCluster(story, clusters))));
        }
        raw.set("activities", activities);
        raw.set("epics", epics);
    }

    /**
     * Import-only activity completion. Imported rows normally have no activity
     * column; cluster the existing Story corpus without changing Story content.
     */
    static void normalizeImportedActivities(ObjectNode raw, ObjectMapper mapper) {
        if (hasSuppliedActivities(raw)) return;
        normalizeImportedDomains(raw, mapper);
    }

    private static void normalizeImportedDomains(ObjectNode raw, ObjectMapper mapper) {
        ArrayNode stories = raw.path("stories").isArray() ? (ArrayNode) raw.path("stories") : mapper.createArrayNode();
        if (stories.isEmpty()) return;
        Map<Domain, List<JsonNode>> grouped = new LinkedHashMap<>();
        IMPORT_DOMAINS.forEach(domain -> grouped.put(domain, new ArrayList<>()));
        for (JsonNode story : stories) {
            String text = storyText(story).toLowerCase(Locale.ROOT);
            Domain best = IMPORT_DOMAINS.stream()
                    .max(Comparator.comparingInt(domain -> domain.score(text)))
                    .orElse(IMPORT_DOMAINS.get(1));
            if (best.score(text) == 0) {
                best = grouped.entrySet().stream().min(Comparator.comparingInt(entry -> entry.getValue().size()))
                        .map(Map.Entry::getKey).orElse(IMPORT_DOMAINS.get(1));
            }
            grouped.get(best).add(story);
        }
        List<Map.Entry<Domain, List<JsonNode>>> populated = grouped.entrySet().stream()
                .filter(entry -> !entry.getValue().isEmpty()).toList();
        ArrayNode activities = mapper.createArrayNode();
        ArrayNode epics = mapper.createArrayNode();
        for (Map.Entry<Domain, List<JsonNode>> entry : populated) {
            int activity = activities.size() + 1;
            activities.add(mapper.createObjectNode().put("id", activity).put("name", entry.getKey().name()));
            epics.add(mapper.createObjectNode().put("id", "E" + activity).put("title", entry.getKey().name()).put("activity", activity));
            entry.getValue().forEach(story -> ((ObjectNode) story).put("activity", activity));
        }
        raw.set("activities", activities);
        raw.set("epics", epics);
    }

    private static void normalize(ObjectNode raw, ObjectMapper mapper, int minimum, int maximum) {
        ArrayNode stories = raw.path("stories").isArray() ? (ArrayNode) raw.path("stories") : mapper.createArrayNode();
        List<JsonNode> corpus = new ArrayList<>();
        stories.forEach(corpus::add);
        if (corpus.isEmpty()) return;
        int desired = Math.max(minimum, Math.min(maximum, Math.max(minimum, (int) Math.round(Math.sqrt(corpus.size()) * 1.5))));
        desired = Math.min(desired, corpus.size());
        List<Cluster> clusters = seedClusters(raw, corpus, desired);
        int seeded = Math.min(desired, corpus.size());
        for (int i = 0; i < seeded; i++) clusters.get(i).stories().add(corpus.get(i));
        for (JsonNode story : corpus.subList(seeded, corpus.size())) {
            Cluster best = clusters.stream().max(Comparator.comparingInt(c -> similarity(storyText(story), c.signature()))).orElse(clusters.get(0));
            best.stories().add(story);
        }
        ArrayNode activities = mapper.createArrayNode();
        ArrayNode epics = mapper.createArrayNode();
        for (int i = 0; i < clusters.size(); i++) {
            Cluster cluster = clusters.get(i);
            if (cluster.stories().isEmpty()) continue;
            int activity = activities.size() + 1;
            String name = nameCluster(cluster, raw);
            activities.add(mapper.createObjectNode().put("id", activity).put("name", name));
            epics.add(mapper.createObjectNode().put("id", "E" + activity).put("title", name).put("activity", activity));
            for (JsonNode story : cluster.stories()) ((ObjectNode) story).put("activity", activity);
        }
        raw.set("activities", activities);
        raw.set("epics", epics);
    }

    private static boolean hasSuppliedActivities(ObjectNode raw) {
        if (raw.path("activities").isArray() && raw.path("activities").size() > 0) return true;
        if (raw.path("epics").isArray() && raw.path("epics").size() > 0) return true;
        if (raw.path("stories").isArray()) {
            for (JsonNode story : raw.path("stories")) {
                if (story.path("_import_activity_explicit").asBoolean(false)) return true;
            }
        }
        return false;
    }

    private static List<Cluster> buildClusters(ObjectNode raw, ArrayNode stories, List<String> supplied, boolean generic) {
        // Natural-language drafts frequently contain a single placeholder Activity.
        // Use the same semantic domain vocabulary as import clustering so Chinese
        // titles form populated business domains instead of one similarity bucket.
        if (generic) {
            Map<Domain, List<JsonNode>> grouped = new LinkedHashMap<>();
            GENERATED_DOMAINS.forEach(domain -> grouped.put(domain, new ArrayList<>()));
            for (JsonNode story : stories) {
                String text = storyText(story).toLowerCase(Locale.ROOT);
                Domain best = GENERATED_DOMAINS.stream().max(Comparator.comparingInt(d -> d.score(text))).orElse(GENERATED_DOMAINS.get(0));
                if (best.score(text) == 0) {
                    best = grouped.entrySet().stream().min(Comparator.comparingInt(e -> e.getValue().size()))
                            .map(Map.Entry::getKey).orElse(GENERATED_DOMAINS.get(0));
                }
                grouped.get(best).add(story);
            }
            List<Cluster> semantic = grouped.entrySet().stream().filter(e -> !e.getValue().isEmpty())
                    .map(e -> new Cluster(e.getKey().name(), e.getValue())).toList();
            if (semantic.size() >= 2) return semantic;
        }
        if (!generic && !supplied.isEmpty()) {
            Map<String, Cluster> byName = new LinkedHashMap<>();
            for (JsonNode story : stories) {
                String label = story.path("epic").asText("").trim();
                if (label.isBlank()) label = supplied.get(Math.max(0, Math.min(supplied.size() - 1, story.path("activity").asInt(1) - 1)));
                final String groupName = label;
                byName.computeIfAbsent(groupName, ignored -> new Cluster(groupName, new ArrayList<>())).stories().add(story);
            }
            if (!byName.isEmpty()) return new ArrayList<>(byName.values()).subList(0, Math.min(5, byName.size()));
        }
        List<JsonNode> corpus = new ArrayList<>();
        stories.forEach(corpus::add);
        if (corpus.isEmpty()) return List.of();
        int desired = Math.max(1, Math.min(5, (int) Math.round(Math.sqrt(corpus.size()))));
        List<Cluster> clusters = seedClusters(raw, corpus, desired);
        for (JsonNode story : corpus) {
            Cluster best = clusters.stream().max(Comparator.comparingInt(c -> similarity(storyText(story), c.signature()))).orElse(clusters.get(0));
            best.stories().add(story);
        }
        return clusters.stream().filter(c -> !c.stories().isEmpty()).map(c -> new Cluster(nameCluster(c, raw), c.stories())).toList();
    }

    private static List<Cluster> seedClusters(ObjectNode raw, List<JsonNode> stories, int count) {
        List<Cluster> seeds = new ArrayList<>();
        List<JsonNode> ordered = stories.stream().sorted(Comparator.comparingInt(s -> -tokenSet(storyText(s)).size())).toList();
        for (JsonNode story : ordered) {
            if (seeds.size() >= count) break;
            if (seeds.stream().noneMatch(c -> similarity(storyText(story), c.signature()) >= 45)) {
                seeds.add(new Cluster(seedName(story, raw), new ArrayList<>()));
            }
        }
        while (seeds.size() < count) seeds.add(new Cluster(projectFallback(raw) + " " + (seeds.size() + 1), new ArrayList<>()));
        return seeds;
    }

    private static String nameCluster(Cluster cluster, ObjectNode raw) {
        Map<String, Integer> counts = new HashMap<>();
        for (JsonNode story : cluster.stories()) tokenSet(storyText(story)).forEach(t -> counts.merge(t, 1, Integer::sum));
        List<String> terms = counts.entrySet().stream().sorted(Map.Entry.<String, Integer>comparingByValue().reversed().thenComparing(Map.Entry::getKey))
                .limit(2).map(Map.Entry::getKey).toList();
        if (terms.isEmpty()) return seedName(cluster.stories().get(0), raw);
        return String.join("与", terms) + "功能";
    }

    private static String seedName(JsonNode story, ObjectNode raw) {
        String title = story.path("title").asText(story.path("name").asText("")).trim();
        if (!title.isBlank()) {
            String[] parts = title.split("[：:，,、\\-—/\\s]+", 2);
            String value = parts[0].trim();
            if (value.length() > 12) value = value.substring(0, 12);
            return value + "功能";
        }
        return projectFallback(raw);
    }

    private static int nearestCluster(JsonNode story, List<Cluster> clusters) {
        int best = 0, score = Integer.MIN_VALUE;
        for (int i = 0; i < clusters.size(); i++) {
            int candidate = similarity(storyText(story), clusters.get(i).signature());
            if (candidate > score) { score = candidate; best = i; }
        }
        return best + 1;
    }

    private static int similarity(String left, String right) {
        Set<String> a = tokenSet(left), b = tokenSet(right);
        if (a.isEmpty() || b.isEmpty()) return 0;
        int common = 0;
        for (String token : a) if (b.contains(token)) common++;
        return common * 100 / Math.max(a.size(), b.size());
    }

    private static Set<String> tokenSet(String text) {
        Set<String> tokens = new LinkedHashSet<>();
        for (String raw : TOKEN_SPLIT.split(text == null ? "" : text)) {
            String token = raw.trim().toLowerCase(Locale.ROOT);
            if (token.length() >= 2 && !STOP_WORDS.contains(token)) tokens.add(token);
            // Chinese titles often arrive as one contiguous token. Add stable
            // two-character terms so related stories can share a cluster.
            if (token.length() > 2 && token.chars().allMatch(ProjectEpicPlanner::isCjk)) {
                for (int i = 0; i + 1 < token.length(); i++) {
                    String term = token.substring(i, i + 2);
                    if (!STOP_WORDS.contains(term)) tokens.add(term);
                }
            }
        }
        return tokens;
    }

    /**
     * A model may emit several Activity/Epic rows but assign every Story to
     * the first row.  Treat that shape as a collapsed generated plan so the
     * semantic recovery path is used; a genuinely distributed multi-domain
     * draft remains untouched by ProjectGenerationValidator's gate.
     */
    private static boolean assignmentsCollapsed(ArrayNode stories, List<String> supplied) {
        if (stories.isEmpty() || supplied.isEmpty()) return false;
        Map<String, Integer> counts = new HashMap<>();
        for (JsonNode story : stories) {
            String activity = story.path("activity").asText("").trim();
            if (!activity.isBlank()) counts.merge(activity, 1, Integer::sum);
        }
        if (counts.isEmpty() || counts.size() <= 1) return true;
        int largest = counts.values().stream().mapToInt(Integer::intValue).max().orElse(0);
        return largest * 100 > stories.size() * 85;
    }

    private static boolean isCjk(int codePoint) {
        return (codePoint >= 0x4e00 && codePoint <= 0x9fff)
                || (codePoint >= 0x3400 && codePoint <= 0x4dbf);
    }

    private static boolean isGenericTemplate(List<String> names) {
        if (names.size() != GENERIC_NAMES.size()) return false;
        for (int i = 0; i < GENERIC_NAMES.size(); i++) {
            if (!names.get(i).equals(GENERIC_NAMES.get(i))) return false;
        }
        return true;
    }

    private static List<String> suppliedNames(ObjectNode raw) {
        LinkedHashSet<String> names = new LinkedHashSet<>();
        collectNames(raw.path("activities"), names);
        collectNames(raw.path("epics"), names);
        return new ArrayList<>(names);
    }

    private static void collectNames(JsonNode rows, Set<String> names) {
        if (!rows.isArray()) return;
        for (JsonNode row : rows) {
            String name = row.isTextual() ? row.asText() : first(row, "title", "name");
            if (!name.isBlank()) names.add(name.trim());
        }
    }

    private static String storyText(JsonNode story) {
        return String.join(" ", story.path("title").asText(""), story.path("name").asText(""),
                story.path("description").asText(""), story.path("acceptance").asText(""), story.path("epic").asText(""));
    }

    private static String projectFallback(ObjectNode raw) {
        String name = raw.path("project").path("name").asText("项目").trim();
        return name.isBlank() ? "项目核心功能" : name + "核心功能";
    }

    private static String first(JsonNode node, String... keys) {
        for (String key : keys) {
            String value = node.path(key).asText("").trim();
            if (!value.isBlank()) return value;
        }
        return "";
    }

    private record Cluster(String name, List<JsonNode> stories) {
        String signature() { return stories.isEmpty() ? name : storyText(stories.get(0)); }
    }

    private record Domain(String name, List<String> terms) {
        Domain(String name, String... terms) { this(name, Arrays.asList(terms)); }
        int score(String text) {
            int score = 0;
            for (String term : terms) if (text.contains(term)) score += term.length() >= 4 ? 3 : 1;
            return score;
        }
    }
}
