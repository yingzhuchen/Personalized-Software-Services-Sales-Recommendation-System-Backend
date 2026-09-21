package com.example.jobrec.controller;

import com.example.jobrec.cache.MysqlFallbackRejectedException;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

class CacheDegradationHandlerTest {
    @Test
    void returnsServiceUnavailableWhenMysqlFallbackRejected() {
        CacheDegradationHandler handler = new CacheDegradationHandler();
        ResponseEntity<Map<String, Object>> response = handler.handleMysqlFallbackRejected(
                new MysqlFallbackRejectedException("MySQL fallback bulkhead full while Redis circuit is OPEN"));

        assertEquals(HttpStatus.SERVICE_UNAVAILABLE, response.getStatusCode());
        assertEquals("UNAVAILABLE", response.getBody().get("status"));
        assertEquals("redis_down_mysql_protected", response.getBody().get("reason"));
    }
}
