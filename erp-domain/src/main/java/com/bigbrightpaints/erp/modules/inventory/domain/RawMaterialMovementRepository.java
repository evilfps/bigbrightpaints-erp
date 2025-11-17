package com.bigbrightpaints.erp.modules.inventory.domain;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface RawMaterialMovementRepository extends JpaRepository<RawMaterialMovement, Long> {
    List<RawMaterialMovement> findByReferenceTypeAndReferenceId(String referenceType, String referenceId);
}
