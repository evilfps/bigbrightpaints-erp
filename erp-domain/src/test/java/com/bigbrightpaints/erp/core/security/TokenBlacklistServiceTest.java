package com.bigbrightpaints.erp.core.security;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.bigbrightpaints.erp.modules.auth.domain.BlacklistedTokenRepository;
import com.bigbrightpaints.erp.modules.auth.domain.UserTokenRevocation;
import com.bigbrightpaints.erp.modules.auth.domain.UserTokenRevocationRepository;
import java.time.Instant;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class TokenBlacklistServiceTest {

    private BlacklistedTokenRepository blacklistedTokenRepository;
    private UserTokenRevocationRepository userTokenRevocationRepository;
    private JwtProperties jwtProperties;
    private TokenBlacklistService tokenBlacklistService;

    @BeforeEach
    void setUp() {
        blacklistedTokenRepository = mock(BlacklistedTokenRepository.class);
        userTokenRevocationRepository = mock(UserTokenRevocationRepository.class);
        jwtProperties = new JwtProperties();
        jwtProperties.setSecret("test-secret-should-be-at-least-32-bytes-long-1234567890");
        tokenBlacklistService = new TokenBlacklistService(
                blacklistedTokenRepository,
                userTokenRevocationRepository,
                jwtProperties);
    }

    @Test
    void isUserTokenRevokedTreatsSameMillisecondIssuanceAsRevoked() {
        UserTokenRevocation revocation = new UserTokenRevocation("user@example.com", "all tokens revoked");
        revocation.setRevokedAt(Instant.parse("2026-03-07T12:00:00.123Z"));
        when(userTokenRevocationRepository.findByUserId("user@example.com"))
                .thenReturn(Optional.of(revocation));

        boolean revoked = tokenBlacklistService.isUserTokenRevoked(
                "user@example.com",
                Instant.parse("2026-03-07T12:00:00.123999Z"));

        assertTrue(revoked);
    }

    @Test
    void isUserTokenRevokedAllowsTokensIssuedAfterRevocationMillisecond() {
        UserTokenRevocation revocation = new UserTokenRevocation("user@example.com", "all tokens revoked");
        revocation.setRevokedAt(Instant.parse("2026-03-07T12:00:00.123Z"));
        when(userTokenRevocationRepository.findByUserId("user@example.com"))
                .thenReturn(Optional.of(revocation));

        boolean revoked = tokenBlacklistService.isUserTokenRevoked(
                "user@example.com",
                Instant.parse("2026-03-07T12:00:00.124Z"));

        assertFalse(revoked);
    }
}
