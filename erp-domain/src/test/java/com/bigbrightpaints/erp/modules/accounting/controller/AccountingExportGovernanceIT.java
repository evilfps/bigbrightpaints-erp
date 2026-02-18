package com.bigbrightpaints.erp.modules.accounting.controller;

import com.bigbrightpaints.erp.core.audit.AuditEvent;
import com.bigbrightpaints.erp.core.audit.AuditLog;
import com.bigbrightpaints.erp.core.audit.AuditLogRepository;
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
import org.springframework.transaction.support.TransactionTemplate;

import java.util.HashMap;
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
    private AuditLogRepository auditLogRepository;

    @Autowired
    private TransactionTemplate transactionTemplate;

    @BeforeEach
    void setupUsers() {
        dataSeeder.ensureUser(ADMIN_EMAIL, PASSWORD, "Export Governance Admin", COMPANY_CODE, List.of("ROLE_ADMIN"));
        dataSeeder.ensureUser(ACCOUNTING_EMAIL, PASSWORD, "Export Governance Accounting", COMPANY_CODE, List.of("ROLE_ACCOUNTING"));
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

        Map<String, String> exportMetadata = awaitDataExportMetadata(ADMIN_EMAIL, "ACCOUNTING_AUDIT_DIGEST");
        assertThat(exportMetadata)
                .containsEntry("operation", "EXPORT")
                .containsEntry("format", "csv")
                .containsEntry("resourceType", "ACCOUNTING_AUDIT_DIGEST");
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

    private Map<String, String> awaitDataExportMetadata(String username, String resourceType) throws InterruptedException {
        for (int attempt = 0; attempt < 30; attempt++) {
            Map<String, String> metadata = transactionTemplate.execute(status -> {
                List<AuditLog> exportLogs = auditLogRepository.findByEventTypeOrderByTimestampDesc(AuditEvent.DATA_EXPORT);
                for (AuditLog log : exportLogs) {
                    if (!username.equalsIgnoreCase(log.getUsername())) {
                        continue;
                    }
                    Map<String, String> snapshot = new HashMap<>(log.getMetadata());
                    if (resourceType.equals(snapshot.get("resourceType"))) {
                        return snapshot;
                    }
                }
                return null;
            });
            if (metadata != null) {
                return metadata;
            }
            Thread.sleep(100);
        }
        fail("Expected DATA_EXPORT audit log for " + resourceType + " by " + username);
        return null;
    }
}
