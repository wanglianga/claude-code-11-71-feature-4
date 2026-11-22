package com.mpi.feeverify.shadow;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/candidates/{candidateId}")
@Tag(name = "影子执行", description = "冻结快照 + 受限资源下运行原语句与候选语句，保存主键集合/金额/机构分组")
public class ShadowController {

    private final ShadowExecutorService shadowExecutor;

    public ShadowController(ShadowExecutorService shadowExecutor) {
        this.shadowExecutor = shadowExecutor;
    }

    @PostMapping("/shadow")
    @Operation(summary = "执行影子验证：原语句与候选语句各跑一次（body.params 为命名参数）")
    @SuppressWarnings("unchecked")
    public Map<String, Object> execute(@PathVariable long candidateId,
                                       @RequestBody Map<String, Object> body) {
        return shadowExecutor.execute(candidateId,
                (Map<String, Object>) body.getOrDefault("params", Map.of()));
    }

    @GetMapping("/executions")
    @Operation(summary = "该候选的影子执行记录")
    public List<Map<String, Object>> executions(@PathVariable long candidateId) {
        return shadowExecutor.listExecutions(candidateId);
    }
}
