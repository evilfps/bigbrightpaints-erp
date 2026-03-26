package com.bigbrightpaints.erp.regression;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.TestPropertySource;

import com.bigbrightpaints.erp.core.exception.ApplicationException;
import com.bigbrightpaints.erp.core.security.CompanyContextHolder;
import com.bigbrightpaints.erp.modules.accounting.domain.Account;
import com.bigbrightpaints.erp.modules.accounting.domain.AccountRepository;
import com.bigbrightpaints.erp.modules.accounting.domain.AccountType;
import com.bigbrightpaints.erp.modules.accounting.domain.JournalEntryRepository;
import com.bigbrightpaints.erp.modules.company.domain.Company;
import com.bigbrightpaints.erp.modules.factory.domain.ProductionLogRepository;
import com.bigbrightpaints.erp.modules.factory.dto.ProductionLogRequest;
import com.bigbrightpaints.erp.modules.factory.service.ProductionLogService;
import com.bigbrightpaints.erp.modules.inventory.domain.FinishedGoodRepository;
import com.bigbrightpaints.erp.modules.inventory.domain.InventoryMovementRepository;
import com.bigbrightpaints.erp.modules.inventory.domain.RawMaterial;
import com.bigbrightpaints.erp.modules.inventory.domain.RawMaterialBatch;
import com.bigbrightpaints.erp.modules.inventory.domain.RawMaterialBatchRepository;
import com.bigbrightpaints.erp.modules.inventory.domain.RawMaterialMovementRepository;
import com.bigbrightpaints.erp.modules.inventory.domain.RawMaterialRepository;
import com.bigbrightpaints.erp.modules.production.domain.ProductionBrand;
import com.bigbrightpaints.erp.modules.production.domain.ProductionBrandRepository;
import com.bigbrightpaints.erp.modules.production.domain.ProductionProduct;
import com.bigbrightpaints.erp.modules.production.domain.ProductionProductRepository;
import com.bigbrightpaints.erp.test.AbstractIntegrationTest;

@DisplayName("Regression: Production log creation rolls back partial postings on failure")
@TestPropertySource(properties = "erp.raw-material.intake.enabled=true")
class ProductionLogAtomicityRegressionIT extends AbstractIntegrationTest {

  private static final String COMPANY_CODE = "LF-016";

  @Autowired private AccountRepository accountRepository;
  @Autowired private ProductionBrandRepository brandRepository;
  @Autowired private ProductionProductRepository productRepository;
  @Autowired private ProductionLogRepository productionLogRepository;
  @Autowired private ProductionLogService productionLogService;
  @Autowired private RawMaterialRepository rawMaterialRepository;
  @Autowired private RawMaterialBatchRepository rawMaterialBatchRepository;
  @Autowired private RawMaterialMovementRepository rawMaterialMovementRepository;
  @Autowired private InventoryMovementRepository inventoryMovementRepository;
  @Autowired private JournalEntryRepository journalEntryRepository;
  @Autowired private FinishedGoodRepository finishedGoodRepository;

  private Company company;
  private Account rmInventory;
  private Account wipAccount;
  private Account cogsAccount;
  private Account revenueAccount;
  private Account discountAccount;
  private Account taxAccount;
  private ProductionBrand brand;
  private ProductionProduct product;

  @BeforeEach
  void setUp() {
    company = dataSeeder.ensureCompany(COMPANY_CODE, COMPANY_CODE + " Ltd");
    CompanyContextHolder.setCompanyId(COMPANY_CODE);

    rmInventory = ensureAccount("INV-LF016", "Inventory", AccountType.ASSET);
    wipAccount = ensureAccount("WIP-LF016", "Work in Progress", AccountType.ASSET);
    cogsAccount = ensureAccount("COGS-LF016", "COGS", AccountType.COGS);
    revenueAccount = ensureAccount("REV-LF016", "Revenue", AccountType.REVENUE);
    discountAccount = ensureAccount("DISC-LF016", "Discount", AccountType.EXPENSE);
    taxAccount = ensureAccount("TAX-LF016", "Tax", AccountType.LIABILITY);

    brand = ensureBrand(company);
    product = ensureProductMissingSemiFinishedMetadata(company, brand);
  }

  @AfterEach
  void clearContext() {
    CompanyContextHolder.clear();
  }

  @Test
  void createLogRollsBackMaterialAndAccountingSideEffectsWhenReceiptFails() {
    RawMaterial material =
        createRawMaterial(company, "RM-LF016", rmInventory.getId(), new BigDecimal("20"));
    RawMaterialBatch batch = createBatch(material, new BigDecimal("20"), new BigDecimal("10.00"));

    int baselineJournalCount =
        journalEntryRepository.findByCompanyOrderByEntryDateDesc(company).size();
    int baselineMovementCount =
        rawMaterialMovementRepository
            .findByCompanyCreatedAtOnOrAfter(company, Instant.EPOCH)
            .size();
    int baselineInventoryMovementCount =
        inventoryMovementRepository.findByCompanyCreatedAtOnOrAfter(company, Instant.EPOCH).size();
    int baselineFinishedGoodCount =
        finishedGoodRepository.findByCompanyOrderByProductCodeAsc(company).size();

    assertThatThrownBy(
            () ->
                productionLogService.createLog(
                    new ProductionLogRequest(
                        brand.getId(),
                        product.getId(),
                        "WHITE",
                        new BigDecimal("10"),
                        "UNIT",
                        new BigDecimal("10"),
                        LocalDate.now().toString(),
                        null,
                        "LF-016 Test",
                        null,
                        BigDecimal.ZERO,
                        BigDecimal.ZERO,
                        List.of(
                            new ProductionLogRequest.MaterialUsageRequest(
                                material.getId(), new BigDecimal("5"), "KG")))))
        .isInstanceOf(ApplicationException.class)
        .hasMessageContaining("missing finished good account metadata");

    assertThat(rawMaterialRepository.findById(material.getId()).orElseThrow().getCurrentStock())
        .isEqualByComparingTo(new BigDecimal("20"));
    assertThat(rawMaterialBatchRepository.findById(batch.getId()).orElseThrow().getQuantity())
        .isEqualByComparingTo(new BigDecimal("20"));
    assertThat(journalEntryRepository.findByCompanyOrderByEntryDateDesc(company))
        .hasSize(baselineJournalCount);
    assertThat(
            rawMaterialMovementRepository.findByCompanyCreatedAtOnOrAfter(company, Instant.EPOCH))
        .hasSize(baselineMovementCount);
    assertThat(inventoryMovementRepository.findByCompanyCreatedAtOnOrAfter(company, Instant.EPOCH))
        .hasSize(baselineInventoryMovementCount);
    assertThat(finishedGoodRepository.findByCompanyOrderByProductCodeAsc(company))
        .hasSize(baselineFinishedGoodCount);
    assertThat(
            productionLogRepository.findByCompanyAndProducedAtBetween(
                company, Instant.EPOCH, Instant.now().plusSeconds(86_400)))
        .isEmpty();
    assertThat(
            finishedGoodRepository.findByCompanyAndProductCode(
                company, product.getSkuCode() + "-BULK"))
        .isEmpty();
  }

  private Account ensureAccount(String code, String name, AccountType type) {
    return accountRepository
        .findByCompanyAndCodeIgnoreCase(company, code)
        .orElseGet(
            () -> {
              Account account = new Account();
              account.setCompany(company);
              account.setCode(code);
              account.setName(name);
              account.setType(type);
              return accountRepository.save(account);
            });
  }

  private ProductionBrand ensureBrand(Company company) {
    ProductionBrand productionBrand = new ProductionBrand();
    productionBrand.setCompany(company);
    productionBrand.setCode("BR-" + UUID.randomUUID());
    productionBrand.setName("LF-016 Brand");
    return brandRepository.save(productionBrand);
  }

  private ProductionProduct ensureProductMissingSemiFinishedMetadata(
      Company company, ProductionBrand productionBrand) {
    ProductionProduct productionProduct = new ProductionProduct();
    productionProduct.setCompany(company);
    productionProduct.setBrand(productionBrand);
    productionProduct.setSkuCode("SKU-" + UUID.randomUUID());
    productionProduct.setProductName("LF-016 Product");
    productionProduct.setCategory("FINISHED_GOOD");
    productionProduct.setUnitOfMeasure("UNIT");

    Map<String, Object> metadata = new HashMap<>();
    metadata.put("wipAccountId", wipAccount.getId());
    metadata.put("fgCogsAccountId", cogsAccount.getId());
    metadata.put("fgRevenueAccountId", revenueAccount.getId());
    metadata.put("fgDiscountAccountId", discountAccount.getId());
    metadata.put("fgTaxAccountId", taxAccount.getId());
    productionProduct.setMetadata(metadata);

    return productRepository.save(productionProduct);
  }

  private RawMaterial createRawMaterial(
      Company company, String sku, Long inventoryAccountId, BigDecimal stock) {
    RawMaterial material = new RawMaterial();
    material.setCompany(company);
    material.setSku(sku);
    material.setName("LF-016 Material");
    material.setUnitType("KG");
    material.setCurrentStock(stock);
    material.setInventoryAccountId(inventoryAccountId);
    return rawMaterialRepository.save(material);
  }

  private RawMaterialBatch createBatch(
      RawMaterial material, BigDecimal quantity, BigDecimal costPerUnit) {
    RawMaterialBatch batch = new RawMaterialBatch();
    batch.setRawMaterial(material);
    batch.setBatchCode("BATCH-" + UUID.randomUUID());
    batch.setQuantity(quantity);
    batch.setUnit("KG");
    batch.setCostPerUnit(costPerUnit);
    return rawMaterialBatchRepository.save(batch);
  }
}
