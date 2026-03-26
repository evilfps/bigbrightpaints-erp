package com.bigbrightpaints.erp.modules.company;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Locale;
import java.util.Map;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;

import com.bigbrightpaints.erp.modules.company.domain.Company;
import com.bigbrightpaints.erp.modules.company.domain.CompanyLifecycleState;
import com.bigbrightpaints.erp.modules.company.domain.CompanyRepository;
import com.bigbrightpaints.erp.test.AbstractIntegrationTest;

class SuperAdminControllerIT extends AbstractIntegrationTest {

  private static final String COMPANY_CODE = "ACME";
  private static final String ROOT_COMPANY_CODE = "ROOT";
  private static final String ADMIN_EMAIL = "admin@bbp.com";
  private static final String SUPER_ADMIN_EMAIL = "super-admin@bbp.com";
  private static final String PASSWORD = "admin123";

  @Autowired private TestRestTemplate rest;

  @Autowired private CompanyRepository companyRepository;

  @Autowired private JdbcTemplate jdbcTemplate;

  @BeforeEach
  void seedUsers() {
    dataSeeder.ensureUser(ADMIN_EMAIL, PASSWORD, "Admin", COMPANY_CODE, List.of("ROLE_ADMIN"));
    dataSeeder.ensureUser(
        SUPER_ADMIN_EMAIL,
        PASSWORD,
        "Super Admin",
        ROOT_COMPANY_CODE,
        List.of("ROLE_SUPER_ADMIN", "ROLE_ADMIN"));
    companyRepository
        .findByCodeIgnoreCase(COMPANY_CODE)
        .ifPresent(
            company -> {
              company.setLifecycleState(CompanyLifecycleState.ACTIVE);
              company.setLifecycleReason(null);
              companyRepository.save(company);
            });
  }

  @Test
  void dashboard_requiresSuperAdminAuthority() {
    String adminToken = loginToken(ADMIN_EMAIL, COMPANY_CODE);
    ResponseEntity<Map> forbidden =
        rest.exchange(
            "/api/v1/superadmin/dashboard",
            HttpMethod.GET,
            new HttpEntity<>(headers(adminToken, COMPANY_CODE)),
            Map.class);

    assertThat(forbidden.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);

    String superAdminToken = loginToken(SUPER_ADMIN_EMAIL, ROOT_COMPANY_CODE);
    ResponseEntity<Map> allowed =
        rest.exchange(
            "/api/v1/superadmin/dashboard",
            HttpMethod.GET,
            new HttpEntity<>(headers(superAdminToken, ROOT_COMPANY_CODE)),
            Map.class);

    assertThat(allowed.getStatusCode()).isEqualTo(HttpStatus.OK);
    assertThat(allowed.getBody()).isNotNull();
    assertThat(allowed.getBody()).containsKey("data");
  }

  @Test
  void superAdmin_canUpdateLifecycle_listTenants_andReadTenantDetail() {
    Company tenant = companyRepository.findByCodeIgnoreCase(COMPANY_CODE).orElseThrow();
    String superAdminToken = loginToken(SUPER_ADMIN_EMAIL, ROOT_COMPANY_CODE);
    HttpHeaders superAdminHeaders = headers(superAdminToken, ROOT_COMPANY_CODE);

    ResponseEntity<Map> suspendResponse =
        rest.exchange(
            "/api/v1/superadmin/tenants/" + tenant.getId() + "/lifecycle",
            HttpMethod.PUT,
            new HttpEntity<>(
                Map.of("state", "SUSPENDED", "reason", "ops-review"), superAdminHeaders),
            Map.class);
    assertThat(suspendResponse.getStatusCode()).isEqualTo(HttpStatus.OK);
    assertThat(readLifecycleState(tenant.getId())).isEqualTo("SUSPENDED");

    ResponseEntity<Map> tenantsResponse =
        rest.exchange(
            "/api/v1/superadmin/tenants?status=SUSPENDED",
            HttpMethod.GET,
            new HttpEntity<>(superAdminHeaders),
            Map.class);
    assertThat(tenantsResponse.getStatusCode()).isEqualTo(HttpStatus.OK);
    @SuppressWarnings("unchecked")
    List<Map<String, Object>> tenants =
        (List<Map<String, Object>>) tenantsResponse.getBody().get("data");
    assertThat(tenants)
        .extracting(row -> row.get("companyCode").toString().toUpperCase(Locale.ROOT))
        .contains(COMPANY_CODE);

    ResponseEntity<Map> detailResponse =
        rest.exchange(
            "/api/v1/superadmin/tenants/" + tenant.getId(),
            HttpMethod.GET,
            new HttpEntity<>(superAdminHeaders),
            Map.class);
    assertThat(detailResponse.getStatusCode()).isEqualTo(HttpStatus.OK);
    @SuppressWarnings("unchecked")
    Map<String, Object> detail = (Map<String, Object>) detailResponse.getBody().get("data");
    assertThat(detail.get("companyCode")).isEqualTo(COMPANY_CODE);
    assertThat(detail.get("lifecycleState")).isEqualTo("SUSPENDED");

    ResponseEntity<Map> deactivateResponse =
        rest.exchange(
            "/api/v1/superadmin/tenants/" + tenant.getId() + "/lifecycle",
            HttpMethod.PUT,
            new HttpEntity<>(
                Map.of("state", "DEACTIVATED", "reason", "security-incident"), superAdminHeaders),
            Map.class);
    assertThat(deactivateResponse.getStatusCode()).isEqualTo(HttpStatus.OK);
    assertThat(readLifecycleState(tenant.getId())).isEqualTo("DEACTIVATED");

    ResponseEntity<Map> activateResponse =
        rest.exchange(
            "/api/v1/superadmin/tenants/" + tenant.getId() + "/lifecycle",
            HttpMethod.PUT,
            new HttpEntity<>(Map.of("state", "ACTIVE", "reason", "recovered"), superAdminHeaders),
            Map.class);
    assertThat(activateResponse.getStatusCode()).isEqualTo(HttpStatus.OK);
    assertThat(readLifecycleState(tenant.getId())).isEqualTo("ACTIVE");
  }

  @Test
  void superAdmin_lifecycle_update_rejects_retired_legacy_states() {
    Company tenant = companyRepository.findByCodeIgnoreCase(COMPANY_CODE).orElseThrow();
    String superAdminToken = loginToken(SUPER_ADMIN_EMAIL, ROOT_COMPANY_CODE);
    HttpHeaders superAdminHeaders = headers(superAdminToken, ROOT_COMPANY_CODE);

    ResponseEntity<Map> holdResponse =
        rest.exchange(
            "/api/v1/superadmin/tenants/" + tenant.getId() + "/lifecycle",
            HttpMethod.PUT,
            new HttpEntity<>(Map.of("state", "HOLD", "reason", "legacy-client"), superAdminHeaders),
            Map.class);

    assertThat(holdResponse.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    assertThat(readLifecycleState(tenant.getId())).isEqualTo("ACTIVE");

    ResponseEntity<Map> blockedResponse =
        rest.exchange(
            "/api/v1/superadmin/tenants/" + tenant.getId() + "/lifecycle",
            HttpMethod.PUT,
            new HttpEntity<>(
                Map.of("state", "BLOCKED", "reason", "legacy-client"), superAdminHeaders),
            Map.class);

    assertThat(blockedResponse.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    assertThat(readLifecycleState(tenant.getId())).isEqualTo("ACTIVE");
  }

  @Test
  void superAdmin_canConfigureTenantModules_andLimits() {
    Company tenant = companyRepository.findByCodeIgnoreCase(COMPANY_CODE).orElseThrow();
    String superAdminToken = loginToken(SUPER_ADMIN_EMAIL, ROOT_COMPANY_CODE);
    HttpHeaders superAdminHeaders = headers(superAdminToken, ROOT_COMPANY_CODE);

    ResponseEntity<Map> modulesResponse =
        rest.exchange(
            "/api/v1/superadmin/tenants/" + tenant.getId() + "/modules",
            HttpMethod.PUT,
            new HttpEntity<>(Map.of("enabledModules", List.of("PORTAL")), superAdminHeaders),
            Map.class);
    assertThat(modulesResponse.getStatusCode()).isEqualTo(HttpStatus.OK);
    Company updatedModules = companyRepository.findById(tenant.getId()).orElseThrow();
    assertThat(updatedModules.getEnabledModules()).containsExactly("PORTAL");

    ResponseEntity<Map> limitsResponse =
        rest.exchange(
            "/api/v1/superadmin/tenants/" + tenant.getId() + "/limits",
            HttpMethod.PUT,
            new HttpEntity<>(
                Map.of(
                    "quotaMaxActiveUsers", 120,
                    "quotaMaxApiRequests", 3000,
                    "quotaMaxStorageBytes", 2_097_152,
                    "quotaMaxConcurrentRequests", 7,
                    "quotaSoftLimitEnabled", true,
                    "quotaHardLimitEnabled", false),
                superAdminHeaders),
            Map.class);
    assertThat(limitsResponse.getStatusCode()).isEqualTo(HttpStatus.OK);
    @SuppressWarnings("unchecked")
    Map<String, Object> limits = (Map<String, Object>) limitsResponse.getBody().get("data");
    assertThat(limits.get("quotaMaxConcurrentRequests")).isEqualTo(7);
  }

  private HttpHeaders headers(String token, String companyCode) {
    HttpHeaders headers = new HttpHeaders();
    headers.setBearerAuth(token);
    headers.setContentType(MediaType.APPLICATION_JSON);
    headers.set("X-Company-Code", companyCode);
    return headers;
  }

  private String loginToken(String email, String companyCode) {
    Map<String, Object> request =
        Map.of(
            "email", email,
            "password", PASSWORD,
            "companyCode", companyCode);
    ResponseEntity<Map> response = rest.postForEntity("/api/v1/auth/login", request, Map.class);
    return (String) response.getBody().get("accessToken");
  }

  private String readLifecycleState(Long companyId) {
    return jdbcTemplate.queryForObject(
        "select lifecycle_state from companies where id = ?", String.class, companyId);
  }
}
