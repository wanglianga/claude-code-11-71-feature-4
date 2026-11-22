package com.mpi.feeverify.gate;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

@RestController
@Tag(name = "发布门禁", description = "通过裁判的改写绑定到参数范围与权限策略；支持灰度、发布、重绑、回退")
public class GateController {

    private final GateService gateService;

    public GateController(GateService gateService) {
        this.gateService = gateService;
    }

    @PostMapping("/api/candidates/{candidateId}/gate")
    @Operation(summary = "创建门禁（初始 GRAY）：paramRange 不得超出已验证参数范围")
    @SuppressWarnings("unchecked")
    public Map<String, Object> create(@PathVariable long candidateId,
                                      @RequestBody Map<String, Object> body) {
        Object policyId = body.get("policyId");
        if (policyId == null) {
            throw com.mpi.feeverify.support.ApiException.badRequest("policyId 必填");
        }
        return gateService.create(candidateId,
                (Map<String, Object>) body.get("paramRange"),
                ((Number) policyId).longValue());
    }

    @GetMapping("/api/gates")
    @Operation(summary = "门禁列表")
    public List<Map<String, Object>> list() {
        return gateService.list();
    }

    @GetMapping("/api/gates/{id}")
    @Operation(summary = "门禁详情")
    public Map<String, Object> get(@PathVariable long id) {
        return gateService.get(id);
    }

    @PostMapping("/api/gates/{id}/publish")
    @Operation(summary = "灰度 → 正式发布")
    public Map<String, Object> publish(@PathVariable long id) {
        return gateService.publish(id);
    }

    @PostMapping("/api/gates/{id}/rebind")
    @Operation(summary = "策略收窄后重新绑定到生效策略")
    public Map<String, Object> rebind(@PathVariable long id, @RequestBody Map<String, Object> body) {
        return gateService.rebind(id, ((Number) body.get("policyId")).longValue());
    }

    @PostMapping("/api/gates/{id}/rollback")
    @Operation(summary = "回退：路由恢复原语句，账本记录受影响请求与恢复依据")
    public Map<String, Object> rollback(@PathVariable long id,
                                        @RequestBody(required = false) Map<String, Object> body) {
        return gateService.rollback(id, body == null ? null : (String) body.get("reason"));
    }
}
