package com.mpi.feeverify.scheduling;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/resource-policies")
@Tag(name = "资源策略目录", description = "按时段(DAY/NIGHT)、用途、角色保存资源上限，版本化生效")
public class ResourcePolicyController {

    private final ResourcePolicyService policyService;

    public ResourcePolicyController(ResourcePolicyService policyService) {
        this.policyService = policyService;
    }

    @GetMapping
    @Operation(summary = "策略目录（可按 window=DAY/NIGHT 过滤）")
    public List<Map<String, Object>> list(@RequestParam(required = false) String window) {
        return policyService.list(window);
    }

    @GetMapping("/resolve")
    @Operation(summary = "解析生效上限：window? + purpose? + role?，按具体到兜底匹配")
    public Map<String, Object> resolve(@RequestParam(required = false) String window,
                                       @RequestParam(required = false) String purpose,
                                       @RequestParam(required = false) String role) {
        return policyService.resolve(window, purpose, role);
    }

    @GetMapping("/current-window")
    @Operation(summary = "按服务器本地时间返回当前时段（DAY/NIGHT）")
    public Map<String, Object> currentWindow() {
        return Map.of("window", policyService.currentWindow());
    }

    @GetMapping("/{id}")
    @Operation(summary = "策略详情")
    public Map<String, Object> get(@PathVariable long id) {
        return policyService.get(id);
    }

    @PostMapping
    @Operation(summary = "新增策略版本（DRAFT）：{window, purpose?, role?, limits:{maxConcurrentTasks,"
            + " maxTotalRows, resultRowCap, statementTimeoutMs, workMem, maxParallelWorkers}}")
    @SuppressWarnings("unchecked")
    public Map<String, Object> create(@RequestBody Map<String, Object> body) {
        return policyService.create(
                (String) body.get("window"),
                (String) body.get("purpose"),
                (String) body.get("role"),
                (Map<String, Object>) body.getOrDefault("limits", Map.of()));
    }

    @PostMapping("/{id}/activate")
    @Operation(summary = "生效新版本：同键旧 ACTIVE 版本退役；运行中任务保留启动时的上限快照")
    public Map<String, Object> activate(@PathVariable long id) {
        return policyService.activate(id);
    }
}
