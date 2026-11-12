package com.posplatform.modules.subscription;

import com.posplatform.shared.domain.Auditable;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.annotations.UuidGenerator;
import org.hibernate.annotations.UuidGenerator.Style;
import org.hibernate.type.SqlTypes;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

/**
 * PLATFORM-scoped commercial plan.
 * price uses NUMERIC semantics via BigDecimal (never float/double).
 */
@Entity
@Table(name = "plan")
public class Plan extends Auditable {

    @Id
    @UuidGenerator(style = Style.TIME)
    private UUID id;

    @Column(nullable = false, unique = true, length = 40)
    private String code;

    @Column(nullable = false, length = 120)
    private String name;

    @Column(length = 255)
    private String description;

    @Column(name = "monthly_price", precision = 12, scale = 2)
    private BigDecimal monthlyPrice;       // null = priceless/custom pricing

    /** ISO 4217 currency code; DB column is char(3) - keep JDBC types aligned. */
    @JdbcTypeCode(SqlTypes.CHAR)
    @Column(length = 3)
    private String currency = "MXN";

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(columnDefinition = "jsonb")
    private List<String> features;

    @Column(nullable = false, columnDefinition = "boolean")
    private boolean active = true;

    protected Plan() {
    }

    public Plan(String code, String name, String description,
                BigDecimal monthlyPrice, String currency, List<String> features) {
        this.code = code;
        this.name = name;
        this.description = description;
        this.monthlyPrice = monthlyPrice;
        this.currency = currency;
        this.features = features;
        this.active = true;
    }

    public UUID getId() { return id; }
    public String getCode() { return code; }
    public String getName() { return name; }
    public String getDescription() { return description; }
    public BigDecimal getMonthlyPrice() { return monthlyPrice; }
    public String getCurrency() { return currency; }
    public List<String> getFeatures() { return features; }
    public boolean isActive() { return active; }
}
