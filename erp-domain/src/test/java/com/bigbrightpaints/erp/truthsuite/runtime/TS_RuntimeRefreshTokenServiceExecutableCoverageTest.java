package com.bigbrightpaints.erp.truthsuite.runtime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import com.bigbrightpaints.erp.core.idempotency.IdempotencyUtils;
import com.bigbrightpaints.erp.modules.auth.domain.RefreshToken;
import com.bigbrightpaints.erp.modules.auth.domain.RefreshTokenRepository;
import com.bigbrightpaints.erp.modules.auth.service.RefreshTokenService;

@Tag("critical")
class TS_RuntimeRefreshTokenServiceExecutableCoverageTest {

  @Test
  void consume_usesDigestLookupAndDeletesExpiredRecords_runtimeCoverage() {
    RefreshTokenRepository repository = mock(RefreshTokenRepository.class);
    RefreshTokenService service = new RefreshTokenService(repository);
    String rawToken = "expired-refresh-token";
    String digest = refreshTokenDigest(rawToken);
    RefreshToken stored =
        RefreshToken.digestOnly(
            digest,
            UUID.randomUUID(),
            "ACME",
            Instant.now().minusSeconds(120),
            Instant.now().minusSeconds(60));
    when(repository.findForUpdateByTokenDigest(digest)).thenReturn(Optional.of(stored));

    assertThat(service.consume(rawToken)).isEmpty();

    verify(repository).findForUpdateByTokenDigest(digest);
    verify(repository).delete(stored);
  }

  @Test
  void revoke_deletesByDigest_runtimeCoverage() {
    RefreshTokenRepository repository = mock(RefreshTokenRepository.class);
    RefreshTokenService service = new RefreshTokenService(repository);
    String rawToken = "active-refresh-token";
    String digest = refreshTokenDigest(rawToken);

    service.revoke(rawToken);

    verify(repository).deleteByTokenDigest(digest);
  }

  private String refreshTokenDigest(String token) {
    return IdempotencyUtils.sha256Hex("refresh-token:" + token);
  }
}
