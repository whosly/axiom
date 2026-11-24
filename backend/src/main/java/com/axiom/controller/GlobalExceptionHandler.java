package com.axiom.controller;

import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.servlet.resource.NoResourceFoundException;

import java.util.Map;

/**
 * 全局异常处理 / Global exception handler.
 *
 * <p>为未显式处理的异常提供统一响应结构，避免底层异常原文（可能含内部路径或 SQL）泄漏到前端。
 * / Provides a uniform response for unhandled exceptions so raw internal messages (paths, SQL) never leak.
 */
@Slf4j
@RestControllerAdvice
public class GlobalExceptionHandler {

    /** 参数错误返回 400 / Bad input maps to 400. */
    @ExceptionHandler({IllegalArgumentException.class, MethodArgumentNotValidException.class})
    public ResponseEntity<Map<String, Object>> onBadRequest(Exception e) {
        // 参数校验异常来自用户输入，回显消息是安全的；仍去掉换行避免日志注入。
        // Validation errors originate from user input; sanitize newlines to avoid log injection.
        String msg = oneLine(e.getMessage(), "请求参数无效 / Invalid request");
        return ResponseEntity.badRequest().body(Map.of("success", false, "error", msg));
    }

    /** 路径/静态资源不存在返回 404 / Unknown paths and missing resources map to 404. */
    @ExceptionHandler(NoResourceFoundException.class)
    public ResponseEntity<Map<String, Object>> onNotFound(NoResourceFoundException e) {
        // 正常的 404 属于客户端导航行为，按 500 上报会污染错误日志。
        // / A legitimate 404 is client navigation; reporting it as 500 pollutes error logs.
        return ResponseEntity.status(HttpStatus.NOT_FOUND)
                .body(Map.of("success", false, "error", "资源不存在 / Resource not found"));
    }

    /** 其余异常统一 500，不回显内部细节 / Everything else is 500 without internal details. */
    @ExceptionHandler(Exception.class)
    public ResponseEntity<Map<String, Object>> onUnexpected(Exception e) {
        log.error("[Unhandled] {}", e.getMessage(), e);
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(Map.of("success", false, "error", "服务器内部错误 / Internal server error"));
    }

    private String oneLine(String raw, String fallback) {
        if (raw == null || raw.isBlank()) return fallback;
        return raw.replace('\n', ' ').replace('\r', ' ');
    }
}
