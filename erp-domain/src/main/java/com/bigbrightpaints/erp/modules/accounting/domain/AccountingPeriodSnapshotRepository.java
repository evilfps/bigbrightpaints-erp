package com.bigbrightpaints.erp.modules.accounting.domain;

import com.bigbrightpaints.erp.modules.company.domain.Company;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface AccountingPeriodSnapshotRepository extends JpaRepository<AccountingPeriodSnapshot, Long> {
    Optional<AccountingPeriodSnapshot> findByCompanyAndPeriod(Company company, AccountingPeriod period);
}
