package com.bigbrightpaints.erp.modules.accounting.service;

import com.bigbrightpaints.erp.core.exception.ApplicationException;
import com.bigbrightpaints.erp.core.util.CompanyClock;
import com.bigbrightpaints.erp.core.util.CompanyEntityLookup;
import com.bigbrightpaints.erp.modules.accounting.domain.AccountRepository;
import com.bigbrightpaints.erp.modules.accounting.domain.JournalCorrectionType;
import com.bigbrightpaints.erp.modules.accounting.domain.AccountingPeriod;
import com.bigbrightpaints.erp.modules.accounting.domain.AccountingPeriodRepository;
import com.bigbrightpaints.erp.modules.accounting.domain.AccountingPeriodStatus;
import com.bigbrightpaints.erp.modules.accounting.domain.JournalEntry;
import com.bigbrightpaints.erp.modules.accounting.domain.ReconciliationDiscrepancyStatus;
import com.bigbrightpaints.erp.modules.accounting.domain.JournalEntryRepository;
import com.bigbrightpaints.erp.modules.accounting.domain.JournalLineRepository;
import com.bigbrightpaints.erp.modules.accounting.domain.PeriodCloseRequest;
import com.bigbrightpaints.erp.modules.accounting.domain.PeriodCloseRequestRepository;
import com.bigbrightpaints.erp.modules.accounting.domain.PeriodCloseRequestStatus;
import com.bigbrightpaints.erp.modules.accounting.domain.ReconciliationDiscrepancyRepository;
import com.bigbrightpaints.erp.modules.accounting.dto.AccountingPeriodCloseRequest;
import com.bigbrightpaints.erp.modules.accounting.dto.AccountingPeriodLockRequest;
import com.bigbrightpaints.erp.modules.accounting.dto.AccountingPeriodReopenRequest;
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
import com.bigbrightpaints.erp.modules.reports.service.ReportService;
import java.time.Instant;
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
class AccountingPeriodServicePolicyTest {

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
    @Mock private AccountingFacade accountingFacade;
    @Mock private PeriodCloseHook periodCloseHook;
    @Mock private AccountingPeriodSnapshotService snapshotService;

    private AccountingPeriodService service;

    @BeforeEach
    void setUp() {
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
        SecurityContextHolder.clearContext();
    }

    @Test
    void lockPeriod_requiresReasonForOpenPeriod() {
        Company company = company(1L, "POLICY");
        AccountingPeriod period = openPeriod(company, 2026, 2);
        when(companyContextService.requireCurrentCompany()).thenReturn(company);
        when(accountingPeriodRepository.lockByCompanyAndId(company, 10L)).thenReturn(Optional.of(period));

        assertThatThrownBy(() -> service.lockPeriod(10L, new AccountingPeriodLockRequest("   ")))
                .isInstanceOf(ApplicationException.class)
                .hasMessageContaining("Lock reason is required");
    }

    @Test
    void closePeriod_requiresApprovedMakerCheckerRequestForOpenPeriod() {
        assertThatThrownBy(() -> service.closePeriod(10L, new AccountingPeriodCloseRequest(true, "policy close")))
                .isInstanceOf(ApplicationException.class)
                .hasMessageContaining("submit /request-close and approve");
    }

    @Test
    void closePeriod_requiresApprovedMakerCheckerRequestForLockedPeriod() {
        assertThatThrownBy(() -> service.closePeriod(10L, new AccountingPeriodCloseRequest(true, "policy close")))
                .isInstanceOf(ApplicationException.class)
                .hasMessageContaining("submit /request-close and approve");
    }

    @Test
    void approvePeriodClose_allowsLockedToClosedWhenReasonProvided() {
        Company company = company(1L, "POLICY");
        AccountingPeriod period = openPeriod(company, 2026, 2);
        ReflectionTestUtils.setField(period, "id", 10L);
        period.setStatus(AccountingPeriodStatus.LOCKED);
        PeriodCloseRequest pending = pendingCloseRequest(company, period, 700L, "maker.user");
        when(companyContextService.requireCurrentCompany()).thenReturn(company);
        when(accountingPeriodRepository.lockByCompanyAndId(company, 10L)).thenReturn(Optional.of(period), Optional.of(period));
        when(periodCloseRequestRepository.lockByCompanyAndAccountingPeriodAndStatus(
                company, period, PeriodCloseRequestStatus.PENDING)).thenReturn(Optional.of(pending));
        when(periodCloseRequestRepository.save(pending)).thenReturn(pending);
        when(goodsReceiptRepository.countByCompanyAndReceiptDateBetweenAndStatusNot(
                company, period.getStartDate(), period.getEndDate(), GoodsReceiptStatus.INVOICED)).thenReturn(0L);
        when(journalLineRepository.summarizeByAccountType(
                company, period.getStartDate(), period.getEndDate())).thenReturn(List.of());
        when(accountingPeriodRepository.save(period)).thenReturn(period);
        when(accountingPeriodRepository.findByCompanyAndYearAndMonth(company, 2026, 3))
                .thenReturn(Optional.of(openPeriod(company, 2026, 3)));
        authenticate("policy.admin", "ROLE_ADMIN");

        assertThat(service.approvePeriodClose(10L, new PeriodCloseRequestActionRequest("close from lock", true)).status())
                .isEqualTo("CLOSED");
    }

    @Test
    void approvePeriodClose_failsClosedWhenChecklistControlIsUnresolved() {
        Company company = company(1L, "POLICY");
        AccountingPeriod period = openPeriod(company, 2026, 2);
        ReflectionTestUtils.setField(period, "id", 10L);
        PeriodCloseRequest pending = pendingCloseRequest(company, period, 701L, "maker.user");
        period.setBankReconciled(true);
        period.setInventoryCounted(true);
        when(companyContextService.requireCurrentCompany()).thenReturn(company);
        when(accountingPeriodRepository.lockByCompanyAndId(company, 10L)).thenReturn(Optional.of(period), Optional.of(period));
        when(periodCloseRequestRepository.lockByCompanyAndAccountingPeriodAndStatus(
                company, period, PeriodCloseRequestStatus.PENDING)).thenReturn(Optional.of(pending));
        when(goodsReceiptRepository.countByCompanyAndReceiptDateBetweenAndStatusNot(
                company, period.getStartDate(), period.getEndDate(), GoodsReceiptStatus.INVOICED)).thenReturn(0L);
        when(journalEntryRepository.countByCompanyAndEntryDateBetweenAndStatusIn(
                company, period.getStartDate(), period.getEndDate(), List.of("DRAFT", "PENDING"))).thenReturn(0L);
        when(reportService.inventoryReconciliation()).thenReturn(null);
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
        authenticate("policy.admin", "ROLE_ADMIN");

        assertThatThrownBy(() -> service.approvePeriodClose(10L, new PeriodCloseRequestActionRequest("period close", false)))
                .isInstanceOf(ApplicationException.class)
                .hasMessageContaining("Checklist controls unresolved for this period")
                .hasMessageContaining("inventoryReconciled");
    }

    @Test
    void closePeriod_whenAlreadyClosed_stillRejectsDirectClosePath() {
        assertThatThrownBy(() -> service.closePeriod(14L, new AccountingPeriodCloseRequest(true, "repeat close")))
                .isInstanceOf(ApplicationException.class)
                .hasMessageContaining("submit /request-close and approve");
        verify(snapshotService, never()).captureSnapshot(any(), any(), anyString());
    }

    @Test
    void approvePeriodClose_reportsUnresolvedControlsInDeterministicPolicyOrder() {
        Company company = company(1L, "POLICY");
        AccountingPeriod period = openPeriod(company, 2026, 2);
        ReflectionTestUtils.setField(period, "id", 11L);
        PeriodCloseRequest pending = pendingCloseRequest(company, period, 702L, "maker.user");
        period.setBankReconciled(true);
        period.setInventoryCounted(true);
        when(companyContextService.requireCurrentCompany()).thenReturn(company);
        when(accountingPeriodRepository.lockByCompanyAndId(company, 11L)).thenReturn(Optional.of(period), Optional.of(period));
        when(periodCloseRequestRepository.lockByCompanyAndAccountingPeriodAndStatus(
                company, period, PeriodCloseRequestStatus.PENDING)).thenReturn(Optional.of(pending));
        when(goodsReceiptRepository.countByCompanyAndReceiptDateBetweenAndStatusNot(
                company, period.getStartDate(), period.getEndDate(), GoodsReceiptStatus.INVOICED)).thenReturn(0L);
        when(journalEntryRepository.countByCompanyAndEntryDateBetweenAndStatusIn(
                company, period.getStartDate(), period.getEndDate(), List.of("DRAFT", "PENDING"))).thenReturn(0L);
        when(reportService.inventoryReconciliation()).thenReturn(null);
        when(reconciliationService.reconcileSubledgersForPeriod(period.getStartDate(), period.getEndDate()))
                .thenReturn(null);
        when(reconciliationService.generateGstReconciliation(java.time.YearMonth.from(period.getStartDate())))
                .thenThrow(new ApplicationException(
                        com.bigbrightpaints.erp.core.exception.ErrorCode.VALIDATION_INVALID_INPUT,
                        "GST unavailable"));
        when(reconciliationDiscrepancyRepository.countByCompanyAndAccountingPeriodAndStatus(
                company, period, ReconciliationDiscrepancyStatus.OPEN)).thenReturn(1L);
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
        authenticate("policy.admin", "ROLE_ADMIN");

        assertThatThrownBy(() -> service.approvePeriodClose(11L, new PeriodCloseRequestActionRequest("period close", false)))
                .isInstanceOf(ApplicationException.class)
                .hasMessage("Checklist controls unresolved for this period: "
                        + "inventoryReconciled [inventory reconciliation result unavailable; run inventory reconciliation before close], "
                        + "arReconciled [AR subledger reconciliation result unavailable; reconcile dealer ledger before close], "
                        + "apReconciled [AP subledger reconciliation result unavailable; reconcile supplier ledger before close], "
                        + "gstReconciled [GST reconciliation result unavailable; run GST reconciliation before close], "
                        + "reconciliationDiscrepanciesResolved [open reconciliation discrepancies exist; resolve discrepancies before close]");
    }

    @Test
    void approvePeriodClose_failsWhenCorrectionJournalLinkageIsMissing() {
        Company company = company(1L, "POLICY");
        AccountingPeriod period = openPeriod(company, 2026, 2);
        ReflectionTestUtils.setField(period, "id", 12L);
        PeriodCloseRequest pending = pendingCloseRequest(company, period, 703L, "maker.user");
        period.setBankReconciled(true);
        period.setInventoryCounted(true);
        JournalEntry correctionEntry = new JournalEntry();
        correctionEntry.setCompany(company);
        correctionEntry.setReferenceNumber("CRN-INV-100");
        correctionEntry.setEntryDate(period.getStartDate().plusDays(3));
        correctionEntry.setStatus("POSTED");
        correctionEntry.setCorrectionType(JournalCorrectionType.REVERSAL);
        correctionEntry.setCorrectionReason("SALES_RETURN");
        when(companyContextService.requireCurrentCompany()).thenReturn(company);
        when(accountingPeriodRepository.lockByCompanyAndId(company, 12L)).thenReturn(Optional.of(period), Optional.of(period));
        when(periodCloseRequestRepository.lockByCompanyAndAccountingPeriodAndStatus(
                company, period, PeriodCloseRequestStatus.PENDING)).thenReturn(Optional.of(pending));
        when(goodsReceiptRepository.countByCompanyAndReceiptDateBetweenAndStatusNot(
                company, period.getStartDate(), period.getEndDate(), GoodsReceiptStatus.INVOICED)).thenReturn(0L);
        when(journalEntryRepository.countByCompanyAndEntryDateBetweenAndStatusIn(
                company, period.getStartDate(), period.getEndDate(), List.of("DRAFT", "PENDING"))).thenReturn(0L);
        when(reportService.inventoryReconciliation()).thenReturn(inventoryReconciliation(BigDecimal.ZERO));
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
        when(journalEntryRepository.findByCompanyAndEntryDateBetweenOrderByEntryDateAsc(
                company, period.getStartDate(), period.getEndDate())).thenReturn(List.of(correctionEntry));
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
        authenticate("policy.admin", "ROLE_ADMIN");

        assertThatThrownBy(() -> service.approvePeriodClose(12L, new PeriodCloseRequestActionRequest("period close", false)))
                .isInstanceOf(ApplicationException.class)
                .hasMessageContaining("Documents missing journal links in this period (1)");
    }

    @Test
    void confirmBankReconciliation_rejectsChecklistMutationOnClosedPeriod() {
        Company company = company(1L, "POLICY");
        AccountingPeriod period = openPeriod(company, 2026, 2);
        period.setStatus(AccountingPeriodStatus.CLOSED);
        when(companyContextService.requireCurrentCompany()).thenReturn(company);
        when(companyEntityLookup.requireAccountingPeriod(company, 20L)).thenReturn(period);

        assertThatThrownBy(() -> service.confirmBankReconciliation(20L, null, "post-close mutation"))
                .isInstanceOf(ApplicationException.class)
                .hasMessageContaining("Checklist cannot be updated for a locked or closed period");
        assertThat(period.isBankReconciled()).isFalse();
    }

    @Test
    void confirmInventoryCount_rejectsChecklistMutationOnClosedPeriod() {
        Company company = company(1L, "POLICY");
        AccountingPeriod period = openPeriod(company, 2026, 2);
        period.setStatus(AccountingPeriodStatus.CLOSED);
        when(companyContextService.requireCurrentCompany()).thenReturn(company);
        when(companyEntityLookup.requireAccountingPeriod(company, 21L)).thenReturn(period);

        assertThatThrownBy(() -> service.confirmInventoryCount(21L, null, "post-close mutation"))
                .isInstanceOf(ApplicationException.class)
                .hasMessageContaining("Checklist cannot be updated for a locked or closed period");
        assertThat(period.isInventoryCounted()).isFalse();
    }

    @Test
    void updateMonthEndChecklist_rejectsChecklistMutationOnClosedPeriod() {
        Company company = company(1L, "POLICY");
        AccountingPeriod period = openPeriod(company, 2026, 2);
        period.setStatus(AccountingPeriodStatus.CLOSED);
        when(companyContextService.requireCurrentCompany()).thenReturn(company);
        when(companyEntityLookup.requireAccountingPeriod(company, 22L)).thenReturn(period);

        assertThatThrownBy(() -> service.updateMonthEndChecklist(22L, new MonthEndChecklistUpdateRequest(true, true, "post-close mutation")))
                .isInstanceOf(ApplicationException.class)
                .hasMessageContaining("Checklist cannot be updated for a locked or closed period");
        assertThat(period.isBankReconciled()).isFalse();
        assertThat(period.isInventoryCounted()).isFalse();
    }

    @Test
    void reopenPeriod_requiresReasonForClosedPeriod() {
        Company company = company(1L, "POLICY");
        AccountingPeriod period = openPeriod(company, 2026, 2);
        period.setStatus(AccountingPeriodStatus.CLOSED);
        when(companyContextService.requireCurrentCompany()).thenReturn(company);
        when(accountingPeriodRepository.lockByCompanyAndId(company, 12L)).thenReturn(Optional.of(period));
        authenticate("policy.superadmin", "ROLE_SUPER_ADMIN");

        assertThatThrownBy(() -> service.reopenPeriod(12L, new AccountingPeriodReopenRequest("   ")))
                .isInstanceOf(ApplicationException.class)
                .hasMessageContaining("Reopen reason is required");
    }

    @Test
    void reopenPeriod_trimsReasonAndUsesCanonicalReasonForReversal() {
        Company company = company(1L, "POLICY");
        AccountingPeriod period = openPeriod(company, 2026, 2);
        period.setStatus(AccountingPeriodStatus.CLOSED);
        period.setClosingJournalEntryId(900L);
        JournalEntry closing = new JournalEntry();
        closing.setStatus("POSTED");
        when(companyContextService.requireCurrentCompany()).thenReturn(company);
        when(accountingPeriodRepository.lockByCompanyAndId(company, 13L)).thenReturn(Optional.of(period));
        when(journalEntryRepository.findByCompanyAndId(company, 900L)).thenReturn(Optional.of(closing));
        when(accountingPeriodRepository.save(period)).thenReturn(period);
        when(accountingFacadeProvider.getObject()).thenReturn(accountingFacade);
        authenticate("policy.superadmin", "ROLE_SUPER_ADMIN");

        assertThat(service.reopenPeriod(13L, new AccountingPeriodReopenRequest("  reopen adjustment  ")).status())
                .isEqualTo("OPEN");
        assertThat(period.getReopenReason()).isEqualTo("reopen adjustment");
        assertThat(period.getClosingJournalEntryId()).isNull();
        verify(accountingFacade).reverseClosingEntryForPeriodReopen(closing, period, "reopen adjustment");
    }

    @Test
    void approvePeriodClose_requiresAdminRole() {
        authenticate("policy.accounting", "ROLE_ACCOUNTING");

        assertThatThrownBy(() -> service.approvePeriodClose(15L, new PeriodCloseRequestActionRequest("month close", true)))
                .isInstanceOf(ApplicationException.class)
                .hasMessageContaining("ROLE_ADMIN authority required");
    }

    @Test
    void rejectPeriodClose_requiresAdminRole() {
        authenticate("policy.accounting", "ROLE_ACCOUNTING");

        assertThatThrownBy(() -> service.rejectPeriodClose(15L, new PeriodCloseRequestActionRequest("reject close", false)))
                .isInstanceOf(ApplicationException.class)
                .hasMessageContaining("ROLE_ADMIN authority required");
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
        return period;
    }

    private PeriodCloseRequest pendingCloseRequest(Company company,
                                                   AccountingPeriod period,
                                                   Long id,
                                                   String requestedBy) {
        PeriodCloseRequest request = new PeriodCloseRequest();
        request.setCompany(company);
        request.setAccountingPeriod(period);
        request.setStatus(PeriodCloseRequestStatus.PENDING);
        request.setRequestedBy(requestedBy);
        request.setRequestNote("close request");
        request.setRequestedAt(Instant.parse("2026-02-28T10:15:30Z"));
        ReflectionTestUtils.setField(request, "id", id);
        return request;
    }

    private void authenticate(String username, String authority) {
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(username, "N/A", List.of(() -> authority)));
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

    private com.bigbrightpaints.erp.modules.reports.dto.ReconciliationSummaryDto inventoryReconciliation(BigDecimal variance) {
        return new com.bigbrightpaints.erp.modules.reports.dto.ReconciliationSummaryDto(
                BigDecimal.ZERO,
                BigDecimal.ZERO,
                variance);
    }
}
