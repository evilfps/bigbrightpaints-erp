package com.bigbrightpaints.erp.modules.accounting.controller;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import com.bigbrightpaints.erp.core.audit.AuditEvent;
import com.bigbrightpaints.erp.core.audit.AuditService;
import com.bigbrightpaints.erp.core.exception.ApplicationException;
import com.bigbrightpaints.erp.core.exception.ErrorCode;
import com.bigbrightpaints.erp.core.security.SensitiveDisclosurePolicyOwner;
import com.bigbrightpaints.erp.modules.accounting.dto.AgingSummaryResponse;
import com.bigbrightpaints.erp.modules.accounting.dto.GstReconciliationDto;
import com.bigbrightpaints.erp.modules.accounting.dto.GstReturnDto;
import com.bigbrightpaints.erp.modules.accounting.dto.JournalEntryDto;
import com.bigbrightpaints.erp.modules.accounting.dto.PartnerStatementResponse;
import com.bigbrightpaints.erp.modules.accounting.dto.SalesReturnPreviewDto;
import com.bigbrightpaints.erp.modules.accounting.dto.SalesReturnRequest;
import com.bigbrightpaints.erp.modules.accounting.service.AccountingService;
import com.bigbrightpaints.erp.modules.accounting.service.StatementService;
import com.bigbrightpaints.erp.modules.accounting.service.TemporalBalanceService;
import com.bigbrightpaints.erp.modules.sales.service.SalesReturnService;

@Component
public class StatementReportControllerSupport {

  private final AccountingService accountingService;
  private final SalesReturnService salesReturnService;
  private final StatementService statementService;
  private final AuditService auditService;

  public StatementReportControllerSupport(
      AccountingService accountingService,
      SalesReturnService salesReturnService,
      StatementService statementService,
      AuditService auditService) {
    this.accountingService = accountingService;
    this.salesReturnService = salesReturnService;
    this.statementService = statementService;
    this.auditService = auditService;
  }

  GstReturnDto generateGstReturn(String period) {
    return accountingService.generateGstReturn(
        StringUtils.hasText(period) ? YearMonth.parse(period.trim()) : null);
  }

  GstReconciliationDto getGstReconciliation(String period) {
    return accountingService.getGstReconciliation(
        StringUtils.hasText(period) ? YearMonth.parse(period.trim()) : null);
  }

  List<JournalEntryDto> listSalesReturns() {
    return accountingService.listJournalEntriesByReferencePrefix("CRN-").stream()
        .filter(this::isSalesReturnCreditNote)
        .toList();
  }

  SalesReturnPreviewDto previewSalesReturn(SalesReturnRequest request) {
    return salesReturnService.previewReturn(request);
  }

  JournalEntryDto recordSalesReturn(SalesReturnRequest request) {
    return salesReturnService.processReturn(request);
  }

  PartnerStatementResponse supplierStatement(Long supplierId, String from, String to) {
    return statementService.supplierStatement(
        supplierId,
        AccountingDateParameters.parseOptionalDate(from, "from"),
        AccountingDateParameters.parseOptionalDate(to, "to"));
  }

  AgingSummaryResponse supplierAging(Long supplierId, String asOf, String buckets) {
    return statementService.supplierAging(
        supplierId, AccountingDateParameters.parseOptionalDate(asOf, "asOf"), buckets);
  }

  byte[] supplierStatementPdf(Long supplierId, String from, String to) {
    byte[] pdf =
        statementService.supplierStatementPdf(
            supplierId,
            AccountingDateParameters.parseOptionalDate(from, "from"),
            AccountingDateParameters.parseOptionalDate(to, "to"));
    logAccountingExport(
        SensitiveDisclosurePolicyOwner.ACCOUNTING_SUPPLIER_STATEMENT_EXPORT_TYPE,
        supplierId,
        "pdf");
    return pdf;
  }

  byte[] supplierAgingPdf(Long supplierId, String asOf, String buckets) {
    byte[] pdf =
        statementService.supplierAgingPdf(
            supplierId, AccountingDateParameters.parseOptionalDate(asOf, "asOf"), buckets);
    logAccountingExport(
        SensitiveDisclosurePolicyOwner.ACCOUNTING_SUPPLIER_AGING_EXPORT_TYPE, supplierId, "pdf");
    return pdf;
  }

  BigDecimal getBalanceAsOf(Long accountId, String date) {
    return accountingService.getBalanceAsOf(
        accountId, AccountingDateParameters.parseRequiredDate(date, "date"));
  }

  TemporalBalanceService.TrialBalanceSnapshot getTrialBalanceAsOf(String date) {
    return accountingService.getTrialBalanceAsOf(
        AccountingDateParameters.parseRequiredDate(date, "date"));
  }

  AccountActivitySummaryResponse getAccountActivity(
      Long accountId, String startDate, String endDate, String from, String to) {
    String resolvedStart = StringUtils.hasText(startDate) ? startDate : from;
    String resolvedEnd = StringUtils.hasText(endDate) ? endDate : to;
    if (!StringUtils.hasText(resolvedStart) || !StringUtils.hasText(resolvedEnd)) {
      throw new ApplicationException(
          ErrorCode.VALIDATION_MISSING_REQUIRED_FIELD,
          "Account activity requires startDate/endDate (or from/to) query parameters");
    }
    LocalDate start;
    LocalDate end;
    try {
      start = AccountingDateParameters.parseRequiredDate(resolvedStart, "startDate");
      end = AccountingDateParameters.parseRequiredDate(resolvedEnd, "endDate");
    } catch (ApplicationException ex) {
      throw new ApplicationException(
              ErrorCode.VALIDATION_INVALID_DATE,
              "Invalid account activity date format; expected ISO date yyyy-MM-dd")
          .withDetail("startDate", resolvedStart)
          .withDetail("endDate", resolvedEnd);
    }
    TemporalBalanceService.AccountActivityReport report =
        accountingService.getAccountActivity(accountId, start, end);
    List<TemporalBalanceService.AccountMovement> movements =
        report != null && report.movements() != null ? report.movements() : List.of();
    BigDecimal totalDebits = report != null ? report.totalDebits() : BigDecimal.ZERO;
    BigDecimal totalCredits = report != null ? report.totalCredits() : BigDecimal.ZERO;
    BigDecimal netMovement = report != null ? report.netMovement() : BigDecimal.ZERO;
    return new AccountActivitySummaryResponse(
        report != null ? report.accountCode() : null,
        report != null ? report.accountName() : null,
        report != null ? report.startDate() : start,
        report != null ? report.endDate() : end,
        report != null ? report.openingBalance() : BigDecimal.ZERO,
        report != null ? report.closingBalance() : BigDecimal.ZERO,
        totalDebits,
        totalCredits,
        netMovement,
        movements.size(),
        movements);
  }

  Map<String, Object> getAccountingDateContext() {
    return accountingService.getAccountingDateContext();
  }

  BalanceComparisonResponse compareBalances(
      Long accountId, String from, String to, String date1, String date2) {
    String resolvedFrom = StringUtils.hasText(from) ? from : date1;
    String resolvedTo = StringUtils.hasText(to) ? to : date2;
    if (!StringUtils.hasText(resolvedFrom) || !StringUtils.hasText(resolvedTo)) {
      throw new ApplicationException(
          ErrorCode.VALIDATION_MISSING_REQUIRED_FIELD,
          "Balance compare requires from/to (or date1/date2) query parameters");
    }
    LocalDate fromDate = AccountingDateParameters.parseRequiredDate(resolvedFrom, "from");
    LocalDate toDate = AccountingDateParameters.parseRequiredDate(resolvedTo, "to");
    TemporalBalanceService.BalanceComparison comparison =
        accountingService.compareBalances(accountId, fromDate, toDate);
    return new BalanceComparisonResponse(
        fromDate, comparison.balance1(), toDate, comparison.balance2(), comparison.change());
  }

  private boolean isSalesReturnCreditNote(JournalEntryDto entry) {
    if (entry == null || !StringUtils.hasText(entry.referenceNumber())) {
      return false;
    }
    String normalizedReference = entry.referenceNumber().trim().toUpperCase();
    if (!normalizedReference.startsWith("CRN-") || normalizedReference.contains("-COGS-")) {
      return false;
    }
    return entry.dealerId() != null || "SALES_RETURN".equalsIgnoreCase(entry.correctionReason());
  }

  private void logAccountingExport(String resourceType, Long resourceId, String format) {
    if (auditService == null) {
      return;
    }
    Map<String, String> metadata = new HashMap<>();
    metadata.put("resourceType", resourceType);
    metadata.put("resourceId", resourceId != null ? resourceId.toString() : "");
    metadata.put("operation", "EXPORT");
    metadata.put("format", format);
    if (SensitiveDisclosurePolicyOwner.isApprovalBypassExportType(resourceType)) {
      metadata.put("approvalBypass", "true");
    }
    auditService.logSuccess(AuditEvent.DATA_EXPORT, metadata);
  }

  record BalanceComparisonResponse(
      LocalDate from,
      BigDecimal fromBalance,
      LocalDate to,
      BigDecimal toBalance,
      BigDecimal change) {}

  record AccountActivitySummaryResponse(
      String accountCode,
      String accountName,
      LocalDate startDate,
      LocalDate endDate,
      BigDecimal openingBalance,
      BigDecimal closingBalance,
      BigDecimal totalDebits,
      BigDecimal totalCredits,
      BigDecimal netMovement,
      int transactionCount,
      List<TemporalBalanceService.AccountMovement> movements) {}
}
