package com.posplatform.modules.cash.application;

import com.posplatform.modules.cash.CashSession;
import com.posplatform.modules.cash.infrastructure.CashSessionRepository;
import com.posplatform.shared.api.ConflictException;
import com.posplatform.shared.api.NotFoundException;
import com.posplatform.shared.outbox.OutboxWriter;
import com.posplatform.shared.tenancy.TenantTransactionRunner;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * CASH use case (fase 7 · ADR-017): the drawer lifecycle. Invariants:
 *
 *  - ONE session OPEN per tenant: the V14 partial unique index is the guard;
 *    the service reads it and answers 409 CASH_SESSION_ALREADY_OPEN first
 *    (the DB constraint remains the last word for races).
 *  - attach: while a session is OPEN the sales service stamps
 *    sale.cash_session_id (same transaction, before the outbox event).
 *  - reconciliation CASH-ONLY: expected_cash = opening_float + SUM(CASH
 *    payments of the attached sales) - SUM(CASH refunds attached, fase 9 ·
 *    ADR-019); difference = counted_cash - expected_cash (the cash CHANGE
 *    given from the drawer already reduced the recorded CASH payment, so
 *    Efectivo neto IS the drawer number — and a CASH refund leaves it).
 *  - card/transfer/other counts are informative snapshots (bank statement
 *    is their reconciliation source), stored but never summed into expected;
 *    refunds through those methods are equally informative.
 *
 * Devoluciones attached to the shift: fase 9 (ADR-019).
 */
@Service
public class CashService {

    private final CashSessionRepository sessionRepository;
    private final OutboxWriter outboxWriter;
    private final TenantTransactionRunner tenantRunner;
    private final JdbcTemplate jdbc;

    public CashService(CashSessionRepository sessionRepository,
                       OutboxWriter outboxWriter,
                       TenantTransactionRunner tenantRunner,
                       JdbcTemplate jdbc) {
        this.sessionRepository = sessionRepository;
        this.outboxWriter = outboxWriter;
        this.tenantRunner = tenantRunner;
        this.jdbc = jdbc;
    }

    // ---- Views ----

    public record CashSessionView(
            UUID id,
            String status,
            String openingFloat,
            String openedAt,
            String closedAt,
            String countedCash,
            String countedCard,
            String countedTransfer,
            String countedOther,
            String expectedCash,
            String difference,
            String closeNote,
            long salesCount,
            String cashTotal,
            long refundsCount,
            String cashRefunds
    ) {
    }

    // ---- Use cases ----

    @Transactional
    public CashSessionView open(UUID tenantId, UUID userId, BigDecimal openingFloat) {
        return tenantRunner.run(tenantId, () -> {
            sessionRepository.findByTenantIdAndStatus(tenantId, CashSession.Status.OPEN)
                    .ifPresent(current -> {
                        throw new ConflictException("CASH_SESSION_ALREADY_OPEN",
                                "Ya existe una sesión de caja abierta (mientras una caja esté abierta no se puede abrir otra)");
                    });

            CashSession session = sessionRepository.save(
                    new CashSession(tenantId, userId, money(openingFloat, 2)));

            outboxWriter.append(
                    tenantId, "cash.session.opened", "cash_session", session.getId(),
                    Map.of("openingFloat", session.getOpeningFloat().toPlainString(),
                            "openedBy", userId.toString()));

            return viewOf(tenantId, session);
        });
    }

    @Transactional
    public CashSessionView close(UUID tenantId, UUID sessionId,
                                 CloseCounts counts, String note) {
        return tenantRunner.run(tenantId, () -> {
            CashSession session = sessionRepository
                    .findByTenantIdAndId(tenantId, sessionId)
                    .orElseThrow(() -> new NotFoundException("cash_session", sessionId.toString()));
            if (session.getStatus() == CashSession.Status.CLOSED) {
                throw new ConflictException("CASH_SESSION_ALREADY_CLOSED",
                        "La sesión de caja ya está cerrada");
            }

            BigDecimal expectedCash = session.getOpeningFloat()
                    .add(cashPaidOfSession(tenantId, session.getId()))
                    .subtract(cashRefundsOf(tenantId, session.getId()))
                    .setScale(2, RoundingMode.HALF_UP);
            BigDecimal difference = counts.cash().subtract(expectedCash)
                    .setScale(2, RoundingMode.HALF_UP);

            session.close(money(counts.cash(), 2), money(counts.card(), 2),
                    money(counts.transfer(), 2), money(counts.other(), 2),
                    expectedCash, difference, blankToNull(note));

            outboxWriter.append(
                    tenantId, "cash.session.closed", "cash_session", session.getId(),
                    Map.of("expectedCash", expectedCash.toPlainString(),
                            "countedCash", session.getCountedCash().toPlainString(),
                            "difference", difference.toPlainString(),
                            "salesCount", String.valueOf(salesCountOf(tenantId, session.getId()))));

            return viewOf(tenantId, session);
        });
    }

    /** Optional on purpose: a tenant without an open drawer is a NORMAL state. */
    @Transactional(readOnly = true)
    public Optional<CashSessionView> currentOpen(UUID tenantId) {
        return tenantRunner.run(tenantId, () ->
                sessionRepository.findByTenantIdAndStatus(tenantId, CashSession.Status.OPEN)
                        .map(session -> viewOf(tenantId, session)));
    }

    @Transactional(readOnly = true)
    public List<CashSessionView> list(UUID tenantId) {
        return tenantRunner.run(tenantId, () -> {
            List<CashSessionView> views = new ArrayList<>();
            for (CashSession session :
                    sessionRepository.findTop50ByTenantIdOrderByOpenedAtDesc(tenantId)) {
                views.add(viewOf(tenantId, session));
            }
            return views;
        });
    }

    @Transactional(readOnly = true)
    public CashSessionView get(UUID tenantId, UUID sessionId) {
        return tenantRunner.run(tenantId, () ->
                sessionRepository.findByTenantIdAndId(tenantId, sessionId)
                        .map(session -> viewOf(tenantId, session))
                        .orElseThrow(() -> new NotFoundException("cash_session", sessionId.toString())));
    }

    // ---- Internals ----

    private CashSessionView viewOf(UUID tenantId, CashSession session) {
        return new CashSessionView(
                session.getId(), String.valueOf(session.getStatus()),
                session.getOpeningFloat().toPlainString(),
                session.getOpenedAt().toString(),
                session.getClosedAt() == null ? null : session.getClosedAt().toString(),
                session.getCountedCash() == null ? null : session.getCountedCash().toPlainString(),
                session.getCountedCard() == null ? null : session.getCountedCard().toPlainString(),
                session.getCountedTransfer() == null ? null : session.getCountedTransfer().toPlainString(),
                session.getCountedOther() == null ? null : session.getCountedOther().toPlainString(),
                session.getExpectedCash() == null ? null : session.getExpectedCash().toPlainString(),
                session.getDifference() == null ? null : session.getDifference().toPlainString(),
                session.getCloseNote(),
                salesCountOf(tenantId, session.getId()),
                cashPaidOfSession(tenantId, session.getId()).toPlainString(),
                refundsCountOf(tenantId, session.getId()),
                cashRefundsOf(tenantId, session.getId()).toPlainString());
    }

    /**
     * CASH expected of the drawer: opening float + attached CASH payments of
     * COMPLETED sales (a canceled sale gave its cash back to the customer —
     * counting it would invent a phantom shortage).
     */
    private BigDecimal cashPaidOfSession(UUID tenantId, UUID sessionId) {
        BigDecimal cash = jdbc.queryForObject("""
                        SELECT COALESCE(SUM(p.amount), 0)
                        FROM sale_payment p
                        JOIN sale s ON s.id = p.sale_id
                                  AND s.tenant_id = p.tenant_id
                        WHERE p.tenant_id = ?
                          AND s.cash_session_id = ?
                          AND s.status = 'COMPLETED'
                          AND p.method = 'CASH'
                        """,
                BigDecimal.class, tenantId, sessionId);
        return (cash == null ? BigDecimal.ZERO : cash).setScale(2, RoundingMode.HALF_UP);
    }

    /** Refunds the shift's drawer paid back (fase 9 · ADR-019). */
    private BigDecimal cashRefundsOf(UUID tenantId, UUID sessionId) {
        BigDecimal refunds = jdbc.queryForObject("""
                        SELECT COALESCE(SUM(r.refund_amount), 0)
                        FROM sale_return r
                        WHERE r.tenant_id = ?
                          AND r.cash_session_id = ?
                          AND r.refund_method = 'CASH'
                        """,
                BigDecimal.class, tenantId, sessionId);
        return (refunds == null ? BigDecimal.ZERO : refunds).setScale(2, RoundingMode.HALF_UP);
    }

    private long refundsCountOf(UUID tenantId, UUID sessionId) {
        Long count = jdbc.queryForObject("""
                        SELECT COUNT(*)
                        FROM sale_return
                        WHERE tenant_id = ? AND cash_session_id = ?
                        """,
                Long.class, tenantId, sessionId);
        return count == null ? 0 : count;
    }

    private long salesCountOf(UUID tenantId, UUID sessionId) {
        Long count = jdbc.queryForObject("""
                        SELECT COUNT(*)
                        FROM sale
                        WHERE tenant_id = ? AND cash_session_id = ?
                        """,
                Long.class, tenantId, sessionId);
        return count == null ? 0 : count;
    }

    private static BigDecimal money(BigDecimal value, int scale) {
        if (value == null) {
            return BigDecimal.ZERO.setScale(scale, RoundingMode.HALF_UP);
        }
        if (value.signum() < 0) {
            throw new IllegalArgumentException("Los conteos de caja no pueden ser negativos");
        }
        return value.setScale(scale, RoundingMode.HALF_UP);
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    /** Per-method count captured at closing time. */
    public record CloseCounts(
            BigDecimal cash,
            BigDecimal card,
            BigDecimal transfer,
            BigDecimal other
    ) {
    }
}
