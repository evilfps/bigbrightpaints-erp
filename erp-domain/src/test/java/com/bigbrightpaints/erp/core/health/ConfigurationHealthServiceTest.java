package com.bigbrightpaints.erp.core.health;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import com.bigbrightpaints.erp.modules.company.domain.Company;
import com.bigbrightpaints.erp.modules.company.domain.CompanyRepository;
import com.bigbrightpaints.erp.modules.inventory.domain.FinishedGoodRepository;
import com.bigbrightpaints.erp.modules.inventory.domain.RawMaterialRepository;
import com.bigbrightpaints.erp.modules.production.domain.ProductionProductRepository;

@ExtendWith(MockitoExtension.class)
class ConfigurationHealthServiceTest {

  @Mock private CompanyRepository companyRepository;
  @Mock private FinishedGoodRepository finishedGoodRepository;
  @Mock private RawMaterialRepository rawMaterialRepository;
  @Mock private ProductionProductRepository productionProductRepository;

  private ConfigurationHealthService configurationHealthService;

  @BeforeEach
  void setup() {
    configurationHealthService =
        new ConfigurationHealthService(
            companyRepository,
            finishedGoodRepository,
            rawMaterialRepository,
            productionProductRepository);
  }

  @Test
  void evaluate_reportsMissingBaseCurrencyAndDefaultAccounts() {
    Company company = new Company();
    company.setCode("CFG-TEST");
    company.setName("Config Test Co");
    company.setTimezone("UTC");
    company.setGstInputTaxAccountId(1L);
    company.setGstOutputTaxAccountId(2L);
    ReflectionTestUtils.setField(company, "baseCurrency", " ");

    when(companyRepository.findAll()).thenReturn(List.of(company));
    when(finishedGoodRepository.findByCompanyOrderByProductCodeAsc(company)).thenReturn(List.of());
    when(rawMaterialRepository.findByCompanyOrderByNameAsc(company)).thenReturn(List.of());
    when(productionProductRepository.findByCompanyOrderByProductNameAsc(company))
        .thenReturn(List.of());

    ConfigurationHealthService.ConfigurationHealthReport report =
        configurationHealthService.evaluate();

    assertThat(report.issues())
        .anySatisfy(issue -> assertThat(issue.domain()).isEqualTo("BASE_CURRENCY"));
    assertThat(report.issues())
        .anySatisfy(
            issue -> {
              assertThat(issue.domain()).isEqualTo("DEFAULT_ACCOUNTS");
              assertThat(issue.message()).contains("defaultDiscountAccountId");
            });
  }

  @Test
  void evaluateCompany_returnsHealthyWhenCompanyMissing() {
    ConfigurationHealthService.ConfigurationHealthReport report =
        configurationHealthService.evaluateCompany(null);

    assertThat(report.healthy()).isTrue();
    assertThat(report.issues()).isEmpty();
  }

  @Test
  void evaluateCompany_reportsNonGstModeWithConfiguredTaxAccounts() {
    Company company = configuredCompany("CFG-NON-GST");
    company.setDefaultGstRate(BigDecimal.ZERO);
    company.setGstInputTaxAccountId(11L);
    company.setGstOutputTaxAccountId(12L);
    company.setGstPayableAccountId(13L);
    stubEmptyCatalog(company);

    ConfigurationHealthService.ConfigurationHealthReport report =
        configurationHealthService.evaluateCompany(company);

    assertThat(report.healthy()).isFalse();
    assertThat(report.issues())
        .anySatisfy(
            issue -> {
              assertThat(issue.domain()).isEqualTo("TAX_ACCOUNT");
              assertThat(issue.reference()).isEqualTo("NON_GST_MODE");
              assertThat(issue.message())
                  .contains("gstInputTaxAccountId")
                  .contains("gstOutputTaxAccountId")
                  .contains("gstPayableAccountId");
            });
  }

  @Test
  void evaluateCompany_allowsNonGstModeWithoutConfiguredTaxAccounts() {
    Company company = configuredCompany("CFG-NON-GST-CLEAN");
    company.setDefaultGstRate(BigDecimal.ZERO);
    company.setGstInputTaxAccountId(null);
    company.setGstOutputTaxAccountId(null);
    company.setGstPayableAccountId(null);
    stubEmptyCatalog(company);

    ConfigurationHealthService.ConfigurationHealthReport report =
        configurationHealthService.evaluateCompany(company);

    assertThat(report.healthy()).isTrue();
    assertThat(report.issues())
        .noneSatisfy(issue -> assertThat(issue.domain()).isEqualTo("TAX_ACCOUNT"));
  }

  @Test
  void evaluateCompany_reportsMissingGstPayableInGstMode() {
    Company company = configuredCompany("CFG-GST");
    company.setDefaultGstRate(new BigDecimal("18.00"));
    company.setGstInputTaxAccountId(21L);
    company.setGstOutputTaxAccountId(22L);
    company.setGstPayableAccountId(null);
    stubEmptyCatalog(company);

    ConfigurationHealthService.ConfigurationHealthReport report =
        configurationHealthService.evaluateCompany(company);

    assertThat(report.healthy()).isFalse();
    assertThat(report.issues())
        .anySatisfy(
            issue -> {
              assertThat(issue.domain()).isEqualTo("TAX_ACCOUNT");
              assertThat(issue.reference()).isEqualTo("GST_PAYABLE");
              assertThat(issue.message()).contains("GST payable account");
            });
  }

  @Test
  void evaluateCompany_reportsMissingGstInputAndOutputInGstMode() {
    Company company = configuredCompany("CFG-GST-MISSING");
    company.setDefaultGstRate(new BigDecimal("18.00"));
    company.setGstInputTaxAccountId(null);
    company.setGstOutputTaxAccountId(null);
    company.setGstPayableAccountId(23L);
    stubEmptyCatalog(company);

    ConfigurationHealthService.ConfigurationHealthReport report =
        configurationHealthService.evaluateCompany(company);

    assertThat(report.healthy()).isFalse();
    assertThat(report.issues())
        .anySatisfy(
            issue -> {
              assertThat(issue.domain()).isEqualTo("TAX_ACCOUNT");
              assertThat(issue.reference()).isEqualTo("GST_INPUT");
            });
    assertThat(report.issues())
        .anySatisfy(
            issue -> {
              assertThat(issue.domain()).isEqualTo("TAX_ACCOUNT");
              assertThat(issue.reference()).isEqualTo("GST_OUTPUT");
            });
  }

  private Company configuredCompany(String code) {
    Company company = new Company();
    company.setCode(code);
    company.setName("Config Test Co");
    company.setTimezone("UTC");
    ReflectionTestUtils.setField(company, "baseCurrency", "INR");
    company.setDefaultInventoryAccountId(1L);
    company.setDefaultCogsAccountId(2L);
    company.setDefaultRevenueAccountId(3L);
    company.setDefaultDiscountAccountId(4L);
    company.setDefaultTaxAccountId(5L);
    return company;
  }

  private void stubEmptyCatalog(Company company) {
    when(finishedGoodRepository.findByCompanyOrderByProductCodeAsc(company)).thenReturn(List.of());
    when(rawMaterialRepository.findByCompanyOrderByNameAsc(company)).thenReturn(List.of());
    when(productionProductRepository.findByCompanyOrderByProductNameAsc(company))
        .thenReturn(List.of());
  }
}
