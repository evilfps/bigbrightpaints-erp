package com.bigbrightpaints.erp.e2e.accounting;

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
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;

import com.bigbrightpaints.erp.test.AbstractIntegrationTest;

@DisplayName("E2E: Period closing hard-cut")
class PeriodCloseLockIT extends AbstractIntegrationTest {

  private static final String COMPANY_CODE = "PERIOD";
  private static final String ADMIN_EMAIL = "period-admin@bbp.com";
  private static final String ADMIN_PASSWORD = "period123";

  @Autowired private TestRestTemplate rest;

  private HttpHeaders headers;

  @BeforeEach
  void setup() {
    dataSeeder.ensureUser(
        ADMIN_EMAIL, ADMIN_PASSWORD, "Period Admin", COMPANY_CODE, List.of("ROLE_ADMIN"));
    headers = authHeaders(ADMIN_EMAIL, ADMIN_PASSWORD, COMPANY_CODE);
  }

  @Test
  @DisplayName("Direct close endpoint is retired from the public API")
  void directCloseEndpointIsRetired() {
    ResponseEntity<String> closeResp =
        rest.exchange(
            "/api/v1/accounting/periods/999/close",
            HttpMethod.POST,
            new HttpEntity<>("{\"note\":\"Month close\",\"force\":true}", headers),
            String.class);

    assertThat(closeResp.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
  }

  private HttpHeaders authHeaders(String email, String password, String companyCode) {
    Map<String, Object> req =
        Map.of(
            "email", email,
            "password", password,
            "companyCode", companyCode);
    ResponseEntity<Map> login = rest.postForEntity("/api/v1/auth/login", req, Map.class);
    assertThat(login.getStatusCode()).isEqualTo(HttpStatus.OK);
    String token = (String) login.getBody().get("accessToken");
    assertThat(token).isNotBlank();
    HttpHeaders h = new HttpHeaders();
    h.setBearerAuth(token);
    h.setContentType(MediaType.APPLICATION_JSON);
    h.set("X-Company-Code", companyCode);
    return h;
  }
}
