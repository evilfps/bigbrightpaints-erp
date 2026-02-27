package com.bigbrightpaints.erp.modules.inventory.domain;

import com.bigbrightpaints.erp.modules.company.domain.Company;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

public interface InventoryAdjustmentRepository extends JpaRepository<InventoryAdjustment, Long> {
    List<InventoryAdjustment> findByCompanyOrderByAdjustmentDateDesc(Company company);
    Optional<InventoryAdjustment> findByCompanyAndId(Company company, Long id);
    Optional<InventoryAdjustment> findByCompanyAndIdempotencyKey(Company company, String idempotencyKey);
    Optional<InventoryAdjustment> findByCompanyAndReversalOf(Company company, InventoryAdjustment reversalOf);

    @EntityGraph(attributePaths = {"lines", "lines.finishedGood"})
    Optional<InventoryAdjustment> findWithLinesByCompanyAndIdempotencyKey(Company company, String idempotencyKey);

    @EntityGraph(attributePaths = {"lines", "lines.finishedGood", "reversalOf", "reversalEntry"})
    @Query("""
            select a
            from InventoryAdjustment a
            where a.company = :company
              and a.id = :id
            """)
    Optional<InventoryAdjustment> findWithLinesByCompanyAndId(@Param("company") Company company, @Param("id") Long id);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("""
            select a
            from InventoryAdjustment a
            where a.company = :company
              and a.id = :id
            """)
    Optional<InventoryAdjustment> lockByCompanyAndId(@Param("company") Company company, @Param("id") Long id);
}
