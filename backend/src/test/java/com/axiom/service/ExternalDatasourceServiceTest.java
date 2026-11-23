package com.axiom.service;

import com.axiom.config.AppProperties;
import com.axiom.model.ExternalDatasource;
import com.axiom.repository.DatasetRepository;
import com.axiom.repository.ExternalDatasourceRepository;
import com.axiom.repository.MetadataRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationEventPublisher;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 外部数据源服务测试 / Tests for external datasource service.
 *
 * <p>覆盖注册、列表、删除与只读 SQL 守卫（安全关键逻辑）。
 * Covers registration, listing, deletion and the read-only SQL guard (security-critical).
 */
@ExtendWith(MockitoExtension.class)
class ExternalDatasourceServiceTest {

    @Mock private ExternalDatasourceRepository repo;
    @Mock private CryptoService crypto;
    @Mock private DatasetRepository datasetRepo;
    @Mock private MetadataRepository metaRepo;
    @Mock private DataIngestionService ingestion;
    @Mock private AnalysisOrchestrator orchestrator;
    @Mock private ApplicationEventPublisher eventPublisher;

    private ExternalDatasourceService service;

    /** 构造被测服务 / Build the service under test. */
    @BeforeEach
    void setUp() {
        service = new ExternalDatasourceService(repo, crypto, datasetRepo, metaRepo,
                ingestion, new ObjectMapper(), orchestrator, new AppProperties(), eventPublisher);
    }

    /** 注册时密码加密、类型小写、密码不回显 / Registration encrypts password and normalizes type. */
    @Test
    @DisplayName("注册数据源密码加密")
    void registerEncryptsPassword() {
        when(crypto.encrypt("secret")).thenReturn("ENC(secret)");
        ExternalDatasource ds = service.register("Prod", "MYSQL", "jdbc:mysql://h/db", "user", "secret");
        assertThat(ds.getType()).isEqualTo("mysql");
        assertThat(ds.getEncryptedPassword()).isEqualTo("ENC(secret)");
        assertThat(ds.getStatus()).isEqualTo("active");
        verify(repo).save(ds);
    }

    /** 列表与删除委托 repo / List and delete delegate to repository. */
    @Test
    @DisplayName("列表与删除委托")
    void listAndDelete() {
        when(repo.listAll()).thenReturn(List.of());
        assertThat(service.listAll()).isEmpty();
        service.delete("ds1");
        verify(repo).delete("ds1");
    }

    /** 只读守卫：拒绝 UPDATE / Read-only guard rejects UPDATE. */
    @Test
    @DisplayName("拒绝非只读 SQL")
    void rejectsNonReadOnlySql() {
        ExternalDatasource ds = new ExternalDatasource();
        ds.setId("ds1");
        when(repo.findById("ds1")).thenReturn(ds);
        assertThatThrownBy(() -> service.importQuery("ds1", "UPDATE t SET x=1", 100, "name"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("只读");
    }

    /** 只读守卫：拒绝多条语句 / Read-only guard rejects multiple statements. */
    @Test
    @DisplayName("拒绝多语句")
    void rejectsMultipleStatements() {
        ExternalDatasource ds = new ExternalDatasource();
        ds.setId("ds1");
        when(repo.findById("ds1")).thenReturn(ds);
        assertThatThrownBy(() -> service.importQuery("ds1", "SELECT 1; DROP TABLE t", 100, "name"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("多条");
    }
}
