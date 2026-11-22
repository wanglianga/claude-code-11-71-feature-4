package com.mpi.feeverify.candidate;

import com.mpi.feeverify.registration.RegistrationService;
import com.mpi.feeverify.support.ApiException;
import com.mpi.feeverify.support.Jsons;
import com.mpi.feeverify.support.SqlRewrite;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 候选生成器：输出等价的条件下推、分页、预聚合、连接重排改写。
 * 预聚合支持 current（正确预聚合表）与 stale（机构合并回溯缺陷表）两个变体，
 * 用于演示“总金额相同而机构分组不同”的裁判阻断。
 */
@Service
public class CandidateService {

    private final JdbcTemplate jdbc;
    private final RegistrationService registrationService;

    public CandidateService(JdbcTemplate jdbc, RegistrationService registrationService) {
        this.jdbc = jdbc;
        this.registrationService = registrationService;
    }

    public List<Map<String, Object>> generate(long registrationId, List<String> types,
                                              List<String> preAggregateVariants) {
        String sql = registrationService.sqlOf(registrationId);
        List<Map<String, Object>> created = new ArrayList<>();
        for (String type : types) {
            switch (type) {
                case "PUSHDOWN" -> created.add(insert(registrationId, type, "current",
                        SqlRewrite.pushdown(sql), "FULL", List.of(),
                        "to_char 月份条件转半开区间 + 明细条件下推派生表"));
                case "PAGINATION" -> created.add(insert(registrationId, type, "current",
                        SqlRewrite.paginate(sql), "PAGE_WINDOW", List.of(),
                        "保持 ORDER BY d.id 的 LIMIT/OFFSET 分页"));
                case "JOIN_REORDER" -> created.add(insert(registrationId, type, "current",
                        SqlRewrite.eliminateRedundantJoins(sql), "FULL", List.of(),
                        "消除冗余连接（未使用的维表关联）并重排剩余连接"));
                case "PRE_AGGREGATE" -> {
                    for (String variant : preAggregateVariants) {
                        created.add(insert(registrationId, type, variant,
                                SqlRewrite.preAggregate(variant), "AGGREGATE",
                                List.of("settleStatus", "diagPrefix"),
                                "基于 snap_fee_daily_org" + ("stale".equals(variant) ? "_stale（含机构合并回溯缺陷）" : "")
                                        + " 的机构级预聚合"));
                    }
                }
                default -> throw ApiException.badRequest("不支持的改写类型: " + type
                        + "（支持 PUSHDOWN/PAGINATION/PRE_AGGREGATE/JOIN_REORDER）");
            }
        }
        return created;
    }

    private Map<String, Object> insert(long registrationId, String type, String variant, String sql,
                                       String compareMode, List<String> requiredNull, String note) {
        Long id = jdbc.queryForObject(
                "INSERT INTO rewrite_candidate (registration_id, rewrite_type, variant, rewritten_sql,"
                        + " compare_mode, required_null_params, note)"
                        + " VALUES (?,?,?,?,?,?::jsonb,?) RETURNING id",
                Long.class, registrationId, type, variant, sql, compareMode,
                Jsons.write(requiredNull), note);
        return get(id);
    }

    public Map<String, Object> get(long id) {
        List<Map<String, Object>> rows = jdbc.queryForList(
                "SELECT * FROM rewrite_candidate WHERE id = ?", id);
        if (rows.isEmpty()) {
            throw ApiException.notFound("改写候选不存在: " + id);
        }
        return Jsons.normalizeRow(rows.get(0));
    }

    public List<Map<String, Object>> listForRegistration(long registrationId) {
        return Jsons.normalizeRows(jdbc.queryForList(
                "SELECT id, rewrite_type, variant, compare_mode, status, note, created_at"
                        + " FROM rewrite_candidate WHERE registration_id = ? ORDER BY id",
                registrationId));
    }
}
