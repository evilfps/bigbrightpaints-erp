package com.bigbrightpaints.erp.modules.inventory.domain;

import com.bigbrightpaints.erp.modules.company.domain.Company;
import java.util.Optional;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

public interface OpeningStockImportRepository extends JpaRepository<OpeningStockImport, Long> {
    Optional<OpeningStockImport> findByCompanyAndIdempotencyKey(Company company, String idempotencyKey);

    Optional<OpeningStockImport> findByCompanyAndOpeningStockBatchKey(Company company, String openingStockBatchKey);

    Optional<OpeningStockImport> findByCompanyAndReplayProtectionKey(Company company, String replayProtectionKey);

    Page<OpeningStockImport> findByCompany(Company company, Pageable pageable);
}
