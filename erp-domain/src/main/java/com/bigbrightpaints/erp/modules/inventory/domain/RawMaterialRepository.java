package com.bigbrightpaints.erp.modules.inventory.domain;

import java.math.BigDecimal;
import java.util.Collection;
import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import com.bigbrightpaints.erp.modules.company.domain.Company;

import jakarta.persistence.LockModeType;

public interface RawMaterialRepository extends JpaRepository<RawMaterial, Long> {
  List<RawMaterial> findByCompanyOrderByNameAsc(Company company);

  Optional<RawMaterial> findByCompanyAndId(Company company, Long id);

  Optional<RawMaterial> findByCompanyAndSku(Company company, String sku);

  Optional<RawMaterial> findByCompanyAndSkuIgnoreCase(Company company, String sku);

  @Lock(LockModeType.PESSIMISTIC_WRITE)
  @Query(
      """
      select rm from RawMaterial rm
      where rm.company = :company
        and lower(rm.sku) = lower(:sku)
      """)
  Optional<RawMaterial> lockByCompanyAndSkuIgnoreCase(
      @Param("company") Company company, @Param("sku") String sku);

  List<RawMaterial> findByCompanyAndSkuIn(Company company, Collection<String> skus);

  @Query("select rm from RawMaterial rm where rm.company = :company and lower(rm.sku) in :skus")
  List<RawMaterial> findByCompanyAndSkuInIgnoreCase(
      @Param("company") Company company, @Param("skus") Collection<String> skus);

  @Lock(LockModeType.PESSIMISTIC_WRITE)
  @Query("select rm from RawMaterial rm where rm.company = :company and rm.id = :id")
  Optional<RawMaterial> lockByCompanyAndId(@Param("company") Company company, @Param("id") Long id);

  @Modifying
  @Query(
      "UPDATE RawMaterial rm SET rm.currentStock = rm.currentStock - :quantity, rm.updatedAt ="
          + " CURRENT_TIMESTAMP WHERE rm.id = :id AND rm.currentStock >= :quantity")
  int deductStockIfSufficient(@Param("id") Long id, @Param("quantity") BigDecimal quantity);

  // Filter by material type
  List<RawMaterial> findByCompanyAndMaterialTypeOrderByNameAsc(
      Company company, MaterialType materialType);

  // Production materials only (for manufacturing)
  default List<RawMaterial> findProductionMaterials(Company company) {
    return findByCompanyAndMaterialTypeOrderByNameAsc(company, MaterialType.PRODUCTION);
  }

  // Packaging materials only (for packing step)
  default List<RawMaterial> findPackagingMaterials(Company company) {
    return findByCompanyAndMaterialTypeOrderByNameAsc(company, MaterialType.PACKAGING);
  }
}
