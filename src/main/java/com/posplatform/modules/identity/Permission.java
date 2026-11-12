package com.posplatform.modules.identity;

import com.posplatform.shared.domain.Auditable;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import org.hibernate.annotations.UuidGenerator;
import org.hibernate.annotations.UuidGenerator.Style;
import java.util.UUID;

/**
 * Platform-owned catalog of authorizable actions.
 * Codes are stable contract constants; seeding happens in migrations.
 */
@Entity
@Table(name = "permission")
public class Permission extends Auditable {

    @Id
    @UuidGenerator(style = Style.TIME)
    private UUID id;

    @Column(nullable = false, unique = true, length = 80)
    private String code;

    @Column(nullable = false, length = 120)
    private String name;

    @Column(length = 255)
    private String description;

    protected Permission() {
    }

    public Permission(String code, String name) {
        this.code = code;
        this.name = name;
    }

    public UUID getId() { return id; }
    public String getCode() { return code; }
    public String getName() { return name; }
}
