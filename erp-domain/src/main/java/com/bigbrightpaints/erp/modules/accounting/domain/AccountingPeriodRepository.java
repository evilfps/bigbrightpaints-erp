package com.bigbrightpaints.erp.modules.accounting.domain;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import com.bigbrightpaints.erp.modules.company.domain.Company;

import jakarta.persistence.LockModeType;

public interface AccountingPeriodRepository extends JpaRepository<AccountingPeriod, Long> {
  List<AccountingPeriod> findByCompanyOrderByYearDescMonthDesc(Company company);

  Optional<AccountingPeriod> findByCompanyAndId(Company company, Long id);

  Optional<AccountingPeriod> findByCompanyAndYearAndMonth(Company company, int year, int month);

  Optional<AccountingPeriod> findFirstByCompanyAndStatusOrderByStartDateDesc(
      Company company, AccountingPeriodStatus status);

  Optional<AccountingPeriod> findFirstByCompanyAndStatusAndStartDateLessThanEqualOrderByStartDateDesc(
      Company company, AccountingPeriodStatus status, LocalDate startDate);

  Optional<AccountingPeriod> findFirstByCompanyOrderByStartDateDesc(Company company);

  @Lock(LockModeType.PESSIMISTIC_WRITE)
  @Query("select p from AccountingPeriod p where p.company = :company and p.id = :periodId")
  Optional<AccountingPeriod> lockByCompanyAndId(
      @Param("company") Company company, @Param("periodId") Long periodId);

  @Lock(LockModeType.PESSIMISTIC_READ)
  @Query(
      "select p from AccountingPeriod p where p.company = :company and p.year = :year and p.month ="
          + " :month")
  Optional<AccountingPeriod> lockByCompanyAndYearAndMonth(
      @Param("company") Company company, @Param("year") int year, @Param("month") int month);
}
