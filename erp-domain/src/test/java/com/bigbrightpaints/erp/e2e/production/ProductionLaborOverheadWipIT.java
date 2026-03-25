package com.bigbrightpaints.erp.e2e.production;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

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

@DisplayName("E2E: Labor/overhead WIP capitalization")
public class ProductionLaborOverheadWipIT extends AbstractIntegrationTest {

  private static final String COMPANY_CODE = "WE-LABOH";

  @Autowired private CompanyRepository companyRepository;
  @Autowired private AccountRepository accountRepository;
  @Autowired private ProductionBrandRepository productionBrandRepository;
  @Autowired private ProductionProductRepository productionProductRepository;
  @Autowired private RawMaterialRepository rawMaterialRepository;
  @Autowired private RawMaterialBatchRepository rawMaterialBatchRepository;
  @Autowired private ProductionLogService productionLogService;
  @Autowired private JournalEntryRepository journalEntryRepository;

  private Company company;
  private Account wip;
  private Account rmInventory;
  private Account fgInventory;
  private Account cogs;
  private Account revenue;
  private Account discount;
  private Account tax;
  private Account laborApplied;
  private Account overheadApplied;

  @BeforeEach
  void init() {
    company = dataSeeder.ensureCompany(COMPANY_CODE, COMPANY_CODE + " Ltd");
    CompanyContextHolder.setCompanyId(COMPANY_CODE);
    wip = ensureAccount("WIP", AccountType.ASSET);
    rmInventory = ensureAccount("INV-RM", AccountType.ASSET);
    fgInventory = ensureAccount("INV-FG", AccountType.ASSET);
    cogs = ensureAccount("COGS", AccountType.COGS);
    revenue = ensureAccount("REV", AccountType.REVENUE);
    discount = ensureAccount("DISC", AccountType.EXPENSE);
    tax = ensureAccount("TAX", AccountType.LIABILITY);
    laborApplied = ensureAccount("LABOR-APPLIED", AccountType.EXPENSE);
    overheadApplied = ensureAccount("OVERHEAD-APPLIED", AccountType.EXPENSE);
  }

  @AfterEach
  void cleanupContext() {
    CompanyContextHolder.clear();
  }

  @Test
  @DisplayName("Production log posts labor/overhead to WIP")
  void postsLaborOverheadAppliedJournal() {
    ProductionBrand brand = createBrand("LAB-Brand");
    ProductionProduct product = createProduct("LAB-PROD", "Labor Product", brand);
    RawMaterial base = createRawMaterial("RM-BASE", new BigDecimal("100"));
    addBatch(base, new BigDecimal("100"), new BigDecimal("10"));

    ProductionLogDetailDto log =
        productionLogService.createLog(
            new ProductionLogRequest(
                brand.getId(),
                product.getId(),
                "WHITE",
                new BigDecimal("100"),
                "L",
                new BigDecimal("100"),
                LocalDate.now().toString(),
                "LAB-TEST",
                "Supervisor",
                null,
                new BigDecimal("1200.00"),
                new BigDecimal("800.00"),
                List.of(
                    new ProductionLogRequest.MaterialUsageRequest(
                        base.getId(), new BigDecimal("10"), "KG"))));

    JournalEntry journal = requireJournal(company, log.productionCode() + "-LABOH");
    assertBalanced(journal);
    assertLineAmount(journal, wip.getId(), new BigDecimal("2000.00"), false);
    assertLineAmount(journal, laborApplied.getId(), new BigDecimal("1200.00"), true);
    assertLineAmount(journal, overheadApplied.getId(), new BigDecimal("800.00"), true);
  }

  private Account ensureAccount(String code, AccountType type) {
    return accountRepository
        .findByCompanyAndCodeIgnoreCase(company, code)
        .orElseGet(
            () -> {
              Account a = new Account();
              a.setCompany(company);
              a.setCode(code);
              a.setName(code);
              a.setType(type);
              return accountRepository.save(a);
            });
  }

  private ProductionBrand createBrand(String code) {
    return productionBrandRepository
        .findByCompanyAndCodeIgnoreCase(company, code)
        .orElseGet(
            () -> {
              ProductionBrand b = new ProductionBrand();
              b.setCompany(company);
              b.setCode(code);
              b.setName(code);
              return productionBrandRepository.save(b);
            });
  }

  private ProductionProduct createProduct(String sku, String name, ProductionBrand brand) {
    ProductionProduct product = new ProductionProduct();
    product.setCompany(company);
    product.setBrand(brand);
    product.setSkuCode(sku);
    product.setProductName(name);
    product.setCategory("FINISHED_GOOD");
    product.setUnitOfMeasure("L");
    Map<String, Object> metadata = new HashMap<>();
    metadata.put("wipAccountId", wip.getId());
    metadata.put("semiFinishedAccountId", fgInventory.getId());
    metadata.put("fgValuationAccountId", fgInventory.getId());
    metadata.put("fgCogsAccountId", cogs.getId());
    metadata.put("fgRevenueAccountId", revenue.getId());
    metadata.put("fgDiscountAccountId", discount.getId());
    metadata.put("fgTaxAccountId", tax.getId());
    metadata.put("laborAppliedAccountId", laborApplied.getId());
    metadata.put("overheadAppliedAccountId", overheadApplied.getId());
    product.setMetadata(metadata);
    return productionProductRepository.save(product);
  }

  private RawMaterial createRawMaterial(String sku, BigDecimal stock) {
    RawMaterial rm = new RawMaterial();
    rm.setCompany(company);
    rm.setSku(sku);
    rm.setName(sku);
    rm.setUnitType("KG");
    rm.setInventoryAccountId(rmInventory.getId());
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

  private JournalEntry requireJournal(Company company, String reference) {
    JournalEntry entry =
        journalEntryRepository.findByCompanyAndReferenceNumber(company, reference).orElseThrow();
    return journalEntryRepository.findById(entry.getId()).orElseThrow();
  }

  private void assertBalanced(JournalEntry entry) {
    BigDecimal debit =
        entry.getLines().stream()
            .map(l -> Optional.ofNullable(l.getDebit()).orElse(BigDecimal.ZERO))
            .reduce(BigDecimal.ZERO, BigDecimal::add);
    BigDecimal credit =
        entry.getLines().stream()
            .map(l -> Optional.ofNullable(l.getCredit()).orElse(BigDecimal.ZERO))
            .reduce(BigDecimal.ZERO, BigDecimal::add);
    assertThat(debit)
        .as("Journal %s balanced", entry.getReferenceNumber())
        .isEqualByComparingTo(credit);
  }

  private void assertLineAmount(
      JournalEntry entry, Long accountId, BigDecimal expected, boolean credit) {
    BigDecimal actual =
        entry.getLines().stream()
            .filter(l -> l.getAccount().getId().equals(accountId))
            .map(l -> credit ? l.getCredit() : l.getDebit())
            .reduce(BigDecimal.ZERO, BigDecimal::add);
    assertThat(actual).isEqualByComparingTo(expected);
  }
}
