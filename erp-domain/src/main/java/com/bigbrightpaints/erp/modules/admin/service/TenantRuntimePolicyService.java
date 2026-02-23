package com.bigbrightpaints.erp.modules.admin.service;

import com.bigbrightpaints.erp.core.audit.AuditEvent;
import com.bigbrightpaints.erp.core.audit.AuditService;
import com.bigbrightpaints.erp.core.config.SystemSetting;
import com.bigbrightpaints.erp.core.config.SystemSettingsRepository;
import com.bigbrightpaints.erp.core.exception.ApplicationException;
import com.bigbrightpaints.erp.core.exception.ErrorCode;
import com.bigbrightpaints.erp.core.util.CompanyTime;
import com.bigbrightpaints.erp.modules.admin.dto.TenantRuntimeMetricsDto;
import com.bigbrightpaints.erp.modules.admin.dto.TenantRuntimePolicyUpdateRequest;
import com.bigbrightpaints.erp.modules.auth.domain.UserAccount;
import com.bigbrightpaints.erp.modules.auth.domain.UserAccountRepository;
import com.bigbrightpaints.erp.modules.company.domain.Company;
import com.bigbrightpaints.erp.modules.company.service.CompanyContextService;
import com.bigbrightpaints.erp.modules.company.service.TenantRuntimeEnforcementService;
import java.time.Instant;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

@Service
public class TenantRuntimePolicyService {

    private static final String HOLD_STATE_ACTIVE = "ACTIVE";
    private static final String HOLD_STATE_HOLD = "HOLD";
    private static final String HOLD_STATE_BLOCKED = "BLOCKED";
    private static final String DEFAULT_POLICY_REFERENCE = "bootstrap";
    private static final int DEFAULT_MAX_ACTIVE_USERS = 250;
    private static final int DEFAULT_MAX_REQUESTS_PER_MINUTE = 1200;
    private static final int DEFAULT_MAX_CONCURRENT_REQUESTS = 40;

    private final CompanyContextService companyContextService;
    private final SystemSettingsRepository systemSettingsRepository;
    private final UserAccountRepository userAccountRepository;
    private final AuditService auditService;
    private final TenantRuntimeEnforcementService tenantRuntimeEnforcementService;

    public TenantRuntimePolicyService(CompanyContextService companyContextService,
                                      SystemSettingsRepository systemSettingsRepository,
                                      UserAccountRepository userAccountRepository,
                                      AuditService auditService) {
        this(companyContextService, systemSettingsRepository, userAccountRepository, auditService, null);
    }

    @Autowired
    public TenantRuntimePolicyService(CompanyContextService companyContextService,
                                      SystemSettingsRepository systemSettingsRepository,
                                      UserAccountRepository userAccountRepository,
                                      AuditService auditService,
                                      TenantRuntimeEnforcementService tenantRuntimeEnforcementService) {
        this.companyContextService = companyContextService;
        this.systemSettingsRepository = systemSettingsRepository;
        this.userAccountRepository = userAccountRepository;
        this.auditService = auditService;
        this.tenantRuntimeEnforcementService = tenantRuntimeEnforcementService;
    }

    @Transactional(readOnly = true)
    public TenantRuntimeMetricsDto metrics() {
        Company company = companyContextService.requireCurrentCompany();
        if (tenantRuntimeEnforcementService != null) {
            return snapshotFromRuntimeEnforcementService(company);
        }
        return snapshot(company);
    }

    @Transactional
    public TenantRuntimeMetricsDto updatePolicy(TenantRuntimePolicyUpdateRequest request) {
        Company company = companyContextService.requireCurrentCompany();
        if (tenantRuntimeEnforcementService != null) {
            TenantRuntimeEnforcementService.TenantRuntimeSnapshot beforeUpdate =
                    tenantRuntimeEnforcementService.snapshot(company.getCode());
            TenantRuntimeEnforcementService.TenantRuntimeSnapshot updated =
                    tenantRuntimeEnforcementService.updatePolicy(
                            company.getCode(),
                            resolveTargetState(request != null ? request.holdState() : null),
                            request != null ? request.holdReason() : null,
                            request != null ? request.maxConcurrentRequests() : null,
                            request != null ? request.maxRequestsPerMinute() : null,
                            request != null ? request.maxActiveUsers() : null,
                            null
                    );
            auditPolicyUpdate(
                    company,
                    runtimePolicyFromSnapshot(beforeUpdate),
                    runtimePolicyFromSnapshot(updated),
                    request != null ? request.changeReason() : null);
            return toMetricsDto(company, updated);
        }
        RuntimePolicy previous = loadPolicy(company.getId());
        RuntimePolicy updated = merge(previous, request);

        String policyReference = UUID.randomUUID().toString();
        Instant updatedAt = CompanyTime.now();

        persistSetting(keyHoldState(company.getId()), updated.holdState());
        persistSetting(keyHoldReason(company.getId()), updated.holdReason());
        persistSetting(keyMaxActiveUsers(company.getId()), Integer.toString(updated.maxActiveUsers()));
        persistSetting(keyMaxRequestsPerMinute(company.getId()), Integer.toString(updated.maxRequestsPerMinute()));
        persistSetting(keyMaxConcurrentRequests(company.getId()), Integer.toString(updated.maxConcurrentRequests()));
        persistSetting(keyPolicyReference(company.getId()), policyReference);
        persistSetting(keyPolicyUpdatedAt(company.getId()), updatedAt.toString());

        RuntimePolicy latest = new RuntimePolicy(
                updated.holdState(),
                updated.holdReason(),
                updated.maxActiveUsers(),
                updated.maxRequestsPerMinute(),
                updated.maxConcurrentRequests(),
                policyReference,
                updatedAt
        );
        auditPolicyUpdate(company, previous, latest, request != null ? request.changeReason() : null);
        return snapshot(company);
    }

    public void assertCanAddEnabledUser(Company company, String operation) {
        if (company == null) {
            return;
        }
        RuntimePolicy policy;
        long enabledUsers;
        if (tenantRuntimeEnforcementService != null) {
            TenantRuntimeEnforcementService.TenantRuntimeSnapshot snapshot =
                    tenantRuntimeEnforcementService.snapshot(company.getCode());
            policy = runtimePolicyFromSnapshot(snapshot);
            enabledUsers = Math.max(snapshot.metrics().activeUsers(), 0L);
        } else {
            policy = loadPolicy(company.getId());
            enabledUsers = countEnabledUsers(company.getId());
        }
        if (enabledUsers >= policy.maxActiveUsers()) {
            String message = "Active user quota exceeded for tenant " + company.getCode();
            auditUserQuotaDenied(company, policy, enabledUsers, operation, message);
            throw new ApplicationException(ErrorCode.BUSINESS_LIMIT_EXCEEDED, message)
                    .withDetail("companyCode", company.getCode())
                    .withDetail("operation", operation)
                    .withDetail("enabledUsers", enabledUsers)
                    .withDetail("maxActiveUsers", policy.maxActiveUsers())
                    .withDetail("policyReference", policy.policyReference());
        }
    }

    private TenantRuntimeMetricsDto snapshotFromRuntimeEnforcementService(Company company) {
        TenantRuntimeEnforcementService.TenantRuntimeSnapshot snapshot =
                tenantRuntimeEnforcementService.snapshot(company.getCode());
        return toMetricsDto(company, snapshot);
    }

    private TenantRuntimeMetricsDto toMetricsDto(Company company,
                                                 TenantRuntimeEnforcementService.TenantRuntimeSnapshot snapshot) {
        long totalUsers = countTotalUsers(company.getId());
        long enabledUsers = Math.max(snapshot.metrics().activeUsers(), 0L);
        int requestsThisMinute = Math.max(snapshot.metrics().minuteRequestCount(), 0);
        int blockedThisMinute = Math.max(snapshot.metrics().minuteRejectedCount(), 0);
        int inFlightRequests = Math.max(snapshot.metrics().inFlightRequests(), 0);
        String holdState = snapshot.state().name();
        String holdReason = TenantRuntimeEnforcementService.TenantRuntimeState.ACTIVE == snapshot.state()
                ? null
                : trimToNull(snapshot.reasonCode());
        return new TenantRuntimeMetricsDto(
                company.getCode(),
                holdState,
                holdReason,
                snapshot.maxActiveUsers(),
                snapshot.maxRequestsPerMinute(),
                snapshot.maxConcurrentRequests(),
                enabledUsers,
                totalUsers,
                requestsThisMinute,
                blockedThisMinute,
                inFlightRequests,
                snapshot.auditChainId(),
                snapshot.updatedAt()
        );
    }

    private RuntimePolicy runtimePolicyFromSnapshot(TenantRuntimeEnforcementService.TenantRuntimeSnapshot snapshot) {
        return new RuntimePolicy(
                snapshot.state().name(),
                TenantRuntimeEnforcementService.TenantRuntimeState.ACTIVE == snapshot.state()
                        ? null
                        : trimToNull(snapshot.reasonCode()),
                snapshot.maxActiveUsers(),
                snapshot.maxRequestsPerMinute(),
                snapshot.maxConcurrentRequests(),
                snapshot.auditChainId(),
                snapshot.updatedAt()
        );
    }

    private TenantRuntimeEnforcementService.TenantRuntimeState resolveTargetState(String holdState) {
        if (!StringUtils.hasText(holdState)) {
            return null;
        }
        String normalized = holdState.trim().toUpperCase(Locale.ROOT);
        return switch (normalized) {
            case HOLD_STATE_ACTIVE -> TenantRuntimeEnforcementService.TenantRuntimeState.ACTIVE;
            case HOLD_STATE_HOLD -> TenantRuntimeEnforcementService.TenantRuntimeState.HOLD;
            case HOLD_STATE_BLOCKED -> TenantRuntimeEnforcementService.TenantRuntimeState.BLOCKED;
            default -> TenantRuntimeEnforcementService.TenantRuntimeState.BLOCKED;
        };
    }

    private TenantRuntimeMetricsDto snapshot(Company company) {
        RuntimePolicy policy = loadPolicy(company.getId());
        long totalUsers = countTotalUsers(company.getId());
        long enabledUsers = countEnabledUsers(company.getId());
        MetricSnapshot metrics = loadMetricSnapshot(company.getId());
        return new TenantRuntimeMetricsDto(
                company.getCode(),
                policy.holdState(),
                policy.holdReason(),
                policy.maxActiveUsers(),
                policy.maxRequestsPerMinute(),
                policy.maxConcurrentRequests(),
                enabledUsers,
                totalUsers,
                metrics.requestsThisMinute(),
                metrics.blockedThisMinute(),
                metrics.inFlightRequests(),
                policy.policyReference(),
                policy.policyUpdatedAt()
        );
    }

    private RuntimePolicy merge(RuntimePolicy previous, TenantRuntimePolicyUpdateRequest request) {
        if (request == null) {
            return previous;
        }
        String holdState = StringUtils.hasText(request.holdState())
                ? normalizeHoldState(request.holdState())
                : previous.holdState();
        String holdReason = request.holdReason() != null ? trimToNull(request.holdReason()) : previous.holdReason();
        if (HOLD_STATE_ACTIVE.equals(holdState)) {
            holdReason = null;
        } else if (!StringUtils.hasText(holdReason)) {
            throw new IllegalArgumentException("holdReason is required when holdState is HOLD or BLOCKED");
        }
        int maxActiveUsers = request.maxActiveUsers() != null ? request.maxActiveUsers() : previous.maxActiveUsers();
        int maxRequestsPerMinute = request.maxRequestsPerMinute() != null
                ? request.maxRequestsPerMinute() : previous.maxRequestsPerMinute();
        int maxConcurrentRequests = request.maxConcurrentRequests() != null
                ? request.maxConcurrentRequests() : previous.maxConcurrentRequests();
        if (maxActiveUsers < 1 || maxRequestsPerMinute < 1 || maxConcurrentRequests < 1) {
            throw new IllegalArgumentException("Quota values must be at least 1");
        }
        return new RuntimePolicy(
                holdState,
                holdReason,
                maxActiveUsers,
                maxRequestsPerMinute,
                maxConcurrentRequests,
                previous.policyReference(),
                previous.policyUpdatedAt()
        );
    }

    private RuntimePolicy loadPolicy(Long companyId) {
        String holdState = normalizeHoldState(readSetting(keyHoldState(companyId), HOLD_STATE_ACTIVE));
        String holdReason = trimToNull(readSetting(keyHoldReason(companyId), null));
        if (HOLD_STATE_ACTIVE.equals(holdState)) {
            holdReason = null;
        }
        int maxActiveUsers = parsePositiveInt(readSetting(keyMaxActiveUsers(companyId), null), DEFAULT_MAX_ACTIVE_USERS);
        int maxRequestsPerMinute = parsePositiveInt(
                readSetting(keyMaxRequestsPerMinute(companyId), null), DEFAULT_MAX_REQUESTS_PER_MINUTE);
        int maxConcurrentRequests = parsePositiveInt(
                readSetting(keyMaxConcurrentRequests(companyId), null), DEFAULT_MAX_CONCURRENT_REQUESTS);
        String policyReference = readSetting(keyPolicyReference(companyId), DEFAULT_POLICY_REFERENCE);
        Instant policyUpdatedAt = parseInstant(readSetting(keyPolicyUpdatedAt(companyId), null));
        return new RuntimePolicy(
                holdState,
                holdReason,
                maxActiveUsers,
                maxRequestsPerMinute,
                maxConcurrentRequests,
                StringUtils.hasText(policyReference) ? policyReference : DEFAULT_POLICY_REFERENCE,
                policyUpdatedAt
        );
    }

    private MetricSnapshot loadMetricSnapshot(Long companyId) {
        long currentMinute = currentMinute();
        long recordedMinute = parseLong(readSetting(keyMetricMinuteEpoch(companyId), null), -1L);
        int requestsThisMinute = 0;
        int blockedThisMinute = 0;
        if (recordedMinute == currentMinute) {
            requestsThisMinute = parseNonNegativeInt(readSetting(keyMetricRequestsMinute(companyId), null));
            blockedThisMinute = parseNonNegativeInt(readSetting(keyMetricBlockedMinute(companyId), null));
        }
        int inFlightRequests = parseNonNegativeInt(readSetting(keyMetricInFlight(companyId), null));
        return new MetricSnapshot(requestsThisMinute, blockedThisMinute, inFlightRequests);
    }

    private void auditPolicyUpdate(Company company,
                                   RuntimePolicy previous,
                                   RuntimePolicy updated,
                                   String changeReason) {
        Map<String, String> metadata = new HashMap<>();
        metadata.put("companyCode", safe(company.getCode()));
        metadata.put("policyReference", safe(updated.policyReference()));
        metadata.put("oldHoldState", safe(previous.holdState()));
        metadata.put("newHoldState", safe(updated.holdState()));
        metadata.put("oldMaxActiveUsers", Integer.toString(previous.maxActiveUsers()));
        metadata.put("newMaxActiveUsers", Integer.toString(updated.maxActiveUsers()));
        metadata.put("oldMaxRequestsPerMinute", Integer.toString(previous.maxRequestsPerMinute()));
        metadata.put("newMaxRequestsPerMinute", Integer.toString(updated.maxRequestsPerMinute()));
        metadata.put("oldMaxConcurrentRequests", Integer.toString(previous.maxConcurrentRequests()));
        metadata.put("newMaxConcurrentRequests", Integer.toString(updated.maxConcurrentRequests()));
        metadata.put("holdReason", safe(updated.holdReason()));
        metadata.put("changeReason", safe(changeReason));
        metadata.put("requestId", safe(currentRequestId()));
        metadata.put("traceId", safe(currentTraceId()));
        metadata.put("ipAddress", safe(currentClientIp()));
        metadata.put("userAgent", safe(currentUserAgent()));
        auditService.logSuccess(AuditEvent.CONFIGURATION_CHANGED, metadata);
    }

    private void auditUserQuotaDenied(Company company,
                                      RuntimePolicy policy,
                                      long enabledUsers,
                                      String operation,
                                      String failureReason) {
        Map<String, String> metadata = new HashMap<>();
        metadata.put("companyCode", safe(company.getCode()));
        metadata.put("operation", safe(operation));
        metadata.put("policyReference", safe(policy.policyReference()));
        metadata.put("enabledUsers", Long.toString(enabledUsers));
        metadata.put("maxActiveUsers", Integer.toString(policy.maxActiveUsers()));
        metadata.put("reason", safe(failureReason));
        metadata.put("requestId", safe(currentRequestId()));
        metadata.put("traceId", safe(currentTraceId()));
        metadata.put("ipAddress", safe(currentClientIp()));
        metadata.put("userAgent", safe(currentUserAgent()));
        auditService.logFailure(AuditEvent.ACCESS_DENIED, metadata);
    }

    private String currentRequestId() {
        return currentHeader("X-Request-Id");
    }

    private String currentTraceId() {
        return currentHeader("X-Trace-Id");
    }

    private String currentUserAgent() {
        return currentHeader("User-Agent");
    }

    private String currentClientIp() {
        ServletRequestAttributes attributes = (ServletRequestAttributes) RequestContextHolder.getRequestAttributes();
        if (attributes == null || attributes.getRequest() == null) {
            return null;
        }
        String forwarded = attributes.getRequest().getHeader("X-Forwarded-For");
        if (StringUtils.hasText(forwarded)) {
            return forwarded.split(",")[0].trim();
        }
        return attributes.getRequest().getRemoteAddr();
    }

    private String currentHeader(String name) {
        ServletRequestAttributes attributes = (ServletRequestAttributes) RequestContextHolder.getRequestAttributes();
        if (attributes == null || attributes.getRequest() == null) {
            return null;
        }
        return trimToNull(attributes.getRequest().getHeader(name));
    }

    private long countTotalUsers(Long companyId) {
        return userAccountRepository.findDistinctByCompanies_Id(companyId).size();
    }

    private long countEnabledUsers(Long companyId) {
        return userAccountRepository.findDistinctByCompanies_Id(companyId).stream()
                .filter(UserAccount::isEnabled)
                .count();
    }

    private String readSetting(String key, String fallback) {
        return systemSettingsRepository.findById(key)
                .map(SystemSetting::getValue)
                .orElse(fallback);
    }

    private void persistSetting(String key, String value) {
        String normalizedValue = value == null ? "" : value;
        systemSettingsRepository.save(new SystemSetting(key, normalizedValue));
    }

    private String normalizeHoldState(String raw) {
        if (raw == null) {
            return HOLD_STATE_ACTIVE;
        }
        String normalized = raw.trim().toUpperCase(Locale.ROOT);
        return switch (normalized) {
            case HOLD_STATE_ACTIVE, HOLD_STATE_HOLD, HOLD_STATE_BLOCKED -> normalized;
            default -> HOLD_STATE_BLOCKED;
        };
    }

    private int parsePositiveInt(String value, int fallback) {
        if (!StringUtils.hasText(value)) {
            return fallback;
        }
        try {
            int parsed = Integer.parseInt(value.trim());
            return parsed > 0 ? parsed : fallback;
        } catch (NumberFormatException ignored) {
            return fallback;
        }
    }

    private int parseNonNegativeInt(String value) {
        if (!StringUtils.hasText(value)) {
            return 0;
        }
        try {
            int parsed = Integer.parseInt(value.trim());
            return Math.max(parsed, 0);
        } catch (NumberFormatException ignored) {
            return 0;
        }
    }

    private long parseLong(String value, long fallback) {
        if (!StringUtils.hasText(value)) {
            return fallback;
        }
        try {
            return Long.parseLong(value.trim());
        } catch (NumberFormatException ignored) {
            return fallback;
        }
    }

    private Instant parseInstant(String raw) {
        if (!StringUtils.hasText(raw)) {
            return null;
        }
        try {
            return Instant.parse(raw.trim());
        } catch (RuntimeException ignored) {
            return null;
        }
    }

    private long currentMinute() {
        return CompanyTime.now().getEpochSecond() / 60;
    }

    private String safe(String value) {
        return value == null ? "" : value;
    }

    private String trimToNull(String value) {
        if (!StringUtils.hasText(value)) {
            return null;
        }
        return value.trim();
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
            int maxActiveUsers,
            int maxRequestsPerMinute,
            int maxConcurrentRequests,
            String policyReference,
            Instant policyUpdatedAt
    ) {
    }

    private record MetricSnapshot(int requestsThisMinute, int blockedThisMinute, int inFlightRequests) {
    }
}
