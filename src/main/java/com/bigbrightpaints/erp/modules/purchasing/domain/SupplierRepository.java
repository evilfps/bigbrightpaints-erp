package com.bigbrightpaints.erp.modules.purchasing.domain;

import com.bigbrightpaints.erp.modules.company.domain.Company;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface SupplierRepository extends JpaRepository<Supplier, Long> {

    List<Supplier> findByCompanyOrderByNameAsc(Company company);

    Optional<Supplier> findByCompanyAndId(Company company, Long id);

    Optional<Supplier> findByCompanyAndCodeIgnoreCase(Company company, String code);
}
