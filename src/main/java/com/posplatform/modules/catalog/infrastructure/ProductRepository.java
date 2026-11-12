package com.posplatform.modules.catalog.infrastructure;

import com.posplatform.modules.catalog.Product;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

public interface ProductRepository extends JpaRepository<Product, UUID> {

    List<Product> findByTenantIdOrderById(UUID tenantId);
}
