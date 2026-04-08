package com.bigbrightpaints.erp.modules.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.fail;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;

import com.bigbrightpaints.erp.core.audit.AuditEvent;
import com.bigbrightpaints.erp.core.audit.AuditLog;
import com.bigbrightpaints.erp.core.audit.AuditLogRepository;
import com.bigbrightpaints.erp.modules.company.domain.Company;
import com.bigbrightpaints.erp.modules.company.domain.CompanyRepository;
import com.bigbrightpaints.erp.test.AbstractIntegrationTest;
import com.bigbrightpaints.erp.test.support.TotpTestUtils;

@DisplayName("Auth audit: login events")
public class AuthAuditIT extends AbstractIntegrationTest {

  private static final String PASSWORD = "Passw0rd!";

  @Autowired private TestRestTemplate rest;

  @Autowired private AuditLogRepository auditLogRepository;

  @Autowired private CompanyRepository companyRepository;

  @Test
  void login_success_logs_company_audit() throws InterruptedException {
    String suffix = Long.toString(System.nanoTime());
    String companyCode = "AUTH-AUD-" + suffix;
    String email = "audit-" + suffix + "@bbp.com";

    dataSeeder.ensureUser(email, PASSWORD, "Audit User", companyCode, List.of("ROLE_ADMIN"));
    Company company = companyRepository.findByCodeIgnoreCase(companyCode).orElseThrow();

    ResponseEntity<Map> loginResp =
        rest.postForEntity(
            "/api/v1/auth/login",
            Map.of("email", email, "password", PASSWORD, "companyCode", companyCode),
            Map.class);
    assertThat(loginResp.getStatusCode()).isEqualTo(HttpStatus.OK);

    AuditLog log = awaitAuditEvent(AuditEvent.LOGIN_SUCCESS, email);
    assertThat(log.getCompanyId()).isEqualTo(company.getId());
  }

  @Test
  void password_change_logs_password_changed_audit_event() throws InterruptedException {
    String suffix = Long.toString(System.nanoTime());
    String companyCode = "AUTH-PWD-" + suffix;
    String email = "pwd-" + suffix + "@bbp.com";
    String newPassword = "Passw0rd!2";

    dataSeeder.ensureUser(email, PASSWORD, "Password User", companyCode, List.of("ROLE_ADMIN"));
    Company company = companyRepository.findByCodeIgnoreCase(companyCode).orElseThrow();
    String token = loginToken(email, PASSWORD, companyCode);

    ResponseEntity<Map> response =
        rest.exchange(
            "/api/v1/auth/password/change",
            HttpMethod.POST,
            new HttpEntity<>(
                Map.of(
                    "currentPassword",
                    PASSWORD,
                    "newPassword",
                    newPassword,
                    "confirmPassword",
                    newPassword),
                bearerJson(token, companyCode)),
            Map.class);

    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
    AuditLog log = awaitAuditEvent(AuditEvent.PASSWORD_CHANGED, email);
    assertThat(log.getCompanyId()).isEqualTo(company.getId());
    assertThat(log.getMetadata()).containsEntry("operation", "password_change");
    assertThat(log.getMetadata()).containsEntry("outcome", "password_updated");
  }

  @Test
  void mfa_profile_changes_log_enrollment_activation_and_disable_events()
      throws InterruptedException {
    String suffix = Long.toString(System.nanoTime());
    String companyCode = "AUTH-MFA-" + suffix;
    String email = "mfa-audit-" + suffix + "@bbp.com";

    dataSeeder.ensureUser(email, PASSWORD, "Mfa Audit User", companyCode, List.of("ROLE_ADMIN"));
    Company company = companyRepository.findByCodeIgnoreCase(companyCode).orElseThrow();
    String token = loginToken(email, PASSWORD, companyCode);

    ResponseEntity<Map> setupResp =
        rest.exchange(
            "/api/v1/auth/mfa/setup",
            HttpMethod.POST,
            new HttpEntity<>(null, bearerJson(token, companyCode)),
            Map.class);
    assertThat(setupResp.getStatusCode()).isEqualTo(HttpStatus.OK);
    @SuppressWarnings("unchecked")
    Map<String, Object> setupData = (Map<String, Object>) setupResp.getBody().get("data");
    String secret = setupData.get("secret").toString();

    ResponseEntity<Map> activateResp =
        rest.exchange(
            "/api/v1/auth/mfa/activate",
            HttpMethod.POST,
            new HttpEntity<>(
                Map.of("code", TotpTestUtils.generateCurrentCode(secret)),
                bearerJson(token, companyCode)),
            Map.class);
    assertThat(activateResp.getStatusCode()).isEqualTo(HttpStatus.OK);

    ResponseEntity<Map> disableResp =
        rest.exchange(
            "/api/v1/auth/mfa/disable",
            HttpMethod.POST,
            new HttpEntity<>(
                Map.of("code", TotpTestUtils.generateCurrentCode(secret)),
                bearerJson(token, companyCode)),
            Map.class);
    assertThat(disableResp.getStatusCode()).isEqualTo(HttpStatus.OK);

    AuditLog enrolled = awaitAuditEvent(AuditEvent.MFA_ENROLLED, email);
    AuditLog activated = awaitAuditEvent(AuditEvent.MFA_ACTIVATED, email);
    AuditLog disabled = awaitAuditEvent(AuditEvent.MFA_DISABLED, email);
    assertThat(enrolled.getCompanyId()).isEqualTo(company.getId());
    assertThat(activated.getCompanyId()).isEqualTo(company.getId());
    assertThat(disabled.getCompanyId()).isEqualTo(company.getId());
  }

  private String loginToken(String email, String password, String companyCode) {
    ResponseEntity<Map> response =
        rest.postForEntity(
            "/api/v1/auth/login",
            Map.of("email", email, "password", password, "companyCode", companyCode),
            Map.class);
    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
    assertThat(response.getBody()).isNotNull();
    return response.getBody().get("accessToken").toString();
  }

  private HttpHeaders bearerJson(String token, String companyCode) {
    HttpHeaders headers = new HttpHeaders();
    headers.setBearerAuth(token);
    headers.setContentType(MediaType.APPLICATION_JSON);
    headers.set("X-Company-Code", companyCode);
    return headers;
  }

  private AuditLog awaitAuditEvent(AuditEvent eventType, String username)
      throws InterruptedException {
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
