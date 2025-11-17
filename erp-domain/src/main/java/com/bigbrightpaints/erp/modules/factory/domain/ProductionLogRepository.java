package com.bigbrightpaints.erp.modules.factory.domain;

import com.bigbrightpaints.erp.modules.company.domain.Company;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Optional;

public interface ProductionLogRepository extends JpaRepository<ProductionLog, Long> {
    List<ProductionLog> findTop25ByCompanyOrderByProducedAtDesc(Company company);
    Optional<ProductionLog> findByCompanyAndId(Company company, Long id);
    Optional<ProductionLog> findTopByCompanyAndProductionCodeStartingWithOrderByProductionCodeDesc(Company company, String prefix);
    List<ProductionLog> findByCompanyAndStatusInOrderByProducedAtAsc(Company company, Collection<ProductionLogStatus> statuses);

    @Query("SELECT pl FROM ProductionLog pl WHERE pl.company = :company " +
            "AND pl.status = 'FULLY_PACKED' " +
            "AND pl.producedAt >= :startDate " +
            "AND pl.producedAt < :endDate " +
            "ORDER BY pl.producedAt ASC")
    List<ProductionLog> findFullyPackedBatchesByMonth(
            @Param("company") Company company,
            @Param("startDate") Instant startDate,
            @Param("endDate") Instant endDate
    );
}
