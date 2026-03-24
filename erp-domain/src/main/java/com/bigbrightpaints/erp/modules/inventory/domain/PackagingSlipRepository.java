package com.bigbrightpaints.erp.modules.inventory.domain;

import com.bigbrightpaints.erp.modules.company.domain.Company;
import org.springframework.data.jpa.repository.EntityGraph;
import jakarta.persistence.LockModeType;
import jakarta.persistence.QueryHint;
import org.springframework.data.jpa.repository.QueryHints;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.Set;

public interface PackagingSlipRepository extends JpaRepository<PackagingSlip, Long> {
    @EntityGraph(attributePaths = {"salesOrder", "salesOrder.dealer", "lines", "lines.finishedGoodBatch", "lines.finishedGoodBatch.finishedGood"})
    List<PackagingSlip> findByCompanyOrderByCreatedAtDesc(Company company);
    @EntityGraph(attributePaths = {"salesOrder", "salesOrder.dealer", "lines", "lines.finishedGoodBatch", "lines.finishedGoodBatch.finishedGood"})
    Optional<PackagingSlip> findByIdAndCompany(Long id, Company company);
    @EntityGraph(attributePaths = {"salesOrder", "salesOrder.dealer", "lines", "lines.finishedGoodBatch", "lines.finishedGoodBatch.finishedGood"})
    Optional<PackagingSlip> findByCompanyAndSalesOrderId(Company company, Long orderId);
    List<PackagingSlip> findAllByCompanyAndSalesOrderId(Company company, Long orderId);
    List<PackagingSlip> findAllByCompanyAndSalesOrderIdIn(Company company, Collection<Long> orderIds);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @QueryHints({@QueryHint(name = "jakarta.persistence.lock.timeout", value = "5000")})
    @Query("""
            select p
            from PackagingSlip p
            where p.company = :company
              and p.salesOrder.id = :orderId
            """)
    List<PackagingSlip> findAllByCompanyAndSalesOrderIdForUpdate(@Param("company") Company company,
                                                                  @Param("orderId") Long orderId);

    List<PackagingSlip> findAllByCompanyAndSalesOrderIdAndIsBackorderFalse(Company company, Long orderId);
    List<PackagingSlip> findAllByCompanyAndSalesOrderIdAndIsBackorderTrue(Company company, Long orderId);

    @Query("""
            select p.id
            from PackagingSlip p
            where p.company = :company
              and p.salesOrder.id = :orderId
              and p.isBackorder = true
              and upper(p.status) <> 'CANCELLED'
            """)
    List<Long> findActiveBackorderSlipIds(@Param("company") Company company,
                                          @Param("orderId") Long orderId);

    @Query("""
            select p
            from PackagingSlip p
            where p.company = :company
              and p.salesOrder.id = :orderId
              and p.isBackorder = false
            """)
    List<PackagingSlip> findPrimarySlipsByOrderId(@Param("company") Company company,
                                                  @Param("orderId") Long orderId);

    List<PackagingSlip> findByCompanyAndDispatchedAtBetween(Company company, Instant start, Instant end);
    long countByCompanyAndStatusInAndCreatedAtBefore(Company company, Set<String> statuses, Instant cutoff);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @QueryHints({@QueryHint(name = "jakarta.persistence.lock.timeout", value = "5000")})
    @Query("select p from PackagingSlip p left join fetch p.salesOrder where p.salesOrder.id = :orderId and p.company = :company")
    Optional<PackagingSlip> findAndLockBySalesOrderId(@Param("orderId") Long orderId, @Param("company") Company company);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @QueryHints({@QueryHint(name = "jakarta.persistence.lock.timeout", value = "5000")})
    @Query("""
            select p from PackagingSlip p left join fetch p.salesOrder
            where p.salesOrder.id = :orderId
              and p.company = :company
              and p.isBackorder = false
            """)
    Optional<PackagingSlip> findAndLockPrimaryBySalesOrderId(@Param("orderId") Long orderId, @Param("company") Company company);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select p from PackagingSlip p where p.id = :id and p.company = :company")
    Optional<PackagingSlip> findAndLockByIdAndCompany(@Param("id") Long id, @Param("company") Company company);
}
