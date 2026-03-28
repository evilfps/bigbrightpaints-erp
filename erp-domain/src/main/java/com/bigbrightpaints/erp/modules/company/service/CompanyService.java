package com.bigbrightpaints.erp.modules.company.service;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;
import java.util.function.LongSupplier;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import com.bigbrightpaints.erp.core.audit.AuditEvent;
import com.bigbrightpaints.erp.core.audit.AuditLogRepository;
import com.bigbrightpaints.erp.core.audit.AuditService;
import com.bigbrightpaints.erp.core.security.AuthScopeService;
import com.bigbrightpaints.erp.core.security.CompanyContextHolder;
import com.bigbrightpaints.erp.core.util.CompanyTime;
import com.bigbrightpaints.erp.modules.auth.domain.UserAccountRepository;
import com.bigbrightpaints.erp.modules.auth.domain.UserPrincipal;
import com.bigbrightpaints.erp.modules.auth.service.PasswordResetService;
import com.bigbrightpaints.erp.modules.auth.service.TenantAdminProvisioningService;
import com.bigbrightpaints.erp.modules.company.domain.Company;
import com.bigbrightpaints.erp.modules.company.domain.CompanyLifecycleState;
import com.bigbrightpaints.erp.modules.company.domain.CompanyModule;
import com.bigbrightpaints.erp.modules.company.domain.CompanyRepository;
import com.bigbrightpaints.erp.modules.company.dto.CompanyAdminCredentialResetDto;
import com.bigbrightpaints.erp.modules.company.dto.CompanyDto;
import com.bigbrightpaints.erp.modules.company.dto.CompanyEnabledModulesDto;
import com.bigbrightpaints.erp.modules.company.dto.CompanyLifecycleStateDto;
import com.bigbrightpaints.erp.modules.company.dto.CompanyLifecycleStateRequest;
import com.bigbrightpaints.erp.modules.company.dto.CompanyRequest;
import com.bigbrightpaints.erp.modules.company.dto.CompanySuperAdminDashboardDto;
import com.bigbrightpaints.erp.modules.company.dto.CompanySupportWarningDto;
import com.bigbrightpaints.erp.modules.company.dto.CompanyTenantMetricsDto;

@Service
public class CompanyService {

  private static final String LIFECYCLE_STATE_METADATA_KEY = "companyLifecycleState";
  private static final String LIFECYCLE_PREVIOUS_STATE_METADATA_KEY =
      "companyPreviousLifecycleState";
  private static final String LIFECYCLE_REASON_METADATA_KEY = "companyLifecycleReason";
  private static final String LIFECYCLE_EVIDENCE_METADATA_KEY = "lifecycleEvidence";
  private static final String LIFECYCLE_EVIDENCE_VALUE = "immutable-audit-log";
  private static final String LIFECYCLE_UPDATED_REASON = "tenant-lifecycle-state-updated";
  private static final String LIFECYCLE_SUPER_ADMIN_REQUIRED_REASON =
      "super-admin-required-for-tenant-lifecycle-control";
  private static final String METRICS_SUPER_ADMIN_REQUIRED_REASON =
      "super-admin-required-for-tenant-metrics-read";
  private static final String METRICS_READ_REASON = "tenant-metrics-read";
  private static final String RUNTIME_POLICY_SUPER_ADMIN_REQUIRED_REASON =
      "super-admin-required-for-tenant-runtime-policy-control";
  private static final String RUNTIME_POLICY_UPDATED_REASON = "tenant-runtime-policy-updated";
  private static final String TENANT_MODULES_UPDATED_REASON = "tenant-enabled-modules-updated";
  private static final String SUPERADMIN_DASHBOARD_READ_REASON = "superadmin-dashboard-read";
  private static final String TENANT_SUPPORT_WARNING_ISSUED_REASON =
      "tenant-support-warning-issued";
  private static final long ERROR_RATE_BASIS_POINTS_SCALE = 10_000L;
  private static final BigDecimal DEFAULT_BOOTSTRAP_GST_RATE = BigDecimal.valueOf(18);

  private final CompanyRepository repository;
  private final AuditService auditService;
  private final UserAccountRepository userAccountRepository;
  private final AuditLogRepository auditLogRepository;
  private final TenantRuntimeEnforcementService tenantRuntimeEnforcementService;
  private final TenantAdminProvisioningService tenantAdminProvisioningService;
  private final TenantLifecycleService tenantLifecycleService;
  private final PasswordResetService passwordResetService;
  private final AuthScopeService authScopeService;

  public CompanyService(CompanyRepository repository) {
    this(repository, null, null, null, null, null, null, null, null);
  }

  public CompanyService(
      CompanyRepository repository,
      AuditService auditService,
      UserAccountRepository userAccountRepository,
      AuditLogRepository auditLogRepository) {
    this(
        repository,
        auditService,
        userAccountRepository,
        auditLogRepository,
        null,
        null,
        null,
        null,
        null);
  }

  public CompanyService(
      CompanyRepository repository,
      AuditService auditService,
      UserAccountRepository userAccountRepository,
      AuditLogRepository auditLogRepository,
      TenantRuntimeEnforcementService tenantRuntimeEnforcementService) {
    this(
        repository,
        auditService,
        userAccountRepository,
        auditLogRepository,
        tenantRuntimeEnforcementService,
        null,
        null,
        null,
        null);
  }

  @Autowired
  public CompanyService(
      CompanyRepository repository,
      AuditService auditService,
      UserAccountRepository userAccountRepository,
      AuditLogRepository auditLogRepository,
      TenantRuntimeEnforcementService tenantRuntimeEnforcementService,
      TenantAdminProvisioningService tenantAdminProvisioningService,
      TenantLifecycleService tenantLifecycleService,
      PasswordResetService passwordResetService,
      AuthScopeService authScopeService) {
    this.repository = repository;
    this.auditService = auditService;
    this.userAccountRepository = userAccountRepository;
    this.auditLogRepository = auditLogRepository;
    this.tenantRuntimeEnforcementService = tenantRuntimeEnforcementService;
    this.tenantAdminProvisioningService = tenantAdminProvisioningService;
    this.tenantLifecycleService = tenantLifecycleService;
    this.passwordResetService = passwordResetService;
    this.authScopeService = authScopeService;
  }

  public List<CompanyDto> findAll() {
    return repository.findAll().stream().map(this::toDto).toList();
  }

  public List<CompanyDto> findAll(Company company) {
    if (company == null) {
      return List.of();
    }
    return List.of(toDto(company));
  }

  @Transactional
  public CompanyDto create(CompanyRequest request) {
    String normalizedCompanyCode = normalizeCompanyCode(request.code());
    Authentication authentication = requireSuperAdminForTenantBootstrap(normalizedCompanyCode);
    ensureCompanyCodeAvailableForCreate(normalizedCompanyCode);
    Company company = new Company();
    company.setName(request.name());
    company.setCode(normalizedCompanyCode);
    company.setTimezone(request.timezone());
    company.setStateCode(normalizeStateCode(request.stateCode()));
    company.setDefaultGstRate(resolveDefaultGstRateForCreate(request.defaultGstRate()));
    company.setQuotaMaxActiveUsers(resolveQuotaForCreate(request.quotaMaxActiveUsers()));
    company.setQuotaMaxApiRequests(resolveQuotaForCreate(request.quotaMaxApiRequests()));
    company.setQuotaMaxStorageBytes(resolveQuotaForCreate(request.quotaMaxStorageBytes()));
    company.setQuotaMaxConcurrentRequests(
        resolveQuotaForCreate(request.quotaMaxConcurrentRequests()));
    company.setQuotaSoftLimitEnabled(
        resolveQuotaFlagForCreate(request.quotaSoftLimitEnabled(), false));
    company.setQuotaHardLimitEnabled(
        resolveQuotaFlagForCreate(request.quotaHardLimitEnabled(), true));
    if (request.enabledModules() != null) {
      company.setEnabledModules(validateAndNormalizeEnabledModules(request.enabledModules()));
    }
    Company saved = repository.save(company);
    provisionInitialAdminIfRequested(
        saved, request.firstAdminEmail(), request.firstAdminDisplayName());
    auditAuthorityDecision(true, "tenant-bootstrap-created", normalizedCompanyCode, authentication);
    return toDto(saved);
  }

  @Transactional
  public CompanyDto update(Long id, CompanyRequest request, Set<Company> allowedCompanies) {
    requireSuperAdminForTenantConfigurationUpdate();
    Company company =
        repository
            .findById(id)
            .orElseThrow(
                () ->
                    com.bigbrightpaints.erp.core.validation.ValidationUtils.invalidInput(
                        "Company not found"));
    assertBoundControlPlaneCompanyMatchesTarget(company.getCode());
    String normalizedCompanyCode = normalizeCompanyCode(request.code());
    ensureCompanyCodeAvailableForUpdate(id, normalizedCompanyCode);
    synchronizeScopedAccountsToCompanyCode(company, normalizedCompanyCode);
    company.setName(request.name());
    company.setCode(normalizedCompanyCode);
    company.setTimezone(request.timezone());
    if (request.stateCode() != null) {
      company.setStateCode(normalizeStateCode(request.stateCode()));
    }
    if (request.defaultGstRate() != null) {
      company.setDefaultGstRate(request.defaultGstRate());
    }
    company.setQuotaMaxActiveUsers(
        resolveQuotaForUpdate(request.quotaMaxActiveUsers(), company.getQuotaMaxActiveUsers()));
    company.setQuotaMaxApiRequests(
        resolveQuotaForUpdate(request.quotaMaxApiRequests(), company.getQuotaMaxApiRequests()));
    company.setQuotaMaxStorageBytes(
        resolveQuotaForUpdate(request.quotaMaxStorageBytes(), company.getQuotaMaxStorageBytes()));
    company.setQuotaMaxConcurrentRequests(
        resolveQuotaForUpdate(
            request.quotaMaxConcurrentRequests(), company.getQuotaMaxConcurrentRequests()));
    company.setQuotaSoftLimitEnabled(
        resolveQuotaFlagForUpdate(
            request.quotaSoftLimitEnabled(), company.isQuotaSoftLimitEnabled()));
    company.setQuotaHardLimitEnabled(
        resolveQuotaFlagForUpdate(
            request.quotaHardLimitEnabled(), company.isQuotaHardLimitEnabled()));
    if (request.enabledModules() != null) {
      company.setEnabledModules(validateAndNormalizeEnabledModules(request.enabledModules()));
    }
    return toDto(company);
  }

  @Transactional
  public CompanyEnabledModulesDto updateEnabledModules(Long id, Set<String> enabledModules) {
    Authentication authentication = requireSuperAdminForTenantConfigurationUpdate();
    if (enabledModules == null) {
      throw com.bigbrightpaints.erp.core.validation.ValidationUtils.invalidInput(
          "enabledModules is required");
    }
    Company company =
        repository
            .findById(id)
            .orElseThrow(
                () ->
                    com.bigbrightpaints.erp.core.validation.ValidationUtils.invalidInput(
                        "Company not found"));
    company.setEnabledModules(validateAndNormalizeEnabledModules(enabledModules));
    auditAuthorityDecision(true, TENANT_MODULES_UPDATED_REASON, company.getCode(), authentication);
    return new CompanyEnabledModulesDto(
        company.getId(), company.getCode(), company.getEnabledModules());
  }

  public void delete(Long id, Set<Company> allowedCompanies) {
    requireMembershipById(id, allowedCompanies);
    repository.deleteById(id);
  }

  @Transactional
  public CompanyLifecycleStateDto updateLifecycleState(
      Long companyId, CompanyLifecycleStateRequest request) {
    CompanyLifecycleState requestedState = CompanyLifecycleState.fromRequestValue(request.state());
    String lifecycleReason = normalizeLifecycleReason(request.reason());
    Authentication authentication =
        requireSuperAdminForLifecycleControl(companyId, requestedState, lifecycleReason);

    Company company =
        repository
            .lockById(companyId)
            .orElseThrow(
                () ->
                    com.bigbrightpaints.erp.core.validation.ValidationUtils.invalidInput(
                        "Company not found"));
    assertBoundControlPlaneCompanyMatchesTarget(company.getCode());

    if (tenantLifecycleService != null) {
      CompanyLifecycleStateDto response =
          tenantLifecycleService.transition(
              company, requestedState, lifecycleReason, authentication);
      synchronizeRuntimePolicyWithLifecycle(
          company, requestedState, lifecycleReason, authentication);
      return response;
    }

    CompanyLifecycleState previousState = resolveLifecycleStateById(company.getId());
    company.setLifecycleState(requestedState);
    company.setLifecycleReason(lifecycleReason);
    persistLifecycleAuditEvidence(
        company, previousState, requestedState, lifecycleReason, authentication);
    synchronizeRuntimePolicyWithLifecycle(company, requestedState, lifecycleReason, authentication);
    return new CompanyLifecycleStateDto(
        company.getId(),
        company.getCode(),
        previousState.name(),
        requestedState.name(),
        lifecycleReason);
  }

  public Company findByCode(String code) {
    return repository
        .findByCodeIgnoreCase(code)
        .orElseThrow(
            () ->
                com.bigbrightpaints.erp.core.validation.ValidationUtils.invalidInput(
                    "Company not found: " + code));
  }

  public boolean isRuntimeAccessAllowed(Long companyId) {
    if (companyId == null) {
      return false;
    }
    Company company = repository.findById(companyId).orElse(null);
    if (company == null) {
      return false;
    }
    CompanyLifecycleState lifecycleState =
        company.getLifecycleState() == null
            ? CompanyLifecycleState.ACTIVE
            : company.getLifecycleState();
    if (lifecycleState != CompanyLifecycleState.ACTIVE) {
      return false;
    }
    if (!company.isQuotaHardLimitEnabled()) {
      return true;
    }
    if (!hasConfiguredHardQuotaEnvelope(company)) {
      return false;
    }
    if (!hasRuntimeQuotaTelemetryDependencies()) {
      return false;
    }
    long activeUsers = resolveRuntimeMetricFailClosed(() -> countActiveUsers(companyId));
    long apiActivity = resolveRuntimeMetricFailClosed(() -> countApiActivity(companyId));
    long storageBytes = resolveRuntimeMetricFailClosed(() -> estimateAuditStorageBytes(companyId));
    long concurrentRequests =
        resolveRuntimeMetricFailClosed(
            () -> resolveCurrentConcurrentRequests(company.getCode(), companyId));
    if (isRuntimeMetricUnavailable(activeUsers)
        || isRuntimeMetricUnavailable(apiActivity)
        || isRuntimeMetricUnavailable(storageBytes)
        || isRuntimeMetricUnavailable(concurrentRequests)) {
      return false;
    }
    if (isQuotaExceeded(activeUsers, company.getQuotaMaxActiveUsers())) {
      return false;
    }
    if (isQuotaExceeded(apiActivity, company.getQuotaMaxApiRequests())) {
      return false;
    }
    if (isQuotaExceeded(storageBytes, company.getQuotaMaxStorageBytes())) {
      return false;
    }
    return !isQuotaExceeded(concurrentRequests, company.getQuotaMaxConcurrentRequests());
  }

  public CompanyLifecycleState resolveLifecycleStateByCode(String companyCode) {
    if (!StringUtils.hasText(companyCode)) {
      return CompanyLifecycleState.DEACTIVATED;
    }
    return repository
        .findByCodeIgnoreCase(companyCode.trim())
        .map(Company::getId)
        .map(this::resolveLifecycleStateById)
        .orElse(CompanyLifecycleState.DEACTIVATED);
  }

  public CompanyLifecycleState resolveLifecycleStateById(Long companyId) {
    if (companyId == null) {
      return CompanyLifecycleState.DEACTIVATED;
    }
    return repository
        .findById(companyId)
        .map(
            company ->
                company.getLifecycleState() == null
                    ? CompanyLifecycleState.ACTIVE
                    : company.getLifecycleState())
        .orElse(CompanyLifecycleState.DEACTIVATED);
  }

  public String resolveCompanyCodeById(Long companyId) {
    if (companyId == null) {
      return null;
    }
    return repository
        .findById(companyId)
        .map(Company::getCode)
        .filter(StringUtils::hasText)
        .map(String::trim)
        .orElse(null);
  }

  public CompanyTenantMetricsDto getTenantMetrics(Long companyId) {
    Authentication authentication = requireSuperAdminForTenantMetrics(companyId);
    Company company =
        repository
            .findById(companyId)
            .orElseThrow(
                () ->
                    com.bigbrightpaints.erp.core.validation.ValidationUtils.invalidInput(
                        "Company not found"));
    assertBoundControlPlaneCompanyMatchesTarget(company.getCode());
    auditAuthorityDecision(true, METRICS_READ_REASON, company.getCode(), authentication);
    return buildTenantMetrics(company);
  }

  public CompanyTenantMetricsDto getTenantMetricsForSuperAdmin(Long companyId) {
    Authentication authentication = requireSuperAdminForTenantMetrics(companyId);
    Company company =
        repository
            .findById(companyId)
            .orElseThrow(
                () ->
                    com.bigbrightpaints.erp.core.validation.ValidationUtils.invalidInput(
                        "Company not found"));
    auditAuthorityDecision(true, METRICS_READ_REASON, company.getCode(), authentication);
    return buildTenantMetrics(company);
  }

  public CompanySuperAdminDashboardDto getSuperAdminDashboard() {
    Authentication authentication = requireSuperAdminForTenantMetrics(null);
    List<CompanySuperAdminDashboardDto.TenantOverview> tenantOverview =
        repository.findAll().stream()
            .sorted(Comparator.comparing(Company::getCode, String.CASE_INSENSITIVE_ORDER))
            .map(this::buildTenantOverview)
            .toList();

    long totalTenants = tenantOverview.size();
    long activeTenants =
        tenantOverview.stream()
            .filter(tenant -> "ACTIVE".equalsIgnoreCase(tenant.lifecycleState()))
            .count();
    long suspendedTenants =
        tenantOverview.stream()
            .filter(tenant -> "SUSPENDED".equalsIgnoreCase(tenant.lifecycleState()))
            .count();
    long deactivatedTenants =
        tenantOverview.stream()
            .filter(tenant -> "DEACTIVATED".equalsIgnoreCase(tenant.lifecycleState()))
            .count();
    long totalActiveUsers =
        tenantOverview.stream()
            .mapToLong(CompanySuperAdminDashboardDto.TenantOverview::activeUsers)
            .sum();
    long totalActiveUserQuota =
        tenantOverview.stream()
            .mapToLong(CompanySuperAdminDashboardDto.TenantOverview::activeUserQuota)
            .sum();
    long totalAuditStorageBytes =
        tenantOverview.stream()
            .mapToLong(CompanySuperAdminDashboardDto.TenantOverview::auditStorageBytes)
            .sum();
    long totalStorageQuotaBytes =
        tenantOverview.stream()
            .mapToLong(CompanySuperAdminDashboardDto.TenantOverview::storageQuotaBytes)
            .sum();
    long totalCurrentConcurrentRequests =
        tenantOverview.stream()
            .mapToLong(CompanySuperAdminDashboardDto.TenantOverview::currentConcurrentRequests)
            .sum();
    long totalConcurrentRequestQuota =
        tenantOverview.stream()
            .mapToLong(CompanySuperAdminDashboardDto.TenantOverview::concurrentRequestQuota)
            .sum();
    auditAuthorityDecision(true, SUPERADMIN_DASHBOARD_READ_REASON, null, authentication);

    return new CompanySuperAdminDashboardDto(
        totalTenants,
        activeTenants,
        suspendedTenants,
        deactivatedTenants,
        totalActiveUsers,
        totalActiveUserQuota,
        totalAuditStorageBytes,
        totalStorageQuotaBytes,
        totalCurrentConcurrentRequests,
        totalConcurrentRequestQuota,
        tenantOverview);
  }

  @Transactional
  public CompanySupportWarningDto issueTenantSupportWarning(
      Long companyId, TenantSupportWarningRequest request) {
    Authentication authentication = requireSuperAdminForTenantConfigurationUpdate();
    Company company =
        repository
            .findById(companyId)
            .orElseThrow(
                () ->
                    com.bigbrightpaints.erp.core.validation.ValidationUtils.invalidInput(
                        "Company not found"));
    if (request == null || !StringUtils.hasText(request.message())) {
      throw com.bigbrightpaints.erp.core.validation.ValidationUtils.invalidInput(
          "Support warning message is required");
    }
    String warningId = UUID.randomUUID().toString();
    String warningCategory = normalizeWarningCategory(request.warningCategory());
    String requestedLifecycleState =
        normalizeWarningLifecycleState(request.requestedLifecycleState());
    int gracePeriodHours = resolveGracePeriodHours(request.gracePeriodHours());
    String warningMessage = request.message().trim();
    String actor = resolveActor(authentication);
    Instant issuedAt = CompanyTime.now(company);

    if (auditService != null) {
      HashMap<String, String> metadata = new HashMap<>();
      metadata.put("actor", actor);
      metadata.put("reason", TENANT_SUPPORT_WARNING_ISSUED_REASON);
      metadata.put("tenantScope", resolveTenantScope(authentication));
      metadata.put("targetCompanyId", String.valueOf(company.getId()));
      metadata.put("targetCompanyCode", company.getCode());
      metadata.put("warningId", warningId);
      metadata.put("warningCategory", warningCategory);
      metadata.put("requestedLifecycleState", requestedLifecycleState);
      metadata.put("gracePeriodHours", String.valueOf(gracePeriodHours));
      metadata.put("message", warningMessage);
      metadata.put("issuedAt", issuedAt.toString());
      auditService.logSuccess(AuditEvent.CONFIGURATION_CHANGED, metadata);
    }

    return new CompanySupportWarningDto(
        company.getId(),
        company.getCode(),
        warningId,
        warningCategory,
        warningMessage,
        requestedLifecycleState,
        gracePeriodHours,
        actor,
        issuedAt);
  }

  @Transactional
  public TenantRuntimeEnforcementService.TenantRuntimeSnapshot updateTenantRuntimePolicy(
      Long companyId, TenantRuntimePolicyMutationRequest request) {
    if (tenantRuntimeEnforcementService == null) {
      throw com.bigbrightpaints.erp.core.validation.ValidationUtils.invalidState(
          "Tenant runtime enforcement service unavailable");
    }
    if (!hasRuntimePolicyMutation(request)) {
      throw com.bigbrightpaints.erp.core.validation.ValidationUtils.invalidInput(
          "Runtime policy mutation payload is required");
    }
    Authentication authentication = requireSuperAdminForTenantRuntimePolicyControl(companyId);
    Company company =
        repository
            .findById(companyId)
            .orElseThrow(
                () ->
                    com.bigbrightpaints.erp.core.validation.ValidationUtils.invalidInput(
                        "Company not found"));
    assertBoundControlPlaneCompanyMatchesTarget(company.getCode());
    TenantRuntimeEnforcementService.TenantRuntimeSnapshot snapshot =
        tenantRuntimeEnforcementService.updatePolicy(
            company.getCode(),
            parseRuntimeState(request.holdState()),
            request.reasonCode(),
            request.maxConcurrentRequests(),
            request.maxRequestsPerMinute(),
            request.maxActiveUsers(),
            resolveActor(authentication));
    auditAuthorityDecision(true, RUNTIME_POLICY_UPDATED_REASON, company.getCode(), authentication);
    return snapshot;
  }

  @Transactional
  public CompanyAdminCredentialResetDto resetTenantAdminPassword(
      Long companyId, String adminEmail) {
    return resetTenantAdminPassword(companyId, adminEmail, null);
  }

  @Transactional
  public CompanyAdminCredentialResetDto resetTenantAdminPassword(
      Long companyId, String adminEmail, String supportReason) {
    Authentication authentication = requireSuperAdminForTenantConfigurationUpdate();
    Company company =
        repository
            .findById(companyId)
            .orElseThrow(
                () ->
                    com.bigbrightpaints.erp.core.validation.ValidationUtils.invalidInput(
                        "Company not found"));
    assertBoundControlPlaneCompanyMatchesTarget(company.getCode());
    requirePasswordResetReady();
    String resetEmail =
        tenantAdminProvisioningService.resetTenantAdminPassword(company, adminEmail);
    if (auditService != null) {
      HashMap<String, String> metadata = new HashMap<>();
      metadata.put("actor", resolveActor(authentication));
      metadata.put("reason", "tenant-admin-password-reset");
      metadata.put("tenantScope", resolveTenantScope(authentication));
      metadata.put("targetCompanyCode", company.getCode());
      metadata.put("targetCompanyId", String.valueOf(company.getId()));
      metadata.put("resetEmail", resetEmail);
      metadata.put(
          "supportReason",
          StringUtils.hasText(supportReason) ? supportReason.trim() : "support-reset-requested");
      auditService.logSuccess(AuditEvent.ACCESS_GRANTED, metadata);
    } else {
      auditAuthorityDecision(
          true, "tenant-admin-password-reset", company.getCode(), authentication);
    }
    return new CompanyAdminCredentialResetDto(
        company.getId(), company.getCode(), resetEmail, "reset-link-emailed");
  }

  private CompanyTenantMetricsDto buildTenantMetrics(Company company) {
    CompanyLifecycleState state =
        company.getLifecycleState() == null
            ? CompanyLifecycleState.ACTIVE
            : company.getLifecycleState();
    Long companyId = company.getId();
    long activeUserCount = countActiveUsers(companyId);
    long apiActivityCount = countApiActivity(companyId);
    long apiErrorCount = countApiFailureActivity(companyId);
    long apiErrorRateInBasisPoints =
        calculateErrorRateInBasisPoints(apiActivityCount, apiErrorCount);
    long currentConcurrentRequests = resolveCurrentConcurrentRequests(company.getCode(), companyId);
    long auditStorageBytes = estimateAuditStorageBytes(companyId);
    return new CompanyTenantMetricsDto(
        company.getId(),
        company.getCode(),
        state.name(),
        company.getLifecycleReason(),
        company.getQuotaMaxActiveUsers(),
        company.getQuotaMaxApiRequests(),
        company.getQuotaMaxStorageBytes(),
        company.getQuotaMaxConcurrentRequests(),
        company.isQuotaSoftLimitEnabled(),
        company.isQuotaHardLimitEnabled(),
        activeUserCount,
        apiActivityCount,
        apiErrorCount,
        apiErrorRateInBasisPoints,
        currentConcurrentRequests,
        auditStorageBytes);
  }

  private CompanySuperAdminDashboardDto.TenantOverview buildTenantOverview(Company company) {
    CompanyTenantMetricsDto metrics = buildTenantMetrics(company);
    return new CompanySuperAdminDashboardDto.TenantOverview(
        metrics.companyId(),
        metrics.companyCode(),
        company.getName(),
        company.getTimezone(),
        metrics.lifecycleState(),
        metrics.lifecycleReason(),
        metrics.activeUserCount(),
        metrics.quotaMaxActiveUsers(),
        metrics.auditStorageBytes(),
        metrics.quotaMaxStorageBytes(),
        metrics.currentConcurrentRequests(),
        metrics.quotaMaxConcurrentRequests(),
        metrics.apiActivityCount(),
        metrics.quotaMaxApiRequests(),
        metrics.apiErrorCount(),
        metrics.apiErrorRateInBasisPoints(),
        metrics.quotaSoftLimitEnabled(),
        metrics.quotaHardLimitEnabled(),
        calculateUtilizationInBasisPoints(metrics.activeUserCount(), metrics.quotaMaxActiveUsers()),
        calculateUtilizationInBasisPoints(
            metrics.auditStorageBytes(), metrics.quotaMaxStorageBytes()),
        calculateUtilizationInBasisPoints(
            metrics.currentConcurrentRequests(), metrics.quotaMaxConcurrentRequests()));
  }

  private void requireMembershipById(Long companyId, Set<Company> allowedCompanies) {
    if (companyId == null || allowedCompanies == null || allowedCompanies.isEmpty()) {
      throw new AccessDeniedException("Not allowed to access company");
    }
    boolean member =
        allowedCompanies.stream().anyMatch(company -> companyId.equals(company.getId()));
    if (!member) {
      throw new AccessDeniedException("Not allowed to access company");
    }
  }

  private void requireMembershipByCode(String companyCode, Set<Company> allowedCompanies) {
    String normalizedCode = companyCode == null ? "" : companyCode.trim();
    if (!StringUtils.hasText(normalizedCode)
        || allowedCompanies == null
        || allowedCompanies.isEmpty()) {
      throw new AccessDeniedException("Not allowed to access company");
    }
    boolean member =
        allowedCompanies.stream()
            .map(Company::getCode)
            .filter(StringUtils::hasText)
            .anyMatch(code -> code.equalsIgnoreCase(normalizedCode));
    if (!member) {
      throw new AccessDeniedException("Not allowed to access company");
    }
  }

  private CompanyDto toDto(Company company) {
    return new CompanyDto(
        company.getId(),
        company.getPublicId(),
        company.getName(),
        company.getCode(),
        company.getTimezone(),
        company.getStateCode(),
        company.getDefaultGstRate());
  }

  private String normalizeStateCode(String stateCode) {
    if (!StringUtils.hasText(stateCode)) {
      return null;
    }
    String normalized = stateCode.trim().toUpperCase(Locale.ROOT);
    if (normalized.length() != 2) {
      throw com.bigbrightpaints.erp.core.validation.ValidationUtils.invalidInput(
          "State code must be exactly 2 characters");
    }
    return normalized;
  }

  private Authentication requireSuperAdminForLifecycleControl(
      Long companyId, CompanyLifecycleState requestedState, String lifecycleReason) {
    Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
    if (!hasAuthority(authentication, "ROLE_SUPER_ADMIN")) {
      Company target = companyId == null ? null : repository.findById(companyId).orElse(null);
      auditLifecycleDenied(
          companyId,
          target == null ? null : target.getCode(),
          requestedState,
          lifecycleReason,
          authentication);
      throw new AccessDeniedException(
          "SUPER_ADMIN authority required for tenant lifecycle control");
    }
    return authentication;
  }

  private Authentication requireSuperAdminForTenantBootstrap(String requestedCompanyCode) {
    Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
    if (!hasAuthority(authentication, "ROLE_SUPER_ADMIN")) {
      auditAuthorityDecision(
          false, "super-admin-required-for-tenant-bootstrap", requestedCompanyCode, authentication);
      throw new AccessDeniedException("SUPER_ADMIN authority required for tenant bootstrap");
    }
    return authentication;
  }

  private Authentication requireSuperAdminForTenantMetrics(Long companyId) {
    Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
    if (!hasAuthority(authentication, "ROLE_SUPER_ADMIN")) {
      Company target = companyId == null ? null : repository.findById(companyId).orElse(null);
      String targetCompanyCode = target == null ? null : target.getCode();
      auditAuthorityDecision(
          false, METRICS_SUPER_ADMIN_REQUIRED_REASON, targetCompanyCode, authentication);
      throw new AccessDeniedException("SUPER_ADMIN authority required for tenant metrics");
    }
    return authentication;
  }

  private Authentication requireSuperAdminForTenantRuntimePolicyControl(Long companyId) {
    Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
    if (!hasAuthority(authentication, "ROLE_SUPER_ADMIN")) {
      Company target = companyId == null ? null : repository.findById(companyId).orElse(null);
      String targetCompanyCode = target == null ? null : target.getCode();
      auditAuthorityDecision(
          false, RUNTIME_POLICY_SUPER_ADMIN_REQUIRED_REASON, targetCompanyCode, authentication);
      throw new AccessDeniedException(
          "SUPER_ADMIN authority required for tenant runtime policy control");
    }
    return authentication;
  }

  private Authentication requireSuperAdminForTenantConfigurationUpdate() {
    Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
    if (!hasAuthority(authentication, "ROLE_SUPER_ADMIN")) {
      throw new AccessDeniedException(
          "SUPER_ADMIN authority required for tenant configuration updates");
    }
    return authentication;
  }

  private void assertBoundControlPlaneCompanyMatchesTarget(String targetCompanyCode) {
    if (!StringUtils.hasText(targetCompanyCode)) {
      return;
    }
    String boundCompanyCode = CompanyContextHolder.getCompanyCode();
    if (!StringUtils.hasText(boundCompanyCode)) {
      return;
    }
    if (authScopeService != null && authScopeService.isPlatformScope(boundCompanyCode)) {
      return;
    }
    if (!boundCompanyCode.trim().equalsIgnoreCase(targetCompanyCode.trim())) {
      throw new AccessDeniedException("Bound company context does not match targeted tenant");
    }
  }

  private void synchronizeRuntimePolicyWithLifecycle(
      Company company,
      CompanyLifecycleState requestedState,
      String lifecycleReason,
      Authentication authentication) {
    if (company == null
        || tenantRuntimeEnforcementService == null
        || requestedState == null
        || !StringUtils.hasText(company.getCode())) {
      return;
    }
    tenantRuntimeEnforcementService.updatePolicy(
        company.getCode(),
        mapLifecycleToRuntimeState(requestedState),
        lifecycleReason,
        safeRuntimeLimit(company.getQuotaMaxConcurrentRequests()),
        safeRuntimeLimit(company.getQuotaMaxApiRequests()),
        safeRuntimeLimit(company.getQuotaMaxActiveUsers()),
        resolveActor(authentication));
  }

  private TenantRuntimeEnforcementService.TenantRuntimeState mapLifecycleToRuntimeState(
      CompanyLifecycleState lifecycleState) {
    return switch (lifecycleState == null ? CompanyLifecycleState.ACTIVE : lifecycleState) {
      case ACTIVE -> TenantRuntimeEnforcementService.TenantRuntimeState.ACTIVE;
      case SUSPENDED -> TenantRuntimeEnforcementService.TenantRuntimeState.HOLD;
      case DEACTIVATED -> TenantRuntimeEnforcementService.TenantRuntimeState.BLOCKED;
    };
  }

  private Integer safeRuntimeLimit(long value) {
    if (value <= 0L) {
      return null;
    }
    return value > Integer.MAX_VALUE ? Integer.MAX_VALUE : (int) value;
  }

  private String normalizeCompanyCode(String code) {
    if (!StringUtils.hasText(code)) {
      throw com.bigbrightpaints.erp.core.validation.ValidationUtils.invalidInput(
          "Company code is required");
    }
    return code.trim().toUpperCase(Locale.ROOT);
  }

  private void ensureCompanyCodeAvailableForCreate(String companyCode) {
    if (authScopeService != null && authScopeService.isPlatformScope(companyCode)) {
      throw com.bigbrightpaints.erp.core.validation.ValidationUtils.invalidInput(
          "Company code conflicts with platform auth code: " + companyCode);
    }
    repository
        .findByCodeIgnoreCase(companyCode)
        .ifPresent(
            existing -> {
              throw com.bigbrightpaints.erp.core.validation.ValidationUtils.invalidInput(
                  "Company code already exists: " + companyCode);
            });
  }

  private void ensureCompanyCodeAvailableForUpdate(Long companyId, String companyCode) {
    if (authScopeService != null && authScopeService.isPlatformScope(companyCode)) {
      throw com.bigbrightpaints.erp.core.validation.ValidationUtils.invalidInput(
          "Company code conflicts with platform auth code: " + companyCode);
    }
    repository
        .findByCodeIgnoreCase(companyCode)
        .ifPresent(
            existing -> {
              if (existing.getId() != null && !existing.getId().equals(companyId)) {
                throw com.bigbrightpaints.erp.core.validation.ValidationUtils.invalidInput(
                    "Company code already exists: " + companyCode);
              }
            });
  }

  private void synchronizeScopedAccountsToCompanyCode(
      Company company, String normalizedCompanyCode) {
    if (company == null
        || company.getId() == null
        || !StringUtils.hasText(normalizedCompanyCode)
        || userAccountRepository == null
        || normalizedCompanyCode.equalsIgnoreCase(company.getCode())) {
      return;
    }
    var companyUsers = userAccountRepository.findByCompany_Id(company.getId());
    for (var companyUser : companyUsers) {
      if (userAccountRepository.existsByEmailIgnoreCaseAndAuthScopeCodeIgnoreCaseAndIdNot(
          companyUser.getEmail(), normalizedCompanyCode, companyUser.getId())) {
        throw com.bigbrightpaints.erp.core.validation.ValidationUtils.invalidInput(
            "Scoped account already exists for email in company code: " + companyUser.getEmail());
      }
    }
    companyUsers.forEach(user -> user.setAuthScopeCode(normalizedCompanyCode));
    userAccountRepository.saveAll(companyUsers);
  }

  private BigDecimal resolveDefaultGstRateForCreate(BigDecimal requestedDefaultGstRate) {
    return requestedDefaultGstRate == null ? DEFAULT_BOOTSTRAP_GST_RATE : requestedDefaultGstRate;
  }

  private void provisionInitialAdminIfRequested(
      Company company, String firstAdminEmail, String firstAdminDisplayName) {
    if (!StringUtils.hasText(firstAdminEmail)) {
      return;
    }
    requireCredentialProvisioningReady();
    tenantAdminProvisioningService.provisionInitialAdmin(
        company, firstAdminEmail, firstAdminDisplayName);
  }

  private void requireCredentialProvisioningDependencies() {
    if (tenantAdminProvisioningService == null) {
      throw com.bigbrightpaints.erp.core.validation.ValidationUtils.invalidState(
          "Credential provisioning dependencies are not available");
    }
  }

  private void requireCredentialProvisioningReady() {
    requireCredentialProvisioningDependencies();
    if (!tenantAdminProvisioningService.isCredentialProvisioningReady()) {
      throw com.bigbrightpaints.erp.core.validation.ValidationUtils.invalidState(
          "Credential email delivery is disabled; enable erp.mail.enabled=true and"
              + " erp.mail.send-credentials=true");
    }
  }

  private void requirePasswordResetReady() {
    if (passwordResetService == null) {
      throw com.bigbrightpaints.erp.core.validation.ValidationUtils.invalidState(
          "Password reset dependencies are not available");
    }
    if (!passwordResetService.isResetEmailDeliveryEnabled()) {
      throw com.bigbrightpaints.erp.core.validation.ValidationUtils.invalidState(
          "Password reset email delivery is disabled; enable erp.mail.enabled=true and"
              + " erp.mail.send-password-reset=true");
    }
  }

  private TenantRuntimeEnforcementService.TenantRuntimeState parseRuntimeState(String holdState) {
    if (!StringUtils.hasText(holdState)) {
      return null;
    }
    String normalized = holdState.trim().toUpperCase(Locale.ROOT);
    return switch (normalized) {
      case "ACTIVE" -> TenantRuntimeEnforcementService.TenantRuntimeState.ACTIVE;
      case "HOLD" -> TenantRuntimeEnforcementService.TenantRuntimeState.HOLD;
      case "BLOCKED" -> TenantRuntimeEnforcementService.TenantRuntimeState.BLOCKED;
      default ->
          throw com.bigbrightpaints.erp.core.validation.ValidationUtils.invalidInput(
              "Unsupported runtime holdState: " + holdState);
    };
  }

  private boolean hasRuntimePolicyMutation(TenantRuntimePolicyMutationRequest request) {
    if (request == null) {
      return false;
    }
    return StringUtils.hasText(request.holdState())
        || request.maxConcurrentRequests() != null
        || request.maxRequestsPerMinute() != null
        || request.maxActiveUsers() != null;
  }

  private boolean hasAuthority(Authentication authentication, String authority) {
    if (authentication == null || !authentication.isAuthenticated()) {
      return false;
    }
    return authentication.getAuthorities().stream()
        .map(GrantedAuthority::getAuthority)
        .anyMatch(granted -> authority.equalsIgnoreCase(granted));
  }

  private String normalizeLifecycleReason(String reason) {
    if (!StringUtils.hasText(reason)) {
      throw com.bigbrightpaints.erp.core.validation.ValidationUtils.invalidInput(
          "Lifecycle reason is required");
    }
    return reason.trim();
  }

  private void persistLifecycleAuditEvidence(
      Company company,
      CompanyLifecycleState previousState,
      CompanyLifecycleState requestedState,
      String lifecycleReason,
      Authentication authentication) {
    if (auditService == null) {
      return;
    }
    HashMap<String, String> metadata = new HashMap<>();
    metadata.put("actor", resolveActor(authentication));
    metadata.put("reason", LIFECYCLE_UPDATED_REASON);
    metadata.put("tenantScope", resolveTenantScope(authentication));
    metadata.put("targetCompanyCode", company.getCode());
    metadata.put("targetCompanyId", String.valueOf(company.getId()));
    metadata.put(LIFECYCLE_PREVIOUS_STATE_METADATA_KEY, previousState.name());
    metadata.put(LIFECYCLE_STATE_METADATA_KEY, requestedState.name());
    metadata.put(LIFECYCLE_REASON_METADATA_KEY, lifecycleReason);
    metadata.put(LIFECYCLE_EVIDENCE_METADATA_KEY, LIFECYCLE_EVIDENCE_VALUE);
    auditService.logAuthSuccess(
        AuditEvent.CONFIGURATION_CHANGED,
        resolveActor(authentication),
        company.getCode(),
        metadata);
  }

  private void auditLifecycleDenied(
      Long companyId,
      String targetCompanyCode,
      CompanyLifecycleState requestedState,
      String lifecycleReason,
      Authentication authentication) {
    if (auditService == null) {
      return;
    }
    HashMap<String, String> metadata = new HashMap<>();
    metadata.put("actor", resolveActor(authentication));
    metadata.put("reason", LIFECYCLE_SUPER_ADMIN_REQUIRED_REASON);
    metadata.put("tenantScope", resolveTenantScope(authentication));
    if (companyId != null) {
      metadata.put("targetCompanyId", String.valueOf(companyId));
    }
    if (StringUtils.hasText(targetCompanyCode)) {
      metadata.put("targetCompanyCode", targetCompanyCode.trim());
    }
    metadata.put(LIFECYCLE_STATE_METADATA_KEY, requestedState.name());
    metadata.put(LIFECYCLE_REASON_METADATA_KEY, lifecycleReason);
    metadata.put(LIFECYCLE_EVIDENCE_METADATA_KEY, LIFECYCLE_EVIDENCE_VALUE);
    auditService.logAuthFailure(
        AuditEvent.ACCESS_DENIED, resolveActor(authentication), targetCompanyCode, metadata);
  }

  private void auditAuthorityDecision(
      boolean granted, String reason, String targetCompanyCode, Authentication authentication) {
    if (auditService == null) {
      return;
    }
    HashMap<String, String> metadata = new HashMap<>();
    metadata.put("actor", resolveActor(authentication));
    metadata.put("reason", reason);
    metadata.put("tenantScope", resolveTenantScope(authentication));
    if (StringUtils.hasText(targetCompanyCode)) {
      metadata.put("targetCompanyCode", targetCompanyCode.trim());
    }
    if (granted) {
      auditService.logSuccess(AuditEvent.ACCESS_GRANTED, metadata);
    } else {
      auditService.logFailure(AuditEvent.ACCESS_DENIED, metadata);
    }
  }

  private String resolveActor(Authentication authentication) {
    if (authentication == null) {
      return "anonymous";
    }
    String name = authentication.getName();
    return StringUtils.hasText(name) ? name.trim() : "anonymous";
  }

  private String resolveTenantScope(Authentication authentication) {
    if (authentication != null
        && authentication.getPrincipal() instanceof UserPrincipal principal
        && principal.getUser() != null
        && principal.getUser().getCompany() != null
        && StringUtils.hasText(principal.getUser().getCompany().getCode())) {
      return principal.getUser().getCompany().getCode().trim();
    }
    return "none";
  }

  private long countActiveUsers(Long companyId) {
    if (userAccountRepository == null) {
      return 0L;
    }
    return userAccountRepository.countByCompany_IdAndEnabledTrue(companyId);
  }

  private long countApiActivity(Long companyId) {
    if (auditLogRepository == null || companyId == null) {
      return 0L;
    }
    return auditLogRepository.countApiActivityByCompanyId(companyId);
  }

  private long countApiFailureActivity(Long companyId) {
    if (auditLogRepository == null || companyId == null) {
      return 0L;
    }
    return auditLogRepository.countApiFailureActivityByCompanyId(companyId);
  }

  private long countDistinctSessionActivity(Long companyId) {
    if (auditLogRepository == null || companyId == null) {
      return 0L;
    }
    return auditLogRepository.countDistinctSessionActivityByCompanyId(companyId);
  }

  private long estimateAuditStorageBytes(Long companyId) {
    if (auditLogRepository == null || companyId == null) {
      return 0L;
    }
    return auditLogRepository.estimateAuditStorageBytesByCompanyId(companyId);
  }

  private long calculateErrorRateInBasisPoints(long apiActivityCount, long apiErrorCount) {
    if (apiActivityCount <= 0L || apiErrorCount <= 0L) {
      return 0L;
    }
    long boundedErrorCount = Math.min(apiErrorCount, apiActivityCount);
    return (boundedErrorCount * ERROR_RATE_BASIS_POINTS_SCALE) / apiActivityCount;
  }

  private long calculateUtilizationInBasisPoints(long used, long quota) {
    if (quota <= 0L || used <= 0L) {
      return 0L;
    }
    long boundedUsed = Math.min(used, quota);
    return (boundedUsed * ERROR_RATE_BASIS_POINTS_SCALE) / quota;
  }

  private String normalizeWarningCategory(String warningCategory) {
    if (!StringUtils.hasText(warningCategory)) {
      return "GENERAL";
    }
    return warningCategory.trim().toUpperCase(Locale.ROOT);
  }

  private String normalizeWarningLifecycleState(String requestedLifecycleState) {
    if (!StringUtils.hasText(requestedLifecycleState)) {
      return "SUSPENDED";
    }
    String normalized = requestedLifecycleState.trim().toUpperCase(Locale.ROOT);
    if ("HOLD".equals(normalized)) {
      return "SUSPENDED";
    }
    if ("BLOCKED".equals(normalized)) {
      return "DEACTIVATED";
    }
    if ("SUSPENDED".equals(normalized) || "DEACTIVATED".equals(normalized)) {
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

  private long resolveQuotaForCreate(Long requestedQuota) {
    return requestedQuota == null ? 0L : requestedQuota;
  }

  private long resolveQuotaForUpdate(Long requestedQuota, long existingQuota) {
    return requestedQuota == null ? existingQuota : requestedQuota;
  }

  private Set<String> validateAndNormalizeEnabledModules(Set<String> requestedModules) {
    LinkedHashSet<String> normalized = new LinkedHashSet<>();
    List<String> unknownModules = new ArrayList<>();
    List<String> coreModules = new ArrayList<>();
    for (String requestedModule : requestedModules) {
      if (!StringUtils.hasText(requestedModule)) {
        unknownModules.add(String.valueOf(requestedModule));
        continue;
      }
      CompanyModule module = CompanyModule.fromValue(requestedModule).orElse(null);
      if (module == null) {
        unknownModules.add(requestedModule.trim());
        continue;
      }
      if (module.isCore()) {
        coreModules.add(module.name());
        continue;
      }
      normalized.add(module.name());
    }

    if (!unknownModules.isEmpty()) {
      throw com.bigbrightpaints.erp.core.validation.ValidationUtils.invalidInput(
          "enabledModules contains unknown modules: " + unknownModules);
    }
    if (!coreModules.isEmpty()) {
      throw com.bigbrightpaints.erp.core.validation.ValidationUtils.invalidInput(
          "enabledModules can only include gatable modules: " + coreModules);
    }
    return normalized;
  }

  private boolean resolveQuotaFlagForCreate(Boolean requestedFlag, boolean defaultValue) {
    return requestedFlag == null ? defaultValue : requestedFlag;
  }

  private boolean resolveQuotaFlagForUpdate(Boolean requestedFlag, boolean existingFlag) {
    return requestedFlag == null ? existingFlag : requestedFlag;
  }

  private boolean hasConfiguredHardQuotaEnvelope(Company company) {
    return company.getQuotaMaxActiveUsers() > 0L
        && company.getQuotaMaxApiRequests() > 0L
        && company.getQuotaMaxStorageBytes() > 0L
        && company.getQuotaMaxConcurrentRequests() > 0L;
  }

  private long resolveCurrentConcurrentRequests(String companyCode, Long companyId) {
    if (tenantRuntimeEnforcementService != null && StringUtils.hasText(companyCode)) {
      try {
        return tenantRuntimeEnforcementService.snapshot(companyCode).metrics().inFlightRequests();
      } catch (RuntimeException ignored) {
        // Fall back to the best available telemetry path when runtime snapshot resolution fails.
      }
    }
    return countDistinctSessionActivity(companyId);
  }

  private boolean hasRuntimeQuotaTelemetryDependencies() {
    return userAccountRepository != null && auditLogRepository != null;
  }

  private long resolveRuntimeMetricFailClosed(LongSupplier metricSupplier) {
    try {
      return metricSupplier.getAsLong();
    } catch (RuntimeException ex) {
      return -1L;
    }
  }

  private boolean isRuntimeMetricUnavailable(long metricValue) {
    return metricValue < 0L;
  }

  private boolean isQuotaExceeded(long observedValue, long quotaLimit) {
    return quotaLimit > 0L && observedValue > quotaLimit;
  }

  public record TenantRuntimePolicyMutationRequest(
      String holdState,
      String reasonCode,
      Integer maxConcurrentRequests,
      Integer maxRequestsPerMinute,
      Integer maxActiveUsers) {}

  public record TenantSupportWarningRequest(
      String warningCategory,
      String message,
      String requestedLifecycleState,
      Integer gracePeriodHours) {}
}
