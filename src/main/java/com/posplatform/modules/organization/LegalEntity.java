package com.posplatform.modules.organization;

import com.posplatform.shared.domain.Auditable;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import org.hibernate.annotations.UuidGenerator;
import org.hibernate.annotations.UuidGenerator.Style;

import java.util.UUID;

/**
 * Razón social. Belongs to exactly one organization (tenant).
 * DB CHECK tenant_id = organization_id.
 */
@Entity
@Table(name = "legal_entity")
public class LegalEntity extends Auditable {

    @Id
    @UuidGenerator(style = Style.TIME)
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "organization_id", nullable = false)
    private Organization organization;

    @Column(nullable = false)
    private UUID tenantId;

    @Column(name = "legal_name", nullable = false, length = 180)
    private String legalName;

    @Column(name = "trade_name", length = 180)
    private String tradeName;

    /** Placeholder for RFC/tax id; validation rules live in shared MexicanTaxId. */
    @Column(name = "tax_id", length = 32)
    private String taxId;

    /**
     * EMISOR fiscal face (V13 · ADR-006 rule 1): SAT fiscal regime code and
     * código postal (LugarExpedicion). An entity without them cannot issue
     * CFDI yet — the invoicing module reads/guards these, the profile sets them.
     */
    @Column(name = "fiscal_regime", columnDefinition = "text")
    private String fiscalRegime;

    @Column(name = "zip_code", length = 5)
    private String zipCode;

    protected LegalEntity() {
    }

    public LegalEntity(Organization organization, String legalName,
                       String tradeName, String taxId) {
        this.organization = organization;
        this.tenantId = organization.getId();
        this.legalName = legalName;
        this.tradeName = tradeName;
        this.taxId = taxId;
    }

    public UUID getId() { return id; }
    public Organization getOrganization() { return organization; }
    public UUID getTenantId() { return tenantId; }
    public String getLegalName() { return legalName; }
    public String getTradeName() { return tradeName; }
    public String getTaxId() { return taxId; }
    public String getFiscalRegime() { return fiscalRegime; }
    public String getZipCode() { return zipCode; }

    /** Emisor fiscal completion (invoicing module owns this use case). */
    public void completeFiscalFace(String fiscalRegime, String zipCode) {
        this.fiscalRegime = fiscalRegime;
        this.zipCode = zipCode;
    }

    /** RFC set-once rule (invoicing module; uniqueness guarded in service). */
    public void assignTaxId(String taxId) {
        this.taxId = taxId;
    }
}
