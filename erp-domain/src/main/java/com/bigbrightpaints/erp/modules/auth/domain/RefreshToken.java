package com.bigbrightpaints.erp.modules.auth.domain;

import jakarta.persistence.*;

import java.time.Instant;

@Entity
@Table(name = "refresh_tokens", indexes = {
    @Index(name = "idx_refresh_tokens_token_digest", columnList = "token_digest"),
    @Index(name = "idx_refresh_tokens_user_email", columnList = "user_email"),
    @Index(name = "idx_refresh_tokens_expires_at", columnList = "expires_at")
})
public class RefreshToken {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "token", length = 255)
    private String token;

    @Column(name = "token_digest", length = 64)
    private String tokenDigest;

    @Column(name = "user_email", nullable = false, length = 255)
    private String userEmail;

    @Column(name = "issued_at", nullable = false)
    private Instant issuedAt;

    @Column(name = "expires_at", nullable = false)
    private Instant expiresAt;

    protected RefreshToken() {
    }

    public RefreshToken(String token, String userEmail, Instant issuedAt, Instant expiresAt) {
        this(token, null, userEmail, issuedAt, expiresAt);
    }

    private RefreshToken(String token, String tokenDigest, String userEmail, Instant issuedAt, Instant expiresAt) {
        this.token = token;
        this.tokenDigest = tokenDigest;
        this.userEmail = userEmail;
        this.issuedAt = issuedAt;
        this.expiresAt = expiresAt;
    }

    public static RefreshToken digestOnly(String tokenDigest, String userEmail, Instant issuedAt, Instant expiresAt) {
        return new RefreshToken(null, tokenDigest, userEmail, issuedAt, expiresAt);
    }

    public Long getId() {
        return id;
    }

    public String getToken() {
        return token;
    }

    public String getTokenDigest() {
        return tokenDigest;
    }

    public String getUserEmail() {
        return userEmail;
    }

    public Instant getIssuedAt() {
        return issuedAt;
    }

    public Instant getExpiresAt() {
        return expiresAt;
    }

    public boolean isExpired(Instant now) {
        return expiresAt.isBefore(now);
    }

    public void migrateToDigest(String newTokenDigest) {
        if (token == null || token.isBlank() || tokenDigest != null) {
            return;
        }
        this.tokenDigest = newTokenDigest;
        this.token = null;
    }
}
