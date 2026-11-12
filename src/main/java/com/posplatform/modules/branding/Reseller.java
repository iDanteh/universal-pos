package com.posplatform.modules.branding;

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

import java.util.UUID;

/**
 * Distributor that administers organizations under a brand.
 * PLATFORM-scoped. Organization commercial relationships are expressed by
 * the tenant-scoped OrganizationBrand, not by a reseller field on the org.
 */
@Entity
@Table(name = "reseller")
public class Reseller extends Auditable {

    public enum Status { ACTIVE, ARCHIVED }

    @Id
    @UuidGenerator(style = Style.TIME)
    private UUID id;

    @Column(nullable = false, unique = true, length = 120)
    private String name;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "brand_id")
    private Brand brand;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, columnDefinition = "text")
    private Status status = Status.ACTIVE;

    protected Reseller() {
    }

    public Reseller(String name, Brand brand) {
        this.name = name;
        this.brand = brand;
        this.status = Status.ACTIVE;
    }

    public UUID getId() { return id; }
    public String getName() { return name; }
    public Brand getBrand() { return brand; }
    public Status getStatus() { return status; }
}
