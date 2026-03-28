package com.bigbrightpaints.erp.regression;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import com.bigbrightpaints.erp.core.security.CompanyContextHolder;
import com.bigbrightpaints.erp.modules.accounting.domain.Account;
import com.bigbrightpaints.erp.modules.accounting.domain.AccountRepository;
import com.bigbrightpaints.erp.modules.accounting.domain.AccountType;
import com.bigbrightpaints.erp.modules.company.domain.Company;
import com.bigbrightpaints.erp.modules.company.domain.CompanyRepository;
import com.bigbrightpaints.erp.modules.production.dto.CatalogItemCreateCommand;
import com.bigbrightpaints.erp.modules.production.service.ProductionCatalogService;
import com.bigbrightpaints.erp.test.AbstractIntegrationTest;

@DisplayName("Regression: Catalog create fails closed without discount default")
class ProductionCatalogDiscountDefaultRegressionIT extends AbstractIntegrationTest {

  private static final String COMPANY_CODE = "LF-014";

  @Autowired private CompanyRepository companyRepository;
  @Autowired private AccountRepository accountRepository;
  @Autowired private ProductionCatalogService productionCatalogService;

  private Company company;
  private Account inventoryAccount;
  private Account cogsAccount;
  private Account revenueAccount;
  private Account taxAccount;

  @BeforeEach
  void setUp() {
    company = dataSeeder.ensureCompany(COMPANY_CODE, COMPANY_CODE + " Ltd");
    CompanyContextHolder.setCompanyCode(COMPANY_CODE);

    inventoryAccount = ensureAccount("INV", "Inventory", AccountType.ASSET);
    cogsAccount = ensureAccount("COGS", "COGS", AccountType.COGS);
    revenueAccount = ensureAccount("REV", "Revenue", AccountType.REVENUE);
    taxAccount = ensureAccount("TAX", "Tax", AccountType.LIABILITY);

    company.setDefaultInventoryAccountId(inventoryAccount.getId());
    company.setDefaultCogsAccountId(cogsAccount.getId());
    company.setDefaultRevenueAccountId(revenueAccount.getId());
    company.setDefaultTaxAccountId(taxAccount.getId());
    company.setDefaultDiscountAccountId(null);
    companyRepository.save(company);
  }

  @AfterEach
  void clearContext() {
    CompanyContextHolder.clear();
  }

  @Test
  void createProductRejectsMissingDiscountDefault() {
    CatalogItemCreateCommand request =
        new CatalogItemCreateCommand(
            null,
            "LF-014 Brand",
            null,
            "LF-014 Product",
            "FINISHED_GOOD",
            "FINISHED_GOOD",
            "WHITE",
            "1L",
            "UNIT",
            null,
            null,
            new BigDecimal("100.00"),
            new BigDecimal("18.00"),
            null,
            null,
            null);

    assertThatThrownBy(() -> productionCatalogService.createCatalogItem(request))
        .isInstanceOf(com.bigbrightpaints.erp.core.exception.ApplicationException.class)
        .hasMessageContaining("Default fgDiscountAccountId is not configured")
        .hasMessageContaining(COMPANY_CODE);
  }

  @Test
  void createProductRejectsFinishedGoodAccountOutsideCompanyScope() {
    Company foreignCompany =
        dataSeeder.ensureCompany(
            "FG-SCOPE-" + UUID.randomUUID().toString().substring(0, 8).toUpperCase(),
            "FG Scope Foreign");
    Account foreignInventoryAccount =
        ensureAccountFor(
            foreignCompany, "FG-INV-FOREIGN", "Foreign FG Inventory", AccountType.ASSET);

    CatalogItemCreateCommand request =
        new CatalogItemCreateCommand(
            null,
            "LF-014 Brand",
            null,
            "LF-014 Foreign Account Product",
            "FINISHED_GOOD",
            "FINISHED_GOOD",
            "WHITE",
            "1L",
            "UNIT",
            null,
            null,
            new BigDecimal("100.00"),
            new BigDecimal("18.00"),
            null,
            null,
            Map.of("fgValuationAccountId", foreignInventoryAccount.getId()));

    assertThatThrownBy(() -> productionCatalogService.createCatalogItem(request))
        .isInstanceOf(com.bigbrightpaints.erp.core.exception.ApplicationException.class)
        .hasMessageContaining("invalid account id")
        .hasMessageContaining("fgValuationAccountId");
  }

  private Account ensureAccount(String code, String name, AccountType type) {
    return ensureAccountFor(company, code, name, type);
  }

  private Account ensureAccountFor(
      Company targetCompany, String code, String name, AccountType type) {
    return accountRepository
        .findByCompanyAndCodeIgnoreCase(targetCompany, code)
        .orElseGet(
            () -> {
              Account account = new Account();
              account.setCompany(targetCompany);
              account.setCode(code);
              account.setName(name);
              account.setType(type);
              return accountRepository.save(account);
            });
  }
}
