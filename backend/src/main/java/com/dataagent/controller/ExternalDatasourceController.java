package com.dataagent.controller;

import com.dataagent.model.ExternalDatasource;
import com.dataagent.service.ExternalDatasourceService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

/**
 * 外部 JDBC 数据源 API / External JDBC datasource API.
 */
@RestController
@RequestMapping("/api/datasources")
@RequiredArgsConstructor
public class ExternalDatasourceController {

    private final ExternalDatasourceService service;

    /** 注册数据源 / Register a datasource. */
    @PostMapping("/jdbc")
    public ResponseEntity<?> register(@RequestBody Map<String, String> body) {
        try {
            ExternalDatasource ds = service.register(
                    body.get("name"), body.get("type"), body.get("jdbcUrl"),
                    body.get("username"), body.getOrDefault("password", ""));
            return ResponseEntity.ok(Map.of("datasource", ds));
        } catch (Exception e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        }
    }

    /** 列出数据源（密码已隐藏）/ List datasources (password hidden). */
    @GetMapping("/jdbc")
    public Map<String, Object> list() {
        List<ExternalDatasource> list = service.listAll();
        return Map.of("datasources", list);
    }

    /** 测试连接 / Test connection. */
    @PostMapping("/jdbc/{id}/test")
    public Map<String, Object> test(@PathVariable String id) {
        return service.testConnection(id);
    }

    /** 执行只读查询并导入为本地数据集 / Execute read-only query and import as Dataset. */
    @PostMapping("/jdbc/{id}/import")
    public ResponseEntity<?> importQuery(@PathVariable String id, @RequestBody Map<String, Object> body) {
        try {
            String sql = String.valueOf(body.get("sql"));
            int limit = body.get("limit") == null ? 1000 : Integer.parseInt(String.valueOf(body.get("limit")));
            String name = body.get("name") == null ? null : String.valueOf(body.get("name"));
            return ResponseEntity.ok(service.importQuery(id, sql, limit, name));
        } catch (Exception e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        }
    }

    /** 删除数据源 / Delete a datasource. */
    @DeleteMapping("/jdbc/{id}")
    public Map<String, Object> delete(@PathVariable String id) {
        service.delete(id);
        return Map.of("success", true);
    }
}
