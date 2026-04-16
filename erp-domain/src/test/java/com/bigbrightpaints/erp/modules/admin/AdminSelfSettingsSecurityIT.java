package com.bigbrightpaints.erp.modules.admin;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;

import com.bigbrightpaints.erp.test.AbstractIntegrationTest;

@Tag("critical")
class AdminSelfSettingsSecurityIT extends AbstractIntegrationTest {

  private static final String COMPANY_CODE = "ADMIN-SELF";
  private static final String PASSWORD = "AdminSelf123!";
  private static final String ADMIN_EMAIL = "self-admin@bbp.com";
  private static final String ACCOUNTING_EMAIL = "self-accounting@bbp.com";
  private static final String SUPER_ADMIN_EMAIL = "self-superadmin@bbp.com";

  @Autowired private TestRestTemplate rest;

  @BeforeEach
  void setUpUsers() {
    dataSeeder.ensureUser(ADMIN_EMAIL, PASSWORD, "Self Admin", COMPANY_CODE, List.of("ROLE_ADMIN"));
    dataSeeder.ensureUser(
        ACCOUNTING_EMAIL, PASSWORD, "Self Accounting", COMPANY_CODE, List.of("ROLE_ACCOUNTING"));
    dataSeeder.ensureUser(
        SUPER_ADMIN_EMAIL,
        PASSWORD,
        "Self Super Admin",
        COMPANY_CODE,
        List.of("ROLE_SUPER_ADMIN", "ROLE_ADMIN"));
  }

  @Test
  void selfSettings_allows_only_tenant_admin_role() {
    ResponseEntity<Map> adminResponse =
        rest.exchange(
            "/api/v1/admin/self/settings",
            HttpMethod.GET,
            new HttpEntity<>(headersFor(ADMIN_EMAIL)),
            Map.class);
    assertThat(adminResponse.getStatusCode()).isEqualTo(HttpStatus.OK);
    assertThat(adminResponse.getBody()).isNotNull();
    @SuppressWarnings("unchecked")
    Map<String, Object> data = (Map<String, Object>) adminResponse.getBody().get("data");
    assertThat(data).isNotNull();
    assertThat(data.get("email")).isEqualTo(ADMIN_EMAIL);
    assertThat(data.get("companyCode")).isEqualTo(COMPANY_CODE);
    assertThat(data).containsKeys("tenantRuntime", "activeSessionEstimate");

    ResponseEntity<Map> accountingResponse =
        rest.exchange(
            "/api/v1/admin/self/settings",
            HttpMethod.GET,
            new HttpEntity<>(headersFor(ACCOUNTING_EMAIL)),
            Map.class);
    assertThat(accountingResponse.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);

    ResponseEntity<Map> superAdminResponse =
        rest.exchange(
            "/api/v1/admin/self/settings",
            HttpMethod.GET,
            new HttpEntity<>(headersFor(SUPER_ADMIN_EMAIL)),
            Map.class);
    assertThat(superAdminResponse.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
  }

  private HttpHeaders headersFor(String email) {
    ResponseEntity<Map> loginResponse =
        rest.postForEntity(
            "/api/v1/auth/login",
            Map.of(
                "email", email,
                "password", PASSWORD,
                "companyCode", COMPANY_CODE),
            Map.class);
    assertThat(loginResponse.getStatusCode()).isEqualTo(HttpStatus.OK);
    assertThat(loginResponse.getBody()).isNotNull();

    HttpHeaders headers = new HttpHeaders();
    headers.setContentType(MediaType.APPLICATION_JSON);
    headers.setBearerAuth(String.valueOf(loginResponse.getBody().get("accessToken")));
    headers.set("X-Company-Code", COMPANY_CODE);
    return headers;
  }
}
