package com.mpi.feeverify.judge;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

@RestController
@Tag(name = "结果裁判", description = "比较明细主键集合、金额、机构级分组；总金额相同而机构分组不同则阻断并定位差异组")
public class JudgeController {

    private final JudgeService judgeService;

    public JudgeController(JudgeService judgeService) {
        this.judgeService = judgeService;
    }

    @PostMapping("/api/candidates/{candidateId}/judge")
    @Operation(summary = "对最近一次成功的影子执行做结果裁判")
    public Map<String, Object> judge(@PathVariable long candidateId) {
        return judgeService.judge(candidateId);
    }

    @GetMapping("/api/candidates/{candidateId}/judgements")
    @Operation(summary = "该候选的裁判记录")
    public List<Map<String, Object>> list(@PathVariable long candidateId) {
        return judgeService.listForCandidate(candidateId);
    }

    @GetMapping("/api/judgements/{id}")
    @Operation(summary = "裁判详情（含差异组/缺失主键/缺失原因）")
    public Map<String, Object> get(@PathVariable long id) {
        return judgeService.get(id);
    }
}
