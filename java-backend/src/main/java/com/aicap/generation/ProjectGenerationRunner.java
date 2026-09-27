package com.aicap.generation;

import com.aicap.agent.AgentError;
import com.aicap.agent.AgentProperties;
import com.aicap.agent.ModelClient;
import com.aicap.entity.User;
import com.aicap.mapper.UserMapper;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** LLM-only planning step. It never writes business tables. */
@Component
@RequiredArgsConstructor
public class ProjectGenerationRunner {
    private final UserMapper users;
    private final ObjectMapper mapper;

    public ObjectNode generate(String request, String mode, ModelClient client) throws AgentError {
        String prompt = """
                你是爱管理 Project Generator。只生成项目规划草案，不修改数据库。
                输出严格 JSON，不要 Markdown，字段必须包含 project,members,sprints,activities,epics,stories,tasks,dependencies,milestones,uml。
                stories 每项使用 id,title,description,acceptance,priority(Must/Should/Could),sprint,activity(1-5),owner_id(现有成员编号或 null)。
                tasks 每项使用 id,name,owner_id,hours,week_start,week_end,story_ref,depends_on。
                每个 task 都必须显式输出 depends_on；推荐使用前置 Task ID 数组，例如 ["T01","T02"]，没有可靠前置任务时输出 []，不得省略该字段。
                同一 Story 内应按真实交付顺序判断依赖，优先考虑：数据库/接口基础 → 后端服务 → 前端接入 → 联调 → 测试。只有存在业务先后关系时才添加，禁止随机跨 Story、禁止自依赖、禁止循环依赖。
                顶层 dependencies 使用 {"from":"前置Task ID","to":"后置Task ID"}，并且必须与各 task 的 depends_on 一致；没有依赖时输出空数组。
                任务周次必须在 W1-W6，并且必须落在所属 Story 的 Sprint 周范围内；优先级高的故事尽量早安排，后置任务开始周不得早于前置任务结束周；按成员容量分散同周任务，只能使用给出的现有成员编号。
                如果用户明确给出 Sprint1、Sprint2、Sprint3，则只能生成这 3 个 Sprint，不要凭空增加 Sprint；project.total_sprints 必须与用户明确的 Sprint 数一致。
                这是模式：%s。用户输入：%s
                现有成员：%s
                """.formatted(mode, request, membersJson());
        List<JsonNode> messages = new ArrayList<>();
        ObjectNode system = mapper.createObjectNode().put("role", "system").put("content", prompt);
        messages.add(system);
        ModelClient.Completion completion = client.complete(messages, List.of(), true);
        ObjectNode draft = parse(completion.message().path("content").asText("{}"));
        repairMissingSprintDistribution(draft, request);
        return draft;
    }

    /** Keep explicit Sprint wording authoritative when the model collapses every story into one Sprint. */
    private void repairMissingSprintDistribution(ObjectNode draft, String request) {
        if (!draft.path("stories").isArray() || draft.path("stories").isEmpty()) return;
        int sprintCount = draft.path("project").path("total_sprints").isIntegralNumber()
                ? Math.max(1, Math.min(6, draft.path("project").path("total_sprints").asInt()))
                : (draft.path("sprints").isArray() && !draft.path("sprints").isEmpty() ? draft.path("sprints").size() : 3);
        if (sprintCount <= 1) return;
        boolean collapsed = true;
        int first = draft.path("stories").get(0).path("sprint").asInt(1);
        for (JsonNode story : draft.path("stories")) {
            if (story.path("sprint").asInt(1) != first) { collapsed = false; break; }
        }
        if (!collapsed) return;

        Map<Integer, List<String>> explicit = new HashMap<>();
        Matcher matcher = Pattern.compile("Sprint\\s*(\\d+)\\s*([^；。\\n]+)", Pattern.CASE_INSENSITIVE).matcher(request == null ? "" : request);
        while (matcher.find()) {
            int sprint = Integer.parseInt(matcher.group(1));
            if (sprint >= 1 && sprint <= sprintCount) {
                List<String> terms = new ArrayList<>();
                for (String term : matcher.group(2).split("[、，,：:和与及 ]")) {
                    if (term.trim().length() >= 2) terms.add(term.trim());
                }
                explicit.put(sprint, terms);
            }
        }

        int index = 0;
        for (JsonNode story : draft.path("stories")) {
            int assigned = 0;
            String searchable = (story.path("title").asText("") + story.path("description").asText("")).toLowerCase();
            for (Map.Entry<Integer, List<String>> entry : explicit.entrySet()) {
                if (entry.getValue().stream().anyMatch(term -> searchable.contains(term.toLowerCase()))) {
                    assigned = entry.getKey();
                    break;
                }
            }
            if (assigned == 0) assigned = Math.min(sprintCount, (index * sprintCount / draft.path("stories").size()) + 1);
            ((ObjectNode) story).put("sprint", assigned);
            index++;
        }
    }

    private String membersJson() { try { return mapper.writeValueAsString(users.selectList(null).stream().map(u -> java.util.Map.of("id",u.getId(),"name",u.getDisplayName(),"role",u.getRole(),"capacity_hours",u.getCapacityHours())).toList()); } catch (Exception e) { return "[]"; } }
    private ObjectNode parse(String text) throws AgentError { try { JsonNode n=mapper.readTree(text); if(n instanceof ObjectNode o)return o; } catch(Exception ignored) { } throw new AgentError("invalid_response","模型没有返回合法项目规划 JSON"); }
}
