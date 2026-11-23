package com.axiom;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.autoconfigure.jdbc.DataSourceAutoConfiguration;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import com.axiom.config.AppProperties;

/**
 * Axiom 应用入口 / Axiom application entry point.
 *
 * <p>排除 DataSourceAutoConfiguration：项目按数据集动态创建 DuckDB/H2 数据源，
 * 避免 Spring Boot 因缺少静态数据源配置而在启动期失败。
 * / Exclude DataSourceAutoConfiguration: datasources are created dynamically per dataset,
 * so a missing static datasource must not fail startup.
 */
@SpringBootApplication(exclude = DataSourceAutoConfiguration.class)
@EnableConfigurationProperties(AppProperties.class)
public class AxiomApplication {

    /**
     * 启动 Axiom 服务 / Start the Axiom service.
     *
     * @param args 命令行参数 / Command-line arguments
     */
    public static void main(String[] args) {
        SpringApplication.run(AxiomApplication.class, args);
    }
}
