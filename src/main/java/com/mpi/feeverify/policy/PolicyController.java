package com.mpi.feeverify.policy;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/policies")
@Tag(name = "权限策略", description = "版本化权限策略；灰度期间收窄时在途请求标记待复核")
public class PolicyController {

    private final PolicyService policyService;

    public PolicyController(PolicyService policyService) {
        this.policyService = policyService;
    }

    @PostMapping
    @Operation(summary = "创建策略新版本（DRAFT）：rules=[{role, allowedOrgs:[...]|\"*\"}]")
    @SuppressWarnings("unchecked")
    public Map<String, Object> create(@RequestBody Map<String, Object> body) {
        return policyService.create((String) body.getOrDefault("family", "fee-query"),
                (List<Map<String, Object>>) body.get("rules"));
    }

    @PostMapping("/{id}/activate")
    @Operation(summary = "生效新版本：旧版本退役；旧策略门禁的在途请求 → PENDING_REVIEW")
    public Map<String, Object> activate(@PathVariable long id) {
        return policyService.activate(id);
    }

    @GetMapping
    @Operation(summary = "策略列表（可按 family 过滤）")
    public List<Map<String, Object>> list(@RequestParam(required = false) String family) {
        return policyService.list(family);
    }

    @GetMapping("/{id}")
    @Operation(summary = "策略详情")
    public Map<String, Object> get(@PathVariable long id) {
        return policyService.get(id);
    }
}
