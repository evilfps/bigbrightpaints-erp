package com.bigbrightpaints.erp.modules.accounting.service;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.bigbrightpaints.erp.modules.accounting.domain.JournalEntryRepository;
import com.bigbrightpaints.erp.modules.accounting.domain.JournalLineRepository;
import com.bigbrightpaints.erp.modules.accounting.domain.PartnerSettlementAllocationRepository;
import com.bigbrightpaints.erp.modules.accounting.dto.AccountingTransactionAuditDetailDto;
import com.bigbrightpaints.erp.modules.accounting.dto.AccountingTransactionAuditListItemDto;
import com.bigbrightpaints.erp.modules.accounting.event.AccountingEventRepository;
import com.bigbrightpaints.erp.modules.company.service.CompanyContextService;
import com.bigbrightpaints.erp.modules.inventory.domain.PackagingSlipRepository;
import com.bigbrightpaints.erp.modules.invoice.domain.InvoiceRepository;
import com.bigbrightpaints.erp.modules.purchasing.domain.RawMaterialPurchaseRepository;
import com.bigbrightpaints.erp.shared.dto.PageResponse;

@Service
public class AccountingAuditTrailService {

  private final AccountingAuditTrailClassifier classifier;
  private final SettlementAuditMemoDecoder settlementAuditMemoDecoder;
  private final AccountingAuditTrailReferenceChainService referenceChainService;
  private final AccountingAuditTrailTransactionQueryService transactionQueryService;
  private final AccountingAuditTrailTransactionDetailService transactionDetailService;

  public AccountingAuditTrailService(
      CompanyContextService companyContextService,
      JournalEntryRepository journalEntryRepository,
      JournalLineRepository journalLineRepository,
      AccountingEventRepository accountingEventRepository,
      PartnerSettlementAllocationRepository settlementAllocationRepository,
      InvoiceRepository invoiceRepository,
      RawMaterialPurchaseRepository rawMaterialPurchaseRepository,
      PackagingSlipRepository packagingSlipRepository) {
    this.classifier = new AccountingAuditTrailClassifier();
    this.settlementAuditMemoDecoder = new SettlementAuditMemoDecoder();
    this.referenceChainService =
        new AccountingAuditTrailReferenceChainService(
            invoiceRepository, settlementAllocationRepository, packagingSlipRepository);
    this.transactionQueryService =
        new AccountingAuditTrailTransactionQueryService(
            companyContextService,
            journalEntryRepository,
            journalLineRepository,
            settlementAllocationRepository,
            invoiceRepository,
            rawMaterialPurchaseRepository,
            classifier);
    this.transactionDetailService =
        new AccountingAuditTrailTransactionDetailService(
            companyContextService,
            journalEntryRepository,
            accountingEventRepository,
            settlementAllocationRepository,
            invoiceRepository,
            rawMaterialPurchaseRepository,
            referenceChainService,
            classifier,
            settlementAuditMemoDecoder);
  }

  @Transactional(readOnly = true)
  public PageResponse<AccountingTransactionAuditListItemDto> listTransactions(
      java.time.LocalDate from,
      java.time.LocalDate to,
      String module,
      String status,
      String referenceNumber,
      int page,
      int size) {
    return transactionQueryService.listTransactions(
        from, to, module, status, referenceNumber, page, size);
  }

  @Transactional(readOnly = true)
  public AccountingTransactionAuditDetailDto transactionDetail(Long journalEntryId) {
    return transactionDetailService.transactionDetail(journalEntryId);
  }
}
