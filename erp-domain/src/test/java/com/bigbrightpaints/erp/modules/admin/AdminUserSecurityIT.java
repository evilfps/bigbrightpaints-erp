package com.bigbrightpaints.erp.modules.admin;

import com.bigbrightpaints.erp.modules.auth.domain.UserAccount;
import com.bigbrightpaints.erp.modules.company.domain.Company;
import com.bigbrightpaints.erp.modules.company.domain.CompanyRepository;
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
    private static final String SUPER_ADMIN_EMAIL = "super-admin-sec@bbp.com";
    private static final String SUPER_ADMIN_PASSWORD = "SuperAdmin123!";
    private static final String DEALER_EMAIL = "dealer-sec@bbp.com";
    private static final String DEALER_PASSWORD = "Dealer123!";

    @Autowired
    private TestRestTemplate rest;

    @Autowired
    private CompanyRepository companyRepository;

    private UserAccount otherCompanyUser;

    @BeforeEach
    void setUp() {
        dataSeeder.ensureUser(ADMIN_EMAIL, ADMIN_PASSWORD, "Security Admin", COMPANY,
                List.of("ROLE_ADMIN"));
        dataSeeder.ensureUser(SUPER_ADMIN_EMAIL, SUPER_ADMIN_PASSWORD, "Security Super Admin", COMPANY,
                List.of("ROLE_SUPER_ADMIN"));
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
    void super_admin_can_access_admin_users_via_role_hierarchy() {
        String token = login(SUPER_ADMIN_EMAIL, SUPER_ADMIN_PASSWORD, COMPANY);
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(token);

        ResponseEntity<Map> response = rest.exchange(
                "/api/v1/admin/users",
                HttpMethod.GET,
                new HttpEntity<>(headers),
                Map.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
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
    void admin_user_update_ignores_cross_company_header() {
        String token = login(ADMIN_EMAIL, ADMIN_PASSWORD, COMPANY);
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(token);
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.set("X-Company-Id", OTHER_COMPANY);

        Map<String, Object> payload = Map.of("displayName", "Other Admin Updated");

        ResponseEntity<Map> response = rest.exchange(
                "/api/v1/admin/users/" + otherCompanyUser.getId(),
                HttpMethod.PUT,
                new HttpEntity<>(payload, headers),
                Map.class);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
    }

    @Test
    void admin_users_list_ignores_cross_company_header() {
        String token = login(ADMIN_EMAIL, ADMIN_PASSWORD, COMPANY);
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(token);
        headers.set("X-Company-Id", OTHER_COMPANY);

        ResponseEntity<Map> response = rest.exchange(
                "/api/v1/admin/users",
                HttpMethod.GET,
                new HttpEntity<>(headers),
                Map.class);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
    }

    @Test
    void admin_user_create_is_blocked_when_active_user_quota_reached() {
        String token = login(ADMIN_EMAIL, ADMIN_PASSWORD, COMPANY);
        String superAdminToken = login(SUPER_ADMIN_EMAIL, SUPER_ADMIN_PASSWORD, COMPANY);
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(token);
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.set("X-Company-Code", COMPANY);
        HttpHeaders superAdminHeaders = new HttpHeaders();
        superAdminHeaders.setBearerAuth(superAdminToken);
        superAdminHeaders.setContentType(MediaType.APPLICATION_JSON);
        superAdminHeaders.set("X-Company-Code", COMPANY);

        ResponseEntity<Map> policyResponse = rest.exchange(
                "/api/v1/admin/tenant-runtime/policy",
                HttpMethod.PUT,
                new HttpEntity<>(Map.of(
                        "maxActiveUsers", 3,
                        "holdState", "ACTIVE",
                        "changeReason", "Quota enforcement test"
                ), superAdminHeaders),
                Map.class);
        assertThat(policyResponse.getStatusCode()).isEqualTo(HttpStatus.OK);

        Company activeCompany = companyRepository.findByCodeIgnoreCase(COMPANY).orElseThrow();
        Map<String, Object> createPayload = Map.of(
                "email", "quota-limited-user@bbp.com",
                "displayName", "Quota Limited User",
                "roles", List.of("ROLE_SALES"),
                "companyIds", List.of(activeCompany.getId())
        );
        ResponseEntity<Map> createResponse = rest.exchange(
                "/api/v1/admin/users",
                HttpMethod.POST,
                new HttpEntity<>(createPayload, headers),
                Map.class);

        assertThat(createResponse.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(createResponse.getBody()).isNotNull();
        assertThat(createResponse.getBody().get("success")).isEqualTo(Boolean.FALSE);
        Map<?, ?> errorData = (Map<?, ?>) createResponse.getBody().get("data");
        assertThat(errorData).isNotNull();
        assertThat(errorData.get("code")).isEqualTo("BUS_006");
    }

    @Test
    void tenant_runtime_policy_update_requires_super_admin_role() {
        String token = login(ADMIN_EMAIL, ADMIN_PASSWORD, COMPANY);
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(token);
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.set("X-Company-Code", COMPANY);

        ResponseEntity<Map> response = rest.exchange(
                "/api/v1/admin/tenant-runtime/policy",
                HttpMethod.PUT,
                new HttpEntity<>(Map.of(
                        "maxActiveUsers", 200,
                        "holdState", "ACTIVE",
                        "changeReason", "RBAC enforcement"
                ), headers),
                Map.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
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
