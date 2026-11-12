package com.posplatform.modules.branding;

import com.posplatform.shared.domain.Auditable;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.annotations.UuidGenerator;
import org.hibernate.annotations.UuidGenerator.Style;

import java.sql.Types;
import java.util.Map;
import java.util.UUID;

/**
 * Commercial identity used to distribute the product (White Label, ADR-007).
 * PLATFORM-scoped: a brand is owned by the platform or a reseller, and is
 * related to organizations through OrganizationBrand (tenant-scoped relation).
 *
 * config is declarative (no code branches per brand).
 */
@Entity
@Table(name = "brand")
public class Brand extends Auditable {

    public enum Status { ACTIVE, ARCHIVED }

    @Id
    @UuidGenerator(style = Style.TIME)
    private UUID id;

    @Column(nullable = false, unique = true, length = 120)
    private String name;

    @JdbcTypeCode(org.hibernate.type.SqlTypes.JSON)
    @Column(columnDefinition = "jsonb")
    private Map<String, Object> config;    // logo, colors, domain, templates...

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, columnDefinition = "text")
    private Status status = Status.ACTIVE;

    protected Brand() {
    }

    public Brand(String name, Map<String, Object> config) {
        this.name = name;
        this.config = config;
        this.status = Status.ACTIVE;
    }

    public UUID getId() { return id; }
    public String getName() { return name; }
    public Map<String, Object> getConfig() { return config; }
    public Status getStatus() { return status; }
}
