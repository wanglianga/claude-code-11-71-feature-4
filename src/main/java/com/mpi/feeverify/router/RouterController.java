package com.mpi.feeverify.router;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/router")
@Tag(name = "路由", description = "按门禁/策略/参数范围路由到候选改写或原语句；在途与待复核请求管理")
public class RouterController {

    private final RouterService routerService;

    public RouterController(RouterService routerService) {
        this.routerService = routerService;
    }

    @PostMapping("/query")
    @Operation(summary = "路由一次费用查询：{token, gateId, params, holdOpen}。"
            + "holdOpen=true 模拟月结长查询（保持 IN_FLIGHT）")
    @SuppressWarnings("unchecked")
    public Map<String, Object> route(@RequestBody Map<String, Object> body) {
        return routerService.route(
                (String) body.get("token"),
                ((Number) body.get("gateId")).longValue(),
                (Map<String, Object>) body.getOrDefault("params", Map.of()),
                Boolean.TRUE.equals(body.get("holdOpen")));
    }

    @PostMapping("/requests/{id}/finish")
    @Operation(summary = "在途请求完成（模拟长查询结束）")
    public Map<String, Object> finish(@PathVariable long id) {
        return routerService.finish(id);
    }

    @PostMapping("/requests/{id}/review")
    @Operation(summary = "复核待复核请求：decision=COMPLETED（放行）/REJECTED（作废）")
    public Map<String, Object> review(@PathVariable long id, @RequestBody Map<String, Object> body) {
        return routerService.review(id, (String) body.get("decision"));
    }

    @GetMapping("/requests/{id}")
    @Operation(summary = "路由请求详情")
    public Map<String, Object> request(@PathVariable long id) {
        return routerService.request(id);
    }

    @GetMapping("/requests")
    @Operation(summary = "某门禁下的路由请求列表")
    public List<Map<String, Object>> list(@RequestParam long gateId) {
        return routerService.listRequests(gateId);
    }
}
