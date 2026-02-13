package com.bigbrightpaints.erp.regression;

import com.bigbrightpaints.erp.core.security.CompanyContextHolder;
import com.bigbrightpaints.erp.modules.accounting.domain.Account;
import com.bigbrightpaints.erp.modules.accounting.domain.AccountRepository;
import com.bigbrightpaints.erp.modules.accounting.domain.AccountType;
import com.bigbrightpaints.erp.modules.company.domain.Company;
import com.bigbrightpaints.erp.modules.company.domain.CompanyRepository;
import com.bigbrightpaints.erp.modules.inventory.domain.RawMaterial;
import com.bigbrightpaints.erp.modules.inventory.domain.RawMaterialRepository;
import com.bigbrightpaints.erp.modules.production.domain.ProductionBrand;
import com.bigbrightpaints.erp.modules.production.domain.ProductionBrandRepository;
import com.bigbrightpaints.erp.modules.production.domain.ProductionProduct;
import com.bigbrightpaints.erp.modules.production.domain.ProductionProductRepository;
import com.bigbrightpaints.erp.modules.production.dto.CatalogImportResponse;
import com.bigbrightpaints.erp.modules.production.service.ProductionCatalogService;
import com.bigbrightpaints.erp.test.AbstractIntegrationTest;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.util.AopTestUtils;
import org.springframework.test.util.ReflectionTestUtils;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("Regression: Catalog -> RawMaterial accounting invariants")
class ProductionCatalogRawMaterialInvariantIT extends AbstractIntegrationTest {

    @Autowired private CompanyRepository companyRepository;
    @Autowired private AccountRepository accountRepository;
    @Autowired private ProductionCatalogService productionCatalogService;
    @Autowired private RawMaterialRepository rawMaterialRepository;
    @Autowired private ProductionBrandRepository productionBrandRepository;
    @Autowired private ProductionProductRepository productionProductRepository;

    private Company company;
    private Account inventoryAccount;
    private Account alternateInventoryAccount;
    private String companyCode;

    @BeforeEach
    void setUp() {
        companyCode = "RM-CAT-" + UUID.randomUUID().toString().substring(0, 8).toUpperCase();
        company = dataSeeder.ensureCompany(companyCode, companyCode + " Ltd");
        CompanyContextHolder.setCompanyId(companyCode);
        inventoryAccount = ensureAccount("RM-INV", "Raw Material Inventory", AccountType.ASSET);
        alternateInventoryAccount = ensureAccount("RM-INV-ALT", "Raw Material Inventory Alt", AccountType.ASSET);
        company.setDefaultInventoryAccountId(inventoryAccount.getId());
        companyRepository.save(company);
    }

    @AfterEach
    void clearContext() {
        CompanyContextHolder.clear();
    }

    @Test
    void importCatalog_seedsRawMaterialInventoryAccountAndGstRate() {
        CatalogImportResponse response = productionCatalogService.importCatalog(
                rawMaterialCsv("RM-TIO2-01", "18.00"),
                "RM-CAT-IDEMP-01"
        );

        assertThat(response.errors()).isEmpty();
        RawMaterial material = rawMaterialRepository.findByCompanyAndSku(company, "RM-TIO2-01").orElseThrow();
        assertThat(material.getInventoryAccountId()).isEqualTo(inventoryAccount.getId());
        assertThat(material.getGstRate()).isEqualByComparingTo("18.00");
    }

    @Test
    void importCatalog_repairsRawMaterialWhenInventoryAccountMissing() {
        RawMaterial stale = new RawMaterial();
        stale.setCompany(company);
        stale.setName("Titanium Dioxide");
        stale.setSku("RM-TIO2-STALE");
        stale.setUnitType("KG");
        stale.setCurrentStock(BigDecimal.ZERO);
        stale.setInventoryAccountId(null);
        stale.setGstRate(BigDecimal.ZERO);
        rawMaterialRepository.save(stale);

        CatalogImportResponse response = productionCatalogService.importCatalog(
                rawMaterialCsv("RM-TIO2-STALE", "12.00"),
                "RM-CAT-IDEMP-02"
        );

        assertThat(response.errors()).isEmpty();
        RawMaterial repaired = rawMaterialRepository.findByCompanyAndSku(company, "RM-TIO2-STALE").orElseThrow();
        assertThat(repaired.getInventoryAccountId()).isEqualTo(inventoryAccount.getId());
        assertThat(repaired.getGstRate()).isEqualByComparingTo("12.00");
    }

    @Test
    void importCatalog_acceptsInventoryAccountIdAliasColumn() {
        CatalogImportResponse response = productionCatalogService.importCatalog(
                rawMaterialCsvWithAccount("RM-TIO2-ALIAS", "5.00", alternateInventoryAccount.getId(), "inventory_account_id"),
                "RM-CAT-IDEMP-03"
        );

        assertThat(response.errors()).isEmpty();
        RawMaterial material = rawMaterialRepository.findByCompanyAndSku(company, "RM-TIO2-ALIAS").orElseThrow();
        assertThat(material.getInventoryAccountId()).isEqualTo(alternateInventoryAccount.getId());
        assertThat(material.getGstRate()).isEqualByComparingTo("5.00");
    }

    @Test
    void importCatalog_explicitRawMaterialInventoryAccountRepairsStaleWrongAccount() {
        RawMaterial stale = new RawMaterial();
        stale.setCompany(company);
        stale.setName("Titanium Dioxide");
        stale.setSku("RM-TIO2-WRONG");
        stale.setUnitType("KG");
        stale.setCurrentStock(BigDecimal.ZERO);
        stale.setInventoryAccountId(inventoryAccount.getId());
        stale.setGstRate(BigDecimal.ZERO);
        rawMaterialRepository.save(stale);

        CatalogImportResponse response = productionCatalogService.importCatalog(
                rawMaterialCsvWithAccount("RM-TIO2-WRONG", "18.00", alternateInventoryAccount.getId(), "rm_inventory_account_id"),
                "RM-CAT-IDEMP-04"
        );

        assertThat(response.errors()).isEmpty();
        RawMaterial repaired = rawMaterialRepository.findByCompanyAndSku(company, "RM-TIO2-WRONG").orElseThrow();
        assertThat(repaired.getInventoryAccountId()).isEqualTo(alternateInventoryAccount.getId());
        assertThat(repaired.getGstRate()).isEqualByComparingTo("18.00");
    }

    @Test
    void syncRawMaterial_withoutExplicitAccountMappingDoesNotReapplyStaleMetadataMapping() throws Exception {
        ProductionProduct product = seedRawMaterialProduct(
                "RM-TIO2-MANUAL",
                alternateInventoryAccount.getId(),
                inventoryAccount.getId());
        product.setGstRate(new BigDecimal("12.00"));
        ProductionCatalogService targetService = AopTestUtils.getTargetObject(productionCatalogService);
        ReflectionTestUtils.invokeMethod(
                targetService,
                "syncRawMaterial",
                company,
                product,
                new HashMap<Long, Long>(),
                false);

        RawMaterial replayed = rawMaterialRepository.findByCompanyAndSku(company, "RM-TIO2-MANUAL").orElseThrow();
        assertThat(replayed.getInventoryAccountId()).isEqualTo(inventoryAccount.getId());
        assertThat(replayed.getGstRate()).isEqualByComparingTo("12.00");
    }

    @Test
    void importCatalog_rejectsRawMaterialInventoryAccountOutsideCompanyScope() {
        Company foreignCompany = dataSeeder.ensureCompany(
                "RM-FOREIGN-" + UUID.randomUUID().toString().substring(0, 8).toUpperCase(),
                "Foreign RM Co");
        Account foreignInventoryAccount = ensureAccountFor(
                foreignCompany,
                "RM-INV-FOREIGN",
                "Foreign Raw Material Inventory",
                AccountType.ASSET);

        CatalogImportResponse response = productionCatalogService.importCatalog(
                rawMaterialCsvWithAccount(
                        "RM-TIO2-OUTSIDE",
                        "18.00",
                        foreignInventoryAccount.getId(),
                        "rm_inventory_account_id"),
                "RM-CAT-IDEMP-05"
        );

        assertThat(response.errors()).hasSize(1);
        assertThat(response.errors().getFirst().message()).contains("invalid inventory account id");
        assertThat(rawMaterialRepository.findByCompanyAndSku(company, "RM-TIO2-OUTSIDE")).isEmpty();
    }

    @Test
    void importCatalog_rejectsFinishedGoodAccountOutsideCompanyScope() {
        Account cogsAccount = ensureAccount("FG-COGS", "Finished Good COGS", AccountType.COGS);
        Account revenueAccount = ensureAccount("FG-REV", "Finished Good Revenue", AccountType.REVENUE);
        Account taxAccount = ensureAccount("FG-TAX", "Finished Good Tax", AccountType.LIABILITY);
        Company managedCompany = companyRepository.findById(company.getId()).orElseThrow();
        managedCompany.setDefaultCogsAccountId(cogsAccount.getId());
        managedCompany.setDefaultRevenueAccountId(revenueAccount.getId());
        managedCompany.setDefaultTaxAccountId(taxAccount.getId());
        company = companyRepository.save(managedCompany);

        Company foreignCompany = dataSeeder.ensureCompany(
                "FG-FOREIGN-" + UUID.randomUUID().toString().substring(0, 8).toUpperCase(),
                "Foreign FG Co");
        Account foreignValuationAccount = ensureAccountFor(
                foreignCompany,
                "FG-INV-FOREIGN",
                "Foreign Finished Good Inventory",
                AccountType.ASSET);

        MockMultipartFile importFile = finishedGoodCsvWithAccount(
                "FG-OUTSIDE-01",
                "18.00",
                foreignValuationAccount.getId(),
                "fg_valuation_account_id");
        CatalogImportResponse response = productionCatalogService.importCatalog(importFile, "RM-CAT-IDEMP-06");
        CatalogImportResponse replay = productionCatalogService.importCatalog(importFile, "RM-CAT-IDEMP-06");

        assertThat(response.errors()).hasSize(1);
        assertThat(replay.errors()).hasSize(1);
        assertThat(response.errors().getFirst().message())
                .contains("invalid account id")
                .contains("fgValuationAccountId");
        assertThat(replay.errors().getFirst().message()).isEqualTo(response.errors().getFirst().message());
        assertThat(productionProductRepository.findByCompanyAndSkuCode(company, "FG-OUTSIDE-01")).isEmpty();
    }

    private Account ensureAccount(String code, String name, AccountType type) {
        return ensureAccountFor(company, code, name, type);
    }

    private Account ensureAccountFor(Company targetCompany, String code, String name, AccountType type) {
        return accountRepository.findByCompanyAndCodeIgnoreCase(targetCompany, code)
                .orElseGet(() -> {
                    Account account = new Account();
                    account.setCompany(targetCompany);
                    account.setCode(code);
                    account.setName(name);
                    account.setType(type);
                    return accountRepository.save(account);
                });
    }

    private ProductionProduct seedRawMaterialProduct(String skuCode,
                                                     Long metadataInventoryAccountId,
                                                     Long materialInventoryAccountId) {
        ProductionBrand brand = new ProductionBrand();
        brand.setCompany(company);
        brand.setName("RMBrand-" + UUID.randomUUID().toString().substring(0, 8).toUpperCase());
        brand.setCode("RM-" + UUID.randomUUID().toString().substring(0, 6).toUpperCase());
        ProductionBrand savedBrand = productionBrandRepository.save(brand);

        ProductionProduct product = new ProductionProduct();
        product.setCompany(company);
        product.setBrand(savedBrand);
        product.setProductName("Titanium Dioxide");
        product.setCategory("RAW_MATERIAL");
        product.setUnitOfMeasure("KG");
        product.setSkuCode(skuCode);
        product.setActive(true);
        product.setBasePrice(BigDecimal.ZERO);
        product.setGstRate(new BigDecimal("18.00"));
        product.setMinDiscountPercent(BigDecimal.ZERO);
        product.setMinSellingPrice(BigDecimal.ZERO);
        Map<String, Object> metadata = new HashMap<>();
        metadata.put("inventoryAccountId", metadataInventoryAccountId);
        product.setMetadata(metadata);
        ProductionProduct savedProduct = productionProductRepository.save(product);

        RawMaterial material = new RawMaterial();
        material.setCompany(company);
        material.setName("Titanium Dioxide");
        material.setSku(skuCode);
        material.setUnitType("KG");
        material.setCurrentStock(BigDecimal.ZERO);
        material.setInventoryAccountId(materialInventoryAccountId);
        material.setGstRate(BigDecimal.ZERO);
        rawMaterialRepository.save(material);
        return savedProduct;
    }

    private MockMultipartFile rawMaterialCsv(String skuCode, String gstRate) {
        String csv = String.join("\n",
                "brand,product_name,sku_code,category,unit_of_measure,gst_rate",
                "RMBrand,Titanium Dioxide," + skuCode + ",RAW_MATERIAL,KG," + gstRate
        );
        return new MockMultipartFile(
                "file",
                "raw-material-catalog.csv",
                "text/csv",
                csv.getBytes(StandardCharsets.UTF_8)
        );
    }

    private MockMultipartFile rawMaterialCsvWithAccount(String skuCode,
                                                        String gstRate,
                                                        Long inventoryAccountId,
                                                        String accountColumnName) {
        String csv = String.join("\n",
                "brand,product_name,sku_code,category,unit_of_measure,gst_rate," + accountColumnName,
                "RMBrand,Titanium Dioxide," + skuCode + ",RAW_MATERIAL,KG," + gstRate + "," + inventoryAccountId
        );
        return new MockMultipartFile(
                "file",
                "raw-material-catalog-account.csv",
                "text/csv",
                csv.getBytes(StandardCharsets.UTF_8)
        );
    }

    private MockMultipartFile finishedGoodCsvWithAccount(String skuCode,
                                                         String gstRate,
                                                         Long accountId,
                                                         String accountColumnName) {
        String csv = String.join("\n",
                "brand,product_name,sku_code,category,unit_of_measure,gst_rate," + accountColumnName,
                "FGBrand,Super Gloss " + skuCode + "," + skuCode + ",FINISHED_GOOD,LTR," + gstRate + "," + accountId
        );
        return new MockMultipartFile(
                "file",
                "finished-good-catalog-account.csv",
                "text/csv",
                csv.getBytes(StandardCharsets.UTF_8)
        );
    }
}
