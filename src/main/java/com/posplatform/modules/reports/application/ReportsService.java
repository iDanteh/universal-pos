package com.posplatform.modules.reports.application;

import com.posplatform.modules.cash.application.CashService;
import com.posplatform.shared.tenancy.TenantTransactionRunner;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * REPORTS use cases (fase 13 · ADR-023). Read-only projections over the
 * snapshots already persisted by sales/returns/cash/invoicing:
 *
 *  - the ledger is NEVER recomputed: money comes from sale/sale_line/
 *    sale_payment/sale_return/sale_return_line and invoice.withholding_total,
 *    i.e. exactly what the cash register charged and the CFDI declared;
 *  - date ranges are LOCAL to the tenant's business time zone (default
 *    America/Mexico_City), converted to half-open instants [from, to+1) so a
 *    sale at 23:59 belongs to TODAY, not to tomorrow UTC;
 *  - every query is tenant-scoped twice: explicit tenant_id (indexes) and
 *    the RLS session variable set by TenantTransactionRunner (fail-closed).
 *
 * Z report (corte): the shift's OWN numbers via cash_session_id, not a date
 * window — a sale rung at 00:05 after the drawer closed at 23:58 still
 * belongs to the drawer that was open when it was charged.
 */
@Service
public class ReportsService {

    private static final BigDecimal ZERO = BigDecimal.ZERO.setScale(2, RoundingMode.HALF_UP);
    private static final int MAX_RANGE_DAYS = 366;
    private static final int MAX_TOP_PRODUCTS = 10;
    private static final int MAX_Z_SALES = 200;

    private final JdbcTemplate jdbc;
    private final TenantTransactionRunner tenantRunner;
    private final CashService cashService;
    private final ZoneId zone;

    public ReportsService(JdbcTemplate jdbc,
                          TenantTransactionRunner tenantRunner,
                          CashService cashService,
                          @Value("${posplatform.reports.time-zone:America/Mexico_City}") String timeZone) {
        this.jdbc = jdbc;
        this.tenantRunner = tenantRunner;
        this.cashService = cashService;
        this.zone = ZoneId.of(timeZone);
    }

    // ---- Views ----

    public record Range(String from, String to, String timeZone) {
    }

    public record DayRow(String date, long salesCount, String subtotal, String taxTotal,
                         String total, String refunds) {
    }

    public record MethodRow(String method, long count, String amount) {
    }

    public record ProductRow(String productName, String sku, String quantity, String amount) {
    }

    public record SalesReport(Range range, String currency,
                              long salesCount, long canceledCount, long refundsCount,
                              String subtotal, String discountTotal, String taxTotal,
                              String iepsTotal, String ivaTotal, String withheldTotal,
                              String grossSales, String refundsTotal, String netSales,
                              String averageTicket,
                              List<DayRow> byDay,
                              List<MethodRow> byPaymentMethod,
                              List<ProductRow> topProducts) {
    }

    public record TaxRow(String code, String scheme, String rate, String base, String amount) {
    }

    public record TaxesReport(Range range, String currency,
                              List<TaxRow> transferred, List<TaxRow> withheld,
                              String transferredTotal, String withheldTotal) {
    }

    public record SaleRow(long folio, String total, String status, String createdAt,
                          String paymentMethods) {
    }

    public record ZReport(Range range, String currency,
                          UUID sessionId, String status, String openedAt, String closedAt,
                          String openingFloat,
                          long salesCount, long canceledCount, long refundsCount,
                          String subtotal, String discountTotal, String taxTotal,
                          String iepsTotal, String ivaTotal, String withheldTotal,
                          String grossSales, String refundsTotal, String netSales,
                          String cashSales, String cashRefunds,
                          String expectedCash, String countedCash, String difference,
                          String closeNote,
                          List<MethodRow> byPaymentMethod,
                          List<SaleRow> sales) {
    }

    // ---- Sales report ----

    @Transactional(readOnly = true)
    public SalesReport salesReport(UUID tenantId, LocalDate from, LocalDate to) {
        return tenantRunner.run(tenantId, () -> {
            Range range = resolveRange(from, to);
            Timestamp start = Timestamp.from(startOf(range));
            Timestamp end = Timestamp.from(endOf(range));

            Totals totals = totals(tenantId, start, end);
            BigDecimal ieps = sum("""
                    SELECT COALESCE(SUM(sl.ieps_total), 0)
                    FROM sale_line sl
                    JOIN sale s ON s.id = sl.sale_id AND s.tenant_id = sl.tenant_id
                    WHERE sl.tenant_id = ? AND s.status = 'COMPLETED'
                      AND s.created_at >= ? AND s.created_at < ?
                    """, tenantId, start, end);
            BigDecimal withheld = sum("""
                    SELECT COALESCE(SUM(withholding_total), 0)
                    FROM invoice
                    WHERE tenant_id = ? AND status = 'STAMPED'
                      AND created_at >= ? AND created_at < ?
                    """, tenantId, start, end);
            Refunds refunds = refunds(tenantId, start, end);

            BigDecimal averageTicket = totals.salesCount() == 0 ? ZERO
                    : totals.total().divide(BigDecimal.valueOf(totals.salesCount()), 2, RoundingMode.HALF_UP);
            BigDecimal netSales = totals.total().subtract(refunds.total());

            return new SalesReport(
                    range, "MXN",
                    totals.salesCount(), totals.canceledCount(), refunds.count(),
                    money(totals.subtotal()), money(totals.discount()), money(totals.tax()),
                    money(ieps), money(totals.tax().subtract(ieps)), money(withheld),
                    money(totals.total()), money(refunds.total()), money(netSales),
                    money(averageTicket),
                    byDay(tenantId, start, end),
                    methods(tenantId, start, end),
                    topProducts(tenantId, start, end));
        });
    }

    // ---- Taxes report ----

    @Transactional(readOnly = true)
    public TaxesReport taxesReport(UUID tenantId, LocalDate from, LocalDate to) {
        return tenantRunner.run(tenantId, () -> {
            Range range = resolveRange(from, to);
            Timestamp start = Timestamp.from(startOf(range));
            Timestamp end = Timestamp.from(endOf(range));

            // NET traslados: completed sale lines (+) minus return lines (-).
            // IVA base is IEPS-inclusive; IEPS base is the line net. A line
            // without a tax code contributes nothing (pre-tax legacy rows).
            List<TaxRow> transferred = jdbc.query("""
                            WITH rows AS (
                                SELECT sl.tax_rate_code AS iva_code, sl.ieps_rate_code AS ieps_code,
                                       sl.line_total, sl.ieps_total, sl.tax_total, 1 AS sign
                                FROM sale_line sl
                                JOIN sale s ON s.id = sl.sale_id AND s.tenant_id = sl.tenant_id
                                WHERE sl.tenant_id = ? AND s.status = 'COMPLETED'
                                  AND s.created_at >= ? AND s.created_at < ?
                                UNION ALL
                                SELECT rl.tax_rate_code, rl.ieps_rate_code,
                                       rl.line_total, rl.ieps_total, rl.tax_total, -1
                                FROM sale_return_line rl
                                JOIN sale_return r ON r.id = rl.return_id AND r.tenant_id = rl.tenant_id
                                WHERE rl.tenant_id = ? AND r.created_at >= ? AND r.created_at < ?
                            )
                            SELECT x.code, tr.scheme, tr.rate,
                                   SUM(x.base) AS base, SUM(x.amount) AS amount
                            FROM (
                                SELECT iva_code AS code,
                                       (line_total + ieps_total) * sign AS base,
                                       (tax_total - ieps_total) * sign AS amount
                                FROM rows WHERE iva_code IS NOT NULL
                                UNION ALL
                                SELECT ieps_code AS code,
                                       line_total * sign AS base,
                                       ieps_total * sign AS amount
                                FROM rows WHERE ieps_code IS NOT NULL
                            ) x
                            JOIN tax_rate tr ON tr.code = x.code
                            GROUP BY x.code, tr.scheme, tr.rate
                            ORDER BY tr.scheme, x.code
                            """,
                    taxRowMapper(), tenantId, start, end, tenantId, start, end);

            List<TaxRow> withheld = jdbc.query("""
                            SELECT wh ->> 'code' AS code,
                                   wh ->> 'scheme' AS scheme,
                                   MAX(wh ->> 'rate') AS rate,
                                   SUM((wh ->> 'amount')::numeric) AS amount
                            FROM invoice i
                            CROSS JOIN LATERAL jsonb_array_elements(i.withholding) wh
                            WHERE i.tenant_id = ? AND i.status = 'STAMPED'
                              AND i.withholding IS NOT NULL
                              AND i.created_at >= ? AND i.created_at < ?
                            GROUP BY wh ->> 'code', wh ->> 'scheme'
                            ORDER BY 2, 1
                            """,
                    (rs, i) -> new TaxRow(rs.getString("code"), rs.getString("scheme"),
                            new BigDecimal(rs.getString("rate")).stripTrailingZeros().toPlainString(),
                            null, money(rs.getBigDecimal("amount"))),
                    tenantId, start, end);

            BigDecimal transferredTotal = transferred.stream()
                    .map(row -> new BigDecimal(row.amount()))
                    .reduce(ZERO, BigDecimal::add);
            BigDecimal withheldTotal = withheld.stream()
                    .map(row -> new BigDecimal(row.amount()))
                    .reduce(ZERO, BigDecimal::add);

            return new TaxesReport(range, "MXN", transferred, withheld,
                    money(transferredTotal), money(withheldTotal));
        });
    }

    // ---- Z report (corte de caja) ----

    @Transactional(readOnly = true)
    public ZReport zReport(UUID tenantId, UUID cashSessionId) {
        return tenantRunner.run(tenantId, () -> {
            // Fail-closed: an unknown/foreign session is 404 before any query.
            CashService.CashSessionView session = cashService.get(tenantId, cashSessionId);

            Totals totals = totals(tenantId, cashSessionId);

            BigDecimal ieps = sum("""
                    SELECT COALESCE(SUM(sl.ieps_total), 0)
                    FROM sale_line sl
                    JOIN sale s ON s.id = sl.sale_id AND s.tenant_id = sl.tenant_id
                    WHERE sl.tenant_id = ? AND s.cash_session_id = ?
                      AND s.status = 'COMPLETED'
                    """, tenantId, cashSessionId);
            BigDecimal withheld = sum("""
                    SELECT COALESCE(SUM(i.withholding_total), 0)
                    FROM invoice i
                    JOIN sale s ON s.id = i.sale_id AND s.tenant_id = i.tenant_id
                    WHERE i.tenant_id = ? AND s.cash_session_id = ?
                      AND i.status = 'STAMPED'
                    """, tenantId, cashSessionId);
            Refunds refunds = refundsOfSession(tenantId, cashSessionId);

            List<MethodRow> methods = jdbc.query("""
                            SELECT p.method, COUNT(*) AS count, COALESCE(SUM(p.amount), 0) AS amount
                            FROM sale_payment p
                            JOIN sale s ON s.id = p.sale_id AND s.tenant_id = p.tenant_id
                            WHERE p.tenant_id = ? AND s.cash_session_id = ?
                              AND s.status = 'COMPLETED'
                            GROUP BY p.method
                            ORDER BY p.method
                            """,
                    methodRowMapper(), tenantId, cashSessionId);

            BigDecimal cashSales = methods.stream()
                    .filter(row -> row.method().equals("CASH"))
                    .map(row -> new BigDecimal(row.amount()))
                    .reduce(ZERO, BigDecimal::add);
            BigDecimal cashRefunds = refunds.cash();
            BigDecimal expected = new BigDecimal(session.openingFloat())
                    .add(cashSales)
                    .subtract(cashRefunds)
                    .setScale(2, RoundingMode.HALF_UP);

            List<SaleRow> sales = jdbc.query("""
                            SELECT s.folio, s.total, s.status, s.created_at,
                                   (SELECT string_agg(DISTINCT p.method, ',')
                                    FROM sale_payment p
                                    WHERE p.sale_id = s.id AND p.tenant_id = s.tenant_id) AS methods
                            FROM sale s
                            WHERE s.tenant_id = ? AND s.cash_session_id = ?
                            ORDER BY s.created_at DESC, s.folio DESC
                            LIMIT ?
                            """,
                    (rs, i) -> new SaleRow(
                            rs.getLong("folio"), money(rs.getBigDecimal("total")),
                            rs.getString("status"), rs.getTimestamp("created_at").toInstant().toString(),
                            rs.getString("methods")),
                    tenantId, cashSessionId, MAX_Z_SALES);

            Range range = new Range(
                    session.openedAt(), session.closedAt() == null ? null : session.closedAt(),
                    zone.getId());
            BigDecimal netSales = totals.total().subtract(refunds.total());

            return new ZReport(
                    range, "MXN",
                    session.id(), session.status(), session.openedAt(), session.closedAt(),
                    session.openingFloat(),
                    totals.salesCount(), totals.canceledCount(), refunds.count(),
                    money(totals.subtotal()), money(totals.discount()), money(totals.tax()),
                    money(ieps), money(totals.tax().subtract(ieps)), money(withheld),
                    money(totals.total()), money(refunds.total()), money(netSales),
                    money(cashSales), money(cashRefunds),
                    money(expected),
                    session.countedCash(), session.difference(), session.closeNote(),
                    methods, sales);
        });
    }

    // ---- Internals ----

    private record Totals(long salesCount, long canceledCount, BigDecimal subtotal,
                          BigDecimal discount, BigDecimal tax, BigDecimal total) {
    }

    private record Refunds(long count, BigDecimal total, BigDecimal cash) {
    }

    /** Sales ledger totals of a date window (completed vs canceled). */
    private Totals totals(UUID tenantId, Timestamp start, Timestamp end) {
        return jdbc.queryForObject("""
                        SELECT COUNT(*) FILTER (WHERE status = 'COMPLETED') AS sales_count,
                               COUNT(*) FILTER (WHERE status = 'CANCELED')  AS canceled_count,
                               COALESCE(SUM(subtotal) FILTER (WHERE status = 'COMPLETED'), 0)       AS subtotal,
                               COALESCE(SUM(discount_total) FILTER (WHERE status = 'COMPLETED'), 0) AS discount_total,
                               COALESCE(SUM(tax_total) FILTER (WHERE status = 'COMPLETED'), 0)      AS tax_total,
                               COALESCE(SUM(total) FILTER (WHERE status = 'COMPLETED'), 0)          AS total
                        FROM sale
                        WHERE tenant_id = ? AND created_at >= ? AND created_at < ?
                        """,
                (rs, i) -> new Totals(
                        rs.getLong("sales_count"), rs.getLong("canceled_count"),
                        rs.getBigDecimal("subtotal"), rs.getBigDecimal("discount_total"),
                        rs.getBigDecimal("tax_total"), rs.getBigDecimal("total")),
                tenantId, start, end);
    }

    /** Totals of the sales attached to ONE drawer (Z report). */
    private Totals totals(UUID tenantId, UUID cashSessionId) {
        return jdbc.queryForObject("""
                        SELECT COUNT(*) FILTER (WHERE status = 'COMPLETED') AS sales_count,
                               COUNT(*) FILTER (WHERE status = 'CANCELED')  AS canceled_count,
                               COALESCE(SUM(subtotal) FILTER (WHERE status = 'COMPLETED'), 0)       AS subtotal,
                               COALESCE(SUM(discount_total) FILTER (WHERE status = 'COMPLETED'), 0) AS discount_total,
                               COALESCE(SUM(tax_total) FILTER (WHERE status = 'COMPLETED'), 0)      AS tax_total,
                               COALESCE(SUM(total) FILTER (WHERE status = 'COMPLETED'), 0)          AS total
                        FROM sale
                        WHERE tenant_id = ? AND cash_session_id = ?
                        """,
                (rs, i) -> new Totals(
                        rs.getLong("sales_count"), rs.getLong("canceled_count"),
                        rs.getBigDecimal("subtotal"), rs.getBigDecimal("discount_total"),
                        rs.getBigDecimal("tax_total"), rs.getBigDecimal("total")),
                tenantId, cashSessionId);
    }

    private Refunds refunds(UUID tenantId, Timestamp start, Timestamp end) {
        return jdbc.queryForObject("""
                        SELECT COUNT(*) AS count,
                               COALESCE(SUM(refund_amount), 0) AS total,
                               COALESCE(SUM(refund_amount) FILTER (WHERE refund_method = 'CASH'), 0) AS cash
                        FROM sale_return
                        WHERE tenant_id = ? AND created_at >= ? AND created_at < ?
                        """,
                (rs, i) -> new Refunds(
                        rs.getLong("count"), rs.getBigDecimal("total"),
                        rs.getBigDecimal("cash")),
                tenantId, start, end);
    }

    private Refunds refundsOfSession(UUID tenantId, UUID cashSessionId) {
        return jdbc.queryForObject("""
                        SELECT COUNT(*) AS count,
                               COALESCE(SUM(refund_amount), 0) AS total,
                               COALESCE(SUM(refund_amount) FILTER (WHERE refund_method = 'CASH'), 0) AS cash
                        FROM sale_return
                        WHERE tenant_id = ? AND cash_session_id = ?
                        """,
                (rs, i) -> new Refunds(
                        rs.getLong("count"), rs.getBigDecimal("total"),
                        rs.getBigDecimal("cash")),
                tenantId, cashSessionId);
    }

    private List<DayRow> byDay(UUID tenantId, Timestamp start, Timestamp end) {
        return jdbc.query("""
                        WITH sale_days AS (
                            SELECT (s.created_at AT TIME ZONE ?)::date AS day,
                                   COUNT(*) AS count,
                                   SUM(s.subtotal) AS subtotal,
                                   SUM(s.tax_total) AS tax,
                                   SUM(s.total) AS total
                            FROM sale s
                            WHERE s.tenant_id = ? AND s.status = 'COMPLETED'
                              AND s.created_at >= ? AND s.created_at < ?
                            GROUP BY 1
                        ), return_days AS (
                            SELECT (r.created_at AT TIME ZONE ?)::date AS day,
                                   SUM(r.refund_amount) AS refunds
                            FROM sale_return r
                            WHERE r.tenant_id = ? AND r.created_at >= ? AND r.created_at < ?
                            GROUP BY 1
                        )
                        SELECT to_char(COALESCE(sd.day, rd.day), 'YYYY-MM-DD') AS day,
                               COALESCE(sd.count, 0) AS count,
                               COALESCE(sd.subtotal, 0) AS subtotal,
                               COALESCE(sd.tax, 0) AS tax,
                               COALESCE(sd.total, 0) AS total,
                               COALESCE(rd.refunds, 0) AS refunds
                        FROM sale_days sd
                        FULL OUTER JOIN return_days rd ON rd.day = sd.day
                        ORDER BY 1
                        """,
                (rs, i) -> new DayRow(rs.getString("day"), rs.getLong("count"),
                        money(rs.getBigDecimal("subtotal")), money(rs.getBigDecimal("tax")),
                        money(rs.getBigDecimal("total")), money(rs.getBigDecimal("refunds"))),
                zone.getId(), tenantId, start, end, zone.getId(), tenantId, start, end);
    }

    private List<MethodRow> methods(UUID tenantId, Timestamp start, Timestamp end) {
        return jdbc.query("""
                        SELECT p.method, COUNT(*) AS count, COALESCE(SUM(p.amount), 0) AS amount
                        FROM sale_payment p
                        JOIN sale s ON s.id = p.sale_id AND s.tenant_id = p.tenant_id
                        WHERE p.tenant_id = ? AND s.status = 'COMPLETED'
                          AND s.created_at >= ? AND s.created_at < ?
                        GROUP BY p.method
                        ORDER BY p.method
                        """,
                methodRowMapper(), tenantId, start, end);
    }

    private List<ProductRow> topProducts(UUID tenantId, Timestamp start, Timestamp end) {
        return jdbc.query("""
                        SELECT sl.product_name, sl.sku, SUM(sl.quantity) AS quantity,
                               SUM(sl.line_total) AS amount
                        FROM sale_line sl
                        JOIN sale s ON s.id = sl.sale_id AND s.tenant_id = sl.tenant_id
                        WHERE sl.tenant_id = ? AND s.status = 'COMPLETED'
                          AND s.created_at >= ? AND s.created_at < ?
                        GROUP BY sl.product_name, sl.sku
                        ORDER BY amount DESC, sl.sku
                        LIMIT ?
                        """,
                (rs, i) -> new ProductRow(rs.getString("product_name"), rs.getString("sku"),
                        rs.getBigDecimal("quantity").stripTrailingZeros().toPlainString(),
                        money(rs.getBigDecimal("amount"))),
                tenantId, start, end, MAX_TOP_PRODUCTS);
    }

    private BigDecimal sum(String sql, Object... args) {
        BigDecimal value = jdbc.queryForObject(sql, BigDecimal.class, args);
        return value == null ? ZERO : value.setScale(2, RoundingMode.HALF_UP);
    }

    private static RowMapper<MethodRow> methodRowMapper() {
        return (rs, i) -> new MethodRow(rs.getString("method"), rs.getLong("count"),
                money(rs.getBigDecimal("amount")));
    }

    private static RowMapper<TaxRow> taxRowMapper() {
        return (rs, i) -> new TaxRow(rs.getString("code"), rs.getString("scheme"),
                rs.getBigDecimal("rate").stripTrailingZeros().toPlainString(),
                money(rs.getBigDecimal("base")), money(rs.getBigDecimal("amount")));
    }

    private Range resolveRange(LocalDate from, LocalDate to) {
        LocalDate end = to == null ? LocalDate.now(zone) : to;
        LocalDate start = from == null ? end.minusDays(6) : from;
        if (start.isAfter(end)) {
            throw new IllegalArgumentException("El rango de reporte es inválido: 'from' no puede ser posterior a 'to'");
        }
        if (ChronoUnit.DAYS.between(start, end) >= MAX_RANGE_DAYS) {
            throw new IllegalArgumentException("El rango de reporte excede el máximo de %d días"
                    .formatted(MAX_RANGE_DAYS));
        }
        return new Range(start.toString(), end.toString(), zone.getId());
    }

    private Instant startOf(Range range) {
        return LocalDate.parse(range.from()).atStartOfDay(zone).toInstant();
    }

    private Instant endOf(Range range) {
        return LocalDate.parse(range.to()).plusDays(1).atStartOfDay(zone).toInstant();
    }

    private static String money(BigDecimal value) {
        if (value == null) {
            return ZERO.toPlainString();
        }
        return value.setScale(2, RoundingMode.HALF_UP).toPlainString();
    }
}

