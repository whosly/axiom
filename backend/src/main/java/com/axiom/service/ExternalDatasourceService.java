package com.axiom.service;

import com.axiom.domain.KnowledgeChangedEvent;
import com.axiom.model.*;
import com.axiom.repository.DatasetRepository;
import com.axiom.repository.ExternalDatasourceRepository;
import com.axiom.repository.MetadataRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.sql.*;
import java.time.LocalDateTime;
import java.util.*;
import java.util.regex.Pattern;

/**
 * 外部 JDBC 数据源服务 / External JDBC datasource service.
 *
 * <p>直连 MySQL/PostgreSQL 业务库，支持测试连接、执行只读 SQL，并把结果导入为本地 Dataset
 * 以便复用现有分析/知识链路。所有外部查询强制只读（只允许 SELECT/WITH/SHOW）。
 * / Connects directly to MySQL/PostgreSQL, supports connection testing and read-only SQL,
 * and imports results as a local Dataset to reuse the existing analysis/knowledge pipeline.
 * All external queries are forced read-only (SELECT/WITH/SHOW only).
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ExternalDatasourceService {

    private final ExternalDatasourceRepository repo;
    private final CryptoService crypto;
    private final DatasetRepository datasetRepo;
    private final MetadataRepository metaRepo;
    private final DataIngestionService ingestion;
    private final ObjectMapper mapper;
    private final AnalysisOrchestrator orchestrator;

    /** data-modifying CTE 关键字，词边界匹配避免误伤列名 / Write keywords with word boundaries to avoid column-name false positives. */
    private static final Pattern WRITE_KEYWORD = Pattern.compile(
            "\\b(INSERT|UPDATE|DELETE|MERGE|CREATE|DROP|ALTER|TRUNCATE|GRANT|REVOKE|CALL|EXEC|EXECUTE)\\b");

    /** 服务端文件/程序访问函数，SELECT 开头也无法通过其余校验 / Server-side file & program access functions. */
    private static final Pattern DANGEROUS_FUNC = Pattern.compile(
            "\\b(pg_read_file|pg_read_binary_file|pg_ls_dir|pg_stat_file|COPY|LOAD_FILE|"
                    + "lo_import|lo_export|UTL_FILE|DBMS_XMLGEN|xp_cmdshell|OPENROWSET|OPENQUERY)\\b");

    /** 写服务端文件的 SQL 子句 / Server-side file-write clauses (MySQL SELECT ... INTO OUTFILE). */
    private static final Pattern INTO_FILE = Pattern.compile("\\bINTO\\s+(OUTFILE|DUMPFILE)\\b|\\bPROGRAM\\b");

    /** 查询超时（秒）/ Query timeout in seconds. */
    private static final int QUERY_TIMEOUT_SECONDS = 30;
    /** 网络 IO 超时（毫秒）/ Socket IO timeout in milliseconds. */
    private static final int QUERY_TIMEOUT_MS = 30_000;

    /**
     * 注册数据源（密码加密存储）/ Register a datasource with encrypted password.
     *
     * @param name     显示名称 / Display name.
     * @param type     mysql | postgresql.
     * @param jdbcUrl  JDBC URL.
     * @param username 用户名 / Username.
     * @param password 明文密码（仅用于加密，不存储）/ Plain password (encrypted, not stored).
     * @return 数据源（密码字段为空）/ Datasource with password field cleared.
     */
    public ExternalDatasource register(String name, String type, String jdbcUrl,
                                       String username, String password) {
        ExternalDatasource ds = new ExternalDatasource();
        ds.setId(UUID.randomUUID().toString());
        ds.setName(name);
        ds.setType(type.toLowerCase());
        ds.setJdbcUrl(jdbcUrl);
        ds.setUsername(username);
        ds.setEncryptedPassword(crypto.encrypt(password));
        ds.setStatus("active");
        ds.setCreatedAt(LocalDateTime.now());
        repo.save(ds);
        log.info("[ExternalDS] registered {} ({})", name, type);
        return ds;
    }

    /** 列出全部数据源（密码已隐藏）/ List all datasources (password hidden). */
    public List<ExternalDatasource> listAll() { return repo.listAll(); }

    /** 删除数据源 / Delete a datasource. */
    public void delete(String id) { repo.delete(id); }

    /**
     * 测试连接 / Test connection.
     *
     * @param id 数据源 ID / Datasource ID.
     * @return 测试结果 / Test result.
     */
    public Map<String, Object> testConnection(String id) {
        ExternalDatasource ds = require(id);
        try (Connection conn = connect(ds)) {
            String catalog = conn.getCatalog();
            int major = conn.getMetaData().getDatabaseMajorVersion();
            ds.setLastTestedAt(LocalDateTime.now());
            ds.setStatus("active");
            repo.save(ds);
            return Map.of("success", true, "catalog", catalog == null ? "" : catalog, "majorVersion", major);
        } catch (Exception e) {
            ds.setStatus("error");
            repo.save(ds);
            return Map.of("success", false, "error", e.getMessage());
        }
    }

    /**
     * 执行只读查询并导入为本地 Dataset / Execute read-only query and import as local Dataset.
     *
     * @param id    数据源 ID / Datasource ID.
     * @param sql   SELECT/WITH/SHOW 查询 / Read-only query.
     * @param limit 最大行数 / Max rows.
     * @param name  导入的数据集名称 / Imported dataset name.
     * @return 导入结果 / Import result.
     */
    public Map<String, Object> importQuery(String id, String sql, int limit, String name) throws Exception {
        ExternalDatasource ds = require(id);
        String safeSql = sql == null ? "" : sql.trim().replaceAll(";\\s*$", "");
        String upper = safeSql.toUpperCase();
        // 只读校验：拒绝非 SELECT/WITH/SHOW，拒绝分号后跟写操作 / Read-only guard.
        if (!(upper.startsWith("SELECT") || upper.startsWith("WITH") || upper.startsWith("SHOW"))) {
            throw new IllegalArgumentException("仅允许只读查询（SELECT/WITH/SHOW）/ Only read-only queries allowed");
        }
        if (upper.contains(";")) throw new IllegalArgumentException("不允许多条语句 / Multiple statements not allowed");
        // WITH 可构造 data-modifying CTE（WITH x AS (DELETE ... RETURNING ...)），前缀校验会漏过。
        // 对 WITH 语句额外扫描顶层关键字，命中写操作直接拒绝。
        // A data-modifying CTE can start with WITH and still write; scan for write keywords.
        if (upper.startsWith("WITH") && WRITE_KEYWORD.matcher(upper).find()) {
            throw new IllegalArgumentException("WITH 查询中不允许写操作（INSERT/UPDATE/DELETE/MERGE 等）/ Data-modifying CTE not allowed");
        }
        // 服务端文件读写的内置函数（SELECT pg_read_file(...)、INTO OUTFILE/LOAD_FILE）必须显式拒绝：
        // 它们以 SELECT 开头，前缀与写关键字校验都拦不住。/ Server-side file access functions start
        // with SELECT and pass every keyword guard; they must be denied explicitly.
        if (DANGEROUS_FUNC.matcher(upper).find() || INTO_FILE.matcher(upper).find()) {
            throw new IllegalArgumentException("查询包含被禁止的服务端文件访问函数 / Server-side file access is forbidden");
        }
        // 连接 URL 白名单：仅允许 mysql/postgresql 协议，阻断自定义协议处理器的
        // SSRF / 参数注入面。/ Protocol allowlist blocks custom JDBC handler SSRF/injection.
        String url = ds.getJdbcUrl() == null ? "" : ds.getJdbcUrl().toLowerCase();
        if (!(url.startsWith("jdbc:mysql:") || url.startsWith("jdbc:postgresql:"))) {
            throw new IllegalArgumentException("仅支持 jdbc:mysql / jdbc:postgresql 连接 / Only mysql/postgresql URLs allowed");
        }

        List<String> headers = new ArrayList<>();
        List<Map<String, Object>> rows = new ArrayList<>();
        try (Connection conn = connect(ds)) {
            // 双保险：SQL 层校验之上，驱动层再强制只读 + 超时，防未知绕过。
            // / Defense in depth: driver-level read-only + timeout on top of SQL checks.
            try {
                conn.setReadOnly(true);
                conn.setNetworkTimeout(null, QUERY_TIMEOUT_MS);
            } catch (Exception ignored) {
                // 个别驱动不支持网络超时参数 / Some drivers reject setNetworkTimeout.
            }
            try (PreparedStatement ps = conn.prepareStatement(safeSql)) {
                ps.setQueryTimeout(QUERY_TIMEOUT_SECONDS);
                try (ResultSet rs = ps.executeQuery()) {
                    ResultSetMetaData md = rs.getMetaData();
                    int cols = md.getColumnCount();
                    for (int c = 1; c <= cols; c++) headers.add(sanitize(md.getColumnLabel(c)));
                    int max = Math.max(1, Math.min(limit, 10000));
                    while (rs.next() && rows.size() < max) {
                        Map<String, Object> row = new LinkedHashMap<>();
                        for (int c = 1; c <= cols; c++) {
                            Object v = rs.getObject(c);
                            row.put(headers.get(c - 1), v == null ? null : String.valueOf(v));
                        }
                        rows.add(row);
                    }
                }
            }
        } catch (SQLException e) {
            throw new IllegalStateException("外部查询失败 / External query failed: " + e.getMessage(), e);
        }

        if (rows.isEmpty()) throw new IllegalArgumentException("查询结果为空 / Query returned no rows");

        // 复用 DataIngestionService 的类型推断，构造 ParsedData。
        // Reuse DataIngestionService for type inference.
        String json = mapper.writeValueAsString(rows);
        DataIngestionService.ParsedData parsed = ingestion.parse(json.getBytes(StandardCharsets.UTF_8),
                "query.json", "application/json");

        String table = "ds_" + UUID.randomUUID().toString().replace("-", "");
        LocalDateTime now = LocalDateTime.now();
        Dataset dataset = new Dataset(UUID.randomUUID().toString(),
                name == null || name.isBlank() ? ds.getName() : name,
                "jdbc:" + ds.getType(), "jdbc", rows.size(), headers.size(),
                table, parsed.columns(), now, now);

        datasetRepo.createTable(table, parsed);
        metaRepo.saveDataset(dataset, mapper.writeValueAsString(parsed.columns()));
        orchestrator.indexDataset(dataset);

        log.info("[ExternalDS] imported {} rows into dataset {}", rows.size(), dataset.getId());
        return Map.of("datasetId", dataset.getId(), "rows", rows.size(), "columns", headers.size());
    }

    /** 建立 JDBC 连接 / Establish a JDBC connection. */
    private Connection connect(ExternalDatasource ds) throws SQLException {
        String driver = switch (ds.getType()) {
            case "mysql" -> "com.mysql.cj.jdbc.Driver";
            case "postgresql", "postgres" -> "org.postgresql.Driver";
            default -> throw new IllegalArgumentException("不支持的数据源类型 / Unsupported type: " + ds.getType());
        };
        try {
            Class.forName(driver);
        } catch (ClassNotFoundException e) {
            throw new IllegalStateException("JDBC driver not found: " + driver, e);
        }
        return DriverManager.getConnection(ds.getJdbcUrl(), ds.getUsername(), crypto.decrypt(ds.getEncryptedPassword()));
    }

    private ExternalDatasource require(String id) {
        ExternalDatasource ds = repo.findById(id);
        if (ds == null) throw new IllegalArgumentException("数据源不存在 / Datasource not found: " + id);
        return ds;
    }

    private String sanitize(String name) {
        String s = name.replaceAll("[^a-zA-Z0-9_\\u4e00-\\u9fa5]", "_");
        return s.matches("^\\d.*") ? "c_" + s : s;
    }
}
