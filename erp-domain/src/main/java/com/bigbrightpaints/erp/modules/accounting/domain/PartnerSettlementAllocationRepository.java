package com.bigbrightpaints.erp.modules.accounting.domain;

import com.bigbrightpaints.erp.modules.company.domain.Company;
import com.bigbrightpaints.erp.modules.invoice.domain.Invoice;
import com.bigbrightpaints.erp.modules.purchasing.domain.RawMaterialPurchase;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface PartnerSettlementAllocationRepository extends JpaRepository<PartnerSettlementAllocation, Long> {

    List<PartnerSettlementAllocation> findByCompanyAndInvoiceOrderByCreatedAtDesc(Company company, Invoice invoice);

    @EntityGraph(attributePaths = {"invoice", "purchase", "dealer", "supplier", "journalEntry"})
    List<PartnerSettlementAllocation> findByCompanyAndInvoice_IdInOrderByCreatedAtDesc(Company company, List<Long> invoiceIds);

    List<PartnerSettlementAllocation> findByCompanyAndPurchaseOrderByCreatedAtDesc(Company company, RawMaterialPurchase purchase);

    @EntityGraph(attributePaths = {"invoice", "purchase", "dealer", "supplier", "journalEntry"})
    List<PartnerSettlementAllocation> findByCompanyAndPurchase_IdInOrderByCreatedAtDesc(Company company, List<Long> purchaseIds);

    List<PartnerSettlementAllocation> findByCompanyAndIdempotencyKey(Company company, String idempotencyKey);

    List<PartnerSettlementAllocation> findByCompanyAndIdempotencyKeyIgnoreCase(Company company, String idempotencyKey);

    List<PartnerSettlementAllocation> findByCompanyAndIdempotencyKeyIgnoreCaseOrderByCreatedAtAscIdAsc(Company company,
                                                                                                        String idempotencyKey);

    List<PartnerSettlementAllocation> findByCompanyAndJournalEntryOrderByCreatedAtAsc(Company company, JournalEntry journalEntry);

    @EntityGraph(attributePaths = {"invoice", "purchase", "dealer", "supplier", "journalEntry"})
    List<PartnerSettlementAllocation> findByCompanyAndJournalEntry_IdIn(Company company, List<Long> journalEntryIds);

    @EntityGraph(attributePaths = {"invoice", "invoice.salesOrder"})
    List<PartnerSettlementAllocation> findByCompanyAndPartnerTypeAndSettlementDateBetween(Company company,
                                                                                           PartnerType partnerType,
                                                                                           java.time.LocalDate start,
                                                                                           java.time.LocalDate end);
}
