package com.bigbrightpaints.erp.modules.admin.service;

import com.bigbrightpaints.erp.core.audit.AuditEvent;
import com.bigbrightpaints.erp.core.audit.AuditService;
import com.bigbrightpaints.erp.core.exception.ApplicationException;
import com.bigbrightpaints.erp.core.exception.ErrorCode;
import com.bigbrightpaints.erp.modules.admin.dto.TenantRuntimeMetricsDto;
import com.bigbrightpaints.erp.modules.auth.domain.UserAccountRepository;
import com.bigbrightpaints.erp.modules.company.domain.Company;
import com.bigbrightpaints.erp.modules.company.service.CompanyContextService;
import java.util.HashMap;
import java.util.Map;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

@Service
public class TenantRuntimePolicyService {

    private final CompanyContextService companyContextService;
    private final UserAccountRepository userAccountRepository;
    private final AuditService auditService;
    private final com.bigbrightpaints.erp.modules.company.service.TenantRuntimeEnforcementService tenantRuntimeEnforcementService;

    public TenantRuntimePolicyService(CompanyContextService companyContextService,
                                      UserAccountRepository userAccountRepository,
                                      AuditService auditService,
                                      com.bigbrightpaints.erp.modules.company.service.TenantRuntimeEnforcementService tenantRuntimeEnforcementService) {
        this.companyContextService = companyContextService;
        this.userAccountRepository = userAccountRepository;
        this.auditService = auditService;
        this.tenantRuntimeEnforcementService = tenantRuntimeEnforcementService;
    }

    @Transactional(readOnly = true)
    public TenantRuntimeMetricsDto metrics() {
        Company company = companyContextService.requireCurrentCompany();
        return snapshot(company, tenantRuntimeEnforcementService.snapshot(company.getCode()));
    }

    public void assertCanAddEnabledUser(Company company, String operation) {
        if (company == null) {
            return;
        }
        com.bigbrightpaints.erp.modules.company.service.TenantRuntimeEnforcementService.TenantRuntimeSnapshot snapshot =
                tenantRuntimeEnforcementService.snapshot(company.getCode());
        long enabledUsers = snapshot.metrics().activeUsers();
        if (enabledUsers >= snapshot.maxActiveUsers()) {
            String message = "Active user quota exceeded for tenant " + company.getCode();
            auditUserQuotaDenied(company, snapshot, enabledUsers, operation, message);
            throw new ApplicationException(ErrorCode.BUSINESS_LIMIT_EXCEEDED, message)
                    .withDetail("companyCode", company.getCode())
                    .withDetail("operation", operation)
                    .withDetail("enabledUsers", enabledUsers)
                    .withDetail("maxActiveUsers", snapshot.maxActiveUsers())
                    .withDetail("policyReference", snapshot.auditChainId());
        }
    }

    private TenantRuntimeMetricsDto snapshot(
            Company company,
            com.bigbrightpaints.erp.modules.company.service.TenantRuntimeEnforcementService.TenantRuntimeSnapshot snapshot) {
        long totalUsers = countTotalUsers(company.getId());
        return new TenantRuntimeMetricsDto(
                company.getCode(),
                snapshot.state().name(),
                snapshot.reasonCode(),
                snapshot.maxActiveUsers(),
                snapshot.maxRequestsPerMinute(),
                snapshot.maxConcurrentRequests(),
                snapshot.metrics().activeUsers(),
                totalUsers,
                snapshot.metrics().minuteRequestCount(),
                snapshot.metrics().minuteRejectedCount(),
                snapshot.metrics().inFlightRequests(),
                snapshot.auditChainId(),
                snapshot.updatedAt()
        );
    }

    private void auditUserQuotaDenied(Company company,
                                      com.bigbrightpaints.erp.modules.company.service.TenantRuntimeEnforcementService.TenantRuntimeSnapshot snapshot,
                                      long enabledUsers,
                                      String operation,
                                      String failureReason) {
        Map<String, String> metadata = new HashMap<>();
        metadata.put("companyCode", safe(company.getCode()));
        metadata.put("operation", safe(operation));
        metadata.put("policyReference", safe(snapshot.auditChainId()));
        metadata.put("enabledUsers", Long.toString(enabledUsers));
        metadata.put("maxActiveUsers", Integer.toString(snapshot.maxActiveUsers()));
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

    private String safe(String value) {
        return value == null ? "" : value;
    }

    private String trimToNull(String value) {
        if (!StringUtils.hasText(value)) {
            return null;
        }
        return value.trim();
    }
}
