package com.mpi.feeverify.registration;

import com.mpi.feeverify.support.ApiException;
import com.mpi.feeverify.support.Jsons;
import com.mpi.feeverify.support.Params;
import com.mpi.feeverify.support.SqlRewrite;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;

/**
 * 查询登记：保存语句摘要、用途、授权范围、参数形态与关联快照。
 * 仅接受平台查询模板族（见 {@link SqlRewrite}），保证后续改写等价性可机械验证。
 */
@Service
public class RegistrationService {

    private final JdbcTemplate jdbc;

    public RegistrationService(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public Map<String, Object> register(String sqlText, String purpose, Map<String, Object> authScope,
                                        List<Map<String, Object>> paramShape, Long snapshotId, String createdBy) {
        if (sqlText == null || sqlText.isBlank()) {
            throw ApiException.badRequest("sqlText 必填");
        }
        if (!SqlRewrite.isSupported(sqlText)) {
            throw ApiException.unprocessable(
                    "SQL 不属于支持的登记模板族（需含 FROM snap_fee_detail d、WHERE、ORDER BY，AND 条件独占一行）");
        }
        if (purpose == null || purpose.isBlank()) {
            throw ApiException.badRequest("purpose（用途）必填");
        }
        Map<String, Object> scope = authScope == null
                ? Map.of("roles", List.of("AUDIT", "DRG", "BUDGET"), "orgCodes", List.of("*"))
                : authScope;
        List<Map<String, Object>> shape = paramShape == null ? defaultParamShape() : paramShape;
        String digest = Params.sha256(sqlText.replaceAll("\\s+", " ").trim());

        Long id = jdbc.queryForObject(
                "INSERT INTO query_registration (sql_digest, sql_text, purpose, auth_scope, param_shape,"
                        + " snapshot_id, created_by) VALUES (?,?,?,?::jsonb,?::jsonb,?,?) RETURNING id",
                Long.class, digest, sqlText, purpose, Jsons.write(scope), Jsons.write(shape),
                snapshotId, createdBy);
        return get(id);
    }

    public Map<String, Object> get(long id) {
        List<Map<String, Object>> rows = jdbc.queryForList(
                "SELECT r.*, "
                        + " (SELECT COUNT(*) FROM plan_capture p WHERE p.registration_id = r.id) AS plan_count,"
                        + " (SELECT COUNT(*) FROM diagnosis d WHERE d.registration_id = r.id) AS diagnosis_count,"
                        + " (SELECT COUNT(*) FROM rewrite_candidate c WHERE c.registration_id = r.id) AS candidate_count"
                        + " FROM query_registration r WHERE r.id = ?", id);
        if (rows.isEmpty()) {
            throw ApiException.notFound("查询登记不存在: " + id);
        }
        return Jsons.normalizeRow(rows.get(0));
    }

    public List<Map<String, Object>> list() {
        return Jsons.normalizeRows(jdbc.queryForList(
                "SELECT id, sql_digest, purpose, status, created_by, created_at FROM query_registration"
                        + " ORDER BY id DESC"));
    }

    public String sqlOf(long registrationId) {
        return String.valueOf(get(registrationId).get("sql_text"));
    }

    public static List<Map<String, Object>> defaultParamShape() {
        return List.of(
                Map.of("name", "monthFrom", "type", "STRING", "nullable", false, "pattern", "^\\d{4}-\\d{2}$"),
                Map.of("name", "monthTo", "type", "STRING", "nullable", false, "pattern", "^\\d{4}-\\d{2}$"),
                Map.of("name", "orgCode", "type", "STRING", "nullable", true),
                Map.of("name", "settleStatus", "type", "STRING", "nullable", true,
                        "enum", List.of("SETTLED", "PENDING", "REJECTED")),
                Map.of("name", "diagPrefix", "type", "STRING", "nullable", true),
                Map.of("name", "pageSize", "type", "INT", "nullable", true),
                Map.of("name", "pageOffset", "type", "INT", "nullable", true));
    }
}
