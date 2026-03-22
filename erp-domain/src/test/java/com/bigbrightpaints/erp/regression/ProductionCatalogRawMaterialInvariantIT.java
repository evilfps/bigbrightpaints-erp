package com.bigbrightpaints.erp.regression;

import com.bigbrightpaints.erp.core.security.CompanyContextHolder;
import com.bigbrightpaints.erp.modules.accounting.domain.Account;
import com.bigbrightpaints.erp.modules.accounting.domain.AccountRepository;
import com.bigbrightpaints.erp.modules.accounting.domain.AccountType;
import com.bigbrightpaints.erp.modules.company.domain.Company;
import com.bigbrightpaints.erp.modules.company.domain.CompanyRepository;
import com.bigbrightpaints.erp.modules.inventory.domain.FinishedGood;
import com.bigbrightpaints.erp.modules.inventory.domain.FinishedGoodRepository;
import com.bigbrightpaints.erp.modules.inventory.domain.RawMaterial;
import com.bigbrightpaints.erp.modules.inventory.domain.RawMaterialRepository;
import com.bigbrightpaints.erp.modules.production.domain.ProductionBrand;
import com.bigbrightpaints.erp.modules.production.domain.ProductionBrandRepository;
import com.bigbrightpaints.erp.modules.production.domain.CatalogImport;
import com.bigbrightpaints.erp.modules.production.domain.CatalogImportRepository;
import com.bigbrightpaints.erp.modules.production.domain.ProductionProduct;
import com.bigbrightpaints.erp.modules.production.domain.ProductionProductRepository;
import com.bigbrightpaints.erp.modules.production.dto.CatalogImportResponse;
import com.bigbrightpaints.erp.modules.production.dto.ProductCreateRequest;
import com.bigbrightpaints.erp.modules.production.dto.ProductUpdateRequest;
import com.bigbrightpaints.erp.modules.production.dto.ProductionProductDto;
import com.bigbrightpaints.erp.modules.production.service.ProductionCatalogService;
import com.bigbrightpaints.erp.test.AbstractIntegrationTest;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.util.AopTestUtils;
import org.springframework.test.util.ReflectionTestUtils;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("Regression: Catalog -> RawMaterial accounting invariants")
@Tag("critical")
class ProductionCatalogRawMaterialInvariantIT extends AbstractIntegrationTest {

    private static final String PASSWORD = "changeme";

    @Autowired private TestRestTemplate rest;
    @Autowired private CompanyRepository companyRepository;
    @Autowired private AccountRepository accountRepository;
    @Autowired private ProductionCatalogService productionCatalogService;
    @Autowired private FinishedGoodRepository finishedGoodRepository;
    @Autowired private RawMaterialRepository rawMaterialRepository;
    @Autowired private ProductionBrandRepository productionBrandRepository;
    @Autowired private ProductionProductRepository productionProductRepository;
    @Autowired private CatalogImportRepository catalogImportRepository;

    private Company company;
    private Account inventoryAccount;
    private Account alternateInventoryAccount;
    private String companyCode;
    private HttpHeaders headers;
    private String adminEmail;

    @BeforeEach
    void setUp() {
        companyCode = "RM-CAT-" + UUID.randomUUID().toString().substring(0, 8).toUpperCase();
        company = dataSeeder.ensureCompany(companyCode, companyCode + " Ltd");
        CompanyContextHolder.setCompanyId(companyCode);
        inventoryAccount = ensureAccount("RM-INV", "Raw Material Inventory", AccountType.ASSET);
        alternateInventoryAccount = ensureAccount("RM-INV-ALT", "Raw Material Inventory Alt", AccountType.ASSET);
        company.setDefaultInventoryAccountId(inventoryAccount.getId());
        companyRepository.save(company);

        adminEmail = "catalog-rm-" + companyCode.toLowerCase() + "@bbp.com";
        dataSeeder.ensureUser(adminEmail, PASSWORD, "Catalog Raw Material Admin", companyCode, List.of("ROLE_ADMIN"));
        headers = authHeaders();
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
    void importCatalog_repairsDriftedRawMaterialCostingAlias_onFreshImportAndReplay() {
        RawMaterial drifted = new RawMaterial();
        drifted.setCompany(company);
        drifted.setName("Titanium Dioxide");
        drifted.setSku("RM-TIO2-COST-DRIFT");
        drifted.setUnitType("KG");
        drifted.setCurrentStock(BigDecimal.ZERO);
        drifted.setInventoryAccountId(inventoryAccount.getId());
        drifted.setGstRate(BigDecimal.ZERO);
        drifted.setCostingMethod(" weighted_average ");
        rawMaterialRepository.save(drifted);

        MockMultipartFile importFile = rawMaterialCsv("RM-TIO2-COST-DRIFT", "18.00");
        CatalogImportResponse firstImport = productionCatalogService.importCatalog(importFile, "RM-CAT-IDEMP-17");
        assertThat(firstImport.errors()).isEmpty();

        RawMaterial afterFirstImport = rawMaterialRepository.findByCompanyAndSku(company, "RM-TIO2-COST-DRIFT").orElseThrow();
        assertThat(afterFirstImport.getCostingMethod()).isEqualTo("WAC");

        afterFirstImport.setCostingMethod("weighted-average");
        rawMaterialRepository.save(afterFirstImport);

        CatalogImportResponse replayImport = productionCatalogService.importCatalog(importFile, "RM-CAT-IDEMP-18");
        assertThat(replayImport.errors()).isEmpty();
        RawMaterial afterReplayImport = rawMaterialRepository.findByCompanyAndSku(company, "RM-TIO2-COST-DRIFT").orElseThrow();
        assertThat(afterReplayImport.getCostingMethod()).isEqualTo("WAC");
    }

    @Test
    void importCatalog_preservesUnsupportedRawMaterialCostingMethod_onSync() {
        RawMaterial legacy = new RawMaterial();
        legacy.setCompany(company);
        legacy.setName("Titanium Dioxide");
        legacy.setSku("RM-TIO2-COST-LEGACY");
        legacy.setUnitType("KG");
        legacy.setCurrentStock(BigDecimal.ZERO);
        legacy.setInventoryAccountId(inventoryAccount.getId());
        legacy.setGstRate(BigDecimal.ZERO);
        legacy.setCostingMethod("CUSTOM_METHOD");
        rawMaterialRepository.save(legacy);

        CatalogImportResponse response = productionCatalogService.importCatalog(
                rawMaterialCsv("RM-TIO2-COST-LEGACY", "12.00"),
                "RM-CAT-IDEMP-19"
        );

        assertThat(response.errors()).isEmpty();
        RawMaterial synced = rawMaterialRepository.findByCompanyAndSku(company, "RM-TIO2-COST-LEGACY").orElseThrow();
        assertThat(synced.getCostingMethod()).isEqualTo("CUSTOM_METHOD");
        assertThat(synced.getGstRate()).isEqualByComparingTo("12.00");
    }

    @Test
    void importCatalog_preservesUnsupportedFinishedGoodCostingMethod_onSync() {
        configureFinishedGoodDefaultAccounts();

        FinishedGood legacy = new FinishedGood();
        legacy.setCompany(company);
        legacy.setProductCode("FG-COST-LEGACY-IMPORT-01");
        legacy.setName("Legacy Unsupported FG");
        legacy.setUnit("LTR");
        legacy.setCurrentStock(BigDecimal.ZERO);
        legacy.setReservedStock(BigDecimal.ZERO);
        legacy.setValuationAccountId(inventoryAccount.getId());
        legacy.setCogsAccountId(company.getDefaultCogsAccountId());
        legacy.setRevenueAccountId(company.getDefaultRevenueAccountId());
        legacy.setTaxAccountId(company.getDefaultTaxAccountId());
        legacy.setCostingMethod("CUSTOM_METHOD");
        finishedGoodRepository.save(legacy);

        CatalogImportResponse response = productionCatalogService.importCatalog(
                finishedGoodCsvWithAccount(
                        "FG-COST-LEGACY-IMPORT-01",
                        "18.00",
                        inventoryAccount.getId(),
                        "fg_valuation_account_id"),
                "RM-CAT-IDEMP-20"
        );

        assertThat(response.errors()).isEmpty();
        FinishedGood synced = finishedGoodRepository
                .findByCompanyAndProductCode(company, "FG-COST-LEGACY-IMPORT-01")
                .orElseThrow();
        assertThat(synced.getCostingMethod()).isEqualTo("CUSTOM_METHOD");
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
        configureFinishedGoodDefaultAccounts();

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
        CatalogImport firstImportRecord = catalogImportRepository
                .findByCompanyAndIdempotencyKey(company, "RM-CAT-IDEMP-06")
                .orElseThrow();
        CatalogImportResponse replay = productionCatalogService.importCatalog(importFile, "RM-CAT-IDEMP-06");
        CatalogImport replayImportRecord = catalogImportRepository
                .findByCompanyAndIdempotencyKey(company, "RM-CAT-IDEMP-06")
                .orElseThrow();

        assertThat(response.errors()).hasSize(1);
        assertThat(replay.errors()).hasSize(1);
        assertThat(response.errors().getFirst().message())
                .contains("invalid account id")
                .contains("fgValuationAccountId");
        assertThat(replay.errors().getFirst().message()).isEqualTo(response.errors().getFirst().message());
        assertThat(replayImportRecord.getId()).isEqualTo(firstImportRecord.getId());
        assertThat(productionProductRepository.findByCompanyAndSkuCode(company, "FG-OUTSIDE-01")).isEmpty();
    }

    @Test
    void importCatalog_repairsDriftedFinishedGoodCostingAlias_onFreshImportAndReplay() {
        configureFinishedGoodDefaultAccounts();

        FinishedGood drifted = new FinishedGood();
        drifted.setCompany(company);
        drifted.setProductCode("FG-COST-DRIFT-01");
        drifted.setName("Legacy Drifted FG");
        drifted.setUnit("LTR");
        drifted.setCurrentStock(BigDecimal.ZERO);
        drifted.setReservedStock(BigDecimal.ZERO);
        drifted.setValuationAccountId(inventoryAccount.getId());
        drifted.setCogsAccountId(company.getDefaultCogsAccountId());
        drifted.setRevenueAccountId(company.getDefaultRevenueAccountId());
        drifted.setTaxAccountId(company.getDefaultTaxAccountId());
        drifted.setCostingMethod(" weighted_average ");
        finishedGoodRepository.save(drifted);

        MockMultipartFile importFile = finishedGoodCsvWithAccount(
                "FG-COST-DRIFT-01",
                "18.00",
                inventoryAccount.getId(),
                "fg_valuation_account_id");

        CatalogImportResponse firstImport = productionCatalogService.importCatalog(importFile, "RM-CAT-IDEMP-07");
        assertThat(firstImport.errors()).isEmpty();
        FinishedGood afterFirstImport = finishedGoodRepository
                .findByCompanyAndProductCode(company, "FG-COST-DRIFT-01")
                .orElseThrow();
        assertThat(afterFirstImport.getCostingMethod()).isEqualTo("WAC");

        afterFirstImport.setCostingMethod("weighted-average");
        finishedGoodRepository.save(afterFirstImport);

        CatalogImportResponse replayImport = productionCatalogService.importCatalog(importFile, "RM-CAT-IDEMP-08");
        assertThat(replayImport.errors()).isEmpty();
        FinishedGood afterReplayImport = finishedGoodRepository
                .findByCompanyAndProductCode(company, "FG-COST-DRIFT-01")
                .orElseThrow();
        assertThat(afterReplayImport.getCostingMethod()).isEqualTo("WAC");
    }

    @Test
    void createAndUpdateProduct_canonicalizeDriftedFinishedGoodCostingAlias() {
        configureFinishedGoodDefaultAccounts();

        String sku = "FG-COST-SYNC-01";
        FinishedGood drifted = new FinishedGood();
        drifted.setCompany(company);
        drifted.setProductCode(sku);
        drifted.setName("Legacy Drifted Create/Update FG");
        drifted.setUnit("LTR");
        drifted.setCurrentStock(BigDecimal.ZERO);
        drifted.setReservedStock(BigDecimal.ZERO);
        drifted.setValuationAccountId(inventoryAccount.getId());
        drifted.setCogsAccountId(company.getDefaultCogsAccountId());
        drifted.setRevenueAccountId(company.getDefaultRevenueAccountId());
        drifted.setTaxAccountId(company.getDefaultTaxAccountId());
        drifted.setCostingMethod(" weighted_average ");
        finishedGoodRepository.save(drifted);

        Map<String, Object> metadata = new HashMap<>();
        metadata.put("fgValuationAccountId", inventoryAccount.getId());
        metadata.put("fgCogsAccountId", company.getDefaultCogsAccountId());
        metadata.put("fgRevenueAccountId", company.getDefaultRevenueAccountId());
        metadata.put("fgTaxAccountId", company.getDefaultTaxAccountId());

        ProductionProductDto created = productionCatalogService.createProduct(new ProductCreateRequest(
                null,
                "FG Sync Brand",
                null,
                "FG Sync Product",
                "FINISHED_GOOD",
                "FINISHED_GOOD",
                null,
                null,
                "LTR",
                null,
                sku,
                BigDecimal.ZERO,
                new BigDecimal("18.00"),
                BigDecimal.ZERO,
                BigDecimal.ZERO,
                metadata
        ));

        FinishedGood afterCreate = finishedGoodRepository
                .findByCompanyAndProductCode(company, sku)
                .orElseThrow();
        assertThat(afterCreate.getCostingMethod()).isEqualTo("WAC");

        afterCreate.setCostingMethod("weighted-average");
        finishedGoodRepository.save(afterCreate);

        productionCatalogService.updateProduct(created.id(), new ProductUpdateRequest(
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null
        ));

        FinishedGood afterUpdate = finishedGoodRepository
                .findByCompanyAndProductCode(company, sku)
                .orElseThrow();
        assertThat(afterUpdate.getCostingMethod()).isEqualTo("WAC");
    }

    @Test
    void createAndUpdateProduct_preserveUnsupportedCostingMethods() {
        configureFinishedGoodDefaultAccounts();

        String fgSku = "FG-COST-LEGACY-SYNC-01";
        FinishedGood legacyFg = new FinishedGood();
        legacyFg.setCompany(company);
        legacyFg.setProductCode(fgSku);
        legacyFg.setName("Legacy Unsupported Sync FG");
        legacyFg.setUnit("LTR");
        legacyFg.setCurrentStock(BigDecimal.ZERO);
        legacyFg.setReservedStock(BigDecimal.ZERO);
        legacyFg.setValuationAccountId(inventoryAccount.getId());
        legacyFg.setCogsAccountId(company.getDefaultCogsAccountId());
        legacyFg.setRevenueAccountId(company.getDefaultRevenueAccountId());
        legacyFg.setTaxAccountId(company.getDefaultTaxAccountId());
        legacyFg.setCostingMethod("CUSTOM_METHOD");
        finishedGoodRepository.save(legacyFg);

        Map<String, Object> fgMetadata = new HashMap<>();
        fgMetadata.put("fgValuationAccountId", inventoryAccount.getId());
        fgMetadata.put("fgCogsAccountId", company.getDefaultCogsAccountId());
        fgMetadata.put("fgRevenueAccountId", company.getDefaultRevenueAccountId());
        fgMetadata.put("fgTaxAccountId", company.getDefaultTaxAccountId());

        ProductionProductDto createdFinishedGood = productionCatalogService.createProduct(new ProductCreateRequest(
                null,
                "FG Unsupported Brand",
                null,
                "FG Unsupported Product",
                "FINISHED_GOOD",
                "FINISHED_GOOD",
                null,
                null,
                "LTR",
                null,
                fgSku,
                BigDecimal.ZERO,
                new BigDecimal("18.00"),
                BigDecimal.ZERO,
                BigDecimal.ZERO,
                fgMetadata
        ));

        FinishedGood afterFgCreate = finishedGoodRepository
                .findByCompanyAndProductCode(company, fgSku)
                .orElseThrow();
        assertThat(afterFgCreate.getCostingMethod()).isEqualTo("CUSTOM_METHOD");

        productionCatalogService.updateProduct(createdFinishedGood.id(), new ProductUpdateRequest(
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null
        ));

        FinishedGood afterFgUpdate = finishedGoodRepository
                .findByCompanyAndProductCode(company, fgSku)
                .orElseThrow();
        assertThat(afterFgUpdate.getCostingMethod()).isEqualTo("CUSTOM_METHOD");

        String rmSku = "RM-COST-LEGACY-SYNC-01";
        RawMaterial legacyRawMaterial = new RawMaterial();
        legacyRawMaterial.setCompany(company);
        legacyRawMaterial.setName("Legacy Unsupported Sync RM");
        legacyRawMaterial.setSku(rmSku);
        legacyRawMaterial.setUnitType("KG");
        legacyRawMaterial.setCurrentStock(BigDecimal.ZERO);
        legacyRawMaterial.setInventoryAccountId(inventoryAccount.getId());
        legacyRawMaterial.setGstRate(BigDecimal.ZERO);
        legacyRawMaterial.setCostingMethod("CUSTOM_METHOD");
        rawMaterialRepository.save(legacyRawMaterial);

        Map<String, Object> rmMetadata = new HashMap<>();
        rmMetadata.put("inventoryAccountId", inventoryAccount.getId());

        ProductionProductDto createdRawMaterial = productionCatalogService.createProduct(new ProductCreateRequest(
                null,
                "RM Unsupported Brand",
                null,
                "RM Unsupported Product",
                "RAW_MATERIAL",
                "RAW_MATERIAL",
                null,
                null,
                "KG",
                null,
                rmSku,
                BigDecimal.ZERO,
                new BigDecimal("12.00"),
                BigDecimal.ZERO,
                BigDecimal.ZERO,
                rmMetadata
        ));

        RawMaterial afterRmCreate = rawMaterialRepository.findByCompanyAndSku(company, rmSku).orElseThrow();
        assertThat(afterRmCreate.getCostingMethod()).isEqualTo("CUSTOM_METHOD");

        productionCatalogService.updateProduct(createdRawMaterial.id(), new ProductUpdateRequest(
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null
        ));

        RawMaterial afterRmUpdate = rawMaterialRepository.findByCompanyAndSku(company, rmSku).orElseThrow();
        assertThat(afterRmUpdate.getCostingMethod()).isEqualTo("CUSTOM_METHOD");
    }

    @Test
    void canonicalRawMaterialCreate_seedsInventoryTruthAndAccountLinkage() {
        ProductionBrand brand = saveBrand("RM Ready " + UUID.randomUUID().toString().substring(0, 8).toUpperCase(), true);

        ResponseEntity<Map> response = rest.exchange(
                "/api/v1/catalog/products",
                HttpMethod.POST,
                new HttpEntity<>(canonicalRawMaterialPayload(brand.getId()), headers),
                Map.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        Map<String, Object> responseData = data(response);
        @SuppressWarnings("unchecked")
        Map<String, Object> member = ((List<Map<String, Object>>) responseData.get("members")).getFirst();
        String sku = String.valueOf(member.get("sku"));

        RawMaterial material = rawMaterialRepository.findByCompanyAndSku(company, sku).orElseThrow();
        assertThat(material.getName()).isEqualTo("Titanium Dioxide NATURAL 25KG");
        assertThat(material.getUnitType()).isEqualTo("KG");
        assertThat(material.getCurrentStock()).isEqualByComparingTo(BigDecimal.ZERO);
        assertThat(material.getInventoryAccountId()).isEqualTo(alternateInventoryAccount.getId());
        assertThat(material.getGstRate()).isEqualByComparingTo("18.00");
        assertThat(finishedGoodRepository.findByCompanyAndProductCode(company, sku)).isEmpty();
    }

    private Account ensureAccount(String code, String name, AccountType type) {
        return ensureAccountFor(company, code, name, type);
    }

    private ProductionBrand saveBrand(String name, boolean active) {
        ProductionBrand brand = new ProductionBrand();
        brand.setCompany(company);
        brand.setName(name);
        brand.setCode(("RM" + UUID.randomUUID().toString().replace("-", "")).substring(0, 10));
        brand.setActive(active);
        return productionBrandRepository.save(brand);
    }

    private HttpHeaders authHeaders() {
        Map<String, Object> loginPayload = Map.of(
                "email", adminEmail,
                "password", PASSWORD,
                "companyCode", companyCode
        );
        ResponseEntity<Map> loginResponse = rest.postForEntity("/api/v1/auth/login", loginPayload, Map.class);
        assertThat(loginResponse.getStatusCode()).isEqualTo(HttpStatus.OK);
        String token = String.valueOf(loginResponse.getBody().get("accessToken"));

        HttpHeaders httpHeaders = new HttpHeaders();
        httpHeaders.setBearerAuth(token);
        httpHeaders.set("X-Company-Code", companyCode);
        return httpHeaders;
    }

    private Map<String, Object> canonicalRawMaterialPayload(Long brandId) {
        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put("inventoryAccountId", alternateInventoryAccount.getId());

        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("brandId", brandId);
        payload.put("baseProductName", "Titanium Dioxide");
        payload.put("category", "RAW_MATERIAL");
        payload.put("itemClass", "RAW_MATERIAL");
        payload.put("unitOfMeasure", "KG");
        payload.put("hsnCode", "320611");
        payload.put("gstRate", new BigDecimal("18.00"));
        payload.put("basePrice", new BigDecimal("500.00"));
        payload.put("minDiscountPercent", BigDecimal.ZERO);
        payload.put("minSellingPrice", new BigDecimal("500.00"));
        payload.put("colors", List.of("NATURAL"));
        payload.put("sizes", List.of("25KG"));
        payload.put("metadata", metadata);
        return payload;
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> data(ResponseEntity<Map> response) {
        assertThat(response.getBody()).isNotNull();
        return (Map<String, Object>) response.getBody().get("data");
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

    private void configureFinishedGoodDefaultAccounts() {
        Account cogsAccount = ensureAccount("FG-COGS", "Finished Good COGS", AccountType.COGS);
        Account revenueAccount = ensureAccount("FG-REV", "Finished Good Revenue", AccountType.REVENUE);
        Account taxAccount = ensureAccount("FG-TAX", "Finished Good Tax", AccountType.LIABILITY);
        Company managedCompany = companyRepository.findById(company.getId()).orElseThrow();
        managedCompany.setDefaultCogsAccountId(cogsAccount.getId());
        managedCompany.setDefaultRevenueAccountId(revenueAccount.getId());
        managedCompany.setDefaultTaxAccountId(taxAccount.getId());
        company = companyRepository.save(managedCompany);
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
