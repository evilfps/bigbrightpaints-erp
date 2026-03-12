package com.bigbrightpaints.erp.modules.auth;

import com.bigbrightpaints.erp.core.security.CompanyContextFilter;
import com.bigbrightpaints.erp.core.security.CompanyContextHolder;
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
import java.util.Set;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.util.ReflectionTestUtils;

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

@ExtendWith(MockitoExtension.class)
class CompanyContextFilterControlPlaneBindingTest {

    private static final String CONTROL_PLANE_AUTH_DENIED_MESSAGE = "Access denied to company control request";

    @Mock
    private TenantRuntimeEnforcementService tenantRuntimeEnforcementService;

    @Mock
    private CompanyService companyService;

    @Mock
    private FilterChain filterChain;

    private CompanyContextFilter filter;

    @BeforeEach
    void setUp() {
        filter = new CompanyContextFilter(
                tenantRuntimeEnforcementService,
                companyService,
                new ObjectMapper().findAndRegisterModules());
    }

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
        CompanyContextHolder.clear();
    }

    @Test
    void lifecycleControlRequest_bindsSuperAdminFlowToPathTargetCompany() throws ServletException, IOException {
        authenticate("root-superadmin@bbp.com", Set.of("ROLE_SUPER_ADMIN"), Set.of("ROOT"));
        when(companyService.resolveCompanyCodeById(42L)).thenReturn("TENANT-A");
        when(companyService.resolveLifecycleStateByCode("TENANT-A")).thenReturn(CompanyLifecycleState.ACTIVE);

        MockHttpServletRequest request = request("POST", "/api/v1/companies/42/lifecycle-state");
        request.setAttribute("jwtClaims", claimsFor("ROOT"));
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter.doFilter(request, response, filterChain);

        assertThat(response.getStatus()).isEqualTo(200);
        verify(companyService).resolveCompanyCodeById(42L);
        verify(companyService).resolveLifecycleStateByCode("TENANT-A");
        verify(tenantRuntimeEnforcementService, never())
                .beginRequest(anyString(), anyString(), anyString(), anyString(), anyBoolean());
        verify(filterChain).doFilter(request, response);
    }

    @Test
    void tenantConfigurationUpdateRequest_bindsSuperAdminFlowToPathTargetCompany() throws ServletException, IOException {
        authenticate("root-superadmin@bbp.com", Set.of("ROLE_SUPER_ADMIN"), Set.of("ROOT"));
        when(companyService.resolveCompanyCodeById(42L)).thenReturn("TENANT-A");
        when(companyService.resolveLifecycleStateByCode("TENANT-A")).thenReturn(CompanyLifecycleState.ACTIVE);

        MockHttpServletRequest request = request("PUT", "/api/v1/companies/42");
        request.setAttribute("jwtClaims", claimsFor("ROOT"));
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter.doFilter(request, response, filterChain);

        assertThat(response.getStatus()).isEqualTo(200);
        verify(companyService).resolveCompanyCodeById(42L);
        verify(companyService).resolveLifecycleStateByCode("TENANT-A");
        verify(tenantRuntimeEnforcementService, never())
                .beginRequest(anyString(), anyString(), anyString(), anyString(), anyBoolean());
        verify(filterChain).doFilter(request, response);
    }

    @Test
    void supportAdminPasswordResetRequest_bindsSuperAdminFlowToPathTargetCompany() throws ServletException, IOException {
        authenticate("root-superadmin@bbp.com", Set.of("ROLE_SUPER_ADMIN"), Set.of("ROOT"));
        when(companyService.resolveCompanyCodeById(42L)).thenReturn("TENANT-A");
        when(companyService.resolveLifecycleStateByCode("TENANT-A")).thenReturn(CompanyLifecycleState.ACTIVE);

        MockHttpServletRequest request = request("POST", "/api/v1/companies/42/support/admin-password-reset");
        request.setAttribute("jwtClaims", claimsFor("ROOT"));
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter.doFilter(request, response, filterChain);

        assertThat(response.getStatus()).isEqualTo(200);
        verify(companyService).resolveCompanyCodeById(42L);
        verify(companyService).resolveLifecycleStateByCode("TENANT-A");
        verify(tenantRuntimeEnforcementService, never())
                .beginRequest(anyString(), anyString(), anyString(), anyString(), anyBoolean());
        verify(filterChain).doFilter(request, response);
    }

    @Test
    void auditTenantWorkflowRequest_rejectsTenantAttachedSuperAdminAsPlatformOnly()
            throws ServletException, IOException {
        authenticate("root-superadmin@bbp.com", Set.of("ROLE_SUPER_ADMIN"), Set.of("TENANT-A"));

        MockHttpServletRequest request = request("GET", "/api/v1/audit/business-events");
        request.setAttribute("jwtClaims", claimsFor("TENANT-A"));
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter.doFilter(request, response, filterChain);

        assertThat(response.getStatus()).isEqualTo(403);
        assertThat(response.getContentAsString()).contains("SUPER_ADMIN_TENANT_WORKFLOW_DENIED");
        assertThat(response.getContentAsString()).contains("platform-only super admin");
        verifyNoInteractions(companyService);
        verify(tenantRuntimeEnforcementService, never())
                .beginRequest(anyString(), anyString(), anyString(), anyString(), anyBoolean());
        verify(filterChain, never()).doFilter(request, response);
    }

    @Test
    void supportTenantWorkflowRequest_rejectsTenantAttachedSuperAdminAsPlatformOnly()
            throws ServletException, IOException {
        authenticate("root-superadmin@bbp.com", Set.of("ROLE_SUPER_ADMIN"), Set.of("TENANT-A"));

        MockHttpServletRequest request = request("GET", "/api/v1/support/tickets");
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter.doFilter(request, response, filterChain);

        assertThat(response.getStatus()).isEqualTo(403);
        assertThat(response.getContentAsString()).contains("SUPER_ADMIN_PLATFORM_ONLY");
        assertThat(response.getContentAsString()).contains("platform control-plane operations");
        verifyNoInteractions(companyService);
        verify(tenantRuntimeEnforcementService, never())
                .beginRequest(anyString(), anyString(), anyString(), anyString(), anyBoolean());
        verify(filterChain, never()).doFilter(request, response);
    }

    @Test
    void lifecycleControlRequest_rejectsNonSuperAdminWhenPathTargetDiffersFromContextCompany()
            throws ServletException, IOException {
        authenticate("tenant-admin@bbp.com", Set.of("ROLE_ADMIN"), Set.of("ROOT"));
        when(companyService.resolveCompanyCodeById(42L)).thenReturn("TENANT-A");

        MockHttpServletRequest request = request("POST", "/api/v1/companies/42/lifecycle-state");
        request.setAttribute("jwtClaims", claimsFor("ROOT"));
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter.doFilter(request, response, filterChain);

        assertThat(response.getStatus()).isEqualTo(403);
        assertThat(response.getContentAsString()).contains(CONTROL_PLANE_AUTH_DENIED_MESSAGE);
        verify(companyService).resolveCompanyCodeById(42L);
        verify(companyService, never()).resolveLifecycleStateByCode(anyString());
        verify(tenantRuntimeEnforcementService, never())
                .beginRequest(anyString(), anyString(), anyString(), anyString(), anyBoolean());
        verify(filterChain, never()).doFilter(request, response);
    }

    @Test
    void controlPlaneRequest_returnsUniformForbiddenMessageForForeignAndUnknownTargets()
            throws ServletException, IOException {
        authenticate("tenant-admin@bbp.com", Set.of("ROLE_ADMIN"), Set.of("ROOT"));
        when(companyService.resolveCompanyCodeById(42L)).thenReturn("TENANT-A");
        when(companyService.resolveCompanyCodeById(404L)).thenReturn(null);

        MockHttpServletRequest foreignTenantRequest = request("PUT", "/api/v1/companies/42");
        foreignTenantRequest.setAttribute("jwtClaims", claimsFor("ROOT"));
        MockHttpServletResponse foreignTenantResponse = new MockHttpServletResponse();
        filter.doFilter(foreignTenantRequest, foreignTenantResponse, filterChain);

        MockHttpServletRequest unknownTenantRequest = request("PUT", "/api/v1/companies/404");
        unknownTenantRequest.setAttribute("jwtClaims", claimsFor("ROOT"));
        MockHttpServletResponse unknownTenantResponse = new MockHttpServletResponse();
        filter.doFilter(unknownTenantRequest, unknownTenantResponse, filterChain);

        assertThat(foreignTenantResponse.getStatus()).isEqualTo(403);
        assertThat(foreignTenantResponse.getContentAsString()).contains(CONTROL_PLANE_AUTH_DENIED_MESSAGE);
        assertThat(unknownTenantResponse.getStatus()).isEqualTo(403);
        assertThat(unknownTenantResponse.getContentAsString()).contains(CONTROL_PLANE_AUTH_DENIED_MESSAGE);
        verify(companyService).resolveCompanyCodeById(42L);
        verify(companyService).resolveCompanyCodeById(404L);
        verify(companyService, never()).resolveLifecycleStateByCode(anyString());
        verify(tenantRuntimeEnforcementService, never())
                .beginRequest(anyString(), anyString(), anyString(), anyString(), anyBoolean());
        verify(filterChain, never()).doFilter(any(), any());
    }

    @Test
    void lifecycleControlRequest_rejectsUnauthenticatedBeforeResolvingTenantFromPath()
            throws ServletException, IOException {
        MockHttpServletRequest existingTenantRequest = request("POST", "/api/v1/companies/42/lifecycle-state");
        MockHttpServletResponse existingTenantResponse = new MockHttpServletResponse();
        filter.doFilter(existingTenantRequest, existingTenantResponse, filterChain);
        assertThat(existingTenantResponse.getStatus()).isEqualTo(403);
        assertThat(existingTenantResponse.getContentAsString()).contains(CONTROL_PLANE_AUTH_DENIED_MESSAGE);

        MockHttpServletRequest unknownTenantRequest = request("POST", "/api/v1/companies/404/lifecycle-state");
        MockHttpServletResponse unknownTenantResponse = new MockHttpServletResponse();
        filter.doFilter(unknownTenantRequest, unknownTenantResponse, filterChain);
        assertThat(unknownTenantResponse.getStatus()).isEqualTo(403);
        assertThat(unknownTenantResponse.getContentAsString()).contains(CONTROL_PLANE_AUTH_DENIED_MESSAGE);

        verifyNoInteractions(companyService);
        verify(tenantRuntimeEnforcementService, never())
                .beginRequest(anyString(), anyString(), anyString(), anyString(), anyBoolean());
        verify(filterChain, never()).doFilter(any(), any());
    }

    @Test
    void lifecycleControlRequest_rejectsAnonymousAuthenticationBeforeResolvingTenantFromPath()
            throws ServletException, IOException {
        SecurityContextHolder.getContext().setAuthentication(new AnonymousAuthenticationToken(
                "anonymous",
                "anonymousUser",
                List.of(new SimpleGrantedAuthority("ROLE_ANONYMOUS"))));
        MockHttpServletRequest request = request("POST", "/api/v1/companies/999/lifecycle-state");
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter.doFilter(request, response, filterChain);

        assertThat(response.getStatus()).isEqualTo(403);
        assertThat(response.getContentAsString()).contains(CONTROL_PLANE_AUTH_DENIED_MESSAGE);
        verifyNoInteractions(companyService);
        verify(tenantRuntimeEnforcementService, never())
                .beginRequest(anyString(), anyString(), anyString(), anyString(), anyBoolean());
        verify(filterChain, never()).doFilter(request, response);
    }

    @Test
    void lifecycleControlRequest_rejectsWhenTargetPathDoesNotContainNumericCompanyId()
            throws ServletException, IOException {
        authenticate("root-superadmin@bbp.com", Set.of("ROLE_SUPER_ADMIN"), Set.of("ROOT"));

        MockHttpServletRequest request = request("POST", "/api/v1/companies/not-a-number/lifecycle-state");
        request.setAttribute("jwtClaims", claimsFor("ROOT"));
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter.doFilter(request, response, filterChain);

        assertThat(response.getStatus()).isEqualTo(403);
        assertThat(response.getContentAsString()).contains(CONTROL_PLANE_AUTH_DENIED_MESSAGE);
        verifyNoInteractions(companyService);
        verify(tenantRuntimeEnforcementService, never())
                .beginRequest(anyString(), anyString(), anyString(), anyString(), anyBoolean());
        verify(filterChain, never()).doFilter(request, response);
    }

    @Test
    void lifecycleControlRequest_rejectsWhenResolvedPathTargetCompanyIsMissing()
            throws ServletException, IOException {
        authenticate("root-superadmin@bbp.com", Set.of("ROLE_SUPER_ADMIN"), Set.of("ROOT"));
        when(companyService.resolveCompanyCodeById(404L)).thenReturn(null);

        MockHttpServletRequest request = request("POST", "/api/v1/companies/404/lifecycle-state");
        request.setAttribute("jwtClaims", claimsFor("ROOT"));
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter.doFilter(request, response, filterChain);

        assertThat(response.getStatus()).isEqualTo(403);
        assertThat(response.getContentAsString()).contains(CONTROL_PLANE_AUTH_DENIED_MESSAGE);
        verify(companyService).resolveCompanyCodeById(404L);
        verify(companyService, never()).resolveLifecycleStateByCode(anyString());
        verify(tenantRuntimeEnforcementService, never())
                .beginRequest(anyString(), anyString(), anyString(), anyString(), anyBoolean());
        verify(filterChain, never()).doFilter(request, response);
    }

    @Test
    void extractCompanyIdFromLifecycleControlPath_handlesMalformedAndOverflowValues() {
        assertThat(extractCompanyId(null)).isNull();
        assertThat(extractCompanyId("   ")).isNull();
        assertThat(extractCompanyId("/api/v1/private")).isNull();
        assertThat(extractCompanyId("/api/v1/companies/not-a-number/lifecycle-state")).isNull();
        assertThat(extractCompanyId("/api/v1/companies//lifecycle-state")).isNull();
        assertThat(extractCompanyId("/api/v1/companies/999999999999999999999999/lifecycle-state")).isNull();
        assertThat(extractCompanyId("/api/v1/companies/42")).isEqualTo(42L);
        assertThat(extractCompanyId("/api/v1/companies/42/lifecycle-state")).isEqualTo(42L);
        assertThat(extractCompanyId("/api/v1/companies/42/support/admin-password-reset")).isEqualTo(42L);
    }

    private Long extractCompanyId(String path) {
        return ReflectionTestUtils.invokeMethod(
                filter,
                "extractCompanyIdFromLifecycleControlPath",
                path);
    }

    private void authenticate(String email, Set<String> authorities, Set<String> companyCodes) {
        UserAccount user = new UserAccount(email, "hash", "Operator");
        for (String companyCode : companyCodes) {
            Company company = new Company();
            company.setCode(companyCode);
            user.addCompany(company);
        }
        UserPrincipal principal = new UserPrincipal(user);
        List<org.springframework.security.core.authority.SimpleGrantedAuthority> granted = authorities.stream()
                .map(org.springframework.security.core.authority.SimpleGrantedAuthority::new)
                .toList();
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(principal, "n/a", granted));
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
            boolean admitted,
            int statusCode,
            String message) {
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
                            boolean.class,
                            String.class,
                            String.class,
                            String.class,
                            String.class,
                            String.class);
            constructor.setAccessible(true);
            return constructor.newInstance(
                    admitted,
                    "TENANT-A",
                    "audit-chain",
                    admitted ? counters : null,
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
