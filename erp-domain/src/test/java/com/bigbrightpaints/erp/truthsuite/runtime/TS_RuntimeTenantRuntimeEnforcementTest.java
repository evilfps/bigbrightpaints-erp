package com.bigbrightpaints.erp.truthsuite.runtime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.bigbrightpaints.erp.core.security.CompanyContextFilter;
import com.bigbrightpaints.erp.core.security.CompanyContextHolder;
import com.bigbrightpaints.erp.modules.auth.domain.UserAccount;
import com.bigbrightpaints.erp.modules.auth.domain.UserPrincipal;
import com.bigbrightpaints.erp.modules.company.domain.Company;
import com.bigbrightpaints.erp.modules.company.domain.CompanyLifecycleState;
import com.bigbrightpaints.erp.modules.company.service.CompanyService;
import com.bigbrightpaints.erp.modules.company.service.TenantRuntimeEnforcementService;
import io.jsonwebtoken.Claims;
import java.lang.reflect.Constructor;
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
import org.springframework.security.core.context.SecurityContextHolder;

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
        filter = new CompanyContextFilter(tenantRuntimeEnforcementService, companyService);
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
    void rejectsWhenTenantLifecycleIsNotActive_beforeRuntimeAdmission() throws Exception {
        authenticateForCompany("actor@bbp.com", "ACME");
        when(companyService.resolveLifecycleStateByCode("ACME")).thenReturn(CompanyLifecycleState.HOLD);

        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/api/v1/private");
        request.setAttribute("jwtClaims", claims("ACME", null));
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter.doFilter(request, response, new MockFilterChain());

        assertThat(response.getStatus()).isEqualTo(403);
        verify(companyService).resolveLifecycleStateByCode("ACME");
        verify(tenantRuntimeEnforcementService).completeRequest(any(), eq(403));
    }

    @Test
    void runtimeAdmissionDenied_writesEscapedJsonPayload_andCompletes() throws Exception {
        authenticateForCompany("actor@bbp.com", "ACME");
        when(companyService.resolveLifecycleStateByCode("ACME")).thenReturn(CompanyLifecycleState.ACTIVE);

        TenantRuntimeEnforcementService.TenantRequestAdmission deniedAdmission =
                admission(false, "ACME", 429, "quota \"hit\" \\\\ retry");
        when(tenantRuntimeEnforcementService.beginRequest("ACME", "/api/v1/private", "POST", "actor@bbp.com"))
                .thenReturn(deniedAdmission);

        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/api/v1/private");
        request.setAttribute("jwtClaims", claims("ACME", null));
        MockHttpServletResponse response = new MockHttpServletResponse();
        MockFilterChain chain = new MockFilterChain();

        filter.doFilter(request, response, chain);

        assertThat(response.getStatus()).isEqualTo(429);
        assertThat(response.getContentType()).isEqualTo("application/json;charset=UTF-8");
        assertThat(response.getContentAsString()).isEqualTo("{\"message\":\"quota \\\"hit\\\" \\\\\\\\ retry\"}");
        assertThat(chain.getRequest()).isNull();
        verify(tenantRuntimeEnforcementService).completeRequest(eq(deniedAdmission), eq(429));
    }

    @Test
    void admittedRequest_setsAndClearsCompanyContext_andCompletes() throws Exception {
        authenticateForCompany("actor@bbp.com", "ACME");
        when(companyService.resolveLifecycleStateByCode("ACME")).thenReturn(CompanyLifecycleState.ACTIVE);

        TenantRuntimeEnforcementService.TenantRequestAdmission admittedAdmission =
                admission(true, "ACME", 200, null);
        when(tenantRuntimeEnforcementService.beginRequest("ACME", "/api/v1/private", "GET", "actor@bbp.com"))
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
    void actuatorPaths_bypassFilterAndDoNotInvokeTenantEnforcement() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/actuator/health");
        request.setServletPath("/actuator/health");
        MockHttpServletResponse response = new MockHttpServletResponse();
        MockFilterChain chain = new MockFilterChain();

        filter.doFilter(request, response, chain);

        assertThat(chain.getRequest()).isNotNull();
        verifyNoInteractions(tenantRuntimeEnforcementService, companyService);
    }

    private Claims claims(String companyCode, String legacyCompanyId) {
        Claims claims = org.mockito.Mockito.mock(Claims.class);
        when(claims.get("companyCode", String.class)).thenReturn(companyCode);
        when(claims.get("cid", String.class)).thenReturn(legacyCompanyId);
        return claims;
    }

    private void authenticateForCompany(String email, String companyCode) {
        Company company = new Company();
        company.setCode(companyCode);

        UserAccount user = new UserAccount(email, "hash", "Actor");
        user.addCompany(company);

        UserPrincipal principal = new UserPrincipal(user);
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(principal, "n/a", principal.getAuthorities()));
    }

    @SuppressWarnings("unchecked")
    private TenantRuntimeEnforcementService.TenantRequestAdmission admission(boolean admitted,
                                                                             String companyCode,
                                                                             int statusCode,
                                                                             String message) {
        try {
            Constructor<TenantRuntimeEnforcementService.TenantRequestAdmission> ctor =
                    (Constructor<TenantRuntimeEnforcementService.TenantRequestAdmission>)
                            TenantRuntimeEnforcementService.TenantRequestAdmission.class.getDeclaredConstructors()[0];
            ctor.setAccessible(true);
            return ctor.newInstance(admitted, companyCode, "chain-id", null, statusCode, message);
        } catch (ReflectiveOperationException ex) {
            throw new IllegalStateException("Unable to construct tenant request admission for test", ex);
        }
    }
}
