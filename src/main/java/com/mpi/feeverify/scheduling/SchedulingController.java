package com.mpi.feeverify.scheduling;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/scheduling")
@Tag(name = "资源调度", description = "资源策略目录 / 请求指纹 / 队列调度与检查点 / 订阅与回收")
public class SchedulingController {

    private final SchedulingService schedulingService;
    private final QueueSchedulerService scheduler;
    private final SubscriptionService subscriptionService;
    private final FingerprintService fingerprintService;
    private final SchedulingDemoService demoService;

    public SchedulingController(SchedulingService schedulingService, QueueSchedulerService scheduler,
                                SubscriptionService subscriptionService,
                                FingerprintService fingerprintService,
                                SchedulingDemoService demoService) {
        this.schedulingService = schedulingService;
        this.scheduler = scheduler;
        this.subscriptionService = subscriptionService;
        this.fingerprintService = fingerprintService;
        this.demoService = demoService;
    }

    @PostMapping("/submit")
    @Operation(summary = "提交一次费用查询：解析授权范围 → 生成指纹 → 决策执行模型 → 建队与订阅。"
            + "body={token, registrationId, params, purpose?, window?, executionModel?, keepAliveMs?, priority?}")
    public Map<String, Object> submit(@RequestBody Map<String, Object> body) {
        return schedulingService.submit(body);
    }

    @PostMapping("/fingerprint/compare")
    @Operation(summary = "同 SQL 同参数、不同 token（授权范围不同）的指纹对比，证明范围不同必不同指纹")
    @SuppressWarnings("unchecked")
    public Map<String, Object> compare(@RequestBody Map<String, Object> body) {
        return schedulingService.compareScopes(
                ((Number) body.get("registrationId")).longValue(),
                (Map<String, Object>) body.getOrDefault("params", Map.of()),
                (List<String>) body.get("tokens"));
    }

    @GetMapping("/fingerprints")
    @Operation(summary = "最近创建的请求指纹")
    public List<Map<String, Object>> fingerprints(@RequestParam(defaultValue = "50") int limit) {
        return fingerprintService.listRecent(limit);
    }

    @GetMapping("/queues")
    @Operation(summary = "各队列的预算、等待与开始状态")
    public List<Map<String, Object>> queues() {
        return scheduler.queues();
    }

    @GetMapping("/jobs")
    @Operation(summary = "调度任务列表（可按状态过滤）")
    public List<Map<String, Object>> jobs(@RequestParam(required = false) String status,
                                          @RequestParam(defaultValue = "50") int limit) {
        return scheduler.listJobs(status, limit);
    }

    @GetMapping("/jobs/{id}")
    @Operation(summary = "任务详情：检查点、订阅、策略与结果载荷状态")
    public Map<String, Object> job(@PathVariable long id) {
        return scheduler.jobDetail(id);
    }

    @PostMapping("/jobs/{id}/run")
    @Operation(summary = "推进任务执行：body={window?, maxPartitions?}。"
            + "跨时段可用 window 切换 DAY/NIGHT；maxPartitions 用于在分区边界演示暂停/恢复")
    public Map<String, Object> run(@PathVariable long id,
                                   @RequestBody(required = false) Map<String, Object> body) {
        Map<String, Object> b = body == null ? Map.of() : body;
        Object window = b.get("window");
        Integer maxPartitions = b.get("maxPartitions") == null ? null
                : Integer.parseInt(String.valueOf(b.get("maxPartitions")));
        return scheduler.run(id, window == null ? null : String.valueOf(window), maxPartitions);
    }

    @GetMapping("/jobs/{id}/events")
    @Operation(summary = "调度事件流水（队列等待、预算耗尽、策略刷新、检查点、回收等）")
    public List<Map<String, Object>> events(@PathVariable long id) {
        return scheduler.events(id);
    }

    @GetMapping("/subscriptions")
    @Operation(summary = "订阅列表（可按状态过滤）")
    public List<Map<String, Object>> subscriptions(@RequestParam(required = false) String status,
                                                   @RequestParam(defaultValue = "50") int limit) {
        return subscriptionService.list(status, limit);
    }

    @GetMapping("/subscriptions/{id}")
    @Operation(summary = "订阅详情")
    public Map<String, Object> subscription(@PathVariable long id) {
        return subscriptionService.get(id);
    }

    @PostMapping("/subscriptions/{id}/cancel")
    @Operation(summary = "取消订阅：独立任务仅影响本人；合并任务仅在最后一个订阅者取消时才取消共享任务")
    public Map<String, Object> cancel(@PathVariable long id,
                                      @RequestBody(required = false) Map<String, Object> body) {
        String reason = body == null || body.get("reason") == null
                ? "申请人取消" : String.valueOf(body.get("reason"));
        return subscriptionService.cancel(id, reason);
    }

    @PostMapping("/subscriptions/{id}/deliver")
    @Operation(summary = "领取结果：按订阅者授权机构范围裁剪后返回，并启动结果载荷生命周期计时")
    public Map<String, Object> deliver(@PathVariable long id) {
        return subscriptionService.deliver(id);
    }

    @PostMapping("/reclaim")
    @Operation(summary = "回收器：释放到期/无订阅/超过绝对上限的结果载荷并返回释放收益")
    public Map<String, Object> reclaim() {
        return subscriptionService.reclaim();
    }

    @GetMapping("/governance")
    @Operation(summary = "治理视图：合并边界决策 + 实际资源收益（扫描行、载荷字节、订阅数）")
    public Map<String, Object> governance(@RequestParam(defaultValue = "50") int limit) {
        return schedulingService.governanceReport(limit);
    }

    @PostMapping("/demo")
    @Operation(summary = "一键调度演示（body 可传 {window:DAY/NIGHT}）：覆盖权限不同的同 SQL、"
            + "并发取消、跨时段限额切换与大结果集样例，全部真实落库")
    public Map<String, Object> demo(@RequestBody(required = false) Map<String, Object> body) {
        String window = body == null || body.get("window") == null
                ? null : String.valueOf(body.get("window"));
        return demoService.run(window);
    }
}
