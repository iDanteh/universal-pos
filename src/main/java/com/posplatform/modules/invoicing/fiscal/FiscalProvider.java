package com.posplatform.modules.invoicing.fiscal;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * PORT to the PAC (ADR-006): stamping/cancellation of CFDI documents.
 * The invoicing module owns the LIFECYCLE MACHINE (states, motives, related
 * docs); providers only transport documents to/from SAT.
 *
 * Implementations:
 *  - MockFiscalProvider (v0.6): deterministic sandbox — validates the shape
 *    and assigns a folio fiscal instantly; NO signature, NO SAT calls.
 *  - A real PAC adapter lands as a new @ConditionalOnProperty bean: the port
 *    interface and the lifecycle policy stay untouched.
 *
 * ADR-006 rule 3: stamping is EVENTUAL and does NOT block the sale. In the
 * Mock world the "eventual" arrival is immediate, but it still goes through
 * the port so a real PAC needs zero business-code changes (only a worker).
 */
public interface FiscalProvider {

    String name();

    /**
     * Stamps a fully-populated document request. Implementations THROW
     * BusinessException-extended exceptions on provider-side rejection;
     * the invoicing service maps them to the invoice ERROR state.
     */
    StampResult stamp(StampRequest request);

    /** Provider-side cancellation acceptance (SAT acknowledgment). */
    void cancel(UUID fiscalId, String motive);

    /** Immutable input for the provider — a domain snapshot, never crowdstate. */
    record StampRequest(
            String serie,
            long folio,
            /** CFDI type: "I" ingreso (FA), "E" egreso (NC) or "P" pago (fase 11). */
            String documentType,
            String emisorRfc,
            String emisorName,
            String emisorRegime,
            String emisorZip,
            String receptorRfc,
            String receptorName,
            String receptorRegime,
            String receptorUse,
            String receptorZip,
            List<StampLine> lines,
            BigDecimal subtotal,
            BigDecimal discountTotal,
            BigDecimal taxTotal,
            BigDecimal total,
            /** For "E": fiscal UUID of the related income CFDI (TipoRelacion 01). */
            UUID relatedFiscalId,
            /** Federal retenciones SNAPSHOT (v0.11 · ADR-021, document level). */
            List<StampWithholding> withholdings,
            /**
             * SAT FormaPago of an I/E document (v0.12 · ADR-022): "01"
             * efectivo, "03" transferencia, "04" tarjeta, "99" por definir.
             * Ignored for "P" (the forma lives in {@link PaymentInfo}).
             */
            String paymentForm,
            /** Complemento de pago 2.0 data; REQUIRED for "P", null otherwise. */
            PaymentInfo payment,
            /** InformacionGlobal (RFC XAXX010101000); null for non-global CFDI. */
            GlobalInfo global
    ) {
    }

    /**
     * Complemento de pago 2.0 snapshot (v0.12 · ADR-022): everything the
     * {@code pago20:Pago}/{@code DoctoRelacionado} needs. The balance figures
     * come from the SAME invoice ledger that enforces PAG_OVER_BALANCE, so a
     * stamped complement can never disagree with our own receivable.
     */
    record PaymentInfo(
            /** SAT forma de pago of the payment (c_FormaPago, e.g. "03"). */
            String formaPago,
            /** Monto pagado (equals the P document Total). */
            BigDecimal amount,
            /** When the payment happened (FechaPago). */
            Instant date,
            /** ImpSaldoAnt: outstanding BEFORE this payment. */
            BigDecimal outstandingBefore,
            /** ImpSaldoInsoluto: outstanding AFTER this payment. */
            BigDecimal outstandingAfter,
            /** NumParcialidad: 1-based payment number for the related CFDI. */
            int partiality,
            /** ObjetoImpDR: the related CFDI carried traslados ("02") or not ("01"). */
            boolean relatedTaxed
    ) {
    }

    /** CFDI 4.0 InformacionGlobal for the monthly public-in-general invoice. */
    record GlobalInfo(int year, int month) {
    }

    /**
     * One line's transferred taxes (v0.11 · ADR-021 extends ADR-018): the
     * invoicing service snapshots code/scheme/factor/rate per line and each
     * amount EXACTLY as charged in the sale — providers validate but never
     * recompute. IVA amount = taxTotal (whose Base is the IEPS-inclusive
     * net when iepsTotal > 0); iepsTotal is the second traslado. Null codes
     * = sin impuesto de ese esquema.
     */
    record StampLine(String description, String sku, String unitSat,
                     BigDecimal quantity, BigDecimal unitPrice,
                     BigDecimal discount, BigDecimal lineTotal,
                     String taxRateCode, String taxScheme, String taxFactor,
                     BigDecimal taxRate, BigDecimal taxTotal,
                     String iepsRateCode, String iepsScheme, String iepsFactor,
                     BigDecimal iepsRate, BigDecimal iepsTotal) {
    }

    /** One document-level federal retention snapshot (v0.11 · ADR-021). */
    record StampWithholding(String code, String scheme, String factor,
                            BigDecimal rate, BigDecimal amount) {
    }

    /** Immutable output: folio fiscal + provider metadata (the XML preview). */
    record StampResult(UUID fiscalId, Instant stampedAt, String detailJson) {
    }
}
