package com.posplatform.modules.cash.infrastructure;

import com.posplatform.modules.cash.CashSession;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface CashSessionRepository extends JpaRepository<CashSession, UUID> {

    /** The current drawer: the V14 partial unique index keeps at most one. */
    Optional<CashSession> findByTenantIdAndStatus(UUID tenantId, CashSession.Status status);

    /** Shift history, newest first (V14 idx_cash_session_tenant_opened). */
    List<CashSession> findTop50ByTenantIdOrderByOpenedAtDesc(UUID tenantId);

    Optional<CashSession> findByTenantIdAndId(UUID tenantId, UUID id);
}
