package com.example.feed;

import java.lang.management.ManagementFactory;

import org.springframework.dao.DataAccessException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
class HealthController {

    record Health(String status, String db, long uptimeS) {
    }

    record Degraded(String status, String db, String error) {
    }

    private final JdbcTemplate jdbc;

    HealthController(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** Liveness + a cheap DB round-trip. Used by the deploy guide and by k6 setup(). */
    @GetMapping("/health")
    ResponseEntity<?> health() {
        try {
            jdbc.queryForObject("SELECT 1", Integer.class);
            return ResponseEntity.ok(new Health("ok", "ok", uptimeSeconds()));
        } catch (DataAccessException ex) {
            return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                    .body(new Degraded("degraded", "unreachable", ex.getMostSpecificCause().getMessage()));
        }
    }

    private static long uptimeSeconds() {
        return Math.round(ManagementFactory.getRuntimeMXBean().getUptime() / 1000.0);
    }
}
