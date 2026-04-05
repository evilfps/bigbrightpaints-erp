package com.bigbrightpaints.erp.modules.inventory.domain;

import java.time.Instant;
import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import com.bigbrightpaints.erp.modules.company.domain.Company;

public interface InventoryMovementRepository extends JpaRepository<InventoryMovement, Long> {
  java.util.Optional<InventoryMovement> findFirstByFinishedGoodOrderByCreatedAtAsc(
      FinishedGood finishedGood);

  List<InventoryMovement> findByReferenceTypeAndReferenceIdOrderByCreatedAtAsc(
      String referenceType, String referenceId);

  List<InventoryMovement> findByFinishedGoodBatchOrderByCreatedAtAsc(
      FinishedGoodBatch finishedGoodBatch);

  List<InventoryMovement>
      findByFinishedGood_CompanyAndReferenceTypeAndReferenceIdOrderByCreatedAtAsc(
          Company company, String referenceType, String referenceId);

  List<InventoryMovement>
      findByFinishedGood_CompanyAndReferenceTypeAndReferenceIdStartingWithOrderByCreatedAtAsc(
          Company company, String referenceType, String referenceId);

  List<InventoryMovement>
      findByFinishedGood_CompanyAndPackingSlipIdAndMovementTypeIgnoreCaseOrderByCreatedAtAsc(
          Company company, Long packingSlipId, String movementType);

  List<InventoryMovement>
      findByFinishedGood_CompanyAndReferenceTypeAndReferenceIdAndPackingSlipIdIsNullAndMovementTypeIgnoreCaseOrderByCreatedAtAsc(
          Company company, String referenceType, String referenceId, String movementType);

  List<InventoryMovement> findByFinishedGood_CompanyAndJournalEntryIdAndReferenceTypeOrderByIdAsc(
      Company company, Long journalEntryId, String referenceType);

  boolean existsByFinishedGood_CompanyAndReferenceTypeAndReferenceIdContainingIgnoreCase(
      Company company, String referenceType, String referenceId);

  List<InventoryMovement> findByReferenceTypeAndReferenceIdStartingWithOrderByCreatedAtAsc(
      String referenceType, String referenceId);

  @Query(
      """
      select m from InventoryMovement m
      join fetch m.finishedGood fg
      left join fetch m.finishedGoodBatch batch
      where fg.company = :company
        and m.createdAt >= :cutoff
      """)
  List<InventoryMovement> findByCompanyCreatedAtOnOrAfter(
      @Param("company") Company company, @Param("cutoff") Instant cutoff);
}
