package com.posplatform.it;

import com.posplatform.modules.identity.Membership;
import com.posplatform.modules.identity.infrastructure.MembershipRepository;
import com.posplatform.modules.identity.infrastructure.MembershipRoleRepository;
import com.posplatform.modules.organization.application.OnboardOrganizationCommand;
import com.posplatform.modules.organization.application.OnboardOrganizationResult;
import com.posplatform.modules.organization.application.OnboardingService;
import com.posplatform.modules.subscription.Subscription;
import com.posplatform.modules.subscription.infrastructure.SubscriptionRepository;
import com.posplatform.shared.outbox.OutboxEventRepository;
import com.posplatform.shared.tenancy.TenantTransactionRunner;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Onboarding graph integrity: one command persists the full tenant backbone
 * in a single transaction and emits a Transactional Outbox event.
 *
 * Verification happens INSIDE the tenant's transaction scope because every
 * tenant-scoped table is protected by RLS (reads without a bound tenant return
 * empty by design - that behavior is proven in MultiTenantIsolationIT).
 */
class OnboardingGraphIT extends AbstractDatabaseIT {

    @Autowired
    private OnboardingService onboardingService;
    @Autowired
    private MembershipRepository membershipRepository;
    @Autowired
    private MembershipRoleRepository membershipRoleRepository;
    @Autowired
    private SubscriptionRepository subscriptionRepository;
    @Autowired
    private OutboxEventRepository outboxEventRepository;
    @Autowired
    private TenantTransactionRunner tenantRunner;

    static OnboardOrganizationCommand commandFor(String suffix) {
        var tag = UUID.randomUUID().toString().substring(0, 8);
        return new OnboardOrganizationCommand(
                "Org " + suffix + " " + tag,
                "owner-%s-%s@example.com".formatted(suffix, tag),
                "Owner %s".formatted(tag),
                "Organización %s SA de CV".formatted(suffix + tag),
                null,
                null,
                "Sucursal %s Centro".formatted(suffix),
                "CDMX");
    }

    @Test
    void onboardingCreatesFullBackboneAndOutboxEvent() {
        OnboardOrganizationResult result = onboardingService.onboard(commandFor("graph"));

        assertThat(result.organizationId()).isNotNull();
        assertThat(result.legalEntityId()).isNotNull();
        assertThat(result.branchId()).isNotNull();
        assertThat(result.ownerId()).isNotNull();
        assertThat(result.roleId()).isNotNull();
        assertThat(result.membershipId()).isNotNull();
        assertThat(result.subscriptionId()).isNotNull();

        // Membership + OWNER role linked within the same tenant (RLS-scoped read)
        var linkedRoles = tenantRunner.run(result.organizationId(), () ->
                membershipRoleRepository.findByMembershipId(result.membershipId()));
        assertThat(linkedRoles).hasSize(1);
        assertThat(linkedRoles.get(0).getRole().getId()).isEqualTo(result.roleId());

        // Trial subscription activated with 14-day period (RLS-scoped read)
        var subscriptions = tenantRunner.run(result.organizationId(), () ->
                subscriptionRepository.findByOrganizationId(result.organizationId()));
        assertThat(subscriptions).hasSize(1);
        assertThat(subscriptions.get(0).getStatus()).isEqualTo(Subscription.Status.TRIAL);

        // Transactional Outbox: event written in the same transaction.
        // (outbox_event has no RLS: platform-level table, tenantId FK is data
        //  only. The DB is shared across IT classes, so the assertion filters
        //  by tenant instead of relying on row order.)
        var pending = outboxEventRepository.findPending();
        var ownEvent = pending.stream()
                .filter(event -> result.organizationId().equals(event.getTenantId()))
                .findFirst();
        assertThat(ownEvent).isPresent();
        assertThat(ownEvent.get().getEventType()).isEqualTo("organization.onboarded");

        // The creating tenant sees its own membership through RLS
        var own = tenantRunner.run(result.organizationId(), () ->
                membershipRepository.findByOrganizationId(result.organizationId()));
        assertThat(own).hasSize(1);
        assertThat(own.get(0).getStatus()).isEqualTo(Membership.Status.ACTIVE);
    }
}
