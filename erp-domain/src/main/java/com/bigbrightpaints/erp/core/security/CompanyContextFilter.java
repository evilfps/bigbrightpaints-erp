package com.bigbrightpaints.erp.core.security;

import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.lang.Nullable;
import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import org.springframework.web.filter.OncePerRequestFilter;

import com.fasterxml.jackson.databind.ObjectMapper;

import com.bigbrightpaints.erp.core.audit.AuditEvent;
import com.bigbrightpaints.erp.core.audit.AuditService;
import com.bigbrightpaints.erp.core.exception.ErrorCode;
import com.bigbrightpaints.erp.modules.auth.domain.UserAccount;
import com.bigbrightpaints.erp.modules.auth.domain.UserPrincipal;
import com.bigbrightpaints.erp.modules.company.domain.CompanyLifecycleState;
import com.bigbrightpaints.erp.modules.company.service.CompanyService;
import com.bigbrightpaints.erp.modules.company.service.TenantRuntimeEnforcementService;
import com.bigbrightpaints.erp.modules.company.service.TenantRuntimeRequestAdmissionService;
import com.bigbrightpaints.erp.shared.dto.ApiResponse;

import io.jsonwebtoken.Claims;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

@Component
public class CompanyContextFilter extends OncePerRequestFilter {

  private static final Logger log = LoggerFactory.getLogger(CompanyContextFilter.class);
  private static final Set<String> TENANT_AUDIT_WORKFLOW_PREFIXES =
      Set.of("/api/v1/audit", "/api/v1/admin/audit");
  private static final Set<String> SUPERADMIN_TENANT_COLLECTION_EXCLUDED_PATHS =
      Set.of("/api/v1/superadmin/tenants/coa-templates");
  private static final List<CompanyBoundControlRoute> COMPANY_BOUND_CONTROL_ROUTES =
      List.of(
          controlRoute("GET", "^/api/v1/superadmin/tenants/([^/]+)$", false),
          controlRoute("PUT", "^/api/v1/superadmin/tenants/([^/]+)/lifecycle$", true),
          controlRoute("PUT", "^/api/v1/superadmin/tenants/([^/]+)/limits$", true),
          controlRoute("PUT", "^/api/v1/superadmin/tenants/([^/]+)/modules$", false),
          controlRoute("POST", "^/api/v1/superadmin/tenants/([^/]+)/support/warnings$", false),
          controlRoute(
              "POST", "^/api/v1/superadmin/tenants/([^/]+)/support/admin-password-reset$", false),
          controlRoute("PUT", "^/api/v1/superadmin/tenants/([^/]+)/support/context$", false),
          controlRoute("POST", "^/api/v1/superadmin/tenants/([^/]+)/force-logout$", false),
          controlRoute("PUT", "^/api/v1/superadmin/tenants/([^/]+)/admins/main$", false),
          controlRoute(
              "POST",
              "^/api/v1/superadmin/tenants/([^/]+)/admins/[^/]+/email-change/request$",
              false),
          controlRoute(
              "POST",
              "^/api/v1/superadmin/tenants/([^/]+)/admins/[^/]+/email-change/confirm$",
              false));
  private static final String CONTROL_PLANE_AUTH_DENIED_MESSAGE =
      "Access denied to company control request";
  private static final String SUPER_ADMIN_PLATFORM_ONLY_MESSAGE =
      "Super Admin is limited to platform control-plane operations and cannot execute tenant"
          + " business workflows";
  private static final Set<String> SUPER_ADMIN_TENANT_BUSINESS_PREFIXES =
      Set.of(
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
          "/api/v1/raw-materials",
          "/api/v1/migration",
          "/api/v1/orchestrator",
          "/api/v1/dispatch");
  private static final Set<String> SUPER_ADMIN_TENANT_ADMIN_WORKFLOW_PREFIXES =
      Set.of(
          "/api/v1/admin/dashboard",
          "/api/v1/admin/approvals",
          "/api/v1/admin/self",
          "/api/v1/admin/support",
          "/api/v1/admin/exports",
          "/api/v1/admin/notify",
          "/api/v1/admin/users");
  private static final Set<String> PUBLIC_PASSWORD_RESET_ENDPOINTS =
      Set.of("/api/v1/auth/password/forgot", "/api/v1/auth/password/reset");

  private record CompanyBoundControlRoute(
      String method, Pattern pattern, boolean tenantRuntimePolicyControl) {}

  private record CompanyBoundControlBinding(Long companyId, boolean tenantRuntimePolicyControl) {}

  private final TenantRuntimeRequestAdmissionService tenantRuntimeRequestAdmissionService;
  private final CompanyService companyService;
  private final AuthScopeService authScopeService;
  private final ObjectMapper objectMapper;

  @Autowired(required = false)
  @Nullable
  private AuditService auditService;

  public CompanyContextFilter(
      TenantRuntimeRequestAdmissionService tenantRuntimeRequestAdmissionService,
      CompanyService companyService,
      AuthScopeService authScopeService,
      ObjectMapper objectMapper) {
    this.tenantRuntimeRequestAdmissionService = tenantRuntimeRequestAdmissionService;
    this.companyService = companyService;
    this.authScopeService = authScopeService;
    this.objectMapper = objectMapper;
  }

  @Override
  protected void doFilterInternal(
      HttpServletRequest request, HttpServletResponse response, FilterChain filterChain)
      throws ServletException, IOException {
    TenantRuntimeEnforcementService.TenantRequestAdmission admission =
        TenantRuntimeEnforcementService.TenantRequestAdmission.notTracked();
    try {
      String runtimePath = normalizePath(resolveApplicationPath(request));
      if (isRetiredAdminHostPath(runtimePath)) {
        // Retired host paths are intentionally unresolved by handlers and should return 404
        // consistently, independent of auth/company-context binding.
        filterChain.doFilter(request, response);
        return;
      }
      if (isPublicPasswordResetRequest(runtimePath, request.getMethod())) {
        filterChain.doFilter(request, response);
        return;
      }
      CompanyBoundControlBinding controlBinding =
          resolveCompanyBoundControlBinding(runtimePath, request.getMethod());
      boolean lifecycleControlRequest = controlBinding != null;
      boolean hasSuperAdminAuthority = hasSuperAdminAuthority();
      boolean tenantRuntimePolicyControlRequest =
          controlBinding != null
              && controlBinding.tenantRuntimePolicyControl()
              && hasSuperAdminAuthority;
      if (lifecycleControlRequest && !hasAuthenticatedPrincipal()) {
        denyControlPlaneRequest(response);
        return;
      }
      if (hasSuperAdminAuthority && isTenantBusinessRequestBlockedForSuperAdmin(runtimePath)) {
        auditSuperAdminPlatformOnlyDenied(request, runtimePath);
        writeAccessDenied(response, "SUPER_ADMIN_PLATFORM_ONLY", SUPER_ADMIN_PLATFORM_ONLY_MESSAGE);
        return;
      }
      String legacyHeaderCompanyId = request.getHeader("X-Company-Id");
      if (StringUtils.hasText(legacyHeaderCompanyId)) {
        writeAccessDenied(
            response,
            "COMPANY_CONTEXT_LEGACY_HEADER_UNSUPPORTED",
            "Use X-Company-Code for company context binding");
        return;
      }
      String headerCompanyCode = request.getHeader("X-Company-Code");
      String requestedCompany = headerCompanyCode;
      Object claimsAttr = request.getAttribute("jwtClaims");
      if (claimsAttr instanceof Claims claims) {
        String tokenCompanyCode = claims.get("companyCode", String.class);
        if (!StringUtils.hasText(tokenCompanyCode)) {
          log.warn(
              "Rejecting authenticated request without company claim. path={}",
              sanitizeForLog(request.getRequestURI()));
          writeAccessDenied(
              response, "COMPANY_CONTEXT_MISSING", "Authenticated token missing company context");
          return;
        }
        if (StringUtils.hasText(requestedCompany)
            && !tokenCompanyCode.trim().equalsIgnoreCase(requestedCompany.trim())) {
          log.warn(
              "Rejecting company header mismatch. path={}",
              sanitizeForLog(request.getRequestURI()));
          writeAccessDenied(
              response,
              "COMPANY_CONTEXT_MISMATCH",
              "Company header does not match authenticated company context");
          return;
        }
        requestedCompany = tokenCompanyCode;
      } else {
        // Do not allow unauthenticated requests to set tenant context via header.
        if (StringUtils.hasText(requestedCompany)) {
          writeAccessDenied(
              response, "COMPANY_CONTEXT_AUTH_REQUIRED", "Access denied to company-scoped request");
          return;
        }
        requestedCompany = null;
      }
      String companyCode = normalizeCompanyCode(requestedCompany);
      boolean superAdminPlatformScope =
          hasSuperAdminAuthority && authScopeService.isPlatformScope(companyCode);
      if (hasSuperAdminAuthority
          && isSuperadminPlatformScopeOnlyHostPath(runtimePath)
          && !superAdminPlatformScope) {
        auditSuperAdminPlatformOnlyDenied(request, runtimePath);
        writeAccessDenied(response, "SUPER_ADMIN_PLATFORM_ONLY", SUPER_ADMIN_PLATFORM_ONLY_MESSAGE);
        return;
      }
      if (superAdminPlatformScope) {
        if (!lifecycleControlRequest && !isPlatformScopedRequestAllowed(runtimePath)) {
          auditSuperAdminPlatformOnlyDenied(request, runtimePath);
          writeAccessDenied(
              response, "SUPER_ADMIN_PLATFORM_ONLY", SUPER_ADMIN_PLATFORM_ONLY_MESSAGE);
          return;
        }
        if (!lifecycleControlRequest) {
          filterChain.doFilter(request, response);
          return;
        }
      }
      boolean lifecycleControlBypass = false;
      if (lifecycleControlRequest) {
        Long lifecycleControlCompanyId = controlBinding.companyId();
        if (lifecycleControlCompanyId == null) {
          denyControlPlaneRequest(response);
          return;
        }
        String pathTargetCompanyCode;
        try {
          pathTargetCompanyCode =
              normalizeCompanyCode(
                  companyService.resolveCompanyCodeById(lifecycleControlCompanyId));
        } catch (RuntimeException ex) {
          log.warn(
              "Rejecting control-plane request because target tenant lookup failed. path={}",
              sanitizeForLog(request.getRequestURI()),
              ex);
          writeServiceUnavailable(
              response,
              "TENANT_CONTROL_TARGET_LOOKUP_UNAVAILABLE",
              "Tenant control-plane target lookup is unavailable");
          return;
        }
        if (pathTargetCompanyCode == null) {
          denyControlPlaneRequest(response);
          return;
        }
        if (hasSuperAdminAuthority) {
          companyCode = pathTargetCompanyCode;
          lifecycleControlBypass = true;
        } else {
          if (companyCode == null) {
            denyControlPlaneRequest(response);
            return;
          }
          if (!companyCode.equalsIgnoreCase(pathTargetCompanyCode)) {
            denyControlPlaneRequest(response);
            return;
          }
          companyCode = pathTargetCompanyCode;
        }
      }
      if (companyCode != null) {
        if (isTenantAuditWorkflowRequest(runtimePath) && hasSuperAdminAuthority) {
          writeAccessDenied(
              response,
              "SUPER_ADMIN_TENANT_WORKFLOW_DENIED",
              "Access denied to tenant audit workflow for platform-only super admin");
          return;
        }
        CompanyLifecycleState lifecycleState;
        try {
          lifecycleState = companyService.resolveLifecycleStateByCode(companyCode);
        } catch (RuntimeException ex) {
          log.warn(
              "Rejecting request because lifecycle lookup failed. path={}",
              sanitizeForLog(request.getRequestURI()),
              ex);
          writeServiceUnavailable(
              response,
              "TENANT_LIFECYCLE_LOOKUP_UNAVAILABLE",
              "Tenant lifecycle admission is unavailable");
          return;
        }
        // Recovery endpoints for non-active tenants are intended for super-admin operators
        // even when they are not explicitly attached to the tenant membership list.
        if (!lifecycleControlBypass && !validateCompanyAccess(companyCode)) {
          log.warn(
              "User attempted to access unauthorized company context. path={}",
              sanitizeForLog(request.getRequestURI()));
          writeAccessDenied(
              response, "COMPANY_ACCESS_DENIED", "Access denied to company: " + companyCode);
          return;
        }
        if (!lifecycleControlBypass
            && shouldDenyTenantRequestByLifecycle(lifecycleState, request.getMethod())) {
          writeAccessDenied(
              response,
              "TENANT_LIFECYCLE_RESTRICTED",
              lifecycleDeniedMessage(lifecycleState, request.getMethod()));
          return;
        }
        if (!lifecycleControlRequest || tenantRuntimePolicyControlRequest) {
          TenantRuntimeEnforcementService.TenantRequestAdmission runtimeAdmission =
              tenantRuntimeRequestAdmissionService.beginRequest(
                  companyCode,
                  runtimePath,
                  request.getMethod(),
                  resolveCurrentActor(),
                  tenantRuntimePolicyControlRequest);
          if (runtimeAdmission == null) {
            log.warn(
                "Rejecting request because tenant runtime admission was unavailable."
                    + " path={}, method={}",
                sanitizeForLog(runtimePath),
                sanitizeForLog(request.getMethod()));
            admission = TenantRuntimeEnforcementService.TenantRequestAdmission.notTracked();
            writeAccessDenied(
                response,
                "TENANT_RUNTIME_ADMISSION_UNAVAILABLE",
                "Tenant runtime admission is unavailable");
            return;
          }
          admission = runtimeAdmission;
          if (!admission.isAdmitted()) {
            writeRuntimeAdmissionDenied(response, admission);
            return;
          }
          request.setAttribute(
              TenantRuntimeRequestAttributes.CANONICAL_ADMISSION_APPLIED, Boolean.TRUE);
        }
        CompanyContextHolder.setCompanyCode(companyCode);
      }
      filterChain.doFilter(request, response);
    } finally {
      tenantRuntimeRequestAdmissionService.completeRequest(admission, response.getStatus());
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
      if (user == null || user.getCompany() == null || user.getCompany().getCode() == null) {
        return false;
      }
      return user.getCompany().getCode().equalsIgnoreCase(companyCode);
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

  private boolean isTenantBusinessRequestBlockedForSuperAdmin(String requestPath) {
    String normalizedPath = normalizePath(requestPath);
    if (!StringUtils.hasText(normalizedPath)) {
      return false;
    }
    boolean matchesTenantAdminWorkflowPrefix =
        SUPER_ADMIN_TENANT_ADMIN_WORKFLOW_PREFIXES.stream()
            .anyMatch(
                prefix -> normalizedPath.equals(prefix) || normalizedPath.startsWith(prefix + "/"));
    if (matchesTenantAdminWorkflowPrefix) {
      return true;
    }
    boolean matchesBusinessPrefix =
        SUPER_ADMIN_TENANT_BUSINESS_PREFIXES.stream()
            .anyMatch(
                prefix -> normalizedPath.equals(prefix) || normalizedPath.startsWith(prefix + "/"));
    if (matchesBusinessPrefix) {
      if (normalizedPath.equals("/api/v1/orchestrator")
          || normalizedPath.startsWith("/api/v1/orchestrator/")) {
        return !isSuperAdminAllowedOrchestratorControlPath(normalizedPath);
      }
      return true;
    }
    if (normalizedPath.equals("/api/v1/accounting")
        || normalizedPath.startsWith("/api/v1/accounting/")) {
      return !isSuperAdminAllowedAccountingControlPath(normalizedPath);
    }
    return false;
  }

  private void auditSuperAdminPlatformOnlyDenied(
      HttpServletRequest request, String normalizedPath) {
    if (auditService == null || AccessDeniedAuditMarker.isCurrentRequestAlreadyAudited(request)) {
      return;
    }
    String actor = SecurityActorResolver.resolveActorWithSystemProcessFallback();
    String tenantScope = AccessDeniedAuditMarker.resolveTenantScope(request);
    Map<String, String> metadata = new LinkedHashMap<>();
    metadata.put("actor", actor);
    metadata.put("reason", "SUPER_ADMIN_PLATFORM_ONLY");
    metadata.put(
        "deniedPath",
        StringUtils.hasText(normalizedPath)
            ? normalizedPath
            : normalizePath(resolveApplicationPath(request)));
    metadata.put("deniedMethod", request.getMethod());
    if (StringUtils.hasText(tenantScope)) {
      metadata.put("tenantScope", tenantScope.trim());
    }
    auditService.logAuthFailure(AuditEvent.ACCESS_DENIED, actor, tenantScope, metadata);
    AccessDeniedAuditMarker.markCurrentRequestAudited();
  }

  private boolean isSuperAdminAllowedOrchestratorControlPath(String normalizedPath) {
    return normalizedPath.equals("/api/v1/orchestrator/health")
        || normalizedPath.startsWith("/api/v1/orchestrator/health/");
  }

  private boolean isSuperAdminAllowedAccountingControlPath(String normalizedPath) {
    return normalizedPath.matches("^/api/v1/accounting/periods/[^/]+/reopen$");
  }

  private boolean hasAuthority(Authentication authentication, String authority) {
    if (authentication == null
        || !authentication.isAuthenticated()
        || !StringUtils.hasText(authority)) {
      return false;
    }
    return authentication.getAuthorities().stream()
        .map(granted -> granted.getAuthority())
        .anyMatch(grantedAuthority -> authority.equalsIgnoreCase(grantedAuthority));
  }

  private CompanyBoundControlBinding resolveCompanyBoundControlBinding(String path, String method) {
    String normalizedPath = normalizePath(path);
    String normalizedMethod = normalizeMethod(method);
    if (!StringUtils.hasText(normalizedPath)) {
      return null;
    }
    if (SUPERADMIN_TENANT_COLLECTION_EXCLUDED_PATHS.contains(normalizedPath)) {
      return null;
    }
    for (CompanyBoundControlRoute route : COMPANY_BOUND_CONTROL_ROUTES) {
      if (StringUtils.hasText(normalizedMethod) && !route.method().equals(normalizedMethod)) {
        continue;
      }
      Matcher matcher = route.pattern().matcher(normalizedPath);
      if (!matcher.matches()) {
        continue;
      }
      Long companyId = parseCompanyId(matcher.group(1));
      return new CompanyBoundControlBinding(companyId, route.tenantRuntimePolicyControl());
    }
    return null;
  }

  private Long extractCompanyIdFromControlPlanePath(String path) {
    CompanyBoundControlBinding binding = resolveCompanyBoundControlBinding(path, null);
    return binding == null ? null : binding.companyId();
  }

  private boolean isLifecycleControlRequest(String path, String method) {
    return resolveCompanyBoundControlBinding(path, method) != null;
  }

  private boolean hasTenantRuntimePolicyControlAuthority(String path, String method) {
    CompanyBoundControlBinding binding = resolveCompanyBoundControlBinding(path, method);
    return binding != null && binding.tenantRuntimePolicyControl();
  }

  private String normalizeCompanyCode(String rawCompanyCode) {
    if (!StringUtils.hasText(rawCompanyCode)) {
      return null;
    }
    return rawCompanyCode.trim();
  }

  private String sanitizeForLog(String rawValue) {
    if (rawValue == null) {
      return null;
    }
    return rawValue.replace('\n', '_').replace('\r', '_');
  }

  private Long parseCompanyId(String rawCompanyId) {
    if (!StringUtils.hasText(rawCompanyId)) {
      return null;
    }
    try {
      return Long.parseLong(rawCompanyId.trim());
    } catch (NumberFormatException ex) {
      return null;
    }
  }

  private void denyControlPlaneRequest(HttpServletResponse response) throws IOException {
    writeAccessDenied(response, "COMPANY_CONTROL_ACCESS_DENIED", CONTROL_PLANE_AUTH_DENIED_MESSAGE);
  }

  private void writeAccessDenied(HttpServletResponse response, String reason, String reasonDetail)
      throws IOException {
    String userMessage = "Access denied";
    Map<String, Object> data = new LinkedHashMap<>();
    data.put("code", ErrorCode.AUTH_INSUFFICIENT_PERMISSIONS.getCode());
    data.put("message", userMessage);
    data.put("reason", reason);
    data.put("reasonDetail", reasonDetail);
    data.put("traceId", UUID.randomUUID().toString());
    writeControlledError(response, HttpServletResponse.SC_FORBIDDEN, userMessage, data);
  }

  private void writeServiceUnavailable(
      HttpServletResponse response, String reason, String reasonDetail) throws IOException {
    String userMessage = ErrorCode.SYSTEM_SERVICE_UNAVAILABLE.getDefaultMessage();
    Map<String, Object> data = new LinkedHashMap<>();
    data.put("code", ErrorCode.SYSTEM_SERVICE_UNAVAILABLE.getCode());
    data.put("message", userMessage);
    data.put("reason", reason);
    data.put("reasonDetail", reasonDetail);
    data.put("traceId", UUID.randomUUID().toString());
    writeControlledError(response, HttpServletResponse.SC_SERVICE_UNAVAILABLE, userMessage, data);
  }

  private void writeRuntimeAdmissionDenied(
      HttpServletResponse response,
      TenantRuntimeEnforcementService.TenantRequestAdmission admission)
      throws IOException {
    String message =
        StringUtils.hasText(admission.message()) ? admission.message() : "Access denied";
    Map<String, Object> data = new LinkedHashMap<>();
    data.put(
        "code",
        StringUtils.hasText(admission.reasonCode())
            ? admission.reasonCode()
            : "TENANT_REQUEST_DENIED");
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

  private void writeControlledError(
      HttpServletResponse response, int status, String message, Map<String, Object> data)
      throws IOException {
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
    String path = normalizePath(resolveApplicationPath(request));
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

  private boolean isTenantAuditWorkflowRequest(String path) {
    String normalizedPath = normalizePath(path);
    return StringUtils.hasText(normalizedPath)
        && TENANT_AUDIT_WORKFLOW_PREFIXES.stream()
            .anyMatch(
                prefix -> normalizedPath.equals(prefix) || normalizedPath.startsWith(prefix + "/"));
  }

  private boolean isPlatformScopedRequestAllowed(String path) {
    String normalizedPath = normalizePath(path);
    if (!StringUtils.hasText(normalizedPath)) {
      return false;
    }
    if (normalizedPath.equals("/api/v1/companies")) {
      return true;
    }
    if (isRetiredAdminHostPath(normalizedPath)) {
      // Let retired admin hosts fall through to dispatcher 404 uniformly.
      return true;
    }
    return normalizedPath.equals("/api/v1/auth")
        || normalizedPath.startsWith("/api/v1/auth/")
        || normalizedPath.equals("/api/v1/superadmin")
        || normalizedPath.startsWith("/api/v1/superadmin/");
  }

  private boolean isRetiredAdminHostPath(String normalizedPath) {
    return RetiredTenantAdminHostPaths.matchesNormalizedPath(normalizedPath);
  }

  private boolean isSuperadminPlatformScopeOnlyHostPath(String path) {
    String normalizedPath = normalizePath(path);
    if (!StringUtils.hasText(normalizedPath)) {
      return false;
    }
    return normalizedPath.equals("/api/v1/superadmin/settings")
        || normalizedPath.startsWith("/api/v1/superadmin/settings/")
        || normalizedPath.equals("/api/v1/superadmin/roles")
        || normalizedPath.startsWith("/api/v1/superadmin/roles/")
        || normalizedPath.equals("/api/v1/superadmin/notify")
        || normalizedPath.startsWith("/api/v1/superadmin/notify/");
  }

  private String normalizeMethod(String method) {
    if (!StringUtils.hasText(method)) {
      return null;
    }
    return method.trim().toUpperCase(Locale.ROOT);
  }

  private static CompanyBoundControlRoute controlRoute(
      String method, String pathPattern, boolean tenantRuntimePolicyControl) {
    return new CompanyBoundControlRoute(
        method, Pattern.compile(pathPattern), tenantRuntimePolicyControl);
  }

  private String normalizePath(String path) {
    if (!StringUtils.hasText(path)) {
      return path;
    }
    String normalizedPath = path.trim();
    String[] segments = normalizedPath.split("/", -1);
    StringBuilder sanitizedPath = new StringBuilder(normalizedPath.length());
    for (int i = 0; i < segments.length; i++) {
      if (i > 0) {
        sanitizedPath.append('/');
      }
      String segment = segments[i];
      int matrixParamIndex = segment.indexOf(';');
      sanitizedPath.append(
          matrixParamIndex >= 0 ? segment.substring(0, matrixParamIndex) : segment);
    }
    normalizedPath = sanitizedPath.toString();
    while (normalizedPath.endsWith("/") && normalizedPath.length() > 1) {
      normalizedPath = normalizedPath.substring(0, normalizedPath.length() - 1);
    }
    return normalizedPath;
  }

  private boolean shouldDenyTenantRequestByLifecycle(
      CompanyLifecycleState lifecycleState, String method) {
    CompanyLifecycleState resolvedState =
        lifecycleState == null ? CompanyLifecycleState.ACTIVE : lifecycleState;
    return switch (resolvedState) {
      case ACTIVE -> false;
      case SUSPENDED -> true;
      case DEACTIVATED -> true;
    };
  }

  private String lifecycleDeniedMessage(CompanyLifecycleState lifecycleState, String method) {
    CompanyLifecycleState resolvedState =
        lifecycleState == null ? CompanyLifecycleState.ACTIVE : lifecycleState;
    return switch (resolvedState) {
      case ACTIVE -> "Tenant lifecycle state allows access";
      case SUSPENDED -> "Tenant is suspended";
      case DEACTIVATED -> "Tenant is deactivated";
    };
  }

  private boolean isMutatingRequest(String method) {
    if (!StringUtils.hasText(method)) {
      return true;
    }
    return switch (method.trim().toUpperCase()) {
      case "GET", "HEAD", "OPTIONS", "TRACE" -> false;
      default -> true;
    };
  }
}
