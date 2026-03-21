package com.bigbrightpaints.erp.modules.accounting.service;

import com.bigbrightpaints.erp.core.audit.AuditService;
import com.bigbrightpaints.erp.core.util.CompanyClock;
import com.bigbrightpaints.erp.core.util.CompanyEntityLookup;
import com.bigbrightpaints.erp.modules.accounting.domain.Account;
import com.bigbrightpaints.erp.modules.accounting.domain.AccountRepository;
import com.bigbrightpaints.erp.modules.accounting.domain.AccountingPeriod;
import com.bigbrightpaints.erp.modules.accounting.domain.AccountingPeriodRepository;
import com.bigbrightpaints.erp.modules.accounting.domain.JournalEntry;
import com.bigbrightpaints.erp.modules.accounting.domain.JournalEntryRepository;
import com.bigbrightpaints.erp.modules.accounting.domain.JournalLineRepository;
import com.bigbrightpaints.erp.modules.accounting.event.AccountingEventStore;
import com.bigbrightpaints.erp.modules.accounting.dto.JournalEntryDto;
import com.bigbrightpaints.erp.modules.accounting.dto.JournalEntryRequest;
import com.bigbrightpaints.erp.modules.company.domain.Company;
import com.bigbrightpaints.erp.modules.company.service.CompanyContextService;
import com.bigbrightpaints.erp.modules.hr.domain.PayrollRunLineRepository;
import com.bigbrightpaints.erp.modules.hr.domain.PayrollRunRepository;
import com.bigbrightpaints.erp.modules.invoice.domain.InvoiceRepository;
import com.bigbrightpaints.erp.modules.invoice.service.InvoiceSettlementPolicy;
import com.bigbrightpaints.erp.modules.inventory.domain.FinishedGoodBatchRepository;
import com.bigbrightpaints.erp.modules.inventory.domain.RawMaterialBatchRepository;
import com.bigbrightpaints.erp.modules.inventory.domain.RawMaterialMovementRepository;
import com.bigbrightpaints.erp.modules.purchasing.domain.RawMaterialPurchaseRepository;
import com.bigbrightpaints.erp.modules.purchasing.domain.SupplierRepository;
import com.bigbrightpaints.erp.modules.sales.domain.DealerRepository;
import com.bigbrightpaints.erp.modules.accounting.domain.PartnerSettlementAllocationRepository;
import jakarta.persistence.EntityManager;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.test.util.ReflectionTestUtils;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class AccountingServiceBenchmarkTest {

    private static final LocalDate TODAY = LocalDate.of(2025, 12, 15);
    private static final Long DEBIT_ACCOUNT_ID = 1L;
    private static final Long CREDIT_ACCOUNT_ID = 2L;

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
    private com.bigbrightpaints.erp.modules.accounting.domain.JournalReferenceMappingRepository journalReferenceMappingRepository;
    @Mock
    private AccountingPeriodRepository accountingPeriodRepository;
    @Mock
    private JournalLineRepository journalLineRepository;
    @Mock
    private AccountingPeriodService accountingPeriodService;
    @Mock
    private EntityManager entityManager;
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
                accountingEventStore,
                mock(JournalEntryService.class),
                mock(DealerReceiptService.class),
                mock(SettlementService.class),
                mock(CreditDebitNoteService.class),
                mock(AccountingAuditService.class),
                mock(InventoryAccountingService.class),
                mock(org.springframework.beans.factory.ObjectProvider.class)
        );

        company = new Company();
        company.setBaseCurrency("INR");
        when(companyContextService.requireCurrentCompany()).thenReturn(company);
        when(companyClock.today(company)).thenReturn(TODAY);

        lenient().when(systemSettingsService.isPeriodLockEnforced()).thenReturn(true);
        lenient().when(journalEntryRepository.findByCompanyAndReferenceNumber(any(), any())).thenReturn(Optional.empty());
        lenient().when(dealerRepository.findByCompanyAndReceivableAccountIn(any(), any())).thenReturn(List.of());
        lenient().when(supplierRepository.findByCompanyAndPayableAccountIn(any(), any())).thenReturn(List.of());
        lenient().when(accountRepository.updateBalanceAtomic(any(), any(), any())).thenReturn(1);

        AccountingPeriod period = new AccountingPeriod();
        period.setYear(TODAY.getYear());
        period.setMonth(TODAY.getMonthValue());
        period.setStartDate(TODAY.withDayOfMonth(1));
        period.setEndDate(TODAY.withDayOfMonth(TODAY.lengthOfMonth()));
        lenient().when(accountingPeriodService.requireOpenPeriod(any(), any())).thenReturn(period);

        Account debitAccount = new Account();
        ReflectionTestUtils.setField(debitAccount, "id", DEBIT_ACCOUNT_ID);
        debitAccount.setCode("BENCH-DEBIT");
        debitAccount.setName("Bench Debit");

        Account creditAccount = new Account();
        ReflectionTestUtils.setField(creditAccount, "id", CREDIT_ACCOUNT_ID);
        creditAccount.setCode("BENCH-CREDIT");
        creditAccount.setName("Bench Credit");

        lenient().when(accountRepository.lockByCompanyAndId(company, DEBIT_ACCOUNT_ID)).thenReturn(Optional.of(debitAccount));
        lenient().when(accountRepository.lockByCompanyAndId(company, CREDIT_ACCOUNT_ID)).thenReturn(Optional.of(creditAccount));

        AtomicLong idSequence = new AtomicLong(1);
        lenient().when(journalEntryRepository.save(any())).thenAnswer(invocation -> {
            JournalEntry entry = invocation.getArgument(0);
            ReflectionTestUtils.setField(entry, "id", idSequence.getAndIncrement());
            return entry;
        });
    }

    @Test
    void benchmark_createJournalEntry_twoLinePosting() {
        Assumptions.assumeTrue(Boolean.getBoolean("runBenchmarks"), "Set -DrunBenchmarks=true to enable benchmarks");

        int warmupIterations = Integer.getInteger("benchWarmup", 500);
        int iterations = Integer.getInteger("benchIterations", 5000);
        BigDecimal amount = new BigDecimal("123.45");

        for (int i = 0; i < warmupIterations; i++) {
            accountingService.createJournalEntry(twoLineRequest("WARM-2L-" + i, amount));
        }

        long start = System.nanoTime();
        JournalEntryDto last = null;
        for (int i = 0; i < iterations; i++) {
            last = accountingService.createJournalEntry(twoLineRequest("BENCH-2L-" + i, amount));
        }
        long elapsedNanos = System.nanoTime() - start;

        double seconds = elapsedNanos / 1_000_000_000.0;
        double opsPerSecond = iterations / seconds;
        double msPerOp = (seconds * 1_000.0) / iterations;
        System.out.printf("AccountingService.createJournalEntry (2 lines): %,d ops in %.3fs => %.0f ops/s (%.3f ms/op)%n",
                iterations, seconds, opsPerSecond, msPerOp);

        assertThat(last).isNotNull();
        assertThat(last.lines()).hasSize(2);
    }

    @Test
    void benchmark_createJournalEntry_fiftyLinePosting() {
        Assumptions.assumeTrue(Boolean.getBoolean("runBenchmarks"), "Set -DrunBenchmarks=true to enable benchmarks");

        int warmupIterations = Integer.getInteger("benchWarmupLarge", 100);
        int iterations = Integer.getInteger("benchIterationsLarge", 1000);
        BigDecimal amount = new BigDecimal("10.00");
        List<JournalEntryRequest.JournalLineRequest> lines = fiftyLineBalanced(amount);

        for (int i = 0; i < warmupIterations; i++) {
            accountingService.createJournalEntry(multiLineRequest("WARM-50L-" + i, lines));
        }

        long start = System.nanoTime();
        JournalEntryDto last = null;
        for (int i = 0; i < iterations; i++) {
            last = accountingService.createJournalEntry(multiLineRequest("BENCH-50L-" + i, lines));
        }
        long elapsedNanos = System.nanoTime() - start;

        double seconds = elapsedNanos / 1_000_000_000.0;
        double opsPerSecond = iterations / seconds;
        double msPerOp = (seconds * 1_000.0) / iterations;
        System.out.printf("AccountingService.createJournalEntry (50 lines): %,d ops in %.3fs => %.0f ops/s (%.3f ms/op)%n",
                iterations, seconds, opsPerSecond, msPerOp);

        assertThat(last).isNotNull();
        assertThat(last.lines()).hasSize(50);
    }

    private static JournalEntryRequest twoLineRequest(String reference, BigDecimal amount) {
        return new JournalEntryRequest(
                reference,
                TODAY,
                "Benchmark posting",
                null,
                null,
                Boolean.FALSE,
                List.of(
                        new JournalEntryRequest.JournalLineRequest(DEBIT_ACCOUNT_ID, "Debit", amount, BigDecimal.ZERO),
                        new JournalEntryRequest.JournalLineRequest(CREDIT_ACCOUNT_ID, "Credit", BigDecimal.ZERO, amount)
                )
        );
    }

    private static JournalEntryRequest multiLineRequest(String reference, List<JournalEntryRequest.JournalLineRequest> lines) {
        return new JournalEntryRequest(reference, TODAY, "Benchmark posting", null, null, Boolean.FALSE, lines);
    }

    private static List<JournalEntryRequest.JournalLineRequest> fiftyLineBalanced(BigDecimal amount) {
        List<JournalEntryRequest.JournalLineRequest> lines = new ArrayList<>();
        for (int i = 0; i < 25; i++) {
            lines.add(new JournalEntryRequest.JournalLineRequest(DEBIT_ACCOUNT_ID, "Debit " + i, amount, BigDecimal.ZERO));
            lines.add(new JournalEntryRequest.JournalLineRequest(CREDIT_ACCOUNT_ID, "Credit " + i, BigDecimal.ZERO, amount));
        }
        return lines;
    }
}
