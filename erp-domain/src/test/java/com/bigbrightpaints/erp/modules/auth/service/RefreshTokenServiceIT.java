package com.bigbrightpaints.erp.modules.auth.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import com.bigbrightpaints.erp.modules.auth.domain.RefreshToken;
import com.bigbrightpaints.erp.modules.auth.domain.RefreshTokenRepository;
import com.bigbrightpaints.erp.test.AbstractIntegrationTest;

class RefreshTokenServiceTest extends AbstractIntegrationTest {

  @Autowired private RefreshTokenService refreshTokenService;
  @Autowired private RefreshTokenRepository refreshTokenRepository;

  @BeforeEach
  void clearTokens() {
    refreshTokenRepository.deleteAll();
  }

  @Test
  void issue_persists_digest_only_consume_removes_and_replay_is_rejected() {
    UUID userPublicId = UUID.randomUUID();
    Instant expiresAt = Instant.now().plusSeconds(300);
    String token = refreshTokenService.issue(userPublicId, "ACME", expiresAt);

    RefreshToken stored = refreshTokenRepository.findAll().getFirst();
    assertThat(stored.getToken()).isNull();
    assertThat(stored.getTokenDigest()).isNotNull();
    assertThat(stored.getUserPublicId()).isEqualTo(userPublicId);
    assertThat(stored.getAuthScopeCode()).isEqualTo("ACME");

    RefreshTokenService.TokenRecord record = refreshTokenService.consume(token).orElseThrow();
    assertThat(record.userPublicId()).isEqualTo(userPublicId);
    assertThat(record.authScopeCode()).isEqualTo("ACME");
    assertThat(record.expiresAt()).isAfterOrEqualTo(expiresAt.minusSeconds(1));
    assertThat(record.expiresAt()).isBeforeOrEqualTo(expiresAt.plusSeconds(1));
    assertThat(refreshTokenService.consume(token)).isEmpty();
    assertThat(refreshTokenRepository.findAll()).isEmpty();
  }

  @Test
  void consume_expired_token_returns_empty_and_deletes() {
    UUID userPublicId = UUID.randomUUID();
    Instant issuedAt = Instant.now().minusSeconds(120);
    Instant expiredAt = Instant.now().minusSeconds(60);
    String rawToken = "expired-token";
    RefreshToken token =
        RefreshToken.digestOnly(
            AuthTokenDigests.refreshTokenDigest(rawToken),
            userPublicId,
            "ACME",
            issuedAt,
            expiredAt);
    refreshTokenRepository.save(token);

    assertThat(refreshTokenService.consume(rawToken)).isEmpty();
    assertThat(
            refreshTokenRepository.findByTokenDigest(AuthTokenDigests.refreshTokenDigest(rawToken)))
        .isEmpty();
  }

  @Test
  void revokeAllForUser_removes_only_matching_public_id() {
    UUID targetUser = UUID.randomUUID();
    UUID otherUser = UUID.randomUUID();
    refreshTokenService.issue(targetUser, "ACME", Instant.now().plusSeconds(300));
    refreshTokenService.issue(targetUser, "BBB", Instant.now().plusSeconds(300));
    refreshTokenService.issue(otherUser, "ACME", Instant.now().plusSeconds(300));

    refreshTokenService.revokeAllForUser(targetUser);

    assertThat(refreshTokenRepository.findAll())
        .extracting(RefreshToken::getUserPublicId)
        .containsExactly(otherUser);
  }

  @Test
  void blankAndNullTokens_areIgnoredByConsumeAndRevoke() {
    assertThat(refreshTokenService.consume(null)).isEmpty();
    assertThat(refreshTokenService.consume(" ")).isEmpty();

    refreshTokenService.revoke(null);
    refreshTokenService.revoke(" ");
    refreshTokenService.revokeAllForUser(null);

    assertThat(refreshTokenRepository.findAll()).isEmpty();
  }
}
