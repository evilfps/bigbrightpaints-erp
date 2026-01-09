package com.bigbrightpaints.erp.modules.production.domain;

import com.bigbrightpaints.erp.modules.company.domain.Company;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ProductionCategoryRepository extends JpaRepository<ProductionCategory, Long> {
    List<ProductionCategory> findByCompanyOrderByNameAsc(Company company);
    Optional<ProductionCategory> findByCompanyAndCodeIgnoreCase(Company company, String code);
    Optional<ProductionCategory> findByCompanyAndNameIgnoreCase(Company company, String name);
    Optional<ProductionCategory> findByCompanyAndId(Company company, Long id);
}
