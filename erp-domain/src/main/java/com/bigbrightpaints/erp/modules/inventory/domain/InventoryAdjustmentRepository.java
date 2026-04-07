package com.bigbrightpaints.erp.modules.inventory.domain;

import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;

import com.bigbrightpaints.erp.modules.company.domain.Company;

public interface InventoryAdjustmentRepository extends JpaRepository<InventoryAdjustment, Long> {
  @EntityGraph(attributePaths = {"lines", "lines.finishedGood"})
  List<InventoryAdjustment> findByCompanyOrderByAdjustmentDateDesc(Company company);

  Optional<InventoryAdjustment> findByCompanyAndId(Company company, Long id);

  Optional<InventoryAdjustment> findByCompanyAndIdempotencyKey(
      Company company, String idempotencyKey);

  @EntityGraph(attributePaths = {"lines", "lines.finishedGood"})
  Optional<InventoryAdjustment> findWithLinesByCompanyAndIdempotencyKey(
      Company company, String idempotencyKey);
}
