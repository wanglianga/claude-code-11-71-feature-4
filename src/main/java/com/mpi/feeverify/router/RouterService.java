package com.mpi.feeverify.router;

import com.mpi.feeverify.gate.GateService;
import com.mpi.feeverify.shadow.ShadowExecutorService;
import com.mpi.feeverify.support.ApiException;
import com.mpi.feeverify.support.Jsons;
import com.mpi.feeverify.support.Params;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 路由器：按门禁状态、裁判时效、权限策略版本与参数范围决定
 * 请求走候选改写、回退原语句还是拒绝。
 * 灰度期间策略收窄：旧策略下的新请求拒绝（POLICY_RETIRED），
 * 在途请求由策略服务标记 PENDING_REVIEW，复核前不再返回。
 */
@Service
public class RouterService {

    private final JdbcTemplate jdbc;
    private final GateService gateService;
    private final ShadowExecutorService shadowExecutor;

    @Value("${verify.router.max-return-rows:200}")
    private int maxReturnRows;

    public RouterService(JdbcTemplate jdbc, GateService gateService,
                         ShadowExecutorService shadowExecutor) {
        this.jdbc = jdbc;
        this.gateService = gateService;
        this.shadowExecutor = shadowExecutor;
    }

    @SuppressWarnings("unchecked")
    public Map<String, Object> route(String token, long gateId, Map<String, Object> rawParams,
                                     boolean holdOpen) {
        Map<String, Object> user = authenticate(token);
        Map<String, Object> gate = gateService.get(gateId);
        Map<String, Object> candidate = jdbc.queryForMap(
                "SELECT * FROM rewrite_candidate WHERE id = ?", gate.get("candidate_id"));
        Map<String, Object> registration = jdbc.queryForMap(
                "SELECT * FROM query_registration WHERE id = ?", candidate.get("registration_id"));
        Map<String, Object> judgement = jdbc.queryForMap(
                "SELECT * FROM judgement WHERE id = ?", gate.get("judgement_id"));
        Map<String, Object> gatePolicy = jdbc.queryForMap(
                "SELECT * FROM permission_policy WHERE id = ?", gate.get("policy_id"));
        Map<String, Object> activePolicy = jdbc.queryForMap(
                "SELECT * FROM permission_policy WHERE family = ? AND status = 'ACTIVE'",
                gatePolicy.get("family"));

        Map<String, Object> params = Params.normalize(rawParams);
        Params.validate(params);
        if ("PAGE_WINDOW".equals(candidate.get("compare_mode"))) {
            params.putIfAbsent("pageSize", 500);
            params.putIfAbsent("pageOffset", 0);
        }

        String routedTo;
        String reason = null;
        if ("ROLLED_BACK".equals(gate.get("status"))) {
            routedTo = "ORIGINAL";
            reason = "GATE_ROLLED_BACK: 门禁已回退，恢复路由原语句";
        } else if ("BLOCKED".equals(gate.get("status"))) {
            routedTo = "ORIGINAL";
            reason = "VALIDATION_STALE_DATA_REFRESHED: 快照已更新，旧验证结论不代表新数据，回退原语句";
        } else if (!"CURRENT".equals(judgement.get("status"))) {
            routedTo = "ORIGINAL";
            reason = "VALIDATION_STALE: 裁判结论已过期，回退原语句";
        } else if (((Number) gatePolicy.get("id")).longValue()
                != ((Number) activePolicy.get("id")).longValue()) {
            routedTo = "REJECTED";
            reason = "POLICY_RETIRED: 灰度期间权限策略已变更，旧策略下的新请求停止受理，待 rebind 后恢复";
        } else if (!authorized(user, registration, activePolicy)) {
            routedTo = "REJECTED";
            reason = "AUTH_DENIED: 用户角色或机构不在授权范围/生效策略内";
        } else {
            List<String> violations = Params.rangeViolations(params, Jsons.asMap(gate.get("param_range")));
            if (!violations.isEmpty()) {
                routedTo = "ORIGINAL";
                reason = "OUT_OF_VALIDATED_RANGE: " + String.join("；", violations);
            } else {
                routedTo = "CANDIDATE";
            }
        }

        List<Map<String, Object>> rows = List.of();
        if (!"REJECTED".equals(routedTo)) {
            String sql = "CANDIDATE".equals(routedTo)
                    ? String.valueOf(candidate.get("rewritten_sql"))
                    : String.valueOf(registration.get("sql_text"));
            rows = shadowExecutor.fetchRows(sql, params);
        }

        String status = "REJECTED".equals(routedTo) ? "REJECTED" : (holdOpen ? "IN_FLIGHT" : "COMPLETED");
        Long requestId = jdbc.queryForObject(
                "INSERT INTO route_request (gate_id, username, policy_version, params, routed_to,"
                        + " status, reason, row_count, completed_at)"
                        + " VALUES (?,?,?,?::jsonb,?,?,?,?,CASE WHEN ? THEN now() ELSE NULL END)"
                        + " RETURNING id",
                Long.class, gateId, user.get("username"),
                ((Number) activePolicy.get("version")).intValue(),
                Jsons.write(params), routedTo, status, reason, rows.size(),
                "COMPLETED".equals(status));

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("routeRequestId", requestId);
        result.put("gateId", gateId);
        result.put("user", user.get("username"));
        result.put("routedTo", routedTo);
        result.put("status", status);
        result.put("reason", reason);
        result.put("policyVersion", activePolicy.get("version"));
        result.put("rowCount", rows.size());
        result.put("rows", rows.stream().limit(maxReturnRows).toList());
        result.put("rowsTruncated", rows.size() > maxReturnRows);
        return result;
    }

    private Map<String, Object> authenticate(String token) {
        if (token == null) {
            throw new ApiException(HttpStatus.UNAUTHORIZED, "缺少 token");
        }
        List<Map<String, Object>> users = jdbc.queryForList(
                "SELECT * FROM app_user WHERE token = ?", token);
        if (users.isEmpty()) {
            throw new ApiException(HttpStatus.UNAUTHORIZED, "token 无效");
        }
        return users.get(0);
    }

    /** 授权：角色须在登记授权范围内，且用户机构被生效策略覆盖。 */
    @SuppressWarnings("unchecked")
    private boolean authorized(Map<String, Object> user, Map<String, Object> registration,
                               Map<String, Object> activePolicy) {
        Map<String, Object> authScope = Jsons.asMap(registration.get("auth_scope"));
        List<Object> roles = (List<Object>) authScope.get("roles");
        if (roles != null && !roles.contains(user.get("role"))) {
            return false;
        }
        List<Map<String, Object>> rules = Jsons.asListOfMaps(activePolicy.get("rules"));
        for (Map<String, Object> rule : rules) {
            if (String.valueOf(rule.get("role")).equals(String.valueOf(user.get("role")))) {
                Object allowed = rule.get("allowedOrgs");
                if ("*".equals(allowed)) {
                    return true;
                }
                if (allowed instanceof List<?> list) {
                    return user.get("org_code") != null && list.contains(user.get("org_code"));
                }
                return false;
            }
        }
        return false;
    }

    /** 在途请求完成（模拟长查询结束）。 */
    public Map<String, Object> finish(long requestId) {
        int updated = jdbc.update(
                "UPDATE route_request SET status = 'COMPLETED', completed_at = now()"
                        + " WHERE id = ? AND status = 'IN_FLIGHT'", requestId);
        if (updated == 0) {
            throw ApiException.conflict("请求 " + requestId + " 不在 IN_FLIGHT 状态");
        }
        return request(requestId);
    }

    /** 待复核请求处置：COMPLETED（放行）或 REJECTED（作废）。 */
    public Map<String, Object> review(long requestId, String decision) {
        if (!List.of("COMPLETED", "REJECTED").contains(decision)) {
            throw ApiException.badRequest("decision 仅支持 COMPLETED / REJECTED");
        }
        int updated = jdbc.update(
                "UPDATE route_request SET status = ?, completed_at = now(),"
                        + " reason = COALESCE(reason, '') || ' | 复核结论: ' || ?"
                        + " WHERE id = ? AND status = 'PENDING_REVIEW'",
                decision, decision, requestId);
        if (updated == 0) {
            throw ApiException.conflict("请求 " + requestId + " 不在 PENDING_REVIEW 状态");
        }
        return request(requestId);
    }

    public Map<String, Object> request(long requestId) {
        List<Map<String, Object>> rows = jdbc.queryForList(
                "SELECT * FROM route_request WHERE id = ?", requestId);
        if (rows.isEmpty()) {
            throw ApiException.notFound("路由请求不存在: " + requestId);
        }
        return Jsons.normalizeRow(rows.get(0));
    }

    public List<Map<String, Object>> listRequests(long gateId) {
        return Jsons.normalizeRows(jdbc.queryForList(
                "SELECT id, gate_id, username, policy_version, routed_to, status, reason, row_count,"
                        + " created_at, completed_at FROM route_request WHERE gate_id = ? ORDER BY id",
                gateId));
    }
}
