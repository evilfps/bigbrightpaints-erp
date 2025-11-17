package com.bigbrightpaints.erp.modules.inventory.domain;

import com.bigbrightpaints.erp.modules.company.domain.Company;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

public interface RawMaterialRepository extends JpaRepository<RawMaterial, Long> {
    List<RawMaterial> findByCompanyOrderByNameAsc(Company company);
    Optional<RawMaterial> findByCompanyAndId(Company company, Long id);
    Optional<RawMaterial> findByCompanyAndSku(Company company, String sku);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select rm from RawMaterial rm where rm.company = :company and rm.id = :id")
    Optional<RawMaterial> lockByCompanyAndId(@Param("company") Company company, @Param("id") Long id);
}
