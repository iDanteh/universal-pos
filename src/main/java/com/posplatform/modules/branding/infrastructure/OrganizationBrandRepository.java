package com.posplatform.modules.branding.infrastructure;

import com.posplatform.modules.branding.OrganizationBrand;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

public interface OrganizationBrandRepository extends JpaRepository<OrganizationBrand, UUID> {

    List<OrganizationBrand> findByTenantId(UUID tenantId);
}
