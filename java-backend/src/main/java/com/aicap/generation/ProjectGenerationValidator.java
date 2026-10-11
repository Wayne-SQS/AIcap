package com.aicap.generation;

import com.aicap.common.ApiException;
import com.aicap.entity.Story;
import com.aicap.entity.Task;
import com.aicap.entity.User;
import com.aicap.mapper.StoryMapper;
import com.aicap.mapper.TaskMapper;
import com.aicap.mapper.UserMapper;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Converts model/import output into the existing Story/Task contract. */
@Service
@RequiredArgsConstructor
public class ProjectGenerationValidator {
    private static final Pattern US = Pattern.compile("^US(\\d+)$", Pattern.CASE_INSENSITIVE);
    private static final Pattern T = Pattern.compile("^T(\\d+)$", Pattern.CASE_INSENSITIVE);
    private final StoryMapper stories;
    private final TaskMapper tasks;
    private final UserMapper users;
    private final ObjectMapper mapper;

    public ObjectNode normalize(JsonNode input, String strategy) {
        if (!(input instanceof ObjectNode raw)) throw ApiException.badRequest("生成结果必须是 JSON 对象");
        ArrayNode rawStories = array(raw, "stories");
        ArrayNode rawTasks = array(raw, "tasks");
        if (rawStories.isEmpty()) throw ApiException.badRequest("至少需要生成一个用户故事");
        if (rawStories.size() > 100 || rawTasks.size() > 300) throw ApiException.badRequest("生成规模超过上限");

        ObjectNode draft = mapper.createObjectNode();
        draft.put("schema_version", 1);
        draft.set("project", object(raw, "project"));
        draft.set("members", members());
        int sprintCount = sprintCount(raw);
        draft.set("sprints", sprints(sprintCount));
        draft.set("activities", array(raw, "activities"));
        draft.set("epics", array(raw, "epics"));
        draft.set("milestones", array(raw, "milestones"));
        ArrayNode outStories = mapper.createArrayNode();
        ArrayNode outTasks = mapper.createArrayNode();
        Map<String, String> storyIds = new HashMap<>();
        int nextStory = "replace".equals(strategy) ? 1 : nextNumber(stories.selectList(null).stream().map(Story::getId).toList(), US);
        Set<String> sourceStoryIds = new HashSet<>();
        for (int i = 0; i < rawStories.size(); i++) {
            JsonNode source = rawStories.get(i);
            if (!source.isObject()) throw ApiException.badRequest("stories 中存在无效行");
            ObjectNode s = mapper.createObjectNode();
            String rawId = text(source, "id", "story" + i);
            if (!sourceStoryIds.add(rawId.toUpperCase())) throw ApiException.badRequest("Story ID 重复: " + rawId);
            String id = String.format("US%02d", nextStory++);
            storyIds.put(rawId.toUpperCase(), id);
            storyIds.put(id, id);
            String title = text(source, "title", text(source, "name", "未命名故事"));
            if (title.isBlank()) throw ApiException.badRequest("故事标题不能为空");
            s.put("id", id); s.put("title", title); s.put("description", text(source, "description", ""));
            s.put("acceptance", text(source, "acceptance", "待补充验收条件"));
            s.put("priority", priority(text(source, "priority", "Should")));
            s.put("sprint", boundedInt(source, "sprint", 1, 1, sprintCount));
            s.put("activity", boundedInt(source, "activity", 1, 1, 5));
            s.put("status", boundedInt(source, "status", 0, 0, 2));
            Integer owner = nullableInt(source, "owner_id", "ownerId");
            if (owner != null && users.selectById(owner) == null) throw ApiException.badRequest("故事负责人不存在: " + owner);
            if (owner == null) s.putNull("owner_id"); else s.put("owner_id", owner);
            outStories.add(s);
        }

        Map<String, String> taskIds = new HashMap<>();
        int nextTask = "replace".equals(strategy) ? 1 : nextNumber(tasks.selectList(null).stream().map(Task::getId).toList(), T);
        Set<String> sourceTaskIds = new HashSet<>();
        for (int i = 0; i < rawTasks.size(); i++) {
            JsonNode source = rawTasks.get(i);
            if (!source.isObject()) throw ApiException.badRequest("tasks 中存在无效行");
            String rawId = text(source, "id", "task" + i);
            if (!sourceTaskIds.add(rawId.toUpperCase())) throw ApiException.badRequest("Task ID 重复: " + rawId);
            String id = String.format("T%02d", nextTask++);
            taskIds.put(rawId.toUpperCase(), id);
            taskIds.put(id, id);
        }
        Map<String, List<String>> topLevelDependencies = topLevelDependencies(raw.path("dependencies"));
        nextTask = "replace".equals(strategy) ? 1 : nextNumber(tasks.selectList(null).stream().map(Task::getId).toList(), T);
        for (int i = 0; i < rawTasks.size(); i++) {
            JsonNode source = rawTasks.get(i);
            if (!source.isObject()) throw ApiException.badRequest("tasks 中存在无效行");
            ObjectNode t = mapper.createObjectNode();
            String rawId = text(source, "id", "task" + i);
            String id = taskIds.get(rawId.toUpperCase());
            nextTask++;
            String name = text(source, "name", text(source, "title", "未命名任务"));
            if (name.isBlank()) throw ApiException.badRequest("任务名称不能为空");
            int owner = nullableInt(source, "owner_id", "ownerId") == null ? firstMemberId() : nullableInt(source, "owner_id", "ownerId");
            if (users.selectById(owner) == null) throw ApiException.badRequest("任务负责人不存在: " + owner);
            int start = boundedInt(source, "week_start", 1, 1, 6);
            int end = boundedInt(source, "week_end", start, 1, 6);
            if (start > end) throw ApiException.badRequest("任务开始周不能晚于结束周: " + id);
            String storyRef = refs(text(source, "story_ref", text(source, "story", "")), storyIds);
            List<String> dependencyRefs = dependencyRefs(source);
            for(String topLevelRef:topLevelDependencies.getOrDefault(rawId.toUpperCase(Locale.ROOT),List.of()))
                if(dependencyRefs.stream().noneMatch(existing->existing.equalsIgnoreCase(topLevelRef)))dependencyRefs.add(topLevelRef);
            String depends = refs(dependencyRefs, taskIds, id);
            int[] storyWeeks = storyWeeks(outStories, storyRef, sprintCount);
            if (storyWeeks != null && (end < storyWeeks[0] || start > storyWeeks[1])) {
                int duration = Math.max(0, end - start);
                start = storyWeeks[0];
                end = Math.min(storyWeeks[1], start + duration);
            }
            t.put("id", id); t.put("name", name); t.put("owner_id", owner);
            t.put("hours", Math.max(0, boundedInt(source, "hours", boundedInt(source, "estimated_hours", 1, 0, 999), 0, 999)));
            t.put("week_start", start); t.put("week_end", end); t.put("story_ref", storyRef.isBlank() ? (outStories.get(0).path("id").asText()) : storyRef);
            t.put("kanban_card_id", t.path("story_ref").asText().split(",")[0]);
            t.put("estimated_hours", t.path("hours").asInt()); t.put("task_type", "feature");
            t.put("depends_on", depends); t.put("status", 0); t.put("progress", 0); t.put("blocked", false);
            outTasks.add(t);
        }
        ArrayNode scheduleWarnings = mapper.createArrayNode();
        inferDependencies(outTasks, scheduleWarnings);
        validateDependencyGraph(outTasks, false);
        repairDependencySchedule(outTasks, scheduleWarnings);
        validateDependencySchedule(outTasks, false);
        draft.set("stories", outStories); draft.set("tasks", outTasks);
        draft.set("dependencies", dependencies(outTasks));
        draft.set("uml", uml(outStories));
        ObjectNode risks = risks(outStories, outTasks, strategy);
        draft.set("risks", risks); ArrayNode allWarnings = warnings(risks); scheduleWarnings.forEach(allWarnings::add); draft.set("warnings", allWarnings);
        draft.put("story_count", outStories.size()); draft.put("task_count", outTasks.size());
        return draft;
    }

    public void validateForExecution(ObjectNode draft, String strategy) {
        if (!draft.path("schema_version").isInt() || !draft.path("stories").isArray() || !draft.path("tasks").isArray()) throw ApiException.conflict("生成草案格式已失效，请重新生成");
        Set<String> storyIds = new HashSet<>();
        for (JsonNode s : draft.path("stories")) { String id = s.path("id").asText(); if (!storyIds.add(id) || stories.selectById(id) != null && !"replace".equals(strategy)) throw ApiException.conflict("草案编号已被占用，请重新生成"); }
        Set<String> taskIds = new HashSet<>();
        for (JsonNode t : draft.path("tasks")) { String id = t.path("id").asText(); if (!taskIds.add(id) || tasks.selectById(id) != null && !"replace".equals(strategy)) throw ApiException.conflict("草案任务编号已被占用，请重新生成"); if (users.selectById(t.path("owner_id").asInt()) == null) throw ApiException.conflict("草案负责人已不存在"); }
        validateDependencyGraph(draft.path("tasks"), true);
        validateDependencySchedule(draft.path("tasks"), true);
    }

    /** Revalidates a user-edited draft without renumbering rows or moving task schedules. */
    public ObjectNode revalidateEditedDraft(ObjectNode draft, String strategy) {
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
        draft.set("uml", uml(storiesNode));
        ObjectNode riskReport = risks(storiesNode, tasksNode, strategy);
        draft.set("risks", riskReport);
        draft.set("warnings", warnings(riskReport));
        draft.put("story_count", storiesNode.size());
        draft.put("task_count", tasksNode.size());
        return draft;
    }

    private ArrayNode members() { ArrayNode a = mapper.createArrayNode(); users.selectList(new QueryWrapper<User>().orderByAsc("id")).forEach(u -> { ObjectNode n=mapper.createObjectNode(); n.put("id",u.getId()); n.put("name",u.getDisplayName()); n.put("role",u.getRole()); n.put("capacity_hours",u.getCapacityHours()); a.add(n); }); return a; }
    private int sprintCount(JsonNode raw) {
        JsonNode project = raw.path("project");
        if (project.path("total_sprints").isIntegralNumber()) return Math.max(1, Math.min(6, project.path("total_sprints").asInt()));
        if (raw.path("sprints").isArray() && !raw.path("sprints").isEmpty()) return Math.max(1, Math.min(6, raw.path("sprints").size()));
        int weeks = project.path("duration_weeks").isIntegralNumber() ? project.path("duration_weeks").asInt() : 6;
        return Math.max(1, Math.min(6, (weeks + 1) / 2));
    }
    private ArrayNode sprints(int count) { ArrayNode a=mapper.createArrayNode(); for(int i=1;i<=count;i++){int start=(i-1)*2+1; a.add(mapper.createObjectNode().put("sprint",i).put("week_start",start).put("week_end",Math.min(6,start+1)));} return a; }
    private int[] storyWeeks(ArrayNode stories, String storyRef, int sprintCount) {
        if (storyRef == null || storyRef.isBlank()) return null;
        JsonNode story = find(stories, storyRef.split(",")[0]);
        if (story == null) return null;
        int sprint = Math.max(1, Math.min(sprintCount, story.path("sprint").asInt(1)));
        return new int[]{(sprint - 1) * 2 + 1, Math.min(6, sprint * 2)};
    }
    private ObjectNode uml(ArrayNode stories) { ObjectNode u=mapper.createObjectNode(); ArrayNode cases=mapper.createArrayNode(); stories.forEach(s -> cases.add(mapper.createObjectNode().put("id", s.path("id").asText()).put("name", s.path("title").asText()))); u.set("use_cases", cases); u.set("participants", members()); return u; }
    private ArrayNode dependencies(ArrayNode tasks) { ArrayNode a=mapper.createArrayNode(); tasks.forEach(t -> { for(String d:t.path("depends_on").asText("").split(",")) if(!d.isBlank()) a.add(mapper.createObjectNode().put("from",d).put("to",t.path("id").asText())); }); return a; }
    private ObjectNode risks(ArrayNode stories, ArrayNode draftTasks, String strategy) { ObjectNode r=mapper.createObjectNode(); ArrayNode load=mapper.createArrayNode(), dep=mapper.createArrayNode(), sprint=mapper.createArrayNode(); Map<Integer,int[]> loads=new HashMap<>(); if(!"replace".equals(strategy))for(Task t:tasks.selectList(null))addLoad(loads,t.getOwnerId(),t.getHours(),t.getWeekStart(),t.getWeekEnd()); for(JsonNode t:draftTasks)addLoad(loads,t.path("owner_id").asInt(),t.path("hours").asInt(),t.path("week_start").asInt(),t.path("week_end").asInt()); for(var e:loads.entrySet()){User u=users.selectById(e.getKey()); if(u==null)continue; for(int w=0;w<6;w++) if(e.getValue()[w]>u.getCapacityHours()) load.add(mapper.createObjectNode().put("kind","load").put("message",u.getDisplayName()+" W"+(w+1)+" 预计 "+e.getValue()[w]+"h / 周容量 "+u.getCapacityHours()+"h"));} Map<String,JsonNode> byTask=new HashMap<>();draftTasks.forEach(t->byTask.put(t.path("id").asText(),t));for(JsonNode t:draftTasks){for(String id:t.path("depends_on").asText("").split(",")){JsonNode p=byTask.get(id);if(p!=null&&t.path("week_start").asInt()<p.path("week_end").asInt())dep.add(mapper.createObjectNode().put("kind","dependency").put("message",t.path("id").asText()+" 早于前置任务 "+id+" 完成"));}for(String sid:t.path("story_ref").asText("").split(",")){JsonNode s=find(stories,sid);if(s!=null){int sp=s.path("sprint").asInt();int sw=(sp-1)*2+1,ew=Math.min(6,sp*2);if(sp>3||t.path("week_end").asInt()<sw||t.path("week_start").asInt()>ew)sprint.add(mapper.createObjectNode().put("kind","sprint").put("message",t.path("id").asText()+" 的执行周与 "+sid+" 的 Sprint "+sp+" 不一致"));}}} r.set("loadRisk",load); r.set("dependencyRisk",dep); r.set("sprintRisk",sprint); return r; }
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
    private List<String> dependencyRefs(JsonNode source){
        List<String> result=new ArrayList<>();
        for(String key:List.of("depends_on","dependsOn","dependencies"))addDependencyValues(source.get(key),result);
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
        for(JsonNode node:rows){ObjectNode task=(ObjectNode)node;for(String story:task.path("story_ref").asText("").split(","))if(!story.isBlank())byStory.computeIfAbsent(story,k->new ArrayList<>()).add(task);}
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
                if(best!=null){task.put("depends_on",best.path("id").asText());warnings.add("为 "+task.path("id").asText()+" 自动补充了推断依赖 "+best.path("id").asText());}
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
    private boolean containsAny(String value,String...terms){for(String term:terms)if(value.contains(term))return true;return false;}
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
    private void repairDependencySchedule(ArrayNode rows,ArrayNode warnings){Map<String,ObjectNode> byId=new HashMap<>();rows.forEach(n->byId.put(n.path("id").asText(),(ObjectNode)n));for(int pass=0;pass<rows.size();pass++){boolean changed=false;for(JsonNode n:rows){ObjectNode t=(ObjectNode)n;for(String ref:t.path("depends_on").asText("").split(",")){ObjectNode p=byId.get(ref);if(p==null)continue;int start=t.path("week_start").asInt(),end=t.path("week_end").asInt(),required=p.path("week_end").asInt();if(start<required){int duration=end-start;int nextEnd=required+duration;if(nextEnd>6)throw ApiException.badRequest(t.path("id").asText()+" 按依赖 "+ref+" 顺延后超出 W6");t.put("week_start",required);t.put("week_end",nextEnd);warnings.add(t.path("id").asText()+" 已按依赖 "+ref+" 自动顺延到 W"+required+"-W"+nextEnd);changed=true;}}}if(!changed)break;}}
    private void validateDependencySchedule(JsonNode rows,boolean execution){Map<String,JsonNode> byId=new HashMap<>();rows.forEach(t->byId.put(t.path("id").asText(),t));for(JsonNode task:rows)for(String dependency:task.path("depends_on").asText("").split(",")){if(dependency.isBlank())continue;JsonNode predecessor=byId.get(dependency);if(predecessor!=null&&task.path("week_start").asInt()<predecessor.path("week_end").asInt())throw dependencyError(task.path("id").asText()+" 的开始周早于前置任务 "+dependency+" 的结束周",execution);}}
    private ArrayNode warnings(ObjectNode risks){ArrayNode a=mapper.createArrayNode(); risks.fields().forEachRemaining(e->e.getValue().forEach(n->a.add(n.path("message").asText()))); return a;}
    private void detectCycles(JsonNode rows,boolean execution){Map<String,String> deps=new HashMap<>(); rows.forEach(t->deps.put(t.path("id").asText(),t.path("depends_on").asText(""))); for(String id:deps.keySet()) if(cycle(id,id,deps,new HashSet<>())) throw dependencyError("任务依赖存在循环: "+id,execution);}
    private boolean cycle(String current,String target,Map<String,String> deps,Set<String> seen){for(String d:deps.getOrDefault(current,"").split(",")){if(d.isBlank())continue; if(d.equals(target))return true; if(seen.add(d)&&cycle(d,target,deps,seen))return true;}return false;}
    private ApiException dependencyError(String message,boolean execution){return execution?ApiException.conflict(message):ApiException.badRequest(message);}
    private String refs(List<String> values,Map<String,String> ids,String currentId){LinkedHashSet<String> normalized=new LinkedHashSet<>();for(String value:values){String key=value.trim().toUpperCase(Locale.ROOT);if(key.isBlank())continue;String mapped=ids.get(key);if(mapped==null)throw ApiException.badRequest("引用不存在: "+value.trim());if(mapped.equals(currentId))throw ApiException.badRequest("任务不能依赖自身: "+currentId);if(!normalized.add(mapped))throw ApiException.badRequest("任务依赖重复: "+currentId+" -> "+mapped);}return String.join(",",normalized);}
    private String refs(String value,Map<String,String> ids){StringBuilder b=new StringBuilder(); for(String p:value.split(",")){String key=p.trim().toUpperCase();if(key.isBlank())continue; String mapped=ids.get(key); if(mapped==null) throw ApiException.badRequest("引用不存在: "+p.trim()); if(b.length()>0)b.append(',');b.append(mapped);}return b.toString();}
    private String text(JsonNode n,String key,String fallback){JsonNode v=n.get(key);return v==null||v.isNull()?fallback:v.asText(fallback).trim();}
    private int boundedInt(JsonNode n,String key,int fallback,int min,int max){JsonNode v=n.get(key);int x=v!=null&&v.isIntegralNumber()?v.asInt():fallback;return Math.max(min,Math.min(max,x));}
    private Integer nullableInt(JsonNode n,String... keys){for(String k:keys){JsonNode v=n.get(k);if(v!=null&&!v.isNull()&&v.isIntegralNumber())return v.asInt();}return null;}
    private String priority(String p){return Set.of("Must","Should","Could").contains(p)?p:"Should";}
    private ArrayNode array(JsonNode n,String key){return n.path(key).isArray()?(ArrayNode)n.path(key):mapper.createArrayNode();}
    private ObjectNode object(JsonNode n,String key){return n.path(key).isObject()?(ObjectNode)n.path(key):mapper.createObjectNode();}
    private int firstMemberId(){User u=users.selectOne(new QueryWrapper<User>().orderByAsc("id").last("LIMIT 1"));if(u==null)throw ApiException.badRequest("当前没有可分配成员");return u.getId();}
    private int nextNumber(java.util.List<String> ids,Pattern pattern){int max=0;for(String id:ids){Matcher m=pattern.matcher(id==null?"":id);if(m.matches())max=Math.max(max,Integer.parseInt(m.group(1)));}return max+1;}
}
