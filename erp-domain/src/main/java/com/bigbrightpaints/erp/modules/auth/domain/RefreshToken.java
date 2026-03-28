package com.bigbrightpaints.erp.modules.auth.domain;

import java.time.Instant;
import java.util.UUID;

import jakarta.persistence.*;

@Entity
@Table(
    name = "refresh_tokens",
    indexes = {
      @Index(name = "idx_refresh_tokens_token_digest", columnList = "token_digest"),
      @Index(name = "idx_refresh_tokens_user_public_id", columnList = "user_public_id"),
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

  @Column(name = "user_public_id", nullable = false)
  private UUID userPublicId;

  @Column(name = "auth_scope_code", nullable = false, length = 64)
  private String authScopeCode;

  @Column(name = "issued_at", nullable = false)
  private Instant issuedAt;

  @Column(name = "expires_at", nullable = false)
  private Instant expiresAt;

  protected RefreshToken() {}

  public RefreshToken(
      String token,
      String tokenDigest,
      UUID userPublicId,
      String authScopeCode,
      Instant issuedAt,
      Instant expiresAt) {
    this.token = token;
    this.tokenDigest = tokenDigest;
    this.userPublicId = userPublicId;
    this.authScopeCode = authScopeCode;
    this.issuedAt = issuedAt;
    this.expiresAt = expiresAt;
  }

  public static RefreshToken digestOnly(
      String tokenDigest,
      UUID userPublicId,
      String authScopeCode,
      Instant issuedAt,
      Instant expiresAt) {
    return new RefreshToken(null, tokenDigest, userPublicId, authScopeCode, issuedAt, expiresAt);
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

  public UUID getUserPublicId() {
    return userPublicId;
  }

  public String getAuthScopeCode() {
    return authScopeCode;
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
