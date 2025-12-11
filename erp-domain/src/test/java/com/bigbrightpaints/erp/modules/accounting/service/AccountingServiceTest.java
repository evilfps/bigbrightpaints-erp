package com.bigbrightpaints.erp.modules.accounting.service;

import com.bigbrightpaints.erp.core.exception.ApplicationException;
import com.bigbrightpaints.erp.core.exception.ErrorCode;
import com.bigbrightpaints.erp.core.util.CompanyClock;
import com.bigbrightpaints.erp.core.util.CompanyEntityLookup;
import com.bigbrightpaints.erp.modules.accounting.domain.AccountRepository;
import com.bigbrightpaints.erp.modules.accounting.domain.AccountingPeriod;
import com.bigbrightpaints.erp.modules.accounting.domain.AccountingPeriodRepository;
import com.bigbrightpaints.erp.modules.accounting.domain.JournalEntryRepository;
import com.bigbrightpaints.erp.modules.accounting.domain.JournalLineRepository;
import com.bigbrightpaints.erp.modules.accounting.dto.DealerSettlementRequest;
import com.bigbrightpaints.erp.modules.accounting.dto.JournalEntryDto;
import com.bigbrightpaints.erp.modules.accounting.dto.JournalEntryRequest;
import com.bigbrightpaints.erp.modules.accounting.dto.SettlementAllocationRequest;
import com.bigbrightpaints.erp.modules.accounting.dto.SettlementPaymentRequest;
import com.bigbrightpaints.erp.modules.company.domain.Company;
import com.bigbrightpaints.erp.modules.company.service.CompanyContextService;
import com.bigbrightpaints.erp.modules.hr.domain.PayrollRunLineRepository;
import com.bigbrightpaints.erp.modules.hr.domain.PayrollRunRepository;
import com.bigbrightpaints.erp.modules.invoice.domain.InvoiceRepository;
import com.bigbrightpaints.erp.modules.inventory.domain.FinishedGoodBatchRepository;
import com.bigbrightpaints.erp.modules.inventory.domain.RawMaterialBatchRepository;
import com.bigbrightpaints.erp.modules.inventory.domain.RawMaterialMovementRepository;
import com.bigbrightpaints.erp.modules.purchasing.domain.RawMaterialPurchaseRepository;
import com.bigbrightpaints.erp.modules.purchasing.domain.SupplierRepository;
import com.bigbrightpaints.erp.modules.sales.domain.Dealer;
import com.bigbrightpaints.erp.modules.sales.domain.DealerRepository;
import com.bigbrightpaints.erp.modules.accounting.domain.PartnerSettlementAllocationRepository;
import com.bigbrightpaints.erp.modules.invoice.service.InvoiceSettlementPolicy;
import com.bigbrightpaints.erp.core.audit.AuditService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
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
import static org.mockito.Mockito.lenient;
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
        when(companyContextService.requireCurrentCompany()).thenReturn(company);
        lenient().when(systemSettingsService.isPeriodLockEnforced()).thenReturn(true);
        lenient().when(accountingPeriodService.requireOpenPeriod(any(), any())).thenReturn(new AccountingPeriod());
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
    void createJournalEntry_returnsExistingOnDuplicateReference() {
        // Test idempotent behavior: return existing entry instead of throwing exception
        LocalDate today = LocalDate.of(2024, 3, 20);
        var existingEntry = new com.bigbrightpaints.erp.modules.accounting.domain.JournalEntry();
        existingEntry.setCompany(company);
        existingEntry.setReferenceNumber("DUP-REF");
        existingEntry.setEntryDate(today);
        existingEntry.setStatus("POSTED");
        org.springframework.test.util.ReflectionTestUtils.setField(existingEntry, "id", 999L);
        
        when(journalEntryRepository.findByCompanyAndReferenceNumber(eq(company), eq("DUP-REF")))
                .thenReturn(Optional.of(existingEntry));

        JournalEntryRequest request = new JournalEntryRequest(
                "DUP-REF",
                today,
                "Duplicate ref",
                null,
                null,
                Boolean.FALSE,
                List.of(new JournalEntryRequest.JournalLineRequest(1L, "Line", new BigDecimal("10.00"), BigDecimal.ZERO))
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
}
