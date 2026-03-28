package com.bigbrightpaints.erp.modules.company.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.time.LocalDateTime;
import java.util.Optional;
import java.util.Set;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.util.ReflectionTestUtils;

import com.bigbrightpaints.erp.core.audit.AuditEvent;
import com.bigbrightpaints.erp.core.audit.AuditLog;
import com.bigbrightpaints.erp.core.audit.AuditLogRepository;
import com.bigbrightpaints.erp.core.audit.AuditService;
import com.bigbrightpaints.erp.core.notification.EmailService;
import com.bigbrightpaints.erp.core.security.TokenBlacklistService;
import com.bigbrightpaints.erp.modules.auth.domain.UserAccount;
import com.bigbrightpaints.erp.modules.auth.domain.UserAccountRepository;
import com.bigbrightpaints.erp.modules.auth.service.RefreshTokenService;
import com.bigbrightpaints.erp.modules.company.domain.Company;
import com.bigbrightpaints.erp.modules.company.domain.CompanyLifecycleState;
import com.bigbrightpaints.erp.modules.company.domain.CompanyRepository;
import com.bigbrightpaints.erp.modules.company.domain.TenantAdminEmailChangeRequest;
import com.bigbrightpaints.erp.modules.company.domain.TenantAdminEmailChangeRequestRepository;
import com.bigbrightpaints.erp.modules.company.domain.TenantSupportWarning;
import com.bigbrightpaints.erp.modules.company.domain.TenantSupportWarningRepository;
import com.bigbrightpaints.erp.modules.company.dto.CompanyTenantMetricsDto;
import com.bigbrightpaints.erp.modules.company.dto.MainAdminSummaryDto;
import com.bigbrightpaints.erp.modules.company.dto.SuperAdminTenantAdminEmailChangeConfirmationDto;
import com.bigbrightpaints.erp.modules.company.dto.SuperAdminTenantAdminEmailChangeRequestDto;
import com.bigbrightpaints.erp.modules.company.dto.SuperAdminTenantDetailDto;
import com.bigbrightpaints.erp.modules.company.dto.SuperAdminTenantForceLogoutDto;
import com.bigbrightpaints.erp.modules.company.dto.SuperAdminTenantLimitsDto;
import com.bigbrightpaints.erp.modules.company.dto.SuperAdminTenantSummaryDto;
import com.bigbrightpaints.erp.modules.company.dto.SuperAdminTenantSupportContextDto;
import com.bigbrightpaints.erp.modules.rbac.domain.Role;

import jakarta.persistence.EntityNotFoundException;

@ExtendWith(MockitoExtension.class)
class SuperAdminTenantControlPlaneServiceTest {

  @Mock private CompanyRepository companyRepository;
  @Mock private UserAccountRepository userAccountRepository;
  @Mock private AuditLogRepository auditLogRepository;
  @Mock private AuditService auditService;
  @Mock private EmailService emailService;
  @Mock private TokenBlacklistService tokenBlacklistService;
  @Mock private RefreshTokenService refreshTokenService;
  @Mock private TenantSupportWarningRepository tenantSupportWarningRepository;
  @Mock private TenantAdminEmailChangeRequestRepository tenantAdminEmailChangeRequestRepository;
  @Mock private TenantRuntimeEnforcementService tenantRuntimeEnforcementService;
  @Mock private CompanyService companyService;

  private SuperAdminTenantControlPlaneService service;

  @BeforeEach
  void setUp() {
    service =
        new SuperAdminTenantControlPlaneService(
            companyRepository,
            userAccountRepository,
            auditLogRepository,
            auditService,
            emailService,
            tokenBlacklistService,
            refreshTokenService,
            tenantSupportWarningRepository,
            tenantAdminEmailChangeRequestRepository,
            tenantRuntimeEnforcementService,
            companyService);
    SecurityContextHolder.getContext()
        .setAuthentication(new UsernamePasswordAuthenticationToken("super-admin@bbp.com", "n/a"));
  }

  @AfterEach
  void tearDown() {
    SecurityContextHolder.clearContext();
  }

  @Test
  void updateLimits_preservesZeroQuotasWhenSyncingRuntimePolicy() {
    Company company = new Company();
    ReflectionTestUtils.setField(company, "id", 7L);
    company.setCode("ACME");
    when(companyRepository.findById(7L)).thenReturn(Optional.of(company));
    when(companyRepository.save(company)).thenReturn(company);

    SuperAdminTenantLimitsDto response = service.updateLimits(7L, 0L, 0L, 0L, 0L, true, false);

    assertThat(response.quotaMaxActiveUsers()).isZero();
    assertThat(response.quotaMaxApiRequests()).isZero();
    assertThat(response.quotaMaxStorageBytes()).isZero();
    assertThat(response.quotaMaxConcurrentRequests()).isZero();
    verify(tenantRuntimeEnforcementService)
        .updatePolicy("ACME", null, "ERP37_LIMITS_UPDATE", 0, 0, 0, "super-admin@bbp.com");
  }

  @Test
  void updateLimits_rejectsEmptyPayload() {
    Company company = company(7L, "ACME");
    when(companyRepository.findById(7L)).thenReturn(Optional.of(company));

    assertThatThrownBy(() -> service.updateLimits(7L, null, null, null, null, null, null))
        .hasMessageContaining("Tenant limits payload is required");
  }

  @Test
  void updateLimits_acceptsPartialPayloadAndCapsRuntimeConcurrentRequests() {
    Company company = company(7L, "ACME");
    when(companyRepository.findById(7L)).thenReturn(Optional.of(company));
    when(companyRepository.save(company)).thenReturn(company);

    SuperAdminTenantLimitsDto response =
        service.updateLimits(7L, null, null, null, Long.MAX_VALUE, null, true);

    assertThat(response.quotaMaxConcurrentRequests()).isEqualTo(Long.MAX_VALUE);
    assertThat(response.quotaHardLimitEnabled()).isTrue();
    assertThat(response.quotaSoftLimitEnabled()).isFalse();
    verify(tenantRuntimeEnforcementService)
        .updatePolicy(
            "ACME", null, "ERP37_LIMITS_UPDATE", Integer.MAX_VALUE, 0, 0, "super-admin@bbp.com");
  }

  @Test
  void listTenants_filtersByLifecycleAndSortsCodesCaseInsensitively() {
    Company alpha = company(1L, "alpha");
    alpha.setName("Alpha");
    alpha.setTimezone("UTC");
    alpha.setLifecycleState(CompanyLifecycleState.ACTIVE);
    Company beta = company(2L, "Beta");
    beta.setName("Beta");
    beta.setTimezone("UTC");
    beta.setLifecycleState(CompanyLifecycleState.SUSPENDED);
    when(companyRepository.findAll()).thenReturn(java.util.List.of(beta, alpha));
    when(companyService.getTenantMetricsForSuperAdmin(1L)).thenReturn(metrics(alpha, "ACTIVE"));
    when(companyService.getTenantMetricsForSuperAdmin(2L)).thenReturn(metrics(beta, "SUSPENDED"));
    when(auditLogRepository.findTop1ByCompanyIdOrderByTimestampDesc(any(Long.class)))
        .thenReturn(Optional.empty());

    java.util.List<SuperAdminTenantSummaryDto> filtered = service.listTenants("active");
    java.util.List<SuperAdminTenantSummaryDto> all = service.listTenants(null);

    assertThat(filtered)
        .extracting(SuperAdminTenantSummaryDto::companyCode)
        .containsExactly("alpha");
    assertThat(all)
        .extracting(SuperAdminTenantSummaryDto::companyCode)
        .containsExactly("alpha", "Beta");
  }

  @Test
  void listTenants_rejectsUnknownLifecycleFilter() {
    assertThatThrownBy(() -> service.listTenants("legacy"))
        .hasMessageContaining("status filter must be ACTIVE, SUSPENDED, or DEACTIVATED");
  }

  @Test
  void getTenantDetail_aggregatesMetricsTimelineAndMainAdmin() {
    Company company = company(7L, "ACME");
    company.setName("Acme Paints");
    company.setTimezone("Asia/Kolkata");
    company.setStateCode("KA");
    company.setLifecycleState(CompanyLifecycleState.SUSPENDED);
    company.setLifecycleReason("ops-review");
    company.setEnabledModules(Set.of("ACCOUNTING", "SALES"));
    company.setSupportNotes("  needs follow-up  ");
    company.setSupportTags(Set.of(" urgent ", "finance"));
    company.setMainAdminUserId(91L);
    company.setOnboardingCoaTemplateCode("  sme ");
    company.setOnboardingAdminEmail("  Admin@Example.com ");
    company.setOnboardingAdminUserId(91L);
    Instant emailedAt = Instant.parse("2026-03-25T11:00:00Z");
    Instant completedAt = Instant.parse("2026-03-25T12:00:00Z");
    company.setOnboardingCredentialsEmailedAt(emailedAt);
    company.setOnboardingCompletedAt(completedAt);
    when(companyRepository.findById(7L)).thenReturn(Optional.of(company));
    when(companyService.getTenantMetricsForSuperAdmin(7L))
        .thenReturn(
            new CompanyTenantMetricsDto(
                7L,
                "ACME",
                "SUSPENDED",
                "ops-review",
                120,
                3000,
                4096,
                8,
                true,
                false,
                12,
                400,
                9,
                225,
                3,
                2048));
    UserAccount mainAdmin = adminUser(91L, "main-admin@acme.com", "ROLE_ADMIN", company);
    mainAdmin.setDisplayName("Main Admin");
    when(userAccountRepository.findById(91L)).thenReturn(Optional.of(mainAdmin));

    TenantSupportWarning warning = new TenantSupportWarning();
    ReflectionTestUtils.setField(warning, "id", 501L);
    warning.setCompany(company);
    warning.setWarningCategory("FINANCE");
    warning.setMessage("Pending payment");
    warning.setRequestedLifecycleState("SUSPENDED");
    warning.setGracePeriodHours(48);
    warning.setIssuedBy("support@bbp.com");
    warning.setIssuedAt(Instant.parse("2026-03-26T09:00:00Z"));
    when(tenantSupportWarningRepository.findByCompany_IdOrderByIssuedAtDesc(7L))
        .thenReturn(java.util.List.of(warning));

    AuditLog newerAudit =
        new AuditLog.Builder()
            .eventType(AuditEvent.CONFIGURATION_CHANGED)
            .companyId(7L)
            .username("super-admin@bbp.com")
            .timestamp(LocalDateTime.of(2026, 3, 26, 12, 0))
            .metadata(java.util.Map.of("reason", "tenant-force-logout"))
            .build();
    AuditLog olderAudit =
        new AuditLog.Builder()
            .eventType(AuditEvent.LOGIN_SUCCESS)
            .companyId(7L)
            .username("system")
            .timestamp(LocalDateTime.of(2026, 3, 25, 12, 0))
            .errorMessage("fallback-error")
            .build();
    when(auditLogRepository.findTop50ByCompanyIdOrderByTimestampDesc(7L))
        .thenReturn(java.util.List.of(olderAudit, newerAudit));
    when(auditLogRepository.findTop1ByCompanyIdOrderByTimestampDesc(7L))
        .thenReturn(Optional.of(newerAudit));

    SuperAdminTenantDetailDto detail = service.getTenantDetail(7L);

    assertThat(detail.companyCode()).isEqualTo("ACME");
    assertThat(detail.lifecycleState()).isEqualTo("SUSPENDED");
    assertThat(detail.mainAdmin().email()).isEqualTo("main-admin@acme.com");
    assertThat(detail.onboarding().templateCode()).isEqualTo("SME");
    assertThat(detail.onboarding().adminEmail()).isEqualTo("admin@example.com");
    assertThat(detail.usage().lastActivityAt()).isEqualTo(Instant.parse("2026-03-26T12:00:00Z"));
    assertThat(detail.supportContext().supportNotes()).isEqualTo("needs follow-up");
    assertThat(detail.supportContext().supportTags())
        .containsExactlyInAnyOrder("URGENT", "FINANCE");
    assertThat(detail.supportTimeline()).hasSize(3);
    assertThat(detail.supportTimeline().get(0).category()).isEqualTo("AUDIT");
    assertThat(detail.supportTimeline().get(0).message()).isEqualTo("tenant-force-logout");
    assertThat(detail.supportTimeline().get(1).category()).isEqualTo("WARNING");
  }

  @Test
  void issueSupportWarning_appliesDefaultsAndAudits() {
    Company company = company(7L, "ACME");
    when(companyRepository.findById(7L)).thenReturn(Optional.of(company));
    when(tenantSupportWarningRepository.save(any(TenantSupportWarning.class)))
        .thenAnswer(
            invocation -> {
              TenantSupportWarning saved = invocation.getArgument(0);
              ReflectionTestUtils.setField(saved, "id", 55L);
              return saved;
            });

    var response = service.issueSupportWarning(7L, "  ", "  Please respond  ", null, null);

    assertThat(response.warningId()).isEqualTo("55");
    assertThat(response.warningCategory()).isEqualTo("GENERAL");
    assertThat(response.requestedLifecycleState()).isEqualTo("SUSPENDED");
    assertThat(response.gracePeriodHours()).isEqualTo(24);
    assertThat(response.message()).isEqualTo("Please respond");
    assertThat(response.issuedAt()).isNotNull();
    verify(auditService)
        .logAuthSuccess(
            eq(AuditEvent.CONFIGURATION_CHANGED), eq("super-admin@bbp.com"), eq("ACME"), any());
  }

  @Test
  void issueSupportWarning_rejectsBlankMessageAndUnsupportedLifecycle() {
    Company company = company(7L, "ACME");
    when(companyRepository.findById(7L)).thenReturn(Optional.of(company));

    assertThatThrownBy(() -> service.issueSupportWarning(7L, "OPS", "   ", null, null))
        .hasMessageContaining("Support warning message is required");
    assertThatThrownBy(() -> service.issueSupportWarning(7L, "OPS", "hello", "ACTIVE", 1))
        .hasMessageContaining("requestedLifecycleState must be SUSPENDED or DEACTIVATED");
  }

  @Test
  void issueSupportWarning_rejectsOutOfRangeGracePeriod() {
    Company company = company(7L, "ACME");
    when(companyRepository.findById(7L)).thenReturn(Optional.of(company));

    assertThatThrownBy(() -> service.issueSupportWarning(7L, "OPS", "hello", "DEACTIVATED", 0))
        .hasMessageContaining("gracePeriodHours must be between 1 and 720");
    assertThatThrownBy(() -> service.issueSupportWarning(7L, "OPS", "hello", "DEACTIVATED", 721))
        .hasMessageContaining("gracePeriodHours must be between 1 and 720");
  }

  @Test
  void updateSupportContext_normalizesNotesAndTags() {
    Company company = company(7L, "ACME");
    when(companyRepository.findById(7L)).thenReturn(Optional.of(company));

    SuperAdminTenantSupportContextDto response =
        service.updateSupportContext(7L, "  investigate first  ", Set.of(" urgent ", "billing"));

    assertThat(response.supportNotes()).isEqualTo("investigate first");
    assertThat(response.supportTags()).containsExactlyInAnyOrder("URGENT", "BILLING");
  }

  @Test
  void updateSupportContext_preservesNotesWhenNotesFieldIsOmitted() {
    Company company = company(7L, "ACME");
    company.setSupportNotes("keep existing notes");
    when(companyRepository.findById(7L)).thenReturn(Optional.of(company));

    SuperAdminTenantSupportContextDto response =
        service.updateSupportContext(7L, null, Set.of(" urgent "));

    assertThat(response.supportNotes()).isEqualTo("keep existing notes");
    assertThat(response.supportTags()).containsExactly("URGENT");
  }

  @Test
  void forceLogoutAllUsers_revokesNonBlankEmailsAndDefaultsReason() {
    Company company = company(7L, "ACME");
    when(companyRepository.findById(7L)).thenReturn(Optional.of(company));
    UserAccount first = adminUser(11L, "admin1@acme.com", "ROLE_ADMIN", company);
    UserAccount second = adminUser(12L, "  ", "ROLE_ADMIN", company);
    when(userAccountRepository.findByCompany_Id(7L)).thenReturn(java.util.List.of(first, second));

    SuperAdminTenantForceLogoutDto response = service.forceLogoutAllUsers(7L, "   ");

    assertThat(response.revokedUserCount()).isEqualTo(2);
    assertThat(response.reason()).isEqualTo("support-request");
    verify(tokenBlacklistService).revokeAllUserTokens(first.getPublicId().toString());
    verify(refreshTokenService).revokeAllForUser(first.getPublicId());
    verify(tokenBlacklistService).revokeAllUserTokens(second.getPublicId().toString());
    verify(refreshTokenService).revokeAllForUser(second.getPublicId());
  }

  @Test
  void forceLogoutAllUsers_skipsNullUsersAndUsersWithoutPublicId() {
    Company company = company(7L, "ACME");
    when(companyRepository.findById(7L)).thenReturn(Optional.of(company));
    UserAccount revocable = adminUser(11L, "admin1@acme.com", "ROLE_ADMIN", company);
    UserAccount missingPublicId = new UserAccount("admin2@acme.com", "ACME", "hash", "Admin Two");
    missingPublicId.setCompany(company);
    when(userAccountRepository.findByCompany_Id(7L))
        .thenReturn(java.util.Arrays.asList(null, missingPublicId, revocable));

    SuperAdminTenantForceLogoutDto response = service.forceLogoutAllUsers(7L, "incident");

    assertThat(response.revokedUserCount()).isEqualTo(3);
    verify(tokenBlacklistService).revokeAllUserTokens(revocable.getPublicId().toString());
    verify(refreshTokenService).revokeAllForUser(revocable.getPublicId());
    verify(tokenBlacklistService, never()).revokeAllUserTokens("null");
    verify(refreshTokenService, never()).revokeAllForUser((java.util.UUID) null);
  }

  @Test
  void replaceMainAdmin_requiresAssignedAdminRoleAndPersistsSelection() {
    Company company = company(7L, "ACME");
    when(companyRepository.findById(7L)).thenReturn(Optional.of(company));
    UserAccount admin = adminUser(91L, "main-admin@acme.com", "ROLE_ADMIN", company);
    admin.setDisplayName("Main Admin");
    when(userAccountRepository.findByIdAndCompany_Id(91L, 7L)).thenReturn(Optional.of(admin));

    MainAdminSummaryDto response = service.replaceMainAdmin(7L, 91L);

    assertThat(company.getMainAdminUserId()).isEqualTo(91L);
    assertThat(response.email()).isEqualTo("main-admin@acme.com");
    assertThat(response.replaceable()).isTrue();
  }

  @Test
  void replaceMainAdmin_rejectsNonAdminAssignments() {
    Company company = company(7L, "ACME");
    when(companyRepository.findById(7L)).thenReturn(Optional.of(company));
    UserAccount user = adminUser(91L, "user@acme.com", "ROLE_USER", company);
    when(userAccountRepository.findByIdAndCompany_Id(91L, 7L)).thenReturn(Optional.of(user));

    assertThatThrownBy(() -> service.replaceMainAdmin(7L, 91L))
        .hasMessageContaining("Target user is not an admin for company: ACME");
  }

  @Test
  void requestAdminEmailChange_persistsNormalizedRequestAndSendsVerification() {
    Company company = company(7L, "ACME");
    when(companyRepository.findById(7L)).thenReturn(Optional.of(company));
    UserAccount admin = adminUser(91L, "admin@acme.com", "ROLE_ADMIN", company);
    when(userAccountRepository.findByIdAndCompany_Id(91L, 7L)).thenReturn(Optional.of(admin));
    when(userAccountRepository.findByEmailIgnoreCaseAndAuthScopeCodeIgnoreCase(
            "new-admin@acme.com", "ACME"))
        .thenReturn(Optional.empty());
    when(tenantAdminEmailChangeRequestRepository.save(any(TenantAdminEmailChangeRequest.class)))
        .thenAnswer(
            invocation -> {
              TenantAdminEmailChangeRequest saved = invocation.getArgument(0);
              ReflectionTestUtils.setField(saved, "id", 301L);
              return saved;
            });

    SuperAdminTenantAdminEmailChangeRequestDto response =
        service.requestAdminEmailChange(7L, 91L, "  New-Admin@Acme.com ");

    assertThat(response.requestId()).isEqualTo(301L);
    assertThat(response.currentEmail()).isEqualTo("admin@acme.com");
    assertThat(response.requestedEmail()).isEqualTo("new-admin@acme.com");
    verify(emailService)
        .sendAdminEmailChangeVerificationRequired(
            eq("new-admin@acme.com"),
            eq("Admin admin@acme.com"),
            eq("ACME"),
            any(String.class),
            eq(response.expiresAt()));
  }

  @Test
  void requestAdminEmailChange_rejectsSameOrExistingEmail() {
    Company company = company(7L, "ACME");
    when(companyRepository.findById(7L)).thenReturn(Optional.of(company));
    UserAccount admin = adminUser(91L, "admin@acme.com", "ROLE_ADMIN", company);
    when(userAccountRepository.findByIdAndCompany_Id(91L, 7L)).thenReturn(Optional.of(admin));

    assertThatThrownBy(() -> service.requestAdminEmailChange(7L, 91L, "admin@acme.com"))
        .hasMessageContaining("newEmail must differ from the current admin email");

    when(userAccountRepository.findByEmailIgnoreCaseAndAuthScopeCodeIgnoreCase(
            "taken@acme.com", "ACME"))
        .thenReturn(Optional.of(new UserAccount()));
    assertThatThrownBy(() -> service.requestAdminEmailChange(7L, 91L, "taken@acme.com"))
        .hasMessageContaining("Email already exists: taken@acme.com");
  }

  @Test
  void requestAdminEmailChange_rejectsBlankEmail() {
    Company company = company(7L, "ACME");
    when(companyRepository.findById(7L)).thenReturn(Optional.of(company));
    UserAccount admin = adminUser(91L, "admin@acme.com", "ROLE_ADMIN", company);
    when(userAccountRepository.findByIdAndCompany_Id(91L, 7L)).thenReturn(Optional.of(admin));

    assertThatThrownBy(() -> service.requestAdminEmailChange(7L, 91L, "   "))
        .hasMessageContaining("newEmail is required");
  }

  @Test
  void confirmAdminEmailChange_updatesAdminRevokesTokensAndMarksRequestConsumed() {
    Company company = company(7L, "ACME");
    when(companyRepository.findById(7L)).thenReturn(Optional.of(company));
    UserAccount admin = adminUser(91L, "admin@acme.com", "ROLE_ADMIN", company);
    when(userAccountRepository.findByIdAndCompany_Id(91L, 7L)).thenReturn(Optional.of(admin));
    TenantAdminEmailChangeRequest request = new TenantAdminEmailChangeRequest();
    ReflectionTestUtils.setField(request, "id", 301L);
    request.setCompanyId(7L);
    request.setAdminUserId(91L);
    request.setCurrentEmail("admin@acme.com");
    request.setRequestedEmail("new-admin@acme.com");
    request.setVerificationToken("verify-123");
    request.setVerificationSentAt(Instant.parse("2026-03-25T10:00:00Z"));
    request.setExpiresAt(Instant.now().plusSeconds(600));
    when(tenantAdminEmailChangeRequestRepository.findById(301L)).thenReturn(Optional.of(request));
    when(userAccountRepository.save(admin)).thenReturn(admin);
    when(tenantAdminEmailChangeRequestRepository.save(request)).thenReturn(request);

    SuperAdminTenantAdminEmailChangeConfirmationDto response =
        service.confirmAdminEmailChange(7L, 91L, 301L, " verify-123 ");

    assertThat(response.updatedEmail()).isEqualTo("new-admin@acme.com");
    assertThat(request.isConsumed()).isTrue();
    assertThat(request.getVerifiedAt()).isNotNull();
    assertThat(request.getConfirmedAt()).isNotNull();
    verify(tokenBlacklistService).revokeAllUserTokens(admin.getPublicId().toString());
    verify(refreshTokenService).revokeAllForUser(admin.getPublicId());
  }

  @Test
  void confirmAdminEmailChange_rejectsMissingMismatchedAndExpiredRequests() {
    Company company = company(7L, "ACME");
    when(companyRepository.findById(7L)).thenReturn(Optional.of(company));
    UserAccount admin = adminUser(91L, "admin@acme.com", "ROLE_ADMIN", company);
    when(userAccountRepository.findByIdAndCompany_Id(91L, 7L)).thenReturn(Optional.of(admin));
    when(tenantAdminEmailChangeRequestRepository.findById(404L)).thenReturn(Optional.empty());

    assertThatThrownBy(() -> service.confirmAdminEmailChange(7L, 91L, 404L, "x"))
        .isInstanceOf(EntityNotFoundException.class);

    TenantAdminEmailChangeRequest mismatched = new TenantAdminEmailChangeRequest();
    mismatched.setCompanyId(8L);
    mismatched.setAdminUserId(91L);
    when(tenantAdminEmailChangeRequestRepository.findById(301L))
        .thenReturn(Optional.of(mismatched));
    assertThatThrownBy(() -> service.confirmAdminEmailChange(7L, 91L, 301L, "x"))
        .isInstanceOf(AccessDeniedException.class);

    TenantAdminEmailChangeRequest expired = new TenantAdminEmailChangeRequest();
    expired.setCompanyId(7L);
    expired.setAdminUserId(91L);
    expired.setCurrentEmail("admin@acme.com");
    expired.setRequestedEmail("new-admin@acme.com");
    expired.setVerificationToken("verify-123");
    expired.setExpiresAt(Instant.parse("2026-03-17T00:00:00Z"));
    when(tenantAdminEmailChangeRequestRepository.findById(302L)).thenReturn(Optional.of(expired));
    assertThatThrownBy(() -> service.confirmAdminEmailChange(7L, 91L, 302L, "verify-123"))
        .hasMessageContaining("Email change verification token has expired");
  }

  @Test
  void confirmAdminEmailChange_rejectsConsumedAndInvalidVerificationTokens() {
    Company company = company(7L, "ACME");
    when(companyRepository.findById(7L)).thenReturn(Optional.of(company));
    UserAccount admin = adminUser(91L, "admin@acme.com", "ROLE_ADMIN", company);
    when(userAccountRepository.findByIdAndCompany_Id(91L, 7L)).thenReturn(Optional.of(admin));

    TenantAdminEmailChangeRequest consumed = new TenantAdminEmailChangeRequest();
    consumed.setCompanyId(7L);
    consumed.setAdminUserId(91L);
    consumed.setConsumed(true);
    when(tenantAdminEmailChangeRequestRepository.findById(303L)).thenReturn(Optional.of(consumed));
    assertThatThrownBy(() -> service.confirmAdminEmailChange(7L, 91L, 303L, "verify-123"))
        .hasMessageContaining("already been consumed");

    TenantAdminEmailChangeRequest invalidToken = new TenantAdminEmailChangeRequest();
    invalidToken.setCompanyId(7L);
    invalidToken.setAdminUserId(91L);
    invalidToken.setVerificationToken("verify-123");
    invalidToken.setExpiresAt(Instant.parse("2026-04-01T00:00:00Z"));
    when(tenantAdminEmailChangeRequestRepository.findById(304L))
        .thenReturn(Optional.of(invalidToken));
    assertThatThrownBy(() -> service.confirmAdminEmailChange(7L, 91L, 304L, "wrong-token"))
        .hasMessageContaining("Invalid verification token");
  }

  @Test
  void confirmAdminEmailChange_rejectsStaleRequests() {
    Company company = company(7L, "ACME");
    when(companyRepository.findById(7L)).thenReturn(Optional.of(company));

    UserAccount exclusiveAdmin = adminUser(92L, "current@acme.com", "ROLE_ADMIN", company);
    when(userAccountRepository.findByIdAndCompany_Id(92L, 7L))
        .thenReturn(Optional.of(exclusiveAdmin));

    TenantAdminEmailChangeRequest staleRequest = new TenantAdminEmailChangeRequest();
    staleRequest.setCompanyId(7L);
    staleRequest.setAdminUserId(92L);
    staleRequest.setCurrentEmail("old@acme.com");
    staleRequest.setRequestedEmail("new@acme.com");
    staleRequest.setVerificationToken("verify-stale");
    staleRequest.setExpiresAt(Instant.now().plusSeconds(600));
    when(tenantAdminEmailChangeRequestRepository.findById(306L))
        .thenReturn(Optional.of(staleRequest));

    assertThatThrownBy(() -> service.confirmAdminEmailChange(7L, 92L, 306L, "verify-stale"))
        .hasMessageContaining("Email change request is stale");
  }

  @Test
  void confirmAdminEmailChange_rejectsRequestedEmailThatBecameOccupied() {
    Company company = company(7L, "ACME");
    when(companyRepository.findById(7L)).thenReturn(Optional.of(company));
    UserAccount admin = adminUser(91L, "admin@acme.com", "ROLE_ADMIN", company);
    when(userAccountRepository.findByIdAndCompany_Id(91L, 7L)).thenReturn(Optional.of(admin));

    TenantAdminEmailChangeRequest request = new TenantAdminEmailChangeRequest();
    request.setCompanyId(7L);
    request.setAdminUserId(91L);
    request.setCurrentEmail("admin@acme.com");
    request.setRequestedEmail("new-admin@acme.com");
    request.setVerificationToken("verify-123");
    request.setExpiresAt(Instant.now().plusSeconds(600));
    when(tenantAdminEmailChangeRequestRepository.findById(307L)).thenReturn(Optional.of(request));

    UserAccount competingUser = adminUser(123L, "new-admin@acme.com", "ROLE_ADMIN", company);
    when(userAccountRepository.findByEmailIgnoreCaseAndAuthScopeCodeIgnoreCase(
            "new-admin@acme.com", "ACME"))
        .thenReturn(Optional.of(competingUser));

    assertThatThrownBy(() -> service.confirmAdminEmailChange(7L, 91L, 307L, "verify-123"))
        .hasMessageContaining("Email already exists: new-admin@acme.com");

    verify(userAccountRepository, never()).save(admin);
    verify(tenantAdminEmailChangeRequestRepository, never())
        .save(any(TenantAdminEmailChangeRequest.class));
  }

  @Test
  void confirmAdminEmailChange_allowsRequestedEmailOwnedBySameAdminRecord() {
    Company company = company(7L, "ACME");
    when(companyRepository.findById(7L)).thenReturn(Optional.of(company));
    UserAccount admin = adminUser(91L, "admin@acme.com", "ROLE_ADMIN", company);
    when(userAccountRepository.findByIdAndCompany_Id(91L, 7L)).thenReturn(Optional.of(admin));

    TenantAdminEmailChangeRequest request = new TenantAdminEmailChangeRequest();
    ReflectionTestUtils.setField(request, "id", 308L);
    request.setCompanyId(7L);
    request.setAdminUserId(91L);
    request.setCurrentEmail("admin@acme.com");
    request.setRequestedEmail("new-admin@acme.com");
    request.setVerificationToken("verify-123");
    request.setExpiresAt(Instant.now().plusSeconds(600));
    when(tenantAdminEmailChangeRequestRepository.findById(308L)).thenReturn(Optional.of(request));

    UserAccount sameAdminRecord = adminUser(91L, "new-admin@acme.com", "ROLE_ADMIN", company);
    when(userAccountRepository.findByEmailIgnoreCaseAndAuthScopeCodeIgnoreCase(
            "new-admin@acme.com", "ACME"))
        .thenReturn(Optional.of(sameAdminRecord));
    when(userAccountRepository.save(admin)).thenReturn(admin);
    when(tenantAdminEmailChangeRequestRepository.save(request)).thenReturn(request);

    SuperAdminTenantAdminEmailChangeConfirmationDto response =
        service.confirmAdminEmailChange(7L, 91L, 308L, "verify-123");

    assertThat(response.updatedEmail()).isEqualTo("new-admin@acme.com");
    verify(userAccountRepository).save(admin);
    verify(tenantAdminEmailChangeRequestRepository).save(request);
  }

  private Company company(Long id, String code) {
    Company company = new Company();
    ReflectionTestUtils.setField(company, "id", id);
    company.setCode(code);
    company.setName(code + " ltd");
    company.setTimezone("UTC");
    company.setStateCode("KA");
    return company;
  }

  private CompanyTenantMetricsDto metrics(Company company, String lifecycleState) {
    return new CompanyTenantMetricsDto(
        company.getId(),
        company.getCode(),
        lifecycleState,
        null,
        10,
        20,
        30,
        2,
        true,
        false,
        5,
        7,
        1,
        25,
        1,
        64);
  }

  private UserAccount adminUser(Long id, String email, String roleName, Company company) {
    UserAccount user = new UserAccount(email, company.getCode(), "hash", "Admin " + email);
    ReflectionTestUtils.setField(user, "id", id);
    Role role = new Role();
    role.setName(roleName);
    user.addRole(role);
    user.setCompany(company);
    return user;
  }
}
