package com.bigbrightpaints.erp.modules.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.fail;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Predicate;
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

import com.bigbrightpaints.erp.core.audit.AuditEvent;
import com.bigbrightpaints.erp.core.audit.AuditLog;
import com.bigbrightpaints.erp.modules.auth.domain.UserAccount;
import com.bigbrightpaints.erp.modules.auth.domain.UserAccountRepository;
import com.bigbrightpaints.erp.modules.company.domain.Company;
import com.bigbrightpaints.erp.modules.company.domain.CompanyLifecycleState;
import com.bigbrightpaints.erp.modules.company.domain.CompanyRepository;
import com.bigbrightpaints.erp.modules.rbac.domain.Permission;
import com.bigbrightpaints.erp.modules.rbac.domain.PermissionRepository;
import com.bigbrightpaints.erp.modules.rbac.domain.Role;
import com.bigbrightpaints.erp.modules.rbac.domain.RoleRepository;
import com.bigbrightpaints.erp.modules.rbac.service.RoleService;
import com.bigbrightpaints.erp.test.AbstractIntegrationTest;

import jakarta.persistence.EntityManager;

class AuthTenantAuthorityIT extends AbstractIntegrationTest {

  private static final String CONTROL_PLANE_AUTH_DENIED_MESSAGE =
      "Access denied to company control request";
  private static final String TENANT_A = "AUTH-TENANT-A";
  private static final String TENANT_B = "AUTH-TENANT-B";
  private static final String ROOT_TENANT = "AUTH-ROOT";

  private static final String ADMIN_EMAIL = "tenant-admin@bbp.com";
  private static final String ROLE_GUARD_ADMIN_EMAIL = "role-guard-admin@bbp.com";
  private static final String NON_PRIVILEGED_ADMIN_EMAIL = "tenant-admin-nonpriv@bbp.com";
  private static final String SALES_SYNC_EMAIL = "sales-sync@bbp.com";
  private static final String ACCOUNTING_SYNC_EMAIL = "accounting-sync@bbp.com";
  private static final String SUPER_ADMIN_EMAIL = "super-admin@bbp.com";
  private static final String SUPER_ADMIN_HIERARCHY_EMAIL = "super-admin-hierarchy@bbp.com";
  private static final String PASSWORD = "Passw0rd!";

  @Autowired private TestRestTemplate rest;

  @Autowired private CompanyRepository companyRepository;

  @Autowired private EntityManager entityManager;

  @Autowired private UserAccountRepository userAccountRepository;

  @Autowired private RoleRepository roleRepository;

  @Autowired private PermissionRepository permissionRepository;

  @Autowired private RoleService roleService;

  @BeforeEach
  void setUp() {
    dataSeeder.ensureUser(ADMIN_EMAIL, PASSWORD, "Tenant Admin", TENANT_A, List.of("ROLE_ADMIN"));
    dataSeeder.ensureUser(
        ROLE_GUARD_ADMIN_EMAIL, PASSWORD, "Role Guard Admin", TENANT_A, List.of("ROLE_ADMIN"));
    dataSeeder.ensureUser(
        NON_PRIVILEGED_ADMIN_EMAIL,
        PASSWORD,
        "Non Privileged Admin",
        TENANT_A,
        List.of("ROLE_ADMIN"));
    dataSeeder.ensureUser(
        "other-admin@bbp.com", PASSWORD, "Other Admin", TENANT_B, List.of("ROLE_ADMIN"));
    dataSeeder.ensureUser(
        "factory-seed@bbp.com", PASSWORD, "Factory Seed", TENANT_A, List.of("ROLE_FACTORY"));
    dataSeeder.ensureUser(
        SALES_SYNC_EMAIL, PASSWORD, "Sales Sync", TENANT_A, List.of("ROLE_SALES"));
    dataSeeder.ensureUser(
        ACCOUNTING_SYNC_EMAIL, PASSWORD, "Accounting Sync", TENANT_A, List.of("ROLE_ACCOUNTING"));
    dataSeeder.ensureUser(
        SUPER_ADMIN_EMAIL,
        PASSWORD,
        "Super Admin",
        ROOT_TENANT,
        List.of("ROLE_SUPER_ADMIN", "ROLE_ADMIN"));
    // Give super admin access to a regular tenant for delegated tenant-admin creation.
    dataSeeder.ensureUser(
        SUPER_ADMIN_EMAIL,
        PASSWORD,
        "Super Admin",
        TENANT_A,
        List.of("ROLE_SUPER_ADMIN", "ROLE_ADMIN"));
    dataSeeder.ensureUser(
        SUPER_ADMIN_HIERARCHY_EMAIL,
        PASSWORD,
        "Hierarchy Super Admin",
        TENANT_A,
        List.of("ROLE_SUPER_ADMIN"));
    resetSeededUserState(ADMIN_EMAIL);
    resetSeededUserState(ROLE_GUARD_ADMIN_EMAIL);
    resetSeededUserState(NON_PRIVILEGED_ADMIN_EMAIL);
    resetSeededUserState("other-admin@bbp.com");
    resetSeededUserState(SALES_SYNC_EMAIL);
    resetSeededUserState(ACCOUNTING_SYNC_EMAIL);
    resetSeededUserState(SUPER_ADMIN_EMAIL);
    resetSeededUserState(SUPER_ADMIN_HIERARCHY_EMAIL);
    resetTenantLifecycle(TENANT_A);
    resetTenantLifecycle(TENANT_B);
    resetTenantLifecycle(ROOT_TENANT);
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

  @Test
  void admin_cannot_bootstrap_new_tenant() {
    String token = login(ADMIN_EMAIL, TENANT_A);
    String newCode = "TEN-BOOT-" + System.nanoTime();
    String firstAdminEmail = "blocked-bootstrap-" + System.nanoTime() + "@bbp.com";

    ResponseEntity<Map> response =
        rest.exchange(
            "/api/v1/superadmin/tenants/onboard",
            HttpMethod.POST,
            new HttpEntity<>(
                tenantOnboardingPayload("Blocked Bootstrap", newCode, firstAdminEmail),
                jsonHeaders(token, TENANT_A)),
            Map.class);

    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
    assertThat(companyRepository.findByCodeIgnoreCase(newCode)).isEmpty();
    assertThat(userAccountRepository.findByEmailIgnoreCase(firstAdminEmail)).isEmpty();
  }

  @Test
  void super_admin_can_bootstrap_new_tenant() {
    String token = login(SUPER_ADMIN_EMAIL, ROOT_TENANT);
    String newCode = "TEN-ALLOW-" + System.nanoTime();
    String firstAdminEmail = "allowed-bootstrap-" + System.nanoTime() + "@bbp.com";

    ResponseEntity<Map> response =
        rest.exchange(
            "/api/v1/superadmin/tenants/onboard",
            HttpMethod.POST,
            new HttpEntity<>(
                tenantOnboardingPayload("Allowed Bootstrap", newCode, firstAdminEmail),
                jsonHeaders(token, ROOT_TENANT)),
            Map.class);

    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
    Company saved = companyRepository.findByCodeIgnoreCase(newCode).orElseThrow();
    assertThat(saved.getCode()).isEqualTo(newCode);
    assertThat(userAccountRepository.findByEmailIgnoreCase(firstAdminEmail)).isPresent();
    @SuppressWarnings("unchecked")
    Map<String, Object> data = (Map<String, Object>) response.getBody().get("data");
    assertThat(data)
        .containsEntry("companyCode", newCode)
        .containsEntry("templateCode", "MANUFACTURING")
        .containsEntry("bootstrapMode", "SEEDED")
        .containsEntry("seededChartOfAccounts", true)
        .containsEntry("defaultAccountingPeriodCreated", true)
        .containsEntry("tenantAdminProvisioned", true);
    assertThat(data.get("adminEmail")).isEqualTo(firstAdminEmail);
    assertThat(data.get("adminTemporaryPassword")).isNotNull();
  }

  @Test
  void super_admin_can_bootstrap_new_tenant_with_first_admin_credentials_provisioning() {
    String token = login(SUPER_ADMIN_EMAIL, ROOT_TENANT);
    String newCode = "TEN-ADM-" + System.nanoTime();
    String firstAdminEmail = "first-admin-" + System.nanoTime() + "@bbp.com";
    Map<String, Object> payload =
        tenantOnboardingPayload("Provisioned Tenant", newCode, firstAdminEmail);
    payload.put("firstAdminDisplayName", "Provisioned Admin");

    ResponseEntity<Map> response =
        rest.exchange(
            "/api/v1/superadmin/tenants/onboard",
            HttpMethod.POST,
            new HttpEntity<>(payload, jsonHeaders(token, ROOT_TENANT)),
            Map.class);

    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
    Company savedCompany = companyRepository.findByCodeIgnoreCase(newCode).orElseThrow();
    assertThat(savedCompany.getDefaultGstRate()).isEqualByComparingTo("18");
    UserAccount firstAdmin =
        userAccountRepository.findByEmailIgnoreCase(firstAdminEmail).orElseThrow();
    assertThat(firstAdmin.isMustChangePassword()).isTrue();
    assertThat(firstAdmin.getCompanies())
        .anyMatch(company -> company.getCode().equalsIgnoreCase(newCode));
    assertThat(firstAdmin.getRoles())
        .anyMatch(role -> "ROLE_ADMIN".equalsIgnoreCase(role.getName()));
    @SuppressWarnings("unchecked")
    Map<String, Object> data = (Map<String, Object>) response.getBody().get("data");
    assertThat(data)
        .containsEntry("companyCode", newCode)
        .containsEntry("adminEmail", firstAdminEmail)
        .containsEntry("tenantAdminProvisioned", true)
        .containsEntry("templateCode", "MANUFACTURING");
    assertThat(String.valueOf(data.get("adminTemporaryPassword"))).isNotBlank();
  }

  @Test
  void super_admin_can_reset_tenant_admin_password_for_support() {
    String superToken = login(SUPER_ADMIN_EMAIL, ROOT_TENANT);
    Long tenantAId =
        companyRepository.findByCodeIgnoreCase(TENANT_A).map(Company::getId).orElseThrow();

    ResponseEntity<Map> response =
        rest.exchange(
            "/api/v1/companies/" + tenantAId + "/support/admin-password-reset",
            HttpMethod.POST,
            new HttpEntity<>(
                Map.of("adminEmail", ADMIN_EMAIL), jsonHeaders(superToken, ROOT_TENANT)),
            Map.class);

    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
    UserAccount admin = userAccountRepository.findByEmailIgnoreCase(ADMIN_EMAIL).orElseThrow();
    assertThat(admin.isMustChangePassword()).isTrue();
  }

  @Test
  void root_only_super_admin_can_reset_tenant_admin_password_for_support() {
    String rootOnlySuperAdminEmail =
        "root-only-support-super-admin@" + System.nanoTime() + ".bbp.com";
    dataSeeder.ensureUser(
        rootOnlySuperAdminEmail,
        PASSWORD,
        "Root Only Support Super Admin",
        ROOT_TENANT,
        List.of("ROLE_SUPER_ADMIN"));
    String superToken = login(rootOnlySuperAdminEmail, ROOT_TENANT);
    Long tenantAId =
        companyRepository.findByCodeIgnoreCase(TENANT_A).map(Company::getId).orElseThrow();

    ResponseEntity<Map> response =
        rest.exchange(
            "/api/v1/companies/" + tenantAId + "/support/admin-password-reset",
            HttpMethod.POST,
            new HttpEntity<>(
                Map.of("adminEmail", ADMIN_EMAIL), jsonHeaders(superToken, ROOT_TENANT)),
            Map.class);

    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
    @SuppressWarnings("unchecked")
    Map<String, Object> responseBody = response.getBody();
    assertThat(responseBody).isNotNull();
    @SuppressWarnings("unchecked")
    Map<String, Object> data = (Map<String, Object>) responseBody.get("data");
    assertThat(data).isNotNull();
    assertThat(data.get("companyCode")).isEqualTo(TENANT_A);
    assertThat(data.get("adminEmail")).isEqualTo(ADMIN_EMAIL);
    assertThat(data).doesNotContainKeys("temporaryPassword", "adminTemporaryPassword");
  }

  @Test
  void control_plane_support_reset_denials_use_uniform_message_for_unknown_and_foreign_tenants() {
    String token = login(ADMIN_EMAIL, TENANT_A);
    Long tenantBId =
        companyRepository.findByCodeIgnoreCase(TENANT_B).map(Company::getId).orElseThrow();
    long unknownCompanyId =
        companyRepository.findAll().stream()
                .map(Company::getId)
                .filter(java.util.Objects::nonNull)
                .mapToLong(Long::longValue)
                .max()
                .orElse(0L)
            + 10_000L;

    ResponseEntity<Map> foreignTenantResponse =
        rest.exchange(
            "/api/v1/companies/" + tenantBId + "/support/admin-password-reset",
            HttpMethod.POST,
            new HttpEntity<>(Map.of("adminEmail", ADMIN_EMAIL), jsonHeaders(token, TENANT_A)),
            Map.class);

    ResponseEntity<Map> unknownTenantResponse =
        rest.exchange(
            "/api/v1/companies/" + unknownCompanyId + "/support/admin-password-reset",
            HttpMethod.POST,
            new HttpEntity<>(Map.of("adminEmail", ADMIN_EMAIL), jsonHeaders(token, TENANT_A)),
            Map.class);

    assertThat(foreignTenantResponse.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
    assertThat(unknownTenantResponse.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
    assertThat(foreignTenantResponse.getBody()).isNotNull();
    assertThat(unknownTenantResponse.getBody()).isNotNull();
    assertThat(foreignTenantResponse.getBody().get("success")).isEqualTo(Boolean.FALSE);
    assertThat(unknownTenantResponse.getBody().get("success")).isEqualTo(Boolean.FALSE);
    assertThat(foreignTenantResponse.getBody().get("message")).isEqualTo("Access denied");
    assertThat(unknownTenantResponse.getBody().get("message")).isEqualTo("Access denied");
    assertThat(foreignTenantResponse.getBody().get("timestamp")).isNotNull();
    assertThat(unknownTenantResponse.getBody().get("timestamp")).isNotNull();
    @SuppressWarnings("unchecked")
    Map<String, Object> foreignTenantError =
        (Map<String, Object>) foreignTenantResponse.getBody().get("data");
    @SuppressWarnings("unchecked")
    Map<String, Object> unknownTenantError =
        (Map<String, Object>) unknownTenantResponse.getBody().get("data");
    assertThat(foreignTenantError).isNotNull();
    assertThat(unknownTenantError).isNotNull();
    assertThat(foreignTenantError.get("code")).isEqualTo("AUTH_004");
    assertThat(unknownTenantError.get("code")).isEqualTo("AUTH_004");
    assertThat(foreignTenantError.get("message")).isEqualTo("Access denied");
    assertThat(unknownTenantError.get("message")).isEqualTo("Access denied");
    assertThat(foreignTenantError.get("reason")).isEqualTo("COMPANY_CONTROL_ACCESS_DENIED");
    assertThat(unknownTenantError.get("reason")).isEqualTo("COMPANY_CONTROL_ACCESS_DENIED");
    assertThat(foreignTenantError.get("reasonDetail")).isEqualTo(CONTROL_PLANE_AUTH_DENIED_MESSAGE);
    assertThat(unknownTenantError.get("reasonDetail")).isEqualTo(CONTROL_PLANE_AUTH_DENIED_MESSAGE);
    assertThat(foreignTenantError.get("traceId")).isNotNull();
    assertThat(unknownTenantError.get("traceId")).isNotNull();
  }

  @Test
  void admin_cannot_block_tenant_lifecycle_state() {
    String token = login(ADMIN_EMAIL, TENANT_A);
    Company before = companyRepository.findByCodeIgnoreCase(TENANT_A).orElseThrow();
    Long tenantAId = before.getId();

    ResponseEntity<Map> response =
        updateLifecycleState(tenantAId, token, TENANT_A, "BLOCKED", "Repeated policy breach");
    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
    entityManager.clear();
    Company after = companyRepository.findById(tenantAId).orElseThrow();
    assertThat(after.getLifecycleState()).isEqualTo(before.getLifecycleState());
    assertThat(after.getLifecycleReason()).isEqualTo(before.getLifecycleReason());
  }

  @Test
  void
      super_admin_can_hold_and_block_tenant_and_hold_lifecycle_allows_authenticated_reads_until_blocked()
          throws InterruptedException {
    String adminToken = login(ADMIN_EMAIL, TENANT_A);
    String superToken = login(SUPER_ADMIN_EMAIL, ROOT_TENANT);
    Long tenantAId =
        companyRepository.findByCodeIgnoreCase(TENANT_A).map(Company::getId).orElseThrow();

    ResponseEntity<Map> baselineMe =
        rest.exchange(
            "/api/v1/auth/me",
            HttpMethod.GET,
            new HttpEntity<>(jsonHeaders(adminToken, TENANT_A)),
            Map.class);
    assertThat(baselineMe.getStatusCode()).isEqualTo(HttpStatus.OK);

    String holdReason = "Compliance review in progress";
    ResponseEntity<Map> holdResponse =
        updateLifecycleState(tenantAId, superToken, ROOT_TENANT, "HOLD", holdReason);
    assertThat(holdResponse.getStatusCode()).isEqualTo(HttpStatus.OK);
    @SuppressWarnings("unchecked")
    Map<String, Object> holdData = (Map<String, Object>) holdResponse.getBody().get("data");
    assertThat(holdData).containsEntry("lifecycleState", "HOLD");
    assertThat(holdData).containsEntry("previousLifecycleState", "ACTIVE");
    assertThat(holdData).containsEntry("reason", holdReason);

    AuditLog holdEvidence =
        awaitAuditEvent(
            AuditEvent.CONFIGURATION_CHANGED,
            log ->
                SUPER_ADMIN_EMAIL.equalsIgnoreCase(log.getUsername())
                    && TENANT_A.equalsIgnoreCase(log.getMetadata().get("targetCompanyCode"))
                    && "tenant-lifecycle-state-updated".equals(log.getMetadata().get("reason"))
                    && "HOLD".equalsIgnoreCase(log.getMetadata().get("companyLifecycleState"))
                    && holdReason.equals(log.getMetadata().get("companyLifecycleReason")));
    assertThat(holdEvidence.getMetadata().get("lifecycleEvidence"))
        .isEqualTo("immutable-audit-log");

    ResponseEntity<Map> meDuringHold =
        rest.exchange(
            "/api/v1/auth/me",
            HttpMethod.GET,
            new HttpEntity<>(jsonHeaders(adminToken, TENANT_A)),
            Map.class);
    assertThat(meDuringHold.getStatusCode()).isEqualTo(HttpStatus.OK);

    String blockReason = "Critical security incident";
    ResponseEntity<Map> blockResponse =
        updateLifecycleState(tenantAId, superToken, ROOT_TENANT, "BLOCKED", blockReason);
    assertThat(blockResponse.getStatusCode()).isEqualTo(HttpStatus.OK);

    AuditLog blockEvidence =
        awaitAuditEvent(
            AuditEvent.CONFIGURATION_CHANGED,
            log ->
                SUPER_ADMIN_EMAIL.equalsIgnoreCase(log.getUsername())
                    && TENANT_A.equalsIgnoreCase(log.getMetadata().get("targetCompanyCode"))
                    && "BLOCKED".equalsIgnoreCase(log.getMetadata().get("companyLifecycleState"))
                    && blockReason.equals(log.getMetadata().get("companyLifecycleReason")));
    assertThat(blockEvidence.getMetadata().get("lifecycleEvidence"))
        .isEqualTo("immutable-audit-log");

    ResponseEntity<Map> meDuringBlock =
        rest.exchange(
            "/api/v1/auth/me",
            HttpMethod.GET,
            new HttpEntity<>(jsonHeaders(adminToken, TENANT_A)),
            Map.class);
    assertControlledAccessDenied(
        meDuringBlock, "TENANT_LIFECYCLE_RESTRICTED", "Tenant is deactivated");
  }

  @Test
  void lifecycle_state_change_requires_reason_metadata() {
    String superToken = login(SUPER_ADMIN_EMAIL, ROOT_TENANT);
    Long tenantAId =
        companyRepository.findByCodeIgnoreCase(TENANT_A).map(Company::getId).orElseThrow();

    ResponseEntity<Map> response =
        updateLifecycleState(tenantAId, superToken, ROOT_TENANT, "HOLD", " ");
    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
  }

  @Test
  void admin_cannot_create_tenant_admin_user() throws InterruptedException {
    String token = login(ADMIN_EMAIL, TENANT_A);
    Long tenantAId =
        companyRepository.findByCodeIgnoreCase(TENANT_A).map(Company::getId).orElseThrow();
    String candidateEmail = "candidate-" + System.nanoTime() + "@bbp.com";

    ResponseEntity<Map> response =
        rest.exchange(
            "/api/v1/admin/users",
            HttpMethod.POST,
            new HttpEntity<>(
                Map.of(
                    "email",
                    candidateEmail,
                    "password",
                    "ChangeMe123!",
                    "displayName",
                    "Tenant Admin Candidate",
                    "companyIds",
                    List.of(tenantAId),
                    "roles",
                    List.of("ROLE_ADMIN")),
                jsonHeaders(token, TENANT_A)),
            Map.class);

    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);

    AuditLog denied =
        awaitAuditEvent(
            AuditEvent.ACCESS_DENIED,
            log ->
                ADMIN_EMAIL.equalsIgnoreCase(log.getUsername())
                    && "tenant-admin-role-management-requires-super-admin"
                        .equals(log.getMetadata().get("reason"))
                    && "ROLE_ADMIN".equalsIgnoreCase(log.getMetadata().get("targetRole")));
    assertThat(denied.getMetadata()).containsEntry("actor", ADMIN_EMAIL);
    assertThat(denied.getMetadata().get("tenantScope")).contains(TENANT_A);
  }

  @Test
  void super_admin_tenant_context_cannot_create_tenant_admin_user_via_admin_workflow_surface() {
    String token = login(SUPER_ADMIN_EMAIL, TENANT_A);
    Long tenantAId =
        companyRepository.findByCodeIgnoreCase(TENANT_A).map(Company::getId).orElseThrow();
    String candidateEmail = "super-candidate-" + System.nanoTime() + "@bbp.com";

    ResponseEntity<Map> response =
        rest.exchange(
            "/api/v1/admin/users",
            HttpMethod.POST,
            new HttpEntity<>(
                Map.of(
                    "email",
                    candidateEmail,
                    "password",
                    "ChangeMe123!",
                    "displayName",
                    "Created By Super Admin",
                    "companyIds",
                    List.of(tenantAId),
                    "roles",
                    List.of("ROLE_ADMIN")),
                jsonHeaders(token, TENANT_A)),
            Map.class);

    assertControlledAccessDenied(
        response,
        "SUPER_ADMIN_PLATFORM_ONLY",
        "Super Admin is limited to platform control-plane operations and cannot execute tenant"
            + " business workflows");
  }

  @Test
  void
      root_only_super_admin_cannot_create_tenant_admin_user_for_foreign_tenant_via_admin_workflow_surface() {
    String rootOnlySuperAdminEmail =
        "root-only-create-super-admin@" + System.nanoTime() + ".bbp.com";
    dataSeeder.ensureUser(
        rootOnlySuperAdminEmail,
        PASSWORD,
        "Root Only Create Super Admin",
        ROOT_TENANT,
        List.of("ROLE_SUPER_ADMIN"));
    String token = login(rootOnlySuperAdminEmail, ROOT_TENANT);
    Long tenantAId =
        companyRepository.findByCodeIgnoreCase(TENANT_A).map(Company::getId).orElseThrow();
    String candidateEmail = "root-super-candidate-" + System.nanoTime() + "@bbp.com";

    ResponseEntity<Map> response =
        rest.exchange(
            "/api/v1/admin/users",
            HttpMethod.POST,
            new HttpEntity<>(
                Map.of(
                    "email",
                    candidateEmail,
                    "password",
                    "ChangeMe123!",
                    "displayName",
                    "Created By Root Super Admin",
                    "companyIds",
                    List.of(tenantAId),
                    "roles",
                    List.of("ROLE_ADMIN")),
                jsonHeaders(token, ROOT_TENANT)),
            Map.class);

    assertControlledAccessDenied(
        response,
        "SUPER_ADMIN_PLATFORM_ONLY",
        "Super Admin is limited to platform control-plane operations and cannot execute tenant"
            + " business workflows");
  }

  @Test
  void super_admin_without_admin_role_is_still_blocked_from_admin_only_sales_target_flow() {
    String token = login(SUPER_ADMIN_HIERARCHY_EMAIL, TENANT_A);
    String targetName = "Hierarchy Target " + System.nanoTime();

    ResponseEntity<Map> response =
        rest.exchange(
            "/api/v1/sales/targets",
            HttpMethod.POST,
            new HttpEntity<>(
                Map.of(
                    "name",
                    targetName,
                    "periodStart",
                    "2026-01-01",
                    "periodEnd",
                    "2026-12-31",
                    "targetAmount",
                    125000,
                    "assignee",
                    ADMIN_EMAIL,
                    "changeReason",
                    "hierarchy-rbac-validation"),
                jsonHeaders(token, TENANT_A)),
            Map.class);

    assertControlledAccessDenied(
        response,
        "SUPER_ADMIN_PLATFORM_ONLY",
        "Super Admin is limited to platform control-plane operations and cannot execute tenant"
            + " business workflows");
  }

  @Test
  void tenant_metrics_endpoint_is_super_admin_only() {
    Long tenantAId =
        companyRepository.findByCodeIgnoreCase(TENANT_A).map(Company::getId).orElseThrow();

    String adminToken = login(ADMIN_EMAIL, TENANT_A);
    ResponseEntity<Map> adminResponse =
        rest.exchange(
            "/api/v1/companies/" + tenantAId + "/tenant-metrics",
            HttpMethod.GET,
            new HttpEntity<>(jsonHeaders(adminToken, TENANT_A)),
            Map.class);
    assertThat(adminResponse.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);

    String superAdminToken = login(SUPER_ADMIN_EMAIL, TENANT_A);
    ResponseEntity<Map> superAdminResponse =
        rest.exchange(
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
    assertThat(data)
        .containsKeys(
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
    assertThat(data)
        .doesNotContainKeys("activeUserQuota", "apiRateLimitPerMinute", "auditStorageQuotaBytes");
    Number apiActivityCount = (Number) data.get("apiActivityCount");
    Number apiErrorCount = (Number) data.get("apiErrorCount");
    Number apiErrorRateInBasisPoints = (Number) data.get("apiErrorRateInBasisPoints");
    Number distinctSessionCount = (Number) data.get("distinctSessionCount");
    Number auditStorageBytes = (Number) data.get("auditStorageBytes");
    assertThat(apiActivityCount).isNotNull();
    assertThat(apiErrorCount).isNotNull();
    assertThat(apiErrorRateInBasisPoints).isNotNull();
    assertThat(distinctSessionCount).isNotNull();
    assertThat(auditStorageBytes).isNotNull();
    assertThat(apiActivityCount.longValue()).isGreaterThanOrEqualTo(apiErrorCount.longValue());
    assertThat(apiErrorRateInBasisPoints.longValue()).isBetween(0L, 10_000L);
    assertThat(distinctSessionCount.longValue()).isGreaterThanOrEqualTo(0L);
    assertThat(auditStorageBytes.longValue()).isGreaterThanOrEqualTo(0L);
  }

  @Test
  void root_only_super_admin_can_control_and_read_target_tenant_via_path_binding() {
    String rootOnlySuperAdminEmail = "root-only-path-super-admin@" + System.nanoTime() + ".bbp.com";
    dataSeeder.ensureUser(
        rootOnlySuperAdminEmail,
        PASSWORD,
        "Root Only Path Super Admin",
        ROOT_TENANT,
        List.of("ROLE_SUPER_ADMIN"));
    Long tenantAId =
        companyRepository.findByCodeIgnoreCase(TENANT_A).map(Company::getId).orElseThrow();
    String token = login(rootOnlySuperAdminEmail, ROOT_TENANT);

    ResponseEntity<Map> holdResponse =
        updateLifecycleState(tenantAId, token, ROOT_TENANT, "HOLD", "path-binding-hardening");
    assertThat(holdResponse.getStatusCode()).isEqualTo(HttpStatus.OK);

    ResponseEntity<Map> metricsResponse =
        rest.exchange(
            "/api/v1/companies/" + tenantAId + "/tenant-metrics",
            HttpMethod.GET,
            new HttpEntity<>(jsonHeaders(token, ROOT_TENANT)),
            Map.class);
    assertThat(metricsResponse.getStatusCode()).isEqualTo(HttpStatus.OK);
    @SuppressWarnings("unchecked")
    Map<String, Object> metricsBody = metricsResponse.getBody();
    assertThat(metricsBody).isNotNull();
    @SuppressWarnings("unchecked")
    Map<String, Object> metricsData = (Map<String, Object>) metricsBody.get("data");
    assertThat(metricsData).isNotNull();
    assertThat(metricsData.get("companyCode")).isEqualTo(TENANT_A);

    ResponseEntity<Map> restoreResponse =
        updateLifecycleState(
            tenantAId, token, ROOT_TENANT, "ACTIVE", "path-binding-hardening-restore");
    assertThat(restoreResponse.getStatusCode()).isEqualTo(HttpStatus.OK);
  }

  @Test
  void tenant_configuration_update_is_super_admin_only() {
    Long tenantAId =
        companyRepository.findByCodeIgnoreCase(TENANT_A).map(Company::getId).orElseThrow();

    String adminToken = login(ADMIN_EMAIL, TENANT_A);
    ResponseEntity<Map> adminResponse =
        updateCompany(
            tenantAId, adminToken, TENANT_A, "Blocked Admin Update", TENANT_A, "UTC", 18.0);
    assertThat(adminResponse.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);

    String superAdminToken = login(SUPER_ADMIN_EMAIL, TENANT_A);
    ResponseEntity<Map> superAdminResponse =
        updateCompany(
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
            true);
    assertThat(superAdminResponse.getStatusCode()).isEqualTo(HttpStatus.OK);
  }

  @Test
  void quota_soft_limit_only_does_not_block_runtime_requests() {
    Long tenantAId =
        companyRepository.findByCodeIgnoreCase(TENANT_A).map(Company::getId).orElseThrow();
    String adminToken = login(ADMIN_EMAIL, TENANT_A);
    String superAdminToken = login(SUPER_ADMIN_EMAIL, TENANT_A);

    ResponseEntity<Map> configureSoftOnly =
        updateCompany(
            tenantAId,
            superAdminToken,
            TENANT_A,
            "Quota Soft Limit Only",
            TENANT_A,
            "UTC",
            18.0,
            1L,
            1_000_000L,
            1_000_000_000L,
            1_000L,
            true,
            false);
    assertThat(configureSoftOnly.getStatusCode()).isEqualTo(HttpStatus.OK);

    ResponseEntity<Map> runtimeAllowed =
        rest.exchange(
            "/api/v1/auth/me",
            HttpMethod.GET,
            new HttpEntity<>(jsonHeaders(adminToken, TENANT_A)),
            Map.class);
    assertThat(runtimeAllowed.getStatusCode()).isEqualTo(HttpStatus.OK);

    ResponseEntity<Map> resetResponse =
        updateCompany(
            tenantAId,
            superAdminToken,
            TENANT_A,
            "Quota Soft Limit Only Reset",
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
    Long tenantBId =
        companyRepository.findByCodeIgnoreCase(TENANT_B).map(Company::getId).orElseThrow();

    HttpHeaders mismatchHeaders = jsonHeaders(token, TENANT_B);
    ResponseEntity<Map> mismatchMe =
        rest.exchange(
            "/api/v1/auth/me", HttpMethod.GET, new HttpEntity<>(mismatchHeaders), Map.class);
    assertThat(mismatchMe.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);

    ResponseEntity<Map> unauthenticatedWithHeader =
        rest.exchange(
            "/api/v1/auth/me",
            HttpMethod.GET,
            new HttpEntity<>(jsonHeaders(null, TENANT_A)),
            Map.class);
    assertThat(unauthenticatedWithHeader.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);

    ResponseEntity<Map> idorUpdate =
        rest.exchange(
            "/api/v1/companies/" + tenantBId,
            HttpMethod.PUT,
            new HttpEntity<>(
                Map.of(
                    "name",
                    "Cross Tenant Update",
                    "code",
                    TENANT_B,
                    "timezone",
                    "UTC",
                    "defaultGstRate",
                    18.0),
                jsonHeaders(token, TENANT_A)),
            Map.class);
    assertThat(idorUpdate.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
  }

  @Test
  void tenant_admin_cannot_mutate_shared_role_permissions() throws InterruptedException {
    String adminToken = login(ROLE_GUARD_ADMIN_EMAIL, TENANT_A);
    String superAdminToken = login(SUPER_ADMIN_EMAIL, TENANT_A);
    assertTokenCanAccessMe(adminToken, TENANT_A);
    Map<String, Object> before = fetchRoleData(superAdminToken, TENANT_A, "ROLE_FACTORY");
    Set<String> beforePermissions = extractPermissionCodes(before);

    ResponseEntity<Map> denied =
        rest.exchange(
            "/api/v1/admin/roles",
            HttpMethod.POST,
            new HttpEntity<>(
                Map.of(
                    "name", "ROLE_FACTORY",
                    "description", "Tenant mutation attempt",
                    "permissions", factoryRolePermissionCodes()),
                jsonHeaders(adminToken, TENANT_A)),
            Map.class);

    assertForbiddenFromRoleMutationGuard(denied);
    Map<String, Object> after = fetchRoleData(superAdminToken, TENANT_A, "ROLE_FACTORY");
    assertThat(extractPermissionCodes(after)).isEqualTo(beforePermissions);

    AuditLog deniedAudit =
        awaitAuditEvent(
            AuditEvent.ACCESS_DENIED,
            log ->
                ROLE_GUARD_ADMIN_EMAIL.equalsIgnoreCase(log.getUsername())
                    && "shared-role-permission-mutation-requires-super-admin"
                        .equals(log.getMetadata().get("reason"))
                    && "ROLE_FACTORY".equalsIgnoreCase(log.getMetadata().get("targetRole")));
    assertThat(deniedAudit.getMetadata().get("tenantScope")).contains(TENANT_A);
  }

  @Test
  void super_admin_can_mutate_shared_role_permissions_via_existing_endpoint()
      throws InterruptedException {
    String superAdminToken = login(SUPER_ADMIN_EMAIL, TENANT_A);
    Map<String, Object> baseline = fetchRoleData(superAdminToken, TENANT_A, "ROLE_FACTORY");
    String description = String.valueOf(baseline.get("description"));
    List<String> permissions = extractPermissionCodes(baseline).stream().toList();

    ResponseEntity<Map> response =
        rest.exchange(
            "/api/v1/admin/roles",
            HttpMethod.POST,
            new HttpEntity<>(
                Map.of(
                    "name",
                    "ROLE_FACTORY",
                    "description",
                    description,
                    "permissions",
                    permissions.isEmpty() ? factoryRolePermissionCodes() : permissions),
                jsonHeaders(superAdminToken, TENANT_A)),
            Map.class);

    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
    AuditLog grantedAudit =
        awaitAuditEvent(
            AuditEvent.ACCESS_GRANTED,
            log ->
                SUPER_ADMIN_EMAIL.equalsIgnoreCase(log.getUsername())
                    && "shared-role-permission-mutation-approved"
                        .equals(log.getMetadata().get("reason"))
                    && "ROLE_FACTORY".equalsIgnoreCase(log.getMetadata().get("targetRole")));
    assertThat(grantedAudit.getMetadata().get("tenantScope")).contains(TENANT_A);
  }

  @Test
  void startupRoleSynchronization_realignsDispatchConfirmAuthoritiesForSystemRoles() {
    addRolePermission("ROLE_SALES", "dispatch.confirm");
    removeRolePermission("ROLE_ADMIN", "dispatch.confirm");
    removeRolePermission("ROLE_ACCOUNTING", "dispatch.confirm");
    removeRolePermission("ROLE_FACTORY", "dispatch.confirm");

    int synchronizedRoles = roleService.synchronizeSystemRoles();

    assertThat(synchronizedRoles).isGreaterThan(0);
    assertThat(rolePermissionCodes("ROLE_ADMIN")).contains("dispatch.confirm");
    assertThat(rolePermissionCodes("ROLE_ACCOUNTING")).contains("dispatch.confirm");
    assertThat(rolePermissionCodes("ROLE_FACTORY")).contains("dispatch.confirm");
    assertThat(rolePermissionCodes("ROLE_SALES")).doesNotContain("dispatch.confirm");

    assertThat(mePermissionCodes(login(ADMIN_EMAIL, TENANT_A), TENANT_A)).contains("dispatch.confirm");
    assertThat(mePermissionCodes(login(ACCOUNTING_SYNC_EMAIL, TENANT_A), TENANT_A))
        .contains("dispatch.confirm");
    assertThat(mePermissionCodes(login("factory-seed@bbp.com", TENANT_A), TENANT_A))
        .contains("dispatch.confirm");
    assertThat(mePermissionCodes(login(SALES_SYNC_EMAIL, TENANT_A), TENANT_A))
        .doesNotContain("dispatch.confirm");
  }

  @Test
  void tenant_admin_can_still_create_non_privileged_user() {
    String token = login(NON_PRIVILEGED_ADMIN_EMAIL, TENANT_A);
    Long tenantAId =
        companyRepository.findByCodeIgnoreCase(TENANT_A).map(Company::getId).orElseThrow();
    String candidateEmail = "sales-operator-" + System.nanoTime() + "@bbp.com";

    ResponseEntity<Map> response =
        rest.exchange(
            "/api/v1/admin/users",
            HttpMethod.POST,
            new HttpEntity<>(
                Map.of(
                    "email",
                    candidateEmail,
                    "password",
                    "ChangeMe123!",
                    "displayName",
                    "Sales Operator",
                    "companyIds",
                    List.of(tenantAId),
                    "roles",
                    List.of("ROLE_SALES")),
                jsonHeaders(token, TENANT_A)),
            Map.class);

    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
    Map<String, Object> body = response.getBody();
    assertThat(body).isNotNull();
    @SuppressWarnings("unchecked")
    Map<String, Object> data = (Map<String, Object>) body.get("data");
    assertThat(data.get("email")).isEqualTo(candidateEmail);
    @SuppressWarnings("unchecked")
    List<String> roles = (List<String>) data.get("roles");
    assertThat(roles).contains("ROLE_SALES");
  }

  @Test
  void
      tenant_admin_cross_tenant_privileged_user_actions_mask_foreign_targets_as_missing_and_still_audit_denials()
          throws InterruptedException {
    String token = login(ADMIN_EMAIL, TENANT_A);
    Long foreignUserId =
        userAccountRepository
            .findByEmailIgnoreCase("other-admin@bbp.com")
            .map(UserAccount::getId)
            .orElseThrow();
    long missingUserId = foreignUserId + 10_000L;

    assertMaskedPrivilegedUserActionPair(
        rest.exchange(
            "/api/v1/admin/users/" + foreignUserId + "/force-reset-password",
            HttpMethod.POST,
            new HttpEntity<>(jsonHeaders(token, TENANT_A)),
            Map.class),
        rest.exchange(
            "/api/v1/admin/users/" + missingUserId + "/force-reset-password",
            HttpMethod.POST,
            new HttpEntity<>(jsonHeaders(token, TENANT_A)),
            Map.class));
    awaitPrivilegedUserDeniedAudit(
        ADMIN_EMAIL, foreignUserId, "admin-force-reset-password-out-of-scope");

    assertMaskedPrivilegedUserActionPair(
        rest.exchange(
            "/api/v1/admin/users/" + foreignUserId + "/status",
            HttpMethod.PUT,
            new HttpEntity<>(Map.of("enabled", false), jsonHeaders(token, TENANT_A)),
            Map.class),
        rest.exchange(
            "/api/v1/admin/users/" + missingUserId + "/status",
            HttpMethod.PUT,
            new HttpEntity<>(Map.of("enabled", false), jsonHeaders(token, TENANT_A)),
            Map.class));
    awaitPrivilegedUserDeniedAudit(ADMIN_EMAIL, foreignUserId, "admin-status-update-out-of-scope");

    assertMaskedPrivilegedUserActionPair(
        rest.exchange(
            "/api/v1/admin/users/" + foreignUserId + "/suspend",
            HttpMethod.PATCH,
            new HttpEntity<>(jsonHeaders(token, TENANT_A)),
            Map.class),
        rest.exchange(
            "/api/v1/admin/users/" + missingUserId + "/suspend",
            HttpMethod.PATCH,
            new HttpEntity<>(jsonHeaders(token, TENANT_A)),
            Map.class));
    awaitPrivilegedUserDeniedAudit(ADMIN_EMAIL, foreignUserId, "admin-suspend-user-out-of-scope");

    assertMaskedPrivilegedUserActionPair(
        rest.exchange(
            "/api/v1/admin/users/" + foreignUserId + "/unsuspend",
            HttpMethod.PATCH,
            new HttpEntity<>(jsonHeaders(token, TENANT_A)),
            Map.class),
        rest.exchange(
            "/api/v1/admin/users/" + missingUserId + "/unsuspend",
            HttpMethod.PATCH,
            new HttpEntity<>(jsonHeaders(token, TENANT_A)),
            Map.class));
    awaitPrivilegedUserDeniedAudit(ADMIN_EMAIL, foreignUserId, "admin-unsuspend-user-out-of-scope");

    assertMaskedPrivilegedUserActionPair(
        rest.exchange(
            "/api/v1/admin/users/" + foreignUserId + "/mfa/disable",
            HttpMethod.PATCH,
            new HttpEntity<>(jsonHeaders(token, TENANT_A)),
            Map.class),
        rest.exchange(
            "/api/v1/admin/users/" + missingUserId + "/mfa/disable",
            HttpMethod.PATCH,
            new HttpEntity<>(jsonHeaders(token, TENANT_A)),
            Map.class));
    awaitPrivilegedUserDeniedAudit(ADMIN_EMAIL, foreignUserId, "admin-disable-mfa-out-of-scope");

    assertMaskedPrivilegedUserActionPair(
        rest.exchange(
            "/api/v1/admin/users/" + foreignUserId,
            HttpMethod.DELETE,
            new HttpEntity<>(jsonHeaders(token, TENANT_A)),
            Map.class),
        rest.exchange(
            "/api/v1/admin/users/" + missingUserId,
            HttpMethod.DELETE,
            new HttpEntity<>(jsonHeaders(token, TENANT_A)),
            Map.class));
    awaitPrivilegedUserDeniedAudit(ADMIN_EMAIL, foreignUserId, "admin-delete-user-out-of-scope");
  }

  @Test
  void super_admin_tenant_context_cannot_execute_privileged_user_action_matrix_across_tenants() {
    String token = login(SUPER_ADMIN_EMAIL, TENANT_A);
    Long foreignUserId =
        userAccountRepository
            .findByEmailIgnoreCase("other-admin@bbp.com")
            .map(UserAccount::getId)
            .orElseThrow();

    ResponseEntity<Map> forceResetResponse =
        rest.exchange(
            "/api/v1/admin/users/" + foreignUserId + "/force-reset-password",
            HttpMethod.POST,
            new HttpEntity<>(jsonHeaders(token, TENANT_A)),
            Map.class);
    assertControlledAccessDenied(
        forceResetResponse,
        "SUPER_ADMIN_PLATFORM_ONLY",
        "Super Admin is limited to platform control-plane operations and cannot execute tenant"
            + " business workflows");

    ResponseEntity<Map> suspendResponse =
        rest.exchange(
            "/api/v1/admin/users/" + foreignUserId + "/suspend",
            HttpMethod.PATCH,
            new HttpEntity<>(jsonHeaders(token, TENANT_A)),
            Map.class);
    assertControlledAccessDenied(
        suspendResponse,
        "SUPER_ADMIN_PLATFORM_ONLY",
        "Super Admin is limited to platform control-plane operations and cannot execute tenant"
            + " business workflows");

    ResponseEntity<Map> unsuspendResponse =
        rest.exchange(
            "/api/v1/admin/users/" + foreignUserId + "/unsuspend",
            HttpMethod.PATCH,
            new HttpEntity<>(jsonHeaders(token, TENANT_A)),
            Map.class);
    assertControlledAccessDenied(
        unsuspendResponse,
        "SUPER_ADMIN_PLATFORM_ONLY",
        "Super Admin is limited to platform control-plane operations and cannot execute tenant"
            + " business workflows");

    ResponseEntity<Map> disableResponse =
        rest.exchange(
            "/api/v1/admin/users/" + foreignUserId + "/status",
            HttpMethod.PUT,
            new HttpEntity<>(Map.of("enabled", false), jsonHeaders(token, TENANT_A)),
            Map.class);
    assertControlledAccessDenied(
        disableResponse,
        "SUPER_ADMIN_PLATFORM_ONLY",
        "Super Admin is limited to platform control-plane operations and cannot execute tenant"
            + " business workflows");

    ResponseEntity<Map> enableResponse =
        rest.exchange(
            "/api/v1/admin/users/" + foreignUserId + "/status",
            HttpMethod.PUT,
            new HttpEntity<>(Map.of("enabled", true), jsonHeaders(token, TENANT_A)),
            Map.class);
    assertControlledAccessDenied(
        enableResponse,
        "SUPER_ADMIN_PLATFORM_ONLY",
        "Super Admin is limited to platform control-plane operations and cannot execute tenant"
            + " business workflows");

    ResponseEntity<Map> disableMfaResponse =
        rest.exchange(
            "/api/v1/admin/users/" + foreignUserId + "/mfa/disable",
            HttpMethod.PATCH,
            new HttpEntity<>(jsonHeaders(token, TENANT_A)),
            Map.class);
    assertControlledAccessDenied(
        disableMfaResponse,
        "SUPER_ADMIN_PLATFORM_ONLY",
        "Super Admin is limited to platform control-plane operations and cannot execute tenant"
            + " business workflows");

    ResponseEntity<Map> deleteResponse =
        rest.exchange(
            "/api/v1/admin/users/" + foreignUserId,
            HttpMethod.DELETE,
            new HttpEntity<>(jsonHeaders(token, TENANT_A)),
            Map.class);
    assertControlledAccessDenied(
        deleteResponse,
        "SUPER_ADMIN_PLATFORM_ONLY",
        "Super Admin is limited to platform control-plane operations and cannot execute tenant"
            + " business workflows");
  }

  private void assertTokenCanAccessMe(String token, String companyCode) {
    ResponseEntity<Map> authCheck =
        rest.exchange(
            "/api/v1/auth/me",
            HttpMethod.GET,
            new HttpEntity<>(jsonHeaders(token, companyCode)),
            Map.class);
    assertThat(authCheck.getStatusCode()).isEqualTo(HttpStatus.OK);
  }

  private void assertForbiddenFromRoleMutationGuard(ResponseEntity<Map> denied) {
    assertThat(denied.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
    Map<String, Object> body = denied.getBody();
    assertThat(body).isNotNull();
    assertThat(body.get("success")).isEqualTo(Boolean.FALSE);
    assertThat(body.get("message")).isEqualTo("Access denied");
    Object errorValue = body.get("data");
    assertThat(errorValue).isInstanceOf(Map.class);
    @SuppressWarnings("unchecked")
    Map<String, Object> error = (Map<String, Object>) errorValue;
    assertThat(error.get("code")).isEqualTo("AUTH_004");
    assertThat(error.get("message")).isEqualTo("Access denied");
  }

  @SuppressWarnings("unchecked")
  private void assertControlledAccessDenied(
      ResponseEntity<Map> response, String reason, String reasonDetail) {
    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
    Map<String, Object> body = response.getBody();
    assertThat(body).isNotNull();
    assertThat(body.get("success")).isEqualTo(Boolean.FALSE);
    assertThat(body.get("message")).isEqualTo("Access denied");
    Object errorValue = body.get("data");
    assertThat(errorValue).isInstanceOf(Map.class);
    Map<String, Object> error = (Map<String, Object>) errorValue;
    assertThat(error.get("code")).isEqualTo("AUTH_004");
    assertThat(error.get("message")).isEqualTo("Access denied");
    assertThat(error.get("reason")).isEqualTo(reason);
    assertThat(error.get("reasonDetail")).isEqualTo(reasonDetail);
    assertThat(error.get("traceId")).isNotNull();
  }

  private void assertMaskedPrivilegedUserActionPair(
      ResponseEntity<Map> foreignResponse, ResponseEntity<Map> missingResponse) {
    Map<String, Object> foreignError = assertMaskedPrivilegedUserAction(foreignResponse);
    Map<String, Object> missingError = assertMaskedPrivilegedUserAction(missingResponse);
    assertThat(foreignError.get("code")).isEqualTo(missingError.get("code"));
    assertThat(foreignError.get("message")).isEqualTo(missingError.get("message"));
    assertThat(foreignError.get("reason")).isEqualTo(missingError.get("reason"));
  }

  @SuppressWarnings("unchecked")
  private Map<String, Object> assertMaskedPrivilegedUserAction(ResponseEntity<Map> response) {
    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    Map<String, Object> body = response.getBody();
    assertThat(body).isNotNull();
    assertThat(body.get("success")).isEqualTo(Boolean.FALSE);
    assertThat(body.get("message")).isEqualTo("User not found");
    Object errorValue = body.get("data");
    assertThat(errorValue).isInstanceOf(Map.class);
    Map<String, Object> error = (Map<String, Object>) errorValue;
    assertThat(error.get("code")).isEqualTo("VAL_001");
    assertThat(error.get("message")).isEqualTo("User not found");
    assertThat(error.get("reason")).isEqualTo("User not found");
    assertThat(error.get("traceId")).isNotNull();
    assertThat(error.get("path")).isNotNull();
    return error;
  }

  private void awaitPrivilegedUserDeniedAudit(String actorEmail, Long targetUserId, String reason)
      throws InterruptedException {
    AuditLog denied =
        awaitAuditEvent(
            AuditEvent.ACCESS_DENIED,
            log ->
                actorEmail.equalsIgnoreCase(log.getUsername())
                    && reason.equals(log.getMetadata().get("reason"))
                    && String.valueOf(targetUserId).equals(log.getMetadata().get("targetUserId")));
    assertThat(denied.getMetadata()).containsEntry("actor", actorEmail);
    assertThat(denied.getMetadata().get("tenantScope")).contains(TENANT_A);
    assertThat(denied.getMetadata().get("targetCompanyCode")).contains(TENANT_B);
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

  private Map<String, Object> fetchRoleData(String token, String companyCode, String roleKey) {
    ResponseEntity<Map> response =
        rest.exchange(
            "/api/v1/admin/roles/" + roleKey,
            HttpMethod.GET,
            new HttpEntity<>(jsonHeaders(token, companyCode)),
            Map.class);
    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
    Map<String, Object> body = response.getBody();
    assertThat(body).isNotNull();
    Object data = body.get("data");
    assertThat(data).isInstanceOf(Map.class);
    @SuppressWarnings("unchecked")
    Map<String, Object> roleData = (Map<String, Object>) data;
    return roleData;
  }

  private Set<String> extractPermissionCodes(Map<String, Object> roleData) {
    Object permissionsValue = roleData.get("permissions");
    if (!(permissionsValue instanceof List<?> permissions)) {
      return Set.of();
    }
    return permissions.stream()
        .filter(Map.class::isInstance)
        .map(Map.class::cast)
        .map(entry -> entry.get("code"))
        .filter(String.class::isInstance)
        .map(String.class::cast)
        .collect(Collectors.toSet());
  }

  private Set<String> mePermissionCodes(String token, String companyCode) {
    ResponseEntity<Map> response =
        rest.exchange(
            "/api/v1/auth/me",
            HttpMethod.GET,
            new HttpEntity<>(jsonHeaders(token, companyCode)),
            Map.class);
    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
    Map<String, Object> body = response.getBody();
    assertThat(body).isNotNull();
    Object dataValue = body.get("data");
    assertThat(dataValue).isInstanceOf(Map.class);
    @SuppressWarnings("unchecked")
    Map<String, Object> data = (Map<String, Object>) dataValue;
    Object permissionsValue = data.get("permissions");
    if (!(permissionsValue instanceof List<?> permissions)) {
      return Set.of();
    }
    return permissions.stream()
        .filter(String.class::isInstance)
        .map(String.class::cast)
        .collect(Collectors.toSet());
  }

  private Set<String> rolePermissionCodes(String roleName) {
    return roleRepository.findByName(roleName).map(this::permissionCodes).orElseThrow();
  }

  private Set<String> permissionCodes(Role role) {
    return role.getPermissions().stream()
        .map(Permission::getCode)
        .filter(String.class::isInstance)
        .collect(Collectors.toSet());
  }

  private void addRolePermission(String roleName, String permissionCode) {
    mutateRolePermission(roleName, permissionCode, true);
  }

  private void removeRolePermission(String roleName, String permissionCode) {
    mutateRolePermission(roleName, permissionCode, false);
  }

  private void mutateRolePermission(String roleName, String permissionCode, boolean present) {
    Role role = roleRepository.findByName(roleName).orElseThrow();
    Permission permission =
        permissionRepository
            .findByCode(permissionCode)
            .orElseGet(
                () -> {
                  Permission created = new Permission();
                  created.setCode(permissionCode);
                  created.setDescription(permissionCode);
                  return permissionRepository.save(created);
                });
    if (present) {
      role.getPermissions().add(permission);
    } else {
      role.getPermissions().removeIf(existing -> permissionCode.equalsIgnoreCase(existing.getCode()));
    }
    roleRepository.save(role);
  }

  private List<String> factoryRolePermissionCodes() {
    return List.of("portal:factory", "factory.dispatch");
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

  private ResponseEntity<Map> updateLifecycleState(
      Long companyId, String token, String companyCode, String state, String reason) {
    return rest.exchange(
        "/api/v1/companies/" + companyId + "/lifecycle-state",
        HttpMethod.POST,
        new HttpEntity<>(
            Map.of(
                "state", state,
                "reason", reason),
            jsonHeaders(token, companyCode)),
        Map.class);
  }

  private ResponseEntity<Map> updateCompany(
      Long companyId,
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

  private ResponseEntity<Map> updateCompany(
      Long companyId,
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

  private Map<String, Object> tenantOnboardingPayload(
      String name, String code, String firstAdminEmail) {
    Map<String, Object> payload = new HashMap<>();
    payload.put("name", name);
    payload.put("code", code);
    payload.put("timezone", "UTC");
    payload.put("defaultGstRate", 18.0);
    payload.put("firstAdminEmail", firstAdminEmail);
    payload.put("firstAdminDisplayName", name + " Admin");
    payload.put("coaTemplateCode", "MANUFACTURING");
    return payload;
  }

  private AuditLog awaitAuditEvent(AuditEvent eventType, Predicate<AuditLog> matcher)
      throws InterruptedException {
    for (int i = 0; i < 80; i++) {
      entityManager.clear();
      List<AuditLog> logs =
          entityManager
              .createQuery(
                  "select distinct al from AuditLog al left join fetch al.metadata where"
                      + " al.eventType = :eventType order by al.timestamp desc",
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
