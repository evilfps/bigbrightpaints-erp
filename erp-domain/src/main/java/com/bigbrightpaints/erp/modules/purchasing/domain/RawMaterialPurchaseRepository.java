package com.bigbrightpaints.erp.modules.purchasing.domain;

import com.bigbrightpaints.erp.modules.company.domain.Company;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface RawMaterialPurchaseRepository extends JpaRepository<RawMaterialPurchase, Long> {
    List<RawMaterialPurchase> findByCompanyOrderByInvoiceDateDesc(Company company);
    Optional<RawMaterialPurchase> findByCompanyAndId(Company company, Long id);
    Optional<RawMaterialPurchase> findByCompanyAndInvoiceNumberIgnoreCase(Company company, String invoiceNumber);
}
