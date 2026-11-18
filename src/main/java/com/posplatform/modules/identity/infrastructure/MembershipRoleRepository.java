package com.posplatform.modules.identity.infrastructure;

import com.posplatform.modules.identity.MembershipRole;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

public interface MembershipRoleRepository extends JpaRepository<MembershipRole, UUID> {

    List<MembershipRole> findByMembershipId(UUID membershipId);

    List<MembershipRole> findByTenantId(UUID tenantId);
}
