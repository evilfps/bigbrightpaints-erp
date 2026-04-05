package com.bigbrightpaints.erp.modules.auth;

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
import java.util.List;
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
import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.util.ReflectionTestUtils;

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

@ExtendWith(MockitoExtension.class)
@Tag("critical")
class CompanyContextFilterControlPlaneBindingTest {

  private static final String CONTROL_PLANE_AUTH_DENIED_MESSAGE =
      "Access denied to company control request";

  @Mock private TenantRuntimeRequestAdmissionService tenantRuntimeRequestAdmissionService;

  @Mock private CompanyService companyService;

  @Mock private AuthScopeService authScopeService;

  @Mock private FilterChain filterChain;

  private CompanyContextFilter filter;

  @BeforeEach
  void setUp() {
    filter =
        new CompanyContextFilter(
            tenantRuntimeRequestAdmissionService,
            companyService,
            authScopeService,
            new ObjectMapper().findAndRegisterModules());
    lenient().when(authScopeService.isPlatformScope(anyString())).thenReturn(false);
  }

  @AfterEach
  void tearDown() {
    SecurityContextHolder.clearContext();
    CompanyContextHolder.clear();
  }

  @Test
  void canonicalTenantDetailRequest_bindsPathTargetCompany_andBypassesRuntimeAdmission()
      throws ServletException, IOException {
    authenticate("root-superadmin@bbp.com", Set.of("ROLE_SUPER_ADMIN"), Set.of("ROOT"));
    when(companyService.resolveCompanyCodeById(42L)).thenReturn("TENANT-A");
    when(companyService.resolveLifecycleStateByCode("TENANT-A"))
        .thenReturn(CompanyLifecycleState.ACTIVE);

    MockHttpServletRequest request = request("GET", "/api/v1/superadmin/tenants/42");
    request.setAttribute("jwtClaims", claimsFor("ROOT"));
    MockHttpServletResponse response = new MockHttpServletResponse();

    filter.doFilter(
        request,
        response,
        (req, res) -> assertThat(CompanyContextHolder.getCompanyCode()).isEqualTo("TENANT-A"));

    assertThat(response.getStatus()).isEqualTo(200);
    verify(companyService).resolveCompanyCodeById(42L);
    verify(companyService).resolveLifecycleStateByCode("TENANT-A");
    verify(tenantRuntimeRequestAdmissionService, never())
        .beginRequest(anyString(), anyString(), anyString(), anyString(), anyBoolean());
  }

  @Test
  void canonicalSupportResetRequest_bindsPathTargetCompany_andBypassesRuntimeAdmission()
      throws ServletException, IOException {
    authenticate("root-superadmin@bbp.com", Set.of("ROLE_SUPER_ADMIN"), Set.of("ROOT"));
    when(companyService.resolveCompanyCodeById(42L)).thenReturn("TENANT-A");
    when(companyService.resolveLifecycleStateByCode("TENANT-A"))
        .thenReturn(CompanyLifecycleState.ACTIVE);

    MockHttpServletRequest request =
        request("POST", "/api/v1/superadmin/tenants/42/support/admin-password-reset");
    request.setAttribute("jwtClaims", claimsFor("ROOT"));
    MockHttpServletResponse response = new MockHttpServletResponse();

    filter.doFilter(request, response, filterChain);

    assertThat(response.getStatus()).isEqualTo(200);
    verify(companyService).resolveCompanyCodeById(42L);
    verify(companyService).resolveLifecycleStateByCode("TENANT-A");
    verify(tenantRuntimeRequestAdmissionService, never())
        .beginRequest(anyString(), anyString(), anyString(), anyString(), anyBoolean());
    verify(filterChain).doFilter(request, response);
  }

  @Test
  void canonicalLimitsRequest_tracksPrivilegedRuntimeAdmissionForSuperAdmin()
      throws ServletException, IOException {
    authenticate("root-superadmin@bbp.com", Set.of("ROLE_SUPER_ADMIN"), Set.of("ROOT"));
    when(companyService.resolveCompanyCodeById(42L)).thenReturn("TENANT-A");
    when(companyService.resolveLifecycleStateByCode("TENANT-A"))
        .thenReturn(CompanyLifecycleState.DEACTIVATED);
    TenantRuntimeEnforcementService.TenantRequestAdmission admission =
        TenantRuntimeEnforcementService.TenantRequestAdmission.admittedPolicyControl(
            "TENANT-A", "chain-1");
    when(tenantRuntimeRequestAdmissionService.beginRequest(
            "TENANT-A",
            "/api/v1/superadmin/tenants/42/limits",
            "PUT",
            "root-superadmin@bbp.com",
            true))
        .thenReturn(admission);

    MockHttpServletRequest request = request("PUT", "/api/v1/superadmin/tenants/42/limits");
    request.setAttribute("jwtClaims", claimsFor("ROOT"));
    MockHttpServletResponse response = new MockHttpServletResponse();

    filter.doFilter(
        request,
        response,
        (req, res) -> assertThat(CompanyContextHolder.getCompanyCode()).isEqualTo("TENANT-A"));

    assertThat(response.getStatus()).isEqualTo(200);
    verify(tenantRuntimeRequestAdmissionService)
        .beginRequest(
            "TENANT-A",
            "/api/v1/superadmin/tenants/42/limits",
            "PUT",
            "root-superadmin@bbp.com",
            true);
    verify(tenantRuntimeRequestAdmissionService).completeRequest(admission, 200);
  }

  @Test
  void canonicalTenantControlRequest_rejectsNonSuperAdmin() throws ServletException, IOException {
    authenticate("tenant-admin@bbp.com", Set.of("ROLE_ADMIN"), Set.of("ROOT"));
    when(companyService.resolveCompanyCodeById(42L)).thenReturn("TENANT-A");

    MockHttpServletRequest request =
        request("PUT", "/api/v1/superadmin/tenants/42/support/context");
    request.setAttribute("jwtClaims", claimsFor("ROOT"));
    MockHttpServletResponse response = new MockHttpServletResponse();

    filter.doFilter(request, response, filterChain);

    assertThat(response.getStatus()).isEqualTo(403);
    assertThat(response.getContentAsString()).contains(CONTROL_PLANE_AUTH_DENIED_MESSAGE);
    verify(companyService).resolveCompanyCodeById(42L);
    verify(companyService, never()).resolveLifecycleStateByCode(anyString());
    verify(tenantRuntimeRequestAdmissionService, never())
        .beginRequest(anyString(), anyString(), anyString(), anyString(), anyBoolean());
    verify(filterChain, never()).doFilter(request, response);
  }

  @Test
  void canonicalTenantControlRequest_rejectsUnauthenticatedBeforeResolvingTarget()
      throws ServletException, IOException {
    MockHttpServletRequest request = request("GET", "/api/v1/superadmin/tenants/42");
    MockHttpServletResponse response = new MockHttpServletResponse();

    filter.doFilter(request, response, filterChain);

    assertThat(response.getStatus()).isEqualTo(403);
    assertThat(response.getContentAsString()).contains(CONTROL_PLANE_AUTH_DENIED_MESSAGE);
    verifyNoInteractions(companyService);
    verify(tenantRuntimeRequestAdmissionService, never())
        .beginRequest(anyString(), anyString(), anyString(), anyString(), anyBoolean());
  }

  @Test
  void canonicalTenantControlRequest_rejectsAnonymousAuthenticationBeforeResolvingTarget()
      throws ServletException, IOException {
    SecurityContextHolder.getContext()
        .setAuthentication(
            new AnonymousAuthenticationToken(
                "anonymous",
                "anonymousUser",
                List.of(new SimpleGrantedAuthority("ROLE_ANONYMOUS"))));
    MockHttpServletRequest request = request("POST", "/api/v1/superadmin/tenants/42/force-logout");
    MockHttpServletResponse response = new MockHttpServletResponse();

    filter.doFilter(request, response, filterChain);

    assertThat(response.getStatus()).isEqualTo(403);
    assertThat(response.getContentAsString()).contains(CONTROL_PLANE_AUTH_DENIED_MESSAGE);
    verifyNoInteractions(companyService);
  }

  @Test
  void companyScopedRequest_rejectsLegacyCompanyIdHeader() throws ServletException, IOException {
    authenticate("tenant-admin@bbp.com", Set.of("ROLE_ADMIN"), Set.of("TENANT-A"));
    MockHttpServletRequest request = request("GET", "/api/v1/private");
    request.setAttribute("jwtClaims", claimsFor("TENANT-A"));
    request.addHeader("X-Company-Id", "42");
    MockHttpServletResponse response = new MockHttpServletResponse();

    filter.doFilter(request, response, filterChain);

    assertThat(response.getStatus()).isEqualTo(403);
    assertThat(response.getContentAsString()).contains("COMPANY_CONTEXT_LEGACY_HEADER_UNSUPPORTED");
    verifyNoInteractions(companyService);
    verify(tenantRuntimeRequestAdmissionService, never())
        .beginRequest(anyString(), anyString(), anyString(), anyString(), anyBoolean());
  }

  @Test
  void companyScopedRequest_rejectsUnauthenticatedHeaderBoundContext()
      throws ServletException, IOException {
    MockHttpServletRequest request = request("GET", "/api/v1/private");
    request.addHeader("X-Company-Code", "TENANT-A");
    MockHttpServletResponse response = new MockHttpServletResponse();

    filter.doFilter(request, response, filterChain);

    assertThat(response.getStatus()).isEqualTo(403);
    assertThat(response.getContentAsString()).contains("COMPANY_CONTEXT_AUTH_REQUIRED");
    verifyNoInteractions(companyService);
    verify(tenantRuntimeRequestAdmissionService, never())
        .beginRequest(anyString(), anyString(), anyString(), anyString(), anyBoolean());
  }

  @Test
  void companyScopedRequest_rejectsHeaderMismatchWithJwtClaim()
      throws ServletException, IOException {
    authenticate("tenant-admin@bbp.com", Set.of("ROLE_ADMIN"), Set.of("TENANT-A"));
    MockHttpServletRequest request = request("GET", "/api/v1/private");
    request.setAttribute("jwtClaims", claimsFor("TENANT-A"));
    request.addHeader("X-Company-Code", "TENANT-B");
    MockHttpServletResponse response = new MockHttpServletResponse();

    filter.doFilter(request, response, filterChain);

    assertThat(response.getStatus()).isEqualTo(403);
    assertThat(response.getContentAsString()).contains("COMPANY_CONTEXT_MISMATCH");
    verifyNoInteractions(companyService);
    verify(tenantRuntimeRequestAdmissionService, never())
        .beginRequest(anyString(), anyString(), anyString(), anyString(), anyBoolean());
  }

  @Test
  void companyScopedRequest_rejectsWhenRuntimeAdmissionServiceReturnsNull()
      throws ServletException, IOException {
    authenticate("tenant-admin@bbp.com", Set.of("ROLE_ADMIN"), Set.of("TENANT-A"));
    when(companyService.resolveLifecycleStateByCode("TENANT-A"))
        .thenReturn(CompanyLifecycleState.ACTIVE);
    when(tenantRuntimeRequestAdmissionService.beginRequest(
            "TENANT-A", "/api/v1/private", "GET", "tenant-admin@bbp.com", false))
        .thenReturn(null);

    MockHttpServletRequest request = request("GET", "/api/v1/private");
    request.setAttribute("jwtClaims", claimsFor("TENANT-A"));
    MockHttpServletResponse response = new MockHttpServletResponse();

    filter.doFilter(request, response, filterChain);

    assertThat(response.getStatus()).isEqualTo(403);
    assertThat(response.getContentAsString()).contains("TENANT_RUNTIME_ADMISSION_UNAVAILABLE");
    verify(companyService).resolveLifecycleStateByCode("TENANT-A");
    verify(filterChain, never()).doFilter(request, response);
  }

  @Test
  void canonicalTenantControlRequest_rejectsWhenResolvedTenantIsMissing()
      throws ServletException, IOException {
    authenticate("root-superadmin@bbp.com", Set.of("ROLE_SUPER_ADMIN"), Set.of("ROOT"));
    when(companyService.resolveCompanyCodeById(404L)).thenReturn(null);

    MockHttpServletRequest request = request("GET", "/api/v1/superadmin/tenants/404");
    request.setAttribute("jwtClaims", claimsFor("ROOT"));
    MockHttpServletResponse response = new MockHttpServletResponse();

    filter.doFilter(request, response, filterChain);

    assertThat(response.getStatus()).isEqualTo(403);
    assertThat(response.getContentAsString()).contains(CONTROL_PLANE_AUTH_DENIED_MESSAGE);
    verify(companyService).resolveCompanyCodeById(404L);
    verify(companyService, never()).resolveLifecycleStateByCode(anyString());
  }

  @Test
  void canonicalTenantControlRequest_returnsServiceUnavailableWhenTargetLookupFails()
      throws ServletException, IOException {
    authenticate("root-superadmin@bbp.com", Set.of("ROLE_SUPER_ADMIN"), Set.of("ROOT"));
    when(companyService.resolveCompanyCodeById(42L))
        .thenThrow(new RuntimeException("tenant-lookup-unavailable"));

    MockHttpServletRequest request = request("GET", "/api/v1/superadmin/tenants/42");
    request.setAttribute("jwtClaims", claimsFor("ROOT"));
    MockHttpServletResponse response = new MockHttpServletResponse();

    filter.doFilter(request, response, filterChain);

    assertThat(response.getStatus()).isEqualTo(503);
    assertThat(response.getContentAsString())
        .contains("SYS_002")
        .contains("TENANT_CONTROL_TARGET_LOOKUP_UNAVAILABLE");
    verify(companyService).resolveCompanyCodeById(42L);
    verify(companyService, never()).resolveLifecycleStateByCode(anyString());
    verify(tenantRuntimeRequestAdmissionService, never())
        .beginRequest(anyString(), anyString(), anyString(), anyString(), anyBoolean());
    verify(filterChain, never()).doFilter(request, response);
  }

  @Test
  void canonicalTenantControlRequest_returnsServiceUnavailableWhenLifecycleLookupFails()
      throws ServletException, IOException {
    authenticate("root-superadmin@bbp.com", Set.of("ROLE_SUPER_ADMIN"), Set.of("ROOT"));
    when(companyService.resolveCompanyCodeById(42L)).thenReturn("TENANT-A");
    when(companyService.resolveLifecycleStateByCode("TENANT-A"))
        .thenThrow(new RuntimeException("lifecycle-unavailable"));

    MockHttpServletRequest request = request("GET", "/api/v1/superadmin/tenants/42");
    request.setAttribute("jwtClaims", claimsFor("ROOT"));
    MockHttpServletResponse response = new MockHttpServletResponse();

    filter.doFilter(request, response, filterChain);

    assertThat(response.getStatus()).isEqualTo(503);
    assertThat(response.getContentAsString())
        .contains("SYS_002")
        .contains("TENANT_LIFECYCLE_LOOKUP_UNAVAILABLE");
    verify(companyService).resolveCompanyCodeById(42L);
    verify(companyService).resolveLifecycleStateByCode("TENANT-A");
    verify(tenantRuntimeRequestAdmissionService, never())
        .beginRequest(anyString(), anyString(), anyString(), anyString(), anyBoolean());
    verify(filterChain, never()).doFilter(request, response);
  }

  @Test
  void auditTenantWorkflowRequest_rejectsSuperAdminAsPlatformOnly()
      throws ServletException, IOException {
    authenticate("root-superadmin@bbp.com", Set.of("ROLE_SUPER_ADMIN"), Set.of("TENANT-A"));

    MockHttpServletRequest request = request("GET", "/api/v1/admin/audit/events");
    request.setAttribute("jwtClaims", claimsFor("TENANT-A"));
    MockHttpServletResponse response = new MockHttpServletResponse();

    filter.doFilter(request, response, filterChain);

    assertThat(response.getStatus()).isEqualTo(403);
    assertThat(response.getContentAsString()).contains("SUPER_ADMIN_TENANT_WORKFLOW_DENIED");
    verifyNoInteractions(companyService);
  }

  @Test
  void platformScopeAllowlist_exposesOnlyPlatformControlRoutes() {
    assertThat(
            (Boolean)
                ReflectionTestUtils.invokeMethod(
                    filter, "isPlatformScopedRequestAllowed", "/api/v1/admin/settings"))
        .isTrue();
    assertThat(
            (Boolean)
                ReflectionTestUtils.invokeMethod(
                    filter, "isPlatformScopedRequestAllowed", "/api/v1/companies"))
        .isTrue();
    assertThat(
            (Boolean)
                ReflectionTestUtils.invokeMethod(
                    filter, "isPlatformScopedRequestAllowed", "/api/v1/admin/audit/events"))
        .isFalse();
    assertThat(
            (Boolean)
                ReflectionTestUtils.invokeMethod(
                    filter,
                    "hasTenantRuntimePolicyControlAuthority",
                    "/api/v1/superadmin/tenants/42/limits",
                    "PUT"))
        .isTrue();
  }

  @Test
  void platformScopedSuperAdmin_rejectsNonAllowlistedBusinessWorkflow()
      throws ServletException, IOException {
    authenticate("root-superadmin@bbp.com", Set.of("ROLE_SUPER_ADMIN"), Set.of());
    when(authScopeService.isPlatformScope("PLATFORM")).thenReturn(true);

    MockHttpServletRequest request = request("GET", "/api/v1/admin/audit/events");
    request.setAttribute("jwtClaims", claimsFor("PLATFORM"));
    request.addHeader("X-Company-Code", "PLATFORM");
    MockHttpServletResponse response = new MockHttpServletResponse();

    filter.doFilter(request, response, filterChain);

    assertThat(response.getStatus()).isEqualTo(403);
    assertThat(response.getContentAsString()).contains("SUPER_ADMIN_PLATFORM_ONLY");
    verify(filterChain, never()).doFilter(request, response);
    verifyNoInteractions(companyService);
  }

  @Test
  void canonicalTenantControlRequest_allowsTenantAdminWhenTokenCompanyMatchesPathTarget()
      throws ServletException, IOException {
    authenticate("tenant-admin@bbp.com", Set.of("ROLE_ADMIN"), Set.of("TENANT-A"));
    when(companyService.resolveCompanyCodeById(42L)).thenReturn("TENANT-A");
    when(companyService.resolveLifecycleStateByCode("TENANT-A"))
        .thenReturn(CompanyLifecycleState.ACTIVE);

    MockHttpServletRequest request = request("PUT", "/api/v1/superadmin/tenants/42/limits");
    request.setAttribute("jwtClaims", claimsFor("TENANT-A"));
    MockHttpServletResponse response = new MockHttpServletResponse();

    filter.doFilter(request, response, filterChain);

    assertThat(response.getStatus()).isEqualTo(200);
    verify(companyService).resolveCompanyCodeById(42L);
    verify(companyService).resolveLifecycleStateByCode("TENANT-A");
    verify(tenantRuntimeRequestAdmissionService, never())
        .beginRequest(anyString(), anyString(), anyString(), anyString(), anyBoolean());
    verify(filterChain).doFilter(request, response);
  }

  @Test
  void extractCompanyIdFromControlPlanePath_handlesCanonicalAndMalformedValues() {
    assertThat(extractCompanyId(null)).isNull();
    assertThat(extractCompanyId("   ")).isNull();
    assertThat(extractCompanyId("/api/v1/private")).isNull();
    assertThat(extractCompanyId("/api/v1/superadmin/tenants/not-a-number")).isNull();
    assertThat(extractCompanyId("/api/v1/superadmin/tenants//limits")).isNull();
    assertThat(extractCompanyId("/api/v1/superadmin/tenants/42")).isEqualTo(42L);
    assertThat(extractCompanyId("/api/v1/superadmin/tenants/42/limits")).isEqualTo(42L);
    assertThat(extractCompanyId("/api/v1/superadmin/tenants/42/support/admin-password-reset"))
        .isEqualTo(42L);
  }

  @Test
  void retiredSharedSupportPrefix_isNotClassifiedAsTenantBusinessKnowledge() {
    assertThat(isTenantBusinessRequestBlockedForSuperAdmin("/api/v1/support")).isFalse();
    assertThat(isTenantBusinessRequestBlockedForSuperAdmin("/api/v1/support/tickets")).isFalse();
    assertThat(isTenantBusinessRequestBlockedForSuperAdmin("/api/v1/portal/support/tickets"))
        .isTrue();
    assertThat(isTenantBusinessRequestBlockedForSuperAdmin("/api/v1/dealer-portal/support/tickets"))
        .isTrue();
  }

  private Long extractCompanyId(String path) {
    return (Long)
        ReflectionTestUtils.invokeMethod(filter, "extractCompanyIdFromControlPlanePath", path);
  }

  private boolean isTenantBusinessRequestBlockedForSuperAdmin(String path) {
    return ReflectionTestUtils.invokeMethod(
        filter, "isTenantBusinessRequestBlockedForSuperAdmin", path);
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
    List<SimpleGrantedAuthority> granted =
        authorities.stream().map(SimpleGrantedAuthority::new).toList();
    SecurityContextHolder.getContext()
        .setAuthentication(new UsernamePasswordAuthenticationToken(principal, "n/a", granted));
  }

  private Claims claimsFor(String companyCode) {
    Claims claims = mock(Claims.class);
    lenient().when(claims.get("companyCode", String.class)).thenReturn(companyCode);
    lenient().when(claims.get("cid", String.class)).thenReturn(null);
    return claims;
  }

  private MockHttpServletRequest request(String method, String servletPath) {
    MockHttpServletRequest request = new MockHttpServletRequest(method, servletPath);
    request.setServletPath(servletPath);
    request.setRequestURI(servletPath);
    return request;
  }
}
