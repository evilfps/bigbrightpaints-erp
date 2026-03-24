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

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class ProfileControllerIT extends AbstractIntegrationTest {

    private static final String COMPANY = "ACME";
    private static final String EMAIL = "profile@bbp.com";
    private static final String PASSWORD = "admin123";

    @Autowired
    private TestRestTemplate rest;

    @Autowired
    private UserAccountRepository userAccountRepository;

    @BeforeEach
    void setUp() {
        UserAccount user = dataSeeder.ensureUser(EMAIL, PASSWORD, "Portal Admin", COMPANY, List.of("ROLE_ADMIN"));
        user.setMustChangePassword(false);
        userAccountRepository.save(user);
    }

    @Test
    void profile_get_and_update_flow() {
        HttpHeaders headers = authenticatedHeaders();

        ResponseEntity<Map> profile = rest.exchange("/api/v1/auth/profile", HttpMethod.GET, new HttpEntity<>(headers), Map.class);
        assertThat(profile.getStatusCode()).isEqualTo(HttpStatus.OK);
        Map<?, ?> body = profile.getBody();
        assertThat(body).isNotNull();
        Map<?, ?> data = (Map<?, ?>) body.get("data");
        assertThat(data).isNotNull();
        assertThat(data.get("email")).isEqualTo(EMAIL);

        Map<String, Object> updatePayload = Map.of(
                "displayName", "Portal Admin Updated",
                "preferredName", "Portal",
                "jobTitle", "Automation Lead",
                "profilePictureUrl", "https://cdn.dev/avatar.png",
                "phoneSecondary", "+1-202-555-0123",
                "secondaryEmail", "portal.secondary@bbp.com"
        );
        ResponseEntity<Map> update = rest.exchange("/api/v1/auth/profile", HttpMethod.PUT, new HttpEntity<>(updatePayload, headers), Map.class);
        assertThat(update.getStatusCode()).isEqualTo(HttpStatus.OK);
        Map<?, ?> updateData = (Map<?, ?>) update.getBody().get("data");
        assertThat(updateData.get("displayName")).isEqualTo("Portal Admin Updated");
        assertThat(updateData.get("preferredName")).isEqualTo("Portal");
        assertThat(updateData.get("jobTitle")).isEqualTo("Automation Lead");
        assertThat(updateData.get("profilePictureUrl")).isEqualTo("https://cdn.dev/avatar.png");
        assertThat(updateData.get("phoneSecondary")).isEqualTo("+1-202-555-0123");
        assertThat(updateData.get("secondaryEmail")).isEqualTo("portal.secondary@bbp.com");

        UserAccount reloaded = userAccountRepository.findByEmailIgnoreCase(EMAIL).orElseThrow();
        assertThat(reloaded.getDisplayName()).isEqualTo("Portal Admin Updated");
        assertThat(reloaded.getPreferredName()).isEqualTo("Portal");
    }

    @Test
    void must_change_password_user_can_view_profile_but_cannot_update_it() {
        markMustChangePassword();
        HttpHeaders headers = authenticatedHeaders();

        ResponseEntity<Map> profile = rest.exchange(
                "/api/v1/auth/profile",
                HttpMethod.GET,
                new HttpEntity<>(headers),
                Map.class);
        assertThat(profile.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(profile.getBody()).isNotNull();
        Map<?, ?> profileData = (Map<?, ?>) profile.getBody().get("data");
        assertThat(profileData).isNotNull();
        assertThat(profileData.get("email")).isEqualTo(EMAIL);

        ResponseEntity<Map> update = rest.exchange(
                "/api/v1/auth/profile",
                HttpMethod.PUT,
                new HttpEntity<>(Map.of("displayName", "Blocked Update"), headers),
                Map.class);
        assertThat(update.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
    }

    private void markMustChangePassword() {
        UserAccount user = userAccountRepository.findByEmailIgnoreCase(EMAIL).orElseThrow();
        user.setMustChangePassword(true);
        userAccountRepository.save(user);
    }

    private HttpHeaders authenticatedHeaders() {
        Map<String, Object> loginPayload = Map.of(
                "email", EMAIL,
                "password", PASSWORD,
                "companyCode", COMPANY
        );
        ResponseEntity<Map> loginResponse = rest.postForEntity("/api/v1/auth/login", loginPayload, Map.class);
        assertThat(loginResponse.getStatusCode()).isEqualTo(HttpStatus.OK);
        String token = (String) loginResponse.getBody().get("accessToken");
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(token);
        headers.add("X-Company-Code", COMPANY);
        return headers;
    }
}
