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
class AdminUserValidationEnvelopeIT extends AbstractIntegrationTest {

  private static final String COMPANY_CODE = "ADM-VAL-ENV";
  private static final String ADMIN_EMAIL = "admin-val-env@bbp.com";
  private static final String ADMIN_PASSWORD = "Admin123!";

  @Autowired private TestRestTemplate rest;

  @BeforeEach
  void setup() {
    dataSeeder.ensureUser(
        ADMIN_EMAIL,
        ADMIN_PASSWORD,
        "Validation Envelope Admin",
        COMPANY_CODE,
        List.of("ROLE_ADMIN"));
  }

  @Test
  void createUserValidationFailuresUseDataErrorsEnvelope() {
    HttpHeaders headers = authenticatedJsonHeaders();
    Map<String, Object> invalidPayload =
        Map.of(
            "email", "invalid-email",
            "displayName", " ",
            "roles", List.of());

    ResponseEntity<Map> response =
        rest.exchange(
            "/api/v1/admin/users",
            HttpMethod.POST,
            new HttpEntity<>(invalidPayload, headers),
            Map.class);

    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    assertThat(response.getBody()).isNotNull();
    assertThat(response.getBody()).containsEntry("success", Boolean.FALSE);
    assertThat(String.valueOf(response.getBody().get("message"))).contains("Validation failed");

    @SuppressWarnings("unchecked")
    Map<String, Object> data = (Map<String, Object>) response.getBody().get("data");
    assertThat(data).isNotNull();
    assertThat(data).containsEntry("code", "VAL_001");
    assertThat(String.valueOf(data.get("reason"))).contains("Validation failed");
    assertThat(data).containsKey("errors");
    assertThat(data).doesNotContainKey("details");

    @SuppressWarnings("unchecked")
    Map<String, String> errors = (Map<String, String>) data.get("errors");
    assertThat(errors).isNotNull();
    assertThat(errors).containsKeys("email", "displayName", "roles");
    assertThat(errors).doesNotContainKey("password");
  }

  private HttpHeaders authenticatedJsonHeaders() {
    Map<String, Object> loginBody =
        Map.of(
            "email", ADMIN_EMAIL,
            "password", ADMIN_PASSWORD,
            "companyCode", COMPANY_CODE);
    ResponseEntity<Map> loginResponse =
        rest.postForEntity("/api/v1/auth/login", loginBody, Map.class);
    assertThat(loginResponse.getStatusCode()).isEqualTo(HttpStatus.OK);
    assertThat(loginResponse.getBody()).isNotNull();

    HttpHeaders headers = new HttpHeaders();
    headers.setBearerAuth(String.valueOf(loginResponse.getBody().get("accessToken")));
    headers.set("X-Company-Code", COMPANY_CODE);
    headers.setContentType(MediaType.APPLICATION_JSON);
    return headers;
  }
}
