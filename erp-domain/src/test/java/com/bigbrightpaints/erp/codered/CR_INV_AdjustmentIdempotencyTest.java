package com.bigbrightpaints.erp.codered;

import com.bigbrightpaints.erp.codered.support.CoderedConcurrencyHarness;
import com.bigbrightpaints.erp.codered.support.CoderedRetry;
import com.bigbrightpaints.erp.core.exception.ApplicationException;
import com.bigbrightpaints.erp.core.security.CompanyContextHolder;
import com.bigbrightpaints.erp.modules.accounting.domain.Account;
import com.bigbrightpaints.erp.modules.accounting.domain.AccountRepository;
import com.bigbrightpaints.erp.modules.accounting.domain.AccountType;
import com.bigbrightpaints.erp.modules.accounting.domain.JournalEntry;
import com.bigbrightpaints.erp.modules.accounting.domain.JournalEntryRepository;
import com.bigbrightpaints.erp.modules.company.domain.Company;
import com.bigbrightpaints.erp.modules.company.domain.CompanyRepository;
import com.bigbrightpaints.erp.modules.inventory.domain.FinishedGood;
import com.bigbrightpaints.erp.modules.inventory.domain.FinishedGoodBatch;
import com.bigbrightpaints.erp.modules.inventory.domain.FinishedGoodBatchRepository;
import com.bigbrightpaints.erp.modules.inventory.domain.FinishedGoodRepository;
import com.bigbrightpaints.erp.modules.inventory.domain.InventoryAdjustment;
import com.bigbrightpaints.erp.modules.inventory.domain.InventoryAdjustmentRepository;
import com.bigbrightpaints.erp.modules.inventory.domain.InventoryAdjustmentType;
import com.bigbrightpaints.erp.modules.inventory.dto.FinishedGoodBatchRequest;
import com.bigbrightpaints.erp.modules.inventory.dto.FinishedGoodRequest;
import com.bigbrightpaints.erp.modules.inventory.dto.InventoryAdjustmentDto;
import com.bigbrightpaints.erp.modules.inventory.dto.InventoryAdjustmentRequest;
import com.bigbrightpaints.erp.modules.inventory.service.FinishedGoodsService;
import com.bigbrightpaints.erp.modules.inventory.service.InventoryAdjustmentService;
import com.bigbrightpaints.erp.modules.sales.domain.SalesOrder;
import com.bigbrightpaints.erp.modules.sales.domain.SalesOrderItem;
import com.bigbrightpaints.erp.modules.sales.domain.SalesOrderRepository;
import com.bigbrightpaints.erp.modules.inventory.domain.PackagingSlipRepository;
import com.bigbrightpaints.erp.test.AbstractIntegrationTest;
import com.bigbrightpaints.erp.test.support.TestDateUtils;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
@Tag("critical")
@Tag("concurrency")

class CR_INV_AdjustmentIdempotencyTest extends AbstractIntegrationTest {

    @Autowired private CompanyRepository companyRepository;
    @Autowired private AccountRepository accountRepository;
    @Autowired private FinishedGoodsService finishedGoodsService;
    @Autowired private FinishedGoodBatchRepository finishedGoodBatchRepository;
    @Autowired private FinishedGoodRepository finishedGoodRepository;
    @Autowired private InventoryAdjustmentService inventoryAdjustmentService;
    @Autowired private InventoryAdjustmentRepository inventoryAdjustmentRepository;
    @Autowired private JournalEntryRepository journalEntryRepository;
    @Autowired private SalesOrderRepository salesOrderRepository;
    @Autowired private PackagingSlipRepository packagingSlipRepository;

    @AfterEach
    void clearCompanyContext() {
        CompanyContextHolder.clear();
    }

    @Test
    void adjustment_usesAdjustmentDate_forJournal() {
        String companyCode = "CR-INV-ADJ-DATE-" + shortId();
        Company company = bootstrapCompany(companyCode);
        Map<String, Account> accounts = ensureAccounts(company);

        FinishedGood fg = ensureFinishedGood(company, "FG-ADJ-DATE-" + shortId(), accounts);
        seedBatch(company, fg, new BigDecimal("50"), new BigDecimal("12.50"));

        LocalDate adjustmentDate = TestDateUtils.safeDate(company);
        InventoryAdjustmentRequest request = new InventoryAdjustmentRequest(
                adjustmentDate,
                InventoryAdjustmentType.DAMAGED,
                accounts.get("VAR").getId(),
                "CODE-RED adjustment date",
                false,
                "INV-ADJ-" + UUID.randomUUID(),
                List.of(new InventoryAdjustmentRequest.LineRequest(
                        fg.getId(),
                        new BigDecimal("5"),
                        new BigDecimal("12.50"),
                        "Damaged batch"
                ))
        );

        CompanyContextHolder.setCompanyId(companyCode);
        InventoryAdjustmentDto result = inventoryAdjustmentService.createAdjustment(request);
        CompanyContextHolder.clear();

        JournalEntry journal = journalEntryRepository.findById(result.journalEntryId()).orElseThrow();
        assertThat(journal.getEntryDate()).isEqualTo(adjustmentDate);
    }

    @Test
    void adjustment_idempotentOnRetry_andMismatchFailsClosed() {
        String companyCode = "CR-INV-ADJ-IDEMP-" + shortId();
        Company company = bootstrapCompany(companyCode);
        Map<String, Account> accounts = ensureAccounts(company);

        FinishedGood fg = ensureFinishedGood(company, "FG-ADJ-IDEMP-" + shortId(), accounts);
        seedBatch(company, fg, new BigDecimal("80"), new BigDecimal("10.00"));

        String idempotencyKey = "INV-ADJ-" + UUID.randomUUID();
        LocalDate adjustmentDate = TestDateUtils.safeDate(company);
        InventoryAdjustmentRequest baseRequest = new InventoryAdjustmentRequest(
                adjustmentDate,
                InventoryAdjustmentType.DAMAGED,
                accounts.get("VAR").getId(),
                "CODE-RED adjustment",
                false,
                idempotencyKey,
                List.of(new InventoryAdjustmentRequest.LineRequest(
                        fg.getId(),
                        new BigDecimal("4"),
                        new BigDecimal("10.00"),
                        "Damaged"
                ))
        );

        CompanyContextHolder.setCompanyId(companyCode);
        InventoryAdjustmentDto first = inventoryAdjustmentService.createAdjustment(baseRequest);
        InventoryAdjustmentDto second = inventoryAdjustmentService.createAdjustment(baseRequest);
        CompanyContextHolder.clear();

        assertThat(second.id()).isEqualTo(first.id());
        InventoryAdjustment stored = inventoryAdjustmentRepository
                .findByCompanyAndIdempotencyKey(company, idempotencyKey)
                .orElseThrow();
        assertThat(stored.getJournalEntryId()).isEqualTo(first.journalEntryId());

        InventoryAdjustmentRequest mismatch = new InventoryAdjustmentRequest(
                adjustmentDate,
                InventoryAdjustmentType.DAMAGED,
                accounts.get("VAR").getId(),
                "CODE-RED adjustment",
                false,
                idempotencyKey,
                List.of(new InventoryAdjustmentRequest.LineRequest(
                        fg.getId(),
                        new BigDecimal("5"),
                        new BigDecimal("10.00"),
                        "Damaged"
                ))
        );

        CompanyContextHolder.setCompanyId(companyCode);
        assertThatThrownBy(() -> inventoryAdjustmentService.createAdjustment(mismatch))
                .isInstanceOf(ApplicationException.class)
                .hasMessageContaining("Idempotency key already used");
        CompanyContextHolder.clear();
    }

    @Test
    void adjustment_idempotentUnderConcurrency_returnsSingleAdjustment() {
        String companyCode = "CR-INV-ADJ-CONC-" + shortId();
        Company company = bootstrapCompany(companyCode);
        Map<String, Account> accounts = ensureAccounts(company);

        FinishedGood fg = ensureFinishedGood(company, "FG-ADJ-CONC-" + shortId(), accounts);
        seedBatch(company, fg, new BigDecimal("100"), new BigDecimal("8.00"));

        String idempotencyKey = "INV-ADJ-" + UUID.randomUUID();
        InventoryAdjustmentRequest request = new InventoryAdjustmentRequest(
                TestDateUtils.safeDate(company),
                InventoryAdjustmentType.DAMAGED,
                accounts.get("VAR").getId(),
                "CODE-RED concurrent adjustment",
                false,
                idempotencyKey,
                List.of(new InventoryAdjustmentRequest.LineRequest(
                        fg.getId(),
                        new BigDecimal("6"),
                        new BigDecimal("8.00"),
                        "Damaged"
                ))
        );

        var result = CoderedConcurrencyHarness.run(
                2,
                3,
                Duration.ofSeconds(30),
                idx -> () -> {
                    CompanyContextHolder.setCompanyId(companyCode);
                    try {
                        return inventoryAdjustmentService.createAdjustment(request);
                    } finally {
                        CompanyContextHolder.clear();
                    }
                },
                CoderedRetry::isRetryable
        );

        assertThat(result.outcomes())
                .allMatch(outcome -> outcome instanceof CoderedConcurrencyHarness.Outcome.Success<?>);

        List<Long> adjustmentIds = result.outcomes().stream()
                .map(outcome -> ((CoderedConcurrencyHarness.Outcome.Success<InventoryAdjustmentDto>) outcome).value())
                .map(InventoryAdjustmentDto::id)
                .distinct()
                .toList();
        assertThat(adjustmentIds).as("Concurrent callers converge on one adjustment").hasSize(1);

        InventoryAdjustment stored = inventoryAdjustmentRepository
                .findByCompanyAndIdempotencyKey(company, idempotencyKey)
                .orElseThrow();
        assertThat(stored.getId()).isEqualTo(adjustmentIds.getFirst());
    }

    @Test
    void adjustment_wacSettingFallsBackToFifoSelection_andIdempotentReplayDoesNotDoubleDeplete() {
        String companyCode = "CR-INV-ADJ-WAC-" + shortId();
        Company company = bootstrapCompany(companyCode);
        Map<String, Account> accounts = ensureAccounts(company);

        FinishedGood fg = ensureFinishedGood(company, "FG-ADJ-WAC-" + shortId(), accounts, "WAC");
        Long olderBatchId = seedBatchWithMetadata(
                company,
                fg,
                "BATCH-ADJ-WAC-OLD-" + shortId(),
                new BigDecimal("3"),
                new BigDecimal("10.00"),
                Instant.now().minus(Duration.ofHours(2)),
                LocalDate.now().plusDays(30));
        Long soonerExpiryBatchId = seedBatchWithMetadata(
                company,
                fg,
                "BATCH-ADJ-WAC-SOON-" + shortId(),
                new BigDecimal("3"),
                new BigDecimal("11.00"),
                Instant.now().minus(Duration.ofHours(1)),
                LocalDate.now().plusDays(3));

        String idempotencyKey = "INV-ADJ-WAC-" + UUID.randomUUID();
        InventoryAdjustmentRequest request = new InventoryAdjustmentRequest(
                TestDateUtils.safeDate(company),
                InventoryAdjustmentType.DAMAGED,
                accounts.get("VAR").getId(),
                "WAC expiry-priority adjustment",
                false,
                idempotencyKey,
                List.of(new InventoryAdjustmentRequest.LineRequest(
                        fg.getId(),
                        new BigDecimal("2"),
                        new BigDecimal("10.00"),
                        "WAC adjustment"
                ))
        );

        CompanyContextHolder.setCompanyId(companyCode);
        InventoryAdjustmentDto first = inventoryAdjustmentService.createAdjustment(request);
        CompanyContextHolder.clear();

        FinishedGoodBatch soonerExpiryAfterFirst = finishedGoodBatchRepository.findById(soonerExpiryBatchId).orElseThrow();
        FinishedGoodBatch olderAfterFirst = finishedGoodBatchRepository.findById(olderBatchId).orElseThrow();
        assertThat(soonerExpiryAfterFirst.getQuantityTotal()).isEqualByComparingTo(new BigDecimal("3"));
        assertThat(soonerExpiryAfterFirst.getQuantityAvailable()).isEqualByComparingTo(new BigDecimal("3"));
        assertThat(olderAfterFirst.getQuantityTotal()).isEqualByComparingTo(new BigDecimal("1"));
        assertThat(olderAfterFirst.getQuantityAvailable()).isEqualByComparingTo(new BigDecimal("1"));

        CompanyContextHolder.setCompanyId(companyCode);
        InventoryAdjustmentDto second = inventoryAdjustmentService.createAdjustment(request);
        CompanyContextHolder.clear();
        assertThat(second.id()).isEqualTo(first.id());

        FinishedGoodBatch soonerExpiryAfterReplay = finishedGoodBatchRepository.findById(soonerExpiryBatchId).orElseThrow();
        FinishedGoodBatch olderAfterReplay = finishedGoodBatchRepository.findById(olderBatchId).orElseThrow();
        assertThat(soonerExpiryAfterReplay.getQuantityTotal()).isEqualByComparingTo(new BigDecimal("3"));
        assertThat(soonerExpiryAfterReplay.getQuantityAvailable()).isEqualByComparingTo(new BigDecimal("3"));
        assertThat(olderAfterReplay.getQuantityTotal()).isEqualByComparingTo(new BigDecimal("1"));
        assertThat(olderAfterReplay.getQuantityAvailable()).isEqualByComparingTo(new BigDecimal("1"));
    }

    @Test
    void adjustment_wacUsesStableBatchIdTieBreak_whenExpiryAndManufacturedAtMatch() {
        String companyCode = "CR-INV-ADJ-WAC-TIE-" + shortId();
        Company company = bootstrapCompany(companyCode);
        Map<String, Account> accounts = ensureAccounts(company);

        FinishedGood fg = ensureFinishedGood(company, "FG-ADJ-WAC-TIE-" + shortId(), accounts, "WAC");
        Instant sameManufacturedAt = Instant.now().minus(Duration.ofHours(2));
        LocalDate sameExpiryDate = LocalDate.now().plusDays(14);

        Long firstBatchId = seedBatchWithMetadata(
                company,
                fg,
                "BATCH-ADJ-WAC-TIE-A-" + shortId(),
                new BigDecimal("3"),
                new BigDecimal("10.00"),
                sameManufacturedAt,
                sameExpiryDate);
        Long secondBatchId = seedBatchWithMetadata(
                company,
                fg,
                "BATCH-ADJ-WAC-TIE-B-" + shortId(),
                new BigDecimal("3"),
                new BigDecimal("11.00"),
                sameManufacturedAt,
                sameExpiryDate);
        assertThat(firstBatchId).isLessThan(secondBatchId);

        String idempotencyKey = "INV-ADJ-WAC-TIE-" + UUID.randomUUID();
        InventoryAdjustmentRequest request = new InventoryAdjustmentRequest(
                TestDateUtils.safeDate(company),
                InventoryAdjustmentType.DAMAGED,
                accounts.get("VAR").getId(),
                "WAC tie-break adjustment",
                false,
                idempotencyKey,
                List.of(new InventoryAdjustmentRequest.LineRequest(
                        fg.getId(),
                        new BigDecimal("2"),
                        new BigDecimal("10.00"),
                        "WAC tie adjustment"
                ))
        );

        CompanyContextHolder.setCompanyId(companyCode);
        InventoryAdjustmentDto first = inventoryAdjustmentService.createAdjustment(request);
        CompanyContextHolder.clear();

        FinishedGoodBatch firstBatchAfter = finishedGoodBatchRepository.findById(firstBatchId).orElseThrow();
        FinishedGoodBatch secondBatchAfter = finishedGoodBatchRepository.findById(secondBatchId).orElseThrow();
        assertThat(firstBatchAfter.getQuantityTotal()).isEqualByComparingTo(BigDecimal.ONE);
        assertThat(firstBatchAfter.getQuantityAvailable()).isEqualByComparingTo(BigDecimal.ONE);
        assertThat(secondBatchAfter.getQuantityTotal()).isEqualByComparingTo(new BigDecimal("3"));
        assertThat(secondBatchAfter.getQuantityAvailable()).isEqualByComparingTo(new BigDecimal("3"));

        CompanyContextHolder.setCompanyId(companyCode);
        InventoryAdjustmentDto second = inventoryAdjustmentService.createAdjustment(request);
        CompanyContextHolder.clear();
        assertThat(second.id()).isEqualTo(first.id());

        FinishedGoodBatch firstBatchAfterReplay = finishedGoodBatchRepository.findById(firstBatchId).orElseThrow();
        FinishedGoodBatch secondBatchAfterReplay = finishedGoodBatchRepository.findById(secondBatchId).orElseThrow();
        assertThat(firstBatchAfterReplay.getQuantityTotal()).isEqualByComparingTo(BigDecimal.ONE);
        assertThat(firstBatchAfterReplay.getQuantityAvailable()).isEqualByComparingTo(BigDecimal.ONE);
        assertThat(secondBatchAfterReplay.getQuantityTotal()).isEqualByComparingTo(new BigDecimal("3"));
        assertThat(secondBatchAfterReplay.getQuantityAvailable()).isEqualByComparingTo(new BigDecimal("3"));
    }

    @Test
    void adjustment_legacyWeightedAverageAlias_usesFifoOrder_underTurkishLocale() {
        String companyCode = "CR-INV-ADJ-WAC-LOC-" + shortId();
        Company company = bootstrapCompany(companyCode);
        Map<String, Account> accounts = ensureAccounts(company);

        FinishedGood fg = ensureFinishedGood(company, "FG-ADJ-WAC-LOC-" + shortId(), accounts, "WAC");
        fg.setCostingMethod("weighted-average");
        fg = finishedGoodRepository.saveAndFlush(fg);

        Long olderBatchId = seedBatchWithMetadata(
                company,
                fg,
                "BATCH-ADJ-WAC-LOC-OLD-" + shortId(),
                new BigDecimal("3"),
                new BigDecimal("10.00"),
                Instant.now().minus(Duration.ofHours(2)),
                LocalDate.now().plusDays(30));
        Long soonerExpiryBatchId = seedBatchWithMetadata(
                company,
                fg,
                "BATCH-ADJ-WAC-LOC-SOON-" + shortId(),
                new BigDecimal("3"),
                new BigDecimal("11.00"),
                Instant.now().minus(Duration.ofHours(1)),
                LocalDate.now().plusDays(3));

        InventoryAdjustmentRequest request = new InventoryAdjustmentRequest(
                TestDateUtils.safeDate(company),
                InventoryAdjustmentType.DAMAGED,
                accounts.get("VAR").getId(),
                "WAC legacy alias locale adjustment",
                false,
                "INV-ADJ-WAC-LOC-" + UUID.randomUUID(),
                List.of(new InventoryAdjustmentRequest.LineRequest(
                        fg.getId(),
                        new BigDecimal("2"),
                        new BigDecimal("10.00"),
                        "WAC adjustment locale"
                ))
        );

        Locale previous = Locale.getDefault();
        Locale.setDefault(Locale.forLanguageTag("tr-TR"));
        try {
            CompanyContextHolder.setCompanyId(companyCode);
            inventoryAdjustmentService.createAdjustment(request);
            CompanyContextHolder.clear();
        } finally {
            Locale.setDefault(previous);
            CompanyContextHolder.clear();
        }

        FinishedGoodBatch soonerExpiryAfter = finishedGoodBatchRepository.findById(soonerExpiryBatchId).orElseThrow();
        FinishedGoodBatch olderAfter = finishedGoodBatchRepository.findById(olderBatchId).orElseThrow();
        assertThat(soonerExpiryAfter.getQuantityTotal()).isEqualByComparingTo(new BigDecimal("3"));
        assertThat(soonerExpiryAfter.getQuantityAvailable()).isEqualByComparingTo(new BigDecimal("3"));
        assertThat(olderAfter.getQuantityTotal()).isEqualByComparingTo(new BigDecimal("1"));
        assertThat(olderAfter.getQuantityAvailable()).isEqualByComparingTo(new BigDecimal("1"));
    }

    @Test
    void dispatch_usesFifoCost_whenCurrentPeriodDefaultsToFifo() {
        String companyCode = "CR-INV-DISP-WAC-" + shortId();
        Company company = bootstrapCompany(companyCode);
        Map<String, Account> accounts = ensureAccounts(company);

        FinishedGood fg = ensureFinishedGood(company, "FG-DISP-WAC-" + shortId(), accounts, "WAC");
        Long fifoPreferredBatchId = seedBatchWithMetadata(
                company,
                fg,
                "BATCH-DISP-WAC-FIFO-" + shortId(),
                new BigDecimal("3"),
                new BigDecimal("8.00"),
                Instant.now().minus(Duration.ofHours(2)),
                LocalDate.now().plusDays(30));
        Long wacExpiryBatchId = seedBatchWithMetadata(
                company,
                fg,
                "BATCH-DISP-WAC-EXP-" + shortId(),
                new BigDecimal("3"),
                new BigDecimal("20.00"),
                Instant.now().minus(Duration.ofHours(1)),
                LocalDate.now().plusDays(3));

        SalesOrder order = createOrder(
                company,
                "SO-DISP-WAC-" + shortId(),
                fg.getProductCode(),
                new BigDecimal("2"));

        CompanyContextHolder.setCompanyId(companyCode);
        try {
            finishedGoodsService.reserveForOrder(order);
            var postings = finishedGoodsService.markSlipDispatched(order.getId());
            assertThat(postings).hasSize(1);
            assertThat(postings.getFirst().cost()).isEqualByComparingTo(new BigDecimal("16.00"));
            // Guard against regressions to weighted-average/expiry-first paths.
            assertThat(postings.getFirst().cost()).isNotEqualByComparingTo(new BigDecimal("28.00"));
            assertThat(postings.getFirst().cost()).isNotEqualByComparingTo(new BigDecimal("40.00"));
        } finally {
            CompanyContextHolder.clear();
        }

        FinishedGoodBatch fifoPreferredAfter = finishedGoodBatchRepository.findById(fifoPreferredBatchId).orElseThrow();
        FinishedGoodBatch wacExpiryAfter = finishedGoodBatchRepository.findById(wacExpiryBatchId).orElseThrow();
        assertThat(fifoPreferredAfter.getQuantityTotal()).isEqualByComparingTo(BigDecimal.ONE);
        assertThat(fifoPreferredAfter.getQuantityAvailable()).isEqualByComparingTo(BigDecimal.ONE);
        assertThat(wacExpiryAfter.getQuantityTotal()).isEqualByComparingTo(new BigDecimal("3"));
        assertThat(wacExpiryAfter.getQuantityAvailable()).isEqualByComparingTo(new BigDecimal("3"));
    }

    @Test
    void reserveAndAdjustment_selectSameBatchBranchAcrossCostingMethods() {
        String companyCode = "CR-INV-SEL-PAR-" + shortId();
        Company company = bootstrapCompany(companyCode);
        Map<String, Account> accounts = ensureAccounts(company);

        assertReserveAdjustmentSelectorParity(companyCode, company, accounts, "FIFO", true);
        assertReserveAdjustmentSelectorParity(companyCode, company, accounts, "LIFO", true);
        assertReserveAdjustmentSelectorParity(companyCode, company, accounts, "WAC", true);
    }

    private Company bootstrapCompany(String companyCode) {
        dataSeeder.ensureCompany(companyCode, companyCode + " Ltd");
        CompanyContextHolder.setCompanyId(companyCode);
        Company company = companyRepository.findByCodeIgnoreCase(companyCode).orElseThrow();
        company.setTimezone("UTC");
        company.setBaseCurrency("INR");
        return companyRepository.save(company);
    }

    private Map<String, Account> ensureAccounts(Company company) {
        Account inventory = ensureAccount(company, "FG_INV", "Finished Goods Inventory", AccountType.ASSET);
        Account variance = ensureAccount(company, "INV_VAR", "Inventory Variance", AccountType.EXPENSE);
        Account cogs = ensureAccount(company, "COGS", "Cost of Goods Sold", AccountType.COGS);
        Account revenue = ensureAccount(company, "REV", "Revenue", AccountType.REVENUE);
        Account tax = ensureAccount(company, "GST_OUT", "GST Output", AccountType.LIABILITY);

        CompanyContextHolder.setCompanyId(company.getCode());
        Company fresh = companyRepository.findById(company.getId()).orElseThrow();
        if (fresh.getDefaultInventoryAccountId() == null) {
            fresh.setDefaultInventoryAccountId(inventory.getId());
        }
        if (fresh.getDefaultCogsAccountId() == null) {
            fresh.setDefaultCogsAccountId(cogs.getId());
        }
        if (fresh.getDefaultRevenueAccountId() == null) {
            fresh.setDefaultRevenueAccountId(revenue.getId());
        }
        if (fresh.getDefaultTaxAccountId() == null) {
            fresh.setDefaultTaxAccountId(tax.getId());
        }
        companyRepository.save(fresh);
        CompanyContextHolder.clear();

        return Map.of(
                "INV", inventory,
                "VAR", variance,
                "COGS", cogs,
                "REV", revenue,
                "TAX", tax
        );
    }

    private Account ensureAccount(Company company, String code, String name, AccountType type) {
        return accountRepository.findByCompanyAndCodeIgnoreCase(company, code)
                .orElseGet(() -> {
                    Account account = new Account();
                    account.setCompany(company);
                    account.setCode(code);
                    account.setName(name);
                    account.setType(type);
                    account.setActive(true);
                    account.setBalance(BigDecimal.ZERO);
                    return accountRepository.save(account);
                });
    }

    private FinishedGood ensureFinishedGood(Company company, String sku, Map<String, Account> accounts) {
        return ensureFinishedGood(company, sku, accounts, "FIFO");
    }

    private FinishedGood ensureFinishedGood(Company company, String sku, Map<String, Account> accounts, String costingMethod) {
        CompanyContextHolder.setCompanyId(company.getCode());
        FinishedGoodRequest request = new FinishedGoodRequest(
                sku,
                sku,
                "PCS",
                costingMethod,
                accounts.get("INV").getId(),
                accounts.get("COGS").getId(),
                accounts.get("REV").getId(),
                null,
                accounts.get("TAX").getId()
        );
        FinishedGood fg = finishedGoodRepository.findByCompanyAndProductCode(company, sku)
                .orElseGet(() -> {
                    var dto = finishedGoodsService.createFinishedGood(request);
                    return finishedGoodRepository.findById(dto.id()).orElseThrow();
                });
        CompanyContextHolder.clear();
        return fg;
    }

    private void seedBatch(Company company, FinishedGood finishedGood, BigDecimal quantity, BigDecimal unitCost) {
        CompanyContextHolder.setCompanyId(company.getCode());
        finishedGoodsService.registerBatch(new FinishedGoodBatchRequest(
                finishedGood.getId(),
                "BATCH-" + shortId(),
                quantity,
                unitCost,
                Instant.now(),
                null
        ));
        CompanyContextHolder.clear();
    }

    private Long seedBatchWithMetadata(Company company,
                                       FinishedGood finishedGood,
                                       String batchCode,
                                       BigDecimal quantity,
                                       BigDecimal unitCost,
                                       Instant manufacturedAt,
                                       LocalDate expiryDate) {
        CompanyContextHolder.setCompanyId(company.getCode());
        try {
            finishedGoodsService.registerBatch(new FinishedGoodBatchRequest(
                    finishedGood.getId(),
                    batchCode,
                    quantity,
                    unitCost,
                    manufacturedAt,
                    expiryDate
            ));
            return finishedGoodBatchRepository.findByFinishedGoodAndBatchCode(finishedGood, batchCode)
                    .map(FinishedGoodBatch::getId)
                    .orElseThrow();
        } finally {
            CompanyContextHolder.clear();
        }
    }

    private void assertReserveAdjustmentSelectorParity(String companyCode,
                                                       Company company,
                                                       Map<String, Account> accounts,
                                                       String costingMethod,
                                                       boolean expectedPrimarySelection) {
        String fixtureId = costingMethod + "-" + shortId();
        Instant primaryManufacturedAt = Instant.now().minus(Duration.ofHours(4));
        Instant secondaryManufacturedAt = Instant.now().minus(Duration.ofHours(1));
        LocalDate primaryExpiry = LocalDate.now().plusDays(30);
        LocalDate secondaryExpiry = LocalDate.now().plusDays(3);

        FinishedGood reserveFinishedGood = ensureFinishedGood(
                company,
                "FG-RES-" + fixtureId,
                accounts,
                costingMethod);
        Long reservePrimaryBatchId = seedBatchWithMetadata(
                company,
                reserveFinishedGood,
                "BATCH-RES-" + fixtureId + "-A",
                new BigDecimal("3"),
                new BigDecimal("10.00"),
                primaryManufacturedAt,
                primaryExpiry);
        Long reserveSecondaryBatchId = seedBatchWithMetadata(
                company,
                reserveFinishedGood,
                "BATCH-RES-" + fixtureId + "-B",
                new BigDecimal("3"),
                new BigDecimal("11.00"),
                secondaryManufacturedAt,
                secondaryExpiry);

        Long reservedBatchId = reserveSingleUnitAndResolveBatch(companyCode, company, reserveFinishedGood);
        boolean reservePickedPrimary = reservePrimaryBatchId.equals(reservedBatchId);
        assertThat(reservedBatchId)
                .isIn(reservePrimaryBatchId, reserveSecondaryBatchId);

        FinishedGood adjustmentFinishedGood = ensureFinishedGood(
                company,
                "FG-ADJ-" + fixtureId,
                accounts,
                costingMethod);
        Long adjustmentPrimaryBatchId = seedBatchWithMetadata(
                company,
                adjustmentFinishedGood,
                "BATCH-ADJ-" + fixtureId + "-A",
                new BigDecimal("3"),
                new BigDecimal("10.00"),
                primaryManufacturedAt,
                primaryExpiry);
        Long adjustmentSecondaryBatchId = seedBatchWithMetadata(
                company,
                adjustmentFinishedGood,
                "BATCH-ADJ-" + fixtureId + "-B",
                new BigDecimal("3"),
                new BigDecimal("11.00"),
                secondaryManufacturedAt,
                secondaryExpiry);

        Long adjustedBatchId = applySingleUnitAdjustmentAndResolveBatch(
                companyCode,
                company,
                accounts,
                adjustmentFinishedGood,
                costingMethod,
                adjustmentPrimaryBatchId,
                adjustmentSecondaryBatchId);
        boolean adjustmentPickedPrimary = adjustmentPrimaryBatchId.equals(adjustedBatchId);

        assertThat(adjustmentPickedPrimary).isEqualTo(reservePickedPrimary);
        assertThat(reservePickedPrimary).isEqualTo(expectedPrimarySelection);
    }

    private Long reserveSingleUnitAndResolveBatch(String companyCode, Company company, FinishedGood finishedGood) {
        SalesOrder order = createOrder(
                company,
                "SO-SEL-PAR-" + shortId(),
                finishedGood.getProductCode(),
                BigDecimal.ONE);

        CompanyContextHolder.setCompanyId(companyCode);
        try {
            finishedGoodsService.reserveForOrder(order);
        } finally {
            CompanyContextHolder.clear();
        }

        return packagingSlipRepository.findByCompanyAndSalesOrderId(company, order.getId())
                .filter(slip -> !slip.isBackorder())
                .orElseThrow()
                .getLines()
                .getFirst()
                .getFinishedGoodBatch()
                .getId();
    }

    private Long applySingleUnitAdjustmentAndResolveBatch(String companyCode,
                                                          Company company,
                                                          Map<String, Account> accounts,
                                                          FinishedGood finishedGood,
                                                          String costingMethod,
                                                          Long primaryBatchId,
                                                          Long secondaryBatchId) {
        InventoryAdjustmentRequest request = new InventoryAdjustmentRequest(
                TestDateUtils.safeDate(company),
                InventoryAdjustmentType.DAMAGED,
                accounts.get("VAR").getId(),
                "selector parity " + costingMethod,
                false,
                "INV-ADJ-SEL-" + UUID.randomUUID(),
                List.of(new InventoryAdjustmentRequest.LineRequest(
                        finishedGood.getId(),
                        BigDecimal.ONE,
                        new BigDecimal("10.00"),
                        "selector parity adjustment")));

        CompanyContextHolder.setCompanyId(companyCode);
        try {
            inventoryAdjustmentService.createAdjustment(request);
        } finally {
            CompanyContextHolder.clear();
        }

        FinishedGoodBatch primaryAfter = finishedGoodBatchRepository.findById(primaryBatchId).orElseThrow();
        FinishedGoodBatch secondaryAfter = finishedGoodBatchRepository.findById(secondaryBatchId).orElseThrow();

        boolean primaryDepletedByOne = primaryAfter.getQuantityTotal().compareTo(new BigDecimal("2")) == 0
                && primaryAfter.getQuantityAvailable().compareTo(new BigDecimal("2")) == 0;
        boolean secondaryDepletedByOne = secondaryAfter.getQuantityTotal().compareTo(new BigDecimal("2")) == 0
                && secondaryAfter.getQuantityAvailable().compareTo(new BigDecimal("2")) == 0;

        assertThat(primaryDepletedByOne ^ secondaryDepletedByOne).isTrue();
        return primaryDepletedByOne ? primaryBatchId : secondaryBatchId;
    }

    private SalesOrder createOrder(Company company, String orderNumber, String productCode, BigDecimal quantity) {
        SalesOrder order = new SalesOrder();
        order.setCompany(company);
        order.setOrderNumber(orderNumber);
        order.setStatus("PENDING");
        order.setTotalAmount(BigDecimal.ZERO);
        order.setCurrency("INR");

        SalesOrderItem item = new SalesOrderItem();
        item.setSalesOrder(order);
        item.setProductCode(productCode);
        item.setQuantity(quantity);
        item.setUnitPrice(BigDecimal.ONE);
        item.setLineSubtotal(BigDecimal.ZERO);
        item.setLineTotal(BigDecimal.ZERO);
        order.getItems().add(item);
        return salesOrderRepository.saveAndFlush(order);
    }

    private static String shortId() {
        return UUID.randomUUID().toString().substring(0, 8);
    }
}
