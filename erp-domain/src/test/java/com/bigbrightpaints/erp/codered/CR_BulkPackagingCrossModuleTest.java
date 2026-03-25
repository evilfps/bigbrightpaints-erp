package com.bigbrightpaints.erp.codered;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import java.time.Duration;
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

import com.bigbrightpaints.erp.codered.support.CoderedConcurrencyHarness;
import com.bigbrightpaints.erp.codered.support.CoderedDbAssertions;
import com.bigbrightpaints.erp.codered.support.CoderedRetry;
import com.bigbrightpaints.erp.core.security.CompanyContextHolder;
import com.bigbrightpaints.erp.modules.accounting.domain.Account;
import com.bigbrightpaints.erp.modules.accounting.domain.AccountRepository;
import com.bigbrightpaints.erp.modules.accounting.domain.AccountType;
import com.bigbrightpaints.erp.modules.accounting.domain.JournalEntryRepository;
import com.bigbrightpaints.erp.modules.company.domain.Company;
import com.bigbrightpaints.erp.modules.company.domain.CompanyRepository;
import com.bigbrightpaints.erp.modules.factory.domain.PackagingSizeMapping;
import com.bigbrightpaints.erp.modules.factory.domain.PackagingSizeMappingRepository;
import com.bigbrightpaints.erp.modules.factory.domain.ProductionLog;
import com.bigbrightpaints.erp.modules.factory.domain.ProductionLogRepository;
import com.bigbrightpaints.erp.modules.factory.domain.SizeVariant;
import com.bigbrightpaints.erp.modules.factory.domain.SizeVariantRepository;
import com.bigbrightpaints.erp.modules.factory.dto.BulkPackRequest;
import com.bigbrightpaints.erp.modules.factory.dto.BulkPackResponse;
import com.bigbrightpaints.erp.modules.factory.dto.PackingLineRequest;
import com.bigbrightpaints.erp.modules.factory.dto.PackingRequest;
import com.bigbrightpaints.erp.modules.factory.service.BulkPackingService;
import com.bigbrightpaints.erp.modules.factory.service.PackingService;
import com.bigbrightpaints.erp.modules.inventory.domain.FinishedGood;
import com.bigbrightpaints.erp.modules.inventory.domain.FinishedGoodBatch;
import com.bigbrightpaints.erp.modules.inventory.domain.FinishedGoodBatchRepository;
import com.bigbrightpaints.erp.modules.inventory.domain.FinishedGoodRepository;
import com.bigbrightpaints.erp.modules.inventory.domain.InventoryReference;
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
import com.bigbrightpaints.erp.test.support.TestDateUtils;

@Tag("critical")
@Tag("concurrency")
@Tag("reconciliation")
class CR_BulkPackagingCrossModuleTest extends AbstractIntegrationTest {

  @Autowired private CompanyRepository companyRepository;
  @Autowired private AccountRepository accountRepository;
  @Autowired private ProductionBrandRepository productionBrandRepository;
  @Autowired private ProductionProductRepository productionProductRepository;
  @Autowired private ProductionLogRepository productionLogRepository;
  @Autowired private PackingService packingService;
  @Autowired private BulkPackingService bulkPackingService;
  @Autowired private FinishedGoodsService finishedGoodsService;
  @Autowired private FinishedGoodRepository finishedGoodRepository;
  @Autowired private FinishedGoodBatchRepository finishedGoodBatchRepository;
  @Autowired private RawMaterialRepository rawMaterialRepository;
  @Autowired private RawMaterialBatchRepository rawMaterialBatchRepository;
  @Autowired private PackagingSizeMappingRepository packagingSizeMappingRepository;
  @Autowired private SizeVariantRepository sizeVariantRepository;
  @Autowired private JournalEntryRepository journalEntryRepository;
  @Autowired private JdbcTemplate jdbcTemplate;

  @AfterEach
  void clearCompanyContext() {
    CompanyContextHolder.clear();
  }

  @Test
  void bulkPack_consumesPackaging_createsChildBatches_andPostsJournal() {
    String companyCode = "CR-BULK-" + shortId();
    Company company = bootstrapCompany(companyCode, "UTC");
    Map<String, Account> accounts = ensureFactoryAccounts(company);

    ProductionProduct bulkProduct =
        ensureProductionProduct(company, accounts, "CR-BULK-" + shortId());
    ProductionLog log =
        createProductionLog(company, bulkProduct, "PROD-" + shortId(), new BigDecimal("10"));

    seedSemiFinishedBatch(
        company,
        companyCode,
        accounts,
        bulkProduct.getSkuCode(),
        log.getProductionCode(),
        new BigDecimal("10"));
    RawMaterial bucket =
        ensurePackagingMaterial(company, accounts.get("PACK_INV"), new BigDecimal("20"));
    ensureRawMaterialBatch(bucket, new BigDecimal("20"), new BigDecimal("2.00"));
    ensurePackagingSizeMapping(company, bucket, "1L");
    ensurePackagingSizeMapping(company, bucket, "4L");
    FinishedGood packTarget = ensurePackTarget(company, bulkProduct, accounts, "1L");

    LocalDate packingDate = TestDateUtils.safeDate(company);
    CompanyContextHolder.setCompanyId(companyCode);
    packingService.recordPacking(
        new PackingRequest(
            log.getId(),
            packingDate,
            "codered",
            List.of(
                new PackingLineRequest(
                    packTarget.getId(), null, "1L", new BigDecimal("10"), 10, null, null))));
    CompanyContextHolder.clear();

    FinishedGood bulkFg =
        finishedGoodRepository
            .findByCompanyAndProductCode(company, bulkProduct.getSkuCode())
            .orElseThrow();
    FinishedGoodBatch bulkBatch =
        finishedGoodBatchRepository.findAvailableBulkBatches(bulkFg).stream()
            .findFirst()
            .orElseThrow();

    FinishedGood childOne =
        ensureFinishedGood(company, "CR-CHILD-1L-" + shortId(), accounts.get("FG_INV"), accounts);
    FinishedGood childFour =
        ensureFinishedGood(company, "CR-CHILD-4L-" + shortId(), accounts.get("FG_INV"), accounts);

    CompanyContextHolder.setCompanyId(companyCode);
    BulkPackResponse response =
        bulkPackingService.pack(
            new BulkPackRequest(
                bulkBatch.getId(),
                List.of(
                    new BulkPackRequest.PackLine(childOne.getId(), new BigDecimal("2"), "1L", "L"),
                    new BulkPackRequest.PackLine(
                        childFour.getId(), new BigDecimal("2"), "4L", "L")),
                packingDate,
                "codered",
                "CODE-RED bulk pack",
                null));
    CompanyContextHolder.clear();

    assertThat(response.journalEntryId()).as("packaging journal").isNotNull();
    CoderedDbAssertions.assertBalancedJournal(journalEntryRepository, response.journalEntryId());
    CoderedDbAssertions.assertAuditLogRecordedForJournal(jdbcTemplate, response.journalEntryId());

    FinishedGoodBatch refreshedBulk =
        finishedGoodBatchRepository.findById(bulkBatch.getId()).orElseThrow();
    assertThat(refreshedBulk.getQuantityAvailable()).as("bulk qty available").isZero();
    assertThat(refreshedBulk.getQuantityTotal()).as("bulk qty total").isZero();

    FinishedGood refreshedBulkFg = finishedGoodRepository.findById(bulkFg.getId()).orElseThrow();
    assertThat(refreshedBulkFg.getCurrentStock()).as("bulk fg stock").isZero();

    List<FinishedGoodBatch> children = finishedGoodBatchRepository.findByParentBatch(refreshedBulk);
    assertThat(children)
        .as("child batches created")
        .hasSize(2)
        .allSatisfy(child -> assertThat(child.isBulk()).isFalse());

    FinishedGood refreshedChildOne =
        finishedGoodRepository.findById(childOne.getId()).orElseThrow();
    FinishedGood refreshedChildFour =
        finishedGoodRepository.findById(childFour.getId()).orElseThrow();
    assertThat(refreshedChildOne.getCurrentStock())
        .as("1L stock")
        .isEqualByComparingTo(new BigDecimal("2"));
    assertThat(refreshedChildFour.getCurrentStock())
        .as("4L stock")
        .isEqualByComparingTo(new BigDecimal("2"));

    RawMaterial refreshedBucket = rawMaterialRepository.findById(bucket.getId()).orElseThrow();
    assertThat(refreshedBucket.getCurrentStock())
        .as("packaging stock consumed")
        .isEqualByComparingTo(new BigDecimal("6"));

    String packReference =
        jdbcTemplate.queryForObject(
            """
            select distinct rmm.reference_id
            from raw_material_movements rmm
            join raw_materials rm on rm.id = rmm.raw_material_id
            where rm.company_id = ?
              and rmm.reference_type = ?
              and rmm.journal_entry_id = ?
            """,
            String.class,
            company.getId(),
            InventoryReference.PACKING_RECORD,
            response.journalEntryId());
    assertThat(packReference).as("pack reference").isNotBlank();

    CoderedDbAssertions.assertRawMaterialMovementsLinkedToJournal(
        jdbcTemplate,
        company.getId(),
        InventoryReference.PACKING_RECORD,
        packReference,
        response.journalEntryId());

    Integer bulkIssues =
        jdbcTemplate.queryForObject(
            """
            select count(*)
            from inventory_movements im
            where im.reference_type = ?
              and im.reference_id = ?
              and im.movement_type = 'ISSUE'
            """,
            Integer.class,
            InventoryReference.PACKING_RECORD,
            packReference);
    assertThat(bulkIssues).as("bulk issue movement").isEqualTo(1);

    Integer childReceipts =
        jdbcTemplate.queryForObject(
            """
            select count(*)
            from inventory_movements im
            where im.reference_type = ?
              and im.reference_id = ?
              and im.movement_type = 'RECEIPT'
            """,
            Integer.class,
            InventoryReference.PACKING_RECORD,
            packReference);
    assertThat(childReceipts).as("child receipt movements").isEqualTo(2);

    CoderedDbAssertions.assertNoNegativeInventory(jdbcTemplate, company.getId());
  }

  @Test
  void bulkPack_rejectsFractionalPackCount_andDoesNotMutateStock() {
    String companyCode = "CR-BULK-FRAC-" + shortId();
    Company company = bootstrapCompany(companyCode, "UTC");
    Map<String, Account> accounts = ensureFactoryAccounts(company);

    ProductionProduct bulkProduct =
        ensureProductionProduct(company, accounts, "CR-BULK-" + shortId());
    ProductionLog log =
        createProductionLog(company, bulkProduct, "PROD-" + shortId(), new BigDecimal("5"));

    seedSemiFinishedBatch(
        company,
        companyCode,
        accounts,
        bulkProduct.getSkuCode(),
        log.getProductionCode(),
        new BigDecimal("5"));
    RawMaterial bucket =
        ensurePackagingMaterial(company, accounts.get("PACK_INV"), new BigDecimal("20"));
    ensureRawMaterialBatch(bucket, new BigDecimal("20"), new BigDecimal("1.50"));
    ensurePackagingSizeMapping(company, bucket, "1L");
    FinishedGood packTarget = ensurePackTarget(company, bulkProduct, accounts, "1L");

    LocalDate packingDate = TestDateUtils.safeDate(company);
    CompanyContextHolder.setCompanyId(companyCode);
    packingService.recordPacking(
        new PackingRequest(
            log.getId(),
            packingDate,
            "codered",
            List.of(
                new PackingLineRequest(
                    packTarget.getId(), null, "1L", new BigDecimal("5"), 5, null, null))));
    CompanyContextHolder.clear();

    FinishedGood bulkFg =
        finishedGoodRepository
            .findByCompanyAndProductCode(company, bulkProduct.getSkuCode())
            .orElseThrow();
    FinishedGoodBatch bulkBatch =
        finishedGoodBatchRepository.findAvailableBulkBatches(bulkFg).stream()
            .findFirst()
            .orElseThrow();

    FinishedGood childOne =
        ensureFinishedGood(company, "CR-CHILD-1L-" + shortId(), accounts.get("FG_INV"), accounts);

    CompanyContextHolder.setCompanyId(companyCode);
    assertThatThrownBy(
            () ->
                bulkPackingService.pack(
                    new BulkPackRequest(
                        bulkBatch.getId(),
                        List.of(
                            new BulkPackRequest.PackLine(
                                childOne.getId(), new BigDecimal("1.5"), "1L", "L")),
                        packingDate,
                        "codered",
                        "CODE-RED fractional pack",
                        null)))
        .hasMessageContaining("whole number");
    CompanyContextHolder.clear();

    FinishedGoodBatch refreshedBulk =
        finishedGoodBatchRepository.findById(bulkBatch.getId()).orElseThrow();
    assertThat(refreshedBulk.getQuantityAvailable())
        .as("bulk unchanged")
        .isEqualByComparingTo(new BigDecimal("5"));
    assertThat(freshenedChildBatches(refreshedBulk)).as("no child batches").isEmpty();

    Integer inventoryMovements =
        jdbcTemplate.queryForObject(
            """
            select count(*)
            from inventory_movements im
            join finished_goods fg on fg.id = im.finished_good_id
            where fg.company_id = ?
              and im.reference_type = ?
              and im.reference_id like 'PACK-%'
            """,
            Integer.class, company.getId(), InventoryReference.PACKING_RECORD);
    assertThat(inventoryMovements).as("no bulk-pack inventory movements").isZero();
  }

  @Test
  void bulkPack_idempotentRetry_doesNotDoubleConsumeOrPost() {
    String companyCode = "CR-BULK-IDEMP-" + shortId();
    Company company = bootstrapCompany(companyCode, "UTC");
    Map<String, Account> accounts = ensureFactoryAccounts(company);

    ProductionProduct bulkProduct =
        ensureProductionProduct(company, accounts, "CR-BULK-" + shortId());
    ProductionLog log =
        createProductionLog(company, bulkProduct, "PROD-" + shortId(), new BigDecimal("10"));
    seedSemiFinishedBatch(
        company,
        companyCode,
        accounts,
        bulkProduct.getSkuCode(),
        log.getProductionCode(),
        new BigDecimal("10"));
    RawMaterial bucket =
        ensurePackagingMaterial(company, accounts.get("PACK_INV"), new BigDecimal("20"));
    ensureRawMaterialBatch(bucket, new BigDecimal("20"), new BigDecimal("2.00"));
    ensurePackagingSizeMapping(company, bucket, "1L");
    ensurePackagingSizeMapping(company, bucket, "4L");
    FinishedGood packTarget = ensurePackTarget(company, bulkProduct, accounts, "1L");

    LocalDate packingDate = TestDateUtils.safeDate(company);
    CompanyContextHolder.setCompanyId(companyCode);
    packingService.recordPacking(
        new PackingRequest(
            log.getId(),
            packingDate,
            "codered",
            List.of(
                new PackingLineRequest(
                    packTarget.getId(), null, "1L", new BigDecimal("10"), 10, null, null))));
    CompanyContextHolder.clear();

    FinishedGood bulkFg =
        finishedGoodRepository
            .findByCompanyAndProductCode(company, bulkProduct.getSkuCode())
            .orElseThrow();
    FinishedGoodBatch bulkBatch =
        finishedGoodBatchRepository.findAvailableBulkBatches(bulkFg).stream()
            .findFirst()
            .orElseThrow();

    FinishedGood childOne =
        ensureFinishedGood(company, "CR-CHILD-1L-" + shortId(), accounts.get("FG_INV"), accounts);
    FinishedGood childFour =
        ensureFinishedGood(company, "CR-CHILD-4L-" + shortId(), accounts.get("FG_INV"), accounts);

    String idempotencyKey = "BULK-IDEMP-" + shortId();
    BulkPackRequest request =
        new BulkPackRequest(
            bulkBatch.getId(),
            List.of(
                new BulkPackRequest.PackLine(childOne.getId(), new BigDecimal("2"), "1L", "L"),
                new BulkPackRequest.PackLine(childFour.getId(), new BigDecimal("2"), "4L", "L")),
            packingDate,
            "codered",
            "CODE-RED bulk pack idempotent",
            idempotencyKey);

    CompanyContextHolder.setCompanyId(companyCode);
    BulkPackResponse first = bulkPackingService.pack(request);
    String packReference = resolvePackReference(company);
    int inventoryMovements = countInventoryMovements(company, packReference);
    int rawMovements = countRawMaterialMovements(company, packReference);
    BigDecimal bulkRemaining =
        finishedGoodBatchRepository
            .findById(bulkBatch.getId())
            .orElseThrow()
            .getQuantityAvailable();
    BigDecimal packagingRemaining =
        rawMaterialRepository.findById(bucket.getId()).orElseThrow().getCurrentStock();

    BulkPackResponse second = bulkPackingService.pack(request);
    CompanyContextHolder.clear();

    assertThat(second.journalEntryId())
        .as("same journal on retry")
        .isEqualTo(first.journalEntryId());
    assertThat(countInventoryMovements(company, packReference))
        .as("no extra inventory movements")
        .isEqualTo(inventoryMovements);
    assertThat(countRawMaterialMovements(company, packReference))
        .as("no extra packaging movements")
        .isEqualTo(rawMovements);
    assertThat(
            finishedGoodBatchRepository
                .findById(bulkBatch.getId())
                .orElseThrow()
                .getQuantityAvailable())
        .as("bulk quantity unchanged on retry")
        .isEqualByComparingTo(bulkRemaining);
    assertThat(rawMaterialRepository.findById(bucket.getId()).orElseThrow().getCurrentStock())
        .as("packaging stock unchanged on retry")
        .isEqualByComparingTo(packagingRemaining);
  }

  @Test
  void bulkPack_idempotentConcurrent_returnsSameJournal() {
    String companyCode = "CR-BULK-CONC-" + shortId();
    Company company = bootstrapCompany(companyCode, "UTC");
    Map<String, Account> accounts = ensureFactoryAccounts(company);

    ProductionProduct bulkProduct =
        ensureProductionProduct(company, accounts, "CR-BULK-" + shortId());
    ProductionLog log =
        createProductionLog(company, bulkProduct, "PROD-" + shortId(), new BigDecimal("10"));
    seedSemiFinishedBatch(
        company,
        companyCode,
        accounts,
        bulkProduct.getSkuCode(),
        log.getProductionCode(),
        new BigDecimal("10"));
    RawMaterial bucket =
        ensurePackagingMaterial(company, accounts.get("PACK_INV"), new BigDecimal("20"));
    ensureRawMaterialBatch(bucket, new BigDecimal("20"), new BigDecimal("2.00"));
    ensurePackagingSizeMapping(company, bucket, "1L");
    ensurePackagingSizeMapping(company, bucket, "4L");
    FinishedGood packTarget = ensurePackTarget(company, bulkProduct, accounts, "1L");

    LocalDate packingDate = TestDateUtils.safeDate(company);
    CompanyContextHolder.setCompanyId(companyCode);
    packingService.recordPacking(
        new PackingRequest(
            log.getId(),
            packingDate,
            "codered",
            List.of(
                new PackingLineRequest(
                    packTarget.getId(), null, "1L", new BigDecimal("10"), 10, null, null))));
    CompanyContextHolder.clear();

    FinishedGood bulkFg =
        finishedGoodRepository
            .findByCompanyAndProductCode(company, bulkProduct.getSkuCode())
            .orElseThrow();
    FinishedGoodBatch bulkBatch =
        finishedGoodBatchRepository.findAvailableBulkBatches(bulkFg).stream()
            .findFirst()
            .orElseThrow();

    FinishedGood childOne =
        ensureFinishedGood(company, "CR-CHILD-1L-" + shortId(), accounts.get("FG_INV"), accounts);
    FinishedGood childFour =
        ensureFinishedGood(company, "CR-CHILD-4L-" + shortId(), accounts.get("FG_INV"), accounts);

    BulkPackRequest request =
        new BulkPackRequest(
            bulkBatch.getId(),
            List.of(
                new BulkPackRequest.PackLine(childOne.getId(), new BigDecimal("2"), "1L", "L"),
                new BulkPackRequest.PackLine(childFour.getId(), new BigDecimal("2"), "4L", "L")),
            packingDate,
            "codered",
            "CODE-RED bulk pack concurrency",
            "BULK-CONC-" + shortId());

    var result =
        CoderedConcurrencyHarness.run(
            2,
            3,
            Duration.ofSeconds(30),
            threadIndex ->
                () -> {
                  CompanyContextHolder.setCompanyId(companyCode);
                  try {
                    return bulkPackingService.pack(request);
                  } finally {
                    CompanyContextHolder.clear();
                  }
                },
            CoderedRetry::isRetryable);

    assertThat(result.outcomes())
        .allMatch(outcome -> outcome instanceof CoderedConcurrencyHarness.Outcome.Success<?>);

    List<Long> journalIds =
        result.outcomes().stream()
            .map(
                outcome ->
                    ((CoderedConcurrencyHarness.Outcome.Success<BulkPackResponse>) outcome).value())
            .map(BulkPackResponse::journalEntryId)
            .distinct()
            .toList();
    assertThat(journalIds).as("single journal for concurrent pack").hasSize(1);

    String packReference = resolvePackReference(company);
    assertThat(countInventoryMovements(company, packReference))
        .as("single pack inventory movement set")
        .isEqualTo(3);
    assertThat(countRawMaterialMovements(company, packReference))
        .as("single packaging movement set")
        .isEqualTo(2);
  }

  private List<FinishedGoodBatch> freshenedChildBatches(FinishedGoodBatch bulkBatch) {
    return finishedGoodBatchRepository.findByParentBatch(bulkBatch);
  }

  private String resolvePackReference(Company company) {
    return jdbcTemplate.queryForObject(
        """
        select distinct im.reference_id
        from inventory_movements im
        join finished_goods fg on fg.id = im.finished_good_id
        where fg.company_id = ?
          and im.reference_type = ?
          and im.reference_id like 'PACK-%'
        """,
        String.class, company.getId(), InventoryReference.PACKING_RECORD);
  }

  private int countInventoryMovements(Company company, String reference) {
    return jdbcTemplate.queryForObject(
        """
        select count(*)
        from inventory_movements im
        join finished_goods fg on fg.id = im.finished_good_id
        where fg.company_id = ?
          and im.reference_type = ?
          and im.reference_id = ?
        """,
        Integer.class,
        company.getId(),
        InventoryReference.PACKING_RECORD,
        reference);
  }

  private int countRawMaterialMovements(Company company, String reference) {
    return jdbcTemplate.queryForObject(
        """
        select count(*)
        from raw_material_movements rm
        join raw_materials r on r.id = rm.raw_material_id
        where r.company_id = ?
          and rm.reference_type = ?
          and rm.reference_id = ?
        """,
        Integer.class,
        company.getId(),
        InventoryReference.PACKING_RECORD,
        reference);
  }

  private Company bootstrapCompany(String companyCode, String timezone) {
    dataSeeder.ensureCompany(companyCode, companyCode + " Ltd");
    CompanyContextHolder.setCompanyId(companyCode);
    Company company = companyRepository.findByCodeIgnoreCase(companyCode).orElseThrow();
    company.setTimezone(timezone);
    company.setBaseCurrency("INR");
    return companyRepository.save(company);
  }

  private Map<String, Account> ensureFactoryAccounts(Company company) {
    Account fgInv = ensureAccount(company, "FG_INV", "FG Inventory", AccountType.ASSET);
    Account sfInv = ensureAccount(company, "SF_INV", "Semi Finished Inventory", AccountType.ASSET);
    Account wip = ensureAccount(company, "WIP", "WIP", AccountType.ASSET);
    Account packInv = ensureAccount(company, "PACK_INV", "Packaging Inventory", AccountType.ASSET);
    Account cogs = ensureAccount(company, "COGS", "COGS", AccountType.COGS);
    Account rev = ensureAccount(company, "REV", "Revenue", AccountType.REVENUE);
    Account disc = ensureAccount(company, "DISC", "Discounts", AccountType.EXPENSE);
    Account gstOut = ensureAccount(company, "GST_OUT", "GST Output", AccountType.LIABILITY);

    updateCompanyDefaults(company, fgInv, cogs, rev, disc, gstOut);

    return Map.of(
        "FG_INV", fgInv,
        "SF_INV", sfInv,
        "WIP", wip,
        "PACK_INV", packInv,
        "COGS", cogs,
        "REV", rev,
        "DISC", disc,
        "GST_OUT", gstOut);
  }

  private void updateCompanyDefaults(
      Company company, Account inv, Account cogs, Account rev, Account disc, Account gstOut) {
    Company fresh = companyRepository.findById(company.getId()).orElseThrow();
    if (fresh.getDefaultInventoryAccountId() == null) {
      fresh.setDefaultInventoryAccountId(inv.getId());
    }
    if (fresh.getDefaultCogsAccountId() == null) {
      fresh.setDefaultCogsAccountId(cogs.getId());
    }
    if (fresh.getDefaultRevenueAccountId() == null) {
      fresh.setDefaultRevenueAccountId(rev.getId());
    }
    if (fresh.getDefaultDiscountAccountId() == null) {
      fresh.setDefaultDiscountAccountId(disc.getId());
    }
    if (fresh.getGstOutputTaxAccountId() == null) {
      fresh.setGstOutputTaxAccountId(gstOut.getId());
    }
    if (fresh.getGstPayableAccountId() == null) {
      fresh.setGstPayableAccountId(gstOut.getId());
    }
    companyRepository.save(fresh);
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
              p.setProductName("Bulk " + sku);
              p.setBasePrice(new BigDecimal("10.00"));
              p.setCategory("GENERAL");
              p.setSizeLabel("BULK");
              p.setDefaultColour("NA");
              p.setMinDiscountPercent(BigDecimal.ZERO);
              p.setMinSellingPrice(BigDecimal.ZERO);
              p.setUnitOfMeasure("L");
              p.setGstRate(BigDecimal.ZERO);
              p.setActive(true);
              Map<String, Object> metadata = new HashMap<>();
              metadata.put("wipAccountId", accounts.get("WIP").getId());
              metadata.put("semiFinishedAccountId", accounts.get("SF_INV").getId());
              metadata.put("fgValuationAccountId", accounts.get("SF_INV").getId());
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
      Company company, ProductionProduct product, String productionCode, BigDecimal qty) {
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
    log.setProducedAt(Instant.now());
    return productionLogRepository.save(log);
  }

  private FinishedGood ensureFinishedGood(
      Company company, String sku, Account valuationAccount, Map<String, Account> accounts) {
    CompanyContextHolder.setCompanyId(company.getCode());
    FinishedGoodRequest req =
        new FinishedGoodRequest(
            sku,
            sku,
            "UNIT",
            "FIFO",
            valuationAccount.getId(),
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
    if (!sizeLabel.equalsIgnoreCase(product.getSizeLabel())) {
      product.setSizeLabel(sizeLabel);
      productionProductRepository.save(product);
    }
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

  private void seedSemiFinishedBatch(
      Company company,
      String companyCode,
      Map<String, Account> accounts,
      String productSku,
      String productionCode,
      BigDecimal quantity) {
    String semiSku = productSku + "-BULK";
    FinishedGood semiFinished =
        ensureFinishedGood(company, semiSku, accounts.get("SF_INV"), accounts);
    CompanyContextHolder.setCompanyId(companyCode);
    finishedGoodsService.registerBatch(
        new FinishedGoodBatchRequest(
            semiFinished.getId(),
            productionCode,
            quantity,
            new BigDecimal("12.5000"),
            Instant.now(),
            null));
    CompanyContextHolder.clear();
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
