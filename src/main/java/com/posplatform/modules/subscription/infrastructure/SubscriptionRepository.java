package com.posplatform.modules.subscription.infrastructure;

import com.posplatform.modules.subscription.Subscription;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface SubscriptionRepository extends JpaRepository<Subscription, UUID> {

    List<Subscription> findByOrganizationId(UUID organizationId);

    Optional<Subscription> findFirstByOrganizationIdAndStatus(UUID organizationId, Subscription.Status status);
}
