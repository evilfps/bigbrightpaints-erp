package com.bigbrightpaints.erp.modules.reports;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import com.bigbrightpaints.erp.core.config.SystemSettingsRepository;
import com.bigbrightpaints.erp.modules.auth.domain.UserAccountRepository;
import com.bigbrightpaints.erp.modules.company.domain.Company;
import com.bigbrightpaints.erp.modules.company.domain.CompanyLifecycleState;
import com.bigbrightpaints.erp.modules.company.domain.CompanyRepository;
import com.bigbrightpaints.erp.modules.company.service.TenantRuntimeEnforcementService;
import com.bigbrightpaints.erp.modules.factory.domain.ProductionLog;
import com.bigbrightpaints.erp.modules.factory.domain.ProductionLogRepository;
import com.bigbrightpaints.erp.modules.production.domain.ProductionBrand;
import com.bigbrightpaints.erp.modules.production.domain.ProductionBrandRepository;
import com.bigbrightpaints.erp.modules.production.domain.ProductionProduct;
import com.bigbrightpaints.erp.modules.production.domain.ProductionProductRepository;
import com.bigbrightpaints.erp.test.AbstractIntegrationTest;

class ReportControllerSecurityIT extends AbstractIntegrationTest {

  private static final String COMPANY_A_CODE = "RPT-SEC-A";
  private static final String COMPANY_B_CODE = "RPT-SEC-B";
  private static final String ACCOUNTING_A_EMAIL = "reports-acc-a@bbp.com";
  private static final String FACTORY_A_EMAIL = "reports-factory-a@bbp.com";
  private static final String ACCOUNTING_B_EMAIL = "reports-acc-b@bbp.com";
  private static final String SUPER_ADMIN_EMAIL = "reports-root-super@bbp.com";
  private static final String PASSWORD = "ReportSec123!";

  @Autowired private TestRestTemplate rest;

  @Autowired private CompanyRepository companyRepository;

  @Autowired private ProductionBrandRepository productionBrandRepository;

  @Autowired private ProductionProductRepository productionProductRepository;

  @Autowired private ProductionLogRepository productionLogRepository;

  @Autowired private UserAccountRepository userAccountRepository;

  @Autowired private SystemSettingsRepository systemSettingsRepository;

  @Autowired private TenantRuntimeEnforcementService tenantRuntimeEnforcementService;

  private Long companyAProductionLogId;

  @BeforeEach
  void setup() {
    dataSeeder.ensureUser(
        ACCOUNTING_A_EMAIL,
        PASSWORD,
        "Reports Accounting A",
        COMPANY_A_CODE,
        List.of("ROLE_ACCOUNTING"));
    dataSeeder.ensureUser(
        FACTORY_A_EMAIL, PASSWORD, "Reports Factory A", COMPANY_A_CODE, List.of("ROLE_FACTORY"));
    dataSeeder.ensureUser(
        ACCOUNTING_B_EMAIL,
        PASSWORD,
        "Reports Accounting B",
        COMPANY_B_CODE,
        List.of("ROLE_ACCOUNTING"));
    dataSeeder.ensureUser(
        SUPER_ADMIN_EMAIL,
        PASSWORD,
        "Reports Root Super",
        COMPANY_A_CODE,
        List.of("ROLE_SUPER_ADMIN", "ROLE_ADMIN"));
    userAccountRepository
        .findByEmailIgnoreCase(SUPER_ADMIN_EMAIL)
        .ifPresent(
            user -> {
              user.setMustChangePassword(false);
              user.setEnabled(true);
              userAccountRepository.save(user);
            });

    Company companyA = companyRepository.findByCodeIgnoreCase(COMPANY_A_CODE).orElseThrow();
    companyA.setLifecycleState(CompanyLifecycleState.ACTIVE);
    companyA.setLifecycleReason(null);
    companyRepository.saveAndFlush(companyA);
    resetTenantRuntimePolicy(companyA.getId(), companyA.getCode());
    companyAProductionLogId = seedProductionLog(companyA).getId();
  }

  @Test
  void costBreakdown_forbidsFactoryRole() {
    HttpHeaders headers = authHeaders(FACTORY_A_EMAIL, COMPANY_A_CODE);
    ResponseEntity<Map> response =
        rest.exchange(
            "/api/v1/reports/production-logs/" + companyAProductionLogId + "/cost-breakdown",
            HttpMethod.GET,
            new HttpEntity<>(headers),
            Map.class);

    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
  }

  @Test
  void costBreakdown_rejectsCrossCompanyProductionLogLookup() {
    HttpHeaders headers = authHeaders(ACCOUNTING_B_EMAIL, COMPANY_B_CODE);
    ResponseEntity<Map> response =
        rest.exchange(
            "/api/v1/reports/production-logs/" + companyAProductionLogId + "/cost-breakdown",
            HttpMethod.GET,
            new HttpEntity<>(headers),
            Map.class);

    assertThat(response.getStatusCode().is4xxClientError()).isTrue();
    assertThat(response.getBody()).isNotNull();
    assertThat(response.getBody().get("success")).isEqualTo(Boolean.FALSE);
  }

  @Test
  void costBreakdown_allowsAccountingRoleInOwningCompany() {
    HttpHeaders headers = authHeaders(ACCOUNTING_A_EMAIL, COMPANY_A_CODE);
    ResponseEntity<Map> response =
        rest.exchange(
            "/api/v1/reports/production-logs/" + companyAProductionLogId + "/cost-breakdown",
            HttpMethod.GET,
            new HttpEntity<>(headers),
            Map.class);

    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
  }

  @Test
  void costBreakdown_followsCanonicalRuntimeBlockAndRecovery() {
    Company companyA = companyRepository.findByCodeIgnoreCase(COMPANY_A_CODE).orElseThrow();
    HttpHeaders superAdminHeaders = authHeaders(SUPER_ADMIN_EMAIL, COMPANY_A_CODE);
    HttpHeaders accountingHeaders = authHeaders(ACCOUNTING_A_EMAIL, COMPANY_A_CODE);

    ResponseEntity<Map> blockResponse =
        rest.exchange(
            "/api/v1/superadmin/tenants/" + companyA.getId() + "/lifecycle",
            HttpMethod.PUT,
            new HttpEntity<>(
                Map.of("state", "DEACTIVATED", "reason", "REPORT_LOCKDOWN"), superAdminHeaders),
            Map.class);
    assertThat(blockResponse.getStatusCode()).isEqualTo(HttpStatus.OK);

    ResponseEntity<Map> denied =
        rest.exchange(
            "/api/v1/reports/production-logs/" + companyAProductionLogId + "/cost-breakdown",
            HttpMethod.GET,
            new HttpEntity<>(accountingHeaders),
            Map.class);
    assertThat(denied.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
    assertThat(denied.getBody()).isNotNull();
    assertThat(((Map<?, ?>) denied.getBody().get("data")).get("reason"))
        .isEqualTo("TENANT_LIFECYCLE_RESTRICTED");
    assertThat(((Map<?, ?>) denied.getBody().get("data")).get("reasonDetail"))
        .isEqualTo("Tenant is deactivated");

    ResponseEntity<Map> recoveryResponse =
        rest.exchange(
            "/api/v1/superadmin/tenants/" + companyA.getId() + "/lifecycle",
            HttpMethod.PUT,
            new HttpEntity<>(
                Map.of("state", "ACTIVE", "reason", "REPORT_RECOVERY"), superAdminHeaders),
            Map.class);
    assertThat(recoveryResponse.getStatusCode()).isEqualTo(HttpStatus.OK);

    ResponseEntity<Map> recovered =
        rest.exchange(
            "/api/v1/reports/production-logs/" + companyAProductionLogId + "/cost-breakdown",
            HttpMethod.GET,
            new HttpEntity<>(accountingHeaders),
            Map.class);
    assertThat(recovered.getStatusCode()).isEqualTo(HttpStatus.OK);
  }

  private HttpHeaders authHeaders(String email, String companyCode) {
    Map<String, Object> payload =
        Map.of(
            "email", email,
            "password", PASSWORD,
            "companyCode", companyCode);
    ResponseEntity<Map> login = rest.postForEntity("/api/v1/auth/login", payload, Map.class);
    assertThat(login.getStatusCode()).isEqualTo(HttpStatus.OK);
    assertThat(login.getBody()).isNotNull();

    HttpHeaders headers = new HttpHeaders();
    headers.setBearerAuth((String) login.getBody().get("accessToken"));
    headers.set("X-Company-Code", companyCode);
    return headers;
  }

  private ProductionLog seedProductionLog(Company company) {
    String suffix = Long.toString(System.nanoTime());
    ProductionBrand brand = new ProductionBrand();
    brand.setCompany(company);
    brand.setCode("RPT-BRAND-" + suffix);
    brand.setName("Report Security Brand " + suffix);
    brand = productionBrandRepository.saveAndFlush(brand);

    ProductionProduct product = new ProductionProduct();
    product.setCompany(company);
    product.setBrand(brand);
    product.setSkuCode("RPT-SKU-" + suffix);
    product.setProductName("Report Security Product " + suffix);
    product.setCategory("PAINT");
    product.setUnitOfMeasure("LITER");
    product.setBasePrice(new BigDecimal("100.00"));
    product.setGstRate(new BigDecimal("18.00"));
    product.setMinDiscountPercent(BigDecimal.ZERO);
    product.setMinSellingPrice(new BigDecimal("90.00"));
    product = productionProductRepository.saveAndFlush(product);

    ProductionLog log = new ProductionLog();
    log.setCompany(company);
    log.setBrand(brand);
    log.setProduct(product);
    log.setProductionCode("RPT-LOG-" + suffix);
    log.setBatchColour("BLUE");
    log.setBatchSize(new BigDecimal("10.00"));
    log.setUnitOfMeasure("LITER");
    log.setMixedQuantity(new BigDecimal("10.00"));
    log.setTotalPackedQuantity(new BigDecimal("10.00"));
    log.setWastageQuantity(BigDecimal.ZERO);
    log.setMaterialCostTotal(new BigDecimal("100.00"));
    log.setLaborCostTotal(new BigDecimal("20.00"));
    log.setOverheadCostTotal(new BigDecimal("10.00"));
    log.setUnitCost(new BigDecimal("13.00"));
    log.setProducedAt(Instant.parse("2026-02-13T00:00:00Z"));
    return productionLogRepository.saveAndFlush(log);
  }

  private void resetTenantRuntimePolicy(Long companyId, String companyCode) {
    if (companyId == null) {
      return;
    }
    systemSettingsRepository.deleteById("tenant.runtime.hold-state." + companyId);
    systemSettingsRepository.deleteById("tenant.runtime.hold-reason." + companyId);
    systemSettingsRepository.deleteById("tenant.runtime.max-active-users." + companyId);
    systemSettingsRepository.deleteById("tenant.runtime.max-requests-per-minute." + companyId);
    systemSettingsRepository.deleteById("tenant.runtime.max-concurrent-requests." + companyId);
    systemSettingsRepository.deleteById("tenant.runtime.policy-reference." + companyId);
    systemSettingsRepository.deleteById("tenant.runtime.policy-updated-at." + companyId);
    tenantRuntimeEnforcementService.invalidatePolicyCache(companyCode);
  }
}
