package com.bigbrightpaints.erp.modules.inventory.domain;

import com.bigbrightpaints.erp.modules.company.domain.Company;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface InventoryMovementRepository extends JpaRepository<InventoryMovement, Long> {
    List<InventoryMovement> findByReferenceTypeAndReferenceIdOrderByCreatedAtAsc(String referenceType, String referenceId);

    List<InventoryMovement> findByFinishedGood_CompanyAndReferenceTypeAndReferenceIdOrderByCreatedAtAsc(
            Company company,
            String referenceType,
            String referenceId);

    List<InventoryMovement> findByFinishedGood_CompanyAndReferenceTypeAndReferenceIdStartingWithOrderByCreatedAtAsc(
            Company company,
            String referenceType,
            String referenceId);

    List<InventoryMovement> findByFinishedGood_CompanyAndPackingSlipIdAndMovementTypeIgnoreCaseOrderByCreatedAtAsc(
            Company company,
            Long packingSlipId,
            String movementType);

    boolean existsByFinishedGood_CompanyAndReferenceTypeAndReferenceIdContainingIgnoreCase(
            Company company,
            String referenceType,
            String referenceId);

    List<InventoryMovement> findByReferenceTypeAndReferenceIdStartingWithOrderByCreatedAtAsc(
            String referenceType,
            String referenceId);
}
