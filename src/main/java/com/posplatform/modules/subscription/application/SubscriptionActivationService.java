package com.posplatform.modules.subscription.application;

import com.posplatform.modules.organization.Organization;
import com.posplatform.modules.organization.application.ports.SubscriptionActivation;
import com.posplatform.modules.subscription.Plan;
import com.posplatform.modules.subscription.Subscription;
import com.posplatform.modules.subscription.infrastructure.PlanRepository;
import com.posplatform.modules.subscription.infrastructure.SubscriptionRepository;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;
import java.util.UUID;

/**
 * Subscription adapter for the SubscriptionActivation port.
 * Grants the seeded TRIAL plan when an organization is onboarded.
 */
@Component
public class SubscriptionActivationService implements SubscriptionActivation {

    static final String TRIAL_PLAN_CODE = "trial";

    private final PlanRepository planRepository;
    private final SubscriptionRepository subscriptionRepository;

    public SubscriptionActivationService(PlanRepository planRepository,
                                         SubscriptionRepository subscriptionRepository) {
        this.planRepository = planRepository;
        this.subscriptionRepository = subscriptionRepository;
    }

    @Override
    public Subscription activateTrial(UUID tenantId, Organization organization) {
        Plan trial = planRepository.findByCode(TRIAL_PLAN_CODE)
                .orElseThrow(() -> new IllegalStateException("trial plan not seeded; run migrations"));

        // Business rule: a single ACTIVE/TRIAL plan per organization (partial unique index).
        subscriptionRepository.findFirstByOrganizationIdAndStatus(
                organization.getId(), Subscription.Status.ACTIVE)
                .ifPresent(sub -> {
                    throw new IllegalStateException("organization has an active subscription");
                });

        Instant now = Instant.now();
        Subscription subscription = new Subscription(
                organization, trial, Subscription.Status.TRIAL,
                now, now, now.plus(Duration.ofDays(14)));
        return subscriptionRepository.save(subscription);
    }
}
