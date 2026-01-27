package com.bigbrightpaints.erp.modules.accounting.service;

import com.bigbrightpaints.erp.core.exception.ApplicationException;
import com.bigbrightpaints.erp.core.exception.ErrorCode;
import com.bigbrightpaints.erp.core.util.CompanyClock;
import com.bigbrightpaints.erp.core.util.CompanyEntityLookup;
import com.bigbrightpaints.erp.modules.accounting.domain.Account;
import com.bigbrightpaints.erp.modules.accounting.domain.AccountRepository;
import com.bigbrightpaints.erp.modules.accounting.domain.AccountType;
import com.bigbrightpaints.erp.modules.accounting.domain.AccountingPeriod;
import com.bigbrightpaints.erp.modules.accounting.domain.AccountingPeriodStatus;
import com.bigbrightpaints.erp.modules.accounting.domain.AccountingPeriodRepository;
import com.bigbrightpaints.erp.modules.accounting.domain.JournalEntryRepository;
import com.bigbrightpaints.erp.modules.accounting.domain.JournalLineRepository;
import com.bigbrightpaints.erp.modules.accounting.dto.DealerSettlementRequest;
import com.bigbrightpaints.erp.modules.accounting.dto.JournalEntryDto;
import com.bigbrightpaints.erp.modules.accounting.dto.JournalEntryRequest;
import com.bigbrightpaints.erp.modules.accounting.dto.JournalEntryReversalRequest;
import com.bigbrightpaints.erp.modules.accounting.dto.InventoryRevaluationRequest;
import com.bigbrightpaints.erp.modules.accounting.dto.SettlementAllocationRequest;
import com.bigbrightpaints.erp.modules.accounting.dto.SettlementPaymentRequest;
import com.bigbrightpaints.erp.modules.accounting.dto.SupplierSettlementRequest;
import com.bigbrightpaints.erp.modules.company.domain.Company;
import com.bigbrightpaints.erp.modules.company.service.CompanyContextService;
import com.bigbrightpaints.erp.modules.hr.domain.PayrollRunLineRepository;
import com.bigbrightpaints.erp.modules.hr.domain.PayrollRunRepository;
import com.bigbrightpaints.erp.modules.invoice.domain.InvoiceRepository;
import com.bigbrightpaints.erp.modules.inventory.domain.FinishedGood;
import com.bigbrightpaints.erp.modules.inventory.domain.FinishedGoodBatch;
import com.bigbrightpaints.erp.modules.inventory.domain.FinishedGoodBatchRepository;
import com.bigbrightpaints.erp.modules.inventory.domain.RawMaterialBatchRepository;
import com.bigbrightpaints.erp.modules.inventory.domain.RawMaterialMovementRepository;
import com.bigbrightpaints.erp.modules.purchasing.domain.RawMaterialPurchaseRepository;
import com.bigbrightpaints.erp.modules.purchasing.domain.RawMaterialPurchase;
import com.bigbrightpaints.erp.modules.purchasing.domain.SupplierRepository;
import com.bigbrightpaints.erp.modules.purchasing.domain.Supplier;
import com.bigbrightpaints.erp.modules.sales.domain.Dealer;
import com.bigbrightpaints.erp.modules.sales.domain.DealerRepository;
import com.bigbrightpaints.erp.modules.accounting.domain.PartnerSettlementAllocationRepository;
import com.bigbrightpaints.erp.modules.invoice.service.InvoiceSettlementPolicy;
import com.bigbrightpaints.erp.core.audit.AuditService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.test.util.ReflectionTestUtils;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class AccountingServiceTest {

    @Mock
    private CompanyContextService companyContextService;
    @Mock
    private AccountRepository accountRepository;
    @Mock
    private JournalEntryRepository journalEntryRepository;
    @Mock
    private DealerLedgerService dealerLedgerService;
    @Mock
    private SupplierLedgerService supplierLedgerService;
    @Mock
    private PayrollRunRepository payrollRunRepository;
    @Mock
    private PayrollRunLineRepository payrollRunLineRepository;
    @Mock
    private ReferenceNumberService referenceNumberService;
    @Mock
    private ApplicationEventPublisher eventPublisher;
    @Mock
    private CompanyClock companyClock;
    @Mock
    private CompanyEntityLookup companyEntityLookup;
    @Mock
    private PartnerSettlementAllocationRepository settlementAllocationRepository;
    @Mock
    private RawMaterialPurchaseRepository rawMaterialPurchaseRepository;
    @Mock
    private InvoiceRepository invoiceRepository;
    @Mock
    private RawMaterialMovementRepository rawMaterialMovementRepository;
    @Mock
    private RawMaterialBatchRepository rawMaterialBatchRepository;
    @Mock
    private FinishedGoodBatchRepository finishedGoodBatchRepository;
    @Mock
    private DealerRepository dealerRepository;
    @Mock
    private SupplierRepository supplierRepository;
    @Mock
    private InvoiceSettlementPolicy invoiceSettlementPolicy;
    @Mock
    private AccountingPeriodRepository accountingPeriodRepository;
    @Mock
    private JournalLineRepository journalLineRepository;
    @Mock
    private AccountingPeriodService accountingPeriodService;
    @Mock
    private jakarta.persistence.EntityManager entityManager;
    @Mock
    private com.bigbrightpaints.erp.core.config.SystemSettingsService systemSettingsService;
    @Mock
    private AuditService auditService;

    private AccountingService accountingService;
    private Company company;

    @BeforeEach
    void setup() {
        accountingService = new AccountingService(
                companyContextService,
                accountRepository,
                journalEntryRepository,
                dealerLedgerService,
                supplierLedgerService,
                payrollRunRepository,
                payrollRunLineRepository,
                accountingPeriodService,
                referenceNumberService,
                eventPublisher,
                companyClock,
                companyEntityLookup,
                settlementAllocationRepository,
                rawMaterialPurchaseRepository,
                invoiceRepository,
                rawMaterialMovementRepository,
                rawMaterialBatchRepository,
                finishedGoodBatchRepository,
                dealerRepository,
                supplierRepository,
                invoiceSettlementPolicy,
                entityManager,
                systemSettingsService,
                auditService
        );
        company = new Company();
        company.setBaseCurrency("INR");
        lenient().when(companyContextService.requireCurrentCompany()).thenReturn(company);
        lenient().when(systemSettingsService.isPeriodLockEnforced()).thenReturn(true);
        lenient().when(accountingPeriodService.requireOpenPeriod(any(), any())).thenReturn(new AccountingPeriod());
        lenient().when(dealerRepository.findAllByCompanyAndReceivableAccount(any(), any())).thenReturn(List.of());
        lenient().when(supplierRepository.findAllByCompanyAndPayableAccount(any(), any())).thenReturn(List.of());
    }

    @Test
    void createJournalEntry_rejectsEntriesOlderThan30Days() {
        LocalDate today = LocalDate.of(2024, 1, 31);
        when(companyClock.today(company)).thenReturn(today);

        JournalEntryRequest request = new JournalEntryRequest(
                "OLD-REF",
                today.minusDays(31),
                "Old period posting",
                null,
                null,
                Boolean.FALSE,
                List.of(new JournalEntryRequest.JournalLineRequest(1L, "Old", new BigDecimal("10.00"), BigDecimal.ZERO))
        );

        assertThatThrownBy(() -> accountingService.createJournalEntry(request))
                .isInstanceOf(ApplicationException.class)
                .hasMessageContaining("Entry date cannot be more than 30 days old");
    }

    @Test
    void createJournalEntry_rejectsClosedPeriod() {
        LocalDate today = LocalDate.of(2024, 2, 1);
        when(companyClock.today(company)).thenReturn(today);
        when(accountingPeriodService.requireOpenPeriod(company, today))
                .thenThrow(new ApplicationException(ErrorCode.VALIDATION_INVALID_INPUT, "Accounting period is closed"));

        JournalEntryRequest request = new JournalEntryRequest(
                "CLOSED-REF",
                today,
                "Closed period",
                null,
                null,
                Boolean.FALSE,
                List.of(new JournalEntryRequest.JournalLineRequest(1L, "Closed", new BigDecimal("25.00"), BigDecimal.ZERO))
        );

        assertThatThrownBy(() -> accountingService.createJournalEntry(request))
                .isInstanceOf(ApplicationException.class)
                .hasMessageContaining("Accounting period is closed");
    }

    @Test
    void reverseJournalEntry_rejectsLockedPeriod() {
        LocalDate today = LocalDate.of(2024, 4, 1);
        when(companyClock.today(company)).thenReturn(today);

        AccountingPeriod lockedPeriod = new AccountingPeriod();
        lockedPeriod.setStatus(AccountingPeriodStatus.LOCKED);
        lockedPeriod.setYear(2024);
        lockedPeriod.setMonth(3);

        var entry = new com.bigbrightpaints.erp.modules.accounting.domain.JournalEntry();
        entry.setStatus("POSTED");
        entry.setReferenceNumber("REV-LOCK");
        entry.setEntryDate(today.minusDays(1));
        entry.setAccountingPeriod(lockedPeriod);

        when(companyEntityLookup.requireJournalEntry(company, 44L)).thenReturn(entry);

        JournalEntryReversalRequest request = new JournalEntryReversalRequest(
                today,
                false,
                "Test reversal",
                "Locked period reversal",
                Boolean.FALSE
        );

        assertThatThrownBy(() -> accountingService.reverseJournalEntry(44L, request))
                .isInstanceOf(ApplicationException.class)
                .hasMessageContaining("LOCKED period");
    }

    @Test
    void reverseJournalEntry_rejectsClosedPeriodWithoutOverride() {
        LocalDate today = LocalDate.of(2024, 4, 1);
        when(companyClock.today(company)).thenReturn(today);

        AccountingPeriod closedPeriod = new AccountingPeriod();
        closedPeriod.setStatus(AccountingPeriodStatus.CLOSED);
        closedPeriod.setYear(2024);
        closedPeriod.setMonth(2);

        var entry = new com.bigbrightpaints.erp.modules.accounting.domain.JournalEntry();
        entry.setStatus("POSTED");
        entry.setReferenceNumber("REV-CLOSED");
        entry.setEntryDate(today.minusDays(1));
        entry.setAccountingPeriod(closedPeriod);

        when(companyEntityLookup.requireJournalEntry(company, 45L)).thenReturn(entry);

        JournalEntryReversalRequest request = new JournalEntryReversalRequest(
                today,
                false,
                "Test reversal",
                "Closed period reversal",
                null
        );

        assertThatThrownBy(() -> accountingService.reverseJournalEntry(45L, request))
                .isInstanceOf(ApplicationException.class)
                .hasMessageContaining("CLOSED period");
    }

    @Test
    void createJournalEntry_rejectsDealerWithoutReceivableAccount() {
        LocalDate today = LocalDate.of(2024, 3, 15);
        when(companyClock.today(company)).thenReturn(today);
        when(accountingPeriodService.requireOpenPeriod(company, today)).thenReturn(new AccountingPeriod());

        Dealer dealer = new Dealer();
        dealer.setName("Test Dealer");
        when(companyEntityLookup.requireDealer(company, 99L)).thenReturn(dealer);

        JournalEntryRequest request = new JournalEntryRequest(
                "DEALER-REF",
                today,
                "Dealer missing AR",
                99L,
                null,
                Boolean.FALSE,
                List.of(new JournalEntryRequest.JournalLineRequest(1L, "Line", new BigDecimal("50.00"), BigDecimal.ZERO))
        );

        assertThatThrownBy(() -> accountingService.createJournalEntry(request))
                .isInstanceOf(ApplicationException.class)
                .hasMessageContaining("Dealer Test Dealer is missing a receivable account");
    }

    @Test
    void createJournalEntry_rejectsArWithoutDealerContext() {
        LocalDate today = LocalDate.of(2024, 4, 6);
        when(companyClock.today(company)).thenReturn(today);
        when(journalEntryRepository.findByCompanyAndReferenceNumber(eq(company), eq("AR-NO-DEALER")))
                .thenReturn(Optional.empty());

        Account ar = new Account();
        ReflectionTestUtils.setField(ar, "id", 1L);
        ar.setCompany(company);
        ar.setCode("AR-100");
        ar.setName("Accounts Receivable");
        ar.setType(AccountType.ASSET);

        Account revenue = new Account();
        ReflectionTestUtils.setField(revenue, "id", 2L);
        revenue.setCompany(company);
        revenue.setCode("REV-100");
        revenue.setName("Revenue");
        revenue.setType(AccountType.REVENUE);

        when(accountRepository.lockByCompanyAndId(eq(company), eq(1L))).thenReturn(Optional.of(ar));
        when(accountRepository.lockByCompanyAndId(eq(company), eq(2L))).thenReturn(Optional.of(revenue));

        JournalEntryRequest request = new JournalEntryRequest(
                "AR-NO-DEALER",
                today,
                "AR without dealer",
                null,
                null,
                Boolean.FALSE,
                List.of(
                        new JournalEntryRequest.JournalLineRequest(1L, "AR", new BigDecimal("100.00"), BigDecimal.ZERO),
                        new JournalEntryRequest.JournalLineRequest(2L, "Revenue", BigDecimal.ZERO, new BigDecimal("100.00"))
                )
        );

        assertThatThrownBy(() -> accountingService.createJournalEntry(request))
                .isInstanceOf(ApplicationException.class)
                .hasMessageContaining("dealer context");
    }

    @Test
    void createJournalEntry_rejectsApWithoutSupplierContext() {
        LocalDate today = LocalDate.of(2024, 4, 7);
        when(companyClock.today(company)).thenReturn(today);
        when(journalEntryRepository.findByCompanyAndReferenceNumber(eq(company), eq("AP-NO-SUPPLIER")))
                .thenReturn(Optional.empty());

        Account ap = new Account();
        ReflectionTestUtils.setField(ap, "id", 3L);
        ap.setCompany(company);
        ap.setCode("AP-200");
        ap.setName("Accounts Payable");
        ap.setType(AccountType.LIABILITY);

        Account inventory = new Account();
        ReflectionTestUtils.setField(inventory, "id", 4L);
        inventory.setCompany(company);
        inventory.setCode("INV-200");
        inventory.setName("Inventory");
        inventory.setType(AccountType.ASSET);

        when(accountRepository.lockByCompanyAndId(eq(company), eq(3L))).thenReturn(Optional.of(ap));
        when(accountRepository.lockByCompanyAndId(eq(company), eq(4L))).thenReturn(Optional.of(inventory));

        JournalEntryRequest request = new JournalEntryRequest(
                "AP-NO-SUPPLIER",
                today,
                "AP without supplier",
                null,
                null,
                Boolean.FALSE,
                List.of(
                        new JournalEntryRequest.JournalLineRequest(4L, "Inventory", new BigDecimal("50.00"), BigDecimal.ZERO),
                        new JournalEntryRequest.JournalLineRequest(3L, "AP", BigDecimal.ZERO, new BigDecimal("50.00"))
                )
        );

        assertThatThrownBy(() -> accountingService.createJournalEntry(request))
                .isInstanceOf(ApplicationException.class)
                .hasMessageContaining("supplier context");
    }

    @Test
    void createJournalEntry_rejectsMixedArAp() {
        LocalDate today = LocalDate.of(2024, 4, 8);
        when(companyClock.today(company)).thenReturn(today);
        when(journalEntryRepository.findByCompanyAndReferenceNumber(eq(company), eq("AR-AP-MIX")))
                .thenReturn(Optional.empty());

        Account ar = new Account();
        ReflectionTestUtils.setField(ar, "id", 5L);
        ar.setCompany(company);
        ar.setCode("AR-300");
        ar.setName("Accounts Receivable");
        ar.setType(AccountType.ASSET);

        Account ap = new Account();
        ReflectionTestUtils.setField(ap, "id", 6L);
        ap.setCompany(company);
        ap.setCode("AP-300");
        ap.setName("Accounts Payable");
        ap.setType(AccountType.LIABILITY);

        when(accountRepository.lockByCompanyAndId(eq(company), eq(5L))).thenReturn(Optional.of(ar));
        when(accountRepository.lockByCompanyAndId(eq(company), eq(6L))).thenReturn(Optional.of(ap));

        JournalEntryRequest request = new JournalEntryRequest(
                "AR-AP-MIX",
                today,
                "AR/AP mix",
                null,
                null,
                Boolean.FALSE,
                List.of(
                        new JournalEntryRequest.JournalLineRequest(5L, "AR", new BigDecimal("25.00"), BigDecimal.ZERO),
                        new JournalEntryRequest.JournalLineRequest(6L, "AP", BigDecimal.ZERO, new BigDecimal("25.00"))
                )
        );

        assertThatThrownBy(() -> accountingService.createJournalEntry(request))
                .isInstanceOf(ApplicationException.class)
                .hasMessageContaining("combine AR and AP");
    }

    @Test
    void createJournalEntry_allowsNonArApWithoutCounterparty() {
        LocalDate today = LocalDate.of(2024, 4, 9);
        when(companyClock.today(company)).thenReturn(today);
        when(journalEntryRepository.findByCompanyAndReferenceNumber(eq(company), eq("NO-AR-AP")))
                .thenReturn(Optional.empty());
        AccountingPeriod period = new AccountingPeriod();
        period.setYear(today.getYear());
        period.setMonth(today.getMonthValue());
        when(accountingPeriodService.requireOpenPeriod(company, today)).thenReturn(period);

        Account cash = new Account();
        ReflectionTestUtils.setField(cash, "id", 7L);
        cash.setCompany(company);
        cash.setCode("CASH");
        cash.setName("Cash");
        cash.setType(AccountType.ASSET);

        Account revenue = new Account();
        ReflectionTestUtils.setField(revenue, "id", 8L);
        revenue.setCompany(company);
        revenue.setCode("REV-200");
        revenue.setName("Revenue");
        revenue.setType(AccountType.REVENUE);

        when(accountRepository.lockByCompanyAndId(eq(company), eq(7L))).thenReturn(Optional.of(cash));
        when(accountRepository.lockByCompanyAndId(eq(company), eq(8L))).thenReturn(Optional.of(revenue));
        when(journalEntryRepository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));
        when(accountRepository.updateBalanceAtomic(eq(company), any(), any())).thenReturn(1);

        JournalEntryRequest request = new JournalEntryRequest(
                "NO-AR-AP",
                today,
                "Cash sale",
                null,
                null,
                Boolean.FALSE,
                List.of(
                        new JournalEntryRequest.JournalLineRequest(7L, "Cash", new BigDecimal("10.00"), BigDecimal.ZERO),
                        new JournalEntryRequest.JournalLineRequest(8L, "Revenue", BigDecimal.ZERO, new BigDecimal("10.00"))
                )
        );

        JournalEntryDto result = accountingService.createJournalEntry(request);
        assertThat(result).isNotNull();
        assertThat(result.referenceNumber()).isEqualTo("NO-AR-AP");
    }

    @Test
    void createJournalEntry_returnsExistingOnDuplicateReference() {
        // Test idempotent behavior: return existing entry instead of throwing exception
        LocalDate today = LocalDate.of(2024, 3, 20);
        var existingEntry = new com.bigbrightpaints.erp.modules.accounting.domain.JournalEntry();
        existingEntry.setCompany(company);
        existingEntry.setReferenceNumber("DUP-REF");
        existingEntry.setEntryDate(today);
        existingEntry.setMemo("Duplicate ref");
        existingEntry.setStatus("POSTED");
        org.springframework.test.util.ReflectionTestUtils.setField(existingEntry, "id", 999L);

        var debitAccount = new com.bigbrightpaints.erp.modules.accounting.domain.Account();
        ReflectionTestUtils.setField(debitAccount, "id", 1L);
        debitAccount.setCompany(company);
        debitAccount.setCode("ACC-1");
        var creditAccount = new com.bigbrightpaints.erp.modules.accounting.domain.Account();
        ReflectionTestUtils.setField(creditAccount, "id", 2L);
        creditAccount.setCompany(company);
        creditAccount.setCode("ACC-2");
        when(accountRepository.lockByCompanyAndId(eq(company), eq(1L))).thenReturn(Optional.of(debitAccount));
        when(accountRepository.lockByCompanyAndId(eq(company), eq(2L))).thenReturn(Optional.of(creditAccount));

        var debitLine = new com.bigbrightpaints.erp.modules.accounting.domain.JournalLine();
        debitLine.setJournalEntry(existingEntry);
        debitLine.setAccount(debitAccount);
        debitLine.setDebit(new BigDecimal("10.00"));
        debitLine.setCredit(BigDecimal.ZERO);
        existingEntry.getLines().add(debitLine);

        var creditLine = new com.bigbrightpaints.erp.modules.accounting.domain.JournalLine();
        creditLine.setJournalEntry(existingEntry);
        creditLine.setAccount(creditAccount);
        creditLine.setDebit(BigDecimal.ZERO);
        creditLine.setCredit(new BigDecimal("10.00"));
        existingEntry.getLines().add(creditLine);
        
        when(journalEntryRepository.findByCompanyAndReferenceNumber(eq(company), eq("DUP-REF")))
                .thenReturn(Optional.of(existingEntry));

        JournalEntryRequest request = new JournalEntryRequest(
                "DUP-REF",
                today,
                "Duplicate ref",
                null,
                null,
                Boolean.FALSE,
                List.of(
                        new JournalEntryRequest.JournalLineRequest(1L, "Debit", new BigDecimal("10.00"), BigDecimal.ZERO),
                        new JournalEntryRequest.JournalLineRequest(2L, "Credit", BigDecimal.ZERO, new BigDecimal("10.00"))
                )
        );

        // Should return existing entry (idempotent) instead of throwing
        JournalEntryDto result = accountingService.createJournalEntry(request);
        assertThat(result).isNotNull();
        assertThat(result.id()).isEqualTo(999L);
        assertThat(result.referenceNumber()).isEqualTo("DUP-REF");
    }

    @Test
    void createJournalEntry_failsWhenAccountBalanceNotUpdated() {
        LocalDate today = LocalDate.of(2024, 3, 21);
        when(companyClock.today(company)).thenReturn(today);
        when(accountingPeriodService.requireOpenPeriod(company, today)).thenReturn(new AccountingPeriod());
        when(journalEntryRepository.findByCompanyAndReferenceNumber(eq(company), eq("ACC-UPD")))
                .thenReturn(Optional.empty());
        // Return locked accounts with zero balance (need two accounts for balanced entry)
        var debitAccount = new com.bigbrightpaints.erp.modules.accounting.domain.Account();
        ReflectionTestUtils.setField(debitAccount, "id", 1L);
        debitAccount.setCompany(company);
        debitAccount.setBalance(BigDecimal.ZERO);
        debitAccount.setCode("DEBIT-ACC");
        var creditAccount = new com.bigbrightpaints.erp.modules.accounting.domain.Account();
        ReflectionTestUtils.setField(creditAccount, "id", 2L);
        creditAccount.setCompany(company);
        creditAccount.setBalance(BigDecimal.ZERO);
        creditAccount.setCode("CREDIT-ACC");
        when(accountRepository.lockByCompanyAndId(eq(company), eq(1L))).thenReturn(Optional.of(debitAccount));
        when(accountRepository.lockByCompanyAndId(eq(company), eq(2L))).thenReturn(Optional.of(creditAccount));
        when(journalEntryRepository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));
        // Force atomic updater to report no rows updated (for any account)
        when(accountRepository.updateBalanceAtomic(eq(company), any(), any())).thenReturn(0);

        JournalEntryRequest request = new JournalEntryRequest(
                "ACC-UPD",
                today,
                "Atomic balance update failure",
                null,
                null,
                Boolean.FALSE,
                List.of(
                        new JournalEntryRequest.JournalLineRequest(1L, "Debit line", new BigDecimal("5.00"), BigDecimal.ZERO),
                        new JournalEntryRequest.JournalLineRequest(2L, "Credit line", BigDecimal.ZERO, new BigDecimal("5.00"))
                )
        );

        assertThatThrownBy(() -> accountingService.createJournalEntry(request))
                .isInstanceOf(ApplicationException.class)
                .hasMessageContaining("Account balance update failed");
    }

    @Test
    void createJournalEntry_convertsForeignCurrencyToBaseAmounts() {
        LocalDate today = LocalDate.of(2024, 4, 1);
        when(companyClock.today(company)).thenReturn(today);
        AccountingPeriod period = new AccountingPeriod();
        period.setYear(today.getYear());
        period.setMonth(today.getMonthValue());
        period.setStartDate(today.withDayOfMonth(1));
        period.setEndDate(today.withDayOfMonth(today.lengthOfMonth()));
        when(accountingPeriodService.requireOpenPeriod(company, today)).thenReturn(period);
        when(journalEntryRepository.findByCompanyAndReferenceNumber(eq(company), eq("FX-REF")))
                .thenReturn(Optional.empty());

        var debitAccount = new com.bigbrightpaints.erp.modules.accounting.domain.Account();
        ReflectionTestUtils.setField(debitAccount, "id", 1L);
        debitAccount.setCompany(company);
        debitAccount.setCode("DEBIT");
        var creditAccount = new com.bigbrightpaints.erp.modules.accounting.domain.Account();
        ReflectionTestUtils.setField(creditAccount, "id", 2L);
        creditAccount.setCompany(company);
        creditAccount.setCode("CREDIT");

        when(accountRepository.lockByCompanyAndId(eq(company), eq(1L))).thenReturn(Optional.of(debitAccount));
        when(accountRepository.lockByCompanyAndId(eq(company), eq(2L))).thenReturn(Optional.of(creditAccount));
        when(journalEntryRepository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));
        when(accountRepository.updateBalanceAtomic(eq(company), any(), any())).thenReturn(1);

        JournalEntryRequest request = new JournalEntryRequest(
                "FX-REF",
                today,
                "USD journal",
                null,
                null,
                Boolean.FALSE,
                List.of(
                        new JournalEntryRequest.JournalLineRequest(1L, "Debit", new BigDecimal("1.00"), BigDecimal.ZERO),
                        new JournalEntryRequest.JournalLineRequest(2L, "Credit", BigDecimal.ZERO, new BigDecimal("1.00"))
                ),
                "USD",
                new BigDecimal("80.0")
        );

        accountingService.createJournalEntry(request);

        verify(accountRepository).updateBalanceAtomic(eq(company), eq(1L), eq(new BigDecimal("80.00")));
        verify(accountRepository).updateBalanceAtomic(eq(company), eq(2L), eq(new BigDecimal("-80.00")));
    }

    @Test
    void updatePurchaseStatus_setsPaidWhenOutstandingZero() {
        RawMaterialPurchase purchase = new RawMaterialPurchase();
        purchase.setTotalAmount(new BigDecimal("120.00"));
        purchase.setOutstandingAmount(BigDecimal.ZERO);
        purchase.setStatus("POSTED");

        accountingService.updatePurchaseStatus(purchase);

        assertThat(purchase.getStatus()).isEqualTo("PAID");
    }

    @Test
    void updatePurchaseStatus_setsPartialWhenOutstandingBetween() {
        RawMaterialPurchase purchase = new RawMaterialPurchase();
        purchase.setTotalAmount(new BigDecimal("120.00"));
        purchase.setOutstandingAmount(new BigDecimal("50.00"));
        purchase.setStatus("POSTED");

        accountingService.updatePurchaseStatus(purchase);

        assertThat(purchase.getStatus()).isEqualTo("PARTIAL");
    }

    @Test
    void updatePurchaseStatus_setsPostedWhenOutstandingEqualsTotal() {
        RawMaterialPurchase purchase = new RawMaterialPurchase();
        purchase.setTotalAmount(new BigDecimal("120.00"));
        purchase.setOutstandingAmount(new BigDecimal("120.00"));
        purchase.setStatus("POSTED");

        accountingService.updatePurchaseStatus(purchase);

        assertThat(purchase.getStatus()).isEqualTo("POSTED");
    }

    @Test
    void createJournalEntry_requiresFxRateForForeignCurrency() {
        LocalDate today = LocalDate.of(2024, 4, 2);
        JournalEntryRequest request = new JournalEntryRequest(
                "FX-NORATE",
                today,
                "USD journal",
                null,
                null,
                Boolean.FALSE,
                List.of(
                        new JournalEntryRequest.JournalLineRequest(1L, "Debit", new BigDecimal("1.00"), BigDecimal.ZERO),
                        new JournalEntryRequest.JournalLineRequest(2L, "Credit", BigDecimal.ZERO, new BigDecimal("1.00"))
                ),
                "USD",
                null
        );

        assertThatThrownBy(() -> accountingService.createJournalEntry(request))
                .isInstanceOf(ApplicationException.class)
                .hasMessageContaining("FX rate is required");
    }

    @Test
    void createJournalEntry_rejectsLineWithMissingAccount() {
        LocalDate today = LocalDate.of(2024, 4, 3);
        when(companyClock.today(company)).thenReturn(today);

        JournalEntryRequest request = new JournalEntryRequest(
                "NO-ACC",
                today,
                "Missing account",
                null,
                null,
                Boolean.FALSE,
                List.of(new JournalEntryRequest.JournalLineRequest(null, "Line", new BigDecimal("10.00"), BigDecimal.ZERO))
        );

        assertThatThrownBy(() -> accountingService.createJournalEntry(request))
                .isInstanceOf(ApplicationException.class)
                .hasMessageContaining("Account is required for every journal line");
    }

    @Test
    void createJournalEntry_rejectsLineWithBothDebitAndCredit() {
        LocalDate today = LocalDate.of(2024, 4, 4);
        when(companyClock.today(company)).thenReturn(today);

        var account = new com.bigbrightpaints.erp.modules.accounting.domain.Account();
        ReflectionTestUtils.setField(account, "id", 1L);
        account.setCompany(company);
        account.setCode("ACC-1");
        when(accountRepository.lockByCompanyAndId(eq(company), eq(1L))).thenReturn(Optional.of(account));

        JournalEntryRequest request = new JournalEntryRequest(
                "BOTH-DC",
                today,
                "Invalid line",
                null,
                null,
                Boolean.FALSE,
                List.of(new JournalEntryRequest.JournalLineRequest(1L, "Line", new BigDecimal("10.00"), new BigDecimal("1.00")))
        );

        assertThatThrownBy(() -> accountingService.createJournalEntry(request))
                .isInstanceOf(ApplicationException.class)
                .hasMessageContaining("Debit and credit cannot both be non-zero on the same line");
    }

    @Test
    void createJournalEntry_rejectsNegativeAmounts() {
        LocalDate today = LocalDate.of(2024, 4, 5);
        when(companyClock.today(company)).thenReturn(today);

        var account = new com.bigbrightpaints.erp.modules.accounting.domain.Account();
        ReflectionTestUtils.setField(account, "id", 1L);
        account.setCompany(company);
        account.setCode("ACC-1");
        when(accountRepository.lockByCompanyAndId(eq(company), eq(1L))).thenReturn(Optional.of(account));

        JournalEntryRequest request = new JournalEntryRequest(
                "NEG-AMT",
                today,
                "Negative debit",
                null,
                null,
                Boolean.FALSE,
                List.of(new JournalEntryRequest.JournalLineRequest(1L, "Line", new BigDecimal("-1.00"), BigDecimal.ZERO))
        );

        assertThatThrownBy(() -> accountingService.createJournalEntry(request))
                .isInstanceOf(ApplicationException.class)
                .hasMessageContaining("Debit/Credit cannot be negative");
    }

    @Test
    void createJournalEntry_rejectsUnbalancedJournalBeyondFxRoundingTolerance() {
        LocalDate today = LocalDate.of(2024, 4, 6);
        when(companyClock.today(company)).thenReturn(today);

        var debitAccount = new com.bigbrightpaints.erp.modules.accounting.domain.Account();
        ReflectionTestUtils.setField(debitAccount, "id", 1L);
        debitAccount.setCompany(company);
        debitAccount.setCode("DEBIT");
        var creditAccount = new com.bigbrightpaints.erp.modules.accounting.domain.Account();
        ReflectionTestUtils.setField(creditAccount, "id", 2L);
        creditAccount.setCompany(company);
        creditAccount.setCode("CREDIT");

        when(accountRepository.lockByCompanyAndId(eq(company), eq(1L))).thenReturn(Optional.of(debitAccount));
        when(accountRepository.lockByCompanyAndId(eq(company), eq(2L))).thenReturn(Optional.of(creditAccount));

        JournalEntryRequest request = new JournalEntryRequest(
                "UNBAL",
                today,
                "Not balanced",
                null,
                null,
                Boolean.FALSE,
                List.of(
                        new JournalEntryRequest.JournalLineRequest(1L, "Debit", new BigDecimal("10.00"), BigDecimal.ZERO),
                        new JournalEntryRequest.JournalLineRequest(2L, "Credit", BigDecimal.ZERO, new BigDecimal("9.00"))
                )
        );

        assertThatThrownBy(() -> accountingService.createJournalEntry(request))
                .isInstanceOf(ApplicationException.class)
                .hasMessageContaining("Journal entry must balance");
    }

    @Test
    void createJournalEntry_adjustsMinorFxRoundingDeltaWithinTolerance() {
        LocalDate today = LocalDate.of(2024, 4, 7);
        when(companyClock.today(company)).thenReturn(today);
        AccountingPeriod period = new AccountingPeriod();
        period.setYear(today.getYear());
        period.setMonth(today.getMonthValue());
        period.setStartDate(today.withDayOfMonth(1));
        period.setEndDate(today.withDayOfMonth(today.lengthOfMonth()));
        when(accountingPeriodService.requireOpenPeriod(company, today)).thenReturn(period);
        when(journalEntryRepository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));
        when(accountRepository.updateBalanceAtomic(eq(company), any(), any())).thenReturn(1);

        var debitAccount = new com.bigbrightpaints.erp.modules.accounting.domain.Account();
        ReflectionTestUtils.setField(debitAccount, "id", 1L);
        debitAccount.setCompany(company);
        debitAccount.setCode("DEBIT");
        var creditAccount1 = new com.bigbrightpaints.erp.modules.accounting.domain.Account();
        ReflectionTestUtils.setField(creditAccount1, "id", 2L);
        creditAccount1.setCompany(company);
        creditAccount1.setCode("CREDIT-1");
        var creditAccount2 = new com.bigbrightpaints.erp.modules.accounting.domain.Account();
        ReflectionTestUtils.setField(creditAccount2, "id", 3L);
        creditAccount2.setCompany(company);
        creditAccount2.setCode("CREDIT-2");

        when(accountRepository.lockByCompanyAndId(eq(company), eq(1L))).thenReturn(Optional.of(debitAccount));
        when(accountRepository.lockByCompanyAndId(eq(company), eq(2L))).thenReturn(Optional.of(creditAccount1));
        when(accountRepository.lockByCompanyAndId(eq(company), eq(3L))).thenReturn(Optional.of(creditAccount2));

        JournalEntryRequest request = new JournalEntryRequest(
                "FX-ROUND",
                today,
                "FX rounding",
                null,
                null,
                Boolean.FALSE,
                List.of(
                        new JournalEntryRequest.JournalLineRequest(1L, "Debit", new BigDecimal("0.02"), BigDecimal.ZERO),
                        new JournalEntryRequest.JournalLineRequest(2L, "Credit 1", BigDecimal.ZERO, new BigDecimal("0.01")),
                        new JournalEntryRequest.JournalLineRequest(3L, "Credit 2", BigDecimal.ZERO, new BigDecimal("0.01"))
                ),
                "USD",
                new BigDecimal("1.333333")
        );

        accountingService.createJournalEntry(request);

        verify(accountRepository).updateBalanceAtomic(eq(company), eq(1L), eq(new BigDecimal("0.03")));
        verify(accountRepository).updateBalanceAtomic(eq(company), eq(2L), eq(new BigDecimal("-0.02")));
        verify(accountRepository).updateBalanceAtomic(eq(company), eq(3L), eq(new BigDecimal("-0.01")));
    }

    @Test
    void createJournalEntry_adjustsMinorBaseRoundingDeltaWithinTolerance() {
        LocalDate today = LocalDate.of(2024, 4, 8);
        when(companyClock.today(company)).thenReturn(today);
        AccountingPeriod period = new AccountingPeriod();
        period.setYear(today.getYear());
        period.setMonth(today.getMonthValue());
        period.setStartDate(today.withDayOfMonth(1));
        period.setEndDate(today.withDayOfMonth(today.lengthOfMonth()));
        when(accountingPeriodService.requireOpenPeriod(company, today)).thenReturn(period);
        when(journalEntryRepository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));
        when(accountRepository.updateBalanceAtomic(eq(company), any(), any())).thenReturn(1);

        var debitAccount = new com.bigbrightpaints.erp.modules.accounting.domain.Account();
        ReflectionTestUtils.setField(debitAccount, "id", 1L);
        debitAccount.setCompany(company);
        debitAccount.setCode("DEBIT");
        var creditAccount = new com.bigbrightpaints.erp.modules.accounting.domain.Account();
        ReflectionTestUtils.setField(creditAccount, "id", 2L);
        creditAccount.setCompany(company);
        creditAccount.setCode("CREDIT");

        when(accountRepository.lockByCompanyAndId(eq(company), eq(1L))).thenReturn(Optional.of(debitAccount));
        when(accountRepository.lockByCompanyAndId(eq(company), eq(2L))).thenReturn(Optional.of(creditAccount));

        JournalEntryRequest request = new JournalEntryRequest(
                "BASE-ROUND",
                today,
                "Base rounding",
                null,
                null,
                Boolean.FALSE,
                List.of(
                        new JournalEntryRequest.JournalLineRequest(1L, "Debit", new BigDecimal("10.005"), BigDecimal.ZERO),
                        new JournalEntryRequest.JournalLineRequest(2L, "Credit", BigDecimal.ZERO, new BigDecimal("10.00"))
                )
        );

        accountingService.createJournalEntry(request);
    }

    @Test
    void settleSupplierInvoices_cashAmountAccountsForDiscountAndFxGain() {
        AccountingService service = spy(accountingService);

        Supplier supplier = new Supplier();
        supplier.setName("Supplier");
        Account payable = new Account();
        payable.setCompany(company);
        payable.setCode("AP");
        payable.setType(AccountType.LIABILITY);
        ReflectionTestUtils.setField(payable, "id", 10L);
        supplier.setPayableAccount(payable);
        ReflectionTestUtils.setField(supplier, "id", 1L);

        Account cash = new Account();
        cash.setCompany(company);
        cash.setCode("CASH");
        ReflectionTestUtils.setField(cash, "id", 20L);

        Account discount = new Account();
        discount.setCompany(company);
        discount.setCode("DISC");
        ReflectionTestUtils.setField(discount, "id", 21L);

        Account fxGain = new Account();
        fxGain.setCompany(company);
        fxGain.setCode("FXGAIN");
        ReflectionTestUtils.setField(fxGain, "id", 22L);

        RawMaterialPurchase purchase = new RawMaterialPurchase();
        purchase.setCompany(company);
        purchase.setSupplier(supplier);
        purchase.setOutstandingAmount(new BigDecimal("200.00"));
        ReflectionTestUtils.setField(purchase, "id", 2L);

        when(supplierRepository.lockByCompanyAndId(eq(company), eq(1L))).thenReturn(Optional.of(supplier));
        when(rawMaterialPurchaseRepository.lockByCompanyAndId(eq(company), eq(2L))).thenReturn(Optional.of(purchase));
        when(settlementAllocationRepository.findByCompanyAndIdempotencyKey(any(), any())).thenReturn(List.of());
        when(companyEntityLookup.requireAccount(eq(company), eq(20L))).thenReturn(cash);
        when(companyEntityLookup.requireAccount(eq(company), eq(21L))).thenReturn(discount);
        when(companyEntityLookup.requireAccount(eq(company), eq(22L))).thenReturn(fxGain);

        JournalEntryDto journalEntryDto = stubEntry(55L);
        ArgumentCaptor<JournalEntryRequest> journalCaptor = ArgumentCaptor.forClass(JournalEntryRequest.class);
        doReturn(journalEntryDto).when(service).createJournalEntry(journalCaptor.capture());
        when(companyEntityLookup.requireJournalEntry(eq(company), eq(55L))).thenReturn(new com.bigbrightpaints.erp.modules.accounting.domain.JournalEntry());

        SettlementAllocationRequest allocation = new SettlementAllocationRequest(
                null,
                2L,
                new BigDecimal("100.00"),
                new BigDecimal("10.00"),
                BigDecimal.ZERO,
                new BigDecimal("5.00"),
                "settle");

        SupplierSettlementRequest request = new SupplierSettlementRequest(
                1L,
                20L,
                21L,
                null,
                22L,
                null,
                LocalDate.of(2024, 4, 9),
                "REF-AP-1",
                "Supplier settlement",
                "IDEMP-AP-1",
                Boolean.FALSE,
                List.of(allocation)
        );

        service.settleSupplierInvoices(request);
        assertThat(purchase.getOutstandingAmount()).isEqualByComparingTo("100.00");

        JournalEntryRequest captured = journalCaptor.getValue();
        JournalEntryRequest.JournalLineRequest cashLine = captured.lines().stream()
                .filter(line -> line.accountId().equals(20L))
                .findFirst()
                .orElseThrow();
        JournalEntryRequest.JournalLineRequest discountLine = captured.lines().stream()
                .filter(line -> line.accountId().equals(21L))
                .findFirst()
                .orElseThrow();
        JournalEntryRequest.JournalLineRequest fxGainLine = captured.lines().stream()
                .filter(line -> line.accountId().equals(22L))
                .findFirst()
                .orElseThrow();

        assertThat(cashLine.credit()).isEqualByComparingTo("85.00");
        assertThat(discountLine.credit()).isEqualByComparingTo("10.00");
        assertThat(fxGainLine.credit()).isEqualByComparingTo("5.00");
    }

    @Test
    void settleDealerInvoices_appliesGrossAmountToInvoice() {
        AccountingService service = spy(accountingService);

        Dealer dealer = new Dealer();
        dealer.setName("Dealer");
        Account receivable = new Account();
        receivable.setCompany(company);
        receivable.setCode("AR");
        receivable.setType(AccountType.ASSET);
        ReflectionTestUtils.setField(receivable, "id", 10L);
        dealer.setReceivableAccount(receivable);
        ReflectionTestUtils.setField(dealer, "id", 1L);

        Account cash = new Account();
        cash.setCompany(company);
        cash.setCode("CASH");
        ReflectionTestUtils.setField(cash, "id", 20L);

        Account discount = new Account();
        discount.setCompany(company);
        discount.setCode("DISC");
        ReflectionTestUtils.setField(discount, "id", 21L);

        var invoice = new com.bigbrightpaints.erp.modules.invoice.domain.Invoice();
        invoice.setDealer(dealer);
        invoice.setCurrency("INR");
        invoice.setOutstandingAmount(new BigDecimal("1000.00"));
        invoice.setTotalAmount(new BigDecimal("1000.00"));
        ReflectionTestUtils.setField(invoice, "id", 5L);

        when(dealerRepository.lockByCompanyAndId(eq(company), eq(1L))).thenReturn(Optional.of(dealer));
        when(invoiceRepository.lockByCompanyAndId(eq(company), eq(5L))).thenReturn(Optional.of(invoice));
        when(settlementAllocationRepository.findByCompanyAndIdempotencyKey(any(), any())).thenReturn(List.of());
        when(companyEntityLookup.requireAccount(eq(company), eq(20L))).thenReturn(cash);
        when(companyEntityLookup.requireAccount(eq(company), eq(21L))).thenReturn(discount);

        JournalEntryDto journalEntryDto = stubEntry(44L);
        doReturn(journalEntryDto).when(service).createJournalEntry(any());
        when(companyEntityLookup.requireJournalEntry(eq(company), eq(44L))).thenReturn(new com.bigbrightpaints.erp.modules.accounting.domain.JournalEntry());

        SettlementAllocationRequest allocation = new SettlementAllocationRequest(
                5L,
                null,
                new BigDecimal("500.00"),
                new BigDecimal("50.00"),
                BigDecimal.ZERO,
                null,
                "settle");

        DealerSettlementRequest request = new DealerSettlementRequest(
                1L,
                20L,
                21L,
                null,
                null,
                null,
                LocalDate.of(2024, 4, 9),
                "REF-AR-1",
                "Dealer settlement",
                "IDEMP-AR-1",
                Boolean.FALSE,
                List.of(allocation),
                null
        );

        service.settleDealerInvoices(request);

        ArgumentCaptor<BigDecimal> amountCaptor = ArgumentCaptor.forClass(BigDecimal.class);
        verify(invoiceSettlementPolicy).applySettlement(eq(invoice), amountCaptor.capture(), eq("IDEMP-AR-1-INV-5"));
        assertThat(amountCaptor.getValue()).isEqualByComparingTo("500.00");
    }

    @Test
    void revalueInventory_distributesAcrossFinishedGoodBatches() {
        AccountingService service = spy(accountingService);

        Account inventory = new Account();
        inventory.setCompany(company);
        inventory.setCode("INV");
        ReflectionTestUtils.setField(inventory, "id", 11L);

        Account reval = new Account();
        reval.setCompany(company);
        reval.setCode("REVAL");
        ReflectionTestUtils.setField(reval, "id", 12L);

        FinishedGood fg = new FinishedGood();
        fg.setCompany(company);
        fg.setProductCode("FG-1");
        fg.setValuationAccountId(11L);

        FinishedGoodBatch batch1 = new FinishedGoodBatch();
        batch1.setFinishedGood(fg);
        batch1.setBatchCode("B1");
        batch1.setQuantityTotal(new BigDecimal("10"));
        batch1.setQuantityAvailable(new BigDecimal("10"));
        batch1.setUnitCost(new BigDecimal("100.000000"));

        FinishedGoodBatch batch2 = new FinishedGoodBatch();
        batch2.setFinishedGood(fg);
        batch2.setBatchCode("B2");
        batch2.setQuantityTotal(new BigDecimal("10"));
        batch2.setQuantityAvailable(new BigDecimal("10"));
        batch2.setUnitCost(new BigDecimal("200.000000"));

        when(companyEntityLookup.requireAccount(eq(company), eq(11L))).thenReturn(inventory);
        when(companyEntityLookup.requireAccount(eq(company), eq(12L))).thenReturn(reval);
        when(journalEntryRepository.findByCompanyAndReferenceNumber(eq(company), any())).thenReturn(Optional.empty());
        when(finishedGoodBatchRepository.findByCompanyAndValuationAccountId(eq(company), eq(11L)))
                .thenReturn(List.of(batch1, batch2));

        JournalEntryDto journalEntryDto = stubEntry(77L);
        doReturn(journalEntryDto).when(service).createJournalEntry(any());

        service.revalueInventory(new InventoryRevaluationRequest(
                11L,
                12L,
                new BigDecimal("20.00"),
                "Reval",
                LocalDate.of(2024, 4, 9),
                "REVAL-1",
                null,
                Boolean.FALSE
        ));

        assertThat(batch1.getUnitCost()).isEqualByComparingTo("101.000000");
        assertThat(batch2.getUnitCost()).isEqualByComparingTo("201.000000");
    }

    @Test
    void settleDealerInvoices_requiresPaymentsToMatchCash() {
        // Setup company/dealer/invoice
        Dealer dealer = new Dealer();
        dealer.setName("Dealer");
        var arAccount = new com.bigbrightpaints.erp.modules.accounting.domain.Account();
        arAccount.setCompany(company);
        arAccount.setCode("AR");
        dealer.setReceivableAccount(arAccount);
        // Set IDs for equality checks
        ReflectionTestUtils.setField(dealer, "id", 1L);
        ReflectionTestUtils.setField(arAccount, "id", 10L);

        var cashAccount = new com.bigbrightpaints.erp.modules.accounting.domain.Account();
        cashAccount.setCompany(company);
        cashAccount.setCode("CASH");
        ReflectionTestUtils.setField(cashAccount, "id", 2L);

        var invoice = new com.bigbrightpaints.erp.modules.invoice.domain.Invoice();
        invoice.setDealer(dealer);
        invoice.setCurrency("INR");
        invoice.setOutstandingAmount(new BigDecimal("100.00"));
        invoice.setTotalAmount(new BigDecimal("100.00"));

        when(dealerRepository.lockByCompanyAndId(eq(company), eq(1L))).thenReturn(Optional.of(dealer));
        when(invoiceRepository.lockByCompanyAndId(eq(company), eq(3L))).thenReturn(Optional.of(invoice));
        when(settlementAllocationRepository.findByCompanyAndIdempotencyKey(any(), any())).thenReturn(List.of());
        when(referenceNumberService.dealerReceiptReference(eq(company), eq(dealer))).thenReturn("REF-1");

        // Allocation requires an invoice reference
        var allocation = new SettlementAllocationRequest(
                3L,
                null,
                new BigDecimal("100.00"),
                BigDecimal.ZERO,
                BigDecimal.ZERO,
                null,
                null);

        // Payments total 50, but cash needed is 100 -> should fail
        var payment = new SettlementPaymentRequest(2L, new BigDecimal("50.00"), "CASH");

        when(companyEntityLookup.requireAccount(eq(company), eq(2L))).thenReturn(cashAccount);

        DealerSettlementRequest request = new DealerSettlementRequest(
                1L,
                null, // legacy cashAccountId not used when payments provided
                null,
                null,
                null,
                null,
                LocalDate.now(),
                null,
                null,
                "IDEMP-TEST",
                Boolean.FALSE,
                List.of(allocation),
                List.of(payment)
        );

        assertThatThrownBy(() -> accountingService.settleDealerInvoices(request))
                .isInstanceOf(ApplicationException.class)
                .hasMessageContaining("Payment total")
                .hasMessageContaining("must equal net cash required");
    }

    @Test
    void settleDealerInvoices_rejectsMissingInvoiceAllocation() {
        Dealer dealer = new Dealer();
        dealer.setName("Dealer");
        Account arAccount = new Account();
        arAccount.setCompany(company);
        arAccount.setCode("AR");
        dealer.setReceivableAccount(arAccount);
        ReflectionTestUtils.setField(dealer, "id", 1L);
        ReflectionTestUtils.setField(arAccount, "id", 10L);

        when(dealerRepository.lockByCompanyAndId(eq(company), eq(1L))).thenReturn(Optional.of(dealer));
        when(settlementAllocationRepository.findByCompanyAndIdempotencyKey(any(), any())).thenReturn(List.of());

        SettlementAllocationRequest allocation = new SettlementAllocationRequest(
                null,
                2L,
                new BigDecimal("100.00"),
                BigDecimal.ZERO,
                BigDecimal.ZERO,
                null,
                null);

        DealerSettlementRequest request = new DealerSettlementRequest(
                1L,
                2L,
                null,
                null,
                null,
                null,
                LocalDate.now(),
                null,
                null,
                "IDEMP-TEST",
                Boolean.FALSE,
                List.of(allocation),
                null
        );

        assertThatThrownBy(() -> accountingService.settleDealerInvoices(request))
                .isInstanceOf(ApplicationException.class)
                .hasMessageContaining("Invoice allocation is required for dealer settlements");
    }

    @Test
    void settleSupplierInvoices_rejectsMissingPurchaseAllocation() {
        AccountingService service = spy(accountingService);

        Supplier supplier = new Supplier();
        supplier.setName("Supplier");
        Account payable = new Account();
        payable.setCompany(company);
        payable.setCode("AP");
        payable.setType(AccountType.LIABILITY);
        ReflectionTestUtils.setField(payable, "id", 10L);
        supplier.setPayableAccount(payable);
        ReflectionTestUtils.setField(supplier, "id", 1L);

        Account cash = new Account();
        cash.setCompany(company);
        cash.setCode("CASH");
        ReflectionTestUtils.setField(cash, "id", 20L);

        when(supplierRepository.lockByCompanyAndId(eq(company), eq(1L))).thenReturn(Optional.of(supplier));
        when(settlementAllocationRepository.findByCompanyAndIdempotencyKey(any(), any())).thenReturn(List.of());
        when(companyEntityLookup.requireAccount(eq(company), eq(20L))).thenReturn(cash);

        SettlementAllocationRequest allocation = new SettlementAllocationRequest(
                null,
                null,
                new BigDecimal("100.00"),
                BigDecimal.ZERO,
                BigDecimal.ZERO,
                null,
                null);

        SupplierSettlementRequest request = new SupplierSettlementRequest(
                1L,
                20L,
                null,
                null,
                null,
                null,
                LocalDate.of(2024, 4, 9),
                "REF-AP-1",
                "Supplier settlement",
                "IDEMP-AP-1",
                Boolean.FALSE,
                List.of(allocation)
        );

        assertThatThrownBy(() -> service.settleSupplierInvoices(request))
                .isInstanceOf(ApplicationException.class)
                .hasMessageContaining("Purchase allocation is required for supplier settlements");
    }

    private JournalEntryDto stubEntry(long id) {
        return new JournalEntryDto(
                id,
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
                null
        );
    }
}
