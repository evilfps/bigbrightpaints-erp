package com.bigbrightpaints.erp.core.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.bigbrightpaints.erp.modules.auth.domain.UserAccount;
import com.bigbrightpaints.erp.modules.auth.domain.UserPrincipal;
import com.bigbrightpaints.erp.modules.company.domain.Company;
import com.bigbrightpaints.erp.modules.company.domain.CompanyLifecycleState;
import com.bigbrightpaints.erp.modules.company.service.CompanyService;
import com.bigbrightpaints.erp.modules.company.service.TenantRuntimeEnforcementService;
import io.jsonwebtoken.Claims;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import java.io.IOException;
import java.lang.reflect.Constructor;
import java.util.List;
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
import org.springframework.security.core.Authentication;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.util.ReflectionTestUtils;

@Tag("critical")
@Tag("concurrency")
@ExtendWith(MockitoExtension.class)
class TS_RuntimeCompanyContextFilterExecutableCoverageTest {

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    @Mock
    private TenantRuntimeEnforcementService tenantRuntimeEnforcementService;

    @Mock
    private CompanyService companyService;

    @Mock
    private FilterChain filterChain;

    private CompanyContextFilter filter;

    @BeforeEach
    void setUp() {
        filter = new CompanyContextFilter(tenantRuntimeEnforcementService, companyService);
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
        assertThat(response.getErrorMessage()).isEqualTo("Access denied to company-scoped request");
        verifyNoInteractions(companyService);
        verify(tenantRuntimeEnforcementService).completeRequest(
                any(TenantRuntimeEnforcementService.TenantRequestAdmission.class), eq(403));
        verify(filterChain, never()).doFilter(request, response);
    }

    @Test
    void doFilter_rejectsAuthenticatedUserWithoutCompanyMembership() throws ServletException, IOException {
        authenticate("user@bbp.com", Set.of("ROLE_ADMIN"), Set.of("OTHER"));
        when(companyService.resolveLifecycleStateByCode("ACME")).thenReturn(CompanyLifecycleState.ACTIVE);

        MockHttpServletRequest request = request("GET", "/api/v1/private");
        request.setAttribute("jwtClaims", claimsFor("ACME"));
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter.doFilter(request, response, filterChain);

        assertThat(response.getStatus()).isEqualTo(403);
        assertThat(response.getErrorMessage()).isEqualTo("Access denied to company: ACME");
        verify(tenantRuntimeEnforcementService, never()).beginRequest(anyString(), anyString(), anyString(), anyString(), anyBoolean());
        verify(filterChain, never()).doFilter(request, response);
    }

    @Test
    void doFilter_allowsSuperAdminLifecycleControlBypassForNonActiveTenant() throws ServletException, IOException {
        authenticate("ops@bbp.com", Set.of("ROLE_SUPER_ADMIN"), Set.of());
        when(companyService.resolveLifecycleStateByCode("ACME")).thenReturn(CompanyLifecycleState.BLOCKED);
        when(tenantRuntimeEnforcementService.beginRequest(
                eq("ACME"),
                eq("/api/v1/companies/42/lifecycle-state"),
                eq("POST"),
                eq("ops@bbp.com"),
                eq(false)))
                .thenReturn(admission(true, 200, null));

        MockHttpServletRequest request = request("POST", "/api/v1/companies/42/lifecycle-state");
        request.setAttribute("jwtClaims", claimsFor("ACME"));
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter.doFilter(request, response, filterChain);

        assertThat(response.getStatus()).isEqualTo(200);
        verify(filterChain).doFilter(request, response);
    }

    @Test
    void doFilter_rejectsWhenTenantRuntimeAdmissionDenied_andEscapesResponseMessage() throws ServletException, IOException {
        authenticate("admin@bbp.com", Set.of("ROLE_ADMIN"), Set.of("ACME"));
        when(companyService.resolveLifecycleStateByCode("ACME")).thenReturn(CompanyLifecycleState.ACTIVE);
        when(tenantRuntimeEnforcementService.beginRequest(
                eq("ACME"),
                eq("/api/v1/private"),
                eq("GET"),
                eq("admin@bbp.com"),
                eq(true)))
                .thenReturn(admission(false, 429, "bad \"quote\" \\\\ slash"));

        MockHttpServletRequest request = request("GET", "/api/v1/private");
        request.setAttribute("jwtClaims", claimsFor("ACME"));
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter.doFilter(request, response, filterChain);

        assertThat(response.getStatus()).isEqualTo(429);
        Map<String, String> payload =
                OBJECT_MAPPER.readValue(response.getContentAsString(), new TypeReference<>() {});
        assertThat(payload).containsEntry("message", "bad \"quote\" \\\\ slash");
        verify(filterChain, never()).doFilter(request, response);
    }

    @Test
    void doFilter_rejectsNonActiveTenantForNonLifecycleRequests() throws ServletException, IOException {
        authenticate("admin@bbp.com", Set.of("ROLE_ADMIN"), Set.of("ACME"));
        when(companyService.resolveLifecycleStateByCode("ACME")).thenReturn(CompanyLifecycleState.BLOCKED);

        MockHttpServletRequest request = request("GET", "/api/v1/private");
        request.setAttribute("jwtClaims", claimsFor("ACME"));
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter.doFilter(request, response, filterChain);

        assertThat(response.getStatus()).isEqualTo(403);
        assertThat(response.getErrorMessage()).isEqualTo("Tenant lifecycle state does not allow access");
        verify(tenantRuntimeEnforcementService, never()).beginRequest(anyString(), anyString(), anyString(), anyString(), anyBoolean());
    }

    @Test
    void doFilter_rejectsLifecycleControlMutationForNonSuperAdminOnBlockedTenant() throws ServletException, IOException {
        authenticate("admin@bbp.com", Set.of("ROLE_ADMIN"), Set.of("ACME"));
        when(companyService.resolveLifecycleStateByCode("ACME")).thenReturn(CompanyLifecycleState.BLOCKED);

        MockHttpServletRequest request = request("POST", "/api/v1/companies/42/lifecycle-state");
        request.setAttribute("jwtClaims", claimsFor("ACME"));
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter.doFilter(request, response, filterChain);

        assertThat(response.getStatus()).isEqualTo(403);
        assertThat(response.getErrorMessage()).isEqualTo("Tenant lifecycle state does not allow access");
        verify(tenantRuntimeEnforcementService, never()).beginRequest(anyString(), anyString(), anyString(), anyString(), anyBoolean());
    }

    @Test
    void helperMethods_coverAuthorityAndPathResolutionBranches() {
        assertThat(invokeHasSuperAdminAuthority()).isFalse();
        assertThat(invokeHasTenantRuntimePolicyControlAuthority()).isFalse();

        authenticate("root@bbp.com", Set.of("ROLE_SUPER_ADMIN"), Set.of());
        assertThat(invokeHasSuperAdminAuthority()).isTrue();
        assertThat(invokeHasTenantRuntimePolicyControlAuthority()).isFalse();

        authenticate("admin@bbp.com", Set.of("ROLE_ADMIN"), Set.of("ACME"));
        assertThat(invokeHasTenantRuntimePolicyControlAuthority()).isTrue();

        MockHttpServletRequest lifecycleMutation = request("POST", "/api/v1/companies/77/lifecycle-state");
        MockHttpServletRequest tenantMetrics = request("GET", "/api/v1/companies/77/tenant-metrics");
        MockHttpServletRequest nonLifecycle = request("POST", "/api/v1/private");
        assertThat(invokeIsLifecycleControlRequest(lifecycleMutation)).isTrue();
        assertThat(invokeIsLifecycleControlRequest(tenantMetrics)).isTrue();
        assertThat(invokeIsLifecycleControlRequest(nonLifecycle)).isFalse();

        assertThat(invokeResolveApplicationPath(null)).isNull();
        MockHttpServletRequest contextAware = request("POST", "");
        contextAware.setContextPath("/erp");
        contextAware.setRequestURI("/erp/api/v1/companies/77/lifecycle-state");
        assertThat(invokeResolveApplicationPath(contextAware)).isEqualTo("/api/v1/companies/77/lifecycle-state");

        MockHttpServletRequest contextRoot = request("GET", "");
        contextRoot.setContextPath("/erp");
        contextRoot.setRequestURI("/erp");
        assertThat(invokeResolveApplicationPath(contextRoot)).isEqualTo("/");
    }

    @Test
    void helperMethods_coverUnauthenticatedAndLifecyclePathFallbackBranches() {
        Authentication unauthenticated = mock(Authentication.class);
        when(unauthenticated.isAuthenticated()).thenReturn(false);
        SecurityContextHolder.getContext().setAuthentication(unauthenticated);
        assertThat(invokeHasSuperAdminAuthority()).isFalse();
        assertThat(invokeHasTenantRuntimePolicyControlAuthority()).isFalse();

        MockHttpServletRequest lifecycleMutationWrongSuffix = request("POST", "/api/v1/companies/77/not-lifecycle");
        assertThat(invokeIsLifecycleControlRequest(lifecycleMutationWrongSuffix)).isFalse();

        MockHttpServletRequest tenantMetricsWrongSuffix = request("GET", "/api/v1/companies/77/not-metrics");
        assertThat(invokeIsLifecycleControlRequest(tenantMetricsWrongSuffix)).isFalse();

        MockHttpServletRequest noPath = request("GET", "");
        noPath.setServletPath("   ");
        noPath.setRequestURI("   ");
        assertThat(invokeIsLifecycleControlRequest(noPath)).isFalse();
        assertThat(invokeResolveApplicationPath(noPath)).isNull();

        MockHttpServletRequest contextStrippedPath = request("GET", "");
        contextStrippedPath.setServletPath(" ");
        contextStrippedPath.setContextPath("/erp");
        contextStrippedPath.setRequestURI("/erp/api/v1/private");
        assertThat(invokeResolveApplicationPath(contextStrippedPath)).isEqualTo("/api/v1/private");

        MockHttpServletRequest contextNotMatched = request("GET", "");
        contextNotMatched.setServletPath(" ");
        contextNotMatched.setContextPath("/erp");
        contextNotMatched.setRequestURI("/outside/api/v1/private");
        assertThat(invokeResolveApplicationPath(contextNotMatched)).isEqualTo("/outside/api/v1/private");
    }

    private void authenticate(String email, Set<String> authorities, Set<String> companyCodes) {
        UserAccount user = new UserAccount(email, "hash", "Operator");
        for (String code : companyCodes) {
            Company company = new Company();
            company.setCode(code);
            user.addCompany(company);
        }
        UserPrincipal principal = new UserPrincipal(user);
        List<SimpleGrantedAuthority> grantedAuthorities = authorities.stream()
                .map(SimpleGrantedAuthority::new)
                .toList();
        Authentication authentication = new UsernamePasswordAuthenticationToken(principal, "n/a", grantedAuthorities);
        SecurityContextHolder.getContext().setAuthentication(authentication);
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

    private TenantRuntimeEnforcementService.TenantRequestAdmission admission(boolean admitted, int statusCode, String message) {
        try {
            Class<?> countersClass = Class.forName(
                    TenantRuntimeEnforcementService.class.getName() + "$TenantRuntimeCounters");
            Constructor<?> countersConstructor = countersClass.getDeclaredConstructor();
            countersConstructor.setAccessible(true);
            Object counters = countersConstructor.newInstance();

            Constructor<TenantRuntimeEnforcementService.TenantRequestAdmission> constructor =
                    TenantRuntimeEnforcementService.TenantRequestAdmission.class.getDeclaredConstructor(
                            boolean.class,
                            String.class,
                            String.class,
                            countersClass,
                            int.class,
                            String.class,
                            boolean.class);
            constructor.setAccessible(true);
            return constructor.newInstance(
                    admitted,
                    "ACME",
                    "audit-chain",
                    admitted ? counters : null,
                    statusCode,
                    message,
                    false);
        } catch (ReflectiveOperationException ex) {
            throw new IllegalStateException("Unable to construct tenant admission handle", ex);
        }
    }

    private boolean invokeHasSuperAdminAuthority() {
        Boolean result = ReflectionTestUtils.invokeMethod(filter, "hasSuperAdminAuthority");
        assertThat(result).isNotNull();
        return result;
    }

    private boolean invokeHasTenantRuntimePolicyControlAuthority() {
        Boolean result = ReflectionTestUtils.invokeMethod(filter, "hasTenantRuntimePolicyControlAuthority");
        assertThat(result).isNotNull();
        return result;
    }

    private boolean invokeIsLifecycleControlRequest(MockHttpServletRequest request) {
        Boolean result = ReflectionTestUtils.invokeMethod(filter, "isLifecycleControlRequest", request);
        assertThat(result).isNotNull();
        return result;
    }

    private String invokeResolveApplicationPath(MockHttpServletRequest request) {
        return ReflectionTestUtils.invokeMethod(filter, "resolveApplicationPath", request);
    }
}
