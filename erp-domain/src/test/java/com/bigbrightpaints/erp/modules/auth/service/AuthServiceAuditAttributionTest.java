package com.bigbrightpaints.erp.modules.auth.service;

import com.bigbrightpaints.erp.core.audit.AuditEvent;
import com.bigbrightpaints.erp.core.audit.AuditService;
import com.bigbrightpaints.erp.core.notification.EmailService;
import com.bigbrightpaints.erp.core.security.JwtProperties;
import com.bigbrightpaints.erp.core.security.JwtTokenService;
import com.bigbrightpaints.erp.core.security.TokenBlacklistService;
import com.bigbrightpaints.erp.modules.auth.domain.UserAccount;
import com.bigbrightpaints.erp.modules.auth.domain.UserAccountRepository;
import com.bigbrightpaints.erp.modules.auth.web.LoginRequest;
import com.bigbrightpaints.erp.modules.company.domain.CompanyRepository;
import com.bigbrightpaints.erp.modules.company.service.TenantRuntimeEnforcementService;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.BadCredentialsException;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class AuthServiceAuditAttributionTest {

    @Mock
    private AuthenticationManager authenticationManager;
    @Mock
    private JwtTokenService tokenService;
    @Mock
    private RefreshTokenService refreshTokenService;
    @Mock
    private UserAccountRepository userAccountRepository;
    @Mock
    private CompanyRepository companyRepository;
    @Mock
    private JwtProperties properties;
    @Mock
    private MfaService mfaService;
    @Mock
    private PasswordService passwordService;
    @Mock
    private EmailService emailService;
    @Mock
    private TokenBlacklistService tokenBlacklistService;
    @Mock
    private AuditService auditService;
    @Mock
    private TenantRuntimeEnforcementService tenantRuntimeEnforcementService;

    private AuthService authService;

    @BeforeEach
    void setup() {
        authService = new AuthService(
                authenticationManager,
                tokenService,
                refreshTokenService,
                userAccountRepository,
                companyRepository,
                properties,
                mfaService,
                passwordService,
                emailService,
                tokenBlacklistService,
                auditService,
                tenantRuntimeEnforcementService);
    }

    @Test
    void loginAuthenticationFailure_usesTrimmedAuditIdentifiers() {
        LoginRequest request = new LoginRequest(" user@example.com ", "wrong-password", "  COMP-01  ", null, null);
        UserAccount user = new UserAccount("user@example.com", "hash", "User");
        user.setEnabled(true);
        when(userAccountRepository.findByEmailIgnoreCase(" user@example.com ")).thenReturn(Optional.of(user));
        when(authenticationManager.authenticate(any()))
                .thenThrow(new BadCredentialsException("bad credentials"));

        assertThatThrownBy(() -> authService.login(request))
                .isInstanceOf(BadCredentialsException.class);

        verify(auditService).logAuthFailure(
                eq(AuditEvent.LOGIN_FAILURE),
                eq("user@example.com"),
                eq("COMP-01"),
                argThat((Map<String, String> map) ->
                        map != null && "Authentication failed".equals(map.get("reason"))));
    }
}
