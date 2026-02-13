package com.bigbrightpaints.erp.modules.sales.domain;

import com.bigbrightpaints.erp.modules.company.domain.Company;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.EntityGraph;
import jakarta.persistence.LockModeType;
import jakarta.persistence.QueryHint;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.jpa.repository.QueryHints;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

public interface SalesOrderRepository extends JpaRepository<SalesOrder, Long> {
    @EntityGraph(attributePaths = {"items", "dealer"})
    List<SalesOrder> findByCompanyOrderByCreatedAtDesc(Company company);

    @EntityGraph(attributePaths = {"items", "dealer"})
    List<SalesOrder> findByCompanyAndStatusOrderByCreatedAtDesc(Company company, String status);

    @EntityGraph(attributePaths = {"items", "dealer"})
    Page<SalesOrder> findByCompanyOrderByCreatedAtDescIdDesc(Company company, Pageable pageable);

    @EntityGraph(attributePaths = {"items", "dealer"})
    Page<SalesOrder> findByCompanyAndStatusOrderByCreatedAtDescIdDesc(Company company, String status, Pageable pageable);

    @Query("select o.id from SalesOrder o where o.company = :company order by o.createdAt desc, o.id desc")
    Page<Long> findIdsByCompanyOrderByCreatedAtDescIdDesc(@Param("company") Company company, Pageable pageable);

    @Query("select o.id from SalesOrder o where o.company = :company and o.status = :status order by o.createdAt desc, o.id desc")
    Page<Long> findIdsByCompanyAndStatusOrderByCreatedAtDescIdDesc(@Param("company") Company company,
                                                                   @Param("status") String status,
                                                                   Pageable pageable);

    @Query("""
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
    Page<Long> findDispatchMarkerCandidateIdsByCompanyOrderByCreatedAtDescIdDesc(@Param("company") Company company,
                                                                                  Pageable pageable);

    @Query("select o.id from SalesOrder o where o.company = :company and o.dealer = :dealer order by o.createdAt desc, o.id desc")
    Page<Long> findIdsByCompanyAndDealerOrderByCreatedAtDescIdDesc(@Param("company") Company company,
                                                                   @Param("dealer") Dealer dealer,
                                                                   Pageable pageable);

    @Query("select o.id from SalesOrder o where o.company = :company and o.dealer = :dealer and o.status = :status order by o.createdAt desc, o.id desc")
    Page<Long> findIdsByCompanyAndDealerAndStatusOrderByCreatedAtDescIdDesc(@Param("company") Company company,
                                                                            @Param("dealer") Dealer dealer,
                                                                            @Param("status") String status,
                                                                            Pageable pageable);

    @EntityGraph(attributePaths = {"items", "dealer"})
    @Query("select o from SalesOrder o where o.company = :company and o.id in :ids order by o.createdAt desc, o.id desc")
    List<SalesOrder> findByCompanyAndIdInOrderByCreatedAtDescIdDesc(@Param("company") Company company,
                                                                    @Param("ids") List<Long> ids);

    Optional<SalesOrder> findByCompanyAndId(Company company, Long id);

    @EntityGraph(attributePaths = {"items", "dealer"})
    Optional<SalesOrder> findWithItemsByCompanyAndId(Company company, Long id);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @QueryHints({@QueryHint(name = "jakarta.persistence.lock.timeout", value = "5000")})
    @EntityGraph(attributePaths = {"items", "dealer"})
    @Query("select o from SalesOrder o where o.company = :company and o.id = :id")
    Optional<SalesOrder> findWithItemsByCompanyAndIdForUpdate(@Param("company") Company company, @Param("id") Long id);

    @EntityGraph(attributePaths = {"company", "dealer"})
    List<SalesOrder> findAll();

    Optional<SalesOrder> findByCompanyAndIdempotencyKey(Company company, String idempotencyKey);

    @EntityGraph(attributePaths = {"items"})
    List<SalesOrder> findByCompanyAndDealerOrderByCreatedAtDesc(Company company, Dealer dealer);

    @EntityGraph(attributePaths = {"items"})
    List<SalesOrder> findByCompanyAndDealerAndStatusOrderByCreatedAtDesc(Company company, Dealer dealer, String status);
}
