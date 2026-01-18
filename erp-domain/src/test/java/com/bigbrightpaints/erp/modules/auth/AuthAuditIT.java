package com.bigbrightpaints.erp.modules.auth;

import com.bigbrightpaints.erp.core.audit.AuditEvent;
import com.bigbrightpaints.erp.core.audit.AuditLog;
import com.bigbrightpaints.erp.core.audit.AuditLogRepository;
import com.bigbrightpaints.erp.modules.company.domain.Company;
import com.bigbrightpaints.erp.modules.company.domain.CompanyRepository;
import com.bigbrightpaints.erp.test.AbstractIntegrationTest;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.fail;

@DisplayName("Auth audit: login events")
public class AuthAuditIT extends AbstractIntegrationTest {

    private static final String PASSWORD = "Passw0rd!";

    @Autowired
    private TestRestTemplate rest;

    @Autowired
    private AuditLogRepository auditLogRepository;

    @Autowired
    private CompanyRepository companyRepository;

    @Test
    void login_success_logs_company_audit() throws InterruptedException {
        String suffix = Long.toString(System.nanoTime());
        String companyCode = "AUTH-AUD-" + suffix;
        String email = "audit-" + suffix + "@bbp.com";

        dataSeeder.ensureUser(email, PASSWORD, "Audit User", companyCode, List.of("ROLE_ADMIN"));
        Company company = companyRepository.findByCodeIgnoreCase(companyCode).orElseThrow();

        ResponseEntity<Map> loginResp = rest.postForEntity("/api/v1/auth/login",
                Map.of("email", email, "password", PASSWORD, "companyCode", companyCode),
                Map.class);
        assertThat(loginResp.getStatusCode()).isEqualTo(HttpStatus.OK);

        AuditLog log = awaitAuditEvent(AuditEvent.LOGIN_SUCCESS, email);
        assertThat(log.getCompanyId()).isEqualTo(company.getId());
    }

    private AuditLog awaitAuditEvent(AuditEvent eventType, String username) throws InterruptedException {
        for (int i = 0; i < 30; i++) {
            List<AuditLog> logs = auditLogRepository.findByEventTypeOrderByTimestampDesc(eventType);
            for (AuditLog log : logs) {
                if (username.equalsIgnoreCase(log.getUsername())) {
                    return log;
                }
            }
            Thread.sleep(100);
        }
        fail("Audit event not recorded for user %s and event %s", username, eventType);
        return null;
    }
}
