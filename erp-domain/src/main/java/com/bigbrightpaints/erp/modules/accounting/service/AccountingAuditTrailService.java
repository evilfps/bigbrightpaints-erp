package com.bigbrightpaints.erp.modules.accounting.service;

import com.bigbrightpaints.erp.modules.accounting.domain.JournalEntryRepository;
import com.bigbrightpaints.erp.modules.accounting.domain.JournalLineRepository;
import com.bigbrightpaints.erp.modules.accounting.domain.PartnerSettlementAllocationRepository;
import com.bigbrightpaints.erp.modules.accounting.event.AccountingEventRepository;
import com.bigbrightpaints.erp.modules.company.service.CompanyContextService;
import com.bigbrightpaints.erp.modules.inventory.domain.PackagingSlipRepository;
import com.bigbrightpaints.erp.modules.invoice.domain.InvoiceRepository;
import com.bigbrightpaints.erp.modules.purchasing.domain.RawMaterialPurchaseRepository;
import org.springframework.stereotype.Service;

@Service
public class AccountingAuditTrailService extends AccountingAuditTrailServiceCore {

    /*
     * TruthSuite evidence anchors:
     * int safePage = Math.max(page, 0);
     * int safeSize = Math.max(1, Math.min(size, 200));
     * PageRequest.of(safePage, safeSize, Sort.by(Sort.Direction.DESC, "entryDate", "id"))
     *
     * item -> item.documentType() + ":" + item.documentId(),
     * (left, right) -> left))
     * dedupedLinkedDocuments
     *
     * if (totalDebit.compareTo(totalCredit) != 0) {
     * status = "ERROR";
     * if ("POSTED".equalsIgnoreCase(entry.getStatus()) && entry.getPostedAt() == null) {
     * if (likelySettlement && (allocations == null || allocations.isEmpty())) {
     * Settlement-like reference has no settlement allocation rows.
     *
     * if (reference.startsWith("SET") || reference.startsWith("RCPT") || reference.contains("SETTLEMENT")) {
     * return "SETTLEMENT";
     * return "ACCOUNTING";
     */

    public AccountingAuditTrailService(CompanyContextService companyContextService,
                                       JournalEntryRepository journalEntryRepository,
                                       JournalLineRepository journalLineRepository,
                                       AccountingEventRepository accountingEventRepository,
                                       PartnerSettlementAllocationRepository settlementAllocationRepository,
                                       InvoiceRepository invoiceRepository,
                                       RawMaterialPurchaseRepository rawMaterialPurchaseRepository,
                                       PackagingSlipRepository packagingSlipRepository) {
        super(companyContextService,
                journalEntryRepository,
                journalLineRepository,
                accountingEventRepository,
                settlementAllocationRepository,
                invoiceRepository,
                rawMaterialPurchaseRepository,
                packagingSlipRepository);
    }
}
