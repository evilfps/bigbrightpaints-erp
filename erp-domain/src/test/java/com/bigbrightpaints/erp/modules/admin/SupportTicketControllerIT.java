package com.bigbrightpaints.erp.modules.admin;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

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
import com.bigbrightpaints.erp.modules.auth.domain.UserAccount;
import com.bigbrightpaints.erp.modules.auth.domain.UserAccountRepository;
import com.bigbrightpaints.erp.modules.company.domain.Company;
import com.bigbrightpaints.erp.modules.company.domain.CompanyRepository;
import com.bigbrightpaints.erp.test.AbstractIntegrationTest;

class SupportTicketControllerIT extends AbstractIntegrationTest {

  private static final String TENANT_A = "SUPA";
  private static final String TENANT_B = "SUPB";
  private static final String ROOT_TENANT = "ROOTSUP";

  private static final String PASSWORD = "Admin@123";
  private static final String REQUESTER_A_EMAIL = "support.requester.a@bbp.com";
  private static final String TEAMMATE_A_EMAIL = "support.teammate.a@bbp.com";
  private static final String ADMIN_A_EMAIL = "support.admin.a@bbp.com";
  private static final String REQUESTER_B_EMAIL = "support.requester.b@bbp.com";
  private static final String SUPER_ADMIN_EMAIL = "support.superadmin@bbp.com";

  @Autowired private TestRestTemplate rest;

  @Autowired private CompanyRepository companyRepository;

  @Autowired private UserAccountRepository userAccountRepository;

  @Autowired private SupportTicketRepository supportTicketRepository;

  @BeforeEach
  void seedUsers() {
    dataSeeder.ensureUser(
        REQUESTER_A_EMAIL, PASSWORD, "Support Requester A", TENANT_A, List.of("ROLE_SALES"));
    dataSeeder.ensureUser(
        TEAMMATE_A_EMAIL, PASSWORD, "Support Teammate A", TENANT_A, List.of("ROLE_SALES"));
    dataSeeder.ensureUser(
        ADMIN_A_EMAIL, PASSWORD, "Support Admin A", TENANT_A, List.of("ROLE_ADMIN"));
    dataSeeder.ensureUser(
        REQUESTER_B_EMAIL, PASSWORD, "Support Requester B", TENANT_B, List.of("ROLE_SALES"));
    dataSeeder.ensureUser(
        SUPER_ADMIN_EMAIL,
        PASSWORD,
        "Support Super Admin",
        ROOT_TENANT,
        List.of("ROLE_SUPER_ADMIN"));
  }

  @Test
  void createTicket_persistsAndReturnsApiEnvelope() {
    String token = login(REQUESTER_A_EMAIL, TENANT_A);
    HttpHeaders headers = authHeaders(token, TENANT_A);

    String subject = "Support create flow " + System.nanoTime();
    ResponseEntity<Map> response =
        rest.exchange(
            "/api/v1/support/tickets",
            HttpMethod.POST,
            new HttpEntity<>(
                Map.of(
                    "category", "SUPPORT",
                    "subject", subject,
                    "description", "Unable to complete export after approval"),
                headers),
            Map.class);

    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
    assertThat(response.getBody()).isNotNull();
    assertThat(response.getBody().get("success")).isEqualTo(Boolean.TRUE);

    @SuppressWarnings("unchecked")
    Map<String, Object> data = (Map<String, Object>) response.getBody().get("data");
    assertThat(data).isNotNull();
    assertThat(data.get("subject")).isEqualTo(subject);
    assertThat(data.get("category")).isEqualTo("SUPPORT");
    assertThat(data.get("companyCode")).isEqualTo(TENANT_A);
    assertThat(data.get("id")).isNotNull();

    Long createdId = Long.parseLong(data.get("id").toString());
    assertThat(supportTicketRepository.findById(createdId)).isPresent();
  }

  @Test
  void listEndpoint_appliesRoleScopedVisibility() {
    String marker = "scope-" + System.nanoTime();
    String selfSubject = marker + "-self";
    String teammateSubject = marker + "-teammate";
    String foreignSubject = marker + "-foreign";

    seedTicket(TENANT_A, REQUESTER_A_EMAIL, selfSubject);
    seedTicket(TENANT_A, TEAMMATE_A_EMAIL, teammateSubject);
    seedTicket(TENANT_B, REQUESTER_B_EMAIL, foreignSubject);

    ResponseEntity<Map> requesterResponse =
        rest.exchange(
            "/api/v1/support/tickets",
            HttpMethod.GET,
            new HttpEntity<>(authHeaders(login(REQUESTER_A_EMAIL, TENANT_A), TENANT_A)),
            Map.class);
    assertThat(requesterResponse.getStatusCode()).isEqualTo(HttpStatus.OK);
    Set<String> requesterSubjects = subjectsFromListResponse(requesterResponse);
    assertThat(requesterSubjects).contains(selfSubject);
    assertThat(requesterSubjects).doesNotContain(teammateSubject, foreignSubject);

    ResponseEntity<Map> adminResponse =
        rest.exchange(
            "/api/v1/support/tickets",
            HttpMethod.GET,
            new HttpEntity<>(authHeaders(login(ADMIN_A_EMAIL, TENANT_A), TENANT_A)),
            Map.class);
    assertThat(adminResponse.getStatusCode()).isEqualTo(HttpStatus.OK);
    Set<String> adminSubjects = subjectsFromListResponse(adminResponse);
    assertThat(adminSubjects).contains(selfSubject, teammateSubject);
    assertThat(adminSubjects).doesNotContain(foreignSubject);

    ResponseEntity<Map> superAdminResponse =
        rest.exchange(
            "/api/v1/support/tickets",
            HttpMethod.GET,
            new HttpEntity<>(authHeaders(login(SUPER_ADMIN_EMAIL, ROOT_TENANT), ROOT_TENANT)),
            Map.class);
    assertForbiddenPlatformOnly(superAdminResponse);
  }

  @Test
  void getByIdEndpoint_enforcesScopedAccessRules() {
    Long ownTicket = seedTicket(TENANT_A, REQUESTER_A_EMAIL, "detail-own-" + System.nanoTime());
    Long teammateTicket =
        seedTicket(TENANT_A, TEAMMATE_A_EMAIL, "detail-team-" + System.nanoTime());
    Long foreignTicket =
        seedTicket(TENANT_B, REQUESTER_B_EMAIL, "detail-foreign-" + System.nanoTime());

    ResponseEntity<Map> requesterForbidden =
        rest.exchange(
            "/api/v1/support/tickets/" + teammateTicket,
            HttpMethod.GET,
            new HttpEntity<>(authHeaders(login(REQUESTER_A_EMAIL, TENANT_A), TENANT_A)),
            Map.class);
    assertThat(requesterForbidden.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);

    ResponseEntity<Map> requesterOwn =
        rest.exchange(
            "/api/v1/support/tickets/" + ownTicket,
            HttpMethod.GET,
            new HttpEntity<>(authHeaders(login(REQUESTER_A_EMAIL, TENANT_A), TENANT_A)),
            Map.class);
    assertThat(requesterOwn.getStatusCode()).isEqualTo(HttpStatus.OK);

    ResponseEntity<Map> adminAllowed =
        rest.exchange(
            "/api/v1/support/tickets/" + teammateTicket,
            HttpMethod.GET,
            new HttpEntity<>(authHeaders(login(ADMIN_A_EMAIL, TENANT_A), TENANT_A)),
            Map.class);
    assertThat(adminAllowed.getStatusCode()).isEqualTo(HttpStatus.OK);

    ResponseEntity<Map> adminForeignDenied =
        rest.exchange(
            "/api/v1/support/tickets/" + foreignTicket,
            HttpMethod.GET,
            new HttpEntity<>(authHeaders(login(ADMIN_A_EMAIL, TENANT_A), TENANT_A)),
            Map.class);
    assertThat(adminForeignDenied.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);

    ResponseEntity<Map> superAdminAllowed =
        rest.exchange(
            "/api/v1/support/tickets/" + foreignTicket,
            HttpMethod.GET,
            new HttpEntity<>(authHeaders(login(SUPER_ADMIN_EMAIL, ROOT_TENANT), ROOT_TENANT)),
            Map.class);
    assertForbiddenPlatformOnly(superAdminAllowed);
  }

  @Test
  void createEndpoint_deniesSuperAdminTenantWorkflowAccess() {
    ResponseEntity<Map> response =
        rest.exchange(
            "/api/v1/support/tickets",
            HttpMethod.POST,
            new HttpEntity<>(
                Map.of(
                    "category", "SUPPORT",
                    "subject", "platform-only-" + System.nanoTime(),
                    "description", "Super admin must not create tenant support tickets"),
                authHeaders(login(SUPER_ADMIN_EMAIL, ROOT_TENANT), ROOT_TENANT)),
            Map.class);

    assertForbiddenPlatformOnly(response);
  }

  private Long seedTicket(String companyCode, String userEmail, String subject) {
    Company company = companyRepository.findByCodeIgnoreCase(companyCode).orElseThrow();
    UserAccount requester =
        userAccountRepository
            .findByEmailIgnoreCaseAndAuthScopeCodeIgnoreCase(userEmail, companyCode)
            .orElseThrow();

    SupportTicket ticket = new SupportTicket();
    ticket.setCompany(company);
    ticket.setUserId(requester.getId());
    ticket.setCategory(SupportTicketCategory.SUPPORT);
    ticket.setSubject(subject);
    ticket.setDescription("Investigate support visibility");
    ticket.setStatus(SupportTicketStatus.OPEN);

    return supportTicketRepository.save(ticket).getId();
  }

  private Set<String> subjectsFromListResponse(ResponseEntity<Map> response) {
    assertThat(response.getBody()).isNotNull();
    @SuppressWarnings("unchecked")
    Map<String, Object> data = (Map<String, Object>) response.getBody().get("data");
    assertThat(data).isNotNull();

    @SuppressWarnings("unchecked")
    List<Map<String, Object>> tickets = (List<Map<String, Object>>) data.get("tickets");
    return tickets.stream()
        .map(ticket -> String.valueOf(ticket.get("subject")))
        .collect(Collectors.toSet());
  }

  private HttpHeaders authHeaders(String token, String companyCode) {
    HttpHeaders headers = new HttpHeaders();
    headers.setBearerAuth(token);
    headers.setContentType(MediaType.APPLICATION_JSON);
    headers.set("X-Company-Code", companyCode);
    return headers;
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
    assertThat(response.getBody()).isNotNull();
    return String.valueOf(response.getBody().get("accessToken"));
  }

  @SuppressWarnings("unchecked")
  private void assertForbiddenPlatformOnly(ResponseEntity<Map> response) {
    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
    assertThat(response.getBody()).isNotNull();
    assertThat(response.getBody().get("success")).isEqualTo(Boolean.FALSE);
    assertThat(response.getBody().get("message")).isEqualTo("Access denied");
    Map<String, Object> data = (Map<String, Object>) response.getBody().get("data");
    assertThat(data).isNotNull();
    assertThat(data.get("code")).isEqualTo("AUTH_004");
    assertThat(data.get("reason")).isEqualTo("SUPER_ADMIN_PLATFORM_ONLY");
  }
}
