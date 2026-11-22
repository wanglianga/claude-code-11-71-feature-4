package com.mpi.feeverify.gate;

import com.mpi.feeverify.candidate.CandidateService;
import com.mpi.feeverify.support.ApiException;
import com.mpi.feeverify.support.Jsons;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 发布门禁：只有通过裁判（PASS 且 CURRENT）的改写才能建门禁，
 * 并把该改写绑定到已验证的参数范围与权限策略——
 * 不允许把小范围参数下的验证结论推广到全省大范围。
 */
@Service
public class GateService {

    private final JdbcTemplate jdbc;
    private final CandidateService candidateService;

    public GateService(JdbcTemplate jdbc, CandidateService candidateService) {
        this.jdbc = jdbc;
        this.candidateService = candidateService;
    }

    @Transactional
    public Map<String, Object> create(long candidateId, Map<String, Object> paramRange, long policyId) {
        Map<String, Object> candidate = candidateService.get(candidateId);
        List<Map<String, Object>> judgements = jdbc.queryForList(
                "SELECT * FROM judgement WHERE candidate_id = ? ORDER BY id DESC LIMIT 1", candidateId);
        if (judgements.isEmpty()) {
            throw ApiException.conflict("候选 " + candidateId + " 尚无裁判结论，不能进入发布门禁");
        }
        Map<String, Object> judgement = judgements.get(0);
        if (!"PASS".equals(judgement.get("verdict"))) {
            throw ApiException.conflict("候选 " + candidateId + " 裁判结论为 BLOCK，不能进入发布门禁");
        }
        if (!"CURRENT".equals(judgement.get("status"))) {
            throw ApiException.conflict("裁判结论已随快照更新而过期（STALE），需重新影子验证后再建门禁");
        }
        List<Map<String, Object>> policies = jdbc.queryForList(
                "SELECT * FROM permission_policy WHERE id = ? AND status = 'ACTIVE'", policyId);
        if (policies.isEmpty()) {
            throw ApiException.conflict("权限策略 " + policyId + " 不存在或未生效");
        }

        Map<String, Object> verifiedParams = Jsons.asMap(jdbc.queryForMap(
                "SELECT params FROM shadow_execution WHERE id = ?",
                judgement.get("original_execution_id")).get("params"));
        validateRangeAgainstVerified(candidate, paramRange, verifiedParams);

        Long id = jdbc.queryForObject(
                "INSERT INTO release_gate (candidate_id, judgement_id, param_range, policy_id, status)"
                        + " VALUES (?,?,?::jsonb,?,'GRAY') RETURNING id",
                Long.class, candidateId, judgement.get("id"), Jsons.write(paramRange), policyId);
        jdbc.update("UPDATE rewrite_candidate SET status = 'PUBLISHED' WHERE id = ?", candidateId);
        return get(id);
    }

    /** 参数范围不得超出已验证范围（防止把小范围验证推广到全省）。 */
    @SuppressWarnings("unchecked")
    private void validateRangeAgainstVerified(Map<String, Object> candidate,
                                              Map<String, Object> range,
                                              Map<String, Object> verifiedParams) {
        if (range == null) {
            throw ApiException.badRequest("paramRange 必填：门禁需绑定已验证的参数范围");
        }
        for (String key : List.of("monthFrom", "monthTo")) {
            Object bound = range.get(key);
            Object verified = verifiedParams.get(key);
            if (bound == null || !bound.equals(verified)) {
                throw ApiException.unprocessable("参数范围 " + key + " 必须等于已验证值 " + verified
                        + "（当前=" + bound + "）；其他月份区间需另行影子验证");
            }
        }
        List<String> requiredNull = Jsons.asList(candidate.get("required_null_params"))
                .stream().map(String::valueOf).toList();
        for (String key : List.of("orgCode", "settleStatus", "diagPrefix")) {
            Object bound = range.get(key);
            Object verified = verifiedParams.get(key);
            if (requiredNull.contains(key)) {
                if (!"NULL_ONLY".equals(bound)) {
                    throw ApiException.unprocessable("候选为预聚合改写，参数 " + key
                            + " 的范围必须绑定 NULL_ONLY（该改写在此参数非空时未验证等价性）");
                }
            } else if (verified != null) {
                if (!String.valueOf(verified).equals(String.valueOf(bound))) {
                    throw ApiException.unprocessable("参数 " + key + " 已在值=" + verified
                            + " 下验证，范围只能绑定该值，不能推广到 " + bound);
                }
            } else if (bound == null) {
                range.put(key, "ANY");
            } else if (!"ANY".equals(bound) && !"NULL_ONLY".equals(bound)) {
                throw ApiException.unprocessable("参数 " + key + " 在全量（空值）下验证，"
                        + "范围只能是 ANY 或 NULL_ONLY，不能收窄到未验证的具体值 " + bound);
            }
        }
    }

    @Transactional
    public Map<String, Object> publish(long gateId) {
        Map<String, Object> gate = get(gateId);
        if (!"GRAY".equals(gate.get("status"))) {
            throw ApiException.conflict("仅 GRAY 状态门禁可发布，当前=" + gate.get("status"));
        }
        assertJudgementCurrent(gate);
        assertPolicyActive(gate);
        jdbc.update("UPDATE release_gate SET status = 'PUBLISHED', published_at = now() WHERE id = ?", gateId);
        return get(gateId);
    }

    /** 灰度期间权限策略收窄后，管理员把门禁重新绑定到新策略。 */
    @Transactional
    public Map<String, Object> rebind(long gateId, long policyId) {
        Map<String, Object> gate = get(gateId);
        List<Map<String, Object>> policies = jdbc.queryForList(
                "SELECT * FROM permission_policy WHERE id = ? AND status = 'ACTIVE'", policyId);
        if (policies.isEmpty()) {
            throw ApiException.conflict("目标策略 " + policyId + " 未生效，不能重绑");
        }
        jdbc.update("UPDATE release_gate SET policy_id = ? WHERE id = ?", policyId, gateId);
        jdbc.update(
                "INSERT INTO rollback_ledger (gate_id, action, from_status, to_status,"
                        + " affected_requests, recovery_basis, detail) VALUES (?,?,?,?,0,?::jsonb,?)",
                gateId, "POLICY_REBIND", String.valueOf(gate.get("status")),
                String.valueOf(gate.get("status")),
                Jsons.write(Map.of("fromPolicyId", ((Number) gate.get("policy_id")).longValue(),
                        "toPolicyId", policyId)),
                "门禁重新绑定到生效策略");
        return get(gateId);
    }

    /** 回退：门禁置 ROLLED_BACK，路由回退到原语句，账本记录受影响请求与恢复依据。 */
    @Transactional
    public Map<String, Object> rollback(long gateId, String reason) {
        Map<String, Object> gate = get(gateId);
        String fromStatus = String.valueOf(gate.get("status"));
        if ("ROLLED_BACK".equals(fromStatus)) {
            throw ApiException.conflict("门禁 " + gateId + " 已处于 ROLLED_BACK");
        }
        Integer affected = jdbc.queryForObject(
                "SELECT COUNT(*) FROM route_request WHERE gate_id = ? AND routed_to = 'CANDIDATE'",
                Integer.class, gateId);
        jdbc.update("UPDATE release_gate SET status = 'ROLLED_BACK' WHERE id = ?", gateId);
        jdbc.update("UPDATE rewrite_candidate SET status = 'ROLLED_BACK' WHERE id = ?",
                gate.get("candidate_id"));

        Map<String, Object> recoveryBasis = new LinkedHashMap<>();
        recoveryBasis.put("judgementId", ((Number) gate.get("judgement_id")).longValue());
        recoveryBasis.put("reason", reason == null ? "未说明" : reason);
        recoveryBasis.put("restoreTo", "ORIGINAL");
        recoveryBasis.put("basis", "登记原语句仍有效，路由立即回退；已路由到候选的请求计数留档");

        Long ledgerId = jdbc.queryForObject(
                "INSERT INTO rollback_ledger (gate_id, action, from_status, to_status,"
                        + " affected_requests, recovery_basis, detail) VALUES (?,?,?,?,?,?::jsonb,?)"
                        + " RETURNING id",
                Long.class, gateId, "ROLLBACK", fromStatus, "ROLLED_BACK",
                affected == null ? 0 : affected, Jsons.write(recoveryBasis),
                "手动回退：" + (reason == null ? "未说明" : reason));

        Map<String, Object> result = get(gateId);
        result.put("ledgerId", ledgerId);
        result.put("affectedRequests", affected);
        return result;
    }

    void assertJudgementCurrent(Map<String, Object> gate) {
        String status = jdbc.queryForObject(
                "SELECT status FROM judgement WHERE id = ?", String.class, gate.get("judgement_id"));
        if (!"CURRENT".equals(status)) {
            throw ApiException.conflict("裁判结论已过期（快照更新），需重新影子验证");
        }
    }

    void assertPolicyActive(Map<String, Object> gate) {
        List<Map<String, Object>> rows = jdbc.queryForList(
                "SELECT id FROM permission_policy WHERE id = ? AND status = 'ACTIVE'",
                gate.get("policy_id"));
        if (rows.isEmpty()) {
            throw ApiException.conflict("门禁绑定的权限策略已退役，请先 rebind 到生效策略");
        }
    }

    public Map<String, Object> get(long gateId) {
        List<Map<String, Object>> rows = jdbc.queryForList(
                "SELECT * FROM release_gate WHERE id = ?", gateId);
        if (rows.isEmpty()) {
            throw ApiException.notFound("发布门禁不存在: " + gateId);
        }
        return Jsons.normalizeRow(rows.get(0));
    }

    public List<Map<String, Object>> list() {
        return Jsons.normalizeRows(jdbc.queryForList(
                "SELECT id, candidate_id, judgement_id, policy_id, status, published_at, created_at"
                        + " FROM release_gate ORDER BY id DESC"));
    }
}
