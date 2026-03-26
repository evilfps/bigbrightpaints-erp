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
    userAccountRepository
        .findByEmailIgnoreCase(ADMIN_EMAIL)
        .ifPresent(
            user -> {
              user.setMustChangePassword(false);
              user.setEnabled(true);
              userAccountRepository.save(user);
            });
    userAccountRepository
        .findByEmailIgnoreCase(SUPER_ADMIN_EMAIL)
        .ifPresent(
            user -> {
              user.setMustChangePassword(false);
              user.setEnabled(true);
              userAccountRepository.save(user);
            });
    userAccountRepository
        .findByEmailIgnoreCase(HIERARCHY_SUPER_ADMIN_EMAIL)
        .ifPresent(
            user -> {
              user.setMustChangePassword(false);
              user.setEnabled(true);
              userAccountRepository.save(user);
            });
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
  void list_companies_allows_super_admin_without_admin_role_assignment() {
    ResponseEntity<Map> listResp =
        rest.exchange(
            "/api/v1/companies",
            HttpMethod.GET,
            new HttpEntity<>(
                jsonHeaders(loginToken(HIERARCHY_SUPER_ADMIN_EMAIL, COMPANY_CODE), COMPANY_CODE)),
            Map.class);

    assertThat(listResp.getStatusCode()).isEqualTo(HttpStatus.OK);
  }

  @Test
  void list_companies_returns_all_tenants_for_root_only_super_admin() {
    String rootOnlySuperAdminEmail = "root-list-super-admin@bbp.com";
    dataSeeder.ensureUser(
        rootOnlySuperAdminEmail,
        ADMIN_PASSWORD,
        "Root List Super Admin",
        ROOT_COMPANY_CODE,
        List.of("ROLE_SUPER_ADMIN"));

    ResponseEntity<Map> listResp =
        rest.exchange(
            "/api/v1/companies",
            HttpMethod.GET,
            new HttpEntity<>(
                jsonHeaders(
                    loginToken(rootOnlySuperAdminEmail, ROOT_COMPANY_CODE), ROOT_COMPANY_CODE)),
            Map.class);

    assertThat(listResp.getStatusCode()).isEqualTo(HttpStatus.OK);
    @SuppressWarnings("unchecked")
    List<Map<String, Object>> companies =
        (List<Map<String, Object>>) listResp.getBody().get("data");
    assertThat(companies)
        .extracting(company -> company.get("code").toString().toUpperCase(Locale.ROOT))
        .contains(ROOT_COMPANY_CODE, COMPANY_CODE);
  }

  @Test
  void delete_company_is_not_permitted_even_for_member_admin() {
    Long companyId = companyRepository.findByCodeIgnoreCase(COMPANY_CODE).orElseThrow().getId();

    ResponseEntity<Map> response =
        rest.exchange(
            "/api/v1/companies/" + companyId,
            HttpMethod.DELETE,
            new HttpEntity<>(jsonHeaders(loginToken(ADMIN_EMAIL, COMPANY_CODE), COMPANY_CODE)),
            Map.class);

    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
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
}
