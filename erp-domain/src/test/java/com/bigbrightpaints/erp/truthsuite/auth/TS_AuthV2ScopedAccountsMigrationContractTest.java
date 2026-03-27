package com.bigbrightpaints.erp.truthsuite.auth;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import com.bigbrightpaints.erp.truthsuite.support.TruthSuiteFileAssert;

@Tag("critical")
class TS_AuthV2ScopedAccountsMigrationContractTest {

  private static final String V2_MIGRATION =
      "src/main/resources/db/migration_v2/V168__auth_v2_scoped_accounts.sql";

  @Test
  void v2MigrationNormalizesEmailsBeforeScopedUniqueness() {
    TruthSuiteFileAssert.assertContains(
        V2_MIGRATION,
        "SET email = LOWER(TRIM(email))",
        "GROUP BY LOWER(TRIM(email)), auth_scope_code",
        "Resolve duplicate emails before rerunning migration.");
    TruthSuiteFileAssert.assertContainsInOrder(
        V2_MIGRATION,
        "DROP CONSTRAINT IF EXISTS app_users_email_key;",
        "SET email = LOWER(TRIM(email));",
        "GROUP BY LOWER(TRIM(email)), auth_scope_code",
        "ADD CONSTRAINT uq_app_users_email_scope UNIQUE (email, auth_scope_code);");
  }

  @Test
  void v2MigrationRejectsPlatformScopeCodeCollisionWithTenantCompanyCodes() {
    TruthSuiteFileAssert.assertContains(
        V2_MIGRATION,
        "FROM companies",
        "UPPER(TRIM(code)) = platform_scope_code",
        "Auth V2 hard cut requires auth.platform.code to stay unique from tenant company codes.");
    TruthSuiteFileAssert.assertContainsInOrder(
        V2_MIGRATION,
        "FROM system_settings",
        "FROM companies",
        "UPPER(TRIM(code)) = platform_scope_code",
        "Rename the platform auth code or the conflicting company code before rerunning migration.");
  }

  @Test
  void v2MigrationFailsRefreshTokenBackfillWhenLegacyEmailIsAmbiguousAcrossScopes() {
    TruthSuiteFileAssert.assertContains(
        V2_MIGRATION,
        "GROUP BY LOWER(TRIM(rt.user_email))",
        "HAVING COUNT(DISTINCT u.id) > 1",
        "Auth V2 hard cut found ambiguous legacy refresh tokens after scoped auth split.",
        "u.email = LOWER(TRIM(rt.user_email))");
    TruthSuiteFileAssert.assertContainsInOrder(
        V2_MIGRATION,
        "ADD COLUMN IF NOT EXISTS user_public_id UUID,",
        "GROUP BY LOWER(TRIM(rt.user_email))",
        "HAVING COUNT(DISTINCT u.id) > 1",
        "UPDATE refresh_tokens rt",
        "u.email = LOWER(TRIM(rt.user_email));");
  }
}
