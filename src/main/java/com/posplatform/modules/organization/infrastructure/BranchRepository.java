package com.posplatform.modules.organization.infrastructure;

import com.posplatform.modules.organization.Branch;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

public interface BranchRepository extends JpaRepository<Branch, UUID> {

    List<Branch> findByTenantId(UUID tenantId);
}
