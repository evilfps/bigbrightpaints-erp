package com.bigbrightpaints.erp.modules.inventory.domain;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Collection;
import java.util.List;

import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import com.bigbrightpaints.erp.modules.company.domain.Company;

import jakarta.persistence.LockModeType;

public interface RawMaterialBatchRepository extends JpaRepository<RawMaterialBatch, Long> {
  List<RawMaterialBatch> findByRawMaterial(RawMaterial rawMaterial);

  @EntityGraph(attributePaths = "rawMaterial")
  java.util.Optional<RawMaterialBatch> findByRawMaterial_CompanyAndId(Company company, Long id);

  @Lock(LockModeType.PESSIMISTIC_WRITE)
  @Query(
      """
      select b from RawMaterialBatch b
      where b.rawMaterial.company = :company
        and b.id = :id
      """)
  java.util.Optional<RawMaterialBatch> lockByRawMaterialCompanyAndId(
      @Param("company") Company company, @Param("id") Long id);

  List<RawMaterialBatch> findByRawMaterial_InventoryAccountId(Long inventoryAccountId);

  boolean existsByRawMaterialAndBatchCode(RawMaterial rawMaterial, String batchCode);

  java.util.Optional<RawMaterialBatch> findByRawMaterialAndBatchCode(
      RawMaterial rawMaterial, String batchCode);

  @Lock(LockModeType.PESSIMISTIC_WRITE)
  @Query(
      """
      select b from RawMaterialBatch b
      where b.rawMaterial = :rawMaterial
        and b.batchCode = :batchCode
      """)
  java.util.Optional<RawMaterialBatch> lockByRawMaterialAndBatchCode(
      @Param("rawMaterial") RawMaterial rawMaterial, @Param("batchCode") String batchCode);

  @Lock(LockModeType.PESSIMISTIC_WRITE)
  @Query(
      """
      select b from RawMaterialBatch b
      where b.rawMaterial = :rawMaterial
        and b.quantity > 0
      order by b.receivedAt asc, b.id asc
      """)
  List<RawMaterialBatch> findAvailableBatchesFIFO(@Param("rawMaterial") RawMaterial rawMaterial);

  @Modifying
  @Query(
      "UPDATE RawMaterialBatch b SET b.quantity = b.quantity - :deductQty "
          + "WHERE b.id = :batchId AND b.quantity >= :deductQty")
  int deductQuantityIfSufficient(
      @Param("batchId") Long batchId, @Param("deductQty") BigDecimal deductQty);

  @Query(
      """
      select sum(b.quantity * b.costPerUnit) / sum(b.quantity)
      from RawMaterialBatch b
      where b.rawMaterial = :rawMaterial
        and b.quantity > 0
      """)
  BigDecimal calculateWeightedAverageCost(@Param("rawMaterial") RawMaterial rawMaterial);

  @Query(
      """
      select b.rawMaterial.id, count(b)
      from RawMaterialBatch b
      where b.rawMaterial.id in :rawMaterialIds
      group by b.rawMaterial.id
      """)
  List<Object[]> countBatchesGroupedByRawMaterialIds(
      @Param("rawMaterialIds") Collection<Long> rawMaterialIds);

  @Query(
      """
      select b from RawMaterialBatch b
      where b.rawMaterial.company = :company
        and b.expiryDate is not null
        and b.expiryDate >= :today
        and b.expiryDate <= :cutoff
        and b.quantity > 0
      order by b.expiryDate asc, b.manufacturedAt asc, b.id asc
      """)
  List<RawMaterialBatch> findExpiringSoonByCompany(
      @Param("company") Company company,
      @Param("today") LocalDate today,
      @Param("cutoff") LocalDate cutoff);
}
