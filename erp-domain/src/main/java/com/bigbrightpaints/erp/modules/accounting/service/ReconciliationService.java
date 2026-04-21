package com.bigbrightpaints.erp.modules.accounting.service;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.List;
import java.util.Set;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.bigbrightpaints.erp.modules.accounting.domain.AccountRepository;
import com.bigbrightpaints.erp.modules.accounting.domain.AccountingPeriod;
import com.bigbrightpaints.erp.modules.accounting.domain.AccountingPeriodRepository;
import com.bigbrightpaints.erp.modules.accounting.domain.DealerLedgerRepository;
import com.bigbrightpaints.erp.modules.accounting.domain.JournalEntryRepository;
import com.bigbrightpaints.erp.modules.accounting.domain.JournalLineRepository;
import com.bigbrightpaints.erp.modules.accounting.domain.ReconciliationDiscrepancyRepository;
import com.bigbrightpaints.erp.modules.accounting.domain.ReconciliationDiscrepancyStatus;
import com.bigbrightpaints.erp.modules.accounting.domain.ReconciliationDiscrepancyType;
import com.bigbrightpaints.erp.modules.accounting.domain.SupplierLedgerRepository;
import com.bigbrightpaints.erp.modules.accounting.dto.BankReconciliationSummaryDto;
import com.bigbrightpaints.erp.modules.accounting.dto.GstReconciliationDto;
import com.bigbrightpaints.erp.modules.accounting.dto.ReconciliationDiscrepancyDto;
import com.bigbrightpaints.erp.modules.accounting.dto.ReconciliationDiscrepancyListResponse;
import com.bigbrightpaints.erp.modules.accounting.dto.ReconciliationDiscrepancyResolveRequest;
import com.bigbrightpaints.erp.modules.company.domain.Company;
import com.bigbrightpaints.erp.modules.company.domain.CompanyRepository;
import com.bigbrightpaints.erp.modules.company.service.CompanyContextService;
import com.bigbrightpaints.erp.modules.purchasing.domain.SupplierRepository;
import com.bigbrightpaints.erp.modules.reports.dto.ReconciliationSummaryDto;
import com.bigbrightpaints.erp.modules.reports.service.ReportService;
import com.bigbrightpaints.erp.modules.sales.domain.DealerRepository;

@Service
public class ReconciliationService {

  private final ReconciliationOperations operations;

  public ReconciliationService(
      CompanyContextService companyContextService,
      CompanyRepository companyRepository,
      AccountRepository accountRepository,
      DealerRepository dealerRepository,
      DealerLedgerRepository dealerLedgerRepository,
      SupplierRepository supplierRepository,
      SupplierLedgerRepository supplierLedgerRepository,
      JournalEntryRepository journalEntryRepository,
      JournalLineRepository journalLineRepository,
      TemporalBalanceService temporalBalanceService,
      ReconciliationDiscrepancyRepository reconciliationDiscrepancyRepository,
      AccountingPeriodRepository accountingPeriodRepository,
      TaxService taxService,
      ReportService reportService,
      ObjectProvider<JournalEntryService> journalEntryServiceProvider) {
    this.operations =
        new ReconciliationOperations(
            companyContextService,
            companyRepository,
            accountRepository,
            dealerRepository,
            dealerLedgerRepository,
            supplierRepository,
            supplierLedgerRepository,
            journalEntryRepository,
            journalLineRepository,
            temporalBalanceService,
            reconciliationDiscrepancyRepository,
            accountingPeriodRepository,
            taxService,
            reportService,
            journalEntryServiceProvider);
  }

  @Transactional(readOnly = true)
  public ReconciliationResult reconcileArWithDealerLedger() {
    return operations.reconcileArWithDealerLedger();
  }

  @Transactional(readOnly = true)
  public SupplierReconciliationResult reconcileApWithSupplierLedger() {
    return operations.reconcileApWithSupplierLedger();
  }

  @Transactional(readOnly = true)
  public BankReconciliationSummaryDto reconcileBankAccount(
      Long bankAccountId,
      LocalDate statementDate,
      BigDecimal statementEndingBalanceInput,
      LocalDate startDate,
      LocalDate endDate,
      Set<Long> clearedJournalLineIds,
      Set<String> clearedReferenceNumbers) {
    return operations.reconcileBankAccount(
        bankAccountId,
        statementDate,
        statementEndingBalanceInput,
        startDate,
        endDate,
        clearedJournalLineIds,
        clearedReferenceNumbers);
  }

  @Transactional
  public SubledgerReconciliationReport reconcileSubledgerBalances() {
    return operations.reconcileSubledgerBalances();
  }

  @Transactional(readOnly = true)
  public GstReconciliationDto generateGstReconciliation(YearMonth period) {
    return operations.generateGstReconciliation(period);
  }

  @Transactional(readOnly = true)
  public InterCompanyReconciliationReport interCompanyReconcile(Long companyAId, Long companyBId) {
    return operations.interCompanyReconcile(companyAId, companyBId);
  }

  @Transactional(readOnly = true)
  public PeriodReconciliationResult reconcileSubledgersForPeriod(LocalDate start, LocalDate end) {
    return operations.reconcileSubledgersForPeriod(start, end);
  }

  @Transactional(readOnly = true)
  public ReconciliationDiscrepancyListResponse listDiscrepancies(
      ReconciliationDiscrepancyStatus status, ReconciliationDiscrepancyType type) {
    return operations.listDiscrepancies(status, type);
  }

  @Transactional
  public ReconciliationDiscrepancyDto resolveDiscrepancy(
      Long discrepancyId, ReconciliationDiscrepancyResolveRequest request) {
    return operations.resolveDiscrepancy(discrepancyId, request);
  }

  @Transactional
  public void syncPeriodDiscrepancies(
      Company company,
      AccountingPeriod period,
      PeriodReconciliationResult periodReconciliation,
      ReconciliationSummaryDto inventorySummary,
      GstReconciliationDto gstSummary) {
    operations.syncPeriodDiscrepancies(
        company, period, periodReconciliation, inventorySummary, gstSummary);
  }

  public record ReconciliationResult(
      BigDecimal glArBalance,
      BigDecimal dealerLedgerTotal,
      BigDecimal variance,
      boolean isReconciled,
      List<DealerDiscrepancy> discrepancies,
      int arAccountCount,
      int dealerCount) {}

  public record DealerDiscrepancy(
      Long dealerId,
      String dealerCode,
      String dealerName,
      BigDecimal outstandingBalance,
      BigDecimal ledgerBalance,
      BigDecimal variance) {}

  public record SupplierReconciliationResult(
      BigDecimal glApBalance,
      BigDecimal supplierLedgerTotal,
      BigDecimal variance,
      boolean isReconciled,
      List<SupplierDiscrepancy> discrepancies,
      int apAccountCount,
      int supplierCount) {}

  public record SupplierDiscrepancy(
      Long supplierId,
      String supplierCode,
      String supplierName,
      BigDecimal outstandingBalance,
      BigDecimal ledgerBalance,
      BigDecimal variance) {}

  public record PeriodReconciliationResult(
      LocalDate startDate,
      LocalDate endDate,
      BigDecimal glArNet,
      BigDecimal dealerLedgerNet,
      BigDecimal arVariance,
      boolean arReconciled,
      BigDecimal glApNet,
      BigDecimal supplierLedgerNet,
      BigDecimal apVariance,
      boolean apReconciled) {}

  public record SubledgerReconciliationReport(
      ReconciliationResult dealerReconciliation,
      SupplierReconciliationResult supplierReconciliation,
      BigDecimal combinedVariance,
      boolean reconciled) {}

  public record InterCompanyReconciliationReport(
      Long companyAId,
      String companyACode,
      Long companyBId,
      String companyBCode,
      List<InterCompanyReconciliationItem> matchedItems,
      List<InterCompanyReconciliationItem> unmatchedItems,
      BigDecimal totalDiscrepancyAmount,
      boolean reconciled) {}

  public record InterCompanyReconciliationItem(
      Long receivableCompanyId,
      String receivableCompanyCode,
      Long payableCompanyId,
      String payableCompanyCode,
      Long receivableDealerId,
      Long payableSupplierId,
      BigDecimal receivableAmount,
      BigDecimal payableAmount,
      BigDecimal discrepancyAmount,
      boolean matched,
      boolean counterpartyMissing) {}
}
