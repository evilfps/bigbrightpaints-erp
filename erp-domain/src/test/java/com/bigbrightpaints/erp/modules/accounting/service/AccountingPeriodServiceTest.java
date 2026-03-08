package com.bigbrightpaints.erp.modules.accounting.service;

import com.bigbrightpaints.erp.core.exception.ApplicationException;
import com.bigbrightpaints.erp.core.util.CompanyClock;
import com.bigbrightpaints.erp.core.util.CompanyEntityLookup;
import com.bigbrightpaints.erp.modules.accounting.domain.AccountRepository;
import com.bigbrightpaints.erp.modules.accounting.domain.AccountType;
import com.bigbrightpaints.erp.modules.accounting.domain.AccountingPeriod;
import com.bigbrightpaints.erp.modules.accounting.domain.AccountingPeriodRepository;
import com.bigbrightpaints.erp.modules.accounting.domain.AccountingPeriodStatus;
import com.bigbrightpaints.erp.modules.accounting.domain.CostingMethod;
import com.bigbrightpaints.erp.modules.accounting.domain.ReconciliationDiscrepancyStatus;
import com.bigbrightpaints.erp.modules.accounting.domain.JournalEntry;
import com.bigbrightpaints.erp.modules.accounting.domain.JournalEntryRepository;
import com.bigbrightpaints.erp.modules.accounting.domain.JournalLineRepository;
import com.bigbrightpaints.erp.modules.accounting.domain.PeriodCloseRequest;
import com.bigbrightpaints.erp.modules.accounting.domain.PeriodCloseRequestRepository;
import com.bigbrightpaints.erp.modules.accounting.domain.PeriodCloseRequestStatus;
import com.bigbrightpaints.erp.modules.accounting.domain.ReconciliationDiscrepancyRepository;
import com.bigbrightpaints.erp.modules.accounting.dto.AccountingPeriodLockRequest;
import com.bigbrightpaints.erp.modules.accounting.dto.AccountingPeriodReopenRequest;
import com.bigbrightpaints.erp.modules.accounting.dto.AccountingPeriodUpdateRequest;
import com.bigbrightpaints.erp.modules.accounting.dto.AccountingPeriodUpsertRequest;
import com.bigbrightpaints.erp.modules.accounting.dto.MonthEndChecklistUpdateRequest;
import com.bigbrightpaints.erp.modules.accounting.dto.PeriodCloseRequestActionRequest;
import com.bigbrightpaints.erp.modules.company.domain.Company;
import com.bigbrightpaints.erp.modules.company.service.CompanyContextService;
import com.bigbrightpaints.erp.modules.hr.domain.PayrollRun;
import com.bigbrightpaints.erp.modules.hr.domain.PayrollRunRepository;
import com.bigbrightpaints.erp.modules.invoice.domain.InvoiceRepository;
import com.bigbrightpaints.erp.modules.purchasing.domain.GoodsReceiptStatus;
import com.bigbrightpaints.erp.modules.purchasing.domain.GoodsReceiptRepository;
import com.bigbrightpaints.erp.modules.purchasing.domain.RawMaterialPurchaseRepository;
import com.bigbrightpaints.erp.modules.reports.dto.TrialBalanceDto;
import com.bigbrightpaints.erp.modules.reports.service.ReportService;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.util.ReflectionTestUtils;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class AccountingPeriodServiceTest {

    @Mock private AccountingPeriodRepository accountingPeriodRepository;
    @Mock private CompanyContextService companyContextService;
    @Mock private JournalEntryRepository journalEntryRepository;
    @Mock private CompanyEntityLookup companyEntityLookup;
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

    private AccountingPeriodService service;

    private static final String SYSTEM_PROCESS_ACTOR = "SYSTEM_PROCESS";

    @BeforeEach
    void setUp() {
        SecurityContextHolder.clearContext();
        SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(
                SYSTEM_PROCESS_ACTOR,
                "N/A",
                List.of(new SimpleGrantedAuthority("ROLE_SYSTEM"))));
        service = new AccountingPeriodService(
                accountingPeriodRepository,
                companyContextService,
                journalEntryRepository,
                companyEntityLookup,
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
                snapshotService
        );
    }

    @Test
    void lockPeriod_whenAlreadyLocked_isIdempotentWithoutSave() {
        Company company = company(1L, "ACME");
        AccountingPeriod period = openPeriod(company, 2026, 2);
        period.setStatus(AccountingPeriodStatus.LOCKED);
        when(companyContextService.requireCurrentCompany()).thenReturn(company);
        when(accountingPeriodRepository.lockByCompanyAndId(company, 10L)).thenReturn(Optional.of(period));

        assertThat(service.lockPeriod(10L, null).status()).isEqualTo("LOCKED");
        verify(accountingPeriodRepository, never()).save(any(AccountingPeriod.class));
    }

    @Test
    void lockPeriod_whenAlreadyClosed_isIdempotentWithoutSave() {
        Company company = company(1L, "ACME");
        AccountingPeriod period = openPeriod(company, 2026, 2);
        period.setStatus(AccountingPeriodStatus.CLOSED);
        when(companyContextService.requireCurrentCompany()).thenReturn(company);
        when(accountingPeriodRepository.lockByCompanyAndId(company, 11L)).thenReturn(Optional.of(period));

        assertThat(service.lockPeriod(11L, null).status()).isEqualTo("CLOSED");
        verify(accountingPeriodRepository, never()).save(any(AccountingPeriod.class));
    }

    @Test
    void lockPeriod_requiresReasonWhenRequestMissing() {
        Company company = company(1L, "ACME");
        AccountingPeriod period = openPeriod(company, 2026, 2);
        when(companyContextService.requireCurrentCompany()).thenReturn(company);
        when(accountingPeriodRepository.lockByCompanyAndId(company, 12L)).thenReturn(Optional.of(period));

        assertThatThrownBy(() -> service.lockPeriod(12L, null))
                .isInstanceOf(ApplicationException.class)
                .hasMessageContaining("Lock reason is required");
    }

    @Test
    void lockPeriod_trimsReasonBeforePersisting() {
        Company company = company(1L, "ACME");
        AccountingPeriod period = openPeriod(company, 2026, 2);
        when(companyContextService.requireCurrentCompany()).thenReturn(company);
        when(accountingPeriodRepository.lockByCompanyAndId(company, 13L)).thenReturn(Optional.of(period));
        when(accountingPeriodRepository.save(period)).thenReturn(period);

        assertThat(service.lockPeriod(13L, new AccountingPeriodLockRequest("  lock for audit  ")).status())
                .isEqualTo("LOCKED");
        assertThat(period.getLockReason()).isEqualTo("lock for audit");
        assertThat(period.getLockedBy()).isEqualTo(SYSTEM_PROCESS_ACTOR);
    }

    @Test
    void reopenPeriod_requiresSuperAdminRole() {
        authenticate("accounting.user", "ROLE_ACCOUNTING");

        assertThatThrownBy(() -> service.reopenPeriod(20L, null))
                .isInstanceOf(ApplicationException.class)
                .hasMessageContaining("SUPER_ADMIN authority required");
        verify(accountingPeriodRepository, never()).save(any(AccountingPeriod.class));
        verify(snapshotService, never()).deleteSnapshotForPeriod(any(), any());
    }

    @Test
    void reopenPeriod_requiresReasonWhenRequestMissing() {
        Company company = company(1L, "ACME");
        AccountingPeriod period = openPeriod(company, 2026, 2);
        period.setStatus(AccountingPeriodStatus.CLOSED);
        when(companyContextService.requireCurrentCompany()).thenReturn(company);
        when(accountingPeriodRepository.lockByCompanyAndId(company, 21L)).thenReturn(Optional.of(period));
        authenticate("super.admin", "ROLE_SUPER_ADMIN");

        assertThatThrownBy(() -> service.reopenPeriod(21L, null))
                .isInstanceOf(ApplicationException.class)
                .hasMessageContaining("Reopen reason is required");
    }

    @Test
    void reopenPeriod_clearsClosingJournalIdAndDeletesSnapshotWhenJournalLookupMisses() {
        Company company = company(1L, "ACME");
        AccountingPeriod period = openPeriod(company, 2026, 2);
        period.setStatus(AccountingPeriodStatus.CLOSED);
        period.setClosingJournalEntryId(901L);
        when(companyContextService.requireCurrentCompany()).thenReturn(company);
        when(accountingPeriodRepository.lockByCompanyAndId(company, 22L)).thenReturn(Optional.of(period));
        when(journalEntryRepository.findByCompanyAndId(company, 901L)).thenReturn(Optional.empty());
        when(accountingPeriodRepository.save(period)).thenReturn(period);
        authenticate("super.admin", "ROLE_SUPER_ADMIN");

        assertThat(service.reopenPeriod(22L, new AccountingPeriodReopenRequest("  reopen correction  ")).status())
                .isEqualTo("OPEN");
        assertThat(period.getReopenReason()).isEqualTo("reopen correction");
        assertThat(period.getClosingJournalEntryId()).isNull();
        verify(accountingFacadeProvider, never()).getObject();
        verify(snapshotService).deleteSnapshotForPeriod(company, period);
    }

    @Test
    void reopenPeriod_skipsReversalWhenClosingJournalAlreadyReversed() {
        Company company = company(1L, "ACME");
        AccountingPeriod period = openPeriod(company, 2026, 2);
        period.setStatus(AccountingPeriodStatus.CLOSED);
        period.setClosingJournalEntryId(902L);
        JournalEntry closing = new JournalEntry();
        closing.setStatus("REVERSED");
        when(companyContextService.requireCurrentCompany()).thenReturn(company);
        when(accountingPeriodRepository.lockByCompanyAndId(company, 23L)).thenReturn(Optional.of(period));
        when(journalEntryRepository.findByCompanyAndId(company, 902L)).thenReturn(Optional.of(closing));
        when(accountingPeriodRepository.save(period)).thenReturn(period);
        authenticate("super.admin", "ROLE_SUPER_ADMIN");

        assertThat(service.reopenPeriod(23L, new AccountingPeriodReopenRequest("monthly reopen")).status())
                .isEqualTo("OPEN");
        verify(accountingFacadeProvider, never()).getObject();
        verify(snapshotService).deleteSnapshotForPeriod(company, period);
    }

    @Test
    void closePeriod_requiresApprovedMakerCheckerRequest() {
        assertThatThrownBy(() -> service.closePeriod(30L, null))
                .isInstanceOf(ApplicationException.class)
                .hasMessageContaining("submit /request-close and approve");
    }

    @Test
    void approvePeriodClose_closesAndCapturesSnapshotWhenNetIncomeZero() {
        Company company = company(1L, "ACME");
        AccountingPeriod period = openPeriod(company, 2026, 2);
        ReflectionTestUtils.setField(period, "id", 31L);
        PeriodCloseRequest pending = pendingCloseRequest(company, period, 501L, "maker.user");
        when(companyContextService.requireCurrentCompany()).thenReturn(company);
        when(accountingPeriodRepository.lockByCompanyAndId(company, 31L)).thenReturn(Optional.of(period), Optional.of(period));
        when(periodCloseRequestRepository.lockByCompanyAndAccountingPeriodAndStatus(
                company, period, PeriodCloseRequestStatus.PENDING)).thenReturn(Optional.of(pending));
        when(goodsReceiptRepository.countByCompanyAndReceiptDateBetweenAndStatusNot(
                company, period.getStartDate(), period.getEndDate(), GoodsReceiptStatus.INVOICED)).thenReturn(0L);
        when(journalLineRepository.summarizeByAccountType(company, period.getStartDate(), period.getEndDate()))
                .thenReturn(List.of());
        when(accountingPeriodRepository.save(period)).thenReturn(period);
        when(periodCloseRequestRepository.save(pending)).thenReturn(pending);
        when(accountingPeriodRepository.findByCompanyAndYearAndMonth(company, 2026, 3))
                .thenReturn(Optional.of(openPeriod(company, 2026, 3)));
        authenticate("checker.user", "ROLE_ADMIN");

        assertThat(service.approvePeriodClose(31L, new PeriodCloseRequestActionRequest("  month close  ", true)).status())
                .isEqualTo("CLOSED");
        assertThat(period.getChecklistNotes()).isEqualTo("month close");
        assertThat(period.getLockReason()).isEqualTo("month close");
        assertThat(period.getClosingJournalEntryId()).isNull();
        assertThat(pending.getStatus()).isEqualTo(PeriodCloseRequestStatus.APPROVED);
        assertThat(pending.getReviewedBy()).isEqualTo("checker.user");
        verify(periodCloseHook).onPeriodCloseLocked(company, period);
        verify(snapshotService).captureSnapshot(company, period, "checker.user");
        verify(journalEntryRepository, never()).findByCompanyAndReferenceNumber(any(), anyString());
    }

    @Test
    void approvePeriodClose_setsClosingJournalEntryIdWhenNetIncomeNonZero() {
        Company company = company(1L, "ACME");
        AccountingPeriod period = openPeriod(company, 2026, 2);
        ReflectionTestUtils.setField(period, "id", 32L);
        PeriodCloseRequest pending = pendingCloseRequest(company, period, 502L, "maker.user");
        JournalEntry closingEntry = journalEntryWithId(444L);
        when(companyContextService.requireCurrentCompany()).thenReturn(company);
        when(accountingPeriodRepository.lockByCompanyAndId(company, 32L)).thenReturn(Optional.of(period), Optional.of(period));
        when(periodCloseRequestRepository.lockByCompanyAndAccountingPeriodAndStatus(
                company, period, PeriodCloseRequestStatus.PENDING)).thenReturn(Optional.of(pending));
        when(goodsReceiptRepository.countByCompanyAndReceiptDateBetweenAndStatusNot(
                company, period.getStartDate(), period.getEndDate(), GoodsReceiptStatus.INVOICED)).thenReturn(0L);
        when(journalLineRepository.summarizeByAccountType(company, period.getStartDate(), period.getEndDate()))
                .thenReturn(List.<Object[]>of(new Object[]{AccountType.REVENUE, BigDecimal.ZERO, new BigDecimal("125.00")}));
        when(journalEntryRepository.findByCompanyAndReferenceNumber(company, "PERIOD-CLOSE-202602"))
                .thenReturn(Optional.of(closingEntry));
        when(accountingPeriodRepository.save(period)).thenReturn(period);
        when(periodCloseRequestRepository.save(pending)).thenReturn(pending);
        when(accountingPeriodRepository.findByCompanyAndYearAndMonth(company, 2026, 3))
                .thenReturn(Optional.of(openPeriod(company, 2026, 3)));
        authenticate("checker.user", "ROLE_ADMIN");

        assertThat(service.approvePeriodClose(32L, new PeriodCloseRequestActionRequest("close for month", true)).status())
                .isEqualTo("CLOSED");
        assertThat(period.getClosingJournalEntryId()).isEqualTo(444L);
        assertThat(pending.getStatus()).isEqualTo(PeriodCloseRequestStatus.APPROVED);
        verify(snapshotService).captureSnapshot(company, period, "checker.user");
    }

    @Test
    void approvePeriodClose_uninvoicedReceiptsPreventCloseAndSnapshotCapture() {
        Company company = company(1L, "ACME");
        AccountingPeriod period = openPeriod(company, 2026, 2);
        ReflectionTestUtils.setField(period, "id", 33L);
        PeriodCloseRequest pending = pendingCloseRequest(company, period, 503L, "maker.user");
        when(companyContextService.requireCurrentCompany()).thenReturn(company);
        when(accountingPeriodRepository.lockByCompanyAndId(company, 33L)).thenReturn(Optional.of(period), Optional.of(period));
        when(periodCloseRequestRepository.lockByCompanyAndAccountingPeriodAndStatus(
                company, period, PeriodCloseRequestStatus.PENDING)).thenReturn(Optional.of(pending));
        when(goodsReceiptRepository.countByCompanyAndReceiptDateBetweenAndStatusNot(
                company, period.getStartDate(), period.getEndDate(), GoodsReceiptStatus.INVOICED)).thenReturn(3L);
        authenticate("checker.user", "ROLE_ADMIN");

        assertThatThrownBy(() -> service.approvePeriodClose(33L, new PeriodCloseRequestActionRequest("close", true)))
                .isInstanceOf(ApplicationException.class)
                .hasMessageContaining("Un-invoiced goods receipts exist in this period (3)");
        verify(periodCloseHook).onPeriodCloseLocked(company, period);
        verify(snapshotService, never()).captureSnapshot(any(), any(), anyString());
        verify(accountingPeriodRepository, never()).save(any(AccountingPeriod.class));
    }

    @Test
    void approvePeriodClose_failsWhenTrialBalanceIsNotBalanced() {
        Company company = company(1L, "ACME");
        AccountingPeriod period = openPeriod(company, 2026, 2);
        ReflectionTestUtils.setField(period, "id", 34L);
        PeriodCloseRequest pending = pendingCloseRequest(company, period, 504L, "maker.user");
        period.setBankReconciled(true);
        period.setInventoryCounted(true);

        when(companyContextService.requireCurrentCompany()).thenReturn(company);
        when(accountingPeriodRepository.lockByCompanyAndId(company, 34L)).thenReturn(Optional.of(period), Optional.of(period));
        when(periodCloseRequestRepository.lockByCompanyAndAccountingPeriodAndStatus(
                company, period, PeriodCloseRequestStatus.PENDING)).thenReturn(Optional.of(pending));
        when(goodsReceiptRepository.countByCompanyAndReceiptDateBetweenAndStatusNot(
                company, period.getStartDate(), period.getEndDate(), GoodsReceiptStatus.INVOICED)).thenReturn(0L);
        when(journalEntryRepository.countByCompanyAndEntryDateBetweenAndStatusIn(
                company, period.getStartDate(), period.getEndDate(), List.of("DRAFT", "PENDING"))).thenReturn(0L);
        when(reportService.inventoryReconciliation()).thenReturn(new com.bigbrightpaints.erp.modules.reports.dto.ReconciliationSummaryDto(
                BigDecimal.ZERO,
                BigDecimal.ZERO,
                BigDecimal.ZERO));
        when(reconciliationService.reconcileSubledgersForPeriod(period.getStartDate(), period.getEndDate()))
                .thenReturn(new ReconciliationService.PeriodReconciliationResult(
                        period.getStartDate(),
                        period.getEndDate(),
                        BigDecimal.ZERO,
                        BigDecimal.ZERO,
                        BigDecimal.ZERO,
                        true,
                        BigDecimal.ZERO,
                        BigDecimal.ZERO,
                        BigDecimal.ZERO,
                        true));
        when(reconciliationService.generateGstReconciliation(java.time.YearMonth.from(period.getStartDate())))
                .thenReturn(gstReconciliation(BigDecimal.ZERO));
        when(reconciliationDiscrepancyRepository.countByCompanyAndAccountingPeriodAndStatus(
                company, period, ReconciliationDiscrepancyStatus.OPEN)).thenReturn(0L);
        when(reportService.trialBalance(period.getEndDate())).thenReturn(new TrialBalanceDto(
                List.of(),
                new BigDecimal("150.00"),
                new BigDecimal("100.00"),
                false,
                null));
        when(journalEntryRepository.findByCompanyAndEntryDateBetweenOrderByEntryDateAsc(
                company, period.getStartDate(), period.getEndDate())).thenReturn(List.of());
        when(invoiceRepository.countByCompanyAndIssueDateBetweenAndStatusNotAndJournalEntryIsNull(
                company, period.getStartDate(), period.getEndDate(), "DRAFT")).thenReturn(0L);
        when(rawMaterialPurchaseRepository.countByCompanyAndInvoiceDateBetweenAndStatusInAndJournalEntryIsNull(
                company, period.getStartDate(), period.getEndDate(), List.of("POSTED", "PARTIAL", "PAID"))).thenReturn(0L);
        when(payrollRunRepository.countByCompanyAndPeriodBetweenAndStatusInAndJournalMissing(
                company,
                period.getStartDate(),
                period.getEndDate(),
                List.of(PayrollRun.PayrollStatus.POSTED, PayrollRun.PayrollStatus.PAID))).thenReturn(0L);
        when(invoiceRepository.countByCompanyAndIssueDateBetweenAndStatusIn(
                company, period.getStartDate(), period.getEndDate(), List.of("DRAFT"))).thenReturn(0L);
        when(rawMaterialPurchaseRepository.countByCompanyAndInvoiceDateBetweenAndStatusNotIn(
                company, period.getStartDate(), period.getEndDate(), List.of("POSTED", "PARTIAL", "PAID"))).thenReturn(0L);
        when(payrollRunRepository.countByCompanyAndPeriodBetweenAndStatusIn(
                company,
                period.getStartDate(),
                period.getEndDate(),
                List.of(PayrollRun.PayrollStatus.DRAFT,
                        PayrollRun.PayrollStatus.CALCULATED,
                        PayrollRun.PayrollStatus.APPROVED))).thenReturn(0L);
        authenticate("checker.user", "ROLE_ADMIN");

        assertThatThrownBy(() -> service.approvePeriodClose(34L, new PeriodCloseRequestActionRequest("month close", false)))
                .isInstanceOf(ApplicationException.class)
                .hasMessageContaining("Trial balance is not balanced");
    }

    @Test
    void getMonthEndChecklist_includesTrialBalancePassFailItem() {
        Company company = company(1L, "ACME");
        AccountingPeriod period = openPeriod(company, 2026, 2);
        period.setBankReconciled(true);
        period.setInventoryCounted(true);

        when(companyContextService.requireCurrentCompany()).thenReturn(company);
        when(companyEntityLookup.requireAccountingPeriod(company, 99L)).thenReturn(period);
        when(journalEntryRepository.countByCompanyAndEntryDateBetweenAndStatusIn(
                company, period.getStartDate(), period.getEndDate(), List.of("DRAFT", "PENDING"))).thenReturn(0L);
        when(reportService.inventoryReconciliation()).thenReturn(new com.bigbrightpaints.erp.modules.reports.dto.ReconciliationSummaryDto(
                BigDecimal.ZERO,
                BigDecimal.ZERO,
                BigDecimal.ZERO));
        when(reconciliationService.reconcileSubledgersForPeriod(period.getStartDate(), period.getEndDate()))
                .thenReturn(new ReconciliationService.PeriodReconciliationResult(
                        period.getStartDate(),
                        period.getEndDate(),
                        BigDecimal.ZERO,
                        BigDecimal.ZERO,
                        BigDecimal.ZERO,
                        true,
                        BigDecimal.ZERO,
                        BigDecimal.ZERO,
                        BigDecimal.ZERO,
                        true));
        when(reconciliationService.generateGstReconciliation(java.time.YearMonth.from(period.getStartDate())))
                .thenReturn(gstReconciliation(BigDecimal.ZERO));
        when(reconciliationDiscrepancyRepository.countByCompanyAndAccountingPeriodAndStatus(
                company, period, ReconciliationDiscrepancyStatus.OPEN)).thenReturn(0L);
        when(reportService.trialBalance(period.getEndDate())).thenReturn(new TrialBalanceDto(
                List.of(),
                new BigDecimal("200.00"),
                new BigDecimal("120.00"),
                false,
                null));
        when(journalEntryRepository.findByCompanyAndEntryDateBetweenOrderByEntryDateAsc(
                company, period.getStartDate(), period.getEndDate())).thenReturn(List.of());
        when(invoiceRepository.countByCompanyAndIssueDateBetweenAndStatusNotAndJournalEntryIsNull(
                company, period.getStartDate(), period.getEndDate(), "DRAFT")).thenReturn(0L);
        when(rawMaterialPurchaseRepository.countByCompanyAndInvoiceDateBetweenAndStatusInAndJournalEntryIsNull(
                company, period.getStartDate(), period.getEndDate(), List.of("POSTED", "PARTIAL", "PAID"))).thenReturn(0L);
        when(payrollRunRepository.countByCompanyAndPeriodBetweenAndStatusInAndJournalMissing(
                company,
                period.getStartDate(),
                period.getEndDate(),
                List.of(PayrollRun.PayrollStatus.POSTED, PayrollRun.PayrollStatus.PAID))).thenReturn(0L);
        when(invoiceRepository.countByCompanyAndIssueDateBetweenAndStatusIn(
                company, period.getStartDate(), period.getEndDate(), List.of("DRAFT"))).thenReturn(0L);
        when(rawMaterialPurchaseRepository.countByCompanyAndInvoiceDateBetweenAndStatusNotIn(
                company, period.getStartDate(), period.getEndDate(), List.of("POSTED", "PARTIAL", "PAID"))).thenReturn(0L);
        when(payrollRunRepository.countByCompanyAndPeriodBetweenAndStatusIn(
                company,
                period.getStartDate(),
                period.getEndDate(),
                List.of(PayrollRun.PayrollStatus.DRAFT,
                        PayrollRun.PayrollStatus.CALCULATED,
                        PayrollRun.PayrollStatus.APPROVED))).thenReturn(0L);

        var checklist = service.getMonthEndChecklist(99L);
        var trialBalanceItem = checklist.items().stream()
                .filter(item -> "trialBalanceBalanced".equals(item.key()))
                .findFirst()
                .orElseThrow();
        assertThat(trialBalanceItem.completed()).isFalse();
        assertThat(trialBalanceItem.detail()).contains("differ by");
    }

    @Test
    void confirmBankReconciliation_trimsChecklistNoteOnOpenPeriod() {
        Company company = company(1L, "ACME");
        AccountingPeriod period = openPeriod(company, 2026, 2);
        when(companyContextService.requireCurrentCompany()).thenReturn(company);
        when(companyEntityLookup.requireAccountingPeriod(company, 40L)).thenReturn(period);
        when(accountingPeriodRepository.save(period)).thenReturn(period);

        assertThat(service.confirmBankReconciliation(40L, null, "  bank done  ").bankReconciled())
                .isTrue();
        assertThat(period.getChecklistNotes()).isEqualTo("bank done");
        assertThat(period.getBankReconciledBy()).isEqualTo(SYSTEM_PROCESS_ACTOR);
    }

    @Test
    void confirmInventoryCount_trimsChecklistNoteOnOpenPeriod() {
        Company company = company(1L, "ACME");
        AccountingPeriod period = openPeriod(company, 2026, 2);
        when(companyContextService.requireCurrentCompany()).thenReturn(company);
        when(companyEntityLookup.requireAccountingPeriod(company, 41L)).thenReturn(period);
        when(accountingPeriodRepository.save(period)).thenReturn(period);

        assertThat(service.confirmInventoryCount(41L, null, "  counted  ").inventoryCounted())
                .isTrue();
        assertThat(period.getChecklistNotes()).isEqualTo("counted");
        assertThat(period.getInventoryCountedBy()).isEqualTo(SYSTEM_PROCESS_ACTOR);
    }

    @Test
    void updateMonthEndChecklist_updatesRequestedFlagsAndTrimsNote() {
        Company company = company(1L, "ACME");
        AccountingPeriod period = openPeriod(company, 2026, 2);
        when(companyContextService.requireCurrentCompany()).thenReturn(company);
        when(companyEntityLookup.requireAccountingPeriod(company, 42L)).thenReturn(period);
        when(accountingPeriodRepository.save(period)).thenReturn(period);
        when(journalEntryRepository.findByCompanyAndEntryDateBetweenOrderByEntryDateAsc(
                company, period.getStartDate(), period.getEndDate())).thenReturn(List.of());

        assertThat(service.updateMonthEndChecklist(
                42L,
                new MonthEndChecklistUpdateRequest(true, null, "  month checklist note  "))
                .period()
                .checklistNotes())
                .isEqualTo("month checklist note");
        assertThat(period.isBankReconciled()).isTrue();
        assertThat(period.isInventoryCounted()).isFalse();
    }

    @Test
    void ensurePeriod_defaultsToWeightedAverageWhenCreatingMissingPeriod() {
        Company company = company(1L, "ACME");
        when(accountingPeriodRepository.findByCompanyAndYearAndMonth(company, 2026, 2))
                .thenReturn(Optional.empty());
        when(accountingPeriodRepository.save(any(AccountingPeriod.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));

        AccountingPeriod created = service.ensurePeriod(company, LocalDate.of(2026, 2, 9));

        assertThat(created.getCostingMethod()).isEqualTo(CostingMethod.WEIGHTED_AVERAGE);
    }

    @Test
    void createOrUpdatePeriod_createsPeriodWithRequestedCostingMethod() {
        Company company = company(1L, "ACME");
        when(companyContextService.requireCurrentCompany()).thenReturn(company);
        when(accountingPeriodRepository.lockByCompanyAndYearAndMonth(company, 2026, 4))
                .thenReturn(Optional.empty());
        when(accountingPeriodRepository.save(any(AccountingPeriod.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));

        var dto = service.createOrUpdatePeriod(new AccountingPeriodUpsertRequest(2026, 4, CostingMethod.LIFO));

        assertThat(dto.year()).isEqualTo(2026);
        assertThat(dto.month()).isEqualTo(4);
        assertThat(dto.costingMethod()).isEqualTo("LIFO");
    }

    @Test
    void createOrUpdatePeriod_defaultsToWeightedAverageWhenRequestMethodMissing() {
        Company company = company(1L, "ACME");
        when(companyContextService.requireCurrentCompany()).thenReturn(company);
        when(accountingPeriodRepository.lockByCompanyAndYearAndMonth(company, 2026, 6))
                .thenReturn(Optional.empty());
        when(accountingPeriodRepository.save(any(AccountingPeriod.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));

        var dto = service.createOrUpdatePeriod(new AccountingPeriodUpsertRequest(2026, 6, null));

        assertThat(dto.costingMethod()).isEqualTo("WEIGHTED_AVERAGE");
    }

    @Test
    void updatePeriod_updatesCostingMethodWithoutChangingExistingDates() {
        Company company = company(1L, "ACME");
        AccountingPeriod period = openPeriod(company, 2026, 5);
        period.setCostingMethod(CostingMethod.FIFO);
        when(companyContextService.requireCurrentCompany()).thenReturn(company);
        when(accountingPeriodRepository.lockByCompanyAndId(company, 77L)).thenReturn(Optional.of(period));
        when(accountingPeriodRepository.save(period)).thenReturn(period);

        var dto = service.updatePeriod(77L, new AccountingPeriodUpdateRequest(CostingMethod.WEIGHTED_AVERAGE));

        assertThat(dto.costingMethod()).isEqualTo("WEIGHTED_AVERAGE");
        assertThat(period.getStartDate()).isEqualTo(LocalDate.of(2026, 5, 1));
        assertThat(period.getEndDate()).isEqualTo(LocalDate.of(2026, 5, 31));
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
        period.setCostingMethod(CostingMethod.WEIGHTED_AVERAGE);
        return period;
    }

    private JournalEntry journalEntryWithId(Long id) {
        JournalEntry entry = new JournalEntry();
        ReflectionTestUtils.setField(entry, "id", id);
        return entry;
    }

    private PeriodCloseRequest pendingCloseRequest(Company company,
                                                   AccountingPeriod period,
                                                   Long requestId,
                                                   String requestedBy) {
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
        List<SimpleGrantedAuthority> authorities = java.util.Arrays.stream(roles)
                .map(SimpleGrantedAuthority::new)
                .toList();
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(username, "N/A", authorities));
    }

    @Test
    void confirmBankReconciliation_rejectsChecklistMutationOnLockedPeriod() {
        Company company = company(1L, "ACME");
        AccountingPeriod period = openPeriod(company, 2026, 2);
        period.setStatus(AccountingPeriodStatus.LOCKED);
        when(companyContextService.requireCurrentCompany()).thenReturn(company);
        when(companyEntityLookup.requireAccountingPeriod(company, 43L)).thenReturn(period);

        assertThatThrownBy(() -> service.confirmBankReconciliation(43L, null, "locked mutation"))
                .isInstanceOf(ApplicationException.class)
                .hasMessageContaining("locked or closed period");
    }

    private com.bigbrightpaints.erp.modules.accounting.dto.GstReconciliationDto gstReconciliation(BigDecimal netTotal) {
        com.bigbrightpaints.erp.modules.accounting.dto.GstReconciliationDto dto =
                new com.bigbrightpaints.erp.modules.accounting.dto.GstReconciliationDto();
        com.bigbrightpaints.erp.modules.accounting.dto.GstReconciliationDto.GstComponentSummary summary =
                new com.bigbrightpaints.erp.modules.accounting.dto.GstReconciliationDto.GstComponentSummary();
        summary.setTotal(netTotal);
        dto.setNetLiability(summary);
        return dto;
    }
}
