package com.bigbrightpaints.erp.regression;

import com.bigbrightpaints.erp.core.security.CompanyContextHolder;
import com.bigbrightpaints.erp.modules.accounting.domain.Account;
import com.bigbrightpaints.erp.modules.accounting.domain.AccountRepository;
import com.bigbrightpaints.erp.modules.accounting.domain.AccountType;
import com.bigbrightpaints.erp.modules.company.domain.Company;
import com.bigbrightpaints.erp.modules.company.domain.CompanyRepository;
import com.bigbrightpaints.erp.modules.inventory.domain.RawMaterial;
import com.bigbrightpaints.erp.modules.inventory.domain.RawMaterialRepository;
import com.bigbrightpaints.erp.modules.production.dto.CatalogImportResponse;
import com.bigbrightpaints.erp.modules.production.service.ProductionCatalogService;
import com.bigbrightpaints.erp.test.AbstractIntegrationTest;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.mock.web.MockMultipartFile;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("Regression: Catalog -> RawMaterial accounting invariants")
class ProductionCatalogRawMaterialInvariantIT extends AbstractIntegrationTest {

    @Autowired private CompanyRepository companyRepository;
    @Autowired private AccountRepository accountRepository;
    @Autowired private ProductionCatalogService productionCatalogService;
    @Autowired private RawMaterialRepository rawMaterialRepository;

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

    private Account ensureAccount(String code, String name, AccountType type) {
        return accountRepository.findByCompanyAndCodeIgnoreCase(company, code)
                .orElseGet(() -> {
                    Account account = new Account();
                    account.setCompany(company);
                    account.setCode(code);
                    account.setName(name);
                    account.setType(type);
                    return accountRepository.save(account);
                });
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
}
