package com.aicap.service;

import com.aicap.common.ApiException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * US22 测试质量输入:接收「测试用例 / 测试代码 / 执行报告」里的用例集合,
 * 按固定边界类别识别覆盖情况,把遗漏的边界类别定位成可确认的质量缺口。
 *
 * 只做「用例声明层面的覆盖判断」,不执行测试、不推断测试代码质量。
 */
@Service
public class QualityTestSetService {
    public static final String FINDING_KIND = "test_missing_boundary_cases";

    /** 来源类型 → 展示名。 */
    public static final Map<String, String> SOURCES = new LinkedHashMap<>(Map.of(
            "test_case", "测试用例", "test_code", "测试代码", "test_report", "执行报告"));

    /** 边界类别 → 展示名。顺序即分析输出顺序。 */
    public static final Map<String, String> BOUNDARIES = new LinkedHashMap<>();
    private static final Map<String, Pattern> KEYWORDS = new LinkedHashMap<>();

    static {
        boundary("empty_value", "空值与空集合", "空|empty|null|缺失");
        boundary("zero_or_negative", "0 值与负数", "零|负|(?<!\\d)0(?!\\d)|negative|zero");
        boundary("upper_lower_limit", "上下限与极值", "上限|下限|最大|最小|极值|边界|max|min|limit|boundary");
        boundary("duplicate_idempotent", "重复提交与幂等", "重复|幂等|duplicate|idempotent");
        boundary("permission", "权限与越权", "权限|越权|未授权|无授权|403|unauthorized|forbidden");
        boundary("timeout_concurrency", "超时、并发与重试", "超时|并发|重试|timeout|concurrent|retry");
        boundary("invalid_format", "非法格式与超长输入", "非法|格式|超长|编码|invalid|malformed|encoding");
        boundary("large_data", "大数据量与批量", "批量|大量|bulk|large");
    }

    private static void boundary(String kind, String label, String regex) {
        BOUNDARIES.put(kind, label);
        KEYWORDS.put(kind, Pattern.compile(regex, Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE));
    }

    public record CaseIn(String name, String boundary_kind) {}
    public record CreateRequest(String name, String source, List<CaseIn> cases) {}

    private final JdbcTemplate jdbc;
    private final ObjectMapper mapper = new ObjectMapper();

    public QualityTestSetService(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** 保存一组测试集并返回本次覆盖/遗漏的边界类别(不建缺口,缺口由 findings 统一给出)。 */
    @Transactional
    public Map<String, Object> create(String projectId, int userId, CreateRequest request) {
        String name = request == null || request.name() == null ? "" : request.name().strip();
        if (name.isEmpty()) throw ApiException.unprocessable("测试集名称必填");
        if (name.length() > 200) throw ApiException.unprocessable("测试集名称不能超过 200 字");
        String source = request.source() == null ? "" : request.source().strip();
        if (!SOURCES.containsKey(source)) throw ApiException.unprocessable("测试集来源无效");
        List<CaseIn> cases = request.cases() == null ? List.of() : request.cases();
        if (cases.isEmpty()) throw ApiException.unprocessable("测试集至少包含 1 条用例");
        if (cases.size() > 500) throw ApiException.unprocessable("测试集最多 500 条用例");

        var normalized = new ArrayList<Map<String, Object>>();
        for (CaseIn item : cases) {
            String caseName = item == null || item.name() == null ? "" : item.name().strip();
            if (caseName.isEmpty()) throw ApiException.unprocessable("用例名称必填");
            if (caseName.length() > 200) throw ApiException.unprocessable("用例名称不能超过 200 字");
            String declared = item.boundary_kind() == null ? "" : item.boundary_kind().strip();
            if (!declared.isEmpty() && !BOUNDARIES.containsKey(declared))
                throw ApiException.unprocessable("边界类别无效：" + declared);
            var row = new LinkedHashMap<String, Object>();
            row.put("name", caseName);
            row.put("boundary_kind", declared.isEmpty() ? null : declared);
            normalized.add(row);
        }

        String json;
        try {
            json = mapper.writeValueAsString(normalized);
        } catch (Exception e) {
            throw ApiException.unprocessable("测试集内容无法序列化");
        }
        // 先插临时码再按自增 id 回填 TS{id},避免并发下抢同一个唯一码。
        String temp = "TMP" + UUID.randomUUID().toString().replace("-", "").substring(0, 12);
        jdbc.update("INSERT INTO quality_test_sets(project_id,code,name,source,cases_json,created_by,created_at) "
                + "VALUES (?,?,?,?,?,?,CURRENT_TIMESTAMP)", projectId, temp, name, source, json, userId);
        Long id = jdbc.queryForObject("SELECT id FROM quality_test_sets WHERE project_id=? AND code=?",
                Long.class, projectId, temp);
        String code = "TS" + id;
        jdbc.update("UPDATE quality_test_sets SET code=? WHERE project_id=? AND id=?", code, projectId, id);
        return view(projectId, code);
    }

    public boolean exists(String projectId, String code) {
        Integer n = jdbc.queryForObject("SELECT COUNT(*) FROM quality_test_sets WHERE project_id=? AND code=?",
                Integer.class, projectId, code);
        return n != null && n > 0;
    }

    /** 每份测试集一条缺口:遗漏的边界类别带定位与补测建议;全覆盖则不出缺口。 */
    public List<Map<String, Object>> findings(String projectId) {
        var findings = new ArrayList<Map<String, Object>>();
        for (var row : rows(projectId)) {
            var analyzed = analyzeRow(row);
            @SuppressWarnings("unchecked")
            var missing = (List<String>) analyzed.get("missing_boundaries");
            if (missing.isEmpty()) continue;
            // missing_boundaries 存的已是中文展示名，直接拼接，不能再查 BOUNDARIES。
            String labels = String.join("、", missing);
            @SuppressWarnings("unchecked")
            var covered = (List<String>) analyzed.get("covered_boundaries");
            var item = new LinkedHashMap<String, Object>();
            item.put("kind", FINDING_KIND);
            item.put("category", "测试质量");
            item.put("source_code", analyzed.get("code"));
            item.put("source_name", analyzed.get("name"));
            item.put("location", "测试集 " + analyzed.get("code") + " · " + analyzed.get("name"));
            item.put("detail", "已提交 " + analyzed.get("total") + " 条用例，覆盖 "
                    + covered.size() + "/" + BOUNDARIES.size() + " 类边界场景；缺少：" + labels + "。");
            item.put("suggestion", "补充覆盖" + labels + "的边界用例并重新提交测试集后，再确认关闭该缺口。");
            findings.add(item);
        }
        return findings;
    }

    /** GET /quality-analysis 的 test_quality 段:每个测试集的用例数与边界覆盖缺口。 */
    public Map<String, Object> analyze(String projectId) {
        var sets = new ArrayList<Map<String, Object>>();
        int casesTotal = 0;
        for (var row : rows(projectId)) {
            var analyzed = analyzeRow(row);
            casesTotal += ((Number) analyzed.get("total")).intValue();
            sets.add(analyzed);
        }
        var result = new LinkedHashMap<String, Object>();
        result.put("sets_total", sets.size());
        result.put("cases_total", casesTotal);
        result.put("boundaries_total", BOUNDARIES.size());
        result.put("sets", sets);
        result.put("note", "仅按用例名称/显式边界类别判断覆盖，不执行测试，也不推断测试代码质量。");
        return result;
    }

    private Map<String, Object> view(String projectId, String code) {
        var row = jdbc.queryForMap("SELECT id,code,name,source,cases_json FROM quality_test_sets "
                + "WHERE project_id=? AND code=?", projectId, code);
        return analyzeRow(row);
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> analyzeRow(Map<String, Object> row) {
        List<Map<String, Object>> cases;
        try {
            cases = mapper.readValue(String.valueOf(row.get("cases_json")), List.class);
        } catch (Exception e) {
            cases = List.of();
        }
        Set<String> covered = new LinkedHashSet<>();
        for (var item : cases) {
            Object declared = item.get("boundary_kind");
            if (declared != null && BOUNDARIES.containsKey(String.valueOf(declared))) {
                covered.add(String.valueOf(declared));
                continue;
            }
            String name = item.get("name") == null ? "" : String.valueOf(item.get("name"));
            for (var entry : KEYWORDS.entrySet()) {
                if (entry.getValue().matcher(name).find()) covered.add(entry.getKey());
            }
        }
        var coveredOrdered = BOUNDARIES.keySet().stream().filter(covered::contains).toList();
        var missing = BOUNDARIES.keySet().stream().filter(kind -> !covered.contains(kind))
                .map(BOUNDARIES::get).toList();
        var result = new LinkedHashMap<String, Object>();
        result.put("id", row.get("id"));
        result.put("code", row.get("code"));
        result.put("name", row.get("name"));
        result.put("source", row.get("source"));
        result.put("total", cases.size());
        result.put("cases", cases);
        result.put("covered_boundaries", coveredOrdered);
        result.put("missing_boundaries", missing);
        return result;
    }

    private List<Map<String, Object>> rows(String projectId) {
        return jdbc.queryForList("SELECT id,code,name,source,cases_json FROM quality_test_sets "
                + "WHERE project_id=? ORDER BY id", projectId);
    }
}
