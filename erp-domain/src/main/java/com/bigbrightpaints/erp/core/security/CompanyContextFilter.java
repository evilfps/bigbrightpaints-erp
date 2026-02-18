package com.bigbrightpaints.erp.core.security;

import com.bigbrightpaints.erp.modules.auth.domain.UserAccount;
import com.bigbrightpaints.erp.modules.auth.domain.UserPrincipal;
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

    public CompanyContextFilter(TenantRuntimeEnforcementService tenantRuntimeEnforcementService) {
        this.tenantRuntimeEnforcementService = tenantRuntimeEnforcementService;
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
                requestedCompany = null;
            }
            String companyCode = StringUtils.hasText(requestedCompany) ? requestedCompany.trim() : null;
            if (companyCode != null) {
                // Validate user has access to this company
                if (!validateCompanyAccess(companyCode)) {
                    log.warn("User attempted to access unauthorized company: {}", companyCode);
                    response.sendError(HttpServletResponse.SC_FORBIDDEN, "Access denied to company: " + companyCode);
                    return;
                }
                admission = tenantRuntimeEnforcementService.beginRequest(
                        companyCode,
                        request.getRequestURI(),
                        request.getMethod(),
                        resolveCurrentActor());
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

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        String path = request.getServletPath();
        return path.startsWith("/actuator") || path.startsWith("/swagger") || path.startsWith("/v3");
    }
}
