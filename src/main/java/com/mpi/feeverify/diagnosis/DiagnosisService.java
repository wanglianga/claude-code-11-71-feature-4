package com.mpi.feeverify.diagnosis;

import com.mpi.feeverify.registration.RegistrationService;
import com.mpi.feeverify.support.Jsons;
import com.mpi.feeverify.support.Params;
import com.mpi.feeverify.support.SqlRewrite;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.time.YearMonth;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * 规则诊断器：按 (登记, 参数签名, 计划哈希) 分别建档。
 * 相同 SQL 在不同参数范围下出现不同计划时，各自独立建档，
 * 不允许把小范围参数下的结论推广到全省大范围。
 */
@Service
public class DiagnosisService {

    private static final Pattern TO_CHAR_ON_DATE =
            Pattern.compile("(?i)to_char\\(\\s*d\\.settle_date");
    private static final Pattern TYPE_CONVERSION =
            Pattern.compile("(?i)(cast\\s*\\(|::\\s*text)");

    private final JdbcTemplate jdbc;
    private final RegistrationService registrationService;

    @Value("#{'${verify.policy-switch-months:2026-01}'.split(',')}")
    private List<String> policySwitchMonths;

    public DiagnosisService(JdbcTemplate jdbc, RegistrationService registrationService) {
        this.jdbc = jdbc;
        this.registrationService = registrationService;
    }

    /** 对该登记下所有已采集的 (参数签名, 计划哈希) 组合分别建档。 */
    public List<Map<String, Object>> diagnose(long registrationId) {
        String sql = registrationService.sqlOf(registrationId);
        List<Map<String, Object>> groups = jdbc.queryForList(
                "SELECT DISTINCT param_signature, param_class, plan_hash FROM plan_capture"
                        + " WHERE registration_id = ? ORDER BY param_signature", registrationId);
        List<Map<String, Object>> out = new ArrayList<>();
        for (Map<String, Object> g : groups) {
            String signature = String.valueOf(g.get("param_signature"));
            String planHash = String.valueOf(g.get("plan_hash"));
            Map<String, Object> capture = jdbc.queryForMap(
                    "SELECT params, plan_json, est_rows FROM plan_capture"
                            + " WHERE registration_id = ? AND param_signature = ? AND plan_hash = ?"
                            + " ORDER BY id DESC LIMIT 1",
                    registrationId, signature, planHash);
            Map<String, Object> params = Jsons.asMap(capture.get("params"));
            String planJson = String.valueOf(capture.get("plan_json"));
            long estRows = ((Number) capture.get("est_rows")).longValue();

            List<Map<String, Object>> findings = buildFindings(sql, params, planJson, estRows);

            Long id = jdbc.queryForObject(
                    "INSERT INTO diagnosis (registration_id, param_signature, param_class, plan_hash, findings)"
                            + " VALUES (?,?,?,?,?::jsonb)"
                            + " ON CONFLICT (registration_id, param_signature, plan_hash)"
                            + " DO UPDATE SET findings = EXCLUDED.findings, created_at = now()"
                            + " RETURNING id",
                    Long.class, registrationId, signature, String.valueOf(g.get("param_class")),
                    planHash, Jsons.write(findings));

            Map<String, Object> item = new LinkedHashMap<>();
            item.put("diagnosisId", id);
            item.put("paramSignature", signature);
            item.put("paramClass", g.get("param_class"));
            item.put("planHash", planHash);
            item.put("findings", findings);
            out.add(item);
        }
        return out;
    }

    private List<Map<String, Object>> buildFindings(String sql, Map<String, Object> params,
                                                    String planJson, long estRows) {
        List<Map<String, Object>> findings = new ArrayList<>();

        // 1. 可索引化条件：to_char 包装使 settle_date 索引失效
        if (TO_CHAR_ON_DATE.matcher(sql).find() && hasIndex("idx_snap_fee_detail_date")) {
            findings.add(finding("INDEXABLE_CONDITION", "HIGH",
                    "settle_date 上存在索引 idx_snap_fee_detail_date，但 to_char(d.settle_date,'YYYY-MM') "
                            + "包装使条件不可索引化，退化为全表扫描",
                    "改写为 settle_date >= to_date(:monthFrom,'YYYY-MM') AND settle_date < "
                            + "to_date(:monthTo,'YYYY-MM') + interval '1 month' 的半开区间条件"));
        }
        // 2. 隐式/显式类型转换
        if (TYPE_CONVERSION.matcher(sql).find()) {
            findings.add(finding("IMPLICIT_CONVERSION", "MEDIUM",
                    "WHERE 子句中存在类型转换（CAST/::text），可能阻断索引使用并改变比较语义",
                    "让参数类型与列类型一致，移除列侧转换"));
        }
        // 3. 冗余关联
        Set<String> redundant = SqlRewrite.redundantJoinAliases(sql);
        for (String alias : redundant) {
            findings.add(finding("REDUNDANT_JOIN", "MEDIUM",
                    "连接 " + alias + " 未在 SELECT/WHERE/ORDER BY 中使用，属冗余关联",
                    "消除该连接或确认其仅用于行数放大校验"));
        }
        // 4. 跨期过滤
        YearMonth from = Params.parseMonth(String.valueOf(params.get("monthFrom")));
        YearMonth to = Params.parseMonth(String.valueOf(params.get("monthTo")));
        for (String switchMonth : policySwitchMonths) {
            YearMonth sw = YearMonth.parse(switchMonth);
            if (!from.isAfter(sw) && !to.isBefore(sw)) {
                findings.add(finding("CROSS_PERIOD_FILTER", "HIGH",
                        "查询区间跨政策切换期 " + switchMonth + "，跨期维表关联可能改变费用口径",
                        "按政策期拆分查询并分别验证，避免把口径变化误当改写差异"));
            }
        }
        long span = (to.getYear() - from.getYear()) * 12L + (to.getMonthValue() - from.getMonthValue()) + 1;
        if (span > 3) {
            findings.add(finding("CROSS_PERIOD_FILTER", "MEDIUM",
                    "查询区间跨度 " + span + " 个月，扫描量大、月结高峰期易拖慢共享库",
                    "考虑预聚合或分页改写，并限制发布门禁的参数范围"));
        }
        // 5. 计划级：顺序扫描 + 扫描量
        if (planJson.contains("Seq Scan on snap_fee_detail") && estRows > 10000) {
            findings.add(finding("FULL_SCAN", "HIGH",
                    "当前参数范围下执行计划为 Seq Scan on snap_fee_detail，估计扫描 " + estRows + " 行",
                    "优先采用条件下推/预聚合候选，避免月结期全表扫描"));
        }
        return findings;
    }

    private boolean hasIndex(String indexName) {
        return Boolean.TRUE.equals(jdbc.queryForObject(
                "SELECT EXISTS(SELECT 1 FROM pg_indexes WHERE indexname = ?)", Boolean.class, indexName));
    }

    private Map<String, Object> finding(String type, String severity, String detail, String suggestion) {
        Map<String, Object> f = new LinkedHashMap<>();
        f.put("type", type);
        f.put("severity", severity);
        f.put("detail", detail);
        f.put("suggestion", suggestion);
        return f;
    }

    public List<Map<String, Object>> list(long registrationId) {
        return Jsons.normalizeRows(jdbc.queryForList(
                "SELECT id, param_signature, param_class, plan_hash, findings, created_at"
                        + " FROM diagnosis WHERE registration_id = ? ORDER BY id", registrationId));
    }
}
