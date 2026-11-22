package com.mpi.feeverify;

import com.mpi.feeverify.demo.DemoScenarioService;
import com.mpi.feeverify.router.RouterService;
import com.mpi.feeverify.support.ApiException;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpStatus;
import org.springframework.test.context.ActiveProfiles;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 演示流程集成测试：在独立 schema（verify_it）中真实跑通
 * 参数倾斜 / 分组不一致 / 权限收窄 / 回退演练 / 数据刷新重验。
 */
@SpringBootTest
@ActiveProfiles("test")
class DemoFlowTest {

    @Autowired
    private DemoScenarioService demoScenarioService;

    @Autowired
    private RouterService routerService;

    @Test
    @SuppressWarnings("unchecked")
    void fullDemoFlowCoversAllRequiredScenarios() {
        Map<String, Object> report = demoScenarioService.run();

        // 1. 参数倾斜：相同 SQL 不同参数范围 → 不同计划 → 分别建档
        Map<String, Object> s1 = (Map<String, Object>) report.get("scenario1_paramSkew");
        assertTrue((Boolean) s1.get("plansDiffer"), "窄/宽参数应产生不同计划");
        assertTrue((Boolean) s1.get("diagnosisFiledSeparately"), "不同计划必须分别建档");
        assertEquals(2, ((Number) s1.get("diagnosisCount")).intValue());
        List<Map<String, Object>> diagnoses = (List<Map<String, Object>>) s1.get("diagnoses");
        assertTrue(diagnoses.stream().anyMatch(d -> "NARROW".equals(d.get("paramClass"))));
        assertTrue(diagnoses.stream().anyMatch(d -> "WIDE".equals(d.get("paramClass"))));

        // 2. 影子执行 + 裁判：等价改写 PASS，含机构合并缺陷的预聚合 BLOCK 且定位差异组
        Map<String, Object> s2 = (Map<String, Object>) report.get("scenario2_groupMismatchBlocked");
        assertEquals("BLOCK", s2.get("verdict"));
        assertEquals(Boolean.TRUE, s2.get("totalAmountEqual"), "总金额相同");
        assertEquals(Boolean.FALSE, s2.get("orgGroupEqual"), "但机构分组不同，必须阻断");
        List<Map<String, Object>> diff = (List<Map<String, Object>>) s2.get("orgGroupDiff");
        assertEquals(2, diff.size(), "差异组应定位到 ORG001/ORG002 两家机构");
        assertTrue(diff.stream().anyMatch(d -> "ORG001".equals(d.get("org"))));
        assertTrue(diff.stream().anyMatch(d -> "ORG002".equals(d.get("org"))));

        Map<String, Object> shadow = (Map<String, Object>) report.get("scenario2_shadowAndJudge");
        List<Map<String, Object>> results = (List<Map<String, Object>>) shadow.get("results");
        assertEquals(5, results.size());
        assertEquals(4, results.stream().filter(r -> "PASS".equals(r.get("verdict"))).count());

        // 3. 发布门禁：BLOCK 候选不得进门禁；参数范围不得超出已验证范围
        Map<String, Object> s3 = (Map<String, Object>) report.get("scenario3_releaseGate");
        assertEquals("GRAY", s3.get("gateStatus"));
        assertEquals(Boolean.TRUE, s3.get("blockedCandidateRejected"));
        assertEquals(Boolean.TRUE, s3.get("overGeneralizationRejected"),
                "未验证的月份范围不得绑定（防止小范围验证推广到全省）");
        assertEquals("CANDIDATE", s3.get("inRangeRoutedTo"));
        assertEquals("ORIGINAL", s3.get("outOfRangeRoutedTo"));

        // 4. 权限收窄：在途 → 待复核；旧策略新请求 → 拒绝
        Map<String, Object> s4 = (Map<String, Object>) report.get("scenario4_policyNarrow");
        assertEquals("PENDING_REVIEW", s4.get("inFlightStatusAfterNarrow"));
        assertEquals("REJECTED", s4.get("newRequestRoutedTo"));
        assertTrue(String.valueOf(s4.get("newRequestReason")).contains("POLICY_RETIRED"));

        // 5. 回退演练：账本记录受影响请求，路由恢复原语句
        Map<String, Object> s5 = (Map<String, Object>) report.get("scenario5_rollback");
        assertEquals("ROLLED_BACK", s5.get("gateStatus"));
        assertTrue(((Number) s5.get("affectedRequests")).intValue() >= 2);
        assertEquals("ORIGINAL", s5.get("routedToAfterRollback"));
        assertNotNull(s5.get("ledgerId"));

        // 6. 数据刷新：旧结论 STALE，门禁 BLOCKED，路由回退原语句
        Map<String, Object> s6 = (Map<String, Object>) report.get("scenario6_dataRefresh");
        assertTrue(((Number) s6.get("judgementsMarkedStale")).intValue() >= 1);
        assertEquals("BLOCKED", s6.get("gate2StatusAfterRefresh"));
        assertEquals("ORIGINAL", s6.get("routedToAfterRefresh"));
    }

    @Test
    void routerRejectsInvalidToken() {
        ApiException e = assertThrows(ApiException.class, () ->
                routerService.route("tok-not-exists", 1L,
                        Map.of("monthFrom", "2025-11", "monthTo", "2026-02"), false));
        assertEquals(HttpStatus.UNAUTHORIZED, e.status());
    }

    @Test
    void routerRejectsUnknownGate() {
        ApiException e = assertThrows(ApiException.class, () ->
                routerService.route("tok-audit01", 999999L,
                        Map.of("monthFrom", "2025-11", "monthTo", "2026-02"), false));
        assertEquals(HttpStatus.NOT_FOUND, e.status());
    }

    @Test
    void demoSqlTemplateIsRegistrable() {
        assertFalse(DemoScenarioService.DEMO_SQL.isBlank());
        assertTrue(DemoScenarioService.DEMO_SQL.contains("FROM snap_fee_detail d"));
    }
}
