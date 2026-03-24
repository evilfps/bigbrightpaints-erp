package com.bigbrightpaints.erp.truthsuite.runtime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.bigbrightpaints.erp.core.config.EmailProperties;
import com.bigbrightpaints.erp.core.security.CompanyContextFilter;
import com.bigbrightpaints.erp.core.security.CompanyContextHolder;
import com.bigbrightpaints.erp.core.security.TokenBlacklistService;
import com.bigbrightpaints.erp.core.notification.EmailService;
import com.bigbrightpaints.erp.modules.auth.controller.AuthController;
import com.bigbrightpaints.erp.modules.auth.domain.PasswordResetToken;
import com.bigbrightpaints.erp.modules.auth.domain.PasswordResetTokenRepository;
import com.bigbrightpaints.erp.modules.auth.domain.UserAccount;
import com.bigbrightpaints.erp.modules.auth.domain.UserAccountRepository;
import com.bigbrightpaints.erp.modules.auth.domain.UserPrincipal;
import com.bigbrightpaints.erp.modules.auth.service.AuthService;
import com.bigbrightpaints.erp.modules.auth.service.PasswordResetService;
import com.bigbrightpaints.erp.modules.auth.service.PasswordService;
import com.bigbrightpaints.erp.modules.auth.service.RefreshTokenService;
import com.bigbrightpaints.erp.modules.auth.web.ForgotPasswordRequest;
import com.bigbrightpaints.erp.modules.company.domain.Company;
import com.bigbrightpaints.erp.modules.company.domain.CompanyLifecycleState;
import com.bigbrightpaints.erp.modules.company.service.CompanyService;
import com.bigbrightpaints.erp.modules.company.service.TenantRuntimeEnforcementService;
import com.bigbrightpaints.erp.modules.rbac.domain.Role;
import com.bigbrightpaints.erp.shared.dto.ApiResponse;
import io.jsonwebtoken.Claims;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import java.io.IOException;
import java.lang.reflect.Constructor;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.batch.support.transaction.ResourcelessTransactionManager;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.http.ResponseEntity;
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

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper().findAndRegisterModules();

    @Mock
    private TenantRuntimeEnforcementService tenantRuntimeEnforcementService;

    @Mock
    private CompanyService companyService;

    @Mock
    private FilterChain filterChain;

    private CompanyContextFilter filter;

    @BeforeEach
    void setUp() {
        filter = new CompanyContextFilter(tenantRuntimeEnforcementService, companyService, OBJECT_MAPPER);
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
        assertThat(response.getContentAsString()).contains("Access denied to company: ACME");
        verify(tenantRuntimeEnforcementService, never()).beginRequest(anyString(), anyString(), anyString(), anyString(), anyBoolean());
        verify(filterChain, never()).doFilter(request, response);
    }

    @Test
    void doFilter_allowsSuperAdminLifecycleControlBypassForNonActiveTenant() throws ServletException, IOException {
        authenticate("ops@bbp.com", Set.of("ROLE_SUPER_ADMIN"), Set.of());
        when(companyService.resolveCompanyCodeById(42L)).thenReturn("ACME");
        when(companyService.resolveLifecycleStateByCode("ACME")).thenReturn(CompanyLifecycleState.DEACTIVATED);

        MockHttpServletRequest request = request("POST", "/api/v1/companies/42/lifecycle-state");
        request.setAttribute("jwtClaims", claimsFor("ACME"));
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter.doFilter(request, response, filterChain);

        assertThat(response.getStatus()).isEqualTo(200);
        verify(tenantRuntimeEnforcementService, never())
                .beginRequest(anyString(), anyString(), anyString(), anyString(), anyBoolean());
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
                eq(false)))
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
        verify(filterChain, never()).doFilter(request, response);
    }

    @Test
    void doFilter_rejectsNonActiveTenantForNonLifecycleRequests() throws ServletException, IOException {
        authenticate("admin@bbp.com", Set.of("ROLE_ADMIN"), Set.of("ACME"));
        when(companyService.resolveLifecycleStateByCode("ACME")).thenReturn(CompanyLifecycleState.DEACTIVATED);

        MockHttpServletRequest request = request("GET", "/api/v1/private");
        request.setAttribute("jwtClaims", claimsFor("ACME"));
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter.doFilter(request, response, filterChain);

        assertThat(response.getStatus()).isEqualTo(403);
        assertThat(response.getContentAsString()).contains("Tenant is deactivated");
        verify(tenantRuntimeEnforcementService, never()).beginRequest(anyString(), anyString(), anyString(), anyString(), anyBoolean());
    }

    @Test
    void doFilter_rejectsLifecycleControlMutationForNonSuperAdminOnBlockedTenant() throws ServletException, IOException {
        authenticate("admin@bbp.com", Set.of("ROLE_ADMIN"), Set.of("ACME"));
        when(companyService.resolveCompanyCodeById(42L)).thenReturn("ACME");
        when(companyService.resolveLifecycleStateByCode("ACME")).thenReturn(CompanyLifecycleState.DEACTIVATED);

        MockHttpServletRequest request = request("POST", "/api/v1/companies/42/lifecycle-state");
        request.setAttribute("jwtClaims", claimsFor("ACME"));
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter.doFilter(request, response, filterChain);

        assertThat(response.getStatus()).isEqualTo(403);
        assertThat(response.getContentAsString()).contains("Tenant is deactivated");
        verify(tenantRuntimeEnforcementService, never()).beginRequest(anyString(), anyString(), anyString(), anyString(), anyBoolean());
    }

    @Test
    void helperMethods_coverAuthorityAndPathResolutionBranches() {
        assertThat(invokeHasSuperAdminAuthority()).isFalse();
        assertThat(invokeHasTenantRuntimePolicyControlAuthority("/api/v1/admin/tenant-runtime/policy", "PUT"))
                .isFalse();

        authenticate("root@bbp.com", Set.of("ROLE_SUPER_ADMIN"), Set.of());
        assertThat(invokeHasSuperAdminAuthority()).isTrue();
        assertThat(invokeHasTenantRuntimePolicyControlAuthority("/api/v1/admin/tenant-runtime/policy", "PUT"))
                .isFalse();
        assertThat(invokeHasTenantRuntimePolicyControlAuthority("/api/v1/companies/77/tenant-runtime/policy", "PUT"))
                .isTrue();
        assertThat(invokeHasTenantRuntimePolicyControlAuthority("/api/v1/admin/tenant-runtime/policy", "GET"))
                .isFalse();

        authenticate("admin@bbp.com", Set.of("ROLE_ADMIN"), Set.of("ACME"));
        assertThat(invokeHasTenantRuntimePolicyControlAuthority("/api/v1/admin/tenant-runtime/policy", "PUT"))
                .isFalse();

        assertThat(invokeIsLifecycleControlRequest("/api/v1/companies/77/lifecycle-state", "POST")).isTrue();
        assertThat(invokeIsLifecycleControlRequest("/api/v1/companies/77/tenant-metrics", "GET")).isTrue();
        assertThat(invokeIsLifecycleControlRequest("/api/v1/companies/77/tenant-runtime/policy", "PUT")).isTrue();
        assertThat(invokeIsLifecycleControlRequest("/api/v1/companies/77", "PUT")).isTrue();
        assertThat(invokeIsLifecycleControlRequest(
                "/api/v1/companies/77/support/admin-password-reset", "POST")).isTrue();
        assertThat(invokeIsLifecycleControlRequest("/api/v1/private", "POST")).isFalse();

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
        assertThat(invokeHasTenantRuntimePolicyControlAuthority("/api/v1/admin/tenant-runtime/policy", "PUT"))
                .isFalse();

        assertThat(invokeIsLifecycleControlRequest("/api/v1/companies/77/not-lifecycle", "POST")).isFalse();

        assertThat(invokeIsLifecycleControlRequest("/api/v1/companies/77/not-metrics", "GET")).isFalse();
        assertThat(invokeIsLifecycleControlRequest("/api/v1/companies/77/x/lifecycle-state", "POST")).isFalse();
        assertThat(invokeIsLifecycleControlRequest("/api/v1/companies/77/x/support/admin-password-reset", "POST"))
                .isFalse();

        MockHttpServletRequest noPath = request("GET", "");
        noPath.setServletPath("   ");
        noPath.setRequestURI("   ");
        assertThat(invokeIsLifecycleControlRequest(null, "GET")).isFalse();
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

    @Test
    void doFilter_allowsPublicPasswordResetBypassWithTrailingSlash() throws ServletException, IOException {
        MockHttpServletRequest request = request("POST", "/api/v1/auth/password/forgot/");
        request.addHeader("X-Company-Code", "ACME");
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter.doFilter(request, response, filterChain);

        assertThat(response.getStatus()).isEqualTo(200);
        verify(filterChain).doFilter(request, response);
        verifyNoInteractions(companyService);
        verify(tenantRuntimeEnforcementService, never())
                .beginRequest(anyString(), anyString(), anyString(), anyString(), anyBoolean());
    }

    @Test
    void helperMethods_coverPublicPasswordResetAndPathNormalizationBranches() {
        assertThat(invokeIsPublicPasswordResetRequest("/api/v1/auth/password/forgot", "POST")).isTrue();
        assertThat(invokeIsPublicPasswordResetRequest("/api/v1/auth/password/forgot/", "POST")).isTrue();
        assertThat(invokeIsPublicPasswordResetRequest("/api/v1/auth/password/reset/", "POST")).isTrue();
        assertThat(invokeIsPublicPasswordResetRequest("/api/v1/auth/password/forgot", "GET")).isFalse();
        assertThat(invokeIsPublicPasswordResetRequest(null, "POST")).isFalse();
        assertThat(invokeIsPublicPasswordResetRequest("/api/v1/private", "POST")).isFalse();
        assertThat(invokeIsPublicPasswordResetRequest("/api/v1/auth/password/forgot/extra", "POST")).isFalse();
        assertThat(invokeIsPublicPasswordResetRequest("/api/v1/auth/password/reset/extra", "POST")).isFalse();

        assertThat(invokeNormalizePath(" /api/v1/auth/password/forgot/// ")).isEqualTo("/api/v1/auth/password/forgot");
        assertThat(invokeNormalizePath("/")).isEqualTo("/");
        assertThat(invokeNormalizePath("   ")).isEqualTo("   ");
    }

    @Test
    void helperMethods_coverSuperAdminTenantBusinessAndAuditGuards() {
        assertThat(invokeIsTenantBusinessRequestBlockedForSuperAdmin(null)).isFalse();
        assertThat(invokeIsTenantBusinessRequestBlockedForSuperAdmin("/api/v1/admin/approvals")).isTrue();
        assertThat(invokeIsTenantBusinessRequestBlockedForSuperAdmin("/api/v1/orchestrator/health")).isFalse();
        assertThat(invokeIsTenantBusinessRequestBlockedForSuperAdmin("/api/v1/orchestrator/jobs")).isTrue();
        assertThat(invokeIsTenantBusinessRequestBlockedForSuperAdmin("/api/v1/accounting/periods/2026-Q1/reopen")).isFalse();
        assertThat(invokeIsTenantBusinessRequestBlockedForSuperAdmin("/api/v1/accounting/journals")).isTrue();

        assertThat(invokeIsTenantAuditWorkflowRequest("/api/v1/audit/business-events")).isTrue();
        assertThat(invokeIsTenantAuditWorkflowRequest("/api/v1/audit;tenant=acme/business-events;mode=full")).isTrue();
        assertThat(invokeIsTenantAuditWorkflowRequest("/api/v1/admin/settings")).isFalse();

        assertThat(invokeNormalizePath("/api/v1/audit;tenant=acme/business-events;mode=full///"))
                .isEqualTo("/api/v1/audit/business-events");
    }

    @Test
    void authControllerForgotPasswordEndpoint_delegatesToPasswordResetService() {
        AuthService authService = mock(AuthService.class);
        PasswordService passwordService = mock(PasswordService.class);
        PasswordResetService passwordResetService = mock(PasswordResetService.class);
        AuthController controller = new AuthController(authService, passwordService, passwordResetService);

        ResponseEntity<ApiResponse<String>> response =
                controller.forgotPassword(new ForgotPasswordRequest("superadmin@example.com"));

        verify(passwordResetService).requestReset("superadmin@example.com");
        assertThat(response.getStatusCode().value()).isEqualTo(200);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().success()).isTrue();
        assertThat(response.getBody().message()).isEqualTo("If the email exists, a reset link has been sent");
        assertThat(response.getBody().data()).isEqualTo("OK");
    }

    @Test
    void passwordResetService_superAdminFlow_successAndMaskedFailures_runtimeCoverage() {
        UserAccountRepository userRepo = mock(UserAccountRepository.class);
        PasswordResetTokenRepository tokenRepo = mock(PasswordResetTokenRepository.class);
        PasswordService passwordService = mock(PasswordService.class);
        EmailService emailService = mock(EmailService.class);
        TokenBlacklistService tokenBlacklistService = mock(TokenBlacklistService.class);
        RefreshTokenService refreshTokenService = mock(RefreshTokenService.class);
        EmailProperties emailProperties = emailProperties(true, true);
        PasswordResetService service = new PasswordResetService(
                userRepo,
                tokenRepo,
                passwordService,
                emailService,
                emailProperties,
                tokenBlacklistService,
                refreshTokenService,
                new ResourcelessTransactionManager());

        UserAccount superAdmin = superAdminUser("superadmin@example.com");
        when(userRepo.findByEmailIgnoreCase("superadmin@example.com")).thenReturn(Optional.of(superAdmin));

        assertThatCode(() -> service.requestResetForSuperAdmin("superadmin@example.com")).doesNotThrowAnyException();

        verify(tokenRepo).deleteByUser(superAdmin);
        verify(tokenRepo).saveAndFlush(any(PasswordResetToken.class));
        verify(tokenRepo, never()).deleteByTokenDigest(anyString());
        verify(emailService).sendSimpleEmail(eq("superadmin@example.com"), eq("Reset your BigBright ERP password"), anyString());
    }

    @Test
    void passwordResetService_superAdminFlow_masksConfigurationAndPersistenceFailures_runtimeCoverage() {
        UserAccount superAdmin = superAdminUser("superadmin@example.com");

        UserAccountRepository disabledDeliveryRepo = mock(UserAccountRepository.class);
        PasswordResetTokenRepository disabledDeliveryTokenRepo = mock(PasswordResetTokenRepository.class);
        EmailService disabledDeliveryEmailService = mock(EmailService.class);
        when(disabledDeliveryRepo.findByEmailIgnoreCase("superadmin@example.com")).thenReturn(Optional.of(superAdmin));
        PasswordResetService disabledDeliveryService = new PasswordResetService(
                disabledDeliveryRepo,
                disabledDeliveryTokenRepo,
                mock(PasswordService.class),
                disabledDeliveryEmailService,
                emailProperties(true, false),
                mock(TokenBlacklistService.class),
                mock(RefreshTokenService.class),
                new ResourcelessTransactionManager());
        assertThatCode(() -> disabledDeliveryService.requestResetForSuperAdmin("superadmin@example.com"))
                .doesNotThrowAnyException();
        verify(disabledDeliveryTokenRepo, never()).saveAndFlush(any(PasswordResetToken.class));
        verifyNoInteractions(disabledDeliveryEmailService);

        UserAccountRepository disabledMailRepo = mock(UserAccountRepository.class);
        PasswordResetTokenRepository disabledMailTokenRepo = mock(PasswordResetTokenRepository.class);
        EmailService disabledMailEmailService = mock(EmailService.class);
        when(disabledMailRepo.findByEmailIgnoreCase("superadmin@example.com")).thenReturn(Optional.of(superAdmin));
        PasswordResetService disabledMailService = new PasswordResetService(
                disabledMailRepo,
                disabledMailTokenRepo,
                mock(PasswordService.class),
                disabledMailEmailService,
                emailProperties(false, true),
                mock(TokenBlacklistService.class),
                mock(RefreshTokenService.class),
                new ResourcelessTransactionManager());
        assertThatCode(() -> disabledMailService.requestResetForSuperAdmin("superadmin@example.com"))
                .doesNotThrowAnyException();
        verify(disabledMailTokenRepo, never()).saveAndFlush(any(PasswordResetToken.class));
        verifyNoInteractions(disabledMailEmailService);

        UserAccountRepository persistenceFailureRepo = mock(UserAccountRepository.class);
        PasswordResetTokenRepository persistenceFailureTokenRepo = mock(PasswordResetTokenRepository.class);
        EmailService persistenceFailureEmailService = mock(EmailService.class);
        when(persistenceFailureRepo.findByEmailIgnoreCase("superadmin@example.com")).thenReturn(Optional.of(superAdmin));
        doThrow(new DataAccessResourceFailureException("db unavailable"))
                .when(persistenceFailureTokenRepo)
                .saveAndFlush(any(PasswordResetToken.class));
        PasswordResetService persistenceFailureService = new PasswordResetService(
                persistenceFailureRepo,
                persistenceFailureTokenRepo,
                mock(PasswordService.class),
                persistenceFailureEmailService,
                emailProperties(true, true),
                mock(TokenBlacklistService.class),
                mock(RefreshTokenService.class),
                new ResourcelessTransactionManager());
        assertThatCode(() -> persistenceFailureService.requestResetForSuperAdmin("superadmin@example.com"))
                .doesNotThrowAnyException();
        verify(persistenceFailureTokenRepo).deleteByUser(superAdmin);
        verify(persistenceFailureTokenRepo).saveAndFlush(any(PasswordResetToken.class));
        verify(persistenceFailureTokenRepo, never()).deleteByTokenDigest(anyString());
        verifyNoInteractions(persistenceFailureEmailService);
    }

    @Test
    void passwordResetService_superAdminFlow_masksDeliveryFailureAndCleanupFailure_runtimeCoverage() {
        UserAccount superAdmin = superAdminUser("superadmin@example.com");
        UserAccountRepository userRepo = mock(UserAccountRepository.class);
        PasswordResetTokenRepository tokenRepo = mock(PasswordResetTokenRepository.class);
        EmailService emailService = mock(EmailService.class);
        when(userRepo.findByEmailIgnoreCase("superadmin@example.com")).thenReturn(Optional.of(superAdmin));
        doThrow(new DataAccessResourceFailureException("cleanup failed"))
                .when(tokenRepo)
                .deleteByTokenDigest(anyString());
        doThrow(new RuntimeException("smtp down"))
                .when(emailService)
                .sendSimpleEmail(eq("superadmin@example.com"), eq("Reset your BigBright ERP password"), anyString());
        PasswordResetService service = new PasswordResetService(
                userRepo,
                tokenRepo,
                mock(PasswordService.class),
                emailService,
                emailProperties(true, true),
                mock(TokenBlacklistService.class),
                mock(RefreshTokenService.class),
                new ResourcelessTransactionManager());

        assertThatCode(() -> service.requestResetForSuperAdmin("superadmin@example.com")).doesNotThrowAnyException();

        verify(tokenRepo).deleteByUser(superAdmin);
        verify(tokenRepo).saveAndFlush(any(PasswordResetToken.class));
        verify(tokenRepo).deleteByTokenDigest(anyString());
        verify(emailService).sendSimpleEmail(eq("superadmin@example.com"), eq("Reset your BigBright ERP password"), anyString());
    }

    @Test
    void passwordResetService_helperBranches_coverRoleFiltersAndEmailObfuscation_runtimeCoverage() {
        PasswordResetService service = new PasswordResetService(
                mock(UserAccountRepository.class),
                mock(PasswordResetTokenRepository.class),
                mock(PasswordService.class),
                mock(EmailService.class),
                emailProperties(true, true),
                mock(TokenBlacklistService.class),
                mock(RefreshTokenService.class),
                new ResourcelessTransactionManager());

        assertThat(invokeHasSuperAdminRole(service, null)).isFalse();

        UserAccount rolesNullUser = new UserAccount("roles-null@example.com", "hash", "Roles Null");
        ReflectionTestUtils.setField(rolesNullUser, "roles", null);
        assertThat(invokeHasSuperAdminRole(service, rolesNullUser)).isFalse();

        UserAccount noRoleUser = new UserAccount("norole@example.com", "hash", "No Role");
        assertThat(invokeHasSuperAdminRole(service, noRoleUser)).isFalse();

        UserAccount nullRoleUser = new UserAccount("nullrole@example.com", "hash", "Null Role");
        nullRoleUser.addRole(null);
        assertThat(invokeHasSuperAdminRole(service, nullRoleUser)).isFalse();

        UserAccount superAdmin = superAdminUser("superadmin@example.com");
        assertThat(invokeHasSuperAdminRole(service, superAdmin)).isTrue();

        assertThat(invokeObfuscateEmail(service, null)).isEqualTo("<empty>");
        assertThat(invokeObfuscateEmail(service, "a@example.com")).isEqualTo("***");
        assertThat(invokeObfuscateEmail(service, "admin@example.com")).isEqualTo("a***@example.com");

        assertThatCode(() -> invokeCleanupFailedSuperAdminResetToken(service, null, null)).doesNotThrowAnyException();
    }

    @Test
    void passwordResetService_cleanupBranch_coversNullUserFallbackOnDeleteFailure() {
        PasswordResetTokenRepository tokenRepository = mock(PasswordResetTokenRepository.class);
        doThrow(new DataAccessResourceFailureException("cleanup failed"))
                .when(tokenRepository)
                .deleteByTokenDigest(anyString());

        PasswordResetService service = new PasswordResetService(
                mock(UserAccountRepository.class),
                tokenRepository,
                mock(PasswordService.class),
                mock(EmailService.class),
                emailProperties(true, true),
                mock(TokenBlacklistService.class),
                mock(RefreshTokenService.class),
                new ResourcelessTransactionManager());

        assertThatCode(() -> invokeCleanupFailedSuperAdminResetToken(service, null, "token-123"))
                .doesNotThrowAnyException();
        verify(tokenRepository).deleteByTokenDigest(anyString());
    }

    @Test
    void passwordResetService_cleanupBranch_coversDeleteByTokenSuccessPath() {
        PasswordResetTokenRepository tokenRepository = mock(PasswordResetTokenRepository.class);
        PasswordResetService service = new PasswordResetService(
                mock(UserAccountRepository.class),
                tokenRepository,
                mock(PasswordService.class),
                mock(EmailService.class),
                emailProperties(true, true),
                mock(TokenBlacklistService.class),
                mock(RefreshTokenService.class),
                new ResourcelessTransactionManager());

        assertThatCode(() -> invokeCleanupFailedSuperAdminResetToken(service, superAdminUser("superadmin@example.com"), "token-123"))
                .doesNotThrowAnyException();
        verify(tokenRepository).deleteByTokenDigest(anyString());
    }

    @Test
    void passwordResetService_requestResetForSuperAdmin_skipsNonSuperAdminAndDisabledUsers_runtimeCoverage() {
        UserAccountRepository userRepo = mock(UserAccountRepository.class);
        PasswordResetTokenRepository tokenRepo = mock(PasswordResetTokenRepository.class);
        EmailService emailService = mock(EmailService.class);
        PasswordResetService service = new PasswordResetService(
                userRepo,
                tokenRepo,
                mock(PasswordService.class),
                emailService,
                emailProperties(true, true),
                mock(TokenBlacklistService.class),
                mock(RefreshTokenService.class),
                new ResourcelessTransactionManager());

        UserAccount nonSuperAdmin = new UserAccount("admin@example.com", "hash", "Admin");
        nonSuperAdmin.setEnabled(true);
        Role adminRole = new Role();
        adminRole.setName("ROLE_ADMIN");
        nonSuperAdmin.addRole(adminRole);
        when(userRepo.findByEmailIgnoreCase("admin@example.com")).thenReturn(Optional.of(nonSuperAdmin));

        UserAccount disabledSuperAdmin = superAdminUser("disabled-superadmin@example.com");
        disabledSuperAdmin.setEnabled(false);
        when(userRepo.findByEmailIgnoreCase("disabled-superadmin@example.com")).thenReturn(Optional.of(disabledSuperAdmin));

        when(userRepo.findByEmailIgnoreCase("missing@example.com")).thenReturn(Optional.empty());

        assertThatCode(() -> service.requestResetForSuperAdmin("admin@example.com")).doesNotThrowAnyException();
        assertThatCode(() -> service.requestResetForSuperAdmin("disabled-superadmin@example.com")).doesNotThrowAnyException();
        assertThatCode(() -> service.requestResetForSuperAdmin("missing@example.com")).doesNotThrowAnyException();

        verify(tokenRepo, never()).deleteByUser(any(UserAccount.class));
        verify(tokenRepo, never()).deleteByTokenDigest(anyString());
        verify(tokenRepo, never()).saveAndFlush(any(PasswordResetToken.class));
        verifyNoInteractions(emailService);
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

    private boolean invokeHasSuperAdminAuthority() {
        Boolean result = ReflectionTestUtils.invokeMethod(filter, "hasSuperAdminAuthority");
        assertThat(result).isNotNull();
        return result;
    }

    private boolean invokeHasTenantRuntimePolicyControlAuthority(String requestPath, String requestMethod) {
        Boolean result = ReflectionTestUtils.invokeMethod(
                filter,
                "hasTenantRuntimePolicyControlAuthority",
                requestPath,
                requestMethod
        );
        assertThat(result).isNotNull();
        return result;
    }

    private boolean invokeIsLifecycleControlRequest(String requestPath, String requestMethod) {
        Boolean result = ReflectionTestUtils.invokeMethod(
                filter,
                "isLifecycleControlRequest",
                requestPath,
                requestMethod
        );
        assertThat(result).isNotNull();
        return result;
    }

    private String invokeResolveApplicationPath(MockHttpServletRequest request) {
        return ReflectionTestUtils.invokeMethod(filter, "resolveApplicationPath", request);
    }

    private boolean invokeIsPublicPasswordResetRequest(String path, String method) {
        Boolean result = ReflectionTestUtils.invokeMethod(filter, "isPublicPasswordResetRequest", path, method);
        assertThat(result).isNotNull();
        return result;
    }

    private String invokeNormalizePath(String path) {
        return ReflectionTestUtils.invokeMethod(filter, "normalizePath", path);
    }

    private boolean invokeIsTenantBusinessRequestBlockedForSuperAdmin(String path) {
        Boolean result = ReflectionTestUtils.invokeMethod(filter, "isTenantBusinessRequestBlockedForSuperAdmin", path);
        assertThat(result).isNotNull();
        return result;
    }

    private boolean invokeIsTenantAuditWorkflowRequest(String path) {
        Boolean result = ReflectionTestUtils.invokeMethod(filter, "isTenantAuditWorkflowRequest", path);
        assertThat(result).isNotNull();
        return result;
    }

    private UserAccount superAdminUser(String email) {
        UserAccount user = new UserAccount(email, "hash", "Super Admin");
        user.setEnabled(true);
        Role role = new Role();
        role.setName("ROLE_SUPER_ADMIN");
        user.addRole(role);
        return user;
    }

    private EmailProperties emailProperties(boolean enabled, boolean sendPasswordReset) {
        EmailProperties props = new EmailProperties();
        props.setEnabled(enabled);
        props.setSendPasswordReset(sendPasswordReset);
        props.setBaseUrl("http://localhost:3004");
        return props;
    }

    private boolean invokeHasSuperAdminRole(PasswordResetService service, UserAccount user) {
        Boolean result = ReflectionTestUtils.invokeMethod(service, "hasSuperAdminRole", user);
        assertThat(result).isNotNull();
        return result;
    }

    private String invokeObfuscateEmail(PasswordResetService service, String email) {
        return ReflectionTestUtils.invokeMethod(service, "obfuscateEmail", email);
    }

    private void invokeCleanupFailedSuperAdminResetToken(PasswordResetService service, UserAccount user, String tokenValue) {
        ReflectionTestUtils.invokeMethod(
                service,
                "cleanupFailedSuperAdminResetToken",
                user,
                tokenValue,
                "test-correlation-id");
    }
}
