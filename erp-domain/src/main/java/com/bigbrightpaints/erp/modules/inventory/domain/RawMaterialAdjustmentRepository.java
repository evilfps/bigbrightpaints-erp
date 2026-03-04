package com.bigbrightpaints.erp.modules.inventory.domain;

import com.bigbrightpaints.erp.modules.company.domain.Company;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface RawMaterialAdjustmentRepository extends JpaRepository<RawMaterialAdjustment, Long> {

    List<RawMaterialAdjustment> findByCompanyOrderByAdjustmentDateDesc(Company company);

    Optional<RawMaterialAdjustment> findByCompanyAndIdempotencyKey(Company company, String idempotencyKey);

    @EntityGraph(attributePaths = {"lines", "lines.rawMaterial"})
    Optional<RawMaterialAdjustment> findWithLinesByCompanyAndIdempotencyKey(Company company, String idempotencyKey);
}
