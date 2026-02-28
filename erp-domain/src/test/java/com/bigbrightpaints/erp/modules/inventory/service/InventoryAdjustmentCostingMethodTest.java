package com.bigbrightpaints.erp.modules.inventory.service;

import com.bigbrightpaints.erp.core.security.CompanyContextHolder;
import com.bigbrightpaints.erp.modules.accounting.domain.Account;
import com.bigbrightpaints.erp.modules.accounting.domain.AccountRepository;
import com.bigbrightpaints.erp.modules.accounting.domain.AccountType;
import com.bigbrightpaints.erp.modules.accounting.domain.AccountingPeriod;
import com.bigbrightpaints.erp.modules.accounting.domain.AccountingPeriodRepository;
import com.bigbrightpaints.erp.modules.accounting.domain.AccountingPeriodStatus;
import com.bigbrightpaints.erp.modules.accounting.domain.CostingMethod;
import com.bigbrightpaints.erp.modules.company.domain.Company;
import com.bigbrightpaints.erp.modules.inventory.domain.FinishedGood;
import com.bigbrightpaints.erp.modules.inventory.domain.FinishedGoodBatch;
import com.bigbrightpaints.erp.modules.inventory.domain.FinishedGoodBatchRepository;
import com.bigbrightpaints.erp.modules.inventory.domain.FinishedGoodRepository;
import com.bigbrightpaints.erp.modules.inventory.domain.InventoryAdjustment;
import com.bigbrightpaints.erp.modules.inventory.domain.InventoryAdjustmentRepository;
import com.bigbrightpaints.erp.modules.inventory.domain.InventoryAdjustmentType;
import com.bigbrightpaints.erp.modules.inventory.domain.InventoryMovement;
import com.bigbrightpaints.erp.modules.inventory.domain.InventoryMovementRepository;
import com.bigbrightpaints.erp.modules.inventory.dto.InventoryAdjustmentDto;
import com.bigbrightpaints.erp.modules.inventory.dto.InventoryAdjustmentRequest;
import com.bigbrightpaints.erp.test.AbstractIntegrationTest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@Transactional
class InventoryAdjustmentCostingMethodTest extends AbstractIntegrationTest {

    @Autowired
    private InventoryAdjustmentService inventoryAdjustmentService;

    @Autowired
    private InventoryAdjustmentRepository inventoryAdjustmentRepository;

    @Autowired
    private InventoryMovementRepository inventoryMovementRepository;

    @Autowired
    private FinishedGoodRepository finishedGoodRepository;

    @Autowired
    private FinishedGoodBatchRepository finishedGoodBatchRepository;

    @Autowired
    private AccountRepository accountRepository;

    @Autowired
    private AccountingPeriodRepository accountingPeriodRepository;

    @AfterEach
    void clearCompanyContext() {
        CompanyContextHolder.clear();
    }

    @Test
    void createAdjustment_fifoConsumesOldestBatchFirst() {
        Company company = seedCompany("ADJ-FIFO");
        Map<String, Account> accounts = ensureAccounts(company);
        LocalDate movementDate = LocalDate.now();
        upsertPeriod(company, movementDate, CostingMethod.FIFO);

        FinishedGood finishedGood = createFinishedGood(company, "FG-ADJ-FIFO", accounts);
        FinishedGoodBatch oldest = createBatch(finishedGood, "FIFO-OLD", "2", "2", "10", Instant.parse("2026-01-01T00:00:00Z"));
        FinishedGoodBatch newest = createBatch(finishedGood, "FIFO-NEW", "2", "2", "20", Instant.parse("2026-01-02T00:00:00Z"));

        InventoryAdjustment adjustment = createAdjustment(company, finishedGood.getId(), accounts.get("VAR").getId(), movementDate, new BigDecimal("3"));

        assertThat(adjustment.getTotalAmount()).isEqualByComparingTo(new BigDecimal("40.00"));
        assertThat(adjustment.getLines()).hasSize(1);
        assertThat(adjustment.getLines().getFirst().getAmount()).isEqualByComparingTo(new BigDecimal("40.00"));

        FinishedGoodBatch oldestAfter = finishedGoodBatchRepository.findById(oldest.getId()).orElseThrow();
        FinishedGoodBatch newestAfter = finishedGoodBatchRepository.findById(newest.getId()).orElseThrow();
        assertThat(oldestAfter.getQuantityTotal()).isEqualByComparingTo(BigDecimal.ZERO);
        assertThat(newestAfter.getQuantityTotal()).isEqualByComparingTo(new BigDecimal("1"));

        InventoryMovement movement = findAdjustmentMovement(adjustment);
        assertThat(movement.getUnitCost()).isEqualByComparingTo(new BigDecimal("13.3333"));
    }

    @Test
    void createAdjustment_lifoConsumesNewestBatchFirst() {
        Company company = seedCompany("ADJ-LIFO");
        Map<String, Account> accounts = ensureAccounts(company);
        LocalDate movementDate = LocalDate.now();
        upsertPeriod(company, movementDate, CostingMethod.LIFO);

        FinishedGood finishedGood = createFinishedGood(company, "FG-ADJ-LIFO", accounts);
        FinishedGoodBatch oldest = createBatch(finishedGood, "LIFO-OLD", "2", "2", "10", Instant.parse("2026-01-01T00:00:00Z"));
        FinishedGoodBatch newest = createBatch(finishedGood, "LIFO-NEW", "2", "2", "20", Instant.parse("2026-01-02T00:00:00Z"));

        InventoryAdjustment adjustment = createAdjustment(company, finishedGood.getId(), accounts.get("VAR").getId(), movementDate, new BigDecimal("3"));

        assertThat(adjustment.getTotalAmount()).isEqualByComparingTo(new BigDecimal("50.00"));
        assertThat(adjustment.getLines()).hasSize(1);
        assertThat(adjustment.getLines().getFirst().getAmount()).isEqualByComparingTo(new BigDecimal("50.00"));

        FinishedGoodBatch oldestAfter = finishedGoodBatchRepository.findById(oldest.getId()).orElseThrow();
        FinishedGoodBatch newestAfter = finishedGoodBatchRepository.findById(newest.getId()).orElseThrow();
        assertThat(oldestAfter.getQuantityTotal()).isEqualByComparingTo(new BigDecimal("1"));
        assertThat(newestAfter.getQuantityTotal()).isEqualByComparingTo(BigDecimal.ZERO);

        InventoryMovement movement = findAdjustmentMovement(adjustment);
        assertThat(movement.getUnitCost()).isEqualByComparingTo(new BigDecimal("16.6667"));
    }

    @Test
    void createAdjustment_weightedAverageAndPeriodChangeAffectsOnlyNewMovements() {
        Company company = seedCompany("ADJ-WA");
        Map<String, Account> accounts = ensureAccounts(company);

        LocalDate firstPeriodDate = LocalDate.now().minusDays(28);
        upsertPeriod(company, firstPeriodDate, CostingMethod.FIFO);
        FinishedGood finishedGood = createFinishedGood(company, "FG-ADJ-MULTI", accounts);
        createBatch(finishedGood, "JAN-OLD", "2", "2", "10", Instant.parse("2026-01-01T00:00:00Z"));
        createBatch(finishedGood, "JAN-NEW", "2", "2", "20", Instant.parse("2026-01-02T00:00:00Z"));
        InventoryAdjustment januaryAdjustment = createAdjustment(company, finishedGood.getId(), accounts.get("VAR").getId(), firstPeriodDate, BigDecimal.ONE);
        InventoryMovement januaryMovement = findAdjustmentMovement(januaryAdjustment);
        assertThat(januaryMovement.getUnitCost()).isEqualByComparingTo(new BigDecimal("10.0000"));

        LocalDate secondPeriodDate = LocalDate.now();
        upsertPeriod(company, secondPeriodDate, CostingMethod.WEIGHTED_AVERAGE);
        InventoryAdjustment februaryAdjustment = createAdjustment(company, finishedGood.getId(), accounts.get("VAR").getId(), secondPeriodDate, BigDecimal.ONE);
        InventoryMovement februaryMovement = findAdjustmentMovement(februaryAdjustment);
        assertThat(februaryMovement.getUnitCost()).isEqualByComparingTo(new BigDecimal("16.6667"));

        InventoryMovement januaryMovementReloaded = inventoryMovementRepository.findById(januaryMovement.getId()).orElseThrow();
        assertThat(januaryMovementReloaded.getUnitCost()).isEqualByComparingTo(new BigDecimal("10.0000"));
    }

    private Company seedCompany(String code) {
        Company company = dataSeeder.ensureCompany(code, code + " Ltd");
        CompanyContextHolder.setCompanyId(company.getCode());
        return company;
    }

    private Map<String, Account> ensureAccounts(Company company) {
        Account inventory = ensureAccount(company, "FG_INV_" + company.getCode(), "Finished Goods Inventory", AccountType.ASSET);
        Account variance = ensureAccount(company, "INV_VAR_" + company.getCode(), "Inventory Variance", AccountType.EXPENSE);
        Account cogs = ensureAccount(company, "COGS_" + company.getCode(), "Cost of Goods Sold", AccountType.COGS);
        Account revenue = ensureAccount(company, "REV_" + company.getCode(), "Revenue", AccountType.REVENUE);
        Account tax = ensureAccount(company, "TAX_" + company.getCode(), "GST", AccountType.LIABILITY);
        return Map.of("INV", inventory, "VAR", variance, "COGS", cogs, "REV", revenue, "TAX", tax);
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

    private void upsertPeriod(Company company, LocalDate referenceDate, CostingMethod costingMethod) {
        int year = referenceDate.getYear();
        int month = referenceDate.getMonthValue();
        AccountingPeriod period = accountingPeriodRepository.findByCompanyAndYearAndMonth(company, year, month)
                .orElseGet(() -> {
                    AccountingPeriod created = new AccountingPeriod();
                    created.setCompany(company);
                    created.setYear(year);
                    created.setMonth(month);
                    created.setStartDate(referenceDate.withDayOfMonth(1));
                    created.setEndDate(referenceDate.withDayOfMonth(1).plusMonths(1).minusDays(1));
                    created.setStatus(AccountingPeriodStatus.OPEN);
                    return created;
                });
        period.setCostingMethod(costingMethod);
        accountingPeriodRepository.saveAndFlush(period);
    }

    private FinishedGood createFinishedGood(Company company, String productCode, Map<String, Account> accounts) {
        FinishedGood finishedGood = new FinishedGood();
        finishedGood.setCompany(company);
        finishedGood.setProductCode(productCode + "-" + UUID.randomUUID().toString().substring(0, 6));
        finishedGood.setName(productCode);
        finishedGood.setUnit("PCS");
        finishedGood.setCostingMethod("FIFO");
        finishedGood.setCurrentStock(new BigDecimal("4"));
        finishedGood.setReservedStock(BigDecimal.ZERO);
        finishedGood.setValuationAccountId(accounts.get("INV").getId());
        finishedGood.setCogsAccountId(accounts.get("COGS").getId());
        finishedGood.setRevenueAccountId(accounts.get("REV").getId());
        finishedGood.setTaxAccountId(accounts.get("TAX").getId());
        return finishedGoodRepository.saveAndFlush(finishedGood);
    }

    private FinishedGoodBatch createBatch(FinishedGood finishedGood,
                                          String code,
                                          String quantityTotal,
                                          String quantityAvailable,
                                          String unitCost,
                                          Instant manufacturedAt) {
        FinishedGoodBatch batch = new FinishedGoodBatch();
        batch.setFinishedGood(finishedGood);
        batch.setBatchCode(code + "-" + UUID.randomUUID().toString().substring(0, 6));
        batch.setQuantityTotal(new BigDecimal(quantityTotal));
        batch.setQuantityAvailable(new BigDecimal(quantityAvailable));
        batch.setUnitCost(new BigDecimal(unitCost));
        batch.setManufacturedAt(manufacturedAt);
        return finishedGoodBatchRepository.saveAndFlush(batch);
    }

    private InventoryAdjustment createAdjustment(Company company,
                                                 Long finishedGoodId,
                                                 Long varianceAccountId,
                                                 LocalDate adjustmentDate,
                                                 BigDecimal quantity) {
        String idempotencyKey = "INV-ADJ-" + UUID.randomUUID();
        InventoryAdjustmentRequest request = new InventoryAdjustmentRequest(
                adjustmentDate,
                InventoryAdjustmentType.DAMAGED,
                varianceAccountId,
                "costing test",
                true,
                idempotencyKey,
                List.of(new InventoryAdjustmentRequest.LineRequest(
                        finishedGoodId,
                        quantity,
                        new BigDecimal("1.00"),
                        "costing test line"
                ))
        );

        CompanyContextHolder.setCompanyId(company.getCode());
        try {
            InventoryAdjustmentDto response = inventoryAdjustmentService.createAdjustment(request);
            return inventoryAdjustmentRepository.findById(response.id()).orElseThrow();
        } finally {
            CompanyContextHolder.clear();
        }
    }

    private InventoryMovement findAdjustmentMovement(InventoryAdjustment adjustment) {
        return inventoryMovementRepository
                .findByReferenceTypeAndReferenceIdOrderByCreatedAtAsc("ADJUSTMENT", adjustment.getReferenceNumber())
                .stream()
                .findFirst()
                .orElseThrow();
    }
}
