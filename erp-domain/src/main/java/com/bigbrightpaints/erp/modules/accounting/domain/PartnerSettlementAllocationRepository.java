package com.bigbrightpaints.erp.modules.accounting.domain;

import com.bigbrightpaints.erp.modules.company.domain.Company;
import com.bigbrightpaints.erp.modules.invoice.domain.Invoice;
import com.bigbrightpaints.erp.modules.purchasing.domain.RawMaterialPurchase;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface PartnerSettlementAllocationRepository extends JpaRepository<PartnerSettlementAllocation, Long> {

    List<PartnerSettlementAllocation> findByCompanyAndInvoiceOrderByCreatedAtDesc(Company company, Invoice invoice);

    List<PartnerSettlementAllocation> findByCompanyAndPurchaseOrderByCreatedAtDesc(Company company, RawMaterialPurchase purchase);

    List<PartnerSettlementAllocation> findByCompanyAndIdempotencyKey(Company company, String idempotencyKey);

    @EntityGraph(attributePaths = {"invoice", "invoice.salesOrder"})
    List<PartnerSettlementAllocation> findByCompanyAndPartnerTypeAndSettlementDateBetween(Company company,
                                                                                          PartnerType partnerType,
                                                                                          java.time.LocalDate start,
                                                                                          java.time.LocalDate end);
}
