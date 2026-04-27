package com.bigbrightpaints.erp.modules.accounting.service;

import java.time.LocalDate;
import java.util.List;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import com.bigbrightpaints.erp.core.util.CompanyClock;
import com.bigbrightpaints.erp.modules.accounting.domain.AccountingPeriod;
import com.bigbrightpaints.erp.modules.accounting.domain.AccountingPeriodRepository;
import com.bigbrightpaints.erp.modules.accounting.domain.JournalEntryRepository;
import com.bigbrightpaints.erp.modules.accounting.domain.JournalLineRepository;
import com.bigbrightpaints.erp.modules.accounting.domain.PeriodCloseRequestRepository;
import com.bigbrightpaints.erp.modules.accounting.domain.ReconciliationDiscrepancyRepository;
import com.bigbrightpaints.erp.modules.accounting.dto.AccountingPeriodDto;
import com.bigbrightpaints.erp.modules.accounting.dto.AccountingPeriodReopenRequest;
import com.bigbrightpaints.erp.modules.accounting.dto.AccountingPeriodRequest;
import com.bigbrightpaints.erp.modules.accounting.dto.MonthEndChecklistDto;
import com.bigbrightpaints.erp.modules.accounting.dto.MonthEndChecklistUpdateRequest;
import com.bigbrightpaints.erp.modules.accounting.dto.PeriodCloseRequestActionRequest;
import com.bigbrightpaints.erp.modules.accounting.dto.PeriodCloseRequestDto;
import com.bigbrightpaints.erp.modules.accounting.dto.PeriodStatusChangeRequest;
import com.bigbrightpaints.erp.modules.company.domain.Company;
import com.bigbrightpaints.erp.modules.company.service.CompanyContextService;
import com.bigbrightpaints.erp.modules.hr.domain.PayrollRunRepository;
import com.bigbrightpaints.erp.modules.invoice.domain.InvoiceRepository;
import com.bigbrightpaints.erp.modules.purchasing.domain.GoodsReceiptRepository;
import com.bigbrightpaints.erp.modules.purchasing.domain.RawMaterialPurchaseRepository;
import com.bigbrightpaints.erp.modules.reports.service.ReportService;

import jakarta.transaction.Transactional;

@Service
public class AccountingPeriodService {

  private final AccountingPeriodMakerCheckerLifecycleOwner lifecycleOwner;
  private final AccountingPeriodChecklistService checklistService;

  @Autowired(required = false)
  private AccountingComplianceAuditService accountingComplianceAuditService;

  @Autowired(required = false)
  private ClosedPeriodPostingExceptionService closedPeriodPostingExceptionService;

  @Autowired
  public AccountingPeriodService(
      AccountingPeriodRepository accountingPeriodRepository,
      CompanyContextService companyContextService,
      JournalEntryRepository journalEntryRepository,
      CompanyScopedAccountingLookupService accountingLookupService,
      JournalLineRepository journalLineRepository,
      com.bigbrightpaints.erp.modules.accounting.domain.AccountRepository accountRepository,
      CompanyClock companyClock,
      ReportService reportService,
      ReconciliationService reconciliationService,
      InvoiceRepository invoiceRepository,
      GoodsReceiptRepository goodsReceiptRepository,
      RawMaterialPurchaseRepository rawMaterialPurchaseRepository,
      PayrollRunRepository payrollRunRepository,
      ReconciliationDiscrepancyRepository reconciliationDiscrepancyRepository,
      PeriodCloseRequestRepository periodCloseRequestRepository,
      ObjectProvider<JournalEntryService> journalEntryServiceProvider,
      PeriodCloseHook periodCloseHook,
      AccountingPeriodSnapshotService snapshotService) {
    AccountingPeriodLifecycleService lifecycleService =
        new AccountingPeriodLifecycleService(
            accountingPeriodRepository,
            companyContextService,
            accountingLookupService,
            companyClock,
            () -> accountingComplianceAuditService);
    AccountingPeriodCorrectionJournalClassifier correctionJournalClassifier =
        new AccountingPeriodCorrectionJournalClassifier();
    AccountingPeriodChecklistDiagnosticsService diagnosticsService =
        new AccountingPeriodChecklistDiagnosticsService(
            journalEntryRepository,
            reportService,
            reconciliationService,
            invoiceRepository,
            goodsReceiptRepository,
            rawMaterialPurchaseRepository,
            payrollRunRepository,
            reconciliationDiscrepancyRepository,
            correctionJournalClassifier);
    this.checklistService =
        new AccountingPeriodChecklistService(
            accountingPeriodRepository,
            companyContextService,
            lifecycleService,
            journalEntryRepository,
            diagnosticsService);
    AccountingPeriodCloseWorkflow closeWorkflow =
        new AccountingPeriodCloseWorkflow(
            accountingPeriodRepository,
            companyContextService,
            journalEntryRepository,
            journalLineRepository,
            accountRepository,
            companyClock,
            periodCloseRequestRepository,
            journalEntryServiceProvider,
            periodCloseHook,
            snapshotService,
            lifecycleService,
            checklistService);
    this.lifecycleOwner =
        new AccountingPeriodMakerCheckerLifecycleOwner(
            lifecycleService, closeWorkflow, () -> closedPeriodPostingExceptionService);
  }

  public List<AccountingPeriodDto> listPeriods() {
    return lifecycleOwner.listPeriods();
  }

  public AccountingPeriodDto getPeriod(Long periodId) {
    return lifecycleOwner.getPeriod(periodId);
  }

  @Transactional
  public AccountingPeriodDto createOrUpdatePeriod(AccountingPeriodRequest request) {
    return lifecycleOwner.createOrUpdatePeriod(request, accountingComplianceAuditService);
  }

  @Transactional
  public AccountingPeriodDto updatePeriod(Long periodId, AccountingPeriodRequest request) {
    return lifecycleOwner.updatePeriod(periodId, request, accountingComplianceAuditService);
  }

  @Transactional
  public PeriodCloseRequestDto requestPeriodClose(
      Long periodId, PeriodCloseRequestActionRequest request) {
    return lifecycleOwner.requestPeriodClose(periodId, request, accountingComplianceAuditService);
  }

  @Transactional
  public AccountingPeriodDto approvePeriodClose(
      Long periodId, PeriodCloseRequestActionRequest request) {
    return lifecycleOwner.approvePeriodClose(periodId, request, accountingComplianceAuditService);
  }

  @Transactional
  public PeriodCloseRequestDto rejectPeriodClose(
      Long periodId, PeriodCloseRequestActionRequest request) {
    return lifecycleOwner.rejectPeriodClose(periodId, request, accountingComplianceAuditService);
  }

  @Transactional
  public AccountingPeriodDto closePeriod(Long periodId, PeriodStatusChangeRequest request) {
    return lifecycleOwner.closePeriod(periodId, request);
  }

  @Transactional
  public AccountingPeriodDto confirmBankReconciliation(
      Long periodId, LocalDate referenceDate, String note) {
    return lifecycleOwner.toDto(
        checklistService.confirmBankReconciliation(periodId, referenceDate, note));
  }

  @Transactional
  public AccountingPeriodDto confirmInventoryCount(
      Long periodId, LocalDate referenceDate, String note) {
    return lifecycleOwner.toDto(
        checklistService.confirmInventoryCount(periodId, referenceDate, note));
  }

  @Transactional
  public AccountingPeriodDto lockPeriod(Long periodId, PeriodStatusChangeRequest request) {
    return lifecycleOwner.lockPeriod(periodId, request, accountingComplianceAuditService);
  }

  @Transactional
  public AccountingPeriodDto reopenPeriod(Long periodId, AccountingPeriodReopenRequest request) {
    return lifecycleOwner.reopenPeriod(periodId, request, accountingComplianceAuditService);
  }

  public MonthEndChecklistDto getMonthEndChecklist(Long periodId) {
    return checklistService.getMonthEndChecklist(periodId);
  }

  @Transactional
  public MonthEndChecklistDto updateMonthEndChecklist(
      Long periodId, MonthEndChecklistUpdateRequest request) {
    return checklistService.updateMonthEndChecklist(periodId, request);
  }

  public AccountingPeriod requireOpenPeriod(Company company, LocalDate referenceDate) {
    return lifecycleOwner.requireOpenPeriod(company, referenceDate);
  }

  public AccountingPeriod requirePostablePeriod(
      Company company,
      LocalDate referenceDate,
      String documentType,
      String documentReference,
      String reason,
      boolean overrideRequested) {
    return lifecycleOwner.requirePostablePeriod(
        company, referenceDate, documentType, documentReference, reason, overrideRequested);
  }

  @Transactional
  public AccountingPeriod ensurePeriod(Company company, LocalDate referenceDate) {
    return lifecycleOwner.ensurePeriod(company, referenceDate);
  }
}
