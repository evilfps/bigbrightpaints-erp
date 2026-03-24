package com.bigbrightpaints.erp.modules.factory.domain;

import com.bigbrightpaints.erp.modules.company.domain.Company;
import com.bigbrightpaints.erp.modules.inventory.domain.RawMaterial;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

public interface PackagingSizeMappingRepository extends JpaRepository<PackagingSizeMapping, Long> {

    @Query("""
            SELECT m FROM PackagingSizeMapping m
            WHERE m.company = :company
              AND UPPER(m.packagingSize) = UPPER(:size)
              AND m.active = true
            ORDER BY m.id ASC
            """)
    List<PackagingSizeMapping> findActiveByCompanyAndPackagingSizeIgnoreCase(
            @Param("company") Company company,
            @Param("size") String packagingSize);

    List<PackagingSizeMapping> findByCompanyAndActiveOrderByPackagingSizeAsc(Company company, boolean active);

    List<PackagingSizeMapping> findByCompanyOrderByPackagingSizeAsc(Company company);

    Optional<PackagingSizeMapping> findByCompanyAndId(Company company, Long id);

    boolean existsByCompanyAndPackagingSizeIgnoreCaseAndRawMaterial(Company company,
                                                                     String packagingSize,
                                                                     RawMaterial rawMaterial);

    boolean existsByCompanyAndPackagingSizeIgnoreCaseAndRawMaterialAndIdNot(Company company,
                                                                            String packagingSize,
                                                                            RawMaterial rawMaterial,
                                                                            Long id);

    boolean existsByCompanyAndPackagingSizeIgnoreCaseAndActiveTrue(Company company,
                                                                   String packagingSize);
}
