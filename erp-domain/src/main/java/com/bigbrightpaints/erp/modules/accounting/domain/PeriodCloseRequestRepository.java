package com.bigbrightpaints.erp.modules.accounting.domain;

import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import com.bigbrightpaints.erp.modules.company.domain.Company;

import jakarta.persistence.LockModeType;

public interface PeriodCloseRequestRepository extends JpaRepository<PeriodCloseRequest, Long> {

  @Query(
      """
select request
from PeriodCloseRequest request
where request.company = :company
  and request.status = com.bigbrightpaints.erp.modules.accounting.domain.PeriodCloseRequestStatus.PENDING
order by request.requestedAt desc
""")
  List<PeriodCloseRequest> findPendingByCompanyOrderByRequestedAtDesc(
      @Param("company") Company company);

  List<PeriodCloseRequest> findByCompanyAndStatusOrderByRequestedAtDesc(
      Company company, PeriodCloseRequestStatus status);

  long countByCompanyAndStatus(Company company, PeriodCloseRequestStatus status);

  Optional<PeriodCloseRequest> findByCompanyAndAccountingPeriodAndStatus(
      Company company, AccountingPeriod accountingPeriod, PeriodCloseRequestStatus status);

  Optional<PeriodCloseRequest> findByCompanyAndId(Company company, Long id);

  @Lock(LockModeType.PESSIMISTIC_WRITE)
  @Query(
      """
      select request
      from PeriodCloseRequest request
      where request.company = :company
        and request.accountingPeriod = :accountingPeriod
        and request.status = :status
      """)
  Optional<PeriodCloseRequest> lockByCompanyAndAccountingPeriodAndStatus(
      @Param("company") Company company,
      @Param("accountingPeriod") AccountingPeriod accountingPeriod,
      @Param("status") PeriodCloseRequestStatus status);

  @Lock(LockModeType.PESSIMISTIC_WRITE)
  @Query(
      """
      select request
      from PeriodCloseRequest request
      where request.company = :company
        and request.id = :requestId
      """)
  Optional<PeriodCloseRequest> lockByCompanyAndId(
      @Param("company") Company company, @Param("requestId") Long requestId);

  @Query(
      """
      select request
      from PeriodCloseRequest request
      where request.company = :company
        and request.accountingPeriod = :period
      order by request.requestedAt desc
      """)
  List<PeriodCloseRequest> findByCompanyAndAccountingPeriodOrderByRequestedAtDesc(
      @Param("company") Company company, @Param("period") AccountingPeriod period);
}
