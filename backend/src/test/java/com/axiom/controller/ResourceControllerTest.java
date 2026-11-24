package com.axiom.controller;

import com.axiom.model.Dataset;
import com.axiom.repository.DatasetRepository;
import com.axiom.repository.MetadataRepository;
import com.axiom.service.AnalysisOrchestrator;
import com.axiom.service.DataIngestionService;
import com.axiom.service.VectorService;
import com.axiom.service.ExternalDatasourceService;
import com.axiom.llm.LlmProviderFactory;
import com.axiom.config.AppProperties;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.ResponseEntity;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 资源类 Controller 测试 / Tests for resource controllers.
 */
@ExtendWith(MockitoExtension.class)
class ResourceControllerTest {

    @Mock private ExternalDatasourceService dsService;
    @Mock private DataIngestionService ingestion;
    @Mock private DatasetRepository datasetRepo;
    @Mock private MetadataRepository metaRepo;
    @Mock private AnalysisOrchestrator orchestrator;
    @Mock private VectorService vectorService;
    @Mock private LlmProviderFactory llmFactory;

    /** 数据源 Controller 委托 / Datasource controller delegates. */
    @Test
    @DisplayName("数据源 Controller 委托")
    void datasourceController() {
        when(dsService.listAll()).thenReturn(List.of());
        when(dsService.testConnection("ds1")).thenReturn(Map.of("success", true));
        ExternalDatasourceController c = new ExternalDatasourceController(dsService);
        assertThat(c.list()).containsKey("datasources");
        assertThat(c.test("ds1")).containsEntry("success", true);
        c.delete("ds1");
        verify(dsService).delete("ds1");
    }

    /** 数据集列表与预览委托 / Dataset list and preview delegate. */
    @Test
    @DisplayName("数据集列表与预览")
    void datasetListAndPreview() {
        when(metaRepo.listDatasets()).thenReturn(List.of());
        Dataset ds = new Dataset();
        ds.setId("d1");
        ds.setTableName("t1");
        when(metaRepo.getDataset("d1")).thenReturn(ds);
        when(datasetRepo.preview("t1", 20)).thenReturn(List.of(Map.of("a", 1)));

        DatasetController c = new DatasetController(ingestion, datasetRepo, metaRepo,
                orchestrator, vectorService, new ObjectMapper(), new AppProperties(), llmFactory);
        assertThat(c.list()).containsKey("datasets");
        ResponseEntity<?> preview = c.preview("d1", 20);
        assertThat(preview.getStatusCode().is2xxSuccessful()).isTrue();
        verify(datasetRepo).preview("t1", 20);
    }

    /** 数据集不存在时预览返回 404 / Preview of missing dataset returns 404. */
    @Test
    @DisplayName("数据集不存在返回 404")
    void previewMissing() {
        when(metaRepo.getDataset("missing")).thenReturn(null);
        DatasetController c = new DatasetController(ingestion, datasetRepo, metaRepo,
                orchestrator, vectorService, new ObjectMapper(), new AppProperties(), llmFactory);
        ResponseEntity<?> res = c.preview("missing", 10);
        assertThat(res.getStatusCode().is4xxClientError()).isTrue();
    }

    /** 数据集删除委托 / Dataset delete delegates. */
    @Test
    @DisplayName("数据集删除委托")
    void datasetDelete() {
        Dataset ds = new Dataset();
        ds.setId("d1");
        ds.setTableName("t1");
        when(metaRepo.getDataset("d1")).thenReturn(ds);
        DatasetController c = new DatasetController(ingestion, datasetRepo, metaRepo,
                orchestrator, vectorService, new ObjectMapper(), new AppProperties(), llmFactory);
        ResponseEntity<?> res = c.delete("d1");
        assertThat(res.getStatusCode().is2xxSuccessful()).isTrue();
        verify(datasetRepo).dropTable("t1");
        verify(metaRepo).deleteDataset("d1");
    }
}
