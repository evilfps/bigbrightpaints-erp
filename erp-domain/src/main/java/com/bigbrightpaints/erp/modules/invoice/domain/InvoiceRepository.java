package com.bigbrightpaints.erp.modules.invoice.domain;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import com.bigbrightpaints.erp.modules.accounting.domain.JournalEntry;
import com.bigbrightpaints.erp.modules.company.domain.Company;
import com.bigbrightpaints.erp.modules.sales.domain.Dealer;

import jakarta.persistence.LockModeType;

public interface InvoiceRepository extends JpaRepository<Invoice, Long> {

  @EntityGraph(attributePaths = {"lines", "dealer"})
  List<Invoice> findByCompanyOrderByIssueDateDesc(Company company);

  @EntityGraph(attributePaths = {"lines", "dealer"})
  List<Invoice> findByCompanyAndIssueDateBetweenOrderByIssueDateAsc(
      Company company, java.time.LocalDate start, java.time.LocalDate end);

  @EntityGraph(attributePaths = {"lines", "dealer"})
  List<Invoice> findByCompanyAndDealerOrderByIssueDateDesc(Company company, Dealer dealer);

  @Query(
      """
select distinct i.salesOrder.id
from Invoice i
where i.company = :company
  and i.salesOrder is not null
  and i.salesOrder.dealer = :dealer
  and (i.status is null or upper(trim(i.status)) not in ('DRAFT', 'VOID', 'REVERSED', 'WRITTEN_OFF'))
""")
  Set<Long> findActiveSalesOrderIdsByCompanyAndDealer(
      @Param("company") Company company, @Param("dealer") Dealer dealer);

  @EntityGraph(attributePaths = {"lines", "dealer"})
  Page<Invoice> findByCompanyOrderByIssueDateDescIdDesc(Company company, Pageable pageable);

  @EntityGraph(attributePaths = {"lines", "dealer"})
  Page<Invoice> findByCompanyAndDealerOrderByIssueDateDescIdDesc(
      Company company, Dealer dealer, Pageable pageable);

  @Query(
      "select i.id from Invoice i where i.company = :company order by i.issueDate desc, i.id desc")
  Page<Long> findIdsByCompanyOrderByIssueDateDescIdDesc(
      @Param("company") Company company, Pageable pageable);

  @Query(
      "select i.id from Invoice i where i.company = :company and i.salesOrder.id = :salesOrderId"
          + " order by i.issueDate desc, i.id desc")
  Page<Long> findIdsByCompanyAndSalesOrderIdOrderByIssueDateDescIdDesc(
      @Param("company") Company company,
      @Param("salesOrderId") Long salesOrderId,
      Pageable pageable);

  @Query(
      "select i.id from Invoice i where i.company = :company and i.dealer = :dealer order by"
          + " i.issueDate desc, i.id desc")
  Page<Long> findIdsByCompanyAndDealerOrderByIssueDateDescIdDesc(
      @Param("company") Company company, @Param("dealer") Dealer dealer, Pageable pageable);

  @EntityGraph(attributePaths = {"lines", "dealer"})
  @Query(
      "select i from Invoice i where i.company = :company and i.id in :ids order by i.issueDate"
          + " desc, i.id desc")
  List<Invoice> findByCompanyAndIdInOrderByIssueDateDescIdDesc(
      @Param("company") Company company, @Param("ids") List<Long> ids);

  long countByCompanyAndIssueDateBetweenAndStatusIn(
      Company company, LocalDate start, LocalDate end, Collection<String> statuses);

  long countByCompanyAndIssueDateBetweenAndStatusNotAndJournalEntryIsNull(
      Company company, LocalDate start, LocalDate end, String status);

  List<Invoice> findAllByCompanyAndSalesOrderId(Company company, Long salesOrderId);

  List<Invoice> findByCompanyAndSalesOrder_IdIn(Company company, List<Long> salesOrderIds);

  Optional<Invoice> findByCompanyAndSalesOrderId(Company company, Long salesOrderId);

  @Lock(LockModeType.PESSIMISTIC_WRITE)
  @Query("select i from Invoice i where i.company = :company and i.salesOrder.id = :salesOrderId")
  Optional<Invoice> lockByCompanyAndSalesOrderId(
      @Param("company") Company company, @Param("salesOrderId") Long salesOrderId);

  @EntityGraph(attributePaths = "lines")
  Optional<Invoice> findByCompanyAndId(Company company, Long id);

  @EntityGraph(attributePaths = {"lines", "dealer", "salesOrder"})
  Optional<Invoice> findPdfViewByCompanyAndId(Company company, Long id);

  @EntityGraph(attributePaths = {"lines", "dealer", "salesOrder"})
  Optional<Invoice> findByCompanyAndJournalEntry(Company company, JournalEntry journalEntry);

  @EntityGraph(attributePaths = {"dealer", "salesOrder"})
  List<Invoice> findByCompanyAndJournalEntry_IdIn(Company company, List<Long> journalEntryIds);

  @Lock(LockModeType.PESSIMISTIC_WRITE)
  @Query("select i from Invoice i where i.company = :company and i.id = :id")
  Optional<Invoice> lockByCompanyAndId(@Param("company") Company company, @Param("id") Long id);

  @Lock(LockModeType.PESSIMISTIC_WRITE)
  @Query(
      """
      select i from Invoice i
      where i.company = :company
        and i.dealer = :dealer
        and i.outstandingAmount > 0
        and (i.status is null or i.status not in ('VOID','REVERSED','DRAFT','WRITTEN_OFF'))
      order by case when i.dueDate is null then 1 else 0 end, i.dueDate, i.issueDate, i.id
      """)
  List<Invoice> lockOpenInvoicesForSettlement(
      @Param("company") Company company, @Param("dealer") Dealer dealer);

  @Query(
      """
      select coalesce(sum(i.totalAmount), 0)
      from Invoice i
      where i.company = :company
        and (i.status is null or upper(trim(i.status)) not in ('DRAFT', 'VOID', 'REVERSED'))
      """)
  BigDecimal sumTotalRevenueByCompany(@Param("company") Company company);

  @Query(
      """
select coalesce(sum(i.outstandingAmount), 0)
from Invoice i
where i.company = :company
  and (i.status is null or upper(trim(i.status)) not in ('DRAFT', 'VOID', 'REVERSED', 'WRITTEN_OFF'))
""")
  BigDecimal sumOutstandingReceivablesByCompany(@Param("company") Company company);
}
