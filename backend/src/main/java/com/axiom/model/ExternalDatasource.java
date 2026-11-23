package com.axiom.model;

import com.fasterxml.jackson.annotation.JsonIgnore;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 外部 JDBC 数据源配置 / External JDBC datasource configuration.
 *
 * <p>存储连接参数与加密后的密码，用于直连 MySQL/PostgreSQL 业务库。
 * 密码以密文存储（@JsonIgnore 避免 API 响应泄露），查询时由 CryptoService 解密。
 * / Stores connection parameters and an encrypted password for direct connections to
 * MySQL/PostgreSQL. The password is stored encrypted and hidden from API responses via
 * @JsonIgnore; CryptoService decrypts it at query time.
 */
@Data
public class ExternalDatasource {

    /** 数据源 ID / Datasource ID. */
    private String id;

    /** 显示名称 / Display name. */
    private String name;

    /** 类型：mysql | postgresql / Type. */
    private String type;

    /** JDBC URL / JDBC URL. */
    private String jdbcUrl;

    /** 用户名 / Username. */
    private String username;

    /** 加密后的密码（不出现在 JSON 响应）/ Encrypted password (excluded from JSON). */
    @JsonIgnore
    private String encryptedPassword;

    /** 连接状态：active | error / Connection status. */
    private String status = "active";

    /** 最近测试时间 / Last test time. */
    private LocalDateTime lastTestedAt;

    /** 创建时间 / Creation time. */
    private LocalDateTime createdAt;
}
