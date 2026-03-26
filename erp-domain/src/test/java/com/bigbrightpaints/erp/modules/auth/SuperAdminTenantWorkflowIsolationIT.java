package com.bigbrightpaints.erp.modules.auth;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;

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

import com.bigbrightpaints.erp.modules.admin.domain.SupportTicket;
import com.bigbrightpaints.erp.modules.admin.domain.SupportTicketCategory;
import com.bigbrightpaints.erp.modules.admin.domain.SupportTicketRepository;
import com.bigbrightpaints.erp.modules.admin.domain.SupportTicketStatus;
import com.bigbrightpaints.erp.modules.auth.domain.UserAccountRepository;
import com.bigbrightpaints.erp.modules.company.domain.Company;
import com.bigbrightpaints.erp.modules.company.domain.CompanyLifecycleState;
import com.bigbrightpaints.erp.modules.company.domain.CompanyRepository;
import com.bigbrightpaints.erp.test.AbstractIntegrationTest;

class SuperAdminTenantWorkflowIsolationIT extends AbstractIntegrationTest {

  private static final String TENANT_A = "AUDIT-TENANT-A";
  private static final String ROOT_TENANT = "AUDIT-ROOT";
  private static final String ADMIN_EMAIL = "audit-admin@bbp.com";
  private static final String SUPER_ADMIN_EMAIL = "audit-super-admin@bbp.com";
  private static final String PASSWORD = "Passw0rd!";

  @Autowired private TestRestTemplate rest;

  @Autowired private CompanyRepository companyRepository;

  @Autowired private UserAccountRepository userAccountRepository;

  @Autowired private SupportTicketRepository supportTicketRepository;

  @BeforeEach
  void setUp() {
    dataSeeder.ensureUser(ADMIN_EMAIL, PASSWORD, "Audit Admin", TENANT_A, List.of("ROLE_ADMIN"));
    dataSeeder.ensureUser(
        SUPER_ADMIN_EMAIL,
        PASSWORD,
        "Audit Super Admin",
        ROOT_TENANT,
        List.of("ROLE_SUPER_ADMIN", "ROLE_ADMIN"));
    dataSeeder.ensureUser(
        SUPER_ADMIN_EMAIL,
        PASSWORD,
        "Audit Super Admin",
        TENANT_A,
        List.of("ROLE_SUPER_ADMIN", "ROLE_ADMIN"));
    resetSeededUserState(ADMIN_EMAIL);
    resetSeededUserState(SUPER_ADMIN_EMAIL);
    resetTenantLifecycle(TENANT_A);
    resetTenantLifecycle(ROOT_TENANT);
  }

  @Test
  void tenantAdminCanReadAuditBusinessEvents_butTenantAttachedSuperAdminIsDenied() {
    String adminToken = login(ADMIN_EMAIL, TENANT_A);
    String superAdminToken = login(SUPER_ADMIN_EMAIL, TENANT_A);

    ResponseEntity<Map> adminResponse =
        rest.exchange(
            "/api/v1/audit/business-events?page=0&size=5",
            HttpMethod.GET,
            new HttpEntity<>(jsonHeaders(adminToken, TENANT_A)),
            Map.class);
    assertThat(adminResponse.getStatusCode()).isEqualTo(HttpStatus.OK);

    ResponseEntity<Map> deniedResponse =
        rest.exchange(
            "/api/v1/audit/business-events?page=0&size=5",
            HttpMethod.GET,
            new HttpEntity<>(jsonHeaders(superAdminToken, TENANT_A)),
            Map.class);
    assertThat(deniedResponse.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
    assertForbiddenReason(deniedResponse, "SUPER_ADMIN_TENANT_WORKFLOW_DENIED");
  }

  @Test
  void tenantAdminCanReadSupportTickets_butTenantAttachedSuperAdminIsDenied() {
    Long ticketId = seedSupportTicket(TENANT_A, ADMIN_EMAIL, "support-denial-" + System.nanoTime());
    String adminToken = login(ADMIN_EMAIL, TENANT_A);
    String superAdminToken = login(SUPER_ADMIN_EMAIL, TENANT_A);

    ResponseEntity<Map> adminResponse =
        rest.exchange(
            "/api/v1/support/tickets",
            HttpMethod.GET,
            new HttpEntity<>(jsonHeaders(adminToken, TENANT_A)),
            Map.class);
    assertThat(adminResponse.getStatusCode()).isEqualTo(HttpStatus.OK);

    ResponseEntity<Map> deniedListResponse =
        rest.exchange(
            "/api/v1/support/tickets",
            HttpMethod.GET,
            new HttpEntity<>(jsonHeaders(superAdminToken, TENANT_A)),
            Map.class);
    assertThat(deniedListResponse.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
    assertForbiddenReason(deniedListResponse, "SUPER_ADMIN_PLATFORM_ONLY");

    ResponseEntity<Map> deniedDetailResponse =
        rest.exchange(
            "/api/v1/support/tickets/" + ticketId,
            HttpMethod.GET,
            new HttpEntity<>(jsonHeaders(superAdminToken, TENANT_A)),
            Map.class);
    assertThat(deniedDetailResponse.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
    assertForbiddenReason(deniedDetailResponse, "SUPER_ADMIN_PLATFORM_ONLY");
  }

  @Test
  void rootSuperAdminRetainsPlatformOnlyControlPlaneAccess() {
    Long tenantAId =
        companyRepository.findByCodeIgnoreCase(TENANT_A).map(Company::getId).orElseThrow();
    String rootToken = login(SUPER_ADMIN_EMAIL, ROOT_TENANT);

    ResponseEntity<Map> metricsResponse =
        rest.exchange(
            "/api/v1/superadmin/tenants/" + tenantAId,
            HttpMethod.GET,
            new HttpEntity<>(jsonHeaders(rootToken, ROOT_TENANT)),
            Map.class);

    assertThat(metricsResponse.getStatusCode()).isEqualTo(HttpStatus.OK);
    Map<String, Object> body = metricsResponse.getBody();
    assertThat(body).isNotNull();
    @SuppressWarnings("unchecked")
    Map<String, Object> data = (Map<String, Object>) body.get("data");
    assertThat(data).isNotNull();
    assertThat(data.get("companyCode")).isEqualTo(TENANT_A);
  }

  private void resetSeededUserState(String email) {
    userAccountRepository
        .findByEmailIgnoreCase(email)
        .ifPresent(
            user -> {
              user.setEnabled(true);
              user.setMustChangePassword(false);
              user.setFailedLoginAttempts(0);
              user.setLockedUntil(null);
              userAccountRepository.save(user);
            });
  }

  private void resetTenantLifecycle(String companyCode) {
    companyRepository
        .findByCodeIgnoreCase(companyCode)
        .ifPresent(
            company -> {
              company.setLifecycleState(CompanyLifecycleState.ACTIVE);
              company.setLifecycleReason(null);
              companyRepository.save(company);
            });
  }

  private String login(String email, String companyCode) {
    ResponseEntity<Map> response =
        rest.postForEntity(
            "/api/v1/auth/login",
            Map.of(
                "email", email,
                "password", PASSWORD,
                "companyCode", companyCode),
            Map.class);
    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
    Map<String, Object> body = response.getBody();
    assertThat(body).isNotNull();
    return body.get("accessToken").toString();
  }

  private Long seedSupportTicket(String companyCode, String email, String subject) {
    Company company = companyRepository.findByCodeIgnoreCase(companyCode).orElseThrow();
    Long userId =
        userAccountRepository.findByEmailIgnoreCase(email).map(user -> user.getId()).orElseThrow();

    SupportTicket ticket = new SupportTicket();
    ticket.setCompany(company);
    ticket.setUserId(userId);
    ticket.setCategory(SupportTicketCategory.SUPPORT);
    ticket.setSubject(subject);
    ticket.setDescription("Support workflow isolation verification");
    ticket.setStatus(SupportTicketStatus.OPEN);
    return supportTicketRepository.save(ticket).getId();
  }

  private HttpHeaders jsonHeaders(String token, String companyCode) {
    HttpHeaders headers = new HttpHeaders();
    headers.setContentType(MediaType.APPLICATION_JSON);
    headers.setBearerAuth(token);
    headers.set("X-Company-Code", companyCode);
    return headers;
  }

  @SuppressWarnings("unchecked")
  private void assertForbiddenReason(ResponseEntity<Map> response, String expectedReason) {
    Map<String, Object> body = response.getBody();
    assertThat(body).isNotNull();
    assertThat(body.get("success")).isEqualTo(Boolean.FALSE);
    assertThat(body.get("message")).isEqualTo("Access denied");
    Map<String, Object> error = (Map<String, Object>) body.get("data");
    assertThat(error).isNotNull();
    assertThat(error.get("code")).isEqualTo("AUTH_004");
    assertThat(error.get("reason")).isEqualTo(expectedReason);
  }
}
