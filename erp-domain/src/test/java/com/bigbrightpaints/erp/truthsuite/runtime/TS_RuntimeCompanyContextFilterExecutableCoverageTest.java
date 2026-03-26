package com.bigbrightpaints.erp.truthsuite.runtime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

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

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;

import com.bigbrightpaints.erp.core.config.EmailProperties;
import com.bigbrightpaints.erp.core.exception.ApplicationException;
import com.bigbrightpaints.erp.core.audit.AuditService;
import com.bigbrightpaints.erp.core.notification.EmailService;
import com.bigbrightpaints.erp.core.security.AuthScopeService;
import com.bigbrightpaints.erp.core.security.CompanyContextFilter;
import com.bigbrightpaints.erp.core.security.CompanyContextHolder;
import com.bigbrightpaints.erp.core.security.SecurityMonitoringService;
import com.bigbrightpaints.erp.core.security.TokenBlacklistService;
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
import com.bigbrightpaints.erp.shared.dto.ApiResponse;

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

  @Mock private AuthScopeService companyContextAuthScopeService;

  @Mock private FilterChain filterChain;

  private CompanyContextFilter filter;

  @BeforeEach
  void setUp() {
    filter =
        new CompanyContextFilter(
            tenantRuntimeEnforcementService,
            companyService,
            companyContextAuthScopeService,
            OBJECT_MAPPER);
    lenient().when(companyContextAuthScopeService.isPlatformScope(anyString())).thenReturn(false);
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
    verify(tenantRuntimeEnforcementService)
        .completeRequest(
            any(TenantRuntimeEnforcementService.TenantRequestAdmission.class), eq(403));
    verify(filterChain, never()).doFilter(request, response);
  }

  @Test
  void doFilter_rejectsAuthenticatedUserWithoutCompanyMembership()
      throws ServletException, IOException {
    authenticate("user@bbp.com", Set.of("ROLE_ADMIN"), Set.of("OTHER"));
    when(companyService.resolveLifecycleStateByCode("ACME"))
        .thenReturn(CompanyLifecycleState.ACTIVE);

    MockHttpServletRequest request = request("GET", "/api/v1/private");
    request.setAttribute("jwtClaims", claimsFor("ACME"));
    MockHttpServletResponse response = new MockHttpServletResponse();

    filter.doFilter(request, response, filterChain);

    assertThat(response.getStatus()).isEqualTo(403);
    assertThat(response.getContentAsString()).contains("Access denied to company: ACME");
    verify(tenantRuntimeEnforcementService, never())
        .beginRequest(anyString(), anyString(), anyString(), anyString(), anyBoolean());
    verify(filterChain, never()).doFilter(request, response);
  }

  @Test
  void doFilter_allowsSuperAdminLifecycleControlBypassForNonActiveTenant()
      throws ServletException, IOException {
    authenticate("ops@bbp.com", Set.of("ROLE_SUPER_ADMIN"), Set.of());
    when(companyService.resolveCompanyCodeById(42L)).thenReturn("ACME");
    when(companyService.resolveLifecycleStateByCode("ACME"))
        .thenReturn(CompanyLifecycleState.DEACTIVATED);

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
  void doFilter_rejectsWhenTenantRuntimeAdmissionDenied_andEscapesResponseMessage()
      throws ServletException, IOException {
    authenticate("admin@bbp.com", Set.of("ROLE_ADMIN"), Set.of("ACME"));
    when(companyService.resolveLifecycleStateByCode("ACME"))
        .thenReturn(CompanyLifecycleState.ACTIVE);
    when(tenantRuntimeEnforcementService.beginRequest(
            eq("ACME"), eq("/api/v1/private"), eq("GET"), eq("admin@bbp.com"), eq(false)))
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
  void doFilter_rejectsNonActiveTenantForNonLifecycleRequests()
      throws ServletException, IOException {
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
  void doFilter_rejectsLifecycleControlMutationForNonSuperAdminOnBlockedTenant()
      throws ServletException, IOException {
    authenticate("admin@bbp.com", Set.of("ROLE_ADMIN"), Set.of("ACME"));
    when(companyService.resolveCompanyCodeById(42L)).thenReturn("ACME");
    when(companyService.resolveLifecycleStateByCode("ACME"))
        .thenReturn(CompanyLifecycleState.DEACTIVATED);

    MockHttpServletRequest request = request("POST", "/api/v1/companies/42/lifecycle-state");
    request.setAttribute("jwtClaims", claimsFor("ACME"));
    MockHttpServletResponse response = new MockHttpServletResponse();

    filter.doFilter(request, response, filterChain);

    assertThat(response.getStatus()).isEqualTo(403);
    assertThat(response.getContentAsString()).contains("Tenant is deactivated");
    verify(tenantRuntimeEnforcementService, never())
        .beginRequest(anyString(), anyString(), anyString(), anyString(), anyBoolean());
  }

  @Test
  void helperMethods_coverAuthorityAndPathResolutionBranches() {
    assertThat(invokeHasSuperAdminAuthority()).isFalse();
    assertThat(
            invokeHasTenantRuntimePolicyControlAuthority(
                "/api/v1/admin/tenant-runtime/policy", "PUT"))
        .isFalse();

    authenticate("root@bbp.com", Set.of("ROLE_SUPER_ADMIN"), Set.of());
    assertThat(invokeHasSuperAdminAuthority()).isTrue();
    assertThat(
            invokeHasTenantRuntimePolicyControlAuthority(
                "/api/v1/admin/tenant-runtime/policy", "PUT"))
        .isFalse();
    assertThat(
            invokeHasTenantRuntimePolicyControlAuthority(
                "/api/v1/companies/77/tenant-runtime/policy", "PUT"))
        .isTrue();
    assertThat(
            invokeHasTenantRuntimePolicyControlAuthority(
                "/api/v1/admin/tenant-runtime/policy", "GET"))
        .isFalse();

    authenticate("admin@bbp.com", Set.of("ROLE_ADMIN"), Set.of("ACME"));
    assertThat(
            invokeHasTenantRuntimePolicyControlAuthority(
                "/api/v1/admin/tenant-runtime/policy", "PUT"))
        .isFalse();

    assertThat(invokeIsLifecycleControlRequest("/api/v1/companies/77/lifecycle-state", "POST"))
        .isTrue();
    assertThat(invokeIsLifecycleControlRequest("/api/v1/companies/77/tenant-metrics", "GET"))
        .isTrue();
    assertThat(invokeIsLifecycleControlRequest("/api/v1/companies/77/tenant-runtime/policy", "PUT"))
        .isTrue();
    assertThat(invokeIsLifecycleControlRequest("/api/v1/companies/77", "PUT")).isTrue();
    assertThat(
            invokeIsLifecycleControlRequest(
                "/api/v1/companies/77/support/admin-password-reset", "POST"))
        .isTrue();
    assertThat(invokeIsLifecycleControlRequest("/api/v1/private", "POST")).isFalse();

    assertThat(invokeResolveApplicationPath(null)).isNull();
    MockHttpServletRequest contextAware = request("POST", "");
    contextAware.setContextPath("/erp");
    contextAware.setRequestURI("/erp/api/v1/companies/77/lifecycle-state");
    assertThat(invokeResolveApplicationPath(contextAware))
        .isEqualTo("/api/v1/companies/77/lifecycle-state");

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
    assertThat(
            invokeHasTenantRuntimePolicyControlAuthority(
                "/api/v1/admin/tenant-runtime/policy", "PUT"))
        .isFalse();

    assertThat(invokeIsLifecycleControlRequest("/api/v1/companies/77/not-lifecycle", "POST"))
        .isFalse();

    assertThat(invokeIsLifecycleControlRequest("/api/v1/companies/77/not-metrics", "GET"))
        .isFalse();
    assertThat(invokeIsLifecycleControlRequest("/api/v1/companies/77/x/lifecycle-state", "POST"))
        .isFalse();
    assertThat(
            invokeIsLifecycleControlRequest(
                "/api/v1/companies/77/x/support/admin-password-reset", "POST"))
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
    assertThat(invokeResolveApplicationPath(contextNotMatched))
        .isEqualTo("/outside/api/v1/private");
  }

  @Test
  void doFilter_allowsPublicPasswordResetBypassWithTrailingSlash()
      throws ServletException, IOException {
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
    assertThat(invokeIsPublicPasswordResetRequest("/api/v1/auth/password/forgot/", "POST"))
        .isTrue();
    assertThat(invokeIsPublicPasswordResetRequest("/api/v1/auth/password/reset/", "POST")).isTrue();
    assertThat(invokeIsPublicPasswordResetRequest("/api/v1/auth/password/forgot", "GET")).isFalse();
    assertThat(invokeIsPublicPasswordResetRequest(null, "POST")).isFalse();
    assertThat(invokeIsPublicPasswordResetRequest("/api/v1/private", "POST")).isFalse();
    assertThat(invokeIsPublicPasswordResetRequest("/api/v1/auth/password/forgot/extra", "POST"))
        .isFalse();
    assertThat(invokeIsPublicPasswordResetRequest("/api/v1/auth/password/reset/extra", "POST"))
        .isFalse();

    assertThat(invokeNormalizePath(" /api/v1/auth/password/forgot/// "))
        .isEqualTo("/api/v1/auth/password/forgot");
    assertThat(invokeNormalizePath("/")).isEqualTo("/");
    assertThat(invokeNormalizePath("   ")).isEqualTo("   ");
  }

  @Test
  void helperMethods_coverSuperAdminTenantBusinessAndAuditGuards() {
    assertThat(invokeIsTenantBusinessRequestBlockedForSuperAdmin(null)).isFalse();
    assertThat(invokeIsTenantBusinessRequestBlockedForSuperAdmin("/api/v1/admin/approvals"))
        .isTrue();
    assertThat(invokeIsTenantBusinessRequestBlockedForSuperAdmin("/api/v1/orchestrator/health"))
        .isFalse();
    assertThat(invokeIsTenantBusinessRequestBlockedForSuperAdmin("/api/v1/orchestrator/jobs"))
        .isTrue();
    assertThat(
            invokeIsTenantBusinessRequestBlockedForSuperAdmin(
                "/api/v1/accounting/periods/2026-Q1/reopen"))
        .isFalse();
    assertThat(invokeIsTenantBusinessRequestBlockedForSuperAdmin("/api/v1/accounting/journals"))
        .isTrue();

    assertThat(invokeIsTenantAuditWorkflowRequest("/api/v1/audit/business-events")).isTrue();
    assertThat(
            invokeIsTenantAuditWorkflowRequest(
                "/api/v1/audit;tenant=acme/business-events;mode=full"))
        .isTrue();
    assertThat(invokeIsTenantAuditWorkflowRequest("/api/v1/admin/settings")).isFalse();
    assertThat(invokeIsPlatformScopedRequestAllowed("/api/v1/auth/me")).isTrue();
    assertThat(invokeIsPlatformScopedRequestAllowed("/api/v1/superadmin/dashboard")).isTrue();
    assertThat(invokeIsPlatformScopedRequestAllowed("/api/v1/admin/settings")).isTrue();
    assertThat(invokeIsPlatformScopedRequestAllowed("/api/v1/admin/changelog")).isFalse();

    assertThat(invokeNormalizePath("/api/v1/audit;tenant=acme/business-events;mode=full///"))
        .isEqualTo("/api/v1/audit/business-events");
  }

  @Test
  void helperMethods_treatReadOnlyMethodsAsNonMutating_andBlankMethodAsMutating() {
    assertThat(invokeIsMutatingRequest("GET")).isFalse();
    assertThat(invokeIsMutatingRequest("HEAD")).isFalse();
    assertThat(invokeIsMutatingRequest("OPTIONS")).isFalse();
    assertThat(invokeIsMutatingRequest("TRACE")).isFalse();
    assertThat(invokeIsMutatingRequest("POST")).isTrue();
    assertThat(invokeIsMutatingRequest("   ")).isTrue();
  }

  @Test
  void authControllerForgotPasswordEndpoint_delegatesToPasswordResetService() {
    AuthService authService = mock(AuthService.class);
    PasswordService passwordService = mock(PasswordService.class);
    PasswordResetService passwordResetService = mock(PasswordResetService.class);
    AuthController controller =
        new AuthController(authService, passwordService, passwordResetService);

    ResponseEntity<ApiResponse<String>> response =
        controller.forgotPassword(new ForgotPasswordRequest("superadmin@example.com", "PLATFORM"));

    verify(passwordResetService).requestReset("superadmin@example.com", "PLATFORM");
    assertThat(response.getStatusCode().value()).isEqualTo(200);
    assertThat(response.getBody()).isNotNull();
    assertThat(response.getBody().success()).isTrue();
    assertThat(response.getBody().message())
        .isEqualTo("If the email exists, a reset link has been sent");
    assertThat(response.getBody().data()).isEqualTo("OK");
  }

  @Test
  void passwordResetService_scopedForgotFlow_dispatchesEmailInPublicRuntimeLane() {
    UserAccountRepository userRepo = mock(UserAccountRepository.class);
    PasswordResetTokenRepository tokenRepo = mock(PasswordResetTokenRepository.class);
    PasswordService passwordService = mock(PasswordService.class);
    EmailService emailService = mock(EmailService.class);
    TokenBlacklistService tokenBlacklistService = mock(TokenBlacklistService.class);
    RefreshTokenService refreshTokenService = mock(RefreshTokenService.class);
    PasswordResetService service =
        new PasswordResetService(
            userRepo,
            tokenRepo,
            passwordService,
            emailService,
            emailProperties(true, true),
            mock(AuditService.class),
            securityMonitoringService(),
            tokenBlacklistService,
            refreshTokenService,
            authScopeService(),
            new ResourcelessTransactionManager());

    UserAccount scopedUser = scopedUser("user@example.com", "ACME");
    when(userRepo.findByEmailIgnoreCaseAndAuthScopeCodeIgnoreCase("user@example.com", "ACME"))
        .thenReturn(Optional.of(scopedUser));
    stubIssuedResetToken(tokenRepo, 41L);

    assertThatCode(() -> service.requestReset("user@example.com", "ACME"))
        .doesNotThrowAnyException();

    verify(tokenRepo).saveAndFlush(any(PasswordResetToken.class));
    verify(emailService)
        .sendPasswordResetEmailRequired(eq("user@example.com"), eq("User"), anyString(), eq("ACME"));
  }

  @Test
  void passwordResetService_scopedForgotFlow_masksConfigurationAndPersistenceFailures_runtimeCoverage() {
    UserAccount scopedUser = scopedUser("user@example.com", "ACME");

    UserAccountRepository disabledDeliveryRepo = mock(UserAccountRepository.class);
    PasswordResetTokenRepository disabledDeliveryTokenRepo =
        mock(PasswordResetTokenRepository.class);
    EmailService disabledDeliveryEmailService = mock(EmailService.class);
    when(disabledDeliveryRepo.findByEmailIgnoreCaseAndAuthScopeCodeIgnoreCase("user@example.com", "ACME"))
        .thenReturn(Optional.of(scopedUser));
    PasswordResetService disabledDeliveryService =
        new PasswordResetService(
            disabledDeliveryRepo,
            disabledDeliveryTokenRepo,
            mock(PasswordService.class),
            disabledDeliveryEmailService,
            emailProperties(true, false),
            mock(AuditService.class),
            securityMonitoringService(),
            mock(TokenBlacklistService.class),
            mock(RefreshTokenService.class),
            authScopeService(),
            new ResourcelessTransactionManager());
    assertThatCode(() -> disabledDeliveryService.requestReset("user@example.com", "ACME"))
        .doesNotThrowAnyException();
    verify(disabledDeliveryTokenRepo, never()).saveAndFlush(any(PasswordResetToken.class));
    verifyNoInteractions(disabledDeliveryEmailService);

    UserAccountRepository persistenceFailureRepo = mock(UserAccountRepository.class);
    PasswordResetTokenRepository persistenceFailureTokenRepo =
        mock(PasswordResetTokenRepository.class);
    EmailService persistenceFailureEmailService = mock(EmailService.class);
    when(persistenceFailureRepo.findByEmailIgnoreCaseAndAuthScopeCodeIgnoreCase("user@example.com", "ACME"))
        .thenReturn(Optional.of(scopedUser));
    doThrow(new DataAccessResourceFailureException("db unavailable"))
        .when(persistenceFailureTokenRepo)
        .saveAndFlush(any(PasswordResetToken.class));
    PasswordResetService persistenceFailureService =
        new PasswordResetService(
            persistenceFailureRepo,
            persistenceFailureTokenRepo,
            mock(PasswordService.class),
            persistenceFailureEmailService,
            emailProperties(true, true),
            mock(AuditService.class),
            securityMonitoringService(),
            mock(TokenBlacklistService.class),
            mock(RefreshTokenService.class),
            authScopeService(),
            new ResourcelessTransactionManager());
    assertThatCode(() -> persistenceFailureService.requestReset("user@example.com", "ACME"))
        .doesNotThrowAnyException();
    verify(persistenceFailureTokenRepo).saveAndFlush(any(PasswordResetToken.class));
    verifyNoInteractions(persistenceFailureEmailService);
  }

  @Test
  void passwordResetService_scopedForgotFlow_cleansUpAndMasksDeliveryFailure() {
    UserAccount scopedUser = scopedUser("user@example.com", "ACME");
    UserAccountRepository userRepo = mock(UserAccountRepository.class);
    PasswordResetTokenRepository tokenRepo = mock(PasswordResetTokenRepository.class);
    EmailService emailService = mock(EmailService.class);
    when(userRepo.findByEmailIgnoreCaseAndAuthScopeCodeIgnoreCase("user@example.com", "ACME"))
        .thenReturn(Optional.of(scopedUser));
    stubIssuedResetToken(tokenRepo, 51L);
    doThrow(new RuntimeException("smtp down"))
        .when(emailService)
        .sendPasswordResetEmailRequired(eq("user@example.com"), eq("User"), anyString(), eq("ACME"));
    PasswordResetService service =
        new PasswordResetService(
            userRepo,
            tokenRepo,
            mock(PasswordService.class),
            emailService,
            emailProperties(true, true),
            mock(AuditService.class),
            securityMonitoringService(),
            mock(TokenBlacklistService.class),
            mock(RefreshTokenService.class),
            authScopeService(),
            new ResourcelessTransactionManager());

    assertThatCode(() -> service.requestReset("user@example.com", "ACME"))
        .doesNotThrowAnyException();

    verify(tokenRepo).saveAndFlush(any(PasswordResetToken.class));
    verify(tokenRepo).deleteByTokenDigest(anyString());
    verify(emailService)
        .sendPasswordResetEmailRequired(eq("user@example.com"), eq("User"), anyString(), eq("ACME"));
  }

  @Test
  void passwordResetService_helperBranches_coverEmailObfuscation_runtimeCoverage() {
    PasswordResetService service =
        new PasswordResetService(
            mock(UserAccountRepository.class),
            mock(PasswordResetTokenRepository.class),
            mock(PasswordService.class),
            mock(EmailService.class),
            emailProperties(true, true),
            mock(AuditService.class),
            securityMonitoringService(),
            mock(TokenBlacklistService.class),
            mock(RefreshTokenService.class),
            authScopeService(),
            new ResourcelessTransactionManager());

    assertThat(invokeObfuscateEmail(service, null)).isEqualTo("<empty>");
    assertThat(invokeObfuscateEmail(service, "a@example.com")).isEqualTo("***");
    assertThat(invokeObfuscateEmail(service, "admin@example.com")).isEqualTo("a***@example.com");
  }

  @Test
  void passwordResetService_requestReset_skipsDisabledAndMissingUsers_runtimeCoverage() {
    UserAccountRepository userRepo = mock(UserAccountRepository.class);
    PasswordResetTokenRepository tokenRepo = mock(PasswordResetTokenRepository.class);
    EmailService emailService = mock(EmailService.class);
    PasswordResetService service =
        new PasswordResetService(
            userRepo,
            tokenRepo,
            mock(PasswordService.class),
            emailService,
            emailProperties(true, true),
            mock(AuditService.class),
            securityMonitoringService(),
            mock(TokenBlacklistService.class),
            mock(RefreshTokenService.class),
            authScopeService(),
            new ResourcelessTransactionManager());

    UserAccount disabledUser = scopedUser("disabled@example.com", "ACME");
    disabledUser.setEnabled(false);
    when(userRepo.findByEmailIgnoreCaseAndAuthScopeCodeIgnoreCase("disabled@example.com", "ACME"))
        .thenReturn(Optional.of(disabledUser));
    when(userRepo.findByEmailIgnoreCaseAndAuthScopeCodeIgnoreCase("missing@example.com", "ACME"))
        .thenReturn(Optional.empty());

    assertThatCode(() -> service.requestReset("disabled@example.com", "ACME"))
        .doesNotThrowAnyException();
    assertThatCode(() -> service.requestReset("missing@example.com", "ACME"))
        .doesNotThrowAnyException();

    verify(tokenRepo, never()).deleteByTokenDigest(anyString());
    verify(tokenRepo, never()).saveAndFlush(any(PasswordResetToken.class));
    verifyNoInteractions(emailService);
  }

  private void authenticate(String email, Set<String> authorities, Set<String> companyCodes) {
    UserAccount user = new UserAccount(email, "hash", "Operator");
    for (String code : companyCodes) {
      Company company = new Company();
      company.setCode(code);
      user.setCompany(company);
    }
    UserPrincipal principal = new UserPrincipal(user);
    List<SimpleGrantedAuthority> grantedAuthorities =
        authorities.stream().map(SimpleGrantedAuthority::new).toList();
    Authentication authentication =
        new UsernamePasswordAuthenticationToken(principal, "n/a", grantedAuthorities);
    SecurityContextHolder.getContext().setAuthentication(authentication);
  }

  private Claims claimsFor(String companyCode) {
    Claims claims = mock(Claims.class);
    when(claims.get("companyCode", String.class)).thenReturn(companyCode);
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

  private boolean invokeHasTenantRuntimePolicyControlAuthority(
      String requestPath, String requestMethod) {
    Boolean result =
        ReflectionTestUtils.invokeMethod(
            filter, "hasTenantRuntimePolicyControlAuthority", requestPath, requestMethod);
    assertThat(result).isNotNull();
    return result;
  }

  private boolean invokeIsLifecycleControlRequest(String requestPath, String requestMethod) {
    Boolean result =
        ReflectionTestUtils.invokeMethod(
            filter, "isLifecycleControlRequest", requestPath, requestMethod);
    assertThat(result).isNotNull();
    return result;
  }

  private String invokeResolveApplicationPath(MockHttpServletRequest request) {
    return ReflectionTestUtils.invokeMethod(filter, "resolveApplicationPath", request);
  }

  private boolean invokeIsPublicPasswordResetRequest(String path, String method) {
    Boolean result =
        ReflectionTestUtils.invokeMethod(filter, "isPublicPasswordResetRequest", path, method);
    assertThat(result).isNotNull();
    return result;
  }

  private String invokeNormalizePath(String path) {
    return ReflectionTestUtils.invokeMethod(filter, "normalizePath", path);
  }

  private boolean invokeIsTenantBusinessRequestBlockedForSuperAdmin(String path) {
    Boolean result =
        ReflectionTestUtils.invokeMethod(
            filter, "isTenantBusinessRequestBlockedForSuperAdmin", path);
    assertThat(result).isNotNull();
    return result;
  }

  private boolean invokeIsTenantAuditWorkflowRequest(String path) {
    Boolean result = ReflectionTestUtils.invokeMethod(filter, "isTenantAuditWorkflowRequest", path);
    assertThat(result).isNotNull();
    return result;
  }

  private boolean invokeIsPlatformScopedRequestAllowed(String path) {
    Boolean result = ReflectionTestUtils.invokeMethod(filter, "isPlatformScopedRequestAllowed", path);
    assertThat(result).isNotNull();
    return result;
  }

  private boolean invokeIsMutatingRequest(String method) {
    Boolean result = ReflectionTestUtils.invokeMethod(filter, "isMutatingRequest", method);
    assertThat(result).isNotNull();
    return result;
  }

  private EmailProperties emailProperties(boolean enabled, boolean sendPasswordReset) {
    EmailProperties props = new EmailProperties();
    props.setEnabled(enabled);
    props.setSendPasswordReset(sendPasswordReset);
    props.setBaseUrl("http://localhost:3004");
    return props;
  }

  private String invokeObfuscateEmail(PasswordResetService service, String email) {
    return ReflectionTestUtils.invokeMethod(service, "obfuscateEmail", email);
  }

  private AuthScopeService authScopeService() {
    AuthScopeService authScopeService = mock(AuthScopeService.class);
    lenient()
        .when(authScopeService.requireScopeCode(anyString()))
        .thenAnswer(invocation -> invocation.getArgument(0, String.class).trim().toUpperCase());
    return authScopeService;
  }

  private SecurityMonitoringService securityMonitoringService() {
    SecurityMonitoringService securityMonitoringService = mock(SecurityMonitoringService.class);
    lenient().when(securityMonitoringService.checkRateLimit(anyString())).thenReturn(true);
    return securityMonitoringService;
  }

  private void stubIssuedResetToken(PasswordResetTokenRepository tokenRepository, long tokenId) {
    when(tokenRepository.saveAndFlush(any(PasswordResetToken.class)))
        .thenAnswer(
            invocation -> {
              PasswordResetToken token = invocation.getArgument(0);
              ReflectionTestUtils.setField(token, "id", tokenId);
              return token;
            });
  }

  private UserAccount scopedUser(String email, String scopeCode) {
    UserAccount user = new UserAccount(email, scopeCode, "hash", "User");
    user.setEnabled(true);
    return user;
  }
}
