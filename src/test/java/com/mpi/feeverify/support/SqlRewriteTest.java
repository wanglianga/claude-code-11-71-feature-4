package com.mpi.feeverify.support;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 改写引擎单元测试（不依赖数据库）。 */
class SqlRewriteTest {

    private static final String DEMO_SQL = """
            SELECT d.id, d.org_code, o.org_name, d.diagnosis_code, d.drug_code, d.settle_status, d.amount, d.settle_date
            FROM snap_fee_detail d
            JOIN snap_dim_org o ON o.org_code = d.org_code
            JOIN snap_dim_drug g ON g.drug_code = d.drug_code
            WHERE to_char(d.settle_date, 'YYYY-MM') BETWEEN :monthFrom AND :monthTo
              AND (:orgCode IS NULL OR d.org_code = :orgCode)
              AND (:settleStatus IS NULL OR d.settle_status = :settleStatus)
              AND (:diagPrefix IS NULL OR d.diagnosis_code LIKE :diagPrefix || '%')
            ORDER BY d.id""";

    @Test
    void pushdownConvertsToCharToRangeAndPushesConditions() {
        String rewritten = SqlRewrite.pushdown(DEMO_SQL);
        assertFalse(rewritten.contains("to_char"), "下推后不应再含 to_char 包装");
        assertTrue(rewritten.contains("settle_date >= to_date(:monthFrom, 'YYYY-MM')"));
        assertTrue(rewritten.contains("settle_date < to_date(:monthTo, 'YYYY-MM') + interval '1 month'"));
        assertTrue(rewritten.contains("FROM (SELECT * FROM snap_fee_detail"), "应生成派生表下推");
        assertTrue(rewritten.contains("(:orgCode IS NULL OR org_code = :orgCode)"),
                "派生表内条件应去掉明细别名");
        assertTrue(rewritten.contains("JOIN snap_dim_org o ON o.org_code = d.org_code"));
        assertTrue(rewritten.contains("ORDER BY d.id"));
    }

    @Test
    void joinReorderEliminatesRedundantJoin() {
        String rewritten = SqlRewrite.eliminateRedundantJoins(DEMO_SQL);
        assertFalse(rewritten.contains("snap_dim_drug"), "冗余的药品维表连接应被消除");
        assertTrue(rewritten.contains("JOIN snap_dim_org o"), "SELECT 引用的机构维表应保留");
        assertTrue(rewritten.contains("o.org_name"));
    }

    @Test
    void paginateAppendsLimitOffset() {
        String rewritten = SqlRewrite.paginate(DEMO_SQL);
        assertTrue(rewritten.endsWith("LIMIT :pageSize OFFSET :pageOffset"));
        assertTrue(rewritten.contains("ORDER BY d.id"));
    }

    @Test
    void preAggregateUsesVariantTables() {
        assertTrue(SqlRewrite.preAggregate("current").contains("FROM snap_fee_daily_org d"));
        assertTrue(SqlRewrite.preAggregate("stale").contains("FROM snap_fee_daily_org_stale d"));
        assertTrue(SqlRewrite.preAggregate("current").contains("GROUP BY d.org_code, o.org_name"));
    }

    @Test
    void detectsRedundantJoinAlias() {
        var redundant = SqlRewrite.redundantJoinAliases(DEMO_SQL);
        assertEquals(1, redundant.size());
        assertTrue(redundant.iterator().next().contains("snap_dim_drug"));
    }

    @Test
    void templateValidation() {
        assertTrue(SqlRewrite.isSupported(DEMO_SQL));
        assertFalse(SqlRewrite.isSupported("SELECT 1"));
    }
}
