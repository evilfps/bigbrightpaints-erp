# Review Evidence

ticket: TKT-ERP-STAGE-111
slice: SLICE-01
reviewer: security-governance
status: approved-with-followup

## Findings
- LOW: role hierarchy (`ROLE_SUPER_ADMIN > ROLE_ADMIN`) is global by design and must remain explicitly governed/documented.
- LOW: `SecurityConfig` contains non-blocking unmapped structural lines in changed-files coverage output.

## Evidence
- commands:
  - `bash ci/check-architecture.sh` -> OK
  - `bash ci/check-enterprise-policy.sh` -> OK
  - `cd erp-domain && mvn -B -ntp -Dtest=CompanyServiceTest,CompanyControllerIT,AdminUserSecurityIT,AuthTenantAuthorityIT test` -> BUILD SUCCESS (Tests run: 70, Failures: 0, Errors: 0)
  - `python3 scripts/changed_files_coverage.py --jacoco erp-domain/target/site/jacoco/jacoco.xml --diff-base origin/tickets/tkt-erp-stage-111/auth-rbac-company-prod-fix` -> passes: true (line_ratio=1.0, branch_ratio=1.0)
- artifacts:
  - Security review note with low-risk governance follow-up captured.
  - Regression matrix evidence captured in surefire reports for the listed suites.
