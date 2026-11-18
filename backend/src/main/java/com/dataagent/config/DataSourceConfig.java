package com.dataagent.config;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.jdbc.DataSourceAutoConfiguration;
import org.springframework.boot.autoconfigure.orm.jpa.HibernateJpaAutoConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import javax.sql.DataSource;

/**
 * Explicit dual-datasource setup.
 * Spring Boot's default DataSourceAutoConfiguration is excluded globally in the main class
 * so that both DuckDB (analytics) and H2/SQLite (metadata) can coexist.
 */
@Configuration
public class DataSourceConfig {

    /**
     * 确保 JDBC 数据库文件的父目录存在 / Ensure the parent directory of a JDBC database file exists.
     * 只创建父目录，不创建数据库文件本身 / Only creates the parent dir, not the database file.
     */
    private static void ensureParentDir(String path) {
        try {
            String raw = path.startsWith("jdbc:") ? path.substring(path.lastIndexOf(':') + 1) : path;
            // H2 URL 带 ;AUTO_SERVER=TRUE 等参数，取分号前部分 / Strip H2 URL params after ';'
            int semi = raw.indexOf(';');
            if (semi > 0) raw = raw.substring(0, semi);
            java.nio.file.Path parent = java.nio.file.Path.of(raw).toAbsolutePath().getParent();
            if (parent != null) java.nio.file.Files.createDirectories(parent);
        } catch (Exception e) {
            throw new IllegalStateException("无法创建数据库父目录 / Cannot create DB parent dir: " + path, e);
        }
    }

    @Bean(name = "duckDataSource")
    public DataSource duckDataSource(AppProperties props) {
        ensureParentDir(props.getStorage().getDuckdbPath());
        DriverManagerDataSource ds = new DriverManagerDataSource();
        ds.setDriverClassName("org.duckdb.DuckDBDriver");
        String path = props.getStorage().getDuckdbPath();
        ds.setUrl(path.startsWith("jdbc:") ? path : "jdbc:duckdb:" + path);
        return ds;
    }

    @Bean(name = "metaDataSource")
    @Primary
    public DataSource metaDataSource(AppProperties props) {
        DriverManagerDataSource ds = new DriverManagerDataSource();
        String type = props.getStorage().getMetadataType().toLowerCase();
        if ("sqlite".equals(type)) {
            ensureParentDir(props.getStorage().getSqliteUrl());
            ds.setDriverClassName("org.sqlite.JDBC");
            ds.setUrl(props.getStorage().getSqliteUrl());
        } else {
            ensureParentDir(props.getStorage().getH2Url());
            ds.setDriverClassName("org.h2.Driver");
            ds.setUrl(props.getStorage().getH2Url());
        }
        ds.setUsername(props.getStorage().getUsername());
        ds.setPassword(props.getStorage().getPassword());
        return ds;
    }

    @Bean(name = "duckJdbcTemplate")
    public JdbcTemplate duckJdbcTemplate(@Qualifier("duckDataSource") DataSource ds) {
        return new JdbcTemplate(ds);
    }

    @Bean(name = "metaJdbcTemplate")
    @Primary
    public JdbcTemplate metaJdbcTemplate(@Qualifier("metaDataSource") DataSource ds) {
        return new JdbcTemplate(ds);
    }
}
