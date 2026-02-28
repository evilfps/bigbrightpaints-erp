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
import com.bigbrightpaints.erp.modules.accounting.domain.JournalEntry;
import com.bigbrightpaints.erp.modules.accounting.domain.JournalEntryRepository;
import com.bigbrightpaints.erp.modules.accounting.domain.JournalLineRepository;
import com.bigbrightpaints.erp.modules.accounting.dto.AccountingPeriodCloseRequest;
import com.bigbrightpaints.erp.modules.accounting.dto.AccountingPeriodLockRequest;
import com.bigbrightpaints.erp.modules.accounting.dto.AccountingPeriodReopenRequest;
import com.bigbrightpaints.erp.modules.accounting.dto.AccountingPeriodUpdateRequest;
import com.bigbrightpaints.erp.modules.accounting.dto.AccountingPeriodUpsertRequest;
import com.bigbrightpaints.erp.modules.accounting.dto.MonthEndChecklistUpdateRequest;
import com.bigbrightpaints.erp.modules.company.domain.Company;
import com.bigbrightpaints.erp.modules.company.service.CompanyContextService;
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
    @Mock private ObjectProvider<AccountingFacade> accountingFacadeProvider;
    @Mock private PeriodCloseHook periodCloseHook;
    @Mock private AccountingPeriodSnapshotService snapshotService;

    private AccountingPeriodService service;

    @BeforeEach
    void setUp() {
        SecurityContextHolder.clearContext();
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
        assertThat(period.getLockedBy()).isEqualTo("system");
    }

    @Test
    void reopenPeriod_whenAlreadyOpen_isIdempotentWithoutSaveOrSnapshotDelete() {
        Company company = company(1L, "ACME");
        AccountingPeriod period = openPeriod(company, 2026, 2);
        when(companyContextService.requireCurrentCompany()).thenReturn(company);
        when(accountingPeriodRepository.lockByCompanyAndId(company, 20L)).thenReturn(Optional.of(period));

        assertThat(service.reopenPeriod(20L, null).status()).isEqualTo("OPEN");
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

        assertThat(service.reopenPeriod(23L, new AccountingPeriodReopenRequest("monthly reopen")).status())
                .isEqualTo("OPEN");
        verify(accountingFacadeProvider, never()).getObject();
        verify(snapshotService).deleteSnapshotForPeriod(company, period);
    }

    @Test
    void closePeriod_requiresReasonWhenRequestMissing() {
        Company company = company(1L, "ACME");
        AccountingPeriod period = openPeriod(company, 2026, 2);
        when(companyContextService.requireCurrentCompany()).thenReturn(company);
        when(accountingPeriodRepository.lockByCompanyAndId(company, 30L)).thenReturn(Optional.of(period));

        assertThatThrownBy(() -> service.closePeriod(30L, null))
                .isInstanceOf(ApplicationException.class)
                .hasMessageContaining("Close reason is required");
    }

    @Test
    void closePeriod_closesAndCapturesSnapshotWhenNetIncomeZero() {
        Company company = company(1L, "ACME");
        AccountingPeriod period = openPeriod(company, 2026, 2);
        when(companyContextService.requireCurrentCompany()).thenReturn(company);
        when(accountingPeriodRepository.lockByCompanyAndId(company, 31L)).thenReturn(Optional.of(period));
        when(goodsReceiptRepository.countByCompanyAndReceiptDateBetweenAndStatusNot(
                company, period.getStartDate(), period.getEndDate(), "INVOICED")).thenReturn(0L);
        when(journalLineRepository.summarizeByAccountType(company, period.getStartDate(), period.getEndDate()))
                .thenReturn(List.of());
        when(accountingPeriodRepository.save(period)).thenReturn(period);
        when(accountingPeriodRepository.findByCompanyAndYearAndMonth(company, 2026, 3))
                .thenReturn(Optional.of(openPeriod(company, 2026, 3)));

        assertThat(service.closePeriod(31L, new AccountingPeriodCloseRequest(true, "  month close  ")).status())
                .isEqualTo("CLOSED");
        assertThat(period.getChecklistNotes()).isEqualTo("month close");
        assertThat(period.getLockReason()).isEqualTo("month close");
        assertThat(period.getClosingJournalEntryId()).isNull();
        verify(periodCloseHook).onPeriodCloseLocked(company, period);
        verify(snapshotService).captureSnapshot(company, period, "system");
        verify(journalEntryRepository, never()).findByCompanyAndReferenceNumber(any(), anyString());
    }

    @Test
    void closePeriod_setsClosingJournalEntryIdWhenNetIncomeNonZero() {
        Company company = company(1L, "ACME");
        AccountingPeriod period = openPeriod(company, 2026, 2);
        JournalEntry closingEntry = journalEntryWithId(444L);
        when(companyContextService.requireCurrentCompany()).thenReturn(company);
        when(accountingPeriodRepository.lockByCompanyAndId(company, 32L)).thenReturn(Optional.of(period));
        when(goodsReceiptRepository.countByCompanyAndReceiptDateBetweenAndStatusNot(
                company, period.getStartDate(), period.getEndDate(), "INVOICED")).thenReturn(0L);
        when(journalLineRepository.summarizeByAccountType(company, period.getStartDate(), period.getEndDate()))
                .thenReturn(List.<Object[]>of(new Object[]{AccountType.REVENUE, BigDecimal.ZERO, new BigDecimal("125.00")}));
        when(journalEntryRepository.findByCompanyAndReferenceNumber(company, "PERIOD-CLOSE-202602"))
                .thenReturn(Optional.of(closingEntry));
        when(accountingPeriodRepository.save(period)).thenReturn(period);
        when(accountingPeriodRepository.findByCompanyAndYearAndMonth(company, 2026, 3))
                .thenReturn(Optional.of(openPeriod(company, 2026, 3)));

        assertThat(service.closePeriod(32L, new AccountingPeriodCloseRequest(true, "close for month")).status())
                .isEqualTo("CLOSED");
        assertThat(period.getClosingJournalEntryId()).isEqualTo(444L);
        verify(snapshotService).captureSnapshot(company, period, "system");
    }

    @Test
    void closePeriod_uninvoicedReceiptsPreventCloseAndSnapshotCapture() {
        Company company = company(1L, "ACME");
        AccountingPeriod period = openPeriod(company, 2026, 2);
        when(companyContextService.requireCurrentCompany()).thenReturn(company);
        when(accountingPeriodRepository.lockByCompanyAndId(company, 33L)).thenReturn(Optional.of(period));
        when(goodsReceiptRepository.countByCompanyAndReceiptDateBetweenAndStatusNot(
                company, period.getStartDate(), period.getEndDate(), "INVOICED")).thenReturn(3L);

        assertThatThrownBy(() -> service.closePeriod(33L, new AccountingPeriodCloseRequest(true, "close")))
                .isInstanceOf(ApplicationException.class)
                .hasMessageContaining("Un-invoiced goods receipts exist in this period (3)");
        verify(periodCloseHook).onPeriodCloseLocked(company, period);
        verify(snapshotService, never()).captureSnapshot(any(), any(), anyString());
        verify(accountingPeriodRepository, never()).save(any(AccountingPeriod.class));
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
        assertThat(period.getBankReconciledBy()).isEqualTo("system");
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
        assertThat(period.getInventoryCountedBy()).isEqualTo("system");
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
}
