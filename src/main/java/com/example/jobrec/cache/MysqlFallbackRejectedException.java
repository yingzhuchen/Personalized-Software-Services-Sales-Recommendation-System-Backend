package com.example.jobrec.cache;

/**
 * Thrown when Redis is down and the per-JVM MySQL fallback bulkhead is full.
 * Callers should fail the request rather than queue more origin load onto MySQL.
 */
public class MysqlFallbackRejectedException extends RuntimeException {
    public MysqlFallbackRejectedException(String message) {
        super(message);
    }
}
