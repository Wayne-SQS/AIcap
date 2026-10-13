package com.aicap.generation;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.*;

/** Task reference boundary: one reference resolves to one identity, never a list of candidates. */
final class TaskIdentityReferences {
    private static final JsonNodeFactory F = JsonNodeFactory.instance;
    private TaskIdentityReferences() {}

    static void resolve(ArrayNode tasks, ArrayNode milestones, ArrayNode dependencies, boolean local) {
        Map<String, LinkedHashSet<String>> codes = new LinkedHashMap<>();
        Set<String> identities = new LinkedHashSet<>();
        Set<String> ambiguousIdentities = new LinkedHashSet<>();
        Map<String, String> identityCodes = new LinkedHashMap<>();
        for (JsonNode node : tasks) {
            ObjectNode task = (ObjectNode) node;
            String code = task.path("id").asText(task.path("code").asText(""));
            if (!task.hasNonNull("model_task_code")) task.put("model_task_code", code);
            if (!task.hasNonNull("generation_identity")) {
                String stage = task.path("generation_stage").asText("legacy");
                String base = stage + "#" + task.path("generation_continuation_round").asInt(0)
                        + "#" + task.path("model_task_code").asText();
                String identity = base;
                int occurrence = 1;
                while (identities.contains(identity)) identity = base + "#occurrence" + (++occurrence);
                task.put("generation_identity", identity);
            }
            String identity = task.path("generation_identity").asText();
            if (identities.contains(identity)) {
                String base = identity;
                ambiguousIdentities.add(base);
                int occurrence = 1;
                while (identities.contains(identity)) identity = base + "#occurrence" + (++occurrence);
                task.put("generation_identity", identity);
            }
            identities.add(identity);
            identityCodes.put(identity, code);
            for (String alias : List.of(code, task.path("model_task_code").asText("")))
                if (!alias.isBlank()) codes.computeIfAbsent(key(alias), ignored -> new LinkedHashSet<>()).add(identity);
            for (JsonNode source : task.path("generation_sources")) {
                String oldIdentity = source.path("identity").asText("");
                if (!oldIdentity.isBlank()) identityCodes.putIfAbsent(oldIdentity, code);
            }
        }
        for (JsonNode node : tasks) {
            ObjectNode task = (ObjectNode) node;
            canonical(task, "depends_on", "dependencies", "dependsOn", "dependency_task_ids", "predecessors", "predecessor");
            canonical(task, "weak_dependencies", "weakDependencies");
            canonical(task, "related_task_ids", "related_tasks", "related_task", "relatedTaskIds");
            refs(task, "depends_on", "generation_dependency_refs", codes, identityCodes, local);
            refs(task, "weak_dependencies", "generation_weak_dependency_refs", codes, identityCodes, local);
            refs(task, "related_task_ids", "generation_related_task_refs", codes, identityCodes, local);
        }
        for (JsonNode node : milestones) {
            ObjectNode row = (ObjectNode) node;
            sourceContext(row, tasks, local);
            canonical(row, "related_task_ids", "task_refs", "task_ids", "tasks", "related_tasks");
            refs(row, "related_task_ids", "generation_related_task_refs", codes, identityCodes, local);
        }
        for (JsonNode node : dependencies) {
            if (!(node instanceof ObjectNode edge)) continue;
            sourceContext(edge, tasks, local);
            scalarAlias(edge, "from", "source", "predecessor", "depends_on");
            scalarAlias(edge, "to", "target", "successor", "task_id");
            edge(edge, "from", codes, identityCodes);
            edge(edge, "to", codes, identityCodes);
        }
    }

    private static void sourceContext(ObjectNode row, ArrayNode tasks, boolean local) {
        if (!local || tasks.isEmpty()) return;
        JsonNode first = tasks.get(0);
        for (String field : List.of("generation_stage", "generation_continuation_round"))
            if (!row.has(field) && first.has(field)) row.set(field, first.path(field).deepCopy());
    }

    private static void refs(ObjectNode row, String field, String identityField,
                             Map<String, LinkedHashSet<String>> codes, Map<String, String> identities, boolean local) {
        String unresolvedField = "unresolved_" + field + "_references";
        LinkedHashSet<String> resolved = new LinkedHashSet<>(values(row.path(identityField)));
        LinkedHashSet<String> pending = new LinkedHashSet<>(values(row.path(unresolvedField)));
        // Legacy local failures belong to depends_on. They may be resolved only
        // by a globally unique original code, never by a newly assigned public code.
        if (field.equals("depends_on")) pending.addAll(values(row.path("unresolved_cross_stage_references")));
        if (!row.has(identityField) || resolved.isEmpty() && pending.isEmpty()) pending.addAll(values(row.path(field)));
        LinkedHashSet<String> rejected = new LinkedHashSet<>();
        for (String ref : pending) {
            Set<String> candidates = codes.getOrDefault(key(ref), new LinkedHashSet<>());
            if (candidates.size() == 1 && !(row.path("generation_identity").asText("").equals(candidates.iterator().next())
                    && values(row.path("unresolved_cross_stage_references")).contains(ref))) {
                String identity = candidates.iterator().next();
                resolved.add(identity);
                if (!local) addUnique(row, "unique_bare_task_references", ref);
            } else rejected.add(ref);
        }
        LinkedHashSet<String> publicRefs = new LinkedHashSet<>();
        for (String identity : resolved) {
            String code = identities.get(identity);
            if (code != null) publicRefs.add(code);
            else rejected.add(identity);
        }
        row.set(identityField, F.arrayNode().addAll(strings(resolved)));
        row.set(unresolvedField, F.arrayNode().addAll(strings(rejected)));
        row.put(field, String.join(",", publicRefs));
        if (field.equals("depends_on")) row.set("unresolved_cross_stage_references", F.arrayNode().addAll(strings(rejected)));
    }

    private static void edge(ObjectNode edge, String field, Map<String, LinkedHashSet<String>> codes,
                             Map<String, String> identities) {
        String identityField = "generation_" + field + "_ref";
        String unresolved = "unresolved_" + field + "_reference";
        String identity = edge.path(identityField).asText("");
        if (!identity.isBlank()) {
            String code = identities.get(identity);
            if (code != null) { edge.put(field, code); edge.remove(unresolved); }
            else edge.put(field, "").put(unresolved, identity);
            return;
        }
        String ref = edge.path(unresolved).asText(edge.path(field).asText("")).trim();
        if (ref.isBlank()) return;
        Set<String> candidates = codes.getOrDefault(key(ref), new LinkedHashSet<>());
        if (candidates.size() == 1) {
            identity = candidates.iterator().next();
            edge.put(identityField, identity).put(field, identities.get(identity));
            edge.put("unique_bare_" + field + "_reference", ref);
            edge.remove(unresolved);
        } else edge.put(unresolved, ref).put(field, "");
    }

    static List<String> values(JsonNode node) {
        List<String> out = new ArrayList<>();
        if (node.isArray()) for (JsonNode item : node) out.addAll(values(item));
        else if (node.isTextual() || node.isNumber())
            for (String ref : node.asText().split("[,，;；\\s]+")) if (!ref.isBlank()) out.add(ref.trim());
        return out;
    }
    private static List<JsonNode> strings(Collection<String> values) {
        List<JsonNode> out = new ArrayList<>();
        for (String value : values) out.add(F.textNode(value));
        return out;
    }
    private static String key(String code) { return code.trim().toUpperCase(Locale.ROOT); }
    private static void addUnique(ObjectNode row, String field, String value) {
        ArrayNode array = row.withArray(field);
        for (JsonNode existing : array) if (existing.asText().equals(value)) return;
        array.add(value);
    }
    private static void canonical(ObjectNode row, String field, String... aliases) {
        LinkedHashSet<String> refs = new LinkedHashSet<>(values(row.path(field)));
        for (String alias : aliases) {
            refs.addAll(values(row.path(alias)));
            row.remove(alias);
        }
        if (!refs.isEmpty()) row.put(field, String.join(",", refs));
    }
    private static void scalarAlias(ObjectNode row, String field, String... aliases) {
        if (!row.hasNonNull(field)) for (String alias : aliases)
            if (row.path(alias).isTextual() || row.path(alias).isNumber()) { row.set(field, row.path(alias)); break; }
        for (String alias : aliases) row.remove(alias);
    }
}
