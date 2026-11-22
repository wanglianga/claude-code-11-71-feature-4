package com.mpi.feeverify.judge;

import com.mpi.feeverify.candidate.CandidateService;
import com.mpi.feeverify.support.ApiException;
import com.mpi.feeverify.support.Jsons;
import com.mpi.feeverify.support.Params;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

/**
 * 结果裁判器：比较影子执行的明细主键集合、金额、机构级分组，并定位缺失原因。
 * 总金额相同而机构分组不同时一律阻断发布，并在 org_group_diff 中定位差异组。
 */
@Service
public class JudgeService {

    private final JdbcTemplate jdbc;
    private final CandidateService candidateService;

    public JudgeService(JdbcTemplate jdbc, CandidateService candidateService) {
        this.jdbc = jdbc;
        this.candidateService = candidateService;
    }

    @SuppressWarnings("unchecked")
    public Map<String, Object> judge(long candidateId) {
        Map<String, Object> candidate = candidateService.get(candidateId);
        String compareMode = String.valueOf(candidate.get("compare_mode"));

        Map<String, Object> original = latestSuccess(candidateId, "ORIGINAL");
        Map<String, Object> cand = latestSuccess(candidateId, "CANDIDATE");
        if (original == null || cand == null) {
            throw ApiException.conflict("候选 " + candidateId + " 缺少成功的影子执行，请先调用 shadow 接口");
        }
        Map<String, Object> params = Jsons.asMap(original.get("params"));
        if (!params.equals(Jsons.asMap(cand.get("params")))) {
            throw ApiException.conflict("原语句与候选语句的影子执行参数不一致，不能裁判");
        }
        long snapshotId = ((Number) original.get("snapshot_id")).longValue();
        if (snapshotId != ((Number) cand.get("snapshot_id")).longValue()) {
            throw ApiException.conflict("原语句与候选语句的影子执行快照不一致，不能裁判");
        }
        String checksum = jdbc.queryForObject(
                "SELECT checksum FROM data_snapshot WHERE id = ?", String.class, snapshotId);

        List<Object> origPks = Jsons.asList(original.get("pk_list"));
        List<Object> candPks = Jsons.asList(cand.get("pk_list"));
        Map<String, BigDecimal> origGroup = toAmountMap(Jsons.asMap(original.get("org_group")));
        Map<String, BigDecimal> candGroup = toAmountMap(Jsons.asMap(cand.get("org_group")));
        BigDecimal origTotal = new BigDecimal(String.valueOf(original.get("total_amount")));
        BigDecimal candTotal = new BigDecimal(String.valueOf(cand.get("total_amount")));

        Boolean pkEqual = null;
        Boolean amountEqual;
        Boolean orgEqual;
        List<Object> missing = List.of();
        List<Object> extra = List.of();
        List<Map<String, Object>> missingReasons = List.of();
        List<Map<String, Object>> orgDiff = List.of();
        BigDecimal comparedOrigTotal = origTotal;
        String detail;

        switch (compareMode) {
            case "FULL" -> {
                pkEqual = origPks.equals(candPks);
                amountEqual = origTotal.compareTo(candTotal) == 0;
                orgEqual = groupsEqual(origGroup, candGroup);
                Set<Object> missingSet = new LinkedHashSet<>(origPks);
                missingSet.removeAll(new LinkedHashSet<>(candPks));
                Set<Object> extraSet = new LinkedHashSet<>(candPks);
                extraSet.removeAll(new LinkedHashSet<>(origPks));
                missing = List.copyOf(missingSet);
                extra = List.copyOf(extraSet);
                missingReasons = classifyMissing(missing, params);
                orgDiff = diffGroups(origGroup, candGroup);
                detail = "全量等价比较：主键集合/总金额/机构分组";
            }
            case "PAGE_WINDOW" -> {
                int offset = Integer.parseInt(String.valueOf(params.get("pageOffset")));
                int size = Integer.parseInt(String.valueOf(params.get("pageSize")));
                int hi = Math.min(offset + size, origPks.size());
                List<Object> window = offset >= origPks.size() ? List.of() : origPks.subList(offset, hi);
                pkEqual = window.equals(candPks);
                // 以冻结快照为基准重算窗口金额与机构分组
                Map<String, Object> truth = windowTruth(window);
                BigDecimal windowTotal = (BigDecimal) truth.get("total");
                Map<String, BigDecimal> windowGroup = (Map<String, BigDecimal>) truth.get("orgGroup");
                amountEqual = windowTotal.compareTo(candTotal) == 0;
                orgEqual = groupsEqual(windowGroup, candGroup);
                orgDiff = diffGroups(windowGroup, candGroup);
                comparedOrigTotal = windowTotal;
                detail = "分页窗口等价比较：offset=" + offset + ", size=" + size
                        + "，窗口内主键序列/窗口金额/窗口机构分组";
            }
            case "AGGREGATE" -> {
                amountEqual = origTotal.compareTo(candTotal) == 0;
                orgEqual = groupsEqual(origGroup, candGroup);
                orgDiff = diffGroups(origGroup, candGroup);
                detail = "聚合等价比较：总金额/机构级分组（候选为预聚合改写，无明细主键）";
            }
            default -> throw ApiException.unprocessable("未知比较模式: " + compareMode);
        }

        boolean pass = Boolean.TRUE.equals(amountEqual) && Boolean.TRUE.equals(orgEqual)
                && (pkEqual == null || pkEqual);
        String verdict = pass ? "PASS" : "BLOCK";
        if (!pass && Boolean.TRUE.equals(amountEqual) && Boolean.FALSE.equals(orgEqual)) {
            detail += "；总金额相同但机构级分组不一致，阻断发布并定位差异组";
        }

        Long judgementId = jdbc.queryForObject(
                "INSERT INTO judgement (candidate_id, snapshot_id, snapshot_checksum,"
                        + " original_execution_id, candidate_execution_id, compare_mode,"
                        + " pk_set_equal, amount_equal, org_group_equal,"
                        + " total_amount_original, total_amount_candidate,"
                        + " org_group_diff, missing_pks, extra_pks, missing_reason, verdict, detail)"
                        + " VALUES (?,?,?,?,?,?,?,?,?,?,?,?::jsonb,?::jsonb,?::jsonb,?::jsonb,?,?)"
                        + " RETURNING id",
                Long.class, candidateId, snapshotId, checksum,
                ((Number) original.get("id")).longValue(), ((Number) cand.get("id")).longValue(),
                compareMode, pkEqual, amountEqual, orgEqual,
                comparedOrigTotal, candTotal,
                Jsons.write(orgDiff), Jsons.write(missing), Jsons.write(extra),
                Jsons.write(missingReasons), verdict, detail);

        jdbc.update("UPDATE rewrite_candidate SET status = ? WHERE id = ?",
                pass ? "VERIFIED" : "BLOCKED", candidateId);
        return get(judgementId);
    }

    private Map<String, Object> latestSuccess(long candidateId, String side) {
        List<Map<String, Object>> rows = jdbc.queryForList(
                "SELECT * FROM shadow_execution WHERE candidate_id = ? AND side = ? AND status = 'SUCCESS'"
                        + " ORDER BY id DESC LIMIT 1",
                candidateId, side);
        return rows.isEmpty() ? null : rows.get(0);
    }

    private Map<String, BigDecimal> toAmountMap(Map<String, Object> json) {
        Map<String, BigDecimal> out = new TreeMap<>();
        json.forEach((k, v) -> out.put(k, new BigDecimal(String.valueOf(v))));
        return out;
    }

    private boolean groupsEqual(Map<String, BigDecimal> a, Map<String, BigDecimal> b) {
        Set<String> keys = new LinkedHashSet<>();
        keys.addAll(a.keySet());
        keys.addAll(b.keySet());
        for (String k : keys) {
            BigDecimal va = a.getOrDefault(k, BigDecimal.ZERO);
            BigDecimal vb = b.getOrDefault(k, BigDecimal.ZERO);
            if (va.compareTo(vb) != 0) {
                return false;
            }
        }
        return true;
    }

    private List<Map<String, Object>> diffGroups(Map<String, BigDecimal> original,
                                                 Map<String, BigDecimal> candidate) {
        List<Map<String, Object>> diff = new ArrayList<>();
        Set<String> keys = new LinkedHashSet<>();
        keys.addAll(original.keySet());
        keys.addAll(candidate.keySet());
        for (String k : keys) {
            BigDecimal va = original.getOrDefault(k, BigDecimal.ZERO);
            BigDecimal vb = candidate.getOrDefault(k, BigDecimal.ZERO);
            if (va.compareTo(vb) != 0) {
                Map<String, Object> d = new LinkedHashMap<>();
                d.put("org", k);
                d.put("original", va);
                d.put("candidate", vb);
                d.put("delta", vb.subtract(va));
                diff.add(d);
            }
        }
        return diff;
    }

    /** 以冻结快照为基准，重算分页窗口内的金额与机构分组。 */
    private Map<String, Object> windowTruth(List<Object> windowPks) {
        if (windowPks.isEmpty()) {
            return Map.of("total", BigDecimal.ZERO, "orgGroup", Map.of());
        }
        String placeholders = String.join(",", windowPks.stream().map(p -> "?").toList());
        List<Map<String, Object>> rows = jdbc.queryForList(
                "SELECT org_code, amount FROM snap_fee_detail WHERE id IN (" + placeholders + ")",
                windowPks.toArray());
        BigDecimal total = BigDecimal.ZERO;
        Map<String, BigDecimal> group = new TreeMap<>();
        for (Map<String, Object> row : rows) {
            BigDecimal amount = (BigDecimal) row.get("amount");
            total = total.add(amount);
            group.merge(String.valueOf(row.get("org_code")), amount, BigDecimal::add);
        }
        return Map.of("total", total, "orgGroup", group);
    }

    /** 缺失主键原因定位：对照快照行属性与查询参数，判断被哪个条件排除。 */
    private List<Map<String, Object>> classifyMissing(List<Object> missing, Map<String, Object> params) {
        List<Map<String, Object>> reasons = new ArrayList<>();
        for (Object pk : missing.stream().limit(50).toList()) {
            List<Map<String, Object>> rows = jdbc.queryForList(
                    "SELECT org_code, settle_status, diagnosis_code, settle_date"
                            + " FROM snap_fee_detail WHERE id = ?", pk);
            String reason;
            if (rows.isEmpty()) {
                reason = "快照中不存在该主键（数据在冻结后变化，需按风险重验）";
            } else {
                Map<String, Object> row = rows.get(0);
                reason = classifyRow(row, params);
            }
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("pk", pk);
            item.put("reason", reason);
            reasons.add(item);
        }
        return reasons;
    }

    private String classifyRow(Map<String, Object> row, Map<String, Object> params) {
        Object org = params.get("orgCode");
        if (org != null && !org.equals(row.get("org_code"))) {
            return "被 orgCode 过滤条件排除（行机构=" + row.get("org_code") + "）";
        }
        Object status = params.get("settleStatus");
        if (status != null && !status.equals(row.get("settle_status"))) {
            return "被 settleStatus 过滤条件排除（行状态=" + row.get("settle_status") + "）";
        }
        Object prefix = params.get("diagPrefix");
        if (prefix != null && !String.valueOf(row.get("diagnosis_code")).startsWith(String.valueOf(prefix))) {
            return "被 diagPrefix 过滤条件排除（行诊断=" + row.get("diagnosis_code") + "）";
        }
        LocalDate date = ((java.sql.Date) row.get("settle_date")).toLocalDate();
        String month = date.toString().substring(0, 7);
        if (month.compareTo(String.valueOf(params.get("monthFrom"))) < 0
                || month.compareTo(String.valueOf(params.get("monthTo"))) > 0) {
            return "被结算月份范围排除（行月份=" + month + "）";
        }
        return "未明原因（需人工复核改写语义）";
    }

    public Map<String, Object> get(long judgementId) {
        List<Map<String, Object>> rows = jdbc.queryForList(
                "SELECT * FROM judgement WHERE id = ?", judgementId);
        if (rows.isEmpty()) {
            throw ApiException.notFound("裁判记录不存在: " + judgementId);
        }
        return Jsons.normalizeRow(rows.get(0));
    }

    public List<Map<String, Object>> listForCandidate(long candidateId) {
        return Jsons.normalizeRows(jdbc.queryForList(
                "SELECT id, compare_mode, verdict, status, amount_equal, org_group_equal, pk_set_equal,"
                        + " created_at FROM judgement WHERE candidate_id = ? ORDER BY id DESC",
                candidateId));
    }
}
