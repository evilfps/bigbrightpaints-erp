package com.bigbrightpaints.erp.modules.accounting.service;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Objects;

import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import com.bigbrightpaints.erp.core.exception.ApplicationException;
import com.bigbrightpaints.erp.core.exception.ErrorCode;
import com.bigbrightpaints.erp.core.util.MoneyUtils;
import com.bigbrightpaints.erp.modules.accounting.domain.Account;
import com.bigbrightpaints.erp.modules.accounting.domain.JournalEntry;
import com.bigbrightpaints.erp.modules.accounting.domain.PartnerSettlementAllocation;
import com.bigbrightpaints.erp.modules.accounting.domain.PartnerSettlementAllocationRepository;
import com.bigbrightpaints.erp.modules.accounting.dto.JournalEntryDto;
import com.bigbrightpaints.erp.modules.accounting.dto.PartnerSettlementResponse;
import com.bigbrightpaints.erp.modules.accounting.dto.SettlementAllocationApplication;
import com.bigbrightpaints.erp.modules.company.domain.Company;
import com.bigbrightpaints.erp.modules.invoice.domain.Invoice;
import com.bigbrightpaints.erp.modules.invoice.service.InvoiceSettlementPolicy;
import com.bigbrightpaints.erp.modules.purchasing.domain.RawMaterialPurchase;
import com.bigbrightpaints.erp.modules.purchasing.domain.Supplier;

@Service
class SettlementOutcomeService {

  private static final BigDecimal ALLOCATION_TOLERANCE = new BigDecimal("0.01");
  private static final String SETTLEMENT_APPLICATION_PREFIX = "[SETTLEMENT-APPLICATION:";

  private final PartnerSettlementAllocationRepository settlementAllocationRepository;
  private final CompanyScopedAccountingLookupService accountingLookupService;
  private final AccountingDtoMapperService dtoMapperService;
  private final DealerLedgerService dealerLedgerService;
  private final InvoiceSettlementPolicy invoiceSettlementPolicy;

  SettlementOutcomeService(
      PartnerSettlementAllocationRepository settlementAllocationRepository,
      CompanyScopedAccountingLookupService accountingLookupService,
      AccountingDtoMapperService dtoMapperService,
      DealerLedgerService dealerLedgerService,
      InvoiceSettlementPolicy invoiceSettlementPolicy) {
    this.settlementAllocationRepository = settlementAllocationRepository;
    this.accountingLookupService = accountingLookupService;
    this.dtoMapperService = dtoMapperService;
    this.dealerLedgerService = dealerLedgerService;
    this.invoiceSettlementPolicy = invoiceSettlementPolicy;
  }

  PartnerSettlementResponse buildDealerSettlementResponse(
      List<PartnerSettlementAllocation> existing) {
    if (existing == null || existing.isEmpty()) {
      throw new ApplicationException(
          ErrorCode.INTERNAL_CONCURRENCY_FAILURE,
          "Settlement allocations missing for idempotent response");
    }
    JournalEntry entry = existing.getFirst().getJournalEntry();
    BigDecimal applied =
        existing.stream()
            .map(PartnerSettlementAllocation::getAllocationAmount)
            .reduce(BigDecimal.ZERO, BigDecimal::add);
    BigDecimal discountSum =
        existing.stream()
            .map(PartnerSettlementAllocation::getDiscountAmount)
            .reduce(BigDecimal.ZERO, BigDecimal::add);
    BigDecimal writeOffSum =
        existing.stream()
            .map(PartnerSettlementAllocation::getWriteOffAmount)
            .reduce(BigDecimal.ZERO, BigDecimal::add);
    BigDecimal fxGainSum =
        existing.stream()
            .map(PartnerSettlementAllocation::getFxDifferenceAmount)
            .filter(v -> v.compareTo(BigDecimal.ZERO) > 0)
            .reduce(BigDecimal.ZERO, BigDecimal::add);
    BigDecimal fxLossSum =
        existing.stream()
            .map(PartnerSettlementAllocation::getFxDifferenceAmount)
            .filter(v -> v.compareTo(BigDecimal.ZERO) < 0)
            .map(BigDecimal::abs)
            .reduce(BigDecimal.ZERO, BigDecimal::add);
    BigDecimal cashAmount =
        applied.add(fxGainSum).subtract(fxLossSum).subtract(discountSum).subtract(writeOffSum);
    for (PartnerSettlementAllocation row : existing) {
      if (row.getInvoice() != null) {
        dealerLedgerService.syncInvoiceLedger(row.getInvoice(), row.getSettlementDate());
      }
    }
    return new PartnerSettlementResponse(
        dtoMapperService.toJournalEntryDto(entry),
        applied,
        cashAmount,
        discountSum,
        writeOffSum,
        fxGainSum,
        fxLossSum,
        toSettlementAllocationSummaries(existing));
  }

  PartnerSettlementResponse buildSupplierSettlementResponse(
      List<PartnerSettlementAllocation> existing) {
    if (existing == null || existing.isEmpty()) {
      throw new ApplicationException(
          ErrorCode.INTERNAL_CONCURRENCY_FAILURE,
          "Settlement allocations missing for idempotent response");
    }
    JournalEntry entry = existing.getFirst().getJournalEntry();
    BigDecimal applied =
        existing.stream()
            .map(PartnerSettlementAllocation::getAllocationAmount)
            .reduce(BigDecimal.ZERO, BigDecimal::add);
    BigDecimal discountSum =
        existing.stream()
            .map(PartnerSettlementAllocation::getDiscountAmount)
            .reduce(BigDecimal.ZERO, BigDecimal::add);
    BigDecimal writeOffSum =
        existing.stream()
            .map(PartnerSettlementAllocation::getWriteOffAmount)
            .reduce(BigDecimal.ZERO, BigDecimal::add);
    BigDecimal fxGainSum =
        existing.stream()
            .map(PartnerSettlementAllocation::getFxDifferenceAmount)
            .filter(v -> v.compareTo(BigDecimal.ZERO) > 0)
            .reduce(BigDecimal.ZERO, BigDecimal::add);
    BigDecimal fxLossSum =
        existing.stream()
            .map(PartnerSettlementAllocation::getFxDifferenceAmount)
            .filter(v -> v.compareTo(BigDecimal.ZERO) < 0)
            .map(BigDecimal::abs)
            .reduce(BigDecimal.ZERO, BigDecimal::add);
    BigDecimal cashAmount =
        applied.add(fxLossSum).subtract(fxGainSum).subtract(discountSum).subtract(writeOffSum);
    return new PartnerSettlementResponse(
        dtoMapperService.toJournalEntryDto(entry),
        applied,
        cashAmount,
        discountSum,
        writeOffSum,
        fxGainSum,
        fxLossSum,
        toSettlementAllocationSummaries(existing));
  }

  PartnerSettlementResponse buildAutoSettlementResponse(
      Company company, JournalEntryDto journalEntry) {
    JournalEntry persistedEntry =
        accountingLookupService.requireJournalEntry(company, journalEntry.id());
    List<PartnerSettlementAllocation> allocations =
        settlementAllocationRepository.findByCompanyAndJournalEntryOrderByCreatedAtAsc(
            company, persistedEntry);
    BigDecimal totalApplied =
        allocations.stream()
            .map(PartnerSettlementAllocation::getAllocationAmount)
            .map(MoneyUtils::zeroIfNull)
            .reduce(BigDecimal.ZERO, BigDecimal::add);
    return new PartnerSettlementResponse(
        journalEntry,
        totalApplied,
        totalApplied,
        BigDecimal.ZERO,
        BigDecimal.ZERO,
        BigDecimal.ZERO,
        BigDecimal.ZERO,
        toSettlementAllocationSummaries(allocations));
  }

  List<PartnerSettlementResponse.Allocation> toSettlementAllocationSummaries(
      List<PartnerSettlementAllocation> allocations) {
    return allocations.stream()
        .map(
            row -> {
              SettlementMemoParts memoParts =
                  row.getInvoice() != null || row.getPurchase() != null
                      ? new SettlementMemoParts(
                          SettlementAllocationApplication.DOCUMENT,
                          normalizeVisibleMemo(row.getMemo()))
                      : decodeSettlementAllocationMemo(row.getMemo());
              return new PartnerSettlementResponse.Allocation(
                  row.getInvoice() != null ? row.getInvoice().getId() : null,
                  row.getPurchase() != null ? row.getPurchase().getId() : null,
                  row.getAllocationAmount(),
                  row.getDiscountAmount(),
                  row.getWriteOffAmount(),
                  row.getFxDifferenceAmount(),
                  memoParts.applicationType(),
                  memoParts.memo());
            })
        .toList();
  }

  void enforceSettlementCurrency(Company company, Invoice invoice) {
    if (company == null || invoice == null) {
      return;
    }
    String settlementCurrency = company.getBaseCurrency();
    if (StringUtils.hasText(settlementCurrency)
        && invoice.getCurrency() != null
        && !invoice.getCurrency().equalsIgnoreCase(settlementCurrency)) {
      throw new ApplicationException(
          ErrorCode.VALIDATION_INVALID_INPUT,
          String.format(
              "Cannot settle invoice %s in %s with settlement currency %s",
              invoice.getInvoiceNumber(), invoice.getCurrency(), settlementCurrency));
    }
  }

  void updatePurchaseStatus(RawMaterialPurchase purchase) {
    if (purchase == null) {
      return;
    }
    String status = purchase.getStatus();
    if (status != null
        && ("VOID".equalsIgnoreCase(status) || "REVERSED".equalsIgnoreCase(status))) {
      return;
    }
    BigDecimal total = MoneyUtils.zeroIfNull(purchase.getTotalAmount());
    BigDecimal outstanding = MoneyUtils.zeroIfNull(purchase.getOutstandingAmount());
    if (outstanding.compareTo(BigDecimal.ZERO) <= 0) {
      purchase.setStatus("PAID");
    } else if (total.compareTo(BigDecimal.ZERO) > 0 && outstanding.compareTo(total) < 0) {
      purchase.setStatus("PARTIAL");
    } else {
      purchase.setStatus("POSTED");
    }
  }

  void enforceSupplierSettlementPostingParity(
      Company company, Long supplierId, RawMaterialPurchase purchase, String idempotencyKey) {
    if (purchase == null || purchase.getId() == null) {
      return;
    }
    Long purchaseId = purchase.getId();
    Supplier purchaseSupplier = purchase.getSupplier();
    if (purchaseSupplier == null
        || purchaseSupplier.getId() == null
        || (supplierId != null && !Objects.equals(purchaseSupplier.getId(), supplierId))) {
      throw supplierSettlementPostingDrift(
          "Supplier settlement purchase posting drift: purchase supplier context mismatch",
          idempotencyKey,
          supplierId,
          purchaseId);
    }
    JournalEntry purchaseJournal = purchase.getJournalEntry();
    if (purchaseJournal == null) {
      throw supplierSettlementPostingDrift(
          "Supplier settlement purchase posting drift: purchase journal link is missing",
          idempotencyKey,
          supplierId,
          purchaseId);
    }
    Supplier journalSupplier = purchaseJournal.getSupplier();
    if (journalSupplier == null
        || journalSupplier.getId() == null
        || !Objects.equals(journalSupplier.getId(), purchaseSupplier.getId())) {
      throw supplierSettlementPostingDrift(
          "Supplier settlement purchase posting drift: purchase journal supplier mismatch",
          idempotencyKey,
          supplierId,
          purchaseId);
    }
    Account payableAccount = purchaseSupplier.getPayableAccount();
    if (payableAccount == null || payableAccount.getId() == null) {
      throw supplierSettlementPostingDrift(
          "Supplier settlement purchase posting drift: supplier payable account is missing",
          idempotencyKey,
          supplierId,
          purchaseId);
    }
    BigDecimal payableCredits = BigDecimal.ZERO;
    for (var line : purchaseJournal.getLines()) {
      if (line == null || line.getAccount() == null || line.getAccount().getId() == null) {
        continue;
      }
      if (!Objects.equals(line.getAccount().getId(), payableAccount.getId())) {
        continue;
      }
      payableCredits =
          payableCredits.add(
              MoneyUtils.zeroIfNull(line.getCredit())
                  .subtract(MoneyUtils.zeroIfNull(line.getDebit())));
    }
    if (payableCredits.compareTo(BigDecimal.ZERO) <= 0) {
      throw supplierSettlementPostingDrift(
          "Supplier settlement purchase posting drift: purchase journal has no payable exposure",
          idempotencyKey,
          supplierId,
          purchaseId);
    }
  }

  void applyCreditNoteToInvoice(
      Invoice invoice,
      BigDecimal creditAmount,
      BigDecimal totalCredited,
      String reference,
      LocalDate entryDate) {
    if (invoice == null) {
      return;
    }
    BigDecimal amount = MoneyUtils.zeroIfNull(creditAmount);
    if (amount.compareTo(BigDecimal.ZERO) <= 0) {
      return;
    }
    if (!StringUtils.hasText(reference)) {
      throw new ApplicationException(
          ErrorCode.VALIDATION_INVALID_INPUT, "Credit note reference is required");
    }
    if (invoice.getPaymentReferences().contains(reference)) {
      dealerLedgerService.syncInvoiceLedger(invoice, entryDate);
      return;
    }
    BigDecimal currentOutstanding = MoneyUtils.zeroIfNull(invoice.getOutstandingAmount());
    if (amount.subtract(currentOutstanding).compareTo(ALLOCATION_TOLERANCE) > 0) {
      throw new ApplicationException(
              ErrorCode.VALIDATION_INVALID_INPUT,
              "Credit note exceeds remaining invoice outstanding amount")
          .withDetail("invoiceId", invoice.getId())
          .withDetail("outstandingAmount", currentOutstanding)
          .withDetail("requested", amount);
    }
    BigDecimal newOutstanding = currentOutstanding.subtract(amount);
    invoice.setOutstandingAmount(newOutstanding);
    invoice.addPaymentReference(reference);
    invoiceSettlementPolicy.updateStatusFromOutstanding(invoice, newOutstanding);
    BigDecimal totalAmount = MoneyUtils.zeroIfNull(invoice.getTotalAmount());
    BigDecimal credited = totalCredited != null ? totalCredited : BigDecimal.ZERO;
    if (credited.compareTo(totalAmount) >= 0 && newOutstanding.compareTo(BigDecimal.ZERO) <= 0) {
      invoice.setStatus(InvoiceSettlementPolicy.InvoiceStatus.VOID.name());
    }
    dealerLedgerService.syncInvoiceLedger(invoice, entryDate);
  }

  void applyBadDebtWriteOffToInvoice(
      Invoice invoice, BigDecimal writeOffAmount, String reference, LocalDate entryDate) {
    if (invoice == null) {
      return;
    }
    BigDecimal amount = MoneyUtils.zeroIfNull(writeOffAmount);
    if (amount.compareTo(BigDecimal.ZERO) <= 0) {
      return;
    }
    if (!StringUtils.hasText(reference)) {
      throw new ApplicationException(
          ErrorCode.VALIDATION_INVALID_INPUT, "Bad debt write-off reference is required");
    }
    if (invoice.getPaymentReferences().contains(reference)) {
      invoiceSettlementPolicy.markWrittenOff(invoice);
      dealerLedgerService.syncInvoiceLedger(invoice, entryDate);
      return;
    }
    BigDecimal currentOutstanding = MoneyUtils.zeroIfNull(invoice.getOutstandingAmount());
    if (amount.subtract(currentOutstanding).compareTo(ALLOCATION_TOLERANCE) > 0) {
      throw new ApplicationException(
              ErrorCode.VALIDATION_INVALID_INPUT,
              "Bad debt write-off exceeds remaining invoice outstanding amount")
          .withDetail("invoiceId", invoice.getId())
          .withDetail("outstandingAmount", currentOutstanding)
          .withDetail("requested", amount);
    }
    BigDecimal newOutstanding = currentOutstanding.subtract(amount);
    invoice.setOutstandingAmount(newOutstanding);
    invoice.addPaymentReference(reference);
    invoiceSettlementPolicy.markWrittenOff(invoice);
    dealerLedgerService.syncInvoiceLedger(invoice, entryDate);
  }

  void applyDebitNoteToPurchase(
      RawMaterialPurchase purchase, BigDecimal debitAmount, BigDecimal totalDebited) {
    if (purchase == null) {
      return;
    }
    BigDecimal amount = MoneyUtils.zeroIfNull(debitAmount);
    if (amount.compareTo(BigDecimal.ZERO) <= 0) {
      return;
    }
    BigDecimal currentOutstanding = MoneyUtils.zeroIfNull(purchase.getOutstandingAmount());
    if (amount.subtract(currentOutstanding).compareTo(ALLOCATION_TOLERANCE) > 0) {
      throw new ApplicationException(
              ErrorCode.VALIDATION_INVALID_INPUT,
              "Debit note exceeds remaining purchase outstanding amount")
          .withDetail("purchaseId", purchase.getId())
          .withDetail("outstandingAmount", currentOutstanding)
          .withDetail("requested", amount);
    }
    BigDecimal newOutstanding = currentOutstanding.subtract(amount);
    purchase.setOutstandingAmount(newOutstanding);
    BigDecimal totalAmount = MoneyUtils.zeroIfNull(purchase.getTotalAmount());
    BigDecimal debited = totalDebited != null ? totalDebited : BigDecimal.ZERO;
    if (totalAmount.compareTo(BigDecimal.ZERO) > 0 && debited.compareTo(totalAmount) >= 0) {
      purchase.setStatus("VOID");
    } else {
      updatePurchaseStatus(purchase);
    }
  }

  private ApplicationException supplierSettlementPostingDrift(
      String message, String idempotencyKey, Long supplierId, Long purchaseId) {
    ApplicationException exception =
        new ApplicationException(ErrorCode.CONCURRENCY_CONFLICT, message)
            .withDetail("idempotencyKey", idempotencyKey)
            .withDetail("supplierId", supplierId)
            .withDetail("purchaseId", purchaseId);
    return exception;
  }

  private SettlementMemoParts decodeSettlementAllocationMemo(String memo) {
    String normalized = normalizeVisibleMemo(memo);
    if (!StringUtils.hasText(normalized) || !normalized.startsWith(SETTLEMENT_APPLICATION_PREFIX)) {
      return new SettlementMemoParts(SettlementAllocationApplication.ON_ACCOUNT, normalized);
    }
    int closingBracket = normalized.indexOf(']');
    if (closingBracket <= SETTLEMENT_APPLICATION_PREFIX.length()) {
      return new SettlementMemoParts(SettlementAllocationApplication.ON_ACCOUNT, normalized);
    }
    String token =
        normalized.substring(SETTLEMENT_APPLICATION_PREFIX.length(), closingBracket).trim();
    SettlementAllocationApplication resolved;
    try {
      resolved = SettlementAllocationApplication.valueOf(token);
    } catch (IllegalArgumentException ex) {
      resolved = SettlementAllocationApplication.ON_ACCOUNT;
    }
    String visibleMemo = normalizeVisibleMemo(normalized.substring(closingBracket + 1));
    return new SettlementMemoParts(resolved, visibleMemo);
  }

  private String normalizeVisibleMemo(String memo) {
    return StringUtils.hasText(memo) ? memo.trim() : null;
  }

  private record SettlementMemoParts(
      SettlementAllocationApplication applicationType, String memo) {}
}
