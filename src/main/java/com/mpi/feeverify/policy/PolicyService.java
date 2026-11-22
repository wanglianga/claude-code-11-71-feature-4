package com.mpi.feeverify.policy;

import com.mpi.feeverify.support.ApiException;
import com.mpi.feeverify.support.Jsons;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 权限策略：版本化管理。灰度期间策略收窄时：
 * 旧策略下的在途请求标记 PENDING_REVIEW（不继续返回），
 * 新请求由路由器按 POLICY_RETIRED 拒绝，处置写入回退账本。
 */
@Service
public class PolicyService {

    private final JdbcTemplate jdbc;

    public PolicyService(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public Map<String, Object> create(String family, List<Map<String, Object>> rules) {
        Integer version = jdbc.queryForObject(
                "SELECT COALESCE(MAX(version), 0) + 1 FROM permission_policy WHERE family = ?",
                Integer.class, family);
        Long id = jdbc.queryForObject(
                "INSERT INTO permission_policy (family, version, rules, status) VALUES (?,?,?::jsonb,'DRAFT')"
                        + " RETURNING id",
                Long.class, family, version, Jsons.write(rules));
        return get(id);
    }

    /**
     * 生效新版本：同族旧版本退役；绑定旧版本的灰度/已发布门禁，
     * 其在途请求标记待复核，并写 POLICY_NARROW 账本。
     */
    @Transactional
    public Map<String, Object> activate(long policyId) {
        Map<String, Object> policy = get(policyId);
        String family = String.valueOf(policy.get("family"));
        List<Map<String, Object>> retired = jdbc.queryForList(
                "SELECT id, version FROM permission_policy WHERE family = ? AND status = 'ACTIVE'"
                        + " AND id <> ?",
                family, policyId);
        jdbc.update("UPDATE permission_policy SET status = 'RETIRED'"
                        + " WHERE family = ? AND status = 'ACTIVE' AND id <> ?",
                family, policyId);
        jdbc.update("UPDATE permission_policy SET status = 'ACTIVE' WHERE id = ?", policyId);

        List<Map<String, Object>> impacts = new ArrayList<>();
        for (Map<String, Object> old : retired) {
            List<Map<String, Object>> gates = jdbc.queryForList(
                    "SELECT id, status FROM release_gate WHERE policy_id = ? AND status IN ('GRAY','PUBLISHED')",
                    old.get("id"));
            for (Map<String, Object> gate : gates) {
                long gateId = ((Number) gate.get("id")).longValue();
                int pending = jdbc.update(
                        "UPDATE route_request SET status = 'PENDING_REVIEW'"
                                + " WHERE gate_id = ? AND status = 'IN_FLIGHT'",
                        gateId);
                Map<String, Object> basis = new LinkedHashMap<>();
                basis.put("reason", "权限策略收窄：v" + old.get("version") + " → v" + policy.get("version"));
                basis.put("fromPolicyId", ((Number) old.get("id")).longValue());
                basis.put("toPolicyId", policyId);
                basis.put("inFlightHandling", "在途结果标记 PENDING_REVIEW，复核前不再返回");
                basis.put("newRequestHandling", "旧策略下的新请求由路由器以 POLICY_RETIRED 拒绝");
                jdbc.update(
                        "INSERT INTO rollback_ledger (gate_id, action, from_status, to_status,"
                                + " affected_requests, recovery_basis, detail) VALUES (?,?,?,?,?,?::jsonb,?)",
                        gateId, "POLICY_NARROW", String.valueOf(gate.get("status")),
                        String.valueOf(gate.get("status")), pending, Jsons.write(basis),
                        "策略 v" + policy.get("version") + " 生效，旧策略在途请求待复核");
                Map<String, Object> impact = new LinkedHashMap<>();
                impact.put("gateId", gateId);
                impact.put("inFlightMarkedPendingReview", pending);
                impacts.add(impact);
            }
        }

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("activatedPolicyId", policyId);
        result.put("family", family);
        result.put("version", policy.get("version"));
        result.put("retiredPolicies", retired);
        result.put("gateImpacts", impacts);
        return result;
    }

    public Map<String, Object> get(long id) {
        List<Map<String, Object>> rows = jdbc.queryForList(
                "SELECT * FROM permission_policy WHERE id = ?", id);
        if (rows.isEmpty()) {
            throw ApiException.notFound("权限策略不存在: " + id);
        }
        return Jsons.normalizeRow(rows.get(0));
    }

    public List<Map<String, Object>> list(String family) {
        if (family == null) {
            return Jsons.normalizeRows(jdbc.queryForList(
                    "SELECT id, family, version, status, created_at FROM permission_policy ORDER BY id"));
        }
        return Jsons.normalizeRows(jdbc.queryForList(
                "SELECT id, family, version, status, created_at FROM permission_policy"
                        + " WHERE family = ? ORDER BY version", family));
    }
}
