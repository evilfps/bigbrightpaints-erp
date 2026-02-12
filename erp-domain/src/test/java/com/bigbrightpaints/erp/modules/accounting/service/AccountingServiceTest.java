package com.bigbrightpaints.erp.modules.accounting.service;

import com.bigbrightpaints.erp.core.audit.AuditEvent;
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
import com.bigbrightpaints.erp.modules.accounting.domain.JournalEntry;
import com.bigbrightpaints.erp.modules.accounting.domain.JournalLine;
import com.bigbrightpaints.erp.modules.accounting.domain.JournalEntryRepository;
import com.bigbrightpaints.erp.modules.accounting.domain.JournalLineRepository;
import com.bigbrightpaints.erp.modules.accounting.domain.JournalReferenceMapping;
import com.bigbrightpaints.erp.modules.accounting.domain.JournalReferenceMappingRepository;
import com.bigbrightpaints.erp.modules.accounting.event.AccountingEventStore;
import com.bigbrightpaints.erp.modules.accounting.dto.DealerSettlementRequest;
import com.bigbrightpaints.erp.modules.accounting.dto.JournalEntryDto;
import com.bigbrightpaints.erp.modules.accounting.dto.JournalEntryRequest;
import com.bigbrightpaints.erp.modules.accounting.dto.JournalEntryReversalRequest;
import com.bigbrightpaints.erp.modules.accounting.dto.InventoryRevaluationRequest;
import com.bigbrightpaints.erp.modules.accounting.dto.PartnerSettlementResponse;
import com.bigbrightpaints.erp.modules.accounting.dto.SettlementAllocationRequest;
import com.bigbrightpaints.erp.modules.accounting.dto.SettlementPaymentRequest;
import com.bigbrightpaints.erp.modules.accounting.dto.SupplierPaymentRequest;
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
import org.springframework.batch.support.transaction.ResourcelessTransactionManager;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.ArrayList;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
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
    private JournalReferenceResolver journalReferenceResolver;
    @Mock
    private JournalReferenceMappingRepository journalReferenceMappingRepository;
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
    @Mock
    private AccountingEventStore accountingEventStore;

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
                journalReferenceResolver,
                journalReferenceMappingRepository,
                entityManager,
                systemSettingsService,
                auditService,
                accountingEventStore
        );
        company = new Company();
        company.setBaseCurrency("INR");
        lenient().when(companyContextService.requireCurrentCompany()).thenReturn(company);
        lenient().when(systemSettingsService.isPeriodLockEnforced()).thenReturn(true);
        lenient().when(accountingPeriodService.requireOpenPeriod(any(), any())).thenReturn(new AccountingPeriod());
        lenient().when(dealerRepository.findAllByCompanyAndReceivableAccount(any(), any())).thenReturn(List.of());
        lenient().when(supplierRepository.findAllByCompanyAndPayableAccount(any(), any())).thenReturn(List.of());
        lenient().when(referenceNumberService.dealerReceiptReference(any(), any())).thenReturn("REF-SETTLE");
        lenient().when(journalReferenceMappingRepository.reserveReferenceMapping(any(), any(), any(), any(), any()))
                .thenReturn(1);
        lenient().when(journalReferenceMappingRepository.findByCompanyAndLegacyReferenceIgnoreCase(any(), any()))
                .thenAnswer(invocation -> Optional.of(new JournalReferenceMapping()));
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
    void reverseJournalEntry_logsSuccessAudit_afterCommitOnly() {
        LocalDate today = LocalDate.of(2024, 4, 1);
        when(companyClock.today(company)).thenReturn(today);
        AccountingPeriod openPeriod = new AccountingPeriod();
        when(accountingPeriodService.requireOpenPeriod(company, today)).thenReturn(openPeriod);

        AccountingService service = spy(accountingService);
        JournalEntry original = reversalSourceEntry(500L, "REV-AUDIT-OK", today);
        JournalEntry reversal = new JournalEntry();
        ReflectionTestUtils.setField(reversal, "id", 900L);

        when(companyEntityLookup.requireJournalEntry(company, 500L)).thenReturn(original);
        when(companyEntityLookup.requireJournalEntry(company, 900L)).thenReturn(reversal);
        when(journalEntryRepository.save(any(JournalEntry.class))).thenAnswer(invocation -> invocation.getArgument(0));
        doReturn(stubEntry(900L)).when(service).createJournalEntry(any(JournalEntryRequest.class));

        JournalEntryReversalRequest request = new JournalEntryReversalRequest(
                today,
                false,
                "Test reversal",
                "Audit commit order",
                Boolean.FALSE
        );

        TransactionTemplate transactionTemplate = new TransactionTemplate(new ResourcelessTransactionManager());
        transactionTemplate.executeWithoutResult(status -> {
            JournalEntryDto result = service.reverseJournalEntry(500L, request);
            assertThat(result.id()).isEqualTo(900L);
            verify(auditService, never()).logSuccess(eq(AuditEvent.JOURNAL_ENTRY_REVERSED), any());
        });

        verify(auditService).logSuccess(eq(AuditEvent.JOURNAL_ENTRY_REVERSED), any());
    }

    @Test
    void reverseJournalEntry_skipsSuccessAudit_whenTransactionRollsBack() {
        LocalDate today = LocalDate.of(2024, 4, 1);
        when(companyClock.today(company)).thenReturn(today);
        AccountingPeriod openPeriod = new AccountingPeriod();
        when(accountingPeriodService.requireOpenPeriod(company, today)).thenReturn(openPeriod);

        AccountingService service = spy(accountingService);
        JournalEntry original = reversalSourceEntry(501L, "REV-AUDIT-ROLLBACK", today);
        JournalEntry reversal = new JournalEntry();
        ReflectionTestUtils.setField(reversal, "id", 901L);

        when(companyEntityLookup.requireJournalEntry(company, 501L)).thenReturn(original);
        when(companyEntityLookup.requireJournalEntry(company, 901L)).thenReturn(reversal);
        when(journalEntryRepository.save(any(JournalEntry.class))).thenAnswer(invocation -> invocation.getArgument(0));
        doReturn(stubEntry(901L)).when(service).createJournalEntry(any(JournalEntryRequest.class));

        JournalEntryReversalRequest request = new JournalEntryReversalRequest(
                today,
                false,
                "Test reversal",
                "Audit rollback order",
                Boolean.FALSE
        );

        TransactionTemplate transactionTemplate = new TransactionTemplate(new ResourcelessTransactionManager());
        transactionTemplate.executeWithoutResult(status -> {
            service.reverseJournalEntry(501L, request);
            status.setRollbackOnly();
            verify(auditService, never()).logSuccess(eq(AuditEvent.JOURNAL_ENTRY_REVERSED), any());
        });

        verify(auditService, never()).logSuccess(eq(AuditEvent.JOURNAL_ENTRY_REVERSED), any());
    }

    @Test
    void createJournalEntry_rejectsDealerWithoutReceivableAccount() {
        LocalDate today = LocalDate.of(2024, 3, 15);
        when(companyClock.today(company)).thenReturn(today);
        when(accountingPeriodService.requireOpenPeriod(company, today)).thenReturn(new AccountingPeriod());
        when(journalEntryRepository.findByCompanyAndReferenceNumber(eq(company), eq("DEALER-REF")))
                .thenReturn(Optional.empty());

        Dealer dealer = new Dealer();
        dealer.setName("Test Dealer");
        when(companyEntityLookup.requireDealer(company, 99L)).thenReturn(dealer);

        Account ar = new Account();
        ReflectionTestUtils.setField(ar, "id", 1L);
        ar.setCompany(company);
        ar.setCode("AR-TEST");
        ar.setName("Accounts Receivable");
        ar.setType(AccountType.ASSET);

        Account revenue = new Account();
        ReflectionTestUtils.setField(revenue, "id", 2L);
        revenue.setCompany(company);
        revenue.setCode("REV-TEST");
        revenue.setName("Revenue");
        revenue.setType(AccountType.REVENUE);

        when(accountRepository.lockByCompanyAndId(eq(company), eq(1L))).thenReturn(Optional.of(ar));
        when(accountRepository.lockByCompanyAndId(eq(company), eq(2L))).thenReturn(Optional.of(revenue));

        JournalEntryRequest request = new JournalEntryRequest(
                "DEALER-REF",
                today,
                "Dealer missing AR",
                99L,
                null,
                Boolean.FALSE,
                List.of(
                        new JournalEntryRequest.JournalLineRequest(1L, "AR", new BigDecimal("50.00"), BigDecimal.ZERO),
                        new JournalEntryRequest.JournalLineRequest(2L, "Revenue", BigDecimal.ZERO, new BigDecimal("50.00"))
                )
        );

        assertThatThrownBy(() -> accountingService.createJournalEntry(request))
                .isInstanceOf(ApplicationException.class)
                .hasMessageContaining("Dealer Test Dealer is missing a receivable account");
    }

    @Test
    void createJournalEntry_allowsDealerContextWithoutArWhenDealerReceivableMissing() {
        LocalDate today = LocalDate.of(2024, 3, 16);
        when(companyClock.today(company)).thenReturn(today);
        AccountingPeriod period = new AccountingPeriod();
        period.setYear(today.getYear());
        period.setMonth(today.getMonthValue());
        when(accountingPeriodService.requireOpenPeriod(company, today)).thenReturn(period);
        when(journalEntryRepository.findByCompanyAndReferenceNumber(eq(company), eq("DEALER-NON-AR")))
                .thenReturn(Optional.empty());
        when(journalEntryRepository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));
        when(accountRepository.updateBalanceAtomic(eq(company), any(), any())).thenReturn(1);

        Dealer dealer = new Dealer();
        dealer.setName("Dealer Without AR");
        when(companyEntityLookup.requireDealer(company, 100L)).thenReturn(dealer);

        Account expense = new Account();
        ReflectionTestUtils.setField(expense, "id", 11L);
        expense.setCompany(company);
        expense.setCode("EXP-TEST");
        expense.setName("Service Expense");
        expense.setType(AccountType.EXPENSE);

        Account cash = new Account();
        ReflectionTestUtils.setField(cash, "id", 12L);
        cash.setCompany(company);
        cash.setCode("CASH");
        cash.setName("Cash");
        cash.setType(AccountType.ASSET);

        when(accountRepository.lockByCompanyAndId(eq(company), eq(11L))).thenReturn(Optional.of(expense));
        when(accountRepository.lockByCompanyAndId(eq(company), eq(12L))).thenReturn(Optional.of(cash));

        JournalEntryRequest request = new JournalEntryRequest(
                "DEALER-NON-AR",
                today,
                "Dealer tagged cash expense",
                100L,
                null,
                Boolean.FALSE,
                List.of(
                        new JournalEntryRequest.JournalLineRequest(11L, "Expense", new BigDecimal("2000.00"), BigDecimal.ZERO),
                        new JournalEntryRequest.JournalLineRequest(12L, "Cash", BigDecimal.ZERO, new BigDecimal("2000.00"))
                )
        );

        JournalEntryDto result = accountingService.createJournalEntry(request);

        assertThat(result).isNotNull();
        assertThat(result.referenceNumber()).isEqualTo("DEALER-NON-AR");
        verify(dealerLedgerService, never()).recordLedgerEntry(any(), any());
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
    void createJournalEntry_infersSupplierContextFromOwnedPayableAccount() {
        LocalDate today = LocalDate.of(2024, 4, 7);
        when(companyClock.today(company)).thenReturn(today);
        when(journalEntryRepository.findByCompanyAndReferenceNumber(eq(company), eq("AP-INFER-SUPPLIER")))
                .thenReturn(Optional.empty());
        AccountingPeriod period = new AccountingPeriod();
        period.setYear(today.getYear());
        period.setMonth(today.getMonthValue());
        when(accountingPeriodService.requireOpenPeriod(company, today)).thenReturn(period);

        Account payable = new Account();
        ReflectionTestUtils.setField(payable, "id", 31L);
        payable.setCompany(company);
        payable.setCode("AP-SKEINA");
        payable.setName("Skeina Payable");
        payable.setType(AccountType.LIABILITY);

        Account cash = new Account();
        ReflectionTestUtils.setField(cash, "id", 32L);
        cash.setCompany(company);
        cash.setCode("CASH");
        cash.setName("Cash");
        cash.setType(AccountType.ASSET);

        Supplier supplier = new Supplier();
        ReflectionTestUtils.setField(supplier, "id", 91L);
        supplier.setName("SKEINA");
        supplier.setPayableAccount(payable);

        when(accountRepository.lockByCompanyAndId(eq(company), eq(31L))).thenReturn(Optional.of(payable));
        when(accountRepository.lockByCompanyAndId(eq(company), eq(32L))).thenReturn(Optional.of(cash));
        when(supplierRepository.findAllByCompanyAndPayableAccount(eq(company), eq(payable))).thenReturn(List.of(supplier));
        when(journalEntryRepository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));
        when(accountRepository.updateBalanceAtomic(eq(company), any(), any())).thenReturn(1);

        JournalEntryRequest request = new JournalEntryRequest(
                "AP-INFER-SUPPLIER",
                today,
                "Supplier inferred from payable account",
                null,
                null,
                Boolean.FALSE,
                List.of(
                        new JournalEntryRequest.JournalLineRequest(32L, "Cash", new BigDecimal("2000.00"), BigDecimal.ZERO),
                        new JournalEntryRequest.JournalLineRequest(31L, "Supplier AP", BigDecimal.ZERO, new BigDecimal("2000.00"))
                )
        );

        JournalEntryDto result = accountingService.createJournalEntry(request);
        assertThat(result).isNotNull();
        assertThat(result.supplierId()).isEqualTo(91L);
    }

    @Test
    void createJournalEntry_rejectsAmbiguousSupplierInference() {
        LocalDate today = LocalDate.of(2024, 4, 7);
        when(companyClock.today(company)).thenReturn(today);
        when(journalEntryRepository.findByCompanyAndReferenceNumber(eq(company), eq("AP-AMBIG-SUPPLIER")))
                .thenReturn(Optional.empty());

        Account payable = new Account();
        ReflectionTestUtils.setField(payable, "id", 33L);
        payable.setCompany(company);
        payable.setCode("AP-SHARED");
        payable.setName("Shared AP");
        payable.setType(AccountType.LIABILITY);

        Account cash = new Account();
        ReflectionTestUtils.setField(cash, "id", 34L);
        cash.setCompany(company);
        cash.setCode("CASH");
        cash.setName("Cash");
        cash.setType(AccountType.ASSET);

        Supplier supplierA = new Supplier();
        ReflectionTestUtils.setField(supplierA, "id", 92L);
        supplierA.setName("SKEINA");
        supplierA.setPayableAccount(payable);

        Supplier supplierB = new Supplier();
        ReflectionTestUtils.setField(supplierB, "id", 93L);
        supplierB.setName("OTHER");
        supplierB.setPayableAccount(payable);

        when(accountRepository.lockByCompanyAndId(eq(company), eq(33L))).thenReturn(Optional.of(payable));
        when(accountRepository.lockByCompanyAndId(eq(company), eq(34L))).thenReturn(Optional.of(cash));
        when(supplierRepository.findAllByCompanyAndPayableAccount(eq(company), eq(payable)))
                .thenReturn(List.of(supplierA, supplierB));

        JournalEntryRequest request = new JournalEntryRequest(
                "AP-AMBIG-SUPPLIER",
                today,
                "Ambiguous supplier inference",
                null,
                null,
                Boolean.FALSE,
                List.of(
                        new JournalEntryRequest.JournalLineRequest(34L, "Cash", new BigDecimal("1000.00"), BigDecimal.ZERO),
                        new JournalEntryRequest.JournalLineRequest(33L, "Supplier AP", BigDecimal.ZERO, new BigDecimal("1000.00"))
                )
        );

        assertThatThrownBy(() -> accountingService.createJournalEntry(request))
                .isInstanceOf(ApplicationException.class)
                .hasMessageContaining("requires a supplier context");
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
    void createJournalEntry_failsWhenEventTrailPersistenceFailsInStrictMode() {
        LocalDate today = LocalDate.of(2024, 3, 22);
        when(companyClock.today(company)).thenReturn(today);
        AccountingPeriod period = new AccountingPeriod();
        period.setYear(today.getYear());
        period.setMonth(today.getMonthValue());
        when(accountingPeriodService.requireOpenPeriod(company, today)).thenReturn(period);
        when(journalEntryRepository.findByCompanyAndReferenceNumber(eq(company), eq("EVT-STRICT")))
                .thenReturn(Optional.empty());

        Account debitAccount = new Account();
        ReflectionTestUtils.setField(debitAccount, "id", 101L);
        debitAccount.setCompany(company);
        debitAccount.setBalance(BigDecimal.ZERO);
        debitAccount.setCode("DEBIT-STRICT");
        Account creditAccount = new Account();
        ReflectionTestUtils.setField(creditAccount, "id", 102L);
        creditAccount.setCompany(company);
        creditAccount.setBalance(BigDecimal.ZERO);
        creditAccount.setCode("CREDIT-STRICT");
        when(accountRepository.lockByCompanyAndId(eq(company), eq(101L))).thenReturn(Optional.of(debitAccount));
        when(accountRepository.lockByCompanyAndId(eq(company), eq(102L))).thenReturn(Optional.of(creditAccount));
        when(journalEntryRepository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));
        when(accountRepository.updateBalanceAtomic(eq(company), any(), any())).thenReturn(1);
        when(accountingEventStore.recordJournalEntryPosted(any(), any()))
                .thenThrow(new IllegalStateException("event-store-down"));

        JournalEntryRequest request = new JournalEntryRequest(
                "EVT-STRICT",
                today,
                "Strict event trail failure test",
                null,
                null,
                Boolean.FALSE,
                List.of(
                        new JournalEntryRequest.JournalLineRequest(101L, "Debit line", new BigDecimal("20.00"), BigDecimal.ZERO),
                        new JournalEntryRequest.JournalLineRequest(102L, "Credit line", BigDecimal.ZERO, new BigDecimal("20.00"))
                )
        );

        assertThatThrownBy(() -> accountingService.createJournalEntry(request))
                .isInstanceOf(ApplicationException.class)
                .satisfies(ex -> assertThat(((ApplicationException) ex).getErrorCode())
                        .isEqualTo(ErrorCode.SYSTEM_DATABASE_ERROR))
                .hasMessageContaining("Accounting event trail persistence failed");
        ArgumentCaptor<Map<String, String>> integrationFailureCaptor = ArgumentCaptor.forClass(Map.class);
        verify(auditService).logFailure(eq(AuditEvent.INTEGRATION_FAILURE), integrationFailureCaptor.capture());
        assertThat(integrationFailureCaptor.getValue())
                .containsEntry("eventTrailOperation", "JOURNAL_ENTRY_POSTED")
                .containsEntry("policy", "STRICT")
                .containsEntry("failureCode", "ACCOUNTING_EVENT_TRAIL_PERSISTENCE_FAILURE")
                .containsEntry("errorCategory", "PERSISTENCE")
                .containsEntry("errorType", "IllegalStateException")
                .containsEntry("alertRoutingVersion", "ACCOUNTING_EVENT_TRAIL_V1")
                .containsEntry("alertRoute", "SEV2_URGENT")
                .doesNotContainKey("error");
    }

    @Test
    void createJournalEntry_bestEffortEventTrailFailureStillPosts() {
        ReflectionTestUtils.setField(accountingService, "strictAccountingEventTrail", false);
        LocalDate today = LocalDate.of(2024, 3, 23);
        when(companyClock.today(company)).thenReturn(today);
        AccountingPeriod period = new AccountingPeriod();
        period.setYear(today.getYear());
        period.setMonth(today.getMonthValue());
        when(accountingPeriodService.requireOpenPeriod(company, today)).thenReturn(period);
        when(journalEntryRepository.findByCompanyAndReferenceNumber(eq(company), eq("EVT-BEST-EFFORT")))
                .thenReturn(Optional.empty());

        Account debitAccount = new Account();
        ReflectionTestUtils.setField(debitAccount, "id", 201L);
        debitAccount.setCompany(company);
        debitAccount.setBalance(BigDecimal.ZERO);
        debitAccount.setCode("DEBIT-BE");
        Account creditAccount = new Account();
        ReflectionTestUtils.setField(creditAccount, "id", 202L);
        creditAccount.setCompany(company);
        creditAccount.setBalance(BigDecimal.ZERO);
        creditAccount.setCode("CREDIT-BE");
        when(accountRepository.lockByCompanyAndId(eq(company), eq(201L))).thenReturn(Optional.of(debitAccount));
        when(accountRepository.lockByCompanyAndId(eq(company), eq(202L))).thenReturn(Optional.of(creditAccount));
        when(journalEntryRepository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));
        when(accountRepository.updateBalanceAtomic(eq(company), any(), any())).thenReturn(1);
        when(accountingEventStore.recordJournalEntryPosted(any(), any()))
                .thenThrow(new IllegalStateException("event-store-down"));

        JournalEntryRequest request = new JournalEntryRequest(
                "EVT-BEST-EFFORT",
                today,
                "Best effort event trail failure test",
                null,
                null,
                Boolean.FALSE,
                List.of(
                        new JournalEntryRequest.JournalLineRequest(201L, "Debit line", new BigDecimal("15.00"), BigDecimal.ZERO),
                        new JournalEntryRequest.JournalLineRequest(202L, "Credit line", BigDecimal.ZERO, new BigDecimal("15.00"))
                )
        );

        JournalEntryDto result = accountingService.createJournalEntry(request);
        assertThat(result.referenceNumber()).isEqualTo("EVT-BEST-EFFORT");
        ArgumentCaptor<Map<String, String>> integrationFailureCaptor = ArgumentCaptor.forClass(Map.class);
        verify(auditService).logFailure(eq(AuditEvent.INTEGRATION_FAILURE), integrationFailureCaptor.capture());
        assertThat(integrationFailureCaptor.getValue())
                .containsEntry("eventTrailOperation", "JOURNAL_ENTRY_POSTED")
                .containsEntry("policy", "BEST_EFFORT")
                .containsEntry("failureCode", "ACCOUNTING_EVENT_TRAIL_PERSISTENCE_FAILURE")
                .containsEntry("errorCategory", "PERSISTENCE")
                .containsEntry("errorType", "IllegalStateException")
                .containsEntry("alertRoutingVersion", "ACCOUNTING_EVENT_TRAIL_V1")
                .containsEntry("alertRoute", "SEV3_TICKET")
                .doesNotContainKey("error");
        verify(auditService).logSuccess(eq(AuditEvent.JOURNAL_ENTRY_POSTED), any());
    }

    @Test
    void createJournalEntry_bestEffortEventTrailValidationFailureClassified() {
        ReflectionTestUtils.setField(accountingService, "strictAccountingEventTrail", false);
        LocalDate today = LocalDate.of(2024, 3, 24);
        when(companyClock.today(company)).thenReturn(today);
        AccountingPeriod period = new AccountingPeriod();
        period.setYear(today.getYear());
        period.setMonth(today.getMonthValue());
        when(accountingPeriodService.requireOpenPeriod(company, today)).thenReturn(period);
        when(journalEntryRepository.findByCompanyAndReferenceNumber(eq(company), eq("EVT-VALIDATION")))
                .thenReturn(Optional.empty());

        Account debitAccount = new Account();
        ReflectionTestUtils.setField(debitAccount, "id", 211L);
        debitAccount.setCompany(company);
        debitAccount.setBalance(BigDecimal.ZERO);
        debitAccount.setCode("DEBIT-VALIDATION");
        Account creditAccount = new Account();
        ReflectionTestUtils.setField(creditAccount, "id", 212L);
        creditAccount.setCompany(company);
        creditAccount.setBalance(BigDecimal.ZERO);
        creditAccount.setCode("CREDIT-VALIDATION");
        when(accountRepository.lockByCompanyAndId(eq(company), eq(211L))).thenReturn(Optional.of(debitAccount));
        when(accountRepository.lockByCompanyAndId(eq(company), eq(212L))).thenReturn(Optional.of(creditAccount));
        when(journalEntryRepository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));
        when(accountRepository.updateBalanceAtomic(eq(company), any(), any())).thenReturn(1);
        when(accountingEventStore.recordJournalEntryPosted(any(), any()))
                .thenThrow(new IllegalArgumentException("bad-event-payload"));

        JournalEntryRequest request = new JournalEntryRequest(
                "EVT-VALIDATION",
                today,
                "Best effort event trail validation classification",
                null,
                null,
                Boolean.FALSE,
                List.of(
                        new JournalEntryRequest.JournalLineRequest(211L, "Debit line", new BigDecimal("25.00"), BigDecimal.ZERO),
                        new JournalEntryRequest.JournalLineRequest(212L, "Credit line", BigDecimal.ZERO, new BigDecimal("25.00"))
                )
        );

        JournalEntryDto result = accountingService.createJournalEntry(request);
        assertThat(result.referenceNumber()).isEqualTo("EVT-VALIDATION");
        ArgumentCaptor<Map<String, String>> integrationFailureCaptor = ArgumentCaptor.forClass(Map.class);
        verify(auditService).logFailure(eq(AuditEvent.INTEGRATION_FAILURE), integrationFailureCaptor.capture());
        assertThat(integrationFailureCaptor.getValue())
                .containsEntry("failureCode", "ACCOUNTING_EVENT_TRAIL_PERSISTENCE_FAILURE")
                .containsEntry("errorCategory", "VALIDATION")
                .containsEntry("errorType", "IllegalArgumentException")
                .containsEntry("alertRoutingVersion", "ACCOUNTING_EVENT_TRAIL_V1")
                .containsEntry("alertRoute", "SEV2_URGENT");
    }

    @Test
    void createJournalEntry_bestEffortEventTrailDataIntegrityFailureClassified() {
        ReflectionTestUtils.setField(accountingService, "strictAccountingEventTrail", false);
        LocalDate today = LocalDate.of(2024, 3, 25);
        when(companyClock.today(company)).thenReturn(today);
        AccountingPeriod period = new AccountingPeriod();
        period.setYear(today.getYear());
        period.setMonth(today.getMonthValue());
        when(accountingPeriodService.requireOpenPeriod(company, today)).thenReturn(period);
        when(journalEntryRepository.findByCompanyAndReferenceNumber(eq(company), eq("EVT-INTEGRITY")))
                .thenReturn(Optional.empty());

        Account debitAccount = new Account();
        ReflectionTestUtils.setField(debitAccount, "id", 221L);
        debitAccount.setCompany(company);
        debitAccount.setBalance(BigDecimal.ZERO);
        debitAccount.setCode("DEBIT-INTEGRITY");
        Account creditAccount = new Account();
        ReflectionTestUtils.setField(creditAccount, "id", 222L);
        creditAccount.setCompany(company);
        creditAccount.setBalance(BigDecimal.ZERO);
        creditAccount.setCode("CREDIT-INTEGRITY");
        when(accountRepository.lockByCompanyAndId(eq(company), eq(221L))).thenReturn(Optional.of(debitAccount));
        when(accountRepository.lockByCompanyAndId(eq(company), eq(222L))).thenReturn(Optional.of(creditAccount));
        when(journalEntryRepository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));
        when(accountRepository.updateBalanceAtomic(eq(company), any(), any())).thenReturn(1);
        when(accountingEventStore.recordJournalEntryPosted(any(), any()))
                .thenThrow(new DataIntegrityViolationException("duplicate-event-sequence"));

        JournalEntryRequest request = new JournalEntryRequest(
                "EVT-INTEGRITY",
                today,
                "Best effort event trail data integrity classification",
                null,
                null,
                Boolean.FALSE,
                List.of(
                        new JournalEntryRequest.JournalLineRequest(221L, "Debit line", new BigDecimal("30.00"), BigDecimal.ZERO),
                        new JournalEntryRequest.JournalLineRequest(222L, "Credit line", BigDecimal.ZERO, new BigDecimal("30.00"))
                )
        );

        JournalEntryDto result = accountingService.createJournalEntry(request);
        assertThat(result.referenceNumber()).isEqualTo("EVT-INTEGRITY");
        ArgumentCaptor<Map<String, String>> integrationFailureCaptor = ArgumentCaptor.forClass(Map.class);
        verify(auditService).logFailure(eq(AuditEvent.INTEGRATION_FAILURE), integrationFailureCaptor.capture());
        assertThat(integrationFailureCaptor.getValue())
                .containsEntry("failureCode", "ACCOUNTING_EVENT_TRAIL_PERSISTENCE_FAILURE")
                .containsEntry("errorCategory", "DATA_INTEGRITY")
                .containsEntry("errorType", "DataIntegrityViolationException")
                .containsEntry("alertRoutingVersion", "ACCOUNTING_EVENT_TRAIL_V1")
                .containsEntry("alertRoute", "SEV1_PAGE");
    }

    @Test
    void createJournalEntry_rejectsEventTrailIncompatibleReferenceLength() {
        LocalDate today = LocalDate.of(2024, 3, 24);
        when(companyClock.today(company)).thenReturn(today);
        AccountingPeriod period = new AccountingPeriod();
        period.setYear(today.getYear());
        period.setMonth(today.getMonthValue());
        when(accountingPeriodService.requireOpenPeriod(company, today)).thenReturn(period);
        String oversizedReference = "R".repeat(101);
        when(journalEntryRepository.findByCompanyAndReferenceNumber(eq(company), eq(oversizedReference)))
                .thenReturn(Optional.empty());

        Account debitAccount = new Account();
        ReflectionTestUtils.setField(debitAccount, "id", 301L);
        debitAccount.setCompany(company);
        debitAccount.setBalance(BigDecimal.ZERO);
        debitAccount.setCode("DEBIT-LIMIT");
        Account creditAccount = new Account();
        ReflectionTestUtils.setField(creditAccount, "id", 302L);
        creditAccount.setCompany(company);
        creditAccount.setBalance(BigDecimal.ZERO);
        creditAccount.setCode("CREDIT-LIMIT");
        when(accountRepository.lockByCompanyAndId(eq(company), eq(301L))).thenReturn(Optional.of(debitAccount));
        when(accountRepository.lockByCompanyAndId(eq(company), eq(302L))).thenReturn(Optional.of(creditAccount));
        when(journalEntryRepository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));
        when(accountRepository.updateBalanceAtomic(eq(company), any(), any())).thenReturn(1);

        JournalEntryRequest request = new JournalEntryRequest(
                oversizedReference,
                today,
                "Event compatibility length violation",
                null,
                null,
                Boolean.FALSE,
                List.of(
                        new JournalEntryRequest.JournalLineRequest(301L, "Debit line", new BigDecimal("10.00"), BigDecimal.ZERO),
                        new JournalEntryRequest.JournalLineRequest(302L, "Credit line", BigDecimal.ZERO, new BigDecimal("10.00"))
                )
        );

        assertThatThrownBy(() -> accountingService.createJournalEntry(request))
                .isInstanceOf(ApplicationException.class)
                .satisfies(ex -> assertThat(((ApplicationException) ex).getErrorCode())
                        .isEqualTo(ErrorCode.VALIDATION_INVALID_INPUT))
                .hasMessageContaining("Accounting event-trail field exceeds allowed length");

        verify(accountingEventStore, never()).recordJournalEntryPosted(any(), any());
    }

    @Test
    void reverseJournalEntry_failsWhenEventTrailPersistenceFailsInStrictMode() {
        LocalDate today = LocalDate.of(2024, 4, 2);
        when(companyClock.today(company)).thenReturn(today);
        AccountingPeriod openPeriod = new AccountingPeriod();
        when(accountingPeriodService.requireOpenPeriod(company, today)).thenReturn(openPeriod);

        AccountingService service = spy(accountingService);
        JournalEntry original = reversalSourceEntry(610L, "REV-EVT-STRICT", today);
        JournalEntry reversal = new JournalEntry();
        ReflectionTestUtils.setField(reversal, "id", 910L);

        when(companyEntityLookup.requireJournalEntry(company, 610L)).thenReturn(original);
        when(companyEntityLookup.requireJournalEntry(company, 910L)).thenReturn(reversal);
        when(journalEntryRepository.save(any(JournalEntry.class))).thenAnswer(invocation -> invocation.getArgument(0));
        doReturn(stubEntry(910L)).when(service).createJournalEntry(any(JournalEntryRequest.class));
        when(accountingEventStore.recordJournalEntryReversed(any(), any(), any()))
                .thenThrow(new IllegalStateException("event-store-down"));

        JournalEntryReversalRequest request = new JournalEntryReversalRequest(
                today,
                false,
                "Strict reversal event failure",
                "Reversal strict failure test",
                Boolean.FALSE
        );

        assertThatThrownBy(() -> service.reverseJournalEntry(610L, request))
                .isInstanceOf(ApplicationException.class)
                .satisfies(ex -> assertThat(((ApplicationException) ex).getErrorCode())
                        .isEqualTo(ErrorCode.SYSTEM_DATABASE_ERROR))
                .hasMessageContaining("Accounting event trail persistence failed");

        ArgumentCaptor<Map<String, String>> integrationFailureCaptor = ArgumentCaptor.forClass(Map.class);
        verify(auditService).logFailure(eq(AuditEvent.INTEGRATION_FAILURE), integrationFailureCaptor.capture());
        assertThat(integrationFailureCaptor.getValue())
                .containsEntry("eventTrailOperation", "JOURNAL_ENTRY_REVERSED")
                .containsEntry("policy", "STRICT")
                .containsEntry("failureCode", "ACCOUNTING_EVENT_TRAIL_PERSISTENCE_FAILURE")
                .containsEntry("errorCategory", "PERSISTENCE")
                .containsEntry("errorType", "IllegalStateException")
                .containsEntry("alertRoutingVersion", "ACCOUNTING_EVENT_TRAIL_V1")
                .containsEntry("alertRoute", "SEV2_URGENT")
                .doesNotContainKey("error");
        verify(auditService, never()).logSuccess(eq(AuditEvent.JOURNAL_ENTRY_REVERSED), any());
    }

    @Test
    void reverseJournalEntry_bestEffortEventTrailValidationApplicationExceptionClassified() {
        ReflectionTestUtils.setField(accountingService, "strictAccountingEventTrail", false);
        LocalDate today = LocalDate.of(2024, 4, 3);
        when(companyClock.today(company)).thenReturn(today);
        AccountingPeriod openPeriod = new AccountingPeriod();
        when(accountingPeriodService.requireOpenPeriod(company, today)).thenReturn(openPeriod);

        AccountingService service = spy(accountingService);
        JournalEntry original = reversalSourceEntry(612L, "REV-EVT-VALIDATION", today);
        JournalEntry reversal = new JournalEntry();
        ReflectionTestUtils.setField(reversal, "id", 912L);

        when(companyEntityLookup.requireJournalEntry(company, 612L)).thenReturn(original);
        when(companyEntityLookup.requireJournalEntry(company, 912L)).thenReturn(reversal);
        when(journalEntryRepository.save(any(JournalEntry.class))).thenAnswer(invocation -> invocation.getArgument(0));
        doReturn(stubEntry(912L)).when(service).createJournalEntry(any(JournalEntryRequest.class));
        when(accountingEventStore.recordJournalEntryReversed(any(), any(), any()))
                .thenThrow(new ApplicationException(
                        ErrorCode.VALIDATION_INVALID_REFERENCE,
                        "invalid reversal event payload"));

        JournalEntryReversalRequest request = new JournalEntryReversalRequest(
                today,
                false,
                "Best effort reversal validation classification",
                "Reversal validation classification test",
                Boolean.FALSE
        );

        JournalEntryDto result = service.reverseJournalEntry(612L, request);
        assertThat(result.id()).isEqualTo(912L);

        ArgumentCaptor<Map<String, String>> integrationFailureCaptor = ArgumentCaptor.forClass(Map.class);
        verify(auditService).logFailure(eq(AuditEvent.INTEGRATION_FAILURE), integrationFailureCaptor.capture());
        assertThat(integrationFailureCaptor.getValue())
                .containsEntry("eventTrailOperation", "JOURNAL_ENTRY_REVERSED")
                .containsEntry("policy", "BEST_EFFORT")
                .containsEntry("failureCode", "ACCOUNTING_EVENT_TRAIL_PERSISTENCE_FAILURE")
                .containsEntry("errorCategory", "VALIDATION")
                .containsEntry("errorType", "ApplicationException")
                .containsEntry("alertRoutingVersion", "ACCOUNTING_EVENT_TRAIL_V1")
                .containsEntry("alertRoute", "SEV2_URGENT");
        verify(auditService).logSuccess(eq(AuditEvent.JOURNAL_ENTRY_REVERSED), any());
    }

    @Test
    void reverseJournalEntry_bestEffortEventTrailDataIntegrityApplicationExceptionClassified() {
        ReflectionTestUtils.setField(accountingService, "strictAccountingEventTrail", false);
        LocalDate today = LocalDate.of(2024, 4, 4);
        when(companyClock.today(company)).thenReturn(today);
        AccountingPeriod openPeriod = new AccountingPeriod();
        when(accountingPeriodService.requireOpenPeriod(company, today)).thenReturn(openPeriod);

        AccountingService service = spy(accountingService);
        JournalEntry original = reversalSourceEntry(613L, "REV-EVT-INTEGRITY", today);
        JournalEntry reversal = new JournalEntry();
        ReflectionTestUtils.setField(reversal, "id", 913L);

        when(companyEntityLookup.requireJournalEntry(company, 613L)).thenReturn(original);
        when(companyEntityLookup.requireJournalEntry(company, 913L)).thenReturn(reversal);
        when(journalEntryRepository.save(any(JournalEntry.class))).thenAnswer(invocation -> invocation.getArgument(0));
        doReturn(stubEntry(913L)).when(service).createJournalEntry(any(JournalEntryRequest.class));
        when(accountingEventStore.recordJournalEntryReversed(any(), any(), any()))
                .thenThrow(new ApplicationException(
                        ErrorCode.INTERNAL_CONCURRENCY_FAILURE,
                        "duplicate reversal event key"));

        JournalEntryReversalRequest request = new JournalEntryReversalRequest(
                today,
                false,
                "Best effort reversal data integrity classification",
                "Reversal data integrity classification test",
                Boolean.FALSE
        );

        JournalEntryDto result = service.reverseJournalEntry(613L, request);
        assertThat(result.id()).isEqualTo(913L);

        ArgumentCaptor<Map<String, String>> integrationFailureCaptor = ArgumentCaptor.forClass(Map.class);
        verify(auditService).logFailure(eq(AuditEvent.INTEGRATION_FAILURE), integrationFailureCaptor.capture());
        assertThat(integrationFailureCaptor.getValue())
                .containsEntry("eventTrailOperation", "JOURNAL_ENTRY_REVERSED")
                .containsEntry("policy", "BEST_EFFORT")
                .containsEntry("failureCode", "ACCOUNTING_EVENT_TRAIL_PERSISTENCE_FAILURE")
                .containsEntry("errorCategory", "DATA_INTEGRITY")
                .containsEntry("errorType", "ApplicationException")
                .containsEntry("alertRoutingVersion", "ACCOUNTING_EVENT_TRAIL_V1")
                .containsEntry("alertRoute", "SEV1_PAGE");
        verify(auditService).logSuccess(eq(AuditEvent.JOURNAL_ENTRY_REVERSED), any());
    }

    @Test
    void reverseJournalEntry_bestEffortEventTrailFailureContinuesWhenAuditMarkerFails() {
        ReflectionTestUtils.setField(accountingService, "strictAccountingEventTrail", false);
        LocalDate today = LocalDate.of(2024, 4, 3);
        when(companyClock.today(company)).thenReturn(today);
        AccountingPeriod openPeriod = new AccountingPeriod();
        when(accountingPeriodService.requireOpenPeriod(company, today)).thenReturn(openPeriod);

        AccountingService service = spy(accountingService);
        JournalEntry original = reversalSourceEntry(611L, "REV-EVT-BEST", today);
        JournalEntry reversal = new JournalEntry();
        ReflectionTestUtils.setField(reversal, "id", 911L);

        when(companyEntityLookup.requireJournalEntry(company, 611L)).thenReturn(original);
        when(companyEntityLookup.requireJournalEntry(company, 911L)).thenReturn(reversal);
        when(journalEntryRepository.save(any(JournalEntry.class))).thenAnswer(invocation -> invocation.getArgument(0));
        doReturn(stubEntry(911L)).when(service).createJournalEntry(any(JournalEntryRequest.class));
        when(accountingEventStore.recordJournalEntryReversed(any(), any(), any()))
                .thenThrow(new IllegalStateException("event-store-down"));
        doThrow(new IllegalStateException("audit-log-down"))
                .when(auditService)
                .logFailure(eq(AuditEvent.INTEGRATION_FAILURE), org.mockito.ArgumentMatchers.<Map<String, String>>any());

        JournalEntryReversalRequest request = new JournalEntryReversalRequest(
                today,
                false,
                "Best effort reversal event failure",
                "Reversal best effort failure test",
                Boolean.FALSE
        );

        JournalEntryDto result = service.reverseJournalEntry(611L, request);
        assertThat(result.id()).isEqualTo(911L);
        verify(auditService).logFailure(eq(AuditEvent.INTEGRATION_FAILURE), org.mockito.ArgumentMatchers.<Map<String, String>>any());
        verify(auditService).logSuccess(eq(AuditEvent.JOURNAL_ENTRY_REVERSED), any());
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
    void recordSupplierPayment_requiresPurchaseAllocation() {
        Supplier supplier = new Supplier();
        supplier.setName("Supplier");
        ReflectionTestUtils.setField(supplier, "id", 1L);

        Account payable = new Account();
        payable.setCompany(company);
        payable.setCode("AP-SUP");
        payable.setType(AccountType.LIABILITY);
        ReflectionTestUtils.setField(payable, "id", 10L);
        supplier.setPayableAccount(payable);

        Account cash = new Account();
        cash.setCompany(company);
        cash.setCode("CASH");
        cash.setType(AccountType.ASSET);
        ReflectionTestUtils.setField(cash, "id", 20L);

        when(supplierRepository.lockByCompanyAndId(eq(company), eq(1L))).thenReturn(Optional.of(supplier));
        when(companyEntityLookup.requireAccount(eq(company), eq(20L))).thenReturn(cash);

        SettlementAllocationRequest allocation = new SettlementAllocationRequest(
                null,
                null,
                new BigDecimal("100.00"),
                BigDecimal.ZERO,
                BigDecimal.ZERO,
                null,
                null
        );

        SupplierPaymentRequest request = new SupplierPaymentRequest(
                1L,
                20L,
                new BigDecimal("100.00"),
                "PAY-ONACC-1",
                "On-account attempt",
                "IDEMP-PAY-ONACC-1",
                List.of(allocation)
        );

        assertThatThrownBy(() -> accountingService.recordSupplierPayment(request))
                .isInstanceOf(ApplicationException.class)
                .hasMessageContaining("Purchase allocation is required for supplier payments");
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
        verify(invoiceSettlementPolicy).applySettlement(eq(invoice), amountCaptor.capture(), eq("REF-AR-1-INV-5"));
        assertThat(amountCaptor.getValue()).isEqualByComparingTo("500.00");
    }

    @Test
    void settleDealerInvoices_idempotentReplayReturnsSameCashAmount() {
        AccountingService service = spy(accountingService);

        Dealer dealer = new Dealer();
        dealer.setName("Dealer");
        ReflectionTestUtils.setField(dealer, "id", 1L);

        Account receivable = new Account();
        receivable.setCompany(company);
        receivable.setCode("AR");
        receivable.setType(AccountType.ASSET);
        ReflectionTestUtils.setField(receivable, "id", 10L);
        dealer.setReceivableAccount(receivable);

        Account cash = new Account();
        cash.setCompany(company);
        cash.setCode("CASH");
        ReflectionTestUtils.setField(cash, "id", 20L);

        Account discount = new Account();
        discount.setCompany(company);
        discount.setCode("DISC");
        ReflectionTestUtils.setField(discount, "id", 21L);

        var invoice = new com.bigbrightpaints.erp.modules.invoice.domain.Invoice();
        invoice.setCompany(company);
        invoice.setDealer(dealer);
        invoice.setCurrency("INR");
        invoice.setOutstandingAmount(new BigDecimal("1000.00"));
        invoice.setTotalAmount(new BigDecimal("1000.00"));
        ReflectionTestUtils.setField(invoice, "id", 5L);

        var journalEntry = new JournalEntry();
        ReflectionTestUtils.setField(journalEntry, "id", 44L);
        journalEntry.setDealer(dealer);
        journalEntry.setReferenceNumber("DR-TEST-1");
        journalEntry.setMemo("Dealer settlement");
        journalEntry.getLines().add(journalLine(journalEntry, cash, "Dealer settlement", new BigDecimal("450.00"), BigDecimal.ZERO));
        journalEntry.getLines().add(journalLine(journalEntry, discount, "Settlement discount", new BigDecimal("50.00"), BigDecimal.ZERO));
        journalEntry.getLines().add(journalLine(journalEntry, receivable, "Dealer settlement", BigDecimal.ZERO, new BigDecimal("500.00")));

        when(dealerRepository.lockByCompanyAndId(eq(company), eq(1L))).thenReturn(Optional.of(dealer));
        when(invoiceRepository.lockByCompanyAndId(eq(company), eq(5L))).thenReturn(Optional.of(invoice));
        when(companyEntityLookup.requireAccount(eq(company), eq(20L))).thenReturn(cash);
        when(companyEntityLookup.requireAccount(eq(company), eq(21L))).thenReturn(discount);
        when(companyEntityLookup.requireJournalEntry(eq(company), eq(44L))).thenReturn(journalEntry);

        List<com.bigbrightpaints.erp.modules.accounting.domain.PartnerSettlementAllocation> savedRows = new ArrayList<>();
        AtomicInteger allocationLookupCount = new AtomicInteger(0);
        when(settlementAllocationRepository.findByCompanyAndIdempotencyKey(eq(company), eq("IDEMP-AR-CASH")))
                .thenAnswer(invocation -> allocationLookupCount.getAndIncrement() == 0 ? List.of() : List.copyOf(savedRows));
        when(settlementAllocationRepository.saveAll(any()))
                .thenAnswer(invocation -> {
                    @SuppressWarnings("unchecked")
                    List<com.bigbrightpaints.erp.modules.accounting.domain.PartnerSettlementAllocation> incoming =
                            (List<com.bigbrightpaints.erp.modules.accounting.domain.PartnerSettlementAllocation>) invocation.getArgument(0);
                    savedRows.clear();
                    savedRows.addAll(incoming);
                    return incoming;
                });

        doReturn(stubEntry(44L)).when(service).createJournalEntry(any());

        SettlementAllocationRequest allocation = new SettlementAllocationRequest(
                5L,
                null,
                new BigDecimal("500.00"),
                new BigDecimal("50.00"),
                BigDecimal.ZERO,
                BigDecimal.ZERO,
                "settle");

        DealerSettlementRequest request = new DealerSettlementRequest(
                1L,
                20L,
                21L,
                null,
                null,
                null,
                LocalDate.of(2024, 4, 9),
                "DR-TEST-1",
                "Dealer settlement",
                "IDEMP-AR-CASH",
                Boolean.FALSE,
                List.of(allocation),
                null
        );

        PartnerSettlementResponse first = service.settleDealerInvoices(request);
        PartnerSettlementResponse replay = service.settleDealerInvoices(request);

        assertThat(first.cashAmount()).isEqualByComparingTo("450.00");
        assertThat(replay.cashAmount()).isEqualByComparingTo(first.cashAmount());
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
    void settleSupplierInvoices_allowsOnAccountAllocationWithoutMemo() {
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
        cash.setType(AccountType.ASSET);
        ReflectionTestUtils.setField(cash, "id", 20L);

        when(supplierRepository.lockByCompanyAndId(eq(company), eq(1L))).thenReturn(Optional.of(supplier));
        when(settlementAllocationRepository.findByCompanyAndIdempotencyKey(any(), any())).thenReturn(List.of());
        when(companyEntityLookup.requireAccount(eq(company), eq(20L))).thenReturn(cash);
        JournalEntryDto journalEntryDto = stubEntry(87L);
        doReturn(journalEntryDto).when(service).createJournalEntry(any(JournalEntryRequest.class));
        when(companyEntityLookup.requireJournalEntry(eq(company), eq(87L)))
                .thenReturn(new com.bigbrightpaints.erp.modules.accounting.domain.JournalEntry());

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

        var response = service.settleSupplierInvoices(request);
        assertThat(response.journalEntry()).isNotNull();
        assertThat(response.journalEntry().id()).isEqualTo(87L);
        assertThat(response.totalApplied()).isEqualByComparingTo("100.00");
        assertThat(response.allocations()).hasSize(1);
        assertThat(response.allocations().getFirst().purchaseId()).isNull();
        assertThat(response.allocations().getFirst().memo()).isNull();
    }

    @Test
    void settleSupplierInvoices_allowsOnAccountAllocationWithMemo() {
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
        cash.setType(AccountType.ASSET);
        ReflectionTestUtils.setField(cash, "id", 20L);

        when(supplierRepository.lockByCompanyAndId(eq(company), eq(1L))).thenReturn(Optional.of(supplier));
        when(settlementAllocationRepository.findByCompanyAndIdempotencyKey(any(), any())).thenReturn(List.of());
        when(companyEntityLookup.requireAccount(eq(company), eq(20L))).thenReturn(cash);

        JournalEntryDto journalEntryDto = stubEntry(88L);
        doReturn(journalEntryDto).when(service).createJournalEntry(any(JournalEntryRequest.class));
        when(companyEntityLookup.requireJournalEntry(eq(company), eq(88L)))
                .thenReturn(new com.bigbrightpaints.erp.modules.accounting.domain.JournalEntry());

        SettlementAllocationRequest allocation = new SettlementAllocationRequest(
                null,
                null,
                new BigDecimal("100.00"),
                BigDecimal.ZERO,
                BigDecimal.ZERO,
                null,
                "AP on-account clearing");

        SupplierSettlementRequest request = new SupplierSettlementRequest(
                1L,
                20L,
                null,
                null,
                null,
                null,
                LocalDate.of(2024, 4, 9),
                "REF-AP-ONACC-1",
                "Supplier settlement",
                "IDEMP-AP-ONACC-1",
                Boolean.FALSE,
                List.of(allocation)
        );

        var response = service.settleSupplierInvoices(request);

        assertThat(response.journalEntry()).isNotNull();
        assertThat(response.journalEntry().id()).isEqualTo(88L);
        assertThat(response.totalApplied()).isEqualByComparingTo("100.00");
        assertThat(response.allocations()).hasSize(1);
        assertThat(response.allocations().getFirst().purchaseId()).isNull();
        assertThat(response.allocations().getFirst().invoiceId()).isNull();
    }

    @Test
    void settleSupplierInvoices_nonLeaderReplayRepairsReferenceMapping() {
        Supplier supplier = new Supplier();
        supplier.setName("Replay Supplier");
        ReflectionTestUtils.setField(supplier, "id", 1L);

        Account payable = new Account();
        payable.setCompany(company);
        payable.setCode("AP-REPLAY");
        payable.setType(AccountType.LIABILITY);
        ReflectionTestUtils.setField(payable, "id", 10L);
        supplier.setPayableAccount(payable);

        Account cash = new Account();
        cash.setCompany(company);
        cash.setCode("CASH-REPLAY");
        cash.setType(AccountType.ASSET);
        ReflectionTestUtils.setField(cash, "id", 20L);

        JournalEntry existingEntry = new JournalEntry();
        ReflectionTestUtils.setField(existingEntry, "id", 901L);
        existingEntry.setSupplier(supplier);
        existingEntry.setReferenceNumber("SUP-SETTLE-REPLAY-1");
        existingEntry.setMemo("Supplier settlement replay");
        existingEntry.getLines().add(journalLine(existingEntry, payable, "Supplier settlement replay", new BigDecimal("100.00"), BigDecimal.ZERO));
        existingEntry.getLines().add(journalLine(existingEntry, cash, "Supplier settlement replay", BigDecimal.ZERO, new BigDecimal("100.00")));

        com.bigbrightpaints.erp.modules.accounting.domain.PartnerSettlementAllocation existingRow =
                new com.bigbrightpaints.erp.modules.accounting.domain.PartnerSettlementAllocation();
        existingRow.setCompany(company);
        existingRow.setPartnerType(com.bigbrightpaints.erp.modules.accounting.domain.PartnerType.SUPPLIER);
        existingRow.setSupplier(supplier);
        existingRow.setJournalEntry(existingEntry);
        existingRow.setSettlementDate(LocalDate.of(2024, 4, 9));
        existingRow.setAllocationAmount(new BigDecimal("100.00"));
        existingRow.setDiscountAmount(BigDecimal.ZERO);
        existingRow.setWriteOffAmount(BigDecimal.ZERO);
        existingRow.setFxDifferenceAmount(BigDecimal.ZERO);
        existingRow.setIdempotencyKey("IDEMP-AP-REPLAY");
        existingRow.setMemo(null);

        JournalReferenceMapping mapping = new JournalReferenceMapping();
        mapping.setCompany(company);
        mapping.setLegacyReference("idemp-ap-replay");
        mapping.setCanonicalReference("SUP-SETTLE-REPLAY-1");
        mapping.setEntityType("SUPPLIER_SETTLEMENT");
        mapping.setEntityId(null);

        when(supplierRepository.lockByCompanyAndId(eq(company), eq(1L))).thenReturn(Optional.of(supplier));
        when(companyEntityLookup.requireAccount(eq(company), eq(20L))).thenReturn(cash);
        when(journalReferenceMappingRepository.findAllByCompanyAndLegacyReferenceIgnoreCase(eq(company), eq("idemp-ap-replay")))
                .thenReturn(List.of(mapping));
        when(journalReferenceResolver.findExistingEntry(eq(company), eq("SUP-SETTLE-REPLAY-1")))
                .thenReturn(Optional.of(existingEntry));
        when(settlementAllocationRepository.findByCompanyAndIdempotencyKeyIgnoreCaseOrderByCreatedAtAscIdAsc(eq(company), eq("IDEMP-AP-REPLAY")))
                .thenReturn(List.of(existingRow));

        SettlementAllocationRequest allocation = new SettlementAllocationRequest(
                null,
                null,
                new BigDecimal("100.00"),
                BigDecimal.ZERO,
                BigDecimal.ZERO,
                BigDecimal.ZERO,
                null
        );

        SupplierSettlementRequest request = new SupplierSettlementRequest(
                1L,
                20L,
                null,
                null,
                null,
                null,
                LocalDate.of(2024, 4, 9),
                null,
                "Supplier settlement replay",
                "IDEMP-AP-REPLAY",
                Boolean.FALSE,
                List.of(allocation)
        );

        PartnerSettlementResponse response = accountingService.settleSupplierInvoices(request);

        assertThat(response.journalEntry()).isNotNull();
        assertThat(response.journalEntry().id()).isEqualTo(901L);
        verify(journalReferenceMappingRepository).save(mapping);
        assertThat(mapping.getEntityId()).isEqualTo(901L);
        assertThat(mapping.getEntityType()).isEqualTo("SUPPLIER_SETTLEMENT");
        assertThat(mapping.getCanonicalReference()).isEqualTo("SUP-SETTLE-REPLAY-1");
    }

    @Test
    void settleSupplierInvoices_rejectsNonAssetCashAccount() {
        AccountingService service = spy(accountingService);

        Supplier supplier = new Supplier();
        supplier.setName("Supplier");
        Account payable = new Account();
        payable.setCompany(company);
        payable.setCode("AP-SUP");
        payable.setType(AccountType.LIABILITY);
        ReflectionTestUtils.setField(payable, "id", 10L);
        supplier.setPayableAccount(payable);
        ReflectionTestUtils.setField(supplier, "id", 1L);

        Account invalidCash = new Account();
        invalidCash.setCompany(company);
        invalidCash.setCode("AP-CLEARING");
        invalidCash.setType(AccountType.LIABILITY);
        ReflectionTestUtils.setField(invalidCash, "id", 20L);

        when(supplierRepository.lockByCompanyAndId(eq(company), eq(1L))).thenReturn(Optional.of(supplier));
        when(companyEntityLookup.requireAccount(eq(company), eq(20L))).thenReturn(invalidCash);

        SettlementAllocationRequest allocation = new SettlementAllocationRequest(
                null,
                2L,
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
                "REF-AP-2",
                "Supplier settlement",
                "IDEMP-AP-2",
                Boolean.FALSE,
                List.of(allocation)
        );

        assertThatThrownBy(() -> service.settleSupplierInvoices(request))
                .isInstanceOf(ApplicationException.class)
                .hasMessageContaining("must be an ASSET account");
    }

    private JournalLine journalLine(JournalEntry entry,
                                    Account account,
                                    String description,
                                    BigDecimal debit,
                                    BigDecimal credit) {
        JournalLine line = new JournalLine();
        line.setJournalEntry(entry);
        line.setAccount(account);
        line.setDescription(description);
        line.setDebit(debit);
        line.setCredit(credit);
        return line;
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

    private JournalEntry reversalSourceEntry(Long id, String reference, LocalDate entryDate) {
        JournalEntry entry = new JournalEntry();
        ReflectionTestUtils.setField(entry, "id", id);
        entry.setStatus("POSTED");
        entry.setReferenceNumber(reference);
        entry.setEntryDate(entryDate);

        Account account = new Account();
        ReflectionTestUtils.setField(account, "id", 700L);
        account.setCode("CASH");
        account.setName("Cash");
        account.setType(AccountType.ASSET);
        account.setCompany(company);

        JournalLine line = new JournalLine();
        line.setJournalEntry(entry);
        line.setAccount(account);
        line.setDescription("line");
        line.setDebit(new BigDecimal("10.00"));
        line.setCredit(BigDecimal.ZERO);
        entry.getLines().add(line);
        return entry;
    }
}
