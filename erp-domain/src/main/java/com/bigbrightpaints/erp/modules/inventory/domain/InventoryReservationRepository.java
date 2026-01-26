package com.bigbrightpaints.erp.modules.inventory.domain;

import com.bigbrightpaints.erp.modules.company.domain.Company;
import com.bigbrightpaints.erp.modules.inventory.domain.FinishedGood;
import com.bigbrightpaints.erp.modules.inventory.domain.FinishedGoodBatch;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface InventoryReservationRepository extends JpaRepository<InventoryReservation, Long> {
    List<InventoryReservation> findByFinishedGoodCompanyAndReferenceTypeAndReferenceId(
            Company company,
            String referenceType,
            String referenceId);
    Optional<InventoryReservation> findFirstByFinishedGoodOrderByCreatedAtAsc(FinishedGood finishedGood);

    List<InventoryReservation> findByFinishedGood(FinishedGood finishedGood);
    List<InventoryReservation> findByFinishedGoodBatch(FinishedGoodBatch finishedGoodBatch);
    
    // For orphan detection: find RESERVED reservations that might need cleanup
    List<InventoryReservation> findByFinishedGoodCompanyAndStatus(Company company, String status);

    Optional<InventoryReservation> findByFinishedGoodCompanyAndId(Company company, Long id);
    
    // Find reservations by order reference
    List<InventoryReservation> findByReferenceTypeAndReferenceId(String referenceType, String referenceId);
}
