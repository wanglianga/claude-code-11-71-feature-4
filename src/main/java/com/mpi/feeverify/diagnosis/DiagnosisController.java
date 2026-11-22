package com.mpi.feeverify.diagnosis;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/registrations/{registrationId}")
@Tag(name = "规则诊断", description = "识别可索引化条件、冗余关联、跨期过滤；按参数签名+计划哈希分别建档")
public class DiagnosisController {

    private final DiagnosisService diagnosisService;

    public DiagnosisController(DiagnosisService diagnosisService) {
        this.diagnosisService = diagnosisService;
    }

    @PostMapping("/diagnose")
    @Operation(summary = "对该登记所有已采集的 (参数签名, 计划哈希) 组合分别诊断建档")
    public List<Map<String, Object>> diagnose(@PathVariable long registrationId) {
        return diagnosisService.diagnose(registrationId);
    }

    @GetMapping("/diagnoses")
    @Operation(summary = "诊断档案列表")
    public List<Map<String, Object>> list(@PathVariable long registrationId) {
        return diagnosisService.list(registrationId);
    }
}
