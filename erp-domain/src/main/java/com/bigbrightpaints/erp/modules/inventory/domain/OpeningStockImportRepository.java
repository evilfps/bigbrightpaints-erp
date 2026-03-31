package com.bigbrightpaints.erp.modules.inventory.domain;

import java.util.Optional;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import com.bigbrightpaints.erp.modules.company.domain.Company;

public interface OpeningStockImportRepository extends JpaRepository<OpeningStockImport, Long> {
  Optional<OpeningStockImport> findByCompanyAndIdempotencyKey(
      Company company, String idempotencyKey);

  Optional<OpeningStockImport> findFirstByCompanyAndContentFingerprintOrderByCreatedAtAscIdAsc(
      Company company, String contentFingerprint);

  Optional<OpeningStockImport> findByCompanyAndOpeningStockBatchKey(
      Company company, String openingStockBatchKey);

  java.util.List<OpeningStockImport> findByCompanyOrderByCreatedAtAscIdAsc(Company company);

  Page<OpeningStockImport> findByCompany(Company company, Pageable pageable);
}
