package com.posplatform.modules.sales;

import com.posplatform.shared.domain.Auditable;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import org.hibernate.annotations.UuidGenerator;
import org.hibernate.annotations.UuidGenerator.Style;

import java.math.BigDecimal;
import java.util.UUID;

/**
 * Payment record of a sale. The SUM of a sale's payments MUST equal its
 * total (service rule + proven in SalesApiIT; cash change modeling arrives
 * with the POS drawer). Composite FK same-tenant as the rest of V11.
 */
@Entity
@Table(name = "sale_payment")
public class SalePayment extends Auditable {

    public enum Method { CASH, CARD, TRANSFER, OTHER }

    @Id
    @UuidGenerator(style = Style.TIME)
    private UUID id;

    @Column(nullable = false)
    private UUID tenantId;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "sale_id", nullable = false)
    private Sale sale;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, columnDefinition = "text")
    private Method method;

    @Column(nullable = false, precision = 14, scale = 2)
    private BigDecimal amount;

    @Column(length = 80)
    private String reference;

    protected SalePayment() {
    }

    public SalePayment(UUID tenantId, Sale sale, Method method,
                       BigDecimal amount, String reference) {
        this.tenantId = tenantId;
        this.sale = sale;
        this.method = method;
        this.amount = amount;
        this.reference = reference;
    }

    public UUID getId() { return id; }
    public UUID getTenantId() { return tenantId; }
    public Sale getSale() { return sale; }
    public Method getMethod() { return method; }
    public BigDecimal getAmount() { return amount; }
    public String getReference() { return reference; }
}
