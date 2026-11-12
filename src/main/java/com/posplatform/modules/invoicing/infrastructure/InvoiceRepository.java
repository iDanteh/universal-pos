package com.posplatform.modules.invoicing.infrastructure;

import com.posplatform.modules.invoicing.Invoice;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface InvoiceRepository extends JpaRepository<Invoice, UUID> {

    /** Recent documents first (V13 idx_invoice_tenant_created). */
    List<Invoice> findTop50ByTenantIdOrderByCreatedAtDesc(UUID tenantId);

    Optional<Invoice> findByTenantIdAndId(UUID tenantId, UUID id);

    /** Active invoice of a sale (the partial unique index backs this rule). */
    Optional<Invoice> findByTenantIdAndSaleIdAndStatusIn(UUID tenantId, UUID saleId,
                                                         List<Invoice.Status> statuses);

    /** Active GLOBAL invoice of a period (V13 partial unique index). */
    Optional<Invoice> findByTenantIdAndKindAndPeriodYearAndPeriodMonthAndStatusIn(
            UUID tenantId, Invoice.Kind kind, int year, int month, List<Invoice.Status> statuses);

    Optional<Invoice> findByTenantIdAndSerieAndFolio(UUID tenantId, String serie, long folio);

    /** Active CREDIT_NOTE of a return (V16 uq_invoice_return_active backs this). */
    Optional<Invoice> findByTenantIdAndReturnIdAndStatusIn(UUID tenantId, UUID returnId,
                                                           List<Invoice.Status> statuses);
}
