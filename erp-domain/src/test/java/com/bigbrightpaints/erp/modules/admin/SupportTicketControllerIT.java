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
  private static final String DEALER_A_EMAIL = "support.dealer.a@bbp.com";
  private static final String DEALER_B_EMAIL = "support.dealer.b@bbp.com";
  private static final String ADMIN_A_EMAIL = "support.admin.a@bbp.com";
  private static final String ADMIN_B_EMAIL = "support.admin.b@bbp.com";
  private static final String SUPER_ADMIN_EMAIL = "support.superadmin@bbp.com";

  @Autowired private TestRestTemplate rest;

  @Autowired private CompanyRepository companyRepository;

  @Autowired private UserAccountRepository userAccountRepository;

  @Autowired private SupportTicketRepository supportTicketRepository;

  @BeforeEach
  void seedUsers() {
    dataSeeder.ensureUser(
        DEALER_A_EMAIL, PASSWORD, "Support Dealer A", TENANT_A, List.of("ROLE_DEALER"));
    dataSeeder.ensureUser(
        DEALER_B_EMAIL, PASSWORD, "Support Dealer B", TENANT_A, List.of("ROLE_DEALER"));
    dataSeeder.ensureUser(
        ADMIN_A_EMAIL, PASSWORD, "Support Admin A", TENANT_A, List.of("ROLE_ADMIN"));
    dataSeeder.ensureUser(
        ADMIN_B_EMAIL, PASSWORD, "Support Admin B", TENANT_B, List.of("ROLE_ADMIN"));
    dataSeeder.ensureUser(
        SUPER_ADMIN_EMAIL,
        PASSWORD,
        "Support Super Admin",
        ROOT_TENANT,
        List.of("ROLE_SUPER_ADMIN"));
  }

  @Test
  void adminCreate_persistsAndReturnsApiEnvelope() {
    String token = login(ADMIN_A_EMAIL, TENANT_A);
    HttpHeaders headers = authHeaders(token, TENANT_A);

    String subject = "Portal support create flow " + System.nanoTime();
    ResponseEntity<Map> response =
        rest.exchange(
            "/api/v1/admin/support/tickets",
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
  void dealerCreate_persistsAndReturnsApiEnvelope() {
    String token = login(DEALER_A_EMAIL, TENANT_A);
    HttpHeaders headers = authHeaders(token, TENANT_A);

    String subject = "Dealer support create flow " + System.nanoTime();
    ResponseEntity<Map> response =
        rest.exchange(
            "/api/v1/dealer-portal/support/tickets",
            HttpMethod.POST,
            new HttpEntity<>(
                Map.of(
                    "category", "SUPPORT",
                    "subject", subject,
                    "description", "Dealer cannot reconcile invoice payment"),
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
  void listEndpoints_applyHostSpecificVisibilityAndRetireSharedHost() {
    String marker = "scope-" + System.nanoTime();
    String adminSubject = marker + "-admin";
    String dealerOwnSubject = marker + "-dealer-own";
    String dealerPeerSubject = marker + "-dealer-peer";
    String foreignSubject = marker + "-foreign";

    seedTicket(TENANT_A, ADMIN_A_EMAIL, adminSubject);
    seedTicket(TENANT_A, DEALER_A_EMAIL, dealerOwnSubject);
    seedTicket(TENANT_A, DEALER_B_EMAIL, dealerPeerSubject);
    seedTicket(TENANT_B, ADMIN_B_EMAIL, foreignSubject);

    ResponseEntity<Map> adminSupportResponse =
        rest.exchange(
            "/api/v1/admin/support/tickets",
            HttpMethod.GET,
            new HttpEntity<>(authHeaders(login(ADMIN_A_EMAIL, TENANT_A), TENANT_A)),
            Map.class);
    assertThat(adminSupportResponse.getStatusCode()).isEqualTo(HttpStatus.OK);
    Set<String> adminSubjects = subjectsFromListResponse(adminSupportResponse);
    assertThat(adminSubjects).contains(adminSubject, dealerOwnSubject, dealerPeerSubject);
    assertThat(adminSubjects).doesNotContain(foreignSubject);

    ResponseEntity<Map> dealerResponse =
        rest.exchange(
            "/api/v1/dealer-portal/support/tickets",
            HttpMethod.GET,
            new HttpEntity<>(authHeaders(login(DEALER_A_EMAIL, TENANT_A), TENANT_A)),
            Map.class);
    assertThat(dealerResponse.getStatusCode()).isEqualTo(HttpStatus.OK);
    Set<String> dealerSubjects = subjectsFromListResponse(dealerResponse);
    assertThat(dealerSubjects).contains(dealerOwnSubject);
    assertThat(dealerSubjects).doesNotContain(adminSubject, dealerPeerSubject, foreignSubject);

    ResponseEntity<Map> adminSupportDealerDenied =
        rest.exchange(
            "/api/v1/admin/support/tickets",
            HttpMethod.GET,
            new HttpEntity<>(authHeaders(login(DEALER_A_EMAIL, TENANT_A), TENANT_A)),
            Map.class);
    assertThat(adminSupportDealerDenied.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);

    ResponseEntity<Map> dealerPortalAdminDenied =
        rest.exchange(
            "/api/v1/dealer-portal/support/tickets",
            HttpMethod.GET,
            new HttpEntity<>(authHeaders(login(ADMIN_A_EMAIL, TENANT_A), TENANT_A)),
            Map.class);
    assertThat(dealerPortalAdminDenied.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);

    ResponseEntity<Map> portalAdminDenied =
        rest.exchange(
            "/api/v1/portal/support/tickets",
            HttpMethod.GET,
            new HttpEntity<>(authHeaders(login(ADMIN_A_EMAIL, TENANT_A), TENANT_A)),
            Map.class);
    assertThat(portalAdminDenied.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);

    ResponseEntity<Map> retiredSharedAdmin =
        rest.exchange(
            "/api/v1/support/tickets",
            HttpMethod.GET,
            new HttpEntity<>(authHeaders(login(ADMIN_A_EMAIL, TENANT_A), TENANT_A)),
            Map.class);
    assertThat(retiredSharedAdmin.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);

    ResponseEntity<Map> retiredSharedDealer =
        rest.exchange(
            "/api/v1/support/tickets",
            HttpMethod.GET,
            new HttpEntity<>(authHeaders(login(DEALER_A_EMAIL, TENANT_A), TENANT_A)),
            Map.class);
    assertThat(retiredSharedDealer.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
  }

  @Test
  void detailEndpoints_enforceHostAndTenantBoundaries() {
    Long dealerOwnTicket =
        seedTicket(TENANT_A, DEALER_A_EMAIL, "detail-dealer-own-" + System.nanoTime());
    Long dealerPeerTicket =
        seedTicket(TENANT_A, DEALER_B_EMAIL, "detail-dealer-peer-" + System.nanoTime());
    Long adminTicket = seedTicket(TENANT_A, ADMIN_A_EMAIL, "detail-admin-" + System.nanoTime());
    Long foreignTicket = seedTicket(TENANT_B, ADMIN_B_EMAIL, "detail-foreign-" + System.nanoTime());

    ResponseEntity<Map> dealerOwnResponse =
        rest.exchange(
            "/api/v1/dealer-portal/support/tickets/" + dealerOwnTicket,
            HttpMethod.GET,
            new HttpEntity<>(authHeaders(login(DEALER_A_EMAIL, TENANT_A), TENANT_A)),
            Map.class);
    assertThat(dealerOwnResponse.getStatusCode()).isEqualTo(HttpStatus.OK);

    ResponseEntity<Map> dealerPeerDenied =
        rest.exchange(
            "/api/v1/dealer-portal/support/tickets/" + dealerPeerTicket,
            HttpMethod.GET,
            new HttpEntity<>(authHeaders(login(DEALER_A_EMAIL, TENANT_A), TENANT_A)),
            Map.class);
    assertThat(dealerPeerDenied.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);

    ResponseEntity<Map> dealerCrossHostProbe =
        rest.exchange(
            "/api/v1/dealer-portal/support/tickets/" + adminTicket,
            HttpMethod.GET,
            new HttpEntity<>(authHeaders(login(DEALER_A_EMAIL, TENANT_A), TENANT_A)),
            Map.class);
    assertThat(dealerCrossHostProbe.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);

    ResponseEntity<Map> dealerForeignDenied =
        rest.exchange(
            "/api/v1/dealer-portal/support/tickets/" + foreignTicket,
            HttpMethod.GET,
            new HttpEntity<>(authHeaders(login(DEALER_A_EMAIL, TENANT_A), TENANT_A)),
            Map.class);
    assertThat(dealerForeignDenied.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);

    ResponseEntity<Map> adminSupportResponse =
        rest.exchange(
            "/api/v1/admin/support/tickets/" + dealerOwnTicket,
            HttpMethod.GET,
            new HttpEntity<>(authHeaders(login(ADMIN_A_EMAIL, TENANT_A), TENANT_A)),
            Map.class);
    assertThat(adminSupportResponse.getStatusCode()).isEqualTo(HttpStatus.OK);

    ResponseEntity<Map> adminSupportForeignDenied =
        rest.exchange(
            "/api/v1/admin/support/tickets/" + foreignTicket,
            HttpMethod.GET,
            new HttpEntity<>(authHeaders(login(ADMIN_A_EMAIL, TENANT_A), TENANT_A)),
            Map.class);
    assertThat(adminSupportForeignDenied.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);

    ResponseEntity<Map> adminSupportDealerDenied =
        rest.exchange(
            "/api/v1/admin/support/tickets/" + dealerOwnTicket,
            HttpMethod.GET,
            new HttpEntity<>(authHeaders(login(DEALER_A_EMAIL, TENANT_A), TENANT_A)),
            Map.class);
    assertThat(adminSupportDealerDenied.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);

    ResponseEntity<Map> dealerPortalAdminDenied =
        rest.exchange(
            "/api/v1/dealer-portal/support/tickets/" + dealerOwnTicket,
            HttpMethod.GET,
            new HttpEntity<>(authHeaders(login(ADMIN_A_EMAIL, TENANT_A), TENANT_A)),
            Map.class);
    assertThat(dealerPortalAdminDenied.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);

    ResponseEntity<Map> retiredSharedAdmin =
        rest.exchange(
            "/api/v1/support/tickets/" + adminTicket,
            HttpMethod.GET,
            new HttpEntity<>(authHeaders(login(ADMIN_A_EMAIL, TENANT_A), TENANT_A)),
            Map.class);
    assertThat(retiredSharedAdmin.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
  }

  @Test
  void createEndpoints_denyCrossHostRolesAndSuperAdminTenantWorkflowAccess() {
    ResponseEntity<Map> dealerOnAdminSupportResponse =
        rest.exchange(
            "/api/v1/admin/support/tickets",
            HttpMethod.POST,
            new HttpEntity<>(
                Map.of(
                    "category", "SUPPORT",
                    "subject", "dealer-on-admin-support-" + System.nanoTime(),
                    "description", "Dealer must not post admin support tickets"),
                authHeaders(login(DEALER_A_EMAIL, TENANT_A), TENANT_A)),
            Map.class);
    assertThat(dealerOnAdminSupportResponse.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);

    ResponseEntity<Map> dealerOnPortalResponse =
        rest.exchange(
            "/api/v1/portal/support/tickets",
            HttpMethod.POST,
            new HttpEntity<>(
                Map.of(
                    "category", "SUPPORT",
                    "subject", "dealer-on-portal-" + System.nanoTime(),
                    "description", "Dealer must not post portal support tickets"),
                authHeaders(login(DEALER_A_EMAIL, TENANT_A), TENANT_A)),
            Map.class);
    assertThat(dealerOnPortalResponse.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);

    ResponseEntity<Map> adminOnDealerPortalResponse =
        rest.exchange(
            "/api/v1/dealer-portal/support/tickets",
            HttpMethod.POST,
            new HttpEntity<>(
                Map.of(
                    "category", "SUPPORT",
                    "subject", "admin-on-dealer-portal-" + System.nanoTime(),
                    "description", "Admin must not post dealer portal support tickets"),
                authHeaders(login(ADMIN_A_EMAIL, TENANT_A), TENANT_A)),
            Map.class);
    assertThat(adminOnDealerPortalResponse.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);

    ResponseEntity<Map> retiredSharedAdminResponse =
        rest.exchange(
            "/api/v1/support/tickets",
            HttpMethod.POST,
            new HttpEntity<>(
                Map.of(
                    "category", "SUPPORT",
                    "subject", "retired-admin-" + System.nanoTime(),
                    "description", "Shared support host must be gone"),
                authHeaders(login(ADMIN_A_EMAIL, TENANT_A), TENANT_A)),
            Map.class);
    assertThat(retiredSharedAdminResponse.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);

    ResponseEntity<Map> retiredSharedSuperAdminResponse =
        rest.exchange(
            "/api/v1/support/tickets",
            HttpMethod.POST,
            new HttpEntity<>(
                Map.of(
                    "category", "SUPPORT",
                    "subject", "retired-super-admin-" + System.nanoTime(),
                    "description", "Shared support host must stay unmapped for super admins too"),
                authHeaders(login(SUPER_ADMIN_EMAIL, ROOT_TENANT), ROOT_TENANT)),
            Map.class);
    assertThat(retiredSharedSuperAdminResponse.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);

    ResponseEntity<Map> response =
        rest.exchange(
            "/api/v1/admin/support/tickets",
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
