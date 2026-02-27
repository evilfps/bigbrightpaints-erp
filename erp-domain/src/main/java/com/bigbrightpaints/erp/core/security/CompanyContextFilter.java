package com.bigbrightpaints.erp.core.security;

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
import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.Set;

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
    private static final Set<String> PUBLIC_PASSWORD_RESET_ENDPOINTS = Set.of(
            "/api/v1/auth/password/forgot",
            "/api/v1/auth/password/forgot/superadmin",
            "/api/v1/auth/password/reset");
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
            String runtimePath = resolveApplicationPath(request);
            if (isPublicPasswordResetRequest(runtimePath, request.getMethod())) {
                filterChain.doFilter(request, response);
                return;
            }
            boolean lifecycleControlRequest = isLifecycleControlRequest(runtimePath, request.getMethod());
            if (lifecycleControlRequest && !hasAuthenticatedPrincipal()) {
                denyControlPlaneRequest(response);
                return;
            }
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
                    response.setStatus(admission.statusCode());
                    response.setContentType("application/json");
                    response.setCharacterEncoding("UTF-8");
                    String message = admission.message();
                    if (StringUtils.hasText(message)) {
                        String escaped = message
                                .replace("\\", "\\\\")
                                .replace("\"", "\\\"");
                        response.getWriter().write("{\"message\":\"" + escaped + "\"}");
                    } else {
                        response.getWriter().write("{\"message\":\"Access denied\"}");
                    }
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
        response.sendError(HttpServletResponse.SC_FORBIDDEN, CONTROL_PLANE_AUTH_DENIED_MESSAGE);
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
}
