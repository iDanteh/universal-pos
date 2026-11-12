package com.posplatform.modules.invoicing;

import com.posplatform.shared.domain.Auditable;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import org.hibernate.annotations.UuidGenerator;
import org.hibernate.annotations.UuidGenerator.Style;

import java.util.UUID;

/**
 * GLOBAL SAT catalog row (ADR-006 rule 4): codes live in MIGRATIONS, never
 * hardcoded. NOT tenant-scoped — reference data, SELECT-only for the app role.
 */
@Entity
@Table(name = "sat_catalog")
public class SatCatalog extends Auditable {

    public enum Catalog {
        REGIMEN, USO_CFDI, FORMA_PAGO, METODO_PAGO,
        MOTIVO_CANCELACION, UNIDAD, PRODUCTO_SERVICIO
    }

    @Id
    @UuidGenerator(style = Style.TIME)
    private UUID id;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, columnDefinition = "text")
    private Catalog catalog;

    @Column(nullable = false, length = 16)
    private String code;

    @Column(nullable = false, columnDefinition = "text")
    private String description;

    @Column(nullable = false)
    private boolean active = true;

    protected SatCatalog() {
    }

    public SatCatalog(Catalog catalog, String code, String description) {
        this.catalog = catalog;
        this.code = code;
        this.description = description;
    }

    public UUID getId() { return id; }
    public Catalog getCatalog() { return catalog; }
    public String getCode() { return code; }
    public String getDescription() { return description; }
    public boolean isActive() { return active; }
}
