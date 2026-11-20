package com.posplatform.modules.invoicing.fiscal;

import com.posplatform.shared.api.ConflictException;
import com.posplatform.modules.invoicing.fiscal.FiscalProvider.StampLine;
import com.posplatform.modules.invoicing.fiscal.FiscalProvider.StampRequest;
import com.posplatform.modules.invoicing.fiscal.FiscalProvider.StampWithholding;

import java.math.BigDecimal;
import java.math.RoundingMode;

/**
 * Document SHAPE guard shared by every {@link FiscalProvider} (v0.12 ·
 * ADR-022). The business layer already validates its commands; this guard
 * protects the LAST line before a provider call and makes the Mock and the
 * real PAC reject the same broken documents with the same codes.
 *
 * <p>It never recomputes money: every amount is the sale SNAPSHOT; the guard
 * only checks coherence (subtotal - discount + taxes - retenciones = total),
 * exactly what a PAC would validate before sending the CFDI to the SAT.</p>
 */
final class FiscalDocumentValidator {

    private FiscalDocumentValidator() {
    }

    static void validate(StampRequest request) {
        validateDocumentType(request);
        validateReceptor(request);
        if ("P".equals(request.documentType())) {
            validatePayment(request);
        } else {
            validateTotals(request);
        }
    }

    /** TipoDeComprobante is mandatory; an egreso (E) REQUIRES its related CFDI. */
    private static void validateDocumentType(StampRequest request) {
        if (request.documentType() == null || request.documentType().isBlank()) {
            throw new ConflictException("FISCAL_INVALID_DOCUMENT",
                    "El comprobante requiere TipoDeComprobante (I, E o P)");
        }
        if (!("I".equals(request.documentType()) || "E".equals(request.documentType())
                || "P".equals(request.documentType()))) {
            throw new ConflictException("FISCAL_INVALID_DOCUMENT",
                    "TipoDeComprobante no soportado: " + request.documentType()
                            + " (soportados: I, E, P)");
        }
        if ("E".equals(request.documentType()) && request.relatedFiscalId() == null) {
            throw new ConflictException("FISCAL_INVALID_DOCUMENT",
                    "Una nota de crédito (E) requiere el folio fiscal de la "
                            + "factura relacionada (CfdiRelacionados 01)");
        }
    }

    private static void validateReceptor(StampRequest request) {
        if (request.receptorRfc() == null || request.receptorRfc().isBlank()
                || request.receptorName() == null || request.receptorName().isBlank()) {
            throw new ConflictException("FISCAL_INVALID_DOCUMENT",
                    "El CFDI requiere RFC y nombre del receptor");
        }
    }

    /** CFDI tipo P (complemento de pago): un concepto, SubTotal = Monto = Total. */
    private static void validatePayment(StampRequest request) {
        if (request.lines() == null || request.lines().size() != 1) {
            throw new ConflictException("FISCAL_INVALID_DOCUMENT",
                    "El complemento de pago lleva UN solo concepto");
        }
        if (request.payment() == null) {
            throw new ConflictException("FISCAL_INVALID_DOCUMENT",
                    "El complemento de pago requiere la forma de pago, la fecha y "
                            + "el saldo del documento relacionado (complemento 2.0)");
        }
        BigDecimal monto = request.subtotal() == null
                ? BigDecimal.ZERO : request.subtotal();
        if (monto.signum() <= 0) {
            throw new ConflictException("FISCAL_INVALID_DOCUMENT",
                    "El monto del complemento de pago debe ser positivo");
        }
        if (request.taxTotal() == null || request.taxTotal().signum() != 0
                || request.discountTotal() == null
                || request.discountTotal().signum() != 0
                || (request.withholdings() != null && !request.withholdings().isEmpty())) {
            throw new ConflictException("FISCAL_INVALID_DOCUMENT",
                    "El CFDI tipo P no lleva impuestos, descuentos ni retenciones");
        }
        if (monto.compareTo(request.total() == null ? BigDecimal.ZERO : request.total()) != 0) {
            throw new ConflictException("FISCAL_INVALID_DOCUMENT",
                    "En el tipo P el Total ES el monto pagado");
        }
        if (request.payment().amount() == null
                || request.payment().amount().compareTo(monto) != 0) {
            throw new ConflictException("FISCAL_INVALID_DOCUMENT",
                    "El Monto del complemento no coincide con el importe pagado");
        }
    }

    private static void validateTotals(StampRequest request) {
        // CFDI 4.0 semantics: concepto Importe = cantidad x valorUnitario (gross);
        // Descuento is separate, and SubTotal - Descuento + Traslados
        // - Retenciones = Total.
        BigDecimal gross = BigDecimal.ZERO;
        BigDecimal discounts = BigDecimal.ZERO;
        for (StampLine line : request.lines()) {
            if (line.quantity() == null || line.quantity().signum() <= 0
                    || line.unitSat() == null || line.unitSat().isBlank()) {
                throw new ConflictException("FISCAL_INVALID_DOCUMENT",
                        "Cada concepto requiere cantidad positiva y clave de unidad");
            }
            BigDecimal importe = line.quantity().multiply(line.unitPrice())
                    .setScale(2, RoundingMode.HALF_UP);
            BigDecimal net = line.lineTotal() == null ? BigDecimal.ZERO : line.lineTotal();
            if (importe.subtract(line.discount() == null ? BigDecimal.ZERO : line.discount())
                    .compareTo(net) != 0) {
                throw new ConflictException("FISCAL_INVALID_DOCUMENT",
                        "El importe neto del concepto '" + line.description()
                                + "' no es coherente (cantidad x valor unitario - descuento)");
            }
            gross = gross.add(importe);
            discounts = discounts.add(line.discount() == null ? BigDecimal.ZERO : line.discount());
        }
        BigDecimal subtotal = request.subtotal() == null ? BigDecimal.ZERO : request.subtotal();
        BigDecimal discountTotal = request.discountTotal() == null
                ? BigDecimal.ZERO : request.discountTotal();
        BigDecimal total = request.total() == null ? BigDecimal.ZERO : request.total();
        BigDecimal taxes = request.taxTotal() == null ? BigDecimal.ZERO : request.taxTotal();
        if (subtotal.signum() < 0 || total.signum() < 0) {
            throw new ConflictException("FISCAL_INVALID_DOCUMENT",
                    "Los totales del CFDI no pueden ser negativos");
        }
        // ADR-018 + ADR-021 (v0.11): per-line SNAPSHOT taxes must be present
        // and coherent. IVA base is the IEPS-INCLUSIVE net; the IEPS slot is
        // computed on the plain net. The Sandbox tolerance absorbs the
        // rounding drift of GLOBAL invoices one cent at a time (a folded
        // concept sums already-rounded sale-line taxes and is NOT re-rounded)
        // — a real PAC applies the provider's own validation behind the port.
        BigDecimal tolerance = BigDecimal.valueOf(0.01)
                .multiply(BigDecimal.valueOf(Math.max(1, request.lines().size())));
        BigDecimal lineTaxes = BigDecimal.ZERO;
        for (StampLine line : request.lines()) {
            BigDecimal net = line.lineTotal() == null ? BigDecimal.ZERO : line.lineTotal();
            BigDecimal ieps = line.iepsTotal() == null
                    ? BigDecimal.ZERO : line.iepsTotal();
            // IVA slot (scheme from the catalog decides which traslado it is —
            // a legacy line whose single code was IEPS still validates here).
            boolean ivaSlotTaxed = line.taxRateCode() != null && !line.taxRateCode().isBlank()
                    && line.taxRate() != null && line.taxRate().signum() > 0
                    && "TASA".equals(line.taxFactor());
            BigDecimal iva = line.taxTotal() == null ? BigDecimal.ZERO : line.taxTotal();
            if (ivaSlotTaxed) {
                if (iva.signum() < 0) {
                    throw new ConflictException("FISCAL_INVALID_DOCUMENT",
                            "El impuesto del concepto '" + line.description()
                                    + "' no puede ser negativo");
                }
                BigDecimal base = "IEPS".equals(line.taxScheme())
                        ? net : net.add(ieps);
                BigDecimal expected = base.multiply(line.taxRate())
                        .setScale(2, RoundingMode.HALF_UP);
                if (iva.subtract(expected).abs().compareTo(tolerance) > 0) {
                    throw new ConflictException("FISCAL_INVALID_DOCUMENT",
                            "El impuesto del concepto '" + line.description()
                                    + "' (" + iva.toPlainString()
                                    + ") no es coherente con su base y tasa ("
                                    + expected.toPlainString() + ")");
                }
            } else if (iva.signum() != 0) {
                throw new ConflictException("FISCAL_INVALID_DOCUMENT",
                        "El concepto '" + line.description()
                                + "' trae impuesto sin tasa activa");
            }
            // IEPS slot (v0.11 · ADR-021).
            boolean iepsSlotTaxed = line.iepsRateCode() != null
                    && !line.iepsRateCode().isBlank()
                    && line.iepsRate() != null && line.iepsRate().signum() > 0
                    && "TASA".equals(line.iepsFactor());
            if (iepsSlotTaxed) {
                BigDecimal expectedIeps = net.multiply(line.iepsRate())
                        .setScale(2, RoundingMode.HALF_UP);
                if (ieps.subtract(expectedIeps).abs().compareTo(tolerance) > 0) {
                    throw new ConflictException("FISCAL_INVALID_DOCUMENT",
                            "El IEPS del concepto '" + line.description()
                                    + "' (" + ieps.toPlainString()
                                    + ") no es coherente con su base y tasa ("
                                    + expectedIeps.toPlainString() + ")");
                }
            } else if (ieps.signum() != 0) {
                throw new ConflictException("FISCAL_INVALID_DOCUMENT",
                        "El concepto '" + line.description()
                                + "' trae IEPS sin tasa activa");
            }
            lineTaxes = lineTaxes.add(iva).add(ieps);
        }
        if (lineTaxes.compareTo(taxes) != 0) {
            throw new ConflictException("FISCAL_INVALID_DOCUMENT",
                    "La suma de impuestos de concepto (" + lineTaxes.toPlainString()
                            + ") no coincide con los impuestos del documento");
        }
        // Retenciones (v0.11 · ADR-021): document-level snapshot validation.
        BigDecimal withholdingTotal = BigDecimal.ZERO;
        if (request.withholdings() != null) {
            BigDecimal net = subtotal.subtract(discountTotal).setScale(2, RoundingMode.HALF_UP);
            for (StampWithholding wh : request.withholdings()) {
                if (wh.amount() == null || wh.amount().signum() <= 0
                        || wh.rate() == null || wh.rate().signum() <= 0
                        || !"TASA".equals(wh.factor())) {
                    throw new ConflictException("FISCAL_INVALID_DOCUMENT",
                            "La retención " + wh.code() + " no es coherente");
                }
                BigDecimal expected = net.multiply(wh.rate())
                        .setScale(2, RoundingMode.HALF_UP);
                if (wh.amount().subtract(expected).abs().compareTo(tolerance) > 0) {
                    throw new ConflictException("FISCAL_INVALID_DOCUMENT",
                            "La retención " + wh.code() + " ("
                                    + wh.amount().toPlainString()
                                    + ") no es coherente con la base neta del documento ("
                                    + expected.toPlainString() + ")");
                }
                if ("IVA".equals(wh.scheme()) && taxes.signum() <= 0) {
                    throw new ConflictException("FISCAL_INVALID_DOCUMENT",
                            "La retención de " + wh.code() + " exige IVA trasladado");
                }
                withholdingTotal = withholdingTotal.add(wh.amount());
            }
        }
        if (gross.compareTo(subtotal) != 0) {
            throw new ConflictException("FISCAL_INVALID_DOCUMENT",
                    "La suma de conceptos brutos (" + gross.toPlainString()
                            + ") no coincide con el subtotal del documento");
        }
        if (discounts.compareTo(discountTotal) != 0) {
            throw new ConflictException("FISCAL_INVALID_DOCUMENT",
                    "La suma de descuentos de concepto (" + discounts.toPlainString()
                            + ") no coincide con el descuento del documento");
        }
        if (subtotal.subtract(discountTotal).add(taxes).subtract(withholdingTotal)
                .compareTo(total) != 0) {
            throw new ConflictException("FISCAL_INVALID_DOCUMENT",
                    "El total del documento no es coherente (subtotal - descuento "
                            + "+ impuestos - retenciones)");
        }
    }
}
