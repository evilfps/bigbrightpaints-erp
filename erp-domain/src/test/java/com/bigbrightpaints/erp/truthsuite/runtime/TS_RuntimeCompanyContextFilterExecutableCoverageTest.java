package com.bigbrightpaints.erp.truthsuite.runtime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyString;
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

import com.bigbrightpaints.erp.core.security.CompanyContextFilter;
import com.bigbrightpaints.erp.core.security.CompanyContextHolder;
import com.bigbrightpaints.erp.modules.auth.domain.UserAccount;
import com.bigbrightpaints.erp.modules.auth.domain.UserPrincipal;
import com.bigbrightpaints.erp.modules.company.domain.Company;
import com.bigbrightpaints.erp.modules.company.domain.CompanyLifecycleState;
import com.bigbrightpaints.erp.modules.company.service.CompanyService;
import com.bigbrightpaints.erp.modules.company.service.TenantRuntimeEnforcementService;

import io.jsonwebtoken.Claims;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;

@Tag("critical")
@Tag("concurrency")
@ExtendWith(MockitoExtension.class)
class TS_RuntimeCompanyContextFilterExecutableCoverageTest {

  private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper().findAndRegisterModules();

  @Mock private TenantRuntimeEnforcementService tenantRuntimeEnforcementService;

  @Mock private CompanyService companyService;

  @Mock private FilterChain filterChain;

  private CompanyContextFilter filter;

  @BeforeEach
  void setUp() {
    filter =
        new CompanyContextFilter(tenantRuntimeEnforcementService, companyService, OBJECT_MAPPER);
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
  void doFilter_allowsSuperAdminCanonicalTenantControlBypassForNonActiveTenant()
      throws ServletException, IOException {
    authenticate("ops@bbp.com", Set.of("ROLE_SUPER_ADMIN"), Set.of("ROOT"));
    when(companyService.resolveCompanyCodeById(42L)).thenReturn("ACME");
    when(companyService.resolveLifecycleStateByCode("ACME"))
        .thenReturn(CompanyLifecycleState.DEACTIVATED);

    MockHttpServletRequest request = request("PUT", "/api/v1/superadmin/tenants/42/lifecycle");
    request.setAttribute("jwtClaims", claimsFor("ROOT"));
    MockHttpServletResponse response = new MockHttpServletResponse();

    filter.doFilter(
        request,
        response,
        (req, res) -> assertThat(CompanyContextHolder.getCompanyCode()).isEqualTo("ACME"));

    assertThat(response.getStatus()).isEqualTo(200);
    verify(tenantRuntimeEnforcementService, never())
        .beginRequest(anyString(), anyString(), anyString(), anyString(), anyBoolean());
  }

  @Test
  void doFilter_rejectsWhenTenantRuntimeAdmissionDenied_andEscapesResponseMessage()
      throws ServletException, IOException {
    authenticate("admin@bbp.com", Set.of("ROLE_ADMIN"), Set.of("ACME"));
    when(companyService.resolveLifecycleStateByCode("ACME"))
        .thenReturn(CompanyLifecycleState.ACTIVE);
    when(tenantRuntimeEnforcementService.beginRequest(
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
    verify(tenantRuntimeEnforcementService, never())
        .beginRequest(anyString(), anyString(), anyString(), anyString(), anyBoolean());
  }

  @Test
  void helperMethods_coverCanonicalControlPathAndPathResolutionBranches() {
    assertThat(invokeIsTenantControlRequest("/api/v1/superadmin/tenants/77", "GET")).isTrue();
    assertThat(invokeIsTenantControlRequest("/api/v1/superadmin/tenants/77/lifecycle", "PUT"))
        .isTrue();
    assertThat(
            invokeIsTenantControlRequest(
                "/api/v1/superadmin/tenants/77/support/admin-password-reset", "POST"))
        .isTrue();
    assertThat(
            invokeIsTenantControlRequest(
                "/api/v1/superadmin/tenants/77/admins/9/email-change/request", "POST"))
        .isTrue();
    assertThat(invokeIsTenantControlRequest("/api/v1/superadmin/tenants/77", "POST")).isFalse();
    assertThat(invokeIsTenantControlRequest("/api/v1/private", "POST")).isFalse();

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
    assertThat(invokeNormalizePath("/api/v1/audit;tenant=acme/business-events;mode=full///"))
        .isEqualTo("/api/v1/audit/business-events");
  }

  @Test
  void helperMethods_coverSuperAdminTenantBusinessAndAuditGuards() {
    assertThat(invokeIsTenantBusinessRequestBlockedForSuperAdmin("/api/v1/admin/approvals"))
        .isTrue();
    assertThat(invokeIsTenantBusinessRequestBlockedForSuperAdmin("/api/v1/orchestrator/health"))
        .isFalse();
    assertThat(invokeIsTenantAuditWorkflowRequest("/api/v1/audit/business-events")).isTrue();
    assertThat(invokeIsTenantAuditWorkflowRequest("/api/v1/admin/settings")).isFalse();
  }

  private boolean invokeIsTenantControlRequest(String path, String method) {
    return (Boolean)
        ReflectionTestUtils.invokeMethod(filter, "isTenantControlRequest", path, method);
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

  private void authenticate(String email, Set<String> authorities, Set<String> companyCodes) {
    UserAccount user = new UserAccount(email, "hash", "Operator");
    for (String companyCode : companyCodes) {
      Company company = new Company();
      company.setCode(companyCode);
      user.addCompany(company);
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
    when(claims.get("cid", String.class)).thenReturn(null);
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
