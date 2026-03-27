package com.bigbrightpaints.erp.codered;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.mock.web.MockMultipartFile;

import com.bigbrightpaints.erp.core.exception.ApplicationException;
import com.bigbrightpaints.erp.core.exception.ErrorCode;
import com.bigbrightpaints.erp.core.security.CompanyContextHolder;
import com.bigbrightpaints.erp.modules.accounting.domain.Account;
import com.bigbrightpaints.erp.modules.accounting.domain.AccountRepository;
import com.bigbrightpaints.erp.modules.accounting.domain.AccountType;
import com.bigbrightpaints.erp.modules.company.domain.Company;
import com.bigbrightpaints.erp.modules.company.domain.CompanyRepository;
import com.bigbrightpaints.erp.modules.production.domain.CatalogImport;
import com.bigbrightpaints.erp.modules.production.domain.CatalogImportRepository;
import com.bigbrightpaints.erp.modules.production.domain.ProductionBrand;
import com.bigbrightpaints.erp.modules.production.domain.ProductionBrandRepository;
import com.bigbrightpaints.erp.modules.production.domain.ProductionProduct;
import com.bigbrightpaints.erp.modules.production.domain.ProductionProductRepository;
import com.bigbrightpaints.erp.modules.production.dto.CatalogImportResponse;
import com.bigbrightpaints.erp.modules.production.service.ProductionCatalogService;
import com.bigbrightpaints.erp.test.AbstractIntegrationTest;

@Tag("critical")
@Tag("concurrency")
class CR_CatalogImportIdempotencyIT extends AbstractIntegrationTest {

  @Autowired private CompanyRepository companyRepository;
  @Autowired private AccountRepository accountRepository;
  @Autowired private ProductionCatalogService productionCatalogService;
  @Autowired private CatalogImportRepository catalogImportRepository;
  @Autowired private ProductionProductRepository productRepository;
  @Autowired private ProductionBrandRepository brandRepository;

  @AfterEach
  void clearCompanyContext() {
    CompanyContextHolder.clear();
  }

  @Test
  void catalogImport_isIdempotentForSameKeyAndFile() {
    Company company = ensureCompany("CR-CAT-IDEMP-" + shortId());
    ensureDefaultAccounts(company);

    CompanyContextHolder.setCompanyCode(company.getCode());
    MockMultipartFile file = csvFile("Safari", "Emulsion White");
    CatalogImportResponse first = productionCatalogService.importCatalog(file, "CAT-IDEMP-001");
    CatalogImportResponse second = productionCatalogService.importCatalog(file, "CAT-IDEMP-001");

    assertThat(second).isEqualTo(first);

    CatalogImport record =
        catalogImportRepository
            .findByCompanyAndIdempotencyKey(company, "CAT-IDEMP-001")
            .orElseThrow();
    assertThat(record.getRowsProcessed()).isEqualTo(1);

    List<ProductionProduct> products =
        productRepository.findByCompanyOrderByProductNameAsc(company);
    List<ProductionBrand> brands = brandRepository.findByCompanyOrderByNameAsc(company);
    assertThat(products).hasSize(1);
    assertThat(brands).hasSize(1);
  }

  @Test
  void catalogImport_mismatchFailsClosed() {
    Company company = ensureCompany("CR-CAT-IDEMP-" + shortId());
    ensureDefaultAccounts(company);

    CompanyContextHolder.setCompanyCode(company.getCode());
    productionCatalogService.importCatalog(csvFile("Safari", "Emulsion White"), "CAT-IDEMP-002");

    assertThatThrownBy(
            () ->
                productionCatalogService.importCatalog(
                    csvFile("Safari", "Emulsion Blue"), "CAT-IDEMP-002"))
        .isInstanceOf(ApplicationException.class)
        .hasMessageContaining("Idempotency key already used")
        .satisfies(
            ex ->
                assertThat(((ApplicationException) ex).getErrorCode())
                    .isEqualTo(ErrorCode.CONCURRENCY_CONFLICT));
  }

  private Company ensureCompany(String code) {
    return companyRepository
        .findByCodeIgnoreCase(code)
        .orElseGet(
            () -> {
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

  private MockMultipartFile csvFile(String brand, String productName) {
    String csv =
        String.join(
            "\n",
            "brand,product_name,category,default_colour,size,unit_of_measure,base_price,gst_rate,min_discount_percent,min_selling_price",
            brand + "," + productName + ",EMULSION,WHITE,1L,L,100.00,18,5,90.00");
    return new MockMultipartFile(
        "file", "catalog.csv", "text/csv", csv.getBytes(StandardCharsets.UTF_8));
  }

  private static String shortId() {
    return UUID.randomUUID().toString().substring(0, 8);
  }
}
