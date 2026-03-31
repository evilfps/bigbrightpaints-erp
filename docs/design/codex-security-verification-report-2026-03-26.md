# Codex Security Verification Report - 2026-03-26

> Status: implemented on branch `packet/erp-39-hardcut-integration` via PR `#173`.

## Objective

Verify the 2026-03-26 security scanner exports against current ERP code, classify every exported row, and record the final disposition after the ERP-39 remediation waves.

## Source artifacts

The raw scanner exports are committed under `artifacts/security/`.

| File | Rows | SHA-256 |
|---|---:|---|
| `artifacts/security/codex-security-findings-2026-03-26T05-22-14.023Z.csv` | 4 | `d5dcb9832f2117b564398e8182eaa02ba559b16ca447129119f832e30dc333d1` |
| `artifacts/security/codex-security-findings-2026-03-26T05-22-33.894Z.csv` | 17 | `cdc9ed8ea289f5e1490dd77c56ffa366f94ae411d8236fb03e058a383670057c` |
| `artifacts/security/codex-security-findings-2026-03-26T05-23-09.603Z.csv` | 28 | `61489faa362d6c8752ca85fbe7efa417e2298636a49deb3f8bebcb20a3412f80` |

Total exported rows: `49`

## Verified baseline on fresh `origin/main`

The pre-remediation verification pass classified the 49 exported rows into these states:

| Baseline state | Count | Meaning |
|---|---:|---|
| `confirmed-current` | 40 | Verified live on then-current `origin/main` and required remediation |
| `stale-already-fixed` | 2 | Scanner row was stale relative to then-current `origin/main` |
| `historical-commit-scoped` | 3 | Historical or no longer current on then-current `origin/main` |
| `needs-deeper-repro` | 4 | Plausible issue, but code change required runtime proof first |

## Final disposition on `packet/erp-39-hardcut-integration`

The deeper-repro slice was executed test-first and all four ambiguous rows were resolved by proof. Two reproduced immediately as live defects, and two additional settlement-path candidates also reproduced once targeted tests were added.

Final disposition on this branch:

| Final state | Count | Notes |
|---|---:|---|
| Remediated on ERP-39 branch | 44 | 40 previously `confirmed-current` rows plus 4 deeper-repro rows that reproduced and were fixed |
| Already fixed on verified `origin/main` | 2 | Stayed non-current throughout ERP-39 execution |
| Historical or stale | 3 | Stayed non-current throughout ERP-39 execution |
| Remaining verified-current open rows on this branch | 0 | No verified-current export row remains unresolved on this branch |

## Exploit-family verification outcome

### Authz and tenant boundary

Verified and remediated on this branch:

- suspended-tenant authenticated runtime access
- inter-company reconciliation tenant binding
- catalog stock exposure to sales surfaces
- supplier bank-detail exposure
- dealer portal inactive mapping access
- export/download ownership enforcement
- public changelog anonymous exposure
- tenant onboarding company-code collision and runtime-policy sync gaps
- CORS private-network origin allowance
- dealer credit detail exposure on shared sales lookups

### Financial integrity and business-abuse hardening

Verified and remediated on this branch:

- opening-stock replay under new batch keys
- cash-mode and payment-mode credit bypasses
- credit/debit note over-crediting
- purchase return and settlement reference replay/provenance reuse
- closed-period landed cost and inventory revaluation mutation
- negative inventory revaluation sign handling
- partial variance under-allocation
- settlement replay on mismatched effective settlement date
- auto-settlement drift under changed resolved allocation state

### Platform and runtime hardening

Verified and remediated on this branch:

- release workflow push-to-main path
- prod actuator exposure posture
- STARTTLS enforcement
- schema drift allowlisting weakness
- predictable temp file handling
- unbounded audit/journal/history query surfaces
- shell execution through `bash -lc` in required checks
- verbose fallback error exposure

### Already non-current during verification

Rows verified as already fixed or stale on then-current `origin/main` stayed non-current throughout this packet. They were not “re-fixed” on this branch.

## Review of remaining fallback behavior in ERP-39 scope

The final ERP-39 review pass removed the last packet-local fail-open behavior and removed the onboarding runtime-policy actor helper that was using a system-thread fallback unnecessarily. The remaining packet paths are single-path and fail closed when required tenant-policy dependencies are absent.

## Conclusion

For the 2026-03-26 scanner export set, ERP-39 is fully dispositioned on this branch:

- every exported row has a recorded state
- every verified-current row has a branch-side remediation
- the deeper-repro-only rows were proven before code change
- no verified-current export row remains open on `packet/erp-39-hardcut-integration`
