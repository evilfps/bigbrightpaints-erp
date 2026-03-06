package com.bigbrightpaints.erp.modules.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.fail;

import com.bigbrightpaints.erp.modules.company.service.TenantRuntimeEnforcementService;
import com.bigbrightpaints.erp.modules.company.service.TenantRuntimeEnforcementService.TenantRuntimeSnapshot;
import com.bigbrightpaints.erp.test.AbstractIntegrationTest;
import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;

@DisplayName("Tenant runtime enforcement through auth and request filters")
class TenantRuntimeEnforcementAuthIT extends AbstractIntegrationTest {

    private static final String PASSWORD = "Passw0rd!";

    @Autowired
    private TestRestTemplate rest;

    @Autowired
    private TenantRuntimeEnforcementService tenantRuntimeEnforcementService;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Test
    void heldTenantBlocksLogin_andEmitsAuditChain() throws InterruptedException {
        Scenario scenario = seedScenario("HOLD");
        TenantRuntimeSnapshot snapshot = tenantRuntimeEnforcementService.holdTenant(
                scenario.companyCode(),
                "COMPLIANCE_REVIEW",
                "ops@bbp.com");

        ResponseEntity<Map> loginResponse = rest.postForEntity(
                "/api/v1/auth/login",
                loginPayload(scenario.email(), scenario.companyCode()),
                Map.class);

        assertThat(loginResponse.getStatusCode()).isEqualTo(HttpStatus.LOCKED);
        assertControlledRuntimeError(loginResponse, "TENANT_ON_HOLD", "Tenant is currently on hold");
        Map<String, String> metadata = awaitAccessDeniedMetadata(snapshot.auditChainId(), "TENANT_ON_HOLD");
        assertThat(metadata).containsEntry("tenantReasonCode", "COMPLIANCE_REVIEW");
    }

    @Test
    void blockedTenantBlocksAuthenticatedRuntimeRequest_withAuditChain() throws InterruptedException {
        Scenario scenario = seedScenario("BLOCK");
        String token = login(scenario.email(), scenario.companyCode());
        TenantRuntimeSnapshot snapshot = tenantRuntimeEnforcementService.blockTenant(
                scenario.companyCode(),
                "ABUSE_INCIDENT",
                "ops@bbp.com");

        ResponseEntity<Map> meResponse = rest.exchange(
                "/api/v1/auth/me",
                HttpMethod.GET,
                new HttpEntity<>(authenticatedHeaders(token, scenario.companyCode())),
                Map.class);

        assertThat(meResponse.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        assertControlledRuntimeError(meResponse, "TENANT_BLOCKED", "Tenant is currently blocked");
        Map<String, String> metadata = awaitAccessDeniedMetadata(snapshot.auditChainId(), "TENANT_BLOCKED");
        assertThat(metadata).containsEntry("tenantReasonCode", "ABUSE_INCIDENT");
    }

    @Test
    void activeUserQuotaBlocksLogin_withAuditChain() throws InterruptedException {
        String suffix = suffix();
        String companyCode = ("AUTH-ACTIVE-" + suffix).toUpperCase(Locale.ROOT);
        String firstUser = "quota-a-" + suffix.toLowerCase(Locale.ROOT) + "@bbp.com";
        String secondUser = "quota-b-" + suffix.toLowerCase(Locale.ROOT) + "@bbp.com";

        dataSeeder.ensureUser(firstUser, PASSWORD, "Quota A", companyCode, List.of("ROLE_ADMIN"));
        dataSeeder.ensureUser(secondUser, PASSWORD, "Quota B", companyCode, List.of("ROLE_ADMIN"));

        TenantRuntimeSnapshot snapshot = tenantRuntimeEnforcementService.updateQuotas(
                companyCode,
                null,
                null,
                1,
                "MAX_USERS_TEST",
                "ops@bbp.com");

        ResponseEntity<Map> loginResponse = rest.postForEntity(
                "/api/v1/auth/login",
                loginPayload(firstUser, companyCode),
                Map.class);

        assertThat(loginResponse.getStatusCode()).isEqualTo(HttpStatus.TOO_MANY_REQUESTS);
        assertControlledRuntimeError(
                loginResponse,
                "TENANT_ACTIVE_USER_QUOTA_EXCEEDED",
                "Tenant active-user quota exceeded");
        Map<String, String> metadata = awaitAccessDeniedMetadata(snapshot.auditChainId(), "TENANT_ACTIVE_USER_QUOTA_EXCEEDED");
        assertThat(metadata).containsEntry("limitType", "MAX_ACTIVE_USERS");
    }

    @Test
    void requestRateQuotaBlocksSecondAuthenticatedCall_withAuditChain() throws InterruptedException {
        Scenario scenario = seedScenario("RATE");
        String token = login(scenario.email(), scenario.companyCode());
        avoidMinuteBoundaryRace();

        TenantRuntimeSnapshot snapshot = tenantRuntimeEnforcementService.updateQuotas(
                scenario.companyCode(),
                50,
                1,
                500,
                "RATE_TEST",
                "ops@bbp.com");

        ResponseEntity<Map> firstCall = rest.exchange(
                "/api/v1/auth/me",
                HttpMethod.GET,
                new HttpEntity<>(authenticatedHeaders(token, scenario.companyCode())),
                Map.class);
        ResponseEntity<Map> secondCall = rest.exchange(
                "/api/v1/auth/me",
                HttpMethod.GET,
                new HttpEntity<>(authenticatedHeaders(token, scenario.companyCode())),
                Map.class);

        assertThat(firstCall.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(secondCall.getStatusCode()).isEqualTo(HttpStatus.TOO_MANY_REQUESTS);
        assertControlledRuntimeError(
                secondCall,
                "TENANT_REQUEST_RATE_EXCEEDED",
                "Tenant request rate quota exceeded");
        Map<String, String> metadata = awaitAccessDeniedMetadata(snapshot.auditChainId(), "TENANT_REQUEST_RATE_EXCEEDED");
        assertThat(metadata).containsEntry("limitType", "MAX_REQUESTS_PER_MINUTE");
    }

    private void assertControlledRuntimeError(ResponseEntity<Map> response,
                                              String expectedCode,
                                              String expectedMessage) {
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody()).containsEntry("success", false);
        assertThat(response.getBody()).containsEntry("message", expectedMessage);
        Object payload = response.getBody().get("data");
        assertThat(payload).isInstanceOf(Map.class);
        @SuppressWarnings("unchecked")
        Map<String, Object> error = (Map<String, Object>) payload;
        assertThat(error).containsEntry("code", expectedCode);
        assertThat(error).containsEntry("message", expectedMessage);
        assertThat(error).containsKey("traceId");
    }

    private Scenario seedScenario(String prefix) {
        String suffix = suffix();
        String companyCode = (prefix + "-" + suffix).toUpperCase(Locale.ROOT);
        String email = prefix.toLowerCase(Locale.ROOT) + "-" + suffix.toLowerCase(Locale.ROOT) + "@bbp.com";
        dataSeeder.ensureUser(email, PASSWORD, prefix + " User", companyCode, List.of("ROLE_ADMIN"));
        return new Scenario(companyCode, email);
    }

    private String login(String email, String companyCode) {
        ResponseEntity<Map> loginResponse = rest.postForEntity(
                "/api/v1/auth/login",
                loginPayload(email, companyCode),
                Map.class);
        assertThat(loginResponse.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(loginResponse.getBody()).isNotNull();
        return (String) loginResponse.getBody().get("accessToken");
    }

    private Map<String, Object> loginPayload(String email, String companyCode) {
        return Map.of(
                "email", email,
                "password", PASSWORD,
                "companyCode", companyCode);
    }

    private HttpHeaders authenticatedHeaders(String token, String companyCode) {
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(token);
        headers.set("X-Company-Code", companyCode);
        return headers;
    }

    private Map<String, String> awaitAccessDeniedMetadata(String expectedChainId, String expectedReasonCode)
            throws InterruptedException {
        String sql = """
                SELECT al.id
                FROM audit_logs al
                JOIN audit_log_metadata chain_meta
                    ON chain_meta.audit_log_id = al.id
                   AND chain_meta.metadata_key = 'auditChainId'
                JOIN audit_log_metadata reason_meta
                    ON reason_meta.audit_log_id = al.id
                   AND reason_meta.metadata_key = 'reasonCode'
                WHERE al.event_type = 'ACCESS_DENIED'
                  AND chain_meta.metadata_value = ?
                  AND reason_meta.metadata_value = ?
                ORDER BY al.timestamp DESC
                LIMIT 1
                """;
        for (int i = 0; i < 40; i++) {
            List<Long> ids = jdbcTemplate.query(
                    sql,
                    (rs, rowNum) -> rs.getLong("id"),
                    expectedChainId,
                    expectedReasonCode);
            if (!ids.isEmpty()) {
                return metadataByAuditLogId(ids.getFirst());
            }
            Thread.sleep(100);
        }
        fail("Missing ACCESS_DENIED audit event with chain %s and reason %s", expectedChainId, expectedReasonCode);
        return Map.of();
    }

    private Map<String, String> metadataByAuditLogId(Long auditLogId) {
        List<Map<String, Object>> rows = jdbcTemplate.queryForList(
                "SELECT metadata_key, metadata_value FROM audit_log_metadata WHERE audit_log_id = ?",
                auditLogId);
        Map<String, String> metadata = new HashMap<>();
        for (Map<String, Object> row : rows) {
            Object key = row.get("metadata_key");
            Object value = row.get("metadata_value");
            if (key != null) {
                metadata.put(key.toString(), value != null ? value.toString() : "");
            }
        }
        return metadata;
    }

    private void avoidMinuteBoundaryRace() throws InterruptedException {
        int second = LocalDateTime.now().getSecond();
        if (second >= 58) {
            Thread.sleep((62L - second) * 1000L);
        }
    }

    private String suffix() {
        return Long.toString(System.nanoTime(), 36);
    }

    private record Scenario(String companyCode, String email) {
    }
}
