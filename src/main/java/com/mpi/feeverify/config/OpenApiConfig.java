package com.mpi.feeverify.config;

import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Info;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class OpenApiConfig {

    @Bean
    public OpenAPI feeVerifyOpenApi() {
        return new OpenAPI().info(new Info()
                .title("省级医保费用查询改写验证服务")
                .version("1.0.0")
                .description("""
                        面向稽核、病种付费与预算团队的费用明细查询改写验证平台。
                        模块：查询登记 → 计划采集 → 规则诊断 → 候选生成 → 影子执行 →
                        结果裁判 → 发布门禁 → 路由/回退。
                        验证方式：冻结快照的只读副本（snap_* 表 + 校验和），
                        验证结论仅代表该快照，数据更新后需按风险重验。

                        资源调度：资源策略目录（按时段/用途/角色限资源）→ 请求指纹器
                        （模板+参数+快照+授权范围）→ 队列调度器（预算/等待/分区检查点）
                        → 订阅回收器（合并与独立两种执行模型下的取消与资源释放）。"""));
    }
}
