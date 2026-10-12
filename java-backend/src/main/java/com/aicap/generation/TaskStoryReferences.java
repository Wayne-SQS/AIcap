package com.aicap.generation;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/** Detailed-only ingress contract. Numeric references require an actual raw Story id/code. */
final class TaskStoryReferences {
    static final String AUDIT = "story_reference_audit";
    static final String MARKER = "_task_story_references_canonical";
    private static final List<String> FIELDS = List.of("story_ref", "story_refs", "story", "storyCode", "story_code");
    private static final JsonNodeFactory JSON = JsonNodeFactory.instance;

    private TaskStoryReferences() { }

    static void canonicalize(ObjectNode draft) {
        canonicalize(draft, draft.path("stories"));
    }

    static void canonicalize(ObjectNode draft, JsonNode stories) {
        Map<String, String> ids = new LinkedHashMap<>();
        Set<String> ambiguous = new LinkedHashSet<>();
        for (JsonNode story : stories) {
            String id = scalar(story.path("id"));
            if (id.isBlank()) id = scalar(story.path("code"));
            if (id.isBlank()) continue;
            for (String field : List.of("id", "code")) {
                String key = scalar(story.path(field)).toUpperCase(Locale.ROOT);
                if (key.isBlank()) continue;
                String prior = ids.putIfAbsent(key, id);
                if (prior != null && !prior.equalsIgnoreCase(id)) ambiguous.add(key);
            }
        }
        for (JsonNode node : draft.path("tasks")) {
            if (!(node instanceof ObjectNode task) || task.path(AUDIT).isArray()) continue;
            ArrayNode audit = JSON.arrayNode();
            LinkedHashSet<String> accepted = new LinkedHashSet<>(), original = new LinkedHashSet<>(), invalid = new LinkedHashSet<>();
            for (String field : FIELDS) {
                JsonNode value = task.get(field);
                if (value == null || value.isNull()) continue;
                if (value.isArray()) {
                    for (int i = 0; i < value.size(); i++) read(task, field, value.get(i), i, ids, ambiguous, audit, accepted, original, invalid);
                } else read(task, field, value, -1, ids, ambiguous, audit, accepted, original, invalid);
            }
            task.put("story_ref", String.join(",", accepted)).put("storyCode", String.join(",", accepted));
            task.set(AUDIT, audit);
            if (!original.isEmpty() && !task.has("model_story_refs")) task.put("model_story_refs", String.join(",", original));
            if (!invalid.isEmpty()) task.put("invalid_story_refs", union(task.path("invalid_story_refs").asText(""), String.join(",", invalid)));
        }
        draft.put(MARKER, true);
    }

    private static void read(ObjectNode task, String field, JsonNode value, int index,
                             Map<String, String> ids, Set<String> ambiguous, ArrayNode audit,
                             Set<String> accepted, Set<String> original, Set<String> invalid) {
        if (value.isTextual()) {
            for (String token : value.asText().split("[,，;；\\s]+")) {
                if (token.isBlank()) continue;
                original.add(token);
                accepted.add(token); // Text title/code compatibility is resolved by the existing normalizer.
                audit.add(entry(task, field, value, index, token).put("parsedReference", token)
                        .put("mapped", false).put("failureReason", "PENDING_NORMALIZATION"));
            }
            return;
        }
        String token = scalar(value), key = token.toUpperCase(Locale.ROOT);
        ObjectNode entry = entry(task, field, value, index, token);
        if (value.isNumber()) {
            original.add(token);
            String mapped = ambiguous.contains(key) ? null : ids.get(key);
            if (mapped != null) {
                accepted.add(mapped);
                entry.put("parsedReference", mapped).put("mapped", true).put("failureReason", "");
            } else {
                invalid.add(token);
                entry.put("parsedReference", "").put("mapped", false)
                        .put("failureReason", ambiguous.contains(key) ? "AMBIGUOUS_RAW_STORY_ID" : "UNKNOWN_RAW_STORY_ID");
            }
        } else {
            entry.put("parsedReference", "").put("mapped", false).put("failureReason", "UNSUPPORTED_JSON_TYPE");
        }
        audit.add(entry);
    }

    private static ObjectNode entry(ObjectNode task, String field, JsonNode value, int index, String token) {
        // Never serialize an object/model response into diagnostics.
        String brief = value.isContainerNode() ? "<" + value.getNodeType() + ">" : value.asText("");
        if (brief.length() > 120) brief = brief.substring(0, 120);
        return JSON.objectNode().put("generation_identity", task.path("generation_identity").asText(""))
                .put("model_task_code", task.path("model_task_code").asText(task.path("id").asText("")))
                .put("field", field).put("jsonType", value.getNodeType().name())
                .put("arrayIndex", index).put("rawValue", brief).put("reference", token);
    }

    /** Consumers use canonical refs after ingress; unsupported scalars are never guessed. */
    static String text(JsonNode task) {
        if (task.path(AUDIT).isArray()) return task.path("story_ref").asText("");
        Set<String> refs = new LinkedHashSet<>();
        for (String field : FIELDS) {
            JsonNode value = task.path(field);
            if (value.isTextual()) addText(refs, value.asText());
            else if (value.isArray()) for (JsonNode item : value) if (item.isTextual()) addText(refs, item.asText());
        }
        return String.join(",", refs);
    }

    private static void addText(Set<String> refs, String value) {
        for (String token : value.split("[,，;；\\s]+")) if (!token.isBlank()) refs.add(token.trim());
    }

    private static String scalar(JsonNode value) {
        return value.isTextual() || value.isNumber() ? value.asText().trim() : "";
    }

    static String union(String left, String right) {
        Set<String> refs = new LinkedHashSet<>();
        addText(refs, left); addText(refs, right);
        return String.join(",", refs);
    }

    static void mergeAudit(ObjectNode retained, JsonNode incoming) {
        if (!incoming.path(AUDIT).isArray()) return;
        ArrayNode audit = retained.withArray(AUDIT);
        for (JsonNode entry : incoming.path(AUDIT)) {
            boolean exists = false;
            for (JsonNode old : audit) if (old.equals(entry)) { exists = true; break; }
            if (!exists) audit.add(entry.deepCopy());
        }
    }

    /** Rewrite only the resolved target; original model value/field/type remain immutable. */
    static void remapAudit(ObjectNode task, Map<String, String> aliases) {
        for (JsonNode node : task.path(AUDIT)) {
            if (!(node instanceof ObjectNode entry)) continue;
            String target = entry.path("parsedReference").asText("");
            Set<String> visited = new LinkedHashSet<>();
            while (aliases.containsKey(target) && visited.add(target)) target = aliases.get(target);
            entry.put("parsedReference", target);
        }
    }

    static ObjectNode summary(JsonNode tasks) {
        ObjectNode totals = JSON.objectNode().put("rawReferenceCount", 0).put("parsedReferenceCount", 0)
                .put("rejectedReferenceCount", 0).put("numericScalarResolvedCount", 0).put("numericScalarRejectedCount", 0);
        ObjectNode byType = totals.putObject("rejectedByFieldAndType");
        for (JsonNode task : tasks) for (JsonNode entry : task.path(AUDIT)) {
            increment(totals, "rawReferenceCount");
            boolean mapped = entry.path("mapped").asBoolean(false);
            increment(totals, mapped ? "parsedReferenceCount" : "rejectedReferenceCount");
            if (!mapped) increment(byType, entry.path("field").asText() + ":" + entry.path("jsonType").asText());
            if ("NUMBER".equals(entry.path("jsonType").asText()) && entry.path("arrayIndex").asInt(-1) < 0)
                increment(totals, mapped ? "numericScalarResolvedCount" : "numericScalarRejectedCount");
        }
        return totals;
    }

    private static void increment(ObjectNode node, String key) { node.put(key, node.path(key).asInt() + 1); }
}
