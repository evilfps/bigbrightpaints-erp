package com.bigbrightpaints.erp.truthsuite.runtime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.bigbrightpaints.erp.core.security.CompanyContextFilter;
import com.bigbrightpaints.erp.core.security.CompanyContextHolder;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.bigbrightpaints.erp.modules.auth.domain.UserAccount;
import com.bigbrightpaints.erp.modules.auth.domain.UserPrincipal;
import com.bigbrightpaints.erp.modules.company.domain.Company;
import com.bigbrightpaints.erp.modules.company.domain.CompanyLifecycleState;
import com.bigbrightpaints.erp.modules.company.service.CompanyService;
import com.bigbrightpaints.erp.modules.company.service.TenantRuntimeEnforcementService;
import io.jsonwebtoken.Claims;
import java.util.Arrays;
import java.lang.reflect.Constructor;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
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
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.util.ReflectionTestUtils;

@ExtendWith(MockitoExtension.class)
@Tag("critical")
@Tag("concurrency")
@Tag("reconciliation")
class TS_RuntimeTenantRuntimeEnforcementTest {

    @Mock
    private TenantRuntimeEnforcementService tenantRuntimeEnforcementService;

    @Mock
    private CompanyService companyService;

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
    void rejectsMismatchedCompanyHeaders_failClosed() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/v1/portal/dashboard");
        request.addHeader("X-Company-Code", "ACME");
        request.addHeader("X-Company-Id", "BETA");
        MockHttpServletResponse response = new MockHttpServletResponse();
        MockFilterChain chain = new MockFilterChain();

        filter.doFilter(request, response, chain);

        assertThat(response.getStatus()).isEqualTo(403);
        assertThat(chain.getRequest()).isNull();
        verify(tenantRuntimeEnforcementService).completeRequest(any(), eq(403));
        verifyNoInteractions(companyService);
    }

    @Test
    void rejectsMismatchedTokenClaims_failClosed() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/v1/portal/dashboard");
        request.setAttribute("jwtClaims", claims("ACME", "BETA"));
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter.doFilter(request, response, new MockFilterChain());

        assertThat(response.getStatus()).isEqualTo(403);
        verify(tenantRuntimeEnforcementService).completeRequest(any(), eq(403));
        verifyNoInteractions(companyService);
    }

    @Test
    void rejectsAuthenticatedTokenWithoutCompanyClaim_failClosed() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/v1/auth/me");
        request.setAttribute("jwtClaims", claims("   ", null));
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter.doFilter(request, response, new MockFilterChain());

        assertThat(response.getStatus()).isEqualTo(403);
        verify(tenantRuntimeEnforcementService).completeRequest(any(), eq(403));
        verifyNoInteractions(companyService);
    }

    @Test
    void rejectsUnauthenticatedHeaderScopedRequest_failClosed() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/v1/reports/trial-balance");
        request.addHeader("X-Company-Code", "ACME");
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter.doFilter(request, response, new MockFilterChain());

        assertThat(response.getStatus()).isEqualTo(403);
        verify(tenantRuntimeEnforcementService).completeRequest(any(), eq(403));
        verifyNoInteractions(companyService);
    }

    @Test
    void rejectsWhenHeaderCompanyMismatchesAuthenticatedTokenCompany() throws Exception {
        authenticateForCompany("actor@bbp.com", "ACME");
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/v1/admin/settings");
        request.addHeader("X-Company-Code", "BETA");
        request.setAttribute("jwtClaims", claims("ACME", null));
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter.doFilter(request, response, new MockFilterChain());

        assertThat(response.getStatus()).isEqualTo(403);
        verify(tenantRuntimeEnforcementService).completeRequest(any(), eq(403));
        verifyNoInteractions(companyService);
    }

    @Test
    void rejectsMutatingRequestWhenTenantLifecycleIsSuspended_beforeRuntimeAdmission() throws Exception {
        authenticateForCompany("actor@bbp.com", "ACME");
        when(companyService.resolveLifecycleStateByCode("ACME")).thenReturn(CompanyLifecycleState.SUSPENDED);

        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/api/v1/private");
        request.setAttribute("jwtClaims", claims("ACME", null));
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter.doFilter(request, response, new MockFilterChain());

        assertThat(response.getStatus()).isEqualTo(403);
        verify(companyService).resolveLifecycleStateByCode("ACME");
        verify(tenantRuntimeEnforcementService).completeRequest(any(), eq(403));
    }

    @Test
    void allowsReadRequestWhenTenantLifecycleIsSuspended_andBeginsRuntimeAdmission() throws Exception {
        authenticateForCompany("actor@bbp.com", "ACME");
        when(companyService.resolveLifecycleStateByCode("ACME")).thenReturn(CompanyLifecycleState.SUSPENDED);
        TenantRuntimeEnforcementService.TenantRequestAdmission admission = admission(true, "ACME", 200, null);
        when(tenantRuntimeEnforcementService.beginRequest(
                eq("ACME"),
                eq("/api/v1/private"),
                eq("GET"),
                eq("actor@bbp.com"),
                eq(false))).thenReturn(admission);

        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/v1/private");
        request.setAttribute("jwtClaims", claims("ACME", null));
        MockHttpServletResponse response = new MockHttpServletResponse();
        MockFilterChain chain = new MockFilterChain();

        filter.doFilter(request, response, chain);

        assertThat(response.getStatus()).isEqualTo(200);
        assertThat(chain.getRequest()).isNotNull();
        verify(companyService).resolveLifecycleStateByCode("ACME");
        verify(tenantRuntimeEnforcementService)
                .beginRequest("ACME", "/api/v1/private", "GET", "actor@bbp.com", false);
        verify(tenantRuntimeEnforcementService).completeRequest(admission, 200);
    }

    @Test
    void rejectsRequestWhenTenantRuntimeAdmissionIsMissing_failClosed() throws Exception {
        authenticateForCompany("actor@bbp.com", "ACME");
        when(companyService.resolveLifecycleStateByCode("ACME")).thenReturn(CompanyLifecycleState.ACTIVE);
        when(tenantRuntimeEnforcementService.beginRequest(
                "ACME",
                "/api/v1/private",
                "GET",
                "actor@bbp.com",
                false)).thenReturn(null);

        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/v1/private");
        request.setAttribute("jwtClaims", claims("ACME", null));
        MockHttpServletResponse response = new MockHttpServletResponse();
        MockFilterChain chain = new MockFilterChain();

        filter.doFilter(request, response, chain);

        assertThat(response.getStatus()).isEqualTo(403);
        assertThat(response.getContentAsString()).contains("Tenant runtime admission is unavailable");
        assertThat(chain.getRequest()).isNull();
        verify(tenantRuntimeEnforcementService).beginRequest(
                "ACME",
                "/api/v1/private",
                "GET",
                "actor@bbp.com",
                false);
        verify(tenantRuntimeEnforcementService).completeRequest(any(), eq(403));
    }

    @Test
    void allowsSuperAdminLifecycleControlWhenTenantIsNotActive() throws Exception {
        authenticateSuperAdminForCompany("super-admin@bbp.com", "ACME");
        when(companyService.resolveCompanyCodeById(1L)).thenReturn("ACME");
        when(companyService.resolveLifecycleStateByCode("ACME")).thenReturn(CompanyLifecycleState.DEACTIVATED);

        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/api/v1/companies/1/lifecycle-state");
        request.setAttribute("jwtClaims", claims("ACME", null));
        MockHttpServletResponse response = new MockHttpServletResponse();
        MockFilterChain chain = new MockFilterChain();

        filter.doFilter(request, response, chain);

        assertThat(chain.getRequest()).isNotNull();
        verify(companyService).resolveLifecycleStateByCode("ACME");
        verify(tenantRuntimeEnforcementService, never())
                .beginRequest(anyString(), anyString(), anyString(), anyString(), anyBoolean());
        verify(tenantRuntimeEnforcementService).completeRequest(any(), eq(200));
    }

    @Test
    void allowsSuperAdminLifecycleControlWithoutTenantMembershipWhenTenantIsNotActive() throws Exception {
        authenticateSuperAdminWithoutCompany("super-admin@bbp.com");
        when(companyService.resolveCompanyCodeById(1L)).thenReturn("ACME");
        when(companyService.resolveLifecycleStateByCode("ACME")).thenReturn(CompanyLifecycleState.DEACTIVATED);

        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/api/v1/companies/1/lifecycle-state");
        request.setAttribute("jwtClaims", claims("ACME", null));
        MockHttpServletResponse response = new MockHttpServletResponse();
        MockFilterChain chain = new MockFilterChain();

        filter.doFilter(request, response, chain);

        assertThat(chain.getRequest()).isNotNull();
        verify(companyService).resolveLifecycleStateByCode("ACME");
        verify(tenantRuntimeEnforcementService, never())
                .beginRequest(anyString(), anyString(), anyString(), anyString(), anyBoolean());
        verify(tenantRuntimeEnforcementService).completeRequest(any(), eq(200));
    }

    @Test
    void allowsSuperAdminLifecycleControlWhenTenantIsNotActive_withContextPath() throws Exception {
        authenticateSuperAdminForCompany("super-admin@bbp.com", "ACME");
        when(companyService.resolveCompanyCodeById(1L)).thenReturn("ACME");
        when(companyService.resolveLifecycleStateByCode("ACME")).thenReturn(CompanyLifecycleState.DEACTIVATED);

        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/erp/api/v1/companies/1/lifecycle-state");
        request.setContextPath("/erp");
        request.setServletPath("/api/v1/companies/1/lifecycle-state");
        request.setAttribute("jwtClaims", claims("ACME", null));
        MockHttpServletResponse response = new MockHttpServletResponse();
        MockFilterChain chain = new MockFilterChain();

        filter.doFilter(request, response, chain);

        assertThat(chain.getRequest()).isNotNull();
        verify(companyService).resolveLifecycleStateByCode("ACME");
        verify(tenantRuntimeEnforcementService, never())
                .beginRequest(anyString(), anyString(), anyString(), anyString(), anyBoolean());
        verify(tenantRuntimeEnforcementService).completeRequest(any(), eq(200));
    }

    @Test
    void allowsSuperAdminLifecycleControlWhenTenantIsNotActive_withContextPathAndEmptyServletPath() throws Exception {
        authenticateSuperAdminForCompany("super-admin@bbp.com", "ACME");
        when(companyService.resolveCompanyCodeById(1L)).thenReturn("ACME");
        when(companyService.resolveLifecycleStateByCode("ACME")).thenReturn(CompanyLifecycleState.DEACTIVATED);

        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/erp/api/v1/companies/1/lifecycle-state");
        request.setContextPath("/erp");
        request.setServletPath("");
        request.setAttribute("jwtClaims", claims("ACME", null));
        MockHttpServletResponse response = new MockHttpServletResponse();
        MockFilterChain chain = new MockFilterChain();

        filter.doFilter(request, response, chain);

        assertThat(chain.getRequest()).isNotNull();
        verify(companyService).resolveLifecycleStateByCode("ACME");
        verify(tenantRuntimeEnforcementService, never())
                .beginRequest(anyString(), anyString(), anyString(), anyString(), anyBoolean());
        verify(tenantRuntimeEnforcementService).completeRequest(any(), eq(200));
    }

    @Test
    void allowsSuperAdminTenantMetricsReadWhenTenantIsNotActive_withContextPathAndEmptyServletPath() throws Exception {
        authenticateSuperAdminForCompany("super-admin@bbp.com", "ACME");
        when(companyService.resolveCompanyCodeById(1L)).thenReturn("ACME");
        when(companyService.resolveLifecycleStateByCode("ACME")).thenReturn(CompanyLifecycleState.DEACTIVATED);

        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/erp/api/v1/companies/1/tenant-metrics");
        request.setContextPath("/erp");
        request.setServletPath("");
        request.setAttribute("jwtClaims", claims("ACME", null));
        MockHttpServletResponse response = new MockHttpServletResponse();
        MockFilterChain chain = new MockFilterChain();

        filter.doFilter(request, response, chain);

        assertThat(chain.getRequest()).isNotNull();
        verify(companyService).resolveLifecycleStateByCode("ACME");
        verify(tenantRuntimeEnforcementService, never())
                .beginRequest(anyString(), anyString(), anyString(), anyString(), anyBoolean());
        verify(tenantRuntimeEnforcementService).completeRequest(any(), eq(200));
    }

    @Test
    void contextPathRequest_withEmptyServletPath_usesContextStrippedPath_forRuntimeAdmission() throws Exception {
        authenticateForCompanyWithAuthorities("actor@bbp.com", "ACME", "ROLE_SUPER_ADMIN");
        when(companyService.resolveCompanyCodeById(42L)).thenReturn("ACME");
        when(companyService.resolveLifecycleStateByCode("ACME")).thenReturn(CompanyLifecycleState.ACTIVE);
        TenantRuntimeEnforcementService.TenantRequestAdmission admittedAdmission =
                admission(true, "ACME", 200, null);
        when(tenantRuntimeEnforcementService.beginRequest(
                "ACME",
                "/api/v1/companies/42/tenant-runtime/policy",
                "PUT",
                "actor@bbp.com",
                true))
                .thenReturn(admittedAdmission);

        MockHttpServletRequest request = new MockHttpServletRequest("PUT", "/erp/api/v1/companies/42/tenant-runtime/policy");
        request.setContextPath("/erp");
        request.setServletPath("");
        request.setAttribute("jwtClaims", claims("ACME", null));
        MockHttpServletResponse response = new MockHttpServletResponse();
        MockFilterChain chain = new MockFilterChain();

        filter.doFilter(request, response, chain);

        assertThat(chain.getRequest()).isNotNull();
        verify(tenantRuntimeEnforcementService).beginRequest(
                "ACME",
                "/api/v1/companies/42/tenant-runtime/policy",
                "PUT",
                "actor@bbp.com",
                true);
        verify(tenantRuntimeEnforcementService).completeRequest(eq(admittedAdmission), eq(200));
    }

    @Test
    void contextPathRequest_usesServletPath_forRuntimeAdmission() throws Exception {
        authenticateForCompanyWithAuthorities("actor@bbp.com", "ACME", "ROLE_SUPER_ADMIN");
        when(companyService.resolveCompanyCodeById(42L)).thenReturn("ACME");
        when(companyService.resolveLifecycleStateByCode("ACME")).thenReturn(CompanyLifecycleState.ACTIVE);
        TenantRuntimeEnforcementService.TenantRequestAdmission admittedAdmission =
                admission(true, "ACME", 200, null);
        when(tenantRuntimeEnforcementService.beginRequest(
                "ACME",
                "/api/v1/companies/42/tenant-runtime/policy",
                "PUT",
                "actor@bbp.com",
                true))
                .thenReturn(admittedAdmission);

        MockHttpServletRequest request = new MockHttpServletRequest("PUT", "/erp/api/v1/companies/42/tenant-runtime/policy");
        request.setContextPath("/erp");
        request.setServletPath("/api/v1/companies/42/tenant-runtime/policy");
        request.setAttribute("jwtClaims", claims("ACME", null));
        MockHttpServletResponse response = new MockHttpServletResponse();
        MockFilterChain chain = new MockFilterChain();

        filter.doFilter(request, response, chain);

        assertThat(chain.getRequest()).isNotNull();
        verify(tenantRuntimeEnforcementService).beginRequest(
                "ACME",
                "/api/v1/companies/42/tenant-runtime/policy",
                "PUT",
                "actor@bbp.com",
                true);
        verify(tenantRuntimeEnforcementService).completeRequest(eq(admittedAdmission), eq(200));
    }

    @Test
    void runtimeAdmissionDenied_writesEscapedJsonPayload_andCompletes() throws Exception {
        authenticateForCompany("actor@bbp.com", "ACME");
        when(companyService.resolveLifecycleStateByCode("ACME")).thenReturn(CompanyLifecycleState.ACTIVE);

        TenantRuntimeEnforcementService.TenantRequestAdmission deniedAdmission =
                admission(false, "ACME", 429, "quota \"hit\" \\\\ retry");
        when(tenantRuntimeEnforcementService.beginRequest("ACME", "/api/v1/private", "POST", "actor@bbp.com", false))
                .thenReturn(deniedAdmission);

        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/api/v1/private");
        request.setAttribute("jwtClaims", claims("ACME", null));
        MockHttpServletResponse response = new MockHttpServletResponse();
        MockFilterChain chain = new MockFilterChain();

        filter.doFilter(request, response, chain);

        assertThat(response.getStatus()).isEqualTo(429);
        assertThat(response.getContentType()).isEqualTo("application/json;charset=UTF-8");
        Map<String, Object> payload = new ObjectMapper().findAndRegisterModules()
                .readValue(response.getContentAsString(), new TypeReference<>() {});
        assertThat(payload).containsEntry("success", false);
        assertThat(payload).containsEntry("message", "quota \"hit\" \\\\ retry");
        assertThat(chain.getRequest()).isNull();
        verify(tenantRuntimeEnforcementService).completeRequest(eq(deniedAdmission), eq(429));
    }

    @Test
    void admittedRequest_setsAndClearsCompanyContext_andCompletes() throws Exception {
        authenticateForCompany("actor@bbp.com", "ACME");
        when(companyService.resolveLifecycleStateByCode("ACME")).thenReturn(CompanyLifecycleState.ACTIVE);

        TenantRuntimeEnforcementService.TenantRequestAdmission admittedAdmission =
                admission(true, "ACME", 200, null);
        when(tenantRuntimeEnforcementService.beginRequest("ACME", "/api/v1/private", "GET", "actor@bbp.com", false))
                .thenReturn(admittedAdmission);

        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/v1/private");
        request.setAttribute("jwtClaims", claims("ACME", null));
        MockHttpServletResponse response = new MockHttpServletResponse();
        AtomicReference<String> companyInChain = new AtomicReference<>();
        AtomicBoolean chainCalled = new AtomicBoolean(false);

        filter.doFilter(request, response, (req, res) -> {
            chainCalled.set(true);
            companyInChain.set(CompanyContextHolder.getCompanyCode());
        });

        assertThat(chainCalled.get()).isTrue();
        assertThat(companyInChain.get()).isEqualTo("ACME");
        assertThat(CompanyContextHolder.getCompanyCode()).isNull();
        verify(tenantRuntimeEnforcementService).completeRequest(eq(admittedAdmission), eq(200));
    }

    @Test
    void lifecycleControlBypass_allowsSuperAdminOutsideTenantMembership_forInactiveTenant() throws Exception {
        authenticateForCompanyWithAuthorities("super-admin@bbp.com", "ROOT", "ROLE_SUPER_ADMIN");
        when(companyService.resolveCompanyCodeById(42L)).thenReturn("ACME");
        when(companyService.resolveLifecycleStateByCode("ACME")).thenReturn(CompanyLifecycleState.DEACTIVATED);
        TenantRuntimeEnforcementService.TenantRequestAdmission admittedAdmission = admission(true, "ACME", 200, null);
        when(tenantRuntimeEnforcementService.beginRequest(
                "ACME",
                "/api/v1/companies/42/tenant-runtime/policy",
                "PUT",
                "super-admin@bbp.com",
                true)).thenReturn(admittedAdmission);

        MockHttpServletRequest request = new MockHttpServletRequest("PUT", "/api/v1/companies/42/tenant-runtime/policy");
        request.setAttribute("jwtClaims", claims("ACME", null));
        MockHttpServletResponse response = new MockHttpServletResponse();
        AtomicBoolean chainCalled = new AtomicBoolean(false);

        filter.doFilter(request, response, (req, res) -> chainCalled.set(true));

        assertThat(response.getStatus()).isEqualTo(200);
        assertThat(chainCalled.get()).isTrue();
        verify(tenantRuntimeEnforcementService).beginRequest(
                "ACME",
                "/api/v1/companies/42/tenant-runtime/policy",
                "PUT",
                "super-admin@bbp.com",
                true);
        verify(tenantRuntimeEnforcementService).completeRequest(eq(admittedAdmission), eq(200));
    }

    @Test
    void lifecycleControlBypass_deniesWithoutSuperAdminAuthority() throws Exception {
        authenticateForCompanyWithAuthorities("admin@bbp.com", "ROOT", "ROLE_ADMIN");
        when(companyService.resolveCompanyCodeById(42L)).thenReturn("ACME");

        MockHttpServletRequest request = new MockHttpServletRequest("PUT", "/api/v1/companies/42/tenant-runtime/policy");
        request.setAttribute("jwtClaims", claims("ACME", null));
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter.doFilter(request, response, new MockFilterChain());

        assertThat(response.getStatus()).isEqualTo(403);
        verify(tenantRuntimeEnforcementService).completeRequest(any(), eq(403));
    }

    @Test
    void lifecycleControlBypass_deniesWhenCompanyIsMissing() throws Exception {
        authenticateForCompanyWithAuthorities("super-admin@bbp.com", "ROOT", "ROLE_SUPER_ADMIN");
        when(companyService.resolveCompanyCodeById(42L)).thenReturn(null);

        MockHttpServletRequest request = new MockHttpServletRequest("PUT", "/api/v1/companies/42/tenant-runtime/policy");
        request.setAttribute("jwtClaims", claims("ACME", null));
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter.doFilter(request, response, new MockFilterChain());

        assertThat(response.getStatus()).isEqualTo(403);
        verify(tenantRuntimeEnforcementService).completeRequest(any(), eq(403));
    }

    @Test
    void actuatorPaths_bypassFilterAndDoNotInvokeTenantEnforcement() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/actuator/health");
        request.setServletPath("/actuator/health");
        MockHttpServletResponse response = new MockHttpServletResponse();
        MockFilterChain chain = new MockFilterChain();

        filter.doFilter(request, response, chain);

        assertThat(chain.getRequest()).isNotNull();
        verifyNoInteractions(tenantRuntimeEnforcementService, companyService);
    }

    @Test
    void privatePolicyAuthorityAndLifecycleHelpers_cover_guard_branches() {
        SecurityContextHolder.clearContext();
        assertThat((Boolean) ReflectionTestUtils.invokeMethod(filter, "hasSuperAdminAuthority")).isFalse();
        assertThat((Boolean) ReflectionTestUtils.invokeMethod(
                filter,
                "hasTenantRuntimePolicyControlAuthority",
                "/api/v1/admin/tenant-runtime/policy",
                "PUT")).isFalse();
        SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken("anonymous", "n/a"));
        assertThat((Boolean) ReflectionTestUtils.invokeMethod(filter, "hasSuperAdminAuthority")).isFalse();
        assertThat((Boolean) ReflectionTestUtils.invokeMethod(
                filter,
                "hasTenantRuntimePolicyControlAuthority",
                "/api/v1/admin/tenant-runtime/policy",
                "PUT")).isFalse();
        assertThat((Boolean) ReflectionTestUtils.invokeMethod(
                filter,
                "hasAuthority",
                SecurityContextHolder.getContext().getAuthentication(),
                "ROLE_SUPER_ADMIN")).isFalse();

        authenticateForCompanyWithAuthorities("super-admin@bbp.com", "ROOT", "ROLE_SUPER_ADMIN");
        assertThat((Boolean) ReflectionTestUtils.invokeMethod(
                filter,
                "hasTenantRuntimePolicyControlAuthority",
                "/api/v1/admin/tenant-runtime/policy/",
                "PUT")).isFalse();
        assertThat((Boolean) ReflectionTestUtils.invokeMethod(
                filter,
                "hasTenantRuntimePolicyControlAuthority",
                "/api/v1/companies/42/tenant-runtime/policy",
                "PUT")).isTrue();
        assertThat((Boolean) ReflectionTestUtils.invokeMethod(
                filter,
                "hasTenantRuntimePolicyControlAuthority",
                "/api/v1/companies/42/x/tenant-runtime/policy",
                "PUT")).isFalse();
        assertThat((Boolean) ReflectionTestUtils.invokeMethod(
                filter,
                "hasTenantRuntimePolicyControlAuthority",
                "/api/v1/companies//tenant-runtime/policy",
                "PUT")).isFalse();
        assertThat((Boolean) ReflectionTestUtils.invokeMethod(
                filter,
                "hasTenantRuntimePolicyControlAuthority",
                "/",
                "PUT")).isFalse();
        assertThat((Boolean) ReflectionTestUtils.invokeMethod(
                filter,
                "hasTenantRuntimePolicyControlAuthority",
                "/api/v1/admin/tenant-runtime/policy",
                "GET")).isFalse();
        assertThat((Boolean) ReflectionTestUtils.invokeMethod(
                filter,
                "hasTenantRuntimePolicyControlAuthority",
                "   ",
                "PUT")).isFalse();
        assertThat((Boolean) ReflectionTestUtils.invokeMethod(
                filter,
                "hasAuthority",
                SecurityContextHolder.getContext().getAuthentication(),
                " ")).isFalse();

        assertThat((Boolean) ReflectionTestUtils.invokeMethod(filter, "isLifecycleControlRequest", null, "PUT"))
                .isFalse();
        assertThat((Boolean) ReflectionTestUtils.invokeMethod(filter, "isLifecycleControlRequest", "/api/v1/private", "PUT"))
                .isFalse();
        assertThat((Boolean) ReflectionTestUtils.invokeMethod(
                filter,
                "isLifecycleControlRequest",
                "/api/v1/companies/7/lifecycle-state",
                "POST")).isTrue();
        assertThat((Boolean) ReflectionTestUtils.invokeMethod(
                filter,
                "isLifecycleControlRequest",
                "/api/v1/companies/7/tenant-metrics",
                "GET")).isTrue();
        assertThat((Boolean) ReflectionTestUtils.invokeMethod(
                filter,
                "isLifecycleControlRequest",
                "/api/v1/companies/7/tenant-runtime/policy",
                "PUT")).isTrue();
        assertThat((Boolean) ReflectionTestUtils.invokeMethod(
                filter,
                "isLifecycleControlRequest",
                "/api/v1/companies/7",
                "PUT")).isTrue();
        assertThat((Boolean) ReflectionTestUtils.invokeMethod(
                filter,
                "isLifecycleControlRequest",
                "/api/v1/companies/7/support/admin-password-reset",
                "POST")).isTrue();
        assertThat((Boolean) ReflectionTestUtils.invokeMethod(
                filter,
                "isLifecycleControlRequest",
                "/api/v1/companies/7/x/lifecycle-state",
                "POST")).isFalse();
        assertThat((Boolean) ReflectionTestUtils.invokeMethod(
                filter,
                "isLifecycleControlRequest",
                "/api/v1/companies/7/x/support/admin-password-reset",
                "POST")).isFalse();
        assertThat((Boolean) ReflectionTestUtils.invokeMethod(
                filter,
                "isLifecycleControlRequest",
                "/api/v1/companies/7/tenant-runtime/policy",
                "PATCH")).isFalse();
    }

    @Test
    void resolveApplicationPathAndShouldNotFilter_cover_edge_cases() {
        assertThat((String) ReflectionTestUtils.invokeMethod(filter, "resolveApplicationPath", (Object) null)).isNull();

        MockHttpServletRequest servletAndInfo = new MockHttpServletRequest();
        servletAndInfo.setServletPath("/api/v1/companies");
        servletAndInfo.setPathInfo("42/tenant-runtime/policy");
        assertThat((String) ReflectionTestUtils.invokeMethod(filter, "resolveApplicationPath", servletAndInfo))
                .isEqualTo("/api/v1/companies/42/tenant-runtime/policy");

        MockHttpServletRequest blankUri = new MockHttpServletRequest();
        blankUri.setRequestURI("   ");
        assertThat((String) ReflectionTestUtils.invokeMethod(filter, "resolveApplicationPath", blankUri)).isNull();

        MockHttpServletRequest rootContext = new MockHttpServletRequest();
        rootContext.setRequestURI("/bbp");
        rootContext.setContextPath("/bbp");
        assertThat((String) ReflectionTestUtils.invokeMethod(filter, "resolveApplicationPath", rootContext))
                .isEqualTo("/");

        MockHttpServletRequest contextPrefix = new MockHttpServletRequest();
        contextPrefix.setRequestURI("/bbp/api/v1/private");
        contextPrefix.setContextPath("/bbp");
        assertThat((String) ReflectionTestUtils.invokeMethod(filter, "resolveApplicationPath", contextPrefix))
                .isEqualTo("/api/v1/private");
        MockHttpServletRequest contextNoPrefix = new MockHttpServletRequest();
        contextNoPrefix.setRequestURI("/api/v1/private");
        contextNoPrefix.setContextPath("/bbp");
        assertThat((String) ReflectionTestUtils.invokeMethod(filter, "resolveApplicationPath", contextNoPrefix))
                .isEqualTo("/api/v1/private");

        MockHttpServletRequest shouldNotFilterBlank = new MockHttpServletRequest();
        shouldNotFilterBlank.setRequestURI("   ");
        assertThat((Boolean) ReflectionTestUtils.invokeMethod(filter, "shouldNotFilter", shouldNotFilterBlank)).isFalse();
    }

    private Claims claims(String companyCode, String legacyCompanyId) {
        Claims claims = org.mockito.Mockito.mock(Claims.class);
        when(claims.get("companyCode", String.class)).thenReturn(companyCode);
        when(claims.get("cid", String.class)).thenReturn(legacyCompanyId);
        return claims;
    }

    private void authenticateForCompany(String email, String companyCode) {
        authenticateForCompanyWithAuthorities(email, companyCode);
    }

    private void authenticateForCompanyWithAuthorities(String email, String companyCode, String... authorities) {
        Company company = new Company();
        company.setCode(companyCode);

        UserAccount user = new UserAccount(email, "hash", "Actor");
        user.addCompany(company);

        UserPrincipal principal = new UserPrincipal(user);
        java.util.Collection<? extends org.springframework.security.core.GrantedAuthority> grantedAuthorities;
        if (authorities == null || authorities.length == 0) {
            grantedAuthorities = principal.getAuthorities();
        } else {
            grantedAuthorities = java.util.Arrays.stream(authorities)
                    .map(authority -> (org.springframework.security.core.GrantedAuthority) () -> authority)
                    .toList();
        }
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(principal, "n/a", grantedAuthorities));
    }

    private void authenticateSuperAdminForCompany(String email, String companyCode) {
        Company company = new Company();
        company.setCode(companyCode);

        UserAccount user = new UserAccount(email, "hash", "Super Admin");
        user.addCompany(company);

        UserPrincipal principal = new UserPrincipal(user);
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(
                        principal,
                        "n/a",
                        java.util.List.of(new SimpleGrantedAuthority("ROLE_SUPER_ADMIN"))));
    }

    private void authenticateSuperAdminWithoutCompany(String email) {
        UserAccount user = new UserAccount(email, "hash", "Super Admin");
        UserPrincipal principal = new UserPrincipal(user);
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(
                        principal,
                        "n/a",
                        java.util.List.of(new SimpleGrantedAuthority("ROLE_SUPER_ADMIN"))));
    }

    @SuppressWarnings("unchecked")
    private TenantRuntimeEnforcementService.TenantRequestAdmission admission(boolean admitted,
                                                                             String companyCode,
                                                                             int statusCode,
                                                                             String message) {
        try {
            Constructor<TenantRuntimeEnforcementService.TenantRequestAdmission> ctor =
                    (Constructor<TenantRuntimeEnforcementService.TenantRequestAdmission>)
                            Arrays.stream(TenantRuntimeEnforcementService.TenantRequestAdmission.class
                                            .getDeclaredConstructors())
                                    .filter(candidate -> candidate.getParameterCount() == 6
                                            || candidate.getParameterCount() == 7
                                            || candidate.getParameterCount() == 12)
                                    .findFirst()
                                    .orElseThrow();
            ctor.setAccessible(true);
            if (ctor.getParameterCount() == 12) {
                return ctor.newInstance(admitted,
                        companyCode,
                        "chain-id",
                        null,
                        statusCode,
                        message,
                        false,
                        null,
                        null,
                        null,
                        null,
                        null);
            }
            if (ctor.getParameterCount() == 7) {
                return ctor.newInstance(admitted, companyCode, "chain-id", null, statusCode, message, false);
            }
            return ctor.newInstance(admitted, companyCode, "chain-id", null, statusCode, message);
        } catch (ReflectiveOperationException ex) {
            throw new IllegalStateException("Unable to construct tenant request admission for test", ex);
        }
    }
}
