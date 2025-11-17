package com.bigbrightpaints.erp.modules.accounting.domain;

import com.bigbrightpaints.erp.modules.company.domain.Company;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface AccountingPeriodRepository extends JpaRepository<AccountingPeriod, Long> {
    List<AccountingPeriod> findByCompanyOrderByYearDescMonthDesc(Company company);
    Optional<AccountingPeriod> findByCompanyAndId(Company company, Long id);
    Optional<AccountingPeriod> findByCompanyAndYearAndMonth(Company company, int year, int month);
    Optional<AccountingPeriod> findFirstByCompanyAndStatusOrderByStartDateDesc(Company company, AccountingPeriodStatus status);
    Optional<AccountingPeriod> findFirstByCompanyOrderByStartDateDesc(Company company);
}
