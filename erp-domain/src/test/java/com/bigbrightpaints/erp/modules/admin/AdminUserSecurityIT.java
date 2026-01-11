package com.bigbrightpaints.erp.modules.admin;

import com.bigbrightpaints.erp.modules.auth.domain.UserAccount;
import com.bigbrightpaints.erp.test.AbstractIntegrationTest;
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

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

public class AdminUserSecurityIT extends AbstractIntegrationTest {

    private static final String COMPANY = "SECADMIN";
    private static final String OTHER_COMPANY = "SECADMIN2";
    private static final String ADMIN_EMAIL = "admin-sec@bbp.com";
    private static final String ADMIN_PASSWORD = "Admin123!";
    private static final String DEALER_EMAIL = "dealer-sec@bbp.com";
    private static final String DEALER_PASSWORD = "Dealer123!";

    @Autowired
    private TestRestTemplate rest;

    private UserAccount otherCompanyUser;

    @BeforeEach
    void setUp() {
        dataSeeder.ensureUser(ADMIN_EMAIL, ADMIN_PASSWORD, "Security Admin", COMPANY,
                List.of("ROLE_ADMIN"));
        dataSeeder.ensureUser(DEALER_EMAIL, DEALER_PASSWORD, "Security Dealer", COMPANY,
                List.of("ROLE_DEALER"));
        otherCompanyUser = dataSeeder.ensureUser("other-admin@bbp.com", "Other123!", "Other Admin", OTHER_COMPANY,
                List.of("ROLE_ADMIN"));
    }

    @Test
    void admin_users_requires_authentication() {
        ResponseEntity<Map> response = rest.exchange(
                "/api/v1/admin/users",
                HttpMethod.GET,
                new HttpEntity<>(new HttpHeaders()),
                Map.class);
        assertThat(response.getStatusCode()).isIn(HttpStatus.UNAUTHORIZED, HttpStatus.FORBIDDEN);
    }

    @Test
    void admin_users_requires_admin_role() {
        String token = login(DEALER_EMAIL, DEALER_PASSWORD, COMPANY);
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(token);
        ResponseEntity<Map> response = rest.exchange(
                "/api/v1/admin/users",
                HttpMethod.GET,
                new HttpEntity<>(headers),
                Map.class);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
    }

    @Test
    void admin_user_update_blocks_cross_company_access() {
        String token = login(ADMIN_EMAIL, ADMIN_PASSWORD, COMPANY);
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(token);
        headers.setContentType(MediaType.APPLICATION_JSON);

        Map<String, Object> payload = Map.of("displayName", "Other Admin Updated");

        ResponseEntity<Map> response = rest.exchange(
                "/api/v1/admin/users/" + otherCompanyUser.getId(),
                HttpMethod.PUT,
                new HttpEntity<>(payload, headers),
                Map.class);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    }

    @Test
    void dealer_role_is_blocked_from_sales_promotions() {
        String token = login(DEALER_EMAIL, DEALER_PASSWORD, COMPANY);
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(token);

        ResponseEntity<Map> response = rest.exchange(
                "/api/v1/sales/promotions",
                HttpMethod.GET,
                new HttpEntity<>(headers),
                Map.class);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
    }

    @Test
    void dealer_role_is_blocked_from_dealer_admin_endpoints() {
        String token = login(DEALER_EMAIL, DEALER_PASSWORD, COMPANY);
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(token);

        ResponseEntity<Map> ledgerResp = rest.exchange(
                "/api/v1/dealers/1/ledger",
                HttpMethod.GET,
                new HttpEntity<>(headers),
                Map.class);
        assertThat(ledgerResp.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);

        ResponseEntity<Map> invoicesResp = rest.exchange(
                "/api/v1/dealers/1/invoices",
                HttpMethod.GET,
                new HttpEntity<>(headers),
                Map.class);
        assertThat(invoicesResp.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);

        ResponseEntity<Map> agingResp = rest.exchange(
                "/api/v1/dealers/1/aging",
                HttpMethod.GET,
                new HttpEntity<>(headers),
                Map.class);
        assertThat(agingResp.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
    }

    private String login(String email, String password, String companyCode) {
        Map<String, Object> body = Map.of(
                "email", email,
                "password", password,
                "companyCode", companyCode
        );
        ResponseEntity<Map> loginResp = rest.postForEntity("/api/v1/auth/login", body, Map.class);
        assertThat(loginResp.getStatusCode()).isEqualTo(HttpStatus.OK);
        Map<String, Object> payload = loginResp.getBody();
        assertThat(payload).isNotNull();
        String token = payload.get("accessToken").toString();
        assertThat(token).isNotBlank();
        return token;
    }
}
