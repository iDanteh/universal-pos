package com.posplatform.modules.identity.infrastructure;

import com.posplatform.modules.identity.Role;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

public interface RoleRepository extends JpaRepository<Role, UUID> {

    List<Role> findByOrganizationId(UUID organizationId);
}
