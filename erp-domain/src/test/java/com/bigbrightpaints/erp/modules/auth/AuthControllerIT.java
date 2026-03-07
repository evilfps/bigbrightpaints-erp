package com.bigbrightpaints.erp.modules.auth;

import com.bigbrightpaints.erp.core.notification.EmailService;
import com.bigbrightpaints.erp.test.AbstractIntegrationTest;
import com.bigbrightpaints.erp.modules.auth.domain.PasswordResetToken;
import com.bigbrightpaints.erp.modules.auth.domain.PasswordResetTokenRepository;
import com.bigbrightpaints.erp.modules.auth.domain.UserAccount;
import com.bigbrightpaints.erp.modules.auth.domain.UserAccountRepository;
import java.util.ArrayList;
import java.util.Collections;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.mock.mockito.SpyBean;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.*;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;

public class AuthControllerIT extends AbstractIntegrationTest {

    @Autowired
    private TestRestTemplate rest;

    @Autowired
    private UserAccountRepository userAccountRepository;

    @Autowired
    private PasswordResetTokenRepository passwordResetTokenRepository;

    @SpyBean
    private EmailService emailService;

    private static final String COMPANY_CODE = "ACME";
    private static final String ADMIN_EMAIL = "admin@bbp.com";
    private static final String ADMIN_PASSWORD = "admin123";
    private static final String USER_EMAIL = "reset-target@bbp.com";
    private static final String USER_PASSWORD = "User@12345";

    @org.junit.jupiter.api.BeforeEach
    void seedUserAndCompany() {
        UserAccount adminUser = dataSeeder.ensureUser(
                ADMIN_EMAIL,
                ADMIN_PASSWORD,
                "Admin",
                COMPANY_CODE,
                java.util.List.of("ROLE_ADMIN"));
        adminUser.setMustChangePassword(false);
        userAccountRepository.save(adminUser);

        UserAccount resetTarget = dataSeeder.ensureUser(
                USER_EMAIL,
                USER_PASSWORD,
                "Reset Target",
                COMPANY_CODE,
                java.util.List.of("ROLE_SALES"));
        resetTarget.setMustChangePassword(false);
        userAccountRepository.save(resetTarget);
    }

    @Test
    void login_and_me_flow_succeeds() {
        Map<String, Object> body = Map.of(
                "email", ADMIN_EMAIL,
                "password", ADMIN_PASSWORD,
                "companyCode", COMPANY_CODE
        );
        ResponseEntity<Map> loginResp = rest.postForEntity("/api/v1/auth/login", body, Map.class);
        assertThat(loginResp.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(loginResp.getBody()).isNotNull();
        String accessToken = (String) loginResp.getBody().get("accessToken");
        assertThat(accessToken).isNotBlank();

        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(accessToken);
        ResponseEntity<Map> meResp = rest.exchange("/api/v1/auth/me", HttpMethod.GET, new HttpEntity<>(headers), Map.class);
        assertThat(meResp.getStatusCode()).isEqualTo(HttpStatus.OK);
        Map data = (Map) meResp.getBody().get("data");
        assertThat(data).isNotNull();
        assertThat(data.get("email")).isEqualTo(ADMIN_EMAIL);
        assertThat(data.get("companyCode")).isEqualTo(COMPANY_CODE);
        assertThat(data.get("companyId")).isEqualTo(COMPANY_CODE);
        List<String> roles = (List<String>) data.get("roles");
        assertThat(roles).isNotNull();
        assertThat(roles).contains("ROLE_ADMIN");
        List<String> permissions = (List<String>) data.get("permissions");
        assertThat(permissions).isNotNull();
    }

    @Test
    void refresh_token_revoked_after_logout() {
        Map<String, Object> loginPayload = login(ADMIN_EMAIL, ADMIN_PASSWORD);
        assertThat(loginPayload).isNotNull();
        String accessToken = loginPayload.get("accessToken").toString();
        String refreshToken = loginPayload.get("refreshToken").toString();

        ResponseEntity<Map> refreshResp = rest.postForEntity("/api/v1/auth/refresh-token",
                Map.of("refreshToken", refreshToken, "companyCode", COMPANY_CODE), Map.class);
        assertThat(refreshResp.getStatusCode()).isEqualTo(HttpStatus.OK);
        Map<String, Object> refreshPayload = refreshResp.getBody();
        assertThat(refreshPayload).isNotNull();
        String refreshedAccessToken = refreshPayload.get("accessToken").toString();
        String refreshedRefreshToken = refreshPayload.get("refreshToken").toString();

        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(refreshedAccessToken);
        headers.set("X-Company-Code", COMPANY_CODE);
        ResponseEntity<Void> logoutResp = rest.exchange(
                "/api/v1/auth/logout?refreshToken=" + refreshedRefreshToken,
                HttpMethod.POST,
                new HttpEntity<>(headers),
                Void.class);
        assertThat(logoutResp.getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);

        ResponseEntity<Map> revokedRefresh = rest.postForEntity("/api/v1/auth/refresh-token",
                Map.of("refreshToken", refreshedRefreshToken, "companyCode", COMPANY_CODE), Map.class);
        assertThat(revokedRefresh.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);

        assertThat(me(accessToken).getStatusCode()).isIn(HttpStatus.UNAUTHORIZED, HttpStatus.FORBIDDEN);
        assertThat(me(refreshedAccessToken).getStatusCode()).isIn(HttpStatus.UNAUTHORIZED, HttpStatus.FORBIDDEN);
    }

    @Test
    void password_change_revokes_existing_access_and_refresh_tokens() {
        Map<String, Object> loginPayload = login(ADMIN_EMAIL, ADMIN_PASSWORD);
        String accessToken = loginPayload.get("accessToken").toString();
        String refreshToken = loginPayload.get("refreshToken").toString();

        HttpHeaders headers = bearerJson(accessToken);
        ResponseEntity<Map> changeResponse = rest.exchange(
                "/api/v1/auth/password/change",
                HttpMethod.POST,
                new HttpEntity<>(Map.of(
                        "currentPassword", ADMIN_PASSWORD,
                        "newPassword", "NewAdmin123!",
                        "confirmPassword", "NewAdmin123!"), headers),
                Map.class);

        assertThat(changeResponse.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(me(accessToken).getStatusCode()).isIn(HttpStatus.UNAUTHORIZED, HttpStatus.FORBIDDEN);

        ResponseEntity<Map> refreshResponse = rest.postForEntity(
                "/api/v1/auth/refresh-token",
                Map.of("refreshToken", refreshToken, "companyCode", COMPANY_CODE),
                Map.class);
        assertThat(refreshResponse.getStatusCode()).isIn(HttpStatus.BAD_REQUEST, HttpStatus.UNAUTHORIZED);

        Map<String, Object> reloginPayload = login(ADMIN_EMAIL, "NewAdmin123!");
        assertThat(reloginPayload.get("accessToken")).isNotNull();
    }

    @Test
    void password_reset_revokes_existing_access_and_refresh_tokens() {
        Map<String, Object> loginPayload = login(ADMIN_EMAIL, ADMIN_PASSWORD);
        String accessToken = loginPayload.get("accessToken").toString();
        String refreshToken = loginPayload.get("refreshToken").toString();

        UserAccount user = userAccountRepository.findByEmailIgnoreCase(ADMIN_EMAIL).orElseThrow();
        passwordResetTokenRepository.deleteByUser(user);
        passwordResetTokenRepository.save(new PasswordResetToken(
                user,
                "legacy-reset-token",
                Instant.now().plusSeconds(600)));

        ResponseEntity<Map> resetResponse = rest.postForEntity(
                "/api/v1/auth/password/reset",
                Map.of(
                        "token", "legacy-reset-token",
                        "newPassword", "ResetAdmin123!",
                        "confirmPassword", "ResetAdmin123!"),
                Map.class);

        assertThat(resetResponse.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(me(accessToken).getStatusCode()).isIn(HttpStatus.UNAUTHORIZED, HttpStatus.FORBIDDEN);

        ResponseEntity<Map> refreshResponse = rest.postForEntity(
                "/api/v1/auth/refresh-token",
                Map.of("refreshToken", refreshToken, "companyCode", COMPANY_CODE),
                Map.class);
        assertThat(refreshResponse.getStatusCode()).isIn(HttpStatus.BAD_REQUEST, HttpStatus.UNAUTHORIZED);

        Map<String, Object> reloginPayload = login(ADMIN_EMAIL, "ResetAdmin123!");
        assertThat(reloginPayload.get("accessToken")).isNotNull();
    }

    @Test
    void must_change_password_user_can_detect_corridor_but_is_blocked_from_admin_surface_until_password_changed() {
        markMustChangePassword(ADMIN_EMAIL);

        Map<String, Object> loginPayload = login(ADMIN_EMAIL, ADMIN_PASSWORD);
        assertThat(loginPayload.get("mustChangePassword")).isEqualTo(true);

        String accessToken = loginPayload.get("accessToken").toString();
        ResponseEntity<Map> meResponse = rest.exchange(
                "/api/v1/auth/me",
                HttpMethod.GET,
                new HttpEntity<>(bearer(accessToken)),
                Map.class);

        assertThat(meResponse.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(meResponse.getBody()).isNotNull();
        Map<?, ?> meData = (Map<?, ?>) meResponse.getBody().get("data");
        assertThat(meData).isNotNull();
        assertThat(meData.get("mustChangePassword")).isEqualTo(true);

        ResponseEntity<Map> blockedAdminResponse = rest.exchange(
                "/api/v1/admin/users",
                HttpMethod.GET,
                new HttpEntity<>(bearer(accessToken)),
                Map.class);
        assertThat(blockedAdminResponse.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(blockedAdminResponse.getBody()).isNotNull();
        assertThat(blockedAdminResponse.getBody()).containsEntry("success", false);
        assertThat(blockedAdminResponse.getBody()).containsEntry(
                "message",
                "Password change required before accessing this resource");
        @SuppressWarnings("unchecked")
        Map<String, Object> blockedAdminError = (Map<String, Object>) blockedAdminResponse.getBody().get("data");
        assertThat(blockedAdminError).isNotNull();
        assertThat(blockedAdminError).containsEntry("code", "AUTH_004");
        assertThat(blockedAdminError).containsEntry("reason", "PASSWORD_CHANGE_REQUIRED");
        assertThat(blockedAdminError).containsEntry("mustChangePassword", true);

        ResponseEntity<Map> changePasswordResponse = rest.exchange(
                "/api/v1/auth/password/change",
                HttpMethod.POST,
                new HttpEntity<>(Map.of(
                        "currentPassword", ADMIN_PASSWORD,
                        "newPassword", "TempChanged123!",
                        "confirmPassword", "TempChanged123!"), bearerJson(accessToken)),
                Map.class);
        assertThat(changePasswordResponse.getStatusCode()).isEqualTo(HttpStatus.OK);

        assertThat(me(accessToken).getStatusCode()).isIn(HttpStatus.UNAUTHORIZED, HttpStatus.FORBIDDEN);

        Map<String, Object> reloginPayload = login(ADMIN_EMAIL, "TempChanged123!");
        assertThat(reloginPayload.get("mustChangePassword")).isEqualTo(false);

        ResponseEntity<Map> adminResponseAfterPasswordChange = rest.exchange(
                "/api/v1/admin/users",
                HttpMethod.GET,
                new HttpEntity<>(bearer(reloginPayload.get("accessToken").toString())),
                Map.class);
        assertThat(adminResponseAfterPasswordChange.getStatusCode()).isEqualTo(HttpStatus.OK);
    }

    @Test
    void must_change_password_corridor_still_enforces_company_header_matching() {
        markMustChangePassword(ADMIN_EMAIL);

        Map<String, Object> loginPayload = login(ADMIN_EMAIL, ADMIN_PASSWORD);
        String accessToken = loginPayload.get("accessToken").toString();

        ResponseEntity<Map> mismatchedCompanyResponse = rest.exchange(
                "/api/v1/auth/me",
                HttpMethod.GET,
                new HttpEntity<>(bearer(accessToken, "OTHER")),
                Map.class);

        assertThat(mismatchedCompanyResponse.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
    }

    @Test
    void tenantBindingMismatch_returnsControlledAuthErrorContract() {
        String accessToken = login(ADMIN_EMAIL, ADMIN_PASSWORD).get("accessToken").toString();

        ResponseEntity<Map> mismatchedCompanyResponse = rest.exchange(
                "/api/v1/auth/me",
                HttpMethod.GET,
                new HttpEntity<>(bearer(accessToken, "OTHER")),
                Map.class);

        assertThat(mismatchedCompanyResponse.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(mismatchedCompanyResponse.getBody()).isNotNull();
        assertThat(mismatchedCompanyResponse.getBody()).containsEntry("success", false);
        assertThat(mismatchedCompanyResponse.getBody()).containsEntry("message", "Access denied");
        Object payload = mismatchedCompanyResponse.getBody().get("data");
        assertThat(payload).isInstanceOf(Map.class);
        @SuppressWarnings("unchecked")
        Map<String, Object> error = (Map<String, Object>) payload;
        assertThat(error).containsEntry("code", "AUTH_004");
        assertThat(error).containsEntry("message", "Insufficient permissions for this operation");
        assertThat(error).containsEntry("reason", "COMPANY_CONTEXT_MISMATCH");
        assertThat(error).containsEntry(
                "reasonDetail",
                "Company header does not match authenticated company context");
        assertThat(error).containsKey("traceId");
    }

    @Test
    void overlappingPublicAndAdminResetRequests_leaveLatestResetLinkUsable() throws Exception {
        UserAccount resetTarget = userAccountRepository.findByEmailIgnoreCase(USER_EMAIL).orElseThrow();
        String adminAccessToken = login(ADMIN_EMAIL, ADMIN_PASSWORD).get("accessToken").toString();

        CountDownLatch bothEmailsQueued = new CountDownLatch(2);
        List<String> deliveredTokens = Collections.synchronizedList(new ArrayList<>());
        doAnswer(invocation -> {
            deliveredTokens.add(invocation.getArgument(2, String.class));
            bothEmailsQueued.countDown();
            assertThat(bothEmailsQueued.await(5, TimeUnit.SECONDS)).isTrue();
            return null;
        }).when(emailService).sendPasswordResetEmailRequired(eq(USER_EMAIL), eq("Reset Target"), anyString());

        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            Future<ResponseEntity<Map>> publicForgot = executor.submit(() -> rest.exchange(
                    "/api/v1/auth/password/forgot",
                    HttpMethod.POST,
                    new HttpEntity<>(Map.of("email", USER_EMAIL), jsonHeaders()),
                    Map.class));
            Future<ResponseEntity<Map>> adminForceReset = executor.submit(() -> rest.exchange(
                    "/api/v1/admin/users/" + resetTarget.getId() + "/force-reset-password",
                    HttpMethod.POST,
                    new HttpEntity<>(bearer(adminAccessToken)),
                    Map.class));

            assertThat(publicForgot.get(5, TimeUnit.SECONDS).getStatusCode()).isEqualTo(HttpStatus.OK);
            assertThat(adminForceReset.get(5, TimeUnit.SECONDS).getStatusCode()).isEqualTo(HttpStatus.OK);
        } finally {
            executor.shutdownNow();
        }

        assertThat(deliveredTokens).hasSize(2);
        ResponseEntity<Map> staleReset = resetPassword(deliveredTokens.getFirst(), "ResetUser123!");
        ResponseEntity<Map> latestReset = resetPassword(deliveredTokens.getLast(), "ResetUser123!");

        assertThat(staleReset.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(staleReset.getBody()).isNotNull();
        assertThat(staleReset.getBody().get("message")).isEqualTo("Invalid or expired token");
        assertThat(latestReset.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(latestReset.getBody()).isNotNull();
        assertThat(latestReset.getBody().get("success")).isEqualTo(true);
    }

    private Map<String, Object> login(String email, String password) {
        Map<String, Object> body = Map.of(
                "email", email,
                "password", password,
                "companyCode", COMPANY_CODE
        );
        ResponseEntity<Map> loginResp = rest.postForEntity("/api/v1/auth/login", body, Map.class);
        assertThat(loginResp.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(loginResp.getBody()).isNotNull();
        return loginResp.getBody();
    }

    private ResponseEntity<Map> me(String accessToken) {
        return rest.exchange(
                "/api/v1/auth/me",
                HttpMethod.GET,
                new HttpEntity<>(bearer(accessToken)),
                Map.class);
    }

    private ResponseEntity<Map> resetPassword(String token, String newPassword) {
        return rest.exchange(
                "/api/v1/auth/password/reset",
                HttpMethod.POST,
                new HttpEntity<>(Map.of(
                        "token", token,
                        "newPassword", newPassword,
                        "confirmPassword", newPassword), jsonHeaders()),
                Map.class);
    }

    private void markMustChangePassword(String email) {
        UserAccount user = userAccountRepository.findByEmailIgnoreCase(email).orElseThrow();
        user.setMustChangePassword(true);
        userAccountRepository.save(user);
    }

    private HttpHeaders bearer(String accessToken) {
        return bearer(accessToken, COMPANY_CODE);
    }

    private HttpHeaders bearer(String accessToken, String companyCode) {
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(accessToken);
        headers.set("X-Company-Code", companyCode);
        return headers;
    }

    private HttpHeaders jsonHeaders() {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        return headers;
    }

    private HttpHeaders bearerJson(String accessToken) {
        HttpHeaders headers = bearer(accessToken);
        headers.setContentType(MediaType.APPLICATION_JSON);
        return headers;
    }
}
