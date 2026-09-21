package com.example.jobrec.controller;

import com.example.jobrec.cache.MysqlFallbackRejectedException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * When Redis is down and the MySQL fallback bulkhead is saturated, fail the
 * request with 503 instead of letting unbounded origin traffic hit RDS.
 */
@RestControllerAdvice
public class CacheDegradationHandler {
    @ExceptionHandler(MysqlFallbackRejectedException.class)
    public ResponseEntity<Map<String, Object>> handleMysqlFallbackRejected(MysqlFallbackRejectedException ex) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("status", "UNAVAILABLE");
        body.put("reason", "redis_down_mysql_protected");
        body.put("message", ex.getMessage());
        return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).body(body);
    }
}
