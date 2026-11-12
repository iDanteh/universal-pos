package com.posplatform.modules.identity;

import com.posplatform.shared.domain.Auditable;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import org.hibernate.annotations.UuidGenerator;
import org.hibernate.annotations.UuidGenerator.Style;
import java.util.UUID;

/**
 * Global user identity (PLATFORM-scoped, not tenant-scoped):
 * a person exists once and belongs to N organizations through Memberships.
 *
 * passwordHash is nullable by DESIGN: onboarding provisions users WITHOUT
 * a password (they arrive via invitation), and the define-password flow
 * (v0.2+) populates it. Authentication itself is live since v0.2 (ADR-011):
 * BCrypt hashes, generic errors, no user enumeration.
 */
@Entity
@Table(name = "app_user")
public class AppUser extends Auditable {

    public enum Status { ACTIVE, BLOCKED }

    @Id
    @UuidGenerator(style = Style.TIME) // UUID v7
    private UUID id;

    @Column(nullable = false, unique = true, length = 320)
    private String email;

    @Column(name = "display_name", nullable = false, length = 120)
    private String displayName;

    @Column(name = "password_hash", length = 255)
    private String passwordHash;           // null until authentication ships

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, columnDefinition = "text")
    private Status status = Status.ACTIVE;

    protected AppUser() {
    }

    public AppUser(String email, String displayName) {
        this.email = email;
        this.displayName = displayName;
        this.status = Status.ACTIVE;
    }

    public UUID getId() { return id; }
    public String getEmail() { return email; }
    public String getDisplayName() { return displayName; }
    public String getPasswordHash() { return passwordHash; }
    public Status getStatus() { return status; }

    public void setDisplayName(String displayName) { this.displayName = displayName; }

    /** Identity-module internal; called at provisioning and password reset. */
    public void setPasswordHash(String passwordHash) { this.passwordHash = passwordHash; }
}
