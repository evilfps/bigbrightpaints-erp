package com.bigbrightpaints.erp.modules.sales.domain;

import com.bigbrightpaints.erp.modules.company.domain.Company;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface DealerRepository extends JpaRepository<Dealer, Long> {
    List<Dealer> findByCompanyOrderByNameAsc(Company company);
    Optional<Dealer> findByCompanyAndId(Company company, Long id);
}
