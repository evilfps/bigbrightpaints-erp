package com.bigbrightpaints.erp.modules.portal.service;

import com.bigbrightpaints.erp.core.audit.AuditEvent;
import com.bigbrightpaints.erp.core.audit.AuditService;
import com.bigbrightpaints.erp.core.config.SystemSetting;
import com.bigbrightpaints.erp.core.config.SystemSettingsRepository;
import com.bigbrightpaints.erp.core.exception.ApplicationException;
import com.bigbrightpaints.erp.core.exception.ErrorCode;
import com.bigbrightpaints.erp.core.util.CompanyTime;
import com.bigbrightpaints.erp.modules.company.domain.Company;
import com.bigbrightpaints.erp.modules.company.service.CompanyContextService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.atomic.AtomicInteger;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import org.springframework.web.servlet.HandlerInterceptor;

@Component
public class TenantRuntimeEnforcementInterceptor implements HandlerInterceptor {

    private static final Logger log = LoggerFactory.getLogger(TenantRuntimeEnforcementInterceptor.class);

    private static final String HOLD_STATE_ACTIVE = "ACTIVE";
    private static final String HOLD_STATE_HOLD = "HOLD";
    private static final String HOLD_STATE_BLOCKED = "BLOCKED";
    private static final int DEFAULT_MAX_REQUESTS_PER_MINUTE = 1200;
    private static final int DEFAULT_MAX_CONCURRENT_REQUESTS = 40;

    private static final String ATTR_ENFORCED = TenantRuntimeEnforcementInterceptor.class.getName() + ".enforced";
    private static final String ATTR_COMPANY_ID = TenantRuntimeEnforcementInterceptor.class.getName() + ".companyId";

    private final CompanyContextService companyContextService;
    private final SystemSettingsRepository systemSettingsRepository;
    private final AuditService auditService;

    private final ConcurrentMap<Long, AtomicInteger> inFlightByCompany = new ConcurrentHashMap<>();
    private final ConcurrentMap<Long, MinuteWindowCounter> minuteCountersByCompany = new ConcurrentHashMap<>();

    public TenantRuntimeEnforcementInterceptor(CompanyContextService companyContextService,
                                               SystemSettingsRepository systemSettingsRepository,
                                               AuditService auditService) {
        this.companyContextService = companyContextService;
        this.systemSettingsRepository = systemSettingsRepository;
        this.auditService = auditService;
    }

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
        String path = request.getRequestURI();
        if (!isEnforcedPath(path)) {
            return true;
        }

        Company company = companyContextService.requireCurrentCompany();
        Long companyId = company.getId();
        RuntimePolicy policy = loadPolicy(companyId);
        long minuteEpoch = currentMinute();

        if (!HOLD_STATE_ACTIVE.equals(policy.holdState())) {
            int blockedCount = blockedCounter(companyId).incrementBlocked(minuteEpoch);
            persistMetricSnapshot(companyId, minuteEpoch, 0, blockedCount, inFlightCount(companyId));
            String failureReason = "Tenant runtime state is " + policy.holdState();
            auditDenied(company, request, policy, "TENANT_RUNTIME_STATE_DENIED", failureReason, Map.of(
                    "holdState", policy.holdState(),
                    "holdReason", safe(policy.holdReason())
            ));
            throw tenantStateException(company, policy, path);
        }

        MinuteWindowCounter minuteWindowCounter = blockedCounter(companyId);
        int requestsThisMinute = minuteWindowCounter.incrementRequests(minuteEpoch);
        if (requestsThisMinute > policy.maxRequestsPerMinute()) {
            int blockedCount = minuteWindowCounter.incrementBlocked(minuteEpoch);
            persistMetricSnapshot(companyId, minuteEpoch, requestsThisMinute, blockedCount, inFlightCount(companyId));
            String failureReason = "Requests per minute exceeded";
            auditDenied(company, request, policy, "TENANT_RUNTIME_RATE_LIMIT_DENIED", failureReason, Map.of(
                    "quotaType", "REQUESTS_PER_MINUTE",
                    "quotaValue", Integer.toString(policy.maxRequestsPerMinute()),
                    "observed", Integer.toString(requestsThisMinute)
            ));
            throw quotaException(
                    company,
                    policy,
                    path,
                    "REQUESTS_PER_MINUTE",
                    policy.maxRequestsPerMinute(),
                    requestsThisMinute
            );
        }

        AtomicInteger inFlightCounter = inFlightByCompany.computeIfAbsent(companyId, ignored -> new AtomicInteger());
        int inFlight = inFlightCounter.incrementAndGet();
        if (inFlight > policy.maxConcurrentRequests()) {
            inFlightCounter.decrementAndGet();
            int blockedCount = minuteWindowCounter.incrementBlocked(minuteEpoch);
            persistMetricSnapshot(companyId, minuteEpoch, requestsThisMinute, blockedCount, inFlightCount(companyId));
            String failureReason = "Concurrent request limit exceeded";
            auditDenied(company, request, policy, "TENANT_RUNTIME_CONCURRENCY_DENIED", failureReason, Map.of(
                    "quotaType", "CONCURRENT_REQUESTS",
                    "quotaValue", Integer.toString(policy.maxConcurrentRequests()),
                    "observed", Integer.toString(inFlight)
            ));
            throw quotaException(
                    company,
                    policy,
                    path,
                    "CONCURRENT_REQUESTS",
                    policy.maxConcurrentRequests(),
                    inFlight
            );
        }

        persistMetricSnapshot(companyId, minuteEpoch, requestsThisMinute, minuteWindowCounter.blocked(minuteEpoch), inFlight);
        request.setAttribute(ATTR_ENFORCED, Boolean.TRUE);
        request.setAttribute(ATTR_COMPANY_ID, companyId);
        return true;
    }

    @Override
    public void afterCompletion(HttpServletRequest request, HttpServletResponse response, Object handler, Exception ex) {
        if (!Boolean.TRUE.equals(request.getAttribute(ATTR_ENFORCED))) {
            return;
        }
        Object companyIdAttr = request.getAttribute(ATTR_COMPANY_ID);
        if (!(companyIdAttr instanceof Long companyId)) {
            return;
        }
        AtomicInteger inFlightCounter = inFlightByCompany.get(companyId);
        if (inFlightCounter == null) {
            return;
        }
        int inFlight = inFlightCounter.decrementAndGet();
        if (inFlight < 0) {
            inFlightCounter.set(0);
            inFlight = 0;
        }
        MinuteWindowCounter minuteWindowCounter = blockedCounter(companyId);
        long minuteEpoch = currentMinute();
        persistMetricSnapshot(
                companyId,
                minuteEpoch,
                minuteWindowCounter.requests(minuteEpoch),
                minuteWindowCounter.blocked(minuteEpoch),
                inFlight
        );
    }

    private boolean isEnforcedPath(String path) {
        if (!StringUtils.hasText(path)) {
            return false;
        }
        return path.startsWith("/api/v1/reports/")
                || path.startsWith("/api/v1/accounting/reports/")
                || path.startsWith("/api/v1/portal/")
                || path.startsWith("/api/v1/demo/");
    }

    private void persistMetricSnapshot(Long companyId,
                                       long minuteEpoch,
                                       int requestsThisMinute,
                                       int blockedThisMinute,
                                       int inFlightRequests) {
        persistSetting(keyMetricMinuteEpoch(companyId), Long.toString(minuteEpoch));
        persistSetting(keyMetricRequestsMinute(companyId), Integer.toString(Math.max(requestsThisMinute, 0)));
        persistSetting(keyMetricBlockedMinute(companyId), Integer.toString(Math.max(blockedThisMinute, 0)));
        persistSetting(keyMetricInFlight(companyId), Integer.toString(Math.max(inFlightRequests, 0)));
    }

    private void persistSetting(String key, String value) {
        try {
            systemSettingsRepository.save(new SystemSetting(key, value));
        } catch (RuntimeException ex) {
            log.debug("Unable to persist tenant runtime metric key {}", key, ex);
        }
    }

    private RuntimePolicy loadPolicy(Long companyId) {
        String holdState = normalizeHoldState(readSetting(keyHoldState(companyId), HOLD_STATE_ACTIVE));
        String holdReason = trimToNull(readSetting(keyHoldReason(companyId), null));
        if (HOLD_STATE_ACTIVE.equals(holdState)) {
            holdReason = null;
        }
        int maxRequestsPerMinute = parsePositiveInt(
                readSetting(keyMaxRequestsPerMinute(companyId), null),
                DEFAULT_MAX_REQUESTS_PER_MINUTE
        );
        int maxConcurrentRequests = parsePositiveInt(
                readSetting(keyMaxConcurrentRequests(companyId), null),
                DEFAULT_MAX_CONCURRENT_REQUESTS
        );
        String policyReference = readSetting(keyPolicyReference(companyId), "bootstrap");
        return new RuntimePolicy(holdState, holdReason, maxRequestsPerMinute, maxConcurrentRequests, policyReference);
    }

    private String readSetting(String key, String fallback) {
        return systemSettingsRepository.findById(key)
                .map(SystemSetting::getValue)
                .orElse(fallback);
    }

    private String normalizeHoldState(String raw) {
        if (!StringUtils.hasText(raw)) {
            return HOLD_STATE_ACTIVE;
        }
        String normalized = raw.trim().toUpperCase(Locale.ROOT);
        return switch (normalized) {
            case HOLD_STATE_ACTIVE, HOLD_STATE_HOLD, HOLD_STATE_BLOCKED -> normalized;
            default -> HOLD_STATE_ACTIVE;
        };
    }

    private int parsePositiveInt(String raw, int fallback) {
        if (!StringUtils.hasText(raw)) {
            return fallback;
        }
        try {
            int parsed = Integer.parseInt(raw.trim());
            return parsed > 0 ? parsed : fallback;
        } catch (NumberFormatException ex) {
            return fallback;
        }
    }

    private void auditDenied(Company company,
                             HttpServletRequest request,
                             RuntimePolicy policy,
                             String action,
                             String failureReason,
                             Map<String, String> details) {
        Map<String, String> metadata = new java.util.HashMap<>();
        metadata.put("companyCode", safe(company.getCode()));
        metadata.put("policyReference", safe(policy.policyReference()));
        metadata.put("action", safe(action));
        metadata.put("reason", safe(failureReason));
        metadata.put("holdState", safe(policy.holdState()));
        metadata.put("holdReason", safe(policy.holdReason()));
        metadata.put("requestPath", safe(request.getRequestURI()));
        metadata.put("requestMethod", safe(request.getMethod()));
        metadata.put("remoteAddr", safe(request.getRemoteAddr()));
        metadata.put("requestId", safe(headerValue(request, "X-Request-Id")));
        metadata.put("traceId", safe(headerValue(request, "X-Trace-Id")));
        metadata.put("userAgent", safe(headerValue(request, "User-Agent")));
        metadata.putAll(details);
        metadata.put("occurredAt", CompanyTime.now().toString());
        auditService.logFailure(AuditEvent.ACCESS_DENIED, metadata);
    }

    private String headerValue(HttpServletRequest request, String headerName) {
        if (request == null || !StringUtils.hasText(headerName)) {
            return null;
        }
        return trimToNull(request.getHeader(headerName));
    }

    private int inFlightCount(Long companyId) {
        AtomicInteger counter = inFlightByCompany.get(companyId);
        return counter != null ? Math.max(counter.get(), 0) : 0;
    }

    private MinuteWindowCounter blockedCounter(Long companyId) {
        return minuteCountersByCompany.computeIfAbsent(companyId, ignored -> new MinuteWindowCounter());
    }

    private ApplicationException tenantStateException(Company company, RuntimePolicy policy, String path) {
        String message = "Tenant runtime is " + policy.holdState() + "; access denied";
        return new ApplicationException(ErrorCode.BUSINESS_INVALID_STATE, message)
                .withDetail("companyCode", company.getCode())
                .withDetail("holdState", policy.holdState())
                .withDetail("holdReason", policy.holdReason())
                .withDetail("policyReference", policy.policyReference())
                .withDetail("path", path);
    }

    private ApplicationException quotaException(Company company,
                                                RuntimePolicy policy,
                                                String path,
                                                String quotaType,
                                                int quotaValue,
                                                int observedValue) {
        String message = "Tenant runtime quota exceeded: " + quotaType;
        return new ApplicationException(ErrorCode.BUSINESS_LIMIT_EXCEEDED, message)
                .withDetail("companyCode", company.getCode())
                .withDetail("quotaType", quotaType)
                .withDetail("quotaValue", quotaValue)
                .withDetail("observed", observedValue)
                .withDetail("policyReference", policy.policyReference())
                .withDetail("path", path);
    }

    private String trimToNull(String value) {
        if (!StringUtils.hasText(value)) {
            return null;
        }
        return value.trim();
    }

    private String safe(String value) {
        return value == null ? "" : value;
    }

    private long currentMinute() {
        return CompanyTime.now().getEpochSecond() / 60;
    }

    private String keyHoldState(Long companyId) {
        return "tenant.runtime.hold-state." + companyId;
    }

    private String keyHoldReason(Long companyId) {
        return "tenant.runtime.hold-reason." + companyId;
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

    private String keyMetricMinuteEpoch(Long companyId) {
        return "tenant.runtime.metrics.minute-epoch." + companyId;
    }

    private String keyMetricRequestsMinute(Long companyId) {
        return "tenant.runtime.metrics.requests-minute." + companyId;
    }

    private String keyMetricBlockedMinute(Long companyId) {
        return "tenant.runtime.metrics.blocked-minute." + companyId;
    }

    private String keyMetricInFlight(Long companyId) {
        return "tenant.runtime.metrics.inflight." + companyId;
    }

    private record RuntimePolicy(
            String holdState,
            String holdReason,
            int maxRequestsPerMinute,
            int maxConcurrentRequests,
            String policyReference
    ) {
    }

    private static final class MinuteWindowCounter {
        private long minuteEpoch = -1L;
        private int requests;
        private int blocked;

        synchronized int incrementRequests(long targetMinute) {
            rollIfNeeded(targetMinute);
            requests++;
            return requests;
        }

        synchronized int incrementBlocked(long targetMinute) {
            rollIfNeeded(targetMinute);
            blocked++;
            return blocked;
        }

        synchronized int requests(long targetMinute) {
            rollIfNeeded(targetMinute);
            return requests;
        }

        synchronized int blocked(long targetMinute) {
            rollIfNeeded(targetMinute);
            return blocked;
        }

        private void rollIfNeeded(long targetMinute) {
            if (targetMinute != minuteEpoch) {
                minuteEpoch = targetMinute;
                requests = 0;
                blocked = 0;
            }
        }
    }
}
