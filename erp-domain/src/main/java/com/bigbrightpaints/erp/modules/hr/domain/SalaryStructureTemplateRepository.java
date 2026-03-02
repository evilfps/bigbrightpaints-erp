package com.bigbrightpaints.erp.modules.hr.domain;

import com.bigbrightpaints.erp.modules.company.domain.Company;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface SalaryStructureTemplateRepository extends JpaRepository<SalaryStructureTemplate, Long> {

    List<SalaryStructureTemplate> findByCompanyOrderByNameAsc(Company company);

    Optional<SalaryStructureTemplate> findByCompanyAndId(Company company, Long id);

    Optional<SalaryStructureTemplate> findByCompanyAndCodeIgnoreCase(Company company, String code);
}
