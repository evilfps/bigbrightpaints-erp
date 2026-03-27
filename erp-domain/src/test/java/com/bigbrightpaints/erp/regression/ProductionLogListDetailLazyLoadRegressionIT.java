package com.bigbrightpaints.erp.regression;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
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

import com.bigbrightpaints.erp.core.security.CompanyContextHolder;
import com.bigbrightpaints.erp.modules.accounting.domain.Account;
import com.bigbrightpaints.erp.modules.accounting.domain.AccountRepository;
import com.bigbrightpaints.erp.modules.accounting.domain.AccountType;
import com.bigbrightpaints.erp.modules.company.domain.Company;
import com.bigbrightpaints.erp.modules.factory.dto.ProductionLogDetailDto;
import com.bigbrightpaints.erp.modules.factory.dto.ProductionLogDto;
import com.bigbrightpaints.erp.modules.factory.dto.ProductionLogRequest;
import com.bigbrightpaints.erp.modules.factory.service.ProductionLogService;
import com.bigbrightpaints.erp.modules.inventory.domain.RawMaterial;
import com.bigbrightpaints.erp.modules.inventory.domain.RawMaterialBatch;
import com.bigbrightpaints.erp.modules.inventory.domain.RawMaterialBatchRepository;
import com.bigbrightpaints.erp.modules.inventory.domain.RawMaterialRepository;
import com.bigbrightpaints.erp.modules.production.domain.ProductionBrand;
import com.bigbrightpaints.erp.modules.production.domain.ProductionBrandRepository;
import com.bigbrightpaints.erp.modules.production.domain.ProductionProduct;
import com.bigbrightpaints.erp.modules.production.domain.ProductionProductRepository;
import com.bigbrightpaints.erp.test.AbstractIntegrationTest;

@DisplayName("Regression: Production log list/detail load lazy fields safely")
@TestPropertySource(properties = "erp.raw-material.intake.enabled=true")
class ProductionLogListDetailLazyLoadRegressionIT extends AbstractIntegrationTest {

  private static final String COMPANY_CODE = "LF-015";

  @Autowired private AccountRepository accountRepository;
  @Autowired private ProductionBrandRepository brandRepository;
  @Autowired private ProductionProductRepository productRepository;
  @Autowired private RawMaterialRepository rawMaterialRepository;
  @Autowired private RawMaterialBatchRepository rawMaterialBatchRepository;
  @Autowired private ProductionLogService productionLogService;

  private Company company;
  private Account rmInventory;
  private Account wipAccount;
  private Account cogsAccount;
  private Account revenueAccount;
  private Account discountAccount;
  private Account taxAccount;
  private Account laborAppliedAccount;
  private Account overheadAppliedAccount;
  private ProductionBrand brand;
  private ProductionProduct product;

  @BeforeEach
  void setUp() {
    company = dataSeeder.ensureCompany(COMPANY_CODE, COMPANY_CODE + " Ltd");
    CompanyContextHolder.setCompanyCode(COMPANY_CODE);

    rmInventory = ensureAccount("INV-LF015", "Inventory", AccountType.ASSET);
    wipAccount = ensureAccount("WIP-LF015", "Work in Progress", AccountType.ASSET);
    cogsAccount = ensureAccount("COGS-LF015", "COGS", AccountType.COGS);
    revenueAccount = ensureAccount("REV-LF015", "Revenue", AccountType.REVENUE);
    discountAccount = ensureAccount("DISC-LF015", "Discount", AccountType.EXPENSE);
    taxAccount = ensureAccount("TAX-LF015", "Tax", AccountType.LIABILITY);
    laborAppliedAccount = ensureAccount("LABOR-LF015", "Labor Applied", AccountType.EXPENSE);
    overheadAppliedAccount = ensureAccount("OVH-LF015", "Overhead Applied", AccountType.EXPENSE);

    brand = ensureBrand(company);
    product = ensureProduct(company, brand);
  }

  @AfterEach
  void clearContext() {
    CompanyContextHolder.clear();
  }

  @Test
  void listAndDetailResolveLazyFields() {
    RawMaterial material =
        createRawMaterial(company, "RM-LF015", rmInventory.getId(), new BigDecimal("25"));
    createBatch(material, new BigDecimal("25"), new BigDecimal("10.00"));

    ProductionLogDetailDto created =
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
                "LF-015 Test",
                null,
                BigDecimal.ZERO,
                BigDecimal.ZERO,
                List.of(
                    new ProductionLogRequest.MaterialUsageRequest(
                        material.getId(), new BigDecimal("5"), "KG"))));

    ProductionLogDto listed =
        productionLogService.recentLogs().stream()
            .filter(dto -> dto.id().equals(created.id()))
            .findFirst()
            .orElseThrow();

    assertThat(listed.brandName()).isEqualTo(brand.getName());
    assertThat(listed.productName()).isEqualTo(product.getProductName());

    ProductionLogDetailDto detail = productionLogService.getLog(created.id());
    assertThat(detail.id()).isNotNull();
    assertThat(detail.publicId()).isNotNull();
    assertThat(detail.productionCode()).isNotBlank();
    assertThat(detail.materials()).isNotEmpty();
    assertThat(detail.materials().get(0).rawMaterialId()).isEqualTo(material.getId());
    assertThat(detail.materials().get(0).rawMaterialBatchCode()).isNotBlank();
    assertThat(detail.materials().get(0).rawMaterialMovementId()).isNotNull();
    assertThat(detail.outputBatchCode()).isEqualTo(detail.productionCode());
    assertThat(detail.outputQuantity()).isEqualByComparingTo(detail.mixedQuantity());
    assertThat(detail.totalPackedQuantity()).isEqualByComparingTo(BigDecimal.ZERO);
    assertThat(detail.status()).isEqualTo("READY_TO_PACK");
    assertThat(detail.wastageReasonCode()).isEqualTo("PROCESS_LOSS");
    assertThat(detail.packingRecords()).isEmpty();
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
    productionBrand.setName("LF-015 Brand");
    return brandRepository.save(productionBrand);
  }

  private ProductionProduct ensureProduct(Company company, ProductionBrand productionBrand) {
    ProductionProduct productionProduct = new ProductionProduct();
    productionProduct.setCompany(company);
    productionProduct.setBrand(productionBrand);
    productionProduct.setSkuCode("SKU-" + UUID.randomUUID());
    productionProduct.setProductName("LF-015 Product");
    productionProduct.setCategory("FINISHED_GOOD");
    productionProduct.setUnitOfMeasure("UNIT");

    Map<String, Object> metadata = new HashMap<>();
    metadata.put("wipAccountId", wipAccount.getId());
    metadata.put("semiFinishedAccountId", rmInventory.getId());
    metadata.put("fgValuationAccountId", rmInventory.getId());
    metadata.put("fgCogsAccountId", cogsAccount.getId());
    metadata.put("fgRevenueAccountId", revenueAccount.getId());
    metadata.put("fgDiscountAccountId", discountAccount.getId());
    metadata.put("fgTaxAccountId", taxAccount.getId());
    metadata.put("laborAppliedAccountId", laborAppliedAccount.getId());
    metadata.put("overheadAppliedAccountId", overheadAppliedAccount.getId());
    productionProduct.setMetadata(metadata);

    return productRepository.save(productionProduct);
  }

  private RawMaterial createRawMaterial(
      Company company, String sku, Long inventoryAccountId, BigDecimal stock) {
    RawMaterial material = new RawMaterial();
    material.setCompany(company);
    material.setSku(sku);
    material.setName("LF-015 Material");
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
