package com.bigbrightpaints.erp.modules.accounting.service;

import java.math.BigDecimal;
import java.time.YearMonth;
import java.util.List;

import com.bigbrightpaints.erp.core.exception.ApplicationException;
import com.bigbrightpaints.erp.modules.accounting.domain.AccountingPeriod;
import com.bigbrightpaints.erp.modules.accounting.domain.JournalEntry;
import com.bigbrightpaints.erp.modules.accounting.domain.JournalEntryRepository;
import com.bigbrightpaints.erp.modules.accounting.domain.JournalLine;
import com.bigbrightpaints.erp.modules.accounting.domain.ReconciliationDiscrepancyRepository;
import com.bigbrightpaints.erp.modules.accounting.domain.ReconciliationDiscrepancyStatus;
import com.bigbrightpaints.erp.modules.company.domain.Company;
import com.bigbrightpaints.erp.modules.company.domain.CompanyModule;
import com.bigbrightpaints.erp.modules.hr.domain.PayrollRun;
import com.bigbrightpaints.erp.modules.hr.domain.PayrollRunRepository;
import com.bigbrightpaints.erp.modules.invoice.domain.InvoiceRepository;
import com.bigbrightpaints.erp.modules.purchasing.domain.GoodsReceiptRepository;
import com.bigbrightpaints.erp.modules.purchasing.domain.GoodsReceiptStatus;
import com.bigbrightpaints.erp.modules.purchasing.domain.RawMaterialPurchaseRepository;
import com.bigbrightpaints.erp.modules.reports.dto.TrialBalanceDto;
import com.bigbrightpaints.erp.modules.reports.service.ReportService;

final class AccountingPeriodChecklistDiagnosticsService {

  private static final BigDecimal BALANCE_TOLERANCE = new BigDecimal("0.01");

  private final JournalEntryRepository journalEntryRepository;
  private final ReportService reportService;
  private final ReconciliationService reconciliationService;
  private final InvoiceRepository invoiceRepository;
  private final GoodsReceiptRepository goodsReceiptRepository;
  private final RawMaterialPurchaseRepository rawMaterialPurchaseRepository;
  private final PayrollRunRepository payrollRunRepository;
  private final ReconciliationDiscrepancyRepository reconciliationDiscrepancyRepository;
  private final AccountingPeriodCorrectionJournalClassifier correctionJournalClassifier;

  AccountingPeriodChecklistDiagnosticsService(
      JournalEntryRepository journalEntryRepository,
      ReportService reportService,
      ReconciliationService reconciliationService,
      InvoiceRepository invoiceRepository,
      GoodsReceiptRepository goodsReceiptRepository,
      RawMaterialPurchaseRepository rawMaterialPurchaseRepository,
      PayrollRunRepository payrollRunRepository,
      ReconciliationDiscrepancyRepository reconciliationDiscrepancyRepository,
      AccountingPeriodCorrectionJournalClassifier correctionJournalClassifier) {
    this.journalEntryRepository = journalEntryRepository;
    this.reportService = reportService;
    this.reconciliationService = reconciliationService;
    this.invoiceRepository = invoiceRepository;
    this.goodsReceiptRepository = goodsReceiptRepository;
    this.rawMaterialPurchaseRepository = rawMaterialPurchaseRepository;
    this.payrollRunRepository = payrollRunRepository;
    this.reconciliationDiscrepancyRepository = reconciliationDiscrepancyRepository;
    this.correctionJournalClassifier = correctionJournalClassifier;
  }

  AccountingPeriodChecklistDiagnostics evaluateChecklistDiagnostics(
      Company company, AccountingPeriod period) {
    List<JournalEntry> periodEntries =
        journalEntryRepository.findByCompanyAndEntryDateBetweenOrderByEntryDateAsc(
            company, period.getStartDate(), period.getEndDate());
    var inventory = reportService.inventoryReconciliation();
    ReconciliationService.PeriodReconciliationResult periodReconciliation =
        reconciliationService.reconcileSubledgersForPeriod(
            period.getStartDate(), period.getEndDate());
    com.bigbrightpaints.erp.modules.accounting.dto.GstReconciliationDto gstReconciliation = null;
    try {
      gstReconciliation =
          reconciliationService.generateGstReconciliation(YearMonth.from(period.getStartDate()));
    } catch (ApplicationException ex) {
      gstReconciliation = null;
    }
    long openReconciliationDiscrepancies =
        reconciliationDiscrepancyRepository.countByCompanyAndAccountingPeriodAndStatus(
            company, period, ReconciliationDiscrepancyStatus.OPEN);
    TrialBalanceDto trialBalance = resolveTrialBalanceForChecklist(period, periodEntries);
    long unbalancedJournals = countUnbalancedJournals(periodEntries);
    long unlinkedDocuments = countUnlinkedDocuments(company, period);
    long unpostedDocuments = countUnpostedDocuments(company, period);
    long uninvoicedReceipts = countUninvoicedReceipts(company, period);
    return new AccountingPeriodChecklistDiagnostics(
        inventory,
        periodReconciliation,
        gstReconciliation,
        openReconciliationDiscrepancies,
        trialBalance,
        unpostedDocuments,
        unlinkedDocuments,
        unbalancedJournals,
        uninvoicedReceipts);
  }

  long countCorrectionLinkageGaps(Company company, AccountingPeriod period) {
    List<JournalEntry> periodEntries =
        journalEntryRepository.findByCompanyAndEntryDateBetweenOrderByEntryDateAsc(
            company, period.getStartDate(), period.getEndDate());
    return correctionJournalClassifier.countCorrectionLinkageGaps(periodEntries);
  }

  long countUninvoicedReceipts(Company company, AccountingPeriod period) {
    return goodsReceiptRepository.countByCompanyAndReceiptDateBetweenAndStatusNot(
        company, period.getStartDate(), period.getEndDate(), GoodsReceiptStatus.INVOICED);
  }

  boolean isHrPayrollEnabled(Company company) {
    return company != null
        && company.getEnabledModules() != null
        && company.getEnabledModules().contains(CompanyModule.HR_PAYROLL.name());
  }

  private long countUnbalancedJournals(List<JournalEntry> periodEntries) {
    if (periodEntries == null || periodEntries.isEmpty()) {
      return 0;
    }
    return periodEntries.stream().filter(this::isUnbalanced).count();
  }

  private TrialBalanceDto resolveTrialBalanceForChecklist(
      AccountingPeriod period, List<JournalEntry> periodEntries) {
    TrialBalanceDto reported = reportService.trialBalance(period.getEndDate());
    if (reported != null) {
      return reported;
    }
    BigDecimal totalDebit = BigDecimal.ZERO;
    BigDecimal totalCredit = BigDecimal.ZERO;
    if (periodEntries != null) {
      for (JournalEntry entry : periodEntries) {
        if (entry == null || entry.getLines() == null) {
          continue;
        }
        for (JournalLine line : entry.getLines()) {
          totalDebit = totalDebit.add(safe(line != null ? line.getDebit() : null));
          totalCredit = totalCredit.add(safe(line != null ? line.getCredit() : null));
        }
      }
    }
    boolean balanced = totalDebit.subtract(totalCredit).abs().compareTo(BALANCE_TOLERANCE) <= 0;
    return new TrialBalanceDto(List.of(), totalDebit, totalCredit, balanced, null);
  }

  private boolean isUnbalanced(JournalEntry entry) {
    BigDecimal debits =
        entry.getLines().stream()
            .map(JournalLine::getDebit)
            .map(this::safe)
            .reduce(BigDecimal.ZERO, BigDecimal::add);
    BigDecimal credits =
        entry.getLines().stream()
            .map(JournalLine::getCredit)
            .map(this::safe)
            .reduce(BigDecimal.ZERO, BigDecimal::add);
    return debits.subtract(credits).abs().compareTo(BALANCE_TOLERANCE) > 0;
  }

  private long countUnpostedDocuments(Company company, AccountingPeriod period) {
    long invoiceDrafts =
        invoiceRepository.countByCompanyAndIssueDateBetweenAndStatusIn(
            company, period.getStartDate(), period.getEndDate(), List.of("DRAFT"));
    long purchaseUnposted =
        rawMaterialPurchaseRepository.countByCompanyAndInvoiceDateBetweenAndStatusNotIn(
            company,
            period.getStartDate(),
            period.getEndDate(),
            List.of("POSTED", "PARTIAL", "PAID"));
    long payrollUnposted =
        isHrPayrollEnabled(company)
            ? payrollRunRepository.countByCompanyAndPeriodBetweenAndStatusIn(
                company,
                period.getStartDate(),
                period.getEndDate(),
                List.of(
                    PayrollRun.PayrollStatus.DRAFT,
                    PayrollRun.PayrollStatus.CALCULATED,
                    PayrollRun.PayrollStatus.APPROVED))
            : 0L;
    return invoiceDrafts + purchaseUnposted + payrollUnposted;
  }

  private long countUnlinkedDocuments(Company company, AccountingPeriod period) {
    long invoiceUnlinked =
        invoiceRepository.countByCompanyAndIssueDateBetweenAndStatusNotAndJournalEntryIsNull(
            company, period.getStartDate(), period.getEndDate(), "DRAFT");
    long purchaseUnlinked =
        rawMaterialPurchaseRepository
            .countByCompanyAndInvoiceDateBetweenAndStatusInAndJournalEntryIsNull(
                company,
                period.getStartDate(),
                period.getEndDate(),
                List.of("POSTED", "PARTIAL", "PAID"));
    long payrollUnlinked =
        isHrPayrollEnabled(company)
            ? payrollRunRepository.countByCompanyAndPeriodBetweenAndStatusInAndJournalMissing(
                company,
                period.getStartDate(),
                period.getEndDate(),
                List.of(PayrollRun.PayrollStatus.POSTED, PayrollRun.PayrollStatus.PAID))
            : 0L;
    long correctionLinkageGaps = countCorrectionLinkageGaps(company, period);
    return invoiceUnlinked + purchaseUnlinked + payrollUnlinked + correctionLinkageGaps;
  }

  private BigDecimal safe(BigDecimal value) {
    return value == null ? BigDecimal.ZERO : value;
  }
}
