package com.bigbrightpaints.erp.codered;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.math.BigDecimal;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;

import com.bigbrightpaints.erp.core.exception.ApplicationException;
import com.bigbrightpaints.erp.core.security.CompanyContextHolder;
import com.bigbrightpaints.erp.modules.company.domain.Company;
import com.bigbrightpaints.erp.modules.company.domain.CompanyRepository;
import com.bigbrightpaints.erp.modules.inventory.domain.FinishedGood;
import com.bigbrightpaints.erp.modules.inventory.domain.FinishedGoodBatchRepository;
import com.bigbrightpaints.erp.modules.inventory.domain.FinishedGoodRepository;
import com.bigbrightpaints.erp.modules.inventory.dto.FinishedGoodBatchRequest;
import com.bigbrightpaints.erp.modules.inventory.service.FinishedGoodsService;
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
      "erp.inventory.finished-goods.batch.enabled=false"
    })
class CR_FinishedGoodBatchProdGatingIT extends AbstractIntegrationTest {

  private static final String COMPANY_CODE = "CR-FG-BATCH-PROD";

  @Autowired private FinishedGoodsService finishedGoodsService;
  @Autowired private CompanyRepository companyRepository;
  @Autowired private FinishedGoodRepository finishedGoodRepository;
  @Autowired private FinishedGoodBatchRepository finishedGoodBatchRepository;

  private Company company;
  private FinishedGood finishedGood;

  @BeforeEach
  void setUp() {
    company =
        companyRepository
            .findByCodeIgnoreCase(COMPANY_CODE)
            .orElseGet(
                () -> {
                  Company created = new Company();
                  created.setCode(COMPANY_CODE);
                  created.setName("CR Finished Good Gate");
                  created.setTimezone("UTC");
                  return companyRepository.save(created);
                });
    finishedGood =
        finishedGoodRepository
            .findByCompanyAndProductCode(company, "CR-FG-LEGACY")
            .orElseGet(
                () -> {
                  FinishedGood fg = new FinishedGood();
                  fg.setCompany(company);
                  fg.setProductCode("CR-FG-LEGACY");
                  fg.setName("CR Legacy FG");
                  fg.setUnit("UNIT");
                  fg.setCostingMethod("FIFO");
                  fg.setCurrentStock(BigDecimal.ZERO);
                  fg.setReservedStock(BigDecimal.ZERO);
                  return finishedGoodRepository.save(fg);
                });
    CompanyContextHolder.setCompanyCode(COMPANY_CODE);
  }

  @AfterEach
  void tearDown() {
    CompanyContextHolder.clear();
  }

  @Test
  void prodBlocksManualFinishedGoodBatchRegistration() {
    FinishedGoodBatchRequest request =
        new FinishedGoodBatchRequest(
            finishedGood.getId(),
            "FG-LEGACY-001",
            new BigDecimal("5"),
            new BigDecimal("10.00"),
            java.time.Instant.parse("2026-01-10T00:00:00Z"),
            null);

    ApplicationException ex =
        assertThrows(ApplicationException.class, () -> finishedGoodsService.registerBatch(request));

    assertThat(ex.getMessage()).contains("Manual finished good batch registration is disabled");
    assertThat(finishedGoodBatchRepository.findByFinishedGoodOrderByManufacturedAtAsc(finishedGood))
        .isEmpty();
  }
}
