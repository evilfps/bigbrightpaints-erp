package com.bigbrightpaints.erp.modules.inventory.controller;

import static org.assertj.core.api.Assertions.assertThat;

import com.bigbrightpaints.erp.test.AbstractIntegrationTest;
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
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;

class RawMaterialControllerSecurityIT extends AbstractIntegrationTest {

    private static final String COMPANY_CODE = "RAWMAT-SEC";
    private static final String PASSWORD = "changeme";
    private static final String ADMIN_EMAIL = "rawmat-admin@bbp.com";
    private static final String SUPER_ADMIN_EMAIL = "rawmat-superadmin@bbp.com";
    private static final String PLATFORM_ONLY_MESSAGE =
            "Super Admin is limited to platform control-plane operations and cannot execute tenant business workflows";

    @Autowired
    private TestRestTemplate rest;

    @BeforeEach
    void setUpUsers() {
        dataSeeder.ensureUser(ADMIN_EMAIL, PASSWORD, "Raw Material Admin", COMPANY_CODE, List.of("ROLE_ADMIN"));
        dataSeeder.ensureUser(SUPER_ADMIN_EMAIL, PASSWORD, "Raw Material Super Admin", COMPANY_CODE,
                List.of("ROLE_SUPER_ADMIN"));
    }

    @Test
    void admin_canReachRawMaterialIntakeWorkflowValidation() {
        ResponseEntity<Map> response = rest.exchange(
                "/api/v1/raw-materials/intake",
                HttpMethod.POST,
                new HttpEntity<>(Map.of(
                        "rawMaterialId", 999999,
                        "quantity", 5,
                        "unit", "KG",
                        "costPerUnit", 12.50,
                        "notes", "validation-path-check"
                ), jsonHeaders(ADMIN_EMAIL)),
                Map.class
        );

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertNotPlatformOnly(response);
    }

    @Test
    void superAdmin_cannotExecuteRawMaterialIntakeWorkflowInsideTenant() {
        ResponseEntity<Map> response = rest.exchange(
                "/api/v1/raw-materials/intake",
                HttpMethod.POST,
                new HttpEntity<>(Map.of(
                        "rawMaterialId", 999999,
                        "quantity", 5,
                        "unit", "KG",
                        "costPerUnit", 12.50,
                        "notes", "platform-only-super-admin"
                ), jsonHeaders(SUPER_ADMIN_EMAIL)),
                Map.class
        );

        assertPlatformOnlyForbidden(response);
    }

    @Test
    void admin_canReachRawMaterialBatchWorkflowValidation() {
        HttpHeaders headers = jsonHeaders(ADMIN_EMAIL);
        headers.set("Idempotency-Key", "rawmat-admin-batch-validation");

        ResponseEntity<Map> response = rest.exchange(
                "/api/v1/raw-material-batches/999999",
                HttpMethod.POST,
                new HttpEntity<>(Map.of(
                        "batchCode", "RM-VALIDATION",
                        "quantity", 3,
                        "unit", "KG",
                        "costPerUnit", 9.25,
                        "notes", "validation-path-check"
                ), headers),
                Map.class
        );

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertNotPlatformOnly(response);
    }

    @Test
    void superAdmin_cannotExecuteRawMaterialBatchWorkflowInsideTenant() {
        HttpHeaders headers = jsonHeaders(SUPER_ADMIN_EMAIL);
        headers.set("Idempotency-Key", "rawmat-superadmin-batch-blocked");

        ResponseEntity<Map> response = rest.exchange(
                "/api/v1/raw-material-batches/999999",
                HttpMethod.POST,
                new HttpEntity<>(Map.of(
                        "batchCode", "RM-SUPERADMIN",
                        "quantity", 3,
                        "unit", "KG",
                        "costPerUnit", 9.25,
                        "notes", "platform-only-super-admin"
                ), headers),
                Map.class
        );

        assertPlatformOnlyForbidden(response);
    }

    private HttpHeaders jsonHeaders(String email) {
        HttpHeaders headers = authHeaders(email);
        headers.setContentType(MediaType.APPLICATION_JSON);
        return headers;
    }

    private HttpHeaders authHeaders(String email) {
        ResponseEntity<Map> login = rest.postForEntity(
                "/api/v1/auth/login",
                Map.of(
                        "email", email,
                        "password", PASSWORD,
                        "companyCode", COMPANY_CODE
                ),
                Map.class);

        assertThat(login.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(login.getBody()).isNotNull();

        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth((String) login.getBody().get("accessToken"));
        headers.set("X-Company-Code", COMPANY_CODE);
        return headers;
    }

    @SuppressWarnings("unchecked")
    private void assertPlatformOnlyForbidden(ResponseEntity<Map> response) {
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().get("message")).isEqualTo("Access denied");
        Map<String, Object> data = (Map<String, Object>) response.getBody().get("data");
        assertThat(data).isNotNull();
        assertThat(data.get("reason")).isEqualTo("SUPER_ADMIN_PLATFORM_ONLY");
        assertThat(data.get("reasonDetail")).isEqualTo(PLATFORM_ONLY_MESSAGE);
    }

    @SuppressWarnings("unchecked")
    private void assertNotPlatformOnly(ResponseEntity<Map> response) {
        assertThat(response.getBody()).isNotNull();
        Map<String, Object> data = (Map<String, Object>) response.getBody().get("data");
        if (data != null) {
            assertThat(data.get("reason")).isNotEqualTo("SUPER_ADMIN_PLATFORM_ONLY");
            assertThat(data.get("reasonDetail")).isNotEqualTo(PLATFORM_ONLY_MESSAGE);
        }
    }
}
