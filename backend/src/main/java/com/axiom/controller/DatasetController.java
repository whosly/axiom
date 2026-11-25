package com.axiom.controller;

import com.axiom.model.*;
import com.axiom.repository.DatasetRepository;
import com.axiom.repository.MetadataRepository;
import com.axiom.service.AnalysisOrchestrator;
import com.axiom.service.DataIngestionService;
import com.axiom.service.DataIngestionService.ParsedData;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.time.LocalDateTime;
import java.util.*;

@Slf4j
@RestController
@RequestMapping("/api/datasets")
@RequiredArgsConstructor
public class DatasetController {

    private final DataIngestionService ingestion;
    private final DatasetRepository datasetRepo;
    private final MetadataRepository metaRepo;
    private final AnalysisOrchestrator orchestrator;
    private final com.axiom.service.VectorService vectorService;
    private final ObjectMapper mapper;

    @GetMapping
    public Map<String, Object> list() {
        return Map.of("datasets", metaRepo.listDatasets());
    }

    @GetMapping("/{id}")
    public ResponseEntity<?> get(@PathVariable String id) {
        Dataset d = metaRepo.getDataset(id);
        return d == null ? ResponseEntity.status(404).body(Map.of("error", "数据集不存在"))
                : ResponseEntity.ok(Map.of("dataset", d));
    }

    @GetMapping("/{id}/preview")
    public ResponseEntity<?> preview(@PathVariable String id, @RequestParam(defaultValue = "20") int limit) {
        Dataset d = metaRepo.getDataset(id);
        if (d == null) return ResponseEntity.status(404).body(Map.of("error", "数据集不存在"));
        return ResponseEntity.ok(Map.of("rows", datasetRepo.preview(d.getTableName(), limit)));
    }

    @PostMapping("/upload")
    public ResponseEntity<?> upload(@RequestParam("file") MultipartFile file) {
        try {
            ParsedData parsed = ingestion.parse(file.getBytes(), file.getOriginalFilename(), file.getContentType());
            return ResponseEntity.ok(registerDataset(parsed, file.getOriginalFilename(), "file"));
        } catch (IllegalArgumentException e) {
            // 解析校验类异常来自用户文件内容，回显安全 / Parse validation messages are safe to echo.
            return ResponseEntity.badRequest().body(Map.of("error", "解析失败: " + e.getMessage()));
        } catch (Exception e) {
            log.error("[Dataset] upload failed", e);
            return ResponseEntity.badRequest().body(Map.of("error",
                    "解析失败，请查看服务端日志 / Parse failed, see server logs"));
        }
    }

    @PostMapping("/paste")
    public ResponseEntity<?> paste(@RequestBody Map<String, String> body) {
        String text = body.get("text");
        String name = body.getOrDefault("name", "粘贴数据");
        if (text == null || text.isBlank()) {
            return ResponseEntity.badRequest().body(Map.of("error", "数据内容不能为空"));
        }
        try {
            ParsedData parsed = ingestion.parsePasted(text);
            ParsedData withName = new ParsedData(parsed.headers(), parsed.rows(), parsed.columns());
            return ResponseEntity.ok(registerDataset(withName, name, "paste"));
        } catch (IllegalArgumentException e) {
            // 解析校验类异常来自用户输入，回显安全 / Parse validation messages are safe to echo.
            return ResponseEntity.badRequest().body(Map.of("error", "解析失败: " + e.getMessage()));
        } catch (Exception e) {
            log.error("[Dataset] paste failed", e);
            return ResponseEntity.badRequest().body(Map.of("error",
                    "解析失败，请查看服务端日志 / Parse failed, see server logs"));
        }
    }

    @PostMapping("/sample")
    public ResponseEntity<?> sample() {
        String csv = """
            日期,产品,地区,销售额,订单数,成本
            2026-01-05,产品A,华东,12500,42,8200
            2026-01-12,产品A,华南,9800,35,6500
            2026-01-19,产品A,华北,11200,39,7300
            2026-01-26,产品A,华东,15400,51,9100
            2026-02-02,产品B,华东,8900,28,5200
            2026-02-09,产品B,华南,7600,24,4500
            2026-02-16,产品B,华北,6100,19,3800
            2026-02-23,产品B,华东,18200,56,9800
            2026-03-02,产品C,华东,3200,12,2100
            2026-03-09,产品C,华南,2800,10,1900
            2026-03-16,产品C,华北,3500,13,2200
            2026-03-23,产品C,华东,2950,11,2000
            2026-04-01,产品A,华东,16800,58,9900
            2026-04-08,产品A,华南,13200,45,7800
            2026-04-15,产品A,华北,14500,49,8600
            2026-04-22,产品A,华东,22100,72,11800
            2026-05-01,产品B,华东,10500,33,6100
            2026-05-08,产品B,华南,9200,29,5400
            2026-05-15,产品B,华北,8800,27,5100
            2026-05-22,产品B,华东,19800,61,10200
            """;
        try {
            ParsedData parsed = ingestion.parsePasted(csv);
            return ResponseEntity.ok(registerDataset(parsed, "示例销售数据", "sample"));
        } catch (Exception e) {
            // 示例数据与用户输入无关，异常属服务端问题：记录日志、返回通用文案。
            // / Sample data is server-owned; failures are internal — log and return a generic message.
            log.error("[Dataset] sample registration failed", e);
            return ResponseEntity.status(500).body(Map.of("error",
                    "示例数据注册失败，请查看服务端日志 / Sample registration failed, see server logs"));
        }
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<?> delete(@PathVariable String id) {
        Dataset d = metaRepo.getDataset(id);
        if (d == null) return ResponseEntity.status(404).body(Map.of("error", "数据集不存在"));
        // 必须同步清理语义向量：否则已删数据集的字段语义/历史结论会继续注入后续 LLM prompt，
        // 形成跨数据集脏上下文。/ Vectors must be removed with the dataset, or its field
        // semantics and past conclusions keep leaking into future prompts.
        vectorService.deleteByRefId(id);
        datasetRepo.dropTable(d.getTableName());
        metaRepo.deleteDataset(id);
        return ResponseEntity.ok(Map.of("success", true));
    }

    // ---- helpers ----

    private Map<String, Object> registerDataset(ParsedData parsed, String name, String source) throws Exception {
        String id = UUID.randomUUID().toString();
        String table = "ds_" + id.replace("-", "");
        LocalDateTime now = LocalDateTime.now();

        Dataset d = new Dataset(id, name, name, source, parsed.rows().size(),
                parsed.headers().size(), table, parsed.columns(), now, now);

        datasetRepo.createTable(table, parsed);
        try {
            metaRepo.saveDataset(d, mapper.writeValueAsString(parsed.columns()));
        } catch (Exception e) {
            // 跨库双写无分布式事务：元数据落库失败必须补偿删掉刚建的 DuckDB 表，
            // 否则孤儿表永久泄漏且无回收入口。/ No cross-DB transaction: a metadata
            // failure must drop the just-created DuckDB table or it leaks forever.
            datasetRepo.dropTable(table);
            throw e;
        }
        orchestrator.indexDataset(d);

        log.info("[Dataset] registered {} ({} rows, {} cols)", id, d.getRowCount(), d.getColumnCount());
        return Map.of("dataset", d);
    }
}
