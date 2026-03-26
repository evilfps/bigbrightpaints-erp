package com.bigbrightpaints.erp.modules.company.domain;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;

import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

class TenantSupportWarningTest {

  @Test
  void prePersist_defaultsMissingFieldsFailClosed() {
    Company company = new Company();
    ReflectionTestUtils.setField(company, "id", 77L);
    company.setTimezone("UTC");
    TenantSupportWarning warning = new TenantSupportWarning();
    warning.setCompany(company);
    warning.setWarningCategory("  ");
    warning.setRequestedLifecycleState(null);
    warning.setIssuedBy("   ");
    warning.setGracePeriodHours(0);

    warning.prePersist();

    assertThat(warning.getWarningCategory()).isEqualTo("GENERAL");
    assertThat(warning.getRequestedLifecycleState()).isEqualTo("SUSPENDED");
    assertThat(warning.getIssuedBy()).isEqualTo("UNKNOWN");
    assertThat(warning.getGracePeriodHours()).isEqualTo(24);
    assertThat(warning.getIssuedAt()).isNotNull();
  }

  @Test
  void prePersist_normalizesExplicitFieldsAndKeepsProvidedInstant() {
    TenantSupportWarning warning = new TenantSupportWarning();
    Instant issuedAt = Instant.parse("2026-03-26T08:00:00Z");
    warning.setWarningCategory(" finance ");
    warning.setRequestedLifecycleState(" deactivated ");
    warning.setIssuedBy("  ops@bbp.com ");
    warning.setGracePeriodHours(72);
    warning.setIssuedAt(issuedAt);

    warning.prePersist();

    assertThat(warning.getWarningCategory()).isEqualTo("FINANCE");
    assertThat(warning.getRequestedLifecycleState()).isEqualTo("DEACTIVATED");
    assertThat(warning.getIssuedBy()).isEqualTo("ops@bbp.com");
    assertThat(warning.getGracePeriodHours()).isEqualTo(72);
    assertThat(warning.getIssuedAt()).isEqualTo(issuedAt);
  }
}
