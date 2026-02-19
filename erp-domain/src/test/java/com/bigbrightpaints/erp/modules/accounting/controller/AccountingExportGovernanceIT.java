package com.bigbrightpaints.erp.modules.accounting.controller;

import com.bigbrightpaints.erp.core.audit.AuditEvent;
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
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.jdbc.core.JdbcTemplate;

import java.lang.reflect.Method;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.fail;

class AccountingExportGovernanceIT extends AbstractIntegrationTest {

    private static final String COMPANY_CODE = "ACC-EXPORT-GOV";
    private static final String ADMIN_EMAIL = "acc-export-admin@bbp.com";
    private static final String ACCOUNTING_EMAIL = "acc-export-accounting@bbp.com";
    private static final String PASSWORD = "ExportGov123!";

    @Autowired
    private TestRestTemplate rest;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @BeforeEach
    void setupUsers() {
        dataSeeder.ensureUser(ADMIN_EMAIL, PASSWORD, "Export Governance Admin", COMPANY_CODE, List.of("ROLE_ADMIN"));
        dataSeeder.ensureUser(ACCOUNTING_EMAIL, PASSWORD, "Export Governance Accounting", COMPANY_CODE, List.of("ROLE_ACCOUNTING"));
    }

    @Test
    void exportEndpoints_areFailClosedToAdminRoleOnly() throws Exception {
        assertMethodIsAdminOnly("dealerStatementPdf", Long.class, String.class, String.class);
        assertMethodIsAdminOnly("supplierStatementPdf", Long.class, String.class, String.class);
        assertMethodIsAdminOnly("dealerAgingPdf", Long.class, String.class, String.class);
        assertMethodIsAdminOnly("supplierAgingPdf", Long.class, String.class, String.class);
        assertMethodIsAdminOnly("auditDigestCsv", String.class, String.class);
    }

    @Test
    void auditDigestCsv_requiresAdminAndLogsExport() throws Exception {
        HttpHeaders accountingHeaders = authHeaders(ACCOUNTING_EMAIL);
        ResponseEntity<String> accountingResponse = rest.exchange(
                "/api/v1/accounting/audit/digest.csv?from=2026-01-01&to=2026-01-31",
                HttpMethod.GET,
                new HttpEntity<>(accountingHeaders),
                String.class
        );
        assertThat(accountingResponse.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);

        HttpHeaders adminHeaders = authHeaders(ADMIN_EMAIL);
        ResponseEntity<String> adminResponse = rest.exchange(
                "/api/v1/accounting/audit/digest.csv?from=2026-01-01&to=2026-01-31",
                HttpMethod.GET,
                new HttpEntity<>(adminHeaders),
                String.class
        );
        assertThat(adminResponse.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(adminResponse.getHeaders().getFirst(HttpHeaders.CONTENT_DISPOSITION))
                .contains("audit-digest.csv");

        assertExportAuditMetadata(ADMIN_EMAIL, "ACCOUNTING_AUDIT_DIGEST", "EXPORT", "csv");
    }

    private void assertMethodIsAdminOnly(String methodName, Class<?>... parameterTypes) throws Exception {
        Method method = AccountingController.class.getMethod(methodName, parameterTypes);
        PreAuthorize preAuthorize = method.getAnnotation(PreAuthorize.class);
        assertThat(preAuthorize).isNotNull();
        assertThat(preAuthorize.value()).isEqualTo("hasAuthority('ROLE_ADMIN')");
    }

    private HttpHeaders authHeaders(String email) {
        Map<String, Object> payload = Map.of(
                "email", email,
                "password", PASSWORD,
                "companyCode", COMPANY_CODE
        );
        ResponseEntity<Map> login = rest.postForEntity("/api/v1/auth/login", payload, Map.class);
        assertThat(login.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(login.getBody()).isNotNull();

        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth((String) login.getBody().get("accessToken"));
        headers.set("X-Company-Id", COMPANY_CODE);
        return headers;
    }

    private void assertExportAuditMetadata(String username,
                                           String resourceType,
                                           String operation,
                                           String format) throws InterruptedException {
        for (int attempt = 0; attempt < 30; attempt++) {
            Integer matchCount = jdbcTemplate.queryForObject("""
                    SELECT COUNT(*)
                    FROM audit_logs al
                    JOIN audit_log_metadata resource_type
                      ON resource_type.audit_log_id = al.id
                     AND resource_type.metadata_key = 'resourceType'
                     AND resource_type.metadata_value = ?
                    JOIN audit_log_metadata operation_type
                      ON operation_type.audit_log_id = al.id
                     AND operation_type.metadata_key = 'operation'
                     AND operation_type.metadata_value = ?
                    JOIN audit_log_metadata export_format
                      ON export_format.audit_log_id = al.id
                     AND export_format.metadata_key = 'format'
                     AND lower(export_format.metadata_value) = lower(?)
                    WHERE al.event_type = ?
                      AND lower(al.username) = lower(?)
                    """, Integer.class, resourceType, operation, format, AuditEvent.DATA_EXPORT.name(), username);
            if (matchCount != null && matchCount > 0) {
                return;
            }
            Thread.sleep(100);
        }
        fail("Expected DATA_EXPORT audit log metadata for " + resourceType + " by " + username);
    }
}
