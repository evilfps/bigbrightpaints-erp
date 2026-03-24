package com.bigbrightpaints.erp.modules.auth.domain;

import com.bigbrightpaints.erp.core.domain.VersionedEntity;
import jakarta.persistence.*;

import java.time.Instant;

@Entity
@Table(name = "password_reset_tokens", indexes = {
    @Index(name = "idx_password_reset_tokens_token_digest", columnList = "token_digest")
})
public class PasswordResetToken extends VersionedEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(optional = false, fetch = FetchType.LAZY)
    @JoinColumn(name = "user_id", nullable = false)
    private UserAccount user;

    @Column(name = "token", length = 255)
    private String token;

    @Column(name = "token_digest", length = 64)
    private String tokenDigest;

    @Column(name = "expires_at", nullable = false)
    private Instant expiresAt;

    @Column(name = "used_at")
    private Instant usedAt;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "delivered_at")
    private Instant deliveredAt;

    protected PasswordResetToken() {
    }

    public PasswordResetToken(UserAccount user, String token, Instant expiresAt) {
        this(user, token, null, expiresAt);
    }

    private PasswordResetToken(UserAccount user, String token, String tokenDigest, Instant expiresAt) {
        this.user = user;
        this.token = token;
        this.tokenDigest = tokenDigest;
        this.expiresAt = expiresAt;
        this.createdAt = Instant.now();
    }

    public static PasswordResetToken digestOnly(UserAccount user, String tokenDigest, Instant expiresAt) {
        return new PasswordResetToken(user, null, tokenDigest, expiresAt);
    }

    public Long getId() {
        return id;
    }

    public UserAccount getUser() {
        return user;
    }

    public String getToken() {
        return token;
    }

    public String getTokenDigest() {
        return tokenDigest;
    }

    public Instant getExpiresAt() {
        return expiresAt;
    }

    public Instant getUsedAt() {
        return usedAt;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getDeliveredAt() {
        return deliveredAt;
    }

    public void markUsed() {
        this.usedAt = Instant.now();
    }

    public void markDelivered(Instant deliveredAt) {
        if (deliveredAt == null) {
            return;
        }
        this.deliveredAt = deliveredAt;
    }

    public boolean isExpired(Instant now) {
        return expiresAt.isBefore(now);
    }

    public boolean isUsed() {
        return usedAt != null;
    }

    public void migrateToDigest(String newTokenDigest) {
        if (token == null || token.isBlank() || tokenDigest != null) {
            return;
        }
        this.tokenDigest = newTokenDigest;
        this.token = null;
    }
}
