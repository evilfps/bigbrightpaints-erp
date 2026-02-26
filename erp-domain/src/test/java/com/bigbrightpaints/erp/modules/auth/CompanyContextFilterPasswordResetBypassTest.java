package com.bigbrightpaints.erp.modules.auth;

import com.bigbrightpaints.erp.core.security.CompanyContextFilter;
import com.bigbrightpaints.erp.modules.company.service.CompanyService;
import com.bigbrightpaints.erp.modules.company.service.TenantRuntimeEnforcementService;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import java.io.IOException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

@ExtendWith(MockitoExtension.class)
class CompanyContextFilterPasswordResetBypassTest {

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

    @Test
    void forgotPasswordEndpoint_isNotBlockedByCompanyHeader() throws ServletException, IOException {
        assertPublicPasswordResetBypass("/api/v1/auth/password/forgot");
    }

    @Test
    void resetPasswordEndpoint_isNotBlockedByCompanyHeader() throws ServletException, IOException {
        assertPublicPasswordResetBypass("/api/v1/auth/password/reset");
    }

    @Test
    void superAdminForgotPasswordEndpoint_isNotBlockedByCompanyHeader() throws ServletException, IOException {
        assertPublicPasswordResetBypass("/api/v1/auth/password/forgot/superadmin");
    }

    @Test
    void forgotPasswordEndpoint_getMethod_isBlockedByCompanyHeader() throws ServletException, IOException {
        assertRequestRejectedByCompanyHeader("GET", "/api/v1/auth/password/forgot");
    }

    @Test
    void lookalikePasswordResetEndpoint_isBlockedByCompanyHeader() throws ServletException, IOException {
        assertRequestRejectedByCompanyHeader("POST", "/api/v1/auth/password/forgot/superadmin/extra");
    }

    private void assertPublicPasswordResetBypass(String path) throws ServletException, IOException {
        MockHttpServletRequest request = new MockHttpServletRequest("POST", path);
        request.setServletPath(path);
        request.setRequestURI(path);
        request.addHeader("X-Company-Code", "FRONTEND-TENANT");
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter.doFilter(request, response, filterChain);

        assertThat(response.getStatus()).isEqualTo(200);
        verify(filterChain).doFilter(request, response);
        verifyNoInteractions(companyService);
        verify(tenantRuntimeEnforcementService).completeRequest(
                any(TenantRuntimeEnforcementService.TenantRequestAdmission.class),
                eq(200));
        verify(tenantRuntimeEnforcementService, never())
                .beginRequest(anyString(), anyString(), anyString(), anyString(), anyBoolean());
    }

    private void assertRequestRejectedByCompanyHeader(String method, String path) throws ServletException, IOException {
        MockHttpServletRequest request = new MockHttpServletRequest(method, path);
        request.setServletPath(path);
        request.setRequestURI(path);
        request.addHeader("X-Company-Code", "FRONTEND-TENANT");
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter.doFilter(request, response, filterChain);

        assertThat(response.getStatus()).isEqualTo(403);
        verify(filterChain, never()).doFilter(request, response);
        verifyNoInteractions(companyService);
        verify(tenantRuntimeEnforcementService).completeRequest(
                any(TenantRuntimeEnforcementService.TenantRequestAdmission.class),
                eq(403));
        verify(tenantRuntimeEnforcementService, never())
                .beginRequest(anyString(), anyString(), anyString(), anyString(), anyBoolean());
    }
}
