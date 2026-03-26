package com.bigbrightpaints.erp.modules.company.domain;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.LinkedHashSet;
import java.util.Set;

import org.junit.jupiter.api.Test;

class CompanyTest {

  @Test
  void prePersist_normalizesTenantControlFieldsAndQuotas() {
    Company company = new Company();
    company.setEnabledModules(Set.of(" portal ", " purchasing "));
    company.setSupportTags(Set.of(" urgent ", "finance", " "));
    company.setSupportNotes("  needs help  ");
    company.setQuotaMaxActiveUsers(-1L);
    company.setQuotaMaxApiRequests(null);
    company.setQuotaMaxStorageBytes(512L);
    company.setQuotaMaxConcurrentRequests(null);
    company.setQuotaSoftLimitEnabled(false);
    company.setQuotaHardLimitEnabled(false);
    company.setOnboardingCoaTemplateCode("  sme ");
    company.setOnboardingAdminEmail("  Admin@Example.com ");
    company.prePersist();

    assertThat(company.getTimezone()).isEqualTo("UTC");
    assertThat(company.getLifecycleState()).isEqualTo(CompanyLifecycleState.ACTIVE);
    assertThat(company.getEnabledModules()).containsExactlyInAnyOrder("PORTAL", "PURCHASING");
    assertThat(company.getSupportTags()).containsExactlyInAnyOrder("URGENT", "FINANCE");
    assertThat(company.getSupportNotes()).isEqualTo("needs help");
    assertThat(company.getQuotaMaxActiveUsers()).isZero();
    assertThat(company.getQuotaMaxApiRequests()).isZero();
    assertThat(company.getQuotaMaxStorageBytes()).isEqualTo(512L);
    assertThat(company.getQuotaMaxConcurrentRequests()).isZero();
    assertThat(company.isQuotaHardLimitEnabled()).isTrue();
    assertThat(company.getOnboardingCoaTemplateCode()).isEqualTo("SME");
    assertThat(company.getOnboardingAdminEmail()).isEqualTo("admin@example.com");
    assertThat(company.getPublicId()).isNotNull();
  }

  @Test
  void setters_trimOptionalControlPlaneFieldsAndPreUpdateReappliesTagNormalization() {
    Company company = new Company();
    Instant completedAt = Instant.parse("2026-03-26T06:00:00Z");
    Instant emailedAt = Instant.parse("2026-03-26T06:30:00Z");
    company.setMainAdminUserId(91L);
    company.setSupportNotes("   ");
    company.setSupportTags(new LinkedHashSet<>(java.util.Arrays.asList(" ops ", null, "ops")));
    company.setOnboardingCoaTemplateCode("   ");
    company.setOnboardingAdminEmail("   ");
    company.setOnboardingAdminUserId(101L);
    company.setOnboardingCompletedAt(completedAt);
    company.setOnboardingCredentialsEmailedAt(emailedAt);
    company.preUpdate();

    assertThat(company.getMainAdminUserId()).isEqualTo(91L);
    assertThat(company.getSupportNotes()).isNull();
    assertThat(company.getSupportTags()).containsExactly("OPS");
    assertThat(company.getOnboardingCoaTemplateCode()).isNull();
    assertThat(company.getOnboardingAdminEmail()).isNull();
    assertThat(company.getOnboardingAdminUserId()).isEqualTo(101L);
    assertThat(company.getOnboardingCompletedAt()).isEqualTo(completedAt);
    assertThat(company.getOnboardingCredentialsEmailedAt()).isEqualTo(emailedAt);
  }
}
