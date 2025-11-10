package com.bigbrightpaints.erp.modules.auth.service;

import com.bigbrightpaints.erp.core.security.JwtProperties;
import com.bigbrightpaints.erp.core.security.JwtTokenService;
import com.bigbrightpaints.erp.modules.auth.domain.UserAccount;
import com.bigbrightpaints.erp.modules.auth.domain.UserAccountRepository;
import com.bigbrightpaints.erp.modules.auth.domain.UserPrincipal;
import com.bigbrightpaints.erp.modules.auth.web.AuthResponse;
import com.bigbrightpaints.erp.modules.auth.web.LoginRequest;
import com.bigbrightpaints.erp.modules.auth.web.RefreshTokenRequest;
import com.bigbrightpaints.erp.modules.company.domain.Company;
import com.bigbrightpaints.erp.modules.company.domain.CompanyRepository;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.HashMap;
import java.util.Map;

@Service
public class AuthService {

    private final AuthenticationManager authenticationManager;
    private final JwtTokenService tokenService;
    private final RefreshTokenService refreshTokenService;
    private final UserAccountRepository userAccountRepository;
    private final CompanyRepository companyRepository;
    private final JwtProperties properties;
    private final MfaService mfaService;

    public AuthService(AuthenticationManager authenticationManager,
                       JwtTokenService tokenService,
                       RefreshTokenService refreshTokenService,
                       UserAccountRepository userAccountRepository,
                       CompanyRepository companyRepository,
                       JwtProperties properties,
                       MfaService mfaService) {
        this.authenticationManager = authenticationManager;
        this.tokenService = tokenService;
        this.refreshTokenService = refreshTokenService;
        this.userAccountRepository = userAccountRepository;
        this.companyRepository = companyRepository;
        this.properties = properties;
        this.mfaService = mfaService;
    }

    public AuthResponse login(LoginRequest request) {
        Authentication authentication = authenticationManager.authenticate(
                new UsernamePasswordAuthenticationToken(request.email(), request.password()));
        UserPrincipal principal = (UserPrincipal) authentication.getPrincipal();
        Company company = resolveCompanyForUser(principal.getUser(), request.companyCode());
        mfaService.verifyDuringLogin(principal.getUser(), request.mfaCode(), request.recoveryCode());
        Map<String, Object> claims = new HashMap<>();
        claims.put("name", principal.getUser().getDisplayName());
        String accessToken = tokenService.generateAccessToken(principal.getUsername(), company.getCode(), claims);
        String refreshToken = refreshTokenService.issue(principal.getUsername(),
                Instant.now().plusSeconds(properties.getRefreshTokenTtlSeconds()));
        return new AuthResponse("Bearer", accessToken, refreshToken, properties.getAccessTokenTtlSeconds(),
                company.getCode(), principal.getUser().getDisplayName());
    }

    public AuthResponse refresh(RefreshTokenRequest request) {
        String userEmail = refreshTokenService.consume(request.refreshToken())
                .orElseThrow(() -> new IllegalArgumentException("Invalid refresh token"));
        UserAccount user = userAccountRepository.findByEmailIgnoreCase(userEmail)
                .orElseThrow(() -> new IllegalArgumentException("User not found"));
        Company company = resolveCompanyForUser(user, request.companyCode());
        Map<String, Object> claims = Map.of("name", userEmail);
        String accessToken = tokenService.generateAccessToken(userEmail, company.getCode(), claims);
        String refreshToken = refreshTokenService.issue(userEmail,
                Instant.now().plusSeconds(properties.getRefreshTokenTtlSeconds()));
        return new AuthResponse("Bearer", accessToken, refreshToken, properties.getAccessTokenTtlSeconds(),
                company.getCode(), userEmail);
    }

    private Company resolveCompanyForUser(UserAccount user, String companyCode) {
        Company company = companyRepository.findByCodeIgnoreCase(companyCode)
                .orElseThrow(() -> new IllegalArgumentException("Company not found: " + companyCode));
        boolean member = user.getCompanies().stream()
                .anyMatch(c -> c.getCode().equalsIgnoreCase(companyCode));
        if (!member) {
            throw new IllegalArgumentException("User not assigned to company: " + companyCode);
        }
        return company;
    }

    public void logout(String refreshToken) {
        if (refreshToken != null) {
            refreshTokenService.revoke(refreshToken);
        }
    }
}
