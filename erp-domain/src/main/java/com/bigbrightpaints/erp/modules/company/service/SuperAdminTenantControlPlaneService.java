package com.bigbrightpaints.erp.modules.company.service;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import com.bigbrightpaints.erp.core.audit.AuditEvent;
import com.bigbrightpaints.erp.core.audit.AuditLog;
import com.bigbrightpaints.erp.core.audit.AuditLogRepository;
import com.bigbrightpaints.erp.core.audit.AuditService;
import com.bigbrightpaints.erp.core.notification.EmailService;
import com.bigbrightpaints.erp.core.security.TokenBlacklistService;
import com.bigbrightpaints.erp.core.util.CompanyTime;
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
import com.bigbrightpaints.erp.modules.company.dto.*;
import com.bigbrightpaints.erp.modules.rbac.domain.Role;

import jakarta.persistence.EntityNotFoundException;

@Service
public class SuperAdminTenantControlPlaneService {

  private final CompanyRepository companyRepository;
  private final UserAccountRepository userAccountRepository;
  private final AuditLogRepository auditLogRepository;
  private final AuditService auditService;
  private final EmailService emailService;
  private final TokenBlacklistService tokenBlacklistService;
  private final RefreshTokenService refreshTokenService;
  private final TenantSupportWarningRepository tenantSupportWarningRepository;
  private final TenantAdminEmailChangeRequestRepository tenantAdminEmailChangeRequestRepository;
  private final TenantRuntimeEnforcementService tenantRuntimeEnforcementService;
  private final CompanyService companyService;

  public SuperAdminTenantControlPlaneService(
      CompanyRepository companyRepository,
      UserAccountRepository userAccountRepository,
      AuditLogRepository auditLogRepository,
      AuditService auditService,
      EmailService emailService,
      TokenBlacklistService tokenBlacklistService,
      RefreshTokenService refreshTokenService,
      TenantSupportWarningRepository tenantSupportWarningRepository,
      TenantAdminEmailChangeRequestRepository tenantAdminEmailChangeRequestRepository,
      TenantRuntimeEnforcementService tenantRuntimeEnforcementService,
      CompanyService companyService) {
    this.companyRepository = companyRepository;
    this.userAccountRepository = userAccountRepository;
    this.auditLogRepository = auditLogRepository;
    this.auditService = auditService;
    this.emailService = emailService;
    this.tokenBlacklistService = tokenBlacklistService;
    this.refreshTokenService = refreshTokenService;
    this.tenantSupportWarningRepository = tenantSupportWarningRepository;
    this.tenantAdminEmailChangeRequestRepository = tenantAdminEmailChangeRequestRepository;
    this.tenantRuntimeEnforcementService = tenantRuntimeEnforcementService;
    this.companyService = companyService;
  }

  @Transactional(readOnly = true)
  public List<SuperAdminTenantSummaryDto> listTenants(String statusFilter) {
    String normalizedStatus = normalizeLifecycleFilter(statusFilter);
    return companyRepository.findAll().stream()
        .sorted(Comparator.comparing(Company::getCode, String.CASE_INSENSITIVE_ORDER))
        .filter(
            company ->
                normalizedStatus == null || normalizedStatus.equals(resolveLifecycle(company)))
        .map(this::toSummary)
        .toList();
  }

  @Transactional(readOnly = true)
  public SuperAdminTenantDetailDto getTenantDetail(Long companyId) {
    return toDetail(requireCompany(companyId));
  }

  @Transactional
  public CompanyLifecycleStateDto updateLifecycleState(
      Long companyId, CompanyLifecycleStateRequest request) {
    return companyService.updateLifecycleState(companyId, request);
  }

  @Transactional
  public CompanyEnabledModulesDto updateModules(Long companyId, Set<String> enabledModules) {
    return companyService.updateEnabledModules(companyId, enabledModules);
  }

  @Transactional
  public CompanyAdminCredentialResetDto resetTenantAdminPassword(
      Long companyId, String adminEmail, String reason) {
    return companyService.resetTenantAdminPassword(companyId, adminEmail, reason);
  }

  @Transactional
  public CompanySupportWarningDto issueSupportWarning(
      Long companyId,
      String warningCategory,
      String message,
      String requestedLifecycleState,
      Integer gracePeriodHours) {
    Company company = requireCompany(companyId);
    String actor = currentActor();
    String normalizedLifecycleState = normalizeRequestedLifecycleState(requestedLifecycleState);
    int resolvedGracePeriodHours = resolveGracePeriodHours(gracePeriodHours);
    if (!StringUtils.hasText(message)) {
      throw com.bigbrightpaints.erp.core.validation.ValidationUtils.invalidInput(
          "Support warning message is required");
    }
    TenantSupportWarning warning = new TenantSupportWarning();
    warning.setCompany(company);
    warning.setWarningCategory(normalizeWarningCategory(warningCategory));
    warning.setMessage(message.trim());
    warning.setRequestedLifecycleState(normalizedLifecycleState);
    warning.setGracePeriodHours(resolvedGracePeriodHours);
    warning.setIssuedBy(actor);
    warning.setIssuedAt(CompanyTime.now(company));
    TenantSupportWarning saved = tenantSupportWarningRepository.save(warning);
    logAuditSuccess(
        company,
        "tenant-support-warning-issued",
        Map.of(
            "warningId", String.valueOf(saved.getId()),
            "warningCategory", saved.getWarningCategory(),
            "requestedLifecycleState", saved.getRequestedLifecycleState(),
            "gracePeriodHours", String.valueOf(saved.getGracePeriodHours())));
    return new CompanySupportWarningDto(
        company.getId(),
        company.getCode(),
        String.valueOf(saved.getId()),
        saved.getWarningCategory(),
        saved.getMessage(),
        saved.getRequestedLifecycleState(),
        saved.getGracePeriodHours(),
        saved.getIssuedBy(),
        saved.getIssuedAt());
  }

  @Transactional
  public SuperAdminTenantLimitsDto updateLimits(
      Long companyId,
      Long quotaMaxActiveUsers,
      Long quotaMaxApiRequests,
      Long quotaMaxStorageBytes,
      Long quotaMaxConcurrentRequests,
      Boolean quotaSoftLimitEnabled,
      Boolean quotaHardLimitEnabled) {
    Company company = requireCompany(companyId);
    if (quotaMaxActiveUsers == null
        && quotaMaxApiRequests == null
        && quotaMaxStorageBytes == null
        && quotaMaxConcurrentRequests == null
        && quotaSoftLimitEnabled == null
        && quotaHardLimitEnabled == null) {
      throw com.bigbrightpaints.erp.core.validation.ValidationUtils.invalidInput(
          "Tenant limits payload is required");
    }
    if (quotaMaxActiveUsers != null) {
      company.setQuotaMaxActiveUsers(quotaMaxActiveUsers);
    }
    if (quotaMaxApiRequests != null) {
      company.setQuotaMaxApiRequests(quotaMaxApiRequests);
    }
    if (quotaMaxStorageBytes != null) {
      company.setQuotaMaxStorageBytes(quotaMaxStorageBytes);
    }
    if (quotaMaxConcurrentRequests != null) {
      company.setQuotaMaxConcurrentRequests(quotaMaxConcurrentRequests);
    }
    if (quotaSoftLimitEnabled != null) {
      company.setQuotaSoftLimitEnabled(quotaSoftLimitEnabled);
    }
    if (quotaHardLimitEnabled != null) {
      company.setQuotaHardLimitEnabled(quotaHardLimitEnabled);
    }
    companyRepository.save(company);
    tenantRuntimeEnforcementService.updatePolicy(
        company.getCode(),
        null,
        "ERP37_LIMITS_UPDATE",
        safeInteger(company.getQuotaMaxConcurrentRequests()),
        safeInteger(company.getQuotaMaxApiRequests()),
        safeInteger(company.getQuotaMaxActiveUsers()),
        currentActor());
    logAuditSuccess(
        company,
        "tenant-limits-updated",
        Map.of(
            "quotaMaxActiveUsers", String.valueOf(company.getQuotaMaxActiveUsers()),
            "quotaMaxApiRequests", String.valueOf(company.getQuotaMaxApiRequests()),
            "quotaMaxStorageBytes", String.valueOf(company.getQuotaMaxStorageBytes()),
            "quotaMaxConcurrentRequests", String.valueOf(company.getQuotaMaxConcurrentRequests())));
    return new SuperAdminTenantLimitsDto(
        company.getId(),
        company.getCode(),
        company.getQuotaMaxActiveUsers(),
        company.getQuotaMaxApiRequests(),
        company.getQuotaMaxStorageBytes(),
        company.getQuotaMaxConcurrentRequests(),
        company.isQuotaSoftLimitEnabled(),
        company.isQuotaHardLimitEnabled());
  }

  @Transactional
  public SuperAdminTenantSupportContextDto updateSupportContext(
      Long companyId, String supportNotes, Set<String> supportTags) {
    Company company = requireCompany(companyId);
    if (supportNotes != null) {
      company.setSupportNotes(supportNotes);
    }
    if (supportTags != null) {
      company.setSupportTags(supportTags);
    }
    companyRepository.save(company);
    logAuditSuccess(company, "tenant-support-context-updated", Map.of());
    return new SuperAdminTenantSupportContextDto(
        company.getId(), company.getCode(), company.getSupportNotes(), company.getSupportTags());
  }

  @Transactional
  public SuperAdminTenantForceLogoutDto forceLogoutAllUsers(Long companyId, String reason) {
    Company company = requireCompany(companyId);
    String actor = currentActor();
    List<UserAccount> users = userAccountRepository.findDistinctByCompanies_Id(companyId);
    assertTenantExclusiveUsers(company, users, "tenant force logout");
    String normalizedReason = normalizeOptionalReason(reason, "support-request");
    for (UserAccount user : users) {
      if (!StringUtils.hasText(user.getEmail())) {
        continue;
      }
      tokenBlacklistService.revokeAllUserTokens(user.getEmail());
      refreshTokenService.revokeAllForUser(user.getEmail());
    }
    Instant occurredAt = CompanyTime.now(company);
    logAuditSuccess(
        company,
        "tenant-force-logout",
        Map.of(
            "revokedUserCount",
            String.valueOf(users.size()),
            "forceLogoutReason", normalizedReason));
    return new SuperAdminTenantForceLogoutDto(
        company.getId(), company.getCode(), users.size(), normalizedReason, actor, occurredAt);
  }

  @Transactional
  public MainAdminSummaryDto replaceMainAdmin(Long companyId, Long adminUserId) {
    Company company = requireCompany(companyId);
    UserAccount targetUser = requireAssignedAdmin(company, adminUserId);
    company.setMainAdminUserId(targetUser.getId());
    companyRepository.save(company);
    logAuditSuccess(
        company,
        "tenant-main-admin-replaced",
        Map.of(
            "mainAdminUserId", String.valueOf(targetUser.getId()),
            "mainAdminEmail", targetUser.getEmail()));
    return toMainAdminSummary(company, targetUser);
  }

  @Transactional
  public SuperAdminTenantAdminEmailChangeRequestDto requestAdminEmailChange(
      Long companyId, Long adminUserId, String requestedEmail) {
    Company company = requireCompany(companyId);
    UserAccount adminUser = requireAssignedAdmin(company, adminUserId);
    assertTenantExclusiveUser(company, adminUser, "tenant admin email change");
    String normalizedRequestedEmail = normalizeRequiredEmail(requestedEmail, "newEmail");
    if (normalizedRequestedEmail.equalsIgnoreCase(adminUser.getEmail())) {
      throw com.bigbrightpaints.erp.core.validation.ValidationUtils.invalidInput(
          "newEmail must differ from the current admin email");
    }
    if (userAccountRepository.findByEmailIgnoreCase(normalizedRequestedEmail).isPresent()) {
      throw com.bigbrightpaints.erp.core.validation.ValidationUtils.invalidInput(
          "Email already exists: " + normalizedRequestedEmail);
    }
    TenantAdminEmailChangeRequest changeRequest = new TenantAdminEmailChangeRequest();
    changeRequest.setCompanyId(company.getId());
    changeRequest.setAdminUserId(adminUser.getId());
    changeRequest.setRequestedBy(currentActor());
    changeRequest.setCurrentEmail(adminUser.getEmail());
    changeRequest.setRequestedEmail(normalizedRequestedEmail);
    changeRequest.setVerificationToken(UUID.randomUUID().toString());
    changeRequest.setVerificationSentAt(CompanyTime.now(company));
    changeRequest.setExpiresAt(changeRequest.getVerificationSentAt().plusSeconds(60L * 60L * 24L));
    TenantAdminEmailChangeRequest saved =
        tenantAdminEmailChangeRequestRepository.save(changeRequest);
    emailService.sendAdminEmailChangeVerificationRequired(
        normalizedRequestedEmail,
        adminUser.getDisplayName(),
        company.getCode(),
        saved.getVerificationToken(),
        saved.getExpiresAt());
    logAuditSuccess(
        company,
        "tenant-admin-email-change-requested",
        Map.of(
            "requestId", String.valueOf(saved.getId()),
            "adminUserId", String.valueOf(adminUser.getId()),
            "currentEmail", adminUser.getEmail(),
            "requestedEmail", normalizedRequestedEmail));
    return new SuperAdminTenantAdminEmailChangeRequestDto(
        saved.getId(),
        company.getId(),
        company.getCode(),
        adminUser.getId(),
        saved.getCurrentEmail(),
        saved.getRequestedEmail(),
        saved.getVerificationSentAt(),
        saved.getExpiresAt());
  }

  @Transactional
  public SuperAdminTenantAdminEmailChangeConfirmationDto confirmAdminEmailChange(
      Long companyId, Long adminUserId, Long requestId, String verificationToken) {
    Company company = requireCompany(companyId);
    UserAccount adminUser = requireAssignedAdmin(company, adminUserId);
    assertTenantExclusiveUser(company, adminUser, "tenant admin email change");
    TenantAdminEmailChangeRequest changeRequest =
        tenantAdminEmailChangeRequestRepository
            .findById(requestId)
            .orElseThrow(() -> new EntityNotFoundException("Email change request not found"));
    if (!companyId.equals(changeRequest.getCompanyId())
        || !adminUserId.equals(changeRequest.getAdminUserId())) {
      throw new AccessDeniedException("Email change request does not match the targeted admin");
    }
    if (changeRequest.isConsumed()) {
      throw com.bigbrightpaints.erp.core.validation.ValidationUtils.invalidState(
          "Email change request has already been consumed");
    }
    if (!StringUtils.hasText(verificationToken)
        || !verificationToken.trim().equals(changeRequest.getVerificationToken())) {
      throw com.bigbrightpaints.erp.core.validation.ValidationUtils.invalidInput(
          "Invalid verification token");
    }
    if (changeRequest.getExpiresAt() != null
        && changeRequest.getExpiresAt().isBefore(CompanyTime.now(company))) {
      throw com.bigbrightpaints.erp.core.validation.ValidationUtils.invalidState(
          "Email change verification token has expired");
    }
    if (!adminUser.getEmail().equalsIgnoreCase(changeRequest.getCurrentEmail())) {
      throw com.bigbrightpaints.erp.core.validation.ValidationUtils.invalidState(
          "Email change request is stale because the admin email has already changed");
    }
    Instant now = CompanyTime.now(company);
    changeRequest.setVerifiedAt(now);
    changeRequest.setConfirmedAt(now);
    changeRequest.setConsumed(true);
    adminUser.setEmail(changeRequest.getRequestedEmail());
    userAccountRepository.save(adminUser);
    tenantAdminEmailChangeRequestRepository.save(changeRequest);
    tokenBlacklistService.revokeAllUserTokens(changeRequest.getCurrentEmail());
    refreshTokenService.revokeAllForUser(changeRequest.getCurrentEmail());
    tokenBlacklistService.revokeAllUserTokens(adminUser.getEmail());
    refreshTokenService.revokeAllForUser(adminUser.getEmail());
    logAuditSuccess(
        company,
        "tenant-admin-email-change-confirmed",
        Map.of(
            "requestId", String.valueOf(changeRequest.getId()),
            "adminUserId", String.valueOf(adminUser.getId()),
            "updatedEmail", adminUser.getEmail()));
    return new SuperAdminTenantAdminEmailChangeConfirmationDto(
        changeRequest.getId(),
        company.getId(),
        company.getCode(),
        adminUser.getId(),
        adminUser.getEmail(),
        changeRequest.getVerifiedAt(),
        changeRequest.getConfirmedAt());
  }

  private SuperAdminTenantSummaryDto toSummary(Company company) {
    CompanyTenantMetricsDto metrics = buildMetrics(company);
    return new SuperAdminTenantSummaryDto(
        company.getId(),
        company.getCode(),
        company.getName(),
        company.getTimezone(),
        metrics.lifecycleState(),
        metrics.lifecycleReason(),
        metrics.activeUserCount(),
        metrics.quotaMaxActiveUsers(),
        metrics.apiActivityCount(),
        metrics.quotaMaxApiRequests(),
        metrics.auditStorageBytes(),
        metrics.quotaMaxStorageBytes(),
        metrics.currentConcurrentRequests(),
        metrics.quotaMaxConcurrentRequests(),
        company.getEnabledModules(),
        toMainAdminSummary(company, resolveMainAdmin(company)),
        resolveLastActivityAt(company.getId()));
  }

  private SuperAdminTenantDetailDto toDetail(Company company) {
    CompanyTenantMetricsDto metrics = buildMetrics(company);
    UserAccount mainAdmin = resolveMainAdmin(company);
    return new SuperAdminTenantDetailDto(
        company.getId(),
        company.getCode(),
        company.getName(),
        company.getTimezone(),
        company.getStateCode(),
        resolveLifecycle(company),
        company.getLifecycleReason(),
        company.getEnabledModules(),
        new SuperAdminTenantDetailDto.Onboarding(
            company.getOnboardingCoaTemplateCode(),
            company.getOnboardingAdminEmail(),
            company.getOnboardingAdminUserId(),
            company.getOnboardingAdminUserId() != null,
            company.getOnboardingCredentialsEmailedAt() != null,
            company.getOnboardingCredentialsEmailedAt(),
            company.getOnboardingCompletedAt()),
        toMainAdminSummary(company, mainAdmin),
        new SuperAdminTenantDetailDto.Limits(
            metrics.quotaMaxActiveUsers(),
            metrics.quotaMaxApiRequests(),
            metrics.quotaMaxStorageBytes(),
            metrics.quotaMaxConcurrentRequests(),
            metrics.quotaSoftLimitEnabled(),
            metrics.quotaHardLimitEnabled()),
        new SuperAdminTenantDetailDto.Usage(
            metrics.activeUserCount(),
            metrics.apiActivityCount(),
            metrics.apiErrorCount(),
            metrics.apiErrorRateInBasisPoints(),
            metrics.auditStorageBytes(),
            metrics.currentConcurrentRequests(),
            resolveLastActivityAt(company.getId())),
        new SuperAdminTenantDetailDto.SupportContext(
            company.getSupportNotes(), company.getSupportTags()),
        buildSupportTimeline(company),
        new SuperAdminTenantDetailDto.AvailableActions(
            true, true, true, true, true, true, true, true));
  }

  private CompanyTenantMetricsDto buildMetrics(Company company) {
    return companyService.getTenantMetricsForSuperAdmin(company.getId());
  }

  private List<SuperAdminTenantDetailDto.SupportTimelineEvent> buildSupportTimeline(
      Company company) {
    List<SuperAdminTenantDetailDto.SupportTimelineEvent> timeline = new ArrayList<>();
    for (TenantSupportWarning warning :
        tenantSupportWarningRepository.findByCompany_IdOrderByIssuedAtDesc(company.getId())) {
      timeline.add(
          new SuperAdminTenantDetailDto.SupportTimelineEvent(
              "WARNING",
              warning.getWarningCategory(),
              warning.getMessage(),
              warning.getIssuedBy(),
              warning.getIssuedAt()));
    }
    for (AuditLog auditLog :
        auditLogRepository.findTop50ByCompanyIdOrderByTimestampDesc(company.getId())) {
      timeline.add(
          new SuperAdminTenantDetailDto.SupportTimelineEvent(
              "AUDIT",
              auditLog.getEventType().name(),
              auditMessage(auditLog),
              StringUtils.hasText(auditLog.getUsername()) ? auditLog.getUsername() : "system",
              toInstant(auditLog.getTimestamp())));
    }
    timeline.sort(
        Comparator.comparing(
                SuperAdminTenantDetailDto.SupportTimelineEvent::occurredAt,
                Comparator.nullsLast(Comparator.reverseOrder()))
            .thenComparing(SuperAdminTenantDetailDto.SupportTimelineEvent::category));
    return timeline.size() > 50 ? timeline.subList(0, 50) : timeline;
  }

  private String auditMessage(AuditLog auditLog) {
    if (auditLog.getMetadata() != null
        && StringUtils.hasText(auditLog.getMetadata().get("reason"))) {
      return auditLog.getMetadata().get("reason");
    }
    if (StringUtils.hasText(auditLog.getErrorMessage())) {
      return auditLog.getErrorMessage();
    }
    return auditLog.getEventType().name();
  }

  private MainAdminSummaryDto toMainAdminSummary(Company company, UserAccount mainAdmin) {
    if (mainAdmin == null) {
      return new MainAdminSummaryDto(company.getMainAdminUserId(), null, null, false, false);
    }
    return new MainAdminSummaryDto(
        mainAdmin.getId(),
        mainAdmin.getEmail(),
        mainAdmin.getDisplayName(),
        mainAdmin.isEnabled(),
        true);
  }

  private UserAccount resolveMainAdmin(Company company) {
    if (company == null || company.getMainAdminUserId() == null) {
      return null;
    }
    return userAccountRepository.findById(company.getMainAdminUserId()).orElse(null);
  }

  private UserAccount requireAssignedAdmin(Company company, Long adminUserId) {
    if (company == null || company.getId() == null || adminUserId == null) {
      throw com.bigbrightpaints.erp.core.validation.ValidationUtils.invalidInput(
          "Target admin is required");
    }
    UserAccount user =
        userAccountRepository
            .findByIdAndCompanies_Id(adminUserId, company.getId())
            .orElseThrow(
                () ->
                    com.bigbrightpaints.erp.core.validation.ValidationUtils.invalidInput(
                        "Admin user not found for company"));
    boolean adminRole =
        user.getRoles().stream()
            .map(Role::getName)
            .filter(StringUtils::hasText)
            .anyMatch(
                roleName ->
                    "ROLE_ADMIN".equalsIgnoreCase(roleName)
                        || "ROLE_SUPER_ADMIN".equalsIgnoreCase(roleName));
    if (!adminRole) {
      throw com.bigbrightpaints.erp.core.validation.ValidationUtils.invalidInput(
          "Target user is not an admin for company: " + company.getCode());
    }
    return user;
  }

  private Company requireCompany(Long companyId) {
    return companyRepository
        .findById(companyId)
        .orElseThrow(
            () ->
                com.bigbrightpaints.erp.core.validation.ValidationUtils.invalidInput(
                    "Company not found"));
  }

  private String resolveLifecycle(Company company) {
    return company == null || company.getLifecycleState() == null
        ? CompanyLifecycleState.ACTIVE.name()
        : company.getLifecycleState().name();
  }

  private Instant resolveLastActivityAt(Long companyId) {
    return auditLogRepository
        .findTop1ByCompanyIdOrderByTimestampDesc(companyId)
        .map(AuditLog::getTimestamp)
        .map(this::toInstant)
        .orElse(null);
  }

  private String normalizeLifecycleFilter(String statusFilter) {
    if (!StringUtils.hasText(statusFilter)) {
      return null;
    }
    String normalized = statusFilter.trim().toUpperCase(Locale.ROOT);
    if (normalized.equals(CompanyLifecycleState.ACTIVE.name())
        || normalized.equals(CompanyLifecycleState.SUSPENDED.name())
        || normalized.equals(CompanyLifecycleState.DEACTIVATED.name())) {
      return normalized;
    }
    throw com.bigbrightpaints.erp.core.validation.ValidationUtils.invalidInput(
        "status filter must be ACTIVE, SUSPENDED, or DEACTIVATED");
  }

  private String normalizeRequiredEmail(String email, String fieldName) {
    if (!StringUtils.hasText(email)) {
      throw com.bigbrightpaints.erp.core.validation.ValidationUtils.invalidInput(
          fieldName + " is required");
    }
    return email.trim().toLowerCase(Locale.ROOT);
  }

  private String normalizeOptionalReason(String value, String fallback) {
    if (!StringUtils.hasText(value)) {
      return fallback;
    }
    return value.trim();
  }

  private String normalizeWarningCategory(String warningCategory) {
    return StringUtils.hasText(warningCategory)
        ? warningCategory.trim().toUpperCase(Locale.ROOT)
        : "GENERAL";
  }

  private String normalizeRequestedLifecycleState(String requestedLifecycleState) {
    if (!StringUtils.hasText(requestedLifecycleState)) {
      return CompanyLifecycleState.SUSPENDED.name();
    }
    String normalized = requestedLifecycleState.trim().toUpperCase(Locale.ROOT);
    if (normalized.equals(CompanyLifecycleState.SUSPENDED.name())
        || normalized.equals(CompanyLifecycleState.DEACTIVATED.name())) {
      return normalized;
    }
    throw com.bigbrightpaints.erp.core.validation.ValidationUtils.invalidInput(
        "requestedLifecycleState must be SUSPENDED or DEACTIVATED");
  }

  private int resolveGracePeriodHours(Integer gracePeriodHours) {
    if (gracePeriodHours == null) {
      return 24;
    }
    if (gracePeriodHours < 1 || gracePeriodHours > 720) {
      throw com.bigbrightpaints.erp.core.validation.ValidationUtils.invalidInput(
          "gracePeriodHours must be between 1 and 720");
    }
    return gracePeriodHours;
  }

  private int safeInteger(long value) {
    if (value > Integer.MAX_VALUE) {
      return Integer.MAX_VALUE;
    }
    return value < 0L ? 0 : (int) value;
  }

  private void assertTenantExclusiveUsers(
      Company company, List<UserAccount> users, String operationDescription) {
    if (users == null || users.isEmpty()) {
      return;
    }
    List<String> sharedUserEmails = new ArrayList<>();
    for (UserAccount user : users) {
      if (isSharedAcrossCompanies(user)) {
        sharedUserEmails.add(StringUtils.hasText(user.getEmail()) ? user.getEmail().trim() : "UNKNOWN");
      }
    }
    if (!sharedUserEmails.isEmpty()) {
      throw com.bigbrightpaints.erp.core.validation.ValidationUtils.invalidState(
          "Cannot perform "
              + operationDescription
              + " while shared users are assigned to "
              + company.getCode()
              + ": "
              + String.join(", ", sharedUserEmails));
    }
  }

  private void assertTenantExclusiveUser(
      Company company, UserAccount user, String operationDescription) {
    if (isSharedAcrossCompanies(user)) {
      throw com.bigbrightpaints.erp.core.validation.ValidationUtils.invalidState(
          "Cannot perform "
              + operationDescription
              + " for shared user "
              + user.getEmail()
              + " in "
              + company.getCode()
              + "; assign a tenant-exclusive admin first");
    }
  }

  private boolean isSharedAcrossCompanies(UserAccount user) {
    if (user == null || user.getCompanies() == null) {
      return false;
    }
    return user.getCompanies().stream()
            .filter(company -> company != null && company.getId() != null)
            .map(Company::getId)
            .distinct()
            .count()
        > 1L;
  }

  private String currentActor() {
    Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
    if (authentication == null || !StringUtils.hasText(authentication.getName())) {
      return "anonymous";
    }
    return authentication.getName().trim();
  }

  private void logAuditSuccess(Company company, String reason, Map<String, String> metadata) {
    if (auditService == null) {
      return;
    }
    HashMap<String, String> auditMetadata = new HashMap<>();
    if (metadata != null) {
      auditMetadata.putAll(metadata);
    }
    auditMetadata.put("actor", currentActor());
    auditMetadata.put("reason", reason);
    auditMetadata.put("targetCompanyCode", company.getCode());
    auditMetadata.put("targetCompanyId", String.valueOf(company.getId()));
    auditService.logAuthSuccess(
        AuditEvent.CONFIGURATION_CHANGED, currentActor(), company.getCode(), auditMetadata);
  }

  private Instant toInstant(LocalDateTime timestamp) {
    return timestamp == null ? null : timestamp.atZone(ZoneOffset.UTC).toInstant();
  }
}
