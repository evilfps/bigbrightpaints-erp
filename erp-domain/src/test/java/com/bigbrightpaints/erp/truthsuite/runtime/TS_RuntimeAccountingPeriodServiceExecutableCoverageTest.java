package com.bigbrightpaints.erp.truthsuite.runtime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

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
import com.bigbrightpaints.erp.modules.accounting.dto.AccountingPeriodReopenRequest;
import com.bigbrightpaints.erp.modules.accounting.dto.JournalCreationRequest;
import com.bigbrightpaints.erp.modules.accounting.dto.JournalEntryDto;
import com.bigbrightpaints.erp.modules.accounting.service.AccountingFacade;
import com.bigbrightpaints.erp.modules.accounting.service.AccountingPeriodService;
import com.bigbrightpaints.erp.modules.accounting.service.AccountingPeriodSnapshotService;
import com.bigbrightpaints.erp.modules.accounting.service.PeriodCloseHook;
import com.bigbrightpaints.erp.modules.accounting.service.ReconciliationService;
import com.bigbrightpaints.erp.modules.company.domain.Company;
import com.bigbrightpaints.erp.modules.company.service.CompanyContextService;
import com.bigbrightpaints.erp.modules.hr.domain.PayrollRunRepository;
import com.bigbrightpaints.erp.modules.invoice.domain.InvoiceRepository;
import com.bigbrightpaints.erp.modules.purchasing.domain.GoodsReceiptRepository;
import com.bigbrightpaints.erp.modules.purchasing.domain.RawMaterialPurchaseRepository;
import com.bigbrightpaints.erp.modules.reports.service.ReportService;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Optional;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.test.util.ReflectionTestUtils;

@Tag("critical")
@Tag("concurrency")
@Tag("reconciliation")
class TS_RuntimeAccountingPeriodServiceExecutableCoverageTest {

    @Test
    void createSystemJournal_postsViaFacadeBoundary_andReadsPersistedEntry() {
        AccountingPeriodRepository periodRepository = mock(AccountingPeriodRepository.class);
        CompanyContextService companyContextService = mock(CompanyContextService.class);
        JournalEntryRepository journalEntryRepository = mock(JournalEntryRepository.class);
        CompanyEntityLookup companyEntityLookup = mock(CompanyEntityLookup.class);
        JournalLineRepository journalLineRepository = mock(JournalLineRepository.class);
        AccountRepository accountRepository = mock(AccountRepository.class);
        CompanyClock companyClock = mock(CompanyClock.class);
        ReportService reportService = mock(ReportService.class);
        ReconciliationService reconciliationService = mock(ReconciliationService.class);
        InvoiceRepository invoiceRepository = mock(InvoiceRepository.class);
        GoodsReceiptRepository goodsReceiptRepository = mock(GoodsReceiptRepository.class);
        RawMaterialPurchaseRepository rawMaterialPurchaseRepository = mock(RawMaterialPurchaseRepository.class);
        PayrollRunRepository payrollRunRepository = mock(PayrollRunRepository.class);
        AccountingFacade accountingFacade = mock(AccountingFacade.class);
        @SuppressWarnings("unchecked")
        ObjectProvider<AccountingFacade> accountingFacadeProvider = mock(ObjectProvider.class);
        when(accountingFacadeProvider.getObject()).thenReturn(accountingFacade);
        PeriodCloseHook periodCloseHook = mock(PeriodCloseHook.class);
        AccountingPeriodSnapshotService snapshotService = mock(AccountingPeriodSnapshotService.class);

        AccountingPeriodService service = new AccountingPeriodService(
                periodRepository,
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

        Company company = company(42L, "TRUTH");
        AccountingPeriod period = period(company, 2026, 2);
        Account retained = account(111L, company, "RETAINED_EARNINGS", AccountType.EQUITY);
        Account periodResult = account(222L, company, "PERIOD_RESULT", AccountType.EQUITY);

        when(accountRepository.findByCompanyAndCodeIgnoreCase(company, "RETAINED_EARNINGS"))
                .thenReturn(Optional.of(retained));
        when(accountRepository.findByCompanyAndCodeIgnoreCase(company, "PERIOD_RESULT"))
                .thenReturn(Optional.of(periodResult));
        when(companyClock.today(company)).thenReturn(LocalDate.of(2026, 2, 28));
        when(accountingFacade.createStandardJournal(any(JournalCreationRequest.class))).thenReturn(new JournalEntryDto(
                9001L,
                null,
                "PERIOD-CLOSE-202602",
                LocalDate.of(2026, 2, 28),
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
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null
        ));

        JournalEntry persisted = new JournalEntry();
        persisted.setCompany(company);
        persisted.setReferenceNumber("PERIOD-CLOSE-202602");
        ReflectionTestUtils.setField(persisted, "id", 9001L);
        when(journalEntryRepository.findByCompanyAndId(company, 9001L)).thenReturn(Optional.of(persisted));

        JournalEntry result = ReflectionTestUtils.invokeMethod(
                service,
                "createSystemJournal",
                company,
                period,
                "PERIOD-CLOSE-202602",
                "Month close",
                new BigDecimal("150.00")
        );

        assertThat(result).isSameAs(persisted);
        verify(accountingFacadeProvider).getObject();
        verify(accountingFacade).createStandardJournal(argThat(request ->
                request != null
                        && "PERIOD-CLOSE-202602".equals(request.sourceReference())
                        && LocalDate.of(2026, 2, 28).equals(request.entryDate())
                        && "Month close".equals(request.narration())
                        && request.debitAccount().equals(222L)
                        && request.creditAccount().equals(111L)
                        && request.amount().compareTo(new BigDecimal("150.00")) == 0
                        && Boolean.TRUE.equals(request.adminOverride())));
    }

    @Test
    void reverseClosingJournalIfNeeded_routesThroughFacadeForOpenReversalState() {
        AccountingPeriodRepository periodRepository = mock(AccountingPeriodRepository.class);
        CompanyContextService companyContextService = mock(CompanyContextService.class);
        JournalEntryRepository journalEntryRepository = mock(JournalEntryRepository.class);
        CompanyEntityLookup companyEntityLookup = mock(CompanyEntityLookup.class);
        JournalLineRepository journalLineRepository = mock(JournalLineRepository.class);
        AccountRepository accountRepository = mock(AccountRepository.class);
        CompanyClock companyClock = mock(CompanyClock.class);
        ReportService reportService = mock(ReportService.class);
        ReconciliationService reconciliationService = mock(ReconciliationService.class);
        InvoiceRepository invoiceRepository = mock(InvoiceRepository.class);
        GoodsReceiptRepository goodsReceiptRepository = mock(GoodsReceiptRepository.class);
        RawMaterialPurchaseRepository rawMaterialPurchaseRepository = mock(RawMaterialPurchaseRepository.class);
        PayrollRunRepository payrollRunRepository = mock(PayrollRunRepository.class);
        AccountingFacade accountingFacade = mock(AccountingFacade.class);
        @SuppressWarnings("unchecked")
        ObjectProvider<AccountingFacade> accountingFacadeProvider = mock(ObjectProvider.class);
        when(accountingFacadeProvider.getObject()).thenReturn(accountingFacade);
        PeriodCloseHook periodCloseHook = mock(PeriodCloseHook.class);
        AccountingPeriodSnapshotService snapshotService = mock(AccountingPeriodSnapshotService.class);

        AccountingPeriodService service = new AccountingPeriodService(
                periodRepository,
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

        JournalEntry closing = new JournalEntry();
        closing.setStatus("POSTED");
        AccountingPeriod period = period(company(84L, "TRUTH"), 2026, 2);

        ReflectionTestUtils.invokeMethod(
                service,
                "reverseClosingJournalIfNeeded",
                closing,
                period,
                "reopen adjustment"
        );

        verify(accountingFacadeProvider).getObject();
        verify(accountingFacade).reverseClosingEntryForPeriodReopen(closing, period, "reopen adjustment");
    }

    @Test
    void reopenPeriod_trimsReasonBeforePersistAndReverse() {
        AccountingPeriodRepository periodRepository = mock(AccountingPeriodRepository.class);
        CompanyContextService companyContextService = mock(CompanyContextService.class);
        JournalEntryRepository journalEntryRepository = mock(JournalEntryRepository.class);
        CompanyEntityLookup companyEntityLookup = mock(CompanyEntityLookup.class);
        JournalLineRepository journalLineRepository = mock(JournalLineRepository.class);
        AccountRepository accountRepository = mock(AccountRepository.class);
        CompanyClock companyClock = mock(CompanyClock.class);
        ReportService reportService = mock(ReportService.class);
        ReconciliationService reconciliationService = mock(ReconciliationService.class);
        InvoiceRepository invoiceRepository = mock(InvoiceRepository.class);
        GoodsReceiptRepository goodsReceiptRepository = mock(GoodsReceiptRepository.class);
        RawMaterialPurchaseRepository rawMaterialPurchaseRepository = mock(RawMaterialPurchaseRepository.class);
        PayrollRunRepository payrollRunRepository = mock(PayrollRunRepository.class);
        AccountingFacade accountingFacade = mock(AccountingFacade.class);
        @SuppressWarnings("unchecked")
        ObjectProvider<AccountingFacade> accountingFacadeProvider = mock(ObjectProvider.class);
        when(accountingFacadeProvider.getObject()).thenReturn(accountingFacade);
        PeriodCloseHook periodCloseHook = mock(PeriodCloseHook.class);
        AccountingPeriodSnapshotService snapshotService = mock(AccountingPeriodSnapshotService.class);

        AccountingPeriodService service = new AccountingPeriodService(
                periodRepository,
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

        Company company = company(99L, "TRUTH");
        AccountingPeriod period = period(company, 2026, 2);
        period.setStatus(AccountingPeriodStatus.CLOSED);
        period.setClosingJournalEntryId(7001L);
        JournalEntry closing = new JournalEntry();
        closing.setStatus("POSTED");

        when(companyContextService.requireCurrentCompany()).thenReturn(company);
        when(periodRepository.lockByCompanyAndId(company, 7001L)).thenReturn(Optional.of(period));
        when(journalEntryRepository.findByCompanyAndId(company, 7001L)).thenReturn(Optional.of(closing));
        when(periodRepository.save(period)).thenReturn(period);

        service.reopenPeriod(7001L, new AccountingPeriodReopenRequest("  runtime reopen reason  "));

        assertThat(period.getReopenReason()).isEqualTo("runtime reopen reason");
        verify(accountingFacade).reverseClosingEntryForPeriodReopen(closing, period, "runtime reopen reason");
    }

    private Company company(Long id, String code) {
        Company company = new Company();
        company.setCode(code);
        company.setName(code + " Pvt");
        company.setTimezone("Asia/Kolkata");
        ReflectionTestUtils.setField(company, "id", id);
        return company;
    }

    private AccountingPeriod period(Company company, int year, int month) {
        AccountingPeriod period = new AccountingPeriod();
        period.setCompany(company);
        period.setYear(year);
        period.setMonth(month);
        period.setStartDate(LocalDate.of(year, month, 1));
        period.setEndDate(LocalDate.of(year, month, 1).plusMonths(1).minusDays(1));
        return period;
    }

    private Account account(Long id, Company company, String code, AccountType type) {
        Account account = new Account();
        ReflectionTestUtils.setField(account, "id", id);
        account.setCompany(company);
        account.setCode(code);
        account.setName(code);
        account.setType(type);
        return account;
    }
}
