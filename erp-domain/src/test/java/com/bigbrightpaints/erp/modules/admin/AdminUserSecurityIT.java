package com.bigbrightpaints.erp.modules.admin;

import com.bigbrightpaints.erp.modules.auth.domain.UserAccount;
import com.bigbrightpaints.erp.modules.company.domain.Company;
import com.bigbrightpaints.erp.modules.company.domain.CompanyRepository;
import com.bigbrightpaints.erp.test.AbstractIntegrationTest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import static org.assertj.core.api.Assertions.assertThat;

@Tag("critical")
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

    @Autowired
    private DataSource dataSource;

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
    void super_admin_tenant_context_cannot_force_reset_password_via_admin_user_management_surface() {
        String token = login(SUPER_ADMIN_EMAIL, SUPER_ADMIN_PASSWORD, COMPANY);
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(token);

        ResponseEntity<Map> response = rest.exchange(
                "/api/v1/admin/users/" + otherCompanyUser.getId() + "/force-reset-password",
                HttpMethod.POST,
                new HttpEntity<>(headers),
                Map.class);

        assertPlatformOnlyAccessDenied(response);
    }

    @Test
    void super_admin_tenant_context_cannot_access_admin_users_via_tenant_workflow_surface() {
        String token = login(SUPER_ADMIN_EMAIL, SUPER_ADMIN_PASSWORD, COMPANY);
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(token);

        ResponseEntity<Map> response = rest.exchange(
                "/api/v1/admin/users",
                HttpMethod.GET,
                new HttpEntity<>(headers),
                Map.class);

        assertPlatformOnlyAccessDenied(response);
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
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
    }

    @Test
    void admin_user_status_update_masks_cross_company_target_as_missing() {
        String token = login(ADMIN_EMAIL, ADMIN_PASSWORD, COMPANY);
        long missingUserId = otherCompanyUser.getId() + 10_000L;
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(token);
        headers.setContentType(MediaType.APPLICATION_JSON);

        ResponseEntity<Map> foreignResponse = rest.exchange(
                "/api/v1/admin/users/" + otherCompanyUser.getId() + "/status",
                HttpMethod.PUT,
                new HttpEntity<>(Map.of("enabled", false), headers),
                Map.class);
        ResponseEntity<Map> missingResponse = rest.exchange(
                "/api/v1/admin/users/" + missingUserId + "/status",
                HttpMethod.PUT,
                new HttpEntity<>(Map.of("enabled", false), headers),
                Map.class);

        assertMaskedMissingUserContractPair(foreignResponse, missingResponse);
    }

    @Test
    void tenant_admin_cross_company_privileged_actions_mask_foreign_targets_as_missing() {
        String token = login(ADMIN_EMAIL, ADMIN_PASSWORD, COMPANY);
        long missingUserId = otherCompanyUser.getId() + 10_000L;
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(token);
        headers.setContentType(MediaType.APPLICATION_JSON);

        ResponseEntity<Map> foreignForceReset = rest.exchange(
                "/api/v1/admin/users/" + otherCompanyUser.getId() + "/force-reset-password",
                HttpMethod.POST,
                new HttpEntity<>(headers),
                Map.class);
        ResponseEntity<Map> missingForceReset = rest.exchange(
                "/api/v1/admin/users/" + missingUserId + "/force-reset-password",
                HttpMethod.POST,
                new HttpEntity<>(headers),
                Map.class);
        assertMaskedMissingUserContractPair(foreignForceReset, missingForceReset);

        ResponseEntity<Map> foreignSuspend = rest.exchange(
                "/api/v1/admin/users/" + otherCompanyUser.getId() + "/suspend",
                HttpMethod.PATCH,
                new HttpEntity<>(headers),
                Map.class);
        ResponseEntity<Map> missingSuspend = rest.exchange(
                "/api/v1/admin/users/" + missingUserId + "/suspend",
                HttpMethod.PATCH,
                new HttpEntity<>(headers),
                Map.class);
        assertMaskedMissingUserContractPair(foreignSuspend, missingSuspend);

        ResponseEntity<Map> foreignUnsuspend = rest.exchange(
                "/api/v1/admin/users/" + otherCompanyUser.getId() + "/unsuspend",
                HttpMethod.PATCH,
                new HttpEntity<>(headers),
                Map.class);
        ResponseEntity<Map> missingUnsuspend = rest.exchange(
                "/api/v1/admin/users/" + missingUserId + "/unsuspend",
                HttpMethod.PATCH,
                new HttpEntity<>(headers),
                Map.class);
        assertMaskedMissingUserContractPair(foreignUnsuspend, missingUnsuspend);

        ResponseEntity<Map> foreignDisableMfa = rest.exchange(
                "/api/v1/admin/users/" + otherCompanyUser.getId() + "/mfa/disable",
                HttpMethod.PATCH,
                new HttpEntity<>(headers),
                Map.class);
        ResponseEntity<Map> missingDisableMfa = rest.exchange(
                "/api/v1/admin/users/" + missingUserId + "/mfa/disable",
                HttpMethod.PATCH,
                new HttpEntity<>(headers),
                Map.class);
        assertMaskedMissingUserContractPair(foreignDisableMfa, missingDisableMfa);

        ResponseEntity<Map> foreignDelete = rest.exchange(
                "/api/v1/admin/users/" + otherCompanyUser.getId(),
                HttpMethod.DELETE,
                new HttpEntity<>(headers),
                Map.class);
        ResponseEntity<Map> missingDelete = rest.exchange(
                "/api/v1/admin/users/" + missingUserId,
                HttpMethod.DELETE,
                new HttpEntity<>(headers),
                Map.class);
        assertMaskedMissingUserContractPair(foreignDelete, missingDelete);

        ResponseEntity<Map> foreignStatus = rest.exchange(
                "/api/v1/admin/users/" + otherCompanyUser.getId() + "/status",
                HttpMethod.PUT,
                new HttpEntity<>(Map.of("enabled", false), headers),
                Map.class);
        ResponseEntity<Map> missingStatus = rest.exchange(
                "/api/v1/admin/users/" + missingUserId + "/status",
                HttpMethod.PUT,
                new HttpEntity<>(Map.of("enabled", false), headers),
                Map.class);
        assertMaskedMissingUserContractPair(foreignStatus, missingStatus);
    }

    @Test
    void tenant_admin_foreign_row_lock_does_not_block_masked_user_operations() throws Exception {
        String token = login(ADMIN_EMAIL, ADMIN_PASSWORD, COMPANY);
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(token);
        headers.setContentType(MediaType.APPLICATION_JSON);

        try (UserRowWriteLock ignored = holdUserRowWriteLock(otherCompanyUser.getId())) {
            assertMaskedMissingUserContract(executeWithTimeout(
                    () -> rest.exchange(
                            "/api/v1/admin/users/" + otherCompanyUser.getId() + "/suspend",
                            HttpMethod.PATCH,
                            new HttpEntity<>(headers),
                            Map.class),
                    Duration.ofSeconds(2)));

            assertMaskedMissingUserContract(executeWithTimeout(
                    () -> rest.exchange(
                            "/api/v1/admin/users/" + otherCompanyUser.getId() + "/unsuspend",
                            HttpMethod.PATCH,
                            new HttpEntity<>(headers),
                            Map.class),
                    Duration.ofSeconds(2)));

            assertMaskedMissingUserContract(executeWithTimeout(
                    () -> rest.exchange(
                            "/api/v1/admin/users/" + otherCompanyUser.getId() + "/mfa/disable",
                            HttpMethod.PATCH,
                            new HttpEntity<>(headers),
                            Map.class),
                    Duration.ofSeconds(2)));

            assertMaskedMissingUserContract(executeWithTimeout(
                    () -> rest.exchange(
                            "/api/v1/admin/users/" + otherCompanyUser.getId(),
                            HttpMethod.DELETE,
                            new HttpEntity<>(headers),
                            Map.class),
                    Duration.ofSeconds(2)));

            assertMaskedMissingUserContract(executeWithTimeout(
                    () -> rest.exchange(
                            "/api/v1/admin/users/" + otherCompanyUser.getId() + "/force-reset-password",
                            HttpMethod.POST,
                            new HttpEntity<>(headers),
                            Map.class),
                    Duration.ofSeconds(2)));

            assertMaskedMissingUserContract(executeWithTimeout(
                    () -> rest.exchange(
                            "/api/v1/admin/users/" + otherCompanyUser.getId() + "/status",
                            HttpMethod.PUT,
                            new HttpEntity<>(Map.of("enabled", false), headers),
                            Map.class),
                    Duration.ofSeconds(2)));
        }
    }

    @Test
    void super_admin_tenant_context_cannot_execute_admin_user_action_matrix() {
        String token = login(SUPER_ADMIN_EMAIL, SUPER_ADMIN_PASSWORD, COMPANY);
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(token);

        assertPlatformOnlyAccessDenied(rest.exchange(
                "/api/v1/admin/users/" + otherCompanyUser.getId() + "/suspend",
                HttpMethod.PATCH,
                new HttpEntity<>(headers),
                Map.class));

        assertPlatformOnlyAccessDenied(rest.exchange(
                "/api/v1/admin/users/" + otherCompanyUser.getId() + "/unsuspend",
                HttpMethod.PATCH,
                new HttpEntity<>(headers),
                Map.class));

        assertPlatformOnlyAccessDenied(rest.exchange(
                "/api/v1/admin/users/" + otherCompanyUser.getId() + "/mfa/disable",
                HttpMethod.PATCH,
                new HttpEntity<>(headers),
                Map.class));

        assertPlatformOnlyAccessDenied(rest.exchange(
                "/api/v1/admin/users/" + otherCompanyUser.getId(),
                HttpMethod.DELETE,
                new HttpEntity<>(headers),
                Map.class));
    }

    @Test
    void admin_user_update_ignores_cross_company_header() {
        String token = login(ADMIN_EMAIL, ADMIN_PASSWORD, COMPANY);
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(token);
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.set("X-Company-Code", OTHER_COMPANY);

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
        headers.set("X-Company-Code", OTHER_COMPANY);

        ResponseEntity<Map> response = rest.exchange(
                "/api/v1/admin/users",
                HttpMethod.GET,
                new HttpEntity<>(headers),
                Map.class);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
    }

    @Test
    void admin_users_list_includes_last_login_field_in_payload() {
        String token = login(ADMIN_EMAIL, ADMIN_PASSWORD, COMPANY);
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(token);

        ResponseEntity<Map> response = rest.exchange(
                "/api/v1/admin/users",
                HttpMethod.GET,
                new HttpEntity<>(headers),
                Map.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).isNotNull();
        Map<String, Object> body = response.getBody();
        assertThat(body.get("success")).isEqualTo(Boolean.TRUE);
        Object dataObj = body.get("data");
        assertThat(dataObj).isInstanceOf(List.class);
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> users = (List<Map<String, Object>>) dataObj;
        assertThat(users).isNotEmpty();
        assertThat(users.getFirst()).containsKey("lastLoginAt");
    }

    @Test
    void admin_user_create_is_blocked_when_active_user_quota_reached() {
        String token = login(ADMIN_EMAIL, ADMIN_PASSWORD, COMPANY);
        String superAdminToken = login(SUPER_ADMIN_EMAIL, SUPER_ADMIN_PASSWORD, COMPANY);
        Long companyId = companyRepository.findByCodeIgnoreCase(COMPANY).orElseThrow().getId();
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(token);
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.set("X-Company-Code", COMPANY);
        HttpHeaders superAdminHeaders = new HttpHeaders();
        superAdminHeaders.setBearerAuth(superAdminToken);
        superAdminHeaders.setContentType(MediaType.APPLICATION_JSON);
        superAdminHeaders.set("X-Company-Code", COMPANY);

        ResponseEntity<Map> policyResponse = rest.exchange(
                "/api/v1/companies/" + companyId + "/tenant-runtime/policy",
                HttpMethod.PUT,
                new HttpEntity<>(Map.of(
                        "maxActiveUsers", 3,
                        "holdState", "ACTIVE",
                        "reasonCode", "quota-enforcement-test",
                        "maxConcurrentRequests", 10,
                        "maxRequestsPerMinute", 120
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
        Long companyId = companyRepository.findByCodeIgnoreCase(COMPANY).orElseThrow().getId();
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(token);
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.set("X-Company-Code", COMPANY);

        ResponseEntity<Map> response = rest.exchange(
                "/api/v1/companies/" + companyId + "/tenant-runtime/policy",
                HttpMethod.PUT,
                new HttpEntity<>(Map.of(
                        "maxActiveUsers", 200,
                        "holdState", "ACTIVE",
                        "reasonCode", "rbac-enforcement",
                        "maxConcurrentRequests", 10,
                        "maxRequestsPerMinute", 120
                ), headers),
                Map.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
    }

    @Test
    void tenant_admin_cannot_update_global_system_settings() {
        String token = login(ADMIN_EMAIL, ADMIN_PASSWORD, COMPANY);
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(token);
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.set("X-Company-Code", COMPANY);

        ResponseEntity<Map> response = rest.exchange(
                "/api/v1/admin/settings",
                HttpMethod.PUT,
                new HttpEntity<>(Map.of("exportApprovalRequired", true), headers),
                Map.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
    }

    @Test
    void super_admin_can_update_global_system_settings() {
        String token = login(SUPER_ADMIN_EMAIL, SUPER_ADMIN_PASSWORD, COMPANY);
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(token);
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.set("X-Company-Code", COMPANY);

        ResponseEntity<Map> response = rest.exchange(
                "/api/v1/admin/settings",
                HttpMethod.PUT,
                new HttpEntity<>(Map.of("exportApprovalRequired", true), headers),
                Map.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().get("success")).isEqualTo(Boolean.TRUE);
        Map<?, ?> data = (Map<?, ?>) response.getBody().get("data");
        assertThat(data).isNotNull();
        assertThat(data.get("exportApprovalRequired")).isEqualTo(Boolean.TRUE);
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

    private void assertMaskedMissingUserContractPair(ResponseEntity<Map> foreignResponse,
                                                     ResponseEntity<Map> missingResponse) {
        Map<String, Object> foreignError = assertMaskedMissingUserContract(foreignResponse);
        Map<String, Object> missingError = assertMaskedMissingUserContract(missingResponse);
        assertThat(foreignError.get("code")).isEqualTo(missingError.get("code"));
        assertThat(foreignError.get("message")).isEqualTo(missingError.get("message"));
        assertThat(foreignError.get("reason")).isEqualTo(missingError.get("reason"));
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> assertMaskedMissingUserContract(ResponseEntity<Map> response) {
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().get("success")).isEqualTo(Boolean.FALSE);
        assertThat(response.getBody().get("message")).isEqualTo("User not found");
        Object errorBody = response.getBody().get("data");
        assertThat(errorBody).isInstanceOf(Map.class);
        Map<String, Object> error = (Map<String, Object>) errorBody;
        assertThat(error.get("code")).isEqualTo("VAL_001");
        assertThat(error.get("message")).isEqualTo("User not found");
        assertThat(error.get("reason")).isEqualTo("User not found");
        assertThat(error.get("traceId")).isNotNull();
        assertThat(error.get("path")).isNotNull();
        return error;
    }

    @SuppressWarnings("unchecked")
    private void assertPlatformOnlyAccessDenied(ResponseEntity<Map> response) {
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().get("success")).isEqualTo(Boolean.FALSE);
        assertThat(response.getBody().get("message")).isEqualTo("Access denied");
        Map<String, Object> error = (Map<String, Object>) response.getBody().get("data");
        assertThat(error).isNotNull();
        assertThat(error.get("code")).isEqualTo("AUTH_004");
        assertThat(error.get("reason")).isEqualTo("SUPER_ADMIN_PLATFORM_ONLY");
        assertThat(error.get("reasonDetail")).isEqualTo(
                "Super Admin is limited to platform control-plane operations and cannot execute tenant business workflows");
    }

    private ResponseEntity<Map> executeWithTimeout(Callable<ResponseEntity<Map>> operation,
                                                   Duration timeout) throws Exception {
        ExecutorService executor = Executors.newSingleThreadExecutor();
        Future<ResponseEntity<Map>> future = executor.submit(operation);
        try {
            return future.get(timeout.toMillis(), TimeUnit.MILLISECONDS);
        } catch (TimeoutException ex) {
            throw new AssertionError("Request timed out while foreign row lock was held", ex);
        } finally {
            executor.shutdownNow();
        }
    }

    private UserRowWriteLock holdUserRowWriteLock(Long userId) throws SQLException {
        Connection connection = dataSource.getConnection();
        connection.setAutoCommit(false);
        try (PreparedStatement statement = connection.prepareStatement(
                "select id from app_users where id = ? for update")) {
            statement.setLong(1, userId);
            statement.executeQuery();
        }
        return new UserRowWriteLock(connection);
    }

    private static final class UserRowWriteLock implements AutoCloseable {
        private final Connection connection;

        private UserRowWriteLock(Connection connection) {
            this.connection = connection;
        }

        @Override
        public void close() throws SQLException {
            try {
                connection.rollback();
            } finally {
                connection.close();
            }
        }
    }
}
