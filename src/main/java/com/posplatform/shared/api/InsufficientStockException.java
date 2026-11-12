package com.posplatform.shared.api;

import java.math.BigDecimal;

/**
 * The vertical's negative-stock policy is FORBIDDEN (or the operation is
 * strict) and the requested quantity exceeds availability. HTTP mapping: 409.
 */
public class InsufficientStockException extends ConflictException {

    public InsufficientStockException(String sku, BigDecimal available,
                                      BigDecimal requested) {
        super("INSUFFICIENT_STOCK",
                "Stock insuficiente de " + sku + ": disponible "
                        + available.toPlainString() + ", solicitado "
                        + requested.toPlainString()
                        + " (el giro bloquea stock negativo)");
    }
}
