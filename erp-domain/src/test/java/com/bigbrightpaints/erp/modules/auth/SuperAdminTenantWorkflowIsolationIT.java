package com.bigbrightpaints.erp.modules.auth;

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

class SuperAdminTenantWorkflowIsolationIT extends AbstractIntegrationTest {

    private static final String TENANT = "SUPERADMIN-ISOLATION";
    private static final String SUPER_ADMIN_EMAIL = "workflow-superadmin@bbp.com";
    private static final String PASSWORD = "changeme";

    @Autowired
    private TestRestTemplate rest;

    @BeforeEach
    void setup() {
        dataSeeder.ensureUser(SUPER_ADMIN_EMAIL, PASSWORD, "Workflow Super Admin", TENANT,
                List.of("ROLE_SUPER_ADMIN"));
    }

    @Test
    void superAdmin_cannotExecuteTenantSalesTargetWorkflow() {
        ResponseEntity<Map> response = rest.exchange(
                "/api/v1/sales/targets",
                HttpMethod.POST,
                new HttpEntity<>(Map.of(
                        "name", "Blocked Target",
                        "periodStart", "2026-01-01",
                        "periodEnd", "2026-12-31",
                        "targetAmount", 125000,
                        "assignee", SUPER_ADMIN_EMAIL,
                        "changeReason", "super-admin-business-isolation"
                ), jsonHeaders()),
                Map.class
        );

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
    }

    @Test
    void superAdmin_cannotReadTenantPortalDashboard() {
        ResponseEntity<Map> response = rest.exchange(
                "/api/v1/portal/dashboard",
                HttpMethod.GET,
                new HttpEntity<>(authHeaders()),
                Map.class
        );

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
    }

    @Test
    void superAdmin_cannotApproveTenantCreditOverrideWorkflow() {
        ResponseEntity<Map> response = rest.exchange(
                "/api/v1/credit/override-requests/999999/approve",
                HttpMethod.POST,
                new HttpEntity<>(Map.of("reason", "platform-only-super-admin"), jsonHeaders()),
                Map.class
        );

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().get("message")).isEqualTo("Access denied");
        @SuppressWarnings("unchecked")
        Map<String, Object> data = (Map<String, Object>) response.getBody().get("data");
        assertThat(data).isNotNull();
        assertThat(data.get("message")).isEqualTo("Access denied");
        assertThat(data.get("reason")).isEqualTo("SUPER_ADMIN_PLATFORM_ONLY");
        assertThat(data.get("reasonDetail"))
                .isEqualTo("Super Admin is limited to platform control-plane operations and cannot execute tenant business workflows");
    }

    private HttpHeaders jsonHeaders() {
        HttpHeaders headers = authHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        return headers;
    }

    private HttpHeaders authHeaders() {
        Map<String, Object> loginPayload = Map.of(
                "email", SUPER_ADMIN_EMAIL,
                "password", PASSWORD,
                "companyCode", TENANT
        );
        ResponseEntity<Map> login = rest.postForEntity("/api/v1/auth/login", loginPayload, Map.class);
        assertThat(login.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(login.getBody()).isNotNull();

        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth((String) login.getBody().get("accessToken"));
        headers.set("X-Company-Code", TENANT);
        return headers;
    }
}
