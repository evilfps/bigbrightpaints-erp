package com.bigbrightpaints.erp.truthsuite.runtime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.platform.engine.discovery.DiscoverySelectors;
import org.junit.platform.launcher.Launcher;
import org.junit.platform.launcher.LauncherDiscoveryRequest;
import org.junit.platform.launcher.core.LauncherDiscoveryRequestBuilder;
import org.junit.platform.launcher.core.LauncherFactory;
import org.junit.platform.launcher.listeners.SummaryGeneratingListener;
import org.junit.platform.launcher.listeners.TestExecutionSummary;
import org.springframework.mail.MailSendException;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.mail.javamail.MimeMessagePreparator;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.util.ReflectionTestUtils;
import org.thymeleaf.spring6.SpringTemplateEngine;

import com.bigbrightpaints.erp.core.audit.AuditService;
import com.bigbrightpaints.erp.core.config.EmailProperties;
import com.bigbrightpaints.erp.core.config.SystemSettingsRepository;
import com.bigbrightpaints.erp.core.exception.ApplicationException;
import com.bigbrightpaints.erp.core.exception.ErrorCode;
import com.bigbrightpaints.erp.core.notification.EmailService;
import com.bigbrightpaints.erp.modules.auth.domain.UserAccount;
import com.bigbrightpaints.erp.modules.auth.domain.UserAccountRepository;
import com.bigbrightpaints.erp.modules.auth.service.PasswordResetService;
import com.bigbrightpaints.erp.modules.auth.service.ScopedAccountBootstrapService;
import com.bigbrightpaints.erp.modules.auth.service.TenantAdminProvisioningService;
import com.bigbrightpaints.erp.modules.company.domain.Company;
import com.bigbrightpaints.erp.modules.company.service.CompanyService;
import com.bigbrightpaints.erp.modules.company.service.TenantRuntimeEnforcementService;
import com.bigbrightpaints.erp.modules.rbac.domain.Role;
import com.bigbrightpaints.erp.modules.rbac.domain.RoleRepository;
import com.bigbrightpaints.erp.modules.rbac.service.RoleService;

@Tag("critical")
class TS_RuntimeTenantPolicyControlExecutableCoverageTest {

  @AfterEach
  void clearSecurity() {
    SecurityContextHolder.clearContext();
  }

  @Test
  void companyService_updateTenantRuntimePolicy_covers_constructors_and_guards() {
    com.bigbrightpaints.erp.modules.company.domain.CompanyRepository repository =
        mock(com.bigbrightpaints.erp.modules.company.domain.CompanyRepository.class);
    CompanyService oneArg = new CompanyService(repository);
    CompanyService fourArg = new CompanyService(repository, null, null, null);
    CompanyService.TenantRuntimePolicyMutationRequest mutation =
        new CompanyService.TenantRuntimePolicyMutationRequest("HOLD", "policy", 1, 1, 1);

    assertThatThrownBy(() -> oneArg.updateTenantRuntimePolicy(1L, mutation))
        .isInstanceOf(ApplicationException.class);
    assertThatThrownBy(() -> fourArg.updateTenantRuntimePolicy(1L, mutation))
        .isInstanceOf(ApplicationException.class);
  }

  @Test
  void companyService_updateTenantRuntimePolicy_enforces_superAdmin_and_state_parsing() {
    com.bigbrightpaints.erp.modules.company.domain.CompanyRepository repository =
        mock(com.bigbrightpaints.erp.modules.company.domain.CompanyRepository.class);
    AuditService auditService = mock(AuditService.class);
    UserAccountRepository userAccountRepository = mock(UserAccountRepository.class);
    com.bigbrightpaints.erp.core.audit.AuditLogRepository auditLogRepository =
        mock(com.bigbrightpaints.erp.core.audit.AuditLogRepository.class);
    TenantRuntimeEnforcementService runtimeService = mock(TenantRuntimeEnforcementService.class);
    CompanyService companyService =
        new CompanyService(
            repository, auditService, userAccountRepository, auditLogRepository, runtimeService);

    Company company = company(12L, "ACME");
    when(repository.findById(12L)).thenReturn(Optional.of(company));
    when(runtimeService.updatePolicy(
            eq("ACME"),
            eq(TenantRuntimeEnforcementService.TenantRuntimeState.BLOCKED),
            eq("incident"),
            eq(4),
            eq(8),
            eq(16),
            eq("super@bbp.com")))
        .thenReturn(snapshot("ACME"));
    when(runtimeService.updatePolicy(
            eq("ACME"),
            eq(TenantRuntimeEnforcementService.TenantRuntimeState.ACTIVE),
            eq("active"),
            eq(2),
            isNull(),
            isNull(),
            eq("super@bbp.com")))
        .thenReturn(snapshot("ACME"));
    when(runtimeService.updatePolicy(
            eq("ACME"),
            eq(TenantRuntimeEnforcementService.TenantRuntimeState.HOLD),
            eq("hold"),
            isNull(),
            eq(7),
            isNull(),
            eq("super@bbp.com")))
        .thenReturn(snapshot("ACME"));
    when(runtimeService.updatePolicy(
            eq("ACME"),
            isNull(),
            eq("limits-only"),
            eq(9),
            isNull(),
            isNull(),
            eq("super@bbp.com")))
        .thenReturn(snapshot("ACME"));
    when(runtimeService.updatePolicy(
            eq("ACME"), isNull(), eq("rpm-only"), isNull(), eq(11), isNull(), eq("super@bbp.com")))
        .thenReturn(snapshot("ACME"));
    when(runtimeService.updatePolicy(
            eq("ACME"),
            isNull(),
            eq("users-only"),
            isNull(),
            isNull(),
            eq(22),
            eq("super@bbp.com")))
        .thenReturn(snapshot("ACME"));

    authenticate("super@bbp.com", "ROLE_SUPER_ADMIN");
    CompanyService.TenantRuntimePolicyMutationRequest validRequest =
        new CompanyService.TenantRuntimePolicyMutationRequest("BLOCKED", "incident", 4, 8, 16);
    TenantRuntimeEnforcementService.TenantRuntimeSnapshot updated =
        companyService.updateTenantRuntimePolicy(12L, validRequest);
    assertThat(updated.companyCode()).isEqualTo("ACME");
    assertThat(
            companyService.updateTenantRuntimePolicy(
                12L,
                new CompanyService.TenantRuntimePolicyMutationRequest(
                    "ACTIVE", "active", 2, null, null)))
        .isNotNull();
    assertThat(
            companyService.updateTenantRuntimePolicy(
                12L,
                new CompanyService.TenantRuntimePolicyMutationRequest(
                    "HOLD", "hold", null, 7, null)))
        .isNotNull();
    assertThat(
            companyService.updateTenantRuntimePolicy(
                12L,
                new CompanyService.TenantRuntimePolicyMutationRequest(
                    null, "limits-only", 9, null, null)))
        .isNotNull();
    assertThat(
            companyService.updateTenantRuntimePolicy(
                12L,
                new CompanyService.TenantRuntimePolicyMutationRequest(
                    null, "rpm-only", null, 11, null)))
        .isNotNull();
    assertThat(
            companyService.updateTenantRuntimePolicy(
                12L,
                new CompanyService.TenantRuntimePolicyMutationRequest(
                    null, "users-only", null, null, 22)))
        .isNotNull();

    authenticate("admin@bbp.com", "ROLE_ADMIN");
    assertThatThrownBy(() -> companyService.updateTenantRuntimePolicy(12L, validRequest))
        .isInstanceOf(org.springframework.security.access.AccessDeniedException.class);
    assertThatThrownBy(() -> companyService.updateTenantRuntimePolicy(null, validRequest))
        .isInstanceOf(org.springframework.security.access.AccessDeniedException.class);

    authenticate("super@bbp.com", "ROLE_SUPER_ADMIN");
    assertThatThrownBy(() -> companyService.updateTenantRuntimePolicy(12L, null))
        .isInstanceOf(ApplicationException.class);
    assertThatThrownBy(
            () ->
                companyService.updateTenantRuntimePolicy(
                    12L,
                    new CompanyService.TenantRuntimePolicyMutationRequest(
                        null, null, null, null, null)))
        .isInstanceOf(ApplicationException.class);
    assertThatThrownBy(
            () ->
                companyService.updateTenantRuntimePolicy(
                    12L,
                    new CompanyService.TenantRuntimePolicyMutationRequest(
                        "UNKNOWN", "x", null, null, null)))
        .isInstanceOf(ApplicationException.class);
  }

  @Test
  void tenantRuntimeEnforcementService_policyControl_and_updatePolicy_paths_are_executable() {
    com.bigbrightpaints.erp.modules.company.domain.CompanyRepository repository =
        mock(com.bigbrightpaints.erp.modules.company.domain.CompanyRepository.class);
    SystemSettingsRepository systemSettingsRepository = mock(SystemSettingsRepository.class);
    UserAccountRepository userAccountRepository = mock(UserAccountRepository.class);
    AuditService auditService = mock(AuditService.class);
    Company company = company(21L, "ACME");
    when(repository.findByCodeIgnoreCase("ACME")).thenReturn(Optional.of(company));
    when(repository.findByCodeIgnoreCase("UNKNOWN")).thenReturn(Optional.empty());
    when(userAccountRepository.countByCompany_IdAndEnabledTrue(21L)).thenReturn(1L);
    when(systemSettingsRepository.findById(any())).thenReturn(Optional.empty());

    TenantRuntimeEnforcementService service =
        new TenantRuntimeEnforcementService(
            repository,
            systemSettingsRepository,
            userAccountRepository,
            auditService,
            100,
            100,
            100,
            15);

    service.holdTenant("ACME", "manual-hold", "ops");

    // Hold rejects mutating requests without privileged control path.
    TenantRuntimeEnforcementService.TenantRequestAdmission rejectedMutation =
        service.beginRequest("ACME", "/api/v1/private", "POST", "actor");
    assertThat(rejectedMutation.isAdmitted()).isFalse();
    assertThat(rejectedMutation.statusCode()).isEqualTo(423);

    // Hold allows reads.
    TenantRuntimeEnforcementService.TenantRequestAdmission readAllowed =
        service.beginRequest("ACME", "/api/v1/private", "GET", "actor");
    assertThat(readAllowed.isAdmitted()).isTrue();
    service.completeRequest(readAllowed, 200);
    TenantRuntimeEnforcementService.TenantRequestAdmission nullMethodMutating =
        service.beginRequest("ACME", "/api/v1/private", null, "actor");
    assertThat(nullMethodMutating.isAdmitted()).isFalse();
    TenantRuntimeEnforcementService.TenantRequestAdmission headAllowed =
        service.beginRequest("ACME", "/api/v1/private", "HEAD", "actor");
    assertThat(headAllowed.isAdmitted()).isTrue();
    service.completeRequest(headAllowed, 200);
    TenantRuntimeEnforcementService.TenantRequestAdmission optionsAllowed =
        service.beginRequest("ACME", "/api/v1/private", "OPTIONS", "actor");
    assertThat(optionsAllowed.isAdmitted()).isTrue();
    service.completeRequest(optionsAllowed, 200);
    TenantRuntimeEnforcementService.TenantRequestAdmission traceAllowed =
        service.beginRequest("ACME", "/api/v1/private", "TRACE", "actor");
    assertThat(traceAllowed.isAdmitted()).isTrue();
    service.completeRequest(traceAllowed, 200);

    TenantRuntimeEnforcementService.TenantRequestAdmission retiredAdminPolicyControl =
        service.beginRequest("ACME", "/api/v1/admin/tenant-runtime/policy", "PUT", "super", true);
    assertThat(retiredAdminPolicyControl.isAdmitted()).isFalse();
    // Privileged canonical superadmin limits path bypasses hold/rate checks.
    TenantRuntimeEnforcementService.TenantRequestAdmission policyControl =
        service.beginRequest("ACME", "/api/v1/superadmin/tenants/21/limits", "PUT", "super", true);
    assertThat(policyControl.isAdmitted()).isTrue();
    service.completeRequest(policyControl, 500);
    TenantRuntimeEnforcementService.TenantRequestAdmission nonPutPolicyControl =
        service.beginRequest(
            "ACME", "/api/v1/superadmin/tenants/21/limits", "PATCH", "super", true);
    assertThat(nonPutPolicyControl.isAdmitted()).isFalse();
    TenantRuntimeEnforcementService.TenantRequestAdmission nullPathPolicyControl =
        service.beginRequest("ACME", null, "PUT", "super", true);
    assertThat(nullPathPolicyControl.isAdmitted()).isFalse();
    TenantRuntimeEnforcementService.TenantRequestAdmission blankMethodPolicyControl =
        service.beginRequest("ACME", "/api/v1/superadmin/tenants/21/limits", "   ", "super", true);
    assertThat(blankMethodPolicyControl.isAdmitted()).isFalse();
    TenantRuntimeEnforcementService.TenantRequestAdmission wrongSuffixPolicyControl =
        service.beginRequest(
            "ACME", "/api/v1/superadmin/tenants/21/not-limits", "PUT", "super", true);
    assertThat(wrongSuffixPolicyControl.isAdmitted()).isFalse();
    TenantRuntimeEnforcementService.TenantRequestAdmission emptyIdPolicyControl =
        service.beginRequest("ACME", "/api/v1/superadmin/tenants//limits", "PUT", "super", true);
    assertThat(emptyIdPolicyControl.isAdmitted()).isFalse();
    TenantRuntimeEnforcementService.TenantRequestAdmission rootPathPolicyControl =
        service.beginRequest("ACME", "/", "PUT", "super", true);
    assertThat(rootPathPolicyControl.isAdmitted()).isFalse();

    // Canonical superadmin limits path with trailing slash also passes.
    TenantRuntimeEnforcementService.TenantRequestAdmission canonicalPolicyControl =
        service.beginRequest("ACME", "/api/v1/superadmin/tenants/21/limits/", "PUT", "super", true);
    assertThat(canonicalPolicyControl.isAdmitted()).isTrue();
    service.completeRequest(canonicalPolicyControl, 500);

    // Invalid canonical path falls back to normal hold rejection.
    TenantRuntimeEnforcementService.TenantRequestAdmission invalidCanonical =
        service.beginRequest(
            "ACME", "/api/v1/superadmin/tenants/21/x/limits", "PUT", "super", true);
    assertThat(invalidCanonical.isAdmitted()).isFalse();

    TenantRuntimeEnforcementService.TenantRuntimeSnapshot updated =
        service.updatePolicy(
            "ACME",
            TenantRuntimeEnforcementService.TenantRuntimeState.ACTIVE,
            "recovered",
            3,
            5,
            7,
            "super");
    assertThat(updated.state())
        .isEqualTo(TenantRuntimeEnforcementService.TenantRuntimeState.ACTIVE);
    assertThat(updated.maxConcurrentRequests()).isEqualTo(3);
    assertThat(updated.maxRequestsPerMinute()).isEqualTo(5);
    assertThat(updated.maxActiveUsers()).isEqualTo(7);
    assertThat(service.updatePolicy("ACME", null, "rpm-only", null, 33, null, "super")).isNotNull();
    assertThat(service.updatePolicy("ACME", null, "users-only", null, null, 44, "super"))
        .isNotNull();

    assertThatThrownBy(() -> service.updatePolicy("ACME", null, "noop", null, null, null, "super"))
        .isInstanceOf(ApplicationException.class);
    assertThatThrownBy(() -> service.updatePolicy("UNKNOWN", null, "x", 1, null, null, "super"))
        .isInstanceOf(ApplicationException.class);

    assertThatCode(() -> service.enforceAuthOperationAllowed("ACME", "actor", "login"))
        .doesNotThrowAnyException();
  }

  @Test
  void emailService_credentialDelivery_requiredPath_is_executable() {
    JavaMailSender mailSender = mock(JavaMailSender.class);
    SpringTemplateEngine templateEngine = mock(SpringTemplateEngine.class);

    EmailProperties enabledProps = new EmailProperties();
    enabledProps.setEnabled(true);
    enabledProps.setSendCredentials(true);
    enabledProps.setFromAddress("noreply@bbp.com");
    enabledProps.setBaseUrl("https://erp.example.com");
    EmailService enabledEmailService = new EmailService(mailSender, enabledProps, templateEngine);

    assertThat(enabledEmailService.isCredentialEmailDeliveryEnabled()).isTrue();

    doThrow(new MailSendException("smtp-failed"))
        .when(mailSender)
        .send(any(MimeMessagePreparator.class));
    assertThatThrownBy(
            () ->
                enabledEmailService.sendUserCredentialsEmailRequired(
                    "admin@ske.com", "Admin", "Temp@12345", "SKE"))
        .isInstanceOf(ApplicationException.class)
        .satisfies(
            ex ->
                assertThat(((ApplicationException) ex).getErrorCode())
                    .isEqualTo(ErrorCode.SYSTEM_EXTERNAL_SERVICE_ERROR));

    EmailProperties disabledProps = new EmailProperties();
    disabledProps.setEnabled(true);
    disabledProps.setSendCredentials(false);
    disabledProps.setFromAddress("noreply@bbp.com");
    disabledProps.setBaseUrl("https://erp.example.com");
    EmailService disabledEmailService = new EmailService(mailSender, disabledProps, templateEngine);

    assertThat(disabledEmailService.isCredentialEmailDeliveryEnabled()).isFalse();
    assertThatThrownBy(
            () ->
                disabledEmailService.sendUserCredentialsEmailRequired(
                    "admin@ske.com", "Admin", "Temp@12345", "SKE"))
        .isInstanceOf(ApplicationException.class)
        .satisfies(
            ex ->
                assertThat(((ApplicationException) ex).getErrorCode())
                    .isEqualTo(ErrorCode.SYSTEM_CONFIGURATION_ERROR));
  }

  @Test
  void tenantAdminProvisioningService_provisionInitialAdmin_covers_guards_and_fallback_display() {
    UserAccountRepository userAccountRepository = mock(UserAccountRepository.class);
    RoleService roleService = mock(RoleService.class);
    RoleRepository roleRepository = mock(RoleRepository.class);
    EmailService emailService = mock(EmailService.class);
    ScopedAccountBootstrapService scopedAccountBootstrapService =
        mock(ScopedAccountBootstrapService.class);
    PasswordResetService passwordResetService = mock(PasswordResetService.class);
    TenantAdminProvisioningService service =
        new TenantAdminProvisioningService(
            userAccountRepository,
            roleService,
            roleRepository,
            scopedAccountBootstrapService,
            passwordResetService);
    Company company = company(10L, "SKE");
    Role adminRole = new Role();
    adminRole.setName("ROLE_ADMIN");
    when(userAccountRepository.existsByEmailIgnoreCaseAndAuthScopeCodeIgnoreCase(
            "new-admin@ske.com", "SKE"))
        .thenReturn(false);
    when(roleRepository.findByName("ROLE_ADMIN")).thenReturn(Optional.of(adminRole));
    UserAccount provisionedAdmin =
        new UserAccount("new-admin@ske.com", "SKE", "hash", "Company SKE Admin");
    ReflectionTestUtils.setField(provisionedAdmin, "id", 410L);
    when(scopedAccountBootstrapService.provisionTenantAccount(
            eq(company),
            eq("new-admin@ske.com"),
            eq("Company SKE Admin"),
            eq(java.util.List.of(adminRole))))
        .thenReturn(provisionedAdmin);

    UserAccount normalizedAdmin =
        service.provisionInitialAdmin(company, " NEW-ADMIN@SKE.COM ", null);

    assertThat(normalizedAdmin.getEmail()).isEqualTo("new-admin@ske.com");
    assertThat(company.getMainAdminUserId()).isEqualTo(410L);
    assertThat(company.getOnboardingAdminEmail()).isEqualTo("new-admin@ske.com");
    assertThat(company.getOnboardingAdminUserId()).isEqualTo(410L);
    verify(scopedAccountBootstrapService)
        .provisionTenantAccount(
            company, "new-admin@ske.com", "Company SKE Admin", java.util.List.of(adminRole));

    when(userAccountRepository.existsByEmailIgnoreCaseAndAuthScopeCodeIgnoreCase(
            "duplicate@ske.com", "SKE"))
        .thenReturn(true);
    assertThatThrownBy(() -> service.provisionInitialAdmin(company, "duplicate@ske.com", "Dup"))
        .isInstanceOf(ApplicationException.class)
        .hasMessageContaining("already exists");

    assertThatThrownBy(() -> service.provisionInitialAdmin(null, "new@ske.com", "x"))
        .isInstanceOf(ApplicationException.class);

    Company transientCompany = new Company();
    transientCompany.setCode("TMP");
    transientCompany.setName("Transient");
    assertThatThrownBy(() -> service.provisionInitialAdmin(transientCompany, "new@tmp.com", "x"))
        .isInstanceOf(ApplicationException.class);
  }

  @Test
  void
      tenantAdminProvisioningService_resetTenantAdminPassword_covers_authority_and_recovery_paths() {
    UserAccountRepository userAccountRepository = mock(UserAccountRepository.class);
    RoleService roleService = mock(RoleService.class);
    RoleRepository roleRepository = mock(RoleRepository.class);
    EmailService emailService = mock(EmailService.class);
    ScopedAccountBootstrapService scopedAccountBootstrapService =
        mock(ScopedAccountBootstrapService.class);
    PasswordResetService passwordResetService = mock(PasswordResetService.class);
    TenantAdminProvisioningService service =
        new TenantAdminProvisioningService(
            userAccountRepository,
            roleService,
            roleRepository,
            scopedAccountBootstrapService,
            passwordResetService);
    Company target = company(55L, "SKE");
    Company other = company(56L, "OTH");

    UserAccount outsider = new UserAccount("outsider@ske.com", "hash", "Out");
    outsider.setCompany(other);
    Role outsiderRole = new Role();
    outsiderRole.setName("ROLE_ADMIN");
    outsider.addRole(outsiderRole);
    when(userAccountRepository.findByEmailIgnoreCaseAndAuthScopeCodeIgnoreCase(
            "outsider@ske.com", "SKE"))
        .thenReturn(Optional.of(outsider));
    assertThatThrownBy(() -> service.resetTenantAdminPassword(target, "outsider@ske.com"))
        .isInstanceOf(ApplicationException.class)
        .hasMessageContaining("not assigned to company");

    UserAccount nonAdmin = new UserAccount("user@ske.com", "hash", "User");
    nonAdmin.setCompany(target);
    Role userRole = new Role();
    userRole.setName("ROLE_USER");
    nonAdmin.addRole(userRole);
    when(userAccountRepository.findByEmailIgnoreCaseAndAuthScopeCodeIgnoreCase(
            "user@ske.com", "SKE"))
        .thenReturn(Optional.of(nonAdmin));
    assertThatThrownBy(() -> service.resetTenantAdminPassword(target, "user@ske.com"))
        .isInstanceOf(ApplicationException.class)
        .hasMessageContaining("not an admin");

    assertThatThrownBy(() -> service.resetTenantAdminPassword(target, "missing@ske.com"))
        .isInstanceOf(ApplicationException.class)
        .hasMessageContaining("not found");

    UserAccount admin = new UserAccount("admin@ske.com", "SKE", "hash", "Admin");
    admin.setCompany(target);
    Role adminRole = new Role();
    adminRole.setName("ROLE_ADMIN");
    admin.addRole(adminRole);
    when(userAccountRepository.findByEmailIgnoreCaseAndAuthScopeCodeIgnoreCase(
            "admin@ske.com", "SKE"))
        .thenReturn(Optional.of(admin));

    String resetEmail = service.resetTenantAdminPassword(target, " ADMIN@SKE.COM ");

    assertThat(resetEmail).isEqualTo("admin@ske.com");
    verify(passwordResetService).requestResetByAdmin(admin);

    assertThatThrownBy(() -> service.resetTenantAdminPassword(null, "admin@ske.com"))
        .isInstanceOf(ApplicationException.class);
  }

  @Test
  void tenantAdminProvisioningService_reportsCredentialProvisioningReadiness() {
    UserAccountRepository userAccountRepository = mock(UserAccountRepository.class);
    RoleService roleService = mock(RoleService.class);
    RoleRepository roleRepository = mock(RoleRepository.class);
    ScopedAccountBootstrapService scopedAccountBootstrapService =
        mock(ScopedAccountBootstrapService.class);
    TenantAdminProvisioningService service =
        new TenantAdminProvisioningService(
            userAccountRepository,
            roleService,
            roleRepository,
            scopedAccountBootstrapService,
            mock(PasswordResetService.class));
    when(scopedAccountBootstrapService.isCredentialProvisioningReady()).thenReturn(false, true);

    assertThat(service.isCredentialProvisioningReady()).isFalse();
    assertThat(service.isCredentialProvisioningReady()).isTrue();
    verify(scopedAccountBootstrapService, never())
        .provisionTenantAccount(any(), any(), any(), any());
  }

  @Test
  void delegatedTenantBootstrapSupportSuites_pass_in_truth_lane() {
    assertDelegatedSuitePasses("com.bigbrightpaints.erp.core.config.DataInitializerTest");
    assertDelegatedSuitePasses("com.bigbrightpaints.erp.core.notification.EmailServiceTest");
    assertDelegatedSuitePasses(
        "com.bigbrightpaints.erp.modules.auth.service.TenantAdminProvisioningServiceTest");
    assertDelegatedSuitePasses(
        "com.bigbrightpaints.erp.modules.company.service.CompanyServiceTest");
  }

  private void assertDelegatedSuitePasses(String className) {
    LauncherDiscoveryRequest request =
        LauncherDiscoveryRequestBuilder.request()
            .selectors(DiscoverySelectors.selectClass(className))
            .build();
    SummaryGeneratingListener summaryListener = new SummaryGeneratingListener();
    Launcher launcher = LauncherFactory.create();
    launcher.registerTestExecutionListeners(summaryListener);
    launcher.execute(request);
    TestExecutionSummary summary = summaryListener.getSummary();
    assertThat(summary.getTestsFoundCount()).isGreaterThan(0L);
    assertThat(summary.getTestsFailedCount()).isZero();
  }

  private void authenticate(String username, String... authorities) {
    var granted =
        java.util.Arrays.stream(authorities)
            .map(authority -> (org.springframework.security.core.GrantedAuthority) () -> authority)
            .toList();
    SecurityContextHolder.getContext()
        .setAuthentication(new UsernamePasswordAuthenticationToken(username, "n/a", granted));
  }

  private Company company(Long id, String code) {
    Company company = new Company();
    ReflectionTestUtils.setField(company, "id", id);
    ReflectionTestUtils.setField(company, "publicId", UUID.randomUUID());
    company.setCode(code);
    company.setName("Company " + code);
    company.setTimezone("UTC");
    company.setDefaultGstRate(BigDecimal.TEN);
    return company;
  }

  private TenantRuntimeEnforcementService.TenantRuntimeSnapshot snapshot(String companyCode) {
    return new TenantRuntimeEnforcementService.TenantRuntimeSnapshot(
        companyCode,
        TenantRuntimeEnforcementService.TenantRuntimeState.ACTIVE,
        "POLICY_ACTIVE",
        "chain",
        Instant.parse("2026-01-01T00:00:00Z"),
        10,
        100,
        50,
        new TenantRuntimeEnforcementService.TenantRuntimeMetrics(0, 0, 0, 0, 0, 0, 0));
  }
}
