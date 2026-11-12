package com.posplatform.modules.identity;

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
 * Join entity MemberRole (membership ↔ role).
 *
 * Why not a plain @ManyToMany: roles are tenant-scoped. The table carries
 * (membership_id, role_id, tenant_id) and DB-level COMPOSITE foreign keys
 * require that both references match the same tenant:
 *
 *   FK (membership_id, tenant_id) -> membership (id, tenant_id)
 *   FK (role_id,       tenant_id) -> role       (id, tenant_id)
 *
 * So no membership of organization A can ever get a role of organization B,
 * regardless of application bugs.
 */
@Entity
@Table(name = "membership_role")
public class MembershipRole extends Auditable {

    @Id
    @UuidGenerator(style = Style.TIME)
    private UUID id;

    @Column(nullable = false)
    private UUID tenantId;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "membership_id", nullable = false)
    private Membership membership;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "role_id", nullable = false)
    private Role role;

    protected MembershipRole() {
    }

    public MembershipRole(UUID tenantId, Membership membership, Role role) {
        this.tenantId = tenantId;
        this.membership = membership;
        this.role = role;
    }

    public UUID getId() { return id; }
    public UUID getTenantId() { return tenantId; }
    public Membership getMembership() { return membership; }
    public Role getRole() { return role; }
}
