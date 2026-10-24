package com.aicap.service;

import com.aicap.common.ApiException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

class QualityTestSetServiceTest {
    private JdbcTemplate jdbc;
    private QualityTestSetService service;

    @BeforeEach void setup() {
        var ds = new DriverManagerDataSource("jdbc:h2:mem:" + UUID.randomUUID()
                + ";MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1", "sa", "");
        jdbc = new JdbcTemplate(ds);
        jdbc.execute("CREATE TABLE quality_test_sets(id bigint NOT NULL AUTO_INCREMENT,project_id varchar(20) NOT NULL,"
                + "code varchar(20) NOT NULL,name varchar(200) NOT NULL,source varchar(20) NOT NULL,"
                + "cases_json varchar(10000) NOT NULL,created_by int NOT NULL,created_at timestamp NOT NULL,"
                + "primary key(id),unique(project_id,code))");
        service = new QualityTestSetService(jdbc);
    }

    private QualityTestSetService.CreateRequest request(String name, String source, String... caseNames) {
        return new QualityTestSetService.CreateRequest(name, source,
                java.util.Arrays.stream(caseNames).map(caseName -> new QualityTestSetService.CaseIn(caseName, null)).toList());
    }

    @Test void keywordInCaseNameMarksTheBoundaryAsCovered() {
        var created = service.create("P001", 7, request("登录测试集", "test_case",
                "用户名为空时登录失败", "超过最大长度的用户名被拒绝", "重复提交同一订单只创建一次"));
        assertEquals("TS1", created.get("code"));
        assertEquals("test_case", created.get("source"));
        assertEquals(3, created.get("total"));
        assertEquals(List.of("empty_value", "upper_lower_limit", "duplicate_idempotent"),
                created.get("covered_boundaries"));
        assertEquals(List.of("0 值与负数", "权限与越权", "超时、并发与重试", "非法格式与超长输入", "大数据量与批量"),
                created.get("missing_boundaries"));
    }

    @Test void explicitBoundaryKindWinsEvenWhenNameHasNoKeyword() {
        var created = service.create("P001", 7, new QualityTestSetService.CreateRequest(
                "执行报告回归", "test_report",
                List.of(new QualityTestSetService.CaseIn("case_001", "permission"))));
        assertEquals(List.of("permission"), created.get("covered_boundaries"));
        assertEquals(7, ((List<?>) created.get("missing_boundaries")).size());
    }

    @Test void coveredBoundariesAreOrderedAndDeduplicated() {
        var created = service.create("P001", 7, request("综合测试集", "test_code",
                "空值输入", "null 输入", "并发下单超时重试", "权限校验"));
        assertEquals(List.of("empty_value", "permission", "timeout_concurrency"),
                created.get("covered_boundaries"));
    }

    @Test void everyBoundaryCategoryCanBeClaimedByExplicitKind() {
        var all = QualityTestSetService.BOUNDARIES.keySet().stream()
                .map(kind -> new QualityTestSetService.CaseIn("用例-" + kind, kind)).toList();
        var created = service.create("P001", 7,
                new QualityTestSetService.CreateRequest("全覆盖", "test_case", all));
        assertEquals(0, ((List<?>) created.get("missing_boundaries")).size());
        assertTrue(service.findings("P001").isEmpty());
    }

    @Test void missingBoundariesBecomeLocatedFindings() {
        service.create("P001", 7, request("登录测试集", "test_case", "用户名为空时登录失败"));
        var findings = service.findings("P001");
        assertEquals(1, findings.size());
        var finding = findings.get(0);
        assertEquals(QualityTestSetService.FINDING_KIND, finding.get("kind"));
        assertEquals("测试质量", finding.get("category"));
        assertEquals("TS1", finding.get("source_code"));
        assertEquals("登录测试集", finding.get("source_name"));
        assertEquals("测试集 TS1 · 登录测试集", finding.get("location"));
        assertTrue(String.valueOf(finding.get("detail")).contains("1/8"));
        assertTrue(String.valueOf(finding.get("suggestion")).contains("权限与越权"));
    }

    @Test void otherProjectTestSetsAreNotCounted() {
        service.create("P001", 7, request("本项目", "test_case", "空值输入"));
        service.create("P002", 7, request("其他项目", "test_case", "空值输入"));
        assertEquals(1, service.analyze("P001").get("sets_total"));
        assertEquals(1, ((List<?>) service.analyze("P001").get("sets")).size());
        assertEquals(8, service.analyze("P001").get("boundaries_total"));
        assertEquals(1, service.findings("P001").size());
    }

    @Test void analyzeSummarizesCaseAndBoundaryTotals() {
        service.create("P001", 7, request("测试集一", "test_case", "空值输入", "权限校验"));
        service.create("P001", 7, request("测试集二", "test_report", "批量导入大数据量"));
        var analyzed = service.analyze("P001");
        assertEquals(2, analyzed.get("sets_total"));
        assertEquals(3, analyzed.get("cases_total"));
        assertEquals(8, analyzed.get("boundaries_total"));
        assertEquals("仅按用例名称/显式边界类别判断覆盖，不执行测试，也不推断测试代码质量。", analyzed.get("note"));
    }

    @Test void invalidInputIsRejectedWithUnprocessable() {
        assertEquals(422, assertThrows(ApiException.class, () -> service.create("P001", 7,
                request(" ", "test_case", "用例"))).getStatus());
        assertEquals(422, assertThrows(ApiException.class, () -> service.create("P001", 7,
                request("名称", "unknown_source", "用例"))).getStatus());
        assertEquals(422, assertThrows(ApiException.class, () -> service.create("P001", 7,
                new QualityTestSetService.CreateRequest("名称", "test_case", List.of()))).getStatus());
        assertEquals(422, assertThrows(ApiException.class, () -> service.create("P001", 7,
                request("名称", "test_case", " "))).getStatus());
        assertEquals(422, assertThrows(ApiException.class, () -> service.create("P001", 7,
                new QualityTestSetService.CreateRequest("名称", "test_case",
                        List.of(new QualityTestSetService.CaseIn("用例", "bad_kind"))))).getStatus());
        assertEquals(422, assertThrows(ApiException.class, () -> service.create("P001", 7,
                request("名".repeat(201), "test_case", "用例"))).getStatus());
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM quality_test_sets", Integer.class));
    }

    @Test void testSetCodeIsDerivedFromTheInsertedId() {
        assertEquals("TS1", service.create("P001", 7, request("一", "test_case", "用例")).get("code"));
        assertEquals("TS2", service.create("P002", 7, request("二", "test_case", "用例")).get("code"));
        assertTrue(service.exists("P001", "TS1"));
        assertFalse(service.exists("P001", "TS2"), "来源码按项目隔离");
    }
}
