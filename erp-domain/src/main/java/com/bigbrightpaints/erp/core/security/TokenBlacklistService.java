package com.bigbrightpaints.erp.core.security;

import com.bigbrightpaints.erp.modules.auth.domain.BlacklistedToken;
import com.bigbrightpaints.erp.modules.auth.domain.BlacklistedTokenRepository;
import com.bigbrightpaints.erp.modules.auth.domain.UserTokenRevocation;
import com.bigbrightpaints.erp.modules.auth.domain.UserTokenRevocationRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Optional;

/**
 * Service for managing JWT token blacklist to support token revocation.
 * Tokens are blacklisted when users logout or when tokens need to be invalidated.
 * 
 * This implementation uses database persistence for distributed deployments
 * and survives server restarts.
 */
@Service
public class TokenBlacklistService {

    private static final Logger logger = LoggerFactory.getLogger(TokenBlacklistService.class);

    private final BlacklistedTokenRepository blacklistedTokenRepository;
    private final UserTokenRevocationRepository userTokenRevocationRepository;
    private final JwtProperties jwtProperties;

    public TokenBlacklistService(BlacklistedTokenRepository blacklistedTokenRepository,
                                  UserTokenRevocationRepository userTokenRevocationRepository,
                                  JwtProperties jwtProperties) {
        this.blacklistedTokenRepository = blacklistedTokenRepository;
        this.userTokenRevocationRepository = userTokenRevocationRepository;
        this.jwtProperties = jwtProperties;
    }

    /**
     * Adds a token to the blacklist.
     *
     * @param tokenId The JWT token ID (jti claim)
     * @param expirationTime The token's expiration time
     */
    @Transactional
    public void blacklistToken(String tokenId, Instant expirationTime) {
        blacklistToken(tokenId, expirationTime, null, "logout");
    }

    @Transactional
    public void blacklistToken(String tokenId, Instant expirationTime, String userId, String reason) {
        if (tokenId == null || expirationTime == null) {
            return;
        }

        if (expirationTime.isAfter(Instant.now())) {
            if (!blacklistedTokenRepository.existsByTokenId(tokenId)) {
                BlacklistedToken token = new BlacklistedToken(tokenId, expirationTime, userId, reason);
                blacklistedTokenRepository.save(token);
                logger.info("Token blacklisted: {} (expires: {})", tokenId, expirationTime);
            }
        }
    }

    /**
     * Revokes all tokens for a specific user.
     * This is useful when a user changes their password or when an account is compromised.
     *
     * @param userId The user ID
     */
    @Transactional
    public void revokeAllUserTokens(String userId) {
        if (userId == null) {
            return;
        }

        Instant revokedAt = truncateToMillis(Instant.now());

        Optional<UserTokenRevocation> existing = userTokenRevocationRepository.findByUserId(userId);
        if (existing.isPresent()) {
            existing.get().setRevokedAt(revokedAt);
            userTokenRevocationRepository.save(existing.get());
        } else {
            UserTokenRevocation revocation = new UserTokenRevocation(userId, "all tokens revoked");
            revocation.setRevokedAt(revokedAt);
            userTokenRevocationRepository.save(revocation);
        }
        logger.info("All tokens revoked for user: {}", userId);
    }

    /**
     * Checks if a token is blacklisted.
     *
     * @param tokenId The JWT token ID (jti claim)
     * @return true if the token is blacklisted, false otherwise
     */
    @Transactional(readOnly = true)
    public boolean isTokenBlacklisted(String tokenId) {
        if (tokenId == null) {
            return false;
        }

        Optional<BlacklistedToken> token = blacklistedTokenRepository.findByTokenId(tokenId);
        if (token.isEmpty()) {
            return false;
        }

        return !token.get().isExpired();
    }

    /**
     * Checks if a user's tokens issued before a certain time are revoked.
     *
     * @param userId The user ID
     * @param tokenIssuedAt When the token was issued
     * @return true if the token should be considered revoked, false otherwise
     */
    @Transactional(readOnly = true)
    public boolean isUserTokenRevoked(String userId, Instant tokenIssuedAt) {
        if (userId == null || tokenIssuedAt == null) {
            return false;
        }

        Optional<UserTokenRevocation> revocation = userTokenRevocationRepository.findByUserId(userId);
        if (revocation.isEmpty()) {
            return false;
        }

        Instant normalizedIssuedAt = truncateToMillis(tokenIssuedAt);
        Instant normalizedRevokedAt = truncateToMillis(revocation.get().getRevokedAt());
        return !normalizedIssuedAt.isAfter(normalizedRevokedAt);
    }

    private Instant truncateToMillis(Instant instant) {
        if (instant == null) {
            return null;
        }
        return instant.truncatedTo(ChronoUnit.MILLIS);
    }

    /**
     * Removes a specific token from the blacklist.
     * This might be used in special administrative scenarios.
     *
     * @param tokenId The JWT token ID (jti claim)
     */
    @Transactional
    public void removeFromBlacklist(String tokenId) {
        if (tokenId != null) {
            blacklistedTokenRepository.findByTokenId(tokenId)
                    .ifPresent(blacklistedTokenRepository::delete);
            logger.info("Token removed from blacklist: {}", tokenId);
        }
    }

    /**
     * Clears revocation status for a user.
     *
     * @param userId The user ID
     */
    @Transactional
    public void clearUserRevocation(String userId) {
        if (userId != null) {
            userTokenRevocationRepository.findByUserId(userId)
                    .ifPresent(userTokenRevocationRepository::delete);
            logger.info("Revocation cleared for user: {}", userId);
        }
    }

    /**
     * Gets the number of blacklisted tokens.
     *
     * @return The count of blacklisted tokens
     */
    public long getBlacklistedTokenCount() {
        return blacklistedTokenRepository.count();
    }

    /**
     * Gets the number of users with revoked sessions.
     *
     * @return The count of users with revoked sessions
     */
    public long getRevokedUserCount() {
        return userTokenRevocationRepository.count();
    }

    /**
     * Scheduled task to clean up expired tokens from the blacklist.
     * Runs every hour to prevent database bloat.
     */
    @Scheduled(fixedDelay = 3600000) // 1 hour
    @Transactional
    public void cleanupExpiredTokens() {
        Instant now = Instant.now();

        int removedTokens = blacklistedTokenRepository.deleteExpiredTokens(now);

        // Keep user revocations at least as long as refresh tokens remain valid.
        Instant cutoff = now.minusSeconds(jwtProperties.getRefreshTokenTtlSeconds());
        int removedRevocations = userTokenRevocationRepository.deleteOldRevocations(cutoff);

        if (removedTokens > 0 || removedRevocations > 0) {
            logger.info("Cleanup completed - Removed {} expired tokens and {} old user revocations",
                       removedTokens, removedRevocations);
        }
    }

    /**
     * Clears all blacklisted tokens and revoked sessions.
     * This should only be used in testing or emergency scenarios.
     */
    @Transactional
    public void clearAll() {
        blacklistedTokenRepository.deleteAll();
        userTokenRevocationRepository.deleteAll();
        logger.warn("All blacklisted tokens and revoked sessions cleared");
    }
}
