package com.bigbrightpaints.erp.modules.auth;

import com.bigbrightpaints.erp.modules.auth.domain.UserAccount;
import com.bigbrightpaints.erp.modules.auth.domain.UserAccountRepository;
import com.bigbrightpaints.erp.test.AbstractIntegrationTest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class AuthDisabledUserTokenIT extends AbstractIntegrationTest {

    private static final String COMPANY_CODE = "AUTH-DISABLED";
    private static final String USER_EMAIL = "disabled-user@bbp.com";
    private static final String USER_PASSWORD = "Passw0rd!";

    @Autowired
    private TestRestTemplate rest;

    @Autowired
    private UserAccountRepository userAccountRepository;

    @BeforeEach
    void seedUser() {
        dataSeeder.ensureUser(USER_EMAIL, USER_PASSWORD, "Disabled User", COMPANY_CODE,
                java.util.List.of("ROLE_ADMIN"));
    }

    @Test
    void disabledUserToken_isRejectedEvenWhenJwtStillValid() {
        String token = loginToken();

        UserAccount user = userAccountRepository.findByEmailIgnoreCase(USER_EMAIL).orElseThrow();
        user.setEnabled(false);
        userAccountRepository.save(user);

        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(token);
        headers.set("X-Company-Code", COMPANY_CODE);
        ResponseEntity<Map> meResponse = rest.exchange(
                "/api/v1/auth/me",
                HttpMethod.GET,
                new HttpEntity<>(headers),
                Map.class);

        assertThat(meResponse.getStatusCode()).isIn(HttpStatus.UNAUTHORIZED, HttpStatus.FORBIDDEN);
    }

    private String loginToken() {
        Map<String, Object> request = Map.of(
                "email", USER_EMAIL,
                "password", USER_PASSWORD,
                "companyCode", COMPANY_CODE
        );
        ResponseEntity<Map> loginResponse = rest.postForEntity("/api/v1/auth/login", request, Map.class);
        assertThat(loginResponse.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(loginResponse.getBody()).isNotNull();
        return (String) loginResponse.getBody().get("accessToken");
    }
}
