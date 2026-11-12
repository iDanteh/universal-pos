package com.posplatform.presentation;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;

/**
 * Read-only system endpoint (public by design, see SecurityConfig).
 * All tenant/business endpoints will live behind authentication from v0.2.
 */
@RestController
@RequestMapping("/api/v1")
public class SystemStatusController {

    private final SystemStatusService systemStatusService;

    public SystemStatusController(SystemStatusService systemStatusService) {
        this.systemStatusService = systemStatusService;
    }

    public record SystemStatusView(
            String status,
            String application,
            String version,
            String database,
            Instant checkedAt) {
    }

    @GetMapping("/system/status")
    public SystemStatusView status() {
        return systemStatusService.status();
    }
}
