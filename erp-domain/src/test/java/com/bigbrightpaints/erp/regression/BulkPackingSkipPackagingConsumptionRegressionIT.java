package com.bigbrightpaints.erp.regression;

import com.bigbrightpaints.erp.core.security.CompanyContextHolder;
import com.bigbrightpaints.erp.modules.accounting.domain.Account;
import com.bigbrightpaints.erp.modules.accounting.domain.AccountRepository;
import com.bigbrightpaints.erp.modules.accounting.domain.AccountType;
import com.bigbrightpaints.erp.modules.company.domain.Company;
import com.bigbrightpaints.erp.modules.factory.domain.PackagingSizeMapping;
import com.bigbrightpaints.erp.modules.factory.domain.PackagingSizeMappingRepository;
import com.bigbrightpaints.erp.modules.factory.dto.BulkPackRequest;
import com.bigbrightpaints.erp.modules.factory.dto.BulkPackResponse;
import com.bigbrightpaints.erp.modules.factory.service.BulkPackingService;
import com.bigbrightpaints.erp.modules.inventory.domain.FinishedGood;
import com.bigbrightpaints.erp.modules.inventory.domain.FinishedGoodBatch;
import com.bigbrightpaints.erp.modules.inventory.domain.FinishedGoodBatchRepository;
import com.bigbrightpaints.erp.modules.inventory.domain.FinishedGoodRepository;
import com.bigbrightpaints.erp.modules.inventory.domain.InventoryReference;
import com.bigbrightpaints.erp.modules.inventory.domain.MaterialType;
import com.bigbrightpaints.erp.modules.inventory.domain.RawMaterial;
import com.bigbrightpaints.erp.modules.inventory.domain.RawMaterialBatch;
import com.bigbrightpaints.erp.modules.inventory.domain.RawMaterialBatchRepository;
import com.bigbrightpaints.erp.modules.inventory.domain.RawMaterialMovementRepository;
import com.bigbrightpaints.erp.modules.inventory.domain.RawMaterialRepository;
import com.bigbrightpaints.erp.test.AbstractIntegrationTest;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("Regression: Bulk pack can skip packaging already consumed upstream")
class BulkPackingSkipPackagingConsumptionRegressionIT extends AbstractIntegrationTest {

    private static final String COMPANY_CODE = "LF-017";

    @Autowired private AccountRepository accountRepository;
    @Autowired private FinishedGoodRepository finishedGoodRepository;
    @Autowired private FinishedGoodBatchRepository finishedGoodBatchRepository;
    @Autowired private RawMaterialRepository rawMaterialRepository;
    @Autowired private RawMaterialBatchRepository rawMaterialBatchRepository;
    @Autowired private RawMaterialMovementRepository rawMaterialMovementRepository;
    @Autowired private PackagingSizeMappingRepository packagingSizeMappingRepository;
    @Autowired private BulkPackingService bulkPackingService;

    private Company company;
    private Account bulkInventory;
    private Account fgInventory;
    private Account packagingInventory;

    @BeforeEach
    void init() {
        company = dataSeeder.ensureCompany(COMPANY_CODE, COMPANY_CODE + " Ltd");
        CompanyContextHolder.setCompanyId(COMPANY_CODE);
        bulkInventory = ensureAccount("INV-BULK-LF017", AccountType.ASSET);
        fgInventory = ensureAccount("INV-FG-LF017", AccountType.ASSET);
        packagingInventory = ensureAccount("INV-PACK-LF017", AccountType.ASSET);
    }

    @AfterEach
    void cleanupContext() {
        CompanyContextHolder.clear();
    }

    @Test
    void packagingAlreadyConsumedSkipsAdditionalPackagingIssue() {
        FinishedGood bulkFg = createFinishedGood("FG-BULK-LF017", "Bulk Paint", "L", bulkInventory);
        FinishedGood child = createFinishedGood("FG-1L-LF017", "Paint 1L", "UNIT", fgInventory);
        FinishedGoodBatch bulkBatch = createBulkBatch(bulkFg, new BigDecimal("10"), new BigDecimal("5"));

        RawMaterial packaging = createRawMaterial("CAN-LF017", packagingInventory, new BigDecimal("10"));
        addBatch(packaging, new BigDecimal("10"), new BigDecimal("1.00"));
        mapPackagingSize("1L", packaging);

        BigDecimal startingStock = rawMaterialRepository.findById(packaging.getId()).orElseThrow().getCurrentStock();

        BulkPackResponse response = bulkPackingService.pack(new BulkPackRequest(
                bulkBatch.getId(),
                List.of(new BulkPackRequest.PackLine(child.getId(), new BigDecimal("2"), "1L", "L")),
                LocalDate.now(),
                "packer",
                null,
                null,
                true
        ));

        RawMaterial refreshed = rawMaterialRepository.findById(packaging.getId()).orElseThrow();
        assertThat(refreshed.getCurrentStock()).isEqualByComparingTo(startingStock);
        assertThat(response.packagingCost()).isEqualByComparingTo(BigDecimal.ZERO);
        assertThat(rawMaterialMovementRepository.findByRawMaterialBatchOrderByCreatedAtAsc(
                rawMaterialBatchRepository.findByRawMaterial(packaging).getFirst()))
                .isEmpty();
    }

    private Account ensureAccount(String code, AccountType type) {
        return accountRepository.findByCompanyAndCodeIgnoreCase(company, code)
                .orElseGet(() -> {
                    Account account = new Account();
                    account.setCompany(company);
                    account.setCode(code);
                    account.setName(code);
                    account.setType(type);
                    return accountRepository.save(account);
                });
    }

    private FinishedGood createFinishedGood(String code, String name, String unit, Account valuationAccount) {
        FinishedGood fg = new FinishedGood();
        fg.setCompany(company);
        fg.setProductCode(code);
        fg.setName(name);
        fg.setUnit(unit);
        fg.setCostingMethod("FIFO");
        fg.setValuationAccountId(valuationAccount.getId());
        fg.setCurrentStock(BigDecimal.ZERO);
        fg.setReservedStock(BigDecimal.ZERO);
        return finishedGoodRepository.save(fg);
    }

    private FinishedGoodBatch createBulkBatch(FinishedGood fg, BigDecimal quantity, BigDecimal unitCost) {
        FinishedGoodBatch batch = new FinishedGoodBatch();
        batch.setFinishedGood(fg);
        batch.setBatchCode("BULK-" + System.currentTimeMillis());
        batch.setQuantityTotal(quantity);
        batch.setQuantityAvailable(quantity);
        batch.setUnitCost(unitCost);
        batch.setManufacturedAt(Instant.now());
        batch.setBulk(true);
        FinishedGoodBatch saved = finishedGoodBatchRepository.save(batch);
        BigDecimal current = Optional.ofNullable(fg.getCurrentStock()).orElse(BigDecimal.ZERO);
        fg.setCurrentStock(current.add(quantity));
        finishedGoodRepository.save(fg);
        return saved;
    }

    private RawMaterial createRawMaterial(String sku, Account inventoryAccount, BigDecimal stock) {
        RawMaterial rm = new RawMaterial();
        rm.setCompany(company);
        rm.setSku(sku);
        rm.setName(sku);
        rm.setUnitType("UNIT");
        rm.setMaterialType(MaterialType.PACKAGING);
        rm.setInventoryAccountId(inventoryAccount.getId());
        rm.setCurrentStock(stock);
        return rawMaterialRepository.save(rm);
    }

    private void addBatch(RawMaterial rm, BigDecimal quantity, BigDecimal costPerUnit) {
        RawMaterialBatch batch = new RawMaterialBatch();
        batch.setRawMaterial(rm);
        batch.setBatchCode(rm.getSku() + "-B1");
        batch.setQuantity(quantity);
        batch.setUnit(Optional.ofNullable(rm.getUnitType()).orElse("UNIT"));
        batch.setCostPerUnit(costPerUnit);
        rawMaterialBatchRepository.save(batch);
    }

    private void mapPackagingSize(String size, RawMaterial material) {
        PackagingSizeMapping mapping = new PackagingSizeMapping();
        mapping.setCompany(company);
        mapping.setPackagingSize(size);
        mapping.setRawMaterial(material);
        mapping.setUnitsPerPack(1);
        mapping.setLitersPerUnit(new BigDecimal(size.replace("L", "")));
        mapping.setActive(true);
        packagingSizeMappingRepository.save(mapping);
    }
}
