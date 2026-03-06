package com.bigbrightpaints.erp.modules.auth;

import com.bigbrightpaints.erp.core.notification.EmailService;
import com.bigbrightpaints.erp.modules.auth.domain.PasswordResetToken;
import com.bigbrightpaints.erp.modules.auth.domain.PasswordResetTokenRepository;
import com.bigbrightpaints.erp.test.AbstractIntegrationTest;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.mock.mockito.SpyBean;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.doAnswer;

class AuthPasswordResetPublicContractIT extends AbstractIntegrationTest {

    private static final String SUPERADMIN_EMAIL = "superadmin.reset.contract@bbp.com";
    private static final String SUPERADMIN_PASSWORD = "Admin@12345";
    private static final String PRIMARY_COMPANY = "RESETA";
    private static final String SECONDARY_COMPANY = "RESETB";

    @Autowired
    private TestRestTemplate rest;

    @SpyBean
    private EmailService emailService;

    @SpyBean
    private PasswordResetTokenRepository passwordResetTokenRepository;

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
    void forgotEndpoint_isPublicAndAntiEnumerationSafeAcrossTenantHeaders() {
        ResponseEntity<Map> knownUserResponse = postForgot(SUPERADMIN_EMAIL, "ANY-TENANT");
        ResponseEntity<Map> unknownUserResponse = postForgot("unknown.superadmin@bbp.com", "OTHER-TENANT");

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

    @Test
    void retiredSuperAdminForgotAlias_returnsControlledCompatibilityContractAcrossTenantHeaders() {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.set("X-Company-Code", "ANY-TENANT");

        ResponseEntity<Map> response = rest.exchange(
                "/api/v1/auth/password/forgot/superadmin",
                HttpMethod.POST,
                new HttpEntity<>(Map.of("email", SUPERADMIN_EMAIL), headers),
                Map.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.GONE);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().get("success")).isEqualTo(false);
        assertThat(response.getBody().get("message"))
                .isEqualTo("Deprecated super-admin forgot-password alias has been retired; use the supported recovery routes");
        @SuppressWarnings("unchecked")
        Map<String, Object> data = (Map<String, Object>) response.getBody().get("data");
        assertThat(data).isNotNull();
        assertThat(data.get("canonicalPath")).isEqualTo("/api/v1/auth/password/forgot");
        assertThat(data.get("supportResetPath")).isEqualTo("/api/v1/companies/{id}/support/admin-password-reset");
    }

    @Test
    void forgotEndpoint_preservesAntiEnumerationWhenTokenPersistenceFails() {
        doThrow(new DataAccessResourceFailureException("db unavailable"))
                .when(passwordResetTokenRepository)
                .saveAndFlush(any(PasswordResetToken.class));

        ResponseEntity<Map> knownUserResponse = postForgot(SUPERADMIN_EMAIL, "ANY-TENANT");
        ResponseEntity<Map> unknownUserResponse = postForgot("unknown.superadmin@bbp.com", "ANY-TENANT");

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
    void overlappingForgotRequests_leaveLatestResetLinkUsable() throws Exception {
        CountDownLatch bothEmailsQueued = new CountDownLatch(2);
        List<String> deliveredTokens = Collections.synchronizedList(new ArrayList<>());
        doAnswer(invocation -> {
            deliveredTokens.add(invocation.getArgument(2, String.class));
            bothEmailsQueued.countDown();
            assertThat(bothEmailsQueued.await(5, TimeUnit.SECONDS)).isTrue();
            return null;
        }).when(emailService).sendPasswordResetEmailRequired(eq(SUPERADMIN_EMAIL), eq("Reset Super Admin"), anyString());

        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            Future<ResponseEntity<Map>> first = executor.submit(() -> postForgot(SUPERADMIN_EMAIL, "TENANT-A"));
            Future<ResponseEntity<Map>> second = executor.submit(() -> postForgot(SUPERADMIN_EMAIL, "TENANT-B"));

            assertThat(first.get(5, TimeUnit.SECONDS).getStatusCode()).isEqualTo(HttpStatus.OK);
            assertThat(second.get(5, TimeUnit.SECONDS).getStatusCode()).isEqualTo(HttpStatus.OK);
        } finally {
            executor.shutdownNow();
        }

        assertThat(deliveredTokens).hasSize(2);
        ResponseEntity<Map> staleReset = postReset(deliveredTokens.getFirst(), "NewPass123!");
        ResponseEntity<Map> latestReset = postReset(deliveredTokens.getLast(), "NewPass123!");

        assertThat(staleReset.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(staleReset.getBody()).isNotNull();
        assertThat(staleReset.getBody().get("message")).isEqualTo("Invalid or expired token");
        assertThat(latestReset.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(latestReset.getBody()).isNotNull();
        assertThat(latestReset.getBody().get("success")).isEqualTo(true);
    }

    private ResponseEntity<Map> postForgot(String email, String companyCodeHeader) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.set("X-Company-Code", companyCodeHeader);
        Map<String, Object> payload = Map.of("email", email);

        return rest.exchange(
                "/api/v1/auth/password/forgot",
                HttpMethod.POST,
                new HttpEntity<>(payload, headers),
                Map.class);
    }

    private ResponseEntity<Map> postReset(String token, String newPassword) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        return rest.exchange(
                "/api/v1/auth/password/reset",
                HttpMethod.POST,
                new HttpEntity<>(Map.of(
                        "token", token,
                        "newPassword", newPassword,
                        "confirmPassword", newPassword), headers),
                Map.class);
    }
}
