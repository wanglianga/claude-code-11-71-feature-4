package com.mpi.feeverify.support;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 面向平台登记查询模板族的 SQL 改写引擎。
 *
 * <p>支持的模板族（行式条件）：
 * <pre>
 * SELECT &lt;列&gt;
 * FROM snap_fee_detail d
 * JOIN &lt;维表&gt; &lt;别名&gt; ON &lt;条件&gt;
 * WHERE &lt;条件1&gt;
 *   AND &lt;条件2&gt;          -- 每个后续条件独占一行并以 AND 开头
 * ORDER BY &lt;排序&gt;
 * </pre>
 * 改写规则：条件下推（含 to_char 月份条件转范围条件）、连接重排/冗余连接消除、
 * 分页、预聚合（基于 snap_fee_daily_org / snap_fee_daily_org_stale）。
 */
public final class SqlRewrite {

    private SqlRewrite() {
    }

    /** 模板族锚点：必须直接查询快照明细表。 */
    public static final String TEMPLATE_ANCHOR = "FROM snap_fee_detail d";

    private static final Pattern JOIN_LINE =
            Pattern.compile("(?im)^\\s*JOIN\\s+(\\S+)\\s+(\\w+)\\s+ON\\s+(.+)$");
    private static final Pattern TO_CHAR_MONTH =
            Pattern.compile("(?i)to_char\\(\\s*d\\.settle_date\\s*,\\s*'YYYY-MM'\\s*\\)\\s+BETWEEN\\s+:monthFrom\\s+AND\\s+:monthTo");

    /** 解析后的模板结构。 */
    public record Parsed(String selectList, List<String[]> joins, List<String> conditions, String orderBy) {
        /** joins: 每项 {table, alias, onCondition} */
    }

    public static boolean isSupported(String sql) {
        return sql != null && sql.contains(TEMPLATE_ANCHOR)
                && sql.toUpperCase().contains("WHERE") && sql.toUpperCase().contains("ORDER BY");
    }

    public static Parsed parse(String sql) {
        if (!isSupported(sql)) {
            throw ApiException.unprocessable(
                    "SQL 不属于支持的登记模板族（需含 `" + TEMPLATE_ANCHOR + "`、WHERE、ORDER BY，且每个 AND 条件独占一行）");
        }
        String norm = sql.replace("\r\n", "\n").trim();
        int fromIdx = norm.indexOf(TEMPLATE_ANCHOR);
        String selectPart = norm.substring(0, fromIdx).trim();
        if (!selectPart.regionMatches(true, 0, "SELECT", 0, 6)) {
            throw ApiException.unprocessable("模板需以 SELECT 开头");
        }
        String selectList = selectPart.substring(6).trim();

        String rest = norm.substring(fromIdx + TEMPLATE_ANCHOR.length());
        int whereIdx = indexOfWord(rest, "WHERE");
        int orderIdx = indexOfWord(rest, "ORDER BY");
        if (whereIdx < 0 || orderIdx < 0 || orderIdx < whereIdx) {
            throw ApiException.unprocessable("模板需包含 WHERE ... ORDER BY ...");
        }
        String joinBlock = rest.substring(0, whereIdx);
        String whereBlock = rest.substring(whereIdx + "WHERE".length(), orderIdx);
        String orderBy = rest.substring(orderIdx + "ORDER BY".length()).trim();

        List<String[]> joins = new ArrayList<>();
        Matcher jm = JOIN_LINE.matcher(joinBlock);
        while (jm.find()) {
            joins.add(new String[]{jm.group(1), jm.group(2), jm.group(3).trim()});
        }

        // 行式条件：第一行是 WHERE 后的首个条件，其余行以 AND 开头
        List<String> conditions = new ArrayList<>();
        String[] lines = whereBlock.split("\n");
        StringBuilder current = new StringBuilder();
        for (String line : lines) {
            String t = line.trim();
            if (t.isEmpty()) {
                continue;
            }
            if (t.regionMatches(true, 0, "AND ", 0, 4) && current.length() > 0) {
                conditions.add(current.toString().trim());
                current = new StringBuilder(t.substring(4).trim());
            } else {
                if (current.length() > 0) {
                    current.append(' ');
                }
                current.append(t);
            }
        }
        if (current.length() > 0) {
            conditions.add(current.toString().trim());
        }
        return new Parsed(selectList, joins, conditions, orderBy);
    }

    private static int indexOfWord(String text, String word) {
        Matcher m = Pattern.compile("(?i)\\b" + Pattern.quote(word) + "\\b").matcher(text);
        return m.find() ? m.start() : -1;
    }

    /** 条件下推：to_char 月份条件改范围条件，并把仅引用明细别名 d 的条件下推进派生表。 */
    public static String pushdown(String sql) {
        Parsed p = parse(sql);
        List<String> pushed = new ArrayList<>();
        List<String> outer = new ArrayList<>();
        for (String cond : p.conditions()) {
            String rewritten = rewriteMonthCondition(cond);
            boolean onlyDetail = true;
            for (String[] j : p.joins()) {
                if (rewritten.matches("(?s).*\\b" + Pattern.quote(j[1]) + "\\..*")) {
                    onlyDetail = false;
                    break;
                }
            }
            if (onlyDetail) {
                pushed.add(stripAlias(rewritten, "d"));
            } else {
                outer.add(rewritten);
            }
        }
        StringBuilder sb = new StringBuilder();
        sb.append("SELECT ").append(p.selectList()).append('\n');
        sb.append("FROM (SELECT * FROM snap_fee_detail\n      WHERE ")
                .append(String.join("\n        AND ", pushed)).append(") d\n");
        for (String[] j : p.joins()) {
            sb.append("JOIN ").append(j[0]).append(' ').append(j[1]).append(" ON ").append(j[2]).append('\n');
        }
        if (!outer.isEmpty()) {
            sb.append("WHERE ").append(String.join("\n  AND ", outer)).append('\n');
        }
        sb.append("ORDER BY ").append(p.orderBy());
        return sb.toString();
    }

    /** to_char 月份 BETWEEN → 半开区间范围条件（可命中 settle_date 索引）。 */
    public static String rewriteMonthCondition(String condition) {
        Matcher m = TO_CHAR_MONTH.matcher(condition);
        if (m.find()) {
            return m.replaceAll("(settle_date >= to_date(:monthFrom, 'YYYY-MM') "
                    + "AND settle_date < to_date(:monthTo, 'YYYY-MM') + interval '1 month')");
        }
        return condition;
    }

    /** 连接重排/冗余连接消除：删除 SELECT/WHERE/ORDER BY 均未引用的维表连接。 */
    public static String eliminateRedundantJoins(String sql) {
        Parsed p = parse(sql);
        String selectWhereOrder = p.selectList() + " " + String.join(" ", p.conditions()) + " " + p.orderBy();
        List<String[]> kept = new ArrayList<>();
        for (String[] j : p.joins()) {
            String alias = j[1];
            boolean usedElsewhere = selectWhereOrder.matches("(?s).*\\b" + Pattern.quote(alias) + "\\..*")
                    || kept.stream().anyMatch(k -> k[2].matches("(?s).*\\b" + Pattern.quote(alias) + "\\..*"));
            if (usedElsewhere) {
                kept.add(j);
            }
        }
        StringBuilder sb = new StringBuilder();
        sb.append("SELECT ").append(p.selectList()).append('\n');
        sb.append(TEMPLATE_ANCHOR).append('\n');
        for (String[] j : kept) {
            sb.append("JOIN ").append(j[0]).append(' ').append(j[1]).append(" ON ").append(j[2]).append('\n');
        }
        sb.append("WHERE ").append(String.join("\n  AND ", p.conditions())).append('\n');
        sb.append("ORDER BY ").append(p.orderBy());
        return sb.toString();
    }

    /** 分页改写：保持原排序，追加 LIMIT/OFFSET。 */
    public static String paginate(String sql) {
        Parsed p = parse(sql);
        return sql.trim() + "\nLIMIT :pageSize OFFSET :pageOffset";
    }

    /** 预聚合改写：基于按日+机构预聚合表（current=正确版 / stale=机构合并回溯缺陷版）。 */
    public static String preAggregate(String variant) {
        String table = "stale".equals(variant) ? "snap_fee_daily_org_stale" : "snap_fee_daily_org";
        return """
                SELECT d.org_code, o.org_name, SUM(d.amount) AS amount, SUM(d.cnt) AS cnt
                FROM %s d
                JOIN snap_dim_org o ON o.org_code = d.org_code
                WHERE d.month BETWEEN :monthFrom AND :monthTo
                  AND (:orgCode IS NULL OR d.org_code = :orgCode)
                GROUP BY d.org_code, o.org_name
                ORDER BY d.org_code""".formatted(table);
    }

    /** 找出未在 SELECT/WHERE/ORDER BY 中使用的连接别名（冗余连接诊断用）。 */
    public static Set<String> redundantJoinAliases(String sql) {
        Parsed p = parse(sql);
        String selectWhereOrder = p.selectList() + " " + String.join(" ", p.conditions()) + " " + p.orderBy();
        Set<String> redundant = new LinkedHashSet<>();
        for (String[] j : p.joins()) {
            if (!selectWhereOrder.matches("(?s).*\\b" + Pattern.quote(j[1]) + "\\..*")) {
                redundant.add(j[1] + " (" + j[0] + ")");
            }
        }
        return redundant;
    }

    private static String stripAlias(String condition, String alias) {
        return condition.replaceAll("\\b" + Pattern.quote(alias) + "\\.", "");
    }
}
