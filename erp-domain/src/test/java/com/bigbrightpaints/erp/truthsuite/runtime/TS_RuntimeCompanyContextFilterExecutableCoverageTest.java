package com.bigbrightpaints.erp.truthsuite.runtime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.io.IOException;
import java.lang.reflect.Constructor;
import java.util.Map;
import java.util.Set;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.util.ReflectionTestUtils;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;

import com.bigbrightpaints.erp.core.security.AuthScopeService;
import com.bigbrightpaints.erp.core.security.CompanyContextFilter;
import com.bigbrightpaints.erp.core.security.CompanyContextHolder;
import com.bigbrightpaints.erp.modules.auth.domain.UserAccount;
import com.bigbrightpaints.erp.modules.auth.domain.UserPrincipal;
import com.bigbrightpaints.erp.modules.company.domain.Company;
import com.bigbrightpaints.erp.modules.company.domain.CompanyLifecycleState;
import com.bigbrightpaints.erp.modules.company.service.CompanyService;
import com.bigbrightpaints.erp.modules.company.service.TenantRuntimeEnforcementService;
import com.bigbrightpaints.erp.modules.company.service.TenantRuntimeRequestAdmissionService;

import io.jsonwebtoken.Claims;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;

@Tag("critical")
@Tag("concurrency")
@ExtendWith(MockitoExtension.class)
class TS_RuntimeCompanyContextFilterExecutableCoverageTest {

  private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper().findAndRegisterModules();

  @Mock private TenantRuntimeRequestAdmissionService tenantRuntimeRequestAdmissionService;

  @Mock private CompanyService companyService;

  @Mock private AuthScopeService authScopeService;

  @Mock private FilterChain filterChain;

  private CompanyContextFilter filter;

  @BeforeEach
  void setUp() {
    filter =
        new CompanyContextFilter(
            tenantRuntimeRequestAdmissionService, companyService, authScopeService, OBJECT_MAPPER);
    lenient().when(authScopeService.isPlatformScope(anyString())).thenReturn(false);
  }

  @AfterEach
  void tearDown() {
    SecurityContextHolder.clearContext();
    CompanyContextHolder.clear();
  }

  @Test
  void doFilter_rejectsCompanyHeaderWithoutAuthentication() throws ServletException, IOException {
    MockHttpServletRequest request = request("GET", "/api/v1/private");
    request.addHeader("X-Company-Code", "ACME");
    MockHttpServletResponse response = new MockHttpServletResponse();

    filter.doFilter(request, response, filterChain);

    assertThat(response.getStatus()).isEqualTo(403);
    assertThat(response.getContentAsString()).contains("Access denied to company-scoped request");
    verifyNoInteractions(companyService);
    verify(filterChain, never()).doFilter(request, response);
  }

  @Test
  void doFilter_allowsSuperAdminCanonicalTenantControlAdmissionForNonActiveTenant()
      throws ServletException, IOException {
    authenticate("ops@bbp.com", Set.of("ROLE_SUPER_ADMIN"), Set.of("ROOT"));
    when(companyService.resolveCompanyCodeById(42L)).thenReturn("ACME");
    when(companyService.resolveLifecycleStateByCode("ACME"))
        .thenReturn(CompanyLifecycleState.DEACTIVATED);
    TenantRuntimeEnforcementService.TenantRequestAdmission admission =
        TenantRuntimeEnforcementService.TenantRequestAdmission.admittedPolicyControl(
            "ACME", "chain-1");
    when(tenantRuntimeRequestAdmissionService.beginRequest(
            "ACME",
            "/api/v1/superadmin/tenants/42/lifecycle",
            "PUT",
            "ops@bbp.com",
            true))
        .thenReturn(admission);

    MockHttpServletRequest request = request("PUT", "/api/v1/superadmin/tenants/42/lifecycle");
    request.setAttribute("jwtClaims", claimsFor("ROOT"));
    MockHttpServletResponse response = new MockHttpServletResponse();

    filter.doFilter(
        request,
        response,
        (req, res) -> assertThat(CompanyContextHolder.getCompanyCode()).isEqualTo("ACME"));

    assertThat(response.getStatus()).isEqualTo(200);
    verify(tenantRuntimeRequestAdmissionService)
        .beginRequest(
            "ACME",
            "/api/v1/superadmin/tenants/42/lifecycle",
            "PUT",
            "ops@bbp.com",
            true);
    verify(tenantRuntimeRequestAdmissionService).completeRequest(admission, 200);
  }

  @Test
  void doFilter_rejectsWhenTenantRuntimeAdmissionDenied_andEscapesResponseMessage()
      throws ServletException, IOException {
    authenticate("admin@bbp.com", Set.of("ROLE_ADMIN"), Set.of("ACME"));
    when(companyService.resolveLifecycleStateByCode("ACME"))
        .thenReturn(CompanyLifecycleState.ACTIVE);
    when(tenantRuntimeRequestAdmissionService.beginRequest(
            "ACME", "/api/v1/private", "GET", "admin@bbp.com", false))
        .thenReturn(admission(false, 429, "bad \"quote\" \\\\ slash"));

    MockHttpServletRequest request = request("GET", "/api/v1/private");
    request.setAttribute("jwtClaims", claimsFor("ACME"));
    MockHttpServletResponse response = new MockHttpServletResponse();

    filter.doFilter(request, response, filterChain);

    assertThat(response.getStatus()).isEqualTo(429);
    Map<String, Object> payload =
        OBJECT_MAPPER.readValue(response.getContentAsString(), new TypeReference<>() {});
    assertThat(payload).containsEntry("message", "bad \"quote\" \\\\ slash");
    assertThat(payload).containsEntry("success", false);
  }

  @Test
  void doFilter_rejectsNonActiveTenantForNonControlRequests() throws ServletException, IOException {
    authenticate("admin@bbp.com", Set.of("ROLE_ADMIN"), Set.of("ACME"));
    when(companyService.resolveLifecycleStateByCode("ACME"))
        .thenReturn(CompanyLifecycleState.DEACTIVATED);

    MockHttpServletRequest request = request("GET", "/api/v1/private");
    request.setAttribute("jwtClaims", claimsFor("ACME"));
    MockHttpServletResponse response = new MockHttpServletResponse();

    filter.doFilter(request, response, filterChain);

    assertThat(response.getStatus()).isEqualTo(403);
    assertThat(response.getContentAsString()).contains("Tenant is deactivated");
    verify(tenantRuntimeRequestAdmissionService, never())
        .beginRequest(anyString(), anyString(), anyString(), anyString(), anyBoolean());
  }

  @Test
  void doFilter_rejectsAuthenticatedRequestWithoutCompanyClaim()
      throws ServletException, IOException {
    authenticate("admin@bbp.com", Set.of("ROLE_ADMIN"), Set.of("ACME"));
    Claims claims = mock(Claims.class);
    when(claims.get("companyCode", String.class)).thenReturn(null);

    MockHttpServletRequest request = request("GET", "/api/v1/private");
    request.setAttribute("jwtClaims", claims);
    MockHttpServletResponse response = new MockHttpServletResponse();

    filter.doFilter(request, response, filterChain);

    assertThat(response.getStatus()).isEqualTo(403);
    assertThat(response.getContentAsString())
        .contains("Authenticated token missing company context");
    verify(filterChain, never()).doFilter(request, response);
  }

  @Test
  void doFilter_rejectsMalformedControlPlaneTenantId() throws ServletException, IOException {
    authenticate("ops@bbp.com", Set.of("ROLE_SUPER_ADMIN"), Set.of("ROOT"));

    MockHttpServletRequest request =
        request("PUT", "/api/v1/superadmin/tenants/not-a-number/lifecycle");
    request.setAttribute("jwtClaims", claimsFor("ROOT"));
    MockHttpServletResponse response = new MockHttpServletResponse();

    filter.doFilter(request, response, filterChain);

    assertThat(response.getStatus()).isEqualTo(403);
    assertThat(response.getContentAsString()).contains("Access denied to company control request");
    verify(filterChain, never()).doFilter(request, response);
  }

  @Test
  void doFilter_rejectsTenantControlRequestWhenScopedClaimIsMissing()
      throws ServletException, IOException {
    authenticate("admin@bbp.com", Set.of("ROLE_ADMIN"), Set.of("ACME"));
    when(companyService.resolveCompanyCodeById(42L)).thenReturn("ACME");

    MockHttpServletRequest request = request("PUT", "/api/v1/superadmin/tenants/42/lifecycle");
    MockHttpServletResponse response = new MockHttpServletResponse();

    filter.doFilter(request, response, filterChain);

    assertThat(response.getStatus()).isEqualTo(403);
    assertThat(response.getContentAsString()).contains("Access denied to company control request");
    verify(filterChain, never()).doFilter(request, response);
  }

  @Test
  void doFilter_rejectsWhenTenantRuntimeAdmissionUnavailable()
      throws ServletException, IOException {
    authenticate("admin@bbp.com", Set.of("ROLE_ADMIN"), Set.of("ACME"));
    when(companyService.resolveLifecycleStateByCode("ACME"))
        .thenReturn(CompanyLifecycleState.ACTIVE);
    when(tenantRuntimeRequestAdmissionService.beginRequest(
            "ACME", "/api/v1/private", "GET", "admin@bbp.com", false))
        .thenReturn(null);

    MockHttpServletRequest request = request("GET", "/api/v1/private");
    request.setAttribute("jwtClaims", claimsFor("ACME"));
    MockHttpServletResponse response = new MockHttpServletResponse();

    filter.doFilter(request, response, filterChain);

    assertThat(response.getStatus()).isEqualTo(403);
    assertThat(response.getContentAsString()).contains("Tenant runtime admission is unavailable");
    verify(filterChain, never()).doFilter(request, response);
  }

  @Test
  void helperMethods_coverCanonicalControlPathAndPathResolutionBranches() {
    assertThat(invokeIsLifecycleControlRequest("/api/v1/superadmin/tenants/77", "GET")).isTrue();
    assertThat(invokeIsLifecycleControlRequest("/api/v1/superadmin/tenants/77/lifecycle", "PUT"))
        .isTrue();
    assertThat(
            invokeIsLifecycleControlRequest(
                "/api/v1/superadmin/tenants/77/support/admin-password-reset", "POST"))
        .isTrue();
    assertThat(
            invokeIsLifecycleControlRequest(
                "/api/v1/superadmin/tenants/77/admins/9/email-change/request", "POST"))
        .isTrue();
    assertThat(invokeIsLifecycleControlRequest("/api/v1/superadmin/tenants/77", "POST")).isFalse();
    assertThat(invokeIsLifecycleControlRequest("/api/v1/private", "POST")).isFalse();

    assertThat(invokeResolveApplicationPath(null)).isNull();
    MockHttpServletRequest contextAware = request("PUT", "");
    contextAware.setContextPath("/erp");
    contextAware.setRequestURI("/erp/api/v1/superadmin/tenants/77/lifecycle");
    assertThat(invokeResolveApplicationPath(contextAware))
        .isEqualTo("/api/v1/superadmin/tenants/77/lifecycle");
  }

  @Test
  void helperMethods_coverPublicPasswordResetAndPathNormalizationBranches() {
    assertThat(invokeIsPublicPasswordResetRequest("/api/v1/auth/password/forgot", "POST")).isTrue();
    assertThat(invokeIsPublicPasswordResetRequest("/api/v1/auth/password/reset/", "POST")).isTrue();
    assertThat(invokeIsPublicPasswordResetRequest("/api/v1/private", "POST")).isFalse();

    assertThat(invokeNormalizePath(" /api/v1/superadmin/tenants/77/limits/// "))
        .isEqualTo("/api/v1/superadmin/tenants/77/limits");
    assertThat(invokeNormalizePath("/api/v1/admin;tenant=acme/audit/events;mode=full///"))
        .isEqualTo("/api/v1/admin/audit/events");
  }

  @Test
  void helperMethods_coverSuperAdminTenantBusinessAndAuditGuards() {
    assertThat(invokeIsTenantBusinessRequestBlockedForSuperAdmin("/api/v1/admin/approvals"))
        .isTrue();
    assertThat(invokeIsTenantBusinessRequestBlockedForSuperAdmin("/api/v1/orchestrator/health"))
        .isFalse();
    assertThat(
            invokeIsTenantBusinessRequestBlockedForSuperAdmin(
                "/api/v1/accounting/periods/2026-Q1/reopen"))
        .isFalse();
    assertThat(invokeIsTenantBusinessRequestBlockedForSuperAdmin("/api/v1/accounting/journals"))
        .isTrue();
    assertThat(invokeIsTenantAuditWorkflowRequest("/api/v1/admin/audit/events")).isTrue();
    assertThat(invokeIsTenantAuditWorkflowRequest("/api/v1/admin/settings")).isFalse();
    assertThat(
            invokeHasTenantRuntimePolicyControlAuthority(
                "/api/v1/superadmin/tenants/77/limits", "PUT"))
        .isTrue();
    assertThat(
            invokeExtractCompanyIdFromControlPlanePath(
                "/api/v1/superadmin/tenants/not-a-number/limits"))
        .isNull();
    assertThat(invokeSanitizeForLog("bad\nvalue\r")).isEqualTo("bad_value_");
    assertThat(invokeSanitizeForLog(null)).isNull();
  }

  private boolean invokeIsLifecycleControlRequest(String path, String method) {
    return (Boolean)
        ReflectionTestUtils.invokeMethod(filter, "isLifecycleControlRequest", path, method);
  }

  private String invokeResolveApplicationPath(MockHttpServletRequest request) {
    return ReflectionTestUtils.invokeMethod(filter, "resolveApplicationPath", request);
  }

  private boolean invokeIsPublicPasswordResetRequest(String path, String method) {
    return (Boolean)
        ReflectionTestUtils.invokeMethod(filter, "isPublicPasswordResetRequest", path, method);
  }

  private String invokeNormalizePath(String path) {
    return ReflectionTestUtils.invokeMethod(filter, "normalizePath", path);
  }

  private boolean invokeIsTenantBusinessRequestBlockedForSuperAdmin(String path) {
    return (Boolean)
        ReflectionTestUtils.invokeMethod(
            filter, "isTenantBusinessRequestBlockedForSuperAdmin", path);
  }

  private boolean invokeIsTenantAuditWorkflowRequest(String path) {
    return (Boolean) ReflectionTestUtils.invokeMethod(filter, "isTenantAuditWorkflowRequest", path);
  }

  private boolean invokeHasTenantRuntimePolicyControlAuthority(String path, String method) {
    return (Boolean)
        ReflectionTestUtils.invokeMethod(
            filter, "hasTenantRuntimePolicyControlAuthority", path, method);
  }

  private Long invokeExtractCompanyIdFromControlPlanePath(String path) {
    return (Long)
        ReflectionTestUtils.invokeMethod(filter, "extractCompanyIdFromControlPlanePath", path);
  }

  private String invokeSanitizeForLog(String value) {
    return ReflectionTestUtils.invokeMethod(filter, "sanitizeForLog", value);
  }

  private void authenticate(String email, Set<String> authorities, Set<String> companyCodes) {
    UserAccount user = new UserAccount(email, "hash", "Operator");
    if (!companyCodes.isEmpty()) {
      String companyCode = companyCodes.iterator().next();
      Company company = new Company();
      company.setCode(companyCode);
      user.setCompany(company);
      user.setAuthScopeCode(companyCode);
    }
    UserPrincipal principal = new UserPrincipal(user);
    var granted =
        authorities.stream()
            .map(authority -> (org.springframework.security.core.GrantedAuthority) () -> authority)
            .toList();
    SecurityContextHolder.getContext()
        .setAuthentication(new UsernamePasswordAuthenticationToken(principal, "n/a", granted));
  }

  private Claims claimsFor(String companyCode) {
    Claims claims = mock(Claims.class);
    when(claims.get("companyCode", String.class)).thenReturn(companyCode);
    lenient().when(claims.get("cid", String.class)).thenReturn(null);
    return claims;
  }

  private MockHttpServletRequest request(String method, String servletPath) {
    MockHttpServletRequest request = new MockHttpServletRequest(method, servletPath);
    request.setServletPath(servletPath);
    request.setRequestURI(servletPath);
    return request;
  }

  private TenantRuntimeEnforcementService.TenantRequestAdmission admission(
      boolean admitted, int statusCode, String message) {
    try {
      Class<?> countersClass =
          Class.forName(TenantRuntimeEnforcementService.class.getName() + "$TenantRuntimeCounters");
      Constructor<?> countersConstructor = countersClass.getDeclaredConstructor();
      countersConstructor.setAccessible(true);
      Object counters = admitted ? countersConstructor.newInstance() : null;

      Constructor<TenantRuntimeEnforcementService.TenantRequestAdmission> constructor =
          TenantRuntimeEnforcementService.TenantRequestAdmission.class.getDeclaredConstructor(
              boolean.class,
              String.class,
              String.class,
              countersClass,
              int.class,
              String.class,
              boolean.class,
              String.class,
              String.class,
              String.class,
              String.class,
              String.class);
      constructor.setAccessible(true);
      return constructor.newInstance(
          admitted,
          "ACME",
          "audit-chain",
          counters,
          statusCode,
          message,
          false,
          null,
          null,
          null,
          null,
          null);
    } catch (ReflectiveOperationException ex) {
      throw new IllegalStateException("Unable to construct tenant admission handle", ex);
    }
  }
}
