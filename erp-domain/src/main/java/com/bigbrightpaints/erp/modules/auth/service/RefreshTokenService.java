package com.bigbrightpaints.erp.modules.auth.service;

import com.bigbrightpaints.erp.modules.auth.domain.RefreshToken;
import com.bigbrightpaints.erp.modules.auth.domain.RefreshTokenRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

@Service
public class RefreshTokenService {

    private static final Logger logger = LoggerFactory.getLogger(RefreshTokenService.class);

    private final RefreshTokenRepository refreshTokenRepository;

    public RefreshTokenService(RefreshTokenRepository refreshTokenRepository) {
        this.refreshTokenRepository = refreshTokenRepository;
    }

    @Transactional
    public String issue(String userEmail, Instant expiresAt) {
        return issue(userEmail, Instant.now(), expiresAt);
    }

    @Transactional
    public String issue(String userEmail, Instant issuedAt, Instant expiresAt) {
        String token = UUID.randomUUID().toString();
        RefreshToken record = RefreshToken.digestOnly(
                AuthTokenDigests.refreshTokenDigest(token),
                userEmail,
                issuedAt,
                expiresAt);
        refreshTokenRepository.save(record);
        return token;
    }

    @Transactional
    public Optional<TokenRecord> consume(String refreshToken) {
        if (refreshToken == null || refreshToken.isBlank()) {
            return Optional.empty();
        }
        String tokenDigest = AuthTokenDigests.refreshTokenDigest(refreshToken);
        Optional<RefreshToken> record = refreshTokenRepository.findForUpdateByTokenDigest(tokenDigest);
        if (record.isEmpty()) {
            return Optional.empty();
        }
        RefreshToken stored = record.get();
        if (stored.isExpired(Instant.now())) {
            refreshTokenRepository.delete(stored);
            return Optional.empty();
        }
        refreshTokenRepository.delete(stored);
        return Optional.of(new TokenRecord(stored.getUserEmail(), stored.getIssuedAt(), stored.getExpiresAt()));
    }

    @Transactional
    public void revoke(String refreshToken) {
        if (refreshToken == null || refreshToken.isBlank()) {
            return;
        }
        String tokenDigest = AuthTokenDigests.refreshTokenDigest(refreshToken);
        refreshTokenRepository.deleteByTokenDigest(tokenDigest);
    }

    @Transactional
    public void revokeAllForUser(String userEmail) {
        if (userEmail == null || userEmail.isBlank()) {
            return;
        }
        refreshTokenRepository.deleteByUserEmail(userEmail);
    }

    @Scheduled(fixedDelay = 3600000) // 1 hour
    @Transactional
    public void cleanupExpiredTokens() {
        int removed = refreshTokenRepository.deleteExpiredTokens(Instant.now());
        if (removed > 0) {
            logger.info("Refresh token cleanup removed {} expired tokens", removed);
        }
    }

    public record TokenRecord(String userEmail, Instant issuedAt, Instant expiresAt) {}
}
