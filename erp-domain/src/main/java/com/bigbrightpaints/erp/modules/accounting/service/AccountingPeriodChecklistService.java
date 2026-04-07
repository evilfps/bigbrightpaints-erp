package com.bigbrightpaints.erp.modules.accounting.service;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;

import org.springframework.util.StringUtils;

import com.bigbrightpaints.erp.core.exception.ApplicationException;
import com.bigbrightpaints.erp.core.exception.ErrorCode;
import com.bigbrightpaints.erp.core.util.CompanyTime;
import com.bigbrightpaints.erp.core.validation.ValidationUtils;
import com.bigbrightpaints.erp.modules.accounting.domain.AccountingPeriod;
import com.bigbrightpaints.erp.modules.accounting.domain.AccountingPeriodRepository;
import com.bigbrightpaints.erp.modules.accounting.domain.AccountingPeriodStatus;
import com.bigbrightpaints.erp.modules.accounting.domain.JournalEntryRepository;
import com.bigbrightpaints.erp.modules.accounting.domain.JournalEntryStatus;
import com.bigbrightpaints.erp.modules.accounting.dto.MonthEndChecklistDto;
import com.bigbrightpaints.erp.modules.accounting.dto.MonthEndChecklistItemDto;
import com.bigbrightpaints.erp.modules.accounting.dto.MonthEndChecklistUpdateRequest;
import com.bigbrightpaints.erp.modules.company.domain.Company;
import com.bigbrightpaints.erp.modules.company.service.CompanyContextService;

final class AccountingPeriodChecklistService {

  private static final List<JournalEntryStatus> DRAFT_CHECKLIST_STATUSES =
      List.of(JournalEntryStatus.DRAFT);
  private static final String UNRESOLVED_CONTROLS_PREFIX =
      "Checklist controls unresolved for this period: ";
  private static final Map<String, String> UNRESOLVED_CONTROL_GUIDANCE =
      Map.of(
          "inventoryReconciled",
          "inventory reconciliation result unavailable; run inventory reconciliation before close",
          "arReconciled",
          "AR subledger reconciliation result unavailable; reconcile dealer ledger before close",
          "apReconciled",
          "AP subledger reconciliation result unavailable; reconcile supplier ledger before close",
          "gstReconciled",
          "GST reconciliation result unavailable; run GST reconciliation before close",
          "reconciliationDiscrepanciesResolved",
          "open reconciliation discrepancies exist; resolve discrepancies before close");

  private final AccountingPeriodRepository accountingPeriodRepository;
  private final CompanyContextService companyContextService;
  private final AccountingPeriodLifecycleService lifecycleService;
  private final JournalEntryRepository journalEntryRepository;
  private final AccountingPeriodChecklistDiagnosticsService diagnosticsService;

  AccountingPeriodChecklistService(
      AccountingPeriodRepository accountingPeriodRepository,
      CompanyContextService companyContextService,
      AccountingPeriodLifecycleService lifecycleService,
      JournalEntryRepository journalEntryRepository,
      AccountingPeriodChecklistDiagnosticsService diagnosticsService) {
    this.accountingPeriodRepository = accountingPeriodRepository;
    this.companyContextService = companyContextService;
    this.lifecycleService = lifecycleService;
    this.journalEntryRepository = journalEntryRepository;
    this.diagnosticsService = diagnosticsService;
  }

  AccountingPeriod confirmBankReconciliation(Long periodId, LocalDate referenceDate, String note) {
    Company company = companyContextService.requireCurrentCompany();
    AccountingPeriod period = lifecycleService.resolvePeriod(company, periodId, referenceDate);
    assertChecklistMutable(period);
    period.setBankReconciled(true);
    period.setBankReconciledAt(CompanyTime.now(company));
    period.setBankReconciledBy(resolveCurrentUsername());
    if (StringUtils.hasText(note)) {
      period.setChecklistNotes(note.trim());
    }
    return accountingPeriodRepository.save(period);
  }

  AccountingPeriod confirmInventoryCount(Long periodId, LocalDate referenceDate, String note) {
    Company company = companyContextService.requireCurrentCompany();
    AccountingPeriod period = lifecycleService.resolvePeriod(company, periodId, referenceDate);
    assertChecklistMutable(period);
    period.setInventoryCounted(true);
    period.setInventoryCountedAt(CompanyTime.now(company));
    period.setInventoryCountedBy(resolveCurrentUsername());
    if (StringUtils.hasText(note)) {
      period.setChecklistNotes(note.trim());
    }
    return accountingPeriodRepository.save(period);
  }

  MonthEndChecklistDto getMonthEndChecklist(Long periodId) {
    Company company = companyContextService.requireCurrentCompany();
    AccountingPeriod period = lifecycleService.resolvePeriod(company, periodId);
    return buildChecklist(company, period);
  }

  MonthEndChecklistDto updateMonthEndChecklist(
      Long periodId, MonthEndChecklistUpdateRequest request) {
    Company company = companyContextService.requireCurrentCompany();
    AccountingPeriod period = lifecycleService.resolvePeriod(company, periodId);
    assertChecklistMutable(period);
    if (request != null) {
      if (request.bankReconciled() != null) {
        period.setBankReconciled(request.bankReconciled());
      }
      if (request.inventoryCounted() != null) {
        period.setInventoryCounted(request.inventoryCounted());
      }
      if (StringUtils.hasText(request.note())) {
        period.setChecklistNotes(request.note().trim());
      }
    }
    AccountingPeriod saved = accountingPeriodRepository.save(period);
    return buildChecklist(company, saved);
  }

  void assertChecklistComplete(Company company, AccountingPeriod period) {
    if (!period.isBankReconciled()) {
      throw ValidationUtils.invalidState(
          "Bank reconciliation has not been confirmed for this period");
    }
    if (!period.isInventoryCounted()) {
      throw ValidationUtils.invalidState("Inventory count has not been confirmed for this period");
    }
    long drafts =
        journalEntryRepository.countByCompanyAndEntryDateBetweenAndStatusIn(
            company, period.getStartDate(), period.getEndDate(), DRAFT_CHECKLIST_STATUSES);
    if (drafts > 0) {
      throw ValidationUtils.invalidState("There are " + drafts + " draft entries in this period");
    }
    AccountingPeriodChecklistDiagnostics diagnostics =
        diagnosticsService.evaluateChecklistDiagnostics(company, period);
    List<String> unresolvedControls = diagnostics.unresolvedControlsInPolicyOrder();
    if (!unresolvedControls.isEmpty()) {
      throw ValidationUtils.invalidState(
          UNRESOLVED_CONTROLS_PREFIX + formatUnresolvedControls(unresolvedControls));
    }
    if (!diagnostics.trialBalanceBalanced()) {
      throw ValidationUtils.invalidState(
          "Trial balance is not balanced (difference "
              + diagnostics.formatVariance(diagnostics.trialBalanceDifference())
              + ")");
    }
    if (!diagnostics.inventoryReconciled()) {
      throw ValidationUtils.invalidState(
          "Inventory reconciliation variance exceeds tolerance ("
              + diagnostics.formatVariance(diagnostics.inventoryVariance())
              + ")");
    }
    if (!diagnostics.arReconciled()) {
      throw ValidationUtils.invalidState(
          "AR reconciliation variance exceeds tolerance ("
              + diagnostics.formatVariance(diagnostics.arVariance())
              + ")");
    }
    if (!diagnostics.apReconciled()) {
      throw ValidationUtils.invalidState(
          "AP reconciliation variance exceeds tolerance ("
              + diagnostics.formatVariance(diagnostics.apVariance())
              + ")");
    }
    if (!diagnostics.gstReconciled()) {
      throw ValidationUtils.invalidState(
          "GST reconciliation variance exceeds tolerance ("
              + diagnostics.formatVariance(diagnostics.gstVariance())
              + ")");
    }
    if (!diagnostics.reconciliationDiscrepanciesResolved()) {
      throw ValidationUtils.invalidState(
          "Open reconciliation discrepancies exist in this period ("
              + diagnostics.openReconciliationDiscrepancies()
              + ")");
    }
    if (diagnostics.unbalancedJournals() > 0) {
      throw ValidationUtils.invalidState(
          "Unbalanced journals present in this period (" + diagnostics.unbalancedJournals() + ")");
    }
    if (diagnostics.unlinkedDocuments() > 0) {
      throw ValidationUtils.invalidState(
          "Documents missing journal links in this period ("
              + diagnostics.unlinkedDocuments()
              + ")");
    }
    if (diagnostics.unpostedDocuments() > 0) {
      throw ValidationUtils.invalidState(
          "Unposted documents exist in this period (" + diagnostics.unpostedDocuments() + ")");
    }
  }

  long countCorrectionLinkageGaps(Company company, AccountingPeriod period) {
    return diagnosticsService.countCorrectionLinkageGaps(company, period);
  }

  long countUninvoicedReceipts(Company company, AccountingPeriod period) {
    return diagnosticsService.countUninvoicedReceipts(company, period);
  }

  boolean isHrPayrollEnabled(Company company) {
    return diagnosticsService.isHrPayrollEnabled(company);
  }

  private void assertChecklistMutable(AccountingPeriod period) {
    if (period.getStatus() == AccountingPeriodStatus.CLOSED
        || period.getStatus() == AccountingPeriodStatus.LOCKED) {
      throw new ApplicationException(
          ErrorCode.VALIDATION_INVALID_INPUT,
          "Checklist cannot be updated for a locked or closed period");
    }
  }

  private MonthEndChecklistDto buildChecklist(Company company, AccountingPeriod period) {
    long draftEntries =
        journalEntryRepository.countByCompanyAndEntryDateBetweenAndStatusIn(
            company, period.getStartDate(), period.getEndDate(), DRAFT_CHECKLIST_STATUSES);
    boolean draftsCleared = draftEntries == 0;
    AccountingPeriodChecklistDiagnostics diagnostics =
        diagnosticsService.evaluateChecklistDiagnostics(company, period);
    boolean trialBalanceBalanced = diagnostics.trialBalanceBalanced();
    boolean inventoryReconciled = diagnostics.inventoryReconciled();
    boolean arReconciled = diagnostics.arReconciled();
    boolean apReconciled = diagnostics.apReconciled();
    boolean gstReconciled = diagnostics.gstReconciled();
    boolean reconciliationDiscrepanciesResolved = diagnostics.reconciliationDiscrepanciesResolved();
    boolean unbalancedCleared = diagnostics.unbalancedJournals() == 0;
    boolean unlinkedCleared = diagnostics.unlinkedDocuments() == 0;
    boolean unpostedCleared = diagnostics.unpostedDocuments() == 0;
    boolean receiptsCleared = diagnostics.uninvoicedReceipts() == 0;
    String inventoryDetail =
        !diagnostics.inventoryControlResolved()
            ? "Control unresolved: inventory reconciliation result unavailable"
            : (inventoryReconciled
                ? "Variance "
                    + diagnostics.formatVariance(diagnostics.inventoryVariance())
                    + " within tolerance"
                : "Variance "
                    + diagnostics.formatVariance(diagnostics.inventoryVariance())
                    + " exceeds tolerance");
    String arDetail =
        !diagnostics.arControlResolved()
            ? "Control unresolved: AR reconciliation result unavailable"
            : (arReconciled
                ? "Variance "
                    + diagnostics.formatVariance(diagnostics.arVariance())
                    + " within tolerance"
                : "Variance "
                    + diagnostics.formatVariance(diagnostics.arVariance())
                    + " exceeds tolerance");
    String apDetail =
        !diagnostics.apControlResolved()
            ? "Control unresolved: AP reconciliation result unavailable"
            : (apReconciled
                ? "Variance "
                    + diagnostics.formatVariance(diagnostics.apVariance())
                    + " within tolerance"
                : "Variance "
                    + diagnostics.formatVariance(diagnostics.apVariance())
                    + " exceeds tolerance");
    String gstDetail =
        !diagnostics.gstControlResolved()
            ? "Control unresolved: GST reconciliation result unavailable"
            : (gstReconciled
                ? "Variance "
                    + diagnostics.formatVariance(diagnostics.gstVariance())
                    + " within tolerance"
                : "Variance "
                    + diagnostics.formatVariance(diagnostics.gstVariance())
                    + " exceeds tolerance");
    String discrepancyDetail =
        reconciliationDiscrepanciesResolved
            ? "No open discrepancies"
            : diagnostics.openReconciliationDiscrepancies()
                + " open discrepancies require resolution";
    String trialBalanceDetail =
        trialBalanceBalanced
            ? "Debits "
                + diagnostics.formatVariance(diagnostics.trialBalanceTotalDebit())
                + " equal credits "
                + diagnostics.formatVariance(diagnostics.trialBalanceTotalCredit())
            : "Debits "
                + diagnostics.formatVariance(diagnostics.trialBalanceTotalDebit())
                + " and credits "
                + diagnostics.formatVariance(diagnostics.trialBalanceTotalCredit())
                + " differ by "
                + diagnostics.formatVariance(diagnostics.trialBalanceDifference());
    List<MonthEndChecklistItemDto> items =
        List.of(
            new MonthEndChecklistItemDto(
                "bankReconciled",
                "Bank accounts reconciled",
                period.isBankReconciled(),
                period.isBankReconciled() ? "Confirmed" : "Pending review"),
            new MonthEndChecklistItemDto(
                "inventoryCounted",
                "Inventory counted",
                period.isInventoryCounted(),
                period.isInventoryCounted() ? "Counts logged" : "Awaiting stock count"),
            new MonthEndChecklistItemDto(
                "draftEntries",
                "Draft entries cleared",
                draftsCleared,
                draftsCleared ? "All entries posted" : draftEntries + " draft entries remaining"),
            new MonthEndChecklistItemDto(
                "trialBalanceBalanced",
                "Trial balance verified",
                trialBalanceBalanced,
                trialBalanceDetail),
            new MonthEndChecklistItemDto(
                "inventoryReconciled",
                "Inventory reconciled to GL",
                inventoryReconciled,
                inventoryDetail),
            new MonthEndChecklistItemDto(
                "arReconciled", "AR reconciled to dealer ledger", arReconciled, arDetail),
            new MonthEndChecklistItemDto(
                "apReconciled", "AP reconciled to supplier ledger", apReconciled, apDetail),
            new MonthEndChecklistItemDto(
                "gstReconciled", "GST reconciled", gstReconciled, gstDetail),
            new MonthEndChecklistItemDto(
                "reconciliationDiscrepanciesResolved",
                "Reconciliation discrepancies resolved",
                reconciliationDiscrepanciesResolved,
                discrepancyDetail),
            new MonthEndChecklistItemDto(
                "unbalancedJournals",
                "Unbalanced journals cleared",
                unbalancedCleared,
                unbalancedCleared
                    ? "All journals balanced"
                    : diagnostics.unbalancedJournals() + " unbalanced journals"),
            new MonthEndChecklistItemDto(
                "unlinkedDocuments",
                "Documents linked to journals",
                unlinkedCleared,
                unlinkedCleared
                    ? "All documents linked"
                    : diagnostics.unlinkedDocuments() + " missing journal links"),
            new MonthEndChecklistItemDto(
                "uninvoicedReceipts",
                "Goods receipts invoiced",
                receiptsCleared,
                receiptsCleared
                    ? "All receipts invoiced"
                    : diagnostics.uninvoicedReceipts() + " receipts awaiting invoice"),
            new MonthEndChecklistItemDto(
                "unpostedDocuments",
                "Unposted documents cleared",
                unpostedCleared,
                unpostedCleared
                    ? "All documents posted"
                    : diagnostics.unpostedDocuments() + " unposted documents"));
    boolean ready =
        period.isBankReconciled()
            && period.isInventoryCounted()
            && draftsCleared
            && trialBalanceBalanced
            && inventoryReconciled
            && arReconciled
            && apReconciled
            && gstReconciled
            && reconciliationDiscrepanciesResolved
            && unbalancedCleared
            && unlinkedCleared
            && receiptsCleared
            && unpostedCleared;
    return new MonthEndChecklistDto(lifecycleService.toDto(period), items, ready);
  }

  private String formatUnresolvedControls(List<String> unresolvedControls) {
    return unresolvedControls.stream()
        .map(
            control -> {
              String guidance = UNRESOLVED_CONTROL_GUIDANCE.get(control);
              if (!StringUtils.hasText(guidance)) {
                return control;
              }
              return control + " [" + guidance + "]";
            })
        .reduce((left, right) -> left + ", " + right)
        .orElse("");
  }

  private String resolveCurrentUsername() {
    return com.bigbrightpaints.erp.core.security.SecurityActorResolver
        .resolveActorWithSystemProcessFallback();
  }
}
