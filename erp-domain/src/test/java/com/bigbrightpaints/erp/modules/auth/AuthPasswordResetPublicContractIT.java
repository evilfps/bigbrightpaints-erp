package com.bigbrightpaints.erp.modules.auth;

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

import static org.assertj.core.api.Assertions.assertThat;

class AuthPasswordResetPublicContractIT extends AbstractIntegrationTest {

    private static final String SUPERADMIN_EMAIL = "superadmin.reset.contract@bbp.com";
    private static final String SUPERADMIN_PASSWORD = "Admin@12345";
    private static final String PRIMARY_COMPANY = "RESETA";
    private static final String SECONDARY_COMPANY = "RESETB";

    @Autowired
    private TestRestTemplate rest;

    @BeforeEach
    void seedSuperAdmin() {
        dataSeeder.ensureUser(
                SUPERADMIN_EMAIL,
                SUPERADMIN_PASSWORD,
                "Reset Super Admin",
                PRIMARY_COMPANY,
                List.of("ROLE_SUPER_ADMIN"));
        dataSeeder.ensureUser(
                SUPERADMIN_EMAIL,
                SUPERADMIN_PASSWORD,
                "Reset Super Admin",
                SECONDARY_COMPANY,
                List.of("ROLE_SUPER_ADMIN"));
    }

    @Test
    void superAdminForgotEndpoint_isPublicAndAntiEnumerationSafeAcrossTenantHeaders() {
        ResponseEntity<Map> knownUserResponse = postSuperAdminForgot(SUPERADMIN_EMAIL, "ANY-TENANT");
        ResponseEntity<Map> unknownUserResponse = postSuperAdminForgot("unknown.superadmin@bbp.com", "OTHER-TENANT");

        assertThat(knownUserResponse.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(unknownUserResponse.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(knownUserResponse.getBody()).isNotNull();
        assertThat(unknownUserResponse.getBody()).isNotNull();
        assertThat(knownUserResponse.getBody().get("success")).isEqualTo(true);
        assertThat(unknownUserResponse.getBody().get("success")).isEqualTo(true);
        assertThat(knownUserResponse.getBody().get("message"))
                .isEqualTo("If the email exists, a reset link has been sent");
        assertThat(unknownUserResponse.getBody().get("message"))
                .isEqualTo("If the email exists, a reset link has been sent");
    }

    @Test
    void resetEndpoint_usesTokenValidationNotTenantContextForFailureDecision() {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.set("X-Company-Code", "UNRELATED-TENANT");
        Map<String, Object> payload = Map.of(
                "token", "missing-token",
                "newPassword", "NewPass123",
                "confirmPassword", "NewPass123");

        ResponseEntity<Map> response = rest.exchange(
                "/api/v1/auth/password/reset",
                HttpMethod.POST,
                new HttpEntity<>(payload, headers),
                Map.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().get("message")).isEqualTo("Invalid or expired token");
    }

    private ResponseEntity<Map> postSuperAdminForgot(String email, String companyCodeHeader) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.set("X-Company-Code", companyCodeHeader);
        Map<String, Object> payload = Map.of("email", email);

        return rest.exchange(
                "/api/v1/auth/password/forgot/superadmin",
                HttpMethod.POST,
                new HttpEntity<>(payload, headers),
                Map.class);
    }
}
