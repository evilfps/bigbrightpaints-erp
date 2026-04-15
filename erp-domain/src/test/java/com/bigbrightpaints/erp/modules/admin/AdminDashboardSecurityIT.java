package com.bigbrightpaints.erp.modules.admin;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;

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

import com.bigbrightpaints.erp.core.audit.AuditEvent;
import com.bigbrightpaints.erp.core.audit.AuditLog;
import com.bigbrightpaints.erp.core.audit.AuditLogRepository;
import com.bigbrightpaints.erp.core.audit.AuditStatus;
import com.bigbrightpaints.erp.modules.auth.domain.UserAccountRepository;
import com.bigbrightpaints.erp.modules.company.domain.CompanyRepository;
import com.bigbrightpaints.erp.test.AbstractIntegrationTest;
import com.bigbrightpaints.erp.test.support.ErpApiRoutes;

@Tag("critical")
class AdminDashboardSecurityIT extends AbstractIntegrationTest {

  private static final String COMPANY_CODE = "ADMIN-DASH";
  private static final String ROOT_COMPANY_CODE = "ROOT";
  private static final String PASSWORD = "AdminDash123!";
  private static final String ADMIN_EMAIL = "dashboard-admin@bbp.com";
  private static final String ACCOUNTING_EMAIL = "dashboard-accounting@bbp.com";
  private static final String SUPER_ADMIN_EMAIL = "dashboard-superadmin@bbp.com";
  private static final String ROOT_SUPER_ADMIN_EMAIL = "dashboard-root-superadmin@bbp.com";

  @Autowired private TestRestTemplate rest;
  @Autowired private CompanyRepository companyRepository;
  @Autowired private AuditLogRepository auditLogRepository;
  @Autowired private UserAccountRepository userAccountRepository;

  @BeforeEach
  void setUpUsers() {
    dataSeeder.ensureUser(ADMIN_EMAIL, PASSWORD, "Dashboard Admin", COMPANY_CODE, List.of("ROLE_ADMIN"));
    dataSeeder.ensureUser(
        ACCOUNTING_EMAIL,
        PASSWORD,
        "Dashboard Accounting",
        COMPANY_CODE,
        List.of("ROLE_ACCOUNTING"));
    dataSeeder.ensureUser(
        SUPER_ADMIN_EMAIL,
        PASSWORD,
        "Dashboard Super Admin",
        COMPANY_CODE,
        List.of("ROLE_SUPER_ADMIN", "ROLE_ADMIN"));
    dataSeeder.ensureUser(
        ROOT_SUPER_ADMIN_EMAIL,
        PASSWORD,
        "Dashboard Root Super Admin",
        ROOT_COMPANY_CODE,
        List.of("ROLE_SUPER_ADMIN"));
  }

  @Test
  void dashboard_allows_only_tenant_admin_role() {
    ResponseEntity<Map> adminResponse =
        rest.exchange(
            ErpApiRoutes.ADMIN_DASHBOARD,
            HttpMethod.GET,
            new HttpEntity<>(headersFor(ADMIN_EMAIL)),
            Map.class);
    assertThat(adminResponse.getStatusCode()).isEqualTo(HttpStatus.OK);
    assertThat(adminResponse.getBody()).isNotNull();
    assertThat(adminResponse.getBody().get("success")).isEqualTo(Boolean.TRUE);
    @SuppressWarnings("unchecked")
    Map<String, Object> data = (Map<String, Object>) adminResponse.getBody().get("data");
    assertThat(data).isNotNull();
    assertThat(data).containsKeys("approvalSummary", "userSummary", "supportSummary", "tenantRuntime");

    ResponseEntity<Map> accountingResponse =
        rest.exchange(
            ErpApiRoutes.ADMIN_DASHBOARD,
            HttpMethod.GET,
            new HttpEntity<>(headersFor(ACCOUNTING_EMAIL)),
            Map.class);
    assertThat(accountingResponse.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);

    ResponseEntity<Map> superAdminResponse =
        rest.exchange(
            ErpApiRoutes.ADMIN_DASHBOARD,
            HttpMethod.GET,
            new HttpEntity<>(headersFor(SUPER_ADMIN_EMAIL)),
            Map.class);
    assertThat(superAdminResponse.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
  }

  @Test
  void dashboard_hides_privileged_identity_counts_and_activity() {
    // Seed a same-tenant privileged actor event that must stay hidden from tenant-admin dashboard.
    headersFor(SUPER_ADMIN_EMAIL);

    ResponseEntity<Map> adminResponse =
        rest.exchange(
            ErpApiRoutes.ADMIN_DASHBOARD,
            HttpMethod.GET,
            new HttpEntity<>(headersFor(ADMIN_EMAIL)),
            Map.class);
    assertThat(adminResponse.getStatusCode()).isEqualTo(HttpStatus.OK);
    assertThat(adminResponse.getBody()).isNotNull();

    @SuppressWarnings("unchecked")
    Map<String, Object> data = (Map<String, Object>) adminResponse.getBody().get("data");
    assertThat(data).isNotNull();

    @SuppressWarnings("unchecked")
    Map<String, Object> userSummary = (Map<String, Object>) data.get("userSummary");
    assertThat(userSummary).isNotNull();
    assertThat(((Number) userSummary.get("totalUsers")).longValue()).isEqualTo(1L);
    assertThat(((Number) userSummary.get("enabledUsers")).longValue()).isEqualTo(1L);
    assertThat(((Number) userSummary.get("disabledUsers")).longValue()).isEqualTo(0L);

    @SuppressWarnings("unchecked")
    List<Map<String, Object>> recentActivity = (List<Map<String, Object>>) data.get("recentActivity");
    assertThat(recentActivity).isNotNull();
    assertThat(recentActivity)
        .extracting(item -> String.valueOf(item.get("actor")).trim().toLowerCase())
        .doesNotContain(SUPER_ADMIN_EMAIL.toLowerCase());
  }

  @Test
  void dashboard_hides_platform_superadmin_activity_rows() {
    Long tenantId = tenantCompanyId();

    ResponseEntity<Map> warningResponse =
        rest.exchange(
            "/api/v1/superadmin/tenants/" + tenantId + "/support/warnings",
            HttpMethod.POST,
            new HttpEntity<>(
                Map.of(
                    "warningCategory", "RUNTIME",
                    "message", "visibility-check",
                    "requestedLifecycleState", "SUSPENDED",
                    "gracePeriodHours", 24),
                headersFor(ROOT_SUPER_ADMIN_EMAIL, ROOT_COMPANY_CODE)),
            Map.class);
    assertThat(warningResponse.getStatusCode()).isEqualTo(HttpStatus.OK);

    ResponseEntity<Map> adminResponse =
        rest.exchange(
            ErpApiRoutes.ADMIN_DASHBOARD,
            HttpMethod.GET,
            new HttpEntity<>(headersFor(ADMIN_EMAIL)),
            Map.class);
    assertThat(adminResponse.getStatusCode()).isEqualTo(HttpStatus.OK);
    assertThat(adminResponse.getBody()).isNotNull();

    @SuppressWarnings("unchecked")
    Map<String, Object> data = (Map<String, Object>) adminResponse.getBody().get("data");
    assertThat(data).isNotNull();
    @SuppressWarnings("unchecked")
    List<Map<String, Object>> recentActivity = (List<Map<String, Object>>) data.get("recentActivity");
    assertThat(recentActivity).isNotNull();
    assertThat(recentActivity)
        .extracting(item -> String.valueOf(item.get("actor")).trim().toLowerCase())
        .doesNotContain(ROOT_SUPER_ADMIN_EMAIL.toLowerCase());
  }

  @Test
  void dashboard_recentActivity_backfills_visible_rows_when_latest_page_is_masked() {
    Long companyId = tenantCompanyId();
    LocalDateTime anchor = LocalDateTime.now().plusHours(6);
    String hiddenPath = "/api/v1/superadmin/tenants/" + companyId + "/limits";
    String visiblePath = "/api/v1/accounting/journal-entries";

    for (int i = 0; i < 55; i++) {
      writeAuditLog(
          companyId,
          ROOT_SUPER_ADMIN_EMAIL,
          UUID.randomUUID().toString(),
          hiddenPath,
          anchor.minusMinutes(i));
    }
    for (int i = 0; i < 20; i++) {
      writeAuditLog(
          companyId,
          ACCOUNTING_EMAIL,
          UUID.randomUUID().toString(),
          visiblePath,
          anchor.minusMinutes(55L + i));
    }

    ResponseEntity<Map> adminResponse =
        rest.exchange(
            ErpApiRoutes.ADMIN_DASHBOARD,
            HttpMethod.GET,
            new HttpEntity<>(headersFor(ADMIN_EMAIL)),
            Map.class);
    assertThat(adminResponse.getStatusCode()).isEqualTo(HttpStatus.OK);
    assertThat(adminResponse.getBody()).isNotNull();

    @SuppressWarnings("unchecked")
    Map<String, Object> data = (Map<String, Object>) adminResponse.getBody().get("data");
    assertThat(data).isNotNull();
    @SuppressWarnings("unchecked")
    List<Map<String, Object>> recentActivity = (List<Map<String, Object>>) data.get("recentActivity");
    assertThat(recentActivity).isNotNull();
    assertThat(recentActivity).hasSize(12);
    assertThat(recentActivity)
        .extracting(item -> String.valueOf(item.get("actor")).trim().toLowerCase())
        .contains(ACCOUNTING_EMAIL.toLowerCase())
        .doesNotContain(ROOT_SUPER_ADMIN_EMAIL.toLowerCase());
  }

  @Test
  void dashboard_keeps_non_privileged_superadmin_path_attempt_visible() {
    Long companyId = tenantCompanyId();
    String nonPrivilegedProbePath = "/api/v1/superadmin/tenants/" + companyId + "/limits";
    String accountingUserId =
        userAccountRepository
            .findByEmailIgnoreCaseAndAuthScopeCodeIgnoreCase(ACCOUNTING_EMAIL, COMPANY_CODE)
            .orElseThrow()
            .getPublicId()
            .toString();

    writeAuditLog(
        companyId,
        ACCOUNTING_EMAIL,
        accountingUserId,
        nonPrivilegedProbePath,
        LocalDateTime.now().plusHours(8));

    ResponseEntity<Map> adminResponse =
        rest.exchange(
            ErpApiRoutes.ADMIN_DASHBOARD,
            HttpMethod.GET,
            new HttpEntity<>(headersFor(ADMIN_EMAIL)),
            Map.class);
    assertThat(adminResponse.getStatusCode()).isEqualTo(HttpStatus.OK);
    assertThat(adminResponse.getBody()).isNotNull();

    @SuppressWarnings("unchecked")
    Map<String, Object> data = (Map<String, Object>) adminResponse.getBody().get("data");
    assertThat(data).isNotNull();
    @SuppressWarnings("unchecked")
    List<Map<String, Object>> recentActivity = (List<Map<String, Object>>) data.get("recentActivity");
    assertThat(recentActivity).isNotNull();
    assertThat(recentActivity)
        .extracting(item -> String.valueOf(item.get("details")))
        .contains("PUT " + nonPrivilegedProbePath);
  }

  @Test
  void dashboard_hides_deleted_privileged_superadmin_path_attempt() {
    Long companyId = tenantCompanyId();
    String deletedPrivilegedProbePath =
        "/api/v1/superadmin/tenants/" + companyId + "/limits/deleted-privileged-probe";
    String privilegedUserId =
        userAccountRepository
            .findByEmailIgnoreCaseAndAuthScopeCodeIgnoreCase(SUPER_ADMIN_EMAIL, COMPANY_CODE)
            .orElseThrow()
            .getPublicId()
            .toString();

    writeAuditLog(
        companyId,
        SUPER_ADMIN_EMAIL,
        privilegedUserId,
        deletedPrivilegedProbePath,
        LocalDateTime.now().plusHours(9),
        AuditEvent.ACCESS_DENIED,
        AuditStatus.FAILURE);

    userAccountRepository
        .findByEmailIgnoreCaseAndAuthScopeCodeIgnoreCase(SUPER_ADMIN_EMAIL, COMPANY_CODE)
        .ifPresent(userAccountRepository::delete);

    ResponseEntity<Map> adminResponse =
        rest.exchange(
            ErpApiRoutes.ADMIN_DASHBOARD,
            HttpMethod.GET,
            new HttpEntity<>(headersFor(ADMIN_EMAIL)),
            Map.class);
    assertThat(adminResponse.getStatusCode()).isEqualTo(HttpStatus.OK);
    assertThat(adminResponse.getBody()).isNotNull();

    @SuppressWarnings("unchecked")
    Map<String, Object> data = (Map<String, Object>) adminResponse.getBody().get("data");
    assertThat(data).isNotNull();
    @SuppressWarnings("unchecked")
    List<Map<String, Object>> recentActivity = (List<Map<String, Object>>) data.get("recentActivity");
    assertThat(recentActivity).isNotNull();
    assertThat(recentActivity)
        .extracting(item -> String.valueOf(item.get("details")))
        .doesNotContain("PUT " + deletedPrivilegedProbePath);
  }

  @Test
  void dashboard_hides_superadmin_path_row_when_actor_id_is_unparseable() {
    Long companyId = tenantCompanyId();
    String unparseableProbePath =
        "/api/v1/superadmin/tenants/" + companyId + "/limits/unparseable-id-probe";

    writeAuditLog(
        companyId,
        ACCOUNTING_EMAIL,
        "not-a-uuid",
        unparseableProbePath,
        LocalDateTime.now().plusHours(10),
        AuditEvent.CONFIGURATION_CHANGED,
        AuditStatus.SUCCESS);

    ResponseEntity<Map> adminResponse =
        rest.exchange(
            ErpApiRoutes.ADMIN_DASHBOARD,
            HttpMethod.GET,
            new HttpEntity<>(headersFor(ADMIN_EMAIL)),
            Map.class);
    assertThat(adminResponse.getStatusCode()).isEqualTo(HttpStatus.OK);
    assertThat(adminResponse.getBody()).isNotNull();

    @SuppressWarnings("unchecked")
    Map<String, Object> data = (Map<String, Object>) adminResponse.getBody().get("data");
    assertThat(data).isNotNull();
    @SuppressWarnings("unchecked")
    List<Map<String, Object>> recentActivity = (List<Map<String, Object>>) data.get("recentActivity");
    assertThat(recentActivity).isNotNull();
    assertThat(recentActivity)
        .extracting(item -> String.valueOf(item.get("details")))
        .doesNotContain("PUT " + unparseableProbePath);
  }

  private HttpHeaders headersFor(String email) {
    return headersFor(email, COMPANY_CODE);
  }

  private HttpHeaders headersFor(String email, String companyCode) {
    ResponseEntity<Map> loginResponse =
        rest.postForEntity(
            "/api/v1/auth/login",
            Map.of(
                "email", email,
                "password", PASSWORD,
                "companyCode", companyCode),
            Map.class);
    assertThat(loginResponse.getStatusCode()).isEqualTo(HttpStatus.OK);
    assertThat(loginResponse.getBody()).isNotNull();

    HttpHeaders headers = new HttpHeaders();
    headers.setContentType(MediaType.APPLICATION_JSON);
    headers.setBearerAuth(String.valueOf(loginResponse.getBody().get("accessToken")));
    headers.set("X-Company-Code", companyCode);
    return headers;
  }

  private Long tenantCompanyId() {
    return companyRepository.findByCodeIgnoreCase(COMPANY_CODE).orElseThrow().getId();
  }

  private void writeAuditLog(
      Long companyId, String actorEmail, String actorUserId, String requestPath, LocalDateTime timestamp) {
    writeAuditLog(
        companyId,
        actorEmail,
        actorUserId,
        requestPath,
        timestamp,
        AuditEvent.CONFIGURATION_CHANGED,
        AuditStatus.SUCCESS);
  }

  private void writeAuditLog(
      Long companyId,
      String actorEmail,
      String actorUserId,
      String requestPath,
      LocalDateTime timestamp,
      AuditEvent eventType,
      AuditStatus status) {
    AuditLog auditLog = new AuditLog();
    auditLog.setEventType(eventType);
    auditLog.setTimestamp(timestamp);
    auditLog.setCompanyId(companyId);
    auditLog.setUsername(actorEmail);
    auditLog.setUserId(actorUserId);
    auditLog.setRequestMethod("PUT");
    auditLog.setRequestPath(requestPath);
    auditLog.setStatus(status);
    auditLogRepository.save(auditLog);
  }
}
