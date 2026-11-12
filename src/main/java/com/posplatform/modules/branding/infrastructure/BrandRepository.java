package com.posplatform.modules.branding.infrastructure;

import com.posplatform.modules.branding.Brand;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.UUID;

public interface BrandRepository extends JpaRepository<Brand, UUID> {
}
