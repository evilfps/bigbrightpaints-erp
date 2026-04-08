package com.bigbrightpaints.erp.modules.inventory.domain;

import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import com.bigbrightpaints.erp.modules.company.domain.Company;

public interface InventoryPhysicalCountRepository
    extends JpaRepository<InventoryPhysicalCount, Long> {

  @Query(
      """
      select countRecord
      from InventoryPhysicalCount countRecord
      where countRecord.company = :company
        and countRecord.target = :target
        and countRecord.inventoryItemId in :inventoryItemIds
      order by countRecord.countDate desc, countRecord.createdAt desc, countRecord.id desc
      """)
  List<InventoryPhysicalCount> findLatestCandidates(
      @Param("company") Company company,
      @Param("target") InventoryPhysicalCountTarget target,
      @Param("inventoryItemIds") List<Long> inventoryItemIds);
}
