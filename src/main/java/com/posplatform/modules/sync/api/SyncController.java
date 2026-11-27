package com.posplatform.modules.sync.api;

import com.fasterxml.jackson.databind.JsonNode;
import com.posplatform.modules.sync.application.SyncService;
import com.posplatform.modules.sync.application.SyncService.ApplyBatchCommand;
import com.posplatform.modules.sync.application.SyncService.BatchOutcome;
import com.posplatform.modules.sync.application.SyncService.BatchView;
import com.posplatform.modules.sync.application.SyncService.DeviceView;
import com.posplatform.modules.sync.application.SyncService.OperationCommand;
import com.posplatform.shared.api.NotFoundException;
import com.posplatform.shared.authz.RequiresPermission;
import com.posplatform.shared.tenancy.TenantContext;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

/**
 * SYNC REST surface (fase 14 · ADR-024). Fail-closed tenant from the verified
 * session; every call needs `sync.operate` (granted to system roles by V20).
 *
 * PUT /sync/devices/{code} is idempotent: the POS can register its till on
 * every start without creating duplicates. POST /sync/batches is the offline
 * delivery: the POS retries with the SAME clientBatchId after a timeout and
 * gets the stored answer; operations are unique per operationId.
 */
@RestController
@RequestMapping("/api/v1/sync")
@RequiresPermission("sync.operate")
public class SyncController {

    private final SyncService syncService;

    public SyncController(SyncService syncService) {
        this.syncService = syncService;
    }

    // ---- Requests ----

    public record DeviceRequest(@NotBlank @Size(max = 120) String name) {
    }

    public record OperationRequest(@NotNull UUID operationId,
                                   @NotBlank String type,
                                   JsonNode payload,
                                   String occurredAt) {
    }

    public record BatchRequest(@NotNull UUID clientBatchId,
                               @NotNull UUID deviceId,
                               @NotEmpty @Size(max = 50) List<OperationRequest> operations) {
    }

    // ---- Devices ----

    @PutMapping("/devices/{code}")
    public DeviceView registerDevice(@PathVariable String code,
                                     @Valid @RequestBody DeviceRequest request) {
        return syncService.upsertDevice(currentTenant(), code, request.name());
    }

    @GetMapping("/devices")
    public List<DeviceView> devices() {
        return syncService.listDevices(currentTenant());
    }

    // ---- Batches ----

    @PostMapping("/batches")
    public ResponseEntity<BatchOutcome> applyBatch(@Valid @RequestBody BatchRequest request) {
        UUID userId = TenantContext.currentUserId()
                .orElseThrow(() -> new NotFoundException("app_user", "current"));
        List<OperationCommand> operations = request.operations().stream()
                .map(operation -> new OperationCommand(
                        operation.operationId(), operation.type(),
                        operation.payload(), operation.occurredAt()))
                .toList();
        BatchOutcome outcome = syncService.applyBatch(currentTenant(), userId,
                new ApplyBatchCommand(request.clientBatchId(), request.deviceId(), operations));
        return ResponseEntity
                .status(outcome.replay() ? HttpStatus.OK : HttpStatus.CREATED)
                .body(outcome);
    }

    @GetMapping("/batches")
    public List<BatchView> batches(@RequestParam(required = false) UUID deviceId) {
        return syncService.listBatches(currentTenant(), deviceId);
    }

    @GetMapping("/batches/{batchId}")
    public BatchView batch(@PathVariable UUID batchId) {
        return syncService.getBatch(currentTenant(), batchId);
    }

    private UUID currentTenant() {
        return TenantContext.currentTenantId()
                .orElseThrow(() -> new NotFoundException("organization", "current"));
    }
}
