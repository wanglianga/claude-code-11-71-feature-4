package com.mpi.feeverify.candidate;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

@RestController
@Tag(name = "候选生成", description = "输出等价的条件下推、分页、预聚合、连接重排改写")
public class CandidateController {

    private final CandidateService candidateService;

    public CandidateController(CandidateService candidateService) {
        this.candidateService = candidateService;
    }

    @PostMapping("/api/registrations/{registrationId}/candidates")
    @Operation(summary = "生成改写候选：types=[PUSHDOWN,PAGINATION,PRE_AGGREGATE,JOIN_REORDER]，"
            + "preAggregateVariants=[current,stale]")
    @SuppressWarnings("unchecked")
    public List<Map<String, Object>> generate(@PathVariable long registrationId,
                                              @RequestBody Map<String, Object> body) {
        List<String> types = (List<String>) body.getOrDefault("types",
                List.of("PUSHDOWN", "PAGINATION", "JOIN_REORDER", "PRE_AGGREGATE"));
        List<String> variants = (List<String>) body.getOrDefault("preAggregateVariants",
                List.of("current"));
        return candidateService.generate(registrationId, types, variants);
    }

    @GetMapping("/api/registrations/{registrationId}/candidates")
    @Operation(summary = "该登记下的候选列表")
    public List<Map<String, Object>> list(@PathVariable long registrationId) {
        return candidateService.listForRegistration(registrationId);
    }

    @GetMapping("/api/candidates/{id}")
    @Operation(summary = "候选详情（含改写后 SQL）")
    public Map<String, Object> get(@PathVariable long id) {
        return candidateService.get(id);
    }
}
