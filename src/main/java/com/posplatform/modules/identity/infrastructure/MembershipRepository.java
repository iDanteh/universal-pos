package com.posplatform.modules.identity.infrastructure;

import com.posplatform.modules.identity.Membership;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface MembershipRepository extends JpaRepository<Membership, UUID> {

    List<Membership> findByOrganizationId(UUID organizationId);

    List<Membership> findByUserId(UUID userId);

    Optional<Membership> findByOrganizationIdAndUserId(UUID organizationId, UUID userId);

    /** Invitation consumption (v0.10): the token hash is the credential. */
    Optional<Membership> findByInvitationTokenHash(String tokenHash);
}
