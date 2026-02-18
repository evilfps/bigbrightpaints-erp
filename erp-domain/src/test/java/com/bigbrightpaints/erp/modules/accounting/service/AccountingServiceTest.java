package com.bigbrightpaints.erp.modules.accounting.service;

import com.bigbrightpaints.erp.core.audit.AuditEvent;
import com.bigbrightpaints.erp.core.audit.IntegrationFailureMetadataSchema;
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
import com.bigbrightpaints.erp.modules.accounting.dto.DealerReceiptRequest;
import com.bigbrightpaints.erp.modules.accounting.dto.DealerReceiptSplitRequest;
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
import com.bigbrightpaints.erp.modules.accounting.domain.PartnerSettlementAllocation;
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
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.times;
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
    void reverseJournalEntry_suppressesLegacySummaryAudit_afterCommit() {
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

        verify(auditService, never()).logSuccess(eq(AuditEvent.JOURNAL_ENTRY_REVERSED), any());
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
    void reverseJournalEntry_skipsLegacySummaryAudit_withoutToggle() {
        LocalDate today = LocalDate.of(2024, 4, 1);
        when(companyClock.today(company)).thenReturn(today);
        AccountingPeriod openPeriod = new AccountingPeriod();
        when(accountingPeriodService.requireOpenPeriod(company, today)).thenReturn(openPeriod);

        AccountingService service = spy(accountingService);
        JournalEntry original = reversalSourceEntry(502L, "REV-AUDIT-POLICY", today);
        JournalEntry reversal = new JournalEntry();
        ReflectionTestUtils.setField(reversal, "id", 902L);

        when(companyEntityLookup.requireJournalEntry(company, 502L)).thenReturn(original);
        when(companyEntityLookup.requireJournalEntry(company, 902L)).thenReturn(reversal);
        when(journalEntryRepository.save(any(JournalEntry.class))).thenAnswer(invocation -> invocation.getArgument(0));
        doReturn(stubEntry(902L)).when(service).createJournalEntry(any(JournalEntryRequest.class));

        JournalEntryReversalRequest request = new JournalEntryReversalRequest(
                today,
                false,
                "Test reversal",
                "Audit suppression policy",
                Boolean.FALSE
        );

        TransactionTemplate transactionTemplate = new TransactionTemplate(new ResourcelessTransactionManager());
        transactionTemplate.executeWithoutResult(status -> {
            JournalEntryDto result = service.reverseJournalEntry(502L, request);
            assertThat(result.id()).isEqualTo(902L);
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
    void createJournalEntry_requiresExplicitSupplierContextForOwnedPayableAccount() {
        LocalDate today = LocalDate.of(2024, 4, 7);
        when(companyClock.today(company)).thenReturn(today);
        when(journalEntryRepository.findByCompanyAndReferenceNumber(eq(company), eq("AP-INFER-SUPPLIER")))
                .thenReturn(Optional.empty());

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

        assertThatThrownBy(() -> accountingService.createJournalEntry(request))
                .isInstanceOf(ApplicationException.class)
                .hasMessageContaining("requires a supplier context");
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
                .containsEntry(IntegrationFailureMetadataSchema.KEY_FAILURE_CODE, "ACCOUNTING_EVENT_TRAIL_PERSISTENCE_FAILURE")
                .containsEntry(IntegrationFailureMetadataSchema.KEY_ERROR_CATEGORY, "PERSISTENCE")
                .containsEntry("errorType", "IllegalStateException")
                .containsEntry(IntegrationFailureMetadataSchema.KEY_ALERT_ROUTING_VERSION, "ACCOUNTING_EVENT_TRAIL_V1")
                .containsEntry(IntegrationFailureMetadataSchema.KEY_ALERT_ROUTE, "SEV2_URGENT")
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
                .containsEntry(IntegrationFailureMetadataSchema.KEY_FAILURE_CODE, "ACCOUNTING_EVENT_TRAIL_PERSISTENCE_FAILURE")
                .containsEntry(IntegrationFailureMetadataSchema.KEY_ERROR_CATEGORY, "PERSISTENCE")
                .containsEntry("errorType", "IllegalStateException")
                .containsEntry(IntegrationFailureMetadataSchema.KEY_ALERT_ROUTING_VERSION, "ACCOUNTING_EVENT_TRAIL_V1")
                .containsEntry(IntegrationFailureMetadataSchema.KEY_ALERT_ROUTE, "SEV3_TICKET")
                .doesNotContainKey("error");
        verify(auditService, never()).logSuccess(eq(AuditEvent.JOURNAL_ENTRY_POSTED), any());
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
                .containsEntry(IntegrationFailureMetadataSchema.KEY_FAILURE_CODE, "ACCOUNTING_EVENT_TRAIL_PERSISTENCE_FAILURE")
                .containsEntry(IntegrationFailureMetadataSchema.KEY_ERROR_CATEGORY, "VALIDATION")
                .containsEntry("errorType", "IllegalArgumentException")
                .containsEntry(IntegrationFailureMetadataSchema.KEY_ALERT_ROUTING_VERSION, "ACCOUNTING_EVENT_TRAIL_V1")
                .containsEntry(IntegrationFailureMetadataSchema.KEY_ALERT_ROUTE, "SEV2_URGENT");
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
                .containsEntry(IntegrationFailureMetadataSchema.KEY_FAILURE_CODE, "ACCOUNTING_EVENT_TRAIL_PERSISTENCE_FAILURE")
                .containsEntry(IntegrationFailureMetadataSchema.KEY_ERROR_CATEGORY, "DATA_INTEGRITY")
                .containsEntry("errorType", "DataIntegrityViolationException")
                .containsEntry(IntegrationFailureMetadataSchema.KEY_ALERT_ROUTING_VERSION, "ACCOUNTING_EVENT_TRAIL_V1")
                .containsEntry(IntegrationFailureMetadataSchema.KEY_ALERT_ROUTE, "SEV1_PAGE");
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
                .containsEntry(IntegrationFailureMetadataSchema.KEY_FAILURE_CODE, "ACCOUNTING_EVENT_TRAIL_PERSISTENCE_FAILURE")
                .containsEntry(IntegrationFailureMetadataSchema.KEY_ERROR_CATEGORY, "PERSISTENCE")
                .containsEntry("errorType", "IllegalStateException")
                .containsEntry(IntegrationFailureMetadataSchema.KEY_ALERT_ROUTING_VERSION, "ACCOUNTING_EVENT_TRAIL_V1")
                .containsEntry(IntegrationFailureMetadataSchema.KEY_ALERT_ROUTE, "SEV2_URGENT")
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
                .containsEntry(IntegrationFailureMetadataSchema.KEY_FAILURE_CODE, "ACCOUNTING_EVENT_TRAIL_PERSISTENCE_FAILURE")
                .containsEntry(IntegrationFailureMetadataSchema.KEY_ERROR_CATEGORY, "VALIDATION")
                .containsEntry("errorType", "ApplicationException")
                .containsEntry(IntegrationFailureMetadataSchema.KEY_ALERT_ROUTING_VERSION, "ACCOUNTING_EVENT_TRAIL_V1")
                .containsEntry(IntegrationFailureMetadataSchema.KEY_ALERT_ROUTE, "SEV2_URGENT");
        verify(auditService, never()).logSuccess(eq(AuditEvent.JOURNAL_ENTRY_REVERSED), any());
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
                .containsEntry(IntegrationFailureMetadataSchema.KEY_FAILURE_CODE, "ACCOUNTING_EVENT_TRAIL_PERSISTENCE_FAILURE")
                .containsEntry(IntegrationFailureMetadataSchema.KEY_ERROR_CATEGORY, "DATA_INTEGRITY")
                .containsEntry("errorType", "ApplicationException")
                .containsEntry(IntegrationFailureMetadataSchema.KEY_ALERT_ROUTING_VERSION, "ACCOUNTING_EVENT_TRAIL_V1")
                .containsEntry(IntegrationFailureMetadataSchema.KEY_ALERT_ROUTE, "SEV1_PAGE");
        verify(auditService, never()).logSuccess(eq(AuditEvent.JOURNAL_ENTRY_REVERSED), any());
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
        verify(auditService, never()).logSuccess(eq(AuditEvent.JOURNAL_ENTRY_REVERSED), any());
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
        purchase.setTotalAmount(new BigDecimal("200.00"));
        purchase.setTaxAmount(BigDecimal.ZERO);
        purchase.setOutstandingAmount(new BigDecimal("200.00"));
        ReflectionTestUtils.setField(purchase, "id", 2L);

        Account inventory = new Account();
        inventory.setCompany(company);
        inventory.setCode("INV-AP");
        inventory.setType(AccountType.ASSET);
        ReflectionTestUtils.setField(inventory, "id", 23L);

        JournalEntry purchaseJournal = new JournalEntry();
        ReflectionTestUtils.setField(purchaseJournal, "id", 2002L);
        purchaseJournal.setSupplier(supplier);
        purchaseJournal.getLines().add(journalLine(
                purchaseJournal,
                inventory,
                "Purchase invoice",
                new BigDecimal("200.00"),
                BigDecimal.ZERO));
        purchaseJournal.getLines().add(journalLine(
                purchaseJournal,
                payable,
                "Purchase invoice",
                BigDecimal.ZERO,
                new BigDecimal("200.00")));
        purchase.setJournalEntry(purchaseJournal);

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
    void settleSupplierInvoices_rejectsGstPurchaseWhenInputTaxPostingMissing() {
        AccountingService service = spy(accountingService);

        Supplier supplier = new Supplier();
        supplier.setName("Supplier");
        ReflectionTestUtils.setField(supplier, "id", 1L);

        Account payable = new Account();
        payable.setCompany(company);
        payable.setCode("AP");
        payable.setType(AccountType.LIABILITY);
        ReflectionTestUtils.setField(payable, "id", 10L);
        supplier.setPayableAccount(payable);

        Account cash = new Account();
        cash.setCompany(company);
        cash.setCode("CASH");
        cash.setType(AccountType.ASSET);
        ReflectionTestUtils.setField(cash, "id", 20L);

        Account inventory = new Account();
        inventory.setCompany(company);
        inventory.setCode("INV");
        inventory.setType(AccountType.ASSET);
        ReflectionTestUtils.setField(inventory, "id", 21L);

        RawMaterialPurchase purchase = new RawMaterialPurchase();
        purchase.setCompany(company);
        purchase.setSupplier(supplier);
        purchase.setTotalAmount(new BigDecimal("118.00"));
        purchase.setTaxAmount(new BigDecimal("18.00"));
        purchase.setOutstandingAmount(new BigDecimal("118.00"));
        ReflectionTestUtils.setField(purchase, "id", 7001L);

        JournalEntry purchaseJournal = new JournalEntry();
        ReflectionTestUtils.setField(purchaseJournal, "id", 9701L);
        purchaseJournal.setSupplier(supplier);
        purchaseJournal.setReferenceNumber("RMP-GST-MISS-1");
        purchaseJournal.getLines().add(journalLine(
                purchaseJournal,
                inventory,
                "Purchase invoice GST missing",
                new BigDecimal("118.00"),
                BigDecimal.ZERO));
        purchaseJournal.getLines().add(journalLine(
                purchaseJournal,
                payable,
                "Purchase invoice GST missing",
                BigDecimal.ZERO,
                new BigDecimal("118.00")));
        purchase.setJournalEntry(purchaseJournal);

        when(supplierRepository.lockByCompanyAndId(eq(company), eq(1L))).thenReturn(Optional.of(supplier));
        when(companyEntityLookup.requireAccount(eq(company), eq(20L))).thenReturn(cash);
        when(rawMaterialPurchaseRepository.lockByCompanyAndId(eq(company), eq(7001L))).thenReturn(Optional.of(purchase));

        SupplierSettlementRequest request = new SupplierSettlementRequest(
                1L,
                20L,
                null,
                null,
                null,
                null,
                LocalDate.of(2024, 4, 9),
                "SUP-GST-MISS-1",
                "Supplier settlement GST mismatch",
                "IDEMP-SUP-GST-MISS-1",
                Boolean.FALSE,
                List.of(new SettlementAllocationRequest(
                        null,
                        7001L,
                        new BigDecimal("50.00"),
                        BigDecimal.ZERO,
                        BigDecimal.ZERO,
                        BigDecimal.ZERO,
                        null
                ))
        );

        assertThatThrownBy(() -> service.settleSupplierInvoices(request))
                .isInstanceOf(ApplicationException.class)
                .hasMessageContaining("GST purchase input tax posting mismatch");
        verify(service, never()).createJournalEntry(any(JournalEntryRequest.class));
    }

    @Test
    void settleSupplierInvoices_rejectsNonGstPurchaseWhenInputTaxPostingExists() {
        AccountingService service = spy(accountingService);

        Supplier supplier = new Supplier();
        supplier.setName("Supplier");
        ReflectionTestUtils.setField(supplier, "id", 1L);

        Account payable = new Account();
        payable.setCompany(company);
        payable.setCode("AP");
        payable.setType(AccountType.LIABILITY);
        ReflectionTestUtils.setField(payable, "id", 10L);
        supplier.setPayableAccount(payable);

        Account cash = new Account();
        cash.setCompany(company);
        cash.setCode("CASH");
        cash.setType(AccountType.ASSET);
        ReflectionTestUtils.setField(cash, "id", 20L);

        Account inventory = new Account();
        inventory.setCompany(company);
        inventory.setCode("INV");
        inventory.setType(AccountType.ASSET);
        ReflectionTestUtils.setField(inventory, "id", 21L);

        Account inputTax = new Account();
        inputTax.setCompany(company);
        inputTax.setCode("GST-IN");
        inputTax.setType(AccountType.ASSET);
        ReflectionTestUtils.setField(inputTax, "id", 22L);

        RawMaterialPurchase purchase = new RawMaterialPurchase();
        purchase.setCompany(company);
        purchase.setSupplier(supplier);
        purchase.setTotalAmount(new BigDecimal("100.00"));
        purchase.setTaxAmount(BigDecimal.ZERO);
        purchase.setOutstandingAmount(new BigDecimal("100.00"));
        ReflectionTestUtils.setField(purchase, "id", 7002L);

        JournalEntry purchaseJournal = new JournalEntry();
        ReflectionTestUtils.setField(purchaseJournal, "id", 9702L);
        purchaseJournal.setSupplier(supplier);
        purchaseJournal.setReferenceNumber("RMP-NONGST-MISS-1");
        purchaseJournal.getLines().add(journalLine(
                purchaseJournal,
                inventory,
                "Purchase invoice non-GST",
                new BigDecimal("82.00"),
                BigDecimal.ZERO));
        purchaseJournal.getLines().add(journalLine(
                purchaseJournal,
                inputTax,
                "Input tax for purchase invoice non-GST",
                new BigDecimal("18.00"),
                BigDecimal.ZERO));
        purchaseJournal.getLines().add(journalLine(
                purchaseJournal,
                payable,
                "Purchase invoice non-GST",
                BigDecimal.ZERO,
                new BigDecimal("100.00")));
        purchase.setJournalEntry(purchaseJournal);

        when(supplierRepository.lockByCompanyAndId(eq(company), eq(1L))).thenReturn(Optional.of(supplier));
        when(companyEntityLookup.requireAccount(eq(company), eq(20L))).thenReturn(cash);
        when(rawMaterialPurchaseRepository.lockByCompanyAndId(eq(company), eq(7002L))).thenReturn(Optional.of(purchase));

        SupplierSettlementRequest request = new SupplierSettlementRequest(
                1L,
                20L,
                null,
                null,
                null,
                null,
                LocalDate.of(2024, 4, 9),
                "SUP-NONGST-MISS-1",
                "Supplier settlement non-GST mismatch",
                "IDEMP-SUP-NONGST-MISS-1",
                Boolean.FALSE,
                List.of(new SettlementAllocationRequest(
                        null,
                        7002L,
                        new BigDecimal("50.00"),
                        BigDecimal.ZERO,
                        BigDecimal.ZERO,
                        BigDecimal.ZERO,
                        null
                ))
        );

        assertThatThrownBy(() -> service.settleSupplierInvoices(request))
                .isInstanceOf(ApplicationException.class)
                .hasMessageContaining("non-GST purchase has input tax posting");
        verify(service, never()).createJournalEntry(any(JournalEntryRequest.class));
    }

    @Test
    void settleDealerInvoices_rejectsOldSettlementDateBeforeMutation() {
        LocalDate today = LocalDate.of(2024, 4, 30);
        when(companyClock.today(company)).thenReturn(today);
        when(journalEntryRepository.findByCompanyAndReferenceNumber(eq(company), any()))
                .thenReturn(Optional.empty());

        Dealer dealer = new Dealer();
        dealer.setName("Closed-period dealer");
        ReflectionTestUtils.setField(dealer, "id", 1L);

        Account receivable = new Account();
        receivable.setCompany(company);
        receivable.setCode("AR-OLD");
        receivable.setType(AccountType.ASSET);
        ReflectionTestUtils.setField(receivable, "id", 10L);
        dealer.setReceivableAccount(receivable);

        Account cash = new Account();
        cash.setCompany(company);
        cash.setCode("CASH-OLD");
        cash.setType(AccountType.ASSET);
        ReflectionTestUtils.setField(cash, "id", 20L);

        com.bigbrightpaints.erp.modules.invoice.domain.Invoice invoice =
                new com.bigbrightpaints.erp.modules.invoice.domain.Invoice();
        invoice.setCompany(company);
        invoice.setDealer(dealer);
        invoice.setCurrency("INR");
        invoice.setOutstandingAmount(new BigDecimal("100.00"));
        invoice.setTotalAmount(new BigDecimal("100.00"));
        ReflectionTestUtils.setField(invoice, "id", 730L);

        when(dealerRepository.lockByCompanyAndId(eq(company), eq(1L))).thenReturn(Optional.of(dealer));
        when(companyEntityLookup.requireAccount(eq(company), eq(20L))).thenReturn(cash);
        when(invoiceRepository.lockByCompanyAndId(eq(company), eq(730L))).thenReturn(Optional.of(invoice));

        DealerSettlementRequest request = new DealerSettlementRequest(
                1L,
                20L,
                null,
                null,
                null,
                null,
                today.minusDays(31),
                "DR-OLD-1",
                "Old dealer settlement",
                "IDEMP-DR-OLD-1",
                Boolean.FALSE,
                List.of(new SettlementAllocationRequest(
                        730L,
                        null,
                        new BigDecimal("100.00"),
                        BigDecimal.ZERO,
                        BigDecimal.ZERO,
                        BigDecimal.ZERO,
                        null
                )),
                null
        );

        assertThatThrownBy(() -> accountingService.settleDealerInvoices(request))
                .isInstanceOf(ApplicationException.class)
                .hasMessageContaining("Entry date cannot be more than 30 days old");

        assertThat(invoice.getOutstandingAmount()).isEqualByComparingTo("100.00");
        verify(invoiceSettlementPolicy, never()).applySettlement(any(), any(), any());
        verify(settlementAllocationRepository, never()).saveAll(any());
        verify(invoiceRepository, never()).saveAll(any());
    }

    @Test
    void settleSupplierInvoices_rejectsOldSettlementDateBeforeMutation() {
        LocalDate today = LocalDate.of(2024, 4, 30);
        when(companyClock.today(company)).thenReturn(today);
        when(journalEntryRepository.findByCompanyAndReferenceNumber(eq(company), any()))
                .thenReturn(Optional.empty());

        Supplier supplier = new Supplier();
        supplier.setName("Closed-period supplier");
        ReflectionTestUtils.setField(supplier, "id", 1L);

        Account payable = new Account();
        payable.setCompany(company);
        payable.setCode("AP-OLD");
        payable.setType(AccountType.LIABILITY);
        ReflectionTestUtils.setField(payable, "id", 10L);
        supplier.setPayableAccount(payable);

        Account cash = new Account();
        cash.setCompany(company);
        cash.setCode("CASH-OLD");
        cash.setType(AccountType.ASSET);
        ReflectionTestUtils.setField(cash, "id", 20L);

        RawMaterialPurchase purchase = new RawMaterialPurchase();
        purchase.setCompany(company);
        purchase.setSupplier(supplier);
        purchase.setTotalAmount(new BigDecimal("100.00"));
        purchase.setTaxAmount(BigDecimal.ZERO);
        purchase.setOutstandingAmount(new BigDecimal("100.00"));
        ReflectionTestUtils.setField(purchase, "id", 740L);

        Account inventory = new Account();
        inventory.setCompany(company);
        inventory.setCode("INV-OLD");
        inventory.setType(AccountType.ASSET);
        ReflectionTestUtils.setField(inventory, "id", 30L);

        JournalEntry purchaseJournal = new JournalEntry();
        ReflectionTestUtils.setField(purchaseJournal, "id", 1740L);
        purchaseJournal.setSupplier(supplier);
        purchaseJournal.getLines().add(journalLine(
                purchaseJournal,
                inventory,
                "Purchase invoice old",
                new BigDecimal("100.00"),
                BigDecimal.ZERO));
        purchaseJournal.getLines().add(journalLine(
                purchaseJournal,
                payable,
                "Purchase invoice old",
                BigDecimal.ZERO,
                new BigDecimal("100.00")));
        purchase.setJournalEntry(purchaseJournal);

        when(supplierRepository.lockByCompanyAndId(eq(company), eq(1L))).thenReturn(Optional.of(supplier));
        when(companyEntityLookup.requireAccount(eq(company), eq(20L))).thenReturn(cash);
        when(rawMaterialPurchaseRepository.lockByCompanyAndId(eq(company), eq(740L))).thenReturn(Optional.of(purchase));

        SupplierSettlementRequest request = new SupplierSettlementRequest(
                1L,
                20L,
                null,
                null,
                null,
                null,
                today.minusDays(31),
                "SUP-OLD-1",
                "Old supplier settlement",
                "IDEMP-SUP-OLD-1",
                Boolean.FALSE,
                List.of(new SettlementAllocationRequest(
                        null,
                        740L,
                        new BigDecimal("100.00"),
                        BigDecimal.ZERO,
                        BigDecimal.ZERO,
                        BigDecimal.ZERO,
                        null
                ))
        );

        assertThatThrownBy(() -> accountingService.settleSupplierInvoices(request))
                .isInstanceOf(ApplicationException.class)
                .hasMessageContaining("Entry date cannot be more than 30 days old");

        assertThat(purchase.getOutstandingAmount()).isEqualByComparingTo("100.00");
        verify(settlementAllocationRepository, never()).saveAll(any());
        verify(rawMaterialPurchaseRepository, never()).saveAll(any());
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
    void recordDealerReceipt_nonLeaderReplayRepairsReferenceMapping() {
        Dealer dealer = new Dealer();
        dealer.setName("Replay Dealer");
        ReflectionTestUtils.setField(dealer, "id", 1L);

        Account receivable = new Account();
        receivable.setCompany(company);
        receivable.setCode("AR-REPLAY");
        receivable.setType(AccountType.ASSET);
        ReflectionTestUtils.setField(receivable, "id", 10L);
        dealer.setReceivableAccount(receivable);

        Account cash = new Account();
        cash.setCompany(company);
        cash.setCode("CASH-REPLAY");
        cash.setType(AccountType.ASSET);
        ReflectionTestUtils.setField(cash, "id", 20L);

        com.bigbrightpaints.erp.modules.invoice.domain.Invoice invoice =
                new com.bigbrightpaints.erp.modules.invoice.domain.Invoice();
        invoice.setCompany(company);
        invoice.setDealer(dealer);
        ReflectionTestUtils.setField(invoice, "id", 501L);

        JournalEntry existingEntry = new JournalEntry();
        ReflectionTestUtils.setField(existingEntry, "id", 901L);
        existingEntry.setDealer(dealer);
        existingEntry.setReferenceNumber("DR-REPLAY-1");
        existingEntry.setMemo("Dealer receipt replay");
        existingEntry.getLines().add(journalLine(existingEntry, cash, "Dealer receipt replay", new BigDecimal("100.00"), BigDecimal.ZERO));
        existingEntry.getLines().add(journalLine(existingEntry, receivable, "Dealer receipt replay", BigDecimal.ZERO, new BigDecimal("100.00")));

        com.bigbrightpaints.erp.modules.accounting.domain.PartnerSettlementAllocation existingRow =
                new com.bigbrightpaints.erp.modules.accounting.domain.PartnerSettlementAllocation();
        existingRow.setCompany(company);
        existingRow.setPartnerType(com.bigbrightpaints.erp.modules.accounting.domain.PartnerType.DEALER);
        existingRow.setDealer(dealer);
        existingRow.setInvoice(invoice);
        existingRow.setJournalEntry(existingEntry);
        existingRow.setSettlementDate(LocalDate.of(2024, 4, 9));
        existingRow.setAllocationAmount(new BigDecimal("100.00"));
        existingRow.setDiscountAmount(BigDecimal.ZERO);
        existingRow.setWriteOffAmount(BigDecimal.ZERO);
        existingRow.setFxDifferenceAmount(BigDecimal.ZERO);
        existingRow.setIdempotencyKey("IDEMP-DR-REPLAY");
        existingRow.setMemo(null);

        JournalReferenceMapping mapping = new JournalReferenceMapping();
        mapping.setCompany(company);
        mapping.setLegacyReference("idemp-dr-replay");
        mapping.setCanonicalReference("DR-REPLAY-1");
        mapping.setEntityType("DEALER_RECEIPT");
        mapping.setEntityId(null);

        when(dealerRepository.lockByCompanyAndId(eq(company), eq(1L))).thenReturn(Optional.of(dealer));
        when(companyEntityLookup.requireAccount(eq(company), eq(20L))).thenReturn(cash);
        when(journalReferenceMappingRepository.findAllByCompanyAndLegacyReferenceIgnoreCase(eq(company), eq("idemp-dr-replay")))
                .thenReturn(List.of(mapping));
        when(journalReferenceResolver.findExistingEntry(eq(company), eq("DR-REPLAY-1")))
                .thenReturn(Optional.of(existingEntry));
        when(settlementAllocationRepository.findByCompanyAndJournalEntryOrderByCreatedAtAsc(eq(company), eq(existingEntry)))
                .thenReturn(List.of(existingRow));

        SettlementAllocationRequest allocation = new SettlementAllocationRequest(
                501L,
                null,
                new BigDecimal("100.00"),
                BigDecimal.ZERO,
                BigDecimal.ZERO,
                BigDecimal.ZERO,
                null
        );

        DealerReceiptRequest request = new DealerReceiptRequest(
                1L,
                20L,
                new BigDecimal("100.00"),
                "DR-REPLAY-1",
                "Dealer receipt replay",
                "IDEMP-DR-REPLAY",
                List.of(allocation)
        );

        JournalEntryDto response = accountingService.recordDealerReceipt(request);

        assertThat(response.id()).isEqualTo(901L);
        verify(journalReferenceMappingRepository).save(mapping);
        assertThat(mapping.getEntityId()).isEqualTo(901L);
        assertThat(mapping.getEntityType()).isEqualTo("DEALER_RECEIPT");
        assertThat(mapping.getCanonicalReference()).isEqualTo("DR-REPLAY-1");
    }

    @Test
    void recordDealerReceiptSplit_nonLeaderReplayRepairsReferenceMapping() {
        Dealer dealer = new Dealer();
        dealer.setName("Replay Dealer");
        ReflectionTestUtils.setField(dealer, "id", 1L);

        Account receivable = new Account();
        receivable.setCompany(company);
        receivable.setCode("AR-SPLIT");
        receivable.setType(AccountType.ASSET);
        ReflectionTestUtils.setField(receivable, "id", 10L);
        dealer.setReceivableAccount(receivable);

        Account cash = new Account();
        cash.setCompany(company);
        cash.setCode("CASH-SPLIT");
        cash.setType(AccountType.ASSET);
        ReflectionTestUtils.setField(cash, "id", 20L);

        Account bank = new Account();
        bank.setCompany(company);
        bank.setCode("BANK-SPLIT");
        bank.setType(AccountType.ASSET);
        ReflectionTestUtils.setField(bank, "id", 21L);

        JournalEntry existingEntry = new JournalEntry();
        ReflectionTestUtils.setField(existingEntry, "id", 902L);
        existingEntry.setDealer(dealer);
        existingEntry.setReferenceNumber("DR-SPLIT-REPLAY-1");
        existingEntry.setMemo("Dealer split replay");
        existingEntry.getLines().add(journalLine(existingEntry, cash, "Dealer split replay", new BigDecimal("70.00"), BigDecimal.ZERO));
        existingEntry.getLines().add(journalLine(existingEntry, bank, "Dealer split replay", new BigDecimal("30.00"), BigDecimal.ZERO));
        existingEntry.getLines().add(journalLine(existingEntry, receivable, "Dealer split replay", BigDecimal.ZERO, new BigDecimal("100.00")));

        com.bigbrightpaints.erp.modules.accounting.domain.PartnerSettlementAllocation existingRow =
                new com.bigbrightpaints.erp.modules.accounting.domain.PartnerSettlementAllocation();
        existingRow.setCompany(company);
        existingRow.setPartnerType(com.bigbrightpaints.erp.modules.accounting.domain.PartnerType.DEALER);
        existingRow.setDealer(dealer);
        existingRow.setJournalEntry(existingEntry);
        existingRow.setSettlementDate(LocalDate.of(2024, 4, 9));
        existingRow.setAllocationAmount(new BigDecimal("100.00"));
        existingRow.setDiscountAmount(BigDecimal.ZERO);
        existingRow.setWriteOffAmount(BigDecimal.ZERO);
        existingRow.setFxDifferenceAmount(BigDecimal.ZERO);
        existingRow.setIdempotencyKey("IDEMP-DR-SPLIT-REPLAY");
        existingRow.setMemo(null);

        JournalReferenceMapping mapping = new JournalReferenceMapping();
        mapping.setCompany(company);
        mapping.setLegacyReference("idemp-dr-split-replay");
        mapping.setCanonicalReference("DR-SPLIT-REPLAY-1");
        mapping.setEntityType("DEALER_RECEIPT_SPLIT");
        mapping.setEntityId(null);

        when(dealerRepository.lockByCompanyAndId(eq(company), eq(1L))).thenReturn(Optional.of(dealer));
        when(companyEntityLookup.requireAccount(eq(company), eq(20L))).thenReturn(cash);
        when(companyEntityLookup.requireAccount(eq(company), eq(21L))).thenReturn(bank);
        when(journalReferenceMappingRepository.findAllByCompanyAndLegacyReferenceIgnoreCase(eq(company), eq("idemp-dr-split-replay")))
                .thenReturn(List.of(mapping));
        when(journalReferenceResolver.findExistingEntry(eq(company), eq("DR-SPLIT-REPLAY-1")))
                .thenReturn(Optional.of(existingEntry));
        when(settlementAllocationRepository.findByCompanyAndJournalEntryOrderByCreatedAtAsc(eq(company), eq(existingEntry)))
                .thenReturn(List.of(existingRow));

        DealerReceiptSplitRequest request = new DealerReceiptSplitRequest(
                1L,
                List.of(
                        new DealerReceiptSplitRequest.IncomingLine(20L, new BigDecimal("70.00")),
                        new DealerReceiptSplitRequest.IncomingLine(21L, new BigDecimal("30.00"))
                ),
                "DR-SPLIT-REPLAY-1",
                "Dealer split replay",
                "IDEMP-DR-SPLIT-REPLAY"
        );

        JournalEntryDto response = accountingService.recordDealerReceiptSplit(request);

        assertThat(response.id()).isEqualTo(902L);
        verify(journalReferenceMappingRepository).save(mapping);
        assertThat(mapping.getEntityId()).isEqualTo(902L);
        assertThat(mapping.getEntityType()).isEqualTo("DEALER_RECEIPT_SPLIT");
        assertThat(mapping.getCanonicalReference()).isEqualTo("DR-SPLIT-REPLAY-1");
    }

    @Test
    void recordDealerReceipt_nonLeaderReplayMissingAllocationIncludesPartnerDetails() {
        Dealer dealer = new Dealer();
        dealer.setName("Replay Dealer");
        ReflectionTestUtils.setField(dealer, "id", 1L);

        Account receivable = new Account();
        receivable.setCompany(company);
        receivable.setCode("AR-RACE-MISS");
        receivable.setType(AccountType.ASSET);
        ReflectionTestUtils.setField(receivable, "id", 10L);
        dealer.setReceivableAccount(receivable);

        Account cash = new Account();
        cash.setCompany(company);
        cash.setCode("CASH-RACE-MISS");
        cash.setType(AccountType.ASSET);
        ReflectionTestUtils.setField(cash, "id", 20L);

        JournalEntry existingEntry = new JournalEntry();
        ReflectionTestUtils.setField(existingEntry, "id", 921L);
        existingEntry.setDealer(dealer);
        existingEntry.setReferenceNumber("DR-RACE-MISS-1");
        existingEntry.setMemo("Dealer receipt replay race");

        JournalReferenceMapping mapping = new JournalReferenceMapping();
        mapping.setCompany(company);
        mapping.setLegacyReference("idemp-dr-race-miss");
        mapping.setCanonicalReference("DR-RACE-MISS-1");
        mapping.setEntityType("DEALER_RECEIPT");
        mapping.setEntityId(null);

        when(dealerRepository.lockByCompanyAndId(eq(company), eq(1L))).thenReturn(Optional.of(dealer));
        when(companyEntityLookup.requireAccount(eq(company), eq(20L))).thenReturn(cash);
        when(journalReferenceMappingRepository.findAllByCompanyAndLegacyReferenceIgnoreCase(eq(company), eq("idemp-dr-race-miss")))
                .thenReturn(List.of(mapping));
        when(journalReferenceResolver.findExistingEntry(eq(company), eq("DR-RACE-MISS-1")))
                .thenReturn(Optional.of(existingEntry));
        when(settlementAllocationRepository.findByCompanyAndJournalEntryOrderByCreatedAtAsc(eq(company), eq(existingEntry)))
                .thenReturn(List.of());
        when(settlementAllocationRepository.findByCompanyAndIdempotencyKeyIgnoreCaseOrderByCreatedAtAscIdAsc(
                eq(company), eq("IDEMP-DR-RACE-MISS")))
                .thenReturn(List.of());
        when(settlementAllocationRepository.findByCompanyAndIdempotencyKey(eq(company), eq("IDEMP-DR-RACE-MISS")))
                .thenReturn(List.of());

        SettlementAllocationRequest allocation = new SettlementAllocationRequest(
                501L,
                null,
                new BigDecimal("100.00"),
                BigDecimal.ZERO,
                BigDecimal.ZERO,
                BigDecimal.ZERO,
                null
        );

        DealerReceiptRequest request = new DealerReceiptRequest(
                1L,
                20L,
                new BigDecimal("100.00"),
                "DR-RACE-MISS-1",
                "Dealer receipt replay race",
                "IDEMP-DR-RACE-MISS",
                List.of(allocation)
        );

        assertThatThrownBy(() -> accountingService.recordDealerReceipt(request))
                .isInstanceOfSatisfying(ApplicationException.class, ex -> {
                    assertThat(ex.getErrorCode()).isEqualTo(ErrorCode.INTERNAL_CONCURRENCY_FAILURE);
                    assertPartnerReplayDetails(ex, "IDEMP-DR-RACE-MISS", "DEALER", 1L);
                })
                .hasMessageContaining("Dealer receipt idempotency key is reserved but allocation not found");
    }

    @Test
    void recordDealerReceiptSplit_nonLeaderReplayMissingAllocationIncludesPartnerDetails() {
        Dealer dealer = new Dealer();
        dealer.setName("Replay Dealer");
        ReflectionTestUtils.setField(dealer, "id", 1L);

        Account receivable = new Account();
        receivable.setCompany(company);
        receivable.setCode("AR-SPLIT-RACE-MISS");
        receivable.setType(AccountType.ASSET);
        ReflectionTestUtils.setField(receivable, "id", 10L);
        dealer.setReceivableAccount(receivable);

        Account cash = new Account();
        cash.setCompany(company);
        cash.setCode("CASH-SPLIT-RACE-MISS");
        cash.setType(AccountType.ASSET);
        ReflectionTestUtils.setField(cash, "id", 20L);

        Account bank = new Account();
        bank.setCompany(company);
        bank.setCode("BANK-SPLIT-RACE-MISS");
        bank.setType(AccountType.ASSET);
        ReflectionTestUtils.setField(bank, "id", 21L);

        JournalEntry existingEntry = new JournalEntry();
        ReflectionTestUtils.setField(existingEntry, "id", 922L);
        existingEntry.setDealer(dealer);
        existingEntry.setReferenceNumber("DR-SPLIT-RACE-MISS-1");
        existingEntry.setMemo("Dealer split replay race");

        JournalReferenceMapping mapping = new JournalReferenceMapping();
        mapping.setCompany(company);
        mapping.setLegacyReference("idemp-dr-split-race-miss");
        mapping.setCanonicalReference("DR-SPLIT-RACE-MISS-1");
        mapping.setEntityType("DEALER_RECEIPT_SPLIT");
        mapping.setEntityId(null);

        when(dealerRepository.lockByCompanyAndId(eq(company), eq(1L))).thenReturn(Optional.of(dealer));
        when(companyEntityLookup.requireAccount(eq(company), eq(20L))).thenReturn(cash);
        when(companyEntityLookup.requireAccount(eq(company), eq(21L))).thenReturn(bank);
        when(journalReferenceMappingRepository.findAllByCompanyAndLegacyReferenceIgnoreCase(eq(company), eq("idemp-dr-split-race-miss")))
                .thenReturn(List.of(mapping));
        when(journalReferenceResolver.findExistingEntry(eq(company), eq("DR-SPLIT-RACE-MISS-1")))
                .thenReturn(Optional.of(existingEntry));
        when(settlementAllocationRepository.findByCompanyAndJournalEntryOrderByCreatedAtAsc(eq(company), eq(existingEntry)))
                .thenReturn(List.of());
        when(settlementAllocationRepository.findByCompanyAndIdempotencyKeyIgnoreCaseOrderByCreatedAtAscIdAsc(
                eq(company), eq("IDEMP-DR-SPLIT-RACE-MISS")))
                .thenReturn(List.of());
        when(settlementAllocationRepository.findByCompanyAndIdempotencyKey(eq(company), eq("IDEMP-DR-SPLIT-RACE-MISS")))
                .thenReturn(List.of());

        DealerReceiptSplitRequest request = new DealerReceiptSplitRequest(
                1L,
                List.of(
                        new DealerReceiptSplitRequest.IncomingLine(20L, new BigDecimal("70.00")),
                        new DealerReceiptSplitRequest.IncomingLine(21L, new BigDecimal("30.00"))
                ),
                "DR-SPLIT-RACE-MISS-1",
                "Dealer split replay race",
                "IDEMP-DR-SPLIT-RACE-MISS"
        );

        assertThatThrownBy(() -> accountingService.recordDealerReceiptSplit(request))
                .isInstanceOfSatisfying(ApplicationException.class, ex -> {
                    assertThat(ex.getErrorCode()).isEqualTo(ErrorCode.INTERNAL_CONCURRENCY_FAILURE);
                    assertPartnerReplayDetails(ex, "IDEMP-DR-SPLIT-RACE-MISS", "DEALER", 1L);
                })
                .hasMessageContaining("Dealer receipt idempotency key is reserved but allocation not found");
    }

    @Test
    void recordSupplierPayment_nonLeaderReplayRepairsReferenceMapping() {
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

        RawMaterialPurchase purchase = new RawMaterialPurchase();
        purchase.setCompany(company);
        purchase.setSupplier(supplier);
        ReflectionTestUtils.setField(purchase, "id", 601L);

        JournalEntry existingEntry = new JournalEntry();
        ReflectionTestUtils.setField(existingEntry, "id", 903L);
        existingEntry.setSupplier(supplier);
        existingEntry.setReferenceNumber("SUP-PAY-REPLAY-1");
        existingEntry.setMemo("Supplier payment replay");
        existingEntry.getLines().add(journalLine(existingEntry, payable, "Supplier payment replay", new BigDecimal("100.00"), BigDecimal.ZERO));
        existingEntry.getLines().add(journalLine(existingEntry, cash, "Supplier payment replay", BigDecimal.ZERO, new BigDecimal("100.00")));

        com.bigbrightpaints.erp.modules.accounting.domain.PartnerSettlementAllocation existingRow =
                new com.bigbrightpaints.erp.modules.accounting.domain.PartnerSettlementAllocation();
        existingRow.setCompany(company);
        existingRow.setPartnerType(com.bigbrightpaints.erp.modules.accounting.domain.PartnerType.SUPPLIER);
        existingRow.setSupplier(supplier);
        existingRow.setPurchase(purchase);
        existingRow.setJournalEntry(existingEntry);
        existingRow.setSettlementDate(LocalDate.of(2024, 4, 9));
        existingRow.setAllocationAmount(new BigDecimal("100.00"));
        existingRow.setDiscountAmount(BigDecimal.ZERO);
        existingRow.setWriteOffAmount(BigDecimal.ZERO);
        existingRow.setFxDifferenceAmount(BigDecimal.ZERO);
        existingRow.setIdempotencyKey("IDEMP-SUP-PAY-REPLAY");
        existingRow.setMemo(null);

        JournalReferenceMapping mapping = new JournalReferenceMapping();
        mapping.setCompany(company);
        mapping.setLegacyReference("idemp-sup-pay-replay");
        mapping.setCanonicalReference("SUP-PAY-REPLAY-1");
        mapping.setEntityType("SUPPLIER_PAYMENT");
        mapping.setEntityId(null);

        when(supplierRepository.lockByCompanyAndId(eq(company), eq(1L))).thenReturn(Optional.of(supplier));
        when(companyEntityLookup.requireAccount(eq(company), eq(20L))).thenReturn(cash);
        when(journalReferenceMappingRepository.findAllByCompanyAndLegacyReferenceIgnoreCase(eq(company), eq("idemp-sup-pay-replay")))
                .thenReturn(List.of(mapping));
        when(journalReferenceResolver.findExistingEntry(eq(company), eq("SUP-PAY-REPLAY-1")))
                .thenReturn(Optional.of(existingEntry));
        when(settlementAllocationRepository.findByCompanyAndIdempotencyKeyIgnoreCaseOrderByCreatedAtAscIdAsc(eq(company), eq("IDEMP-SUP-PAY-REPLAY")))
                .thenReturn(List.of(existingRow));

        SettlementAllocationRequest allocation = new SettlementAllocationRequest(
                null,
                601L,
                new BigDecimal("100.00"),
                BigDecimal.ZERO,
                BigDecimal.ZERO,
                BigDecimal.ZERO,
                null
        );

        SupplierPaymentRequest request = new SupplierPaymentRequest(
                1L,
                20L,
                new BigDecimal("100.00"),
                "SUP-PAY-REPLAY-1",
                "Supplier payment replay",
                "IDEMP-SUP-PAY-REPLAY",
                List.of(allocation)
        );

        JournalEntryDto response = accountingService.recordSupplierPayment(request);

        assertThat(response.id()).isEqualTo(903L);
        verify(journalReferenceMappingRepository).save(mapping);
        assertThat(mapping.getEntityId()).isEqualTo(903L);
        assertThat(mapping.getEntityType()).isEqualTo("SUPPLIER_PAYMENT");
        assertThat(mapping.getCanonicalReference()).isEqualTo("SUP-PAY-REPLAY-1");
    }

    @Test
    void recordSupplierPayment_nonLeaderReplayMissingAllocationIncludesPartnerDetails() {
        Supplier supplier = new Supplier();
        supplier.setName("Replay Supplier");
        ReflectionTestUtils.setField(supplier, "id", 1L);

        Account payable = new Account();
        payable.setCompany(company);
        payable.setCode("AP-RACE-MISS");
        payable.setType(AccountType.LIABILITY);
        ReflectionTestUtils.setField(payable, "id", 10L);
        supplier.setPayableAccount(payable);

        Account cash = new Account();
        cash.setCompany(company);
        cash.setCode("CASH-RACE-MISS");
        cash.setType(AccountType.ASSET);
        ReflectionTestUtils.setField(cash, "id", 20L);

        JournalEntry existingEntry = new JournalEntry();
        ReflectionTestUtils.setField(existingEntry, "id", 923L);
        existingEntry.setSupplier(supplier);
        existingEntry.setReferenceNumber("SUP-PAY-RACE-MISS-1");
        existingEntry.setMemo("Supplier payment replay race");

        JournalReferenceMapping mapping = new JournalReferenceMapping();
        mapping.setCompany(company);
        mapping.setLegacyReference("idemp-sup-pay-race-miss");
        mapping.setCanonicalReference("SUP-PAY-RACE-MISS-1");
        mapping.setEntityType("SUPPLIER_PAYMENT");
        mapping.setEntityId(null);

        when(supplierRepository.lockByCompanyAndId(eq(company), eq(1L))).thenReturn(Optional.of(supplier));
        when(companyEntityLookup.requireAccount(eq(company), eq(20L))).thenReturn(cash);
        when(journalReferenceMappingRepository.findAllByCompanyAndLegacyReferenceIgnoreCase(eq(company), eq("idemp-sup-pay-race-miss")))
                .thenReturn(List.of(mapping));
        when(journalReferenceResolver.findExistingEntry(eq(company), eq("SUP-PAY-RACE-MISS-1")))
                .thenReturn(Optional.of(existingEntry));
        when(settlementAllocationRepository.findByCompanyAndIdempotencyKeyIgnoreCaseOrderByCreatedAtAscIdAsc(
                eq(company), eq("IDEMP-SUP-PAY-RACE-MISS")))
                .thenReturn(List.of());
        when(settlementAllocationRepository.findByCompanyAndIdempotencyKey(eq(company), eq("IDEMP-SUP-PAY-RACE-MISS")))
                .thenReturn(List.of());

        SettlementAllocationRequest allocation = new SettlementAllocationRequest(
                null,
                601L,
                new BigDecimal("100.00"),
                BigDecimal.ZERO,
                BigDecimal.ZERO,
                BigDecimal.ZERO,
                null
        );

        SupplierPaymentRequest request = new SupplierPaymentRequest(
                1L,
                20L,
                new BigDecimal("100.00"),
                "SUP-PAY-RACE-MISS-1",
                "Supplier payment replay race",
                "IDEMP-SUP-PAY-RACE-MISS",
                List.of(allocation)
        );

        assertThatThrownBy(() -> accountingService.recordSupplierPayment(request))
                .isInstanceOfSatisfying(ApplicationException.class, ex -> {
                    assertThat(ex.getErrorCode()).isEqualTo(ErrorCode.INTERNAL_CONCURRENCY_FAILURE);
                    assertPartnerReplayDetails(ex, "IDEMP-SUP-PAY-RACE-MISS", "SUPPLIER", 1L);
                })
                .hasMessageContaining("Supplier payment idempotency key is reserved but allocation not found");
    }

    @Test
    void recordDealerReceipt_replayRejectsMappingAllocationJournalMismatch() {
        Dealer dealer = new Dealer();
        dealer.setName("Mismatch Dealer");
        ReflectionTestUtils.setField(dealer, "id", 1L);

        Account receivable = new Account();
        receivable.setCompany(company);
        receivable.setCode("AR-MISMATCH");
        receivable.setType(AccountType.ASSET);
        ReflectionTestUtils.setField(receivable, "id", 10L);
        dealer.setReceivableAccount(receivable);

        Account cash = new Account();
        cash.setCompany(company);
        cash.setCode("CASH-MISMATCH");
        cash.setType(AccountType.ASSET);
        ReflectionTestUtils.setField(cash, "id", 20L);

        com.bigbrightpaints.erp.modules.invoice.domain.Invoice invoice =
                new com.bigbrightpaints.erp.modules.invoice.domain.Invoice();
        invoice.setCompany(company);
        invoice.setDealer(dealer);
        ReflectionTestUtils.setField(invoice, "id", 501L);

        JournalEntry mappingEntry = new JournalEntry();
        ReflectionTestUtils.setField(mappingEntry, "id", 904L);
        mappingEntry.setDealer(dealer);
        mappingEntry.setReferenceNumber("DR-MAP-MISMATCH-1");

        JournalEntry allocationEntry = new JournalEntry();
        ReflectionTestUtils.setField(allocationEntry, "id", 905L);
        allocationEntry.setDealer(dealer);
        allocationEntry.setReferenceNumber("DR-ALLOC-MISMATCH-1");

        com.bigbrightpaints.erp.modules.accounting.domain.PartnerSettlementAllocation existingRow =
                new com.bigbrightpaints.erp.modules.accounting.domain.PartnerSettlementAllocation();
        existingRow.setCompany(company);
        existingRow.setPartnerType(com.bigbrightpaints.erp.modules.accounting.domain.PartnerType.DEALER);
        existingRow.setDealer(dealer);
        existingRow.setInvoice(invoice);
        existingRow.setJournalEntry(allocationEntry);
        existingRow.setSettlementDate(LocalDate.of(2024, 4, 9));
        existingRow.setAllocationAmount(new BigDecimal("100.00"));
        existingRow.setDiscountAmount(BigDecimal.ZERO);
        existingRow.setWriteOffAmount(BigDecimal.ZERO);
        existingRow.setFxDifferenceAmount(BigDecimal.ZERO);
        existingRow.setIdempotencyKey("IDEMP-DR-MISMATCH");

        JournalReferenceMapping mapping = new JournalReferenceMapping();
        mapping.setCompany(company);
        mapping.setLegacyReference("idemp-dr-mismatch");
        mapping.setCanonicalReference("DR-MAP-MISMATCH-1");
        mapping.setEntityType("DEALER_RECEIPT");
        mapping.setEntityId(null);

        when(dealerRepository.lockByCompanyAndId(eq(company), eq(1L))).thenReturn(Optional.of(dealer));
        when(companyEntityLookup.requireAccount(eq(company), eq(20L))).thenReturn(cash);
        when(journalReferenceMappingRepository.findAllByCompanyAndLegacyReferenceIgnoreCase(eq(company), eq("idemp-dr-mismatch")))
                .thenReturn(List.of(mapping));
        when(journalReferenceResolver.findExistingEntry(eq(company), eq("DR-MAP-MISMATCH-1")))
                .thenReturn(Optional.of(mappingEntry));
        when(settlementAllocationRepository.findByCompanyAndJournalEntryOrderByCreatedAtAsc(eq(company), eq(mappingEntry)))
                .thenReturn(List.of());
        when(settlementAllocationRepository.findByCompanyAndIdempotencyKeyIgnoreCaseOrderByCreatedAtAscIdAsc(eq(company), eq("IDEMP-DR-MISMATCH")))
                .thenReturn(List.of(existingRow));

        DealerReceiptRequest request = new DealerReceiptRequest(
                1L,
                20L,
                new BigDecimal("100.00"),
                "DR-MAP-MISMATCH-1",
                "Dealer receipt replay",
                "IDEMP-DR-MISMATCH",
                List.of(new SettlementAllocationRequest(
                        501L,
                        null,
                        new BigDecimal("100.00"),
                        BigDecimal.ZERO,
                        BigDecimal.ZERO,
                        BigDecimal.ZERO,
                        null
                ))
        );

        assertThatThrownBy(() -> accountingService.recordDealerReceipt(request))
                .isInstanceOf(ApplicationException.class)
                .hasMessageContaining("different journal than settled allocations");
    }

    @Test
    void recordDealerReceipt_replayRejectsMappingAllocationJournalMismatchOnLeaderFastPath() {
        Dealer dealer = new Dealer();
        dealer.setName("Mismatch Dealer");
        ReflectionTestUtils.setField(dealer, "id", 1L);

        Account receivable = new Account();
        receivable.setCompany(company);
        receivable.setCode("AR-MISMATCH");
        receivable.setType(AccountType.ASSET);
        ReflectionTestUtils.setField(receivable, "id", 10L);
        dealer.setReceivableAccount(receivable);

        Account cash = new Account();
        cash.setCompany(company);
        cash.setCode("CASH-MISMATCH");
        cash.setType(AccountType.ASSET);
        ReflectionTestUtils.setField(cash, "id", 20L);

        com.bigbrightpaints.erp.modules.invoice.domain.Invoice invoice =
                new com.bigbrightpaints.erp.modules.invoice.domain.Invoice();
        invoice.setCompany(company);
        invoice.setDealer(dealer);
        ReflectionTestUtils.setField(invoice, "id", 501L);

        JournalEntry mappingEntry = new JournalEntry();
        ReflectionTestUtils.setField(mappingEntry, "id", 1904L);
        mappingEntry.setDealer(dealer);
        mappingEntry.setReferenceNumber("DR-MAP-MISMATCH-LEADER-1");

        JournalEntry allocationEntry = new JournalEntry();
        ReflectionTestUtils.setField(allocationEntry, "id", 1905L);
        allocationEntry.setDealer(dealer);
        allocationEntry.setReferenceNumber("DR-ALLOC-MISMATCH-LEADER-1");

        com.bigbrightpaints.erp.modules.accounting.domain.PartnerSettlementAllocation existingRow =
                new com.bigbrightpaints.erp.modules.accounting.domain.PartnerSettlementAllocation();
        existingRow.setCompany(company);
        existingRow.setPartnerType(com.bigbrightpaints.erp.modules.accounting.domain.PartnerType.DEALER);
        existingRow.setDealer(dealer);
        existingRow.setInvoice(invoice);
        existingRow.setJournalEntry(allocationEntry);
        existingRow.setSettlementDate(LocalDate.of(2024, 4, 9));
        existingRow.setAllocationAmount(new BigDecimal("100.00"));
        existingRow.setDiscountAmount(BigDecimal.ZERO);
        existingRow.setWriteOffAmount(BigDecimal.ZERO);
        existingRow.setFxDifferenceAmount(BigDecimal.ZERO);
        existingRow.setIdempotencyKey("IDEMP-DR-MISMATCH-LEADER");

        when(dealerRepository.lockByCompanyAndId(eq(company), eq(1L))).thenReturn(Optional.of(dealer));
        when(companyEntityLookup.requireAccount(eq(company), eq(20L))).thenReturn(cash);
        when(journalReferenceMappingRepository.findAllByCompanyAndLegacyReferenceIgnoreCase(
                eq(company), eq("idemp-dr-mismatch-leader")))
                .thenReturn(List.of());
        when(journalReferenceResolver.findExistingEntry(eq(company), eq("DR-MAP-MISMATCH-LEADER-1")))
                .thenReturn(Optional.of(mappingEntry));
        when(settlementAllocationRepository.findByCompanyAndIdempotencyKeyIgnoreCaseOrderByCreatedAtAscIdAsc(
                eq(company), eq("IDEMP-DR-MISMATCH-LEADER")))
                .thenReturn(List.of(existingRow));

        DealerReceiptRequest request = new DealerReceiptRequest(
                1L,
                20L,
                new BigDecimal("100.00"),
                "DR-MAP-MISMATCH-LEADER-1",
                "Dealer receipt replay",
                "IDEMP-DR-MISMATCH-LEADER",
                List.of(new SettlementAllocationRequest(
                        501L,
                        null,
                        new BigDecimal("100.00"),
                        BigDecimal.ZERO,
                        BigDecimal.ZERO,
                        BigDecimal.ZERO,
                        null
                ))
        );

        assertThatThrownBy(() -> accountingService.recordDealerReceipt(request))
                .isInstanceOf(ApplicationException.class)
                .hasMessageContaining("different journal than settled allocations");
    }

    @Test
    void recordDealerReceiptSplit_replayRejectsMappingAllocationJournalMismatch() {
        Dealer dealer = new Dealer();
        dealer.setName("Mismatch Dealer");
        ReflectionTestUtils.setField(dealer, "id", 1L);

        Account receivable = new Account();
        receivable.setCompany(company);
        receivable.setCode("AR-SPLIT-MISMATCH");
        receivable.setType(AccountType.ASSET);
        ReflectionTestUtils.setField(receivable, "id", 10L);
        dealer.setReceivableAccount(receivable);

        Account cash = new Account();
        cash.setCompany(company);
        cash.setCode("CASH-SPLIT-MISMATCH");
        cash.setType(AccountType.ASSET);
        ReflectionTestUtils.setField(cash, "id", 20L);

        JournalEntry mappingEntry = new JournalEntry();
        ReflectionTestUtils.setField(mappingEntry, "id", 906L);
        mappingEntry.setDealer(dealer);
        mappingEntry.setReferenceNumber("DR-SPLIT-MAP-MISMATCH-1");

        JournalEntry allocationEntry = new JournalEntry();
        ReflectionTestUtils.setField(allocationEntry, "id", 907L);
        allocationEntry.setDealer(dealer);
        allocationEntry.setReferenceNumber("DR-SPLIT-ALLOC-MISMATCH-1");

        com.bigbrightpaints.erp.modules.accounting.domain.PartnerSettlementAllocation existingRow =
                new com.bigbrightpaints.erp.modules.accounting.domain.PartnerSettlementAllocation();
        existingRow.setCompany(company);
        existingRow.setPartnerType(com.bigbrightpaints.erp.modules.accounting.domain.PartnerType.DEALER);
        existingRow.setDealer(dealer);
        existingRow.setJournalEntry(allocationEntry);
        existingRow.setSettlementDate(LocalDate.of(2024, 4, 9));
        existingRow.setAllocationAmount(new BigDecimal("100.00"));
        existingRow.setDiscountAmount(BigDecimal.ZERO);
        existingRow.setWriteOffAmount(BigDecimal.ZERO);
        existingRow.setFxDifferenceAmount(BigDecimal.ZERO);
        existingRow.setIdempotencyKey("IDEMP-DR-SPLIT-MISMATCH");

        JournalReferenceMapping mapping = new JournalReferenceMapping();
        mapping.setCompany(company);
        mapping.setLegacyReference("idemp-dr-split-mismatch");
        mapping.setCanonicalReference("DR-SPLIT-MAP-MISMATCH-1");
        mapping.setEntityType("DEALER_RECEIPT_SPLIT");
        mapping.setEntityId(null);

        when(dealerRepository.lockByCompanyAndId(eq(company), eq(1L))).thenReturn(Optional.of(dealer));
        when(companyEntityLookup.requireAccount(eq(company), eq(20L))).thenReturn(cash);
        when(journalReferenceMappingRepository.findAllByCompanyAndLegacyReferenceIgnoreCase(eq(company), eq("idemp-dr-split-mismatch")))
                .thenReturn(List.of(mapping));
        when(journalReferenceResolver.findExistingEntry(eq(company), eq("DR-SPLIT-MAP-MISMATCH-1")))
                .thenReturn(Optional.of(mappingEntry));
        when(settlementAllocationRepository.findByCompanyAndJournalEntryOrderByCreatedAtAsc(eq(company), eq(mappingEntry)))
                .thenReturn(List.of());
        when(settlementAllocationRepository.findByCompanyAndIdempotencyKeyIgnoreCaseOrderByCreatedAtAscIdAsc(eq(company), eq("IDEMP-DR-SPLIT-MISMATCH")))
                .thenReturn(List.of(existingRow));

        DealerReceiptSplitRequest request = new DealerReceiptSplitRequest(
                1L,
                List.of(new DealerReceiptSplitRequest.IncomingLine(20L, new BigDecimal("100.00"))),
                "DR-SPLIT-MAP-MISMATCH-1",
                "Dealer split replay",
                "IDEMP-DR-SPLIT-MISMATCH"
        );

        assertThatThrownBy(() -> accountingService.recordDealerReceiptSplit(request))
                .isInstanceOf(ApplicationException.class)
                .hasMessageContaining("different journal than settled allocations");
    }

    @Test
    void recordDealerReceiptSplit_replayRejectsMappingAllocationJournalMismatchOnLeaderFastPath() {
        Dealer dealer = new Dealer();
        dealer.setName("Mismatch Dealer");
        ReflectionTestUtils.setField(dealer, "id", 1L);

        Account receivable = new Account();
        receivable.setCompany(company);
        receivable.setCode("AR-SPLIT-MISMATCH");
        receivable.setType(AccountType.ASSET);
        ReflectionTestUtils.setField(receivable, "id", 10L);
        dealer.setReceivableAccount(receivable);

        Account cash = new Account();
        cash.setCompany(company);
        cash.setCode("CASH-SPLIT-MISMATCH");
        cash.setType(AccountType.ASSET);
        ReflectionTestUtils.setField(cash, "id", 20L);

        JournalEntry mappingEntry = new JournalEntry();
        ReflectionTestUtils.setField(mappingEntry, "id", 1906L);
        mappingEntry.setDealer(dealer);
        mappingEntry.setReferenceNumber("DR-SPLIT-MAP-MISMATCH-LEADER-1");

        JournalEntry allocationEntry = new JournalEntry();
        ReflectionTestUtils.setField(allocationEntry, "id", 1907L);
        allocationEntry.setDealer(dealer);
        allocationEntry.setReferenceNumber("DR-SPLIT-ALLOC-MISMATCH-LEADER-1");

        com.bigbrightpaints.erp.modules.accounting.domain.PartnerSettlementAllocation existingRow =
                new com.bigbrightpaints.erp.modules.accounting.domain.PartnerSettlementAllocation();
        existingRow.setCompany(company);
        existingRow.setPartnerType(com.bigbrightpaints.erp.modules.accounting.domain.PartnerType.DEALER);
        existingRow.setDealer(dealer);
        existingRow.setJournalEntry(allocationEntry);
        existingRow.setSettlementDate(LocalDate.of(2024, 4, 9));
        existingRow.setAllocationAmount(new BigDecimal("100.00"));
        existingRow.setDiscountAmount(BigDecimal.ZERO);
        existingRow.setWriteOffAmount(BigDecimal.ZERO);
        existingRow.setFxDifferenceAmount(BigDecimal.ZERO);
        existingRow.setIdempotencyKey("IDEMP-DR-SPLIT-MISMATCH-LEADER");

        when(dealerRepository.lockByCompanyAndId(eq(company), eq(1L))).thenReturn(Optional.of(dealer));
        when(companyEntityLookup.requireAccount(eq(company), eq(20L))).thenReturn(cash);
        when(journalReferenceMappingRepository.findAllByCompanyAndLegacyReferenceIgnoreCase(
                eq(company), eq("idemp-dr-split-mismatch-leader")))
                .thenReturn(List.of());
        when(journalReferenceResolver.findExistingEntry(eq(company), eq("DR-SPLIT-MAP-MISMATCH-LEADER-1")))
                .thenReturn(Optional.of(mappingEntry));
        when(settlementAllocationRepository.findByCompanyAndIdempotencyKeyIgnoreCaseOrderByCreatedAtAscIdAsc(
                eq(company), eq("IDEMP-DR-SPLIT-MISMATCH-LEADER")))
                .thenReturn(List.of(existingRow));

        DealerReceiptSplitRequest request = new DealerReceiptSplitRequest(
                1L,
                List.of(new DealerReceiptSplitRequest.IncomingLine(20L, new BigDecimal("100.00"))),
                "DR-SPLIT-MAP-MISMATCH-LEADER-1",
                "Dealer split replay",
                "IDEMP-DR-SPLIT-MISMATCH-LEADER"
        );

        assertThatThrownBy(() -> accountingService.recordDealerReceiptSplit(request))
                .isInstanceOf(ApplicationException.class)
                .hasMessageContaining("different journal than settled allocations");
    }

    @Test
    void recordSupplierPayment_replayRejectsMappingAllocationJournalMismatch() {
        Supplier supplier = new Supplier();
        supplier.setName("Mismatch Supplier");
        ReflectionTestUtils.setField(supplier, "id", 1L);

        Account payable = new Account();
        payable.setCompany(company);
        payable.setCode("AP-MISMATCH");
        payable.setType(AccountType.LIABILITY);
        ReflectionTestUtils.setField(payable, "id", 10L);
        supplier.setPayableAccount(payable);

        Account cash = new Account();
        cash.setCompany(company);
        cash.setCode("CASH-MISMATCH");
        cash.setType(AccountType.ASSET);
        ReflectionTestUtils.setField(cash, "id", 20L);

        RawMaterialPurchase purchase = new RawMaterialPurchase();
        purchase.setCompany(company);
        purchase.setSupplier(supplier);
        ReflectionTestUtils.setField(purchase, "id", 601L);

        JournalEntry mappingEntry = new JournalEntry();
        ReflectionTestUtils.setField(mappingEntry, "id", 908L);
        mappingEntry.setSupplier(supplier);
        mappingEntry.setReferenceNumber("SUP-PAY-MAP-MISMATCH-1");

        JournalEntry allocationEntry = new JournalEntry();
        ReflectionTestUtils.setField(allocationEntry, "id", 909L);
        allocationEntry.setSupplier(supplier);
        allocationEntry.setReferenceNumber("SUP-PAY-ALLOC-MISMATCH-1");

        com.bigbrightpaints.erp.modules.accounting.domain.PartnerSettlementAllocation existingRow =
                new com.bigbrightpaints.erp.modules.accounting.domain.PartnerSettlementAllocation();
        existingRow.setCompany(company);
        existingRow.setPartnerType(com.bigbrightpaints.erp.modules.accounting.domain.PartnerType.SUPPLIER);
        existingRow.setSupplier(supplier);
        existingRow.setPurchase(purchase);
        existingRow.setJournalEntry(allocationEntry);
        existingRow.setSettlementDate(LocalDate.of(2024, 4, 9));
        existingRow.setAllocationAmount(new BigDecimal("100.00"));
        existingRow.setDiscountAmount(BigDecimal.ZERO);
        existingRow.setWriteOffAmount(BigDecimal.ZERO);
        existingRow.setFxDifferenceAmount(BigDecimal.ZERO);
        existingRow.setIdempotencyKey("IDEMP-SUP-PAY-MISMATCH");

        JournalReferenceMapping mapping = new JournalReferenceMapping();
        mapping.setCompany(company);
        mapping.setLegacyReference("idemp-sup-pay-mismatch");
        mapping.setCanonicalReference("SUP-PAY-MAP-MISMATCH-1");
        mapping.setEntityType("SUPPLIER_PAYMENT");
        mapping.setEntityId(null);

        when(supplierRepository.lockByCompanyAndId(eq(company), eq(1L))).thenReturn(Optional.of(supplier));
        when(companyEntityLookup.requireAccount(eq(company), eq(20L))).thenReturn(cash);
        when(journalReferenceMappingRepository.findAllByCompanyAndLegacyReferenceIgnoreCase(eq(company), eq("idemp-sup-pay-mismatch")))
                .thenReturn(List.of(mapping));
        when(journalReferenceResolver.findExistingEntry(eq(company), eq("SUP-PAY-MAP-MISMATCH-1")))
                .thenReturn(Optional.of(mappingEntry));
        when(settlementAllocationRepository.findByCompanyAndIdempotencyKeyIgnoreCaseOrderByCreatedAtAscIdAsc(eq(company), eq("IDEMP-SUP-PAY-MISMATCH")))
                .thenReturn(List.of(existingRow));

        SupplierPaymentRequest request = new SupplierPaymentRequest(
                1L,
                20L,
                new BigDecimal("100.00"),
                "SUP-PAY-MAP-MISMATCH-1",
                "Supplier payment replay",
                "IDEMP-SUP-PAY-MISMATCH",
                List.of(new SettlementAllocationRequest(
                        null,
                        601L,
                        new BigDecimal("100.00"),
                        BigDecimal.ZERO,
                        BigDecimal.ZERO,
                        BigDecimal.ZERO,
                        null
                ))
        );

        assertThatThrownBy(() -> accountingService.recordSupplierPayment(request))
                .isInstanceOf(ApplicationException.class)
                .hasMessageContaining("different journal than settled allocations");
    }

    @Test
    void recordSupplierPayment_replayRejectsMappingAllocationJournalMismatchOnLeaderFastPath() {
        Supplier supplier = new Supplier();
        supplier.setName("Mismatch Supplier");
        ReflectionTestUtils.setField(supplier, "id", 1L);

        Account payable = new Account();
        payable.setCompany(company);
        payable.setCode("AP-MISMATCH");
        payable.setType(AccountType.LIABILITY);
        ReflectionTestUtils.setField(payable, "id", 10L);
        supplier.setPayableAccount(payable);

        Account cash = new Account();
        cash.setCompany(company);
        cash.setCode("CASH-MISMATCH");
        cash.setType(AccountType.ASSET);
        ReflectionTestUtils.setField(cash, "id", 20L);

        RawMaterialPurchase purchase = new RawMaterialPurchase();
        purchase.setCompany(company);
        purchase.setSupplier(supplier);
        ReflectionTestUtils.setField(purchase, "id", 601L);

        JournalEntry mappingEntry = new JournalEntry();
        ReflectionTestUtils.setField(mappingEntry, "id", 1908L);
        mappingEntry.setSupplier(supplier);
        mappingEntry.setReferenceNumber("SUP-PAY-MAP-MISMATCH-LEADER-1");

        JournalEntry allocationEntry = new JournalEntry();
        ReflectionTestUtils.setField(allocationEntry, "id", 1909L);
        allocationEntry.setSupplier(supplier);
        allocationEntry.setReferenceNumber("SUP-PAY-ALLOC-MISMATCH-LEADER-1");

        com.bigbrightpaints.erp.modules.accounting.domain.PartnerSettlementAllocation existingRow =
                new com.bigbrightpaints.erp.modules.accounting.domain.PartnerSettlementAllocation();
        existingRow.setCompany(company);
        existingRow.setPartnerType(com.bigbrightpaints.erp.modules.accounting.domain.PartnerType.SUPPLIER);
        existingRow.setSupplier(supplier);
        existingRow.setPurchase(purchase);
        existingRow.setJournalEntry(allocationEntry);
        existingRow.setSettlementDate(LocalDate.of(2024, 4, 9));
        existingRow.setAllocationAmount(new BigDecimal("100.00"));
        existingRow.setDiscountAmount(BigDecimal.ZERO);
        existingRow.setWriteOffAmount(BigDecimal.ZERO);
        existingRow.setFxDifferenceAmount(BigDecimal.ZERO);
        existingRow.setIdempotencyKey("IDEMP-SUP-PAY-MISMATCH-LEADER");

        when(supplierRepository.lockByCompanyAndId(eq(company), eq(1L))).thenReturn(Optional.of(supplier));
        when(companyEntityLookup.requireAccount(eq(company), eq(20L))).thenReturn(cash);
        when(journalReferenceMappingRepository.findAllByCompanyAndLegacyReferenceIgnoreCase(
                eq(company), eq("idemp-sup-pay-mismatch-leader")))
                .thenReturn(List.of());
        when(journalReferenceResolver.findExistingEntry(eq(company), eq("SUP-PAY-MAP-MISMATCH-LEADER-1")))
                .thenReturn(Optional.of(mappingEntry));
        when(settlementAllocationRepository.findByCompanyAndIdempotencyKeyIgnoreCaseOrderByCreatedAtAscIdAsc(
                eq(company), eq("IDEMP-SUP-PAY-MISMATCH-LEADER")))
                .thenReturn(List.of(existingRow));

        SupplierPaymentRequest request = new SupplierPaymentRequest(
                1L,
                20L,
                new BigDecimal("100.00"),
                "SUP-PAY-MAP-MISMATCH-LEADER-1",
                "Supplier payment replay",
                "IDEMP-SUP-PAY-MISMATCH-LEADER",
                List.of(new SettlementAllocationRequest(
                        null,
                        601L,
                        new BigDecimal("100.00"),
                        BigDecimal.ZERO,
                        BigDecimal.ZERO,
                        BigDecimal.ZERO,
                        null
                ))
        );

        assertThatThrownBy(() -> accountingService.recordSupplierPayment(request))
                .isInstanceOf(ApplicationException.class)
                .hasMessageContaining("different journal than settled allocations");
    }

    @Test
    void recordDealerReceipt_dataIntegrityFallbackRepairsReferenceMapping() {
        AccountingService service = spy(accountingService);

        Dealer dealer = new Dealer();
        dealer.setName("Race Dealer");
        ReflectionTestUtils.setField(dealer, "id", 1L);

        Account receivable = new Account();
        receivable.setCompany(company);
        receivable.setCode("AR-RACE");
        receivable.setType(AccountType.ASSET);
        ReflectionTestUtils.setField(receivable, "id", 10L);
        dealer.setReceivableAccount(receivable);

        Account cash = new Account();
        cash.setCompany(company);
        cash.setCode("CASH-RACE");
        cash.setType(AccountType.ASSET);
        ReflectionTestUtils.setField(cash, "id", 20L);

        com.bigbrightpaints.erp.modules.invoice.domain.Invoice invoice =
                new com.bigbrightpaints.erp.modules.invoice.domain.Invoice();
        invoice.setCompany(company);
        invoice.setDealer(dealer);
        invoice.setCurrency("INR");
        invoice.setOutstandingAmount(new BigDecimal("100.00"));
        invoice.setTotalAmount(new BigDecimal("100.00"));
        ReflectionTestUtils.setField(invoice, "id", 502L);

        JournalEntry createdEntry = new JournalEntry();
        ReflectionTestUtils.setField(createdEntry, "id", 910L);
        createdEntry.setDealer(dealer);
        createdEntry.setReferenceNumber("DR-RACE-NEW-1");
        createdEntry.setMemo("Dealer receipt race");
        createdEntry.getLines().add(journalLine(createdEntry, cash, "Dealer receipt race", new BigDecimal("100.00"), BigDecimal.ZERO));
        createdEntry.getLines().add(journalLine(createdEntry, receivable, "Dealer receipt race", BigDecimal.ZERO, new BigDecimal("100.00")));

        JournalEntry concurrentEntry = new JournalEntry();
        ReflectionTestUtils.setField(concurrentEntry, "id", 911L);
        concurrentEntry.setDealer(dealer);
        concurrentEntry.setReferenceNumber("DR-RACE-EXIST-1");
        concurrentEntry.setMemo("Dealer receipt race");
        concurrentEntry.getLines().add(journalLine(concurrentEntry, cash, "Dealer receipt race", new BigDecimal("100.00"), BigDecimal.ZERO));
        concurrentEntry.getLines().add(journalLine(concurrentEntry, receivable, "Dealer receipt race", BigDecimal.ZERO, new BigDecimal("100.00")));

        com.bigbrightpaints.erp.modules.accounting.domain.PartnerSettlementAllocation concurrentRow =
                new com.bigbrightpaints.erp.modules.accounting.domain.PartnerSettlementAllocation();
        concurrentRow.setCompany(company);
        concurrentRow.setPartnerType(com.bigbrightpaints.erp.modules.accounting.domain.PartnerType.DEALER);
        concurrentRow.setDealer(dealer);
        concurrentRow.setInvoice(invoice);
        concurrentRow.setJournalEntry(concurrentEntry);
        concurrentRow.setSettlementDate(LocalDate.of(2024, 4, 9));
        concurrentRow.setAllocationAmount(new BigDecimal("100.00"));
        concurrentRow.setDiscountAmount(BigDecimal.ZERO);
        concurrentRow.setWriteOffAmount(BigDecimal.ZERO);
        concurrentRow.setFxDifferenceAmount(BigDecimal.ZERO);
        concurrentRow.setIdempotencyKey("IDEMP-DR-RACE");

        JournalReferenceMapping mapping = new JournalReferenceMapping();
        mapping.setCompany(company);
        mapping.setLegacyReference("idemp-dr-race");
        mapping.setCanonicalReference("DR-RACE-NEW-1");
        mapping.setEntityType("DEALER_RECEIPT");
        mapping.setEntityId(null);

        AtomicInteger mappingLookups = new AtomicInteger(0);
        when(journalReferenceMappingRepository.findAllByCompanyAndLegacyReferenceIgnoreCase(eq(company), eq("idemp-dr-race")))
                .thenAnswer(invocation -> mappingLookups.getAndIncrement() < 2 ? List.of() : List.of(mapping));

        AtomicInteger allocationLookups = new AtomicInteger(0);
        when(settlementAllocationRepository.findByCompanyAndIdempotencyKeyIgnoreCaseOrderByCreatedAtAscIdAsc(eq(company), eq("IDEMP-DR-RACE")))
                .thenAnswer(invocation -> allocationLookups.getAndIncrement() < 2 ? List.of() : List.of(concurrentRow));

        when(dealerRepository.lockByCompanyAndId(eq(company), eq(1L))).thenReturn(Optional.of(dealer));
        when(companyEntityLookup.requireAccount(eq(company), eq(20L))).thenReturn(cash);
        when(invoiceRepository.lockByCompanyAndId(eq(company), eq(502L))).thenReturn(Optional.of(invoice));
        when(settlementAllocationRepository.findByCompanyAndJournalEntryOrderByCreatedAtAsc(eq(company), eq(createdEntry)))
                .thenReturn(List.of());
        doReturn(stubEntry(910L)).when(service).createJournalEntry(any(JournalEntryRequest.class));
        when(companyEntityLookup.requireJournalEntry(eq(company), eq(910L))).thenReturn(createdEntry);
        when(settlementAllocationRepository.saveAll(any())).thenThrow(new DataIntegrityViolationException("duplicate"));

        DealerReceiptRequest request = new DealerReceiptRequest(
                1L,
                20L,
                new BigDecimal("100.00"),
                "DR-RACE-NEW-1",
                "Dealer receipt race",
                "IDEMP-DR-RACE",
                List.of(new SettlementAllocationRequest(
                        502L,
                        null,
                        new BigDecimal("100.00"),
                        BigDecimal.ZERO,
                        BigDecimal.ZERO,
                        BigDecimal.ZERO,
                        null
                ))
        );

        JournalEntryDto response = service.recordDealerReceipt(request);

        assertThat(response.id()).isEqualTo(911L);
        verify(journalReferenceMappingRepository).save(mapping);
        assertThat(mapping.getEntityId()).isEqualTo(911L);
        assertThat(mapping.getEntityType()).isEqualTo("DEALER_RECEIPT");
        assertThat(mapping.getCanonicalReference()).isEqualTo("DR-RACE-EXIST-1");
        verify(invoiceSettlementPolicy, never()).applySettlement(any(), any(), any());
        verify(dealerLedgerService, never()).syncInvoiceLedger(any(), any());
    }

    @Test
    void recordDealerReceipt_dataIntegrityFallbackRejectsMappingAllocationJournalMismatch() {
        AccountingService service = spy(accountingService);

        Dealer dealer = new Dealer();
        dealer.setName("Race Dealer");
        ReflectionTestUtils.setField(dealer, "id", 1L);

        Account receivable = new Account();
        receivable.setCompany(company);
        receivable.setCode("AR-RACE");
        receivable.setType(AccountType.ASSET);
        ReflectionTestUtils.setField(receivable, "id", 10L);
        dealer.setReceivableAccount(receivable);

        Account cash = new Account();
        cash.setCompany(company);
        cash.setCode("CASH-RACE");
        cash.setType(AccountType.ASSET);
        ReflectionTestUtils.setField(cash, "id", 20L);

        com.bigbrightpaints.erp.modules.invoice.domain.Invoice invoice =
                new com.bigbrightpaints.erp.modules.invoice.domain.Invoice();
        invoice.setCompany(company);
        invoice.setDealer(dealer);
        invoice.setCurrency("INR");
        invoice.setOutstandingAmount(new BigDecimal("100.00"));
        invoice.setTotalAmount(new BigDecimal("100.00"));
        ReflectionTestUtils.setField(invoice, "id", 502L);

        JournalEntry createdEntry = new JournalEntry();
        ReflectionTestUtils.setField(createdEntry, "id", 1910L);
        createdEntry.setDealer(dealer);
        createdEntry.setReferenceNumber("DR-RACE-MISMATCH-NEW-1");
        createdEntry.setMemo("Dealer receipt race");
        createdEntry.getLines().add(journalLine(createdEntry, cash, "Dealer receipt race", new BigDecimal("100.00"), BigDecimal.ZERO));
        createdEntry.getLines().add(journalLine(createdEntry, receivable, "Dealer receipt race", BigDecimal.ZERO, new BigDecimal("100.00")));

        JournalEntry mappingEntry = new JournalEntry();
        ReflectionTestUtils.setField(mappingEntry, "id", 1911L);
        mappingEntry.setDealer(dealer);
        mappingEntry.setReferenceNumber("DR-RACE-MISMATCH-MAP-1");

        JournalEntry concurrentEntry = new JournalEntry();
        ReflectionTestUtils.setField(concurrentEntry, "id", 1912L);
        concurrentEntry.setDealer(dealer);
        concurrentEntry.setReferenceNumber("DR-RACE-MISMATCH-ALLOC-1");
        concurrentEntry.setMemo("Dealer receipt race");
        concurrentEntry.getLines().add(journalLine(concurrentEntry, cash, "Dealer receipt race", new BigDecimal("100.00"), BigDecimal.ZERO));
        concurrentEntry.getLines().add(journalLine(concurrentEntry, receivable, "Dealer receipt race", BigDecimal.ZERO, new BigDecimal("100.00")));

        com.bigbrightpaints.erp.modules.accounting.domain.PartnerSettlementAllocation concurrentRow =
                new com.bigbrightpaints.erp.modules.accounting.domain.PartnerSettlementAllocation();
        concurrentRow.setCompany(company);
        concurrentRow.setPartnerType(com.bigbrightpaints.erp.modules.accounting.domain.PartnerType.DEALER);
        concurrentRow.setDealer(dealer);
        concurrentRow.setInvoice(invoice);
        concurrentRow.setJournalEntry(concurrentEntry);
        concurrentRow.setSettlementDate(LocalDate.of(2024, 4, 9));
        concurrentRow.setAllocationAmount(new BigDecimal("100.00"));
        concurrentRow.setDiscountAmount(BigDecimal.ZERO);
        concurrentRow.setWriteOffAmount(BigDecimal.ZERO);
        concurrentRow.setFxDifferenceAmount(BigDecimal.ZERO);
        concurrentRow.setIdempotencyKey("IDEMP-DR-RACE-MISMATCH");

        AtomicInteger allocationLookups = new AtomicInteger(0);
        when(settlementAllocationRepository.findByCompanyAndIdempotencyKeyIgnoreCaseOrderByCreatedAtAscIdAsc(
                eq(company), eq("IDEMP-DR-RACE-MISMATCH")))
                .thenAnswer(invocation -> allocationLookups.getAndIncrement() < 2 ? List.of() : List.of(concurrentRow));

        when(dealerRepository.lockByCompanyAndId(eq(company), eq(1L))).thenReturn(Optional.of(dealer));
        when(companyEntityLookup.requireAccount(eq(company), eq(20L))).thenReturn(cash);
        when(invoiceRepository.lockByCompanyAndId(eq(company), eq(502L))).thenReturn(Optional.of(invoice));
        when(settlementAllocationRepository.findByCompanyAndJournalEntryOrderByCreatedAtAsc(eq(company), eq(createdEntry)))
                .thenReturn(List.of());
        when(journalReferenceResolver.findExistingEntry(eq(company), eq("DR-RACE-MISMATCH-NEW-1")))
                .thenReturn(Optional.of(mappingEntry));
        doReturn(stubEntry(1910L)).when(service).createJournalEntry(any(JournalEntryRequest.class));
        when(companyEntityLookup.requireJournalEntry(eq(company), eq(1910L))).thenReturn(createdEntry);
        when(settlementAllocationRepository.saveAll(any())).thenThrow(new DataIntegrityViolationException("duplicate"));

        DealerReceiptRequest request = new DealerReceiptRequest(
                1L,
                20L,
                new BigDecimal("100.00"),
                "DR-RACE-MISMATCH-NEW-1",
                "Dealer receipt race",
                "IDEMP-DR-RACE-MISMATCH",
                List.of(new SettlementAllocationRequest(
                        502L,
                        null,
                        new BigDecimal("100.00"),
                        BigDecimal.ZERO,
                        BigDecimal.ZERO,
                        BigDecimal.ZERO,
                        null
                ))
        );

        assertThatThrownBy(() -> service.recordDealerReceipt(request))
                .isInstanceOf(ApplicationException.class)
                .hasMessageContaining("different journal than settled allocations");
    }

    @Test
    void recordDealerReceiptSplit_dataIntegrityFallbackRepairsReferenceMapping() {
        AccountingService service = spy(accountingService);

        Dealer dealer = new Dealer();
        dealer.setName("Race Dealer");
        ReflectionTestUtils.setField(dealer, "id", 1L);

        Account receivable = new Account();
        receivable.setCompany(company);
        receivable.setCode("AR-SPLIT-RACE");
        receivable.setType(AccountType.ASSET);
        ReflectionTestUtils.setField(receivable, "id", 10L);
        dealer.setReceivableAccount(receivable);

        Account cash = new Account();
        cash.setCompany(company);
        cash.setCode("CASH-SPLIT-RACE");
        cash.setType(AccountType.ASSET);
        ReflectionTestUtils.setField(cash, "id", 20L);

        com.bigbrightpaints.erp.modules.invoice.domain.Invoice invoice =
                new com.bigbrightpaints.erp.modules.invoice.domain.Invoice();
        invoice.setCompany(company);
        invoice.setDealer(dealer);
        invoice.setCurrency("INR");
        invoice.setOutstandingAmount(new BigDecimal("100.00"));
        invoice.setTotalAmount(new BigDecimal("100.00"));
        ReflectionTestUtils.setField(invoice, "id", 602L);

        JournalEntry createdEntry = new JournalEntry();
        ReflectionTestUtils.setField(createdEntry, "id", 920L);
        createdEntry.setDealer(dealer);
        createdEntry.setReferenceNumber("DR-SPLIT-RACE-NEW-1");
        createdEntry.setMemo("Dealer split race");
        createdEntry.getLines().add(journalLine(createdEntry, cash, "Dealer split race", new BigDecimal("100.00"), BigDecimal.ZERO));
        createdEntry.getLines().add(journalLine(createdEntry, receivable, "Dealer split race", BigDecimal.ZERO, new BigDecimal("100.00")));

        JournalEntry concurrentEntry = new JournalEntry();
        ReflectionTestUtils.setField(concurrentEntry, "id", 921L);
        concurrentEntry.setDealer(dealer);
        concurrentEntry.setReferenceNumber("DR-SPLIT-RACE-EXIST-1");
        concurrentEntry.setMemo("Dealer split race");
        concurrentEntry.getLines().add(journalLine(concurrentEntry, cash, "Dealer split race", new BigDecimal("100.00"), BigDecimal.ZERO));
        concurrentEntry.getLines().add(journalLine(concurrentEntry, receivable, "Dealer split race", BigDecimal.ZERO, new BigDecimal("100.00")));

        com.bigbrightpaints.erp.modules.accounting.domain.PartnerSettlementAllocation concurrentRow =
                new com.bigbrightpaints.erp.modules.accounting.domain.PartnerSettlementAllocation();
        concurrentRow.setCompany(company);
        concurrentRow.setPartnerType(com.bigbrightpaints.erp.modules.accounting.domain.PartnerType.DEALER);
        concurrentRow.setDealer(dealer);
        concurrentRow.setInvoice(invoice);
        concurrentRow.setJournalEntry(concurrentEntry);
        concurrentRow.setSettlementDate(LocalDate.of(2024, 4, 9));
        concurrentRow.setAllocationAmount(new BigDecimal("100.00"));
        concurrentRow.setDiscountAmount(BigDecimal.ZERO);
        concurrentRow.setWriteOffAmount(BigDecimal.ZERO);
        concurrentRow.setFxDifferenceAmount(BigDecimal.ZERO);
        concurrentRow.setIdempotencyKey("IDEMP-DR-SPLIT-RACE");

        JournalReferenceMapping mapping = new JournalReferenceMapping();
        mapping.setCompany(company);
        mapping.setLegacyReference("idemp-dr-split-race");
        mapping.setCanonicalReference("DR-SPLIT-RACE-NEW-1");
        mapping.setEntityType("DEALER_RECEIPT_SPLIT");
        mapping.setEntityId(null);

        AtomicInteger mappingLookups = new AtomicInteger(0);
        when(journalReferenceMappingRepository.findAllByCompanyAndLegacyReferenceIgnoreCase(eq(company), eq("idemp-dr-split-race")))
                .thenAnswer(invocation -> mappingLookups.getAndIncrement() < 2 ? List.of() : List.of(mapping));

        AtomicInteger allocationLookups = new AtomicInteger(0);
        when(settlementAllocationRepository.findByCompanyAndIdempotencyKeyIgnoreCaseOrderByCreatedAtAscIdAsc(eq(company), eq("IDEMP-DR-SPLIT-RACE")))
                .thenAnswer(invocation -> allocationLookups.getAndIncrement() < 2 ? List.of() : List.of(concurrentRow));

        when(dealerRepository.lockByCompanyAndId(eq(company), eq(1L))).thenReturn(Optional.of(dealer));
        when(companyEntityLookup.requireAccount(eq(company), eq(20L))).thenReturn(cash);
        when(invoiceRepository.lockOpenInvoicesForSettlement(eq(company), eq(dealer))).thenReturn(List.of(invoice));
        when(settlementAllocationRepository.findByCompanyAndJournalEntryOrderByCreatedAtAsc(eq(company), eq(createdEntry)))
                .thenReturn(List.of());
        doReturn(stubEntry(920L)).when(service).createJournalEntry(any(JournalEntryRequest.class));
        when(companyEntityLookup.requireJournalEntry(eq(company), eq(920L))).thenReturn(createdEntry);
        when(settlementAllocationRepository.saveAll(any())).thenThrow(new DataIntegrityViolationException("duplicate"));

        DealerReceiptSplitRequest request = new DealerReceiptSplitRequest(
                1L,
                List.of(new DealerReceiptSplitRequest.IncomingLine(20L, new BigDecimal("100.00"))),
                "DR-SPLIT-RACE-NEW-1",
                "Dealer split race",
                "IDEMP-DR-SPLIT-RACE"
        );

        JournalEntryDto response = service.recordDealerReceiptSplit(request);

        assertThat(response.id()).isEqualTo(921L);
        verify(journalReferenceMappingRepository).save(mapping);
        assertThat(mapping.getEntityId()).isEqualTo(921L);
        assertThat(mapping.getEntityType()).isEqualTo("DEALER_RECEIPT_SPLIT");
        assertThat(mapping.getCanonicalReference()).isEqualTo("DR-SPLIT-RACE-EXIST-1");
        verify(invoiceSettlementPolicy, never()).applySettlement(any(), any(), any());
        verify(dealerLedgerService, never()).syncInvoiceLedger(any(), any());
    }

    @Test
    void recordSupplierPayment_dataIntegrityFallbackRepairsReferenceMapping() {
        AccountingService service = spy(accountingService);

        Supplier supplier = new Supplier();
        supplier.setName("Race Supplier");
        ReflectionTestUtils.setField(supplier, "id", 1L);

        Account payable = new Account();
        payable.setCompany(company);
        payable.setCode("AP-RACE");
        payable.setType(AccountType.LIABILITY);
        ReflectionTestUtils.setField(payable, "id", 10L);
        supplier.setPayableAccount(payable);

        Account cash = new Account();
        cash.setCompany(company);
        cash.setCode("CASH-RACE");
        cash.setType(AccountType.ASSET);
        ReflectionTestUtils.setField(cash, "id", 20L);

        RawMaterialPurchase purchase = new RawMaterialPurchase();
        purchase.setCompany(company);
        purchase.setSupplier(supplier);
        purchase.setOutstandingAmount(new BigDecimal("100.00"));
        ReflectionTestUtils.setField(purchase, "id", 603L);

        JournalEntry createdEntry = new JournalEntry();
        ReflectionTestUtils.setField(createdEntry, "id", 930L);
        createdEntry.setSupplier(supplier);
        createdEntry.setReferenceNumber("SUP-PAY-RACE-NEW-1");
        createdEntry.setMemo("Supplier payment race");
        createdEntry.getLines().add(journalLine(createdEntry, payable, "Supplier payment race", new BigDecimal("100.00"), BigDecimal.ZERO));
        createdEntry.getLines().add(journalLine(createdEntry, cash, "Supplier payment race", BigDecimal.ZERO, new BigDecimal("100.00")));

        JournalEntry concurrentEntry = new JournalEntry();
        ReflectionTestUtils.setField(concurrentEntry, "id", 931L);
        concurrentEntry.setSupplier(supplier);
        concurrentEntry.setReferenceNumber("SUP-PAY-RACE-EXIST-1");
        concurrentEntry.setMemo("Supplier payment race");
        concurrentEntry.getLines().add(journalLine(concurrentEntry, payable, "Supplier payment race", new BigDecimal("100.00"), BigDecimal.ZERO));
        concurrentEntry.getLines().add(journalLine(concurrentEntry, cash, "Supplier payment race", BigDecimal.ZERO, new BigDecimal("100.00")));

        com.bigbrightpaints.erp.modules.accounting.domain.PartnerSettlementAllocation concurrentRow =
                new com.bigbrightpaints.erp.modules.accounting.domain.PartnerSettlementAllocation();
        concurrentRow.setCompany(company);
        concurrentRow.setPartnerType(com.bigbrightpaints.erp.modules.accounting.domain.PartnerType.SUPPLIER);
        concurrentRow.setSupplier(supplier);
        concurrentRow.setPurchase(purchase);
        concurrentRow.setJournalEntry(concurrentEntry);
        concurrentRow.setSettlementDate(LocalDate.of(2024, 4, 9));
        concurrentRow.setAllocationAmount(new BigDecimal("100.00"));
        concurrentRow.setDiscountAmount(BigDecimal.ZERO);
        concurrentRow.setWriteOffAmount(BigDecimal.ZERO);
        concurrentRow.setFxDifferenceAmount(BigDecimal.ZERO);
        concurrentRow.setIdempotencyKey("IDEMP-SUP-PAY-RACE");

        JournalReferenceMapping mapping = new JournalReferenceMapping();
        mapping.setCompany(company);
        mapping.setLegacyReference("idemp-sup-pay-race");
        mapping.setCanonicalReference("SUP-PAY-RACE-NEW-1");
        mapping.setEntityType("SUPPLIER_PAYMENT");
        mapping.setEntityId(null);

        AtomicInteger mappingLookups = new AtomicInteger(0);
        when(journalReferenceMappingRepository.findAllByCompanyAndLegacyReferenceIgnoreCase(eq(company), eq("idemp-sup-pay-race")))
                .thenAnswer(invocation -> mappingLookups.getAndIncrement() < 2 ? List.of() : List.of(mapping));

        AtomicInteger allocationLookups = new AtomicInteger(0);
        when(settlementAllocationRepository.findByCompanyAndIdempotencyKeyIgnoreCaseOrderByCreatedAtAscIdAsc(eq(company), eq("IDEMP-SUP-PAY-RACE")))
                .thenAnswer(invocation -> allocationLookups.getAndIncrement() < 2 ? List.of() : List.of(concurrentRow));

        when(supplierRepository.lockByCompanyAndId(eq(company), eq(1L))).thenReturn(Optional.of(supplier));
        when(companyEntityLookup.requireAccount(eq(company), eq(20L))).thenReturn(cash);
        when(rawMaterialPurchaseRepository.lockByCompanyAndId(eq(company), eq(603L))).thenReturn(Optional.of(purchase));
        doReturn(stubEntry(930L)).when(service).createJournalEntry(any(JournalEntryRequest.class));
        when(companyEntityLookup.requireJournalEntry(eq(company), eq(930L))).thenReturn(createdEntry);
        when(settlementAllocationRepository.saveAll(any())).thenThrow(new DataIntegrityViolationException("duplicate"));

        SupplierPaymentRequest request = new SupplierPaymentRequest(
                1L,
                20L,
                new BigDecimal("100.00"),
                "SUP-PAY-RACE-NEW-1",
                "Supplier payment race",
                "IDEMP-SUP-PAY-RACE",
                List.of(new SettlementAllocationRequest(
                        null,
                        603L,
                        new BigDecimal("100.00"),
                        BigDecimal.ZERO,
                        BigDecimal.ZERO,
                        BigDecimal.ZERO,
                        null
                ))
        );

        JournalEntryDto response = service.recordSupplierPayment(request);

        assertThat(response.id()).isEqualTo(931L);
        verify(journalReferenceMappingRepository).save(mapping);
        assertThat(mapping.getEntityId()).isEqualTo(931L);
        assertThat(mapping.getEntityType()).isEqualTo("SUPPLIER_PAYMENT");
        assertThat(mapping.getCanonicalReference()).isEqualTo("SUP-PAY-RACE-EXIST-1");
        assertThat(purchase.getOutstandingAmount()).isEqualByComparingTo("100.00");
        verify(rawMaterialPurchaseRepository, never()).saveAll(any());
    }

    @Test
    void settleDealerInvoices_dataIntegrityFallbackRepairsReferenceMapping() {
        AccountingService service = spy(accountingService);

        Dealer dealer = new Dealer();
        dealer.setName("Dealer");
        ReflectionTestUtils.setField(dealer, "id", 1L);

        Account receivable = new Account();
        receivable.setCompany(company);
        receivable.setCode("AR-SETTLE-RACE");
        receivable.setType(AccountType.ASSET);
        ReflectionTestUtils.setField(receivable, "id", 10L);
        dealer.setReceivableAccount(receivable);

        Account cash = new Account();
        cash.setCompany(company);
        cash.setCode("CASH-SETTLE-RACE");
        cash.setType(AccountType.ASSET);
        ReflectionTestUtils.setField(cash, "id", 20L);

        com.bigbrightpaints.erp.modules.invoice.domain.Invoice invoice =
                new com.bigbrightpaints.erp.modules.invoice.domain.Invoice();
        invoice.setCompany(company);
        invoice.setDealer(dealer);
        invoice.setCurrency("INR");
        invoice.setOutstandingAmount(new BigDecimal("100.00"));
        invoice.setTotalAmount(new BigDecimal("100.00"));
        ReflectionTestUtils.setField(invoice, "id", 702L);

        JournalEntry createdEntry = new JournalEntry();
        ReflectionTestUtils.setField(createdEntry, "id", 950L);
        createdEntry.setDealer(dealer);
        createdEntry.setReferenceNumber("DR-SETTLE-RACE-NEW-1");
        createdEntry.setMemo("Dealer settlement race");
        createdEntry.getLines().add(journalLine(createdEntry, cash, "Dealer settlement race", new BigDecimal("100.00"), BigDecimal.ZERO));
        createdEntry.getLines().add(journalLine(createdEntry, receivable, "Dealer settlement race", BigDecimal.ZERO, new BigDecimal("100.00")));

        JournalEntry concurrentEntry = new JournalEntry();
        ReflectionTestUtils.setField(concurrentEntry, "id", 951L);
        concurrentEntry.setDealer(dealer);
        concurrentEntry.setReferenceNumber("DR-SETTLE-RACE-EXIST-1");
        concurrentEntry.setMemo("Dealer settlement race");
        concurrentEntry.getLines().add(journalLine(concurrentEntry, cash, "Dealer settlement race", new BigDecimal("100.00"), BigDecimal.ZERO));
        concurrentEntry.getLines().add(journalLine(concurrentEntry, receivable, "Dealer settlement race", BigDecimal.ZERO, new BigDecimal("100.00")));

        com.bigbrightpaints.erp.modules.accounting.domain.PartnerSettlementAllocation concurrentRow =
                new com.bigbrightpaints.erp.modules.accounting.domain.PartnerSettlementAllocation();
        concurrentRow.setCompany(company);
        concurrentRow.setPartnerType(com.bigbrightpaints.erp.modules.accounting.domain.PartnerType.DEALER);
        concurrentRow.setDealer(dealer);
        concurrentRow.setInvoice(invoice);
        concurrentRow.setJournalEntry(concurrentEntry);
        concurrentRow.setSettlementDate(LocalDate.of(2024, 4, 9));
        concurrentRow.setAllocationAmount(new BigDecimal("100.00"));
        concurrentRow.setDiscountAmount(BigDecimal.ZERO);
        concurrentRow.setWriteOffAmount(BigDecimal.ZERO);
        concurrentRow.setFxDifferenceAmount(BigDecimal.ZERO);
        concurrentRow.setIdempotencyKey("IDEMP-DR-SETTLE-RACE");

        JournalReferenceMapping mapping = new JournalReferenceMapping();
        mapping.setCompany(company);
        mapping.setLegacyReference("idemp-dr-settle-race");
        mapping.setCanonicalReference("DR-SETTLE-RACE-NEW-1");
        mapping.setEntityType("DEALER_SETTLEMENT");
        mapping.setEntityId(null);

        AtomicInteger mappingLookups = new AtomicInteger(0);
        when(journalReferenceMappingRepository.findAllByCompanyAndLegacyReferenceIgnoreCase(eq(company), eq("idemp-dr-settle-race")))
                .thenAnswer(invocation -> mappingLookups.getAndIncrement() < 2 ? List.of() : List.of(mapping));

        AtomicInteger allocationLookups = new AtomicInteger(0);
        when(settlementAllocationRepository.findByCompanyAndIdempotencyKeyIgnoreCaseOrderByCreatedAtAscIdAsc(eq(company), eq("IDEMP-DR-SETTLE-RACE")))
                .thenAnswer(invocation -> allocationLookups.getAndIncrement() < 2 ? List.of() : List.of(concurrentRow));

        when(dealerRepository.lockByCompanyAndId(eq(company), eq(1L))).thenReturn(Optional.of(dealer));
        when(companyEntityLookup.requireAccount(eq(company), eq(20L))).thenReturn(cash);
        when(journalReferenceMappingRepository.reserveReferenceMapping(
                eq(company.getId()),
                eq("idemp-dr-settle-race"),
                eq("DR-SETTLE-RACE-NEW-1"),
                eq("DEALER_SETTLEMENT"),
                any()))
                .thenReturn(1);
        when(invoiceRepository.lockByCompanyAndId(eq(company), eq(702L))).thenReturn(Optional.of(invoice));
        doReturn(stubEntry(950L)).when(service).createJournalEntry(any(JournalEntryRequest.class));
        when(companyEntityLookup.requireJournalEntry(eq(company), eq(950L))).thenReturn(createdEntry);
        when(settlementAllocationRepository.saveAll(any())).thenThrow(new DataIntegrityViolationException("duplicate"));

        DealerSettlementRequest request = new DealerSettlementRequest(
                1L,
                20L,
                null,
                null,
                null,
                null,
                LocalDate.of(2024, 4, 9),
                "DR-SETTLE-RACE-NEW-1",
                "Dealer settlement race",
                "IDEMP-DR-SETTLE-RACE",
                Boolean.FALSE,
                List.of(new SettlementAllocationRequest(
                        702L,
                        null,
                        new BigDecimal("100.00"),
                        BigDecimal.ZERO,
                        BigDecimal.ZERO,
                        BigDecimal.ZERO,
                        null
                )),
                null
        );

        PartnerSettlementResponse response = service.settleDealerInvoices(request);

        assertThat(response.journalEntry()).isNotNull();
        assertThat(response.journalEntry().id()).isEqualTo(951L);
        ArgumentCaptor<JournalReferenceMapping> mappingCaptor = ArgumentCaptor.forClass(JournalReferenceMapping.class);
        verify(journalReferenceMappingRepository, atLeastOnce()).save(mappingCaptor.capture());
        JournalReferenceMapping savedMapping = mappingCaptor.getAllValues().getLast();
        assertThat(savedMapping.getLegacyReference()).isEqualTo("idemp-dr-settle-race");
        assertThat(savedMapping.getEntityId()).isEqualTo(951L);
        assertThat(savedMapping.getEntityType()).isEqualTo("DEALER_SETTLEMENT");
        assertThat(savedMapping.getCanonicalReference()).isEqualTo("DR-SETTLE-RACE-EXIST-1");
        verify(invoiceSettlementPolicy, never()).applySettlement(any(), any(), any());
    }

    @Test
    void settleSupplierInvoices_dataIntegrityFallbackRepairsReferenceMapping() {
        AccountingService service = spy(accountingService);

        Supplier supplier = new Supplier();
        supplier.setName("Supplier");
        ReflectionTestUtils.setField(supplier, "id", 1L);

        Account payable = new Account();
        payable.setCompany(company);
        payable.setCode("AP-SETTLE-RACE");
        payable.setType(AccountType.LIABILITY);
        ReflectionTestUtils.setField(payable, "id", 10L);
        supplier.setPayableAccount(payable);

        Account cash = new Account();
        cash.setCompany(company);
        cash.setCode("CASH-SETTLE-RACE");
        cash.setType(AccountType.ASSET);
        ReflectionTestUtils.setField(cash, "id", 20L);

        RawMaterialPurchase purchase = new RawMaterialPurchase();
        purchase.setCompany(company);
        purchase.setSupplier(supplier);
        purchase.setTotalAmount(new BigDecimal("100.00"));
        purchase.setTaxAmount(BigDecimal.ZERO);
        purchase.setOutstandingAmount(new BigDecimal("100.00"));
        ReflectionTestUtils.setField(purchase, "id", 704L);

        Account inventory = new Account();
        inventory.setCompany(company);
        inventory.setCode("INV-SETTLE-RACE");
        inventory.setType(AccountType.ASSET);
        ReflectionTestUtils.setField(inventory, "id", 22L);

        JournalEntry purchaseJournal = new JournalEntry();
        ReflectionTestUtils.setField(purchaseJournal, "id", 959L);
        purchaseJournal.setSupplier(supplier);
        purchaseJournal.setReferenceNumber("RMP-SETTLE-RACE-1");
        purchaseJournal.getLines().add(journalLine(
                purchaseJournal,
                inventory,
                "Purchase invoice race",
                new BigDecimal("100.00"),
                BigDecimal.ZERO));
        purchaseJournal.getLines().add(journalLine(
                purchaseJournal,
                payable,
                "Purchase invoice race",
                BigDecimal.ZERO,
                new BigDecimal("100.00")));
        purchase.setJournalEntry(purchaseJournal);

        JournalEntry createdEntry = new JournalEntry();
        ReflectionTestUtils.setField(createdEntry, "id", 960L);
        createdEntry.setSupplier(supplier);
        createdEntry.setReferenceNumber("SUP-SETTLE-RACE-NEW-1");
        createdEntry.setMemo("Supplier settlement race");
        createdEntry.getLines().add(journalLine(createdEntry, payable, "Supplier settlement race", new BigDecimal("100.00"), BigDecimal.ZERO));
        createdEntry.getLines().add(journalLine(createdEntry, cash, "Supplier settlement race", BigDecimal.ZERO, new BigDecimal("100.00")));

        JournalEntry concurrentEntry = new JournalEntry();
        ReflectionTestUtils.setField(concurrentEntry, "id", 961L);
        concurrentEntry.setSupplier(supplier);
        concurrentEntry.setReferenceNumber("SUP-SETTLE-RACE-NEW-1");
        concurrentEntry.setMemo("Supplier settlement race");
        concurrentEntry.getLines().add(journalLine(concurrentEntry, payable, "Supplier settlement race", new BigDecimal("100.00"), BigDecimal.ZERO));
        concurrentEntry.getLines().add(journalLine(concurrentEntry, cash, "Supplier settlement race", BigDecimal.ZERO, new BigDecimal("100.00")));

        com.bigbrightpaints.erp.modules.accounting.domain.PartnerSettlementAllocation concurrentRow =
                new com.bigbrightpaints.erp.modules.accounting.domain.PartnerSettlementAllocation();
        concurrentRow.setCompany(company);
        concurrentRow.setPartnerType(com.bigbrightpaints.erp.modules.accounting.domain.PartnerType.SUPPLIER);
        concurrentRow.setSupplier(supplier);
        concurrentRow.setPurchase(purchase);
        concurrentRow.setJournalEntry(concurrentEntry);
        concurrentRow.setSettlementDate(LocalDate.of(2024, 4, 9));
        concurrentRow.setAllocationAmount(new BigDecimal("100.00"));
        concurrentRow.setDiscountAmount(BigDecimal.ZERO);
        concurrentRow.setWriteOffAmount(BigDecimal.ZERO);
        concurrentRow.setFxDifferenceAmount(BigDecimal.ZERO);
        concurrentRow.setIdempotencyKey("IDEMP-AP-SETTLE-RACE");
        concurrentRow.setMemo(null);

        JournalReferenceMapping mapping = new JournalReferenceMapping();
        mapping.setCompany(company);
        mapping.setLegacyReference("idemp-ap-settle-race");
        mapping.setCanonicalReference("SUP-SETTLE-RACE-NEW-1");
        mapping.setEntityType("SUPPLIER_SETTLEMENT");
        mapping.setEntityId(null);

        AtomicInteger mappingLookups = new AtomicInteger(0);
        when(journalReferenceMappingRepository.findAllByCompanyAndLegacyReferenceIgnoreCase(eq(company), eq("idemp-ap-settle-race")))
                .thenAnswer(invocation -> mappingLookups.getAndIncrement() < 2 ? List.of() : List.of(mapping));

        AtomicInteger allocationLookups = new AtomicInteger(0);
        when(settlementAllocationRepository.findByCompanyAndIdempotencyKeyIgnoreCaseOrderByCreatedAtAscIdAsc(eq(company), eq("IDEMP-AP-SETTLE-RACE")))
                .thenAnswer(invocation -> allocationLookups.getAndIncrement() < 2 ? List.of() : List.of(concurrentRow));

        when(supplierRepository.lockByCompanyAndId(eq(company), eq(1L))).thenReturn(Optional.of(supplier));
        when(companyEntityLookup.requireAccount(eq(company), eq(20L))).thenReturn(cash);
        when(journalReferenceMappingRepository.reserveReferenceMapping(
                eq(company.getId()),
                eq("idemp-ap-settle-race"),
                eq("SUP-SETTLE-RACE-NEW-1"),
                eq("SUPPLIER_SETTLEMENT"),
                any()))
                .thenReturn(1);
        when(rawMaterialPurchaseRepository.lockByCompanyAndId(eq(company), eq(704L))).thenReturn(Optional.of(purchase));
        doReturn(stubEntry(960L)).when(service).createJournalEntry(any(JournalEntryRequest.class));
        when(companyEntityLookup.requireJournalEntry(eq(company), eq(960L))).thenReturn(createdEntry);
        when(settlementAllocationRepository.saveAll(any())).thenThrow(new DataIntegrityViolationException("duplicate"));

        SettlementAllocationRequest allocation = new SettlementAllocationRequest(
                null,
                704L,
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
                "SUP-SETTLE-RACE-NEW-1",
                "Supplier settlement race",
                "IDEMP-AP-SETTLE-RACE",
                Boolean.FALSE,
                List.of(allocation)
        );

        PartnerSettlementResponse response = service.settleSupplierInvoices(request);

        assertThat(response.journalEntry()).isNotNull();
        assertThat(response.journalEntry().id()).isEqualTo(961L);
        verify(journalReferenceMappingRepository, times(2)).save(mapping);
        assertThat(mapping.getEntityId()).isEqualTo(961L);
        assertThat(mapping.getEntityType()).isEqualTo("SUPPLIER_SETTLEMENT");
        assertThat(mapping.getCanonicalReference()).isEqualTo("SUP-SETTLE-RACE-NEW-1");
        assertThat(purchase.getOutstandingAmount()).isEqualByComparingTo("100.00");
        verify(rawMaterialPurchaseRepository, never()).saveAll(any());
    }

    @Test
    void settleDealerInvoices_replayRejectsMappingAllocationJournalMismatch() {
        Dealer dealer = new Dealer();
        dealer.setName("Mismatch Dealer");
        ReflectionTestUtils.setField(dealer, "id", 1L);

        Account receivable = new Account();
        receivable.setCompany(company);
        receivable.setCode("AR-SETTLE-MISMATCH");
        receivable.setType(AccountType.ASSET);
        ReflectionTestUtils.setField(receivable, "id", 10L);
        dealer.setReceivableAccount(receivable);

        Account cash = new Account();
        cash.setCompany(company);
        cash.setCode("CASH-SETTLE-MISMATCH");
        cash.setType(AccountType.ASSET);
        ReflectionTestUtils.setField(cash, "id", 20L);

        com.bigbrightpaints.erp.modules.invoice.domain.Invoice invoice =
                new com.bigbrightpaints.erp.modules.invoice.domain.Invoice();
        invoice.setCompany(company);
        invoice.setDealer(dealer);
        ReflectionTestUtils.setField(invoice, "id", 701L);

        JournalEntry mappingEntry = new JournalEntry();
        ReflectionTestUtils.setField(mappingEntry, "id", 940L);
        mappingEntry.setDealer(dealer);
        mappingEntry.setReferenceNumber("DR-SETTLE-MAP-MISMATCH-1");

        JournalEntry allocationEntry = new JournalEntry();
        ReflectionTestUtils.setField(allocationEntry, "id", 941L);
        allocationEntry.setDealer(dealer);
        allocationEntry.setReferenceNumber("DR-SETTLE-ALLOC-MISMATCH-1");

        com.bigbrightpaints.erp.modules.accounting.domain.PartnerSettlementAllocation existingRow =
                new com.bigbrightpaints.erp.modules.accounting.domain.PartnerSettlementAllocation();
        existingRow.setCompany(company);
        existingRow.setPartnerType(com.bigbrightpaints.erp.modules.accounting.domain.PartnerType.DEALER);
        existingRow.setDealer(dealer);
        existingRow.setInvoice(invoice);
        existingRow.setJournalEntry(allocationEntry);
        existingRow.setSettlementDate(LocalDate.of(2024, 4, 9));
        existingRow.setAllocationAmount(new BigDecimal("100.00"));
        existingRow.setDiscountAmount(BigDecimal.ZERO);
        existingRow.setWriteOffAmount(BigDecimal.ZERO);
        existingRow.setFxDifferenceAmount(BigDecimal.ZERO);
        existingRow.setIdempotencyKey("IDEMP-DR-SETTLE-MISMATCH");

        JournalReferenceMapping mapping = new JournalReferenceMapping();
        mapping.setCompany(company);
        mapping.setLegacyReference("idemp-dr-settle-mismatch");
        mapping.setCanonicalReference("DR-SETTLE-MAP-MISMATCH-1");
        mapping.setEntityType("DEALER_SETTLEMENT");
        mapping.setEntityId(null);

        when(dealerRepository.lockByCompanyAndId(eq(company), eq(1L))).thenReturn(Optional.of(dealer));
        when(companyEntityLookup.requireAccount(eq(company), eq(20L))).thenReturn(cash);
        when(journalReferenceMappingRepository.findAllByCompanyAndLegacyReferenceIgnoreCase(eq(company), eq("idemp-dr-settle-mismatch")))
                .thenReturn(List.of(mapping));
        when(journalReferenceResolver.findExistingEntry(eq(company), eq("DR-SETTLE-MAP-MISMATCH-1")))
                .thenReturn(Optional.of(mappingEntry));
        when(settlementAllocationRepository.findByCompanyAndIdempotencyKeyIgnoreCaseOrderByCreatedAtAscIdAsc(eq(company), eq("IDEMP-DR-SETTLE-MISMATCH")))
                .thenReturn(List.of(existingRow));

        DealerSettlementRequest request = new DealerSettlementRequest(
                1L,
                20L,
                null,
                null,
                null,
                null,
                LocalDate.of(2024, 4, 9),
                "DR-SETTLE-MAP-MISMATCH-1",
                "Dealer settlement replay",
                "IDEMP-DR-SETTLE-MISMATCH",
                Boolean.FALSE,
                List.of(new SettlementAllocationRequest(
                        701L,
                        null,
                        new BigDecimal("100.00"),
                        BigDecimal.ZERO,
                        BigDecimal.ZERO,
                        BigDecimal.ZERO,
                        null
                )),
                null
        );

        assertThatThrownBy(() -> accountingService.settleDealerInvoices(request))
                .isInstanceOf(ApplicationException.class)
                .hasMessageContaining("different journal than settled allocations");
    }

    @Test
    void settleDealerInvoices_replayRejectsMappingAllocationJournalMismatchOnLeaderFastPath() {
        Dealer dealer = new Dealer();
        dealer.setName("Mismatch Dealer");
        ReflectionTestUtils.setField(dealer, "id", 1L);

        Account receivable = new Account();
        receivable.setCompany(company);
        receivable.setCode("AR-SETTLE-MISMATCH");
        receivable.setType(AccountType.ASSET);
        ReflectionTestUtils.setField(receivable, "id", 10L);
        dealer.setReceivableAccount(receivable);

        Account cash = new Account();
        cash.setCompany(company);
        cash.setCode("CASH-SETTLE-MISMATCH");
        cash.setType(AccountType.ASSET);
        ReflectionTestUtils.setField(cash, "id", 20L);

        com.bigbrightpaints.erp.modules.invoice.domain.Invoice invoice =
                new com.bigbrightpaints.erp.modules.invoice.domain.Invoice();
        invoice.setCompany(company);
        invoice.setDealer(dealer);
        ReflectionTestUtils.setField(invoice, "id", 701L);

        JournalEntry mappingEntry = new JournalEntry();
        ReflectionTestUtils.setField(mappingEntry, "id", 1940L);
        mappingEntry.setDealer(dealer);
        mappingEntry.setReferenceNumber("DR-SETTLE-MAP-MISMATCH-LEADER-1");

        JournalEntry allocationEntry = new JournalEntry();
        ReflectionTestUtils.setField(allocationEntry, "id", 1941L);
        allocationEntry.setDealer(dealer);
        allocationEntry.setReferenceNumber("DR-SETTLE-ALLOC-MISMATCH-LEADER-1");

        com.bigbrightpaints.erp.modules.accounting.domain.PartnerSettlementAllocation existingRow =
                new com.bigbrightpaints.erp.modules.accounting.domain.PartnerSettlementAllocation();
        existingRow.setCompany(company);
        existingRow.setPartnerType(com.bigbrightpaints.erp.modules.accounting.domain.PartnerType.DEALER);
        existingRow.setDealer(dealer);
        existingRow.setInvoice(invoice);
        existingRow.setJournalEntry(allocationEntry);
        existingRow.setSettlementDate(LocalDate.of(2024, 4, 9));
        existingRow.setAllocationAmount(new BigDecimal("100.00"));
        existingRow.setDiscountAmount(BigDecimal.ZERO);
        existingRow.setWriteOffAmount(BigDecimal.ZERO);
        existingRow.setFxDifferenceAmount(BigDecimal.ZERO);
        existingRow.setIdempotencyKey("IDEMP-DR-SETTLE-MISMATCH-LEADER");

        when(dealerRepository.lockByCompanyAndId(eq(company), eq(1L))).thenReturn(Optional.of(dealer));
        when(companyEntityLookup.requireAccount(eq(company), eq(20L))).thenReturn(cash);
        when(journalReferenceMappingRepository.findAllByCompanyAndLegacyReferenceIgnoreCase(
                eq(company), eq("idemp-dr-settle-mismatch-leader")))
                .thenReturn(List.of());
        when(journalReferenceResolver.findExistingEntry(eq(company), eq("DR-SETTLE-MAP-MISMATCH-LEADER-1")))
                .thenReturn(Optional.of(mappingEntry));
        when(settlementAllocationRepository.findByCompanyAndIdempotencyKeyIgnoreCaseOrderByCreatedAtAscIdAsc(
                eq(company), eq("IDEMP-DR-SETTLE-MISMATCH-LEADER")))
                .thenReturn(List.of(existingRow));

        DealerSettlementRequest request = new DealerSettlementRequest(
                1L,
                20L,
                null,
                null,
                null,
                null,
                LocalDate.of(2024, 4, 9),
                "DR-SETTLE-MAP-MISMATCH-LEADER-1",
                "Dealer settlement replay",
                "IDEMP-DR-SETTLE-MISMATCH-LEADER",
                Boolean.FALSE,
                List.of(new SettlementAllocationRequest(
                        701L,
                        null,
                        new BigDecimal("100.00"),
                        BigDecimal.ZERO,
                        BigDecimal.ZERO,
                        BigDecimal.ZERO,
                        null
                )),
                null
        );

        assertThatThrownBy(() -> accountingService.settleDealerInvoices(request))
                .isInstanceOf(ApplicationException.class)
                .hasMessageContaining("different journal than settled allocations");
    }

    @Test
    void settleDealerInvoices_nonLeaderReplayMissingAllocationsIncludesPartnerDetails() {
        Dealer dealer = new Dealer();
        dealer.setName("Dealer");
        ReflectionTestUtils.setField(dealer, "id", 1L);

        Account receivable = new Account();
        receivable.setCompany(company);
        receivable.setCode("AR-RACE");
        receivable.setType(AccountType.ASSET);
        ReflectionTestUtils.setField(receivable, "id", 10L);
        dealer.setReceivableAccount(receivable);

        Account cash = new Account();
        cash.setCompany(company);
        cash.setCode("CASH-RACE");
        cash.setType(AccountType.ASSET);
        ReflectionTestUtils.setField(cash, "id", 20L);

        var invoice = new com.bigbrightpaints.erp.modules.invoice.domain.Invoice();
        invoice.setCompany(company);
        invoice.setDealer(dealer);
        invoice.setCurrency("INR");
        invoice.setOutstandingAmount(new BigDecimal("1000.00"));
        invoice.setTotalAmount(new BigDecimal("1000.00"));
        ReflectionTestUtils.setField(invoice, "id", 5L);

        JournalEntry existingEntry = new JournalEntry();
        ReflectionTestUtils.setField(existingEntry, "id", 991L);
        existingEntry.setDealer(dealer);
        existingEntry.setReferenceNumber("DR-SETTLE-RACE-1");

        JournalReferenceMapping mapping = new JournalReferenceMapping();
        mapping.setCompany(company);
        mapping.setLegacyReference("idemp-dr-race-miss");
        mapping.setCanonicalReference("DR-SETTLE-RACE-1");
        mapping.setEntityType("DEALER_SETTLEMENT");
        mapping.setEntityId(null);

        when(dealerRepository.lockByCompanyAndId(eq(company), eq(1L))).thenReturn(Optional.of(dealer));
        when(companyEntityLookup.requireAccount(eq(company), eq(20L))).thenReturn(cash);
        when(journalReferenceMappingRepository.findAllByCompanyAndLegacyReferenceIgnoreCase(eq(company), eq("idemp-dr-race-miss")))
                .thenReturn(List.of(mapping));
        when(settlementAllocationRepository.findByCompanyAndIdempotencyKey(eq(company), eq("IDEMP-DR-RACE-MISS")))
                .thenReturn(List.of());
        when(journalReferenceResolver.findExistingEntry(eq(company), eq("DR-SETTLE-RACE-1")))
                .thenReturn(Optional.of(existingEntry));
        when(settlementAllocationRepository.findByCompanyAndIdempotencyKeyIgnoreCaseOrderByCreatedAtAscIdAsc(
                eq(company), eq("IDEMP-DR-RACE-MISS")))
                .thenReturn(List.of());

        DealerSettlementRequest request = new DealerSettlementRequest(
                1L,
                20L,
                null,
                null,
                null,
                null,
                LocalDate.of(2024, 4, 9),
                "DR-SETTLE-RACE-1",
                "Dealer settlement replay race",
                "IDEMP-DR-RACE-MISS",
                Boolean.FALSE,
                List.of(new SettlementAllocationRequest(
                        5L,
                        null,
                        new BigDecimal("100.00"),
                        BigDecimal.ZERO,
                        BigDecimal.ZERO,
                        BigDecimal.ZERO,
                        null
                )),
                null
        );

        assertThatThrownBy(() -> accountingService.settleDealerInvoices(request))
                .isInstanceOfSatisfying(ApplicationException.class, ex -> {
                    assertThat(ex.getErrorCode()).isEqualTo(ErrorCode.INTERNAL_CONCURRENCY_FAILURE);
                    assertPartnerReplayDetails(ex, "IDEMP-DR-RACE-MISS", "DEALER", 1L);
                })
                .hasMessageContaining("Dealer settlement idempotency key is reserved but allocation not found");
    }

    @Test
    void settleDealerInvoices_replayPartnerMismatchIncludesPartnerDetails() {
        Dealer dealer = new Dealer();
        dealer.setName("Dealer");
        ReflectionTestUtils.setField(dealer, "id", 1L);

        Dealer otherDealer = new Dealer();
        otherDealer.setName("Other Dealer");
        ReflectionTestUtils.setField(otherDealer, "id", 2L);

        Account receivable = new Account();
        receivable.setCompany(company);
        receivable.setCode("AR-RACE");
        receivable.setType(AccountType.ASSET);
        ReflectionTestUtils.setField(receivable, "id", 10L);
        dealer.setReceivableAccount(receivable);

        Account cash = new Account();
        cash.setCompany(company);
        cash.setCode("CASH-RACE");
        cash.setType(AccountType.ASSET);
        ReflectionTestUtils.setField(cash, "id", 20L);

        var invoice = new com.bigbrightpaints.erp.modules.invoice.domain.Invoice();
        invoice.setCompany(company);
        invoice.setDealer(dealer);
        invoice.setCurrency("INR");
        invoice.setOutstandingAmount(new BigDecimal("1000.00"));
        invoice.setTotalAmount(new BigDecimal("1000.00"));
        ReflectionTestUtils.setField(invoice, "id", 5L);

        JournalEntry existingEntry = new JournalEntry();
        ReflectionTestUtils.setField(existingEntry, "id", 993L);
        existingEntry.setDealer(otherDealer);
        existingEntry.setReferenceNumber("DR-SETTLE-RACE-PARTNER-1");

        com.bigbrightpaints.erp.modules.accounting.domain.PartnerSettlementAllocation existingRow =
                new com.bigbrightpaints.erp.modules.accounting.domain.PartnerSettlementAllocation();
        existingRow.setCompany(company);
        existingRow.setPartnerType(com.bigbrightpaints.erp.modules.accounting.domain.PartnerType.DEALER);
        existingRow.setDealer(dealer);
        existingRow.setInvoice(invoice);
        existingRow.setJournalEntry(existingEntry);
        existingRow.setSettlementDate(LocalDate.of(2024, 4, 9));
        existingRow.setAllocationAmount(new BigDecimal("100.00"));
        existingRow.setDiscountAmount(BigDecimal.ZERO);
        existingRow.setWriteOffAmount(BigDecimal.ZERO);
        existingRow.setFxDifferenceAmount(BigDecimal.ZERO);
        existingRow.setIdempotencyKey("IDEMP-DR-RACE-PARTNER");

        JournalReferenceMapping mapping = new JournalReferenceMapping();
        mapping.setCompany(company);
        mapping.setLegacyReference("idemp-dr-race-partner");
        mapping.setCanonicalReference("DR-SETTLE-RACE-PARTNER-1");
        mapping.setEntityType("DEALER_SETTLEMENT");
        mapping.setEntityId(null);

        when(dealerRepository.lockByCompanyAndId(eq(company), eq(1L))).thenReturn(Optional.of(dealer));
        when(companyEntityLookup.requireAccount(eq(company), eq(20L))).thenReturn(cash);
        when(journalReferenceMappingRepository.findAllByCompanyAndLegacyReferenceIgnoreCase(eq(company), eq("idemp-dr-race-partner")))
                .thenReturn(List.of(mapping));
        when(journalReferenceResolver.findExistingEntry(eq(company), eq("DR-SETTLE-RACE-PARTNER-1")))
                .thenReturn(Optional.of(existingEntry));
        when(settlementAllocationRepository.findByCompanyAndIdempotencyKeyIgnoreCaseOrderByCreatedAtAscIdAsc(
                eq(company), eq("IDEMP-DR-RACE-PARTNER")))
                .thenReturn(List.of(existingRow));

        DealerSettlementRequest request = new DealerSettlementRequest(
                1L,
                20L,
                null,
                null,
                null,
                null,
                LocalDate.of(2024, 4, 9),
                "DR-SETTLE-RACE-PARTNER-1",
                "Dealer settlement replay partner mismatch",
                "IDEMP-DR-RACE-PARTNER",
                Boolean.FALSE,
                List.of(new SettlementAllocationRequest(
                        5L,
                        null,
                        new BigDecimal("100.00"),
                        BigDecimal.ZERO,
                        BigDecimal.ZERO,
                        BigDecimal.ZERO,
                        null
                )),
                null
        );

        assertThatThrownBy(() -> accountingService.settleDealerInvoices(request))
                .isInstanceOfSatisfying(ApplicationException.class, ex -> {
                    assertThat(ex.getErrorCode()).isEqualTo(ErrorCode.CONCURRENCY_CONFLICT);
                    assertPartnerReplayDetails(ex, "IDEMP-DR-RACE-PARTNER", "DEALER", 1L);
                })
                .hasMessageContaining("another dealer");
    }

    @Test
    void settleDealerInvoices_replayPayloadMismatchWinsOverNetCashPrevalidation() {
        Dealer dealer = new Dealer();
        dealer.setName("Replay Dealer");
        ReflectionTestUtils.setField(dealer, "id", 1L);

        Account receivable = new Account();
        receivable.setCompany(company);
        receivable.setCode("AR-SETTLE-REPLAY");
        receivable.setType(AccountType.ASSET);
        ReflectionTestUtils.setField(receivable, "id", 10L);
        dealer.setReceivableAccount(receivable);

        Account cash = new Account();
        cash.setCompany(company);
        cash.setCode("CASH-SETTLE-REPLAY");
        cash.setType(AccountType.ASSET);
        ReflectionTestUtils.setField(cash, "id", 20L);

        Account discount = new Account();
        discount.setCompany(company);
        discount.setCode("DISC-SETTLE-REPLAY");
        ReflectionTestUtils.setField(discount, "id", 21L);

        com.bigbrightpaints.erp.modules.invoice.domain.Invoice invoiceA =
                new com.bigbrightpaints.erp.modules.invoice.domain.Invoice();
        invoiceA.setCompany(company);
        invoiceA.setDealer(dealer);
        ReflectionTestUtils.setField(invoiceA, "id", 701L);

        com.bigbrightpaints.erp.modules.invoice.domain.Invoice invoiceB =
                new com.bigbrightpaints.erp.modules.invoice.domain.Invoice();
        invoiceB.setCompany(company);
        invoiceB.setDealer(dealer);
        ReflectionTestUtils.setField(invoiceB, "id", 702L);

        JournalEntry existingEntry = new JournalEntry();
        ReflectionTestUtils.setField(existingEntry, "id", 1950L);
        existingEntry.setDealer(dealer);
        existingEntry.setReferenceNumber("DR-SETTLE-REPLAY-NETCASH-1");

        com.bigbrightpaints.erp.modules.accounting.domain.PartnerSettlementAllocation existingRowA =
                new com.bigbrightpaints.erp.modules.accounting.domain.PartnerSettlementAllocation();
        existingRowA.setCompany(company);
        existingRowA.setPartnerType(com.bigbrightpaints.erp.modules.accounting.domain.PartnerType.DEALER);
        existingRowA.setDealer(dealer);
        existingRowA.setInvoice(invoiceA);
        existingRowA.setJournalEntry(existingEntry);
        existingRowA.setSettlementDate(LocalDate.of(2024, 4, 9));
        existingRowA.setAllocationAmount(new BigDecimal("100.00"));
        existingRowA.setDiscountAmount(BigDecimal.ZERO);
        existingRowA.setWriteOffAmount(BigDecimal.ZERO);
        existingRowA.setFxDifferenceAmount(BigDecimal.ZERO);
        existingRowA.setIdempotencyKey("IDEMP-DR-SETTLE-REPLAY-NETCASH");

        com.bigbrightpaints.erp.modules.accounting.domain.PartnerSettlementAllocation existingRowB =
                new com.bigbrightpaints.erp.modules.accounting.domain.PartnerSettlementAllocation();
        existingRowB.setCompany(company);
        existingRowB.setPartnerType(com.bigbrightpaints.erp.modules.accounting.domain.PartnerType.DEALER);
        existingRowB.setDealer(dealer);
        existingRowB.setInvoice(invoiceB);
        existingRowB.setJournalEntry(existingEntry);
        existingRowB.setSettlementDate(LocalDate.of(2024, 4, 9));
        existingRowB.setAllocationAmount(new BigDecimal("200.00"));
        existingRowB.setDiscountAmount(BigDecimal.ZERO);
        existingRowB.setWriteOffAmount(BigDecimal.ZERO);
        existingRowB.setFxDifferenceAmount(BigDecimal.ZERO);
        existingRowB.setIdempotencyKey("IDEMP-DR-SETTLE-REPLAY-NETCASH");

        when(dealerRepository.lockByCompanyAndId(eq(company), eq(1L))).thenReturn(Optional.of(dealer));
        when(companyEntityLookup.requireAccount(eq(company), eq(20L))).thenReturn(cash);
        when(companyEntityLookup.requireAccount(eq(company), eq(21L))).thenReturn(discount);
        when(journalReferenceMappingRepository.findAllByCompanyAndLegacyReferenceIgnoreCase(
                eq(company), eq("idemp-dr-settle-replay-netcash")))
                .thenReturn(List.of());
        when(journalReferenceResolver.findExistingEntry(eq(company), eq("DR-SETTLE-REPLAY-NETCASH-1")))
                .thenReturn(Optional.empty());
        when(journalReferenceMappingRepository.reserveReferenceMapping(
                eq(company.getId()),
                eq("idemp-dr-settle-replay-netcash"),
                eq("DR-SETTLE-REPLAY-NETCASH-1"),
                eq("DEALER_SETTLEMENT"),
                any()))
                .thenReturn(1);
        when(settlementAllocationRepository.findByCompanyAndIdempotencyKeyIgnoreCaseOrderByCreatedAtAscIdAsc(
                eq(company), eq("IDEMP-DR-SETTLE-REPLAY-NETCASH")))
                .thenReturn(List.of(existingRowA, existingRowB));

        DealerSettlementRequest request = new DealerSettlementRequest(
                1L,
                20L,
                21L,
                null,
                null,
                null,
                LocalDate.of(2024, 4, 9),
                "DR-SETTLE-REPLAY-NETCASH-1",
                "Dealer settlement replay",
                "IDEMP-DR-SETTLE-REPLAY-NETCASH",
                Boolean.FALSE,
                List.of(
                        new SettlementAllocationRequest(
                                701L,
                                null,
                                new BigDecimal("100.00"),
                                new BigDecimal("120.00"),
                                BigDecimal.ZERO,
                                BigDecimal.ZERO,
                                null
                        ),
                        new SettlementAllocationRequest(
                                702L,
                                null,
                                new BigDecimal("200.00"),
                                BigDecimal.ZERO,
                                BigDecimal.ZERO,
                                BigDecimal.ZERO,
                                null
                        )
                ),
                null
        );

        assertThatThrownBy(() -> accountingService.settleDealerInvoices(request))
                .isInstanceOfSatisfying(ApplicationException.class, ex -> {
                    assertPartnerReplayDetails(ex, "IDEMP-DR-SETTLE-REPLAY-NETCASH", "DEALER", 1L);
                    assertAllocationReplayDiagnostics(ex, 2, 2, "|applied=100", "|discount=120");
                })
                .hasMessageContaining("different settlement payload")
                .satisfies(ex -> assertThat(ex).hasMessageNotContaining("negative net cash contribution"));
    }

    @Test
    void settleSupplierInvoices_replayPayloadMismatchWinsOverNetCashPrevalidation() {
        Supplier supplier = new Supplier();
        supplier.setName("Replay Supplier");
        ReflectionTestUtils.setField(supplier, "id", 1L);

        Account payable = new Account();
        payable.setCompany(company);
        payable.setCode("AP-SETTLE-REPLAY");
        payable.setType(AccountType.LIABILITY);
        ReflectionTestUtils.setField(payable, "id", 10L);
        supplier.setPayableAccount(payable);

        Account cash = new Account();
        cash.setCompany(company);
        cash.setCode("CASH-SETTLE-REPLAY");
        cash.setType(AccountType.ASSET);
        ReflectionTestUtils.setField(cash, "id", 20L);

        Account discount = new Account();
        discount.setCompany(company);
        discount.setCode("DISC-SETTLE-REPLAY");
        ReflectionTestUtils.setField(discount, "id", 21L);

        RawMaterialPurchase purchaseA = new RawMaterialPurchase();
        purchaseA.setCompany(company);
        purchaseA.setSupplier(supplier);
        purchaseA.setTotalAmount(new BigDecimal("100.00"));
        purchaseA.setTaxAmount(BigDecimal.ZERO);
        ReflectionTestUtils.setField(purchaseA, "id", 801L);

        RawMaterialPurchase purchaseB = new RawMaterialPurchase();
        purchaseB.setCompany(company);
        purchaseB.setSupplier(supplier);
        purchaseB.setTotalAmount(new BigDecimal("200.00"));
        purchaseB.setTaxAmount(BigDecimal.ZERO);
        ReflectionTestUtils.setField(purchaseB, "id", 802L);

        Account inventoryA = new Account();
        inventoryA.setCompany(company);
        inventoryA.setCode("INV-SETTLE-REPLAY-A");
        inventoryA.setType(AccountType.ASSET);
        ReflectionTestUtils.setField(inventoryA, "id", 31L);

        Account inventoryB = new Account();
        inventoryB.setCompany(company);
        inventoryB.setCode("INV-SETTLE-REPLAY-B");
        inventoryB.setType(AccountType.ASSET);
        ReflectionTestUtils.setField(inventoryB, "id", 32L);

        JournalEntry purchaseJournalA = new JournalEntry();
        ReflectionTestUtils.setField(purchaseJournalA, "id", 1801L);
        purchaseJournalA.setSupplier(supplier);
        purchaseJournalA.getLines().add(journalLine(
                purchaseJournalA,
                inventoryA,
                "Purchase replay A",
                new BigDecimal("100.00"),
                BigDecimal.ZERO));
        purchaseJournalA.getLines().add(journalLine(
                purchaseJournalA,
                payable,
                "Purchase replay A",
                BigDecimal.ZERO,
                new BigDecimal("100.00")));
        purchaseA.setJournalEntry(purchaseJournalA);

        JournalEntry purchaseJournalB = new JournalEntry();
        ReflectionTestUtils.setField(purchaseJournalB, "id", 1802L);
        purchaseJournalB.setSupplier(supplier);
        purchaseJournalB.getLines().add(journalLine(
                purchaseJournalB,
                inventoryB,
                "Purchase replay B",
                new BigDecimal("200.00"),
                BigDecimal.ZERO));
        purchaseJournalB.getLines().add(journalLine(
                purchaseJournalB,
                payable,
                "Purchase replay B",
                BigDecimal.ZERO,
                new BigDecimal("200.00")));
        purchaseB.setJournalEntry(purchaseJournalB);

        JournalEntry existingEntry = new JournalEntry();
        ReflectionTestUtils.setField(existingEntry, "id", 1951L);
        existingEntry.setSupplier(supplier);
        existingEntry.setReferenceNumber("AP-SETTLE-REPLAY-NETCASH-1");

        com.bigbrightpaints.erp.modules.accounting.domain.PartnerSettlementAllocation existingRowA =
                new com.bigbrightpaints.erp.modules.accounting.domain.PartnerSettlementAllocation();
        existingRowA.setCompany(company);
        existingRowA.setPartnerType(com.bigbrightpaints.erp.modules.accounting.domain.PartnerType.SUPPLIER);
        existingRowA.setSupplier(supplier);
        existingRowA.setPurchase(purchaseA);
        existingRowA.setJournalEntry(existingEntry);
        existingRowA.setSettlementDate(LocalDate.of(2024, 4, 9));
        existingRowA.setAllocationAmount(new BigDecimal("100.00"));
        existingRowA.setDiscountAmount(BigDecimal.ZERO);
        existingRowA.setWriteOffAmount(BigDecimal.ZERO);
        existingRowA.setFxDifferenceAmount(BigDecimal.ZERO);
        existingRowA.setIdempotencyKey("IDEMP-AP-SETTLE-REPLAY-NETCASH");

        com.bigbrightpaints.erp.modules.accounting.domain.PartnerSettlementAllocation existingRowB =
                new com.bigbrightpaints.erp.modules.accounting.domain.PartnerSettlementAllocation();
        existingRowB.setCompany(company);
        existingRowB.setPartnerType(com.bigbrightpaints.erp.modules.accounting.domain.PartnerType.SUPPLIER);
        existingRowB.setSupplier(supplier);
        existingRowB.setPurchase(purchaseB);
        existingRowB.setJournalEntry(existingEntry);
        existingRowB.setSettlementDate(LocalDate.of(2024, 4, 9));
        existingRowB.setAllocationAmount(new BigDecimal("200.00"));
        existingRowB.setDiscountAmount(BigDecimal.ZERO);
        existingRowB.setWriteOffAmount(BigDecimal.ZERO);
        existingRowB.setFxDifferenceAmount(BigDecimal.ZERO);
        existingRowB.setIdempotencyKey("IDEMP-AP-SETTLE-REPLAY-NETCASH");

        when(supplierRepository.lockByCompanyAndId(eq(company), eq(1L))).thenReturn(Optional.of(supplier));
        when(companyEntityLookup.requireAccount(eq(company), eq(20L))).thenReturn(cash);
        when(companyEntityLookup.requireAccount(eq(company), eq(21L))).thenReturn(discount);
        when(journalReferenceMappingRepository.findAllByCompanyAndLegacyReferenceIgnoreCase(
                eq(company), eq("idemp-ap-settle-replay-netcash")))
                .thenReturn(List.of());
        when(journalReferenceResolver.findExistingEntry(eq(company), eq("AP-SETTLE-REPLAY-NETCASH-1")))
                .thenReturn(Optional.empty());
        when(journalReferenceMappingRepository.reserveReferenceMapping(
                eq(company.getId()),
                eq("idemp-ap-settle-replay-netcash"),
                eq("AP-SETTLE-REPLAY-NETCASH-1"),
                eq("SUPPLIER_SETTLEMENT"),
                any()))
                .thenReturn(1);
        when(settlementAllocationRepository.findByCompanyAndIdempotencyKeyIgnoreCaseOrderByCreatedAtAscIdAsc(
                eq(company), eq("IDEMP-AP-SETTLE-REPLAY-NETCASH")))
                .thenReturn(List.of(existingRowA, existingRowB));

        SupplierSettlementRequest request = new SupplierSettlementRequest(
                1L,
                20L,
                21L,
                null,
                null,
                null,
                LocalDate.of(2024, 4, 9),
                "AP-SETTLE-REPLAY-NETCASH-1",
                "Supplier settlement replay",
                "IDEMP-AP-SETTLE-REPLAY-NETCASH",
                Boolean.FALSE,
                List.of(
                        new SettlementAllocationRequest(
                                null,
                                801L,
                                new BigDecimal("100.00"),
                                new BigDecimal("120.00"),
                                BigDecimal.ZERO,
                                BigDecimal.ZERO,
                                null
                        ),
                        new SettlementAllocationRequest(
                                null,
                                802L,
                                new BigDecimal("200.00"),
                                BigDecimal.ZERO,
                                BigDecimal.ZERO,
                                BigDecimal.ZERO,
                                null
                        )
                )
        );

        assertThatThrownBy(() -> accountingService.settleSupplierInvoices(request))
                .isInstanceOfSatisfying(ApplicationException.class, ex -> {
                    assertPartnerReplayDetails(ex, "IDEMP-AP-SETTLE-REPLAY-NETCASH", "SUPPLIER", 1L);
                    assertAllocationReplayDiagnostics(ex, 2, 2, "|applied=100", "|discount=120");
                })
                .hasMessageContaining("different settlement payload")
                .satisfies(ex -> assertThat(ex).hasMessageNotContaining("negative net cash contribution"));
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
    void buildDealerSettlementIdempotencyKey_isStableAcrossEquivalentSplitPaymentOrder() {
        DealerSettlementRequest requestA = new DealerSettlementRequest(
                1L,
                null,
                null,
                null,
                null,
                null,
                LocalDate.of(2024, 5, 1),
                null,
                null,
                null,
                Boolean.FALSE,
                List.of(new SettlementAllocationRequest(
                        701L,
                        null,
                        new BigDecimal("100.00"),
                        BigDecimal.ZERO,
                        BigDecimal.ZERO,
                        BigDecimal.ZERO,
                        null
                )),
                List.of(
                        new SettlementPaymentRequest(20L, new BigDecimal("60.00"), "CASH"),
                        new SettlementPaymentRequest(20L, new BigDecimal("40.00"), "BANK")
                )
        );

        DealerSettlementRequest requestB = new DealerSettlementRequest(
                1L,
                null,
                null,
                null,
                null,
                null,
                LocalDate.of(2024, 5, 1),
                null,
                null,
                null,
                Boolean.FALSE,
                List.of(new SettlementAllocationRequest(
                        701L,
                        null,
                        new BigDecimal("100.00"),
                        BigDecimal.ZERO,
                        BigDecimal.ZERO,
                        BigDecimal.ZERO,
                        null
                )),
                List.of(
                        new SettlementPaymentRequest(20L, new BigDecimal("40.00"), "BANK"),
                        new SettlementPaymentRequest(20L, new BigDecimal("60.00"), "CASH")
                )
        );

        String keyA = ReflectionTestUtils.invokeMethod(accountingService, "buildDealerSettlementIdempotencyKey", requestA);
        String keyB = ReflectionTestUtils.invokeMethod(accountingService, "buildDealerSettlementIdempotencyKey", requestB);

        assertThat(keyA).isEqualTo(keyB);
    }

    @Test
    void resolveDealerSettlementIdempotencyKey_prefersLegacyAutoKeyWhenReplayExists() {
        DealerSettlementRequest request = new DealerSettlementRequest(
                1L,
                null,
                null,
                null,
                null,
                null,
                LocalDate.of(2024, 5, 1),
                null,
                null,
                null,
                Boolean.FALSE,
                List.of(new SettlementAllocationRequest(
                        701L,
                        null,
                        new BigDecimal("100.00"),
                        BigDecimal.ZERO,
                        BigDecimal.ZERO,
                        BigDecimal.ZERO,
                        null
                )),
                List.of(
                        new SettlementPaymentRequest(20L, new BigDecimal("60.00"), "CASH"),
                        new SettlementPaymentRequest(20L, new BigDecimal("40.00"), "BANK")
                )
        );

        String canonicalKey = ReflectionTestUtils.invokeMethod(accountingService, "buildDealerSettlementIdempotencyKey", request);
        String legacyKey = ReflectionTestUtils.invokeMethod(accountingService, "buildLegacyDealerSettlementIdempotencyKey", request);
        assertThat(canonicalKey).isNotEqualTo(legacyKey);

        Dealer dealer = new Dealer();
        ReflectionTestUtils.setField(dealer, "id", 1L);

        var invoice = new com.bigbrightpaints.erp.modules.invoice.domain.Invoice();
        invoice.setCompany(company);
        invoice.setDealer(dealer);
        ReflectionTestUtils.setField(invoice, "id", 701L);

        var existing = new PartnerSettlementAllocation();
        existing.setCompany(company);
        existing.setDealer(dealer);
        existing.setInvoice(invoice);
        existing.setAllocationAmount(new BigDecimal("100.00"));
        existing.setDiscountAmount(BigDecimal.ZERO);
        existing.setWriteOffAmount(BigDecimal.ZERO);
        existing.setFxDifferenceAmount(BigDecimal.ZERO);
        existing.setIdempotencyKey(legacyKey);

        Account settlementCashAccount = new Account();
        settlementCashAccount.setCompany(company);
        settlementCashAccount.setType(AccountType.ASSET);
        settlementCashAccount.setCode("CASH");
        settlementCashAccount.setName("Cash");
        ReflectionTestUtils.setField(settlementCashAccount, "id", 20L);

        JournalEntry existingEntry = new JournalEntry();
        existingEntry.getLines().add(journalLine(
                existingEntry,
                settlementCashAccount,
                "payment-cash",
                new BigDecimal("60.00"),
                BigDecimal.ZERO
        ));
        existingEntry.getLines().add(journalLine(
                existingEntry,
                settlementCashAccount,
                "payment-bank",
                new BigDecimal("40.00"),
                BigDecimal.ZERO
        ));
        existing.setJournalEntry(existingEntry);

        when(invoiceRepository.findByCompanyAndId(eq(company), eq(701L))).thenReturn(Optional.of(invoice));
        when(settlementAllocationRepository.findByCompanyAndInvoiceOrderByCreatedAtDesc(eq(company), eq(invoice)))
                .thenReturn(List.of(existing));
        when(settlementAllocationRepository.findByCompanyAndIdempotencyKeyIgnoreCaseOrderByCreatedAtAscIdAsc(
                eq(company), eq(legacyKey))).thenReturn(List.of(existing));

        String resolved = ReflectionTestUtils.invokeMethod(
                accountingService,
                "resolveDealerSettlementIdempotencyKey",
                company,
                request
        );

        assertThat(resolved).isEqualTo(legacyKey);
    }

    @Test
    void resolveDealerSettlementIdempotencyKey_matchesLegacyReplayAcrossEquivalentPaymentReorder() {
        DealerSettlementRequest originalOrderRequest = new DealerSettlementRequest(
                1L,
                null,
                null,
                null,
                null,
                null,
                LocalDate.of(2024, 5, 1),
                null,
                null,
                null,
                Boolean.FALSE,
                List.of(new SettlementAllocationRequest(
                        701L,
                        null,
                        new BigDecimal("100.00"),
                        BigDecimal.ZERO,
                        BigDecimal.ZERO,
                        BigDecimal.ZERO,
                        null
                )),
                List.of(
                        new SettlementPaymentRequest(20L, new BigDecimal("60.00"), "CASH"),
                        new SettlementPaymentRequest(20L, new BigDecimal("40.00"), "BANK")
                )
        );

        DealerSettlementRequest replayOrderRequest = new DealerSettlementRequest(
                1L,
                null,
                null,
                null,
                null,
                null,
                LocalDate.of(2024, 5, 1),
                null,
                null,
                null,
                Boolean.FALSE,
                List.of(new SettlementAllocationRequest(
                        701L,
                        null,
                        new BigDecimal("100.00"),
                        BigDecimal.ZERO,
                        BigDecimal.ZERO,
                        BigDecimal.ZERO,
                        null
                )),
                List.of(
                        new SettlementPaymentRequest(20L, new BigDecimal("40.00"), "BANK"),
                        new SettlementPaymentRequest(20L, new BigDecimal("60.00"), "CASH")
                )
        );

        String canonicalReplayKey = ReflectionTestUtils.invokeMethod(
                accountingService,
                "buildDealerSettlementIdempotencyKey",
                replayOrderRequest
        );
        String legacyOriginalKey = ReflectionTestUtils.invokeMethod(
                accountingService,
                "buildLegacyDealerSettlementIdempotencyKey",
                originalOrderRequest
        );
        assertThat(canonicalReplayKey).isNotEqualTo(legacyOriginalKey);

        Dealer dealer = new Dealer();
        ReflectionTestUtils.setField(dealer, "id", 1L);

        var invoice = new com.bigbrightpaints.erp.modules.invoice.domain.Invoice();
        invoice.setCompany(company);
        invoice.setDealer(dealer);
        ReflectionTestUtils.setField(invoice, "id", 701L);

        var existing = new PartnerSettlementAllocation();
        existing.setCompany(company);
        existing.setDealer(dealer);
        existing.setInvoice(invoice);
        existing.setAllocationAmount(new BigDecimal("100.00"));
        existing.setDiscountAmount(BigDecimal.ZERO);
        existing.setWriteOffAmount(BigDecimal.ZERO);
        existing.setFxDifferenceAmount(BigDecimal.ZERO);
        existing.setIdempotencyKey(legacyOriginalKey);

        Account settlementCashAccount = new Account();
        settlementCashAccount.setCompany(company);
        settlementCashAccount.setType(AccountType.ASSET);
        settlementCashAccount.setCode("CASH");
        settlementCashAccount.setName("Cash");
        ReflectionTestUtils.setField(settlementCashAccount, "id", 20L);

        Account adjustmentAssetAccount = new Account();
        adjustmentAssetAccount.setCompany(company);
        adjustmentAssetAccount.setType(AccountType.ASSET);
        adjustmentAssetAccount.setCode("ADJ-ASSET");
        adjustmentAssetAccount.setName("Adjustment Asset");
        ReflectionTestUtils.setField(adjustmentAssetAccount, "id", 99L);

        JournalEntry existingEntry = new JournalEntry();
        existingEntry.getLines().add(journalLine(
                existingEntry,
                settlementCashAccount,
                "payment-cash",
                new BigDecimal("60.00"),
                BigDecimal.ZERO
        ));
        existingEntry.getLines().add(journalLine(
                existingEntry,
                settlementCashAccount,
                "payment-bank",
                new BigDecimal("40.00"),
                BigDecimal.ZERO
        ));
        existingEntry.getLines().add(journalLine(
                existingEntry,
                adjustmentAssetAccount,
                "non-payment-adjustment",
                new BigDecimal("5.00"),
                BigDecimal.ZERO
        ));
        existing.setJournalEntry(existingEntry);

        when(invoiceRepository.findByCompanyAndId(eq(company), eq(701L))).thenReturn(Optional.of(invoice));
        when(settlementAllocationRepository.findByCompanyAndInvoiceOrderByCreatedAtDesc(eq(company), eq(invoice)))
                .thenReturn(List.of(existing));
        when(settlementAllocationRepository.findByCompanyAndIdempotencyKeyIgnoreCaseOrderByCreatedAtAscIdAsc(
                eq(company), eq(legacyOriginalKey))).thenReturn(List.of(existing));

        String resolved = ReflectionTestUtils.invokeMethod(
                accountingService,
                "resolveDealerSettlementIdempotencyKey",
                company,
                replayOrderRequest
        );

        assertThat(resolved).isEqualTo(legacyOriginalKey);
    }

    @Test
    void resolveDealerSettlementIdempotencyKey_rejectsLegacyReplayWhenPaymentSignaturesDiffer() {
        DealerSettlementRequest originalOrderRequest = new DealerSettlementRequest(
                1L,
                null,
                null,
                null,
                null,
                null,
                LocalDate.of(2024, 5, 1),
                null,
                null,
                null,
                Boolean.FALSE,
                List.of(new SettlementAllocationRequest(
                        701L,
                        null,
                        new BigDecimal("100.00"),
                        BigDecimal.ZERO,
                        BigDecimal.ZERO,
                        BigDecimal.ZERO,
                        null
                )),
                List.of(
                        new SettlementPaymentRequest(20L, new BigDecimal("60.00"), "CASH"),
                        new SettlementPaymentRequest(20L, new BigDecimal("40.00"), "BANK")
                )
        );

        DealerSettlementRequest replayOrderRequest = new DealerSettlementRequest(
                1L,
                null,
                null,
                null,
                null,
                null,
                LocalDate.of(2024, 5, 1),
                null,
                null,
                null,
                Boolean.FALSE,
                List.of(new SettlementAllocationRequest(
                        701L,
                        null,
                        new BigDecimal("100.00"),
                        BigDecimal.ZERO,
                        BigDecimal.ZERO,
                        BigDecimal.ZERO,
                        null
                )),
                List.of(new SettlementPaymentRequest(20L, new BigDecimal("100.00"), "CASH"))
        );

        String canonicalReplayKey = ReflectionTestUtils.invokeMethod(
                accountingService,
                "buildDealerSettlementIdempotencyKey",
                replayOrderRequest
        );
        String legacyOriginalKey = ReflectionTestUtils.invokeMethod(
                accountingService,
                "buildLegacyDealerSettlementIdempotencyKey",
                originalOrderRequest
        );
        assertThat(canonicalReplayKey).isNotEqualTo(legacyOriginalKey);

        Dealer dealer = new Dealer();
        ReflectionTestUtils.setField(dealer, "id", 1L);

        var invoice = new com.bigbrightpaints.erp.modules.invoice.domain.Invoice();
        invoice.setCompany(company);
        invoice.setDealer(dealer);
        ReflectionTestUtils.setField(invoice, "id", 701L);

        var existing = new PartnerSettlementAllocation();
        existing.setCompany(company);
        existing.setDealer(dealer);
        existing.setInvoice(invoice);
        existing.setAllocationAmount(new BigDecimal("100.00"));
        existing.setDiscountAmount(BigDecimal.ZERO);
        existing.setWriteOffAmount(BigDecimal.ZERO);
        existing.setFxDifferenceAmount(BigDecimal.ZERO);
        existing.setIdempotencyKey(legacyOriginalKey);

        Account settlementCashAccount = new Account();
        settlementCashAccount.setCompany(company);
        settlementCashAccount.setType(AccountType.ASSET);
        settlementCashAccount.setCode("CASH");
        settlementCashAccount.setName("Cash");
        ReflectionTestUtils.setField(settlementCashAccount, "id", 20L);

        JournalEntry existingEntry = new JournalEntry();
        existingEntry.getLines().add(journalLine(
                existingEntry,
                settlementCashAccount,
                "payment-cash",
                new BigDecimal("60.00"),
                BigDecimal.ZERO
        ));
        existingEntry.getLines().add(journalLine(
                existingEntry,
                settlementCashAccount,
                "payment-bank",
                new BigDecimal("40.00"),
                BigDecimal.ZERO
        ));
        existing.setJournalEntry(existingEntry);

        when(invoiceRepository.findByCompanyAndId(eq(company), eq(701L))).thenReturn(Optional.of(invoice));
        when(settlementAllocationRepository.findByCompanyAndInvoiceOrderByCreatedAtDesc(eq(company), eq(invoice)))
                .thenReturn(List.of(existing));
        when(settlementAllocationRepository.findByCompanyAndIdempotencyKeyIgnoreCaseOrderByCreatedAtAscIdAsc(
                eq(company), eq(legacyOriginalKey))).thenReturn(List.of(existing));

        String resolved = ReflectionTestUtils.invokeMethod(
                accountingService,
                "resolveDealerSettlementIdempotencyKey",
                company,
                replayOrderRequest
        );

        assertThat(resolved).isEqualTo(canonicalReplayKey);
    }

    @Test
    void buildDealerSettlementIdempotencyKey_distinguishesImplicitCashAccountWhenPaymentsOmitted() {
        DealerSettlementRequest cashRequest = new DealerSettlementRequest(
                1L,
                20L,
                null,
                null,
                null,
                null,
                LocalDate.of(2024, 5, 1),
                null,
                null,
                null,
                Boolean.FALSE,
                List.of(new SettlementAllocationRequest(
                        701L,
                        null,
                        new BigDecimal("100.00"),
                        BigDecimal.ZERO,
                        BigDecimal.ZERO,
                        BigDecimal.ZERO,
                        null
                )),
                null
        );
        DealerSettlementRequest bankRequest = new DealerSettlementRequest(
                1L,
                21L,
                null,
                null,
                null,
                null,
                LocalDate.of(2024, 5, 1),
                null,
                null,
                null,
                Boolean.FALSE,
                List.of(new SettlementAllocationRequest(
                        701L,
                        null,
                        new BigDecimal("100.00"),
                        BigDecimal.ZERO,
                        BigDecimal.ZERO,
                        BigDecimal.ZERO,
                        null
                )),
                null
        );

        String canonicalCash = ReflectionTestUtils.invokeMethod(accountingService, "buildDealerSettlementIdempotencyKey", cashRequest);
        String canonicalBank = ReflectionTestUtils.invokeMethod(accountingService, "buildDealerSettlementIdempotencyKey", bankRequest);
        String legacyCash = ReflectionTestUtils.invokeMethod(accountingService, "buildLegacyDealerSettlementIdempotencyKey", cashRequest);
        String legacyBank = ReflectionTestUtils.invokeMethod(accountingService, "buildLegacyDealerSettlementIdempotencyKey", bankRequest);

        assertThat(canonicalCash).isNotEqualTo(canonicalBank);
        assertThat(legacyCash).isEqualTo(legacyBank);
    }

    @Test
    void resolveDealerSettlementIdempotencyKey_rejectsLegacyReplayWhenImplicitCashAccountDiffers() {
        DealerSettlementRequest originalOrderRequest = new DealerSettlementRequest(
                1L,
                20L,
                null,
                null,
                null,
                null,
                LocalDate.of(2024, 5, 1),
                null,
                null,
                null,
                Boolean.FALSE,
                List.of(new SettlementAllocationRequest(
                        701L,
                        null,
                        new BigDecimal("100.00"),
                        BigDecimal.ZERO,
                        BigDecimal.ZERO,
                        BigDecimal.ZERO,
                        null
                )),
                null
        );
        DealerSettlementRequest replayOrderRequest = new DealerSettlementRequest(
                1L,
                21L,
                null,
                null,
                null,
                null,
                LocalDate.of(2024, 5, 1),
                null,
                null,
                null,
                Boolean.FALSE,
                List.of(new SettlementAllocationRequest(
                        701L,
                        null,
                        new BigDecimal("100.00"),
                        BigDecimal.ZERO,
                        BigDecimal.ZERO,
                        BigDecimal.ZERO,
                        null
                )),
                null
        );

        String canonicalReplayKey = ReflectionTestUtils.invokeMethod(
                accountingService,
                "buildDealerSettlementIdempotencyKey",
                replayOrderRequest
        );
        String legacyOriginalKey = ReflectionTestUtils.invokeMethod(
                accountingService,
                "buildLegacyDealerSettlementIdempotencyKey",
                originalOrderRequest
        );
        assertThat(canonicalReplayKey).isNotEqualTo(legacyOriginalKey);

        Dealer dealer = new Dealer();
        ReflectionTestUtils.setField(dealer, "id", 1L);

        var invoice = new com.bigbrightpaints.erp.modules.invoice.domain.Invoice();
        invoice.setCompany(company);
        invoice.setDealer(dealer);
        ReflectionTestUtils.setField(invoice, "id", 701L);

        var existing = new PartnerSettlementAllocation();
        existing.setCompany(company);
        existing.setDealer(dealer);
        existing.setInvoice(invoice);
        existing.setAllocationAmount(new BigDecimal("100.00"));
        existing.setDiscountAmount(BigDecimal.ZERO);
        existing.setWriteOffAmount(BigDecimal.ZERO);
        existing.setFxDifferenceAmount(BigDecimal.ZERO);
        existing.setIdempotencyKey(legacyOriginalKey);

        Account legacyCashAccount = new Account();
        legacyCashAccount.setCompany(company);
        legacyCashAccount.setType(AccountType.ASSET);
        legacyCashAccount.setCode("CASH");
        legacyCashAccount.setName("Cash");
        ReflectionTestUtils.setField(legacyCashAccount, "id", 20L);

        JournalEntry existingEntry = new JournalEntry();
        existingEntry.getLines().add(journalLine(
                existingEntry,
                legacyCashAccount,
                "legacy-cash-payment",
                new BigDecimal("100.00"),
                BigDecimal.ZERO
        ));
        existing.setJournalEntry(existingEntry);

        when(invoiceRepository.findByCompanyAndId(eq(company), eq(701L))).thenReturn(Optional.of(invoice));
        when(settlementAllocationRepository.findByCompanyAndInvoiceOrderByCreatedAtDesc(eq(company), eq(invoice)))
                .thenReturn(List.of(existing));
        when(settlementAllocationRepository.findByCompanyAndIdempotencyKeyIgnoreCaseOrderByCreatedAtAscIdAsc(
                eq(company), eq(legacyOriginalKey))).thenReturn(List.of(existing));

        String resolved = ReflectionTestUtils.invokeMethod(
                accountingService,
                "resolveDealerSettlementIdempotencyKey",
                company,
                replayOrderRequest
        );

        assertThat(resolved).isEqualTo(canonicalReplayKey);
    }

    @Test
    void resolveDealerSettlementIdempotencyKey_acceptsLegacyReplayWhenDiscountDebitSharesCashAccount() {
        DealerSettlementRequest replayRequest = new DealerSettlementRequest(
                1L,
                20L,
                20L,
                null,
                null,
                null,
                LocalDate.of(2024, 5, 1),
                null,
                null,
                null,
                Boolean.FALSE,
                List.of(new SettlementAllocationRequest(
                        701L,
                        null,
                        new BigDecimal("100.00"),
                        new BigDecimal("40.00"),
                        BigDecimal.ZERO,
                        BigDecimal.ZERO,
                        null
                )),
                null
        );

        String canonicalReplayKey = ReflectionTestUtils.invokeMethod(
                accountingService,
                "buildDealerSettlementIdempotencyKey",
                replayRequest
        );
        String legacyKey = ReflectionTestUtils.invokeMethod(
                accountingService,
                "buildLegacyDealerSettlementIdempotencyKey",
                replayRequest
        );
        assertThat(canonicalReplayKey).isNotEqualTo(legacyKey);

        Dealer dealer = new Dealer();
        ReflectionTestUtils.setField(dealer, "id", 1L);

        var invoice = new com.bigbrightpaints.erp.modules.invoice.domain.Invoice();
        invoice.setCompany(company);
        invoice.setDealer(dealer);
        ReflectionTestUtils.setField(invoice, "id", 701L);

        var existing = new PartnerSettlementAllocation();
        existing.setCompany(company);
        existing.setDealer(dealer);
        existing.setInvoice(invoice);
        existing.setAllocationAmount(new BigDecimal("100.00"));
        existing.setDiscountAmount(new BigDecimal("40.00"));
        existing.setWriteOffAmount(BigDecimal.ZERO);
        existing.setFxDifferenceAmount(BigDecimal.ZERO);
        existing.setIdempotencyKey(legacyKey);

        Account cashAccount = new Account();
        cashAccount.setCompany(company);
        cashAccount.setType(AccountType.ASSET);
        cashAccount.setCode("CASH");
        cashAccount.setName("Cash");
        ReflectionTestUtils.setField(cashAccount, "id", 20L);

        Account receivableAccount = new Account();
        receivableAccount.setCompany(company);
        receivableAccount.setType(AccountType.ASSET);
        receivableAccount.setCode("AR-DEALER");
        receivableAccount.setName("Dealer Receivable");
        ReflectionTestUtils.setField(receivableAccount, "id", 10L);

        JournalEntry existingEntry = new JournalEntry();
        existingEntry.getLines().add(journalLine(
                existingEntry,
                cashAccount,
                "Dealer settlement",
                new BigDecimal("60.00"),
                BigDecimal.ZERO
        ));
        existingEntry.getLines().add(journalLine(
                existingEntry,
                cashAccount,
                "Settlement discount",
                new BigDecimal("40.00"),
                BigDecimal.ZERO
        ));
        existingEntry.getLines().add(journalLine(
                existingEntry,
                receivableAccount,
                "Dealer settlement",
                BigDecimal.ZERO,
                new BigDecimal("100.00")
        ));
        existing.setJournalEntry(existingEntry);

        when(invoiceRepository.findByCompanyAndId(eq(company), eq(701L))).thenReturn(Optional.of(invoice));
        when(settlementAllocationRepository.findByCompanyAndInvoiceOrderByCreatedAtDesc(eq(company), eq(invoice)))
                .thenReturn(List.of(existing));
        when(settlementAllocationRepository.findByCompanyAndIdempotencyKeyIgnoreCaseOrderByCreatedAtAscIdAsc(
                eq(company), eq(legacyKey))).thenReturn(List.of(existing));

        String resolved = ReflectionTestUtils.invokeMethod(
                accountingService,
                "resolveDealerSettlementIdempotencyKey",
                company,
                replayRequest
        );

        assertThat(resolved).isEqualTo(legacyKey);
    }

    @Test
    void resolveDealerSettlementIdempotencyKey_acceptsLegacyReplayWhenDiscountDebitSplitsAcrossLines() {
        DealerSettlementRequest replayRequest = new DealerSettlementRequest(
                1L,
                20L,
                20L,
                null,
                null,
                null,
                LocalDate.of(2024, 5, 1),
                null,
                null,
                null,
                Boolean.FALSE,
                List.of(new SettlementAllocationRequest(
                        701L,
                        null,
                        new BigDecimal("100.00"),
                        new BigDecimal("40.00"),
                        BigDecimal.ZERO,
                        BigDecimal.ZERO,
                        null
                )),
                null
        );

        String canonicalReplayKey = ReflectionTestUtils.invokeMethod(
                accountingService,
                "buildDealerSettlementIdempotencyKey",
                replayRequest
        );
        String legacyKey = ReflectionTestUtils.invokeMethod(
                accountingService,
                "buildLegacyDealerSettlementIdempotencyKey",
                replayRequest
        );
        assertThat(canonicalReplayKey).isNotEqualTo(legacyKey);

        Dealer dealer = new Dealer();
        ReflectionTestUtils.setField(dealer, "id", 1L);

        var invoice = new com.bigbrightpaints.erp.modules.invoice.domain.Invoice();
        invoice.setCompany(company);
        invoice.setDealer(dealer);
        ReflectionTestUtils.setField(invoice, "id", 701L);

        var existing = new PartnerSettlementAllocation();
        existing.setCompany(company);
        existing.setDealer(dealer);
        existing.setInvoice(invoice);
        existing.setAllocationAmount(new BigDecimal("100.00"));
        existing.setDiscountAmount(new BigDecimal("40.00"));
        existing.setWriteOffAmount(BigDecimal.ZERO);
        existing.setFxDifferenceAmount(BigDecimal.ZERO);
        existing.setIdempotencyKey(legacyKey);

        Account cashAccount = new Account();
        cashAccount.setCompany(company);
        cashAccount.setType(AccountType.ASSET);
        cashAccount.setCode("CASH");
        cashAccount.setName("Cash");
        ReflectionTestUtils.setField(cashAccount, "id", 20L);

        Account receivableAccount = new Account();
        receivableAccount.setCompany(company);
        receivableAccount.setType(AccountType.ASSET);
        receivableAccount.setCode("AR-DEALER");
        receivableAccount.setName("Dealer Receivable");
        ReflectionTestUtils.setField(receivableAccount, "id", 10L);

        JournalEntry existingEntry = new JournalEntry();
        existingEntry.getLines().add(journalLine(
                existingEntry,
                cashAccount,
                "Dealer settlement",
                new BigDecimal("60.00"),
                BigDecimal.ZERO
        ));
        existingEntry.getLines().add(journalLine(
                existingEntry,
                cashAccount,
                "Settlement discount",
                new BigDecimal("20.00"),
                BigDecimal.ZERO
        ));
        existingEntry.getLines().add(journalLine(
                existingEntry,
                cashAccount,
                "Settlement discount",
                new BigDecimal("20.00"),
                BigDecimal.ZERO
        ));
        existingEntry.getLines().add(journalLine(
                existingEntry,
                receivableAccount,
                "Dealer settlement",
                BigDecimal.ZERO,
                new BigDecimal("100.00")
        ));
        existing.setJournalEntry(existingEntry);

        when(invoiceRepository.findByCompanyAndId(eq(company), eq(701L))).thenReturn(Optional.of(invoice));
        when(settlementAllocationRepository.findByCompanyAndInvoiceOrderByCreatedAtDesc(eq(company), eq(invoice)))
                .thenReturn(List.of(existing));
        when(settlementAllocationRepository.findByCompanyAndIdempotencyKeyIgnoreCaseOrderByCreatedAtAscIdAsc(
                eq(company), eq(legacyKey))).thenReturn(List.of(existing));

        String resolved = ReflectionTestUtils.invokeMethod(
                accountingService,
                "resolveDealerSettlementIdempotencyKey",
                company,
                replayRequest
        );

        assertThat(resolved).isEqualTo(legacyKey);
    }

    @Test
    void resolveDealerSettlementIdempotencyKey_acceptsLegacyReplayWhenDiscountDebitOnNonPaymentAssetAccount() {
        DealerSettlementRequest replayRequest = new DealerSettlementRequest(
                1L,
                20L,
                30L,
                null,
                null,
                null,
                LocalDate.of(2024, 5, 1),
                null,
                null,
                null,
                Boolean.FALSE,
                List.of(new SettlementAllocationRequest(
                        701L,
                        null,
                        new BigDecimal("120.00"),
                        new BigDecimal("40.00"),
                        BigDecimal.ZERO,
                        BigDecimal.ZERO,
                        null
                )),
                null
        );

        String canonicalReplayKey = ReflectionTestUtils.invokeMethod(
                accountingService,
                "buildDealerSettlementIdempotencyKey",
                replayRequest
        );
        String legacyKey = ReflectionTestUtils.invokeMethod(
                accountingService,
                "buildLegacyDealerSettlementIdempotencyKey",
                replayRequest
        );
        assertThat(canonicalReplayKey).isNotEqualTo(legacyKey);

        Dealer dealer = new Dealer();
        ReflectionTestUtils.setField(dealer, "id", 1L);

        var invoice = new com.bigbrightpaints.erp.modules.invoice.domain.Invoice();
        invoice.setCompany(company);
        invoice.setDealer(dealer);
        ReflectionTestUtils.setField(invoice, "id", 701L);

        var existing = new PartnerSettlementAllocation();
        existing.setCompany(company);
        existing.setDealer(dealer);
        existing.setInvoice(invoice);
        existing.setAllocationAmount(new BigDecimal("120.00"));
        existing.setDiscountAmount(new BigDecimal("40.00"));
        existing.setWriteOffAmount(BigDecimal.ZERO);
        existing.setFxDifferenceAmount(BigDecimal.ZERO);
        existing.setIdempotencyKey(legacyKey);

        Account cashAccount = new Account();
        cashAccount.setCompany(company);
        cashAccount.setType(AccountType.ASSET);
        cashAccount.setCode("BANK-20");
        cashAccount.setName("Bank 20");
        ReflectionTestUtils.setField(cashAccount, "id", 20L);

        Account discountAssetAccount = new Account();
        discountAssetAccount.setCompany(company);
        discountAssetAccount.setType(AccountType.ASSET);
        discountAssetAccount.setCode("ASSET-ADJ");
        discountAssetAccount.setName("Adjustment Asset");
        ReflectionTestUtils.setField(discountAssetAccount, "id", 30L);

        Account receivableAccount = new Account();
        receivableAccount.setCompany(company);
        receivableAccount.setType(AccountType.ASSET);
        receivableAccount.setCode("AR-DEALER");
        receivableAccount.setName("Dealer Receivable");
        ReflectionTestUtils.setField(receivableAccount, "id", 10L);

        JournalEntry existingEntry = new JournalEntry();
        existingEntry.getLines().add(journalLine(
                existingEntry,
                cashAccount,
                "Dealer settlement",
                new BigDecimal("80.00"),
                BigDecimal.ZERO
        ));
        existingEntry.getLines().add(journalLine(
                existingEntry,
                discountAssetAccount,
                "Settlement discount",
                new BigDecimal("40.00"),
                BigDecimal.ZERO
        ));
        existingEntry.getLines().add(journalLine(
                existingEntry,
                receivableAccount,
                "Dealer settlement",
                BigDecimal.ZERO,
                new BigDecimal("120.00")
        ));
        existing.setJournalEntry(existingEntry);

        when(invoiceRepository.findByCompanyAndId(eq(company), eq(701L))).thenReturn(Optional.of(invoice));
        when(settlementAllocationRepository.findByCompanyAndInvoiceOrderByCreatedAtDesc(eq(company), eq(invoice)))
                .thenReturn(List.of(existing));
        when(settlementAllocationRepository.findByCompanyAndIdempotencyKeyIgnoreCaseOrderByCreatedAtAscIdAsc(
                eq(company), eq(legacyKey))).thenReturn(List.of(existing));

        String resolved = ReflectionTestUtils.invokeMethod(
                accountingService,
                "resolveDealerSettlementIdempotencyKey",
                company,
                replayRequest
        );

        assertThat(resolved).isEqualTo(legacyKey);
    }

    @Test
    void resolveDealerSettlementIdempotencyKey_acceptsLegacyReplayWhenMemoMatchesAdjustmentLabel() {
        DealerSettlementRequest replayRequest = new DealerSettlementRequest(
                1L,
                20L,
                null,
                null,
                null,
                null,
                LocalDate.of(2024, 5, 1),
                null,
                "Settlement discount",
                null,
                Boolean.FALSE,
                List.of(new SettlementAllocationRequest(
                        701L,
                        null,
                        new BigDecimal("100.00"),
                        BigDecimal.ZERO,
                        BigDecimal.ZERO,
                        BigDecimal.ZERO,
                        null
                )),
                null
        );

        String canonicalReplayKey = ReflectionTestUtils.invokeMethod(
                accountingService,
                "buildDealerSettlementIdempotencyKey",
                replayRequest
        );
        String legacyKey = ReflectionTestUtils.invokeMethod(
                accountingService,
                "buildLegacyDealerSettlementIdempotencyKey",
                replayRequest
        );
        assertThat(canonicalReplayKey).isNotEqualTo(legacyKey);

        Dealer dealer = new Dealer();
        ReflectionTestUtils.setField(dealer, "id", 1L);

        var invoice = new com.bigbrightpaints.erp.modules.invoice.domain.Invoice();
        invoice.setCompany(company);
        invoice.setDealer(dealer);
        ReflectionTestUtils.setField(invoice, "id", 701L);

        var existing = new PartnerSettlementAllocation();
        existing.setCompany(company);
        existing.setDealer(dealer);
        existing.setInvoice(invoice);
        existing.setAllocationAmount(new BigDecimal("100.00"));
        existing.setDiscountAmount(BigDecimal.ZERO);
        existing.setWriteOffAmount(BigDecimal.ZERO);
        existing.setFxDifferenceAmount(BigDecimal.ZERO);
        existing.setIdempotencyKey(legacyKey);

        Account cashAccount = new Account();
        cashAccount.setCompany(company);
        cashAccount.setType(AccountType.ASSET);
        cashAccount.setCode("CASH");
        cashAccount.setName("Cash");
        ReflectionTestUtils.setField(cashAccount, "id", 20L);

        Account receivableAccount = new Account();
        receivableAccount.setCompany(company);
        receivableAccount.setType(AccountType.ASSET);
        receivableAccount.setCode("AR-DEALER");
        receivableAccount.setName("Dealer Receivable");
        ReflectionTestUtils.setField(receivableAccount, "id", 10L);

        JournalEntry existingEntry = new JournalEntry();
        existingEntry.getLines().add(journalLine(
                existingEntry,
                cashAccount,
                "Settlement discount",
                new BigDecimal("100.00"),
                BigDecimal.ZERO
        ));
        existingEntry.getLines().add(journalLine(
                existingEntry,
                receivableAccount,
                "Settlement discount",
                BigDecimal.ZERO,
                new BigDecimal("100.00")
        ));
        existing.setJournalEntry(existingEntry);

        when(invoiceRepository.findByCompanyAndId(eq(company), eq(701L))).thenReturn(Optional.of(invoice));
        when(settlementAllocationRepository.findByCompanyAndInvoiceOrderByCreatedAtDesc(eq(company), eq(invoice)))
                .thenReturn(List.of(existing));
        when(settlementAllocationRepository.findByCompanyAndIdempotencyKeyIgnoreCaseOrderByCreatedAtAscIdAsc(
                eq(company), eq(legacyKey))).thenReturn(List.of(existing));

        String resolved = ReflectionTestUtils.invokeMethod(
                accountingService,
                "resolveDealerSettlementIdempotencyKey",
                company,
                replayRequest
        );

        assertThat(resolved).isEqualTo(legacyKey);
    }

    @Test
    void resolveDealerSettlementIdempotencyKey_rejectsLegacyReplayWhenDiscountLineMasksMissingPaymentSplit() {
        DealerSettlementRequest replayRequest = new DealerSettlementRequest(
                1L,
                null,
                20L,
                null,
                null,
                null,
                LocalDate.of(2024, 5, 1),
                null,
                null,
                null,
                Boolean.FALSE,
                List.of(new SettlementAllocationRequest(
                        701L,
                        null,
                        new BigDecimal("120.00"),
                        new BigDecimal("40.00"),
                        BigDecimal.ZERO,
                        BigDecimal.ZERO,
                        null
                )),
                List.of(
                        new SettlementPaymentRequest(20L, new BigDecimal("40.00"), "BANK"),
                        new SettlementPaymentRequest(20L, new BigDecimal("40.00"), "BANK")
                )
        );

        String canonicalReplayKey = ReflectionTestUtils.invokeMethod(
                accountingService,
                "buildDealerSettlementIdempotencyKey",
                replayRequest
        );

        Dealer dealer = new Dealer();
        ReflectionTestUtils.setField(dealer, "id", 1L);

        var invoice = new com.bigbrightpaints.erp.modules.invoice.domain.Invoice();
        invoice.setCompany(company);
        invoice.setDealer(dealer);
        ReflectionTestUtils.setField(invoice, "id", 701L);

        DealerSettlementRequest legacyRequest = new DealerSettlementRequest(
                1L,
                null,
                20L,
                null,
                null,
                null,
                LocalDate.of(2024, 5, 1),
                null,
                null,
                null,
                Boolean.FALSE,
                List.of(new SettlementAllocationRequest(
                        701L,
                        null,
                        new BigDecimal("120.00"),
                        new BigDecimal("40.00"),
                        BigDecimal.ZERO,
                        BigDecimal.ZERO,
                        null
                )),
                List.of(
                        new SettlementPaymentRequest(20L, new BigDecimal("40.00"), "BANK"),
                        new SettlementPaymentRequest(21L, new BigDecimal("40.00"), "BANK")
                )
        );
        String legacyKey = ReflectionTestUtils.invokeMethod(
                accountingService,
                "buildLegacyDealerSettlementIdempotencyKey",
                legacyRequest
        );
        assertThat(canonicalReplayKey).isNotEqualTo(legacyKey);

        var existing = new PartnerSettlementAllocation();
        existing.setCompany(company);
        existing.setDealer(dealer);
        existing.setInvoice(invoice);
        existing.setAllocationAmount(new BigDecimal("120.00"));
        existing.setDiscountAmount(new BigDecimal("40.00"));
        existing.setWriteOffAmount(BigDecimal.ZERO);
        existing.setFxDifferenceAmount(BigDecimal.ZERO);
        existing.setIdempotencyKey(legacyKey);

        Account account20 = new Account();
        account20.setCompany(company);
        account20.setType(AccountType.ASSET);
        account20.setCode("BANK-20");
        account20.setName("Bank 20");
        ReflectionTestUtils.setField(account20, "id", 20L);

        Account account21 = new Account();
        account21.setCompany(company);
        account21.setType(AccountType.ASSET);
        account21.setCode("BANK-21");
        account21.setName("Bank 21");
        ReflectionTestUtils.setField(account21, "id", 21L);

        Account receivableAccount = new Account();
        receivableAccount.setCompany(company);
        receivableAccount.setType(AccountType.ASSET);
        receivableAccount.setCode("AR-DEALER");
        receivableAccount.setName("Dealer Receivable");
        ReflectionTestUtils.setField(receivableAccount, "id", 10L);

        JournalEntry existingEntry = new JournalEntry();
        existingEntry.getLines().add(journalLine(
                existingEntry,
                account20,
                "Dealer settlement",
                new BigDecimal("40.00"),
                BigDecimal.ZERO
        ));
        existingEntry.getLines().add(journalLine(
                existingEntry,
                account21,
                "Dealer settlement",
                new BigDecimal("40.00"),
                BigDecimal.ZERO
        ));
        existingEntry.getLines().add(journalLine(
                existingEntry,
                account20,
                "Settlement discount",
                new BigDecimal("40.00"),
                BigDecimal.ZERO
        ));
        existingEntry.getLines().add(journalLine(
                existingEntry,
                receivableAccount,
                "Dealer settlement",
                BigDecimal.ZERO,
                new BigDecimal("120.00")
        ));
        existing.setJournalEntry(existingEntry);

        when(invoiceRepository.findByCompanyAndId(eq(company), eq(701L))).thenReturn(Optional.of(invoice));
        when(settlementAllocationRepository.findByCompanyAndInvoiceOrderByCreatedAtDesc(eq(company), eq(invoice)))
                .thenReturn(List.of(existing));
        when(settlementAllocationRepository.findByCompanyAndIdempotencyKeyIgnoreCaseOrderByCreatedAtAscIdAsc(
                eq(company), eq(legacyKey))).thenReturn(List.of(existing));

        String resolved = ReflectionTestUtils.invokeMethod(
                accountingService,
                "resolveDealerSettlementIdempotencyKey",
                company,
                replayRequest
        );

        assertThat(resolved).isEqualTo(canonicalReplayKey);
    }

    @Test
    void resolveDealerSettlementIdempotencyKey_rejectsLegacyReplayWhenDiscountLabelMemoMasksMissingPaymentSplit() {
        DealerSettlementRequest replayRequest = new DealerSettlementRequest(
                1L,
                null,
                20L,
                null,
                null,
                null,
                LocalDate.of(2024, 5, 1),
                null,
                "Settlement discount",
                null,
                Boolean.FALSE,
                List.of(new SettlementAllocationRequest(
                        701L,
                        null,
                        new BigDecimal("120.00"),
                        new BigDecimal("40.00"),
                        BigDecimal.ZERO,
                        BigDecimal.ZERO,
                        null
                )),
                List.of(
                        new SettlementPaymentRequest(20L, new BigDecimal("40.00"), "BANK"),
                        new SettlementPaymentRequest(20L, new BigDecimal("40.00"), "BANK")
                )
        );

        String canonicalReplayKey = ReflectionTestUtils.invokeMethod(
                accountingService,
                "buildDealerSettlementIdempotencyKey",
                replayRequest
        );

        Dealer dealer = new Dealer();
        ReflectionTestUtils.setField(dealer, "id", 1L);

        var invoice = new com.bigbrightpaints.erp.modules.invoice.domain.Invoice();
        invoice.setCompany(company);
        invoice.setDealer(dealer);
        ReflectionTestUtils.setField(invoice, "id", 701L);

        DealerSettlementRequest legacyRequest = new DealerSettlementRequest(
                1L,
                null,
                20L,
                null,
                null,
                null,
                LocalDate.of(2024, 5, 1),
                null,
                "Settlement discount",
                null,
                Boolean.FALSE,
                List.of(new SettlementAllocationRequest(
                        701L,
                        null,
                        new BigDecimal("120.00"),
                        new BigDecimal("40.00"),
                        BigDecimal.ZERO,
                        BigDecimal.ZERO,
                        null
                )),
                List.of(
                        new SettlementPaymentRequest(20L, new BigDecimal("40.00"), "BANK"),
                        new SettlementPaymentRequest(21L, new BigDecimal("40.00"), "BANK")
                )
        );
        String legacyKey = ReflectionTestUtils.invokeMethod(
                accountingService,
                "buildLegacyDealerSettlementIdempotencyKey",
                legacyRequest
        );
        assertThat(canonicalReplayKey).isNotEqualTo(legacyKey);

        var existing = new PartnerSettlementAllocation();
        existing.setCompany(company);
        existing.setDealer(dealer);
        existing.setInvoice(invoice);
        existing.setAllocationAmount(new BigDecimal("120.00"));
        existing.setDiscountAmount(new BigDecimal("40.00"));
        existing.setWriteOffAmount(BigDecimal.ZERO);
        existing.setFxDifferenceAmount(BigDecimal.ZERO);
        existing.setIdempotencyKey(legacyKey);

        Account account20 = new Account();
        account20.setCompany(company);
        account20.setType(AccountType.ASSET);
        account20.setCode("BANK-20");
        account20.setName("Bank 20");
        ReflectionTestUtils.setField(account20, "id", 20L);

        Account account21 = new Account();
        account21.setCompany(company);
        account21.setType(AccountType.ASSET);
        account21.setCode("BANK-21");
        account21.setName("Bank 21");
        ReflectionTestUtils.setField(account21, "id", 21L);

        Account receivableAccount = new Account();
        receivableAccount.setCompany(company);
        receivableAccount.setType(AccountType.ASSET);
        receivableAccount.setCode("AR-DEALER");
        receivableAccount.setName("Dealer Receivable");
        ReflectionTestUtils.setField(receivableAccount, "id", 10L);

        JournalEntry existingEntry = new JournalEntry();
        existingEntry.getLines().add(journalLine(
                existingEntry,
                account20,
                "Settlement discount",
                new BigDecimal("40.00"),
                BigDecimal.ZERO
        ));
        existingEntry.getLines().add(journalLine(
                existingEntry,
                account21,
                "Settlement discount",
                new BigDecimal("40.00"),
                BigDecimal.ZERO
        ));
        existingEntry.getLines().add(journalLine(
                existingEntry,
                account20,
                "Settlement discount",
                new BigDecimal("40.00"),
                BigDecimal.ZERO
        ));
        existingEntry.getLines().add(journalLine(
                existingEntry,
                receivableAccount,
                "Settlement discount",
                BigDecimal.ZERO,
                new BigDecimal("120.00")
        ));
        existing.setJournalEntry(existingEntry);

        when(invoiceRepository.findByCompanyAndId(eq(company), eq(701L))).thenReturn(Optional.of(invoice));
        when(settlementAllocationRepository.findByCompanyAndInvoiceOrderByCreatedAtDesc(eq(company), eq(invoice)))
                .thenReturn(List.of(existing));
        when(settlementAllocationRepository.findByCompanyAndIdempotencyKeyIgnoreCaseOrderByCreatedAtAscIdAsc(
                eq(company), eq(legacyKey))).thenReturn(List.of(existing));

        String resolved = ReflectionTestUtils.invokeMethod(
                accountingService,
                "resolveDealerSettlementIdempotencyKey",
                company,
                replayRequest
        );

        assertThat(resolved).isEqualTo(canonicalReplayKey);
    }

    @Test
    void resolveDealerSettlementIdempotencyKey_rejectsLegacyReplayWhenDiscountLineOnUnrequestedPaymentAccount() {
        DealerSettlementRequest replayRequest = new DealerSettlementRequest(
                1L,
                null,
                30L,
                null,
                null,
                null,
                LocalDate.of(2024, 5, 1),
                null,
                null,
                null,
                Boolean.FALSE,
                List.of(new SettlementAllocationRequest(
                        701L,
                        null,
                        new BigDecimal("120.00"),
                        new BigDecimal("40.00"),
                        BigDecimal.ZERO,
                        BigDecimal.ZERO,
                        null
                )),
                List.of(
                        new SettlementPaymentRequest(20L, new BigDecimal("40.00"), "BANK"),
                        new SettlementPaymentRequest(21L, new BigDecimal("40.00"), "BANK")
                )
        );

        String canonicalReplayKey = ReflectionTestUtils.invokeMethod(
                accountingService,
                "buildDealerSettlementIdempotencyKey",
                replayRequest
        );

        Dealer dealer = new Dealer();
        ReflectionTestUtils.setField(dealer, "id", 1L);

        var invoice = new com.bigbrightpaints.erp.modules.invoice.domain.Invoice();
        invoice.setCompany(company);
        invoice.setDealer(dealer);
        ReflectionTestUtils.setField(invoice, "id", 701L);

        DealerSettlementRequest legacyRequest = new DealerSettlementRequest(
                replayRequest.dealerId(),
                20L,
                replayRequest.discountAccountId(),
                replayRequest.writeOffAccountId(),
                replayRequest.fxGainAccountId(),
                replayRequest.fxLossAccountId(),
                replayRequest.settlementDate(),
                replayRequest.referenceNumber(),
                replayRequest.memo(),
                replayRequest.idempotencyKey(),
                replayRequest.adminOverride(),
                replayRequest.allocations(),
                null
        );
        String legacyKey = ReflectionTestUtils.invokeMethod(
                accountingService,
                "buildLegacyDealerSettlementIdempotencyKey",
                legacyRequest
        );
        assertThat(canonicalReplayKey).isNotEqualTo(legacyKey);

        var existing = new PartnerSettlementAllocation();
        existing.setCompany(company);
        existing.setDealer(dealer);
        existing.setInvoice(invoice);
        existing.setAllocationAmount(new BigDecimal("120.00"));
        existing.setDiscountAmount(new BigDecimal("40.00"));
        existing.setWriteOffAmount(BigDecimal.ZERO);
        existing.setFxDifferenceAmount(BigDecimal.ZERO);
        existing.setIdempotencyKey(legacyKey);

        Account account20 = new Account();
        account20.setCompany(company);
        account20.setType(AccountType.ASSET);
        account20.setCode("BANK-20");
        account20.setName("Bank 20");
        ReflectionTestUtils.setField(account20, "id", 20L);

        Account account21 = new Account();
        account21.setCompany(company);
        account21.setType(AccountType.ASSET);
        account21.setCode("BANK-21");
        account21.setName("Bank 21");
        ReflectionTestUtils.setField(account21, "id", 21L);

        Account receivableAccount = new Account();
        receivableAccount.setCompany(company);
        receivableAccount.setType(AccountType.ASSET);
        receivableAccount.setCode("AR-DEALER");
        receivableAccount.setName("Dealer Receivable");
        ReflectionTestUtils.setField(receivableAccount, "id", 10L);

        JournalEntry existingEntry = new JournalEntry();
        existingEntry.getLines().add(journalLine(
                existingEntry,
                account20,
                "Dealer settlement",
                new BigDecimal("40.00"),
                BigDecimal.ZERO
        ));
        existingEntry.getLines().add(journalLine(
                existingEntry,
                account21,
                "Dealer settlement",
                new BigDecimal("40.00"),
                BigDecimal.ZERO
        ));
        existingEntry.getLines().add(journalLine(
                existingEntry,
                account21,
                "Settlement discount",
                new BigDecimal("40.00"),
                BigDecimal.ZERO
        ));
        existingEntry.getLines().add(journalLine(
                existingEntry,
                receivableAccount,
                "Dealer settlement",
                BigDecimal.ZERO,
                new BigDecimal("120.00")
        ));
        existing.setJournalEntry(existingEntry);

        when(invoiceRepository.findByCompanyAndId(eq(company), eq(701L))).thenReturn(Optional.of(invoice));
        when(settlementAllocationRepository.findByCompanyAndInvoiceOrderByCreatedAtDesc(eq(company), eq(invoice)))
                .thenReturn(List.of(existing));
        when(settlementAllocationRepository.findByCompanyAndIdempotencyKeyIgnoreCaseOrderByCreatedAtAscIdAsc(
                eq(company), eq(legacyKey))).thenReturn(List.of(existing));

        String resolved = ReflectionTestUtils.invokeMethod(
                accountingService,
                "resolveDealerSettlementIdempotencyKey",
                company,
                replayRequest
        );

        assertThat(resolved).isEqualTo(canonicalReplayKey);
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
    void settleDealerInvoices_rejectsAllocationWithNegativeNetCashContribution() {
        Dealer dealer = new Dealer();
        dealer.setName("Dealer");
        Account receivable = new Account();
        receivable.setCompany(company);
        receivable.setCode("AR");
        receivable.setType(AccountType.ASSET);
        ReflectionTestUtils.setField(receivable, "id", 10L);
        dealer.setReceivableAccount(receivable);
        ReflectionTestUtils.setField(dealer, "id", 1L);

        when(dealerRepository.lockByCompanyAndId(eq(company), eq(1L))).thenReturn(Optional.of(dealer));

        DealerSettlementRequest request = new DealerSettlementRequest(
                1L,
                20L,
                21L,
                null,
                null,
                null,
                LocalDate.of(2024, 5, 1),
                "REF-NEG-CASH-DEALER",
                "Dealer settlement",
                "IDEMP-NEG-CASH-DEALER",
                Boolean.FALSE,
                List.of(
                        new SettlementAllocationRequest(
                                5L,
                                null,
                                new BigDecimal("100.00"),
                                new BigDecimal("120.00"),
                                BigDecimal.ZERO,
                                BigDecimal.ZERO,
                                "over-discounted allocation"
                        ),
                        new SettlementAllocationRequest(
                                6L,
                                null,
                                new BigDecimal("200.00"),
                                BigDecimal.ZERO,
                                BigDecimal.ZERO,
                                BigDecimal.ZERO,
                                "offsetting allocation"
                        )
                ),
                null
        );

        assertThatThrownBy(() -> accountingService.settleDealerInvoices(request))
                .isInstanceOf(ApplicationException.class)
                .hasMessageContaining("negative net cash contribution");
        verify(journalReferenceMappingRepository, never())
                .reserveReferenceMapping(any(), any(), any(), any(), any());
    }

    @Test
    void settleDealerInvoices_allowsToleranceBoundaryForNegativeNetCashContribution() {
        Dealer dealer = new Dealer();
        dealer.setName("Dealer");
        Account receivable = new Account();
        receivable.setCompany(company);
        receivable.setCode("AR");
        receivable.setType(AccountType.ASSET);
        ReflectionTestUtils.setField(receivable, "id", 10L);
        dealer.setReceivableAccount(receivable);
        ReflectionTestUtils.setField(dealer, "id", 1L);

        when(dealerRepository.lockByCompanyAndId(eq(company), eq(1L))).thenReturn(Optional.of(dealer));
        when(journalReferenceMappingRepository.findAllByCompanyAndLegacyReferenceIgnoreCase(
                eq(company), eq("idemp-neg-cash-dealer-tolerance")))
                .thenReturn(List.of());
        when(journalReferenceMappingRepository.reserveReferenceMapping(
                eq(company.getId()),
                eq("idemp-neg-cash-dealer-tolerance"),
                eq("REF-NEG-CASH-DEALER-TOLERANCE"),
                eq("DEALER_SETTLEMENT"),
                any()))
                .thenReturn(1);

        DealerSettlementRequest request = new DealerSettlementRequest(
                1L,
                20L,
                null,
                null,
                null,
                null,
                LocalDate.of(2024, 5, 1),
                "REF-NEG-CASH-DEALER-TOLERANCE",
                "Dealer settlement",
                "IDEMP-NEG-CASH-DEALER-TOLERANCE",
                Boolean.FALSE,
                List.of(
                        new SettlementAllocationRequest(
                                5L,
                                null,
                                new BigDecimal("100.00"),
                                new BigDecimal("100.01"),
                                BigDecimal.ZERO,
                                BigDecimal.ZERO,
                                "tolerance-boundary allocation"
                        )
                ),
                null
        );

        assertThatThrownBy(() -> accountingService.settleDealerInvoices(request))
                .isInstanceOf(ApplicationException.class)
                .hasMessageContaining("Discount account is required when a discount is applied")
                .satisfies(ex -> assertThat(ex).hasMessageNotContaining("negative net cash contribution"));
        verify(journalReferenceMappingRepository, times(1))
                .reserveReferenceMapping(any(), any(), any(), any(), any());
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
    void settleDealerInvoices_rejectsDuplicateInvoiceAllocationTargets() {
        Dealer dealer = new Dealer();
        dealer.setName("Dealer");
        Account arAccount = new Account();
        arAccount.setCompany(company);
        arAccount.setCode("AR");
        arAccount.setType(AccountType.ASSET);
        dealer.setReceivableAccount(arAccount);
        ReflectionTestUtils.setField(dealer, "id", 1L);
        ReflectionTestUtils.setField(arAccount, "id", 10L);

        when(dealerRepository.lockByCompanyAndId(eq(company), eq(1L))).thenReturn(Optional.of(dealer));

        DealerSettlementRequest request = new DealerSettlementRequest(
                1L,
                20L,
                null,
                null,
                null,
                null,
                LocalDate.of(2024, 5, 1),
                "REF-DUP-DEALER",
                "Dealer settlement",
                "IDEMP-DUP-DEALER",
                Boolean.FALSE,
                List.of(
                        new SettlementAllocationRequest(
                                5L,
                                null,
                                new BigDecimal("60.00"),
                                BigDecimal.ZERO,
                                BigDecimal.ZERO,
                                BigDecimal.ZERO,
                                "first slice"
                        ),
                        new SettlementAllocationRequest(
                                5L,
                                null,
                                new BigDecimal("40.00"),
                                BigDecimal.ZERO,
                                BigDecimal.ZERO,
                                BigDecimal.ZERO,
                                "duplicate slice"
                        )
                ),
                null
        );

        assertThatThrownBy(() -> accountingService.settleDealerInvoices(request))
                .isInstanceOf(ApplicationException.class)
                .hasMessageContaining("duplicate invoice allocations");
        verify(journalReferenceMappingRepository, never())
                .reserveReferenceMapping(any(), any(), any(), any(), any());
    }

    @Test
    void settleDealerInvoices_nonLeaderReplayAllowsInactiveCashAccountAndRepairsReferenceMapping() {
        Dealer dealer = new Dealer();
        dealer.setName("Replay Dealer");
        ReflectionTestUtils.setField(dealer, "id", 1L);

        Account receivable = new Account();
        receivable.setCompany(company);
        receivable.setCode("AR-REPLAY");
        receivable.setType(AccountType.ASSET);
        ReflectionTestUtils.setField(receivable, "id", 10L);
        dealer.setReceivableAccount(receivable);

        Account cash = new Account();
        cash.setCompany(company);
        cash.setCode("CASH-REPLAY");
        cash.setType(AccountType.ASSET);
        cash.setActive(false);
        ReflectionTestUtils.setField(cash, "id", 20L);

        var invoice = new com.bigbrightpaints.erp.modules.invoice.domain.Invoice();
        invoice.setCompany(company);
        invoice.setDealer(dealer);
        ReflectionTestUtils.setField(invoice, "id", 701L);

        JournalEntry existingEntry = new JournalEntry();
        ReflectionTestUtils.setField(existingEntry, "id", 901L);
        existingEntry.setDealer(dealer);
        existingEntry.setReferenceNumber("DR-SETTLE-REPLAY-1");
        existingEntry.setMemo("Dealer settlement replay");
        existingEntry.getLines().add(journalLine(existingEntry, cash, "Dealer settlement replay", new BigDecimal("100.00"), BigDecimal.ZERO));
        existingEntry.getLines().add(journalLine(existingEntry, receivable, "Dealer settlement replay", BigDecimal.ZERO, new BigDecimal("100.00")));

        PartnerSettlementAllocation existingRow = new PartnerSettlementAllocation();
        existingRow.setCompany(company);
        existingRow.setPartnerType(com.bigbrightpaints.erp.modules.accounting.domain.PartnerType.DEALER);
        existingRow.setDealer(dealer);
        existingRow.setInvoice(invoice);
        existingRow.setJournalEntry(existingEntry);
        existingRow.setSettlementDate(LocalDate.of(2024, 4, 9));
        existingRow.setAllocationAmount(new BigDecimal("100.00"));
        existingRow.setDiscountAmount(BigDecimal.ZERO);
        existingRow.setWriteOffAmount(BigDecimal.ZERO);
        existingRow.setFxDifferenceAmount(BigDecimal.ZERO);
        existingRow.setIdempotencyKey("IDEMP-DR-REPLAY");
        existingRow.setMemo(null);

        JournalReferenceMapping mapping = new JournalReferenceMapping();
        mapping.setCompany(company);
        mapping.setLegacyReference("idemp-dr-replay");
        mapping.setCanonicalReference("DR-SETTLE-REPLAY-1");
        mapping.setEntityType("DEALER_SETTLEMENT");
        mapping.setEntityId(null);

        when(dealerRepository.lockByCompanyAndId(eq(company), eq(1L))).thenReturn(Optional.of(dealer));
        when(companyEntityLookup.requireAccount(eq(company), eq(20L))).thenReturn(cash);
        when(journalReferenceMappingRepository.findAllByCompanyAndLegacyReferenceIgnoreCase(eq(company), eq("idemp-dr-replay")))
                .thenReturn(List.of(mapping));
        when(journalReferenceResolver.findExistingEntry(eq(company), eq("DR-SETTLE-REPLAY-1")))
                .thenReturn(Optional.of(existingEntry));
        when(settlementAllocationRepository.findByCompanyAndIdempotencyKeyIgnoreCaseOrderByCreatedAtAscIdAsc(eq(company), eq("IDEMP-DR-REPLAY")))
                .thenReturn(List.of(existingRow));

        DealerSettlementRequest request = new DealerSettlementRequest(
                1L,
                20L,
                null,
                null,
                null,
                null,
                LocalDate.of(2024, 4, 9),
                null,
                "Dealer settlement replay",
                "IDEMP-DR-REPLAY",
                Boolean.FALSE,
                List.of(new SettlementAllocationRequest(
                        701L,
                        null,
                        new BigDecimal("100.00"),
                        BigDecimal.ZERO,
                        BigDecimal.ZERO,
                        BigDecimal.ZERO,
                        null
                )),
                null
        );

        PartnerSettlementResponse response = accountingService.settleDealerInvoices(request);

        assertThat(response.journalEntry()).isNotNull();
        assertThat(response.journalEntry().id()).isEqualTo(901L);
        verify(journalReferenceMappingRepository).save(mapping);
        assertThat(mapping.getEntityId()).isEqualTo(901L);
        assertThat(mapping.getEntityType()).isEqualTo("DEALER_SETTLEMENT");
        assertThat(mapping.getCanonicalReference()).isEqualTo("DR-SETTLE-REPLAY-1");
    }

    @Test
    void settleDealerInvoices_nonLeaderReplayAllowsLegacyInvoiceDiscountAdjustments() {
        Dealer dealer = new Dealer();
        dealer.setName("Replay Dealer");
        ReflectionTestUtils.setField(dealer, "id", 1L);

        Account receivable = new Account();
        receivable.setCompany(company);
        receivable.setCode("AR-REPLAY");
        receivable.setType(AccountType.ASSET);
        ReflectionTestUtils.setField(receivable, "id", 10L);
        dealer.setReceivableAccount(receivable);

        Account cash = new Account();
        cash.setCompany(company);
        cash.setCode("CASH-REPLAY");
        cash.setType(AccountType.ASSET);
        ReflectionTestUtils.setField(cash, "id", 20L);

        Account discount = new Account();
        discount.setCompany(company);
        discount.setCode("DISC-REPLAY");
        discount.setType(AccountType.EXPENSE);
        ReflectionTestUtils.setField(discount, "id", 30L);

        var invoice = new com.bigbrightpaints.erp.modules.invoice.domain.Invoice();
        invoice.setCompany(company);
        invoice.setDealer(dealer);
        ReflectionTestUtils.setField(invoice, "id", 701L);

        JournalEntry existingEntry = new JournalEntry();
        ReflectionTestUtils.setField(existingEntry, "id", 904L);
        existingEntry.setDealer(dealer);
        existingEntry.setReferenceNumber("DR-SETTLE-REPLAY-ADJ-1");
        existingEntry.setMemo("Dealer settlement replay");
        existingEntry.getLines().addAll(List.of(
                journalLine(existingEntry, cash, "Dealer settlement replay", new BigDecimal("90.00"), BigDecimal.ZERO),
                journalLine(existingEntry, discount, "Settlement discount", new BigDecimal("10.00"), BigDecimal.ZERO),
                journalLine(existingEntry, receivable, "Dealer settlement replay", BigDecimal.ZERO, new BigDecimal("100.00"))
        ));

        PartnerSettlementAllocation existingRow = new PartnerSettlementAllocation();
        existingRow.setCompany(company);
        existingRow.setPartnerType(com.bigbrightpaints.erp.modules.accounting.domain.PartnerType.DEALER);
        existingRow.setDealer(dealer);
        existingRow.setInvoice(invoice);
        existingRow.setJournalEntry(existingEntry);
        existingRow.setSettlementDate(LocalDate.of(2024, 4, 9));
        existingRow.setAllocationAmount(new BigDecimal("100.00"));
        existingRow.setDiscountAmount(new BigDecimal("10.00"));
        existingRow.setWriteOffAmount(BigDecimal.ZERO);
        existingRow.setFxDifferenceAmount(BigDecimal.ZERO);
        existingRow.setIdempotencyKey("IDEMP-DR-REPLAY-ADJ");
        existingRow.setMemo("Legacy invoice discount");

        JournalReferenceMapping mapping = new JournalReferenceMapping();
        mapping.setCompany(company);
        mapping.setLegacyReference("idemp-dr-replay-adj");
        mapping.setCanonicalReference("DR-SETTLE-REPLAY-ADJ-1");
        mapping.setEntityType("DEALER_SETTLEMENT");
        mapping.setEntityId(null);

        when(dealerRepository.lockByCompanyAndId(eq(company), eq(1L))).thenReturn(Optional.of(dealer));
        when(companyEntityLookup.requireAccount(eq(company), eq(20L))).thenReturn(cash);
        when(companyEntityLookup.requireAccount(eq(company), eq(30L))).thenReturn(discount);
        when(journalReferenceMappingRepository.findAllByCompanyAndLegacyReferenceIgnoreCase(eq(company), eq("idemp-dr-replay-adj")))
                .thenReturn(List.of(mapping));
        when(journalReferenceResolver.findExistingEntry(eq(company), eq("DR-SETTLE-REPLAY-ADJ-1")))
                .thenReturn(Optional.of(existingEntry));
        when(settlementAllocationRepository.findByCompanyAndIdempotencyKeyIgnoreCaseOrderByCreatedAtAscIdAsc(eq(company), eq("IDEMP-DR-REPLAY-ADJ")))
                .thenReturn(List.of(existingRow));

        DealerSettlementRequest request = new DealerSettlementRequest(
                1L,
                20L,
                30L,
                null,
                null,
                null,
                LocalDate.of(2024, 4, 9),
                null,
                "Dealer settlement replay",
                "IDEMP-DR-REPLAY-ADJ",
                Boolean.FALSE,
                List.of(new SettlementAllocationRequest(
                        701L,
                        null,
                        new BigDecimal("100.00"),
                        new BigDecimal("10.00"),
                        BigDecimal.ZERO,
                        BigDecimal.ZERO,
                        "Legacy invoice discount"
                )),
                null
        );

        PartnerSettlementResponse response = accountingService.settleDealerInvoices(request);

        assertThat(response.journalEntry()).isNotNull();
        assertThat(response.journalEntry().id()).isEqualTo(904L);
        assertThat(response.totalApplied()).isEqualByComparingTo("100.00");
        assertThat(response.totalDiscount()).isEqualByComparingTo("10.00");
        verify(journalReferenceMappingRepository).save(mapping);
        verify(journalReferenceMappingRepository, never())
                .reserveReferenceMapping(any(), any(), any(), any(), any());
        verify(journalEntryRepository, never()).save(any());
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
    void settleSupplierInvoices_rejectsOnAccountAllocationWithAdjustments() {
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
        when(companyEntityLookup.requireAccount(eq(company), eq(20L))).thenReturn(cash);

        SettlementAllocationRequest allocation = new SettlementAllocationRequest(
                null,
                null,
                new BigDecimal("100.00"),
                new BigDecimal("10.00"),
                BigDecimal.ZERO,
                BigDecimal.ZERO,
                "Invalid on-account discount"
        );

        SupplierSettlementRequest request = new SupplierSettlementRequest(
                1L,
                20L,
                21L,
                null,
                null,
                null,
                LocalDate.of(2024, 4, 9),
                "REF-AP-ONACC-INVALID",
                "Supplier settlement",
                "IDEMP-AP-ONACC-INVALID",
                Boolean.FALSE,
                List.of(allocation)
        );

        assertThatThrownBy(() -> service.settleSupplierInvoices(request))
                .isInstanceOf(ApplicationException.class)
                .hasMessageContaining("On-account supplier settlement allocations cannot include discount/write-off/FX adjustments");
        verify(service, never()).createJournalEntry(any(JournalEntryRequest.class));
        verify(journalReferenceMappingRepository, never())
                .reserveReferenceMapping(any(), any(), any(), any(), any());
    }

    @Test
    void settleSupplierInvoices_rejectsDuplicatePurchaseAllocationTargets() {
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
        when(companyEntityLookup.requireAccount(eq(company), eq(20L))).thenReturn(cash);

        SupplierSettlementRequest request = new SupplierSettlementRequest(
                1L,
                20L,
                21L,
                null,
                null,
                null,
                LocalDate.of(2024, 5, 1),
                "REF-DUP-SUPPLIER",
                "Supplier settlement",
                "IDEMP-DUP-SUPPLIER",
                Boolean.FALSE,
                List.of(
                        new SettlementAllocationRequest(
                                null,
                                2L,
                                new BigDecimal("100.00"),
                                BigDecimal.ZERO,
                                BigDecimal.ZERO,
                                BigDecimal.ZERO,
                                "first allocation"
                        ),
                        new SettlementAllocationRequest(
                                null,
                                2L,
                                new BigDecimal("25.00"),
                                BigDecimal.ZERO,
                                BigDecimal.ZERO,
                                BigDecimal.ZERO,
                                "duplicate allocation"
                        )
                )
        );

        assertThatThrownBy(() -> accountingService.settleSupplierInvoices(request))
                .isInstanceOf(ApplicationException.class)
                .hasMessageContaining("duplicate purchase allocations");
        verify(journalReferenceMappingRepository, never())
                .reserveReferenceMapping(any(), any(), any(), any(), any());
    }

    @Test
    void settleSupplierInvoices_rejectsAllocationWithNegativeNetCashContribution() {
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
        when(companyEntityLookup.requireAccount(eq(company), eq(20L))).thenReturn(cash);

        SupplierSettlementRequest request = new SupplierSettlementRequest(
                1L,
                20L,
                21L,
                null,
                null,
                null,
                LocalDate.of(2024, 5, 1),
                "REF-NEG-CASH-SUPPLIER",
                "Supplier settlement",
                "IDEMP-NEG-CASH-SUPPLIER",
                Boolean.FALSE,
                List.of(
                        new SettlementAllocationRequest(
                                null,
                                2L,
                                new BigDecimal("100.00"),
                                new BigDecimal("120.00"),
                                BigDecimal.ZERO,
                                BigDecimal.ZERO,
                                "over-discounted allocation"
                        ),
                        new SettlementAllocationRequest(
                                null,
                                3L,
                                new BigDecimal("200.00"),
                                BigDecimal.ZERO,
                                BigDecimal.ZERO,
                                BigDecimal.ZERO,
                                "offsetting allocation"
                        )
                )
        );

        assertThatThrownBy(() -> accountingService.settleSupplierInvoices(request))
                .isInstanceOf(ApplicationException.class)
                .hasMessageContaining("negative net cash contribution");
        verify(journalReferenceMappingRepository, never())
                .reserveReferenceMapping(any(), any(), any(), any(), any());
    }

    @Test
    void settleSupplierInvoices_allowsToleranceBoundaryForNegativeNetCashContribution() {
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
        when(companyEntityLookup.requireAccount(eq(company), eq(20L))).thenReturn(cash);
        when(journalReferenceMappingRepository.findAllByCompanyAndLegacyReferenceIgnoreCase(
                eq(company), eq("idemp-neg-cash-supplier-tolerance")))
                .thenReturn(List.of());
        when(journalReferenceMappingRepository.reserveReferenceMapping(
                eq(company.getId()),
                eq("idemp-neg-cash-supplier-tolerance"),
                eq("REF-NEG-CASH-SUPPLIER-TOLERANCE"),
                eq("SUPPLIER_SETTLEMENT"),
                any()))
                .thenReturn(1);

        SupplierSettlementRequest request = new SupplierSettlementRequest(
                1L,
                20L,
                null,
                null,
                null,
                null,
                LocalDate.of(2024, 5, 1),
                "REF-NEG-CASH-SUPPLIER-TOLERANCE",
                "Supplier settlement",
                "IDEMP-NEG-CASH-SUPPLIER-TOLERANCE",
                Boolean.FALSE,
                List.of(
                        new SettlementAllocationRequest(
                                null,
                                2L,
                                new BigDecimal("100.00"),
                                new BigDecimal("100.01"),
                                BigDecimal.ZERO,
                                BigDecimal.ZERO,
                                "tolerance-boundary allocation"
                        )
                )
        );

        assertThatThrownBy(() -> accountingService.settleSupplierInvoices(request))
                .isInstanceOf(ApplicationException.class)
                .hasMessageContaining("Discount account is required when a discount is applied")
                .satisfies(ex -> assertThat(ex).hasMessageNotContaining("negative net cash contribution"));
        verify(journalReferenceMappingRepository, times(1))
                .reserveReferenceMapping(any(), any(), any(), any(), any());
    }

    @Test
    void settleSupplierInvoices_nonLeaderReplayAllowsInactiveCashAccountAndRepairsReferenceMapping() {
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
        cash.setActive(false);
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
    void settleSupplierInvoices_nonLeaderReplayAllowsLegacyOnAccountAdjustments() {
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

        Account discount = new Account();
        discount.setCompany(company);
        discount.setCode("DISC-REPLAY");
        discount.setType(AccountType.REVENUE);
        ReflectionTestUtils.setField(discount, "id", 30L);

        JournalEntry existingEntry = new JournalEntry();
        ReflectionTestUtils.setField(existingEntry, "id", 904L);
        existingEntry.setSupplier(supplier);
        existingEntry.setReferenceNumber("SUP-SETTLE-REPLAY-ADJ-1");
        existingEntry.setMemo("Supplier settlement replay");
        existingEntry.getLines().addAll(List.of(
                journalLine(existingEntry, payable, "Supplier settlement replay", new BigDecimal("100.00"), BigDecimal.ZERO),
                journalLine(existingEntry, cash, "Supplier settlement replay", BigDecimal.ZERO, new BigDecimal("90.00")),
                journalLine(existingEntry, discount, "Settlement discount received", BigDecimal.ZERO, new BigDecimal("10.00"))
        ));

        com.bigbrightpaints.erp.modules.accounting.domain.PartnerSettlementAllocation existingRow =
                new com.bigbrightpaints.erp.modules.accounting.domain.PartnerSettlementAllocation();
        existingRow.setCompany(company);
        existingRow.setPartnerType(com.bigbrightpaints.erp.modules.accounting.domain.PartnerType.SUPPLIER);
        existingRow.setSupplier(supplier);
        existingRow.setJournalEntry(existingEntry);
        existingRow.setSettlementDate(LocalDate.of(2024, 4, 9));
        existingRow.setAllocationAmount(new BigDecimal("100.00"));
        existingRow.setDiscountAmount(new BigDecimal("10.00"));
        existingRow.setWriteOffAmount(BigDecimal.ZERO);
        existingRow.setFxDifferenceAmount(BigDecimal.ZERO);
        existingRow.setIdempotencyKey("IDEMP-AP-REPLAY-ADJ");
        existingRow.setMemo("Legacy on-account discount");

        JournalReferenceMapping mapping = new JournalReferenceMapping();
        mapping.setCompany(company);
        mapping.setLegacyReference("idemp-ap-replay-adj");
        mapping.setCanonicalReference("SUP-SETTLE-REPLAY-ADJ-1");
        mapping.setEntityType("SUPPLIER_SETTLEMENT");
        mapping.setEntityId(null);

        when(supplierRepository.lockByCompanyAndId(eq(company), eq(1L))).thenReturn(Optional.of(supplier));
        when(companyEntityLookup.requireAccount(eq(company), eq(20L))).thenReturn(cash);
        when(companyEntityLookup.requireAccount(eq(company), eq(30L))).thenReturn(discount);
        when(journalReferenceMappingRepository.findAllByCompanyAndLegacyReferenceIgnoreCase(eq(company), eq("idemp-ap-replay-adj")))
                .thenReturn(List.of(mapping));
        when(journalReferenceResolver.findExistingEntry(eq(company), eq("SUP-SETTLE-REPLAY-ADJ-1")))
                .thenReturn(Optional.of(existingEntry));
        when(settlementAllocationRepository.findByCompanyAndIdempotencyKeyIgnoreCaseOrderByCreatedAtAscIdAsc(eq(company), eq("IDEMP-AP-REPLAY-ADJ")))
                .thenReturn(List.of(existingRow));

        SettlementAllocationRequest allocation = new SettlementAllocationRequest(
                null,
                null,
                new BigDecimal("100.00"),
                new BigDecimal("10.00"),
                BigDecimal.ZERO,
                BigDecimal.ZERO,
                "Legacy on-account discount"
        );

        SupplierSettlementRequest request = new SupplierSettlementRequest(
                1L,
                20L,
                30L,
                null,
                null,
                null,
                LocalDate.of(2024, 4, 9),
                null,
                "Supplier settlement replay",
                "IDEMP-AP-REPLAY-ADJ",
                Boolean.FALSE,
                List.of(allocation)
        );

        PartnerSettlementResponse response = accountingService.settleSupplierInvoices(request);

        assertThat(response.journalEntry()).isNotNull();
        assertThat(response.journalEntry().id()).isEqualTo(904L);
        assertThat(response.totalApplied()).isEqualByComparingTo("100.00");
        assertThat(response.totalDiscount()).isEqualByComparingTo("10.00");
        verify(journalReferenceMappingRepository).save(mapping);
        verify(journalReferenceMappingRepository, never())
                .reserveReferenceMapping(any(), any(), any(), any(), any());
        verify(journalEntryRepository, never()).save(any());
    }

    @Test
    void settleSupplierInvoices_replayRejectsMappingAllocationJournalMismatch() {
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

        JournalEntry mappingEntry = new JournalEntry();
        ReflectionTestUtils.setField(mappingEntry, "id", 901L);
        mappingEntry.setSupplier(supplier);
        mappingEntry.setReferenceNumber("SUP-SETTLE-MAP-1");

        JournalEntry allocationEntry = new JournalEntry();
        ReflectionTestUtils.setField(allocationEntry, "id", 902L);
        allocationEntry.setSupplier(supplier);
        allocationEntry.setReferenceNumber("SUP-SETTLE-ALLOC-1");

        com.bigbrightpaints.erp.modules.accounting.domain.PartnerSettlementAllocation existingRow =
                new com.bigbrightpaints.erp.modules.accounting.domain.PartnerSettlementAllocation();
        existingRow.setCompany(company);
        existingRow.setPartnerType(com.bigbrightpaints.erp.modules.accounting.domain.PartnerType.SUPPLIER);
        existingRow.setSupplier(supplier);
        existingRow.setJournalEntry(allocationEntry);
        existingRow.setSettlementDate(LocalDate.of(2024, 4, 9));
        existingRow.setAllocationAmount(new BigDecimal("100.00"));
        existingRow.setDiscountAmount(BigDecimal.ZERO);
        existingRow.setWriteOffAmount(BigDecimal.ZERO);
        existingRow.setFxDifferenceAmount(BigDecimal.ZERO);
        existingRow.setIdempotencyKey("IDEMP-AP-MISMATCH");
        existingRow.setMemo(null);

        JournalReferenceMapping mapping = new JournalReferenceMapping();
        mapping.setCompany(company);
        mapping.setLegacyReference("idemp-ap-mismatch");
        mapping.setCanonicalReference("SUP-SETTLE-MAP-1");
        mapping.setEntityType("SUPPLIER_SETTLEMENT");
        mapping.setEntityId(null);

        when(supplierRepository.lockByCompanyAndId(eq(company), eq(1L))).thenReturn(Optional.of(supplier));
        when(companyEntityLookup.requireAccount(eq(company), eq(20L))).thenReturn(cash);
        when(journalReferenceMappingRepository.findAllByCompanyAndLegacyReferenceIgnoreCase(eq(company), eq("idemp-ap-mismatch")))
                .thenReturn(List.of(mapping));
        when(journalReferenceResolver.findExistingEntry(eq(company), eq("SUP-SETTLE-MAP-1")))
                .thenReturn(Optional.of(mappingEntry));
        when(settlementAllocationRepository.findByCompanyAndIdempotencyKeyIgnoreCaseOrderByCreatedAtAscIdAsc(eq(company), eq("IDEMP-AP-MISMATCH")))
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
                "IDEMP-AP-MISMATCH",
                Boolean.FALSE,
                List.of(allocation)
        );

        assertThatThrownBy(() -> accountingService.settleSupplierInvoices(request))
                .isInstanceOf(ApplicationException.class)
                .hasMessageContaining("different journal than settled allocations");
    }

    @Test
    void settleSupplierInvoices_replayRejectsMappingAllocationJournalMismatchOnLeaderFastPath() {
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

        JournalEntry mappingEntry = new JournalEntry();
        ReflectionTestUtils.setField(mappingEntry, "id", 1901L);
        mappingEntry.setSupplier(supplier);
        mappingEntry.setReferenceNumber("SUP-SETTLE-MAP-LEADER-1");

        JournalEntry allocationEntry = new JournalEntry();
        ReflectionTestUtils.setField(allocationEntry, "id", 1902L);
        allocationEntry.setSupplier(supplier);
        allocationEntry.setReferenceNumber("SUP-SETTLE-ALLOC-LEADER-1");

        com.bigbrightpaints.erp.modules.accounting.domain.PartnerSettlementAllocation existingRow =
                new com.bigbrightpaints.erp.modules.accounting.domain.PartnerSettlementAllocation();
        existingRow.setCompany(company);
        existingRow.setPartnerType(com.bigbrightpaints.erp.modules.accounting.domain.PartnerType.SUPPLIER);
        existingRow.setSupplier(supplier);
        existingRow.setJournalEntry(allocationEntry);
        existingRow.setSettlementDate(LocalDate.of(2024, 4, 9));
        existingRow.setAllocationAmount(new BigDecimal("100.00"));
        existingRow.setDiscountAmount(BigDecimal.ZERO);
        existingRow.setWriteOffAmount(BigDecimal.ZERO);
        existingRow.setFxDifferenceAmount(BigDecimal.ZERO);
        existingRow.setIdempotencyKey("IDEMP-AP-MISMATCH-LEADER");
        existingRow.setMemo(null);

        when(supplierRepository.lockByCompanyAndId(eq(company), eq(1L))).thenReturn(Optional.of(supplier));
        when(companyEntityLookup.requireAccount(eq(company), eq(20L))).thenReturn(cash);
        when(journalReferenceMappingRepository.findAllByCompanyAndLegacyReferenceIgnoreCase(
                eq(company), eq("idemp-ap-mismatch-leader")))
                .thenReturn(List.of());
        when(journalReferenceResolver.findExistingEntry(eq(company), eq("SUP-SETTLE-MAP-LEADER-1")))
                .thenReturn(Optional.of(mappingEntry));
        when(settlementAllocationRepository.findByCompanyAndIdempotencyKeyIgnoreCaseOrderByCreatedAtAscIdAsc(
                eq(company), eq("IDEMP-AP-MISMATCH-LEADER")))
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
                "SUP-SETTLE-MAP-LEADER-1",
                "Supplier settlement replay",
                "IDEMP-AP-MISMATCH-LEADER",
                Boolean.FALSE,
                List.of(allocation)
        );

        assertThatThrownBy(() -> accountingService.settleSupplierInvoices(request))
                .isInstanceOf(ApplicationException.class)
                .hasMessageContaining("different journal than settled allocations");
    }

    @Test
    void settleSupplierInvoices_nonLeaderReplayMissingAllocationsIncludesPartnerDetails() {
        Supplier supplier = new Supplier();
        supplier.setName("Supplier");
        ReflectionTestUtils.setField(supplier, "id", 1L);

        Account payable = new Account();
        payable.setCompany(company);
        payable.setCode("AP-RACE");
        payable.setType(AccountType.LIABILITY);
        ReflectionTestUtils.setField(payable, "id", 10L);
        supplier.setPayableAccount(payable);

        Account cash = new Account();
        cash.setCompany(company);
        cash.setCode("CASH-RACE");
        cash.setType(AccountType.ASSET);
        ReflectionTestUtils.setField(cash, "id", 20L);

        JournalEntry existingEntry = new JournalEntry();
        ReflectionTestUtils.setField(existingEntry, "id", 992L);
        existingEntry.setSupplier(supplier);
        existingEntry.setReferenceNumber("SUP-SETTLE-RACE-1");

        JournalReferenceMapping mapping = new JournalReferenceMapping();
        mapping.setCompany(company);
        mapping.setLegacyReference("idemp-ap-race-miss");
        mapping.setCanonicalReference("SUP-SETTLE-RACE-1");
        mapping.setEntityType("SUPPLIER_SETTLEMENT");
        mapping.setEntityId(null);

        when(supplierRepository.lockByCompanyAndId(eq(company), eq(1L))).thenReturn(Optional.of(supplier));
        when(companyEntityLookup.requireAccount(eq(company), eq(20L))).thenReturn(cash);
        when(journalReferenceMappingRepository.findAllByCompanyAndLegacyReferenceIgnoreCase(eq(company), eq("idemp-ap-race-miss")))
                .thenReturn(List.of(mapping));
        when(settlementAllocationRepository.findByCompanyAndIdempotencyKey(eq(company), eq("IDEMP-AP-RACE-MISS")))
                .thenReturn(List.of());
        when(journalReferenceResolver.findExistingEntry(eq(company), eq("SUP-SETTLE-RACE-1")))
                .thenReturn(Optional.of(existingEntry));
        when(settlementAllocationRepository.findByCompanyAndIdempotencyKeyIgnoreCaseOrderByCreatedAtAscIdAsc(
                eq(company), eq("IDEMP-AP-RACE-MISS")))
                .thenReturn(List.of());

        SupplierSettlementRequest request = new SupplierSettlementRequest(
                1L,
                20L,
                null,
                null,
                null,
                null,
                LocalDate.of(2024, 4, 9),
                "SUP-SETTLE-RACE-1",
                "Supplier settlement replay race",
                "IDEMP-AP-RACE-MISS",
                Boolean.FALSE,
                List.of(new SettlementAllocationRequest(
                        null,
                        null,
                        new BigDecimal("100.00"),
                        BigDecimal.ZERO,
                        BigDecimal.ZERO,
                        BigDecimal.ZERO,
                        null
                ))
        );

        assertThatThrownBy(() -> accountingService.settleSupplierInvoices(request))
                .isInstanceOfSatisfying(ApplicationException.class, ex -> {
                    assertThat(ex.getErrorCode()).isEqualTo(ErrorCode.INTERNAL_CONCURRENCY_FAILURE);
                    assertPartnerReplayDetails(ex, "IDEMP-AP-RACE-MISS", "SUPPLIER", 1L);
                })
                .hasMessageContaining("Supplier settlement idempotency key is reserved but allocation not found");
    }

    @Test
    void settleSupplierInvoices_replayPartnerMismatchIncludesPartnerDetails() {
        Supplier supplier = new Supplier();
        supplier.setName("Supplier");
        ReflectionTestUtils.setField(supplier, "id", 1L);

        Supplier otherSupplier = new Supplier();
        otherSupplier.setName("Other Supplier");
        ReflectionTestUtils.setField(otherSupplier, "id", 2L);

        Account payable = new Account();
        payable.setCompany(company);
        payable.setCode("AP-RACE");
        payable.setType(AccountType.LIABILITY);
        ReflectionTestUtils.setField(payable, "id", 10L);
        supplier.setPayableAccount(payable);

        Account cash = new Account();
        cash.setCompany(company);
        cash.setCode("CASH-RACE");
        cash.setType(AccountType.ASSET);
        ReflectionTestUtils.setField(cash, "id", 20L);

        JournalEntry existingEntry = new JournalEntry();
        ReflectionTestUtils.setField(existingEntry, "id", 994L);
        existingEntry.setSupplier(otherSupplier);
        existingEntry.setReferenceNumber("SUP-SETTLE-RACE-PARTNER-1");

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
        existingRow.setIdempotencyKey("IDEMP-AP-RACE-PARTNER");

        JournalReferenceMapping mapping = new JournalReferenceMapping();
        mapping.setCompany(company);
        mapping.setLegacyReference("idemp-ap-race-partner");
        mapping.setCanonicalReference("SUP-SETTLE-RACE-PARTNER-1");
        mapping.setEntityType("SUPPLIER_SETTLEMENT");
        mapping.setEntityId(null);

        when(supplierRepository.lockByCompanyAndId(eq(company), eq(1L))).thenReturn(Optional.of(supplier));
        when(companyEntityLookup.requireAccount(eq(company), eq(20L))).thenReturn(cash);
        when(journalReferenceMappingRepository.findAllByCompanyAndLegacyReferenceIgnoreCase(eq(company), eq("idemp-ap-race-partner")))
                .thenReturn(List.of(mapping));
        when(journalReferenceResolver.findExistingEntry(eq(company), eq("SUP-SETTLE-RACE-PARTNER-1")))
                .thenReturn(Optional.of(existingEntry));
        when(settlementAllocationRepository.findByCompanyAndIdempotencyKeyIgnoreCaseOrderByCreatedAtAscIdAsc(
                eq(company), eq("IDEMP-AP-RACE-PARTNER")))
                .thenReturn(List.of(existingRow));

        SupplierSettlementRequest request = new SupplierSettlementRequest(
                1L,
                20L,
                null,
                null,
                null,
                null,
                LocalDate.of(2024, 4, 9),
                "SUP-SETTLE-RACE-PARTNER-1",
                "Supplier settlement replay partner mismatch",
                "IDEMP-AP-RACE-PARTNER",
                Boolean.FALSE,
                List.of(new SettlementAllocationRequest(
                        null,
                        null,
                        new BigDecimal("100.00"),
                        BigDecimal.ZERO,
                        BigDecimal.ZERO,
                        BigDecimal.ZERO,
                        null
                ))
        );

        assertThatThrownBy(() -> accountingService.settleSupplierInvoices(request))
                .isInstanceOfSatisfying(ApplicationException.class, ex -> {
                    assertThat(ex.getErrorCode()).isEqualTo(ErrorCode.CONCURRENCY_CONFLICT);
                    assertPartnerReplayDetails(ex, "IDEMP-AP-RACE-PARTNER", "SUPPLIER", 1L);
                })
                .hasMessageContaining("another supplier");
    }

    @Test
    void settleSupplierInvoices_rejectsInactiveCashAccount() {
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

        Account inactiveCash = new Account();
        inactiveCash.setCompany(company);
        inactiveCash.setCode("BANK-INACTIVE");
        inactiveCash.setType(AccountType.ASSET);
        inactiveCash.setActive(false);
        ReflectionTestUtils.setField(inactiveCash, "id", 20L);

        when(supplierRepository.lockByCompanyAndId(eq(company), eq(1L))).thenReturn(Optional.of(supplier));
        when(companyEntityLookup.requireAccount(eq(company), eq(20L))).thenReturn(inactiveCash);

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
                "REF-AP-INACTIVE",
                "Supplier settlement",
                "IDEMP-AP-INACTIVE",
                Boolean.FALSE,
                List.of(allocation)
        );

        assertThatThrownBy(() -> service.settleSupplierInvoices(request))
                .isInstanceOf(ApplicationException.class)
                .hasMessageContaining("must be active");
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

    @Test
    void partnerMismatchMessage_fallbackUsesPartnerTypeWording() {
        String dealerMessage = ReflectionTestUtils.invokeMethod(
                accountingService,
                "partnerMismatchMessage",
                com.bigbrightpaints.erp.modules.accounting.domain.PartnerType.DEALER);
        String supplierMessage = ReflectionTestUtils.invokeMethod(
                accountingService,
                "partnerMismatchMessage",
                com.bigbrightpaints.erp.modules.accounting.domain.PartnerType.SUPPLIER);
        String message = ReflectionTestUtils.invokeMethod(
                accountingService,
                "partnerMismatchMessage",
                (com.bigbrightpaints.erp.modules.accounting.domain.PartnerType) null);

        assertThat(dealerMessage).isEqualTo("Idempotency key already used for another dealer");
        assertThat(supplierMessage).isEqualTo("Idempotency key already used for another supplier");
        assertThat(message).isEqualTo("Idempotency key already used for another partner type");
    }

    @Test
    void ensureDuplicateMatchesExisting_partnerMismatchesExposeCanonicalPartnerTypes() {
        JournalEntry existing = new JournalEntry();
        existing.setReferenceNumber("REF-DUP-1");
        existing.setEntryDate(LocalDate.of(2024, 4, 1));
        existing.setCurrency("INR");
        existing.setFxRate(BigDecimal.ONE);
        existing.setMemo("Duplicate guard");

        Dealer existingDealer = new Dealer();
        ReflectionTestUtils.setField(existingDealer, "id", 10L);
        existing.setDealer(existingDealer);
        Supplier existingSupplier = new Supplier();
        ReflectionTestUtils.setField(existingSupplier, "id", 20L);
        existing.setSupplier(existingSupplier);

        JournalEntry candidate = new JournalEntry();
        candidate.setEntryDate(LocalDate.of(2024, 4, 1));
        candidate.setCurrency("INR");
        candidate.setFxRate(BigDecimal.ONE);
        candidate.setMemo("Duplicate guard");

        Dealer candidateDealer = new Dealer();
        ReflectionTestUtils.setField(candidateDealer, "id", 11L);
        candidate.setDealer(candidateDealer);
        Supplier candidateSupplier = new Supplier();
        ReflectionTestUtils.setField(candidateSupplier, "id", 21L);
        candidate.setSupplier(candidateSupplier);

        assertThatThrownBy(() -> ReflectionTestUtils.invokeMethod(
                accountingService,
                "ensureDuplicateMatchesExisting",
                existing,
                candidate,
                List.of()))
                .isInstanceOf(ApplicationException.class)
                .satisfies(ex -> {
                    ApplicationException appEx = (ApplicationException) ex;
                    assertThat(appEx.getErrorCode()).isEqualTo(ErrorCode.BUSINESS_DUPLICATE_ENTRY);
                    assertThat(appEx.getDetails())
                            .containsEntry("partnerMismatchTypes", List.of("DEALER", "SUPPLIER"));
                });
    }

    private void assertPartnerReplayDetails(ApplicationException ex,
                                            String idempotencyKey,
                                            String partnerType,
                                            Long partnerId) {
        assertThat(ex.getDetails())
                .containsEntry(IntegrationFailureMetadataSchema.KEY_IDEMPOTENCY_KEY, idempotencyKey)
                .containsEntry(IntegrationFailureMetadataSchema.KEY_PARTNER_TYPE, partnerType)
                .containsEntry(IntegrationFailureMetadataSchema.KEY_PARTNER_ID, partnerId);
    }

    private void assertAllocationReplayDiagnostics(ApplicationException ex,
                                                   int existingCount,
                                                   int requestCount,
                                                   String existingAppliedMarker,
                                                   String requestAppliedMarker) {
        assertThat(ex.getDetails())
                .containsEntry("existingAllocationCount", existingCount)
                .containsEntry("requestAllocationCount", requestCount)
                .containsKey("existingAllocationSignatureDigest")
                .containsKey("requestAllocationSignatureDigest");
        assertThat(String.valueOf(ex.getDetails().get("existingAllocationSignatureDigest")))
                .contains(existingAppliedMarker);
        assertThat(String.valueOf(ex.getDetails().get("requestAllocationSignatureDigest")))
                .contains(requestAppliedMarker);
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
