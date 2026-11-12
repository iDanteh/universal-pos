package com.posplatform.modules.organization.infrastructure;

import com.posplatform.modules.organization.Organization;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.UUID;

public interface OrganizationRepository extends JpaRepository<Organization, UUID> {
}
