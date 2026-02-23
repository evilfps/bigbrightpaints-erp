package com.bigbrightpaints.erp.modules.company.service;

import com.bigbrightpaints.erp.core.audit.AuditEvent;
import com.bigbrightpaints.erp.core.audit.AuditService;
import com.bigbrightpaints.erp.core.config.SystemSetting;
import com.bigbrightpaints.erp.core.config.SystemSettingsRepository;
import com.bigbrightpaints.erp.core.util.CompanyTime;
import com.bigbrightpaints.erp.modules.auth.domain.UserAccountRepository;
import com.bigbrightpaints.erp.modules.company.domain.Company;
import com.bigbrightpaints.erp.modules.company.domain.CompanyRepository;
import java.time.Instant;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;
import org.springframework.web.server.ResponseStatusException;

@Service
public class TenantRuntimeEnforcementService {

    private static final Logger log = LoggerFactory.getLogger(TenantRuntimeEnforcementService.class);
    private static final int MIN_LIMIT = 1;
    private static final String DEFAULT_REASON = "POLICY_ACTIVE";
    private static final String DEFAULT_POLICY_REFERENCE = "bootstrap";
    private static final String UNKNOWN_ACTOR = "UNKNOWN_AUTH_ACTOR";
    private static final String TENANT_RUNTIME_POLICY_PATH = "/api/v1/admin/tenant-runtime/policy";
    private static final String CANONICAL_COMPANY_RUNTIME_POLICY_PREFIX = "/api/v1/companies/";
    private static final String CANONICAL_COMPANY_RUNTIME_POLICY_SUFFIX = "/tenant-runtime/policy";

    private final CompanyRepository companyRepository;
    private final SystemSettingsRepository systemSettingsRepository;
    private final UserAccountRepository userAccountRepository;
    private final AuditService auditService;
    private final int defaultMaxConcurrentRequests;
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
            @Value("${erp.tenant.runtime.default-max-concurrent-requests:200}") int defaultMaxConcurrentRequests,
            @Value("${erp.tenant.runtime.default-max-requests-per-minute:5000}") int defaultMaxRequestsPerMinute,
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

    public TenantRequestAdmission beginRequest(String companyCode,
                                               String requestPath,
                                               String requestMethod,
                                               String actor) {
        return beginRequest(companyCode, requestPath, requestMethod, actor, false);
    }

    public TenantRequestAdmission beginRequest(String companyCode,
                                               String requestPath,
                                               String requestMethod,
                                               String actor,
                                               boolean policyControlPrivilegedActor) {
        String normalizedCompany = normalizeCompanyCode(companyCode);
        if (normalizedCompany == null) {
            return TenantRequestAdmission.notTracked();
        }
        TenantRuntimePolicy policy = policyFor(normalizedCompany);
        TenantRuntimeCounters usageCounters = countersFor(normalizedCompany);
        boolean policyControlRequest = isTenantRuntimePolicyControlRequest(
                requestPath,
                requestMethod,
                policyControlPrivilegedActor);

        TenantRuntimeRejection stateRejection = stateRejection(
                policy,
                normalizedCompany,
                policyControlRequest,
                requestMethod);
        if (stateRejection != null) {
            recordRejection(usageCounters);
            auditRejection(stateRejection, actor, requestPath, requestMethod);
            return TenantRequestAdmission.rejected(stateRejection);
        }

        if (policyControlRequest) {
            usageCounters.totalRequests.incrementAndGet();
            usageCounters.inFlightRequests.incrementAndGet();
            return TenantRequestAdmission.admittedPolicyControl(normalizedCompany, policy.auditChainId, usageCounters);
        }

        long minuteBucket = CompanyTime.now().getEpochSecond() / 60L;
        int requestsInMinute = incrementMinuteCount(usageCounters, minuteBucket);
        int maxRequestsPerMinute = policy.effectiveMaxRequestsPerMinute(defaultMaxRequestsPerMinute);
        if (requestsInMinute > maxRequestsPerMinute) {
            incrementMinuteRejectedCount(usageCounters, minuteBucket);
            usageCounters.rejectedRequests.incrementAndGet();
            TenantRuntimeRejection rejection = new TenantRuntimeRejection(
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
            recordRejection(usageCounters);
            TenantRuntimeRejection rejection = new TenantRuntimeRejection(
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

    public void completeRequest(TenantRequestAdmission admission, int responseStatus) {
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

    public void enforceAuthOperationAllowed(String companyCode, String actor, String operation) {
        String normalizedCompany = requireCompanyCode(companyCode);
        String scope = "auth_" + normalizeToken(operation, "UNKNOWN");
        TenantRuntimePolicy policy = policyFor(normalizedCompany);
        TenantRuntimeCounters usageCounters = countersFor(normalizedCompany);

        TenantRuntimeRejection stateRejection = stateRejection(policy, normalizedCompany, false, "POST");
        if (stateRejection != null) {
            recordRejection(usageCounters);
            auditRejection(stateRejection, actor, scope, "POST");
            throw stateRejection.asException();
        }

        Company company = companyRepository.findByCodeIgnoreCase(normalizedCompany)
                .orElseThrow(() -> new IllegalArgumentException("Company not found: " + normalizedCompany));
        long activeUsers = userAccountRepository.countDistinctByCompanies_IdAndEnabledTrue(company.getId());
        int maxActiveUsers = policy.effectiveMaxActiveUsers(defaultMaxActiveUsers);
        if (activeUsers > maxActiveUsers) {
            recordRejection(usageCounters);
            TenantRuntimeRejection rejection = new TenantRuntimeRejection(
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
            throw rejection.asException();
        }
    }

    @Transactional
    public TenantRuntimeSnapshot holdTenant(String companyCode, String reasonCode, String actor) {
        return updatePolicy(
                companyCode,
                TenantRuntimeState.HOLD,
                reasonCode,
                null,
                null,
                null,
                actor);
    }

    @Transactional
    public TenantRuntimeSnapshot blockTenant(String companyCode, String reasonCode, String actor) {
        return updatePolicy(
                companyCode,
                TenantRuntimeState.BLOCKED,
                reasonCode,
                null,
                null,
                null,
                actor);
    }

    @Transactional
    public TenantRuntimeSnapshot resumeTenant(String companyCode, String actor) {
        return updatePolicy(
                companyCode,
                TenantRuntimeState.ACTIVE,
                DEFAULT_REASON,
                null,
                null,
                null,
                actor);
    }

    @Transactional
    public TenantRuntimeSnapshot updateQuotas(String companyCode,
                                              Integer maxConcurrentRequests,
                                              Integer maxRequestsPerMinute,
                                              Integer maxActiveUsers,
                                              String reasonCode,
                                              String actor) {
        return updatePolicy(
                companyCode,
                null,
                reasonCode,
                maxConcurrentRequests,
                maxRequestsPerMinute,
                maxActiveUsers,
                actor);
    }

    @Transactional
    public TenantRuntimeSnapshot updatePolicy(String companyCode,
                                              TenantRuntimeState targetState,
                                              String reasonCode,
                                              Integer maxConcurrentRequests,
                                              Integer maxRequestsPerMinute,
                                              Integer maxActiveUsers,
                                              String actor) {
        requireRuntimePolicyMutationAuthority();
        Integer validatedMaxConcurrentRequests =
                validateRuntimeLimit(maxConcurrentRequests, "maxConcurrentRequests");
        Integer validatedMaxRequestsPerMinute =
                validateRuntimeLimit(maxRequestsPerMinute, "maxRequestsPerMinute");
        Integer validatedMaxActiveUsers =
                validateRuntimeLimit(maxActiveUsers, "maxActiveUsers");
        boolean stateMutation = targetState != null;
        boolean quotaMutation = validatedMaxConcurrentRequests != null
                || validatedMaxRequestsPerMinute != null
                || validatedMaxActiveUsers != null;
        if (!stateMutation && !quotaMutation) {
            throw new IllegalArgumentException("Runtime policy mutation payload is required");
        }
        String normalizedCompany = requireCompanyCode(companyCode);
        // Force fail-closed for unknown company code updates.
        Company company = companyRepository.findByCodeIgnoreCase(normalizedCompany)
                .orElseThrow(() -> new IllegalArgumentException("Company not found: " + normalizedCompany));
        if (company.getId() == null) {
            throw new IllegalArgumentException("Company not found: " + normalizedCompany);
        }
        Long companyId = company.getId();
        boolean requiresFreshQuotaBaseline = validatedMaxConcurrentRequests == null
                || validatedMaxRequestsPerMinute == null
                || validatedMaxActiveUsers == null;
        TenantRuntimePolicy policy = policyFor(normalizedCompany);
        String normalizedReason = stateMutation
                ? requireStateReason(reasonCode, targetState)
                : normalizeReason(reasonCode);
        String previousChainId;
        String newChainId = UUID.randomUUID().toString();
        Instant now = CompanyTime.now();
        synchronized (policy) {
            previousChainId = policy.auditChainId;
            TenantRuntimeState nextState = stateMutation ? targetState : policy.state;
            TenantRuntimePolicy quotaBaseline = policy;
            if (requiresFreshQuotaBaseline) {
                TenantRuntimePolicy persistedQuotaBaseline = loadPersistedPolicyStrict(companyId);
                if (persistedQuotaBaseline != null) {
                    quotaBaseline = persistedQuotaBaseline;
                }
            }
            int nextMaxConcurrentRequests = validatedMaxConcurrentRequests != null
                    ? validatedMaxConcurrentRequests
                    : quotaBaseline.maxConcurrentRequests;
            int nextMaxRequestsPerMinute = validatedMaxRequestsPerMinute != null
                    ? validatedMaxRequestsPerMinute
                    : quotaBaseline.maxRequestsPerMinute;
            int nextMaxActiveUsers = validatedMaxActiveUsers != null
                    ? validatedMaxActiveUsers
                    : quotaBaseline.maxActiveUsers;
            String nextReason = policy.reasonCode;
            if (stateMutation || quotaMutation) {
                nextReason = normalizedReason;
            }
            Instant nextUpdatedAt = now;
            long nextRefreshEpoch = System.currentTimeMillis() + persistedPolicyCacheTtlMillis;

            persistPolicySettings(
                    companyId,
                    nextState,
                    nextReason,
                    nextMaxConcurrentRequests,
                    nextMaxRequestsPerMinute,
                    nextMaxActiveUsers,
                    newChainId,
                    nextUpdatedAt);

            policy.state = nextState;
            policy.reasonCode = nextReason;
            policy.maxConcurrentRequests = nextMaxConcurrentRequests;
            policy.maxRequestsPerMinute = nextMaxRequestsPerMinute;
            policy.maxActiveUsers = nextMaxActiveUsers;
            policy.updatedAt = nextUpdatedAt;
            policy.auditChainId = newChainId;
            policy.policyRefreshAfterEpochMillis = nextRefreshEpoch;
        }
        try {
            auditPolicyChange(
                    policyChangeAction(stateMutation, quotaMutation, targetState),
                    normalizedCompany,
                    resolveMutationActor(actor),
                    normalizedReason,
                    previousChainId,
                    policy.auditChainId,
                    policy);
        } catch (RuntimeException ex) {
            // Do not roll back policy mutations when audit sink is transiently unavailable.
            log.warn("Tenant runtime policy audit logging failed for company {}", normalizedCompany, ex);
        }
        return snapshot(normalizedCompany);
    }

    public TenantRuntimeSnapshot snapshot(String companyCode) {
        String normalizedCompany = requireCompanyCode(companyCode);
        TenantRuntimePolicy policy = policyFor(normalizedCompany);
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

    private TenantRuntimeSnapshot updateState(String companyCode,
                                              TenantRuntimeState targetState,
                                              String reasonCode,
                                              String actor,
                                              String action) {
        String normalizedCompany = requireCompanyCode(companyCode);
        // Force fail-closed for unknown company code updates.
        companyRepository.findByCodeIgnoreCase(normalizedCompany)
                .orElseThrow(() -> new IllegalArgumentException("Company not found: " + normalizedCompany));

        TenantRuntimePolicy policy = policyFor(normalizedCompany);
        String normalizedReason = normalizeReason(reasonCode);
        String previousChainId;
        String newChainId = UUID.randomUUID().toString();
        Instant now = CompanyTime.now();
        synchronized (policy) {
            previousChainId = policy.auditChainId;
            policy.state = targetState;
            policy.reasonCode = normalizedReason;
            policy.auditChainId = newChainId;
            policy.updatedAt = now;
            policy.policyRefreshAfterEpochMillis = System.currentTimeMillis() + persistedPolicyCacheTtlMillis;
        }
        auditPolicyChange(
                action,
                normalizedCompany,
                normalizeActor(actor),
                normalizedReason,
                previousChainId,
                policy.auditChainId,
                policy);
        return snapshot(normalizedCompany);
    }

    private void auditPolicyChange(String action,
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
        metadata.put("tenantState", policy.state.name());
        metadata.put("maxConcurrentRequests",
                Integer.toString(policy.effectiveMaxConcurrentRequests(defaultMaxConcurrentRequests)));
        metadata.put("maxRequestsPerMinute",
                Integer.toString(policy.effectiveMaxRequestsPerMinute(defaultMaxRequestsPerMinute)));
        metadata.put("maxActiveUsers",
                Integer.toString(policy.effectiveMaxActiveUsers(defaultMaxActiveUsers)));
        auditService.logAuthSuccess(AuditEvent.CONFIGURATION_CHANGED, actor, companyCode, metadata);
    }

    private TenantRuntimeRejection stateRejection(TenantRuntimePolicy policy,
                                                  String companyCode,
                                                  boolean policyControlRequest,
                                                  String requestMethod) {
        if (policyControlRequest) {
            return null;
        }
        if (policy.state == TenantRuntimeState.HOLD && isMutatingRequest(requestMethod)) {
            return new TenantRuntimeRejection(
                    companyCode,
                    policy.state,
                    policy.reasonCode,
                    policy.auditChainId,
                    HttpStatus.LOCKED,
                    "TENANT_ON_HOLD",
                    "Tenant is currently on hold",
                    "TENANT_STATE",
                    policy.state.name(),
                    TenantRuntimeState.ACTIVE.name());
        }
        if (policy.state == TenantRuntimeState.BLOCKED) {
            return new TenantRuntimeRejection(
                    companyCode,
                    policy.state,
                    policy.reasonCode,
                    policy.auditChainId,
                    HttpStatus.FORBIDDEN,
                    "TENANT_BLOCKED",
                    "Tenant is currently blocked",
                    "TENANT_STATE",
                    policy.state.name(),
                    TenantRuntimeState.ACTIVE.name());
        }
        return null;
    }

    private boolean isMutatingRequest(String requestMethod) {
        if (!StringUtils.hasText(requestMethod)) {
            return true;
        }
        String normalizedMethod = requestMethod.trim().toUpperCase(Locale.ROOT);
        return switch (normalizedMethod) {
            case "GET", "HEAD", "OPTIONS", "TRACE" -> false;
            default -> true;
        };
    }

    private boolean isTenantRuntimePolicyControlRequest(String requestPath,
                                                        String requestMethod,
                                                        boolean policyControlPrivilegedActor) {
        if (!policyControlPrivilegedActor) {
            return false;
        }
        if (!StringUtils.hasText(requestMethod) || !"PUT".equalsIgnoreCase(requestMethod.trim())) {
            return false;
        }
        if (!StringUtils.hasText(requestPath)) {
            return false;
        }
        String normalizedPath = requestPath.trim();
        while (normalizedPath.endsWith("/") && normalizedPath.length() > 1) {
            normalizedPath = normalizedPath.substring(0, normalizedPath.length() - 1);
        }
        if (TENANT_RUNTIME_POLICY_PATH.equals(normalizedPath)) {
            return true;
        }
        return isCanonicalCompanyRuntimePolicyPath(normalizedPath);
    }

    private boolean isCanonicalCompanyRuntimePolicyPath(String normalizedPath) {
        if (!StringUtils.hasText(normalizedPath)
                || !normalizedPath.startsWith(CANONICAL_COMPANY_RUNTIME_POLICY_PREFIX)
                || !normalizedPath.endsWith(CANONICAL_COMPANY_RUNTIME_POLICY_SUFFIX)) {
            return false;
        }
        int prefixLength = CANONICAL_COMPANY_RUNTIME_POLICY_PREFIX.length();
        int suffixLength = CANONICAL_COMPANY_RUNTIME_POLICY_SUFFIX.length();
        String companyIdSegment = normalizedPath.substring(prefixLength, normalizedPath.length() - suffixLength);
        return StringUtils.hasText(companyIdSegment) && !companyIdSegment.contains("/");
    }

    private void auditRejection(TenantRuntimeRejection rejection, String actor, String requestPath, String requestMethod) {
        Map<String, String> metadata = new HashMap<>();
        metadata.put("scope", "tenant_runtime_enforcement");
        metadata.put("reasonCode", rejection.reasonCode);
        metadata.put("reasonDetail", rejection.reasonDetail);
        metadata.put("auditChainId", rejection.auditChainId);
        metadata.put("tenantState", rejection.tenantState.name());
        metadata.put("tenantReasonCode", rejection.tenantReasonCode);
        metadata.put("limitType", rejection.limitType);
        metadata.put("observedValue", rejection.observedValue);
        metadata.put("limitValue", rejection.limitValue);
        if (StringUtils.hasText(requestPath)) {
            metadata.put("requestPath", requestPath.trim());
        }
        if (StringUtils.hasText(requestMethod)) {
            metadata.put("requestMethod", requestMethod.trim().toUpperCase());
        }
        auditService.logAuthFailure(
                AuditEvent.ACCESS_DENIED,
                normalizeActor(actor),
                rejection.companyCode,
                metadata);
    }

    private Long resolveActiveUsers(String companyCode) {
        return companyRepository.findByCodeIgnoreCase(companyCode)
                .map(Company::getId)
                .map(userAccountRepository::countDistinctByCompanies_IdAndEnabledTrue)
                .orElse(0L);
    }

    private int incrementMinuteCount(TenantRuntimeCounters usageCounters, long minuteBucket) {
        while (true) {
            long previousBucket = usageCounters.minuteBucket.get();
            if (previousBucket != minuteBucket) {
                if (usageCounters.minuteBucket.compareAndSet(previousBucket, minuteBucket)) {
                    usageCounters.minuteRequestCount.set(0);
                    usageCounters.minuteRejectedCount.set(0);
                } else {
                    continue;
                }
            }
            return usageCounters.minuteRequestCount.incrementAndGet();
        }
    }

    private int incrementMinuteRejectedCount(TenantRuntimeCounters usageCounters, long minuteBucket) {
        while (true) {
            long previousBucket = usageCounters.minuteBucket.get();
            if (previousBucket != minuteBucket) {
                if (usageCounters.minuteBucket.compareAndSet(previousBucket, minuteBucket)) {
                    usageCounters.minuteRequestCount.set(0);
                    usageCounters.minuteRejectedCount.set(0);
                } else {
                    continue;
                }
            }
            return usageCounters.minuteRejectedCount.incrementAndGet();
        }
    }

    private void recordRejection(TenantRuntimeCounters usageCounters) {
        usageCounters.rejectedRequests.incrementAndGet();
        long minuteBucket = CompanyTime.now().getEpochSecond() / 60L;
        incrementMinuteRejectedCount(usageCounters, minuteBucket);
    }

    private TenantRuntimePolicy policyFor(String companyCode) {
        TenantRuntimePolicy current = policies.get(companyCode);
        long nowMillis = System.currentTimeMillis();
        if (current != null && current.policyRefreshAfterEpochMillis > nowMillis) {
            return current;
        }
        return policies.compute(companyCode, (key, cached) -> {
            long refreshCheckAt = System.currentTimeMillis();
            if (cached != null && cached.policyRefreshAfterEpochMillis > refreshCheckAt) {
                return cached;
            }
            TenantRuntimePolicy persisted;
            try {
                persisted = loadPersistedPolicy(key);
            } catch (RuntimeException ignored) {
                // Keep request admission available during transient policy persistence outages.
                persisted = null;
            }
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
            return resolved;
        });
    }

    private boolean shouldUsePersistedPolicy(TenantRuntimePolicy current, TenantRuntimePolicy persisted) {
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
                && (current.reasonCode == null ? persisted.reasonCode == null
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
        } catch (RuntimeException ignored) {
            return null;
        }
        if (company == null || company.getId() == null) {
            return null;
        }
        Long companyId = company.getId();
        String persistedState = readSetting(keyHoldState(companyId), null);
        String persistedReason = readSetting(keyHoldReason(companyId), null);
        String persistedMaxConcurrent = readSetting(keyMaxConcurrentRequests(companyId), null);
        String persistedMaxPerMinute = readSetting(keyMaxRequestsPerMinute(companyId), null);
        String persistedMaxActiveUsers = readSetting(keyMaxActiveUsers(companyId), null);
        String persistedPolicyReference = readSetting(keyPolicyReference(companyId), null);
        String persistedUpdatedAt = readSetting(keyPolicyUpdatedAt(companyId), null);

        boolean hasPersistedPolicy = StringUtils.hasText(persistedState)
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
        String reason = StringUtils.hasText(persistedReason)
                ? persistedReason.trim()
                : DEFAULT_REASON;
        int maxConcurrentRequests = parsePositiveInt(persistedMaxConcurrent, defaultMaxConcurrentRequests);
        int maxRequestsPerMinute = parsePositiveInt(persistedMaxPerMinute, defaultMaxRequestsPerMinute);
        int maxActiveUsers = parsePositiveInt(persistedMaxActiveUsers, defaultMaxActiveUsers);
        String auditChainId = StringUtils.hasText(persistedPolicyReference)
                ? persistedPolicyReference.trim()
                : DEFAULT_POLICY_REFERENCE;
        Instant updatedAt = parseInstantOrNow(persistedUpdatedAt);
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

    private TenantRuntimePolicy loadPersistedPolicyStrict(Long companyId) {
        if (companyId == null) {
            return null;
        }
        String persistedState = readSettingStrict(keyHoldState(companyId));
        String persistedReason = readSettingStrict(keyHoldReason(companyId));
        String persistedMaxConcurrent = readSettingStrict(keyMaxConcurrentRequests(companyId));
        String persistedMaxPerMinute = readSettingStrict(keyMaxRequestsPerMinute(companyId));
        String persistedMaxActiveUsers = readSettingStrict(keyMaxActiveUsers(companyId));
        String persistedPolicyReference = readSettingStrict(keyPolicyReference(companyId));
        String persistedUpdatedAt = readSettingStrict(keyPolicyUpdatedAt(companyId));

        boolean hasPersistedPolicy = StringUtils.hasText(persistedState)
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
        String reason = StringUtils.hasText(persistedReason)
                ? persistedReason.trim()
                : DEFAULT_REASON;
        int maxConcurrentRequests = parsePositiveInt(persistedMaxConcurrent, defaultMaxConcurrentRequests);
        int maxRequestsPerMinute = parsePositiveInt(persistedMaxPerMinute, defaultMaxRequestsPerMinute);
        int maxActiveUsers = parsePositiveInt(persistedMaxActiveUsers, defaultMaxActiveUsers);
        String auditChainId = StringUtils.hasText(persistedPolicyReference)
                ? persistedPolicyReference.trim()
                : DEFAULT_POLICY_REFERENCE;
        Instant updatedAt = parseInstantOrNow(persistedUpdatedAt);
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
                UUID.randomUUID().toString(),
                CompanyTime.now(),
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
        try {
            int parsed = Integer.parseInt(rawValue.trim());
            return parsed > 0 ? parsed : fallback;
        } catch (NumberFormatException ignored) {
            return fallback;
        }
    }

    private Instant parseInstantOrNow(String rawValue) {
        if (!StringUtils.hasText(rawValue)) {
            return CompanyTime.now();
        }
        try {
            return Instant.parse(rawValue.trim());
        } catch (RuntimeException ignored) {
            return CompanyTime.now();
        }
    }

    private String readSetting(String key, String fallback) {
        try {
            return systemSettingsRepository.findById(key)
                    .map(SystemSetting::getValue)
                    .orElse(fallback);
        } catch (RuntimeException ignored) {
            return fallback;
        }
    }

    private String readSettingStrict(String key) {
        return systemSettingsRepository.findById(key)
                .map(SystemSetting::getValue)
                .orElse(null);
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

    private void persistPolicySettings(Long companyId,
                                       TenantRuntimeState state,
                                       String reasonCode,
                                       int maxConcurrentRequests,
                                       int maxRequestsPerMinute,
                                       int maxActiveUsers,
                                       String auditChainId,
                                       Instant updatedAt) {
        if (companyId == null || state == null) {
            return;
        }
        persistSetting(keyHoldState(companyId), state.name());
        persistSetting(keyHoldReason(companyId), reasonCode);
        persistSetting(keyMaxActiveUsers(companyId), Integer.toString(maxActiveUsers));
        persistSetting(keyMaxRequestsPerMinute(companyId), Integer.toString(maxRequestsPerMinute));
        persistSetting(keyMaxConcurrentRequests(companyId), Integer.toString(maxConcurrentRequests));
        persistSetting(keyPolicyReference(companyId), auditChainId);
        persistSetting(keyPolicyUpdatedAt(companyId),
                updatedAt != null ? updatedAt.toString() : CompanyTime.now().toString());
    }

    private void persistSetting(String key, String value) {
        String normalizedValue = value == null ? "" : value;
        systemSettingsRepository.save(new SystemSetting(key, normalizedValue));
    }

    private TenantRuntimeCounters countersFor(String companyCode) {
        return counters.computeIfAbsent(companyCode, key -> new TenantRuntimeCounters());
    }

    private String normalizeActor(String actor) {
        String normalized = normalizeToken(actor, null);
        if (!StringUtils.hasText(normalized)) {
            return UNKNOWN_ACTOR;
        }
        return normalized;
    }

    private String resolveMutationActor(String actor) {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication != null && authentication.isAuthenticated() && StringUtils.hasText(authentication.getName())) {
            return authentication.getName().trim();
        }
        return normalizeActor(actor);
    }

    private void requireRuntimePolicyMutationAuthority() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication == null || !authentication.isAuthenticated()) {
            throw new AccessDeniedException(
                    "Authenticated SUPER_ADMIN authority required for tenant runtime policy updates");
        }
        boolean superAdmin = authentication.getAuthorities().stream()
                .map(GrantedAuthority::getAuthority)
                .anyMatch(authority -> "ROLE_SUPER_ADMIN".equalsIgnoreCase(authority));
        if (!superAdmin) {
            throw new AccessDeniedException("SUPER_ADMIN authority required for tenant runtime policy updates");
        }
    }

    private String requireCompanyCode(String companyCode) {
        String normalized = normalizeCompanyCode(companyCode);
        if (normalized == null) {
            throw new IllegalArgumentException("Company code is required");
        }
        return normalized;
    }

    private String requireStateReason(String reasonCode, TenantRuntimeState targetState) {
        if (targetState == TenantRuntimeState.ACTIVE) {
            return DEFAULT_REASON;
        }
        if (!StringUtils.hasText(reasonCode)) {
            throw new IllegalArgumentException("Reason code is required for " + targetState.name() + " state updates");
        }
        return normalizeReason(reasonCode);
    }

    private Integer validateRuntimeLimit(Integer requestedLimit, String fieldName) {
        if (requestedLimit == null) {
            return null;
        }
        if (requestedLimit < MIN_LIMIT) {
            throw new IllegalArgumentException(fieldName + " must be at least " + MIN_LIMIT);
        }
        return requestedLimit;
    }

    private String policyChangeAction(boolean stateMutation,
                                      boolean quotaMutation,
                                      TenantRuntimeState targetState) {
        if (stateMutation && quotaMutation) {
            return "UPDATE_TENANT_RUNTIME_POLICY";
        }
        if (quotaMutation) {
            return "UPDATE_TENANT_QUOTAS";
        }
        if (targetState == TenantRuntimeState.HOLD) {
            return "HOLD_TENANT";
        }
        if (targetState == TenantRuntimeState.BLOCKED) {
            return "BLOCK_TENANT";
        }
        return "RESUME_TENANT";
    }

    private String normalizeCompanyCode(String companyCode) {
        String normalized = normalizeToken(companyCode, null);
        return StringUtils.hasText(normalized) ? normalized : null;
    }

    private String normalizeReason(String reasonCode) {
        return normalizeToken(reasonCode, DEFAULT_REASON);
    }

    private String normalizeToken(String value, String fallback) {
        if (!StringUtils.hasText(value)) {
            return fallback;
        }
        return value.trim().toUpperCase();
    }

    private int sanitizeLimit(int limit) {
        return Math.max(MIN_LIMIT, limit);
    }

    public enum TenantRuntimeState {
        ACTIVE,
        HOLD,
        BLOCKED
    }

    public record TenantRuntimeMetrics(long totalRequests,
                                       long rejectedRequests,
                                       long errorResponses,
                                       int inFlightRequests,
                                       int minuteRequestCount,
                                       int minuteRejectedCount,
                                       long activeUsers) {
    }

    public record TenantRuntimeSnapshot(String companyCode,
                                        TenantRuntimeState state,
                                        String reasonCode,
                                        String auditChainId,
                                        Instant updatedAt,
                                        int maxConcurrentRequests,
                                        int maxRequestsPerMinute,
                                        int maxActiveUsers,
                                        TenantRuntimeMetrics metrics) {
    }

    public static final class TenantRequestAdmission {
        private static final TenantRequestAdmission NOT_TRACKED =
                new TenantRequestAdmission(false, null, null, null, HttpStatus.OK.value(), null, false);

        private final boolean admitted;
        private final String companyCode;
        private final String auditChainId;
        private final TenantRuntimeCounters counters;
        private final int statusCode;
        private final String message;
        private final boolean policyControlRequest;
        private final String reasonCode;
        private final String limitType;
        private final String observedValue;
        private final String limitValue;

        private TenantRequestAdmission(boolean admitted,
                                       String companyCode,
                                       String auditChainId,
                                       TenantRuntimeCounters counters,
                                       int statusCode,
                                       String message) {
            this(admitted, companyCode, auditChainId, counters, statusCode, message, false);
        }

        private TenantRequestAdmission(boolean admitted,
                                       String companyCode,
                                       String auditChainId,
                                       TenantRuntimeCounters counters,
                                       int statusCode,
                                       String message,
                                       boolean policyControlRequest) {
            this(admitted,
                    companyCode,
                    auditChainId,
                    counters,
                    statusCode,
                    message,
                    policyControlRequest,
                    null,
                    null,
                    null,
                    null);
        }

        private TenantRequestAdmission(boolean admitted,
                                       String companyCode,
                                       String auditChainId,
                                       TenantRuntimeCounters counters,
                                       int statusCode,
                                       String message,
                                       boolean policyControlRequest,
                                       String reasonCode,
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
            this.limitType = limitType;
            this.observedValue = observedValue;
            this.limitValue = limitValue;
        }

        public static TenantRequestAdmission notTracked() {
            return NOT_TRACKED;
        }

        public static TenantRequestAdmission admitted(String companyCode,
                                                      String auditChainId,
                                                      TenantRuntimeCounters counters) {
            return new TenantRequestAdmission(true, companyCode, auditChainId, counters, HttpStatus.OK.value(), null);
        }

        public static TenantRequestAdmission admittedPolicyControl(String companyCode,
                                                                   String auditChainId,
                                                                   TenantRuntimeCounters counters) {
            return new TenantRequestAdmission(
                    true,
                    companyCode,
                    auditChainId,
                    counters,
                    HttpStatus.OK.value(),
                    null,
                    true);
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

        private TenantRuntimePolicy(TenantRuntimeState state,
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

    private record TenantRuntimeRejection(String companyCode,
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
    }
}
