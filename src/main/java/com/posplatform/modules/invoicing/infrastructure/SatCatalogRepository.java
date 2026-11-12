package com.posplatform.modules.invoicing.infrastructure;

import com.posplatform.modules.invoicing.SatCatalog;
import com.posplatform.modules.invoicing.SatCatalog.Catalog;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface SatCatalogRepository extends JpaRepository<SatCatalog, UUID> {

    List<SatCatalog> findByCatalogAndActiveTrueOrderByCodeAsc(Catalog catalog);

    Optional<SatCatalog> findByCatalogAndCode(Catalog catalog, String code);
}
