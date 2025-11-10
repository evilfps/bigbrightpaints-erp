package com.bigbrightpaints.erp.modules.inventory.domain;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface InventoryReservationRepository extends JpaRepository<InventoryReservation, Long> {
    List<InventoryReservation> findByReferenceTypeAndReferenceId(String referenceType, String referenceId);
    Optional<InventoryReservation> findFirstByFinishedGoodOrderByCreatedAtAsc(FinishedGood finishedGood);
}
