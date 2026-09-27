package com.mqspg;

import org.mybatis.spring.annotation.MapperScan;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * 应用入口。
 *
 * <p>V1 采用「单模块 + 包边界」组织代码，包结构对应 tech-design.md §4.2 的模块划分：
 * <pre>
 *   com.mqspg.common     领域模型、错误码
 *   com.mqspg.config     配置域（cfg_*）与 ConfigRegistry
 *   com.mqspg.mqs        MQS SPI 与消费循环
 *   com.mqspg.transform  转换引擎
 *   com.mqspg.writer     批处理、折叠、PG 写入
 *   com.mqspg.retry      重试与 DLQ
 *   com.mqspg.raw        Raw Message 留存
 *   com.mqspg.console    控制台 API
 * </pre>
 */
@SpringBootApplication
@EnableScheduling
@MapperScan("com.mqspg.**.mapper")
public class MqsPgApplication {

    public static void main(String[] args) {
        SpringApplication.run(MqsPgApplication.class, args);
    }
}
