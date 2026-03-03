package com.bigbrightpaints.erp.modules.accounting.domain;

import com.bigbrightpaints.erp.modules.company.domain.Company;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface OpeningBalanceImportRepository extends JpaRepository<OpeningBalanceImport, Long> {
    Optional<OpeningBalanceImport> findByCompanyAndIdempotencyKey(Company company, String idempotencyKey);
}
