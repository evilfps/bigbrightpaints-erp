package com.bigbrightpaints.erp.modules.company.domain;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;

import org.junit.jupiter.api.Test;

class TenantAdminEmailChangeRequestTest {

  @Test
  void prePersist_normalizesEmailsAndDefaultsAuditTimestamps() {
    TenantAdminEmailChangeRequest request = new TenantAdminEmailChangeRequest();
    request.setRequestedBy("  Super Admin  ");
    request.setCurrentEmail("  Old.Admin@Example.com ");
    request.setRequestedEmail("  New.Admin@Example.com ");
    request.setVerificationToken("  token-123  ");

    request.prePersist();

    assertThat(request.getRequestedBy()).isEqualTo("Super Admin");
    assertThat(request.getCurrentEmail()).isEqualTo("old.admin@example.com");
    assertThat(request.getRequestedEmail()).isEqualTo("new.admin@example.com");
    assertThat(request.getVerificationToken()).isEqualTo("token-123");
    assertThat(request.getVerificationSentAt()).isNotNull();
    assertThat(request.getExpiresAt())
        .isEqualTo(request.getVerificationSentAt().plusSeconds(86_400));
  }

  @Test
  void prePersist_usesFallbacksForBlankValuesAndPreservesExplicitTimestamps() {
    TenantAdminEmailChangeRequest request = new TenantAdminEmailChangeRequest();
    Instant sentAt = Instant.parse("2026-03-26T10:15:30Z");
    Instant expiresAt = Instant.parse("2026-03-28T10:15:30Z");
    request.setRequestedBy("   ");
    request.setCurrentEmail(null);
    request.setRequestedEmail("   ");
    request.setVerificationToken("   ");
    request.setVerificationSentAt(sentAt);
    request.setExpiresAt(expiresAt);
    request.setConsumed(true);

    request.prePersist();

    assertThat(request.getRequestedBy()).isEqualTo("UNKNOWN");
    assertThat(request.getCurrentEmail()).isNull();
    assertThat(request.getRequestedEmail()).isNull();
    assertThat(request.getVerificationToken()).isNull();
    assertThat(request.getVerificationSentAt()).isEqualTo(sentAt);
    assertThat(request.getExpiresAt()).isEqualTo(expiresAt);
    assertThat(request.isConsumed()).isTrue();
  }
}
