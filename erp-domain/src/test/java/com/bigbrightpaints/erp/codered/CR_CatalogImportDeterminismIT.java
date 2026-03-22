package com.bigbrightpaints.erp.codered;

import com.bigbrightpaints.erp.core.security.CompanyContextHolder;
import com.bigbrightpaints.erp.modules.accounting.domain.Account;
import com.bigbrightpaints.erp.modules.accounting.domain.AccountRepository;
import com.bigbrightpaints.erp.modules.accounting.domain.AccountType;
import com.bigbrightpaints.erp.modules.company.domain.Company;
import com.bigbrightpaints.erp.modules.company.domain.CompanyRepository;
import com.bigbrightpaints.erp.modules.production.domain.ProductionBrand;
import com.bigbrightpaints.erp.modules.production.domain.ProductionBrandRepository;
import com.bigbrightpaints.erp.modules.production.domain.ProductionProduct;
import com.bigbrightpaints.erp.modules.production.domain.ProductionProductRepository;
import com.bigbrightpaints.erp.modules.production.dto.CatalogImportResponse;
import com.bigbrightpaints.erp.modules.production.dto.ProductCreateRequest;
import com.bigbrightpaints.erp.modules.production.service.ProductionCatalogService;
import com.bigbrightpaints.erp.test.AbstractIntegrationTest;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.mock.web.MockMultipartFile;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
@Tag("critical")

class CR_CatalogImportDeterminismIT extends AbstractIntegrationTest {

    @Autowired private CompanyRepository companyRepository;
    @Autowired private AccountRepository accountRepository;
    @Autowired private ProductionCatalogService productionCatalogService;
    @Autowired private ProductionBrandRepository brandRepository;
    @Autowired private ProductionProductRepository productRepository;

    @AfterEach
    void clearCompanyContext() {
        CompanyContextHolder.clear();
    }

    @Test
    void import_reusesExistingProductWhenSkuMissing() {
        String companyCode = "CR-CAT-IMP-" + shortId();
        Company company = ensureCompany(companyCode);
        ensureDefaultAccounts(company);

        CompanyContextHolder.setCompanyId(companyCode);
        CatalogImportResponse first = productionCatalogService.importCatalog(csvFile());
        List<ProductionProduct> productsAfterFirst = productRepository.findByCompanyOrderByProductNameAsc(company);
        assertThat(productsAfterFirst).hasSize(1);
        String skuAfterFirst = productsAfterFirst.get(0).getSkuCode();

        CatalogImportResponse second = productionCatalogService.importCatalog(csvFile());
        List<ProductionProduct> productsAfterSecond = productRepository.findByCompanyOrderByProductNameAsc(company);
        List<ProductionBrand> brandsAfterSecond = brandRepository.findByCompanyOrderByNameAsc(company);
        CompanyContextHolder.clear();

        assertThat(second.errors()).isEmpty();
        assertThat(productsAfterSecond).hasSize(1);
        assertThat(brandsAfterSecond).hasSize(1);
        assertThat(productsAfterSecond.get(0).getSkuCode()).isEqualTo(skuAfterFirst);
        assertThat(productsAfterSecond.get(0).getId()).isEqualTo(productsAfterFirst.get(0).getId());
        assertThat(first.rowsProcessed()).isEqualTo(1);
    }

    @Test
    void createProduct_rejectsCrossTenantBrandId() {
        Company companyA = ensureCompany("CR-CAT-A-" + shortId());
        Company companyB = ensureCompany("CR-CAT-B-" + shortId());

        ProductionBrand brand = new ProductionBrand();
        brand.setCompany(companyA);
        brand.setName("Tenant A Brand");
        brand.setCode("TENANTA");
        brand = brandRepository.save(brand);

        CompanyContextHolder.setCompanyId(companyB.getCode());
        ProductCreateRequest request = new ProductCreateRequest(
                brand.getId(),
                null,
                null,
                "Cross-Tenant Raw",
                "RAW_MATERIAL",
                "RAW_MATERIAL",
                null,
                null,
                "KG",
                null,
                null,
                null,
                null,
                null,
                null,
                null
        );

        assertThatThrownBy(() -> productionCatalogService.createProduct(request))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Production brand not found");
        CompanyContextHolder.clear();
    }

    private Company ensureCompany(String code) {
        return companyRepository.findByCodeIgnoreCase(code)
                .orElseGet(() -> {
                    Company company = new Company();
                    company.setCode(code);
                    company.setName("CR Catalog " + code);
                    company.setTimezone("UTC");
                    return companyRepository.save(company);
                });
    }

    private void ensureDefaultAccounts(Company company) {
        Account inventory = ensureAccount(company, "CAT-INV", "Catalog Inventory", AccountType.ASSET);
        Account cogs = ensureAccount(company, "CAT-COGS", "Catalog COGS", AccountType.COGS);
        Account revenue = ensureAccount(company, "CAT-REV", "Catalog Revenue", AccountType.REVENUE);
        Account discount = ensureAccount(company, "CAT-DISC", "Catalog Discount", AccountType.EXPENSE);
        Account tax = ensureAccount(company, "CAT-TAX", "Catalog Tax", AccountType.LIABILITY);

        company.setDefaultInventoryAccountId(inventory.getId());
        company.setDefaultCogsAccountId(cogs.getId());
        company.setDefaultRevenueAccountId(revenue.getId());
        company.setDefaultDiscountAccountId(discount.getId());
        company.setDefaultTaxAccountId(tax.getId());
        companyRepository.save(company);
    }

    private Account ensureAccount(Company company, String code, String name, AccountType type) {
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

    private MockMultipartFile csvFile() {
        String csv = String.join("\n",
                "brand,product_name,category,default_colour,size,unit_of_measure,base_price,gst_rate,min_discount_percent,min_selling_price",
                "Safari,Emulsion White,EMULSION,WHITE,1L,L,100.00,18,5,90.00"
        );
        return new MockMultipartFile(
                "file",
                "catalog.csv",
                "text/csv",
                csv.getBytes(StandardCharsets.UTF_8)
        );
    }

    private static String shortId() {
        return UUID.randomUUID().toString().substring(0, 8);
    }
}
