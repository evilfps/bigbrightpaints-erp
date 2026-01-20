package com.bigbrightpaints.erp.regression;

import com.bigbrightpaints.erp.core.security.CompanyContextHolder;
import com.bigbrightpaints.erp.modules.accounting.domain.Account;
import com.bigbrightpaints.erp.modules.accounting.domain.AccountRepository;
import com.bigbrightpaints.erp.modules.accounting.domain.AccountType;
import com.bigbrightpaints.erp.modules.company.domain.Company;
import com.bigbrightpaints.erp.modules.company.domain.CompanyRepository;
import com.bigbrightpaints.erp.modules.production.dto.ProductCreateRequest;
import com.bigbrightpaints.erp.modules.production.dto.ProductionProductDto;
import com.bigbrightpaints.erp.modules.production.service.ProductionCatalogService;
import com.bigbrightpaints.erp.test.AbstractIntegrationTest;
import java.math.BigDecimal;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("Regression: Catalog create handles null discount default")
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
        CompanyContextHolder.setCompanyId(COMPANY_CODE);

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
    void createProductDoesNotFailWhenDiscountDefaultMissing() {
        ProductCreateRequest request = new ProductCreateRequest(
                null,
                "LF-014 Brand",
                null,
                "LF-014 Product",
                "FINISHED_GOOD",
                "WHITE",
                "1L",
                "UNIT",
                null,
                new BigDecimal("100.00"),
                new BigDecimal("18.00"),
                null,
                null,
                null
        );

        ProductionProductDto dto = productionCatalogService.createProduct(request);
        Map<String, Object> metadata = dto.metadata();

        assertThat(asLong(metadata.get("fgValuationAccountId"))).isEqualTo(inventoryAccount.getId());
        assertThat(asLong(metadata.get("fgCogsAccountId"))).isEqualTo(cogsAccount.getId());
        assertThat(asLong(metadata.get("fgRevenueAccountId"))).isEqualTo(revenueAccount.getId());
        assertThat(asLong(metadata.get("fgTaxAccountId"))).isEqualTo(taxAccount.getId());
        assertThat(metadata).doesNotContainKey("fgDiscountAccountId");
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

    private Long asLong(Object value) {
        if (value instanceof Number number) {
            return number.longValue();
        }
        if (value instanceof String text && !text.isBlank()) {
            return Long.parseLong(text.trim());
        }
        return null;
    }
}
