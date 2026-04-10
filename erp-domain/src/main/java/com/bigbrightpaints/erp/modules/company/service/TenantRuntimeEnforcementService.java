package com.bigbrightpaints.erp.modules.company.service;

import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Supplier;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;
import org.springframework.web.server.ResponseStatusException;

import com.bigbrightpaints.erp.core.audit.AuditEvent;
import com.bigbrightpaints.erp.core.audit.AuditService;
import com.bigbrightpaints.erp.core.config.SystemSetting;
import com.bigbrightpaints.erp.core.config.SystemSettingsRepository;
import com.bigbrightpaints.erp.core.exception.ApplicationException;
import com.bigbrightpaints.erp.core.exception.AuthSecurityContractException;
import com.bigbrightpaints.erp.core.exception.ErrorCode;
import com.bigbrightpaints.erp.core.idempotency.IdempotencyUtils;
import com.bigbrightpaints.erp.core.util.CompanyTime;
import com.bigbrightpaints.erp.modules.auth.domain.UserAccountRepository;
import com.bigbrightpaints.erp.modules.company.domain.Company;
import com.bigbrightpaints.erp.modules.company.domain.CompanyRepository;

/**
 * Canonical tenant runtime policy-mutation and snapshot owner.
 *
 * <p>Request admission now enters through {@link TenantRuntimeRequestAdmissionService} so runtime
 * filters, interceptors, and auth flows do not bind directly to this policy service.
 */
@Service
public class TenantRuntimeEnforcementService {

  private static final int MIN_LIMIT = 1;
  private static final String DEFAULT_REASON = "POLICY_ACTIVE";
  private static final String DEFAULT_POLICY_REFERENCE = "bootstrap";
  private static final String UNKNOWN_ACTOR = "UNKNOWN_AUTH_ACTOR";
  private static final String CANONICAL_SUPERADMIN_TENANT_LIMITS_PREFIX =
      "/api/v1/superadmin/tenants/";
  private static final String CANONICAL_SUPERADMIN_TENANT_LIFECYCLE_SUFFIX = "/lifecycle";
  private static final String CANONICAL_SUPERADMIN_TENANT_LIMITS_SUFFIX = "/limits";
  private final CompanyRepository companyRepository;
  private final SystemSettingsRepository systemSettingsRepository;
  private final UserAccountRepository userAccountRepository;
  private final AuditService auditService;
  private final int defaultMaxConcurrentRequests;
  private final ConcurrentMap<String, Object> policyMutationLocks = new ConcurrentHashMap<>();
  private final int defaultMaxRequestsPerMinute;
  private final int defaultMaxActiveUsers;
  private final long persistedPolicyCacheTtlMillis;
  private final ConcurrentMap<String, TenantRuntimePolicy> policies = new ConcurrentHashMap<>();
  private final ConcurrentMap<String, TenantRuntimeCounters> counters = new ConcurrentHashMap<>();

  public TenantRuntimeEnforcementService(
      CompanyRepository companyRepository,
      SystemSettingsRepository systemSettingsRepository,
      UserAccountRepository userAccountRepository,
      AuditService auditService,
      @Value("${erp.tenant.runtime.default-max-concurrent-requests:200}")
          int defaultMaxConcurrentRequests,
      @Value("${erp.tenant.runtime.default-max-requests-per-minute:5000}")
          int defaultMaxRequestsPerMinute,
      @Value("${erp.tenant.runtime.default-max-active-users:500}") int defaultMaxActiveUsers,
      @Value("${erp.tenant.runtime.policy-cache-seconds:15}") long policyCacheSeconds) {
    this.companyRepository = companyRepository;
    this.systemSettingsRepository = systemSettingsRepository;
    this.userAccountRepository = userAccountRepository;
    this.auditService = auditService;
    this.defaultMaxConcurrentRequests = sanitizeLimit(defaultMaxConcurrentRequests);
    this.defaultMaxRequestsPerMinute = sanitizeLimit(defaultMaxRequestsPerMinute);
    this.defaultMaxActiveUsers = sanitizeLimit(defaultMaxActiveUsers);
    this.persistedPolicyCacheTtlMillis = Math.max(1L, policyCacheSeconds) * 1000L;
  }

  TenantRequestAdmission admitRequest(
      String companyCode, String requestPath, String requestMethod, String actor) {
    return admitRequest(companyCode, requestPath, requestMethod, actor, false);
  }

  TenantRequestAdmission admitRequest(
      String companyCode,
      String requestPath,
      String requestMethod,
      String actor,
      boolean policyControlPrivilegedActor) {
    String normalizedCompany = normalizeCompanyCode(companyCode);
    if (normalizedCompany == null) {
      return TenantRequestAdmission.notTracked();
    }
    TenantRuntimeCounters usageCounters = countersFor(normalizedCompany);
    boolean policyControlRequest =
        isTenantRuntimePolicyControlRequest(
            requestPath, requestMethod, policyControlPrivilegedActor);
    if (policyControlRequest) {
      usageCounters.totalRequests.incrementAndGet();
      usageCounters.inFlightRequests.incrementAndGet();
      return TenantRequestAdmission.admittedPolicyControl(
          normalizedCompany,
          currentAuditChainId(normalizedCompany).orElse(DEFAULT_POLICY_REFERENCE),
          usageCounters);
    }
    TenantRuntimePolicy policy;
    try {
      policy = policyFor(normalizedCompany);
    } catch (TenantRuntimeAdmissionFailure failure) {
      incrementRejectedCount(usageCounters);
      auditRejection(failure.rejection(), actor, requestPath, requestMethod);
      return TenantRequestAdmission.rejected(failure.rejection());
    }

    TenantRuntimeRejection stateRejection =
        stateRejection(policy, normalizedCompany, policyControlRequest, requestMethod);
    if (stateRejection != null) {
      incrementRejectedCount(usageCounters);
      auditRejection(stateRejection, actor, requestPath, requestMethod);
      return TenantRequestAdmission.rejected(stateRejection);
    }

    long minuteBucket = CompanyTime.now().getEpochSecond() / 60L;
    int requestsInMinute = incrementMinuteCount(usageCounters, minuteBucket);
    int maxRequestsPerMinute = policy.effectiveMaxRequestsPerMinute(defaultMaxRequestsPerMinute);
    if (requestsInMinute > maxRequestsPerMinute) {
      incrementRejectedCount(usageCounters);
      TenantRuntimeRejection rejection =
          new TenantRuntimeRejection(
              normalizedCompany,
              policy.state,
              policy.reasonCode,
              policy.auditChainId,
              HttpStatus.TOO_MANY_REQUESTS,
              "TENANT_REQUEST_RATE_EXCEEDED",
              "Tenant request rate quota exceeded",
              "MAX_REQUESTS_PER_MINUTE",
              Integer.toString(requestsInMinute),
              Integer.toString(maxRequestsPerMinute));
      auditRejection(rejection, actor, requestPath, requestMethod);
      return TenantRequestAdmission.rejected(rejection);
    }

    int inFlightNow = usageCounters.inFlightRequests.incrementAndGet();
    int maxConcurrentRequests = policy.effectiveMaxConcurrentRequests(defaultMaxConcurrentRequests);
    if (inFlightNow > maxConcurrentRequests) {
      usageCounters.inFlightRequests.decrementAndGet();
      incrementRejectedCount(usageCounters);
      TenantRuntimeRejection rejection =
          new TenantRuntimeRejection(
              normalizedCompany,
              policy.state,
              policy.reasonCode,
              policy.auditChainId,
              HttpStatus.TOO_MANY_REQUESTS,
              "TENANT_CONCURRENCY_EXCEEDED",
              "Tenant concurrency quota exceeded",
              "MAX_CONCURRENT_REQUESTS",
              Integer.toString(inFlightNow),
              Integer.toString(maxConcurrentRequests));
      auditRejection(rejection, actor, requestPath, requestMethod);
      return TenantRequestAdmission.rejected(rejection);
    }

    usageCounters.totalRequests.incrementAndGet();
    return TenantRequestAdmission.admitted(normalizedCompany, policy.auditChainId, usageCounters);
  }

  void completeRequestAdmission(TenantRequestAdmission admission, int responseStatus) {
    if (admission == null || !admission.isAdmitted()) {
      return;
    }
    admission.counters().inFlightRequests.decrementAndGet();
    if (responseStatus >= 400) {
      admission.counters().errorResponses.incrementAndGet();
    }
    if (admission.isPolicyControlRequest() && responseStatus < 400) {
      invalidatePolicyCache(admission.companyCode());
    }
  }

  void enforceAuthOperation(String companyCode, String actor, String operation) {
    String normalizedCompany = requireCompanyCode(companyCode);
    String scope = "auth_" + normalizeUpperToken(operation, "UNKNOWN");
    TenantRuntimeCounters usageCounters = countersFor(normalizedCompany);
    TenantRuntimePolicy policy;
    try {
      policy = policyFor(normalizedCompany);
    } catch (TenantRuntimeAdmissionFailure failure) {
      incrementRejectedCount(usageCounters);
      auditRejection(failure.rejection(), actor, scope, "POST");
      throw failure.rejection().asAuthSecurityContractException();
    }

    TenantRuntimeRejection stateRejection =
        stateRejection(policy, normalizedCompany, false, "POST");
    if (stateRejection != null) {
      incrementRejectedCount(usageCounters);
      auditRejection(stateRejection, actor, scope, "POST");
      throw stateRejection.asAuthSecurityContractException();
    }

    Optional<Company> companyLookup;
    try {
      companyLookup = companyRepository.findByCodeIgnoreCase(normalizedCompany);
    } catch (RuntimeException ex) {
      TenantRuntimeRejection rejection =
          unavailableRejection(
              normalizedCompany,
              "TENANT_COMPANY_LOOKUP_UNAVAILABLE",
              "Tenant company lookup is unavailable");
      incrementRejectedCount(usageCounters);
      auditRejection(rejection, actor, scope, "POST");
      throw rejection.asAuthSecurityContractException();
    }
    Company company = companyLookup.orElse(null);
    if (company == null || company.getId() == null) {
      TenantRuntimeRejection rejection = tenantNotFoundRejection(normalizedCompany);
      incrementRejectedCount(usageCounters);
      auditRejection(rejection, actor, scope, "POST");
      throw rejection.asAuthSecurityContractException();
    }
    long activeUsers;
    try {
      activeUsers = userAccountRepository.countByCompany_IdAndEnabledTrue(company.getId());
    } catch (RuntimeException ex) {
      TenantRuntimeRejection rejection =
          unavailableRejection(
              normalizedCompany,
              "TENANT_ACTIVE_USER_QUOTA_UNAVAILABLE",
              "Tenant active-user quota is unavailable");
      incrementRejectedCount(usageCounters);
      auditRejection(rejection, actor, scope, "POST");
      throw rejection.asAuthSecurityContractException();
    }
    int maxActiveUsers = policy.effectiveMaxActiveUsers(defaultMaxActiveUsers);
    if (activeUsers > maxActiveUsers) {
      incrementRejectedCount(usageCounters);
      TenantRuntimeRejection rejection =
          new TenantRuntimeRejection(
              normalizedCompany,
              policy.state,
              policy.reasonCode,
              policy.auditChainId,
              HttpStatus.TOO_MANY_REQUESTS,
              "TENANT_ACTIVE_USER_QUOTA_EXCEEDED",
              "Tenant active-user quota exceeded",
              "MAX_ACTIVE_USERS",
              Long.toString(activeUsers),
              Integer.toString(maxActiveUsers));
      auditRejection(rejection, actor, scope, null);
      throw rejection.asAuthSecurityContractException();
    }
  }

  public TenantRuntimeSnapshot holdTenant(String companyCode, String reasonCode, String actor) {
    return updateState(companyCode, TenantRuntimeState.HOLD, reasonCode, actor, "HOLD_TENANT");
  }

  public TenantRuntimeSnapshot blockTenant(String companyCode, String reasonCode, String actor) {
    return updateState(companyCode, TenantRuntimeState.BLOCKED, reasonCode, actor, "BLOCK_TENANT");
  }

  public TenantRuntimeSnapshot resumeTenant(String companyCode, String actor) {
    return updateState(
        companyCode, TenantRuntimeState.ACTIVE, DEFAULT_REASON, actor, "RESUME_TENANT");
  }

  public TenantRuntimeSnapshot updateQuotas(
      String companyCode,
      Integer maxConcurrentRequests,
      Integer maxRequestsPerMinute,
      Integer maxActiveUsers,
      String reasonCode,
      String actor) {
    String normalizedCompany = requireCompanyCode(companyCode);
    return withPolicyMutationLock(
        normalizedCompany,
        () -> {
          Company company = requireTrackedCompany(normalizedCompany);
          TenantRuntimePolicy current =
              persistedPolicyForManagedOperation(normalizedCompany, company);
          String normalizedReason = normalizeReason(reasonCode);
          String previousChainId = current.auditChainId;
          String newChainId = UUID.randomUUID().toString();
          Instant now = CompanyTime.now();
          TenantRuntimePolicy updated = copyPolicy(current);
          if (maxConcurrentRequests != null) {
            updated.maxConcurrentRequests = sanitizeLimit(maxConcurrentRequests);
          }
          if (maxRequestsPerMinute != null) {
            updated.maxRequestsPerMinute = sanitizeLimit(maxRequestsPerMinute);
          }
          if (maxActiveUsers != null) {
            updated.maxActiveUsers = sanitizeLimit(maxActiveUsers);
          }
          updated.reasonCode = normalizedReason;
          updated.updatedAt = now;
          updated.auditChainId = newChainId;
          updated.policyRefreshAfterEpochMillis =
              System.currentTimeMillis() + persistedPolicyCacheTtlMillis;
          persistPolicyAndAuditChange(
              company,
              normalizedCompany,
              "UPDATE_TENANT_QUOTAS",
              normalizeActor(actor),
              normalizedReason,
              previousChainId,
              updated);
          activatePolicy(normalizedCompany, updated);
          return snapshot(normalizedCompany);
        });
  }

  public TenantRuntimeSnapshot updatePolicy(
      String companyCode,
      TenantRuntimeState targetState,
      String reasonCode,
      Integer maxConcurrentRequests,
      Integer maxRequestsPerMinute,
      Integer maxActiveUsers,
      String actor) {
    String normalizedCompany = requireCompanyCode(companyCode);
    return withPolicyMutationLock(
        normalizedCompany,
        () -> {
          Company company = requireTrackedCompany(normalizedCompany);
          if (targetState == null
              && maxConcurrentRequests == null
              && maxRequestsPerMinute == null
              && maxActiveUsers == null) {
            throw com.bigbrightpaints.erp.core.validation.ValidationUtils.invalidInput(
                "Runtime policy mutation payload is required");
          }
          TenantRuntimePolicy current =
              persistedPolicyForManagedOperation(normalizedCompany, company);
          String normalizedReason = normalizeReason(reasonCode);
          String previousChainId = current.auditChainId;
          String newChainId = UUID.randomUUID().toString();
          Instant now = CompanyTime.now();
          TenantRuntimePolicy updated = copyPolicy(current);
          if (targetState != null) {
            updated.state = targetState;
          }
          if (maxConcurrentRequests != null) {
            updated.maxConcurrentRequests = sanitizeLimit(maxConcurrentRequests);
          }
          if (maxRequestsPerMinute != null) {
            updated.maxRequestsPerMinute = sanitizeLimit(maxRequestsPerMinute);
          }
          if (maxActiveUsers != null) {
            updated.maxActiveUsers = sanitizeLimit(maxActiveUsers);
          }
          updated.reasonCode = normalizedReason;
          updated.updatedAt = now;
          updated.auditChainId = newChainId;
          updated.policyRefreshAfterEpochMillis =
              System.currentTimeMillis() + persistedPolicyCacheTtlMillis;
          persistPolicyAndAuditChange(
              company,
              normalizedCompany,
              "UPDATE_TENANT_RUNTIME_POLICY",
              normalizeActor(actor),
              normalizedReason,
              previousChainId,
              updated);
          activatePolicy(normalizedCompany, updated);
          return snapshot(normalizedCompany);
        });
  }

  public TenantRuntimeSnapshot snapshot(String companyCode) {
    String normalizedCompany = requireCompanyCode(companyCode);
    TenantRuntimePolicy policy = policyForManagedOperation(normalizedCompany);
    TenantRuntimeCounters usageCounters = countersFor(normalizedCompany);
    Long activeUsers = resolveActiveUsers(normalizedCompany);
    return new TenantRuntimeSnapshot(
        normalizedCompany,
        policy.state,
        policy.reasonCode,
        policy.auditChainId,
        policy.updatedAt,
        policy.effectiveMaxConcurrentRequests(defaultMaxConcurrentRequests),
        policy.effectiveMaxRequestsPerMinute(defaultMaxRequestsPerMinute),
        policy.effectiveMaxActiveUsers(defaultMaxActiveUsers),
        new TenantRuntimeMetrics(
            usageCounters.totalRequests.get(),
            usageCounters.rejectedRequests.get(),
            usageCounters.errorResponses.get(),
            usageCounters.inFlightRequests.get(),
            usageCounters.minuteRequestCount.get(),
            usageCounters.minuteRejectedCount.get(),
            activeUsers));
  }

  public void invalidatePolicyCache(String companyCode) {
    String normalizedCompany = normalizeCompanyCode(companyCode);
    if (normalizedCompany == null) {
      return;
    }
    policies.remove(normalizedCompany);
  }

  private void persistPolicy(Company company, TenantRuntimePolicy policy) {
    if (company == null || company.getId() == null || policy == null) {
      return;
    }
    policyToPersistedState(company.getId(), policy).forEach(this::persistSetting);
  }

  private void persistPolicyAndAuditChange(
      Company company,
      String companyCode,
      String action,
      String actor,
      String reasonCode,
      String previousChainId,
      TenantRuntimePolicy policy) {
    Map<String, String> persistedPolicyState =
        capturePersistedPolicyState(companyCode, company == null ? null : company.getId());
    try {
      persistPolicy(company, policy);
      auditPolicyChange(
          action, companyCode, actor, reasonCode, previousChainId, policy.auditChainId, policy);
    } catch (RuntimeException ex) {
      restorePersistedPolicyState(company, companyCode, policy, persistedPolicyState, ex);
      throw ex;
    }
  }

  private TenantRuntimeSnapshot updateState(
      String companyCode,
      TenantRuntimeState targetState,
      String reasonCode,
      String actor,
      String action) {
    String normalizedCompany = requireCompanyCode(companyCode);
    return withPolicyMutationLock(
        normalizedCompany,
        () -> {
          Company company = requireTrackedCompany(normalizedCompany);
          TenantRuntimePolicy current =
              persistedPolicyForManagedOperation(normalizedCompany, company);
          String normalizedReason = normalizeReason(reasonCode);
          String previousChainId = current.auditChainId;
          String newChainId = UUID.randomUUID().toString();
          Instant now = CompanyTime.now();
          TenantRuntimePolicy updated = copyPolicy(current);
          updated.state = targetState;
          updated.reasonCode = normalizedReason;
          updated.auditChainId = newChainId;
          updated.updatedAt = now;
          updated.policyRefreshAfterEpochMillis =
              System.currentTimeMillis() + persistedPolicyCacheTtlMillis;
          persistPolicyAndAuditChange(
              company,
              normalizedCompany,
              action,
              normalizeActor(actor),
              normalizedReason,
              previousChainId,
              updated);
          activatePolicy(normalizedCompany, updated);
          return snapshot(normalizedCompany);
        });
  }

  private Map<String, String> capturePersistedPolicyState(String companyCode, Long companyId) {
    Map<String, String> persistedPolicyState = new HashMap<>();
    if (companyId == null) {
      return persistedPolicyState;
    }
    persistedPolicyState.put(
        keyHoldState(companyId), readPersistedPolicySetting(companyCode, keyHoldState(companyId)));
    persistedPolicyState.put(
        keyHoldReason(companyId),
        readPersistedPolicySetting(companyCode, keyHoldReason(companyId)));
    persistedPolicyState.put(
        keyMaxConcurrentRequests(companyId),
        readPersistedPolicySetting(companyCode, keyMaxConcurrentRequests(companyId)));
    persistedPolicyState.put(
        keyMaxRequestsPerMinute(companyId),
        readPersistedPolicySetting(companyCode, keyMaxRequestsPerMinute(companyId)));
    persistedPolicyState.put(
        keyMaxActiveUsers(companyId),
        readPersistedPolicySetting(companyCode, keyMaxActiveUsers(companyId)));
    persistedPolicyState.put(
        keyPolicyReference(companyId),
        readPersistedPolicySetting(companyCode, keyPolicyReference(companyId)));
    persistedPolicyState.put(
        keyPolicyUpdatedAt(companyId),
        readPersistedPolicySetting(companyCode, keyPolicyUpdatedAt(companyId)));
    return persistedPolicyState;
  }

  private String readPersistedPolicySetting(String companyCode, String key) {
    try {
      return systemSettingsRepository.findById(key).map(SystemSetting::getValue).orElse(null);
    } catch (RuntimeException ex) {
      throw toManagedOperationException(
          companyCode,
          new TenantRuntimeAdmissionFailure(
              unavailableRejection(
                  companyCode,
                  "TENANT_RUNTIME_POLICY_UNAVAILABLE",
                  "Tenant runtime policy is unavailable"),
              ex));
    }
  }

  private void restorePersistedPolicyState(
      Company company,
      String companyCode,
      TenantRuntimePolicy attemptedPolicy,
      Map<String, String> persistedPolicyState,
      RuntimeException originalFailure) {
    try {
      Long companyId = company == null ? null : company.getId();
      Map<String, String> latestPersistedPolicyState =
          companyId == null ? Map.of() : capturePersistedPolicyState(companyCode, companyId);
      if (shouldPreserveLatestPersistedState(
          companyId, persistedPolicyState, latestPersistedPolicyState, attemptedPolicy)) {
        activatePolicy(
            companyCode,
            Optional.ofNullable(policyFromPersistedState(companyId, latestPersistedPolicyState))
                .orElseGet(this::defaultPolicy));
        return;
      }
      for (Map.Entry<String, String> persistedSetting : persistedPolicyState.entrySet()) {
        restorePersistedSetting(persistedSetting.getKey(), persistedSetting.getValue());
      }
      activatePolicy(
          companyCode,
          companyId == null
              ? defaultPolicy()
              : Optional.ofNullable(policyFromPersistedState(companyId, persistedPolicyState))
                  .orElseGet(this::defaultPolicy));
    } catch (RuntimeException restoreFailure) {
      originalFailure.addSuppressed(restoreFailure);
    }
  }

  private void restorePersistedSetting(String key, String value) {
    if (!StringUtils.hasText(key)) {
      return;
    }
    if (value == null) {
      systemSettingsRepository.deleteById(key);
      return;
    }
    systemSettingsRepository.save(new SystemSetting(key, value));
  }

  private boolean shouldPreserveLatestPersistedState(
      Long companyId,
      Map<String, String> persistedPolicyState,
      Map<String, String> latestPersistedPolicyState,
      TenantRuntimePolicy attemptedPolicy) {
    if (companyId == null || attemptedPolicy == null || latestPersistedPolicyState == null) {
      return false;
    }
    Map<String, String> attemptedPersistedState =
        policyToPersistedState(companyId, attemptedPolicy);
    if (hasConcurrentPolicyVersionChange(
        companyId, persistedPolicyState, latestPersistedPolicyState, attemptedPersistedState)) {
      return true;
    }
    for (Map.Entry<String, String> persistedSetting : persistedPolicyState.entrySet()) {
      String key = persistedSetting.getKey();
      if (isConcurrentPersistedPolicyChange(
          key, persistedPolicyState, latestPersistedPolicyState, attemptedPersistedState)) {
        return true;
      }
    }
    return false;
  }

  private boolean hasConcurrentPolicyVersionChange(
      Long companyId,
      Map<String, String> persistedPolicyState,
      Map<String, String> latestPersistedPolicyState,
      Map<String, String> attemptedPersistedState) {
    return isConcurrentPersistedPolicyChange(
            keyPolicyReference(companyId),
            persistedPolicyState,
            latestPersistedPolicyState,
            attemptedPersistedState)
        || isConcurrentPersistedPolicyChange(
            keyPolicyUpdatedAt(companyId),
            persistedPolicyState,
            latestPersistedPolicyState,
            attemptedPersistedState);
  }

  private boolean isConcurrentPersistedPolicyChange(
      String key,
      Map<String, String> persistedPolicyState,
      Map<String, String> latestPersistedPolicyState,
      Map<String, String> attemptedPersistedState) {
    String latestValue = latestPersistedPolicyState.get(key);
    String previousValue = persistedPolicyState.get(key);
    String attemptedValue = attemptedPersistedState.get(key);
    return !Objects.equals(latestValue, previousValue)
        && !Objects.equals(latestValue, attemptedValue);
  }

  private TenantRuntimePolicy policyFromPersistedState(
      Long companyId, Map<String, String> persistedPolicyState) {
    if (persistedPolicyState == null || persistedPolicyState.isEmpty()) {
      return null;
    }
    String persistedState = persistedPolicyState.get(keyHoldState(companyId));
    String persistedReason = persistedPolicyState.get(keyHoldReason(companyId));
    String persistedMaxConcurrent = persistedPolicyState.get(keyMaxConcurrentRequests(companyId));
    String persistedMaxPerMinute = persistedPolicyState.get(keyMaxRequestsPerMinute(companyId));
    String persistedMaxActiveUsers = persistedPolicyState.get(keyMaxActiveUsers(companyId));
    String persistedPolicyReference = persistedPolicyState.get(keyPolicyReference(companyId));
    String persistedUpdatedAt = persistedPolicyState.get(keyPolicyUpdatedAt(companyId));

    boolean hasPersistedPolicy =
        persistedState != null
            || persistedReason != null
            || persistedMaxConcurrent != null
            || persistedMaxPerMinute != null
            || persistedMaxActiveUsers != null
            || persistedPolicyReference != null
            || persistedUpdatedAt != null;
    if (!hasPersistedPolicy) {
      return null;
    }

    String normalizedReason = persistedReason == null ? null : persistedReason.trim();
    if (!StringUtils.hasText(normalizedReason)) {
      normalizedReason = DEFAULT_REASON;
    }
    String normalizedPolicyReference =
        persistedPolicyReference == null ? null : persistedPolicyReference.trim();
    if (!StringUtils.hasText(normalizedPolicyReference)) {
      normalizedPolicyReference = DEFAULT_POLICY_REFERENCE;
    }

    return new TenantRuntimePolicy(
        normalizeState(persistedState),
        normalizedReason,
        parsePositiveInt(persistedMaxConcurrent, defaultMaxConcurrentRequests),
        parsePositiveInt(persistedMaxPerMinute, defaultMaxRequestsPerMinute),
        parsePositiveInt(persistedMaxActiveUsers, defaultMaxActiveUsers),
        normalizedPolicyReference,
        parseInstantOrNull(persistedUpdatedAt),
        0L);
  }

  private Map<String, String> policyToPersistedState(Long companyId, TenantRuntimePolicy policy) {
    Map<String, String> persistedState = new HashMap<>();
    if (companyId == null || policy == null) {
      return persistedState;
    }
    persistedState.put(keyHoldState(companyId), safePolicyStateName(policy));
    persistedState.put(keyHoldReason(companyId), safePolicyReasonCode(policy));
    persistedState.put(
        keyMaxConcurrentRequests(companyId), Integer.toString(policy.maxConcurrentRequests));
    persistedState.put(
        keyMaxRequestsPerMinute(companyId), Integer.toString(policy.maxRequestsPerMinute));
    persistedState.put(keyMaxActiveUsers(companyId), Integer.toString(policy.maxActiveUsers));
    persistedState.put(keyPolicyReference(companyId), safePolicyAuditChainId(policy));
    persistedState.put(
        keyPolicyUpdatedAt(companyId),
        policy.updatedAt == null ? null : policy.updatedAt.toString());
    return persistedState;
  }

  private void auditPolicyChange(
      String action,
      String companyCode,
      String actor,
      String reasonCode,
      String previousChainId,
      String chainId,
      TenantRuntimePolicy policy) {
    Map<String, String> metadata = new HashMap<>();
    metadata.put("scope", "tenant_runtime_policy");
    metadata.put("action", action);
    metadata.put("reasonCode", reasonCode);
    metadata.put("auditChainId", chainId);
    metadata.put("previousAuditChainId", previousChainId);
    metadata.put("tenantState", safePolicyStateName(policy));
    metadata.put(
        "maxConcurrentRequests",
        Integer.toString(policy.effectiveMaxConcurrentRequests(defaultMaxConcurrentRequests)));
    metadata.put(
        "maxRequestsPerMinute",
        Integer.toString(policy.effectiveMaxRequestsPerMinute(defaultMaxRequestsPerMinute)));
    metadata.put(
        "maxActiveUsers", Integer.toString(policy.effectiveMaxActiveUsers(defaultMaxActiveUsers)));
    auditService.logAuthSuccess(AuditEvent.CONFIGURATION_CHANGED, actor, companyCode, metadata);
  }

  private TenantRuntimeRejection stateRejection(
      TenantRuntimePolicy policy,
      String companyCode,
      boolean policyControlRequest,
      String requestMethod) {
    if (policyControlRequest) {
      return null;
    }
    TenantRuntimeState policyState = safePolicyState(policy);
    String policyReasonCode = safePolicyReasonCode(policy);
    String auditChainId = safePolicyAuditChainId(policy);
    if (policyState == TenantRuntimeState.HOLD && isMutatingRequest(requestMethod)) {
      return new TenantRuntimeRejection(
          companyCode,
          policyState,
          policyReasonCode,
          auditChainId,
          HttpStatus.LOCKED,
          "TENANT_ON_HOLD",
          "Tenant is currently on hold",
          "TENANT_STATE",
          policyState.name(),
          TenantRuntimeState.ACTIVE.name());
    }
    if (policyState == TenantRuntimeState.BLOCKED) {
      return new TenantRuntimeRejection(
          companyCode,
          policyState,
          policyReasonCode,
          auditChainId,
          HttpStatus.FORBIDDEN,
          "TENANT_BLOCKED",
          "Tenant is currently blocked",
          "TENANT_STATE",
          policyState.name(),
          TenantRuntimeState.ACTIVE.name());
    }
    return null;
  }

  private boolean isMutatingRequest(String requestMethod) {
    String normalizedMethod = normalizeRequestMethod(requestMethod);
    if (normalizedMethod == null) {
      return true;
    }
    return switch (normalizedMethod) {
      case "GET", "HEAD", "OPTIONS", "TRACE" -> false;
      default -> true;
    };
  }

  private boolean isTenantRuntimePolicyControlRequest(
      String requestPath, String requestMethod, boolean policyControlPrivilegedActor) {
    if (!policyControlPrivilegedActor) {
      return false;
    }
    String normalizedMethod = normalizeRequestMethod(requestMethod);
    if (!"PUT".equals(normalizedMethod)) {
      return false;
    }
    if (!StringUtils.hasText(requestPath)) {
      return false;
    }
    String normalizedPath = requestPath.trim();
    while (normalizedPath.endsWith("/") && normalizedPath.length() > 1) {
      normalizedPath = normalizedPath.substring(0, normalizedPath.length() - 1);
    }
    return isCanonicalSuperAdminTenantLimitsPath(normalizedPath);
  }

  private boolean isCanonicalSuperAdminTenantLimitsPath(String normalizedPath) {
    if (!StringUtils.hasText(normalizedPath)
        || !normalizedPath.startsWith(CANONICAL_SUPERADMIN_TENANT_LIMITS_PREFIX)
        || (!normalizedPath.endsWith(CANONICAL_SUPERADMIN_TENANT_LIMITS_SUFFIX)
            && !normalizedPath.endsWith(CANONICAL_SUPERADMIN_TENANT_LIFECYCLE_SUFFIX))) {
      return false;
    }
    int prefixLength = CANONICAL_SUPERADMIN_TENANT_LIMITS_PREFIX.length();
    int suffixLength =
        normalizedPath.endsWith(CANONICAL_SUPERADMIN_TENANT_LIMITS_SUFFIX)
            ? CANONICAL_SUPERADMIN_TENANT_LIMITS_SUFFIX.length()
            : CANONICAL_SUPERADMIN_TENANT_LIFECYCLE_SUFFIX.length();
    String companyIdSegment =
        normalizedPath.substring(prefixLength, normalizedPath.length() - suffixLength);
    return StringUtils.hasText(companyIdSegment) && !companyIdSegment.contains("/");
  }

  private void auditRejection(
      TenantRuntimeRejection rejection, String actor, String requestPath, String requestMethod) {
    Map<String, String> metadata = new HashMap<>();
    metadata.put("scope", "tenant_runtime_enforcement");
    metadata.put("reasonCode", rejection.reasonCode);
    metadata.put("reasonDetail", rejection.reasonDetail);
    metadata.put("auditChainId", rejection.auditChainId);
    metadata.put("tenantState", safeRejectionTenantStateName(rejection));
    metadata.put("tenantReasonCode", rejection.tenantReasonCode);
    metadata.put("limitType", rejection.limitType);
    metadata.put("observedValue", rejection.observedValue);
    metadata.put("limitValue", rejection.limitValue);
    if (StringUtils.hasText(requestPath)) {
      metadata.put("requestPath", requestPath.trim());
    }
    String normalizedMethod = normalizeRequestMethod(requestMethod);
    if (normalizedMethod != null) {
      metadata.put("requestMethod", normalizedMethod);
    }
    auditService.logAuthFailure(
        AuditEvent.ACCESS_DENIED, normalizeActor(actor), rejection.companyCode, metadata);
  }

  private String normalizeRequestMethod(String requestMethod) {
    if (!StringUtils.hasText(requestMethod)) {
      return null;
    }
    return requestMethod.trim().toUpperCase(Locale.ROOT);
  }

  private Long resolveActiveUsers(String companyCode) {
    Company company;
    try {
      company = companyRepository.findByCodeIgnoreCase(companyCode).orElse(null);
    } catch (RuntimeException ex) {
      throw toManagedOperationException(
          companyCode,
          new TenantRuntimeAdmissionFailure(
              unavailableRejection(
                  companyCode,
                  "TENANT_COMPANY_LOOKUP_UNAVAILABLE",
                  "Tenant company lookup is unavailable"),
              ex));
    }
    if (company == null || company.getId() == null) {
      return 0L;
    }
    return userAccountRepository.countByCompany_IdAndEnabledTrue(company.getId());
  }

  private int incrementMinuteCount(TenantRuntimeCounters usageCounters, long minuteBucket) {
    while (true) {
      long previousBucket = usageCounters.minuteBucket.get();
      if (previousBucket != minuteBucket) {
        if (!usageCounters.minuteBucket.compareAndSet(previousBucket, minuteBucket)) {
          continue;
        }
        usageCounters.minuteRequestCount.set(0);
        usageCounters.minuteRejectedCount.set(0);
      }
      return usageCounters.minuteRequestCount.incrementAndGet();
    }
  }

  private void incrementRejectedCount(TenantRuntimeCounters usageCounters) {
    usageCounters.rejectedRequests.incrementAndGet();
    long minuteBucket = CompanyTime.now().getEpochSecond() / 60L;
    synchronized (usageCounters) {
      if (usageCounters.minuteBucket.get() != minuteBucket) {
        usageCounters.minuteBucket.set(minuteBucket);
        usageCounters.minuteRequestCount.set(0);
        usageCounters.minuteRejectedCount.set(0);
      }
      usageCounters.minuteRejectedCount.incrementAndGet();
    }
  }

  private TenantRuntimePolicy policyFor(String companyCode) {
    TenantRuntimePolicy current = policies.get(companyCode);
    long nowMillis = System.currentTimeMillis();
    if (current != null && current.policyRefreshAfterEpochMillis > nowMillis) {
      return current;
    }
    return withPolicyMutationLock(
        companyCode,
        () -> {
          TenantRuntimePolicy cached = policies.get(companyCode);
          long refreshCheckAt = System.currentTimeMillis();
          if (cached != null && cached.policyRefreshAfterEpochMillis > refreshCheckAt) {
            return cached;
          }
          TenantRuntimePolicy persisted = loadPersistedPolicy(companyCode);
          TenantRuntimePolicy resolved;
          if (persisted != null && shouldUsePersistedPolicy(cached, persisted)) {
            resolved = persisted;
          } else if (cached != null) {
            resolved = cached;
          } else if (persisted != null) {
            resolved = persisted;
          } else {
            resolved = defaultPolicy();
          }
          resolved.policyRefreshAfterEpochMillis = refreshCheckAt + persistedPolicyCacheTtlMillis;
          policies.put(companyCode, resolved);
          return resolved;
        });
  }

  private boolean shouldUsePersistedPolicy(
      TenantRuntimePolicy current, TenantRuntimePolicy persisted) {
    if (persisted == null) {
      return false;
    }
    if (current == null) {
      return true;
    }
    if (current.auditChainId != null
        && current.auditChainId.equalsIgnoreCase(persisted.auditChainId)
        && current.state == persisted.state
        && current.maxConcurrentRequests == persisted.maxConcurrentRequests
        && current.maxRequestsPerMinute == persisted.maxRequestsPerMinute
        && current.maxActiveUsers == persisted.maxActiveUsers
        && (current.reasonCode == null
            ? persisted.reasonCode == null
            : current.reasonCode.equalsIgnoreCase(persisted.reasonCode))) {
      return false;
    }
    if (persisted.updatedAt == null) {
      return true;
    }
    if (current.updatedAt == null) {
      return true;
    }
    return !persisted.updatedAt.isBefore(current.updatedAt);
  }

  private TenantRuntimePolicy loadPersistedPolicy(String companyCode) {
    if (!StringUtils.hasText(companyCode)) {
      return null;
    }
    Company company;
    try {
      company = companyRepository.findByCodeIgnoreCase(companyCode.trim()).orElse(null);
    } catch (RuntimeException ex) {
      throw new TenantRuntimeAdmissionFailure(
          unavailableRejection(
              companyCode,
              "TENANT_COMPANY_LOOKUP_UNAVAILABLE",
              "Tenant company lookup is unavailable"),
          ex);
    }
    if (company == null || company.getId() == null) {
      throw new TenantRuntimeAdmissionFailure(tenantNotFoundRejection(companyCode));
    }
    return loadPersistedPolicy(companyCode, company.getId());
  }

  private TenantRuntimePolicy loadPersistedPolicy(String companyCode, Long companyId) {
    if (!StringUtils.hasText(companyCode)) {
      return null;
    }
    if (companyId == null) {
      throw new TenantRuntimeAdmissionFailure(tenantNotFoundRejection(companyCode));
    }
    String persistedState = readSetting(companyCode, keyHoldState(companyId), null);
    String persistedReason = readSetting(companyCode, keyHoldReason(companyId), null);
    String persistedMaxConcurrent =
        readSetting(companyCode, keyMaxConcurrentRequests(companyId), null);
    String persistedMaxPerMinute =
        readSetting(companyCode, keyMaxRequestsPerMinute(companyId), null);
    String persistedMaxActiveUsers = readSetting(companyCode, keyMaxActiveUsers(companyId), null);
    String persistedPolicyReference = readSetting(companyCode, keyPolicyReference(companyId), null);
    String persistedUpdatedAt = readSetting(companyCode, keyPolicyUpdatedAt(companyId), null);

    boolean hasPersistedPolicy =
        StringUtils.hasText(persistedState)
            || StringUtils.hasText(persistedReason)
            || StringUtils.hasText(persistedMaxConcurrent)
            || StringUtils.hasText(persistedMaxPerMinute)
            || StringUtils.hasText(persistedMaxActiveUsers)
            || StringUtils.hasText(persistedPolicyReference)
            || StringUtils.hasText(persistedUpdatedAt);
    if (!hasPersistedPolicy) {
      return null;
    }

    TenantRuntimeState state = normalizeState(persistedState);
    String reason = StringUtils.hasText(persistedReason) ? persistedReason.trim() : DEFAULT_REASON;
    int maxConcurrentRequests =
        parsePositiveInt(persistedMaxConcurrent, defaultMaxConcurrentRequests);
    int maxRequestsPerMinute = parsePositiveInt(persistedMaxPerMinute, defaultMaxRequestsPerMinute);
    int maxActiveUsers = parsePositiveInt(persistedMaxActiveUsers, defaultMaxActiveUsers);
    String auditChainId =
        StringUtils.hasText(persistedPolicyReference)
            ? persistedPolicyReference.trim()
            : DEFAULT_POLICY_REFERENCE;
    Instant updatedAt = parseInstantOrNull(persistedUpdatedAt);
    return new TenantRuntimePolicy(
        state,
        reason,
        maxConcurrentRequests,
        maxRequestsPerMinute,
        maxActiveUsers,
        auditChainId,
        updatedAt,
        0L);
  }

  private TenantRuntimePolicy defaultPolicy() {
    return new TenantRuntimePolicy(
        TenantRuntimeState.ACTIVE,
        DEFAULT_REASON,
        defaultMaxConcurrentRequests,
        defaultMaxRequestsPerMinute,
        defaultMaxActiveUsers,
        DEFAULT_POLICY_REFERENCE,
        null,
        0L);
  }

  private TenantRuntimeState normalizeState(String rawState) {
    if (!StringUtils.hasText(rawState)) {
      return TenantRuntimeState.ACTIVE;
    }
    String normalized = rawState.trim().toUpperCase(Locale.ROOT);
    return switch (normalized) {
      case "ACTIVE" -> TenantRuntimeState.ACTIVE;
      case "HOLD" -> TenantRuntimeState.HOLD;
      case "BLOCKED" -> TenantRuntimeState.BLOCKED;
      default -> TenantRuntimeState.BLOCKED;
    };
  }

  private int parsePositiveInt(String rawValue, int fallback) {
    if (!StringUtils.hasText(rawValue)) {
      return fallback;
    }
    String normalized = rawValue.trim();
    if (!normalized.chars().allMatch(Character::isDigit)) {
      return fallback;
    }
    try {
      int parsed = Integer.parseInt(normalized);
      return parsed > 0 ? parsed : fallback;
    } catch (NumberFormatException ex) {
      return fallback;
    }
  }

  private Instant parseInstantOrNull(String rawValue) {
    if (!StringUtils.hasText(rawValue)) {
      return null;
    }
    try {
      return Instant.parse(rawValue.trim());
    } catch (DateTimeParseException ex) {
      return null;
    }
  }

  private String readSetting(String companyCode, String key, String fallback) {
    try {
      return systemSettingsRepository.findById(key).map(SystemSetting::getValue).orElse(fallback);
    } catch (RuntimeException ex) {
      throw new TenantRuntimeAdmissionFailure(
          unavailableRejection(
              companyCode,
              "TENANT_RUNTIME_POLICY_UNAVAILABLE",
              "Tenant runtime policy is unavailable"),
          ex);
    }
  }

  private TenantRuntimeRejection unavailableRejection(
      String companyCode, String reasonCode, String reasonDetail) {
    return new TenantRuntimeRejection(
        companyCode,
        TenantRuntimeState.BLOCKED,
        DEFAULT_REASON,
        DEFAULT_POLICY_REFERENCE,
        HttpStatus.SERVICE_UNAVAILABLE,
        reasonCode,
        reasonDetail,
        null,
        null,
        null);
  }

  private TenantRuntimeRejection tenantNotFoundRejection(String companyCode) {
    return new TenantRuntimeRejection(
        companyCode,
        TenantRuntimeState.BLOCKED,
        DEFAULT_REASON,
        DEFAULT_POLICY_REFERENCE,
        HttpStatus.FORBIDDEN,
        "TENANT_NOT_FOUND",
        "Tenant not found",
        null,
        null,
        null);
  }

  private void persistSetting(String key, String value) {
    if (!StringUtils.hasText(key)) {
      return;
    }
    systemSettingsRepository.save(new SystemSetting(key, value));
  }

  private String keyHoldState(Long companyId) {
    return "tenant.runtime.hold-state." + companyId;
  }

  private String keyHoldReason(Long companyId) {
    return "tenant.runtime.hold-reason." + companyId;
  }

  private String keyMaxActiveUsers(Long companyId) {
    return "tenant.runtime.max-active-users." + companyId;
  }

  private String keyMaxRequestsPerMinute(Long companyId) {
    return "tenant.runtime.max-requests-per-minute." + companyId;
  }

  private String keyMaxConcurrentRequests(Long companyId) {
    return "tenant.runtime.max-concurrent-requests." + companyId;
  }

  private String keyPolicyReference(Long companyId) {
    return "tenant.runtime.policy-reference." + companyId;
  }

  private String keyPolicyUpdatedAt(Long companyId) {
    return "tenant.runtime.policy-updated-at." + companyId;
  }

  private TenantRuntimeCounters countersFor(String companyCode) {
    return counters.computeIfAbsent(companyCode, key -> new TenantRuntimeCounters());
  }

  private <T> T withPolicyMutationLock(String companyCode, Supplier<T> action) {
    Object lock = policyMutationLocks.computeIfAbsent(companyCode, key -> new Object());
    synchronized (lock) {
      return action.get();
    }
  }

  private Optional<String> currentAuditChainId(String companyCode) {
    TenantRuntimePolicy current = policies.get(companyCode);
    if (current == null || !StringUtils.hasText(current.auditChainId)) {
      return Optional.empty();
    }
    return Optional.of(current.auditChainId);
  }

  private void activatePolicy(String companyCode, TenantRuntimePolicy updatedPolicy) {
    if (!StringUtils.hasText(companyCode) || updatedPolicy == null) {
      return;
    }
    updatedPolicy.policyRefreshAfterEpochMillis =
        System.currentTimeMillis() + persistedPolicyCacheTtlMillis;
    policies.put(companyCode, updatedPolicy);
  }

  private TenantRuntimePolicy copyPolicy(TenantRuntimePolicy source) {
    return new TenantRuntimePolicy(
        source.state,
        source.reasonCode,
        source.maxConcurrentRequests,
        source.maxRequestsPerMinute,
        source.maxActiveUsers,
        source.auditChainId,
        source.updatedAt,
        source.policyRefreshAfterEpochMillis);
  }

  private String normalizeActor(String actor) {
    String normalized = normalizeUpperToken(actor, null);
    if (!StringUtils.hasText(normalized)) {
      return UNKNOWN_ACTOR;
    }
    return normalized;
  }

  private String requireCompanyCode(String companyCode) {
    String normalized = normalizeCompanyCode(companyCode);
    if (normalized == null) {
      throw com.bigbrightpaints.erp.core.validation.ValidationUtils.invalidInput(
          "Company code is required");
    }
    return normalized;
  }

  private Company requireTrackedCompany(String normalizedCompany) {
    Optional<Company> company;
    try {
      company = companyRepository.findByCodeIgnoreCase(normalizedCompany);
    } catch (RuntimeException ex) {
      throw toManagedOperationException(
          normalizedCompany,
          new TenantRuntimeAdmissionFailure(
              unavailableRejection(
                  normalizedCompany,
                  "TENANT_COMPANY_LOOKUP_UNAVAILABLE",
                  "Tenant company lookup is unavailable"),
              ex));
    }
    return company.orElseThrow(
        () ->
            com.bigbrightpaints.erp.core.validation.ValidationUtils.invalidInput(
                "Company not found: " + normalizedCompany));
  }

  private TenantRuntimePolicy policyForManagedOperation(String normalizedCompany) {
    try {
      return policyFor(normalizedCompany);
    } catch (TenantRuntimeAdmissionFailure failure) {
      throw toManagedOperationException(normalizedCompany, failure);
    }
  }

  private TenantRuntimePolicy persistedPolicyForManagedOperation(
      String normalizedCompany, Company company) {
    try {
      TenantRuntimePolicy persistedPolicy =
          loadPersistedPolicy(normalizedCompany, company == null ? null : company.getId());
      return persistedPolicy == null ? defaultPolicy() : persistedPolicy;
    } catch (TenantRuntimeAdmissionFailure failure) {
      throw toManagedOperationException(normalizedCompany, failure);
    }
  }

  private ApplicationException toManagedOperationException(
      String normalizedCompany, TenantRuntimeAdmissionFailure failure) {
    TenantRuntimeRejection rejection = failure == null ? null : failure.rejection();
    if (rejection != null && rejection.httpStatus() == HttpStatus.SERVICE_UNAVAILABLE) {
      return withRejectionDetails(
          new ApplicationException(
              ErrorCode.SYSTEM_SERVICE_UNAVAILABLE, rejection.reasonDetail(), failure),
          rejection);
    }
    return withRejectionDetails(
        com.bigbrightpaints.erp.core.validation.ValidationUtils.invalidInput(
            "Company not found: " + normalizedCompany),
        rejection);
  }

  private ApplicationException withRejectionDetails(
      ApplicationException ex, TenantRuntimeRejection rejection) {
    if (ex == null || rejection == null) {
      return ex;
    }
    if (StringUtils.hasText(rejection.companyCode())) {
      ex.withDetail("companyCode", rejection.companyCode());
    }
    if (StringUtils.hasText(rejection.reasonCode())) {
      ex.withDetail("reason", rejection.reasonCode());
    }
    if (StringUtils.hasText(rejection.auditChainId())) {
      ex.withDetail("auditChainId", rejection.auditChainId());
    }
    if (StringUtils.hasText(rejection.tenantReasonCode())) {
      ex.withDetail("tenantReasonCode", rejection.tenantReasonCode());
    }
    if (StringUtils.hasText(rejection.limitType())) {
      ex.withDetail("limitType", rejection.limitType());
    }
    if (StringUtils.hasText(rejection.observedValue())) {
      ex.withDetail("observedValue", rejection.observedValue());
    }
    if (StringUtils.hasText(rejection.limitValue())) {
      ex.withDetail("limitValue", rejection.limitValue());
    }
    return ex;
  }

  private String normalizeCompanyCode(String companyCode) {
    String normalized = normalizeUpperToken(companyCode, null);
    return StringUtils.hasText(normalized) ? normalized : null;
  }

  private String normalizeReason(String reasonCode) {
    return normalizeUpperToken(reasonCode, DEFAULT_REASON);
  }

  private String normalizeUpperToken(String value, String fallback) {
    if (!StringUtils.hasText(value)) {
      return fallback;
    }
    return IdempotencyUtils.normalizeUpperToken(value);
  }

  private TenantRuntimeState safePolicyState(TenantRuntimePolicy policy) {
    if (policy == null || policy.state == null) {
      return TenantRuntimeState.ACTIVE;
    }
    return policy.state;
  }

  private String safePolicyStateName(TenantRuntimePolicy policy) {
    return safePolicyState(policy).name();
  }

  private String safePolicyReasonCode(TenantRuntimePolicy policy) {
    if (policy == null || !StringUtils.hasText(policy.reasonCode)) {
      return DEFAULT_REASON;
    }
    return policy.reasonCode;
  }

  private String safePolicyAuditChainId(TenantRuntimePolicy policy) {
    if (policy == null || !StringUtils.hasText(policy.auditChainId)) {
      return DEFAULT_POLICY_REFERENCE;
    }
    return policy.auditChainId;
  }

  private String safeRejectionTenantStateName(TenantRuntimeRejection rejection) {
    if (rejection == null || rejection.tenantState == null) {
      return TenantRuntimeState.BLOCKED.name();
    }
    return rejection.tenantState.name();
  }

  private int sanitizeLimit(int limit) {
    return Math.max(MIN_LIMIT, limit);
  }

  public enum TenantRuntimeState {
    ACTIVE,
    HOLD,
    BLOCKED
  }

  public record TenantRuntimeMetrics(
      long totalRequests,
      long rejectedRequests,
      long errorResponses,
      int inFlightRequests,
      int minuteRequestCount,
      int minuteRejectedCount,
      long activeUsers) {}

  public record TenantRuntimeSnapshot(
      String companyCode,
      TenantRuntimeState state,
      String reasonCode,
      String auditChainId,
      Instant updatedAt,
      int maxConcurrentRequests,
      int maxRequestsPerMinute,
      int maxActiveUsers,
      TenantRuntimeMetrics metrics) {}

  public static final class TenantRequestAdmission {
    private static final TenantRequestAdmission NOT_TRACKED =
        new TenantRequestAdmission(
            false,
            null,
            null,
            null,
            HttpStatus.OK.value(),
            null,
            false,
            null,
            null,
            null,
            null,
            null);

    private final boolean admitted;
    private final String companyCode;
    private final String auditChainId;
    private final TenantRuntimeCounters counters;
    private final int statusCode;
    private final String message;
    private final boolean policyControlRequest;
    private final String reasonCode;
    private final String tenantReasonCode;
    private final String limitType;
    private final String observedValue;
    private final String limitValue;

    private TenantRequestAdmission(
        boolean admitted,
        String companyCode,
        String auditChainId,
        TenantRuntimeCounters counters,
        int statusCode,
        String message) {
      this(
          admitted,
          companyCode,
          auditChainId,
          counters,
          statusCode,
          message,
          false,
          null,
          null,
          null,
          null,
          null);
    }

    private TenantRequestAdmission(
        boolean admitted,
        String companyCode,
        String auditChainId,
        TenantRuntimeCounters counters,
        int statusCode,
        String message,
        boolean policyControlRequest,
        String reasonCode,
        String tenantReasonCode,
        String limitType,
        String observedValue,
        String limitValue) {
      this.admitted = admitted;
      this.companyCode = companyCode;
      this.auditChainId = auditChainId;
      this.counters = counters;
      this.statusCode = statusCode;
      this.message = message;
      this.policyControlRequest = policyControlRequest;
      this.reasonCode = reasonCode;
      this.tenantReasonCode = tenantReasonCode;
      this.limitType = limitType;
      this.observedValue = observedValue;
      this.limitValue = limitValue;
    }

    public static TenantRequestAdmission notTracked() {
      return NOT_TRACKED;
    }

    public static TenantRequestAdmission admitted(String companyCode, String auditChainId) {
      return admitted(companyCode, auditChainId, new TenantRuntimeCounters());
    }

    public static TenantRequestAdmission admitted(
        String companyCode, String auditChainId, TenantRuntimeCounters counters) {
      return new TenantRequestAdmission(
          true, companyCode, auditChainId, counters, HttpStatus.OK.value(), null);
    }

    public static TenantRequestAdmission admittedPolicyControl(
        String companyCode, String auditChainId) {
      return admittedPolicyControl(companyCode, auditChainId, new TenantRuntimeCounters());
    }

    public static TenantRequestAdmission admittedPolicyControl(
        String companyCode, String auditChainId, TenantRuntimeCounters counters) {
      return new TenantRequestAdmission(
          true,
          companyCode,
          auditChainId,
          counters,
          HttpStatus.OK.value(),
          null,
          true,
          null,
          null,
          null,
          null,
          null);
    }

    public static TenantRequestAdmission rejected(TenantRuntimeRejection rejection) {
      return new TenantRequestAdmission(
          false,
          rejection.companyCode,
          rejection.auditChainId,
          null,
          rejection.httpStatus.value(),
          rejection.reasonDetail,
          false,
          rejection.reasonCode,
          rejection.tenantReasonCode,
          rejection.limitType,
          rejection.observedValue,
          rejection.limitValue);
    }

    public boolean isAdmitted() {
      return admitted;
    }

    public int statusCode() {
      return statusCode;
    }

    public String message() {
      return message;
    }

    private TenantRuntimeCounters counters() {
      return counters;
    }

    public String companyCode() {
      return companyCode;
    }

    public String auditChainId() {
      return auditChainId;
    }

    public String reasonCode() {
      return reasonCode;
    }

    public String tenantReasonCode() {
      return tenantReasonCode;
    }

    public String limitType() {
      return limitType;
    }

    public String observedValue() {
      return observedValue;
    }

    public String limitValue() {
      return limitValue;
    }

    private boolean isPolicyControlRequest() {
      return policyControlRequest;
    }
  }

  private static final class TenantRuntimePolicy {
    private volatile TenantRuntimeState state;
    private volatile String reasonCode;
    private volatile int maxConcurrentRequests;
    private volatile int maxRequestsPerMinute;
    private volatile int maxActiveUsers;
    private volatile String auditChainId;
    private volatile Instant updatedAt;
    private volatile long policyRefreshAfterEpochMillis;

    private TenantRuntimePolicy(
        TenantRuntimeState state,
        String reasonCode,
        int maxConcurrentRequests,
        int maxRequestsPerMinute,
        int maxActiveUsers,
        String auditChainId,
        Instant updatedAt,
        long policyRefreshAfterEpochMillis) {
      this.state = state;
      this.reasonCode = reasonCode;
      this.maxConcurrentRequests = maxConcurrentRequests;
      this.maxRequestsPerMinute = maxRequestsPerMinute;
      this.maxActiveUsers = maxActiveUsers;
      this.auditChainId = auditChainId;
      this.updatedAt = updatedAt;
      this.policyRefreshAfterEpochMillis = policyRefreshAfterEpochMillis;
    }

    private int effectiveMaxConcurrentRequests(int fallback) {
      return Math.max(MIN_LIMIT, maxConcurrentRequests > 0 ? maxConcurrentRequests : fallback);
    }

    private int effectiveMaxRequestsPerMinute(int fallback) {
      return Math.max(MIN_LIMIT, maxRequestsPerMinute > 0 ? maxRequestsPerMinute : fallback);
    }

    private int effectiveMaxActiveUsers(int fallback) {
      return Math.max(MIN_LIMIT, maxActiveUsers > 0 ? maxActiveUsers : fallback);
    }
  }

  private static final class TenantRuntimeCounters {
    private final AtomicInteger inFlightRequests = new AtomicInteger(0);
    private final AtomicLong totalRequests = new AtomicLong(0);
    private final AtomicLong rejectedRequests = new AtomicLong(0);
    private final AtomicLong errorResponses = new AtomicLong(0);
    private final AtomicLong minuteBucket = new AtomicLong(0);
    private final AtomicInteger minuteRequestCount = new AtomicInteger(0);
    private final AtomicInteger minuteRejectedCount = new AtomicInteger(0);
  }

  private record TenantRuntimeRejection(
      String companyCode,
      TenantRuntimeState tenantState,
      String tenantReasonCode,
      String auditChainId,
      HttpStatus httpStatus,
      String reasonCode,
      String reasonDetail,
      String limitType,
      String observedValue,
      String limitValue) {
    private ResponseStatusException asException() {
      return new ResponseStatusException(httpStatus, reasonDetail);
    }

    private AuthSecurityContractException asAuthSecurityContractException() {
      AuthSecurityContractException ex =
          new AuthSecurityContractException(httpStatus, reasonCode, reasonDetail)
              .withDetail("reason", reasonCode);
      if (auditChainId != null && !auditChainId.isBlank()) {
        ex.withDetail("auditChainId", auditChainId);
      }
      if (tenantReasonCode != null && !tenantReasonCode.isBlank()) {
        ex.withDetail("tenantReasonCode", tenantReasonCode);
      }
      if (limitType != null && !limitType.isBlank()) {
        ex.withDetail("limitType", limitType);
      }
      if (observedValue != null && !observedValue.isBlank()) {
        ex.withDetail("observedValue", observedValue);
      }
      if (limitValue != null && !limitValue.isBlank()) {
        ex.withDetail("limitValue", limitValue);
      }
      return ex;
    }
  }

  private static final class TenantRuntimeAdmissionFailure extends RuntimeException {
    private static final long serialVersionUID = 1L;

    private final TenantRuntimeRejection rejection;

    private TenantRuntimeAdmissionFailure(TenantRuntimeRejection rejection) {
      this.rejection = rejection;
    }

    private TenantRuntimeAdmissionFailure(TenantRuntimeRejection rejection, Throwable cause) {
      super(cause);
      this.rejection = rejection;
    }

    private TenantRuntimeRejection rejection() {
      return rejection;
    }
  }
}
