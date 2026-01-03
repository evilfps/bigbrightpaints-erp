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
import java.util.List;
import java.util.Optional;

public interface PackagingSlipRepository extends JpaRepository<PackagingSlip, Long> {
    @EntityGraph(attributePaths = {"salesOrder", "salesOrder.dealer", "lines", "lines.finishedGoodBatch", "lines.finishedGoodBatch.finishedGood"})
    List<PackagingSlip> findByCompanyOrderByCreatedAtDesc(Company company);

    @EntityGraph(attributePaths = {"lines"})
    List<PackagingSlip> findByCompanyAndDispatchedAtBetween(Company company,
                                                            java.time.Instant start,
                                                            java.time.Instant end);
    @EntityGraph(attributePaths = {"salesOrder", "salesOrder.dealer", "lines", "lines.finishedGoodBatch", "lines.finishedGoodBatch.finishedGood"})
    Optional<PackagingSlip> findByIdAndCompany(Long id, Company company);
    @EntityGraph(attributePaths = {"salesOrder", "salesOrder.dealer", "lines", "lines.finishedGoodBatch", "lines.finishedGoodBatch.finishedGood"})
    Optional<PackagingSlip> findByCompanyAndSalesOrderId(Company company, Long orderId);
    List<PackagingSlip> findAllByCompanyAndSalesOrderId(Company company, Long orderId);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @QueryHints({@QueryHint(name = "javax.persistence.lock.timeout", value = "5000")})
    @Query("select p from PackagingSlip p left join fetch p.salesOrder where p.salesOrder.id = :orderId and p.company = :company")
    Optional<PackagingSlip> findAndLockBySalesOrderId(@Param("orderId") Long orderId, @Param("company") Company company);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select p from PackagingSlip p where p.id = :id and p.company = :company")
    Optional<PackagingSlip> findAndLockByIdAndCompany(@Param("id") Long id, @Param("company") Company company);

    long countByCompanyAndStatusInAndCreatedAtBefore(Company company,
                                                     java.util.Collection<String> statuses,
                                                     java.time.Instant cutoff);
}
