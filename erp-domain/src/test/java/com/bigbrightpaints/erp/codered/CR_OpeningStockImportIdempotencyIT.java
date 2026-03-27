package com.bigbrightpaints.erp.codered;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.util.List;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.mock.web.MockMultipartFile;

import com.bigbrightpaints.erp.core.exception.ApplicationException;
import com.bigbrightpaints.erp.core.exception.ErrorCode;
import com.bigbrightpaints.erp.core.security.CompanyContextHolder;
import com.bigbrightpaints.erp.modules.accounting.domain.Account;
import com.bigbrightpaints.erp.modules.accounting.domain.AccountRepository;
import com.bigbrightpaints.erp.modules.accounting.domain.AccountType;
import com.bigbrightpaints.erp.modules.company.domain.Company;
import com.bigbrightpaints.erp.modules.company.domain.CompanyRepository;
import com.bigbrightpaints.erp.modules.inventory.domain.FinishedGood;
import com.bigbrightpaints.erp.modules.inventory.domain.FinishedGoodRepository;
import com.bigbrightpaints.erp.modules.inventory.domain.InventoryMovement;
import com.bigbrightpaints.erp.modules.inventory.domain.InventoryMovementRepository;
import com.bigbrightpaints.erp.modules.inventory.domain.InventoryReference;
import com.bigbrightpaints.erp.modules.inventory.domain.OpeningStockImport;
import com.bigbrightpaints.erp.modules.inventory.domain.OpeningStockImportRepository;
import com.bigbrightpaints.erp.modules.inventory.domain.RawMaterial;
import com.bigbrightpaints.erp.modules.inventory.domain.RawMaterialMovement;
import com.bigbrightpaints.erp.modules.inventory.domain.RawMaterialMovementRepository;
import com.bigbrightpaints.erp.modules.inventory.domain.RawMaterialRepository;
import com.bigbrightpaints.erp.modules.inventory.dto.OpeningStockImportResponse;
import com.bigbrightpaints.erp.modules.inventory.service.OpeningStockImportService;
import com.bigbrightpaints.erp.modules.production.domain.ProductionBrand;
import com.bigbrightpaints.erp.modules.production.domain.ProductionBrandRepository;
import com.bigbrightpaints.erp.modules.production.domain.ProductionProduct;
import com.bigbrightpaints.erp.modules.production.domain.ProductionProductRepository;
import com.bigbrightpaints.erp.test.AbstractIntegrationTest;

class CR_OpeningStockImportIdempotencyIT extends AbstractIntegrationTest {

  private static final String COMPANY_CODE = "CR-OPEN-IDEMP";
  private static final String IDEMPOTENCY_KEY = "OPEN-STOCK-IDEMP-001";
  private static final String OPENING_STOCK_BATCH_KEY = "OPEN-STOCK-BATCH-IDEMP-001";

  @Autowired private CompanyRepository companyRepository;
  @Autowired private AccountRepository accountRepository;
  @Autowired private ProductionBrandRepository productionBrandRepository;
  @Autowired private ProductionProductRepository productionProductRepository;
  @Autowired private RawMaterialRepository rawMaterialRepository;
  @Autowired private FinishedGoodRepository finishedGoodRepository;
  @Autowired private OpeningStockImportService openingStockImportService;
  @Autowired private OpeningStockImportRepository openingStockImportRepository;
  @Autowired private RawMaterialMovementRepository rawMaterialMovementRepository;
  @Autowired private InventoryMovementRepository inventoryMovementRepository;

  private Company company;

  @BeforeEach
  void setUp() {
    company =
        companyRepository
            .findByCodeIgnoreCase(COMPANY_CODE)
            .orElseGet(
                () -> {
                  Company created = new Company();
                  created.setCode(COMPANY_CODE);
                  created.setName("CR Opening Stock Idempotency");
                  created.setTimezone("UTC");
                  return companyRepository.save(created);
                });
    Account inventory = ensureAccount(company, "INV-OPEN", "Inventory", AccountType.ASSET);
    Account cogs = ensureAccount(company, "COGS-OPEN", "COGS", AccountType.COGS);
    Account revenue = ensureAccount(company, "REV-OPEN", "Revenue", AccountType.REVENUE);
    Account tax = ensureAccount(company, "GST-OPEN", "GST Output", AccountType.LIABILITY);
    ensureAccount(company, "OPEN-BAL", "Opening Balance", AccountType.EQUITY);

    company.setDefaultInventoryAccountId(inventory.getId());
    company.setDefaultCogsAccountId(cogs.getId());
    company.setDefaultRevenueAccountId(revenue.getId());
    company.setDefaultTaxAccountId(tax.getId());
    companyRepository.save(company);

    ensurePreparedRawMaterialSku("RM-OPEN-1", "Resin", inventory);
    ensurePreparedFinishedGoodSku("FG-OPEN-1", "Paint 1L", inventory, cogs, revenue, tax);
    CompanyContextHolder.setCompanyCode(COMPANY_CODE);
  }

  @AfterEach
  void tearDown() {
    CompanyContextHolder.clear();
  }

  @Test
  void openingStockImport_isIdempotentForSameKeyAndFile() {
    MockMultipartFile file = csvFile();

    OpeningStockImportResponse first =
        openingStockImportService.importOpeningStock(
            file, IDEMPOTENCY_KEY, OPENING_STOCK_BATCH_KEY);
    OpeningStockImportResponse second =
        openingStockImportService.importOpeningStock(
            file, IDEMPOTENCY_KEY, OPENING_STOCK_BATCH_KEY);

    assertThat(second).isEqualTo(first);

    OpeningStockImport record =
        openingStockImportRepository
            .findByCompanyAndIdempotencyKey(company, IDEMPOTENCY_KEY)
            .orElseThrow();
    assertThat(record.getJournalEntryId()).isNotNull();

    List<RawMaterialMovement> rmMovements =
        rawMaterialMovementRepository.findByRawMaterialCompanyAndReferenceTypeAndReferenceId(
            company, InventoryReference.OPENING_STOCK, "RM-OPEN-B1");
    assertThat(rmMovements).hasSize(1);

    List<InventoryMovement> fgMovements =
        inventoryMovementRepository
            .findByFinishedGood_CompanyAndReferenceTypeAndReferenceIdOrderByCreatedAtAsc(
                company, InventoryReference.OPENING_STOCK, "FG-OPEN-B1");
    assertThat(fgMovements).hasSize(1);
  }

  @Test
  void openingStockImport_rejectsFreshIdempotencyKeyForAlreadyProcessedBatch() {
    MockMultipartFile file = csvFile();

    openingStockImportService.importOpeningStock(file, IDEMPOTENCY_KEY, OPENING_STOCK_BATCH_KEY);

    assertThatThrownBy(
            () ->
                openingStockImportService.importOpeningStock(
                    csvFile(), "OPEN-STOCK-IDEMP-002", OPENING_STOCK_BATCH_KEY))
        .isInstanceOfSatisfying(
            ApplicationException.class,
            ex -> {
              assertThat(ex.getErrorCode()).isEqualTo(ErrorCode.BUSINESS_DUPLICATE_ENTRY);
              assertThat(ex.getMessage()).contains("Opening stock batch key already exists");
              assertThat(ex.getDetails())
                  .containsEntry("existingIdempotencyKey", IDEMPOTENCY_KEY)
                  .containsEntry("openingStockBatchKey", OPENING_STOCK_BATCH_KEY)
                  .containsEntry("attemptedIdempotencyKey", "OPEN-STOCK-IDEMP-002")
                  .containsEntry("attemptedOpeningStockBatchKey", OPENING_STOCK_BATCH_KEY);
            });

    OpeningStockImport persisted =
        openingStockImportRepository
            .findByCompanyAndOpeningStockBatchKey(company, OPENING_STOCK_BATCH_KEY)
            .orElseThrow();
    assertThat(persisted.getIdempotencyKey()).isEqualTo(IDEMPOTENCY_KEY);
    assertThat(persisted.getJournalEntryId()).isNotNull();

    assertThat(
            openingStockImportRepository.findByCompanyAndIdempotencyKey(
                company, "OPEN-STOCK-IDEMP-002"))
        .isEmpty();

    List<RawMaterialMovement> rmMovements =
        rawMaterialMovementRepository.findByRawMaterialCompanyAndReferenceTypeAndReferenceId(
            company, InventoryReference.OPENING_STOCK, "RM-OPEN-B1");
    assertThat(rmMovements).hasSize(1);

    List<InventoryMovement> fgMovements =
        inventoryMovementRepository
            .findByFinishedGood_CompanyAndReferenceTypeAndReferenceIdOrderByCreatedAtAsc(
                company, InventoryReference.OPENING_STOCK, "FG-OPEN-B1");
    assertThat(fgMovements).hasSize(1);
  }

  private MockMultipartFile csvFile() {
    String csv =
        String.join(
            "\n",
            "type,sku,name,unit,unit_type,batch_code,quantity,unit_cost,material_type,manufactured_at",
            "RAW_MATERIAL,RM-OPEN-1,Resin,KG,KG,RM-OPEN-B1,10,5.00,PRODUCTION,",
            "FINISHED_GOOD,FG-OPEN-1,Paint 1L,L,L,FG-OPEN-B1,5,12.50,,2026-01-10");
    return new MockMultipartFile(
        "file", "opening.csv", "text/csv", csv.getBytes(StandardCharsets.UTF_8));
  }

  private Account ensureAccount(Company company, String code, String name, AccountType type) {
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

  private void ensurePreparedRawMaterialSku(String sku, String name, Account inventoryAccount) {
    ensureProductMaster(sku, name, "RAW_MATERIAL", "KG", null);
    RawMaterial material =
        rawMaterialRepository
            .findByCompanyAndSkuIgnoreCase(company, sku)
            .orElseGet(RawMaterial::new);
    material.setCompany(company);
    material.setSku(sku);
    material.setName(name);
    material.setUnitType("KG");
    material.setInventoryAccountId(inventoryAccount.getId());
    rawMaterialRepository.save(material);
  }

  private void ensurePreparedFinishedGoodSku(
      String sku,
      String name,
      Account inventoryAccount,
      Account cogsAccount,
      Account revenueAccount,
      Account taxAccount) {
    ensureProductMaster(sku, name, "FINISHED_GOOD", "L", "1L");
    FinishedGood finishedGood =
        finishedGoodRepository
            .findByCompanyAndProductCodeIgnoreCase(company, sku)
            .orElseGet(FinishedGood::new);
    finishedGood.setCompany(company);
    finishedGood.setProductCode(sku);
    finishedGood.setName(name);
    finishedGood.setUnit("L");
    finishedGood.setValuationAccountId(inventoryAccount.getId());
    finishedGood.setCogsAccountId(cogsAccount.getId());
    finishedGood.setRevenueAccountId(revenueAccount.getId());
    finishedGood.setTaxAccountId(taxAccount.getId());
    finishedGoodRepository.save(finishedGood);
  }

  private void ensureProductMaster(
      String sku, String name, String category, String unitOfMeasure, String sizeLabel) {
    ProductionProduct product =
        productionProductRepository
            .findByCompanyAndSkuCodeIgnoreCase(company, sku)
            .orElseGet(ProductionProduct::new);
    product.setCompany(company);
    product.setBrand(ensureBrand());
    product.setProductName(name);
    product.setCategory(category);
    product.setSkuCode(sku);
    product.setUnitOfMeasure(unitOfMeasure);
    product.setSizeLabel(sizeLabel);
    product.setHsnCode("320910");
    product.setBasePrice(BigDecimal.ZERO);
    product.setGstRate(BigDecimal.ZERO);
    product.setMinDiscountPercent(BigDecimal.ZERO);
    product.setMinSellingPrice(BigDecimal.ZERO);
    product.setActive(true);
    productionProductRepository.save(product);
  }

  private ProductionBrand ensureBrand() {
    return productionBrandRepository
        .findByCompanyAndCodeIgnoreCase(company, "OPEN-STOCK")
        .orElseGet(
            () -> {
              ProductionBrand brand = new ProductionBrand();
              brand.setCompany(company);
              brand.setCode("OPEN-STOCK");
              brand.setName("Opening Stock");
              return productionBrandRepository.save(brand);
            });
  }
}
