package com.bigbrightpaints.erp.truthsuite.runtime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.util.ReflectionTestUtils;

import com.bigbrightpaints.erp.core.util.CompanyClock;
import com.bigbrightpaints.erp.modules.accounting.domain.AccountRepository;
import com.bigbrightpaints.erp.modules.accounting.domain.AccountingPeriod;
import com.bigbrightpaints.erp.modules.accounting.domain.AccountingPeriodRepository;
import com.bigbrightpaints.erp.modules.accounting.domain.AccountingPeriodStatus;
import com.bigbrightpaints.erp.modules.accounting.domain.JournalEntry;
import com.bigbrightpaints.erp.modules.accounting.domain.JournalEntryRepository;
import com.bigbrightpaints.erp.modules.accounting.domain.JournalEntryStatus;
import com.bigbrightpaints.erp.modules.accounting.domain.JournalLineRepository;
import com.bigbrightpaints.erp.modules.accounting.domain.PeriodCloseRequest;
import com.bigbrightpaints.erp.modules.accounting.domain.PeriodCloseRequestRepository;
import com.bigbrightpaints.erp.modules.accounting.domain.PeriodCloseRequestStatus;
import com.bigbrightpaints.erp.modules.accounting.domain.ReconciliationDiscrepancyRepository;
import com.bigbrightpaints.erp.modules.accounting.dto.AccountingPeriodReopenRequest;
import com.bigbrightpaints.erp.modules.accounting.dto.PeriodCloseRequestActionRequest;
import com.bigbrightpaints.erp.modules.accounting.dto.PeriodStatusChangeRequest;
import com.bigbrightpaints.erp.modules.accounting.service.AccountingFacade;
import com.bigbrightpaints.erp.modules.accounting.service.AccountingPeriodService;
import com.bigbrightpaints.erp.modules.accounting.service.AccountingPeriodSnapshotService;
import com.bigbrightpaints.erp.modules.accounting.service.CompanyScopedAccountingLookupService;
import com.bigbrightpaints.erp.modules.accounting.service.PeriodCloseHook;
import com.bigbrightpaints.erp.modules.accounting.service.ReconciliationService;
import com.bigbrightpaints.erp.modules.company.domain.Company;
import com.bigbrightpaints.erp.modules.company.domain.CompanyModule;
import com.bigbrightpaints.erp.modules.company.service.CompanyContextService;
import com.bigbrightpaints.erp.modules.hr.domain.PayrollRun;
import com.bigbrightpaints.erp.modules.hr.domain.PayrollRunRepository;
import com.bigbrightpaints.erp.modules.invoice.domain.InvoiceRepository;
import com.bigbrightpaints.erp.modules.purchasing.domain.GoodsReceiptRepository;
import com.bigbrightpaints.erp.modules.purchasing.domain.GoodsReceiptStatus;
import com.bigbrightpaints.erp.modules.purchasing.domain.RawMaterialPurchaseRepository;
import com.bigbrightpaints.erp.modules.reports.service.ReportService;

@ExtendWith(MockitoExtension.class)
@Tag("critical")
@Tag("reconciliation")
class TS_RuntimeAccountingPeriodServiceRegressionExecutableCoverageTest {

  @Mock private AccountingPeriodRepository accountingPeriodRepository;
  @Mock private CompanyContextService companyContextService;
  @Mock private JournalEntryRepository journalEntryRepository;
  @Mock private CompanyScopedAccountingLookupService accountingLookupService;
  @Mock private JournalLineRepository journalLineRepository;
  @Mock private AccountRepository accountRepository;
  @Mock private CompanyClock companyClock;
  @Mock private ReportService reportService;
  @Mock private ReconciliationService reconciliationService;
  @Mock private InvoiceRepository invoiceRepository;
  @Mock private GoodsReceiptRepository goodsReceiptRepository;
  @Mock private RawMaterialPurchaseRepository rawMaterialPurchaseRepository;
  @Mock private PayrollRunRepository payrollRunRepository;
  @Mock private ReconciliationDiscrepancyRepository reconciliationDiscrepancyRepository;
  @Mock private PeriodCloseRequestRepository periodCloseRequestRepository;
  @Mock private ObjectProvider<AccountingFacade> accountingFacadeProvider;
  @Mock private PeriodCloseHook periodCloseHook;
  @Mock private AccountingPeriodSnapshotService snapshotService;

  @AfterEach
  void clearSecurityContext() {
    SecurityContextHolder.clearContext();
  }

  @Test
  void lockPeriod_trimsReasonAndTransitionsToLocked() {
    SecurityContextHolder.clearContext();
    AccountingPeriodService service = newService();
    Company company = company(1L, "TRUTH");
    AccountingPeriod period = openPeriod(company, 2026, 2);
    when(companyContextService.requireCurrentCompany()).thenReturn(company);
    when(accountingPeriodRepository.lockByCompanyAndId(company, 13L))
        .thenReturn(Optional.of(period));
    when(accountingPeriodRepository.save(period)).thenReturn(period);

    assertThat(
            service.lockPeriod(13L, new PeriodStatusChangeRequest("  lock for audit  ")).status())
        .isEqualTo("LOCKED");
    assertThat(period.getStatus()).isEqualTo(AccountingPeriodStatus.LOCKED);
    assertThat(period.getLockReason()).isEqualTo("lock for audit");
  }

  @Test
  void closeThenReopen_periodLifecycleUpdatesStateAndSnapshotHooks() {
    SecurityContextHolder.clearContext();
    AccountingPeriodService service = newService();
    Company company = company(2L, "TRUTH2");
    AccountingPeriod period = openPeriod(company, 2026, 2);
    JournalEntry closingEntry = new JournalEntry();
    ReflectionTestUtils.setField(closingEntry, "id", 444L);
    closingEntry.setStatus("REVERSED");

    when(companyContextService.requireCurrentCompany()).thenReturn(company);
    when(accountingPeriodRepository.lockByCompanyAndId(company, 21L))
        .thenReturn(Optional.of(period), Optional.of(period));
    when(goodsReceiptRepository.countByCompanyAndReceiptDateBetweenAndStatusNot(
            company, period.getStartDate(), period.getEndDate(), GoodsReceiptStatus.INVOICED))
        .thenReturn(0L);
    when(journalLineRepository.summarizeByAccountType(
            company, period.getStartDate(), period.getEndDate()))
        .thenReturn(List.of());
    when(accountingPeriodRepository.save(period)).thenReturn(period);
    when(accountingPeriodRepository.findByCompanyAndYearAndMonth(company, 2026, 3))
        .thenReturn(Optional.of(openPeriod(company, 2026, 3)));
    when(journalEntryRepository.findByCompanyAndId(company, 444L))
        .thenReturn(Optional.of(closingEntry));

    PeriodCloseRequest pending = pendingCloseRequest(company, period, 901L, "maker.user");
    when(periodCloseRequestRepository.lockByCompanyAndAccountingPeriodAndStatus(
            company, period, PeriodCloseRequestStatus.PENDING))
        .thenReturn(Optional.of(pending));
    when(periodCloseRequestRepository.save(pending)).thenReturn(pending);
    authenticate("checker.user", "ROLE_ADMIN");
    assertThat(
            service
                .approvePeriodClose(21L, new PeriodCloseRequestActionRequest("month close", true))
                .status())
        .isEqualTo("CLOSED");
    assertThat(period.getStatus()).isEqualTo(AccountingPeriodStatus.CLOSED);
    period.setClosingJournalEntryId(444L);

    authenticate("super.admin", "ROLE_SUPER_ADMIN");
    assertThat(
            service.reopenPeriod(21L, new AccountingPeriodReopenRequest(" reopen month ")).status())
        .isEqualTo("OPEN");
    assertThat(period.getStatus()).isEqualTo(AccountingPeriodStatus.OPEN);
    assertThat(period.getReopenReason()).isEqualTo("reopen month");

    verify(periodCloseHook).onPeriodCloseLocked(company, period);
    verify(snapshotService).captureSnapshot(company, period, "checker.user");
    verify(snapshotService).deleteSnapshotForPeriod(company, period);
  }

  @Test
  void getMonthEndChecklist_countsPayrollDiagnosticsWhenHrPayrollEnabled() {
    AccountingPeriodService service = newService();
    Company company = company(3L, "TRUTH3");
    company.setEnabledModules(Set.of(CompanyModule.HR_PAYROLL.name()));
    AccountingPeriod period = openPeriod(company, 2026, 2);

    when(companyContextService.requireCurrentCompany()).thenReturn(company);
    when(accountingLookupService.requireAccountingPeriod(company, 31L)).thenReturn(period);
    when(journalEntryRepository.countByCompanyAndEntryDateBetweenAndStatusIn(
            company, period.getStartDate(), period.getEndDate(), List.of(JournalEntryStatus.DRAFT)))
        .thenReturn(0L);
    when(reportService.inventoryReconciliation())
        .thenReturn(
            new com.bigbrightpaints.erp.modules.reports.dto.ReconciliationSummaryDto(
                java.math.BigDecimal.ZERO, java.math.BigDecimal.ZERO, java.math.BigDecimal.ZERO));
    when(reconciliationService.reconcileSubledgersForPeriod(
            period.getStartDate(), period.getEndDate()))
        .thenReturn(
            new ReconciliationService.PeriodReconciliationResult(
                period.getStartDate(),
                period.getEndDate(),
                java.math.BigDecimal.ZERO,
                java.math.BigDecimal.ZERO,
                java.math.BigDecimal.ZERO,
                true,
                java.math.BigDecimal.ZERO,
                java.math.BigDecimal.ZERO,
                java.math.BigDecimal.ZERO,
                true));
    when(reconciliationService.generateGstReconciliation(
            java.time.YearMonth.from(period.getStartDate())))
        .thenReturn(new com.bigbrightpaints.erp.modules.accounting.dto.GstReconciliationDto());
    when(reconciliationDiscrepancyRepository.countByCompanyAndAccountingPeriodAndStatus(
            company,
            period,
            com.bigbrightpaints.erp.modules.accounting.domain.ReconciliationDiscrepancyStatus.OPEN))
        .thenReturn(0L);
    when(reportService.trialBalance(period.getEndDate()))
        .thenReturn(
            new com.bigbrightpaints.erp.modules.reports.dto.TrialBalanceDto(
                List.of(), java.math.BigDecimal.ZERO, java.math.BigDecimal.ZERO, true, null));
    when(journalEntryRepository.findByCompanyAndEntryDateBetweenOrderByEntryDateAsc(
            company, period.getStartDate(), period.getEndDate()))
        .thenReturn(List.of());
    when(invoiceRepository.countByCompanyAndIssueDateBetweenAndStatusNotAndJournalEntryIsNull(
            company, period.getStartDate(), period.getEndDate(), "DRAFT"))
        .thenReturn(0L);
    when(rawMaterialPurchaseRepository
            .countByCompanyAndInvoiceDateBetweenAndStatusInAndJournalEntryIsNull(
                company,
                period.getStartDate(),
                period.getEndDate(),
                List.of("POSTED", "PARTIAL", "PAID")))
        .thenReturn(0L);
    when(payrollRunRepository.countByCompanyAndPeriodBetweenAndStatusInAndJournalMissing(
            company,
            period.getStartDate(),
            period.getEndDate(),
            List.of(PayrollRun.PayrollStatus.POSTED, PayrollRun.PayrollStatus.PAID)))
        .thenReturn(2L);
    when(invoiceRepository.countByCompanyAndIssueDateBetweenAndStatusIn(
            company, period.getStartDate(), period.getEndDate(), List.of("DRAFT")))
        .thenReturn(0L);
    when(rawMaterialPurchaseRepository.countByCompanyAndInvoiceDateBetweenAndStatusNotIn(
            company,
            period.getStartDate(),
            period.getEndDate(),
            List.of("POSTED", "PARTIAL", "PAID")))
        .thenReturn(0L);
    when(payrollRunRepository.countByCompanyAndPeriodBetweenAndStatusIn(
            company,
            period.getStartDate(),
            period.getEndDate(),
            List.of(
                PayrollRun.PayrollStatus.DRAFT,
                PayrollRun.PayrollStatus.CALCULATED,
                PayrollRun.PayrollStatus.APPROVED)))
        .thenReturn(3L);

    var checklist = service.getMonthEndChecklist(31L);

    assertThat(checklist.items())
        .filteredOn(
            item ->
                "unlinkedDocuments".equals(item.key()) || "unpostedDocuments".equals(item.key()))
        .allMatch(item -> !item.completed());
    verify(payrollRunRepository)
        .countByCompanyAndPeriodBetweenAndStatusInAndJournalMissing(
            company,
            period.getStartDate(),
            period.getEndDate(),
            List.of(PayrollRun.PayrollStatus.POSTED, PayrollRun.PayrollStatus.PAID));
    verify(payrollRunRepository)
        .countByCompanyAndPeriodBetweenAndStatusIn(
            company,
            period.getStartDate(),
            period.getEndDate(),
            List.of(
                PayrollRun.PayrollStatus.DRAFT,
                PayrollRun.PayrollStatus.CALCULATED,
                PayrollRun.PayrollStatus.APPROVED));
  }

  @Test
  void getMonthEndChecklist_skipsPayrollDiagnosticsWhenEnabledModulesAreNull() {
    AccountingPeriodService service = newService();
    Company company = company(4L, "TRUTH4");
    company.setEnabledModules(null);
    AccountingPeriod period = openPeriod(company, 2026, 2);

    when(companyContextService.requireCurrentCompany()).thenReturn(company);
    when(accountingLookupService.requireAccountingPeriod(company, 32L)).thenReturn(period);
    when(journalEntryRepository.countByCompanyAndEntryDateBetweenAndStatusIn(
            company, period.getStartDate(), period.getEndDate(), List.of(JournalEntryStatus.DRAFT)))
        .thenReturn(0L);
    when(reportService.inventoryReconciliation())
        .thenReturn(
            new com.bigbrightpaints.erp.modules.reports.dto.ReconciliationSummaryDto(
                java.math.BigDecimal.ZERO, java.math.BigDecimal.ZERO, java.math.BigDecimal.ZERO));
    when(reconciliationService.reconcileSubledgersForPeriod(
            period.getStartDate(), period.getEndDate()))
        .thenReturn(
            new ReconciliationService.PeriodReconciliationResult(
                period.getStartDate(),
                period.getEndDate(),
                java.math.BigDecimal.ZERO,
                java.math.BigDecimal.ZERO,
                java.math.BigDecimal.ZERO,
                true,
                java.math.BigDecimal.ZERO,
                java.math.BigDecimal.ZERO,
                java.math.BigDecimal.ZERO,
                true));
    when(reconciliationService.generateGstReconciliation(
            java.time.YearMonth.from(period.getStartDate())))
        .thenReturn(new com.bigbrightpaints.erp.modules.accounting.dto.GstReconciliationDto());
    when(reconciliationDiscrepancyRepository.countByCompanyAndAccountingPeriodAndStatus(
            company,
            period,
            com.bigbrightpaints.erp.modules.accounting.domain.ReconciliationDiscrepancyStatus.OPEN))
        .thenReturn(0L);
    when(reportService.trialBalance(period.getEndDate()))
        .thenReturn(
            new com.bigbrightpaints.erp.modules.reports.dto.TrialBalanceDto(
                List.of(), java.math.BigDecimal.ZERO, java.math.BigDecimal.ZERO, true, null));
    when(journalEntryRepository.findByCompanyAndEntryDateBetweenOrderByEntryDateAsc(
            company, period.getStartDate(), period.getEndDate()))
        .thenReturn(List.of());
    when(invoiceRepository.countByCompanyAndIssueDateBetweenAndStatusNotAndJournalEntryIsNull(
            company, period.getStartDate(), period.getEndDate(), "DRAFT"))
        .thenReturn(0L);
    when(rawMaterialPurchaseRepository
            .countByCompanyAndInvoiceDateBetweenAndStatusInAndJournalEntryIsNull(
                company,
                period.getStartDate(),
                period.getEndDate(),
                List.of("POSTED", "PARTIAL", "PAID")))
        .thenReturn(0L);
    when(invoiceRepository.countByCompanyAndIssueDateBetweenAndStatusIn(
            company, period.getStartDate(), period.getEndDate(), List.of("DRAFT")))
        .thenReturn(0L);
    when(rawMaterialPurchaseRepository.countByCompanyAndInvoiceDateBetweenAndStatusNotIn(
            company,
            period.getStartDate(),
            period.getEndDate(),
            List.of("POSTED", "PARTIAL", "PAID")))
        .thenReturn(0L);

    var checklist = service.getMonthEndChecklist(32L);

    assertThat(checklist.items())
        .filteredOn(
            item ->
                "unlinkedDocuments".equals(item.key()) || "unpostedDocuments".equals(item.key()))
        .allMatch(item -> item.completed());
    verify(payrollRunRepository, org.mockito.Mockito.never())
        .countByCompanyAndPeriodBetweenAndStatusInAndJournalMissing(
            company,
            period.getStartDate(),
            period.getEndDate(),
            List.of(PayrollRun.PayrollStatus.POSTED, PayrollRun.PayrollStatus.PAID));
    verify(payrollRunRepository, org.mockito.Mockito.never())
        .countByCompanyAndPeriodBetweenAndStatusIn(
            company,
            period.getStartDate(),
            period.getEndDate(),
            List.of(
                PayrollRun.PayrollStatus.DRAFT,
                PayrollRun.PayrollStatus.CALCULATED,
                PayrollRun.PayrollStatus.APPROVED));
  }

  private AccountingPeriodService newService() {
    return new AccountingPeriodService(
        accountingPeriodRepository,
        companyContextService,
        journalEntryRepository,
        accountingLookupService,
        journalLineRepository,
        accountRepository,
        companyClock,
        reportService,
        reconciliationService,
        invoiceRepository,
        goodsReceiptRepository,
        rawMaterialPurchaseRepository,
        payrollRunRepository,
        reconciliationDiscrepancyRepository,
        periodCloseRequestRepository,
        accountingFacadeProvider,
        periodCloseHook,
        snapshotService);
  }

  private Company company(Long id, String code) {
    Company company = new Company();
    company.setCode(code);
    company.setName(code + " Pvt");
    company.setTimezone("Asia/Kolkata");
    ReflectionTestUtils.setField(company, "id", id);
    return company;
  }

  private AccountingPeriod openPeriod(Company company, int year, int month) {
    AccountingPeriod period = new AccountingPeriod();
    period.setCompany(company);
    period.setYear(year);
    period.setMonth(month);
    LocalDate startDate = LocalDate.of(year, month, 1);
    period.setStartDate(startDate);
    period.setEndDate(startDate.plusMonths(1).minusDays(1));
    period.setStatus(AccountingPeriodStatus.OPEN);
    ReflectionTestUtils.setField(period, "id", 21L);
    return period;
  }

  private PeriodCloseRequest pendingCloseRequest(
      Company company, AccountingPeriod period, Long requestId, String requestedBy) {
    PeriodCloseRequest request = new PeriodCloseRequest();
    ReflectionTestUtils.setField(request, "id", requestId);
    request.setCompany(company);
    request.setAccountingPeriod(period);
    request.setStatus(PeriodCloseRequestStatus.PENDING);
    request.setRequestedBy(requestedBy);
    request.setRequestNote("pending close");
    request.setForceRequested(true);
    request.setRequestedAt(java.time.Instant.parse("2026-01-01T00:00:00Z"));
    return request;
  }

  private void authenticate(String username, String... roles) {
    SecurityContextHolder.getContext()
        .setAuthentication(
            new UsernamePasswordAuthenticationToken(
                username,
                "N/A",
                java.util.Arrays.stream(roles).map(SimpleGrantedAuthority::new).toList()));
  }
}
