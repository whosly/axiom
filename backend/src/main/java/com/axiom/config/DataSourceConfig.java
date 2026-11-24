package com.axiom.config;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.jdbc.DataSourceAutoConfiguration;
import org.springframework.boot.autoconfigure.orm.jpa.HibernateJpaAutoConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.PlatformTransactionManager;

import javax.sql.DataSource;

/**
 * Explicit dual-datasource setup.
 * Spring Boot's default DataSourceAutoConfiguration is excluded globally in the main class
 * so that both DuckDB (analytics) and H2/SQLite (metadata) can coexist.
 *
 * <p>两个数据源都改用 Hikari 连接池：此前 DriverManagerDataSource 每次取连接都物理新建，
 * DuckDB 嵌入式引擎每查询重建，代价极高。/ Both pools replace DriverManagerDataSource,
 * which opened a physical connection per checkout and re-built the embedded DuckDB engine
 * per query.
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

    @Bean(name = "duckDataSource", destroyMethod = "close")
    public DataSource duckDataSource(AppProperties props) {
        ensureParentDir(props.getStorage().getDuckdbPath());
        String path = props.getStorage().getDuckdbPath();
        String url = path.startsWith("jdbc:") ? path : "jdbc:duckdb:" + path;
        HikariConfig cfg = new HikariConfig();
        cfg.setDriverClassName("org.duckdb.DuckDBDriver");
        cfg.setJdbcUrl(url);
        // DuckDB 嵌入式引擎单写多读，小池即可；过大会在 native 层排队。
        // / The embedded engine is single-writer; a small pool avoids native-level queuing.
        cfg.setMaximumPoolSize(4);
        cfg.setPoolName("axiom-duckdb");
        applyPoolGuardrails(cfg);
        return new HikariDataSource(cfg);
    }

    @Bean(name = "metaDataSource", destroyMethod = "close")
    @Primary
    public DataSource metaDataSource(AppProperties props) {
        HikariConfig cfg = new HikariConfig();
        String type = props.getStorage().getMetadataType().toLowerCase();
        if ("sqlite".equals(type)) {
            ensureParentDir(props.getStorage().getSqliteUrl());
            cfg.setDriverClassName("org.sqlite.JDBC");
            cfg.setJdbcUrl(props.getStorage().getSqliteUrl());
        } else {
            ensureParentDir(props.getStorage().getH2Url());
            cfg.setDriverClassName("org.h2.Driver");
            cfg.setJdbcUrl(props.getStorage().getH2Url());
        }
        cfg.setUsername(props.getStorage().getUsername());
        cfg.setPassword(props.getStorage().getPassword());
        // SQLite 同一时刻只允许一个写者，池大只会放大 SQLITE_BUSY 竞争。
        // / SQLite allows one writer at a time; a larger pool only amplifies SQLITE_BUSY.
        cfg.setMaximumPoolSize(2);
        cfg.setPoolName("axiom-meta");
        applyPoolGuardrails(cfg);
        return new HikariDataSource(cfg);
    }

    /**
     * 池守护参数：小池（2~4 连接）+ 多线程竞争下，取连接超时与泄漏检测必须显式设置，
     * 否则连接泄漏时线程静默挂满默认 30s 且无任何诊断输出。
     * / Pool guardrails: with a 2~4-connection pool contended by many threads, checkout
     * timeout and leak detection must be explicit, or a leaking connection stalls threads
     * for the default 30s with zero diagnostics.
     */
    private void applyPoolGuardrails(HikariConfig cfg) {
        cfg.setConnectionTimeout(10_000);
        // 60s 后打泄漏警告日志（不中断连接）：足以定位未关闭的连接。
        // / Leak warning after 60s (connection not interrupted): enough to locate the culprit.
        cfg.setLeakDetectionThreshold(60_000);
        // 空闲连接回收比 TCP 空闲超时短，避免收到已被对端/中间件断开的死连接。
        // / Recycle idle connections below TCP idle timeout so dead sockets are avoided.
        cfg.setMaxLifetime(1_800_000);
        cfg.setIdleTimeout(300_000);
    }

    /**
     * 元数据事务管理器：支撑 DELETE+INSERT 类多步写的原子性。
     * / Transaction manager for the metadata store, enabling atomic multi-step writes.
     */
    @Bean(name = "metaTransactionManager")
    @Primary
    public PlatformTransactionManager metaTransactionManager(@Qualifier("metaDataSource") DataSource ds) {
        return new DataSourceTransactionManager(ds);
    }

    @Bean(name = "duckJdbcTemplate")
    public JdbcTemplate duckJdbcTemplate(@Qualifier("duckDataSource") DataSource ds) {
        return new JdbcTemplate(ds);
    }

    /**
     * 元数据事务模板：供服务层以编程方式包住多步写（如图谱全量重建），
     * 中途失败整体回滚而不是留下半图。
     * / Transaction template for the metadata store, letting services wrap multi-step
     * writes (e.g. full graph rebuild) so a mid-way failure rolls back cleanly.
     */
    @Bean
    public org.springframework.transaction.support.TransactionTemplate transactionTemplate(
            @Qualifier("metaTransactionManager") PlatformTransactionManager tm) {
        return new org.springframework.transaction.support.TransactionTemplate(tm);
    }

    @Bean(name = "metaJdbcTemplate")
    @Primary
    public JdbcTemplate metaJdbcTemplate(@Qualifier("metaDataSource") DataSource ds) {
        return new JdbcTemplate(ds);
    }
}
