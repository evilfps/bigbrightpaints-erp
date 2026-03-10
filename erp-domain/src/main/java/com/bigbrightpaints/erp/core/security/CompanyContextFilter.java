package com.bigbrightpaints.erp.core.security;

import com.bigbrightpaints.erp.core.exception.ErrorCode;
import com.bigbrightpaints.erp.modules.auth.domain.UserAccount;
import com.bigbrightpaints.erp.modules.auth.domain.UserPrincipal;
import com.bigbrightpaints.erp.modules.company.domain.CompanyLifecycleState;
import com.bigbrightpaints.erp.modules.company.service.CompanyService;
import com.bigbrightpaints.erp.modules.company.service.TenantRuntimeEnforcementService;
import com.bigbrightpaints.erp.shared.dto.ApiResponse;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.jsonwebtoken.Claims;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

@Component
public class CompanyContextFilter extends OncePerRequestFilter {

    private static final Logger log = LoggerFactory.getLogger(CompanyContextFilter.class);
    private static final String COMPANY_API_PREFIX = "/api/v1/companies/";
    private static final String LIFECYCLE_STATE_SUFFIX = "/lifecycle-state";
    private static final String TENANT_METRICS_SUFFIX = "/tenant-metrics";
    private static final String TENANT_RUNTIME_POLICY_SUFFIX = "/tenant-runtime/policy";
    private static final String SUPPORT_ADMIN_PASSWORD_RESET_SUFFIX = "/support/admin-password-reset";
    private static final String CONTROL_PLANE_AUTH_DENIED_MESSAGE =
            "Access denied to company control request";
    private static final String SUPER_ADMIN_PLATFORM_ONLY_MESSAGE =
            "Super Admin is limited to platform control-plane operations and cannot execute tenant business workflows";
    private static final Set<String> SUPER_ADMIN_TENANT_BUSINESS_PREFIXES = Set.of(
            "/api/v1/dealer-portal",
            "/api/v1/portal",
            "/api/v1/sales",
            "/api/v1/credit",
            "/api/v1/dealers",
            "/api/v1/invoices",
            "/api/v1/reports",
            "/api/v1/exports",
            "/api/v1/factory",
            "/api/v1/production",
            "/api/v1/hr",
            "/api/v1/payroll",
            "/api/v1/inventory",
            "/api/v1/finished-goods",
            "/api/v1/purchasing",
            "/api/v1/suppliers",
            "/api/v1/catalog",
            "/api/v1/dispatch");
    private static final Set<String> SUPER_ADMIN_TENANT_ADMIN_WORKFLOW_PREFIXES = Set.of(
            "/api/v1/admin/approvals",
            "/api/v1/admin/exports",
            "/api/v1/admin/notify",
            "/api/v1/admin/users");
    private static final Set<String> PUBLIC_PASSWORD_RESET_ENDPOINTS = Set.of(
            "/api/v1/auth/password/forgot",
            "/api/v1/auth/password/forgot/superadmin",
            "/api/v1/auth/password/reset");
    private final TenantRuntimeEnforcementService tenantRuntimeEnforcementService;
    private final CompanyService companyService;
    private final ObjectMapper objectMapper;

    public CompanyContextFilter(TenantRuntimeEnforcementService tenantRuntimeEnforcementService,
                                CompanyService companyService,
                                ObjectMapper objectMapper) {
        this.tenantRuntimeEnforcementService = tenantRuntimeEnforcementService;
        this.companyService = companyService;
        this.objectMapper = objectMapper;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain filterChain)
            throws ServletException, IOException {
        TenantRuntimeEnforcementService.TenantRequestAdmission admission =
                TenantRuntimeEnforcementService.TenantRequestAdmission.notTracked();
        try {
            String runtimePath = resolveApplicationPath(request);
            if (isPublicPasswordResetRequest(runtimePath, request.getMethod())) {
                filterChain.doFilter(request, response);
                return;
            }
            boolean lifecycleControlRequest = isLifecycleControlRequest(runtimePath, request.getMethod());
            boolean tenantRuntimePolicyControlRequest =
                    hasTenantRuntimePolicyControlAuthority(runtimePath, request.getMethod());
            if (lifecycleControlRequest && !hasAuthenticatedPrincipal()) {
                denyControlPlaneRequest(response);
                return;
            }
            if (hasSuperAdminAuthority() && isTenantBusinessRequestBlockedForSuperAdmin(runtimePath)) {
                writeAccessDenied(response, "SUPER_ADMIN_PLATFORM_ONLY", SUPER_ADMIN_PLATFORM_ONLY_MESSAGE);
                return;
            }
            String headerCompanyCode = request.getHeader("X-Company-Code");
            String legacyHeaderCompanyId = request.getHeader("X-Company-Id");
            if (StringUtils.hasText(headerCompanyCode) && StringUtils.hasText(legacyHeaderCompanyId)
                    && !headerCompanyCode.trim().equalsIgnoreCase(legacyHeaderCompanyId.trim())) {
                log.warn("Rejecting mismatched company headers. X-Company-Code={}, X-Company-Id={}, path={}",
                        headerCompanyCode, legacyHeaderCompanyId, request.getRequestURI());
                writeAccessDenied(response, "COMPANY_HEADER_MISMATCH", "Company headers do not match");
                return;
            }
            String requestedCompany = StringUtils.hasText(headerCompanyCode)
                    ? headerCompanyCode
                    : legacyHeaderCompanyId;
            Object claimsAttr = request.getAttribute("jwtClaims");
            if (claimsAttr instanceof Claims claims) {
                String tokenCompanyCode = claims.get("companyCode", String.class);
                String legacyTokenCompanyId = claims.get("cid", String.class);
                if (StringUtils.hasText(tokenCompanyCode) && StringUtils.hasText(legacyTokenCompanyId)
                        && !tokenCompanyCode.trim().equalsIgnoreCase(legacyTokenCompanyId.trim())) {
                    log.warn("Rejecting mismatched token company claims. companyCode={}, cid={}, path={}",
                            tokenCompanyCode, legacyTokenCompanyId, request.getRequestURI());
                    writeAccessDenied(response, "COMPANY_CLAIM_MISMATCH", "Token company claims do not match");
                    return;
                }
                String resolvedTokenCompany = StringUtils.hasText(tokenCompanyCode)
                        ? tokenCompanyCode
                        : legacyTokenCompanyId;
                if (!StringUtils.hasText(resolvedTokenCompany)) {
                    log.warn("Rejecting authenticated request without company claim. path={}", request.getRequestURI());
                    writeAccessDenied(response, "COMPANY_CONTEXT_MISSING", "Authenticated token missing company context");
                    return;
                }
                if (StringUtils.hasText(requestedCompany)
                        && !resolvedTokenCompany.trim().equalsIgnoreCase(requestedCompany.trim())) {
                    log.warn("Rejecting company header mismatch. tokenCompanyCode={}, headerCompanyCode={}, path={}",
                            resolvedTokenCompany, requestedCompany, request.getRequestURI());
                    writeAccessDenied(response,
                            "COMPANY_CONTEXT_MISMATCH",
                            "Company header does not match authenticated company context");
                    return;
                }
                requestedCompany = resolvedTokenCompany;
            } else {
                // Do not allow unauthenticated requests to set tenant context via header.
                if (StringUtils.hasText(requestedCompany)) {
                    writeAccessDenied(response,
                            "COMPANY_CONTEXT_AUTH_REQUIRED",
                            "Access denied to company-scoped request");
                    return;
                }
                requestedCompany = null;
            }
            String companyCode = StringUtils.hasText(requestedCompany) ? requestedCompany.trim() : null;
            boolean lifecycleControlBypass = false;
            if (lifecycleControlRequest) {
                Long lifecycleControlCompanyId = extractCompanyIdFromLifecycleControlPath(runtimePath);
                if (lifecycleControlCompanyId == null) {
                    denyControlPlaneRequest(response);
                    return;
                }
                String pathTargetCompanyCode = companyService.resolveCompanyCodeById(lifecycleControlCompanyId);
                if (!StringUtils.hasText(pathTargetCompanyCode)) {
                    denyControlPlaneRequest(response);
                    return;
                }
                if (hasSuperAdminAuthority()) {
                    companyCode = pathTargetCompanyCode.trim();
                    lifecycleControlBypass = true;
                } else if (!StringUtils.hasText(companyCode)
                        || !companyCode.trim().equalsIgnoreCase(pathTargetCompanyCode.trim())) {
                    denyControlPlaneRequest(response);
                    return;
                } else {
                    companyCode = pathTargetCompanyCode.trim();
                }
            }
            if (companyCode != null) {
                CompanyLifecycleState lifecycleState = companyService.resolveLifecycleStateByCode(companyCode);
                // Recovery endpoints for non-active tenants are intended for super-admin operators
                // even when they are not explicitly attached to the tenant membership list.
                if (!lifecycleControlBypass && !validateCompanyAccess(companyCode)) {
                    log.warn("User attempted to access unauthorized company: {}", companyCode);
                    writeAccessDenied(response,
                            "COMPANY_ACCESS_DENIED",
                            "Access denied to company: " + companyCode);
                    return;
                }
                if (!lifecycleControlBypass && shouldDenyTenantRequestByLifecycle(lifecycleState, request.getMethod())) {
                    writeAccessDenied(response,
                            "TENANT_LIFECYCLE_RESTRICTED",
                            lifecycleDeniedMessage(lifecycleState, request.getMethod()));
                    return;
                }
                if (!lifecycleControlRequest || tenantRuntimePolicyControlRequest) {
                    TenantRuntimeEnforcementService.TenantRequestAdmission runtimeAdmission =
                            tenantRuntimeEnforcementService.beginRequest(
                            companyCode,
                            runtimePath,
                            request.getMethod(),
                            resolveCurrentActor(),
                            tenantRuntimePolicyControlRequest);
                    if (runtimeAdmission == null) {
                        log.warn("Rejecting request because tenant runtime admission was unavailable. companyCode={}, path={}, method={}", companyCode, runtimePath, request.getMethod());
                        admission = TenantRuntimeEnforcementService.TenantRequestAdmission.notTracked();
                        writeAccessDenied(response, "TENANT_RUNTIME_ADMISSION_UNAVAILABLE", "Tenant runtime admission is unavailable");
                        return;
                    }
                    admission = runtimeAdmission;
                    if (!admission.isAdmitted()) {
                        writeRuntimeAdmissionDenied(response, admission);
                        return;
                    }
                }
                CompanyContextHolder.setCompanyCode(companyCode);
            }
            filterChain.doFilter(request, response);
        } finally {
            tenantRuntimeEnforcementService.completeRequest(admission, response.getStatus());
            CompanyContextHolder.clear();
        }
    }

    private boolean hasAuthenticatedPrincipal() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        return auth != null
                && auth.isAuthenticated()
                && !(auth instanceof AnonymousAuthenticationToken);
    }

    private boolean validateCompanyAccess(String companyCode) {
        if (!hasAuthenticatedPrincipal()) {
            return false;
        }
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        Object principal = auth.getPrincipal();
        if (principal instanceof UserPrincipal userPrincipal) {
            UserAccount user = userPrincipal.getUser();
            if (user == null || user.getCompanies() == null) {
                return false;
            }
            // Check if user has access to the requested company
            return user.getCompanies().stream()
                    .anyMatch(c -> c.getCode().equalsIgnoreCase(companyCode));
        }
        // Fail closed for unknown principal types.
        return false;
    }

    private String resolveCurrentActor() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth == null || !StringUtils.hasText(auth.getName())) {
            return null;
        }
        return auth.getName().trim();
    }

    private boolean hasSuperAdminAuthority() {
        if (!hasAuthenticatedPrincipal()) {
            return false;
        }
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        return auth.getAuthorities().stream()
                .anyMatch(granted -> "ROLE_SUPER_ADMIN".equalsIgnoreCase(granted.getAuthority()));
    }

    private boolean hasTenantRuntimePolicyControlAuthority(String requestPath, String requestMethod) {
        if (!hasAuthenticatedPrincipal()) {
            return false;
        }
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (!"PUT".equalsIgnoreCase(requestMethod) || !StringUtils.hasText(requestPath)) {
            return false;
        }
        String normalizedPath = requestPath.trim();
        while (normalizedPath.endsWith("/") && normalizedPath.length() > 1) {
            normalizedPath = normalizedPath.substring(0, normalizedPath.length() - 1);
        }
        if ("/api/v1/admin/tenant-runtime/policy".equals(normalizedPath)) {
            return hasAuthority(auth, "ROLE_SUPER_ADMIN");
        }
        if (isCanonicalCompanyRuntimePolicyPath(normalizedPath)) {
            return hasAuthority(auth, "ROLE_SUPER_ADMIN");
        }
        return false;
    }

    private boolean isTenantBusinessRequestBlockedForSuperAdmin(String requestPath) {
        String normalizedPath = normalizePath(requestPath);
        if (!StringUtils.hasText(normalizedPath)) {
            return false;
        }
        boolean matchesTenantAdminWorkflowPrefix = SUPER_ADMIN_TENANT_ADMIN_WORKFLOW_PREFIXES.stream()
                .anyMatch(prefix -> normalizedPath.equals(prefix) || normalizedPath.startsWith(prefix + "/"));
        if (matchesTenantAdminWorkflowPrefix) {
            return true;
        }
        boolean matchesBusinessPrefix = SUPER_ADMIN_TENANT_BUSINESS_PREFIXES.stream()
                .anyMatch(prefix -> normalizedPath.equals(prefix) || normalizedPath.startsWith(prefix + "/"));
        if (matchesBusinessPrefix) {
            return true;
        }
        if (normalizedPath.equals("/api/v1/accounting") || normalizedPath.startsWith("/api/v1/accounting/")) {
            return !isSuperAdminAllowedAccountingControlPath(normalizedPath);
        }
        return false;
    }

    private boolean isSuperAdminAllowedAccountingControlPath(String normalizedPath) {
        return normalizedPath.matches("^/api/v1/accounting/periods/[^/]+/reopen$");
    }

    private boolean hasAuthority(Authentication authentication, String authority) {
        if (authentication == null || !authentication.isAuthenticated() || !StringUtils.hasText(authority)) {
            return false;
        }
        return authentication.getAuthorities().stream()
                .map(granted -> granted.getAuthority())
                .anyMatch(grantedAuthority -> authority.equalsIgnoreCase(grantedAuthority));
    }

    private boolean isCanonicalCompanyRuntimePolicyPath(String path) {
        return isCanonicalCompanyControlSuffixPath(path, TENANT_RUNTIME_POLICY_SUFFIX);
    }

    private boolean isCanonicalCompanyUpdatePath(String path) {
        if (!StringUtils.hasText(path) || !path.startsWith(COMPANY_API_PREFIX)) {
            return false;
        }
        String companyIdSegment = path.substring(COMPANY_API_PREFIX.length());
        return hasSingleCompanyIdSegment(companyIdSegment);
    }

    private boolean isCanonicalCompanyControlSuffixPath(String path, String suffix) {
        if (!StringUtils.hasText(path) || !StringUtils.hasText(suffix)) {
            return false;
        }
        if (!path.startsWith(COMPANY_API_PREFIX) || !path.endsWith(suffix)) {
            return false;
        }
        int companyIdSegmentStart = COMPANY_API_PREFIX.length();
        int companyIdSegmentEnd = path.length() - suffix.length();
        if (companyIdSegmentEnd <= companyIdSegmentStart) {
            return false;
        }
        String companyIdSegment = path.substring(companyIdSegmentStart, companyIdSegmentEnd);
        return hasSingleCompanyIdSegment(companyIdSegment);
    }

    private boolean hasSingleCompanyIdSegment(String companyIdSegment) {
        return StringUtils.hasText(companyIdSegment) && !companyIdSegment.contains("/");
    }

    private boolean isLifecycleControlRequest(String path, String method) {
        String normalizedPath = normalizePath(path);
        if (!StringUtils.hasText(normalizedPath)) {
            return false;
        }
        if (!normalizedPath.startsWith(COMPANY_API_PREFIX)) {
            return false;
        }
        boolean lifecycleMutation = "POST".equalsIgnoreCase(method)
                && isCanonicalCompanyControlSuffixPath(normalizedPath, LIFECYCLE_STATE_SUFFIX);
        boolean tenantMetricsRead = "GET".equalsIgnoreCase(method)
                && isCanonicalCompanyControlSuffixPath(normalizedPath, TENANT_METRICS_SUFFIX);
        boolean runtimePolicyMutation = "PUT".equalsIgnoreCase(method)
                && isCanonicalCompanyRuntimePolicyPath(normalizedPath);
        boolean tenantConfigurationUpdate = "PUT".equalsIgnoreCase(method)
                && isCanonicalCompanyUpdatePath(normalizedPath);
        boolean supportAdminPasswordReset = "POST".equalsIgnoreCase(method)
                && isCanonicalCompanyControlSuffixPath(normalizedPath, SUPPORT_ADMIN_PASSWORD_RESET_SUFFIX);
        return lifecycleMutation
                || tenantMetricsRead
                || runtimePolicyMutation
                || tenantConfigurationUpdate
                || supportAdminPasswordReset;
    }

    private Long extractCompanyIdFromLifecycleControlPath(String path) {
        if (!StringUtils.hasText(path)) {
            return null;
        }
        String normalizedPath = normalizePath(path);
        if (!StringUtils.hasText(normalizedPath) || !normalizedPath.startsWith(COMPANY_API_PREFIX)) {
            return null;
        }
        int companySegmentStart = COMPANY_API_PREFIX.length();
        int companySegmentEnd = normalizedPath.indexOf('/', companySegmentStart);
        if (companySegmentEnd < 0) {
            companySegmentEnd = normalizedPath.length();
        }
        if (companySegmentEnd <= companySegmentStart) {
            return null;
        }
        String idSegment = normalizedPath.substring(companySegmentStart, companySegmentEnd).trim();
        if (!StringUtils.hasText(idSegment) || !idSegment.chars().allMatch(Character::isDigit)) {
            return null;
        }
        try {
            return Long.parseLong(idSegment);
        } catch (NumberFormatException ex) {
            return null;
        }
    }

    private void denyControlPlaneRequest(HttpServletResponse response) throws IOException {
        writeAccessDenied(response, "COMPANY_CONTROL_ACCESS_DENIED", CONTROL_PLANE_AUTH_DENIED_MESSAGE);
    }

    private void writeAccessDenied(HttpServletResponse response,
                                   String reason,
                                   String reasonDetail) throws IOException {
        String userMessage = "Access denied";
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("code", ErrorCode.AUTH_INSUFFICIENT_PERMISSIONS.getCode());
        data.put("message", userMessage);
        data.put("reason", reason);
        data.put("reasonDetail", reasonDetail);
        data.put("traceId", UUID.randomUUID().toString());
        writeControlledError(response, HttpServletResponse.SC_FORBIDDEN, userMessage, data);
    }

    private void writeRuntimeAdmissionDenied(HttpServletResponse response,
                                             TenantRuntimeEnforcementService.TenantRequestAdmission admission)
            throws IOException {
        String message = StringUtils.hasText(admission.message()) ? admission.message() : "Access denied";
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("code", StringUtils.hasText(admission.reasonCode()) ? admission.reasonCode() : "TENANT_REQUEST_DENIED");
        data.put("message", message);
        data.put("traceId", UUID.randomUUID().toString());
        if (StringUtils.hasText(admission.reasonCode())) {
            data.put("reason", admission.reasonCode());
        }
        if (StringUtils.hasText(admission.auditChainId())) {
            data.put("auditChainId", admission.auditChainId());
        }
        if (StringUtils.hasText(admission.tenantReasonCode())) {
            data.put("tenantReasonCode", admission.tenantReasonCode());
        }
        if (StringUtils.hasText(admission.limitType())) {
            data.put("limitType", admission.limitType());
        }
        if (StringUtils.hasText(admission.observedValue())) {
            data.put("observedValue", admission.observedValue());
        }
        if (StringUtils.hasText(admission.limitValue())) {
            data.put("limitValue", admission.limitValue());
        }
        writeControlledError(response, admission.statusCode(), message, data);
    }

    private void writeControlledError(HttpServletResponse response,
                                      int status,
                                      String message,
                                      Map<String, Object> data) throws IOException {
        response.setStatus(status);
        response.setContentType("application/json");
        response.setCharacterEncoding("UTF-8");
        objectMapper.writeValue(response.getWriter(), ApiResponse.failure(message, data));
    }

    private String resolveApplicationPath(HttpServletRequest request) {
        if (request == null) {
            return null;
        }
        String servletPath = request.getServletPath();
        String pathInfo = request.getPathInfo();
        if (StringUtils.hasText(servletPath) || StringUtils.hasText(pathInfo)) {
            StringBuilder combined = new StringBuilder();
            if (StringUtils.hasText(servletPath)) {
                combined.append(servletPath.trim());
            }
            if (StringUtils.hasText(pathInfo)) {
                String normalizedPathInfo = pathInfo.trim();
                if (!normalizedPathInfo.startsWith("/") && combined.length() > 0) {
                    combined.append('/');
                }
                combined.append(normalizedPathInfo);
            }
            if (combined.length() > 0) {
                return combined.toString();
            }
        }
        String requestUri = request.getRequestURI();
        if (!StringUtils.hasText(requestUri)) {
            return null;
        }
        String normalizedUri = requestUri.trim();
        String contextPath = request.getContextPath();
        if (StringUtils.hasText(contextPath)) {
            String normalizedContextPath = contextPath.trim();
            if (normalizedUri.equals(normalizedContextPath)) {
                return "/";
            }
            if (normalizedUri.startsWith(normalizedContextPath + "/")) {
                normalizedUri = normalizedUri.substring(normalizedContextPath.length());
            }
        }
        return normalizedUri;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        String path = resolveApplicationPath(request);
        if (!StringUtils.hasText(path)) {
            return false;
        }
        return path.startsWith("/actuator") || path.startsWith("/swagger") || path.startsWith("/v3");
    }

    private boolean isPublicPasswordResetRequest(String path, String method) {
        if (!"POST".equalsIgnoreCase(method) || !StringUtils.hasText(path)) {
            return false;
        }
        String normalizedPath = normalizePath(path);
        return PUBLIC_PASSWORD_RESET_ENDPOINTS.contains(normalizedPath);
    }

    private String normalizePath(String path) {
        if (!StringUtils.hasText(path)) {
            return path;
        }
        String normalizedPath = path.trim();
        while (normalizedPath.endsWith("/") && normalizedPath.length() > 1) {
            normalizedPath = normalizedPath.substring(0, normalizedPath.length() - 1);
        }
        return normalizedPath;
    }

    private boolean shouldDenyTenantRequestByLifecycle(CompanyLifecycleState lifecycleState, String method) {
        CompanyLifecycleState resolvedState = lifecycleState == null
                ? CompanyLifecycleState.ACTIVE
                : lifecycleState;
        return switch (resolvedState) {
            case ACTIVE -> false;
            case SUSPENDED, DEACTIVATED -> true;
        };
    }

    private String lifecycleDeniedMessage(CompanyLifecycleState lifecycleState, String method) {
        CompanyLifecycleState resolvedState = lifecycleState == null
                ? CompanyLifecycleState.ACTIVE
                : lifecycleState;
        return switch (resolvedState) {
            case ACTIVE -> "Tenant lifecycle state allows access";
            case SUSPENDED -> "Tenant is suspended";
            case DEACTIVATED -> "Tenant is deactivated";
        };
    }
}
