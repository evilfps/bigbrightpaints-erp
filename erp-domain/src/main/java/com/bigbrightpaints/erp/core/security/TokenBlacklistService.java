package com.bigbrightpaints.erp.core.security;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Service for managing JWT token blacklist to support token revocation.
 * Tokens are blacklisted when users logout or when tokens need to be invalidated.
 *
 * TODO: For production, this should be replaced with Redis or a database-backed solution
 * to support distributed deployments and persistence across restarts.
 */
@Service
public class TokenBlacklistService {

    private static final Logger logger = LoggerFactory.getLogger(TokenBlacklistService.class);

    /**
     * Store for blacklisted tokens with their expiration time.
     * Key: JWT token ID (jti claim)
     * Value: Token expiration time
     */
    private final Map<String, Instant> blacklistedTokens = new ConcurrentHashMap<>();

    /**
     * Store for revoked user sessions.
     * Key: User ID
     * Value: Timestamp when all tokens were revoked for this user
     */
    private final Map<String, Instant> revokedUserSessions = new ConcurrentHashMap<>();

    /**
     * Adds a token to the blacklist.
     *
     * @param tokenId The JWT token ID (jti claim)
     * @param expirationTime The token's expiration time
     */
    public void blacklistToken(String tokenId, Instant expirationTime) {
        if (tokenId == null || expirationTime == null) {
            return;
        }

        // Only blacklist if the token hasn't expired yet
        if (expirationTime.isAfter(Instant.now())) {
            blacklistedTokens.put(tokenId, expirationTime);
            logger.info("Token blacklisted: {} (expires: {})", tokenId, expirationTime);
        }
    }

    /**
     * Revokes all tokens for a specific user.
     * This is useful when a user changes their password or when an account is compromised.
     *
     * @param userId The user ID
     */
    public void revokeAllUserTokens(String userId) {
        if (userId == null) {
            return;
        }

        revokedUserSessions.put(userId, Instant.now());
        logger.info("All tokens revoked for user: {}", userId);
    }

    /**
     * Checks if a token is blacklisted.
     *
     * @param tokenId The JWT token ID (jti claim)
     * @return true if the token is blacklisted, false otherwise
     */
    public boolean isTokenBlacklisted(String tokenId) {
        if (tokenId == null) {
            return false;
        }

        Instant expirationTime = blacklistedTokens.get(tokenId);
        if (expirationTime == null) {
            return false;
        }

        // Remove expired tokens from blacklist
        if (expirationTime.isBefore(Instant.now())) {
            blacklistedTokens.remove(tokenId);
            return false;
        }

        return true;
    }

    /**
     * Checks if a user's tokens issued before a certain time are revoked.
     *
     * @param userId The user ID
     * @param tokenIssuedAt When the token was issued
     * @return true if the token should be considered revoked, false otherwise
     */
    public boolean isUserTokenRevoked(String userId, Instant tokenIssuedAt) {
        if (userId == null || tokenIssuedAt == null) {
            return false;
        }

        Instant revocationTime = revokedUserSessions.get(userId);
        if (revocationTime == null) {
            return false;
        }

        // Token is revoked if it was issued before the revocation time
        return tokenIssuedAt.isBefore(revocationTime);
    }

    /**
     * Removes a specific token from the blacklist.
     * This might be used in special administrative scenarios.
     *
     * @param tokenId The JWT token ID (jti claim)
     */
    public void removeFromBlacklist(String tokenId) {
        if (tokenId != null) {
            blacklistedTokens.remove(tokenId);
            logger.info("Token removed from blacklist: {}", tokenId);
        }
    }

    /**
     * Clears revocation status for a user.
     *
     * @param userId The user ID
     */
    public void clearUserRevocation(String userId) {
        if (userId != null) {
            revokedUserSessions.remove(userId);
            logger.info("Revocation cleared for user: {}", userId);
        }
    }

    /**
     * Gets the number of blacklisted tokens.
     *
     * @return The count of blacklisted tokens
     */
    public int getBlacklistedTokenCount() {
        return blacklistedTokens.size();
    }

    /**
     * Gets the number of users with revoked sessions.
     *
     * @return The count of users with revoked sessions
     */
    public int getRevokedUserCount() {
        return revokedUserSessions.size();
    }

    /**
     * Scheduled task to clean up expired tokens from the blacklist.
     * Runs every hour to prevent memory leaks.
     */
    @Scheduled(fixedDelay = 3600000) // 1 hour
    public void cleanupExpiredTokens() {
        Instant now = Instant.now();
        int removedCount = 0;

        // Remove expired tokens
        var iterator = blacklistedTokens.entrySet().iterator();
        while (iterator.hasNext()) {
            var entry = iterator.next();
            if (entry.getValue().isBefore(now)) {
                iterator.remove();
                removedCount++;
            }
        }

        // Clean up old user revocations (older than 24 hours)
        Instant cutoff = now.minusSeconds(86400); // 24 hours
        var userIterator = revokedUserSessions.entrySet().iterator();
        int removedUsers = 0;
        while (userIterator.hasNext()) {
            var entry = userIterator.next();
            if (entry.getValue().isBefore(cutoff)) {
                userIterator.remove();
                removedUsers++;
            }
        }

        if (removedCount > 0 || removedUsers > 0) {
            logger.info("Cleanup completed - Removed {} expired tokens and {} old user revocations",
                       removedCount, removedUsers);
        }
    }

    /**
     * Clears all blacklisted tokens and revoked sessions.
     * This should only be used in testing or emergency scenarios.
     */
    public void clearAll() {
        blacklistedTokens.clear();
        revokedUserSessions.clear();
        logger.warn("All blacklisted tokens and revoked sessions cleared");
    }
}