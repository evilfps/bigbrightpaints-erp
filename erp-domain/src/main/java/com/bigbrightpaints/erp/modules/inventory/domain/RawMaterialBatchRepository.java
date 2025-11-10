package com.bigbrightpaints.erp.modules.inventory.domain;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface RawMaterialBatchRepository extends JpaRepository<RawMaterialBatch, Long> {
    List<RawMaterialBatch> findByRawMaterial(RawMaterial rawMaterial);
}
