package com.bigbrightpaints.erp.modules.inventory.domain;

import com.bigbrightpaints.erp.modules.company.domain.Company;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface FinishedGoodRepository extends JpaRepository<FinishedGood, Long> {
    List<FinishedGood> findByCompanyOrderByProductCodeAsc(Company company);
    Optional<FinishedGood> findByCompanyAndId(Company company, Long id);
    Optional<FinishedGood> findByCompanyAndProductCode(Company company, String productCode);
}
