package com.bigbrightpaints.erp.modules.factory.domain;

import com.bigbrightpaints.erp.modules.company.domain.Company;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

public interface ProductionBatchRepository extends JpaRepository<ProductionBatch, Long> {
    List<ProductionBatch> findByCompanyOrderByProducedAtDesc(Company company);
    Optional<ProductionBatch> findByCompanyAndId(Company company, Long id);
    Optional<ProductionBatch> findByCompanyAndBatchNumber(Company company, String batchNumber);

    @Modifying
    @Query(value = """
            INSERT INTO production_batches (
                company_id,
                plan_id,
                batch_number,
                quantity_produced,
                produced_at,
                logged_by,
                notes
            )
            VALUES (
                :companyId,
                :planId,
                :batchNumber,
                :quantityProduced,
                :producedAt,
                :loggedBy,
                :notes
            )
            ON CONFLICT (company_id, batch_number) DO NOTHING
            """, nativeQuery = true)
    int insertIfAbsent(@Param("companyId") Long companyId,
                       @Param("planId") Long planId,
                       @Param("batchNumber") String batchNumber,
                       @Param("quantityProduced") Double quantityProduced,
                       @Param("producedAt") Instant producedAt,
                       @Param("loggedBy") String loggedBy,
                       @Param("notes") String notes);
}
