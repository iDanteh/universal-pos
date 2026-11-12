package com.posplatform.presentation;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.time.Instant;

import com.posplatform.presentation.SystemStatusController.SystemStatusView;

/**
 * Read-only system status used by the admin shell health page.
 * Deeper health semantics live in Actuator; this endpoint exists so the
 * frontend has an application-level signed "the core is alive" signal.
 */
@Service
public class SystemStatusService {

    private final JdbcTemplate jdbcTemplate;
    private final String versionProperty;
    private final String applicationName;

    public SystemStatusService(
            ApplicationMetadata metadata,
            JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
        this.versionProperty = metadata.version();
        this.applicationName = metadata.name();
    }

    public SystemStatusView status() {
        boolean databaseUp = checkDatabase();
        String status = databaseUp ? "UP" : "DEGRADED";
        return new SystemStatusView(
                status,
                applicationName,
                versionProperty,
                databaseUp ? "up" : "down",
                Instant.now());
    }

    private boolean checkDatabase() {
        try {
            jdbcTemplate.queryForObject("SELECT 1", Integer.class);
            return true;
        } catch (RuntimeException ex) {
            return false;
        }
    }
}
