package com.bigbrightpaints.erp.modules.accounting.service;

import com.bigbrightpaints.erp.core.exception.ApplicationException;
import com.bigbrightpaints.erp.core.util.CompanyClock;
import com.bigbrightpaints.erp.core.util.CompanyEntityLookup;
import com.bigbrightpaints.erp.modules.accounting.domain.Account;
import com.bigbrightpaints.erp.modules.accounting.domain.AccountRepository;
import com.bigbrightpaints.erp.modules.accounting.domain.AccountType;
import com.bigbrightpaints.erp.modules.accounting.domain.AccountingPeriod;
import com.bigbrightpaints.erp.modules.accounting.domain.AccountingPeriodRepository;
import com.bigbrightpaints.erp.modules.accounting.domain.AccountingPeriodStatus;
import com.bigbrightpaints.erp.modules.accounting.domain.JournalEntry;
import com.bigbrightpaints.erp.modules.accounting.domain.JournalEntryRepository;
import com.bigbrightpaints.erp.modules.accounting.domain.JournalLineRepository;
import com.bigbrightpaints.erp.modules.accounting.dto.AccountingPeriodCloseRequest;
import com.bigbrightpaints.erp.modules.accounting.dto.AccountingPeriodLockRequest;
import com.bigbrightpaints.erp.modules.accounting.dto.AccountingPeriodReopenRequest;
import com.bigbrightpaints.erp.modules.accounting.dto.JournalEntryDto;
import com.bigbrightpaints.erp.modules.accounting.dto.MonthEndChecklistUpdateRequest;
import com.bigbrightpaints.erp.modules.company.domain.Company;
import com.bigbrightpaints.erp.modules.company.service.CompanyContextService;
import com.bigbrightpaints.erp.modules.hr.domain.PayrollRun;
import com.bigbrightpaints.erp.modules.hr.domain.PayrollRunRepository;
import com.bigbrightpaints.erp.modules.invoice.domain.InvoiceRepository;
import com.bigbrightpaints.erp.modules.purchasing.domain.GoodsReceiptRepository;
import com.bigbrightpaints.erp.modules.purchasing.domain.RawMaterialPurchaseRepository;
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
import org.springframework.test.util.ReflectionTestUtils;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
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
                accountingFacadeProvider,
                periodCloseHook,
                snapshotService
        );
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
    void closePeriod_requiresReasonForOpenPeriod() {
        Company company = company(1L, "POLICY");
        AccountingPeriod period = openPeriod(company, 2026, 2);
        when(companyContextService.requireCurrentCompany()).thenReturn(company);
        when(accountingPeriodRepository.lockByCompanyAndId(company, 10L)).thenReturn(Optional.of(period));

        assertThatThrownBy(() -> service.closePeriod(10L, new AccountingPeriodCloseRequest(true, "   ")))
                .isInstanceOf(ApplicationException.class)
                .hasMessageContaining("Close reason is required");
    }

    @Test
    void closePeriod_requiresReasonForLockedPeriod() {
        Company company = company(1L, "POLICY");
        AccountingPeriod period = openPeriod(company, 2026, 2);
        period.setStatus(AccountingPeriodStatus.LOCKED);
        when(companyContextService.requireCurrentCompany()).thenReturn(company);
        when(accountingPeriodRepository.lockByCompanyAndId(company, 10L)).thenReturn(Optional.of(period));

        assertThatThrownBy(() -> service.closePeriod(10L, new AccountingPeriodCloseRequest(true, "   ")))
                .isInstanceOf(ApplicationException.class)
                .hasMessageContaining("Close reason is required");
    }

    @Test
    void closePeriod_allowsLockedToClosedWhenReasonProvided() {
        Company company = company(1L, "POLICY");
        AccountingPeriod period = openPeriod(company, 2026, 2);
        period.setStatus(AccountingPeriodStatus.LOCKED);
        when(companyContextService.requireCurrentCompany()).thenReturn(company);
        when(accountingPeriodRepository.lockByCompanyAndId(company, 10L)).thenReturn(Optional.of(period));
        when(goodsReceiptRepository.countByCompanyAndReceiptDateBetweenAndStatusNot(
                company, period.getStartDate(), period.getEndDate(), "INVOICED")).thenReturn(0L);
        when(journalLineRepository.summarizeByAccountType(
                company, period.getStartDate(), period.getEndDate())).thenReturn(List.of());
        when(accountingPeriodRepository.save(period)).thenReturn(period);
        when(accountingPeriodRepository.findByCompanyAndYearAndMonth(company, 2026, 3))
                .thenReturn(Optional.of(openPeriod(company, 2026, 3)));

        assertThat(service.closePeriod(10L, new AccountingPeriodCloseRequest(true, "close from lock")).status())
                .isEqualTo("CLOSED");
    }

    @Test
    void closePeriod_failsClosedWhenChecklistControlIsUnresolved() {
        Company company = company(1L, "POLICY");
        AccountingPeriod period = openPeriod(company, 2026, 2);
        period.setBankReconciled(true);
        period.setInventoryCounted(true);
        when(companyContextService.requireCurrentCompany()).thenReturn(company);
        when(accountingPeriodRepository.lockByCompanyAndId(company, 10L)).thenReturn(Optional.of(period));
        when(goodsReceiptRepository.countByCompanyAndReceiptDateBetweenAndStatusNot(
                company, period.getStartDate(), period.getEndDate(), "INVOICED")).thenReturn(0L);
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

        assertThatThrownBy(() -> service.closePeriod(10L, new AccountingPeriodCloseRequest(false, "period close")))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Checklist controls unresolved for this period")
                .hasMessageContaining("inventoryReconciled");
    }

    @Test
    void closePeriod_whenAlreadyClosed_isIdempotentWithoutSnapshotRecapture() {
        Company company = company(1L, "POLICY");
        AccountingPeriod period = openPeriod(company, 2026, 2);
        period.setStatus(AccountingPeriodStatus.CLOSED);
        when(companyContextService.requireCurrentCompany()).thenReturn(company);
        when(accountingPeriodRepository.lockByCompanyAndId(company, 14L)).thenReturn(Optional.of(period));

        assertThat(service.closePeriod(14L, new AccountingPeriodCloseRequest(true, "repeat close")).status())
                .isEqualTo("CLOSED");
        verify(snapshotService, never()).captureSnapshot(any(), any(), anyString());
    }

    @Test
    void closePeriod_reportsUnresolvedControlsInDeterministicPolicyOrder() {
        Company company = company(1L, "POLICY");
        AccountingPeriod period = openPeriod(company, 2026, 2);
        period.setBankReconciled(true);
        period.setInventoryCounted(true);
        when(companyContextService.requireCurrentCompany()).thenReturn(company);
        when(accountingPeriodRepository.lockByCompanyAndId(company, 11L)).thenReturn(Optional.of(period));
        when(goodsReceiptRepository.countByCompanyAndReceiptDateBetweenAndStatusNot(
                company, period.getStartDate(), period.getEndDate(), "INVOICED")).thenReturn(0L);
        when(journalEntryRepository.countByCompanyAndEntryDateBetweenAndStatusIn(
                company, period.getStartDate(), period.getEndDate(), List.of("DRAFT", "PENDING"))).thenReturn(0L);
        when(reportService.inventoryReconciliation()).thenReturn(null);
        when(reconciliationService.reconcileSubledgersForPeriod(period.getStartDate(), period.getEndDate()))
                .thenReturn(null);
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

        assertThatThrownBy(() -> service.closePeriod(11L, new AccountingPeriodCloseRequest(false, "period close")))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("Checklist controls unresolved for this period: inventoryReconciled, arReconciled, apReconciled");
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
                .hasMessageContaining("Checklist cannot be updated for a closed period");
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
                .hasMessageContaining("Checklist cannot be updated for a closed period");
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
                .hasMessageContaining("Checklist cannot be updated for a closed period");
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

        assertThat(service.reopenPeriod(13L, new AccountingPeriodReopenRequest("  reopen adjustment  ")).status())
                .isEqualTo("OPEN");
        assertThat(period.getReopenReason()).isEqualTo("reopen adjustment");
        assertThat(period.getClosingJournalEntryId()).isNull();
        verify(accountingFacade).reverseClosingEntryForPeriodReopen(closing, period, "reopen adjustment");
    }

    @Test
    void reopenPeriod_failsClosedWhenClosingJournalLinkMissing() {
        Company company = company(1L, "POLICY");
        AccountingPeriod period = openPeriod(company, 2026, 2);
        period.setStatus(AccountingPeriodStatus.CLOSED);
        period.setClosingJournalEntryId(901L);
        when(companyContextService.requireCurrentCompany()).thenReturn(company);
        when(accountingPeriodRepository.lockByCompanyAndId(company, 15L)).thenReturn(Optional.of(period));
        when(journalEntryRepository.findByCompanyAndId(company, 901L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.reopenPeriod(15L, new AccountingPeriodReopenRequest("reopen adjustment")))
                .isInstanceOf(ApplicationException.class)
                .hasMessageContaining("Closing journal entry 901 is missing");
        verify(accountingPeriodRepository, never()).save(any(AccountingPeriod.class));
    }

    @Test
    void closePeriod_whenBaseClosingReferenceIsReversed_usesRecoveryReference() {
        Company company = company(1L, "POLICY");
        AccountingPeriod period = openPeriod(company, 2026, 2);
        when(companyContextService.requireCurrentCompany()).thenReturn(company);
        when(accountingPeriodRepository.lockByCompanyAndId(company, 30L)).thenReturn(Optional.of(period));
        when(goodsReceiptRepository.countByCompanyAndReceiptDateBetweenAndStatusNot(
                company, period.getStartDate(), period.getEndDate(), "INVOICED")).thenReturn(0L);
        when(journalLineRepository.summarizeByAccountType(
                company, period.getStartDate(), period.getEndDate()))
                .thenReturn(List.<Object[]>of(
                        new Object[]{AccountType.REVENUE, BigDecimal.ZERO, new BigDecimal("100.00")}));

        JournalEntry reversedBase = new JournalEntry();
        reversedBase.setReferenceNumber("PERIOD-CLOSE-202602");
        reversedBase.setStatus("REVERSED");
        when(journalEntryRepository.findByCompanyAndReferenceNumber(company, "PERIOD-CLOSE-202602"))
                .thenReturn(Optional.of(reversedBase));
        when(journalEntryRepository.findByCompanyAndReferenceNumberStartingWith(company, "PERIOD-CLOSE-202602-R"))
                .thenReturn(List.of());

        when(accountRepository.findByCompanyAndCodeIgnoreCase(company, "RETAINED_EARNINGS"))
                .thenReturn(Optional.of(equityAccount(company, 1001L, "RETAINED_EARNINGS", "Retained Earnings")));
        when(accountRepository.findByCompanyAndCodeIgnoreCase(company, "PERIOD_RESULT"))
                .thenReturn(Optional.of(equityAccount(company, 1002L, "PERIOD_RESULT", "Period Result")));
        when(companyClock.today(company)).thenReturn(period.getEndDate());
        when(accountingFacadeProvider.getObject()).thenReturn(accountingFacade);
        when(accountingFacade.postSimpleJournal(
                eq("PERIOD-CLOSE-202602-R1"),
                any(LocalDate.class),
                anyString(),
                any(Long.class),
                any(Long.class),
                any(BigDecimal.class),
                eq(true)))
                .thenReturn(journalEntryDto(950L, "PERIOD-CLOSE-202602-R1", period.getEndDate()));

        JournalEntry postedRecovery = new JournalEntry();
        postedRecovery.setReferenceNumber("PERIOD-CLOSE-202602-R1");
        postedRecovery.setStatus("POSTED");
        ReflectionTestUtils.setField(postedRecovery, "id", 950L);
        when(journalEntryRepository.findByCompanyAndId(company, 950L)).thenReturn(Optional.of(postedRecovery));
        when(accountingPeriodRepository.save(period)).thenReturn(period);
        when(accountingPeriodRepository.findByCompanyAndYearAndMonth(company, 2026, 3))
                .thenReturn(Optional.of(openPeriod(company, 2026, 3)));

        assertThat(service.closePeriod(30L, new AccountingPeriodCloseRequest(true, "reclose after reopen"))
                .closingJournalEntryId()).isEqualTo(950L);
        verify(accountingFacade).postSimpleJournal(
                eq("PERIOD-CLOSE-202602-R1"),
                any(LocalDate.class),
                anyString(),
                any(Long.class),
                any(Long.class),
                any(BigDecimal.class),
                eq(true));
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

    private Account equityAccount(Company company, Long id, String code, String name) {
        Account account = new Account();
        account.setCompany(company);
        account.setCode(code);
        account.setName(name);
        account.setType(AccountType.EQUITY);
        ReflectionTestUtils.setField(account, "id", id);
        return account;
    }

    private JournalEntryDto journalEntryDto(Long id, String reference, LocalDate entryDate) {
        return new JournalEntryDto(
                id,
                null,
                reference,
                entryDate,
                "period close",
                "POSTED",
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                List.of(),
                null,
                null,
                null,
                null,
                null,
                null
        );
    }
}
