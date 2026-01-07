package com.bigbrightpaints.erp.modules.auth;

import com.bigbrightpaints.erp.test.AbstractIntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.*;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

public class AuthControllerIT extends AbstractIntegrationTest {

    @Autowired
    private TestRestTemplate rest;

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
        assertThat(data.get("companyId")).isEqualTo(COMPANY_CODE);
        List<String> roles = (List<String>) data.get("roles");
        assertThat(roles).isNotNull();
        assertThat(roles).contains("ROLE_ADMIN");
        List<String> permissions = (List<String>) data.get("permissions");
        assertThat(permissions).isNotNull();
    }

    @Test
    void refresh_token_revoked_after_logout() {
        Map<String, Object> body = Map.of(
                "email", ADMIN_EMAIL,
                "password", ADMIN_PASSWORD,
                "companyCode", COMPANY_CODE
        );
        ResponseEntity<Map> loginResp = rest.postForEntity("/api/v1/auth/login", body, Map.class);
        assertThat(loginResp.getStatusCode()).isEqualTo(HttpStatus.OK);
        Map<String, Object> loginPayload = loginResp.getBody();
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
        ResponseEntity<Void> logoutResp = rest.exchange(
                "/api/v1/auth/logout?refreshToken=" + refreshedRefreshToken,
                HttpMethod.POST,
                new HttpEntity<>(headers),
                Void.class);
        assertThat(logoutResp.getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);

        ResponseEntity<Map> revokedRefresh = rest.postForEntity("/api/v1/auth/refresh-token",
                Map.of("refreshToken", refreshedRefreshToken, "companyCode", COMPANY_CODE), Map.class);
        assertThat(revokedRefresh.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    }
}
