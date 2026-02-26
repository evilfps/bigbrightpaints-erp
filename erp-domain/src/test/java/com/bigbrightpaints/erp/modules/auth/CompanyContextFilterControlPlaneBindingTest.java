package com.bigbrightpaints.erp.modules.auth;

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
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class CompanyContextFilterControlPlaneBindingTest {

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
    void lifecycleControlRequest_bindsSuperAdminFlowToPathTargetCompany() throws ServletException, IOException {
        authenticate("root-superadmin@bbp.com", Set.of("ROLE_SUPER_ADMIN"), Set.of("ROOT"));
        when(companyService.resolveCompanyCodeById(42L)).thenReturn("TENANT-A");
        when(companyService.resolveLifecycleStateByCode("TENANT-A")).thenReturn(CompanyLifecycleState.ACTIVE);
        when(tenantRuntimeEnforcementService.beginRequest(
                eq("TENANT-A"),
                eq("/api/v1/companies/42/lifecycle-state"),
                eq("POST"),
                eq("root-superadmin@bbp.com"),
                eq(false)))
                .thenReturn(admission(true, 200, null));

        MockHttpServletRequest request = request("POST", "/api/v1/companies/42/lifecycle-state");
        request.setAttribute("jwtClaims", claimsFor("ROOT"));
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter.doFilter(request, response, filterChain);

        assertThat(response.getStatus()).isEqualTo(200);
        verify(companyService).resolveCompanyCodeById(42L);
        verify(companyService).resolveLifecycleStateByCode("TENANT-A");
        verify(tenantRuntimeEnforcementService).beginRequest(
                eq("TENANT-A"),
                eq("/api/v1/companies/42/lifecycle-state"),
                eq("POST"),
                eq("root-superadmin@bbp.com"),
                eq(false));
        verify(filterChain).doFilter(request, response);
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
        assertThat(response.getErrorMessage()).isEqualTo("Company path does not match authenticated company context");
        verify(companyService).resolveCompanyCodeById(42L);
        verify(companyService, never()).resolveLifecycleStateByCode(anyString());
        verify(tenantRuntimeEnforcementService, never())
                .beginRequest(anyString(), anyString(), anyString(), anyString(), anyBoolean());
        verify(filterChain, never()).doFilter(request, response);
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
                            boolean.class);
            constructor.setAccessible(true);
            return constructor.newInstance(
                    admitted,
                    "TENANT-A",
                    "audit-chain",
                    admitted ? counters : null,
                    statusCode,
                    message,
                    false);
        } catch (ReflectiveOperationException ex) {
            throw new IllegalStateException("Unable to construct tenant admission handle", ex);
        }
    }
}
