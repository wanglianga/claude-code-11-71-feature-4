package com.mpi.feeverify.plan;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/registrations/{registrationId}/plans")
@Tag(name = "计划采集", description = "按参数签名分别 EXPLAIN：执行计划、扫描量、锁等待、代价")
public class PlanController {

    private final PlanCaptureService planCaptureService;

    public PlanController(PlanCaptureService planCaptureService) {
        this.planCaptureService = planCaptureService;
    }

    @PostMapping
    @Operation(summary = "采集计划：body.params 为命名参数（monthFrom/monthTo/orgCode/settleStatus/diagPrefix）")
    @SuppressWarnings("unchecked")
    public Map<String, Object> capture(@PathVariable long registrationId,
                                       @RequestBody Map<String, Object> body) {
        return planCaptureService.capture(registrationId,
                (Map<String, Object>) body.getOrDefault("params", Map.of()));
    }

    @GetMapping
    @Operation(summary = "该登记下的计划采集记录")
    public List<Map<String, Object>> list(@PathVariable long registrationId) {
        return planCaptureService.listForRegistration(registrationId);
    }
}
