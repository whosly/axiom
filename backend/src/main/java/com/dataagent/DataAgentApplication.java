package com.dataagent;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.autoconfigure.jdbc.DataSourceAutoConfiguration;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import com.dataagent.config.AppProperties;

@SpringBootApplication(exclude = DataSourceAutoConfiguration.class)
@EnableConfigurationProperties(AppProperties.class)
public class DataAgentApplication {
    public static void main(String[] args) {
        SpringApplication.run(DataAgentApplication.class, args);
    }
}
