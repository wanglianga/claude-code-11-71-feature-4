package com.mpi.feeverify.snapshot;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/snapshots")
@Tag(name = "数据快照", description = "冻结只读副本：snap_* 物理表 + 校验和；数据更新后旧结论置 STALE")
public class SnapshotController {

    private final SnapshotService snapshotService;

    public SnapshotController(SnapshotService snapshotService) {
        this.snapshotService = snapshotService;
    }

    @PostMapping("/freeze")
    @Operation(summary = "冻结当前业务基表为新快照（复制表结构+索引+统计信息，计算校验和）")
    public Map<String, Object> freeze(@RequestBody(required = false) Map<String, Object> body) {
        return snapshotService.freeze(body == null ? null : (String) body.get("name"),
                body == null ? null : (String) body.get("note"));
    }

    @GetMapping
    @Operation(summary = "快照列表")
    public List<Map<String, Object>> list() {
        return snapshotService.list();
    }

    @GetMapping("/{id}")
    @Operation(summary = "快照详情")
    public Map<String, Object> get(@PathVariable long id) {
        return snapshotService.get(id);
    }

    @GetMapping("/{id}/sample")
    @Operation(summary = "可计算的费用快照样例：机构级汇总+总额+可手工重算的 SQL")
    public Map<String, Object> sample(@PathVariable long id) {
        return snapshotService.sample(id);
    }
}
