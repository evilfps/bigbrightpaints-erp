package com.bigbrightpaints.erp.modules.production.domain;

import com.bigbrightpaints.erp.modules.company.domain.Company;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface CatalogImportRepository extends JpaRepository<CatalogImport, Long> {
    Optional<CatalogImport> findByCompanyAndIdempotencyKey(Company company, String idempotencyKey);
}
