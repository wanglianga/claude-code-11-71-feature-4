package com.mpi.feeverify.snapshot;

import com.mpi.feeverify.support.ApiException;
import com.mpi.feeverify.support.Jsons;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 数据快照：把业务基表冻结为 snap_* 只读副本表（含索引与统计信息），
 * 记录行数与 MD5 校验和。新快照产生时，旧快照上的裁判结论标记 STALE、
 * 依赖旧结论的灰度/已发布门禁置 BLOCKED 并写入回退账本（数据更新后按风险重验）。
 */
@Service
public class SnapshotService {

    /** 纳入快照的业务基表。 */
    private static final List<String> BASE_TABLES = List.of(
            "fee_detail", "dim_org", "dim_diagnosis", "dim_drug", "fee_daily_org", "fee_daily_org_stale");

    private final JdbcTemplate jdbc;

    public SnapshotService(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Transactional
    public Map<String, Object> freeze(String name, String note) {
        for (String base : BASE_TABLES) {
            jdbc.execute("DROP TABLE IF EXISTS snap_" + base);
            jdbc.execute("CREATE TABLE snap_" + base + " AS SELECT * FROM " + base);
        }
        jdbc.execute("CREATE INDEX idx_snap_fee_detail_org ON snap_fee_detail (org_code)");
        jdbc.execute("CREATE INDEX idx_snap_fee_detail_date ON snap_fee_detail (settle_date)");
        jdbc.execute("ANALYZE snap_fee_detail");

        Long rowCount = jdbc.queryForObject("SELECT COUNT(*) FROM snap_fee_detail", Long.class);
        String checksum = jdbc.queryForObject(
                "SELECT md5(string_agg(md5(concat_ws('|', id, org_code, diagnosis_code, drug_code,"
                        + " settle_status, amount, settle_date)), '' ORDER BY id)) FROM snap_fee_detail",
                String.class);

        Map<String, Object> tableStats = new LinkedHashMap<>();
        for (String base : BASE_TABLES) {
            Long cnt = jdbc.queryForObject("SELECT COUNT(*) FROM snap_" + base, Long.class);
            tableStats.put("snap_" + base, cnt);
        }

        // 旧快照与依赖其结论的对象：置旧 + 阻断 + 记账
        List<Long> staleSnapshots = jdbc.queryForList(
                "SELECT id FROM data_snapshot WHERE status = 'ACTIVE'", Long.class);
        jdbc.update("UPDATE data_snapshot SET status = 'SUPERSEDED' WHERE status = 'ACTIVE'");
        int staleJudgements = jdbc.update(
                "UPDATE judgement SET status = 'STALE' WHERE status = 'CURRENT'");
        List<Map<String, Object>> gatesToBlock = jdbc.queryForList(
                "SELECT g.id, g.status FROM release_gate g"
                        + " WHERE g.status IN ('GRAY','PUBLISHED')"
                        + " AND g.judgement_id IN (SELECT id FROM judgement WHERE status = 'STALE')");
        int blockedGates = 0;
        for (Map<String, Object> gate : gatesToBlock) {
            long gateId = ((Number) gate.get("id")).longValue();
            jdbc.update("UPDATE release_gate SET status = 'BLOCKED' WHERE id = ?", gateId);
            Integer affected = jdbc.queryForObject(
                    "SELECT COUNT(*) FROM route_request WHERE gate_id = ? AND routed_to = 'CANDIDATE'",
                    Integer.class, gateId);
            jdbc.update(
                    "INSERT INTO rollback_ledger (gate_id, action, from_status, to_status,"
                            + " affected_requests, recovery_basis, detail) VALUES (?,?,?,?,?,?::jsonb,?)",
                    gateId, "DATA_REFRESH", String.valueOf(gate.get("status")), "BLOCKED",
                    affected == null ? 0 : affected,
                    Jsons.write(Map.of("reason", "数据快照更新，旧验证结论仅代表旧快照，需按风险重验",
                            "staleSnapshots", staleSnapshots)),
                    "快照刷新，门禁自动阻断");
            blockedGates++;
        }

        Long id = jdbc.queryForObject(
                "INSERT INTO data_snapshot (name, mode, status, row_count, checksum, table_stats, note)"
                        + " VALUES (?,?,?,?,?,?::jsonb,?) RETURNING id",
                Long.class,
                name == null ? "snapshot" : name, "FROZEN_READ_REPLICA", "ACTIVE",
                rowCount, checksum, Jsons.write(tableStats), note);

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("snapshotId", id);
        result.put("mode", "FROZEN_READ_REPLICA");
        result.put("rowCount", rowCount);
        result.put("checksum", checksum);
        result.put("tableStats", tableStats);
        result.put("supersededSnapshots", staleSnapshots);
        result.put("judgementsMarkedStale", staleJudgements);
        result.put("gatesBlocked", blockedGates);
        return result;
    }

    public List<Map<String, Object>> list() {
        return Jsons.normalizeRows(jdbc.queryForList(
                "SELECT id, name, mode, status, row_count, checksum, frozen_at FROM data_snapshot"
                        + " ORDER BY id DESC"));
    }

    public Map<String, Object> get(long id) {
        List<Map<String, Object>> rows = jdbc.queryForList(
                "SELECT id, name, mode, status, row_count, checksum, table_stats, note, frozen_at"
                        + " FROM data_snapshot WHERE id = ?", id);
        if (rows.isEmpty()) {
            throw ApiException.notFound("快照不存在: " + id);
        }
        return Jsons.normalizeRow(rows.get(0));
    }

    public Map<String, Object> active() {
        List<Map<String, Object>> rows = jdbc.queryForList(
                "SELECT id FROM data_snapshot WHERE status = 'ACTIVE' ORDER BY id DESC LIMIT 1");
        if (rows.isEmpty()) {
            throw ApiException.conflict("当前没有 ACTIVE 快照，请先调用 POST /api/snapshots/freeze");
        }
        return get(((Number) rows.get(0).get("id")).longValue());
    }

    /** 可计算的费用快照样例：机构级汇总 + 总额 + 可手工重算的 SQL。 */
    public Map<String, Object> sample(long id) {
        Map<String, Object> snap = get(id);
        List<Map<String, Object>> perOrg = jdbc.queryForList(
                "SELECT d.org_code, o.org_name, COUNT(*) AS cnt, SUM(d.amount) AS amount"
                        + " FROM snap_fee_detail d JOIN snap_dim_org o ON o.org_code = d.org_code"
                        + " GROUP BY d.org_code, o.org_name ORDER BY d.org_code");
        Object total = jdbc.queryForObject("SELECT SUM(amount) FROM snap_fee_detail", Object.class);
        Object cnt = jdbc.queryForObject("SELECT COUNT(*) FROM snap_fee_detail", Object.class);

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("snapshotId", id);
        result.put("status", snap.get("status"));
        result.put("checksum", snap.get("checksum"));
        result.put("rowCount", cnt);
        result.put("totalAmount", total);
        result.put("perOrg", perOrg);
        result.put("reproduceSql",
                "SELECT d.org_code, o.org_name, COUNT(*) AS cnt, SUM(d.amount) AS amount"
                        + " FROM snap_fee_detail d JOIN snap_dim_org o ON o.org_code = d.org_code"
                        + " GROUP BY d.org_code, o.org_name ORDER BY d.org_code;");
        result.put("checksumSql",
                "SELECT md5(string_agg(md5(concat_ws('|', id, org_code, diagnosis_code, drug_code,"
                        + " settle_status, amount, settle_date)), '' ORDER BY id)) FROM snap_fee_detail;");
        return result;
    }
}
