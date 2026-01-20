package com.bigbrightpaints.erp.modules.inventory.domain;

import org.springframework.data.jpa.repository.JpaRepository;

import com.bigbrightpaints.erp.modules.company.domain.Company;
import java.util.List;

public interface RawMaterialMovementRepository extends JpaRepository<RawMaterialMovement, Long> {
    List<RawMaterialMovement> findByReferenceTypeAndReferenceId(String referenceType, String referenceId);
    List<RawMaterialMovement> findByRawMaterialCompanyAndReferenceTypeAndReferenceId(Company company,
                                                                                     String referenceType,
                                                                                     String referenceId);
    List<RawMaterialMovement> findByRawMaterialBatch(RawMaterialBatch batch);
}
