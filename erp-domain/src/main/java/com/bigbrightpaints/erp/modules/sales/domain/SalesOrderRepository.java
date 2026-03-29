package com.bigbrightpaints.erp.modules.sales.domain;

import java.math.BigDecimal;
import java.time.Instant;
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
import org.springframework.data.jpa.repository.QueryHints;
import org.springframework.data.repository.query.Param;

import com.bigbrightpaints.erp.modules.company.domain.Company;
import com.bigbrightpaints.erp.modules.sales.dto.DealerCreditExposureView;

import jakarta.persistence.LockModeType;
import jakarta.persistence.QueryHint;

public interface SalesOrderRepository extends JpaRepository<SalesOrder, Long> {
  @EntityGraph(attributePaths = {"items", "dealer"})
  List<SalesOrder> findByCompanyOrderByCreatedAtDesc(Company company);

  @EntityGraph(attributePaths = {"items", "dealer"})
  List<SalesOrder> findByCompanyAndStatusOrderByCreatedAtDesc(Company company, String status);

  @EntityGraph(attributePaths = {"items", "dealer"})
  Page<SalesOrder> findByCompanyOrderByCreatedAtDescIdDesc(Company company, Pageable pageable);

  @EntityGraph(attributePaths = {"items", "dealer"})
  Page<SalesOrder> findByCompanyAndStatusOrderByCreatedAtDescIdDesc(
      Company company, String status, Pageable pageable);

  @Query(
      "select o.id from SalesOrder o where o.company = :company order by o.createdAt desc, o.id"
          + " desc")
  Page<Long> findIdsByCompanyOrderByCreatedAtDescIdDesc(
      @Param("company") Company company, Pageable pageable);

  @Query(
      "select o.id from SalesOrder o where o.company = :company and o.status = :status order by"
          + " o.createdAt desc, o.id desc")
  Page<Long> findIdsByCompanyAndStatusOrderByCreatedAtDescIdDesc(
      @Param("company") Company company, @Param("status") String status, Pageable pageable);

  @Query(
      """
      select o.id
      from SalesOrder o
      where o.company = :company
        and (
              o.salesJournalEntryId is not null
           or o.cogsJournalEntryId is not null
           or o.fulfillmentInvoiceId is not null
           or exists (
                  select 1
                  from PackagingSlip ps
                  where ps.company = :company
                    and ps.salesOrder = o
                    and (ps.invoiceId is not null
                         or ps.journalEntryId is not null
                         or ps.cogsJournalEntryId is not null)
              )
            )
      order by o.createdAt desc, o.id desc
      """)
  Page<Long> findDispatchMarkerCandidateIdsByCompanyOrderByCreatedAtDescIdDesc(
      @Param("company") Company company, Pageable pageable);

  @Query(
      "select o.id from SalesOrder o where o.company = :company and o.dealer = :dealer order by"
          + " o.createdAt desc, o.id desc")
  Page<Long> findIdsByCompanyAndDealerOrderByCreatedAtDescIdDesc(
      @Param("company") Company company, @Param("dealer") Dealer dealer, Pageable pageable);

  @Query(
      "select o.id from SalesOrder o where o.company = :company and o.dealer = :dealer and o.status"
          + " = :status order by o.createdAt desc, o.id desc")
  Page<Long> findIdsByCompanyAndDealerAndStatusOrderByCreatedAtDescIdDesc(
      @Param("company") Company company,
      @Param("dealer") Dealer dealer,
      @Param("status") String status,
      Pageable pageable);

  @Query(
      """
select o.id
from SalesOrder o
where o.company = :company
  and (
        :status is null
        or upper(trim(o.status)) = :status
        or (:status = 'DRAFT' and upper(trim(o.status)) = 'BOOKED')
        or (:status = 'DISPATCHED' and upper(trim(o.status)) in ('SHIPPED', 'FULFILLED'))
        or (:status = 'SETTLED' and upper(trim(o.status)) = 'COMPLETED')
  )
  and (:dealer is null or o.dealer = :dealer)
  and (:orderNumber is null or lower(o.orderNumber) like lower(concat('%', :orderNumber, '%')))
  and (:fromDate is null or o.createdAt >= :fromDate)
  and (:toDate is null or o.createdAt <= :toDate)
order by o.createdAt desc, o.id desc
""")
  Page<Long> searchIdsByCompany(
      @Param("company") Company company,
      @Param("status") String status,
      @Param("dealer") Dealer dealer,
      @Param("orderNumber") String orderNumber,
      @Param("fromDate") Instant fromDate,
      @Param("toDate") Instant toDate,
      Pageable pageable);

  @EntityGraph(attributePaths = {"items", "dealer"})
  @Query(
      "select o from SalesOrder o where o.company = :company and o.id in :ids order by o.createdAt"
          + " desc, o.id desc")
  List<SalesOrder> findByCompanyAndIdInOrderByCreatedAtDescIdDesc(
      @Param("company") Company company, @Param("ids") List<Long> ids);

  Optional<SalesOrder> findByCompanyAndId(Company company, Long id);

  @EntityGraph(attributePaths = {"items", "dealer"})
  Optional<SalesOrder> findWithItemsByCompanyAndId(Company company, Long id);

  @Lock(LockModeType.PESSIMISTIC_WRITE)
  @QueryHints({@QueryHint(name = "jakarta.persistence.lock.timeout", value = "5000")})
  @EntityGraph(attributePaths = {"items", "dealer"})
  @Query("select o from SalesOrder o where o.company = :company and o.id = :id")
  Optional<SalesOrder> findWithItemsByCompanyAndIdForUpdate(
      @Param("company") Company company, @Param("id") Long id);

  @EntityGraph(attributePaths = {"company", "dealer"})
  List<SalesOrder> findAll();

  Optional<SalesOrder> findByCompanyAndIdempotencyKey(Company company, String idempotencyKey);

  @EntityGraph(attributePaths = {"items"})
  List<SalesOrder> findByCompanyAndDealerOrderByCreatedAtDesc(Company company, Dealer dealer);

  @EntityGraph(attributePaths = {"items"})
  List<SalesOrder> findByCompanyAndDealerAndStatusOrderByCreatedAtDesc(
      Company company, Dealer dealer, String status);

  @Query(
      """
select coalesce(sum(o.totalAmount), 0)
from SalesOrder o
where o.company = :company
  and o.dealer = :dealer
  and o.status is not null
  and (o.paymentMode is null or upper(trim(o.paymentMode)) <> 'CASH')
  and upper(trim(o.status)) in :statuses
  and (:excludeOrderId is null or o.id <> :excludeOrderId)
  and not exists (
        select 1
        from Invoice i
        where i.company = :company
          and i.salesOrder = o
          and (i.status is null or upper(trim(i.status)) not in ('DRAFT', 'VOID', 'REVERSED'))
  )
""")
  BigDecimal sumPendingCreditExposureByCompanyAndDealer(
      @Param("company") Company company,
      @Param("dealer") Dealer dealer,
      @Param("statuses") Set<String> statuses,
      @Param("excludeOrderId") Long excludeOrderId);

  @Query(
      """
select new com.bigbrightpaints.erp.modules.sales.dto.DealerCreditExposureView(o.dealer.id, coalesce(sum(o.totalAmount), 0))
from SalesOrder o
where o.company = :company
  and o.dealer.id in :dealerIds
  and o.status is not null
  and (o.paymentMode is null or upper(trim(o.paymentMode)) <> 'CASH')
  and upper(trim(o.status)) in :statuses
  and not exists (
        select 1
        from Invoice i
        where i.company = :company
          and i.salesOrder = o
          and (i.status is null or upper(trim(i.status)) not in ('DRAFT', 'VOID', 'REVERSED')))
group by o.dealer.id
""")
  List<DealerCreditExposureView> sumPendingCreditExposureByCompanyAndDealerIds(
      @Param("company") Company company,
      @Param("dealerIds") Collection<Long> dealerIds,
      @Param("statuses") Set<String> statuses);

  @Query(
      """
select count(o)
from SalesOrder o
where o.company = :company
  and o.dealer = :dealer
  and o.status is not null
  and (o.paymentMode is null or upper(trim(o.paymentMode)) <> 'CASH')
  and upper(trim(o.status)) in :statuses
  and (:excludeOrderId is null or o.id <> :excludeOrderId)
  and not exists (
        select 1
        from Invoice i
        where i.company = :company
          and i.salesOrder = o
          and (i.status is null or upper(trim(i.status)) not in ('DRAFT', 'VOID', 'REVERSED'))
  )
""")
  long countPendingCreditExposureByCompanyAndDealer(
      @Param("company") Company company,
      @Param("dealer") Dealer dealer,
      @Param("statuses") Set<String> statuses,
      @Param("excludeOrderId") Long excludeOrderId);

  @Query(
      """
      select upper(trim(coalesce(o.status, 'UNKNOWN'))), count(o)
      from SalesOrder o
      where o.company = :company
      group by upper(trim(coalesce(o.status, 'UNKNOWN')))
      """)
  List<Object[]> countByCompanyGroupedByNormalizedStatus(@Param("company") Company company);
}
