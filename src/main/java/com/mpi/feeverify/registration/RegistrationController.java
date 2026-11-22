package com.mpi.feeverify.registration;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/registrations")
@Tag(name = "查询登记", description = "保存语句摘要、用途、授权范围、参数形态与数据快照")
public class RegistrationController {

    private final RegistrationService registrationService;

    public RegistrationController(RegistrationService registrationService) {
        this.registrationService = registrationService;
    }

    @PostMapping
    @Operation(summary = "登记查询语句（仅接受平台模板族：FROM snap_fee_detail d + 行式 AND 条件）")
    @SuppressWarnings("unchecked")
    public Map<String, Object> register(@RequestBody Map<String, Object> body) {
        return registrationService.register(
                (String) body.get("sqlText"),
                (String) body.get("purpose"),
                (Map<String, Object>) body.get("authScope"),
                (List<Map<String, Object>>) body.get("paramShape"),
                body.get("snapshotId") == null ? null : ((Number) body.get("snapshotId")).longValue(),
                (String) body.getOrDefault("createdBy", "api"));
    }

    @GetMapping
    @Operation(summary = "登记列表")
    public List<Map<String, Object>> list() {
        return registrationService.list();
    }

    @GetMapping("/{id}")
    @Operation(summary = "登记详情（含计划/诊断/候选计数）")
    public Map<String, Object> get(@PathVariable long id) {
        return registrationService.get(id);
    }
}
