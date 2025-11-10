package com.bigbrightpaints.erp.modules.inventory.domain;

import com.bigbrightpaints.erp.modules.company.domain.Company;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface PackagingSlipRepository extends JpaRepository<PackagingSlip, Long> {
    List<PackagingSlip> findByCompanyOrderByCreatedAtDesc(Company company);
    Optional<PackagingSlip> findByIdAndCompany(Long id, Company company);
    Optional<PackagingSlip> findBySalesOrderId(Long orderId);
}
