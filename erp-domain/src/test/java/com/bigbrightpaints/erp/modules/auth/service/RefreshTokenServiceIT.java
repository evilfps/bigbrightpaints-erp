package com.bigbrightpaints.erp.modules.auth.service;

import com.bigbrightpaints.erp.modules.auth.domain.RefreshToken;
import com.bigbrightpaints.erp.modules.auth.domain.RefreshTokenRepository;
import com.bigbrightpaints.erp.test.AbstractIntegrationTest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

class RefreshTokenServiceTest extends AbstractIntegrationTest {

    @Autowired
    private RefreshTokenService refreshTokenService;

    @Autowired
    private RefreshTokenRepository refreshTokenRepository;

    @BeforeEach
    void clearTokens() {
        refreshTokenRepository.deleteAll();
    }

    @Test
    void issue_persists_digest_only_consume_removes_and_replay_is_rejected() {
        Instant expiresAt = Instant.now().plusSeconds(300);
        String token = refreshTokenService.issue("user@example.com", expiresAt);

        RefreshToken stored = refreshTokenRepository.findAll().getFirst();
        assertThat(stored.getToken()).isNull();
        assertThat(stored.getTokenDigest()).isNotNull();

        RefreshTokenService.TokenRecord record = refreshTokenService.consume(token).orElseThrow();
        assertThat(record.userEmail()).isEqualTo("user@example.com");
        assertThat(record.expiresAt()).isAfterOrEqualTo(expiresAt.minusSeconds(1));
        assertThat(record.expiresAt()).isBeforeOrEqualTo(expiresAt.plusSeconds(1));
        assertThat(refreshTokenService.consume(token)).isEmpty();
        assertThat(refreshTokenRepository.findAll()).isEmpty();
    }

    @Test
    void consume_expired_token_returns_empty_and_deletes() {
        Instant issuedAt = Instant.now().minusSeconds(120);
        Instant expiredAt = Instant.now().minusSeconds(60);
        RefreshToken token = new RefreshToken("expired-token", "user@example.com", issuedAt, expiredAt);
        refreshTokenRepository.save(token);

        assertThat(refreshTokenService.consume("expired-token")).isEmpty();
        assertThat(refreshTokenRepository.findByToken("expired-token")).isEmpty();
    }

    @Test
    void consume_accepts_legacy_raw_token_during_transition() {
        Instant issuedAt = Instant.now().minusSeconds(30);
        Instant expiresAt = Instant.now().plusSeconds(300);
        refreshTokenRepository.save(new RefreshToken("legacy-token", "legacy@example.com", issuedAt, expiresAt));

        RefreshTokenService.TokenRecord record = refreshTokenService.consume("legacy-token").orElseThrow();

        assertThat(record.userEmail()).isEqualTo("legacy@example.com");
        assertThat(refreshTokenRepository.findAll()).isEmpty();
    }

    @Test
    void backfillLegacyTokens_migrates_plaintext_rows_to_digest_only_storage() {
        Instant issuedAt = Instant.now().minusSeconds(30);
        Instant expiresAt = Instant.now().plusSeconds(300);
        RefreshToken legacy = refreshTokenRepository.save(
                new RefreshToken("legacy-token", "legacy@example.com", issuedAt, expiresAt));

        int migrated = refreshTokenService.backfillLegacyTokens();

        assertThat(migrated).isEqualTo(1);
        RefreshToken stored = refreshTokenRepository.findById(legacy.getId()).orElseThrow();
        assertThat(stored.getToken()).isNull();
        assertThat(stored.getTokenDigest()).isEqualTo(AuthTokenDigests.refreshTokenDigest("legacy-token"));

        RefreshTokenService.TokenRecord record = refreshTokenService.consume("legacy-token").orElseThrow();
        assertThat(record.userEmail()).isEqualTo("legacy@example.com");
        assertThat(refreshTokenRepository.findAll()).isEmpty();
    }

    @Test
    void revokeAllForUser_removes_user_tokens_only() {
        refreshTokenService.issue("user@example.com", Instant.now().plusSeconds(300));
        refreshTokenService.issue("User@Example.com", Instant.now().plusSeconds(300));
        refreshTokenService.issue("other@example.com", Instant.now().plusSeconds(300));

        refreshTokenService.revokeAllForUser("USER@example.com");

        assertThat(refreshTokenRepository.findAll())
                .allMatch(record -> record.getUserEmail().equalsIgnoreCase("other@example.com"));
    }
}
