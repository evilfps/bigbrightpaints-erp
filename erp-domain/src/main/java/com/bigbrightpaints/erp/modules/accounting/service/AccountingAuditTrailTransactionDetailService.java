package com.bigbrightpaints.erp.modules.accounting.service;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.stream.Collectors;

import com.bigbrightpaints.erp.core.exception.ApplicationException;
import com.bigbrightpaints.erp.core.exception.ErrorCode;
import com.bigbrightpaints.erp.modules.accounting.domain.JournalEntry;
import com.bigbrightpaints.erp.modules.accounting.domain.JournalEntryRepository;
import com.bigbrightpaints.erp.modules.accounting.domain.JournalLine;
import com.bigbrightpaints.erp.modules.accounting.domain.PartnerSettlementAllocation;
import com.bigbrightpaints.erp.modules.accounting.domain.PartnerSettlementAllocationRepository;
import com.bigbrightpaints.erp.modules.accounting.dto.AccountingTransactionAuditDetailDto;
import com.bigbrightpaints.erp.modules.accounting.dto.JournalLineDto;
import com.bigbrightpaints.erp.modules.accounting.event.AccountingEvent;
import com.bigbrightpaints.erp.modules.accounting.event.AccountingEventRepository;
import com.bigbrightpaints.erp.modules.company.domain.Company;
import com.bigbrightpaints.erp.modules.company.service.CompanyContextService;
import com.bigbrightpaints.erp.modules.invoice.domain.Invoice;
import com.bigbrightpaints.erp.modules.invoice.domain.InvoiceRepository;
import com.bigbrightpaints.erp.modules.purchasing.domain.RawMaterialPurchase;
import com.bigbrightpaints.erp.modules.purchasing.domain.RawMaterialPurchaseRepository;
import com.bigbrightpaints.erp.shared.dto.LinkedBusinessReferenceDto;

final class AccountingAuditTrailTransactionDetailService {

  private final CompanyContextService companyContextService;
  private final JournalEntryRepository journalEntryRepository;
  private final AccountingEventRepository accountingEventRepository;
  private final PartnerSettlementAllocationRepository settlementAllocationRepository;
  private final InvoiceRepository invoiceRepository;
  private final RawMaterialPurchaseRepository rawMaterialPurchaseRepository;
  private final AccountingAuditTrailReferenceChainService referenceChainService;
  private final AccountingAuditTrailClassifier classifier;
  private final SettlementAuditMemoDecoder settlementAuditMemoDecoder;

  AccountingAuditTrailTransactionDetailService(
      CompanyContextService companyContextService,
      JournalEntryRepository journalEntryRepository,
      AccountingEventRepository accountingEventRepository,
      PartnerSettlementAllocationRepository settlementAllocationRepository,
      InvoiceRepository invoiceRepository,
      RawMaterialPurchaseRepository rawMaterialPurchaseRepository,
      AccountingAuditTrailReferenceChainService referenceChainService,
      AccountingAuditTrailClassifier classifier,
      SettlementAuditMemoDecoder settlementAuditMemoDecoder) {
    this.companyContextService = companyContextService;
    this.journalEntryRepository = journalEntryRepository;
    this.accountingEventRepository = accountingEventRepository;
    this.settlementAllocationRepository = settlementAllocationRepository;
    this.invoiceRepository = invoiceRepository;
    this.rawMaterialPurchaseRepository = rawMaterialPurchaseRepository;
    this.referenceChainService = referenceChainService;
    this.classifier = classifier;
    this.settlementAuditMemoDecoder = settlementAuditMemoDecoder;
  }

  AccountingTransactionAuditDetailDto transactionDetail(Long journalEntryId) {
    Company company = companyContextService.requireCurrentCompany();
    JournalEntry entry =
        journalEntryRepository
            .findByCompanyAndId(company, journalEntryId)
            .orElseThrow(
                () ->
                    new ApplicationException(
                        ErrorCode.BUSINESS_ENTITY_NOT_FOUND, "Journal entry not found"));

    List<PartnerSettlementAllocation> allocations =
        settlementAllocationRepository.findByCompanyAndJournalEntryOrderByCreatedAtAsc(
            company, entry);
    Optional<Invoice> invoice = findInvoiceByJournalEntry(company, entry);
    Optional<RawMaterialPurchase> purchase = findPurchaseByJournalEntry(company, entry);
    List<AccountingEvent> events =
        accountingEventRepository.findByJournalEntryIdOrderByEventTimestampAsc(entry.getId());

    BigDecimal totalDebit =
        entry.getLines().stream()
            .map(JournalLine::getDebit)
            .filter(Objects::nonNull)
            .reduce(BigDecimal.ZERO, BigDecimal::add);
    BigDecimal totalCredit =
        entry.getLines().stream()
            .map(JournalLine::getCredit)
            .filter(Objects::nonNull)
            .reduce(BigDecimal.ZERO, BigDecimal::add);

    AccountingAuditTrailClassifier.ConsistencyResult consistency =
        classifier.assessConsistency(entry, allocations, totalDebit, totalCredit);
    String transactionType =
        classifier.deriveTransactionType(
            entry, invoice.orElse(null), purchase.orElse(null), allocations);
    String module = classifier.deriveModule(transactionType, entry.getReferenceNumber());

    List<JournalLineDto> lines =
        entry.getLines().stream()
            .sorted(Comparator.comparing(line -> line.getAccount().getCode()))
            .map(
                line ->
                    new JournalLineDto(
                        line.getAccount().getId(),
                        line.getAccount().getCode(),
                        line.getDescription(),
                        line.getDebit(),
                        line.getCredit()))
            .toList();

    List<AccountingTransactionAuditDetailDto.LinkedDocument> linkedDocuments = new ArrayList<>();
    invoice.ifPresent(
        value ->
            linkedDocuments.add(
                new AccountingTransactionAuditDetailDto.LinkedDocument(
                    "INVOICE",
                    value.getId(),
                    value.getInvoiceNumber(),
                    value.getStatus(),
                    value.getTotalAmount(),
                    value.getOutstandingAmount())));
    purchase.ifPresent(
        value ->
            linkedDocuments.add(
                new AccountingTransactionAuditDetailDto.LinkedDocument(
                    "PURCHASE",
                    value.getId(),
                    value.getInvoiceNumber(),
                    value.getStatus(),
                    value.getTotalAmount(),
                    value.getOutstandingAmount())));
    for (PartnerSettlementAllocation allocation : allocations) {
      if (allocation.getInvoice() != null) {
        Invoice allocInvoice = allocation.getInvoice();
        linkedDocuments.add(
            new AccountingTransactionAuditDetailDto.LinkedDocument(
                "SETTLEMENT_INVOICE",
                allocInvoice.getId(),
                allocInvoice.getInvoiceNumber(),
                allocInvoice.getStatus(),
                allocInvoice.getTotalAmount(),
                allocInvoice.getOutstandingAmount()));
      }
      if (allocation.getPurchase() != null) {
        RawMaterialPurchase allocPurchase = allocation.getPurchase();
        linkedDocuments.add(
            new AccountingTransactionAuditDetailDto.LinkedDocument(
                "SETTLEMENT_PURCHASE",
                allocPurchase.getId(),
                allocPurchase.getInvoiceNumber(),
                allocPurchase.getStatus(),
                allocPurchase.getTotalAmount(),
                allocPurchase.getOutstandingAmount()));
      }
    }
    List<AccountingTransactionAuditDetailDto.LinkedDocument> dedupedLinkedDocuments =
        linkedDocuments.stream()
            .collect(
                Collectors.toMap(
                    item -> item.documentType() + ":" + item.documentId(),
                    item -> item,
                    (left, right) -> left))
            .values()
            .stream()
            .toList();

    List<AccountingTransactionAuditDetailDto.SettlementAllocation> allocationRows =
        allocations.stream()
            .map(
                allocation -> {
                  SettlementAuditMemoDecoder.DecodedSettlementAuditMemo memoParts =
                      settlementAuditMemoDecoder.decode(allocation);
                  return new AccountingTransactionAuditDetailDto.SettlementAllocation(
                      allocation.getId(),
                      allocation.getPartnerType() != null
                          ? allocation.getPartnerType().name()
                          : null,
                      allocation.getDealer() != null ? allocation.getDealer().getId() : null,
                      allocation.getSupplier() != null ? allocation.getSupplier().getId() : null,
                      allocation.getInvoice() != null ? allocation.getInvoice().getId() : null,
                      allocation.getPurchase() != null ? allocation.getPurchase().getId() : null,
                      allocation.getAllocationAmount(),
                      allocation.getDiscountAmount(),
                      allocation.getWriteOffAmount(),
                      allocation.getFxDifferenceAmount(),
                      memoParts.applicationType().name(),
                      memoParts.memo(),
                      allocation.getSettlementDate());
                })
            .toList();

    List<AccountingTransactionAuditDetailDto.EventTrailItem> eventTrail =
        events.stream()
            .map(
                event ->
                    new AccountingTransactionAuditDetailDto.EventTrailItem(
                        event.getId(),
                        event.getEventType() != null ? event.getEventType().name() : null,
                        event.getAggregateType(),
                        event.getAggregateId(),
                        event.getSequenceNumber(),
                        event.getEventTimestamp(),
                        event.getEffectiveDate(),
                        event.getAccountId(),
                        event.getAccountCode(),
                        event.getDebitAmount(),
                        event.getCreditAmount(),
                        event.getBalanceBefore(),
                        event.getBalanceAfter(),
                        event.getDescription(),
                        event.getUserId(),
                        event.getCorrelationId()))
            .toList();

    LinkedBusinessReferenceDto drivingDocument =
        referenceChainService.resolveDrivingDocument(
            invoice.orElse(null), purchase.orElse(null), allocations);
    List<LinkedBusinessReferenceDto> linkedReferenceChain =
        referenceChainService.buildReferenceChain(
            entry, invoice.orElse(null), purchase.orElse(null), allocations, drivingDocument);

    return new AccountingTransactionAuditDetailDto(
        entry.getId(),
        entry.getPublicId(),
        entry.getReferenceNumber(),
        entry.getEntryDate(),
        entry.getStatus() != null ? entry.getStatus().name() : null,
        module,
        transactionType,
        entry.getMemo(),
        entry.getDealer() != null ? entry.getDealer().getId() : null,
        entry.getDealer() != null ? entry.getDealer().getName() : null,
        entry.getSupplier() != null ? entry.getSupplier().getId() : null,
        entry.getSupplier() != null ? entry.getSupplier().getName() : null,
        entry.getAccountingPeriod() != null ? entry.getAccountingPeriod().getId() : null,
        entry.getAccountingPeriod() != null ? entry.getAccountingPeriod().getLabel() : null,
        entry.getAccountingPeriod() != null && entry.getAccountingPeriod().getStatus() != null
            ? entry.getAccountingPeriod().getStatus().name()
            : null,
        entry.getReversalOf() != null ? entry.getReversalOf().getId() : null,
        entry.getReversalEntry() != null ? entry.getReversalEntry().getId() : null,
        entry.getCorrectionType() != null ? entry.getCorrectionType().name() : null,
        entry.getCorrectionReason(),
        entry.getVoidReason(),
        totalDebit,
        totalCredit,
        consistency.status(),
        consistency.notes(),
        lines,
        dedupedLinkedDocuments,
        allocationRows,
        eventTrail,
        drivingDocument,
        linkedReferenceChain,
        entry.getCreatedAt(),
        entry.getUpdatedAt(),
        entry.getPostedAt(),
        entry.getCreatedBy(),
        entry.getPostedBy(),
        entry.getLastModifiedBy());
  }

  private Optional<Invoice> findInvoiceByJournalEntry(Company company, JournalEntry journalEntry) {
    Optional<Invoice> direct = invoiceRepository.findByCompanyAndJournalEntry(company, journalEntry);
    if (direct.isPresent()) {
      return direct;
    }
    return invoiceRepository.findByCompanyAndJournalEntry_ReversalOf(company, journalEntry);
  }

  private Optional<RawMaterialPurchase> findPurchaseByJournalEntry(
      Company company, JournalEntry journalEntry) {
    Optional<RawMaterialPurchase> direct =
        rawMaterialPurchaseRepository.findByCompanyAndJournalEntry(company, journalEntry);
    if (direct.isPresent()) {
      return direct;
    }
    return rawMaterialPurchaseRepository.findByCompanyAndJournalEntry_ReversalOf(
        company, journalEntry);
  }
}
