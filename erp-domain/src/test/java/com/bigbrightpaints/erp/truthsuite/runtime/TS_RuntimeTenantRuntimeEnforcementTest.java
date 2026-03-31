package com.bigbrightpaints.erp.truthsuite.runtime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.lang.reflect.Constructor;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
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

@ExtendWith(MockitoExtension.class)
@Tag("critical")
@Tag("concurrency")
@Tag("reconciliation")
class TS_RuntimeTenantRuntimeEnforcementTest {

  @Mock private TenantRuntimeRequestAdmissionService tenantRuntimeRequestAdmissionService;

  @Mock private CompanyService companyService;

  @Mock private AuthScopeService authScopeService;

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
  void rejectsMismatchedCompanyHeaders_failClosed() throws Exception {
    MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/v1/portal/dashboard");
    request.addHeader("X-Company-Code", "ACME");
    request.addHeader("X-Company-Id", "BETA");
    MockHttpServletResponse response = new MockHttpServletResponse();

    filter.doFilter(request, response, new MockFilterChain());

    assertThat(response.getStatus()).isEqualTo(403);
    verify(tenantRuntimeRequestAdmissionService)
        .completeRequest(any(), org.mockito.ArgumentMatchers.eq(403));
    verifyNoInteractions(companyService);
  }

  @Test
  void rejectsMismatchedTokenClaims_failClosed() throws Exception {
    MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/v1/portal/dashboard");
    request.setAttribute("jwtClaims", claims("ACME", "BETA"));
    MockHttpServletResponse response = new MockHttpServletResponse();

    filter.doFilter(request, response, new MockFilterChain());

    assertThat(response.getStatus()).isEqualTo(403);
    verify(companyService).resolveLifecycleStateByCode("ACME");
  }

  @Test
  void rejectsMutatingRequestWhenTenantLifecycleIsSuspended_beforeRuntimeAdmission()
      throws Exception {
    authenticateForCompany("actor@bbp.com", "ACME", "ROLE_ADMIN");
    when(companyService.resolveLifecycleStateByCode("ACME"))
        .thenReturn(CompanyLifecycleState.SUSPENDED);

    MockHttpServletRequest request = new MockHttpServletRequest("POST", "/api/v1/private");
    request.setAttribute("jwtClaims", claims("ACME", null));
    MockHttpServletResponse response = new MockHttpServletResponse();

    filter.doFilter(request, response, new MockFilterChain());

    assertThat(response.getStatus()).isEqualTo(403);
    verify(tenantRuntimeRequestAdmissionService, never())
        .beginRequest(anyString(), anyString(), anyString(), anyString(), anyBoolean());
  }

  @Test
  void rejectsReadRequestWhenTenantLifecycleIsSuspended_beforeRuntimeAdmission()
      throws Exception {
    authenticateForCompany("actor@bbp.com", "ACME", "ROLE_ADMIN");
    when(companyService.resolveLifecycleStateByCode("ACME"))
        .thenReturn(CompanyLifecycleState.SUSPENDED);

    MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/v1/private");
    request.setAttribute("jwtClaims", claims("ACME", null));
    MockHttpServletResponse response = new MockHttpServletResponse();

    filter.doFilter(request, response, new MockFilterChain());

    assertThat(response.getStatus()).isEqualTo(403);
    verify(tenantRuntimeRequestAdmissionService, never())
        .beginRequest(anyString(), anyString(), anyString(), anyString(), anyBoolean());
  }

  @Test
  void rejectsRequestWhenTenantRuntimeAdmissionIsMissing_failClosed() throws Exception {
    authenticateForCompany("actor@bbp.com", "ACME", "ROLE_ADMIN");
    when(companyService.resolveLifecycleStateByCode("ACME"))
        .thenReturn(CompanyLifecycleState.ACTIVE);
    when(tenantRuntimeRequestAdmissionService.beginRequest(
            "ACME", "/api/v1/private", "GET", "actor@bbp.com", false))
        .thenReturn(null);

    MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/v1/private");
    request.setAttribute("jwtClaims", claims("ACME", null));
    MockHttpServletResponse response = new MockHttpServletResponse();

    filter.doFilter(request, response, new MockFilterChain());

    assertThat(response.getStatus()).isEqualTo(403);
    assertThat(response.getContentAsString()).contains("Tenant runtime admission is unavailable");
  }

  @Test
  void canonicalSuperadminControlBypassesRuntimeAdmission_forInactiveTenant() throws Exception {
    authenticateForCompany("super-admin@bbp.com", "ROOT", "ROLE_SUPER_ADMIN");
    when(companyService.resolveCompanyCodeById(42L)).thenReturn("ACME");
    when(companyService.resolveLifecycleStateByCode("ACME"))
        .thenReturn(CompanyLifecycleState.DEACTIVATED);

    MockHttpServletRequest request =
        new MockHttpServletRequest("PUT", "/api/v1/superadmin/tenants/42/limits");
    request.setServletPath("/api/v1/superadmin/tenants/42/limits");
    request.setAttribute("jwtClaims", claims("ROOT", null));
    MockHttpServletResponse response = new MockHttpServletResponse();
    AtomicReference<String> companyInChain = new AtomicReference<>();

    filter.doFilter(
        request, response, (req, res) -> companyInChain.set(CompanyContextHolder.getCompanyCode()));

    assertThat(response.getStatus()).isEqualTo(200);
    assertThat(companyInChain.get()).isEqualTo("ACME");
    verify(tenantRuntimeRequestAdmissionService, never())
        .beginRequest(anyString(), anyString(), anyString(), anyString(), anyBoolean());
  }

  @Test
  void admittedRequest_setsAndClearsCompanyContext_andCompletes() throws Exception {
    authenticateForCompany("actor@bbp.com", "ACME", "ROLE_ADMIN");
    when(companyService.resolveLifecycleStateByCode("ACME"))
        .thenReturn(CompanyLifecycleState.ACTIVE);

    TenantRuntimeEnforcementService.TenantRequestAdmission admittedAdmission =
        admission(true, "ACME", 200, null);
    when(tenantRuntimeRequestAdmissionService.beginRequest(
            "ACME", "/api/v1/private", "GET", "actor@bbp.com", false))
        .thenReturn(admittedAdmission);

    MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/v1/private");
    request.setAttribute("jwtClaims", claims("ACME", null));
    MockHttpServletResponse response = new MockHttpServletResponse();
    AtomicReference<String> companyInChain = new AtomicReference<>();

    filter.doFilter(
        request, response, (req, res) -> companyInChain.set(CompanyContextHolder.getCompanyCode()));

    assertThat(companyInChain.get()).isEqualTo("ACME");
    assertThat(CompanyContextHolder.getCompanyCode()).isNull();
    verify(tenantRuntimeRequestAdmissionService).completeRequest(admittedAdmission, 200);
  }

  @Test
  void actuatorPaths_bypassFilterAndDoNotInvokeTenantEnforcement() throws Exception {
    MockHttpServletRequest request = new MockHttpServletRequest("GET", "/actuator/health");
    request.setServletPath("/actuator/health");
    MockHttpServletResponse response = new MockHttpServletResponse();
    MockFilterChain chain = new MockFilterChain();

    filter.doFilter(request, response, chain);

    assertThat(chain.getRequest()).isNotNull();
    verifyNoInteractions(tenantRuntimeRequestAdmissionService, companyService);
  }

  @Test
  void privateHelperMethods_coverCanonicalControlRequestBranches() {
    assertThat(
            (Boolean)
                ReflectionTestUtils.invokeMethod(
                    filter,
                    "isLifecycleControlRequest",
                    "/api/v1/superadmin/tenants/7/limits",
                    "PUT"))
        .isTrue();
    assertThat(
            (Boolean)
                ReflectionTestUtils.invokeMethod(
                    filter,
                    "isLifecycleControlRequest",
                    "/api/v1/superadmin/tenants/7/admins/3/email-change/confirm",
                    "POST"))
        .isTrue();
    assertThat(
            (Boolean)
                ReflectionTestUtils.invokeMethod(
                    filter,
                    "isLifecycleControlRequest",
                    "/api/v1/superadmin/tenants/7/limits",
                    "PATCH"))
        .isFalse();
  }

  private Claims claims(String companyCode, String legacyCompanyId) {
    Claims claims = mock(Claims.class);
    when(claims.get("companyCode", String.class)).thenReturn(companyCode);
    lenient().when(claims.get("cid", String.class)).thenReturn(legacyCompanyId);
    return claims;
  }

  private void authenticateForCompany(String email, String companyCode, String authority) {
    UserAccount user = new UserAccount(email, "hash", "Operator");
    Company company = new Company();
    company.setCode(companyCode);
    user.setCompany(company);
    user.setAuthScopeCode(companyCode);
    UserPrincipal principal = new UserPrincipal(user);
    SecurityContextHolder.getContext()
        .setAuthentication(
            new UsernamePasswordAuthenticationToken(
                principal,
                "n/a",
                java.util.List.of(
                    (org.springframework.security.core.GrantedAuthority) () -> authority)));
  }

  private TenantRuntimeEnforcementService.TenantRequestAdmission admission(
      boolean admitted, String companyCode, int statusCode, String message) throws Exception {
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
        companyCode,
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
  }
}
