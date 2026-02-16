package com.bigbrightpaints.erp.modules.auth;

import com.bigbrightpaints.erp.core.audit.AuditEvent;
import com.bigbrightpaints.erp.core.audit.AuditLog;
import com.bigbrightpaints.erp.core.audit.AuditLogRepository;
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
import java.util.function.Predicate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.fail;

class AuthTenantAuthorityIT extends AbstractIntegrationTest {

    private static final String TENANT_A = "AUTH-TENANT-A";
    private static final String TENANT_B = "AUTH-TENANT-B";
    private static final String ROOT_TENANT = "AUTH-ROOT";

    private static final String ADMIN_EMAIL = "tenant-admin@bbp.com";
    private static final String SUPER_ADMIN_EMAIL = "super-admin@bbp.com";
    private static final String PASSWORD = "Passw0rd!";

    @Autowired
    private TestRestTemplate rest;

    @Autowired
    private CompanyRepository companyRepository;

    @Autowired
    private AuditLogRepository auditLogRepository;

    @BeforeEach
    void setUp() {
        dataSeeder.ensureUser(ADMIN_EMAIL, PASSWORD, "Tenant Admin", TENANT_A, List.of("ROLE_ADMIN"));
        dataSeeder.ensureUser("other-admin@bbp.com", PASSWORD, "Other Admin", TENANT_B, List.of("ROLE_ADMIN"));
        dataSeeder.ensureUser(SUPER_ADMIN_EMAIL, PASSWORD, "Super Admin", ROOT_TENANT,
                List.of("ROLE_SUPER_ADMIN", "ROLE_ADMIN"));
        // Give super admin access to a regular tenant for delegated tenant-admin creation.
        dataSeeder.ensureUser(SUPER_ADMIN_EMAIL, PASSWORD, "Super Admin", TENANT_A,
                List.of("ROLE_SUPER_ADMIN", "ROLE_ADMIN"));
    }

    @Test
    void admin_cannot_bootstrap_new_tenant() throws InterruptedException {
        String token = login(ADMIN_EMAIL, TENANT_A);
        String newCode = "TEN-BOOT-" + System.nanoTime();

        ResponseEntity<Map> response = rest.exchange(
                "/api/v1/companies",
                HttpMethod.POST,
                new HttpEntity<>(Map.of(
                        "name", "Blocked Bootstrap",
                        "code", newCode,
                        "timezone", "UTC",
                        "defaultGstRate", 18.0
                ), jsonHeaders(token, TENANT_A)),
                Map.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);

        AuditLog denied = awaitAuditEvent(AuditEvent.ACCESS_DENIED, log ->
                ADMIN_EMAIL.equalsIgnoreCase(log.getUsername())
                        && "super-admin-required-for-tenant-bootstrap".equals(log.getMetadata().get("reason"))
                        && newCode.equalsIgnoreCase(log.getMetadata().get("targetCompanyCode")));
        assertThat(denied.getMetadata()).containsEntry("actor", ADMIN_EMAIL);
        assertThat(denied.getMetadata().get("tenantScope")).contains(TENANT_A);
    }

    @Test
    void super_admin_can_bootstrap_new_tenant() throws InterruptedException {
        String token = login(SUPER_ADMIN_EMAIL, ROOT_TENANT);
        String newCode = "TEN-ALLOW-" + System.nanoTime();

        ResponseEntity<Map> response = rest.exchange(
                "/api/v1/companies",
                HttpMethod.POST,
                new HttpEntity<>(Map.of(
                        "name", "Allowed Bootstrap",
                        "code", newCode,
                        "timezone", "UTC",
                        "defaultGstRate", 18.0
                ), jsonHeaders(token, ROOT_TENANT)),
                Map.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        Company saved = companyRepository.findByCodeIgnoreCase(newCode).orElseThrow();
        assertThat(saved.getCode()).isEqualTo(newCode);

        AuditLog granted = awaitAuditEvent(AuditEvent.ACCESS_GRANTED, log ->
                SUPER_ADMIN_EMAIL.equalsIgnoreCase(log.getUsername())
                        && "tenant-bootstrap-created".equals(log.getMetadata().get("reason"))
                        && newCode.equalsIgnoreCase(log.getMetadata().get("targetCompanyCode")));
        assertThat(granted.getMetadata()).containsEntry("actor", SUPER_ADMIN_EMAIL);
        assertThat(granted.getMetadata().get("tenantScope")).contains(ROOT_TENANT);
    }

    @Test
    void admin_cannot_create_tenant_admin_user() throws InterruptedException {
        String token = login(ADMIN_EMAIL, TENANT_A);
        Long tenantAId = companyRepository.findByCodeIgnoreCase(TENANT_A).map(Company::getId).orElseThrow();
        String candidateEmail = "candidate-" + System.nanoTime() + "@bbp.com";

        ResponseEntity<Map> response = rest.exchange(
                "/api/v1/admin/users",
                HttpMethod.POST,
                new HttpEntity<>(Map.of(
                        "email", candidateEmail,
                        "password", "ChangeMe123!",
                        "displayName", "Tenant Admin Candidate",
                        "companyIds", List.of(tenantAId),
                        "roles", List.of("ROLE_ADMIN")
                ), jsonHeaders(token, TENANT_A)),
                Map.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);

        AuditLog denied = awaitAuditEvent(AuditEvent.ACCESS_DENIED, log ->
                ADMIN_EMAIL.equalsIgnoreCase(log.getUsername())
                        && "tenant-admin-role-management-requires-super-admin".equals(log.getMetadata().get("reason"))
                        && "ROLE_ADMIN".equalsIgnoreCase(log.getMetadata().get("targetRole")));
        assertThat(denied.getMetadata()).containsEntry("actor", ADMIN_EMAIL);
        assertThat(denied.getMetadata().get("tenantScope")).contains(TENANT_A);
    }

    @Test
    void super_admin_can_create_tenant_admin_user() {
        String token = login(SUPER_ADMIN_EMAIL, TENANT_A);
        Long tenantAId = companyRepository.findByCodeIgnoreCase(TENANT_A).map(Company::getId).orElseThrow();
        String candidateEmail = "super-candidate-" + System.nanoTime() + "@bbp.com";

        ResponseEntity<Map> response = rest.exchange(
                "/api/v1/admin/users",
                HttpMethod.POST,
                new HttpEntity<>(Map.of(
                        "email", candidateEmail,
                        "password", "ChangeMe123!",
                        "displayName", "Created By Super Admin",
                        "companyIds", List.of(tenantAId),
                        "roles", List.of("ROLE_ADMIN")
                ), jsonHeaders(token, TENANT_A)),
                Map.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        Map<String, Object> body = response.getBody();
        assertThat(body).isNotNull();
        @SuppressWarnings("unchecked")
        Map<String, Object> data = (Map<String, Object>) body.get("data");
        assertThat(data.get("email")).isEqualTo(candidateEmail);
        @SuppressWarnings("unchecked")
        List<String> roles = (List<String>) data.get("roles");
        assertThat(roles).contains("ROLE_ADMIN");
    }

    @Test
    void tenant_mismatch_and_cross_tenant_idor_fail_closed() {
        String token = login(ADMIN_EMAIL, TENANT_A);
        Long tenantBId = companyRepository.findByCodeIgnoreCase(TENANT_B).map(Company::getId).orElseThrow();

        HttpHeaders mismatchHeaders = jsonHeaders(token, TENANT_B);
        ResponseEntity<Map> mismatchMe = rest.exchange(
                "/api/v1/auth/me",
                HttpMethod.GET,
                new HttpEntity<>(mismatchHeaders),
                Map.class);
        assertThat(mismatchMe.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);

        ResponseEntity<Map> unauthenticatedWithHeader = rest.exchange(
                "/api/v1/auth/me",
                HttpMethod.GET,
                new HttpEntity<>(jsonHeaders(null, TENANT_A)),
                Map.class);
        assertThat(unauthenticatedWithHeader.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);

        ResponseEntity<Map> idorUpdate = rest.exchange(
                "/api/v1/companies/" + tenantBId,
                HttpMethod.PUT,
                new HttpEntity<>(Map.of(
                        "name", "Cross Tenant Update",
                        "code", TENANT_B,
                        "timezone", "UTC",
                        "defaultGstRate", 18.0
                ), jsonHeaders(token, TENANT_A)),
                Map.class);
        assertThat(idorUpdate.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
    }

    private String login(String email, String companyCode) {
        ResponseEntity<Map> response = rest.postForEntity("/api/v1/auth/login", Map.of(
                "email", email,
                "password", PASSWORD,
                "companyCode", companyCode
        ), Map.class);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        Map<String, Object> body = response.getBody();
        assertThat(body).isNotNull();
        return body.get("accessToken").toString();
    }

    private HttpHeaders jsonHeaders(String token, String companyCode) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        if (token != null && !token.isBlank()) {
            headers.setBearerAuth(token);
        }
        if (companyCode != null && !companyCode.isBlank()) {
            headers.set("X-Company-Code", companyCode);
        }
        return headers;
    }

    private AuditLog awaitAuditEvent(AuditEvent eventType, Predicate<AuditLog> matcher) throws InterruptedException {
        for (int i = 0; i < 40; i++) {
            List<AuditLog> logs = auditLogRepository.findByEventTypeOrderByTimestampDesc(eventType);
            for (AuditLog log : logs) {
                if (matcher.test(log)) {
                    return log;
                }
            }
            Thread.sleep(100);
        }
        fail("Audit event %s not found with expected metadata", eventType);
        return null;
    }
}
