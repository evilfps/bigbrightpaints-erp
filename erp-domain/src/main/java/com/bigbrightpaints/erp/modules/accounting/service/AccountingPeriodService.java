package com.bigbrightpaints.erp.modules.accounting.service;

import java.time.LocalDate;
import java.util.List;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import com.bigbrightpaints.erp.core.util.CompanyClock;
import com.bigbrightpaints.erp.core.validation.ValidationUtils;
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

  private final AccountingPeriodLifecycleService lifecycleService;
  private final AccountingPeriodCorrectionJournalClassifier correctionJournalClassifier;
  private final AccountingPeriodChecklistService checklistService;
  private final AccountingPeriodCloseWorkflow closeWorkflow;

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
    this.lifecycleService =
        new AccountingPeriodLifecycleService(
            accountingPeriodRepository,
            companyContextService,
            accountingLookupService,
            companyClock,
            () -> accountingComplianceAuditService);
    this.correctionJournalClassifier = new AccountingPeriodCorrectionJournalClassifier();
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
    this.closeWorkflow =
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
  }

  public List<AccountingPeriodDto> listPeriods() {
    return lifecycleService.listPeriods();
  }

  public AccountingPeriodDto getPeriod(Long periodId) {
    return lifecycleService.getPeriod(periodId);
  }

  @Transactional
  public AccountingPeriodDto createOrUpdatePeriod(AccountingPeriodRequest request) {
    return lifecycleService.createOrUpdatePeriod(request, accountingComplianceAuditService);
  }

  @Transactional
  public AccountingPeriodDto updatePeriod(Long periodId, AccountingPeriodRequest request) {
    return lifecycleService.updatePeriod(periodId, request, accountingComplianceAuditService);
  }

  @Transactional
  public PeriodCloseRequestDto requestPeriodClose(
      Long periodId, PeriodCloseRequestActionRequest request) {
    return closeWorkflow.requestPeriodClose(periodId, request, accountingComplianceAuditService);
  }

  @Transactional
  public AccountingPeriodDto approvePeriodClose(
      Long periodId, PeriodCloseRequestActionRequest request) {
    return closeWorkflow.approvePeriodClose(periodId, request, accountingComplianceAuditService);
  }

  @Transactional
  public PeriodCloseRequestDto rejectPeriodClose(
      Long periodId, PeriodCloseRequestActionRequest request) {
    return closeWorkflow.rejectPeriodClose(periodId, request, accountingComplianceAuditService);
  }

  @Transactional
  public AccountingPeriodDto closePeriod(Long periodId, PeriodStatusChangeRequest request) {
    throw ValidationUtils.invalidState(
        "Direct close is disabled; submit /request-close and approve via maker-checker workflow");
  }

  @Transactional
  public AccountingPeriodDto confirmBankReconciliation(
      Long periodId, LocalDate referenceDate, String note) {
    return lifecycleService.toDto(
        checklistService.confirmBankReconciliation(periodId, referenceDate, note));
  }

  @Transactional
  public AccountingPeriodDto confirmInventoryCount(
      Long periodId, LocalDate referenceDate, String note) {
    return lifecycleService.toDto(
        checklistService.confirmInventoryCount(periodId, referenceDate, note));
  }

  @Transactional
  public AccountingPeriodDto lockPeriod(Long periodId, PeriodStatusChangeRequest request) {
    return closeWorkflow.lockPeriod(periodId, request, accountingComplianceAuditService);
  }

  @Transactional
  public AccountingPeriodDto reopenPeriod(Long periodId, AccountingPeriodReopenRequest request) {
    return closeWorkflow.reopenPeriod(periodId, request, accountingComplianceAuditService);
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
    return lifecycleService.requireOpenPeriod(company, referenceDate);
  }

  public AccountingPeriod requirePostablePeriod(
      Company company,
      LocalDate referenceDate,
      String documentType,
      String documentReference,
      String reason,
      boolean overrideRequested) {
    return lifecycleService.requirePostablePeriod(
        company,
        referenceDate,
        documentType,
        documentReference,
        reason,
        overrideRequested,
        closedPeriodPostingExceptionService);
  }

  @Transactional
  public AccountingPeriod ensurePeriod(Company company, LocalDate referenceDate) {
    return lifecycleService.ensurePeriod(company, referenceDate);
  }
}
