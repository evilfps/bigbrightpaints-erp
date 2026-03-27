package com.bigbrightpaints.erp.codered;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.nio.charset.StandardCharsets;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;

import com.bigbrightpaints.erp.core.exception.ApplicationException;
import com.bigbrightpaints.erp.core.security.CompanyContextHolder;
import com.bigbrightpaints.erp.modules.accounting.domain.Account;
import com.bigbrightpaints.erp.modules.accounting.domain.AccountRepository;
import com.bigbrightpaints.erp.modules.accounting.domain.AccountType;
import com.bigbrightpaints.erp.modules.company.domain.Company;
import com.bigbrightpaints.erp.modules.company.domain.CompanyRepository;
import com.bigbrightpaints.erp.modules.inventory.domain.InventoryMovementRepository;
import com.bigbrightpaints.erp.modules.inventory.domain.InventoryReference;
import com.bigbrightpaints.erp.modules.inventory.domain.OpeningStockImportRepository;
import com.bigbrightpaints.erp.modules.inventory.domain.RawMaterialMovementRepository;
import com.bigbrightpaints.erp.modules.inventory.service.OpeningStockImportService;
import com.bigbrightpaints.erp.test.AbstractIntegrationTest;

@ActiveProfiles(
    value = {"test", "prod"},
    inheritProfiles = false)
@TestPropertySource(
    properties = {
      "jwt.secret=2f4f8a6c9b1d4e7f8a2c5d9e3f6b7c1a4d8e2f5a9c3b6d7e",
      "spring.mail.host=localhost",
      "spring.mail.username=test-smtp-user",
      "spring.mail.password=test-smtp-password",
      "ERP_LICENSE_KEY=test-license-key",
      "ERP_DISPATCH_DEBIT_ACCOUNT_ID=1",
      "ERP_DISPATCH_CREDIT_ACCOUNT_ID=2",
      "management.endpoint.health.validate-group-membership=false",
      "erp.environment.validation.enabled=false",
      "erp.inventory.opening-stock.enabled=false"
    })
class CR_OpeningStockImportProdGatingIT extends AbstractIntegrationTest {

  private static final String COMPANY_CODE = "CR-OPEN-PROD";
  private static final String OPENING_STOCK_BATCH_KEY = "OPEN-STOCK-BATCH-PROD-001";

  @Autowired private OpeningStockImportService openingStockImportService;
  @Autowired private OpeningStockImportRepository openingStockImportRepository;
  @Autowired private CompanyRepository companyRepository;
  @Autowired private AccountRepository accountRepository;
  @Autowired private RawMaterialMovementRepository rawMaterialMovementRepository;
  @Autowired private InventoryMovementRepository inventoryMovementRepository;

  private Company company;

  @BeforeEach
  void setUp() {
    company =
        companyRepository
            .findByCodeIgnoreCase(COMPANY_CODE)
            .orElseGet(
                () -> {
                  Company created = new Company();
                  created.setCode(COMPANY_CODE);
                  created.setName("CR Opening Stock Prod Gate");
                  created.setTimezone("UTC");
                  return companyRepository.save(created);
                });
    Account inventory = ensureAccount(company, "INV-OPEN-PROD", "Inventory", AccountType.ASSET);
    ensureAccount(company, "COGS-OPEN-PROD", "COGS", AccountType.COGS);
    ensureAccount(company, "REV-OPEN-PROD", "Revenue", AccountType.REVENUE);
    ensureAccount(company, "GST-OPEN-PROD", "GST Output", AccountType.LIABILITY);
    company.setDefaultInventoryAccountId(inventory.getId());
    company.setDefaultCogsAccountId(
        accountRepository
            .findByCompanyAndCodeIgnoreCase(company, "COGS-OPEN-PROD")
            .orElseThrow()
            .getId());
    company.setDefaultRevenueAccountId(
        accountRepository
            .findByCompanyAndCodeIgnoreCase(company, "REV-OPEN-PROD")
            .orElseThrow()
            .getId());
    company.setDefaultTaxAccountId(
        accountRepository
            .findByCompanyAndCodeIgnoreCase(company, "GST-OPEN-PROD")
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
  void prodBlocksOpeningStockImport() {
    MockMultipartFile file = csvFile();

    ApplicationException ex =
        assertThrows(
            ApplicationException.class,
            () ->
                openingStockImportService.importOpeningStock(
                    file, "OPEN-PROD-001", OPENING_STOCK_BATCH_KEY));
    assertThat(ex.getMessage()).contains("Opening stock import is disabled");

    assertThat(
            openingStockImportRepository.findByCompanyAndIdempotencyKey(company, "OPEN-PROD-001"))
        .isEmpty();
    assertThat(
            rawMaterialMovementRepository.findByRawMaterialCompanyAndReferenceTypeAndReferenceId(
                company, InventoryReference.OPENING_STOCK, "RM-OPEN-P1"))
        .isEmpty();
    assertThat(
            inventoryMovementRepository
                .findByFinishedGood_CompanyAndReferenceTypeAndReferenceIdOrderByCreatedAtAsc(
                    company, InventoryReference.OPENING_STOCK, "FG-OPEN-P1"))
        .isEmpty();
  }

  private MockMultipartFile csvFile() {
    String csv =
        String.join(
            "\n",
            "type,sku,name,unit,unit_type,batch_code,quantity,unit_cost,material_type,manufactured_at",
            "RAW_MATERIAL,RM-OPEN-P1,Resin,KG,KG,RM-OPEN-P1,10,5.00,PRODUCTION,",
            "FINISHED_GOOD,FG-OPEN-P1,Paint 1L,L,L,FG-OPEN-P1,5,12.50,,2026-01-10");
    return new MockMultipartFile(
        "file", "opening.csv", "text/csv", csv.getBytes(StandardCharsets.UTF_8));
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
