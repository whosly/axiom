package com.axiom.controller;

import com.axiom.model.ExternalDatasource;
import com.axiom.service.ExternalDatasourceService;
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
        } catch (IllegalArgumentException e) {
            // 校验类异常（URL 白名单等）来自用户输入，回显安全 / Validation messages are safe to echo.
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        } catch (Exception e) {
            return ResponseEntity.badRequest().body(Map.of("error",
                    "数据源处理失败，请查看服务端日志 / Datasource operation failed, see server logs"));
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
            int limit = intParam(body.get("limit"), 1000);
            String name = body.get("name") == null ? null : String.valueOf(body.get("name"));
            return ResponseEntity.ok(service.importQuery(id, sql, limit, name));
        } catch (IllegalArgumentException e) {
            // 只读校验/URL 白名单异常来自用户输入，回显安全 / Read-only & URL guard messages are safe to echo.
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        } catch (Exception e) {
            // 其他异常可能含驱动层主机/SQL 细节，不回显 / Other exceptions may leak driver host/SQL details.
            return ResponseEntity.badRequest().body(Map.of("error",
                    "查询导入失败，请查看服务端日志 / Query import failed, see server logs"));
        }
    }

    /** 删除数据源 / Delete a datasource. */
    @DeleteMapping("/jdbc/{id}")
    public Map<String, Object> delete(@PathVariable String id) {
        service.delete(id);
        return Map.of("success", true);
    }

    /**
     * 解析整数参数：非数字输入抛 IllegalArgumentException，
     * 由 GlobalExceptionHandler 统一转 400，而不是 NumberFormatException 变 500。
     * / Parse an integer param: non-numeric input throws IllegalArgumentException,
     * mapped to 400 by the global handler instead of a NumberFormatException 500.
     */
    private static int intParam(Object raw, int dflt) {
        if (raw == null) return dflt;
        try {
            return Integer.parseInt(String.valueOf(raw));
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("整数参数格式错误 / Invalid integer value: " + raw);
        }
    }
}
