package com.bigbrightpaints.erp.codered;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;

import com.bigbrightpaints.erp.test.AbstractIntegrationTest;

@ActiveProfiles(
    value = {"test", "prod"},
    inheritProfiles = false)
@TestPropertySource(
    properties = {
      "jwt.secret=placeholder-placeholder-placeholder-000000",
      "spring.mail.host=localhost",
      "spring.mail.port=2525",
      "spring.mail.username=test-smtp-user",
      "spring.mail.password=test-smtp-password",
      "ERP_LICENSE_KEY=test-license-key",
      "ERP_SECURITY_AUDIT_PRIVATE_KEY=test-audit-signing-key",
      "ERP_SECURITY_ENCRYPTION_KEY=12345678901234567890123456789012",
      "ERP_DISPATCH_DEBIT_ACCOUNT_ID=1",
      "ERP_DISPATCH_CREDIT_ACCOUNT_ID=2",
      "management.endpoint.health.validate-group-membership=false",
      "erp.environment.validation.enabled=false"
    })
class CR_HealthEndpointProdHardeningIT extends AbstractIntegrationTest {

  private static final String COMPANY_CODE = "ACME";
  private static final String ADMIN_EMAIL = "admin-health@bbp.com";
  private static final String ADMIN_PASSWORD = "admin123";
  private static final String SALES_EMAIL = "sales-health@bbp.com";
  private static final String SALES_PASSWORD = "sales123";

  @Autowired private TestRestTemplate rest;

  @BeforeEach
  void seedUsers() {
    dataSeeder.ensureUser(
        ADMIN_EMAIL, ADMIN_PASSWORD, "Admin", COMPANY_CODE, List.of("ROLE_ADMIN"));
    dataSeeder.ensureUser(
        SALES_EMAIL, SALES_PASSWORD, "Sales", COMPANY_CODE, List.of("ROLE_SALES"));
  }

  @Test
  @DisplayName("Prod blocks unauthenticated health endpoints")
  void prodBlocksUnauthenticatedHealthEndpoints() {
    assertBlocked("/api/v1/orchestrator/health/integrations");
    assertBlocked("/api/v1/orchestrator/health/events");
    assertBlocked("/api/integration/health");
  }

  @Test
  @DisplayName("Prod blocks non-admin health access")
  void prodBlocksNonAdminHealthAccess() {
    HttpHeaders headers = authHeaders(loginToken(SALES_EMAIL, SALES_PASSWORD));
    assertForbidden("/api/v1/orchestrator/health/integrations", headers);
    assertForbidden("/api/v1/orchestrator/health/events", headers);
    assertForbidden("/api/integration/health", headers);
  }

  @Test
  @DisplayName("Prod allows admin health access")
  void prodAllowsAdminHealthAccess() {
    HttpHeaders headers = authHeaders(loginToken(ADMIN_EMAIL, ADMIN_PASSWORD));
    assertOk("/api/v1/orchestrator/health/integrations", headers);
    assertOk("/api/v1/orchestrator/health/events", headers);
    assertOk("/api/integration/health", headers);
  }

  private void assertBlocked(String path) {
    ResponseEntity<String> response = rest.getForEntity(path, String.class);
    assertThat(response.getStatusCode()).isIn(HttpStatus.UNAUTHORIZED, HttpStatus.FORBIDDEN);
  }

  private void assertForbidden(String path, HttpHeaders headers) {
    ResponseEntity<String> response =
        rest.exchange(
            path, org.springframework.http.HttpMethod.GET, new HttpEntity<>(headers), String.class);
    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
  }

  private void assertOk(String path, HttpHeaders headers) {
    ResponseEntity<String> response =
        rest.exchange(
            path, org.springframework.http.HttpMethod.GET, new HttpEntity<>(headers), String.class);
    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
  }

  private String loginToken(String email, String password) {
    Map<String, Object> req =
        Map.of(
            "email", email,
            "password", password,
            "companyCode", COMPANY_CODE);
    return (String)
        rest.postForEntity("/api/v1/auth/login", req, Map.class).getBody().get("accessToken");
  }

  private HttpHeaders authHeaders(String token) {
    HttpHeaders headers = new HttpHeaders();
    headers.setBearerAuth(token);
    return headers;
  }
}
