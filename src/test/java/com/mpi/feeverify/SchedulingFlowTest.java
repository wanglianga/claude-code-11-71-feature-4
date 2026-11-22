package com.mpi.feeverify;

import com.mpi.feeverify.scheduling.QueueSchedulerService;
import com.mpi.feeverify.scheduling.SchedulingDemoService;
import com.mpi.feeverify.scheduling.SchedulingService;
import com.mpi.feeverify.support.ApiException;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 资源调度集成测试：在独立 schema（verify_it）中真实跑通
 * 权限不同的同 SQL / 并发取消 / 跨时段限额切换 / 大结果集四个样例。
 */
@SpringBootTest
@ActiveProfiles("test")
class SchedulingFlowTest {

    @Autowired
    private SchedulingDemoService demoService;

    @Autowired
    private SchedulingService schedulingService;

    @Autowired
    private QueueSchedulerService scheduler;

    @Test
    @SuppressWarnings("unchecked")
    void schedulingDemoCoversRequiredScenarios() {
        Map<String, Object> report = demoService.run("DAY");

        // A. 同 SQL 同参数、授权范围不同 → 指纹必须不同
        Map<String, Object> a = (Map<String, Object>) report.get("scenarioA_scopeDistinctFingerprint");
        assertEquals(Boolean.TRUE, a.get("allFingerprintsDistinct"));
        assertEquals(Boolean.TRUE, a.get("auditScopesDiffer"));
        List<Map<String, Object>> items = (List<Map<String, Object>>) a.get("items");
        Map<String, Object> audit01 = items.stream()
                .filter(i -> "audit01".equals(i.get("username"))).findFirst().orElseThrow();
        Map<String, Object> audit02 = items.stream()
                .filter(i -> "audit02".equals(i.get("username"))).findFirst().orElseThrow();
        assertEquals("ORG001", audit01.get("scopeKey"));
        assertEquals("ORG002", audit02.get("scopeKey"));
        assertNotEquals(audit01.get("fingerprint"), audit02.get("fingerprint"));

        // A2. 权限不同的同 SQL：不共享任务/结果
        Map<String, Object> a2 = (Map<String, Object>) report.get("scenarioA2_noCrossScopeSharing");
        assertEquals(Boolean.TRUE, a2.get("fingerprintsDiffer"));
        assertEquals(Boolean.TRUE, a2.get("separateJobs"));
        assertEquals(Boolean.TRUE, a2.get("noMergeJoined"));

        // B. 合并 + 并发取消：首个取消者不终止共享任务，最后取消者才收尾；未取消者拿到完整结果
        Map<String, Object> b = (Map<String, Object>) report.get("scenarioB_mergeAndConcurrentCancel");
        assertEquals("MERGE", b.get("duplicateRequestModel"));
        assertEquals(Boolean.TRUE, b.get("thirdJoinedMerge"));
        assertEquals(Boolean.FALSE, ((Map<String, Object>) b.get("firstCancel")).get("lastCanceller"));
        assertEquals(Boolean.TRUE, b.get("jobContinuedForSurvivors"));
        assertEquals(Boolean.TRUE, ((Map<String, Object>) b.get("lastCancel")).get("lastCanceller"));
        int survivorRows = ((Number) b.get("survivorDeliveredRows")).intValue();
        int resultRows = ((Number) b.get("finalResultRows")).intValue();
        assertTrue(survivorRows > 0 && survivorRows == resultRows,
                "未取消订阅者必须拿到完整正确结果: " + survivorRows + " / " + resultRows);
        assertEquals("RECLAIMED", b.get("payloadStatusAfterLastCancel"));

        // C. 跨时段限额切换：运行中高峰策略暂停未开始分区，保留检查点，夜间大配额跑完
        Map<String, Object> c = (Map<String, Object>) report.get("scenarioC_windowLimitSwitch");
        assertEquals(Boolean.TRUE, c.get("peakDidNotStartNewPartition"));
        assertEquals("BUDGET_PAUSED", c.get("afterPeakPolicyStatus"));
        assertEquals("COMPLETED", c.get("nightResumeStatus"));
        assertEquals(Boolean.TRUE, c.get("checkpointPreservedAcrossWindow"));
        assertEquals(((Number) c.get("totalPartitions")).intValue(),
                ((Number) c.get("partitionsDoneAfterNight")).intValue());

        // C2. 队列预算/等待状态
        Map<String, Object> c2 = (Map<String, Object>) report.get("scenarioC2_queueBudgetWaiting");
        assertEquals(Boolean.TRUE, c2.get("budgetSaturated"));
        assertEquals("QUEUED", c2.get("thirdStatus"));
        assertTrue(String.valueOf(c2.get("thirdWaitReason")).contains("队列预算已满"));

        // D. 大结果集：独立执行、载荷裁剪、回收器释放
        Map<String, Object> d = (Map<String, Object>) report.get("scenarioD_largeResultSet");
        assertEquals("PER_REQUEST", d.get("duplicateRequestModel"),
                "结果体积超上限时，即使重复率高也不合并");
        assertEquals(Boolean.TRUE, d.get("truncated"));
        assertEquals("RECLAIMED", d.get("payloadStatusAfterReclaim"));
        assertTrue(((Number) d.get("reclaimReclaimedBytes")).longValue() > 0);
    }

    @Test
    void submitRejectsInvalidToken() {
        ApiException e = assertThrows(ApiException.class, () -> schedulingService.submit(Map.of(
                "token", "tok-not-exists", "registrationId", 1,
                "params", Map.of("monthFrom", "2025-11", "monthTo", "2025-11"))));
        assertEquals(401, e.status().value());
    }

    @Test
    void partitionKeysSplitByMonth() {
        List<String> keys = scheduler.partitionKeys(Map.of(
                "monthFrom", "2025-11", "monthTo", "2026-02"));
        assertEquals(List.of("2025-11", "2025-12", "2026-01", "2026-02"), keys);
        assertFalse(keys.isEmpty());
    }
}
