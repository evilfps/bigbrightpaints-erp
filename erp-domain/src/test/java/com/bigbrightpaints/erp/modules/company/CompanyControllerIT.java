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

import com.bigbrightpaints.erp.core.security.AuthScopeService;
import com.bigbrightpaints.erp.modules.auth.domain.UserAccountRepository;
import com.bigbrightpaints.erp.modules.company.domain.CompanyLifecycleState;
import com.bigbrightpaints.erp.modules.company.domain.CompanyRepository;
import com.bigbrightpaints.erp.test.AbstractIntegrationTest;

class CompanyControllerIT extends AbstractIntegrationTest {

  private static final String COMPANY_CODE = "ACME";
  private static final String ROOT_COMPANY_CODE = "ROOT";
  private static final String ADMIN_EMAIL = "admin@bbp.com";
  private static final String SUPER_ADMIN_EMAIL = "super-admin@bbp.com";
  private static final String HIERARCHY_SUPER_ADMIN_EMAIL = "super-admin-hierarchy@bbp.com";
  private static final String ADMIN_PASSWORD = "admin123";

  @Autowired private TestRestTemplate rest;

  @Autowired private CompanyRepository companyRepository;

  @Autowired private UserAccountRepository userAccountRepository;

  @BeforeEach
  void seed() {
    dataSeeder.ensureUser(
        ADMIN_EMAIL, ADMIN_PASSWORD, "Admin", COMPANY_CODE, List.of("ROLE_ADMIN"));
    dataSeeder.ensureUser(
        SUPER_ADMIN_EMAIL,
        ADMIN_PASSWORD,
        "Super Admin",
        ROOT_COMPANY_CODE,
        List.of("ROLE_SUPER_ADMIN", "ROLE_ADMIN"));
    dataSeeder.ensureUser(
        HIERARCHY_SUPER_ADMIN_EMAIL,
        ADMIN_PASSWORD,
        "Hierarchy Super Admin",
        COMPANY_CODE,
        List.of("ROLE_SUPER_ADMIN"));
    updateUserState(ADMIN_EMAIL, COMPANY_CODE);
    updateUserState(SUPER_ADMIN_EMAIL, ROOT_COMPANY_CODE);
    updateUserState(HIERARCHY_SUPER_ADMIN_EMAIL, COMPANY_CODE);
    companyRepository
        .findByCodeIgnoreCase(COMPANY_CODE)
        .ifPresent(
            company -> {
              company.setLifecycleState(CompanyLifecycleState.ACTIVE);
              company.setLifecycleReason(null);
              companyRepository.save(company);
            });
    companyRepository
        .findByCodeIgnoreCase(ROOT_COMPANY_CODE)
        .ifPresent(
            company -> {
              company.setLifecycleState(CompanyLifecycleState.ACTIVE);
              company.setLifecycleReason(null);
              companyRepository.save(company);
            });
  }

  @Test
  void list_companies_as_admin_only() {
    ResponseEntity<Map> listResp =
        rest.exchange(
            "/api/v1/companies",
            HttpMethod.GET,
            new HttpEntity<>(jsonHeaders(loginToken(ADMIN_EMAIL, COMPANY_CODE), COMPANY_CODE)),
            Map.class);

    assertThat(listResp.getStatusCode()).isEqualTo(HttpStatus.OK);
    assertThat(listResp.getBody()).isNotNull();
    @SuppressWarnings("unchecked")
    List<Map<String, Object>> companies =
        (List<Map<String, Object>>) listResp.getBody().get("data");
    assertThat(companies)
        .extracting(company -> company.get("code").toString().toUpperCase(Locale.ROOT))
        .containsExactly(COMPANY_CODE);
  }

  @Test
  void list_companies_is_denied_for_tenant_scoped_super_admin_without_admin_role_assignment() {
    ResponseEntity<Map> listResp =
        rest.exchange(
            "/api/v1/companies",
            HttpMethod.GET,
            new HttpEntity<>(
                jsonHeaders(loginToken(HIERARCHY_SUPER_ADMIN_EMAIL, COMPANY_CODE), COMPANY_CODE)),
            Map.class);

    assertThat(listResp.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
    assertPlatformOnlyDenial(listResp);
  }

  @Test
  void list_companies_is_retired_for_root_only_super_admin_and_superadmin_tenants_remains_canonical() {
    String rootOnlySuperAdminEmail = "root-list-super-admin@bbp.com";
    dataSeeder.ensureUser(
        rootOnlySuperAdminEmail,
        ADMIN_PASSWORD,
        "Root List Super Admin",
        ROOT_COMPANY_CODE,
        List.of("ROLE_SUPER_ADMIN"));
    String token = loginToken(rootOnlySuperAdminEmail, ROOT_COMPANY_CODE);

    ResponseEntity<Map> listResp =
        rest.exchange(
            "/api/v1/companies",
            HttpMethod.GET,
            new HttpEntity<>(jsonHeaders(token, ROOT_COMPANY_CODE)),
            Map.class);

    assertThat(listResp.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
    assertPlatformOnlyDenial(listResp);

    ResponseEntity<Map> canonicalTenantsResp =
        rest.exchange(
            "/api/v1/superadmin/tenants",
            HttpMethod.GET,
            new HttpEntity<>(jsonHeaders(token, ROOT_COMPANY_CODE)),
            Map.class);

    assertThat(canonicalTenantsResp.getStatusCode()).isEqualTo(HttpStatus.OK);
    assertThat(canonicalTenantsResp.getBody()).isNotNull();
    @SuppressWarnings("unchecked")
    List<Map<String, Object>> tenants =
        (List<Map<String, Object>>) canonicalTenantsResp.getBody().get("data");
    assertThat(tenants)
        .extracting(tenant -> tenant.get("companyCode").toString().toUpperCase(Locale.ROOT))
        .contains(ROOT_COMPANY_CODE, COMPANY_CODE);
  }

  @Test
  void list_companies_is_denied_for_platform_scoped_super_admin() {
    String platformOnlySuperAdminEmail = "platform-list-super-admin@bbp.com";
    dataSeeder.ensureUser(
        platformOnlySuperAdminEmail,
        ADMIN_PASSWORD,
        "Platform List Super Admin",
        AuthScopeService.DEFAULT_PLATFORM_AUTH_CODE,
        List.of("ROLE_SUPER_ADMIN"));

    ResponseEntity<Map> listResp =
        rest.exchange(
            "/api/v1/companies",
            HttpMethod.GET,
            new HttpEntity<>(
                jsonHeaders(
                    loginToken(platformOnlySuperAdminEmail, AuthScopeService.DEFAULT_PLATFORM_AUTH_CODE),
                    AuthScopeService.DEFAULT_PLATFORM_AUTH_CODE)),
            Map.class);

    assertThat(listResp.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
    assertThat(listResp.getBody()).isNotNull();
    @SuppressWarnings("unchecked")
    Map<String, Object> error = (Map<String, Object>) listResp.getBody().get("data");
    assertThat(error).isNotNull();
    assertThat(error.get("reason")).isEqualTo("SUPER_ADMIN_PLATFORM_ONLY");
    assertThat(error.get("reasonDetail"))
        .isEqualTo(
            "Super Admin is limited to platform control-plane operations and cannot execute tenant"
                + " business workflows");
  }

  @Test
  void delete_company_endpoint_is_not_exposed() {
    Long companyId = companyRepository.findByCodeIgnoreCase(COMPANY_CODE).orElseThrow().getId();

    ResponseEntity<Map> response =
        rest.exchange(
            "/api/v1/companies/" + companyId,
            HttpMethod.DELETE,
            new HttpEntity<>(jsonHeaders(loginToken(ADMIN_EMAIL, COMPANY_CODE), COMPANY_CODE)),
            Map.class);

    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
  }

  @Test
  void retired_company_control_plane_aliases_are_not_exposed() {
    Long companyId = companyRepository.findByCodeIgnoreCase(COMPANY_CODE).orElseThrow().getId();
    HttpHeaders headers =
        jsonHeaders(loginToken(SUPER_ADMIN_EMAIL, ROOT_COMPANY_CODE), ROOT_COMPANY_CODE);

    ResponseEntity<Map> dashboardResponse =
        rest.exchange(
            "/api/v1/companies/superadmin/dashboard",
            HttpMethod.GET,
            new HttpEntity<>(headers),
            Map.class);
    ResponseEntity<Map> supportResetResponse =
        rest.exchange(
            "/api/v1/companies/" + companyId + "/support/admin-password-reset",
            HttpMethod.POST,
            new HttpEntity<>(Map.of("adminEmail", ADMIN_EMAIL), headers),
            Map.class);
    ResponseEntity<Map> metricsResponse =
        rest.exchange(
            "/api/v1/companies/" + companyId + "/tenant-metrics",
            HttpMethod.GET,
            new HttpEntity<>(headers),
            Map.class);
    ResponseEntity<Map> limitsResponse =
        rest.exchange(
            "/api/v1/companies/" + companyId + "/tenant-runtime/policy",
            HttpMethod.PUT,
            new HttpEntity<>(Map.of("maxActiveUsers", 1), headers),
            Map.class);

    assertThat(dashboardResponse.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    assertThat(supportResetResponse.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    assertThat(metricsResponse.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    assertThat(limitsResponse.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
  }

  @Test
  void retired_company_control_plane_aliases_fail_closed_for_platform_scoped_super_admin() {
    String platformOnlySuperAdminEmail = "platform-alias-probe-super-admin@bbp.com";
    dataSeeder.ensureUser(
        platformOnlySuperAdminEmail,
        ADMIN_PASSWORD,
        "Platform Alias Probe Super Admin",
        AuthScopeService.DEFAULT_PLATFORM_AUTH_CODE,
        List.of("ROLE_SUPER_ADMIN"));
    Long companyId = companyRepository.findByCodeIgnoreCase(COMPANY_CODE).orElseThrow().getId();
    HttpHeaders headers =
        jsonHeaders(
            loginToken(platformOnlySuperAdminEmail, AuthScopeService.DEFAULT_PLATFORM_AUTH_CODE),
            AuthScopeService.DEFAULT_PLATFORM_AUTH_CODE);

    ResponseEntity<Map> dashboardResponse =
        rest.exchange(
            "/api/v1/companies/superadmin/dashboard",
            HttpMethod.GET,
            new HttpEntity<>(headers),
            Map.class);
    ResponseEntity<Map> supportResetResponse =
        rest.exchange(
            "/api/v1/companies/" + companyId + "/support/admin-password-reset",
            HttpMethod.POST,
            new HttpEntity<>(Map.of("adminEmail", ADMIN_EMAIL), headers),
            Map.class);
    ResponseEntity<Map> metricsResponse =
        rest.exchange(
            "/api/v1/companies/" + companyId + "/tenant-metrics",
            HttpMethod.GET,
            new HttpEntity<>(headers),
            Map.class);
    ResponseEntity<Map> limitsResponse =
        rest.exchange(
            "/api/v1/companies/" + companyId + "/tenant-runtime/policy",
            HttpMethod.PUT,
            new HttpEntity<>(Map.of("maxActiveUsers", 1), headers),
            Map.class);

    assertThat(dashboardResponse.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    assertThat(supportResetResponse.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    assertThat(metricsResponse.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    assertThat(limitsResponse.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
  }

  @SuppressWarnings("unchecked")
  private void assertPlatformOnlyDenial(ResponseEntity<Map> response) {
    assertThat(response.getBody()).isNotNull();
    assertThat(response.getBody().get("success")).isEqualTo(Boolean.FALSE);
    Map<String, Object> error = (Map<String, Object>) response.getBody().get("data");
    assertThat(error).isNotNull();
    assertThat(error.get("code")).isEqualTo("AUTH_004");
    assertThat(error.get("reason")).isEqualTo("SUPER_ADMIN_PLATFORM_ONLY");
    assertThat(error.get("reasonDetail"))
        .isEqualTo(
            "Super Admin is limited to platform control-plane operations and cannot execute tenant"
                + " business workflows");
  }

  private HttpHeaders jsonHeaders(String token, String companyCode) {
    HttpHeaders headers = new HttpHeaders();
    headers.setBearerAuth(token);
    headers.setContentType(MediaType.APPLICATION_JSON);
    headers.set("X-Company-Code", companyCode);
    return headers;
  }

  private String loginToken(String email, String companyCode) {
    ResponseEntity<Map> response =
        rest.postForEntity(
            "/api/v1/auth/login",
            Map.of("email", email, "password", ADMIN_PASSWORD, "companyCode", companyCode),
            Map.class);
    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
    assertThat(response.getBody()).isNotNull();
    return response.getBody().get("accessToken").toString();
  }

  private void updateUserState(String email, String companyCode) {
    userAccountRepository
        .findByEmailIgnoreCaseAndAuthScopeCodeIgnoreCase(email, companyCode)
        .ifPresent(
            user -> {
              user.setMustChangePassword(false);
              user.setEnabled(true);
              userAccountRepository.save(user);
            });
  }
}
