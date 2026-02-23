package com.bigbrightpaints.erp.modules.company.service;

import com.bigbrightpaints.erp.core.audit.AuditEvent;
import com.bigbrightpaints.erp.core.audit.AuditService;
import com.bigbrightpaints.erp.core.util.CompanyTime;
import com.bigbrightpaints.erp.modules.auth.domain.UserAccountRepository;
import com.bigbrightpaints.erp.modules.company.domain.Company;
import com.bigbrightpaints.erp.modules.company.domain.CompanyRepository;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;
import org.springframework.web.server.ResponseStatusException;

@Service
public class TenantRuntimeEnforcementService {

    private static final int MIN_LIMIT = 1;
    private static final String DEFAULT_REASON = "POLICY_ACTIVE";
    private static final String UNKNOWN_ACTOR = "UNKNOWN_AUTH_ACTOR";
    private static final String TENANT_RUNTIME_POLICY_PATH = "/api/v1/admin/tenant-runtime/policy";
    private static final String CANONICAL_COMPANY_RUNTIME_POLICY_PREFIX = "/api/v1/companies/";
    private static final String CANONICAL_COMPANY_RUNTIME_POLICY_SUFFIX = "/tenant-runtime/policy";

    private final CompanyRepository companyRepository;
    private final UserAccountRepository userAccountRepository;
    private final AuditService auditService;
    private final int defaultMaxConcurrentRequests;
    private final int defaultMaxRequestsPerMinute;
    private final int defaultMaxActiveUsers;
    private final ConcurrentMap<String, TenantRuntimePolicy> policies = new ConcurrentHashMap<>();
    private final ConcurrentMap<String, TenantRuntimeCounters> counters = new ConcurrentHashMap<>();

    public TenantRuntimeEnforcementService(
            CompanyRepository companyRepository,
            UserAccountRepository userAccountRepository,
            AuditService auditService,
            @Value("${erp.tenant.runtime.default-max-concurrent-requests:200}") int defaultMaxConcurrentRequests,
            @Value("${erp.tenant.runtime.default-max-requests-per-minute:5000}") int defaultMaxRequestsPerMinute,
            @Value("${erp.tenant.runtime.default-max-active-users:500}") int defaultMaxActiveUsers) {
        this.companyRepository = companyRepository;
        this.userAccountRepository = userAccountRepository;
        this.auditService = auditService;
        this.defaultMaxConcurrentRequests = sanitizeLimit(defaultMaxConcurrentRequests);
        this.defaultMaxRequestsPerMinute = sanitizeLimit(defaultMaxRequestsPerMinute);
        this.defaultMaxActiveUsers = sanitizeLimit(defaultMaxActiveUsers);
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
            usageCounters.rejectedRequests.incrementAndGet();
            auditRejection(stateRejection, actor, requestPath, requestMethod);
            return TenantRequestAdmission.rejected(stateRejection);
        }

        if (policyControlRequest) {
            usageCounters.totalRequests.incrementAndGet();
            usageCounters.inFlightRequests.incrementAndGet();
            return TenantRequestAdmission.admitted(normalizedCompany, policy.auditChainId, usageCounters);
        }

        long minuteBucket = CompanyTime.now().getEpochSecond() / 60L;
        int requestsInMinute = incrementMinuteCount(usageCounters, minuteBucket);
        int maxRequestsPerMinute = policy.effectiveMaxRequestsPerMinute(defaultMaxRequestsPerMinute);
        if (requestsInMinute > maxRequestsPerMinute) {
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
            usageCounters.rejectedRequests.incrementAndGet();
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
    }

    public void enforceAuthOperationAllowed(String companyCode, String actor, String operation) {
        String normalizedCompany = requireCompanyCode(companyCode);
        String scope = "auth_" + normalizeToken(operation, "UNKNOWN");
        TenantRuntimePolicy policy = policyFor(normalizedCompany);
        TenantRuntimeCounters usageCounters = countersFor(normalizedCompany);

        TenantRuntimeRejection stateRejection = stateRejection(policy, normalizedCompany, false, "POST");
        if (stateRejection != null) {
            usageCounters.rejectedRequests.incrementAndGet();
            auditRejection(stateRejection, actor, null, null);
            throw stateRejection.asException();
        }

        Company company = companyRepository.findByCodeIgnoreCase(normalizedCompany)
                .orElseThrow(() -> new IllegalArgumentException("Company not found: " + normalizedCompany));
        long activeUsers = userAccountRepository.countDistinctByCompanies_IdAndEnabledTrue(company.getId());
        int maxActiveUsers = policy.effectiveMaxActiveUsers(defaultMaxActiveUsers);
        if (activeUsers > maxActiveUsers) {
            usageCounters.rejectedRequests.incrementAndGet();
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

    public TenantRuntimeSnapshot holdTenant(String companyCode, String reasonCode, String actor) {
        return updateState(companyCode, TenantRuntimeState.HOLD, reasonCode, actor, "HOLD_TENANT");
    }

    public TenantRuntimeSnapshot blockTenant(String companyCode, String reasonCode, String actor) {
        return updateState(companyCode, TenantRuntimeState.BLOCKED, reasonCode, actor, "BLOCK_TENANT");
    }

    public TenantRuntimeSnapshot resumeTenant(String companyCode, String actor) {
        return updateState(companyCode, TenantRuntimeState.ACTIVE, DEFAULT_REASON, actor, "RESUME_TENANT");
    }

    public TenantRuntimeSnapshot updateQuotas(String companyCode,
                                              Integer maxConcurrentRequests,
                                              Integer maxRequestsPerMinute,
                                              Integer maxActiveUsers,
                                              String reasonCode,
                                              String actor) {
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
            if (maxConcurrentRequests != null) {
                policy.maxConcurrentRequests = sanitizeLimit(maxConcurrentRequests);
            }
            if (maxRequestsPerMinute != null) {
                policy.maxRequestsPerMinute = sanitizeLimit(maxRequestsPerMinute);
            }
            if (maxActiveUsers != null) {
                policy.maxActiveUsers = sanitizeLimit(maxActiveUsers);
            }
            policy.reasonCode = normalizedReason;
            policy.updatedAt = now;
            policy.auditChainId = newChainId;
        }
        auditPolicyChange(
                "UPDATE_TENANT_QUOTAS",
                normalizedCompany,
                normalizeActor(actor),
                normalizedReason,
                previousChainId,
                policy.auditChainId,
                policy);
        return snapshot(normalizedCompany);
    }

    public TenantRuntimeSnapshot updatePolicy(String companyCode,
                                              TenantRuntimeState targetState,
                                              String reasonCode,
                                              Integer maxConcurrentRequests,
                                              Integer maxRequestsPerMinute,
                                              Integer maxActiveUsers,
                                              String actor) {
        String normalizedCompany = requireCompanyCode(companyCode);
        companyRepository.findByCodeIgnoreCase(normalizedCompany)
                .orElseThrow(() -> new IllegalArgumentException("Company not found: " + normalizedCompany));
        if (targetState == null
                && maxConcurrentRequests == null
                && maxRequestsPerMinute == null
                && maxActiveUsers == null) {
            throw new IllegalArgumentException("Runtime policy mutation payload is required");
        }
        TenantRuntimePolicy policy = policyFor(normalizedCompany);
        String normalizedReason = normalizeReason(reasonCode);
        String previousChainId;
        String newChainId = UUID.randomUUID().toString();
        Instant now = CompanyTime.now();
        synchronized (policy) {
            previousChainId = policy.auditChainId;
            if (targetState != null) {
                policy.state = targetState;
            }
            if (maxConcurrentRequests != null) {
                policy.maxConcurrentRequests = sanitizeLimit(maxConcurrentRequests);
            }
            if (maxRequestsPerMinute != null) {
                policy.maxRequestsPerMinute = sanitizeLimit(maxRequestsPerMinute);
            }
            if (maxActiveUsers != null) {
                policy.maxActiveUsers = sanitizeLimit(maxActiveUsers);
            }
            policy.reasonCode = normalizedReason;
            policy.updatedAt = now;
            policy.auditChainId = newChainId;
        }
        auditPolicyChange(
                "UPDATE_TENANT_RUNTIME_POLICY",
                normalizedCompany,
                normalizeActor(actor),
                normalizedReason,
                previousChainId,
                policy.auditChainId,
                policy);
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
                        activeUsers));
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
        String normalizedMethod = requestMethod.trim().toUpperCase();
        return !("GET".equals(normalizedMethod)
                || "HEAD".equals(normalizedMethod)
                || "OPTIONS".equals(normalizedMethod)
                || "TRACE".equals(normalizedMethod));
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
                } else {
                    continue;
                }
            }
            return usageCounters.minuteRequestCount.incrementAndGet();
        }
    }

    private TenantRuntimePolicy policyFor(String companyCode) {
        return policies.computeIfAbsent(companyCode, key -> new TenantRuntimePolicy(
                TenantRuntimeState.ACTIVE,
                DEFAULT_REASON,
                defaultMaxConcurrentRequests,
                defaultMaxRequestsPerMinute,
                defaultMaxActiveUsers,
                UUID.randomUUID().toString(),
                CompanyTime.now()));
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

    private String requireCompanyCode(String companyCode) {
        String normalized = normalizeCompanyCode(companyCode);
        if (normalized == null) {
            throw new IllegalArgumentException("Company code is required");
        }
        return normalized;
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
                new TenantRequestAdmission(false, null, null, null, HttpStatus.OK.value(), null);

        private final boolean admitted;
        private final String companyCode;
        private final String auditChainId;
        private final TenantRuntimeCounters counters;
        private final int statusCode;
        private final String message;

        private TenantRequestAdmission(boolean admitted,
                                       String companyCode,
                                       String auditChainId,
                                       TenantRuntimeCounters counters,
                                       int statusCode,
                                       String message) {
            this.admitted = admitted;
            this.companyCode = companyCode;
            this.auditChainId = auditChainId;
            this.counters = counters;
            this.statusCode = statusCode;
            this.message = message;
        }

        public static TenantRequestAdmission notTracked() {
            return NOT_TRACKED;
        }

        public static TenantRequestAdmission admitted(String companyCode,
                                                      String auditChainId,
                                                      TenantRuntimeCounters counters) {
            return new TenantRequestAdmission(true, companyCode, auditChainId, counters, HttpStatus.OK.value(), null);
        }

        public static TenantRequestAdmission rejected(TenantRuntimeRejection rejection) {
            return new TenantRequestAdmission(
                    false,
                    rejection.companyCode,
                    rejection.auditChainId,
                    null,
                    rejection.httpStatus.value(),
                    rejection.reasonDetail);
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
    }

    private static final class TenantRuntimePolicy {
        private volatile TenantRuntimeState state;
        private volatile String reasonCode;
        private volatile int maxConcurrentRequests;
        private volatile int maxRequestsPerMinute;
        private volatile int maxActiveUsers;
        private volatile String auditChainId;
        private volatile Instant updatedAt;

        private TenantRuntimePolicy(TenantRuntimeState state,
                                    String reasonCode,
                                    int maxConcurrentRequests,
                                    int maxRequestsPerMinute,
                                    int maxActiveUsers,
                                    String auditChainId,
                                    Instant updatedAt) {
            this.state = state;
            this.reasonCode = reasonCode;
            this.maxConcurrentRequests = maxConcurrentRequests;
            this.maxRequestsPerMinute = maxRequestsPerMinute;
            this.maxActiveUsers = maxActiveUsers;
            this.auditChainId = auditChainId;
            this.updatedAt = updatedAt;
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
