# R2 Slice 2 Evidence Bundle

Captured on: 2026-04-16
Branch: `codex/tenant-admin-hardcut-s1`

## Commands

1. `cd erp-domain && MIGRATION_SET=v2 mvn -q -Dtest=RequestBodyCachingFilterTest,CompanyContextFilterControlPlaneBindingTest,AuthTenantAuthorityIT#tenant_scoped_super_admin_cannot_access_platform_only_superadmin_hosts test`
2. `bash ci/check-enterprise-policy.sh`
3. `bash ci/check-codex-review-guidelines.sh`

## Result anchors

- `com.bigbrightpaints.erp.core.security.RequestBodyCachingFilterTest.txt` -> `Tests run: 6, Failures: 0, Errors: 0, Skipped: 0`
- `com.bigbrightpaints.erp.modules.auth.CompanyContextFilterControlPlaneBindingTest.txt` -> `Tests run: 19, Failures: 0, Errors: 0, Skipped: 0`
- `com.bigbrightpaints.erp.modules.auth.AuthTenantAuthorityIT.txt` -> `Tests run: 1, Failures: 0, Errors: 0, Skipped: 0`
- `TEST-com.bigbrightpaints.erp.modules.auth.AuthTenantAuthorityIT.xml` includes testcase `tenant_scoped_super_admin_cannot_access_platform_only_superadmin_hosts`.
- `check-enterprise-policy.txt` ends with `[enterprise-policy] OK`.
- `check-codex-review-guidelines.txt` ends with `[codex-review-guidelines] OK`.

## SHA-256

- `9bd2556502e3cdd0a1163bfbac4af5886b0d362eb0d1433b34c84b0c4de330b9` `check-codex-review-guidelines.txt`
- `b77449a75ea328df9d7dfe4bd82d670cb7c3d095bd4f0cf82b628a9d06d7cf82` `check-enterprise-policy.txt`
- `c668d01b9c0c432d19b51ea3d820dce1819e27bc9f161da5feb5548b56e6e77a` `com.bigbrightpaints.erp.core.security.RequestBodyCachingFilterTest.txt`
- `b410522b450e0862d6b08d473fbaf1367afa3d17153749ed00283df4d08d9fdb` `com.bigbrightpaints.erp.modules.auth.CompanyContextFilterControlPlaneBindingTest.txt`
- `dde787e409ea3b057c4cb3bd0b644bbef289d39ed59f9c01dae9a84112e46bc5` `com.bigbrightpaints.erp.modules.auth.AuthTenantAuthorityIT.txt`
- `a73183516a8b7179d55fcba9a3ff6275d1cbb75a4eee98210fbe12b280bc0cd1` `TEST-com.bigbrightpaints.erp.modules.auth.AuthTenantAuthorityIT.xml`
