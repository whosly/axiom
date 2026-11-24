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
            "\\b(pg_read_file|pg_read_binary_file|pg_ls_dir|pg_stat_file|LOAD_FILE|"
                    + "lo_import|lo_export|UTL_FILE|DBMS_XMLGEN|xp_cmdshell|OPENROWSET|OPENQUERY)\\b");

    /** PostgreSQL COPY ... FROM/TO 服务端读写语句 / PostgreSQL server-side COPY statement form. */
    private static final Pattern COPY_STMT = Pattern.compile("\\bCOPY\\s+[\\w\".\\[\\]]+\\s+(FROM|TO)\\b");

    /** 写服务端文件的 SQL 子句（OUTFILE/DUMPFILE/INTO PROGRAM）/ Server-side file-write clauses. */
    private static final Pattern INTO_FILE = Pattern.compile("\\bINTO\\s+(OUTFILE|DUMPFILE)\\b|\\bINTO\\s+PROGRAM\\b");

    /**
     * 剥离 SQL 注释（保留字符串字面量），注释替换为单个空格以保持词边界。
     * MySQL 词法把块注释当空白，<code>SELECT ... INTO/&minus;&minus;OUTFILE</code> 之类写法
     * 可直接绕过正则匹配，因此所有危险模式校验必须基于剥离后的 SQL。
     * / Strip SQL comments (string literals preserved), replacing each with a single space
     * so word boundaries survive. MySQL lexes block comments as whitespace, letting
     * <code>INTO/&starf;OUTFILE</code> bypass plain regex checks — all dangerous-pattern
     * validation must therefore run on the stripped SQL.
     */
    private String stripSqlComments(String sql) {
        StringBuilder out = new StringBuilder(sql.length());
        boolean inString = false;
        for (int i = 0; i < sql.length(); i++) {
            char c = sql.charAt(i);
            if (inString) {
                out.append(c);
                if (c == '\\' && i + 1 < sql.length()) { // 反斜杠转义（MySQL）/ backslash escape.
                    out.append(sql.charAt(++i));
                } else if (c == '\'') {
                    // '' 双写转义 / doubled-quote escape.
                    if (i + 1 < sql.length() && sql.charAt(i + 1) == '\'') { out.append('\''); i++; }
                    else inString = false;
                }
                continue;
            }
            if (c == '\'') { inString = true; out.append(c); continue; }
            if (c == '/' && i + 1 < sql.length() && sql.charAt(i + 1) == '*') {
                int end = sql.indexOf("*/", i + 2);
                out.append(' ');
                if (end < 0) break; // 未闭合注释吞到结尾（非法 SQL 由驱动拒绝）/ unterminated: swallow rest.
                i = end + 1;
                continue;
            }
            if ((c == '-' && i + 1 < sql.length() && sql.charAt(i + 1) == '-') || c == '#') {
                int nl = sql.indexOf('\n', i);
                out.append(' ');
                if (nl < 0) break;
                i = nl;
                continue;
            }
            out.append(c);
        }
        return out.toString();
    }

    /**
     * JDBC URL 危险驱动参数：本地文件读取（allowLoadLocalInfile）、反序列化 gadget
     * （autoDeserialize）、自定义拦截器/Socket 工厂等已知攻击向量。
     * / Dangerous JDBC driver params: local-file reads, deserialization gadgets,
     * custom interceptors/socket factories — known attack vectors on older drivers.
     */
    private static final Pattern DANGEROUS_URL_PARAMS = Pattern.compile(
            "(?i)\\b(autoDeserialize|queryInterceptors|statementInterceptors|"
                    + "allowLoadLocalInfile|allowUrlInLocalInfile|socketFactory|sslfactory|loggerFile)\\b");

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
        // 入口即校验：非法/危险 URL 在注册阶段直接拒绝，而不是等到查询时才失败。
        // / Validate at the entry point: bad URLs are rejected at registration, not deferred
        // to query time.
        validateJdbcUrl(type, jdbcUrl);
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
        // 校验基于剥离注释后的 SQL：块注释在 MySQL 词法中等价于空白，
        // 不剥离则 INTO/**/OUTFILE 可穿透全部正则防线。
        // / Validate the comment-stripped SQL: block comments lex as whitespace in MySQL,
        // so without stripping INTO/**/OUTFILE sails through every regex guard.
        String upper = stripSqlComments(safeSql).trim().toUpperCase();
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
        if (DANGEROUS_FUNC.matcher(upper).find() || COPY_STMT.matcher(upper).find()
                || INTO_FILE.matcher(upper).find()) {
            throw new IllegalArgumentException("查询包含被禁止的服务端文件访问函数 / Server-side file access is forbidden");
        }
        // 连接 URL 白名单 + 危险参数拦截（查询路径的最后防线）。
        // / URL allowlist + dangerous-param rejection (final guard for the query path).
        validateJdbcUrl(ds.getType(), ds.getJdbcUrl());

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
                // 驱动层截断结果集：limit 只在 Java 侧生效时，大表仍会全量传输占满超时窗口。
                // / Driver-side row cap: without it the full result set streams over the wire
                // even though only `limit` rows are kept.
                ps.setMaxRows(Math.max(1, Math.min(limit, 10000)));
                try (ResultSet rs = ps.executeQuery()) {
                    ResultSetMetaData md = rs.getMetaData();
                    int cols = md.getColumnCount();
                    // 列名 sanitize 后可能重名（如 SELECT a, a AS "a"）：不去重会让
                    // row.put 互相覆盖且 headers 数与实际列数错位，与
                    // DataIngestionService.sanitizeHeaders 的去重行为对齐。
                    // / Sanitized names can collide; dedupe like DataIngestionService
                    // or row.put silently overwrites and headers drift out of alignment.
                    java.util.Set<String> seen = new java.util.HashSet<>();
                    for (int c = 1; c <= cols; c++) {
                        String h = sanitize(md.getColumnLabel(c));
                        String base = h;
                        int suffix = 2;
                        while (seen.contains(h)) h = base + "_" + suffix++;
                        seen.add(h);
                        headers.add(h);
                    }
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

    /**
     * 校验 JDBC URL：协议白名单 + 类型一致性 + 必须是网络 URL + 危险驱动参数拦截。
     * register/connect/importQuery 三个入口统一调用，避免只保护查询路径。
     * / Validates the JDBC URL: protocol allowlist, type consistency, network-URL form and
     * dangerous driver params. Called from register/connect/importQuery so every entry
     * point is protected, not just the query path.
     */
    private void validateJdbcUrl(String type, String jdbcUrl) {
        String expectedPrefix = switch (type == null ? "" : type.toLowerCase()) {
            case "mysql" -> "jdbc:mysql:";
            case "postgresql", "postgres" -> "jdbc:postgresql:";
            default -> throw new IllegalArgumentException("不支持的数据源类型 / Unsupported type: " + type);
        };
        String url = jdbcUrl == null ? "" : jdbcUrl.trim();
        String lower = url.toLowerCase();
        if (!lower.startsWith(expectedPrefix)) {
            throw new IllegalArgumentException("仅支持 jdbc:mysql / jdbc:postgresql 连接 / Only mysql/postgresql URLs allowed");
        }
        // 必须是网络 URL（含 //host 部分），排除 jdbc:mysql:file: 之类的变体协议。
        // / Must be a network URL (contains //host) to exclude exotic sub-protocols.
        if (!lower.startsWith(expectedPrefix + "//")) {
            throw new IllegalArgumentException("JDBC URL 必须包含主机部分（jdbc:...://host/...）/ URL must include a host section");
        }
        // 危险驱动参数（反序列化/本地文件读取/自定义拦截器）一律拒绝。
        // / Reject dangerous driver params (deserialization, local infile, interceptors).
        String query = url.contains("?") ? url.substring(url.indexOf('?') + 1) : "";
        if (!query.isEmpty() && DANGEROUS_URL_PARAMS.matcher(query).find()) {
            throw new IllegalArgumentException("JDBC URL 包含被禁止的驱动参数 / URL contains forbidden driver parameters");
        }
    }

    /** 建立 JDBC 连接 / Establish a JDBC connection. */
    private Connection connect(ExternalDatasource ds) throws SQLException {
        String driver = switch (ds.getType()) {
            case "mysql" -> "com.mysql.cj.jdbc.Driver";
            case "postgresql", "postgres" -> "org.postgresql.Driver";
            default -> throw new IllegalArgumentException("不支持的数据源类型 / Unsupported type: " + ds.getType());
        };
        // 建连前再校验一次（历史数据可能注册于校验加入之前）。
        // / Validate again before connecting (legacy rows may predate the validation).
        validateJdbcUrl(ds.getType(), ds.getJdbcUrl());
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
