package com.mpi.feeverify.ledger;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;
import com.mpi.feeverify.support.Jsons;

@RestController
@RequestMapping("/api/ledger")
@Tag(name = "回退账本", description = "路由切换、受影响请求与恢复依据（ROLLBACK/POLICY_NARROW/DATA_REFRESH/POLICY_REBIND）")
public class LedgerController {

    private final JdbcTemplate jdbc;

    public LedgerController(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @GetMapping
    @Operation(summary = "账本查询（可按 gateId 过滤）")
    public List<Map<String, Object>> list(@RequestParam(required = false) Long gateId) {
        if (gateId == null) {
            return Jsons.normalizeRows(jdbc.queryForList("SELECT * FROM rollback_ledger ORDER BY id DESC"));
        }
        return Jsons.normalizeRows(jdbc.queryForList(
                "SELECT * FROM rollback_ledger WHERE gate_id = ? ORDER BY id DESC", gateId));
    }
}
