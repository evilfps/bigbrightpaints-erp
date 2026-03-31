# Codex Security Scan Remediation Report - 2026-03-26

> Status: integrated on branch `packet/erp-39-hardcut-integration`, PR `#173`.

## Remediation strategy

ERP-39 was executed as grouped exploit-family packets instead of row-by-row cleanup:

1. authz and tenant-boundary hardening
2. financial integrity and business-abuse hardening
3. platform/runtime hardening
4. deeper-repro proof-first fixes

The packet stayed hard-cut throughout: one canonical current-state implementation, no compatibility bridges, no soft fallback behavior, and no dual-mode legacy paths introduced as part of the security fix.

## What changed

### Wave 1: initial hard-cut perimeter

Representative integrated commits:

- `3ba9d329` `Harden ERP-39 canonical auth and integrity flows`
- `cfb03e6e` `Simplify ERP-39 follow-up checks and import flow`

Key outcomes:

- suspended tenants fail closed for authenticated runtime access
- sales catalog views no longer disclose stock
- supplier bank details are redacted on read for unauthorized roles
- reconciliation requires correct tenant participation
- opening-stock replay is blocked by canonical content fingerprint
- prod actuator and workflow/config surfaces were tightened
- audit queries, error handling, and command execution paths were hardened

### Wave 2-3: auth, finance, and config backlog closure

Representative integrated commits:

- `0798135c` auth/policy hardening integration
- `a201cfec` financial abuse hardening
- `db4a8bc5` finance proof alignment
- `7700a0e4` export ownership and dealer credit redaction
- `dcc8f4d3` batched dealer exposure simplify follow-up

Key outcomes:

- tenant bootstrap/update/onboarding now synchronize runtime policy canonically
- inactive dealer portal mappings hard-fail
- dealer credit amount fields were removed from shared sales lookup payloads
- export downloads are owner-only
- note, settlement, purchase-return, and closed-period financial abuse paths fail closed
- local defaults, compose posture, and agent network defaults were hardened

### Deeper-repro wave

Representative integrated commits:

- `02a210d4` signed revaluation fix
- `75bd72f2` settlement audit leak removal
- `504ff529` settlement-date replay conflict fix
- `2454e0ff` auto-settlement drift fix
- `28d43192` integration proof alignment

Key outcomes:

- negative inventory revaluations now stay negative through posting
- raw settlement idempotency keys are gone from audit detail and linked-reference outputs
- same key plus different effective settlement date is now a hard conflict
- implicit auto-settlement replay identity is now derived from resolved allocation state

### Final cleanup after review and CI

Representative integrated commits:

- `8c11df83` changed-files coverage proof completion
- `af6bfc3e` automated review finding cleanup
- `67539267` tenant bootstrap defaults simplification
- `1a134d7c` fail-closed tenant policy dependency enforcement
- `eb187575` truthsuite alignment plus onboarding actor cleanup

Key outcomes:

- the last changed-files coverage gate passed without widening runtime behavior
- tenant runtime-policy paths no longer silently continue when required services are absent
- onboarding runtime-policy bootstrap uses the canonical actor resolution path already expected by the tests
- auth/tenant truthsuite coverage now matches the hard-cut dependency model instead of the deleted fallback path

## Validation summary

The branch was validated incrementally and then rechecked on GitHub Actions. Representative proof commands used during the packet included:

- focused `mvn` suites for auth/tenant, accounting, dealer/supplier, and purchasing slices
- `scripts/run_test_manifest.sh` against CI shard manifests
- `python3 scripts/changed_files_coverage.py ...`
- `python3 -m unittest testing.ci.test_harness_orchestrator`
- `bash scripts/guard_accounting_portal_scope_contract.sh`
- `bash scripts/guard_audit_trail_ownership_contract.sh`
- `git diff --check`

The final PR head for this report is expected to be fully green before merge; the report documents the code-side closure and proof structure for the ERP-39 remediation set.

## Contract impact summary

Important intentional contract changes from ERP-39:

- journal listing is paginated
- shared dealer lookup payloads no longer expose exact `creditLimit` / `outstandingBalance`
- settlement audit detail no longer exposes raw client idempotency keys
- same settlement key with a different effective date is now a conflict
- negative inventory revaluation requests now produce actual write-down behavior

## Hard-cut decisions recorded

- no compatibility bridges were introduced for old tenant policy or settlement behavior
- ambiguous findings were not changed until runtime proof existed
- where dependencies became mandatory for secure behavior, code was changed to fail closed instead of silently continuing
