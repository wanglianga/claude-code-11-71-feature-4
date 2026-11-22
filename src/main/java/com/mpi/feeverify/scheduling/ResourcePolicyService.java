package com.mpi.feeverify.scheduling;

import com.mpi.feeverify.support.ApiException;
import com.mpi.feeverify.support.Jsons;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.time.LocalTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeSet;

/**
 * 资源策略目录：按时段（DAY/NIGHT）、用途、角色保存资源上限，版本化。
 *
 * <p>解析规则（优先级由具体到兜底）：purpose+role → purpose+* → *+role → *+*。
 * 时段由调用方指定或按当前本地时间推断。查询检查点与订阅状态与策略联动：
 * 调度器在分区安全点重新解析生效上限，超限时暂停未开始分区而不是强杀已提交事务。
 */
@Service
public class ResourcePolicyService {

    public static final String FAMILY = "fee-scheduling";

    private final JdbcTemplate jdbc;

    @Value("${verify.scheduling.day-start:08:00}")
    private String dayStart;
    @Value("${verify.scheduling.day-end:20:00}")
    private String dayEnd;

    public ResourcePolicyService(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** 按时段推断窗口：DAY 或 NIGHT。 */
    public String currentWindow() {
        LocalTime now = LocalTime.now();
        LocalTime start = LocalTime.parse(dayStart);
        LocalTime end = LocalTime.parse(dayEnd);
        return (!now.isBefore(start) && now.isBefore(end)) ? "DAY" : "NIGHT";
    }

    /** 解析生效上限：匹配最具体的 (purpose, role) 组合。 */
    public Map<String, Object> resolve(String window, String purpose, String role) {
        String w = window == null ? currentWindow() : window.toUpperCase();
        if (!w.equals("DAY") && !w.equals("NIGHT")) {
            throw ApiException.badRequest("window 仅支持 DAY / NIGHT");
        }
        String p = purpose == null || purpose.isBlank() ? "*" : purpose;
        String r = role == null || role.isBlank() ? "*" : role;
        List<Map<String, Object>> rows = jdbc.queryForList(
                "SELECT * FROM resource_policy WHERE family = ? AND window_name = ? AND status = 'ACTIVE'"
                        + " AND purpose IN (?, '*') AND role_name IN (?, '*')"
                        + " ORDER BY (purpose = ?) DESC, (role_name = ?) DESC, version DESC LIMIT 1",
                FAMILY, w, p, r, p, r);
        if (rows.isEmpty()) {
            throw ApiException.conflict("时段 " + w + " 无可用资源策略（purpose=" + p + ", role=" + r + "）");
        }
        Map<String, Object> policy = Jsons.normalizeRow(rows.get(0));
        policy.put("requestedWindow", w);
        policy.put("requestedPurpose", p);
        policy.put("requestedRole", r);
        policy.put("matchedExactPurpose", p.equals(policy.get("purpose")));
        policy.put("matchedExactRole", r.equals(policy.get("role_name")));
        return policy;
    }

    /** 目录列表（可按 window 过滤）。 */
    public List<Map<String, Object>> list(String window) {
        if (window == null) {
            return Jsons.normalizeRows(jdbc.queryForList(
                    "SELECT * FROM resource_policy ORDER BY window_name, purpose, role_name, version DESC"));
        }
        return Jsons.normalizeRows(jdbc.queryForList(
                "SELECT * FROM resource_policy WHERE window_name = ? ORDER BY purpose, role_name, version DESC",
                window.toUpperCase()));
    }

    public Map<String, Object> get(long id) {
        List<Map<String, Object>> rows = jdbc.queryForList(
                "SELECT * FROM resource_policy WHERE id = ?", id);
        if (rows.isEmpty()) {
            throw ApiException.notFound("资源策略不存在: " + id);
        }
        return Jsons.normalizeRow(rows.get(0));
    }

    /**
     * 新增策略版本（DRAFT）。同 (family, window, purpose, role) 下版本号自增。
     * 生效只是把该行置 ACTIVE；此前同键的 ACTIVE 行退役——用于“高峰策略运行中生效”。
     */
    public Map<String, Object> create(String window, String purpose, String role,
                                      Map<String, Object> limits) {
        String w = window == null ? currentWindow() : window.toUpperCase();
        String p = purpose == null || purpose.isBlank() ? "*" : purpose;
        String r = role == null || role.isBlank() ? "*" : role;
        Integer version = jdbc.queryForObject(
                "SELECT COALESCE(MAX(version), 0) + 1 FROM resource_policy"
                        + " WHERE family = ? AND window_name = ? AND purpose = ? AND role_name = ?",
                Integer.class, FAMILY, w, p, r);
        Long id = jdbc.queryForObject(
                "INSERT INTO resource_policy (family, window_name, purpose, role_name, version,"
                        + " max_concurrent_tasks, max_total_rows, result_row_cap, statement_timeout_ms,"
                        + " work_mem, max_parallel_workers, status)"
                        + " VALUES (?,?,?,?,?,?,?,?,?,?,?,'DRAFT') RETURNING id",
                Long.class, FAMILY, w, p, r, version,
                intOf(limits, "maxConcurrentTasks", 2),
                longOf(limits, "maxTotalRows", 6000),
                intOf(limits, "resultRowCap", 200),
                intOf(limits, "statementTimeoutMs", 4000),
                String.valueOf(limits.getOrDefault("workMem", "8MB")),
                intOf(limits, "maxParallelWorkers", 1));
        return get(id);
    }

    /** 生效策略新版本：同键旧 ACTIVE 版本退役。运行中任务仍沿用启动时的 limit_snapshot。 */
    public Map<String, Object> activate(long policyId) {
        Map<String, Object> p = get(policyId);
        List<Map<String, Object>> retired = jdbc.queryForList(
                "SELECT id, version FROM resource_policy WHERE family = ? AND window_name = ?"
                        + " AND purpose = ? AND role_name = ? AND status = 'ACTIVE' AND id <> ?",
                p.get("family"), p.get("window_name"), p.get("purpose"), p.get("role_name"), policyId);
        jdbc.update("UPDATE resource_policy SET status = 'RETIRED' WHERE family = ? AND window_name = ?"
                        + " AND purpose = ? AND role_name = ? AND status = 'ACTIVE' AND id <> ?",
                p.get("family"), p.get("window_name"), p.get("purpose"), p.get("role_name"), policyId);
        jdbc.update("UPDATE resource_policy SET status = 'ACTIVE' WHERE id = ?", policyId);
        Map<String, Object> out = get(policyId);
        out.put("retiredPolicies", retired);
        return out;
    }

    /**
     * 解析用户的有效授权机构范围：
     * 登记授权范围 ∩ 生效权限策略；机构级账号再收敛到本人机构，省级账号保留交集全域。
     * 交集为空 → 拒绝（授权范围不同必然产生不同指纹，不能共享宽权限结果）。
     */
    public Scope resolveScope(String role, String userOrg, Map<String, Object> registrationAuth,
                              Map<String, Object> permissionPolicy) {
        TreeSet<String> regSet = toSet(registrationAuth == null ? null
                : Jsons.asList(registrationAuth.get("orgCodes")));
        TreeSet<String> policySet = new TreeSet<>();
        List<Map<String, Object>> rules = Jsons.asListOfMaps(permissionPolicy.get("rules"));
        boolean policyFound = false;
        for (Map<String, Object> rule : rules) {
            if (String.valueOf(rule.get("role")).equals(role)) {
                policyFound = true;
                Object allowed = rule.get("allowedOrgs");
                if ("*".equals(allowed)) {
                    policySet.add("*");
                } else if (allowed instanceof List<?> list) {
                    list.forEach(o -> policySet.add(String.valueOf(o)));
                }
                break;
            }
        }
        if (!policyFound) {
            throw ApiException.unprocessable("生效权限策略未覆盖角色 " + role);
        }
        TreeSet<String> base;
        if (regSet.contains("*") && policySet.contains("*")) {
            base = new TreeSet<>(List.of("*"));
        } else if (regSet.contains("*")) {
            base = policySet;
        } else if (policySet.contains("*")) {
            base = regSet;
        } else {
            base = new TreeSet<>(regSet);
            base.retainAll(policySet);
        }
        if (userOrg != null) {
            if (!base.contains("*") && !base.contains(userOrg)) {
                base = new TreeSet<>();
            } else {
                base = new TreeSet<>(List.of(userOrg));
            }
        }
        if (base.isEmpty()) {
            throw ApiException.unprocessable(
                    "角色 " + role + " 在登记授权范围与生效策略的交集中没有任何可访问机构");
        }
        List<String> sorted = new ArrayList<>(base);
        return new Scope(sorted, String.join(",", sorted),
                "登记授权机构 " + regSet + " ∩ 生效策略机构 " + policySet
                        + (userOrg == null ? "；省级账号保留交集全域" : "；机构级账号收敛到 " + userOrg));
    }

    private TreeSet<String> toSet(List<Object> orgs) {
        TreeSet<String> set = new TreeSet<>();
        if (orgs == null || orgs.isEmpty() || orgs.contains("*")) {
            set.add("*");
            return set;
        }
        for (Object o : orgs) {
            set.add(String.valueOf(o));
        }
        return set;
    }

    /** 有效授权范围；orgs 排序后作为指纹要素。 */
    public record Scope(List<String> orgs, String scopeKey, String basis) {
    }

    private static int intOf(Map<String, Object> m, String key, int def) {
        Object v = m.get(key);
        return v == null ? def : Integer.parseInt(String.valueOf(v));
    }

    private static long longOf(Map<String, Object> m, String key, long def) {
        Object v = m.get(key);
        return v == null ? def : Long.parseLong(String.valueOf(v));
    }
}
