package com.bigbrightpaints.erp.modules.auth;

import com.bigbrightpaints.erp.test.AbstractIntegrationTest;
import com.bigbrightpaints.erp.modules.auth.domain.PasswordResetToken;
import com.bigbrightpaints.erp.modules.auth.domain.PasswordResetTokenRepository;
import com.bigbrightpaints.erp.modules.auth.domain.UserAccount;
import com.bigbrightpaints.erp.modules.auth.domain.UserAccountRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.*;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

public class AuthControllerIT extends AbstractIntegrationTest {

    @Autowired
    private TestRestTemplate rest;

    @Autowired
    private UserAccountRepository userAccountRepository;

    @Autowired
    private PasswordResetTokenRepository passwordResetTokenRepository;

    private static final String COMPANY_CODE = "ACME";
    private static final String ADMIN_EMAIL = "admin@bbp.com";
    private static final String ADMIN_PASSWORD = "admin123";

    @org.junit.jupiter.api.BeforeEach
    void seedUserAndCompany() {
        dataSeeder.ensureUser(ADMIN_EMAIL, ADMIN_PASSWORD, "Admin", COMPANY_CODE, java.util.List.of("ROLE_ADMIN"));
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

    private HttpHeaders bearer(String accessToken) {
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(accessToken);
        headers.set("X-Company-Code", COMPANY_CODE);
        return headers;
    }

    private HttpHeaders bearerJson(String accessToken) {
        HttpHeaders headers = bearer(accessToken);
        headers.setContentType(MediaType.APPLICATION_JSON);
        return headers;
    }
}
