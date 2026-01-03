package com.bigbrightpaints.erp.modules.factory.domain;

import com.bigbrightpaints.erp.modules.company.domain.Company;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface ProductionBatchRepository extends JpaRepository<ProductionBatch, Long> {
    List<ProductionBatch> findByCompanyOrderByProducedAtDesc(Company company);
    Optional<ProductionBatch> findByCompanyAndId(Company company, Long id);
    long countByCompany(Company company);
}
