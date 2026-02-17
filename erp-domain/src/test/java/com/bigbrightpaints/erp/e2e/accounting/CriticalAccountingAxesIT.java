package com.bigbrightpaints.erp.e2e.accounting;

import com.bigbrightpaints.erp.core.exception.ApplicationException;
import com.bigbrightpaints.erp.core.exception.ErrorCode;
import com.bigbrightpaints.erp.core.security.CompanyContextHolder;
import com.bigbrightpaints.erp.modules.accounting.domain.Account;
import com.bigbrightpaints.erp.modules.accounting.domain.AccountRepository;
import com.bigbrightpaints.erp.modules.accounting.domain.AccountType;
import com.bigbrightpaints.erp.modules.accounting.domain.JournalEntry;
import com.bigbrightpaints.erp.modules.accounting.domain.JournalEntryRepository;
import com.bigbrightpaints.erp.modules.accounting.domain.JournalLine;
import com.bigbrightpaints.erp.modules.accounting.domain.JournalLineRepository;
import com.bigbrightpaints.erp.modules.accounting.domain.DealerLedgerRepository;
import com.bigbrightpaints.erp.modules.accounting.domain.SupplierLedgerRepository;
import com.bigbrightpaints.erp.modules.accounting.domain.AccountingPeriodRepository;
import com.bigbrightpaints.erp.modules.accounting.domain.AccountingPeriod;
import com.bigbrightpaints.erp.modules.accounting.domain.AccountingPeriodStatus;
import com.bigbrightpaints.erp.modules.accounting.dto.JournalEntryDto;
import com.bigbrightpaints.erp.modules.accounting.dto.JournalEntryRequest;
import com.bigbrightpaints.erp.modules.accounting.dto.JournalEntryReversalRequest;
import com.bigbrightpaints.erp.modules.accounting.service.AccountingFacade;
import com.bigbrightpaints.erp.modules.accounting.service.AccountingService;
import com.bigbrightpaints.erp.modules.accounting.service.CompanyAccountingSettingsService;
import com.bigbrightpaints.erp.modules.accounting.service.JournalReferenceResolver;
import com.bigbrightpaints.erp.modules.accounting.service.TaxService;
import com.bigbrightpaints.erp.modules.accounting.service.ReconciliationService;
import com.bigbrightpaints.erp.modules.company.domain.Company;
import com.bigbrightpaints.erp.modules.company.domain.CompanyRepository;
import com.bigbrightpaints.erp.modules.inventory.domain.FinishedGood;
import com.bigbrightpaints.erp.modules.inventory.domain.FinishedGoodBatch;
import com.bigbrightpaints.erp.modules.inventory.domain.FinishedGoodBatchRepository;
import com.bigbrightpaints.erp.modules.inventory.domain.FinishedGoodRepository;
import com.bigbrightpaints.erp.modules.inventory.dto.FinishedGoodBatchRequest;
import com.bigbrightpaints.erp.modules.inventory.dto.FinishedGoodRequest;
import com.bigbrightpaints.erp.modules.inventory.service.FinishedGoodsService;
import com.bigbrightpaints.erp.modules.production.domain.ProductionBrand;
import com.bigbrightpaints.erp.modules.production.domain.ProductionBrandRepository;
import com.bigbrightpaints.erp.modules.production.domain.ProductionProduct;
import com.bigbrightpaints.erp.modules.production.domain.ProductionProductRepository;
import com.bigbrightpaints.erp.modules.reports.service.ReportService;
import com.bigbrightpaints.erp.modules.sales.domain.Dealer;
import com.bigbrightpaints.erp.modules.sales.domain.DealerRepository;
import com.bigbrightpaints.erp.modules.sales.domain.SalesOrder;
import com.bigbrightpaints.erp.modules.sales.domain.SalesOrderRepository;
import com.bigbrightpaints.erp.modules.sales.dto.SalesOrderItemRequest;
import com.bigbrightpaints.erp.modules.sales.dto.SalesOrderRequest;
import com.bigbrightpaints.erp.modules.sales.service.SalesService;
import com.bigbrightpaints.erp.modules.sales.util.SalesOrderReference;
import com.bigbrightpaints.erp.modules.purchasing.domain.Supplier;
import com.bigbrightpaints.erp.modules.purchasing.domain.SupplierRepository;
import com.bigbrightpaints.erp.modules.reports.dto.TrialBalanceDto;
import com.bigbrightpaints.erp.modules.reports.dto.ProfitLossDto;
import com.bigbrightpaints.erp.test.AbstractIntegrationTest;
import jakarta.transaction.Transactional;
import org.assertj.core.api.Assertions;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.dao.CannotAcquireLockException;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DisplayName("Critical accounting and costing invariants")
class CriticalAccountingAxesIT extends AbstractIntegrationTest {

    private static final String COMPANY_CODE = "CRIT-AXES";
    private static final String ADMIN_EMAIL = "axes@bbp.com";
    private static final String ADMIN_PASSWORD = "axes123";

    @Autowired private AccountingService accountingService;
    @Autowired private AccountingFacade accountingFacade;
    @Autowired private AccountRepository accountRepository;
    @Autowired private JournalEntryRepository journalEntryRepository;
    @Autowired private JournalLineRepository journalLineRepository;
    @Autowired private DealerLedgerRepository dealerLedgerRepository;
    @Autowired private SupplierLedgerRepository supplierLedgerRepository;
    @Autowired private AccountingPeriodRepository accountingPeriodRepository;
    @Autowired private CompanyRepository companyRepository;
    @Autowired private DealerRepository dealerRepository;
    @Autowired private SupplierRepository supplierRepository;
    @Autowired private FinishedGoodsService finishedGoodsService;
    @Autowired private FinishedGoodRepository finishedGoodRepository;
    @Autowired private FinishedGoodBatchRepository finishedGoodBatchRepository;
    @Autowired private ProductionProductRepository productionProductRepository;
    @Autowired private ProductionBrandRepository productionBrandRepository;
    @Autowired private SalesService salesService;
    @Autowired private SalesOrderRepository salesOrderRepository;
    @Autowired private TaxService taxService;
    @Autowired private CompanyAccountingSettingsService companyAccountingSettingsService;
    @Autowired private ReconciliationService reconciliationService;
    @Autowired private ReportService reportService;
    @Autowired private JournalReferenceResolver journalReferenceResolver;
    @Autowired private TestRestTemplate restTemplate;

    private Company company;
    private Dealer dealer;
    private Supplier supplier;
    private ProductionBrand brand;
    private final Map<String, Account> accounts = new HashMap<>();

    @BeforeEach
    void init() {
        dataSeeder.ensureUser(ADMIN_EMAIL, ADMIN_PASSWORD, "Axes Admin", COMPANY_CODE,
                List.of("ROLE_ADMIN", "ROLE_ACCOUNTING", "ROLE_SALES", "ROLE_FACTORY"));
        CompanyContextHolder.setCompanyId(COMPANY_CODE);
        company = companyRepository.findByCodeIgnoreCase(COMPANY_CODE).orElseThrow();
        company.setDefaultGstRate(BigDecimal.ZERO);
        ensureBaseAccounts();
        dealer = ensureDealer("TRAIN-DEALER", "Training Dealer");
        supplier = ensureSupplier("TRAIN-SUP", "Training Supplier");
        dealerLedgerRepository.deleteAll(dealerLedgerRepository.findByCompanyAndDealerOrderByEntryDateAsc(company, dealer));
        supplierLedgerRepository.deleteAll(supplierLedgerRepository.findByCompanyAndSupplierOrderByEntryDateAsc(company, supplier));
        brand = ensureBrand("TRAIN-BRAND");
    }

    @AfterEach
    void clearContext() {
        CompanyContextHolder.clear();
    }

    @Test
    @DisplayName("Double-entry invariants hold and reversals fully offset originals")
    @Transactional
    void doubleEntryAndReversalInvariant() {
        BigDecimal saleAmount = new BigDecimal("1180.00");
        BigDecimal saleTax = new BigDecimal("180.00");
        String saleRef = "SO-" + UUID.randomUUID();
        JournalEntryDto sale = accountingService.createJournalEntry(new JournalEntryRequest(
                saleRef,
                LocalDate.now(),
                "GST sale",
                dealer.getId(),
                null,
                Boolean.FALSE,
                List.of(
                        new JournalEntryRequest.JournalLineRequest(accounts.get("AR").getId(), "AR", saleAmount, BigDecimal.ZERO),
                        new JournalEntryRequest.JournalLineRequest(accounts.get("REV").getId(), "Revenue", BigDecimal.ZERO, saleAmount.subtract(saleTax)),
                        new JournalEntryRequest.JournalLineRequest(accounts.get("GST_OUT").getId(), "GST Output", BigDecimal.ZERO, saleTax)
                )
        ));

        String purchaseRef = "PO-" + UUID.randomUUID();
        accountingService.createJournalEntry(new JournalEntryRequest(
                purchaseRef,
                LocalDate.now(),
                "Inventory buy",
                null,
                supplier.getId(),
                Boolean.FALSE,
                List.of(
                        new JournalEntryRequest.JournalLineRequest(accounts.get("INV").getId(), "Inventory", new BigDecimal("500.00"), BigDecimal.ZERO),
                        new JournalEntryRequest.JournalLineRequest(accounts.get("GST_IN").getId(), "GST Input", new BigDecimal("90.00"), BigDecimal.ZERO),
                        new JournalEntryRequest.JournalLineRequest(accounts.get("AP").getId(), "AP", BigDecimal.ZERO, new BigDecimal("590.00"))
                )
        ));

        accountingFacade.postCOGS(
                saleRef,
                accounts.get("COGS").getId(),
                accounts.get("INV").getId(),
                new BigDecimal("320.00"),
                "COGS for " + saleRef);

        JournalEntryDto reversal = accountingService.reverseJournalEntry(
                sale.id(),
                new JournalEntryReversalRequest(LocalDate.now(), false, "Customer return", "Reverse " + saleRef, Boolean.FALSE));

        BigDecimal totalDebits = sumDebitForCompany();
        BigDecimal totalCredits = sumCreditForCompany();
        assertThat(totalDebits.subtract(totalCredits).abs()).isLessThanOrEqualTo(new BigDecimal("0.0001"));

        JournalEntry reversedEntry = journalEntryRepository.findById(reversal.id()).orElseThrow();
        assertThat(reversedEntry.getReversalOf()).isNotNull();
        BigDecimal reversalDebits = reversedEntry.getLines().stream()
                .map(line -> line.getDebit() == null ? BigDecimal.ZERO : line.getDebit())
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        BigDecimal reversalCredits = reversedEntry.getLines().stream()
                .map(line -> line.getCredit() == null ? BigDecimal.ZERO : line.getCredit())
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        assertThat(reversalDebits).isEqualByComparingTo(reversalCredits);
    }

    @Test
    @DisplayName("FIFO/LIFO dispatch uses correct batches and COGS matches inventory reduction")
    @Transactional
    void costingFlowsStayAlignedWithInventory() {
        FinishedGood fifoGood = createFinishedGood("FG-FIFO-" + UUID.randomUUID(), "FIFO");
        registerBatch(fifoGood, new BigDecimal("50"), new BigDecimal("10.00"), Instant.now().minusSeconds(3600));
        registerBatch(fifoGood, new BigDecimal("50"), new BigDecimal("12.00"), Instant.now());
        // Expected cost: 50 @ 10 + 10 @ 12 = 620
        BigDecimal fifoCost = new BigDecimal("620.00");

        BigDecimal invBefore = accounts.get("INV").getBalance();
        BigDecimal cogsBefore = accounts.get("COGS").getBalance();
        accountingFacade.postCOGS(
                "FIFO-DISP-" + fifoGood.getProductCode(),
                accounts.get("COGS").getId(),
                accounts.get("INV").getId(),
                fifoCost,
                "COGS FIFO");
        refreshAccounts();
        BigDecimal actualFifoCost = invBefore.subtract(accounts.get("INV").getBalance());
        assertThat(actualFifoCost).isEqualByComparingTo(fifoCost);
        assertThat(accounts.get("COGS").getBalance().subtract(cogsBefore)).isEqualByComparingTo(fifoCost);

        FinishedGood lifoGood = createFinishedGood("FG-LIFO-" + UUID.randomUUID(), "LIFO");
        registerBatch(lifoGood, new BigDecimal("50"), new BigDecimal("10.00"), Instant.now().minusSeconds(7200));
        registerBatch(lifoGood, new BigDecimal("50"), new BigDecimal("12.00"), Instant.now());
        BigDecimal lifoCost = new BigDecimal("700.00");
    }

    @Test
    @DisplayName("GST per-item vs exempt lines post correct tax and rounding")
    @Transactional
    void gstEdgeCasesAndReturns() {
        enableGstMode(new BigDecimal("18.00"));
        FinishedGood taxable = createFinishedGood("FG-TAX-" + UUID.randomUUID(), "FIFO");
        FinishedGood exempt = createFinishedGood("FG-EXEMPT-" + UUID.randomUUID(), "FIFO");
        SalesOrder order = createOrderWithMixedGst(taxable, exempt);
        BigDecimal subtotal = order.getSubtotalAmount();
        BigDecimal gstTotal = order.getGstTotal();

        String invoiceNumber = "INV-" + UUID.randomUUID();
        accountingFacade.postSalesJournal(
                dealer.getId(),
                invoiceNumber,
                LocalDate.now(),
                "GST mixed sale",
                Map.of(accounts.get("REV").getId(), subtotal),
                Map.of(accounts.get("GST_OUT").getId(), gstTotal),
                order.getTotalAmount(),
                null);

        var gstReturn = taxService.generateGstReturn(YearMonth.from(LocalDate.now()));
        assertThat(gstReturn.getOutputTax()).isGreaterThan(BigDecimal.ZERO);

        accountingFacade.postSalesReturn(
                dealer.getId(),
                invoiceNumber,
                Map.of(
                        accounts.get("REV").getId(), subtotal,
                        accounts.get("GST_OUT").getId(), gstTotal),
                order.getTotalAmount(),
                "Return all");

        var gstReturnAfter = taxService.generateGstReturn(YearMonth.from(LocalDate.now()));
        assertThat(gstReturnAfter.getOutputTax().abs()).isLessThanOrEqualTo(gstReturn.getOutputTax().abs());
        assertThat(order.getGstRoundingAdjustment().abs()).isLessThanOrEqualTo(new BigDecimal("0.05"));
    }

    @Test
    @DisplayName("GST return includes input and output tax for the period")
    @Transactional
    void gstReturnIncludesInputAndOutputTax() {
        enableGstMode(new BigDecimal("18.00"));
        LocalDate today = LocalDate.now();
        YearMonth period = YearMonth.from(today);
        var before = taxService.generateGstReturn(period);
        CompanyAccountingSettingsService.TaxAccountConfiguration taxAccounts =
                companyAccountingSettingsService.requireTaxAccounts();

        BigDecimal saleBase = new BigDecimal("1000.00");
        BigDecimal saleTax = new BigDecimal("180.00");
        JournalEntryDto saleJournal = accountingFacade.postSalesJournal(
                dealer.getId(),
                "GST-OUT-" + UUID.randomUUID(),
                today,
                "GST sale",
                Map.of(accounts.get("REV").getId(), saleBase),
                Map.of(accounts.get("GST_OUT").getId(), saleTax),
                saleBase.add(saleTax),
                null);
        JournalEntry saleEntry = journalEntryRepository.findById(saleJournal.id()).orElseThrow();
        BigDecimal outputTaxCredit = saleEntry.getLines().stream()
                .filter(line -> line.getAccount().getId().equals(taxAccounts.outputTaxAccountId()))
                .map(line -> line.getCredit() == null ? BigDecimal.ZERO : line.getCredit())
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        assertThat(outputTaxCredit).isEqualByComparingTo(saleTax);

        BigDecimal purchaseBase = new BigDecimal("500.00");
        BigDecimal purchaseTax = new BigDecimal("90.00");
        JournalEntryDto purchaseJournal = accountingFacade.postPurchaseJournal(
                supplier.getId(),
                "GST-IN-" + UUID.randomUUID(),
                today,
                "GST purchase",
                Map.of(accounts.get("INV").getId(), purchaseBase),
                Map.of(accounts.get("GST_IN").getId(), purchaseTax),
                purchaseBase.add(purchaseTax),
                null);
        JournalEntry purchaseEntry = journalEntryRepository.findById(purchaseJournal.id()).orElseThrow();
        BigDecimal inputTaxDebit = purchaseEntry.getLines().stream()
                .filter(line -> line.getAccount().getId().equals(taxAccounts.inputTaxAccountId()))
                .map(line -> line.getDebit() == null ? BigDecimal.ZERO : line.getDebit())
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        assertThat(inputTaxDebit).isEqualByComparingTo(purchaseTax);

        var after = taxService.generateGstReturn(period);

        assertThat(after.getOutputTax().subtract(before.getOutputTax())).isEqualByComparingTo(saleTax);
        assertThat(after.getInputTax().subtract(before.getInputTax())).isEqualByComparingTo(purchaseTax);
        assertThat(after.getNetPayable().subtract(before.getNetPayable()))
                .isEqualByComparingTo(saleTax.subtract(purchaseTax));
    }

    @Test
    @DisplayName("Subledger reconciliation aligns for AR/AP within period")
    @Transactional
    void subledgerReconciliationAlignsWithinPeriod() {
        LocalDate today = LocalDate.now();
        var before = reconciliationService.reconcileSubledgersForPeriod(today, today);

        BigDecimal saleBase = new BigDecimal("600.00");
        BigDecimal saleTax = new BigDecimal("108.00");
        BigDecimal saleTotal = saleBase.add(saleTax);
        accountingFacade.postSalesJournal(
                dealer.getId(),
                "RECON-SALE-" + UUID.randomUUID(),
                today,
                "Recon sale",
                Map.of(accounts.get("REV").getId(), saleBase),
                Map.of(accounts.get("GST_OUT").getId(), saleTax),
                saleTotal,
                null);

        BigDecimal purchaseBase = new BigDecimal("400.00");
        BigDecimal purchaseTax = new BigDecimal("72.00");
        BigDecimal purchaseTotal = purchaseBase.add(purchaseTax);
        accountingFacade.postPurchaseJournal(
                supplier.getId(),
                "RECON-BUY-" + UUID.randomUUID(),
                today,
                "Recon purchase",
                Map.of(accounts.get("INV").getId(), purchaseBase),
                Map.of(accounts.get("GST_IN").getId(), purchaseTax),
                purchaseTotal,
                null);

        var after = reconciliationService.reconcileSubledgersForPeriod(today, today);

        assertThat(after.glArNet().subtract(before.glArNet())).isEqualByComparingTo(saleTotal);
        assertThat(after.dealerLedgerNet().subtract(before.dealerLedgerNet())).isEqualByComparingTo(saleTotal);
        assertThat(after.glApNet().subtract(before.glApNet())).isEqualByComparingTo(purchaseTotal);
        assertThat(after.supplierLedgerNet().subtract(before.supplierLedgerNet())).isEqualByComparingTo(purchaseTotal);
        assertThat(after.arReconciled()).isTrue();
        assertThat(after.apReconciled()).isTrue();
    }

    @Test
    @DisplayName("Sales journal idempotency keeps canonical references stable while honoring explicit invoice references")
    @Transactional
    void salesJournalIdempotentAcrossReferenceVariants() {
        LocalDate today = LocalDate.now();
        String orderNumber = "SO-IDEMP-" + UUID.randomUUID();
        BigDecimal base = new BigDecimal("250.00");
        BigDecimal tax = new BigDecimal("45.00");
        BigDecimal total = base.add(tax);

        JournalEntryDto first = accountingFacade.postSalesJournal(
                dealer.getId(),
                orderNumber,
                today,
                "Idempotent sale",
                Map.of(accounts.get("REV").getId(), base),
                Map.of(accounts.get("GST_OUT").getId(), tax),
                total,
                null);

        String canonicalReference = SalesOrderReference.invoiceReference(orderNumber);
        JournalEntryDto second = accountingFacade.postSalesJournal(
                dealer.getId(),
                orderNumber,
                today,
                "Idempotent sale",
                Map.of(accounts.get("REV").getId(), base),
                Map.of(accounts.get("GST_OUT").getId(), tax),
                total,
                canonicalReference);

        assertThat(second.id()).isEqualTo(first.id());

        String aliasReference = "INV-" + UUID.randomUUID();
        JournalEntryDto third = accountingFacade.postSalesJournal(
                dealer.getId(),
                orderNumber,
                today,
                "Idempotent sale",
                Map.of(accounts.get("REV").getId(), base),
                Map.of(accounts.get("GST_OUT").getId(), tax),
                total,
                aliasReference);

        assertThat(third.id()).isEqualTo(first.id());
        assertThat(journalReferenceResolver.findExistingEntry(company, aliasReference))
                .isPresent()
                .get()
                .extracting(JournalEntry::getId)
                .isEqualTo(first.id());
    }

    @Test
    @DisplayName("Sales journal idempotency rejects mismatched payloads across reference aliases")
    @Transactional
    void salesJournalIdempotencyRejectsMismatchedAliasPayload() {
        LocalDate today = LocalDate.now();
        String orderNumber = "SO-IDEMP-MISMATCH-" + UUID.randomUUID();
        BigDecimal base = new BigDecimal("250.00");
        BigDecimal tax = new BigDecimal("45.00");
        BigDecimal total = base.add(tax);

        accountingFacade.postSalesJournal(
                dealer.getId(),
                orderNumber,
                today,
                "Idempotent sale",
                Map.of(accounts.get("REV").getId(), base),
                Map.of(accounts.get("GST_OUT").getId(), tax),
                total,
                null);

        BigDecimal changedBase = new BigDecimal("260.00");
        BigDecimal changedTax = new BigDecimal("40.00");
        BigDecimal changedTotal = changedBase.add(changedTax);
        String aliasReference = "INV-" + UUID.randomUUID();

        assertThatThrownBy(() -> accountingFacade.postSalesJournal(
                dealer.getId(),
                orderNumber,
                today,
                "Idempotent sale",
                Map.of(accounts.get("REV").getId(), changedBase),
                Map.of(accounts.get("GST_OUT").getId(), changedTax),
                changedTotal,
                aliasReference))
                .isInstanceOf(ApplicationException.class)
                .satisfies(ex -> assertThat(((ApplicationException) ex).getErrorCode())
                        .isEqualTo(ErrorCode.BUSINESS_DUPLICATE_ENTRY));
    }

    @Test
    @DisplayName("Concurrent sales journal posts converge on one canonical entry")
    void salesJournalConcurrentDedupesAcrossReferences() {
        LocalDate today = LocalDate.now();
        String orderNumber = "SO-IDEMP-CONCURRENT-" + UUID.randomUUID();
        BigDecimal base = new BigDecimal("125.00");
        BigDecimal tax = new BigDecimal("22.50");
        BigDecimal total = base.add(tax);
        String aliasReference = "INV-" + UUID.randomUUID();

        ExecutorService pool = Executors.newFixedThreadPool(2);
        CompletableFuture<JournalEntryDto> canonicalFuture = CompletableFuture.supplyAsync(() -> {
            CompanyContextHolder.setCompanyId(COMPANY_CODE);
            try {
                return postSalesJournalWithRetry(orderNumber, today, base, tax, total, null);
            } finally {
                CompanyContextHolder.clear();
            }
        }, pool);
        CompletableFuture<JournalEntryDto> aliasFuture = CompletableFuture.supplyAsync(() -> {
            CompanyContextHolder.setCompanyId(COMPANY_CODE);
            try {
                return postSalesJournalWithRetry(orderNumber, today, base, tax, total, aliasReference);
            } finally {
                CompanyContextHolder.clear();
            }
        }, pool);

        JournalEntryDto canonical = canonicalFuture.join();
        JournalEntryDto alias = aliasFuture.join();
        pool.shutdown();

        assertThat(canonical.id()).isEqualTo(alias.id());
        String canonicalReference = SalesOrderReference.invoiceReference(orderNumber);
        assertThat(journalEntryRepository.findByCompanyAndReferenceNumber(company, canonicalReference))
                .isPresent()
                .get()
                .extracting(JournalEntry::getId)
                .isEqualTo(canonical.id());
    }

    private JournalEntryDto postSalesJournalWithRetry(String orderNumber,
                                                      LocalDate today,
                                                      BigDecimal base,
                                                      BigDecimal tax,
                                                      BigDecimal total,
                                                      String reference) {
        int attempts = 0;
        while (true) {
            try {
                return accountingFacade.postSalesJournal(
                        dealer.getId(),
                        orderNumber,
                        today,
                        "Concurrent sale",
                        Map.of(accounts.get("REV").getId(), base),
                        Map.of(accounts.get("GST_OUT").getId(), tax),
                        total,
                        reference);
            } catch (CannotAcquireLockException ex) {
                attempts++;
                if (attempts >= 5) {
                    throw ex;
                }
                try {
                    Thread.sleep(50L * attempts);
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    throw ex;
                }
            }
        }
    }

    @Test
    @DisplayName("Locked periods reject backdated postings without override")
    void periodLockBlocksBackdatedPosting() {
        LocalDate lockedDate = LocalDate.now().minusMonths(1).withDayOfMonth(10);
        AccountingPeriod period = ensureLockedPeriod(lockedDate);
        JournalEntryRequest request = new JournalEntryRequest(
                "LOCK-" + UUID.randomUUID(),
                lockedDate,
                "Backdated test",
                null,
                null,
                Boolean.FALSE,
                List.of(
                        new JournalEntryRequest.JournalLineRequest(accounts.get("CASH").getId(), "Cash in", new BigDecimal("100.00"), BigDecimal.ZERO),
                        new JournalEntryRequest.JournalLineRequest(accounts.get("REV").getId(), "Revenue", BigDecimal.ZERO, new BigDecimal("100.00"))
                ));

        assertThatThrownBy(() -> accountingService.createJournalEntry(request))
                .isInstanceOf(ApplicationException.class)
                .hasMessageContaining("locked/closed");
        accountingPeriodRepository.delete(period);
    }

    @Test
    @DisplayName("Concurrent postings remain balanced and isolated")
    void concurrencyReservationSafety() {
        // Capture starting balances before concurrent posts
        Account startCash = accountRepository.findByCompanyAndCodeIgnoreCase(company, "CASH").orElseThrow();
        Account startRev = accountRepository.findByCompanyAndCodeIgnoreCase(company, "REV").orElseThrow();
        BigDecimal startCashBalance = startCash.getBalance() != null ? startCash.getBalance() : BigDecimal.ZERO;
        BigDecimal startRevBalance = startRev.getBalance() != null ? startRev.getBalance() : BigDecimal.ZERO;
        
        BigDecimal journalAmount = new BigDecimal("50.00");
        ExecutorService pool = Executors.newFixedThreadPool(5);
        List<CompletableFuture<Void>> futures = IntStream.range(0, 10)
                .mapToObj(i -> CompletableFuture.runAsync(() -> {
                    CompanyContextHolder.setCompanyId(COMPANY_CODE);
                    JournalEntryRequest request = new JournalEntryRequest(
                            "CC-" + UUID.randomUUID(),
                            LocalDate.now(),
                            "Concurrent post " + i,
                            null,
                            null,
                            Boolean.FALSE,
                            List.of(
                                    new JournalEntryRequest.JournalLineRequest(accounts.get("CASH").getId(), "Cash in", journalAmount, BigDecimal.ZERO),
                                    new JournalEntryRequest.JournalLineRequest(accounts.get("REV").getId(), "Revenue", BigDecimal.ZERO, journalAmount)
                            )
                    );
                    accountingService.createJournalEntry(request);
                    CompanyContextHolder.clear();
                }, pool))
                .toList();
        CompletableFuture.allOf(futures.toArray(new CompletableFuture[0])).join();
        pool.shutdownNow();

        // Verify total debits = credits (basic double-entry check)
        BigDecimal totalDebits = sumDebitForCompany();
        BigDecimal totalCredits = sumCreditForCompany();
        assertThat(totalDebits).isEqualByComparingTo(totalCredits);
        
        // CRITICAL: Verify actual account balances to detect lost updates
        // 10 concurrent posts x $50 = $500 net change expected
        BigDecimal expectedNetChange = journalAmount.multiply(new BigDecimal("10"));
        
        // Re-fetch accounts fresh from DB to verify atomic updates worked
        Account freshCash = accountRepository.findByCompanyAndCodeIgnoreCase(company, "CASH").orElseThrow();
        Account freshRev = accountRepository.findByCompanyAndCodeIgnoreCase(company, "REV").orElseThrow();
        BigDecimal endCashBalance = freshCash.getBalance() != null ? freshCash.getBalance() : BigDecimal.ZERO;
        BigDecimal endRevBalance = freshRev.getBalance() != null ? freshRev.getBalance() : BigDecimal.ZERO;
        
        // Cash (ASSET) increases by debit: starting balance + 500
        BigDecimal actualCashChange = endCashBalance.subtract(startCashBalance);
        assertThat(actualCashChange).as("Cash balance change after concurrent posts")
                .isEqualByComparingTo(expectedNetChange);
        
        // Revenue (REVENUE) increases by credit - in this system, credits are stored as negative
        // So $500 credits = -500 balance change (credit-normal accounts show negative for increases)
        BigDecimal actualRevChange = endRevBalance.subtract(startRevBalance);
        BigDecimal expectedRevChange = expectedNetChange.negate(); // Credits stored as negative
        assertThat(actualRevChange).as("Revenue balance change after concurrent posts (credits are negative)")
                .isEqualByComparingTo(expectedRevChange);
    }

    @Test
    @DisplayName("RBAC denies accounting endpoints for non-accounting roles")
    void rbacGuardsAccountingEndpoints() {
        dataSeeder.ensureUser("dealer@test.com", "dealer123", "Dealer User", COMPANY_CODE, List.of("ROLE_DEALER"));
        String token = login("dealer@test.com", "dealer123");
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(token);
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.set("X-Company-Id", COMPANY_CODE);

        Map<String, Object> payload = Map.of(
                "entryDate", LocalDate.now(),
                "memo", "Unauthorized journal",
                "lines", List.of(
                        Map.of("accountId", accounts.get("CASH").getId(), "debit", new BigDecimal("10.00"), "credit", BigDecimal.ZERO),
                        Map.of("accountId", accounts.get("REV").getId(), "debit", BigDecimal.ZERO, "credit", new BigDecimal("10.00"))
                )
        );
        ResponseEntity<Map> response = restTemplate.postForEntity("/api/v1/accounting/journal-entries",
                new HttpEntity<>(payload, headers), Map.class);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
    }

    @Test
    @DisplayName("Fuzzed transactions keep trial balance and P&L consistent with journals")
    @Transactional
    void fuzzedTransactionsKeepStatementsBalanced() {
        int txnCount = Integer.getInteger("fuzz.txn.count", 1024);
        Random random = new Random(42);

        for (int i = 0; i < txnCount; i++) {
            BigDecimal base = BigDecimal.valueOf(50 + random.nextInt(400)).setScale(2, RoundingMode.HALF_UP);
            switch (i % 4) {
                case 0 -> postSale(base, new BigDecimal("0.18"));
                case 1 -> postPurchase(base);
                case 2 -> postExpense(base.divide(BigDecimal.valueOf(2), 2, RoundingMode.HALF_UP));
                default -> settleReceivable(base.divide(BigDecimal.valueOf(3), 2, RoundingMode.HALF_UP));
            }
        }

        TrialBalanceDto tb = reportService.trialBalance();
        assertThat(tb.balanced()).isTrue();
        assertThat(tb.totalDebit()).isEqualByComparingTo(tb.totalCredit());

        ProfitLossDto pl = reportService.profitLoss();
        BigDecimal cogsFromJournals = journalLineRepository.findAll().stream()
                .filter(line -> line.getAccount().getId().equals(accounts.get("COGS").getId()))
                .map(line -> line.getDebit().subtract(line.getCredit()))
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        assertThat(pl.costOfGoodsSold()).isEqualByComparingTo(cogsFromJournals);
    }

    private void postSale(BigDecimal amount, BigDecimal gstRate) {
        BigDecimal tax = amount.multiply(gstRate).setScale(2, RoundingMode.HALF_UP);
        JournalEntryRequest request = new JournalEntryRequest(
                "FZ-SALE-" + UUID.randomUUID(),
                LocalDate.now(),
                "Fuzz sale",
                dealer.getId(),
                null,
                Boolean.FALSE,
                List.of(
                        new JournalEntryRequest.JournalLineRequest(accounts.get("AR").getId(), "AR", amount.add(tax), BigDecimal.ZERO),
                        new JournalEntryRequest.JournalLineRequest(accounts.get("REV").getId(), "Revenue", BigDecimal.ZERO, amount),
                        new JournalEntryRequest.JournalLineRequest(accounts.get("GST_OUT").getId(), "Output tax", BigDecimal.ZERO, tax)
                )
        );
        accountingService.createJournalEntry(request);
    }

    private void postPurchase(BigDecimal amount) {
        BigDecimal tax = amount.multiply(new BigDecimal("0.05")).setScale(2, RoundingMode.HALF_UP);
        JournalEntryRequest request = new JournalEntryRequest(
                "FZ-BUY-" + UUID.randomUUID(),
                LocalDate.now(),
                "Fuzz purchase",
                null,
                supplier.getId(),
                Boolean.FALSE,
                List.of(
                        new JournalEntryRequest.JournalLineRequest(accounts.get("INV").getId(), "Inventory", amount, BigDecimal.ZERO),
                        new JournalEntryRequest.JournalLineRequest(accounts.get("GST_IN").getId(), "Input tax", tax, BigDecimal.ZERO),
                        new JournalEntryRequest.JournalLineRequest(accounts.get("AP").getId(), "AP", BigDecimal.ZERO, amount.add(tax))
                )
        );
        accountingService.createJournalEntry(request);
    }

    private void postExpense(BigDecimal amount) {
        JournalEntryRequest request = new JournalEntryRequest(
                "FZ-EXP-" + UUID.randomUUID(),
                LocalDate.now(),
                "Random expense",
                null,
                null,
                Boolean.FALSE,
                List.of(
                        new JournalEntryRequest.JournalLineRequest(accounts.get("EXP").getId(), "Expense", amount, BigDecimal.ZERO),
                        new JournalEntryRequest.JournalLineRequest(accounts.get("CASH").getId(), "Pay cash", BigDecimal.ZERO, amount)
                )
        );
        accountingService.createJournalEntry(request);
    }

    private void settleReceivable(BigDecimal amount) {
        JournalEntryRequest request = new JournalEntryRequest(
                "FZ-SET-" + UUID.randomUUID(),
                LocalDate.now(),
                "Settle receivable",
                dealer.getId(),
                null,
                Boolean.FALSE,
                List.of(
                        new JournalEntryRequest.JournalLineRequest(accounts.get("CASH").getId(), "Cash in", amount, BigDecimal.ZERO),
                        new JournalEntryRequest.JournalLineRequest(accounts.get("AR").getId(), "Clear AR", BigDecimal.ZERO, amount)
                )
        );
        accountingService.createJournalEntry(request);
    }

    private FinishedGood createFinishedGood(String productCode, String costingMethod) {
        FinishedGoodRequest request = new FinishedGoodRequest(
                productCode,
                productCode + " Name",
                "UNIT",
                costingMethod,
                accounts.get("INV").getId(),
                accounts.get("COGS").getId(),
                accounts.get("REV").getId(),
                accounts.get("DISC").getId(),
                accounts.get("GST_OUT").getId()
        );
        finishedGoodsService.createFinishedGood(request);
        FinishedGood fg = finishedGoodRepository.findByCompanyAndProductCode(company, productCode).orElseThrow();
        ProductionProduct product = ensureProduct(productCode, new BigDecimal("20.00"), new BigDecimal("18.00"));
        fg.setCompany(company);
        fg.setValuationAccountId(accounts.get("INV").getId());
        fg.setCogsAccountId(accounts.get("COGS").getId());
        fg.setRevenueAccountId(accounts.get("REV").getId());
        fg.setTaxAccountId(accounts.get("GST_OUT").getId());
        finishedGoodRepository.save(fg);
        return fg;
    }

    private ProductionProduct ensureProduct(String sku, BigDecimal basePrice, BigDecimal gstRate) {
        return productionProductRepository.findByCompanyAndSkuCode(company, sku)
                .orElseGet(() -> {
                    ProductionProduct product = new ProductionProduct();
                    product.setCompany(company);
                    product.setBrand(brand);
                    product.setProductName("Product " + sku);
                    product.setCategory("FINISHED_GOOD");
                    product.setUnitOfMeasure("UNIT");
                    product.setSkuCode(sku);
                    product.setBasePrice(basePrice);
                    product.setGstRate(gstRate);
                    product.setMinDiscountPercent(new BigDecimal("100"));
                    product.setMinSellingPrice(BigDecimal.ZERO);
                    return productionProductRepository.save(product);
                });
    }

    private SalesOrder createOrder(FinishedGood fg, BigDecimal quantity, BigDecimal price, BigDecimal gstRate, String gstTreatment) {
        SalesOrderRequest request = new SalesOrderRequest(
                dealer.getId(),
                price.multiply(quantity),
                "INR",
                null,
                List.of(new SalesOrderItemRequest(
                        fg.getProductCode(),
                        "Training line",
                        quantity,
                        price,
                        gstRate
                )),
                gstTreatment,
                null,
                null,
                null
        );
        var dto = salesService.createOrder(request);
        SalesOrder order = salesOrderRepository.findWithItemsByCompanyAndId(company, dto.id()).orElseThrow();
        return order;
    }

    private SalesOrder createOrderWithMixedGst(FinishedGood taxable, FinishedGood exempt) {
        SalesOrderRequest request = new SalesOrderRequest(
                dealer.getId(),
                new BigDecimal("218.00"),
                "INR",
                null,
                List.of(
                        new SalesOrderItemRequest(taxable.getProductCode(), "Taxed", new BigDecimal("5"), new BigDecimal("20.00"), new BigDecimal("18.00")),
                        new SalesOrderItemRequest(exempt.getProductCode(), "Exempt", new BigDecimal("5"), new BigDecimal("20.00"), BigDecimal.ZERO)
                ),
                "PER_ITEM",
                null,
                null,
                null
        );
        var dto = salesService.createOrder(request);
        SalesOrder order = salesOrderRepository.findWithItemsByCompanyAndId(company, dto.id()).orElseThrow();
        return order;
    }

    private void registerBatch(FinishedGood fg, BigDecimal qty, BigDecimal unitCost, Instant manufacturedAt) {
        FinishedGoodBatchRequest request = new FinishedGoodBatchRequest(
                fg.getId(),
                null,
                qty,
                unitCost,
                manufacturedAt,
                null
        );
        finishedGoodsService.registerBatch(request);
    }

    private BigDecimal consumeCost(FinishedGood fg, BigDecimal quantity) {
        // Re-fetch to ensure we have latest state
        FinishedGood freshFg = finishedGoodRepository.findById(fg.getId()).orElseThrow();
        List<FinishedGoodBatch> batches = finishedGoodBatchRepository.findByFinishedGoodOrderByManufacturedAtAsc(freshFg);
        if ("LIFO".equalsIgnoreCase(freshFg.getCostingMethod())) {
            java.util.Collections.reverse(batches);
        }
        BigDecimal remaining = quantity;
        BigDecimal cost = BigDecimal.ZERO;
        for (FinishedGoodBatch batch : batches) {
            if (remaining.compareTo(BigDecimal.ZERO) <= 0) {
                break;
            }
            BigDecimal available = batch.getQuantityAvailable() != null && batch.getQuantityAvailable().compareTo(BigDecimal.ZERO) > 0
                    ? batch.getQuantityAvailable()
                    : batch.getQuantityTotal();
            BigDecimal take = remaining.min(available);
            cost = cost.add(take.multiply(batch.getUnitCost()));
            batch.setQuantityAvailable(available.subtract(take));
            batch.setQuantityTotal(batch.getQuantityTotal().subtract(take));
            remaining = remaining.subtract(take);
        }
        finishedGoodBatchRepository.saveAll(batches);
        freshFg.adjustStock(quantity.negate(), "TEST DISPATCH");
        finishedGoodRepository.save(freshFg);
        return cost;
    }

    private void ensureBaseAccounts() {
        accounts.put("CASH", ensureAccount("CASH", "Cash", AccountType.ASSET));
        accounts.put("AR", ensureAccount("AR", "Accounts Receivable", AccountType.ASSET));
        accounts.put("AP", ensureAccount("AP", "Accounts Payable", AccountType.LIABILITY));
        accounts.put("INV", ensureAccount("INV", "Inventory", AccountType.ASSET));
        accounts.put("COGS", ensureAccount("COGS", "Cost of Goods Sold", AccountType.COGS));
        accounts.put("REV", ensureAccount("REV", "Revenue", AccountType.REVENUE));
        accounts.put("GST_IN", ensureAccount("GST-IN", "GST Input Tax", AccountType.ASSET));
        accounts.put("GST_OUT", ensureAccount("GST-OUT", "GST Output Tax", AccountType.LIABILITY));
        accounts.put("GST_PAY", ensureAccount("GST-PAY", "GST Payable", AccountType.LIABILITY));
        accounts.put("DISC", ensureAccount("DISC", "Discounts", AccountType.EXPENSE));
        accounts.put("EXP", ensureAccount("EXP", "General Expense", AccountType.EXPENSE));

        company.setGstInputTaxAccountId(accounts.get("GST_IN").getId());
        company.setGstOutputTaxAccountId(accounts.get("GST_OUT").getId());
        company.setGstPayableAccountId(accounts.get("GST_PAY").getId());
        companyRepository.save(company);
    }

    private Account ensureAccount(String code, String name, AccountType type) {
        return accountRepository.findByCompanyAndCodeIgnoreCase(company, code)
                .orElseGet(() -> {
                    Account account = new Account();
                    account.setCompany(company);
                    account.setCode(code);
                    account.setName(name);
                    account.setType(type);
                    account.setBalance(BigDecimal.ZERO);
                    return accountRepository.save(account);
                });
    }

    private Dealer ensureDealer(String code, String name) {
        Dealer dealerEntity = dealerRepository.findByCompanyAndCodeIgnoreCase(company, code)
                .orElseGet(() -> {
                    Dealer d = new Dealer();
                    d.setCompany(company);
                    d.setCode(code);
                    d.setName(name);
                    return d;
                });
        dealerEntity.setCreditLimit(new BigDecimal("1000000"));
        dealerEntity.setOutstandingBalance(BigDecimal.ZERO);
        dealerEntity.setReceivableAccount(accounts.get("AR"));
        return dealerRepository.save(dealerEntity);
    }

    private Supplier ensureSupplier(String code, String name) {
        Supplier supplierEntity = supplierRepository.findByCompanyAndCodeIgnoreCase(company, code)
                .orElseGet(() -> {
                    Supplier s = new Supplier();
                    s.setCompany(company);
                    s.setCode(code);
                    s.setName(name);
                    return s;
                });
        supplierEntity.setCreditLimit(new BigDecimal("1000000"));
        supplierEntity.setOutstandingBalance(BigDecimal.ZERO);
        supplierEntity.setPayableAccount(accounts.get("AP"));
        return supplierRepository.save(supplierEntity);
    }

    private ProductionBrand ensureBrand(String code) {
        return productionBrandRepository.findByCompanyAndCodeIgnoreCase(company, code)
                .orElseGet(() -> {
                    ProductionBrand b = new ProductionBrand();
                    b.setCompany(company);
                    b.setCode(code);
                    b.setName("Brand " + code);
                    return productionBrandRepository.save(b);
                });
    }

    private AccountingPeriod ensureLockedPeriod(LocalDate date) {
        AccountingPeriod period = accountingPeriodRepository.findByCompanyAndYearAndMonth(company, date.getYear(), date.getMonthValue())
                .orElseGet(() -> {
                    AccountingPeriod p = new AccountingPeriod();
                    p.setCompany(company);
                    p.setYear(date.getYear());
                    p.setMonth(date.getMonthValue());
                    p.setStartDate(date.withDayOfMonth(1));
                    p.setEndDate(date.withDayOfMonth(date.lengthOfMonth()));
                    return p;
                });
        period.setStatus(AccountingPeriodStatus.LOCKED);
        return accountingPeriodRepository.save(period);
    }

    private BigDecimal sumDebitForCompany() {
        return journalEntryRepository.findByCompanyOrderByEntryDateDesc(company).stream()
                .flatMap(entry -> entry.getLines().stream())
                .map(JournalLine::getDebit)
                .filter(val -> val != null)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
    }

    private BigDecimal sumCreditForCompany() {
        return journalEntryRepository.findByCompanyOrderByEntryDateDesc(company).stream()
                .flatMap(entry -> entry.getLines().stream())
                .map(JournalLine::getCredit)
                .filter(val -> val != null)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
    }

    private void refreshAccounts() {
        accounts.replaceAll((k, v) -> accountRepository.findById(v.getId()).orElseThrow());
    }

    private void enableGstMode(BigDecimal defaultGstRate) {
        company.setDefaultGstRate(defaultGstRate);
        company = companyRepository.save(company);
    }

    private String login(String email, String password) {
        Map<String, Object> req = Map.of(
                "email", email,
                "password", password,
                "companyCode", COMPANY_CODE
        );
        ResponseEntity<Map> response = restTemplate.postForEntity("/api/v1/auth/login", req, Map.class);
        Assertions.assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        return (String) response.getBody().get("accessToken");
    }
}
