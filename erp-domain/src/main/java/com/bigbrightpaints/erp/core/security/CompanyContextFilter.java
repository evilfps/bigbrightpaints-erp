package com.bigbrightpaints.erp.core.security;

import com.bigbrightpaints.erp.core.exception.ErrorCode;
import com.bigbrightpaints.erp.modules.auth.domain.UserAccount;
import com.bigbrightpaints.erp.modules.auth.domain.UserPrincipal;
import com.bigbrightpaints.erp.modules.company.domain.CompanyLifecycleState;
import com.bigbrightpaints.erp.modules.company.service.CompanyService;
import com.bigbrightpaints.erp.modules.company.service.TenantRuntimeEnforcementService;
import io.jsonwebtoken.Claims;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;

@Component
public class CompanyContextFilter extends OncePerRequestFilter {

    private static final Logger log = LoggerFactory.getLogger(CompanyContextFilter.class);
    private final TenantRuntimeEnforcementService tenantRuntimeEnforcementService;
    private final CompanyService companyService;

    public CompanyContextFilter(TenantRuntimeEnforcementService tenantRuntimeEnforcementService,
                                CompanyService companyService) {
        this.tenantRuntimeEnforcementService = tenantRuntimeEnforcementService;
        this.companyService = companyService;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain filterChain)
            throws ServletException, IOException {
        TenantRuntimeEnforcementService.TenantRequestAdmission admission =
                TenantRuntimeEnforcementService.TenantRequestAdmission.notTracked();
        try {
            String headerCompanyCode = request.getHeader("X-Company-Code");
            String legacyHeaderCompanyId = request.getHeader("X-Company-Id");
            if (StringUtils.hasText(headerCompanyCode) && StringUtils.hasText(legacyHeaderCompanyId)
                    && !headerCompanyCode.trim().equalsIgnoreCase(legacyHeaderCompanyId.trim())) {
                log.warn("Rejecting mismatched company headers. X-Company-Code={}, X-Company-Id={}, path={}",
                        headerCompanyCode, legacyHeaderCompanyId, request.getRequestURI());
                response.sendError(HttpServletResponse.SC_FORBIDDEN, "Company headers do not match");
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
                    response.sendError(HttpServletResponse.SC_FORBIDDEN, "Token company claims do not match");
                    return;
                }
                String resolvedTokenCompany = StringUtils.hasText(tokenCompanyCode)
                        ? tokenCompanyCode
                        : legacyTokenCompanyId;
                if (!StringUtils.hasText(resolvedTokenCompany)) {
                    log.warn("Rejecting authenticated request without company claim. path={}", request.getRequestURI());
                    response.sendError(HttpServletResponse.SC_FORBIDDEN, "Authenticated token missing company context");
                    return;
                }
                if (StringUtils.hasText(requestedCompany)
                        && !resolvedTokenCompany.trim().equalsIgnoreCase(requestedCompany.trim())) {
                    log.warn("Rejecting company header mismatch. tokenCompanyCode={}, headerCompanyCode={}, path={}",
                            resolvedTokenCompany, requestedCompany, request.getRequestURI());
                    response.sendError(HttpServletResponse.SC_FORBIDDEN, "Company header does not match authenticated company context");
                    return;
                }
                requestedCompany = resolvedTokenCompany;
            } else {
                // Do not allow unauthenticated requests to set tenant context via header.
                if (StringUtils.hasText(requestedCompany)) {
                    response.sendError(HttpServletResponse.SC_FORBIDDEN,
                            "Access denied to company-scoped request");
                    return;
                }
                requestedCompany = null;
            }
            String companyCode = StringUtils.hasText(requestedCompany) ? requestedCompany.trim() : null;
            if (companyCode != null) {
                String runtimePath = resolveApplicationPath(request);
                CompanyLifecycleState lifecycleState = companyService.resolveLifecycleStateByCode(companyCode);
                boolean lifecycleControlBypass = lifecycleState != CompanyLifecycleState.ACTIVE
                        && isLifecycleControlRequest(runtimePath, request.getMethod())
                        && hasSuperAdminAuthority();
                // Recovery endpoints for non-active tenants are intended for super-admin operators
                // even when they are not explicitly attached to the tenant membership list.
                if (!lifecycleControlBypass && !validateCompanyAccess(companyCode)) {
                    log.warn("User attempted to access unauthorized company: {}", companyCode);
                    response.sendError(HttpServletResponse.SC_FORBIDDEN, "Access denied to company: " + companyCode);
                    return;
                }
                if (lifecycleState != CompanyLifecycleState.ACTIVE && !lifecycleControlBypass) {
                    response.sendError(HttpServletResponse.SC_FORBIDDEN,
                            "Tenant lifecycle state does not allow access");
                    return;
                }
                admission = tenantRuntimeEnforcementService.beginRequest(
                        companyCode,
                        runtimePath,
                        request.getMethod(),
                        resolveCurrentActor(),
                        hasTenantRuntimePolicyControlAuthority(runtimePath, request.getMethod()));
                if (!admission.isAdmitted()) {
                    writeRuntimeAdmissionRejection(request, response, admission);
                    return;
                }
                CompanyContextHolder.setCompanyCode(companyCode);
            }
            filterChain.doFilter(request, response);
        } finally {
            tenantRuntimeEnforcementService.completeRequest(admission, response.getStatus());
            CompanyContextHolder.clear();
        }
    }

    private boolean validateCompanyAccess(String companyCode) {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth == null || !auth.isAuthenticated()) {
            return false;
        }
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
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth == null || !auth.isAuthenticated()) {
            return false;
        }
        return auth.getAuthorities().stream()
                .anyMatch(granted -> "ROLE_SUPER_ADMIN".equalsIgnoreCase(granted.getAuthority()));
    }

    private boolean hasTenantRuntimePolicyControlAuthority(String requestPath, String requestMethod) {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth == null || !auth.isAuthenticated()) {
            return false;
        }
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

    private boolean hasAuthority(Authentication authentication, String authority) {
        if (authentication == null || !authentication.isAuthenticated() || !StringUtils.hasText(authority)) {
            return false;
        }
        return authentication.getAuthorities().stream()
                .map(granted -> granted.getAuthority())
                .anyMatch(grantedAuthority -> authority.equalsIgnoreCase(grantedAuthority));
    }

    private boolean isCanonicalCompanyRuntimePolicyPath(String path) {
        if (!StringUtils.hasText(path)) {
            return false;
        }
        if (!path.startsWith("/api/v1/companies/") || !path.endsWith("/tenant-runtime/policy")) {
            return false;
        }
        String companyIdSegment =
                path.substring("/api/v1/companies/".length(), path.length() - "/tenant-runtime/policy".length());
        return StringUtils.hasText(companyIdSegment) && !companyIdSegment.contains("/");
    }

    private boolean isLifecycleControlRequest(String path, String method) {
        if (!StringUtils.hasText(path)) {
            return false;
        }
        if (!path.startsWith("/api/v1/companies/")) {
            return false;
        }
        boolean lifecycleMutation = "POST".equalsIgnoreCase(method)
                && path.endsWith("/lifecycle-state");
        boolean tenantMetricsRead = "GET".equalsIgnoreCase(method)
                && path.endsWith("/tenant-metrics");
        return lifecycleMutation || tenantMetricsRead;
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

    private void writeRuntimeAdmissionRejection(HttpServletRequest request,
                                                HttpServletResponse response,
                                                TenantRuntimeEnforcementService.TenantRequestAdmission admission)
            throws IOException {
        response.setStatus(admission.statusCode());
        response.setContentType("application/json");
        response.setCharacterEncoding("UTF-8");

        String message = StringUtils.hasText(admission.message()) ? admission.message() : "Access denied";
        String escapedMessage = escapeJson(message);
        StringBuilder payload = new StringBuilder("{\"success\":false,\"message\":\"")
                .append(escapedMessage)
                .append("\",\"data\":{\"code\":\"")
                .append(mapRuntimeErrorCode(admission.reasonCode()))
                .append("\",\"message\":\"")
                .append(escapedMessage)
                .append("\",\"reason\":\"")
                .append(escapedMessage)
                .append("\"");
        if (request != null && StringUtils.hasText(request.getRequestURI())) {
            payload.append(",\"path\":\"")
                    .append(escapeJson(request.getRequestURI()))
                    .append("\"");
        }
        String policyReference = admission.auditChainId();
        String limitType = admission.limitType();
        String observedValue = admission.observedValue();
        String limitValue = admission.limitValue();
        boolean hasDetails = StringUtils.hasText(policyReference)
                || StringUtils.hasText(limitType)
                || StringUtils.hasText(observedValue)
                || StringUtils.hasText(limitValue);
        if (hasDetails) {
            payload.append(",\"details\":{");
            boolean firstDetail = true;
            if (StringUtils.hasText(policyReference)) {
                payload.append("\"policyReference\":\"")
                        .append(escapeJson(policyReference))
                        .append("\"");
                firstDetail = false;
            }
            if (StringUtils.hasText(limitType)) {
                if (!firstDetail) {
                    payload.append(',');
                }
                payload.append("\"limitType\":\"")
                        .append(escapeJson(limitType))
                        .append("\"");
                firstDetail = false;
            }
            if (StringUtils.hasText(observedValue)) {
                if (!firstDetail) {
                    payload.append(',');
                }
                payload.append("\"observedValue\":\"")
                        .append(escapeJson(observedValue))
                        .append("\"");
                firstDetail = false;
            }
            if (StringUtils.hasText(limitValue)) {
                if (!firstDetail) {
                    payload.append(',');
                }
                payload.append("\"limitValue\":\"")
                        .append(escapeJson(limitValue))
                        .append("\"");
            }
            payload.append('}');
        }
        payload.append("}}");
        response.getWriter().write(payload.toString());
    }

    private String mapRuntimeErrorCode(String runtimeReasonCode) {
        if (!StringUtils.hasText(runtimeReasonCode)) {
            return ErrorCode.BUSINESS_INVALID_STATE.getCode();
        }
        String normalized = runtimeReasonCode.trim().toUpperCase();
        if ("TENANT_CONCURRENCY_LIMIT".equals(normalized)
                || "TENANT_RATE_LIMIT".equals(normalized)
                || "TENANT_CONCURRENCY_EXCEEDED".equals(normalized)
                || "TENANT_REQUEST_RATE_EXCEEDED".equals(normalized)
                || "TENANT_ACTIVE_USER_QUOTA_EXCEEDED".equals(normalized)) {
            return ErrorCode.BUSINESS_LIMIT_EXCEEDED.getCode();
        }
        return ErrorCode.BUSINESS_INVALID_STATE.getCode();
    }

    private String escapeJson(String value) {
        if (value == null) {
            return "";
        }
        return value
                .replace("\\", "\\\\")
                .replace("\"", "\\\"");
    }
}
