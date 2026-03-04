package com.bigbrightpaints.erp.modules.inventory.service;

import com.bigbrightpaints.erp.core.security.CompanyContextHolder;
import com.bigbrightpaints.erp.modules.accounting.domain.Account;
import com.bigbrightpaints.erp.modules.accounting.domain.AccountRepository;
import com.bigbrightpaints.erp.modules.accounting.domain.AccountType;
import com.bigbrightpaints.erp.modules.company.domain.Company;
import com.bigbrightpaints.erp.modules.company.domain.CompanyRepository;
import com.bigbrightpaints.erp.modules.inventory.domain.FinishedGood;
import com.bigbrightpaints.erp.modules.inventory.domain.FinishedGoodBatch;
import com.bigbrightpaints.erp.modules.inventory.domain.FinishedGoodBatchRepository;
import com.bigbrightpaints.erp.modules.inventory.domain.FinishedGoodRepository;
import com.bigbrightpaints.erp.modules.inventory.domain.InventoryBatchSource;
import com.bigbrightpaints.erp.modules.inventory.domain.RawMaterial;
import com.bigbrightpaints.erp.modules.inventory.domain.RawMaterialBatch;
import com.bigbrightpaints.erp.modules.inventory.domain.RawMaterialBatchRepository;
import com.bigbrightpaints.erp.modules.inventory.domain.RawMaterialRepository;
import com.bigbrightpaints.erp.modules.inventory.dto.InventoryExpiringBatchDto;
import com.bigbrightpaints.erp.test.AbstractIntegrationTest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@Tag("critical")
class InventoryBatchQueryServiceTest extends AbstractIntegrationTest {

    @Autowired
    private InventoryBatchQueryService inventoryBatchQueryService;

    @Autowired
    private RawMaterialRepository rawMaterialRepository;

    @Autowired
    private RawMaterialBatchRepository rawMaterialBatchRepository;

    @Autowired
    private FinishedGoodRepository finishedGoodRepository;

    @Autowired
    private FinishedGoodBatchRepository finishedGoodBatchRepository;

    @Autowired
    private CompanyRepository companyRepository;

    @Autowired
    private AccountRepository accountRepository;

    @AfterEach
    void clearCompanyContext() {
        CompanyContextHolder.clear();
    }

    @Test
    void listExpiringSoonBatches_returnsCombinedSortedRowsWithinWindow() {
        Company company = seedCompany("EXP-SOON");
        Account inventoryAccount = ensureInventoryAccount(company);

        RawMaterial rawMaterial = createRawMaterial(company, "RM-EXP-" + shortId(), inventoryAccount.getId());
        FinishedGood finishedGood = createFinishedGood(company, "FG-EXP-" + shortId(), inventoryAccount.getId());

        createRawBatch(rawMaterial, "RM-NEAR-A", LocalDate.now().plusDays(3), new BigDecimal("8.00"));
        createRawBatch(rawMaterial, "RM-NEAR-B", LocalDate.now().plusDays(10), new BigDecimal("9.00"));
        createRawBatch(rawMaterial, "RM-OUT", LocalDate.now().plusDays(45), new BigDecimal("7.00"));

        createFinishedBatch(finishedGood, "FG-NEAR", LocalDate.now().plusDays(6), new BigDecimal("4.00"));
        createFinishedBatch(finishedGood, "FG-OUT", LocalDate.now().plusDays(60), new BigDecimal("4.00"));

        List<InventoryExpiringBatchDto> rows;
        CompanyContextHolder.setCompanyCode(company.getCode());
        try {
            rows = inventoryBatchQueryService.listExpiringSoonBatches(30);
        } finally {
            CompanyContextHolder.clear();
        }

        assertThat(rows).hasSize(3);
        assertThat(rows)
                .extracting(InventoryExpiringBatchDto::batchCode)
                .containsExactly("RM-NEAR-A", "FG-NEAR", "RM-NEAR-B");

        assertThat(rows)
                .allMatch(row -> row.daysUntilExpiry() >= 0 && row.daysUntilExpiry() <= 30);
        assertThat(rows)
                .allMatch(row -> row.expiryDate() != null);
    }

    @Test
    void listExpiringSoonBatches_treatsNegativeDaysAsZero() {
        Company company = seedCompany("EXP-NEG");
        Account inventoryAccount = ensureInventoryAccount(company);

        RawMaterial rawMaterial = createRawMaterial(company, "RM-EXP-NEG-" + shortId(), inventoryAccount.getId());
        LocalDate today = LocalDate.now(ZoneOffset.UTC);
        createRawBatch(rawMaterial, "RM-TODAY", today, new BigDecimal("2.00"));
        createRawBatch(rawMaterial, "RM-TOMORROW", today.plusDays(1), new BigDecimal("2.00"));

        List<InventoryExpiringBatchDto> rows;
        CompanyContextHolder.setCompanyCode(company.getCode());
        try {
            rows = inventoryBatchQueryService.listExpiringSoonBatches(-7);
        } finally {
            CompanyContextHolder.clear();
        }

        assertThat(rows).extracting(InventoryExpiringBatchDto::batchCode).containsExactly("RM-TODAY");
        assertThat(rows.getFirst().daysUntilExpiry()).isZero();
    }

    private Company seedCompany(String code) {
        dataSeeder.ensureCompany(code, code + " Ltd");
        Company company = companyRepository.findByCodeIgnoreCase(code).orElseThrow();
        company.setTimezone("UTC");
        return companyRepository.saveAndFlush(company);
    }

    private Account ensureInventoryAccount(Company company) {
        Account account = accountRepository.findByCompanyAndCodeIgnoreCase(company, "INV-EXP-" + company.getCode())
                .orElseGet(() -> {
                    Account created = new Account();
                    created.setCompany(company);
                    created.setCode("INV-EXP-" + company.getCode());
                    created.setName("Inventory " + company.getCode());
                    created.setType(AccountType.ASSET);
                    created.setActive(true);
                    created.setBalance(BigDecimal.ZERO);
                    return accountRepository.saveAndFlush(created);
                });

        Company refreshed = companyRepository.findById(company.getId()).orElseThrow();
        if (refreshed.getDefaultInventoryAccountId() == null) {
            refreshed.setDefaultInventoryAccountId(account.getId());
            companyRepository.saveAndFlush(refreshed);
        }
        return account;
    }

    private RawMaterial createRawMaterial(Company company, String sku, Long inventoryAccountId) {
        RawMaterial material = new RawMaterial();
        material.setCompany(company);
        material.setName("Raw " + sku);
        material.setSku(sku);
        material.setUnitType("KG");
        material.setCurrentStock(new BigDecimal("50.00"));
        material.setInventoryAccountId(inventoryAccountId);
        return rawMaterialRepository.saveAndFlush(material);
    }

    private FinishedGood createFinishedGood(Company company, String code, Long inventoryAccountId) {
        FinishedGood finishedGood = new FinishedGood();
        finishedGood.setCompany(company);
        finishedGood.setProductCode(code + "-" + shortId());
        finishedGood.setName("Finished " + code);
        finishedGood.setUnit("PCS");
        finishedGood.setCurrentStock(new BigDecimal("25.00"));
        finishedGood.setReservedStock(BigDecimal.ZERO);
        finishedGood.setValuationAccountId(inventoryAccountId);
        finishedGood.setCogsAccountId(inventoryAccountId);
        finishedGood.setRevenueAccountId(inventoryAccountId);
        finishedGood.setTaxAccountId(inventoryAccountId);
        finishedGood.setCostingMethod("FIFO");
        return finishedGoodRepository.saveAndFlush(finishedGood);
    }

    private void createRawBatch(RawMaterial material, String code, LocalDate expiryDate, BigDecimal quantity) {
        RawMaterialBatch batch = new RawMaterialBatch();
        batch.setRawMaterial(material);
        batch.setBatchCode(code);
        batch.setQuantity(quantity);
        batch.setUnit("KG");
        batch.setCostPerUnit(new BigDecimal("11.00"));
        batch.setManufacturedAt(Instant.now().minusSeconds(86400));
        batch.setExpiryDate(expiryDate);
        batch.setSource(InventoryBatchSource.PURCHASE);
        rawMaterialBatchRepository.saveAndFlush(batch);
    }

    private void createFinishedBatch(FinishedGood finishedGood, String code, LocalDate expiryDate, BigDecimal quantity) {
        FinishedGoodBatch batch = new FinishedGoodBatch();
        batch.setFinishedGood(finishedGood);
        batch.setBatchCode(code);
        batch.setQuantityTotal(quantity);
        batch.setQuantityAvailable(quantity);
        batch.setUnitCost(new BigDecimal("14.00"));
        batch.setManufacturedAt(Instant.now().minusSeconds(7200));
        batch.setExpiryDate(expiryDate);
        batch.setSource(InventoryBatchSource.PRODUCTION);
        finishedGoodBatchRepository.saveAndFlush(batch);
    }

    private String shortId() {
        return UUID.randomUUID().toString().substring(0, 8);
    }
}
