package com.bigbrightpaints.erp.modules.accounting.controller;

import java.math.BigDecimal;
import java.time.Instant;
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
import com.bigbrightpaints.erp.core.util.CompanyClock;
import com.bigbrightpaints.erp.modules.accounting.dto.AgingSummaryResponse;
import com.bigbrightpaints.erp.modules.accounting.dto.GstReconciliationDto;
import com.bigbrightpaints.erp.modules.accounting.dto.GstReturnDto;
import com.bigbrightpaints.erp.modules.accounting.dto.JournalEntryDto;
import com.bigbrightpaints.erp.modules.accounting.dto.PartnerStatementResponse;
import com.bigbrightpaints.erp.modules.accounting.dto.SalesReturnPreviewDto;
import com.bigbrightpaints.erp.modules.accounting.dto.SalesReturnRequest;
import com.bigbrightpaints.erp.modules.accounting.service.JournalEntryService;
import com.bigbrightpaints.erp.modules.accounting.service.StatementService;
import com.bigbrightpaints.erp.modules.accounting.service.TaxService;
import com.bigbrightpaints.erp.modules.accounting.service.TemporalBalanceService;
import com.bigbrightpaints.erp.modules.company.domain.Company;
import com.bigbrightpaints.erp.modules.company.service.CompanyContextService;
import com.bigbrightpaints.erp.modules.sales.service.SalesReturnService;

@Component
public class StatementReportControllerSupport {

  private final TaxService taxService;
  private final JournalEntryService journalEntryService;
  private final SalesReturnService salesReturnService;
  private final StatementService statementService;
  private final TemporalBalanceService temporalBalanceService;
  private final CompanyContextService companyContextService;
  private final CompanyClock companyClock;
  private final AuditService auditService;

  public StatementReportControllerSupport(
      TaxService taxService,
      JournalEntryService journalEntryService,
      SalesReturnService salesReturnService,
      StatementService statementService,
      TemporalBalanceService temporalBalanceService,
      CompanyContextService companyContextService,
      CompanyClock companyClock,
      AuditService auditService) {
    this.taxService = taxService;
    this.journalEntryService = journalEntryService;
    this.salesReturnService = salesReturnService;
    this.statementService = statementService;
    this.temporalBalanceService = temporalBalanceService;
    this.companyContextService = companyContextService;
    this.companyClock = companyClock;
    this.auditService = auditService;
  }

  GstReturnDto generateGstReturn(String period) {
    return taxService.generateGstReturn(
        StringUtils.hasText(period) ? YearMonth.parse(period.trim()) : null);
  }

  GstReconciliationDto getGstReconciliation(String period) {
    return taxService.generateGstReconciliation(
        StringUtils.hasText(period) ? YearMonth.parse(period.trim()) : null);
  }

  List<JournalEntryDto> listSalesReturns() {
    return journalEntryService.listJournalEntriesByReferencePrefix("CRN-").stream()
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
    logAccountingExport("ACCOUNTING_SUPPLIER_STATEMENT", supplierId, "pdf");
    return pdf;
  }

  byte[] supplierAgingPdf(Long supplierId, String asOf, String buckets) {
    byte[] pdf =
        statementService.supplierAgingPdf(
            supplierId, AccountingDateParameters.parseOptionalDate(asOf, "asOf"), buckets);
    logAccountingExport("ACCOUNTING_SUPPLIER_AGING", supplierId, "pdf");
    return pdf;
  }

  BigDecimal getBalanceAsOf(Long accountId, String date) {
    return temporalBalanceService.getBalanceAsOfDate(
        accountId, AccountingDateParameters.parseRequiredDate(date, "date"));
  }

  TemporalBalanceService.TrialBalanceSnapshot getTrialBalanceAsOf(String date) {
    return temporalBalanceService.getTrialBalanceAsOf(
        AccountingDateParameters.parseRequiredDate(date, "date"));
  }

  TemporalBalanceService.AccountActivityReport getAccountActivity(
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
    return temporalBalanceService.getAccountActivity(accountId, start, end);
  }

  Map<String, Object> getAccountingDateContext() {
    Company company = companyContextService.requireCurrentCompany();
    LocalDate today = companyClock.today(company);
    Instant now = companyClock.now(company);
    Map<String, Object> payload = new HashMap<>();
    payload.put("companyId", company != null ? company.getId() : null);
    payload.put("companyCode", company != null ? company.getCode() : null);
    payload.put("timezone", company != null ? company.getTimezone() : null);
    payload.put("today", today);
    payload.put("now", now);
    return payload;
  }

  TemporalBalanceService.BalanceComparison compareBalances(
      Long accountId, String date1, String date2) {
    return temporalBalanceService.compareBalances(
        accountId,
        AccountingDateParameters.parseRequiredDate(date1, "date1"),
        AccountingDateParameters.parseRequiredDate(date2, "date2"));
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
    auditService.logSuccess(AuditEvent.DATA_EXPORT, metadata);
  }
}
