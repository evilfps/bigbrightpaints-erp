package com.bigbrightpaints.erp.modules.accounting.service;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import com.bigbrightpaints.erp.modules.accounting.domain.AccountingPeriod;
import com.bigbrightpaints.erp.modules.accounting.domain.JournalEntry;
import com.bigbrightpaints.erp.modules.accounting.dto.AutoSettlementRequest;
import com.bigbrightpaints.erp.modules.accounting.dto.DealerReceiptRequest;
import com.bigbrightpaints.erp.modules.accounting.dto.DealerReceiptSplitRequest;
import com.bigbrightpaints.erp.modules.accounting.dto.JournalCreationRequest;
import com.bigbrightpaints.erp.modules.accounting.dto.JournalEntryDto;
import com.bigbrightpaints.erp.modules.accounting.dto.JournalEntryRequest;
import com.bigbrightpaints.erp.modules.accounting.dto.ManualJournalRequest;
import com.bigbrightpaints.erp.modules.accounting.dto.PartnerSettlementRequest;
import com.bigbrightpaints.erp.modules.accounting.dto.PartnerSettlementResponse;
import com.bigbrightpaints.erp.modules.accounting.dto.SupplierPaymentRequest;
import com.bigbrightpaints.erp.modules.accounting.event.AccountCacheInvalidatedEvent;
import com.bigbrightpaints.erp.modules.hr.dto.PayrollPaymentRequest;

@Service
public class AccountingFacade {

  public static final String MANUAL_REFERENCE_PREFIX = "MANUAL-";
  private static final Set<String> RESERVED_REFERENCE_PREFIXES =
      Set.of(
          "JRN-",
          "INV-",
          "SALE-",
          "COGS-",
          "RMP-",
          "PRN-",
          "PAYROLL-",
          "RM-",
          "ADJ-",
          "OPEN-STOCK-",
          "CAL-",
          "INVJ-",
          "RCPT-",
          "SUP-",
          "COST-ALLOC-",
          "CRN-",
          "DBN-",
          "DISPATCH-");

  private final AccountingService accountingService;
  private final DealerReceiptService dealerReceiptService;
  private final SalesJournalFacadeOperations salesJournalOperations;
  private final SalesReturnJournalFacadeOperations salesReturnJournalOperations;
  private final PurchaseJournalFacadeOperations purchaseJournalOperations;
  private final FactoryJournalFacadeOperations factoryJournalOperations;
  private final InventoryAdjustmentFacadeOperations inventoryAdjustmentOperations;
  private final ManualJournalFacadeOperations manualJournalOperations;
  private final AccountingFacadeAccountResolver accountResolver;
  private final PayrollAccountingService payrollAccountingService;

  public AccountingFacade(
      AccountingService accountingService,
      DealerReceiptService dealerReceiptService,
      SalesJournalFacadeOperations salesJournalOperations,
      SalesReturnJournalFacadeOperations salesReturnJournalOperations,
      PurchaseJournalFacadeOperations purchaseJournalOperations,
      FactoryJournalFacadeOperations factoryJournalOperations,
      InventoryAdjustmentFacadeOperations inventoryAdjustmentOperations,
      ManualJournalFacadeOperations manualJournalOperations,
      AccountingFacadeAccountResolver accountResolver,
      PayrollAccountingService payrollAccountingService) {
    this.accountingService = accountingService;
    this.dealerReceiptService = dealerReceiptService;
    this.salesJournalOperations = salesJournalOperations;
    this.salesReturnJournalOperations = salesReturnJournalOperations;
    this.purchaseJournalOperations = purchaseJournalOperations;
    this.factoryJournalOperations = factoryJournalOperations;
    this.inventoryAdjustmentOperations = inventoryAdjustmentOperations;
    this.manualJournalOperations = manualJournalOperations;
    this.accountResolver = accountResolver;
    this.payrollAccountingService = payrollAccountingService;
  }

  public static boolean isReservedReferenceNamespace(String referenceNumber) {
    if (!StringUtils.hasText(referenceNumber)) {
      return false;
    }
    String normalized = referenceNumber.trim().toUpperCase(Locale.ROOT);
    if (normalized.startsWith(MANUAL_REFERENCE_PREFIX)) {
      return false;
    }
    if (normalized.contains("-INV-")) {
      return true;
    }
    for (String prefix : RESERVED_REFERENCE_PREFIXES) {
      if (normalized.startsWith(prefix)) {
        return true;
      }
    }
    return false;
  }

  public JournalEntryDto postSalesJournal(
      Long dealerId,
      String orderNumber,
      LocalDate entryDate,
      String memo,
      Map<Long, BigDecimal> revenueLines,
      Map<Long, BigDecimal> taxLines,
      BigDecimal totalAmount,
      String referenceNumber) {
    return salesJournalOperations.postSalesJournal(
        dealerId,
        orderNumber,
        entryDate,
        memo,
        revenueLines,
        taxLines,
        totalAmount,
        referenceNumber);
  }

  public JournalEntryDto postSalesJournal(
      Long dealerId,
      String orderNumber,
      LocalDate entryDate,
      String memo,
      Map<Long, BigDecimal> revenueLines,
      Map<Long, BigDecimal> taxLines,
      Map<Long, BigDecimal> discountLines,
      BigDecimal totalAmount,
      String referenceNumber) {
    return salesJournalOperations.postSalesJournal(
        dealerId,
        orderNumber,
        entryDate,
        memo,
        revenueLines,
        taxLines,
        discountLines,
        totalAmount,
        referenceNumber);
  }

  public JournalEntryDto postSalesJournal(
      Long dealerId,
      String orderNumber,
      LocalDate entryDate,
      String memo,
      Map<Long, BigDecimal> revenueLines,
      Map<Long, BigDecimal> taxLines,
      Map<Long, BigDecimal> discountLines,
      JournalCreationRequest.GstBreakdown gstBreakdown,
      BigDecimal totalAmount,
      String referenceNumber) {
    return salesJournalOperations.postSalesJournal(
        dealerId,
        orderNumber,
        entryDate,
        memo,
        revenueLines,
        taxLines,
        discountLines,
        gstBreakdown,
        totalAmount,
        referenceNumber);
  }

  public JournalEntryDto postPurchaseJournal(
      Long supplierId,
      String invoiceNumber,
      LocalDate invoiceDate,
      String memo,
      Map<Long, BigDecimal> inventoryLines,
      BigDecimal totalAmount) {
    return purchaseJournalOperations.postPurchaseJournal(
        supplierId, invoiceNumber, invoiceDate, memo, inventoryLines, totalAmount);
  }

  public JournalEntryDto postPurchaseJournal(
      Long supplierId,
      String invoiceNumber,
      LocalDate invoiceDate,
      String memo,
      Map<Long, BigDecimal> inventoryLines,
      Map<Long, BigDecimal> taxLines,
      BigDecimal totalAmount,
      String referenceNumber) {
    return purchaseJournalOperations.postPurchaseJournal(
        supplierId,
        invoiceNumber,
        invoiceDate,
        memo,
        inventoryLines,
        taxLines,
        totalAmount,
        referenceNumber);
  }

  public JournalEntryDto postPurchaseJournal(
      Long supplierId,
      String invoiceNumber,
      LocalDate invoiceDate,
      String memo,
      Map<Long, BigDecimal> inventoryLines,
      Map<Long, BigDecimal> taxLines,
      JournalCreationRequest.GstBreakdown gstBreakdown,
      BigDecimal totalAmount,
      String referenceNumber) {
    return purchaseJournalOperations.postPurchaseJournal(
        supplierId,
        invoiceNumber,
        invoiceDate,
        memo,
        inventoryLines,
        taxLines,
        gstBreakdown,
        totalAmount,
        referenceNumber);
  }

  public JournalEntryDto postPurchaseReturn(
      Long supplierId,
      String referenceNumber,
      LocalDate returnDate,
      String memo,
      Map<Long, BigDecimal> inventoryCredits,
      BigDecimal totalAmount) {
    return purchaseJournalOperations.postPurchaseReturn(
        supplierId, referenceNumber, returnDate, memo, inventoryCredits, totalAmount);
  }

  public JournalEntryDto postPurchaseReturn(
      Long supplierId,
      String referenceNumber,
      LocalDate returnDate,
      String memo,
      Map<Long, BigDecimal> inventoryCredits,
      Map<Long, BigDecimal> taxCredits,
      BigDecimal totalAmount) {
    return purchaseJournalOperations.postPurchaseReturn(
        supplierId, referenceNumber, returnDate, memo, inventoryCredits, taxCredits, totalAmount);
  }

  public JournalEntryDto postPurchaseReturn(
      Long supplierId,
      String referenceNumber,
      LocalDate returnDate,
      String memo,
      Map<Long, BigDecimal> inventoryCredits,
      Map<Long, BigDecimal> taxCredits,
      JournalCreationRequest.GstBreakdown gstBreakdown,
      BigDecimal totalAmount) {
    return purchaseJournalOperations.postPurchaseReturn(
        supplierId,
        referenceNumber,
        returnDate,
        memo,
        inventoryCredits,
        taxCredits,
        gstBreakdown,
        totalAmount);
  }

  public JournalEntryDto postPackingJournal(
      String reference,
      LocalDate entryDate,
      String memo,
      List<JournalEntryRequest.JournalLineRequest> lines) {
    return factoryJournalOperations.postPackingJournal(reference, entryDate, memo, lines);
  }

  public JournalEntryDto postCostAllocation(
      String batchCode,
      Long finishedGoodsAcctId,
      Long laborExpenseAcctId,
      Long overheadExpenseAcctId,
      BigDecimal laborCost,
      BigDecimal overheadCost,
      String notes) {
    return factoryJournalOperations.postCostAllocation(
        batchCode,
        finishedGoodsAcctId,
        laborExpenseAcctId,
        overheadExpenseAcctId,
        laborCost,
        overheadCost,
        notes);
  }

  public JournalEntryDto postCOGS(
      String referenceId,
      Long dealerId,
      Long cogsAccountId,
      Long inventoryAcctId,
      BigDecimal cost,
      String memo) {
    return factoryJournalOperations.postCOGS(
        referenceId, dealerId, cogsAccountId, inventoryAcctId, cost, memo);
  }

  public JournalEntryDto postCOGS(
      String referenceId, Long cogsAccountId, Long inventoryAcctId, BigDecimal cost, String memo) {
    return factoryJournalOperations.postCOGS(
        referenceId, cogsAccountId, inventoryAcctId, cost, memo);
  }

  public JournalEntryDto postCogsJournal(
      String referenceId,
      Long dealerId,
      LocalDate entryDate,
      String memo,
      List<JournalEntryRequest.JournalLineRequest> lines) {
    return factoryJournalOperations.postCogsJournal(referenceId, dealerId, entryDate, memo, lines);
  }

  public JournalEntryDto postCostVarianceAllocation(
      String batchCode,
      String periodKey,
      LocalDate entryDate,
      Long finishedGoodsAcctId,
      Long laborExpenseAcctId,
      Long overheadExpenseAcctId,
      BigDecimal laborVariance,
      BigDecimal overheadVariance,
      String notes) {
    return factoryJournalOperations.postCostVarianceAllocation(
        batchCode,
        periodKey,
        entryDate,
        finishedGoodsAcctId,
        laborExpenseAcctId,
        overheadExpenseAcctId,
        laborVariance,
        overheadVariance,
        notes);
  }

  public Optional<String> findExistingCostVarianceReference(String batchCode, String periodKey) {
    return factoryJournalOperations.findExistingCostVarianceReference(batchCode, periodKey);
  }

  public boolean hasCogsJournalFor(String referenceId) {
    return factoryJournalOperations.hasCogsJournalFor(referenceId);
  }

  public JournalEntryDto postSalesReturn(
      Long dealerId,
      String invoiceNumber,
      Map<Long, BigDecimal> returnLines,
      BigDecimal totalAmount,
      String reason) {
    return salesReturnJournalOperations.postSalesReturn(
        dealerId, invoiceNumber, returnLines, totalAmount, reason);
  }

  public JournalEntryDto postInventoryAdjustment(
      String adjustmentType,
      String referenceId,
      Long inventoryAcctId,
      Long varianceAcctId,
      BigDecimal amount,
      String memo) {
    return inventoryAdjustmentOperations.postInventoryAdjustment(
        adjustmentType, referenceId, inventoryAcctId, varianceAcctId, amount, memo);
  }

  public JournalEntryDto postInventoryAdjustment(
      String adjustmentType,
      String referenceId,
      Long varianceAcctId,
      Map<Long, BigDecimal> inventoryLines,
      boolean increaseInventory,
      boolean adminOverride,
      String memo) {
    return inventoryAdjustmentOperations.postInventoryAdjustment(
        adjustmentType,
        referenceId,
        varianceAcctId,
        inventoryLines,
        increaseInventory,
        adminOverride,
        memo);
  }

  public JournalEntryDto postInventoryAdjustment(
      String adjustmentType,
      String referenceId,
      Long varianceAcctId,
      Map<Long, BigDecimal> inventoryLines,
      boolean increaseInventory,
      boolean adminOverride,
      String memo,
      LocalDate entryDate) {
    return inventoryAdjustmentOperations.postInventoryAdjustment(
        adjustmentType,
        referenceId,
        varianceAcctId,
        inventoryLines,
        increaseInventory,
        adminOverride,
        memo,
        entryDate);
  }

  public JournalEntryDto createStandardJournal(JournalCreationRequest request) {
    return accountingService.createStandardJournal(request);
  }

  public JournalEntryDto postPayrollRun(
      String runNumber,
      Long runId,
      LocalDate postingDate,
      String memo,
      List<JournalEntryRequest.JournalLineRequest> lines) {
    return payrollAccountingService.postPayrollRun(runNumber, runId, postingDate, memo, lines);
  }

  public JournalEntryDto recordDealerReceipt(DealerReceiptRequest request) {
    return dealerReceiptService.recordDealerReceipt(request);
  }

  public JournalEntryDto recordDealerReceiptSplit(DealerReceiptSplitRequest request) {
    return dealerReceiptService.recordDealerReceiptSplit(request);
  }

  public JournalEntryDto recordSupplierPayment(SupplierPaymentRequest request) {
    return accountingService.recordSupplierPayment(request);
  }

  public PartnerSettlementResponse settleDealerInvoices(PartnerSettlementRequest request) {
    return accountingService.settleDealerInvoices(request);
  }

  public PartnerSettlementResponse autoSettleDealer(Long dealerId, AutoSettlementRequest request) {
    return accountingService.autoSettleDealer(dealerId, request);
  }

  public PartnerSettlementResponse settleSupplierInvoices(PartnerSettlementRequest request) {
    return accountingService.settleSupplierInvoices(request);
  }

  public PartnerSettlementResponse autoSettleSupplier(
      Long supplierId, AutoSettlementRequest request) {
    return accountingService.autoSettleSupplier(supplierId, request);
  }

  public JournalEntryDto reverseClosingEntryForPeriodReopen(
      JournalEntry entry, AccountingPeriod period, String reason) {
    return accountingService.reverseClosingEntryForPeriodReopen(entry, period, reason);
  }

  public JournalEntryDto createManualJournal(ManualJournalRequest request) {
    return manualJournalOperations.createManualJournal(request);
  }

  public JournalEntryDto createManualJournalEntry(
      JournalEntryRequest request, String idempotencyKey) {
    return manualJournalOperations.createManualJournalEntry(request, idempotencyKey);
  }

  public JournalEntryDto recordPayrollPayment(PayrollPaymentRequest request) {
    return payrollAccountingService.recordPayrollPayment(request);
  }

  public void clearAccountCache() {
    accountResolver.clearAccountCache();
  }

  public void clearAccountCache(Long companyId) {
    accountResolver.clearAccountCache(companyId);
  }

  @EventListener
  public void handleAccountCacheInvalidated(AccountCacheInvalidatedEvent event) {
    clearAccountCache(event.companyId());
  }
}
