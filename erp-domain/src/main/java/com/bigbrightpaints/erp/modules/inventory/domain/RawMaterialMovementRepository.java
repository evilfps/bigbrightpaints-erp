package com.bigbrightpaints.erp.modules.inventory.domain;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import com.bigbrightpaints.erp.modules.company.domain.Company;
import java.time.Instant;
import java.util.List;

public interface RawMaterialMovementRepository extends JpaRepository<RawMaterialMovement, Long> {
    List<RawMaterialMovement> findByReferenceTypeAndReferenceId(String referenceType, String referenceId);
    List<RawMaterialMovement> findByRawMaterialCompanyAndReferenceTypeAndReferenceId(Company company,
                                                                                     String referenceType,
                                                                                     String referenceId);
    List<RawMaterialMovement> findByRawMaterialBatch(RawMaterialBatch batch);

    @Query("""
            select m from RawMaterialMovement m
            join fetch m.rawMaterial rm
            left join fetch m.rawMaterialBatch batch
            where rm.company = :company
              and m.createdAt >= :cutoff
            """)
    List<RawMaterialMovement> findByCompanyCreatedAtOnOrAfter(@Param("company") Company company,
                                                              @Param("cutoff") Instant cutoff);
}
