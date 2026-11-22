package com.mpi.feeverify;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * 省级医保费用查询改写验证服务。
 * 模块：查询登记 / 计划采集 / 规则诊断 / 候选生成 / 影子执行 / 结果裁判 / 发布门禁 / 路由回退。
 */
@SpringBootApplication
public class FeeVerifyApplication {

    public static void main(String[] args) {
        SpringApplication.run(FeeVerifyApplication.class, args);
    }
}
