package com.bigbrightpaints.erp.modules.inventory.domain;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface InventoryMovementRepository extends JpaRepository<InventoryMovement, Long> {
    List<InventoryMovement> findByReferenceTypeAndReferenceIdOrderByCreatedAtAsc(String referenceType, String referenceId);
    List<InventoryMovement> findByReferenceTypeAndReferenceIdAndMovementTypeIgnoreCaseAndJournalEntryIdIsNull(
            String referenceType, String referenceId, String movementType);
    List<InventoryMovement> findByReferenceTypeAndReferenceIdAndFinishedGoodIdAndJournalEntryIdIsNull(
            String referenceType, String referenceId, Long finishedGoodId);
}
