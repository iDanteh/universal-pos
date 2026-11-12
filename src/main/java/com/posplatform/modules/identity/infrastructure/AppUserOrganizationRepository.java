package com.posplatform.modules.identity.infrastructure;

import com.posplatform.modules.identity.AppUserOrganization;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface AppUserOrganizationRepository extends JpaRepository<AppUserOrganization, UUID> {

    List<AppUserOrganization> findByUserIdOrderByCreatedAtAsc(UUID userId);

    Optional<AppUserOrganization> findByUserIdAndOrganizationId(UUID userId, UUID organizationId);
}
