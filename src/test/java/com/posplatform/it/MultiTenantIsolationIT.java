package com.posplatform.it;

import com.posplatform.modules.identity.Membership;
import com.posplatform.modules.identity.infrastructure.MembershipRepository;
import com.posplatform.modules.organization.application.OnboardOrganizationCommand;
import com.posplatform.modules.organization.application.OnboardOrganizationResult;
import com.posplatform.modules.organization.application.OnboardingService;
import com.posplatform.modules.subscription.infrastructure.SubscriptionRepository;
import com.posplatform.shared.domain.UuidV7;
import com.posplatform.shared.tenancy.TenantTransactionRunner;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * THE core acceptance test for Foundation: multi-tenant isolation.
 *
 * These tests assert, running as the RESTRICTED application user (no
 * BYPASSRLS, not table owner):
 *   1. RLS policies are installed on every tenant-scoped table.
 *   2. A tenant CANNOT read rows of another tenant (even knowing their ids).
 *   3. A tenant CANNOT write rows attributed to another tenant (RLS violation).
 *   4. Cross-tenant referential traps are rejected by composite foreign keys.
 *   5. Data integrity rules hold (duplicate membership rejected).
 */
class MultiTenantIsolationIT extends AbstractDatabaseIT {

    @Autowired
    private OnboardingService onboardingService;
    @Autowired
    private TenantTransactionRunner tenantRunner;
    @Autowired
    private MembershipRepository membershipRepository;
    @Autowired
    private SubscriptionRepository subscriptionRepository;
    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Test
    void rlsPoliciesAreInstalledOnEveryTenantScopedTable() {
        var tablesWithPolicy = jdbcTemplate.query(
                "SELECT tablename FROM pg_policies WHERE policyname = 'tenant_isolation'",
                (rs, i) -> rs.getString(1));

        assertThat(tablesWithPolicy).containsExactlyInAnyOrder(
                "organization", "legal_entity", "branch", "role", "membership",
                "membership_role", "subscription", "organization_brand", "idempotency_key",
                "product", "product_variant", "sale_folio", "sale", "sale_line", "sale_payment",
                "warehouse", "inventory_movement", "inventory_lot", "inventory_balance",
                "invoice_folio", "invoice", "invoice_line", "cash_session",
                "return_folio", "sale_return", "sale_return_line");
    }

    @Test
    void tenantCannotReadAnotherTenantData() {
        OnboardOrganizationResult tenantA = onboardingService.onboard(
                commandFor("isoA"));
        OnboardOrganizationResult tenantB = onboardingService.onboard(
                commandFor("isoB"));

        // Tenant A cannot resolve B's rows even with concrete ids:
        assertThat(tenantRunner.run(tenantA.organizationId(), () ->
                membershipRepository.findById(tenantB.membershipId()))).isEmpty();

        assertThat(tenantRunner.run(tenantA.organizationId(), () ->
                membershipRepository.findByOrganizationId(tenantB.organizationId()))).isEmpty();

        assertThat(tenantRunner.run(tenantA.organizationId(), () ->
                subscriptionRepository.findByOrganizationId(tenantB.organizationId()))).isEmpty();

        // List queries leak nothing: ONLY the own row comes back.
        assertThat(tenantRunner.run(tenantA.organizationId(), () ->
                membershipRepository.findAll()))
                .extracting(Membership::getId)
                .containsExactly(tenantA.membershipId());

        // Tenant B still sees its own data (positive control).
        assertThat(tenantRunner.run(tenantB.organizationId(), () ->
                membershipRepository.findById(tenantB.membershipId()))).isPresent();
    }

    @Test
    void tenantCannotWriteRowsAttributedToAnotherTenant() {
        OnboardOrganizationResult tenantA = onboardingService.onboard(commandFor("wrA"));
        OnboardOrganizationResult tenantB = onboardingService.onboard(commandFor("wrB"));

        // Forged insert: organization of tenant B while bound to tenant A.
        // Spring wraps the driver error (DataAccessException); the RLS rejection
        // is in the ROOT CAUSE, so we assert against it.
        assertThatThrownBy(() ->
                tenantRunner.run(tenantA.organizationId(), () ->
                        jdbcTemplate.update("""
                                INSERT INTO role (id, organization_id, tenant_id, name, created_at, updated_at)
                                VALUES (?, ?, ?, 'forged-role', now(), now())
                                """,
                                UuidV7.next(), tenantB.organizationId(), tenantB.organizationId())))
                .isInstanceOf(Exception.class)
                .getRootCause()
                .hasMessageContaining("row-level security");
    }

    @Test
    void crossTenantForeignKeysAreForbidden() {
        OnboardOrganizationResult tenantA = onboardingService.onboard(commandFor("fkA"));
        OnboardOrganizationResult tenantB = onboardingService.onboard(commandFor("fkB"));

        // A branch of tenant A pointing to the legal entity of tenant B.
        assertThatThrownBy(() ->
                tenantRunner.run(tenantA.organizationId(), () ->
                        jdbcTemplate.update("""
                                INSERT INTO branch (id, tenant_id, legal_entity_id, name, created_at, updated_at)
                                VALUES (?, ?, ?, 'forged-branch', now(), now())
                                """,
                                UuidV7.next(), tenantA.organizationId(), tenantB.legalEntityId())))
                .isInstanceOf(Exception.class);
    }

    @Test
    void duplicateMembershipIsRejectedByUniqueConstraint() {
        OnboardOrganizationResult tenant = onboardingService.onboard(commandFor("dup"));

        assertThatThrownBy(() ->
                tenantRunner.run(tenant.organizationId(), () ->
                        jdbcTemplate.update("""
                                INSERT INTO membership (id, organization_id, tenant_id, user_id, status, created_at, updated_at)
                                VALUES (gen_random_uuid(), ?, ?, ?, 'ACTIVE', now(), now())
                                """,
                                tenant.organizationId(), tenant.organizationId(), tenant.ownerId())))
                .isInstanceOf(Exception.class);
    }

    static OnboardOrganizationCommand commandFor(String suffix) {
        return OnboardingGraphIT.commandFor(suffix);
    }
}
