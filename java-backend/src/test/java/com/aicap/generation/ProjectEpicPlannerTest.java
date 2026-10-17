package com.aicap.generation;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ProjectEpicPlannerTest {
    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    void differentStoryCorporaProduceDifferentEpicNames() {
        ObjectNode clinic = draft("Clinic Appointment Portal", List.of("Patient registration", "Doctor availability", "Appointment booking"));
        ObjectNode warehouse = draft("Warehouse Inventory System", List.of("Stock receiving", "Warehouse picking", "Inventory audit"));

        ProjectEpicPlanner.normalize(clinic, mapper);
        ProjectEpicPlanner.normalize(warehouse, mapper);

        List<String> clinicNames = clinic.path("activities").findValuesAsText("name");
        List<String> warehouseNames = warehouse.path("activities").findValuesAsText("name");
        assertFalse(clinicNames.isEmpty());
        assertFalse(warehouseNames.isEmpty());
        assertFalse(clinicNames.equals(warehouseNames));
        assertTrue(((ArrayNode) clinic.path("stories")).findValuesAsText("activity").stream().allMatch(value -> Integer.parseInt(value) >= 1));
        assertTrue(((ArrayNode) warehouse.path("stories")).findValuesAsText("activity").stream().allMatch(value -> Integer.parseInt(value) >= 1));
    }

    @Test
    void importWithoutActivitiesCreatesSeveralBusinessGroups() {
        ObjectNode raw = mapper.createObjectNode();
        raw.set("project", mapper.createObjectNode().put("name", "设备借用管理"));
        ArrayNode stories = mapper.createArrayNode();
        String[] titles = {"用户注册与登录", "角色与权限管理", "设备分类与基础信息维护", "库存查看与设备搜索",
                "借用申请与审批", "借用时间冲突校验", "设备维修登记", "设备归还处理", "借用统计报表"};
        for (int i = 0; i < titles.length; i++) stories.add(mapper.createObjectNode().put("id", "US" + String.format("%02d", i + 1)).put("title", titles[i]));
        raw.set("stories", stories);

        ProjectEpicPlanner.normalizeImportedActivities(raw, mapper);

        assertTrue(raw.path("activities").size() >= 3);
        assertTrue(raw.path("activities").size() <= 8);
        assertTrue(raw.path("stories").findValuesAsText("id").contains("US01"));
        assertTrue(raw.path("stories").findValuesAsText("id").contains("US09"));
        assertTrue(raw.path("stories").findValuesAsText("activity").stream().distinct().count() >= 3);
    }

    @Test
    void defaultImportedActivityDoesNotSuppressSemanticClustering() {
        ObjectNode raw = mapper.createObjectNode();
        raw.set("project", mapper.createObjectNode().put("name", "设备借用管理"));
        ArrayNode stories = mapper.createArrayNode();
        String[] titles = {"用户注册与登录", "角色与权限管理", "设备分类维护", "设备查询", "借用申请与审批", "设备归还处理"};
        for (int i = 0; i < titles.length; i++) stories.add(mapper.createObjectNode()
                .put("id", "US" + String.format("%02d", i + 1)).put("title", titles[i]).put("activity", 1)
                .put("_import_activity_explicit", false));
        raw.set("stories", stories);

        ProjectEpicPlanner.normalizeImportedActivities(raw, mapper);

        assertTrue(raw.path("activities").size() >= 3);
        assertTrue(raw.path("stories").findValuesAsText("activity").stream().distinct().count() >= 3);
    }

    @Test
    void thirtyImportedStoriesProduceAtMostSixActivitiesWithoutChangingStories() {
        ObjectNode raw = mapper.createObjectNode();
        raw.set("project", mapper.createObjectNode().put("name", "综合业务管理系统"));
        ArrayNode stories = mapper.createArrayNode();
        String[] titles = {"用户注册", "用户登录", "角色权限管理", "账号安全认证", "设备分类维护", "设备库存查看",
                "设备详情查询", "基础信息维护", "借用申请", "借用审批", "申请进度查看", "借用冲突校验", "设备出库",
                "设备归还", "预约申请", "订单办理", "超期提醒", "设备损坏登记", "维修记录", "设备报废管理",
                "异常申诉", "消息通知", "系统公告", "协同反馈", "利用率统计", "数据分析报表", "数据导出",
                "操作日志审计", "系统配置", "发布验收"};
        for (int i = 0; i < titles.length; i++) stories.add(mapper.createObjectNode()
                .put("id", "US" + String.format("%02d", i + 1)).put("title", titles[i]).put("activity", 1)
                .put("_import_activity_explicit", false));
        raw.set("stories", stories);

        ProjectEpicPlanner.normalizeImportedActivities(raw, mapper);

        assertEquals(30, raw.path("stories").size());
        assertTrue(raw.path("activities").size() >= 4 && raw.path("activities").size() <= 6);
        assertEquals(30, raw.path("stories").findValuesAsText("id").stream().distinct().count());
        assertTrue(raw.path("activities").findValuesAsText("name").stream().noneMatch(name -> name.startsWith("与") || name.endsWith("功能")));
    }

    @Test
    void naturalLanguageActivityCorpusDoesNotCollapseIntoIdentityDomain() {
        ObjectNode raw = mapper.createObjectNode();
        raw.set("project", mapper.createObjectNode().put("name", "Volunteer Service Platform"));
        ArrayNode stories = mapper.createArrayNode();
        String[] titles = {
                "用户注册与登录", "角色权限管理", "志愿活动创建", "活动信息维护", "活动发布",
                "活动报名申请", "报名审核", "签到记录", "签退与服务时长", "消息通知提醒",
                "系统公告", "异常签到处理", "取消活动", "评价反馈", "服务时长统计",
                "统计报表导出", "后台用户管理", "活动状态查询", "报名进度查看", "系统验收"
        };
        for (int i = 0; i < titles.length; i++) stories.add(mapper.createObjectNode()
                .put("id", "US" + String.format("%02d", i + 1)).put("title", titles[i]));
        raw.set("stories", stories);

        ProjectEpicPlanner.normalize(raw, mapper);

        assertTrue(raw.path("activities").size() >= 4, raw.toString());
        assertTrue(raw.path("activities").size() <= 8, raw.toString());
        assertTrue(raw.path("stories").findValuesAsText("activity").stream().distinct().count() >= 4, raw.toString());
    }

    @Test
    void existingNonEmptyActivityAssignmentsArePreserved() {
        ObjectNode raw = mapper.createObjectNode();
        raw.set("project", mapper.createObjectNode().put("name", "Existing Plan"));
        ArrayNode activities = raw.putArray("activities");
        activities.add(mapper.createObjectNode().put("id", 1).put("name", "用户与权限"));
        activities.add(mapper.createObjectNode().put("id", 2).put("name", "核心流程"));
        activities.add(mapper.createObjectNode().put("id", 3).put("name", "通知协同"));
        activities.add(mapper.createObjectNode().put("id", 4).put("name", "统计后台"));
        raw.putArray("epics").add(mapper.createObjectNode().put("id", "E1").put("title", "用户与权限").put("activity", 1));
        ArrayNode stories = raw.putArray("stories");
        for (int i = 0; i < 20; i++) stories.add(mapper.createObjectNode().put("id", "US" + (i + 1))
                .put("title", "Story " + (i + 1)).put("activity", i % 4 + 1));

        ProjectEpicPlanner.normalize(raw, mapper);

        assertEquals(4, raw.path("activities").size());
        assertEquals("用户与权限", raw.path("activities").get(0).path("name").asText());
        assertEquals(4, raw.path("stories").findValuesAsText("activity").stream().distinct().count());
    }

    @Test
    void collapsedDeclaredActivitiesFallBackToSemanticRecovery() {
        ObjectNode raw = mapper.createObjectNode();
        raw.set("project", mapper.createObjectNode().put("name", "业务协同平台"));
        raw.putArray("activities")
                .add(mapper.createObjectNode().put("id", 1).put("name", "域一"))
                .add(mapper.createObjectNode().put("id", 2).put("name", "域二"))
                .add(mapper.createObjectNode().put("id", 3).put("name", "域三"));
        raw.putArray("epics")
                .add(mapper.createObjectNode().put("id", "E1").put("title", "域一").put("activity", 1))
                .add(mapper.createObjectNode().put("id", "E2").put("title", "域二").put("activity", 2))
                .add(mapper.createObjectNode().put("id", "E3").put("title", "域三").put("activity", 3));
        ArrayNode stories = raw.putArray("stories");
        String[] titles = {"用户注册", "角色权限管理", "活动创建", "报名审核", "签到签退", "消息通知",
                "服务时长统计", "异常处理", "数据统计", "管理员后台", "活动评价", "报名状态查询"};
        for (int i = 0; i < titles.length; i++) stories.add(mapper.createObjectNode()
                .put("id", "US" + (i + 1)).put("title", titles[i]).put("activity", 1));

        ProjectEpicPlanner.normalize(raw, mapper);

        assertTrue(raw.path("activities").size() >= 3, raw.toString());
        assertTrue(raw.path("stories").findValuesAsText("activity").stream().distinct().count() >= 3,
                raw.toString());
    }

    private ObjectNode draft(String projectName, List<String> titles) {
        ObjectNode raw = mapper.createObjectNode();
        raw.set("project", mapper.createObjectNode().put("name", projectName).put("description", projectName + " planning"));
        ArrayNode stories = mapper.createArrayNode();
        int id = 1;
        for (String title : titles) stories.add(mapper.createObjectNode().put("id", "S" + id++).put("title", title).put("sprint", 1));
        raw.set("stories", stories);
        return raw;
    }
}
