package com.mpi.feeverify.support;

import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 参数分级与门禁参数范围校验单元测试。 */
class ParamsTest {

    @Test
    void classifyBySpanAndOrgScope() {
        assertEquals("NARROW", Params.classify(Params.normalize(
                Map.of("monthFrom", "2025-08", "monthTo", "2025-08", "orgCode", "ORG003"))));
        assertEquals("MEDIUM", Params.classify(Params.normalize(
                Map.of("monthFrom", "2025-08", "monthTo", "2025-08"))));
        assertEquals("MEDIUM", Params.classify(Params.normalize(
                Map.of("monthFrom", "2025-08", "monthTo", "2025-10", "orgCode", "ORG003"))));
        assertEquals("WIDE", Params.classify(Params.normalize(
                Map.of("monthFrom", "2025-11", "monthTo", "2026-02"))));
    }

    @Test
    void signatureSeparatesParamRanges() {
        String narrow = Params.signature(Params.normalize(
                Map.of("monthFrom", "2025-08", "monthTo", "2025-08", "orgCode", "ORG003")));
        String wide = Params.signature(Params.normalize(
                Map.of("monthFrom", "2025-11", "monthTo", "2026-02")));
        assertNotEquals(narrow, wide, "不同参数范围必须产生不同签名，支撑分别建档");
    }

    @Test
    void rangeViolationsEnforcesBinding() {
        Map<String, Object> range = new HashMap<>(Map.of(
                "monthFrom", "2025-11", "monthTo", "2026-02",
                "orgCode", "ANY", "settleStatus", "NULL_ONLY"));
        assertTrue(Params.rangeViolations(Params.normalize(
                Map.of("monthFrom", "2025-11", "monthTo", "2026-02")), range).isEmpty());

        List<String> v1 = Params.rangeViolations(Params.normalize(
                Map.of("monthFrom", "2026-03", "monthTo", "2026-03")), range);
        assertEquals(2, v1.size(), "月份越界应逐项报出");

        List<String> v2 = Params.rangeViolations(Params.normalize(
                Map.of("monthFrom", "2025-11", "monthTo", "2026-02", "settleStatus", "SETTLED")), range);
        assertEquals(1, v2.size(), "NULL_ONLY 绑定拒绝非空值");
    }
}
