package com.bigbrightpaints.erp.regression;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.math.RoundingMode;
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
import com.bigbrightpaints.erp.modules.accounting.domain.JournalEntry;
import com.bigbrightpaints.erp.modules.accounting.domain.JournalEntryRepository;
import com.bigbrightpaints.erp.modules.company.domain.Company;
import com.bigbrightpaints.erp.modules.company.domain.CompanyRepository;
import com.bigbrightpaints.erp.modules.factory.dto.ProductionLogDetailDto;
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

@DisplayName("Regression: WIP debits/credits match total cost at log creation")
@TestPropertySource(properties = "erp.raw-material.intake.enabled=true")
class ProductionLogWipPostingRegressionIT extends AbstractIntegrationTest {

  private static final String COMPANY_CODE = "LF-012";

  @Autowired private CompanyRepository companyRepository;
  @Autowired private AccountRepository accountRepository;
  @Autowired private ProductionBrandRepository brandRepository;
  @Autowired private ProductionProductRepository productRepository;
  @Autowired private RawMaterialRepository rawMaterialRepository;
  @Autowired private RawMaterialBatchRepository rawMaterialBatchRepository;
  @Autowired private ProductionLogService productionLogService;
  @Autowired private JournalEntryRepository journalEntryRepository;

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
    CompanyContextHolder.setCompanyId(COMPANY_CODE);

    rmInventory = ensureAccount(company, "INV", "Inventory", AccountType.ASSET);
    wipAccount = ensureAccount(company, "WIP", "Work in Progress", AccountType.ASSET);
    cogsAccount = ensureAccount(company, "COGS", "Cost of Goods Sold", AccountType.COGS);
    revenueAccount = ensureAccount(company, "REV", "Revenue", AccountType.REVENUE);
    discountAccount = ensureAccount(company, "DISC", "Discount", AccountType.EXPENSE);
    taxAccount = ensureAccount(company, "TAX", "Tax Payable", AccountType.LIABILITY);
    laborAppliedAccount =
        ensureAccount(company, "LABOR-APPLIED", "Labor Applied", AccountType.EXPENSE);
    overheadAppliedAccount =
        ensureAccount(company, "OVERHEAD-APPLIED", "Overhead Applied", AccountType.EXPENSE);

    brand = ensureBrand(company);
    product = ensureProduct(company, brand);
  }

  @AfterEach
  void clearContext() {
    CompanyContextHolder.clear();
  }

  @Test
  void wipDebitsAndCreditsMatchTotalCost() {
    BigDecimal unitCost = new BigDecimal("10.00");
    BigDecimal materialQty = new BigDecimal("5");
    BigDecimal laborCost = new BigDecimal("15.00");
    BigDecimal overheadCost = new BigDecimal("5.00");

    RawMaterial material =
        createRawMaterial(company, "RM-LF012", rmInventory.getId(), new BigDecimal("20"));
    createBatch(material, new BigDecimal("20"), unitCost);

    BigDecimal materialCost = unitCost.multiply(materialQty).setScale(2, RoundingMode.HALF_UP);
    BigDecimal totalCost =
        materialCost.add(laborCost).add(overheadCost).setScale(2, RoundingMode.HALF_UP);

    ProductionLogDetailDto log =
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
                "LF-012 Test",
                null,
                laborCost,
                overheadCost,
                List.of(
                    new ProductionLogRequest.MaterialUsageRequest(
                        material.getId(), materialQty, "KG"))));

    String productionCode = log.productionCode();
    JournalEntry rmEntry = requireEntry(company, productionCode + "-RM");
    JournalEntry laborEntry = requireEntry(company, productionCode + "-LABOH");
    JournalEntry semiEntry = requireEntry(company, productionCode + "-SEMIFG");

    BigDecimal wipDebit =
        sumAccount(rmEntry, wipAccount.getId(), true)
            .add(sumAccount(laborEntry, wipAccount.getId(), true));
    BigDecimal wipCredit = sumAccount(semiEntry, wipAccount.getId(), false);

    assertThat(sumAccount(rmEntry, wipAccount.getId(), true)).isEqualByComparingTo(materialCost);
    assertThat(sumAccount(laborEntry, wipAccount.getId(), true))
        .isEqualByComparingTo(laborCost.add(overheadCost).setScale(2, RoundingMode.HALF_UP));
    assertThat(wipDebit).isEqualByComparingTo(totalCost);
    assertThat(wipCredit).isEqualByComparingTo(totalCost);
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

  private ProductionBrand ensureBrand(Company company) {
    ProductionBrand brand = new ProductionBrand();
    brand.setCompany(company);
    brand.setCode("BR-" + UUID.randomUUID());
    brand.setName("LF-012 Brand");
    return brandRepository.save(brand);
  }

  private ProductionProduct ensureProduct(Company company, ProductionBrand brand) {
    ProductionProduct product = new ProductionProduct();
    product.setCompany(company);
    product.setBrand(brand);
    product.setSkuCode("SKU-" + UUID.randomUUID());
    product.setProductName("LF-012 Product");
    product.setCategory("FINISHED_GOOD");
    product.setUnitOfMeasure("UNIT");

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
    product.setMetadata(metadata);

    return productRepository.save(product);
  }

  private RawMaterial createRawMaterial(
      Company company, String sku, Long inventoryAccountId, BigDecimal stock) {
    RawMaterial material = new RawMaterial();
    material.setCompany(company);
    material.setSku(sku);
    material.setName("LF-012 Material");
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

  private JournalEntry requireEntry(Company company, String reference) {
    JournalEntry entry =
        journalEntryRepository.findByCompanyAndReferenceNumber(company, reference).orElseThrow();
    return journalEntryRepository.findById(entry.getId()).orElseThrow();
  }

  private BigDecimal sumAccount(JournalEntry entry, Long accountId, boolean debit) {
    return entry.getLines().stream()
        .filter(line -> line.getAccount().getId().equals(accountId))
        .map(line -> debit ? line.getDebit() : line.getCredit())
        .reduce(
            BigDecimal.ZERO, (left, right) -> left.add(right == null ? BigDecimal.ZERO : right));
  }
}
