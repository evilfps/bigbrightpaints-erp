package com.bigbrightpaints.erp.codered;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.TestPropertySource;

import com.bigbrightpaints.erp.codered.support.CoderedDbAssertions;
import com.bigbrightpaints.erp.core.security.CompanyContextHolder;
import com.bigbrightpaints.erp.modules.accounting.domain.Account;
import com.bigbrightpaints.erp.modules.accounting.domain.AccountRepository;
import com.bigbrightpaints.erp.modules.accounting.domain.AccountType;
import com.bigbrightpaints.erp.modules.accounting.domain.JournalEntryRepository;
import com.bigbrightpaints.erp.modules.company.domain.Company;
import com.bigbrightpaints.erp.modules.company.domain.CompanyRepository;
import com.bigbrightpaints.erp.modules.factory.domain.PackagingSizeMapping;
import com.bigbrightpaints.erp.modules.factory.domain.PackagingSizeMappingRepository;
import com.bigbrightpaints.erp.modules.factory.domain.PackingRecord;
import com.bigbrightpaints.erp.modules.factory.domain.PackingRecordRepository;
import com.bigbrightpaints.erp.modules.factory.domain.ProductionLog;
import com.bigbrightpaints.erp.modules.factory.domain.ProductionLogRepository;
import com.bigbrightpaints.erp.modules.factory.domain.ProductionLogStatus;
import com.bigbrightpaints.erp.modules.factory.domain.SizeVariant;
import com.bigbrightpaints.erp.modules.factory.domain.SizeVariantRepository;
import com.bigbrightpaints.erp.modules.factory.dto.CostAllocationRequest;
import com.bigbrightpaints.erp.modules.factory.dto.PackingLineRequest;
import com.bigbrightpaints.erp.modules.factory.dto.PackingRequest;
import com.bigbrightpaints.erp.modules.factory.service.CostAllocationService;
import com.bigbrightpaints.erp.modules.factory.service.PackingService;
import com.bigbrightpaints.erp.modules.inventory.domain.FinishedGood;
import com.bigbrightpaints.erp.modules.inventory.domain.FinishedGoodRepository;
import com.bigbrightpaints.erp.modules.inventory.domain.MaterialType;
import com.bigbrightpaints.erp.modules.inventory.domain.RawMaterial;
import com.bigbrightpaints.erp.modules.inventory.domain.RawMaterialBatch;
import com.bigbrightpaints.erp.modules.inventory.domain.RawMaterialBatchRepository;
import com.bigbrightpaints.erp.modules.inventory.domain.RawMaterialRepository;
import com.bigbrightpaints.erp.modules.inventory.dto.FinishedGoodBatchRequest;
import com.bigbrightpaints.erp.modules.inventory.dto.FinishedGoodRequest;
import com.bigbrightpaints.erp.modules.inventory.service.FinishedGoodsService;
import com.bigbrightpaints.erp.modules.production.domain.ProductionBrand;
import com.bigbrightpaints.erp.modules.production.domain.ProductionBrandRepository;
import com.bigbrightpaints.erp.modules.production.domain.ProductionProduct;
import com.bigbrightpaints.erp.modules.production.domain.ProductionProductRepository;
import com.bigbrightpaints.erp.test.AbstractIntegrationTest;

@TestPropertySource(properties = "erp.benchmark.override-date=2025-03-31")
@Tag("critical")
@Tag("reconciliation")
class CR_ManufacturingWipCostingTest extends AbstractIntegrationTest {

  @Autowired private CompanyRepository companyRepository;
  @Autowired private AccountRepository accountRepository;
  @Autowired private ProductionBrandRepository productionBrandRepository;
  @Autowired private ProductionProductRepository productionProductRepository;
  @Autowired private ProductionLogRepository productionLogRepository;
  @Autowired private PackingService packingService;
  @Autowired private PackingRecordRepository packingRecordRepository;
  @Autowired private PackagingSizeMappingRepository packagingSizeMappingRepository;
  @Autowired private SizeVariantRepository sizeVariantRepository;
  @Autowired private RawMaterialRepository rawMaterialRepository;
  @Autowired private RawMaterialBatchRepository rawMaterialBatchRepository;
  @Autowired private FinishedGoodsService finishedGoodsService;
  @Autowired private FinishedGoodRepository finishedGoodRepository;
  @Autowired private JournalEntryRepository journalEntryRepository;
  @Autowired private CostAllocationService costAllocationService;
  @Autowired private JdbcTemplate jdbcTemplate;

  @AfterEach
  void clearCompanyContext() {
    CompanyContextHolder.clear();
  }

  @Test
  void packing_postsBalancedWipAndFgJournals_andClearsWipForPackaging() {
    String companyCode = "CR-MFG-PACK-" + shortId();
    Company company = bootstrapCompany(companyCode, "UTC");
    Map<String, Account> accounts = ensureManufacturingAccounts(company);

    ProductionProduct product = ensureProductionProduct(company, accounts, "CR-FG-" + shortId());
    String productionCode = "CR-PROD-" + shortId();
    Instant producedAt = Instant.parse("2025-03-15T00:00:00Z");
    ProductionLog log =
        createProductionLog(company, product, productionCode, new BigDecimal("10"), producedAt);

    // Seed semi-finished (bulk) inventory required by packing service
    String bulkSku = product.getSkuCode() + "-BULK";
    FinishedGood semiFinished =
        ensureFinishedGood(company, bulkSku, accounts.get("SF_INV"), accounts);
    CompanyContextHolder.setCompanyCode(companyCode);
    finishedGoodsService.registerBatch(
        new FinishedGoodBatchRequest(
            semiFinished.getId(),
            productionCode,
            new BigDecimal("10"),
            new BigDecimal("12.5000"),
            log.getProducedAt(),
            null));
    CompanyContextHolder.clear();

    // Seed packaging material + mapping (1 bucket per 1L)
    RawMaterial bucket =
        ensurePackagingMaterial(company, accounts.get("PACK_INV"), new BigDecimal("100"));
    ensureRawMaterialBatch(bucket, new BigDecimal("100"), new BigDecimal("2.00"));
    ensurePackagingSizeMapping(company, bucket, "1L");
    FinishedGood packTarget = ensurePackTarget(company, product, accounts, "1L");

    CompanyContextHolder.setCompanyCode(companyCode);
    LocalDate packingDate = LocalDate.of(2025, 3, 31);
    packingService.recordPacking(
        new PackingRequest(
            log.getId(),
            packingDate,
            "codered",
            List.of(
                new PackingLineRequest(
                    packTarget.getId(), null, "1L", new BigDecimal("5"), 5, null, null))));
    CompanyContextHolder.clear();

    PackingRecord record =
        packingRecordRepository
            .findByCompanyAndProductionLogOrderByPackedDateAscIdAsc(company, log)
            .stream()
            .findFirst()
            .orElseThrow();
    String packagingReference = productionCode + "-PACK-" + record.getId();

    String packagingJournalRef = packagingReference + "-PACKMAT";
    Long packagingJournalId =
        journalEntryRepository
            .findByCompanyAndReferenceNumber(company, packagingJournalRef)
            .map(e -> e.getId())
            .orElseThrow();

    // Find the packing session journal (reference uses inventory movement id)
    Long packSessionJournalId =
        journalEntryRepository
            .findByCompanyAndReferenceNumberStartingWith(company, productionCode + "-PACK-")
            .stream()
            .map(e -> e.getId())
            .filter(
                id ->
                    !journalEntryRepository
                        .findById(id)
                        .orElseThrow()
                        .getReferenceNumber()
                        .endsWith("-PACKMAT"))
            .findFirst()
            .orElseThrow();

    CoderedDbAssertions.assertBalancedJournal(journalEntryRepository, packagingJournalId);
    CoderedDbAssertions.assertBalancedJournal(journalEntryRepository, packSessionJournalId);

    BigDecimal wipDebit = sumAccountDebit(packagingJournalId, accounts.get("WIP").getId());
    BigDecimal wipCredit = sumAccountCredit(packSessionJournalId, accounts.get("WIP").getId());
    assertThat(wipDebit).as("WIP debit from packaging consumption").isGreaterThan(BigDecimal.ZERO);
    assertThat(wipCredit).as("WIP credit clearing into FG").isEqualByComparingTo(wipDebit);

    CoderedDbAssertions.assertNoNegativeInventory(jdbcTemplate, company.getId());
  }

  @Test
  void costAllocation_respectsCompanyTimezoneMonthBoundaries_andIsRetrySafe() {
    String companyCode = "CR-MFG-COST-" + shortId();
    Company company = bootstrapCompany(companyCode, "Asia/Kolkata");
    Map<String, Account> accounts = ensureManufacturingAccounts(company);

    // Create one log that is March 1st in IST (Feb 28th UTC evening) and one that is April 1st in
    // IST (Mar 31st UTC evening)
    ProductionProduct product = ensureProductionProduct(company, accounts, "CR-FG-" + shortId());
    Instant included = Instant.parse("2025-02-28T20:00:00Z"); // 2025-03-01 01:30 IST
    Instant excluded = Instant.parse("2025-03-31T20:00:00Z"); // 2025-04-01 01:30 IST

    createFullyPackedLog(
        company, product, "CR-BATCH-IN-" + shortId(), new BigDecimal("10"), included);
    createFullyPackedLog(
        company, product, "CR-BATCH-OUT-" + shortId(), new BigDecimal("10"), excluded);

    CompanyContextHolder.setCompanyCode(companyCode);
    var first =
        costAllocationService.allocateCosts(
            new CostAllocationRequest(
                2025,
                3,
                new BigDecimal("100.00"),
                new BigDecimal("50.00"),
                accounts.get("FG_INV").getId(),
                accounts.get("LAB_EXP").getId(),
                accounts.get("OH_EXP").getId(),
                "CODE-RED allocation"));
    var second =
        costAllocationService.allocateCosts(
            new CostAllocationRequest(
                2025,
                3,
                new BigDecimal("100.00"),
                new BigDecimal("50.00"),
                accounts.get("FG_INV").getId(),
                accounts.get("LAB_EXP").getId(),
                accounts.get("OH_EXP").getId(),
                "CODE-RED allocation retry"));
    CompanyContextHolder.clear();

    assertThat(first.batchesProcessed()).as("Only IST-March batch included").isEqualTo(1);
    assertThat(first.journalEntryIds()).hasSize(1);
    assertThat(second.journalEntryIds()).as("Retry does not double-post").isEmpty();
    assertThat(second.summary()).contains("skipped:");

    Long journalId = first.journalEntryIds().getFirst();
    CoderedDbAssertions.assertBalancedJournal(journalEntryRepository, journalId);

    Integer cvarCount =
        jdbcTemplate.queryForObject(
            "select count(*) from journal_entries where company_id = ? and reference_number like"
                + " 'CVAR-%-202503'",
            Integer.class, company.getId());
    assertThat(cvarCount).as("No duplicate CVAR journals").isEqualTo(1);
  }

  private BigDecimal sumAccountDebit(Long journalId, Long accountId) {
    return jdbcTemplate.queryForObject(
        """
        select coalesce(sum(jl.debit), 0)
        from journal_lines jl
        where jl.journal_entry_id = ?
          and jl.account_id = ?
        """,
        BigDecimal.class,
        journalId,
        accountId);
  }

  private BigDecimal sumAccountCredit(Long journalId, Long accountId) {
    return jdbcTemplate.queryForObject(
        """
        select coalesce(sum(jl.credit), 0)
        from journal_lines jl
        where jl.journal_entry_id = ?
          and jl.account_id = ?
        """,
        BigDecimal.class,
        journalId,
        accountId);
  }

  private Company bootstrapCompany(String companyCode, String timezone) {
    dataSeeder.ensureCompany(companyCode, companyCode + " Ltd");
    CompanyContextHolder.setCompanyCode(companyCode);
    Company company = companyRepository.findByCodeIgnoreCase(companyCode).orElseThrow();
    company.setTimezone(timezone);
    company.setBaseCurrency("INR");
    return companyRepository.save(company);
  }

  private Map<String, Account> ensureManufacturingAccounts(Company company) {
    Account wip = ensureAccount(company, "WIP", "Work in Progress", AccountType.ASSET);
    Account sf = ensureAccount(company, "SF-INV", "Semi-Finished Inventory", AccountType.ASSET);
    Account fg = ensureAccount(company, "FG-INV", "Finished Goods Inventory", AccountType.ASSET);
    Account packInv = ensureAccount(company, "PACK-INV", "Packaging Inventory", AccountType.ASSET);
    Account cogs = ensureAccount(company, "COGS", "COGS", AccountType.COGS);
    Account rev = ensureAccount(company, "REV", "Revenue", AccountType.REVENUE);
    Account disc = ensureAccount(company, "DISC", "Discounts", AccountType.EXPENSE);
    Account gstOut = ensureAccount(company, "GST-OUT", "GST Output", AccountType.LIABILITY);
    Account lab = ensureAccount(company, "LAB-EXP", "Labor Expense", AccountType.EXPENSE);
    Account oh = ensureAccount(company, "OH-EXP", "Overhead Expense", AccountType.EXPENSE);

    return Map.of(
        "WIP", wip,
        "SF_INV", sf,
        "FG_INV", fg,
        "PACK_INV", packInv,
        "COGS", cogs,
        "REV", rev,
        "DISC", disc,
        "GST_OUT", gstOut,
        "LAB_EXP", lab,
        "OH_EXP", oh);
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
              account.setActive(true);
              account.setBalance(BigDecimal.ZERO);
              return accountRepository.save(account);
            });
  }

  private ProductionProduct ensureProductionProduct(
      Company company, Map<String, Account> accounts, String sku) {
    ProductionBrand brand =
        productionBrandRepository
            .findByCompanyAndCodeIgnoreCase(company, "CR-BRAND")
            .orElseGet(
                () -> {
                  ProductionBrand b = new ProductionBrand();
                  b.setCompany(company);
                  b.setCode("CR-BRAND");
                  b.setName("Code-Red Brand");
                  return productionBrandRepository.save(b);
                });

    return productionProductRepository
        .findByCompanyAndSkuCode(company, sku)
        .orElseGet(
            () -> {
              ProductionProduct p = new ProductionProduct();
              p.setCompany(company);
              p.setBrand(brand);
              p.setSkuCode(sku);
              p.setProductName("Code-Red " + sku);
              p.setCategory("FINISHED_GOOD");
              p.setUnitOfMeasure("L");
              p.setGstRate(BigDecimal.ZERO);
              Map<String, Object> metadata = new HashMap<>();
              metadata.put("wipAccountId", accounts.get("WIP").getId());
              metadata.put("semiFinishedAccountId", accounts.get("SF_INV").getId());
              metadata.put("fgValuationAccountId", accounts.get("FG_INV").getId());
              metadata.put("fgCogsAccountId", accounts.get("COGS").getId());
              metadata.put("fgRevenueAccountId", accounts.get("REV").getId());
              metadata.put("fgDiscountAccountId", accounts.get("DISC").getId());
              metadata.put("fgTaxAccountId", accounts.get("GST_OUT").getId());
              metadata.put("wastageAccountId", accounts.get("COGS").getId());
              p.setMetadata(metadata);
              return productionProductRepository.save(p);
            });
  }

  private ProductionLog createProductionLog(
      Company company,
      ProductionProduct product,
      String productionCode,
      BigDecimal qty,
      Instant producedAt) {
    ProductionLog log = new ProductionLog();
    log.setCompany(company);
    log.setBrand(product.getBrand());
    log.setProduct(product);
    log.setProductionCode(productionCode);
    log.setBatchColour("NA");
    log.setBatchSize(qty);
    log.setUnitOfMeasure("L");
    log.setMixedQuantity(qty);
    log.setUnitCost(new BigDecimal("12.5000"));
    log.setProducedAt(producedAt);
    return productionLogRepository.save(log);
  }

  private ProductionLog createFullyPackedLog(
      Company company,
      ProductionProduct product,
      String productionCode,
      BigDecimal qty,
      Instant producedAt) {
    ProductionLog log = createProductionLog(company, product, productionCode, qty, producedAt);
    log.setStatus(ProductionLogStatus.FULLY_PACKED);
    log.setTotalPackedQuantity(qty);
    log.setLaborCostTotal(BigDecimal.ZERO);
    log.setOverheadCostTotal(BigDecimal.ZERO);
    log.setMaterialCostTotal(BigDecimal.ZERO);
    log.setUnitCost(new BigDecimal("10.0000"));
    return productionLogRepository.save(log);
  }

  private FinishedGood ensureFinishedGood(
      Company company, String sku, Account valuation, Map<String, Account> accounts) {
    CompanyContextHolder.setCompanyCode(company.getCode());
    FinishedGoodRequest req =
        new FinishedGoodRequest(
            sku,
            sku,
            "L",
            "FIFO",
            valuation.getId(),
            accounts.get("COGS").getId(),
            accounts.get("REV").getId(),
            accounts.get("DISC").getId(),
            accounts.get("GST_OUT").getId());
    FinishedGood fg =
        finishedGoodRepository
            .findByCompanyAndProductCode(company, sku)
            .orElseGet(
                () -> {
                  var dto = finishedGoodsService.createFinishedGood(req);
                  return finishedGoodRepository.findById(dto.id()).orElseThrow();
                });
    CompanyContextHolder.clear();
    return fg;
  }

  private FinishedGood ensurePackTarget(
      Company company, ProductionProduct product, Map<String, Account> accounts, String sizeLabel) {
    SizeVariant variant =
        sizeVariantRepository
            .findByCompanyAndProductAndSizeLabelIgnoreCase(company, product, sizeLabel)
            .orElseGet(
                () -> {
                  SizeVariant created = new SizeVariant();
                  created.setCompany(company);
                  created.setProduct(product);
                  created.setSizeLabel(sizeLabel);
                  created.setCartonQuantity(1);
                  created.setLitersPerUnit(new BigDecimal(sizeLabel.replace("L", "")));
                  created.setActive(true);
                  return sizeVariantRepository.save(created);
                });
    variant.setActive(true);
    sizeVariantRepository.save(variant);
    return ensureFinishedGood(company, product.getSkuCode(), accounts.get("FG_INV"), accounts);
  }

  private RawMaterial ensurePackagingMaterial(
      Company company, Account inventoryAccount, BigDecimal stock) {
    return rawMaterialRepository
        .findByCompanyAndSku(company, "CR-BUCKET")
        .orElseGet(
            () -> {
              RawMaterial rm = new RawMaterial();
              rm.setCompany(company);
              rm.setSku("CR-BUCKET");
              rm.setName("Bucket");
              rm.setUnitType("PCS");
              rm.setMaterialType(MaterialType.PACKAGING);
              rm.setInventoryAccountId(inventoryAccount.getId());
              rm.setCurrentStock(stock);
              return rawMaterialRepository.save(rm);
            });
  }

  private void ensureRawMaterialBatch(
      RawMaterial material, BigDecimal quantity, BigDecimal costPerUnit) {
    RawMaterialBatch batch = new RawMaterialBatch();
    batch.setRawMaterial(material);
    batch.setBatchCode(material.getSku() + "-B1");
    batch.setQuantity(quantity);
    batch.setUnit(material.getUnitType());
    batch.setCostPerUnit(costPerUnit);
    rawMaterialBatchRepository.save(batch);
  }

  private void ensurePackagingSizeMapping(Company company, RawMaterial material, String size) {
    if (!packagingSizeMappingRepository
        .findActiveByCompanyAndPackagingSizeIgnoreCase(company, size)
        .isEmpty()) {
      return;
    }
    PackagingSizeMapping mapping = new PackagingSizeMapping();
    mapping.setCompany(company);
    mapping.setPackagingSize(size);
    mapping.setRawMaterial(material);
    mapping.setUnitsPerPack(1);
    mapping.setLitersPerUnit(new BigDecimal("1"));
    mapping.setActive(true);
    packagingSizeMappingRepository.save(mapping);
  }

  private static String shortId() {
    return UUID.randomUUID().toString().substring(0, 8);
  }
}
