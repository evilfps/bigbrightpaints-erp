package com.bigbrightpaints.erp.core.health;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import com.bigbrightpaints.erp.modules.company.domain.Company;
import com.bigbrightpaints.erp.modules.company.domain.CompanyRepository;
import com.bigbrightpaints.erp.modules.inventory.domain.FinishedGood;
import com.bigbrightpaints.erp.modules.inventory.domain.FinishedGoodRepository;
import com.bigbrightpaints.erp.modules.inventory.domain.RawMaterial;
import com.bigbrightpaints.erp.modules.inventory.domain.RawMaterialRepository;
import com.bigbrightpaints.erp.modules.production.domain.ProductionProduct;
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

  @Test
  void evaluateCompany_reportsFinishedGoodValuationGapAsFinishedGoodAccountIssue() {
    Company company = configuredCompany("CFG-FG-VALUATION");
    company.setDefaultGstRate(BigDecimal.ZERO);
    company.setGstInputTaxAccountId(null);
    company.setGstOutputTaxAccountId(null);
    company.setGstPayableAccountId(null);

    FinishedGood finishedGood = new FinishedGood();
    finishedGood.setProductCode("FG-GST");
    finishedGood.setValuationAccountId(null);
    finishedGood.setRevenueAccountId(301L);
    finishedGood.setTaxAccountId(302L);

    when(finishedGoodRepository.findByCompanyOrderByProductCodeAsc(company))
        .thenReturn(List.of(finishedGood));
    when(rawMaterialRepository.findByCompanyOrderByNameAsc(company)).thenReturn(List.of());
    when(productionProductRepository.findByCompanyOrderByProductNameAsc(company))
        .thenReturn(List.of());

    ConfigurationHealthService.ConfigurationHealthReport report =
        configurationHealthService.evaluateCompany(company);

    assertThat(report.healthy()).isFalse();
    assertThat(report.issues())
        .anySatisfy(
            issue -> {
              assertThat(issue.domain()).isEqualTo("FINISHED_GOOD_ACCOUNT");
              assertThat(issue.reference()).isEqualTo("FG-GST");
              assertThat(issue.message()).contains("valuation");
            });
  }

  @Test
  void evaluateCompany_reportsStockCarryingVariantMetadataGapAsFinishedGoodAccountIssue() {
    Company company = configuredCompany("CFG-FG-VARIANT");
    company.setDefaultGstRate(BigDecimal.ZERO);
    company.setGstInputTaxAccountId(null);
    company.setGstOutputTaxAccountId(null);
    company.setGstPayableAccountId(null);

    ProductionProduct variant = new ProductionProduct();
    variant.setCategory("FINISHED_GOOD");
    variant.setSkuCode("FG-VAR-01");
    variant.setMetadata(Map.of("fgRevenueAccountId", 401L, "fgTaxAccountId", 402L));

    when(finishedGoodRepository.findByCompanyOrderByProductCodeAsc(company)).thenReturn(List.of());
    when(rawMaterialRepository.findByCompanyOrderByNameAsc(company)).thenReturn(List.of());
    when(productionProductRepository.findByCompanyOrderByProductNameAsc(company))
        .thenReturn(List.of(variant));

    ConfigurationHealthService.ConfigurationHealthReport report =
        configurationHealthService.evaluateCompany(company);

    assertThat(report.healthy()).isFalse();
    assertThat(report.issues())
        .anySatisfy(
            issue -> {
              assertThat(issue.domain()).isEqualTo("FINISHED_GOOD_ACCOUNT");
              assertThat(issue.reference()).isEqualTo("FG-VAR-01");
              assertThat(issue.message()).contains("valuation");
            });
  }

  @Test
  void evaluateCompany_keepsRawMaterialAndTaxIssuesAlongsideFinishedGoodCoverage() {
    Company company = configuredCompany("CFG-MIXED-GAPS");
    company.setDefaultGstRate(new BigDecimal("18.00"));
    company.setGstInputTaxAccountId(501L);
    company.setGstOutputTaxAccountId(null);
    company.setGstPayableAccountId(503L);

    FinishedGood finishedGood = new FinishedGood();
    finishedGood.setProductCode("FG-MIXED-01");
    finishedGood.setValuationAccountId(null);
    finishedGood.setRevenueAccountId(601L);
    finishedGood.setTaxAccountId(602L);

    RawMaterial rawMaterial = new RawMaterial();
    rawMaterial.setSku("RM-RESIN");
    rawMaterial.setInventoryAccountId(null);

    when(finishedGoodRepository.findByCompanyOrderByProductCodeAsc(company))
        .thenReturn(List.of(finishedGood));
    when(rawMaterialRepository.findByCompanyOrderByNameAsc(company))
        .thenReturn(List.of(rawMaterial));
    when(productionProductRepository.findByCompanyOrderByProductNameAsc(company))
        .thenReturn(List.of());

    ConfigurationHealthService.ConfigurationHealthReport report =
        configurationHealthService.evaluateCompany(company);

    assertThat(report.healthy()).isFalse();
    assertThat(report.issues())
        .anySatisfy(issue -> assertThat(issue.domain()).isEqualTo("FINISHED_GOOD_ACCOUNT"));
    assertThat(report.issues())
        .anySatisfy(issue -> assertThat(issue.domain()).isEqualTo("RAW_MATERIAL_ACCOUNT"));
    assertThat(report.issues()).anySatisfy(issue -> assertThat(issue.domain()).isEqualTo("TAX_ACCOUNT"));
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
