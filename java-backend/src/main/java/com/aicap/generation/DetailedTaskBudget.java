package com.aicap.generation;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/** Deterministic size guard used only by staged detailed generation. */
final class DetailedTaskBudget {
    private DetailedTaskBudget() { }

    static int forStories(int stories) {
        if (stories <= 0) return 0;
        if (stories <= 12) return Math.max(12, stories * 3 + 4);
        if (stories <= 20) return stories * 3 + 6;
        if (stories <= 35) return stories * 3 + 8;
        if (stories <= 50) return stories * 3 + 10;
        return Math.min(180, stories * 3 + 12);
    }

    static int supplementBudget(int remainingBudget, int gapStories, int milestoneGaps) {
        int gapLimit = gapStories * 3 + Math.min(4, Math.max(0, milestoneGaps));
        return Math.max(0, Math.min(remainingBudget, gapLimit));
    }

    static int enforce(ObjectNode draft, int budget) {
        if (!draft.path("tasks").isArray()) return 0;
        ArrayNode tasks = (ArrayNode) draft.path("tasks");
        int before = tasks.size();
        if (budget < 0 || before <= budget) return 0;

        List<ObjectNode> ordered = new ArrayList<>();
        for (JsonNode node : tasks) if (node instanceof ObjectNode task) ordered.add(task);
        ordered.sort(Comparator.comparing(DetailedTaskBudget::storyKey)
                .thenComparing(DetailedTaskBudget::stageKey)
                .thenComparing(DetailedTaskBudget::semanticKey)
                .thenComparing(task -> task.path("generation_identity").asText(task.path("id").asText("")),
                        String.CASE_INSENSITIVE_ORDER));

        LinkedHashSet<ObjectNode> retained = new LinkedHashSet<>();
        Map<String, Integer> perStory = new LinkedHashMap<>();
        Set<String> signatures = new HashSet<>();
        for (ObjectNode task : ordered) {
            String story = storyKey(task);
            if (story.isBlank()) continue;
            String signature = story + "|" + stageKey(task) + "|" + semanticKey(task);
            if (perStory.getOrDefault(story, 0) >= 3 || !signatures.add(signature)) continue;
            retained.add(task);
            perStory.merge(story, 1, Integer::sum);
            if (retained.size() == budget) break;
        }
        for (ObjectNode task : ordered) {
            if (retained.size() == budget) break;
            if (storyKey(task).isBlank() && retained.stream().filter(row -> storyKey(row).isBlank()).count() >= 8) continue;
            retained.add(task);
        }

        Set<String> keptCodes = new HashSet<>();
        retained.forEach(task -> keptCodes.add(task.path("id").asText("").toUpperCase(Locale.ROOT)));
        tasks.removeAll();
        retained.forEach(tasks::add);
        for (JsonNode node : tasks) if (node instanceof ObjectNode task) {
            LinkedHashSet<String> refs = new LinkedHashSet<>();
            for (String ref : refs(task.path("depends_on")))
                if (keptCodes.contains(ref.toUpperCase(Locale.ROOT))) refs.add(ref);
            task.put("depends_on", String.join(",", refs));
        }
        if (draft.path("dependencies").isArray()) {
            ArrayNode kept = com.fasterxml.jackson.databind.node.JsonNodeFactory.instance.arrayNode();
            for (JsonNode edge : draft.path("dependencies")) {
                String from = edge.path("from").asText("").toUpperCase(Locale.ROOT);
                String to = edge.path("to").asText("").toUpperCase(Locale.ROOT);
                if (keptCodes.contains(from) && keptCodes.contains(to)) kept.add(edge);
            }
            draft.set("dependencies", kept);
        }
        if (draft.path("milestones").isArray()) for (JsonNode node : draft.path("milestones")) {
            if (!(node instanceof ObjectNode milestone)) continue;
            List<String> refs = refs(milestone.path("related_task_ids")).stream()
                    .filter(ref -> keptCodes.contains(ref.toUpperCase(Locale.ROOT))).toList();
            milestone.put("related_task_ids", String.join(",", refs));
        }
        return before - tasks.size();
    }

    static boolean highConfidenceDuplicate(JsonNode left, JsonNode right) {
        String leftStory = storyKey(left), rightStory = storyKey(right);
        return !leftStory.isBlank() && leftStory.equalsIgnoreCase(rightStory)
                && stageKey(left).equals(stageKey(right))
                && !semanticKey(left).isBlank() && semanticKey(left).equals(semanticKey(right));
    }

    static String storyKey(JsonNode task) {
        String refs = TaskStoryReferences.text(task);
        return refs(refs).stream().map(value -> value.toUpperCase(Locale.ROOT)).sorted()
                .reduce((a, b) -> a + "," + b).orElse("");
    }

    private static String stageKey(JsonNode task) {
        String text = task.path("name").asText("") + " " + task.path("description").asText("");
        text = text.toLowerCase(Locale.ROOT);
        if (contains(text, "数据库", "数据表", "表结构", "schema")) return "data";
        if (contains(text, "接口", "api", "后端", "service")) return "api";
        if (contains(text, "页面", "前端", "界面", "表单", "组件")) return "ui";
        if (contains(text, "校验", "验证", "权限")) return "validation";
        if (contains(text, "测试", "联调", "验收", "回归")) return "test";
        return "business";
    }

    private static String semanticKey(JsonNode task) {
        return (task.path("name").asText("") + task.path("description").asText(""))
                .toLowerCase(Locale.ROOT)
                .replaceAll("(负责|进行|完成|实现|开发|编写|构建|制作|处理|相关|功能|逻辑|模块)", "")
                .replaceAll("(后端api|api接口|后端接口|服务接口|接口服务)", "接口")
                .replaceAll("(前端页面|页面前端|web页面|界面|组件页面)", "页面")
                .replaceAll("[\\p{Punct}\\p{Z}\\s，。；：、（）]+", "");
    }

    private static boolean contains(String text, String... values) {
        for (String value : values) if (text.contains(value)) return true;
        return false;
    }

    private static List<String> refs(JsonNode value) {
        if (value == null || value.isNull()) return List.of();
        List<String> result = new ArrayList<>();
        if (value.isArray()) value.forEach(item -> { if (!item.asText("").isBlank()) result.add(item.asText().trim()); });
        else result.addAll(refs(value.asText("")));
        return result;
    }

    private static List<String> refs(String value) {
        List<String> result = new ArrayList<>();
        for (String ref : value.split("[,，;；\\s]+")) if (!ref.isBlank()) result.add(ref.trim());
        return result;
    }
}
