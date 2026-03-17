package com.bigbrightpaints.erp.modules.accounting.service;

import com.bigbrightpaints.erp.core.config.GitHubProperties;
import com.bigbrightpaints.erp.core.exception.ApplicationException;
import com.bigbrightpaints.erp.core.util.CompanyClock;
import com.bigbrightpaints.erp.core.util.CompanyTime;
import com.bigbrightpaints.erp.modules.accounting.domain.Account;
import com.bigbrightpaints.erp.modules.accounting.domain.AccountRepository;
import com.bigbrightpaints.erp.modules.accounting.domain.AccountType;
import com.bigbrightpaints.erp.modules.accounting.domain.AccountingPeriod;
import com.bigbrightpaints.erp.modules.accounting.domain.AccountingPeriodRepository;
import com.bigbrightpaints.erp.modules.accounting.domain.AccountingPeriodStatus;
import com.bigbrightpaints.erp.modules.accounting.domain.DealerLedgerRepository;
import com.bigbrightpaints.erp.modules.accounting.domain.JournalEntry;
import com.bigbrightpaints.erp.modules.accounting.domain.JournalEntryRepository;
import com.bigbrightpaints.erp.modules.accounting.domain.JournalLine;
import com.bigbrightpaints.erp.modules.accounting.domain.JournalLineRepository;
import com.bigbrightpaints.erp.modules.accounting.domain.JournalLineRepository.AccountLineTotals;
import com.bigbrightpaints.erp.modules.accounting.domain.PartnerType;
import com.bigbrightpaints.erp.modules.accounting.domain.ReconciliationDiscrepancy;
import com.bigbrightpaints.erp.modules.accounting.domain.ReconciliationDiscrepancyRepository;
import com.bigbrightpaints.erp.modules.accounting.domain.ReconciliationDiscrepancyResolution;
import com.bigbrightpaints.erp.modules.accounting.domain.ReconciliationDiscrepancyStatus;
import com.bigbrightpaints.erp.modules.accounting.domain.ReconciliationDiscrepancyType;
import com.bigbrightpaints.erp.modules.accounting.domain.SupplierLedgerRepository;
import com.bigbrightpaints.erp.modules.accounting.dto.BankReconciliationRequest;
import com.bigbrightpaints.erp.modules.accounting.dto.DealerBalanceView;
import com.bigbrightpaints.erp.modules.accounting.dto.GstReconciliationDto;
import com.bigbrightpaints.erp.modules.accounting.dto.JournalCreationRequest;
import com.bigbrightpaints.erp.modules.accounting.dto.JournalEntryDto;
import com.bigbrightpaints.erp.modules.accounting.dto.ReconciliationDiscrepancyDto;
import com.bigbrightpaints.erp.modules.accounting.dto.ReconciliationDiscrepancyListResponse;
import com.bigbrightpaints.erp.modules.accounting.dto.ReconciliationDiscrepancyResolveRequest;
import com.bigbrightpaints.erp.modules.accounting.dto.SupplierBalanceView;
import com.bigbrightpaints.erp.modules.admin.service.GitHubIssueClient;
import com.bigbrightpaints.erp.modules.company.domain.Company;
import com.bigbrightpaints.erp.modules.company.domain.CompanyRepository;
import com.bigbrightpaints.erp.modules.company.service.CompanyContextService;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.bigbrightpaints.erp.modules.purchasing.domain.Supplier;
import com.bigbrightpaints.erp.modules.purchasing.domain.SupplierRepository;
import com.bigbrightpaints.erp.modules.reports.service.ReportService;
import com.bigbrightpaints.erp.modules.sales.domain.Dealer;
import com.bigbrightpaints.erp.modules.sales.domain.DealerRepository;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.util.ReflectionTestUtils;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@Tag("critical")
class ReconciliationServiceTest {

    @Mock private CompanyContextService companyContextService;
    @Mock private CompanyRepository companyRepository;
    @Mock private AccountRepository accountRepository;
    @Mock private DealerRepository dealerRepository;
    @Mock private DealerLedgerRepository dealerLedgerRepository;
    @Mock private SupplierRepository supplierRepository;
    @Mock private SupplierLedgerRepository supplierLedgerRepository;
    @Mock private JournalEntryRepository journalEntryRepository;
    @Mock private JournalLineRepository journalLineRepository;
    @Mock private TemporalBalanceService temporalBalanceService;
    @Mock private ReconciliationDiscrepancyRepository reconciliationDiscrepancyRepository;
    @Mock private AccountingPeriodRepository accountingPeriodRepository;
    @Mock private TaxService taxService;
    @Mock private ReportService reportService;
    @Mock private CompanyClock companyClock;

    private ReconciliationService reconciliationService;
    private Company company;

    private ObjectProvider<AccountingFacade> accountingFacadeProvider;
    private AccountingFacade accountingFacade;

    @BeforeEach
    void setUp() {
        SecurityContextHolder.clearContext();
        @SuppressWarnings("unchecked")
        ObjectProvider<AccountingFacade> provider = mock(ObjectProvider.class);
        accountingFacadeProvider = provider;
        accountingFacade = mock(AccountingFacade.class);
        lenient().when(accountingFacadeProvider.getObject()).thenReturn(accountingFacade);

        reconciliationService = new ReconciliationService(
                companyContextService,
                companyRepository,
                accountRepository,
                dealerRepository,
                dealerLedgerRepository,
                supplierRepository,
                supplierLedgerRepository,
                journalEntryRepository,
                journalLineRepository,
                temporalBalanceService,
                reconciliationDiscrepancyRepository,
                accountingPeriodRepository,
                taxService,
                reportService,
                accountingFacadeProvider
        );
        company = new Company();
        company.setCode("ACME");
        company.setTimezone("Asia/Kolkata");
        ReflectionTestUtils.setField(company, "id", 1L);
        new CompanyTime(companyClock);
        lenient().when(companyClock.today(company)).thenReturn(LocalDate.of(2026, 3, 18));
        lenient().when(companyContextService.requireCurrentCompany()).thenReturn(company);
    }

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void reconcileArWithDealerLedger_reportsDiscrepanciesPerDealer() {
        Account receivable = new Account();
        ReflectionTestUtils.setField(receivable, "id", 10L);
        receivable.setType(AccountType.ASSET);
        receivable.setCode("AR-CONTROL");
        receivable.setBalance(new BigDecimal("260.00"));

        Dealer firstDealer = new Dealer();
        ReflectionTestUtils.setField(firstDealer, "id", 1L);
        firstDealer.setCode("D-1");
        firstDealer.setName("Dealer One");
        firstDealer.setOutstandingBalance(new BigDecimal("120.00"));
        firstDealer.setReceivableAccount(receivable);

        Dealer secondDealer = new Dealer();
        ReflectionTestUtils.setField(secondDealer, "id", 2L);
        secondDealer.setCode("D-2");
        secondDealer.setName("Dealer Two");
        secondDealer.setOutstandingBalance(new BigDecimal("140.00"));
        secondDealer.setReceivableAccount(receivable);

        when(accountRepository.findByCompanyOrderByCodeAsc(company)).thenReturn(List.of(receivable));
        when(dealerRepository.findByCompanyOrderByNameAsc(company)).thenReturn(List.of(firstDealer, secondDealer));
        when(dealerLedgerRepository.aggregateBalances(company, List.of(1L, 2L))).thenReturn(List.of(
                new DealerBalanceView(1L, new BigDecimal("100.00")),
                new DealerBalanceView(2L, new BigDecimal("170.00"))
        ));

        ReconciliationService.ReconciliationResult result = reconciliationService.reconcileArWithDealerLedger();

        assertThat(result.discrepancies()).hasSize(2);
        assertThat(result.discrepancies().get(0).dealerId()).isEqualTo(1L);
        assertThat(result.discrepancies().get(0).variance()).isEqualByComparingTo("20.00");
        assertThat(result.discrepancies().get(1).dealerId()).isEqualTo(2L);
        assertThat(result.discrepancies().get(1).variance()).isEqualByComparingTo("-30.00");
    }

    @Test
    void reconcileBankAccount_matchesClearedReferencesAndReportsUnclearedVariance() {
        Account bank = new Account();
        ReflectionTestUtils.setField(bank, "id", 99L);
        bank.setCode("BANK-MAIN");
        bank.setName("Main Bank");
        bank.setType(AccountType.ASSET);
        bank.setBalance(new BigDecimal("1300.00"));

        when(accountRepository.findByCompanyAndId(company, 99L)).thenReturn(Optional.of(bank));
        when(temporalBalanceService.getBalanceAsOfDate(99L, LocalDate.of(2026, 2, 28)))
                .thenReturn(new BigDecimal("1000.00"));

        JournalEntry depositEntry = new JournalEntry();
        ReflectionTestUtils.setField(depositEntry, "id", 501L);
        depositEntry.setReferenceNumber("DEP-1");
        depositEntry.setEntryDate(LocalDate.of(2026, 2, 5));
        depositEntry.setMemo("Deposit in transit");

        JournalLine depositLine = new JournalLine();
        depositLine.setJournalEntry(depositEntry);
        depositLine.setDebit(new BigDecimal("300.00"));
        depositLine.setCredit(BigDecimal.ZERO);

        JournalEntry checkEntry = new JournalEntry();
        ReflectionTestUtils.setField(checkEntry, "id", 502L);
        checkEntry.setReferenceNumber("CHK-1");
        checkEntry.setEntryDate(LocalDate.of(2026, 2, 6));
        checkEntry.setMemo("Outstanding cheque");

        JournalLine checkLine = new JournalLine();
        checkLine.setJournalEntry(checkEntry);
        checkLine.setDebit(BigDecimal.ZERO);
        checkLine.setCredit(new BigDecimal("200.00"));

        JournalEntry clearedEntry = new JournalEntry();
        ReflectionTestUtils.setField(clearedEntry, "id", 503L);
        clearedEntry.setReferenceNumber("CLR-1");
        clearedEntry.setEntryDate(LocalDate.of(2026, 2, 7));
        clearedEntry.setMemo("Cleared movement");

        JournalLine clearedLine = new JournalLine();
        clearedLine.setJournalEntry(clearedEntry);
        clearedLine.setDebit(new BigDecimal("50.00"));
        clearedLine.setCredit(BigDecimal.ZERO);

        when(journalLineRepository.findLinesForAccountBetween(
                company,
                99L,
                LocalDate.of(2026, 2, 1),
                LocalDate.of(2026, 2, 28)
        )).thenReturn(List.of(depositLine, checkLine, clearedLine));

        BankReconciliationRequest request = new BankReconciliationRequest(
                99L,
                LocalDate.of(2026, 2, 28),
                new BigDecimal("900.00"),
                LocalDate.of(2026, 2, 1),
                LocalDate.of(2026, 2, 28),
                List.of("CLR-1"),
                null,
                null,
                null
        );

        var result = reconciliationService.reconcileBankAccount(request);

        assertThat(result.outstandingDeposits()).isEqualByComparingTo("300.00");
        assertThat(result.outstandingChecks()).isEqualByComparingTo("200.00");
        assertThat(result.ledgerBalance()).isEqualByComparingTo("1000.00");
        assertThat(result.difference()).isEqualByComparingTo("0.00");
        assertThat(result.balanced()).isTrue();
        assertThat(result.unclearedDeposits()).hasSize(1);
        assertThat(result.unclearedChecks()).hasSize(1);
    }

    @Test
    void reconcileSubledgerBalances_includesCombinedVarianceSummary() {
        Account receivable = new Account();
        ReflectionTestUtils.setField(receivable, "id", 11L);
        receivable.setType(AccountType.ASSET);
        receivable.setCode("AR-CONTROL");
        receivable.setBalance(new BigDecimal("500.00"));

        Account payable = new Account();
        ReflectionTestUtils.setField(payable, "id", 12L);
        payable.setType(AccountType.LIABILITY);
        payable.setCode("AP-CONTROL");
        payable.setBalance(new BigDecimal("-300.00"));

        Dealer dealer = new Dealer();
        ReflectionTestUtils.setField(dealer, "id", 1L);
        dealer.setCode("D-1");
        dealer.setName("Dealer");
        dealer.setOutstandingBalance(new BigDecimal("450.00"));
        dealer.setReceivableAccount(receivable);

        Supplier supplier = new Supplier();
        ReflectionTestUtils.setField(supplier, "id", 2L);
        supplier.setCode("S-1");
        supplier.setName("Supplier");
        supplier.setOutstandingBalance(new BigDecimal("250.00"));
        supplier.setPayableAccount(payable);

        AccountingPeriod openPeriod = openPeriod(99L, company, 2026, 3);
        company.setDefaultInventoryAccountId(13L);
        company.setGstPayableAccountId(14L);

        Account inventory = new Account();
        ReflectionTestUtils.setField(inventory, "id", 13L);
        inventory.setType(AccountType.ASSET);
        inventory.setCode("INV");
        inventory.setBalance(new BigDecimal("500.00"));

        when(accountingPeriodRepository.findFirstByCompanyAndStatusOrderByStartDateDesc(company, AccountingPeriodStatus.OPEN))
                .thenReturn(Optional.of(openPeriod));
        when(accountRepository.findByCompanyOrderByCodeAsc(company)).thenReturn(List.of(receivable, payable));
        when(dealerRepository.findByCompanyOrderByNameAsc(company)).thenReturn(List.of(dealer));
        when(supplierRepository.findByCompanyOrderByNameAsc(company)).thenReturn(List.of(supplier));
        when(dealerLedgerRepository.aggregateBalances(company, List.of(1L)))
                .thenReturn(List.of(new DealerBalanceView(1L, new BigDecimal("430.00"))));
        when(supplierLedgerRepository.aggregateBalances(company, List.of(2L)))
                .thenReturn(List.of(new SupplierBalanceView(2L, new BigDecimal("260.00"))));
        when(dealerLedgerRepository.aggregateBalancesBetween(company, List.of(1L), openPeriod.getStartDate(), openPeriod.getEndDate()))
                .thenReturn(List.of(new DealerBalanceView(1L, new BigDecimal("430.00"))));
        when(supplierLedgerRepository.aggregateBalancesBetween(company, List.of(2L), openPeriod.getStartDate(), openPeriod.getEndDate()))
                .thenReturn(List.of(new SupplierBalanceView(2L, new BigDecimal("260.00"))));
        when(reconciliationDiscrepancyRepository.deleteByCompanyAndAccountingPeriodAndTypeAndStatus(
                any(), any(), any(), any())).thenReturn(1);

        AccountLineTotals arTotals = mock(AccountLineTotals.class);
        when(arTotals.getTotalDebit()).thenReturn(new BigDecimal("500.00"));
        when(arTotals.getTotalCredit()).thenReturn(BigDecimal.ZERO);
        AccountLineTotals apTotals = mock(AccountLineTotals.class);
        when(apTotals.getTotalDebit()).thenReturn(BigDecimal.ZERO);
        when(apTotals.getTotalCredit()).thenReturn(new BigDecimal("300.00"));
        when(journalLineRepository.summarizeTotalsByCompanyAndAccountIdsWithin(
                eq(company),
                anyCollection(),
                eq(openPeriod.getStartDate()),
                eq(openPeriod.getEndDate()),
                eq("POSTED")))
                .thenReturn(List.of(arTotals), List.of(apTotals));

        when(reportService.inventoryValuationAsOf(openPeriod.getEndDate()))
                .thenReturn(new com.bigbrightpaints.erp.modules.reports.dto.InventoryValuationDto(
                        new BigDecimal("500.00"),
                        0,
                        "WEIGHTED_AVERAGE",
                        List.of(),
                        List.of(),
                        List.of(),
                        null));
        when(accountRepository.findByCompanyAndId(company, 13L)).thenReturn(Optional.of(inventory));

        GstReconciliationDto gst = gstReconciliation("0.00", "0.00", "0.00");
        when(taxService.generateGstReconciliation(YearMonth.from(openPeriod.getStartDate()))).thenReturn(gst);

        ReconciliationService.SubledgerReconciliationReport report = reconciliationService.reconcileSubledgerBalances();

        verify(journalLineRepository).summarizeTotalsByCompanyAndAccountIdsWithin(
                eq(company),
                eq(List.of(11L)),
                eq(openPeriod.getStartDate()),
                eq(openPeriod.getEndDate()),
                eq("POSTED"));
        verify(journalLineRepository).summarizeTotalsByCompanyAndAccountIdsWithin(
                eq(company),
                eq(List.of(12L)),
                eq(openPeriod.getStartDate()),
                eq(openPeriod.getEndDate()),
                eq("POSTED"));

        assertThat(report.dealerReconciliation().variance()).isEqualByComparingTo("70.00");
        assertThat(report.supplierReconciliation().variance()).isEqualByComparingTo("40.00");
        assertThat(report.combinedVariance()).isEqualByComparingTo("110.00");
        assertThat(report.reconciled()).isFalse();
        verify(reconciliationDiscrepancyRepository).deleteByCompanyAndAccountingPeriodAndTypeAndStatus(
                company, openPeriod, ReconciliationDiscrepancyType.INVENTORY, ReconciliationDiscrepancyStatus.OPEN);
        verify(reconciliationDiscrepancyRepository).deleteByCompanyAndAccountingPeriodAndTypeAndStatus(
                company, openPeriod, ReconciliationDiscrepancyType.GST, ReconciliationDiscrepancyStatus.OPEN);
    }

    @Test
    void reconcileSubledgerBalances_createsOpenDiscrepanciesForControlVariances() {
        Account receivable = account(111L, "AR", AccountType.ASSET, new BigDecimal("500.00"));
        Account payable = account(112L, "AP", AccountType.LIABILITY, new BigDecimal("-300.00"));
        Account inventory = account(113L, "INV", AccountType.ASSET, new BigDecimal("500.00"));

        Dealer dealer = dealer(31L, "D-1", "Dealer", "450.00");
        dealer.setReceivableAccount(receivable);
        Supplier supplier = supplier(41L, "S-1", "Supplier", "250.00");
        supplier.setPayableAccount(payable);

        AccountingPeriod openPeriod = openPeriod(77L, company, 2026, 3);
        company.setDefaultInventoryAccountId(113L);

        when(accountingPeriodRepository.findFirstByCompanyAndStatusOrderByStartDateDesc(company, AccountingPeriodStatus.OPEN))
                .thenReturn(Optional.of(openPeriod));
        when(accountRepository.findByCompanyOrderByCodeAsc(company)).thenReturn(List.of(receivable, payable));
        when(dealerRepository.findByCompanyOrderByNameAsc(company)).thenReturn(List.of(dealer));
        when(supplierRepository.findByCompanyOrderByNameAsc(company)).thenReturn(List.of(supplier));

        when(dealerLedgerRepository.aggregateBalances(company, List.of(31L)))
                .thenReturn(List.of(new DealerBalanceView(31L, new BigDecimal("430.00"))));
        when(supplierLedgerRepository.aggregateBalances(company, List.of(41L)))
                .thenReturn(List.of(new SupplierBalanceView(41L, new BigDecimal("260.00"))));
        when(dealerLedgerRepository.aggregateBalancesBetween(company, List.of(31L), openPeriod.getStartDate(), openPeriod.getEndDate()))
                .thenReturn(List.of(new DealerBalanceView(31L, new BigDecimal("430.00"))));
        when(supplierLedgerRepository.aggregateBalancesBetween(company, List.of(41L), openPeriod.getStartDate(), openPeriod.getEndDate()))
                .thenReturn(List.of(new SupplierBalanceView(41L, new BigDecimal("260.00"))));

        AccountLineTotals arTotals = mock(AccountLineTotals.class);
        when(arTotals.getTotalDebit()).thenReturn(new BigDecimal("500.00"));
        when(arTotals.getTotalCredit()).thenReturn(BigDecimal.ZERO);
        AccountLineTotals apTotals = mock(AccountLineTotals.class);
        when(apTotals.getTotalDebit()).thenReturn(BigDecimal.ZERO);
        when(apTotals.getTotalCredit()).thenReturn(new BigDecimal("300.00"));
        when(journalLineRepository.summarizeTotalsByCompanyAndAccountIdsWithin(
                eq(company),
                anyCollection(),
                eq(openPeriod.getStartDate()),
                eq(openPeriod.getEndDate()),
                eq("POSTED")))
                .thenReturn(List.of(arTotals), List.of(apTotals));

        when(reportService.inventoryValuationAsOf(openPeriod.getEndDate()))
                .thenReturn(new com.bigbrightpaints.erp.modules.reports.dto.InventoryValuationDto(
                        new BigDecimal("420.00"),
                        0,
                        "WEIGHTED_AVERAGE",
                        List.of(),
                        List.of(),
                        List.of(),
                        null));
        when(accountRepository.findByCompanyAndId(company, 113L)).thenReturn(Optional.of(inventory));

        when(taxService.generateGstReconciliation(YearMonth.from(openPeriod.getStartDate())))
                .thenReturn(gstReconciliation("30.00", "15.00", "15.00"));

        when(reconciliationDiscrepancyRepository.deleteByCompanyAndAccountingPeriodAndTypeAndStatus(
                any(), any(), any(), any())).thenReturn(1);
        when(reconciliationDiscrepancyRepository.save(any(ReconciliationDiscrepancy.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));

        reconciliationService.reconcileSubledgerBalances();

        ArgumentCaptor<ReconciliationDiscrepancy> discrepancyCaptor =
                ArgumentCaptor.forClass(ReconciliationDiscrepancy.class);
        verify(reconciliationDiscrepancyRepository, times(4)).save(discrepancyCaptor.capture());
        List<ReconciliationDiscrepancy> saved = discrepancyCaptor.getAllValues();

        assertThat(saved)
                .extracting(ReconciliationDiscrepancy::getType)
                .containsExactlyInAnyOrder(
                        ReconciliationDiscrepancyType.AR,
                        ReconciliationDiscrepancyType.AP,
                        ReconciliationDiscrepancyType.INVENTORY,
                        ReconciliationDiscrepancyType.GST);
        assertThat(saved)
                .extracting(ReconciliationDiscrepancy::getStatus)
                .containsOnly(ReconciliationDiscrepancyStatus.OPEN);

        ReconciliationDiscrepancy inventoryDiscrepancy = saved.stream()
                .filter(item -> item.getType() == ReconciliationDiscrepancyType.INVENTORY)
                .findFirst()
                .orElseThrow();
        assertThat(inventoryDiscrepancy.getExpectedAmount()).isEqualByComparingTo("500.00");
        assertThat(inventoryDiscrepancy.getActualAmount()).isEqualByComparingTo("420.00");
        assertThat(inventoryDiscrepancy.getVariance()).isEqualByComparingTo("-80.00");

        ReconciliationDiscrepancy gstDiscrepancy = saved.stream()
                .filter(item -> item.getType() == ReconciliationDiscrepancyType.GST)
                .findFirst()
                .orElseThrow();
        assertThat(gstDiscrepancy.getExpectedAmount()).isEqualByComparingTo("30.00");
        assertThat(gstDiscrepancy.getActualAmount()).isEqualByComparingTo("15.00");
        assertThat(gstDiscrepancy.getVariance()).isEqualByComparingTo("15.00");
    }

    @Test
    void reconcileSubledgerBalances_withoutOpenPeriod_skipsDiscrepancySync() {
        Account receivable = account(121L, "AR", AccountType.ASSET, new BigDecimal("120.00"));
        Dealer dealer = dealer(51L, "D-5", "Dealer Five", "100.00");
        dealer.setReceivableAccount(receivable);

        when(accountingPeriodRepository.findFirstByCompanyAndStatusOrderByStartDateDesc(company, AccountingPeriodStatus.OPEN))
                .thenReturn(Optional.empty());
        when(accountRepository.findByCompanyOrderByCodeAsc(company)).thenReturn(List.of(receivable));
        when(dealerRepository.findByCompanyOrderByNameAsc(company)).thenReturn(List.of(dealer));
        when(supplierRepository.findByCompanyOrderByNameAsc(company)).thenReturn(List.of());
        when(dealerLedgerRepository.aggregateBalances(company, List.of(51L)))
                .thenReturn(List.of(new DealerBalanceView(51L, new BigDecimal("100.00"))));

        ReconciliationService.SubledgerReconciliationReport report = reconciliationService.reconcileSubledgerBalances();

        assertThat(report.dealerReconciliation().variance()).isEqualByComparingTo("20.00");
        verify(reconciliationDiscrepancyRepository, never()).save(any(ReconciliationDiscrepancy.class));
        verify(reconciliationDiscrepancyRepository, never())
                .deleteByCompanyAndAccountingPeriodAndTypeAndStatus(any(), any(), any(), any());
    }

    @Test
    void interCompanyReconcile_bidirectionalBalancesMatch_reportsMatchedItems() {
        Company companyA = company(11L, "COMP-A", "Company A");
        Company companyB = company(22L, "COMP-B", "Company B");

        Dealer aReceivableFromB = dealer(101L, "COMP-B", "Company B Dealer", "150.00");
        Supplier bPayableToA = supplier(201L, "COMP-A", "Company A Supplier", "150.00");

        Dealer bReceivableFromA = dealer(102L, "COMP-A", "Company A Dealer", "80.00");
        Supplier aPayableToB = supplier(202L, "COMP-B", "Company B Supplier", "80.00");

        when(companyRepository.findById(11L)).thenReturn(Optional.of(companyA));
        when(companyRepository.findById(22L)).thenReturn(Optional.of(companyB));

        when(dealerRepository.findByCompanyAndCodeIgnoreCase(companyA, "COMP-B"))
                .thenReturn(Optional.of(aReceivableFromB));
        when(supplierRepository.findByCompanyAndCodeIgnoreCase(companyB, "COMP-A"))
                .thenReturn(Optional.of(bPayableToA));
        when(dealerRepository.findByCompanyAndCodeIgnoreCase(companyB, "COMP-A"))
                .thenReturn(Optional.of(bReceivableFromA));
        when(supplierRepository.findByCompanyAndCodeIgnoreCase(companyA, "COMP-B"))
                .thenReturn(Optional.of(aPayableToB));

        ReconciliationService.InterCompanyReconciliationReport report =
                reconciliationService.interCompanyReconcile(11L, 22L);

        assertThat(report.reconciled()).isTrue();
        assertThat(report.matchedItems()).hasSize(2);
        assertThat(report.unmatchedItems()).isEmpty();
        assertThat(report.totalDiscrepancyAmount()).isEqualByComparingTo("0.00");
    }

    @Test
    void interCompanyReconcile_mismatchAndMissingCounterparty_reportsUnmatchedItems() {
        Company companyA = company(11L, "COMP-A", "Company A");
        Company companyB = company(22L, "COMP-B", "Company B");

        Dealer aReceivableFromB = dealer(101L, "COMP-B", "Company B Dealer", "150.00");
        Supplier bPayableToA = supplier(201L, "COMP-A", "Company A Supplier", "120.00");
        Supplier aPayableToB = supplier(202L, "COMP-B", "Company B Supplier", "75.00");

        when(companyRepository.findById(11L)).thenReturn(Optional.of(companyA));
        when(companyRepository.findById(22L)).thenReturn(Optional.of(companyB));

        when(dealerRepository.findByCompanyAndCodeIgnoreCase(companyA, "COMP-B"))
                .thenReturn(Optional.of(aReceivableFromB));
        when(supplierRepository.findByCompanyAndCodeIgnoreCase(companyB, "COMP-A"))
                .thenReturn(Optional.of(bPayableToA));
        when(dealerRepository.findByCompanyAndCodeIgnoreCase(companyB, "COMP-A"))
                .thenReturn(Optional.empty());
        when(supplierRepository.findByCompanyAndCodeIgnoreCase(companyA, "COMP-B"))
                .thenReturn(Optional.of(aPayableToB));

        ReconciliationService.InterCompanyReconciliationReport report =
                reconciliationService.interCompanyReconcile(11L, 22L);

        assertThat(report.reconciled()).isFalse();
        assertThat(report.matchedItems()).isEmpty();
        assertThat(report.unmatchedItems()).hasSize(2);
        assertThat(report.totalDiscrepancyAmount()).isEqualByComparingTo("105.00");
        assertThat(report.unmatchedItems())
                .extracting(ReconciliationService.InterCompanyReconciliationItem::counterpartyMissing)
                .containsExactlyInAnyOrder(false, true);
    }

    @Test
    void listDiscrepancies_returnsMappedDtosAndCounts() {
        ReconciliationDiscrepancy open = discrepancy(101L, ReconciliationDiscrepancyStatus.OPEN, ReconciliationDiscrepancyType.AR, company, null);
        ReconciliationDiscrepancy acknowledged = discrepancy(102L, ReconciliationDiscrepancyStatus.ACKNOWLEDGED, ReconciliationDiscrepancyType.GST, company, null);

        when(reconciliationDiscrepancyRepository.findFiltered(company, ReconciliationDiscrepancyStatus.OPEN, ReconciliationDiscrepancyType.AR))
                .thenReturn(List.of(open));
        when(reconciliationDiscrepancyRepository.findFiltered(company, null, null))
                .thenReturn(List.of(open, acknowledged));

        ReconciliationDiscrepancyListResponse filtered = reconciliationService.listDiscrepancies(
                ReconciliationDiscrepancyStatus.OPEN,
                ReconciliationDiscrepancyType.AR);
        assertThat(filtered.items()).hasSize(1);
        assertThat(filtered.openCount()).isEqualTo(1);
        assertThat(filtered.resolvedCount()).isEqualTo(0);
        assertThat(filtered.items().get(0).status()).isEqualTo("OPEN");

        ReconciliationDiscrepancyListResponse all = reconciliationService.listDiscrepancies(null, null);
        assertThat(all.items()).hasSize(2);
        assertThat(all.openCount()).isEqualTo(1);
        assertThat(all.resolvedCount()).isEqualTo(1);
    }

    @Test
    void resolveDiscrepancy_acknowledged_setsStatusWithoutJournal() {
        ReconciliationDiscrepancy discrepancy = discrepancy(201L, ReconciliationDiscrepancyStatus.OPEN,
                ReconciliationDiscrepancyType.AR, company, null);
        when(reconciliationDiscrepancyRepository.findByCompanyAndId(company, 201L))
                .thenReturn(Optional.of(discrepancy));
        when(reconciliationDiscrepancyRepository.save(discrepancy)).thenReturn(discrepancy);

        ReconciliationDiscrepancyDto resolved = reconciliationService.resolveDiscrepancy(
                201L,
                new ReconciliationDiscrepancyResolveRequest(
                        ReconciliationDiscrepancyResolution.ACKNOWLEDGED,
                        "  reviewed  ",
                        null));

        assertThat(resolved.status()).isEqualTo("ACKNOWLEDGED");
        assertThat(resolved.resolution()).isEqualTo("ACKNOWLEDGED");
        assertThat(resolved.resolutionJournalId()).isNull();
        assertThat(resolved.resolutionNote()).isEqualTo("reviewed");
        assertThat(resolved.resolvedBy()).isEqualTo("UNKNOWN_AUTH_ACTOR");
        verify(accountingFacadeProvider, never()).getObject();
    }

    @Test
    void resolveDiscrepancy_adjustmentJournal_createsJournalAndMarksAdjusted() {
        Account arControl = account(701L, "AR", AccountType.ASSET, new BigDecimal("600.00"));
        Account adjustment = account(702L, "REV-ADJ", AccountType.REVENUE, BigDecimal.ZERO);
        ReconciliationDiscrepancy discrepancy = discrepancy(
                202L,
                ReconciliationDiscrepancyStatus.OPEN,
                ReconciliationDiscrepancyType.AR,
                company,
                new BigDecimal("25.00"));

        when(reconciliationDiscrepancyRepository.findByCompanyAndId(company, 202L))
                .thenReturn(Optional.of(discrepancy));
        when(accountRepository.findByCompanyAndId(company, 702L)).thenReturn(Optional.of(adjustment));
        when(accountRepository.findByCompanyAndCodeIgnoreCase(company, "AR")).thenReturn(Optional.of(arControl));
        when(accountingFacade.createStandardJournal(any(JournalCreationRequest.class)))
                .thenReturn(journalEntryDto(9001L, "RECON-ADJUSTMENT_JOURNAL-202"));

        JournalEntry created = new JournalEntry();
        ReflectionTestUtils.setField(created, "id", 9001L);
        when(journalEntryRepository.findByCompanyAndId(company, 9001L)).thenReturn(Optional.of(created));
        when(reconciliationDiscrepancyRepository.save(discrepancy)).thenReturn(discrepancy);

        ReconciliationDiscrepancyDto resolved = reconciliationService.resolveDiscrepancy(
                202L,
                new ReconciliationDiscrepancyResolveRequest(
                        ReconciliationDiscrepancyResolution.ADJUSTMENT_JOURNAL,
                        "variance booking",
                        702L));

        assertThat(resolved.status()).isEqualTo("ADJUSTED");
        assertThat(resolved.resolution()).isEqualTo("ADJUSTMENT_JOURNAL");
        assertThat(resolved.resolutionJournalId()).isEqualTo(9001L);
        assertThat(resolved.resolvedBy()).isEqualTo("UNKNOWN_AUTH_ACTOR");

        ArgumentCaptor<JournalCreationRequest> requestCaptor = ArgumentCaptor.forClass(JournalCreationRequest.class);
        verify(accountingFacade).createStandardJournal(requestCaptor.capture());
        assertThat(requestCaptor.getValue().entryDate()).isEqualTo(LocalDate.of(2026, 3, 18));
    }

    @Test
    void gitHubIssueClient_fetchIssueState_usesCompanyTimeForSyncTimestamp() {
        Instant syncedAt = Instant.parse("2026-03-18T01:00:00Z");
        when(companyClock.now((Company) null)).thenReturn(syncedAt);

        GitHubProperties gitHubProperties = new GitHubProperties();
        gitHubProperties.setEnabled(true);
        gitHubProperties.setToken("token");
        gitHubProperties.setRepoOwner("acme");
        gitHubProperties.setRepoName("repo");

        org.springframework.boot.web.client.RestTemplateBuilder restTemplateBuilder =
                mock(org.springframework.boot.web.client.RestTemplateBuilder.class);
        org.springframework.web.client.RestTemplate restTemplate =
                mock(org.springframework.web.client.RestTemplate.class);
        when(restTemplateBuilder.build()).thenReturn(restTemplate);
        when(restTemplate.exchange(
                eq("https://api.github.com/repos/acme/repo/issues/55"),
                eq(org.springframework.http.HttpMethod.GET),
                any(org.springframework.http.HttpEntity.class),
                eq(String.class)))
                .thenReturn(org.springframework.http.ResponseEntity.ok(
                        "{\"html_url\":\"https://github.com/acme/repo/issues/55\",\"state\":\"closed\"}"));

        GitHubIssueClient client = new GitHubIssueClient(
                gitHubProperties,
                restTemplateBuilder,
                new ObjectMapper());

        GitHubIssueClient.GitHubIssueStateResult result = client.fetchIssueState(55L);

        assertThat(result.issueNumber()).isEqualTo(55L);
        assertThat(result.issueUrl()).isEqualTo("https://github.com/acme/repo/issues/55");
        assertThat(result.issueState()).isEqualTo("CLOSED");
        assertThat(result.syncedAt()).isEqualTo(syncedAt);
    }

    @Test
    void gitHubIssueClient_createIssue_usesCompanyTimeForSyncTimestamp() {
        Instant syncedAt = Instant.parse("2026-03-18T01:05:00Z");
        when(companyClock.now((Company) null)).thenReturn(syncedAt);

        GitHubProperties gitHubProperties = new GitHubProperties();
        gitHubProperties.setEnabled(true);
        gitHubProperties.setToken("token");
        gitHubProperties.setRepoOwner("acme");
        gitHubProperties.setRepoName("repo");

        org.springframework.boot.web.client.RestTemplateBuilder restTemplateBuilder =
                mock(org.springframework.boot.web.client.RestTemplateBuilder.class);
        org.springframework.web.client.RestTemplate restTemplate =
                mock(org.springframework.web.client.RestTemplate.class);
        when(restTemplateBuilder.build()).thenReturn(restTemplate);
        when(restTemplate.exchange(
                eq("https://api.github.com/repos/acme/repo/issues"),
                eq(org.springframework.http.HttpMethod.POST),
                any(org.springframework.http.HttpEntity.class),
                eq(String.class)))
                .thenReturn(org.springframework.http.ResponseEntity.ok(
                        "{\"number\":99,\"html_url\":\"https://github.com/acme/repo/issues/99\",\"state\":\"open\"}"));

        GitHubIssueClient client = new GitHubIssueClient(
                gitHubProperties,
                restTemplateBuilder,
                new ObjectMapper());

        GitHubIssueClient.GitHubIssueCreateResult result = client.createIssue("Title", "Body", List.of("bug"));

        assertThat(result.issueNumber()).isEqualTo(99L);
        assertThat(result.issueUrl()).isEqualTo("https://github.com/acme/repo/issues/99");
        assertThat(result.issueState()).isEqualTo("OPEN");
        assertThat(result.syncedAt()).isEqualTo(syncedAt);
    }

    @Test
    void resolveDiscrepancy_writeOff_createsJournalAndMarksResolved() {
        Account inventoryControl = account(711L, "INV", AccountType.ASSET, new BigDecimal("250.00"));
        Account writeOff = account(712L, "INV-WOFF", AccountType.EXPENSE, BigDecimal.ZERO);
        ReconciliationDiscrepancy discrepancy = discrepancy(
                203L,
                ReconciliationDiscrepancyStatus.OPEN,
                ReconciliationDiscrepancyType.INVENTORY,
                company,
                new BigDecimal("12.50"));

        when(reconciliationDiscrepancyRepository.findByCompanyAndId(company, 203L))
                .thenReturn(Optional.of(discrepancy));
        when(accountRepository.findByCompanyAndId(company, 712L)).thenReturn(Optional.of(writeOff));
        when(accountRepository.findByCompanyAndCodeIgnoreCase(company, "INV")).thenReturn(Optional.of(inventoryControl));
        when(accountingFacade.createStandardJournal(any(JournalCreationRequest.class)))
                .thenReturn(journalEntryDto(9002L, "RECON-WRITE_OFF-203"));

        JournalEntry created = new JournalEntry();
        ReflectionTestUtils.setField(created, "id", 9002L);
        when(journalEntryRepository.findByCompanyAndId(company, 9002L)).thenReturn(Optional.of(created));
        when(reconciliationDiscrepancyRepository.save(discrepancy)).thenReturn(discrepancy);

        ReconciliationDiscrepancyDto resolved = reconciliationService.resolveDiscrepancy(
                203L,
                new ReconciliationDiscrepancyResolveRequest(
                        ReconciliationDiscrepancyResolution.WRITE_OFF,
                        "stock write-off",
                        712L));

        assertThat(resolved.status()).isEqualTo("RESOLVED");
        assertThat(resolved.resolution()).isEqualTo("WRITE_OFF");
        assertThat(resolved.resolutionJournalId()).isEqualTo(9002L);
        verify(accountingFacade).createStandardJournal(any(JournalCreationRequest.class));
    }

    @Test
    void resolveDiscrepancy_rejectsMissingAdjustmentAccountForJournalResolutions() {
        ReconciliationDiscrepancy discrepancy = discrepancy(
                204L,
                ReconciliationDiscrepancyStatus.OPEN,
                ReconciliationDiscrepancyType.AP,
                company,
                new BigDecimal("18.00"));
        when(reconciliationDiscrepancyRepository.findByCompanyAndId(company, 204L))
                .thenReturn(Optional.of(discrepancy));

        assertThatThrownBy(() -> reconciliationService.resolveDiscrepancy(
                204L,
                new ReconciliationDiscrepancyResolveRequest(
                        ReconciliationDiscrepancyResolution.ADJUSTMENT_JOURNAL,
                        "missing account",
                        null)))
                .isInstanceOf(ApplicationException.class)
                .hasMessageContaining("adjustmentAccountId is required for ADJUSTMENT_JOURNAL");
    }

    @Test
    void resolveDiscrepancy_rejectsAlreadyResolvedDiscrepancy() {
        ReconciliationDiscrepancy discrepancy = discrepancy(
                205L,
                ReconciliationDiscrepancyStatus.RESOLVED,
                ReconciliationDiscrepancyType.GST,
                company,
                new BigDecimal("4.00"));
        when(reconciliationDiscrepancyRepository.findByCompanyAndId(company, 205L))
                .thenReturn(Optional.of(discrepancy));

        assertThatThrownBy(() -> reconciliationService.resolveDiscrepancy(
                205L,
                new ReconciliationDiscrepancyResolveRequest(
                        ReconciliationDiscrepancyResolution.ACKNOWLEDGED,
                        "duplicate",
                        null)))
                .isInstanceOf(ApplicationException.class)
                .hasMessageContaining("Only OPEN discrepancies can be resolved");
    }

    @Test
    void resolveDiscrepancy_throwsWhenJournalCreationReturnsNullId() {
        Account apControl = account(721L, "AP", AccountType.LIABILITY, new BigDecimal("-500.00"));
        Account adjustment = account(722L, "AP-ADJ", AccountType.EXPENSE, BigDecimal.ZERO);
        ReconciliationDiscrepancy discrepancy = discrepancy(
                206L,
                ReconciliationDiscrepancyStatus.OPEN,
                ReconciliationDiscrepancyType.AP,
                company,
                new BigDecimal("9.00"));

        when(reconciliationDiscrepancyRepository.findByCompanyAndId(company, 206L))
                .thenReturn(Optional.of(discrepancy));
        when(accountRepository.findByCompanyAndId(company, 722L)).thenReturn(Optional.of(adjustment));
        when(accountRepository.findByCompanyAndCodeIgnoreCase(company, "AP")).thenReturn(Optional.of(apControl));
        when(accountingFacade.createStandardJournal(any(JournalCreationRequest.class)))
                .thenReturn(new JournalEntryDto(
                        null,
                        null,
                        null,
                        LocalDate.now(),
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
                        List.of(),
                        null,
                        null,
                        null,
                        null,
                        null,
                        null));

        assertThatThrownBy(() -> reconciliationService.resolveDiscrepancy(
                206L,
                new ReconciliationDiscrepancyResolveRequest(
                        ReconciliationDiscrepancyResolution.ADJUSTMENT_JOURNAL,
                        "bad journal",
                        722L)))
                .isInstanceOf(ApplicationException.class)
                .hasMessageContaining("Failed to create discrepancy resolution journal");
    }

    @Test
    void resolveDiscrepancy_usesGstPayableControlAccountForGstType() {
        company.setGstPayableAccountId(731L);
        Account gstPayable = account(731L, "GST-PAYABLE", AccountType.LIABILITY, new BigDecimal("200.00"));
        Account writeOff = account(732L, "GST-WOFF", AccountType.EXPENSE, BigDecimal.ZERO);
        ReconciliationDiscrepancy discrepancy = discrepancy(
                207L,
                ReconciliationDiscrepancyStatus.OPEN,
                ReconciliationDiscrepancyType.GST,
                company,
                new BigDecimal("6.00"));

        when(reconciliationDiscrepancyRepository.findByCompanyAndId(company, 207L))
                .thenReturn(Optional.of(discrepancy));
        when(accountRepository.findByCompanyAndId(company, 732L)).thenReturn(Optional.of(writeOff));
        when(accountRepository.findByCompanyAndId(company, 731L)).thenReturn(Optional.of(gstPayable));
        when(accountingFacade.createStandardJournal(any(JournalCreationRequest.class)))
                .thenReturn(journalEntryDto(9007L, "RECON-WRITE_OFF-207"));

        JournalEntry created = new JournalEntry();
        ReflectionTestUtils.setField(created, "id", 9007L);
        when(journalEntryRepository.findByCompanyAndId(company, 9007L)).thenReturn(Optional.of(created));
        when(reconciliationDiscrepancyRepository.save(discrepancy)).thenReturn(discrepancy);

        ReconciliationDiscrepancyDto resolved = reconciliationService.resolveDiscrepancy(
                207L,
                new ReconciliationDiscrepancyResolveRequest(
                        ReconciliationDiscrepancyResolution.WRITE_OFF,
                        "gst write-off",
                        732L));

        assertThat(resolved.status()).isEqualTo("RESOLVED");
        verify(accountingFacade).createStandardJournal(any(JournalCreationRequest.class));
    }

    @Test
    void resolveDiscrepancy_rejectsGstResolutionWhenPayableAccountMissing() {
        company.setGstPayableAccountId(null);
        Account writeOff = account(733L, "GST-WOFF", AccountType.EXPENSE, BigDecimal.ZERO);
        ReconciliationDiscrepancy discrepancy = discrepancy(
                208L,
                ReconciliationDiscrepancyStatus.OPEN,
                ReconciliationDiscrepancyType.GST,
                company,
                new BigDecimal("3.00"));

        when(reconciliationDiscrepancyRepository.findByCompanyAndId(company, 208L))
                .thenReturn(Optional.of(discrepancy));
        when(accountRepository.findByCompanyAndId(company, 733L)).thenReturn(Optional.of(writeOff));

        assertThatThrownBy(() -> reconciliationService.resolveDiscrepancy(
                208L,
                new ReconciliationDiscrepancyResolveRequest(
                        ReconciliationDiscrepancyResolution.WRITE_OFF,
                        "gst missing",
                        733L)))
                .isInstanceOf(ApplicationException.class)
                .hasMessageContaining("GST payable account not configured");
    }

    private Company company(Long id, String code, String name) {
        Company value = new Company();
        ReflectionTestUtils.setField(value, "id", id);
        value.setCode(code);
        value.setName(name);
        return value;
    }

    private Dealer dealer(Long id, String code, String name, String outstandingBalance) {
        Dealer value = new Dealer();
        ReflectionTestUtils.setField(value, "id", id);
        value.setCode(code);
        value.setName(name);
        value.setOutstandingBalance(new BigDecimal(outstandingBalance));
        return value;
    }

    private Supplier supplier(Long id, String code, String name, String outstandingBalance) {
        Supplier value = new Supplier();
        ReflectionTestUtils.setField(value, "id", id);
        value.setCode(code);
        value.setName(name);
        value.setOutstandingBalance(new BigDecimal(outstandingBalance));
        return value;
    }

    private AccountingPeriod openPeriod(Long id, Company scopedCompany, int year, int month) {
        AccountingPeriod period = new AccountingPeriod();
        ReflectionTestUtils.setField(period, "id", id);
        period.setCompany(scopedCompany);
        period.setYear(year);
        period.setMonth(month);
        LocalDate start = LocalDate.of(year, month, 1);
        period.setStartDate(start);
        period.setEndDate(start.plusMonths(1).minusDays(1));
        period.setStatus(AccountingPeriodStatus.OPEN);
        return period;
    }

    private Account account(Long id, String code, AccountType type, BigDecimal balance) {
        Account account = new Account();
        ReflectionTestUtils.setField(account, "id", id);
        account.setCode(code);
        account.setType(type);
        account.setBalance(balance);
        return account;
    }

    private ReconciliationDiscrepancy discrepancy(Long id,
                                                  ReconciliationDiscrepancyStatus status,
                                                  ReconciliationDiscrepancyType type,
                                                  Company scopedCompany,
                                                  BigDecimal variance) {
        ReconciliationDiscrepancy discrepancy = new ReconciliationDiscrepancy();
        ReflectionTestUtils.setField(discrepancy, "id", id);
        discrepancy.setCompany(scopedCompany);
        discrepancy.setStatus(status);
        discrepancy.setType(type);
        discrepancy.setPartnerType(type == ReconciliationDiscrepancyType.AR ? PartnerType.DEALER : null);
        discrepancy.setPartnerId(type == ReconciliationDiscrepancyType.AR ? 901L : null);
        discrepancy.setPeriodStart(LocalDate.of(2026, 3, 1));
        discrepancy.setPeriodEnd(LocalDate.of(2026, 3, 31));
        discrepancy.setExpectedAmount(new BigDecimal("100.00"));
        discrepancy.setActualAmount(new BigDecimal("75.00"));
        discrepancy.setVariance(variance != null ? variance : new BigDecimal("25.00"));
        discrepancy.setResolutionNote("seeded");
        ReflectionTestUtils.setField(discrepancy, "createdAt", Instant.parse("2026-03-01T00:00:00Z"));
        ReflectionTestUtils.setField(discrepancy, "updatedAt", Instant.parse("2026-03-01T00:00:00Z"));
        return discrepancy;
    }

    private GstReconciliationDto gstReconciliation(String collectedTotal, String inputTotal, String netTotal) {
        GstReconciliationDto dto = new GstReconciliationDto();
        dto.setCollected(new GstReconciliationDto.GstComponentSummary(
                BigDecimal.ZERO,
                BigDecimal.ZERO,
                BigDecimal.ZERO,
                new BigDecimal(collectedTotal)));
        dto.setInputTaxCredit(new GstReconciliationDto.GstComponentSummary(
                BigDecimal.ZERO,
                BigDecimal.ZERO,
                BigDecimal.ZERO,
                new BigDecimal(inputTotal)));
        dto.setNetLiability(new GstReconciliationDto.GstComponentSummary(
                BigDecimal.ZERO,
                BigDecimal.ZERO,
                BigDecimal.ZERO,
                new BigDecimal(netTotal)));
        return dto;
    }

    private JournalEntryDto journalEntryDto(Long id, String referenceNumber) {
        return new JournalEntryDto(
                id,
                null,
                referenceNumber,
                LocalDate.of(2026, 3, 20),
                "recon resolution",
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
