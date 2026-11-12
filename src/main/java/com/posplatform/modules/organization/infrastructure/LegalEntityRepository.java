package com.posplatform.modules.organization.infrastructure;

import com.posplatform.modules.organization.LegalEntity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

public interface LegalEntityRepository extends JpaRepository<LegalEntity, UUID> {

    List<LegalEntity> findByOrganizationId(UUID organizationId);

    /** RFC unique per tenant (V8 partial unique index mirrors this rule). */
    boolean existsByOrganizationIdAndTaxIdIgnoreCase(UUID organizationId, String taxId);
}
