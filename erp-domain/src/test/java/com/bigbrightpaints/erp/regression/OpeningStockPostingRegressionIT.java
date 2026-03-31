package com.bigbrightpaints.erp.regression;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.mock.web.MockMultipartFile;

import com.bigbrightpaints.erp.core.security.CompanyContextHolder;
import com.bigbrightpaints.erp.modules.accounting.domain.Account;
import com.bigbrightpaints.erp.modules.accounting.domain.AccountRepository;
import com.bigbrightpaints.erp.modules.accounting.domain.AccountType;
import com.bigbrightpaints.erp.modules.accounting.domain.JournalEntryRepository;
import com.bigbrightpaints.erp.modules.company.domain.Company;
import com.bigbrightpaints.erp.modules.company.domain.CompanyRepository;
import com.bigbrightpaints.erp.modules.inventory.domain.InventoryMovement;
import com.bigbrightpaints.erp.modules.inventory.domain.InventoryMovementRepository;
import com.bigbrightpaints.erp.modules.inventory.domain.InventoryReference;
import com.bigbrightpaints.erp.modules.inventory.domain.RawMaterial;
import com.bigbrightpaints.erp.modules.inventory.domain.RawMaterialBatch;
import com.bigbrightpaints.erp.modules.inventory.domain.RawMaterialBatchRepository;
import com.bigbrightpaints.erp.modules.inventory.domain.RawMaterialMovement;
import com.bigbrightpaints.erp.modules.inventory.domain.RawMaterialMovementRepository;
import com.bigbrightpaints.erp.modules.inventory.domain.RawMaterialRepository;
import com.bigbrightpaints.erp.modules.inventory.dto.OpeningStockImportResponse;
import com.bigbrightpaints.erp.modules.inventory.service.OpeningStockImportService;
import com.bigbrightpaints.erp.modules.production.domain.ProductionBrand;
import com.bigbrightpaints.erp.modules.production.domain.ProductionBrandRepository;
import com.bigbrightpaints.erp.modules.production.dto.CatalogItemCreateCommand;
import com.bigbrightpaints.erp.modules.production.service.ProductionCatalogService;
import com.bigbrightpaints.erp.modules.reports.dto.ReconciliationSummaryDto;
import com.bigbrightpaints.erp.modules.reports.service.ReportService;
import com.bigbrightpaints.erp.test.AbstractIntegrationTest;

@DisplayName("Regression: Opening stock import posts GL and links movements")
@Tag("critical")
class OpeningStockPostingRegressionIT extends AbstractIntegrationTest {

  private static final String COMPANY_CODE = "LF-021";
  private static final String OPENING_STOCK_BATCH_KEY = "OPEN-STOCK-BATCH-LF021-001";

  @Autowired private CompanyRepository companyRepository;

  @Autowired private AccountRepository accountRepository;

  @Autowired private OpeningStockImportService openingStockImportService;

  @Autowired private ProductionCatalogService productionCatalogService;

  @Autowired private ProductionBrandRepository productionBrandRepository;

  @Autowired private RawMaterialMovementRepository rawMaterialMovementRepository;

  @Autowired private RawMaterialRepository rawMaterialRepository;

  @Autowired private RawMaterialBatchRepository rawMaterialBatchRepository;

  @Autowired private InventoryMovementRepository inventoryMovementRepository;

  @Autowired private JournalEntryRepository journalEntryRepository;

  @Autowired private ReportService reportService;

  private Company company;
  private Account inventoryAccount;
  private Account wipAccount;
  private Account discountAccount;

  @BeforeEach
  void setUp() {
    company =
        companyRepository
            .findByCodeIgnoreCase(COMPANY_CODE)
            .orElseGet(
                () -> {
                  Company created = new Company();
                  created.setCode(COMPANY_CODE);
                  created.setName("LF-021 Opening Stock");
                  created.setTimezone("UTC");
                  return companyRepository.save(created);
                });
    inventoryAccount = ensureAccount(company, "INV-LF021", "Inventory", AccountType.ASSET);
    wipAccount = ensureAccount(company, "WIP-LF021", "Work In Progress", AccountType.ASSET);
    discountAccount = ensureAccount(company, "DISC-LF021", "Discounts", AccountType.EXPENSE);
    ensureAccount(company, "COGS-LF021", "COGS", AccountType.COGS);
    ensureAccount(company, "REV-LF021", "Revenue", AccountType.REVENUE);
    ensureAccount(company, "GST-LF021", "GST Output", AccountType.LIABILITY);
    ensureAccount(company, "OPEN-BAL", "Opening Balance", AccountType.EQUITY);

    company.setDefaultInventoryAccountId(inventoryAccount.getId());
    company.setDefaultCogsAccountId(
        accountRepository
            .findByCompanyAndCodeIgnoreCase(company, "COGS-LF021")
            .orElseThrow()
            .getId());
    company.setDefaultRevenueAccountId(
        accountRepository
            .findByCompanyAndCodeIgnoreCase(company, "REV-LF021")
            .orElseThrow()
            .getId());
    company.setDefaultDiscountAccountId(discountAccount.getId());
    company.setDefaultTaxAccountId(
        accountRepository
            .findByCompanyAndCodeIgnoreCase(company, "GST-LF021")
            .orElseThrow()
            .getId());
    companyRepository.save(company);

    CompanyContextHolder.setCompanyCode(COMPANY_CODE);
  }

  @AfterEach
  void tearDown() {
    CompanyContextHolder.clear();
  }

  @Test
  void openingStockImportCreatesJournalAndReconciles() {
    String rawMaterialSku = createCatalogRawMaterialSku();
    String finishedGoodSku = createCatalogFinishedGoodSku();
    String csv =
        String.join(
            "\n",
            "type,sku,name,unit,unit_type,batch_code,quantity,unit_cost,material_type,manufactured_at,expiry_date",
            "RAW_MATERIAL,%s,Resin,KG,KG,RM-OPEN-B1,10,5.00,PRODUCTION,2026-01-05,2027-01-05"
                .formatted(rawMaterialSku),
            "FINISHED_GOOD,%s,Paint 1L,LITER,LITER,FG-OPEN-B1,5,12.50,,2026-01-10"
                .formatted(finishedGoodSku));
    MockMultipartFile file =
        new MockMultipartFile(
            "file", "opening.csv", "text/csv", csv.getBytes(StandardCharsets.UTF_8));

    OpeningStockImportResponse response =
        openingStockImportService.importOpeningStock(
            file, "opening-stock-regression-key", OPENING_STOCK_BATCH_KEY);
    assertThat(response.rowsProcessed()).isEqualTo(2);
    assertThat(response.rawMaterialBatchesCreated()).isEqualTo(1);
    assertThat(response.finishedGoodBatchesCreated()).isEqualTo(1);
    assertThat(response.errors()).isEmpty();
    assertThat(response.results())
        .extracting(OpeningStockImportResponse.ImportRowResult::sku)
        .containsExactly(rawMaterialSku, finishedGoodSku);

    List<RawMaterialMovement> rmMovements =
        rawMaterialMovementRepository.findByRawMaterialCompanyAndReferenceTypeAndReferenceId(
            company, InventoryReference.OPENING_STOCK, "RM-OPEN-B1");
    assertThat(rmMovements).hasSize(1);
    assertThat(rmMovements.get(0).getJournalEntryId()).isNotNull();

    RawMaterial rawMaterial =
        rawMaterialRepository.findByCompanyAndSku(company, rawMaterialSku).orElseThrow();
    RawMaterialBatch rawBatch =
        rawMaterialBatchRepository.findByRawMaterial(rawMaterial).stream()
            .filter(batch -> "RM-OPEN-B1".equals(batch.getBatchCode()))
            .findFirst()
            .orElseThrow();
    assertThat(rawBatch.getManufacturedAt()).isEqualTo(Instant.parse("2026-01-05T00:00:00Z"));
    assertThat(rawBatch.getExpiryDate()).isEqualTo(LocalDate.of(2027, 1, 5));

    List<InventoryMovement> fgMovements =
        inventoryMovementRepository
            .findByFinishedGood_CompanyAndReferenceTypeAndReferenceIdOrderByCreatedAtAsc(
                company, InventoryReference.OPENING_STOCK, "FG-OPEN-B1");
    assertThat(fgMovements).hasSize(1);
    assertThat(fgMovements.get(0).getJournalEntryId()).isNotNull();
    assertThat(fgMovements.get(0).getJournalEntryId())
        .isEqualTo(rmMovements.get(0).getJournalEntryId());
    assertThat(
            journalEntryRepository
                .findById(fgMovements.get(0).getJournalEntryId())
                .orElseThrow()
                .getReferenceNumber())
        .startsWith("OPEN-STOCK-");

    ReconciliationSummaryDto summary = reportService.inventoryReconciliation();
    assertThat(summary.variance().abs()).isLessThanOrEqualTo(new BigDecimal("0.01"));
  }

  private String createCatalogRawMaterialSku() {
    CatalogItemCreateCommand request =
        new CatalogItemCreateCommand(
            saveBrand("LF021 Raw Material").getId(),
            null,
            null,
            "Opening Resin Natural 25KG",
            "RAW_MATERIAL",
            "RAW_MATERIAL",
            "NATURAL",
            "25KG",
            "KG",
            "320611",
            null,
            new BigDecimal("500.00"),
            new BigDecimal("18.00"),
            BigDecimal.ZERO,
            new BigDecimal("500.00"),
            Map.of("inventoryAccountId", inventoryAccount.getId()));
    return productionCatalogService.createCatalogItem(request).skuCode();
  }

  private String createCatalogFinishedGoodSku() {
    CatalogItemCreateCommand request =
        new CatalogItemCreateCommand(
            saveBrand("LF021 Finished Good").getId(),
            null,
            null,
            "Opening Paint White 1L",
            "FINISHED_GOOD",
            "FINISHED_GOOD",
            "WHITE",
            "1L",
            "LITER",
            "320910",
            null,
            new BigDecimal("1200.00"),
            new BigDecimal("18.00"),
            new BigDecimal("5.00"),
            new BigDecimal("1140.00"),
            Map.of("wipAccountId", wipAccount.getId()));
    return productionCatalogService.createCatalogItem(request).skuCode();
  }

  private ProductionBrand saveBrand(String name) {
    ProductionBrand brand = new ProductionBrand();
    brand.setCompany(company);
    brand.setName(name);
    String brandCode = ("LF" + Long.toString(System.nanoTime(), 36)).toUpperCase(Locale.ROOT);
    brand.setCode(brandCode.length() <= 10 ? brandCode : brandCode.substring(0, 10));
    brand.setActive(true);
    return productionBrandRepository.save(brand);
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
}
