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
import com.bigbrightpaints.erp.modules.factory.domain.PackagingSizeMapping;
import com.bigbrightpaints.erp.modules.factory.domain.PackagingSizeMappingRepository;
import com.bigbrightpaints.erp.modules.factory.domain.ProductionLog;
import com.bigbrightpaints.erp.modules.factory.domain.ProductionLogRepository;
import com.bigbrightpaints.erp.modules.factory.domain.ProductionLogStatus;
import com.bigbrightpaints.erp.modules.factory.domain.SizeVariant;
import com.bigbrightpaints.erp.modules.factory.domain.SizeVariantRepository;
import com.bigbrightpaints.erp.modules.factory.dto.CostAllocationRequest;
import com.bigbrightpaints.erp.modules.factory.dto.CostAllocationResponse;
import com.bigbrightpaints.erp.modules.factory.dto.PackingLineRequest;
import com.bigbrightpaints.erp.modules.factory.dto.PackingRequest;
import com.bigbrightpaints.erp.modules.factory.dto.ProductionLogDetailDto;
import com.bigbrightpaints.erp.modules.factory.dto.ProductionLogRequest;
import com.bigbrightpaints.erp.modules.factory.service.CostAllocationService;
import com.bigbrightpaints.erp.modules.factory.service.PackingService;
import com.bigbrightpaints.erp.modules.factory.service.ProductionLogService;
import com.bigbrightpaints.erp.modules.inventory.domain.FinishedGood;
import com.bigbrightpaints.erp.modules.inventory.domain.FinishedGoodBatch;
import com.bigbrightpaints.erp.modules.inventory.domain.FinishedGoodBatchRepository;
import com.bigbrightpaints.erp.modules.inventory.domain.FinishedGoodRepository;
import com.bigbrightpaints.erp.modules.inventory.domain.MaterialType;
import com.bigbrightpaints.erp.modules.inventory.domain.RawMaterial;
import com.bigbrightpaints.erp.modules.inventory.domain.RawMaterialBatch;
import com.bigbrightpaints.erp.modules.inventory.domain.RawMaterialBatchRepository;
import com.bigbrightpaints.erp.modules.inventory.domain.RawMaterialRepository;
import com.bigbrightpaints.erp.modules.production.domain.ProductionBrand;
import com.bigbrightpaints.erp.modules.production.domain.ProductionBrandRepository;
import com.bigbrightpaints.erp.modules.production.domain.ProductionProduct;
import com.bigbrightpaints.erp.modules.production.domain.ProductionProductRepository;
import com.bigbrightpaints.erp.test.AbstractIntegrationTest;

@DisplayName("E2E: Cost allocation variance policy")
public class CostAllocationVariancePolicyIT extends AbstractIntegrationTest {

  private static final String COMPANY_CODE_PREFIX = "WE-VAR";

  @Autowired private CompanyRepository companyRepository;
  @Autowired private AccountRepository accountRepository;
  @Autowired private ProductionBrandRepository productionBrandRepository;
  @Autowired private ProductionProductRepository productionProductRepository;
  @Autowired private RawMaterialRepository rawMaterialRepository;
  @Autowired private RawMaterialBatchRepository rawMaterialBatchRepository;
  @Autowired private PackagingSizeMappingRepository packagingSizeMappingRepository;
  @Autowired private SizeVariantRepository sizeVariantRepository;
  @Autowired private ProductionLogService productionLogService;
  @Autowired private PackingService packingService;
  @Autowired private CostAllocationService costAllocationService;
  @Autowired private FinishedGoodRepository finishedGoodRepository;
  @Autowired private FinishedGoodBatchRepository finishedGoodBatchRepository;
  @Autowired private JournalEntryRepository journalEntryRepository;
  @Autowired private ProductionLogRepository productionLogRepository;

  private Company company;
  private String companyCode;
  private Account wip;
  private Account rmInventory;
  private Account packagingInventory;
  private Account fgInventory;
  private Account cogs;
  private Account revenue;
  private Account discount;
  private Account tax;
  private Account laborApplied;
  private Account overheadApplied;

  @BeforeEach
  void init() {
    companyCode = COMPANY_CODE_PREFIX + "-" + System.nanoTime();
    company = dataSeeder.ensureCompany(companyCode, companyCode + " Ltd");
    CompanyContextHolder.setCompanyId(companyCode);
    wip = ensureAccount("WIP", AccountType.ASSET);
    rmInventory = ensureAccount("INV-RM", AccountType.ASSET);
    packagingInventory = ensureAccount("INV-PACK", AccountType.ASSET);
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
  @DisplayName("Variance allocation is a no-op when applied equals actual")
  void varianceAllocationNoOpWhenAppliedMatchesActual() {
    ProductionBrand brand = createBrand("VAR-BRAND");
    ProductionProduct product = createProduct("VAR-PROD", "Variance Product", brand);

    RawMaterial base = createRawMaterial("RM-BASE", rmInventory, new BigDecimal("100"));
    RawMaterial bucket =
        createRawMaterial("RM-BUCKET-5L", packagingInventory, new BigDecimal("10"));
    addBatch(base, new BigDecimal("100"), new BigDecimal("10"));
    addBatch(bucket, new BigDecimal("10"), new BigDecimal("5"));
    mapPackagingSize("5L", bucket);
    FinishedGood packTarget = ensurePackTarget(product, "5L");

    LocalDate periodDate = resolveClosedPeriodDate();

    ProductionLogDetailDto log =
        productionLogService.createLog(
            new ProductionLogRequest(
                brand.getId(),
                product.getId(),
                "WHITE",
                new BigDecimal("10"),
                "L",
                new BigDecimal("10"),
                periodDate.toString(),
                "VAR-TEST",
                "Supervisor",
                null,
                new BigDecimal("100.00"),
                new BigDecimal("50.00"),
                List.of(
                    new ProductionLogRequest.MaterialUsageRequest(
                        base.getId(), new BigDecimal("10"), "KG"))));

    packingService.recordPacking(
        new PackingRequest(
            log.id(),
            periodDate,
            "Packer",
            List.of(
                new PackingLineRequest(
                    packTarget.getId(), null, "5L", new BigDecimal("10"), 2, null, null))));
    ensureFullyPackedForPeriod(log.id(), periodDate);

    FinishedGood fg =
        finishedGoodRepository
            .findByCompanyAndProductCode(company, product.getSkuCode())
            .orElseThrow();
    FinishedGoodBatch fgBatch =
        finishedGoodBatchRepository.findAll().stream()
            .filter(batch -> batch.getFinishedGood().getId().equals(fg.getId()))
            .findFirst()
            .orElseThrow();
    BigDecimal unitCostBefore = fgBatch.getUnitCost();

    CostAllocationResponse response =
        costAllocationService.allocateCosts(
            new CostAllocationRequest(
                periodDate.getYear(),
                periodDate.getMonthValue(),
                new BigDecimal("100.00"),
                new BigDecimal("50.00"),
                fgInventory.getId(),
                laborApplied.getId(),
                overheadApplied.getId(),
                "Variance allocation"));

    assertThat(response.journalEntryIds()).isEmpty();
    assertThat(response.totalLaborAllocated()).isEqualByComparingTo(BigDecimal.ZERO);
    assertThat(response.totalOverheadAllocated()).isEqualByComparingTo(BigDecimal.ZERO);

    FinishedGoodBatch refreshed =
        finishedGoodBatchRepository.findById(fgBatch.getId()).orElseThrow();
    assertThat(refreshed.getUnitCost()).isEqualByComparingTo(unitCostBefore);
  }

  @Test
  @DisplayName("Second allocation run is a no-op when CVAR exists")
  void allocationNoOpWhenCvarExists() {
    ProductionBrand brand = createBrand("VAR-BRAND-2");
    ProductionProduct product = createProduct("VAR-PROD-2", "Variance Product 2", brand);

    RawMaterial base = createRawMaterial("RM-BASE-2", rmInventory, new BigDecimal("100"));
    RawMaterial bucket =
        createRawMaterial("RM-BUCKET-5L-2", packagingInventory, new BigDecimal("10"));
    addBatch(base, new BigDecimal("100"), new BigDecimal("10"));
    addBatch(bucket, new BigDecimal("10"), new BigDecimal("5"));
    mapPackagingSize("5L", bucket);
    FinishedGood packTarget = ensurePackTarget(product, "5L");

    LocalDate periodDate = resolveClosedPeriodDate();

    ProductionLogDetailDto log =
        productionLogService.createLog(
            new ProductionLogRequest(
                brand.getId(),
                product.getId(),
                "WHITE",
                new BigDecimal("10"),
                "L",
                new BigDecimal("10"),
                periodDate.toString(),
                "VAR-TEST-2",
                "Supervisor",
                null,
                new BigDecimal("100.00"),
                new BigDecimal("50.00"),
                List.of(
                    new ProductionLogRequest.MaterialUsageRequest(
                        base.getId(), new BigDecimal("10"), "KG"))));

    packingService.recordPacking(
        new PackingRequest(
            log.id(),
            periodDate,
            "Packer",
            List.of(
                new PackingLineRequest(
                    packTarget.getId(), null, "5L", new BigDecimal("10"), 2, null, null))));
    ensureFullyPackedForPeriod(log.id(), periodDate);

    String periodKey = String.format("%04d%02d", periodDate.getYear(), periodDate.getMonthValue());
    String reference = "CVAR-" + log.productionCode() + "-" + periodKey;

    CostAllocationResponse first =
        costAllocationService.allocateCosts(
            new CostAllocationRequest(
                periodDate.getYear(),
                periodDate.getMonthValue(),
                new BigDecimal("200.00"),
                new BigDecimal("80.00"),
                fgInventory.getId(),
                laborApplied.getId(),
                overheadApplied.getId(),
                "Variance allocation"));
    assertThat(first.journalEntryIds())
        .as("Expected CVAR journal; response summary: %s", first.summary())
        .isNotEmpty();

    JournalEntry firstJournal =
        journalEntryRepository.findByCompanyAndReferenceNumber(company, reference).orElseThrow();
    ProductionLog afterFirst = productionLogRepository.findById(log.id()).orElseThrow();

    FinishedGood fg =
        finishedGoodRepository
            .findByCompanyAndProductCode(company, product.getSkuCode())
            .orElseThrow();
    FinishedGoodBatch fgBatch =
        finishedGoodBatchRepository.findAll().stream()
            .filter(batch -> batch.getFinishedGood().getId().equals(fg.getId()))
            .findFirst()
            .orElseThrow();
    BigDecimal unitCostAfterFirst = fgBatch.getUnitCost();

    CostAllocationResponse second =
        costAllocationService.allocateCosts(
            new CostAllocationRequest(
                periodDate.getYear(),
                periodDate.getMonthValue(),
                new BigDecimal("250.00"),
                new BigDecimal("90.00"),
                fgInventory.getId(),
                laborApplied.getId(),
                overheadApplied.getId(),
                "Variance allocation rerun"));

    JournalEntry secondJournal =
        journalEntryRepository.findByCompanyAndReferenceNumber(company, reference).orElseThrow();
    assertThat(secondJournal.getId()).isEqualTo(firstJournal.getId());
    assertThat(second.summary()).contains("skipped");

    ProductionLog afterSecond = productionLogRepository.findById(log.id()).orElseThrow();
    assertThat(afterSecond.getLaborCostTotal())
        .isEqualByComparingTo(afterFirst.getLaborCostTotal());
    assertThat(afterSecond.getOverheadCostTotal())
        .isEqualByComparingTo(afterFirst.getOverheadCostTotal());
    assertThat(afterSecond.getUnitCost()).isEqualByComparingTo(afterFirst.getUnitCost());

    FinishedGoodBatch refreshed =
        finishedGoodBatchRepository.findById(fgBatch.getId()).orElseThrow();
    assertThat(refreshed.getUnitCost()).isEqualByComparingTo(unitCostAfterFirst);
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

  private FinishedGood ensurePackTarget(ProductionProduct product, String sizeLabel) {
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

    return finishedGoodRepository
        .findByCompanyAndProductCode(company, product.getSkuCode())
        .orElseGet(
            () -> {
              FinishedGood finishedGood = new FinishedGood();
              finishedGood.setCompany(company);
              finishedGood.setProductCode(product.getSkuCode());
              finishedGood.setName(product.getProductName());
              finishedGood.setUnit("L");
              finishedGood.setValuationAccountId(fgInventory.getId());
              finishedGood.setCogsAccountId(cogs.getId());
              finishedGood.setRevenueAccountId(revenue.getId());
              finishedGood.setDiscountAccountId(discount.getId());
              finishedGood.setTaxAccountId(tax.getId());
              return finishedGoodRepository.save(finishedGood);
            });
  }

  private RawMaterial createRawMaterial(String sku, Account inventoryAccount, BigDecimal stock) {
    RawMaterial rm = new RawMaterial();
    rm.setCompany(company);
    rm.setSku(sku);
    rm.setName(sku);
    rm.setUnitType("KG");
    rm.setMaterialType(
        inventoryAccount != null
                && packagingInventory != null
                && inventoryAccount.getId().equals(packagingInventory.getId())
            ? MaterialType.PACKAGING
            : MaterialType.PRODUCTION);
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

  private void ensureFullyPackedForPeriod(Long logId, LocalDate periodDate) {
    ProductionLog stored = productionLogRepository.findById(logId).orElseThrow();
    stored.setProducedAt(periodDate.atStartOfDay().toInstant(java.time.ZoneOffset.UTC));
    stored.setStatus(ProductionLogStatus.FULLY_PACKED);
    productionLogRepository.save(stored);
  }

  private LocalDate resolveClosedPeriodDate() {
    LocalDate today = LocalDate.now();
    java.time.YearMonth currentPeriod = java.time.YearMonth.from(today);
    LocalDate currentPeriodEnd = currentPeriod.atEndOfMonth();
    if (!currentPeriodEnd.isAfter(today)) {
      return today;
    }
    return today.withDayOfMonth(1).minusDays(1);
  }
}
