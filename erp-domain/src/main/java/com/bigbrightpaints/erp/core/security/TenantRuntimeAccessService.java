package com.bigbrightpaints.erp.core.security;

import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.lang.Nullable;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.util.StringUtils;

import com.bigbrightpaints.erp.core.audit.AuditEvent;
import com.bigbrightpaints.erp.core.audit.AuditService;
import com.bigbrightpaints.erp.core.audittrail.AuditActionEventCommand;
import com.bigbrightpaints.erp.core.audittrail.AuditActionEventSource;
import com.bigbrightpaints.erp.core.audittrail.AuditActionEventStatus;
import com.bigbrightpaints.erp.core.audittrail.EnterpriseAuditTrailService;
import com.bigbrightpaints.erp.core.config.SystemSetting;
import com.bigbrightpaints.erp.core.config.SystemSettingsRepository;
import com.bigbrightpaints.erp.core.util.CompanyTime;
import com.bigbrightpaints.erp.modules.auth.domain.UserAccount;
import com.bigbrightpaints.erp.modules.auth.domain.UserPrincipal;
import com.bigbrightpaints.erp.modules.company.domain.Company;
import com.bigbrightpaints.erp.modules.company.domain.CompanyRepository;

import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

public class TenantRuntimeAccessService {

  private static final Logger log = LoggerFactory.getLogger(TenantRuntimeAccessService.class);
  private static final int HTTP_STATUS_TENANT_LOCKED = 423;
  private static final int HTTP_STATUS_TOO_MANY_REQUESTS = 429;
  private static final String SETTINGS_PREFIX = "tenant.runtime.";
  private static final String DEFAULT_TOKEN = "default";
  private static final String LEGACY_SETTINGS_PREFIX = SETTINGS_PREFIX;
  private static final String KEY_HOLD_STATE_PREFIX = SETTINGS_PREFIX + "hold-state.";
  private static final String KEY_HOLD_REASON_PREFIX = SETTINGS_PREFIX + "hold-reason.";
  private static final String KEY_MAX_CONCURRENT_REQUESTS_PREFIX =
      SETTINGS_PREFIX + "max-concurrent-requests.";
  private static final String KEY_MAX_REQUESTS_PER_MINUTE_PREFIX =
      SETTINGS_PREFIX + "max-requests-per-minute.";
  private static final String MODULE = "tenant-control-plane";
  private static final String ACTION = "TENANT_RUNTIME_ENFORCEMENT_DENIED";
  private static final String ENTITY_TYPE = "TENANT";

  private final CompanyRepository companyRepository;
  private final SystemSettingsRepository settingsRepository;
  private final AuditService auditService;
  private final EnterpriseAuditTrailService enterpriseAuditTrailService;
  @Nullable private final MeterRegistry meterRegistry;
  private final int defaultMaxConcurrentRequests;
  private final int defaultMaxRequestsPerMinute;
  private final long policyCacheTtlMillis;

  private final ConcurrentMap<String, CachedPolicy> policyCache = new ConcurrentHashMap<>();
  private final ConcurrentMap<String, TenantRuntimeMetrics> runtimeMetrics =
      new ConcurrentHashMap<>();

  public TenantRuntimeAccessService(
      CompanyRepository companyRepository,
      SystemSettingsRepository settingsRepository,
      AuditService auditService,
      EnterpriseAuditTrailService enterpriseAuditTrailService,
      @Nullable MeterRegistry meterRegistry,
      @Value("${erp.tenant.runtime.default.max-concurrent-requests:0}")
          int defaultMaxConcurrentRequests,
      @Value("${erp.tenant.runtime.default.max-requests-per-minute:0}")
          int defaultMaxRequestsPerMinute,
      @Value("${erp.tenant.runtime.policy-cache-seconds:15}") long policyCacheSeconds) {
    this.companyRepository = companyRepository;
    this.settingsRepository = settingsRepository;
    this.auditService = auditService;
    this.enterpriseAuditTrailService = enterpriseAuditTrailService;
    this.meterRegistry = meterRegistry;
    this.defaultMaxConcurrentRequests = Math.max(defaultMaxConcurrentRequests, 0);
    this.defaultMaxRequestsPerMinute = Math.max(defaultMaxRequestsPerMinute, 0);
    this.policyCacheTtlMillis = Math.max(policyCacheSeconds, 1L) * 1000L;
  }

  public AccessHandle acquire(String companyCode, HttpServletRequest request) {
    if (!StringUtils.hasText(companyCode)) {
      return AccessHandle.denied(
          HttpServletResponse.SC_FORBIDDEN, "Missing company context", "TENANT_CONTEXT_MISSING");
    }
    String normalizedCompanyCode = companyCode.trim();
    Company company;
    try {
      company = companyRepository.findByCodeIgnoreCase(normalizedCompanyCode).orElse(null);
    } catch (RuntimeException ex) {
      return AccessHandle.denied(
          HttpServletResponse.SC_SERVICE_UNAVAILABLE,
          "Tenant company lookup is unavailable",
          "TENANT_COMPANY_LOOKUP_UNAVAILABLE");
    }
    if (company == null) {
      return AccessHandle.denied(
          HttpServletResponse.SC_FORBIDDEN,
          "Access denied to company: " + normalizedCompanyCode,
          "TENANT_NOT_FOUND");
    }
    Long companyId = company.getId();
    if (companyId == null) {
      return AccessHandle.denied(
          HttpServletResponse.SC_FORBIDDEN,
          "Access denied to company: " + normalizedCompanyCode,
          "TENANT_NOT_FOUND");
    }
    String tenantToken = normalizeTenantToken(normalizedCompanyCode);
    String metricsKey = runtimeMetricsKey(companyId, tenantToken);
    TenantRuntimeMetrics metrics =
        runtimeMetrics.computeIfAbsent(metricsKey, key -> createMetrics(company.getCode()));
    metrics.totalRequests.incrementAndGet();

    TenantRuntimePolicy policy;
    try {
      policy = resolvePolicy(tenantToken, companyId);
    } catch (TenantRuntimePolicyResolutionFailure failure) {
      return denyUnavailable(
          company,
          request,
          metrics,
          failure.httpStatus(),
          failure.reasonCode(),
          failure.getMessage());
    }
    if (policy.state() == TenantRuntimeState.BLOCKED) {
      return deny(
          company,
          request,
          policy,
          metrics,
          HttpServletResponse.SC_FORBIDDEN,
          "TENANT_BLOCKED",
          "Tenant is currently blocked");
    }
    if (policy.state() == TenantRuntimeState.HOLD && isMutating(request.getMethod())) {
      return deny(
          company,
          request,
          policy,
          metrics,
          HTTP_STATUS_TENANT_LOCKED,
          "TENANT_ON_HOLD",
          "Tenant is currently on hold");
    }
    if (policy.maxRequestsPerMinute() > 0
        && !consumeRateQuota(metrics, policy.maxRequestsPerMinute())) {
      return deny(
          company,
          request,
          policy,
          metrics,
          HTTP_STATUS_TOO_MANY_REQUESTS,
          "TENANT_QUOTA_RATE_LIMIT",
          "Tenant request quota exceeded");
    }
    if (!acquireConcurrencySlot(metrics.activeRequests, policy.maxConcurrentRequests())) {
      return deny(
          company,
          request,
          policy,
          metrics,
          HTTP_STATUS_TOO_MANY_REQUESTS,
          "TENANT_QUOTA_CONCURRENCY_LIMIT",
          "Tenant concurrent request quota exceeded");
    }

    metrics.allowedRequests.incrementAndGet();
    return AccessHandle.allowed(
        "ALLOW", () -> metrics.activeRequests.updateAndGet(current -> Math.max(0L, current - 1L)));
  }

  public Optional<TenantRuntimeMetricsSnapshot> snapshot(String companyCode) {
    if (!StringUtils.hasText(companyCode)) {
      return Optional.empty();
    }
    String normalizedCompanyCode = companyCode.trim();
    String tenantToken = normalizeTenantToken(normalizedCompanyCode);
    TenantRuntimeMetrics metrics =
        companyRepository
            .findByCodeIgnoreCase(normalizedCompanyCode)
            .map(Company::getId)
            .map(companyId -> runtimeMetrics.get(runtimeMetricsKey(companyId, tenantToken)))
            .orElse(null);
    if (metrics == null) {
      metrics = runtimeMetrics.get(tenantToken);
    }
    if (metrics == null) {
      return Optional.empty();
    }
    return Optional.of(toSnapshot(metrics));
  }

  public Map<String, TenantRuntimeMetricsSnapshot> snapshotAll() {
    Map<String, TenantRuntimeMetrics> metricsSnapshot = new LinkedHashMap<>(runtimeMetrics);
    Map<String, Integer> tokenFrequency = new LinkedHashMap<>();
    metricsSnapshot.forEach(
        (metricsKey, metrics) -> tokenFrequency.merge(metricsToken(metricsKey), 1, Integer::sum));

    Map<String, TenantRuntimeMetricsSnapshot> snapshots = new LinkedHashMap<>();
    metricsSnapshot.forEach(
        (metricsKey, metrics) -> {
          String token = metricsToken(metricsKey);
          String snapshotKey = tokenFrequency.getOrDefault(token, 0) > 1 ? metricsKey : token;
          snapshots.put(snapshotKey, toSnapshot(metrics));
        });
    return Map.copyOf(snapshots);
  }

  void evictPolicyCache(String companyCode) {
    if (!StringUtils.hasText(companyCode)) {
      return;
    }
    companyRepository
        .findByCodeIgnoreCase(companyCode.trim())
        .map(Company::getId)
        .map(String::valueOf)
        .ifPresent(policyCache::remove);
  }

  private AccessHandle deny(
      Company company,
      HttpServletRequest request,
      TenantRuntimePolicy policy,
      TenantRuntimeMetrics metrics,
      int httpStatus,
      String reasonCode,
      String defaultMessage) {
    metrics.deniedRequests.incrementAndGet();
    if ("TENANT_ON_HOLD".equals(reasonCode)) {
      metrics.holdDeniedRequests.incrementAndGet();
    } else if ("TENANT_BLOCKED".equals(reasonCode)) {
      metrics.blockDeniedRequests.incrementAndGet();
    } else {
      metrics.quotaDeniedRequests.incrementAndGet();
    }

    String policyReason = policy.reasonCode();
    String message =
        StringUtils.hasText(policyReason)
            ? defaultMessage + " (" + policyReason + ")"
            : defaultMessage;
    recordDeniedAudit(company, request, policy, metrics, httpStatus, reasonCode, message);
    return AccessHandle.denied(httpStatus, message, reasonCode);
  }

  private AccessHandle denyUnavailable(
      Company company,
      HttpServletRequest request,
      TenantRuntimeMetrics metrics,
      int httpStatus,
      String reasonCode,
      String message) {
    return deny(
        company,
        request, new TenantRuntimePolicy(TenantRuntimeState.BLOCKED, null, defaultMaxConcurrentRequests, defaultMaxRequestsPerMinute),
        metrics,
        httpStatus,
        reasonCode,
        message);
  }

  private void recordDeniedAudit(
      Company company,
      HttpServletRequest request,
      TenantRuntimePolicy policy,
      TenantRuntimeMetrics metrics,
      int httpStatus,
      String reasonCode,
      String message) {
    Map<String, String> metadata = new LinkedHashMap<>();
    metadata.put("companyCode", company.getCode());
    metadata.put("reasonCode", reasonCode);
    metadata.put("tenantState", policy.state().name());
    if (StringUtils.hasText(policy.reasonCode())) {
      metadata.put("tenantReasonCode", policy.reasonCode());
    }
    metadata.put("maxConcurrentRequests", Integer.toString(policy.maxConcurrentRequests()));
    metadata.put("maxRequestsPerMinute", Integer.toString(policy.maxRequestsPerMinute()));
    metadata.put("activeRequests", Long.toString(metrics.activeRequests.get()));
    metadata.put("minuteWindowRequests", Long.toString(currentMinuteUsage(metrics)));
    metadata.put("httpStatus", Integer.toString(httpStatus));
    metadata.put("message", message);
    if (request != null) {
      metadata.put("requestMethod", safeRequestMethod(request));
      metadata.put("requestPath", safeRequestPath(request));
      String traceId = header(request, "X-Trace-Id");
      String requestId = header(request, "X-Request-Id");
      if (StringUtils.hasText(traceId)) {
        metadata.put("traceId", traceId);
      }
      if (StringUtils.hasText(requestId)) {
        metadata.put("requestId", requestId);
      }
    }

    logDeniedAccessEvent(company, metadata);

    UserAccount actor = resolveCurrentActor().orElse(null);
    enterpriseAuditTrailService.recordBusinessEvent(
        new AuditActionEventCommand(
            company,
            AuditActionEventSource.BACKEND,
            MODULE,
            ACTION,
            ENTITY_TYPE,
            company.getCode(),
            null,
            AuditActionEventStatus.FAILURE,
            reasonCode,
            null,
            null,
            parseUuid(header(request, "X-Correlation-Id")),
            header(request, "X-Request-Id"),
            header(request, "X-Trace-Id"),
            clientIp(request),
            header(request, "User-Agent"),
            actor,
            false,
            null,
            metadata,
            CompanyTime.now()));
  }

  private void logDeniedAccessEvent(Company company, Map<String, String> metadata) {
    String previousCompany = CompanyContextHolder.getCompanyCode();
    try {
      CompanyContextHolder.setCompanyCode(company.getCode());
      auditService.logFailure(AuditEvent.ACCESS_DENIED, metadata);
    } catch (RuntimeException ex) {
      log.warn("Unable to write legacy audit denial event for company {}", company.getCode(), ex);
    } finally {
      restoreCompanyContext(previousCompany);
    }
  }

  private TenantRuntimePolicy resolvePolicy(String tenantToken, Long companyId) {
    long nowMillis = System.currentTimeMillis();
    String companyCacheKey = companyId != null ? companyId.toString() : tenantToken;
    CachedPolicy cached = policyCache.get(companyCacheKey);
    if (cached != null && cached.expiresAtMillis() > nowMillis) {
      return cached.policy();
    }
    TenantRuntimePolicy fresh = loadPolicy(tenantToken, companyId);
    policyCache.put(companyCacheKey, new CachedPolicy(fresh, nowMillis + policyCacheTtlMillis));
    return fresh;
  }

  private TenantRuntimePolicy loadPolicy(String tenantToken, Long companyId) {
    String stateByCompanyId = setting(keyHoldState(companyId));
    String reasonByCompanyId = setting(keyHoldReason(companyId));
    String maxConcurrentByCompanyId = setting(keyMaxConcurrentRequests(companyId));
    String maxRateByCompanyId = setting(keyMaxRequestsPerMinute(companyId));
    String stateByLegacyCode = setting(legacyKey(tenantToken, "state"));
    String reasonByLegacyCode = setting(legacyKey(tenantToken, "reason-code"));
    String maxConcurrentByLegacyCode = setting(legacyKey(tenantToken, "quota.max-concurrent"));
    String maxRateByLegacyCode = setting(legacyKey(tenantToken, "quota.max-requests-per-minute"));

    TenantRuntimeState defaultState =
        parseLegacyTenantState(
            setting(legacyKey(DEFAULT_TOKEN, "state")), TenantRuntimeState.ACTIVE);
    TenantRuntimeState legacyState = parseLegacyTenantState(stateByLegacyCode, defaultState);

    int defaultConcurrent =
        parseNonNegative(
            setting(legacyKey(DEFAULT_TOKEN, "quota.max-concurrent")),
            defaultMaxConcurrentRequests);
    int legacyConcurrent = parseNonNegative(maxConcurrentByLegacyCode, defaultConcurrent);

    int defaultRate =
        parseNonNegative(
            setting(legacyKey(DEFAULT_TOKEN, "quota.max-requests-per-minute")),
            defaultMaxRequestsPerMinute);
    int legacyRate = parseNonNegative(maxRateByLegacyCode, defaultRate);

    TenantRuntimeState tenantState;
    if (StringUtils.hasText(stateByCompanyId)) {
      tenantState = TenantRuntimeState.parse(stateByCompanyId, TenantRuntimeState.BLOCKED);
    } else {
      tenantState = legacyState;
    }

    int maxConcurrent = parseNonNegative(maxConcurrentByCompanyId, legacyConcurrent);
    int maxRate = parseNonNegative(maxRateByCompanyId, legacyRate);
    String reasonCode =
        trimToNull(StringUtils.hasText(reasonByCompanyId) ? reasonByCompanyId : reasonByLegacyCode);

    return new TenantRuntimePolicy(tenantState, reasonCode, maxConcurrent, maxRate);
  }

  private TenantRuntimeMetrics createMetrics(String companyCode) {
    TenantRuntimeMetrics metrics = new TenantRuntimeMetrics();
    if (meterRegistry != null) {
      String tagCompany =
          StringUtils.hasText(companyCode)
              ? companyCode.trim().toUpperCase(Locale.ROOT)
              : "UNKNOWN";
      Gauge.builder("tenant.runtime.requests.active", metrics.activeRequests, AtomicLong::get)
          .tag("tenant", tagCompany)
          .description("In-flight requests currently running for a tenant")
          .register(meterRegistry);
      Gauge.builder("tenant.runtime.requests.total", metrics.totalRequests, AtomicLong::get)
          .tag("tenant", tagCompany)
          .description("Total requests observed for a tenant by runtime enforcement")
          .register(meterRegistry);
      Gauge.builder("tenant.runtime.requests.denied", metrics.deniedRequests, AtomicLong::get)
          .tag("tenant", tagCompany)
          .description("Requests denied by tenant runtime enforcement")
          .register(meterRegistry);
    }
    return metrics;
  }

  private TenantRuntimeMetricsSnapshot toSnapshot(TenantRuntimeMetrics metrics) {
    return new TenantRuntimeMetricsSnapshot(
        metrics.totalRequests.get(),
        metrics.allowedRequests.get(),
        metrics.deniedRequests.get(),
        metrics.holdDeniedRequests.get(),
        metrics.blockDeniedRequests.get(),
        metrics.quotaDeniedRequests.get(),
        metrics.activeRequests.get(),
        currentMinuteUsage(metrics));
  }

  private long currentMinuteUsage(TenantRuntimeMetrics metrics) {
    synchronized (metrics.rateWindowLock) {
      return metrics.rateWindowCount;
    }
  }

  private boolean consumeRateQuota(TenantRuntimeMetrics metrics, int maxRequestsPerMinute) {
    long nowMinute = CompanyTime.now().getEpochSecond() / 60L;
    synchronized (metrics.rateWindowLock) {
      if (metrics.rateWindowEpochMinute != nowMinute) {
        metrics.rateWindowEpochMinute = nowMinute;
        metrics.rateWindowCount = 0L;
      }
      if (metrics.rateWindowCount >= maxRequestsPerMinute) {
        return false;
      }
      metrics.rateWindowCount++;
      return true;
    }
  }

  private boolean acquireConcurrencySlot(AtomicLong activeRequests, int maxConcurrentRequests) {
    while (true) {
      long current = activeRequests.get();
      if (maxConcurrentRequests > 0 && current >= maxConcurrentRequests) {
        return false;
      }
      if (activeRequests.compareAndSet(current, current + 1L)) {
        return true;
      }
    }
  }

  private Optional<UserAccount> resolveCurrentActor() {
    Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
    if (authentication == null || !authentication.isAuthenticated()) {
      return Optional.empty();
    }
    Object principal = authentication.getPrincipal();
    if (principal instanceof UserPrincipal userPrincipal) {
      return Optional.ofNullable(userPrincipal.getUser());
    }
    return Optional.empty();
  }

  private String setting(String key) {
    try {
      return settingsRepository.findById(key).map(SystemSetting::getValue).orElse(null);
    } catch (RuntimeException ex) {
      throw new TenantRuntimePolicyResolutionFailure(
          HttpServletResponse.SC_SERVICE_UNAVAILABLE,
          "TENANT_RUNTIME_POLICY_UNAVAILABLE",
          "Tenant runtime policy is unavailable",
          ex);
    }
  }

  private String legacyKey(String tenantToken, String suffix) {
    return LEGACY_SETTINGS_PREFIX + tenantToken + "." + suffix;
  }

  private String keyHoldState(Long companyId) {
    return KEY_HOLD_STATE_PREFIX + companyId;
  }

  private String keyHoldReason(Long companyId) {
    return KEY_HOLD_REASON_PREFIX + companyId;
  }

  private String keyMaxConcurrentRequests(Long companyId) {
    return KEY_MAX_CONCURRENT_REQUESTS_PREFIX + companyId;
  }

  private String keyMaxRequestsPerMinute(Long companyId) {
    return KEY_MAX_REQUESTS_PER_MINUTE_PREFIX + companyId;
  }

  private String normalizeTenantToken(String companyCode) {
    String normalized = companyCode == null ? "" : companyCode.trim().toLowerCase(Locale.ROOT);
    if (!StringUtils.hasText(normalized)) {
      return "unknown";
    }
    return normalized.replaceAll("[^a-z0-9\\-]", "_");
  }

  private static boolean isMutating(String method) {
    if (!StringUtils.hasText(method)) {
      return true;
    }
    String normalized = method.trim().toUpperCase(Locale.ROOT);
    return !"GET".equals(normalized) && !"HEAD".equals(normalized) && !"OPTIONS".equals(normalized);
  }

  private static String trimToNull(String raw) {
    if (raw == null) {
      return null;
    }
    String trimmed = raw.trim();
    return trimmed.isEmpty() ? null : trimmed;
  }

  private static int parseNonNegative(String raw, int fallback) {
    if (!StringUtils.hasText(raw)) {
      return Math.max(fallback, 0);
    }
    try {
      int value = Integer.parseInt(raw.trim());
      return Math.max(value, 0);
    } catch (NumberFormatException ex) {
      return Math.max(fallback, 0);
    }
  }

  private static TenantRuntimeState parseLegacyTenantState(
      String raw, TenantRuntimeState fallbackWhenMissing) {
    if (!StringUtils.hasText(raw)) {
      return fallbackWhenMissing;
    }
    return TenantRuntimeState.parse(raw, TenantRuntimeState.BLOCKED);
  }

  private static String runtimeMetricsKey(Long companyId, String tenantToken) {
    if (companyId == null) {
      return tenantToken;
    }
    return companyId + ":" + tenantToken;
  }

  private static String metricsToken(String metricsKey) {
    if (!StringUtils.hasText(metricsKey)) {
      return "unknown";
    }
    int separator = metricsKey.indexOf(':');
    if (separator < 0 || separator >= metricsKey.length() - 1) {
      return metricsKey;
    }
    return metricsKey.substring(separator + 1);
  }

  private static UUID parseUuid(String raw) {
    if (!StringUtils.hasText(raw)) {
      return null;
    }
    try {
      return UUID.fromString(raw.trim());
    } catch (IllegalArgumentException ex) {
      return null;
    }
  }

  private static String safeRequestMethod(HttpServletRequest request) {
    return request != null ? request.getMethod() : "";
  }

  private static String safeRequestPath(HttpServletRequest request) {
    return request != null ? request.getRequestURI() : "";
  }

  private static String header(HttpServletRequest request, String headerName) {
    if (request == null || !StringUtils.hasText(headerName)) {
      return null;
    }
    String value = request.getHeader(headerName);
    return StringUtils.hasText(value) ? value.trim() : null;
  }

  private static String clientIp(HttpServletRequest request) {
    if (request == null) {
      return null;
    }
    String forwarded = header(request, "X-Forwarded-For");
    if (StringUtils.hasText(forwarded)) {
      int comma = forwarded.indexOf(',');
      return comma >= 0 ? forwarded.substring(0, comma).trim() : forwarded;
    }
    return request.getRemoteAddr();
  }

  private static void restoreCompanyContext(String previousCompany) {
    if (StringUtils.hasText(previousCompany)) {
      CompanyContextHolder.setCompanyCode(previousCompany);
    } else {
      CompanyContextHolder.clear();
    }
  }

  private enum TenantRuntimeState {
    ACTIVE,
    HOLD,
    BLOCKED;

    static TenantRuntimeState parse(String raw, TenantRuntimeState fallback) {
      if (!StringUtils.hasText(raw)) {
        return fallback;
      }
      try {
        return TenantRuntimeState.valueOf(raw.trim().toUpperCase(Locale.ROOT));
      } catch (IllegalArgumentException ex) {
        return fallback;
      }
    }
  }

  private record TenantRuntimePolicy(
      TenantRuntimeState state,
      String reasonCode,
      int maxConcurrentRequests,
      int maxRequestsPerMinute) {}

  private record CachedPolicy(TenantRuntimePolicy policy, long expiresAtMillis) {}

  private static final class TenantRuntimeMetrics {
    private final AtomicLong totalRequests = new AtomicLong();
    private final AtomicLong allowedRequests = new AtomicLong();
    private final AtomicLong deniedRequests = new AtomicLong();
    private final AtomicLong holdDeniedRequests = new AtomicLong();
    private final AtomicLong blockDeniedRequests = new AtomicLong();
    private final AtomicLong quotaDeniedRequests = new AtomicLong();
    private final AtomicLong activeRequests = new AtomicLong();
    private final Object rateWindowLock = new Object();
    private long rateWindowEpochMinute = -1L;
    private long rateWindowCount = 0L;
  }

  public record TenantRuntimeMetricsSnapshot(
      long totalRequests,
      long allowedRequests,
      long deniedRequests,
      long holdDeniedRequests,
      long blockDeniedRequests,
      long quotaDeniedRequests,
      long activeRequests,
      long minuteWindowRequests) {}

  public static final class AccessHandle implements AutoCloseable {
    private final boolean allowed;
    private final int httpStatus;
    private final String message;
    private final String reasonCode;
    private final Runnable releaseAction;
    private final AtomicBoolean closed = new AtomicBoolean(false);

    private AccessHandle(
        boolean allowed,
        int httpStatus,
        String message,
        String reasonCode,
        Runnable releaseAction) {
      this.allowed = allowed;
      this.httpStatus = httpStatus;
      this.message = message;
      this.reasonCode = reasonCode;
      this.releaseAction = releaseAction;
    }

    public static AccessHandle allowed(String reasonCode, Runnable releaseAction) {
      return new AccessHandle(true, HttpServletResponse.SC_OK, "OK", reasonCode, releaseAction);
    }

    public static AccessHandle denied(int httpStatus, String message, String reasonCode) {
      return new AccessHandle(false, httpStatus, message, reasonCode, () -> {});
    }

    public static AccessHandle noop() {
      return new AccessHandle(true, HttpServletResponse.SC_OK, "OK", "NOOP", () -> {});
    }

    public boolean allowed() {
      return allowed;
    }

    public int httpStatus() {
      return httpStatus;
    }

    public String message() {
      return message;
    }

    public String reasonCode() {
      return reasonCode;
    }

    @Override
    public void close() {
      if (closed.compareAndSet(false, true)) {
        releaseAction.run();
      }
    }
  }

  private static final class TenantRuntimePolicyResolutionFailure extends RuntimeException
  {
    private final int httpStatus;
    private final String reasonCode;

    private TenantRuntimePolicyResolutionFailure(
        int httpStatus, String reasonCode, String message, Throwable cause) {
      super(message, cause);
      this.httpStatus = httpStatus;
      this.reasonCode = reasonCode;
    }

    private int httpStatus() {
      return httpStatus;
    }

    private String reasonCode() {
      return reasonCode;
    }
  }
}
