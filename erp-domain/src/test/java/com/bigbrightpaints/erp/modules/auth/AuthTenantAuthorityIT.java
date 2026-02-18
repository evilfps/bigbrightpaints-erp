package com.bigbrightpaints.erp.modules.auth;

import com.bigbrightpaints.erp.core.audit.AuditEvent;
import com.bigbrightpaints.erp.core.audit.AuditLog;
import com.bigbrightpaints.erp.modules.company.domain.Company;
import com.bigbrightpaints.erp.modules.company.domain.CompanyLifecycleState;
import com.bigbrightpaints.erp.modules.company.domain.CompanyRepository;
import com.bigbrightpaints.erp.test.AbstractIntegrationTest;
import jakarta.persistence.EntityManager;
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

import java.util.HashMap;
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
    private EntityManager entityManager;

    @BeforeEach
    void setUp() {
        dataSeeder.ensureUser(ADMIN_EMAIL, PASSWORD, "Tenant Admin", TENANT_A, List.of("ROLE_ADMIN"));
        dataSeeder.ensureUser("other-admin@bbp.com", PASSWORD, "Other Admin", TENANT_B, List.of("ROLE_ADMIN"));
        dataSeeder.ensureUser(SUPER_ADMIN_EMAIL, PASSWORD, "Super Admin", ROOT_TENANT,
                List.of("ROLE_SUPER_ADMIN", "ROLE_ADMIN"));
        // Give super admin access to a regular tenant for delegated tenant-admin creation.
        dataSeeder.ensureUser(SUPER_ADMIN_EMAIL, PASSWORD, "Super Admin", TENANT_A,
                List.of("ROLE_SUPER_ADMIN", "ROLE_ADMIN"));
        resetTenantRuntimePolicy(TENANT_A);
        resetTenantRuntimePolicy(TENANT_B);
        resetTenantRuntimePolicy(ROOT_TENANT);
    }

    @Test
    void admin_cannot_bootstrap_new_tenant() {
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
        assertThat(companyRepository.findByCodeIgnoreCase(newCode)).isEmpty();
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
    void admin_cannot_block_tenant_lifecycle_state() {
        String token = login(ADMIN_EMAIL, TENANT_A);
        Company before = companyRepository.findByCodeIgnoreCase(TENANT_A).orElseThrow();
        Long tenantAId = before.getId();

        ResponseEntity<Map> response = updateLifecycleState(tenantAId, token, TENANT_A, "BLOCKED", "Repeated policy breach");
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        entityManager.clear();
        Company after = companyRepository.findById(tenantAId).orElseThrow();
        assertThat(after.getLifecycleState()).isEqualTo(before.getLifecycleState());
        assertThat(after.getLifecycleReason()).isEqualTo(before.getLifecycleReason());
    }

    @Test
    void super_admin_can_hold_and_block_tenant_and_runtime_enforcement_denies_access() throws InterruptedException {
        String adminToken = login(ADMIN_EMAIL, TENANT_A);
        String superToken = login(SUPER_ADMIN_EMAIL, ROOT_TENANT);
        Long tenantAId = companyRepository.findByCodeIgnoreCase(TENANT_A).map(Company::getId).orElseThrow();

        ResponseEntity<Map> baselineMe = rest.exchange(
                "/api/v1/auth/me",
                HttpMethod.GET,
                new HttpEntity<>(jsonHeaders(adminToken, TENANT_A)),
                Map.class);
        assertThat(baselineMe.getStatusCode()).isEqualTo(HttpStatus.OK);

        String holdReason = "Compliance review in progress";
        ResponseEntity<Map> holdResponse = updateLifecycleState(tenantAId, superToken, ROOT_TENANT, "HOLD", holdReason);
        assertThat(holdResponse.getStatusCode()).isEqualTo(HttpStatus.OK);
        @SuppressWarnings("unchecked")
        Map<String, Object> holdData = (Map<String, Object>) holdResponse.getBody().get("data");
        assertThat(holdData).containsEntry("lifecycleState", "HOLD");
        assertThat(holdData).containsEntry("previousLifecycleState", "ACTIVE");
        assertThat(holdData).containsEntry("reason", holdReason);

        AuditLog holdEvidence = awaitAuditEvent(AuditEvent.CONFIGURATION_CHANGED, log ->
                SUPER_ADMIN_EMAIL.equalsIgnoreCase(log.getUsername())
                        && TENANT_A.equalsIgnoreCase(log.getMetadata().get("targetCompanyCode"))
                        && "tenant-lifecycle-state-updated".equals(log.getMetadata().get("reason"))
                        && "HOLD".equalsIgnoreCase(log.getMetadata().get("companyLifecycleState"))
                        && holdReason.equals(log.getMetadata().get("companyLifecycleReason")));
        assertThat(holdEvidence.getMetadata().get("lifecycleEvidence")).isEqualTo("immutable-audit-log");

        ResponseEntity<Map> meDuringHold = rest.exchange(
                "/api/v1/auth/me",
                HttpMethod.GET,
                new HttpEntity<>(jsonHeaders(adminToken, TENANT_A)),
                Map.class);
        assertThat(meDuringHold.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);

        String blockReason = "Critical security incident";
        ResponseEntity<Map> blockResponse = updateLifecycleState(tenantAId, superToken, ROOT_TENANT, "BLOCKED", blockReason);
        assertThat(blockResponse.getStatusCode()).isEqualTo(HttpStatus.OK);

        AuditLog blockEvidence = awaitAuditEvent(AuditEvent.CONFIGURATION_CHANGED, log ->
                SUPER_ADMIN_EMAIL.equalsIgnoreCase(log.getUsername())
                        && TENANT_A.equalsIgnoreCase(log.getMetadata().get("targetCompanyCode"))
                        && "BLOCKED".equalsIgnoreCase(log.getMetadata().get("companyLifecycleState"))
                        && blockReason.equals(log.getMetadata().get("companyLifecycleReason")));
        assertThat(blockEvidence.getMetadata().get("lifecycleEvidence")).isEqualTo("immutable-audit-log");

        ResponseEntity<Map> meDuringBlock = rest.exchange(
                "/api/v1/auth/me",
                HttpMethod.GET,
                new HttpEntity<>(jsonHeaders(adminToken, TENANT_A)),
                Map.class);
        assertThat(meDuringBlock.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
    }

    @Test
    void lifecycle_state_change_requires_reason_metadata() {
        String superToken = login(SUPER_ADMIN_EMAIL, ROOT_TENANT);
        Long tenantAId = companyRepository.findByCodeIgnoreCase(TENANT_A).map(Company::getId).orElseThrow();

        ResponseEntity<Map> response = updateLifecycleState(tenantAId, superToken, ROOT_TENANT, "HOLD", " ");
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
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
    void tenant_metrics_endpoint_is_super_admin_only() {
        Long tenantAId = companyRepository.findByCodeIgnoreCase(TENANT_A).map(Company::getId).orElseThrow();

        String adminToken = login(ADMIN_EMAIL, TENANT_A);
        ResponseEntity<Map> adminResponse = rest.exchange(
                "/api/v1/companies/" + tenantAId + "/tenant-metrics",
                HttpMethod.GET,
                new HttpEntity<>(jsonHeaders(adminToken, TENANT_A)),
                Map.class);
        assertThat(adminResponse.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);

        String superAdminToken = login(SUPER_ADMIN_EMAIL, TENANT_A);
        ResponseEntity<Map> superAdminResponse = rest.exchange(
                "/api/v1/companies/" + tenantAId + "/tenant-metrics",
                HttpMethod.GET,
                new HttpEntity<>(jsonHeaders(superAdminToken, TENANT_A)),
                Map.class);
        assertThat(superAdminResponse.getStatusCode()).isEqualTo(HttpStatus.OK);

        Map<String, Object> body = superAdminResponse.getBody();
        assertThat(body).isNotNull();
        @SuppressWarnings("unchecked")
        Map<String, Object> data = (Map<String, Object>) body.get("data");
        assertThat(data).isNotNull();
        assertThat(data.get("companyCode")).isEqualTo(TENANT_A);
        assertThat(data).containsKeys(
                "lifecycleState",
                "quotaMaxActiveUsers",
                "quotaMaxApiRequests",
                "quotaMaxStorageBytes",
                "quotaMaxConcurrentSessions",
                "quotaSoftLimitEnabled",
                "quotaHardLimitEnabled",
                "activeUserCount",
                "apiActivityCount",
                "apiErrorCount",
                "apiErrorRateInBasisPoints",
                "distinctSessionCount",
                "auditStorageBytes");
        Number quotaMaxActiveUsers = (Number) data.get("quotaMaxActiveUsers");
        Number quotaMaxApiRequests = (Number) data.get("quotaMaxApiRequests");
        Number quotaMaxStorageBytes = (Number) data.get("quotaMaxStorageBytes");
        Number quotaMaxConcurrentSessions = (Number) data.get("quotaMaxConcurrentSessions");
        Boolean quotaSoftLimitEnabled = (Boolean) data.get("quotaSoftLimitEnabled");
        Boolean quotaHardLimitEnabled = (Boolean) data.get("quotaHardLimitEnabled");
        Number apiActivityCount = (Number) data.get("apiActivityCount");
        Number apiErrorCount = (Number) data.get("apiErrorCount");
        Number apiErrorRateInBasisPoints = (Number) data.get("apiErrorRateInBasisPoints");
        Number distinctSessionCount = (Number) data.get("distinctSessionCount");
        Number auditStorageBytes = (Number) data.get("auditStorageBytes");
        assertThat(quotaMaxActiveUsers).isNotNull();
        assertThat(quotaMaxApiRequests).isNotNull();
        assertThat(quotaMaxStorageBytes).isNotNull();
        assertThat(quotaMaxConcurrentSessions).isNotNull();
        assertThat(quotaSoftLimitEnabled).isNotNull();
        assertThat(quotaHardLimitEnabled).isNotNull();
        assertThat(apiActivityCount).isNotNull();
        assertThat(apiErrorCount).isNotNull();
        assertThat(apiErrorRateInBasisPoints).isNotNull();
        assertThat(distinctSessionCount).isNotNull();
        assertThat(auditStorageBytes).isNotNull();
        assertThat(quotaMaxActiveUsers.longValue()).isGreaterThanOrEqualTo(0L);
        assertThat(quotaMaxApiRequests.longValue()).isGreaterThanOrEqualTo(0L);
        assertThat(quotaMaxStorageBytes.longValue()).isGreaterThanOrEqualTo(0L);
        assertThat(quotaMaxConcurrentSessions.longValue()).isGreaterThanOrEqualTo(0L);
        assertThat(quotaSoftLimitEnabled).isFalse();
        assertThat(quotaHardLimitEnabled).isTrue();
        assertThat(apiActivityCount.longValue()).isGreaterThanOrEqualTo(apiErrorCount.longValue());
        assertThat(apiErrorRateInBasisPoints.longValue()).isBetween(0L, 10_000L);
        assertThat(distinctSessionCount.longValue()).isGreaterThanOrEqualTo(0L);
        assertThat(auditStorageBytes.longValue()).isGreaterThanOrEqualTo(0L);
    }

    @Test
    void tenant_configuration_update_is_super_admin_only() {
        Long tenantAId = companyRepository.findByCodeIgnoreCase(TENANT_A).map(Company::getId).orElseThrow();

        String adminToken = login(ADMIN_EMAIL, TENANT_A);
        ResponseEntity<Map> adminResponse = updateCompany(
                tenantAId,
                adminToken,
                TENANT_A,
                "Blocked Admin Update",
                TENANT_A,
                "UTC",
                18.0,
                120L,
                3_000L,
                2_097_152L,
                7L,
                false,
                false);
        assertThat(adminResponse.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);

        String superAdminToken = login(SUPER_ADMIN_EMAIL, TENANT_A);
        ResponseEntity<Map> superAdminResponse = updateCompany(
                tenantAId,
                superAdminToken,
                TENANT_A,
                "Allowed Super Admin Update",
                TENANT_A,
                "UTC",
                18.0,
                120L,
                3_000L,
                2_097_152L,
                7L,
                false,
                false);
        assertThat(superAdminResponse.getStatusCode()).isEqualTo(HttpStatus.OK);

        ResponseEntity<Map> metricsResponse = rest.exchange(
                "/api/v1/companies/" + tenantAId + "/tenant-metrics",
                HttpMethod.GET,
                new HttpEntity<>(jsonHeaders(superAdminToken, TENANT_A)),
                Map.class);
        assertThat(metricsResponse.getStatusCode()).isEqualTo(HttpStatus.OK);
        Map<String, Object> metricsBody = metricsResponse.getBody();
        assertThat(metricsBody).isNotNull();
        @SuppressWarnings("unchecked")
        Map<String, Object> metricsData = (Map<String, Object>) metricsBody.get("data");
        assertThat(metricsData).isNotNull();
        assertThat(metricsData).containsEntry("quotaMaxActiveUsers", 120);
        assertThat(metricsData).containsEntry("quotaMaxApiRequests", 3000);
        assertThat(metricsData).containsEntry("quotaMaxStorageBytes", 2_097_152);
        assertThat(metricsData).containsEntry("quotaMaxConcurrentSessions", 7);
        assertThat(metricsData).containsEntry("quotaSoftLimitEnabled", false);
        assertThat(metricsData).containsEntry("quotaHardLimitEnabled", true);
    }

    @Test
    void quota_hard_limit_exceeded_denies_runtime_requests() {
        Long tenantAId = companyRepository.findByCodeIgnoreCase(TENANT_A).map(Company::getId).orElseThrow();
        String adminToken = login(ADMIN_EMAIL, TENANT_A);
        String superAdminToken = login(SUPER_ADMIN_EMAIL, TENANT_A);

        ResponseEntity<Map> enforceResponse = updateCompany(
                tenantAId,
                superAdminToken,
                TENANT_A,
                "Quota Runtime Enforcement",
                TENANT_A,
                "UTC",
                18.0,
                1L,
                1_000_000L,
                1_000_000_000L,
                1_000L,
                false,
                true);
        assertThat(enforceResponse.getStatusCode()).isEqualTo(HttpStatus.OK);

        ResponseEntity<Map> runtimeBlocked = rest.exchange(
                "/api/v1/auth/me",
                HttpMethod.GET,
                new HttpEntity<>(jsonHeaders(adminToken, TENANT_A)),
                Map.class);
        assertThat(runtimeBlocked.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);

        ResponseEntity<Map> resetResponse = updateCompany(
                tenantAId,
                superAdminToken,
                TENANT_A,
                "Quota Runtime Enforcement Reset",
                TENANT_A,
                "UTC",
                18.0,
                120L,
                3_000L,
                2_097_152L,
                7L,
                false,
                true);
        assertThat(resetResponse.getStatusCode()).isEqualTo(HttpStatus.OK);
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

    private ResponseEntity<Map> updateLifecycleState(Long companyId,
                                                     String token,
                                                     String companyCode,
                                                     String state,
                                                     String reason) {
        return rest.exchange(
                "/api/v1/companies/" + companyId + "/lifecycle-state",
                HttpMethod.POST,
                new HttpEntity<>(Map.of(
                        "state", state,
                        "reason", reason
                ), jsonHeaders(token, companyCode)),
                Map.class);
    }

    private ResponseEntity<Map> updateCompany(Long companyId,
                                              String token,
                                              String companyCode,
                                              String name,
                                              String code,
                                              String timezone,
                                              double defaultGstRate) {
        return updateCompany(
                companyId,
                token,
                companyCode,
                name,
                code,
                timezone,
                defaultGstRate,
                null,
                null,
                null,
                null,
                null,
                null);
    }

    private ResponseEntity<Map> updateCompany(Long companyId,
                                              String token,
                                              String companyCode,
                                              String name,
                                              String code,
                                              String timezone,
                                              double defaultGstRate,
                                              Long quotaMaxActiveUsers,
                                              Long quotaMaxApiRequests,
                                              Long quotaMaxStorageBytes,
                                              Long quotaMaxConcurrentSessions,
                                              Boolean quotaSoftLimitEnabled,
                                              Boolean quotaHardLimitEnabled) {
        Map<String, Object> payload = new HashMap<>();
        payload.put("name", name);
        payload.put("code", code);
        payload.put("timezone", timezone);
        payload.put("defaultGstRate", defaultGstRate);
        if (quotaMaxActiveUsers != null) {
            payload.put("quotaMaxActiveUsers", quotaMaxActiveUsers);
        }
        if (quotaMaxApiRequests != null) {
            payload.put("quotaMaxApiRequests", quotaMaxApiRequests);
        }
        if (quotaMaxStorageBytes != null) {
            payload.put("quotaMaxStorageBytes", quotaMaxStorageBytes);
        }
        if (quotaMaxConcurrentSessions != null) {
            payload.put("quotaMaxConcurrentSessions", quotaMaxConcurrentSessions);
        }
        if (quotaSoftLimitEnabled != null) {
            payload.put("quotaSoftLimitEnabled", quotaSoftLimitEnabled);
        }
        if (quotaHardLimitEnabled != null) {
            payload.put("quotaHardLimitEnabled", quotaHardLimitEnabled);
        }
        return rest.exchange(
                "/api/v1/companies/" + companyId,
                HttpMethod.PUT,
                new HttpEntity<>(payload, jsonHeaders(token, companyCode)),
                Map.class);
    }

    private void resetTenantRuntimePolicy(String companyCode) {
        companyRepository.findByCodeIgnoreCase(companyCode).ifPresent(company -> {
            company.setLifecycleState(CompanyLifecycleState.ACTIVE);
            company.setLifecycleReason("baseline-active");
            company.setQuotaMaxActiveUsers(1_000L);
            company.setQuotaMaxApiRequests(1_000_000L);
            company.setQuotaMaxStorageBytes(1_000_000_000L);
            company.setQuotaMaxConcurrentSessions(1_000L);
            company.setQuotaSoftLimitEnabled(false);
            company.setQuotaHardLimitEnabled(true);
            companyRepository.save(company);
        });
    }

    private AuditLog awaitAuditEvent(AuditEvent eventType, Predicate<AuditLog> matcher) throws InterruptedException {
        for (int i = 0; i < 40; i++) {
            List<AuditLog> logs = entityManager.createQuery(
                            "select distinct al from AuditLog al left join fetch al.metadata where al.eventType = :eventType order by al.timestamp desc",
                            AuditLog.class)
                    .setParameter("eventType", eventType)
                    .getResultList();
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
