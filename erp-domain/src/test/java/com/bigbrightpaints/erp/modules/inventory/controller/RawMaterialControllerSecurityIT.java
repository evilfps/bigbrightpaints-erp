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
    void admin_canReachRawMaterialAdjustmentWorkflowValidation() {
        HttpHeaders headers = jsonHeaders(ADMIN_EMAIL);
        headers.set("Idempotency-Key", "rawmat-admin-adjustment-validation");

        ResponseEntity<Map> response = rest.exchange(
                "/api/v1/inventory/raw-materials/adjustments",
                HttpMethod.POST,
                new HttpEntity<>(Map.of(
                        "adjustmentDate", "2026-03-22",
                        "direction", "OUT",
                        "adjustmentAccountId", 999999,
                        "reason", "validation-path-check",
                        "lines", List.of(Map.of(
                                "rawMaterialId", 999999,
                                "quantity", 3,
                                "unitCost", 9.25
                        ))
                ), headers),
                Map.class
        );

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertNotPlatformOnly(response);
    }

    @Test
    void superAdmin_cannotExecuteRawMaterialAdjustmentWorkflowInsideTenant() {
        HttpHeaders headers = jsonHeaders(SUPER_ADMIN_EMAIL);
        headers.set("Idempotency-Key", "rawmat-superadmin-adjustment-blocked");

        ResponseEntity<Map> response = rest.exchange(
                "/api/v1/inventory/raw-materials/adjustments",
                HttpMethod.POST,
                new HttpEntity<>(Map.of(
                        "adjustmentDate", "2026-03-22",
                        "direction", "OUT",
                        "adjustmentAccountId", 999999,
                        "reason", "platform-only-super-admin",
                        "lines", List.of(Map.of(
                                "rawMaterialId", 999999,
                                "quantity", 3,
                                "unitCost", 9.25
                        ))
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
