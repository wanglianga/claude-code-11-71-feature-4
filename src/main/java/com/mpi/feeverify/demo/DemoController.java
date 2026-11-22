package com.mpi.feeverify.demo;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

@RestController
@RequestMapping("/api/demo")
@Tag(name = "演示场景", description = "一键跑通：参数倾斜 / 分组不一致 / 权限收窄 / 回退演练 / 数据刷新重验")
public class DemoController {

    private final DemoScenarioService demoScenarioService;

    public DemoController(DemoScenarioService demoScenarioService) {
        this.demoScenarioService = demoScenarioService;
    }

    @PostMapping("/run")
    @Operation(summary = "运行全部演示场景并返回结构化证据（所有对象真实落库，可逐条复查）")
    public Map<String, Object> run() {
        return demoScenarioService.run();
    }

    @GetMapping("/sql")
    @Operation(summary = "查看演示登记的 SQL 模板与两组参数（窄/宽）")
    public Map<String, Object> demoSql() {
        return Map.of("sql", DemoScenarioService.DEMO_SQL,
                "narrowParams", DemoScenarioService.NARROW_PARAMS,
                "wideParams", DemoScenarioService.WIDE_PARAMS);
    }
}
