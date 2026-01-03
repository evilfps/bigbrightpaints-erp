package com.bigbrightpaints.erp.modules.inventory.domain;

import com.bigbrightpaints.erp.modules.company.domain.Company;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;

public interface RawMaterialRepository extends JpaRepository<RawMaterial, Long> {
    List<RawMaterial> findByCompanyOrderByNameAsc(Company company);
    Optional<RawMaterial> findByCompanyAndId(Company company, Long id);
    Optional<RawMaterial> findByCompanyAndSku(Company company, String sku);
    long countByCompany(Company company);

    @Query("select count(rm) from RawMaterial rm where rm.company = :company and rm.currentStock < rm.reorderLevel")
    long countLowStockByCompany(@Param("company") Company company);

    @Query("select count(rm) from RawMaterial rm where rm.company = :company and rm.currentStock <= rm.minStock")
    long countCriticalStockByCompany(@Param("company") Company company);

    @Query("""
            select rm from RawMaterial rm
            where rm.company = :company
              and (rm.currentStock < rm.reorderLevel or rm.currentStock <= rm.minStock)
            order by rm.name asc
            """)
    List<RawMaterial> findLowStockByCompany(@Param("company") Company company);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select rm from RawMaterial rm where rm.company = :company and rm.id = :id")
    Optional<RawMaterial> lockByCompanyAndId(@Param("company") Company company, @Param("id") Long id);

    @Modifying
    @Query("UPDATE RawMaterial rm SET rm.currentStock = rm.currentStock - :quantity, rm.updatedAt = CURRENT_TIMESTAMP " +
           "WHERE rm.id = :id AND rm.currentStock >= :quantity")
    int deductStockIfSufficient(@Param("id") Long id, @Param("quantity") BigDecimal quantity);

    // Filter by material type
    List<RawMaterial> findByCompanyAndMaterialTypeOrderByNameAsc(Company company, MaterialType materialType);

    // Production materials only (for manufacturing)
    default List<RawMaterial> findProductionMaterials(Company company) {
        return findByCompanyAndMaterialTypeOrderByNameAsc(company, MaterialType.PRODUCTION);
    }

    // Packaging materials only (for packing step)
    default List<RawMaterial> findPackagingMaterials(Company company) {
        return findByCompanyAndMaterialTypeOrderByNameAsc(company, MaterialType.PACKAGING);
    }
}
