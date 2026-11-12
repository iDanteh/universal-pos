package com.posplatform.modules.organization.application.ports;

import com.posplatform.modules.organization.Organization;
import com.posplatform.modules.subscription.Subscription;

import java.util.UUID;

/** PORT: what the organization module needs from the subscription module. */
public interface SubscriptionActivation {

    Subscription activateTrial(UUID tenantId, Organization organization);
}
