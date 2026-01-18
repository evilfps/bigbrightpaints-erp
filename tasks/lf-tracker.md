# LF Tracker (derived from commit messages + evidence folders)

This file is a lightweight index of LF work based on commit messages and
evidence artifacts. It does not guarantee a fix unless a validating test or
explicit completion note exists.

## Summary
- Confirmed complete: LF-19 (see `HYDRATION.md`).
- Likely addressed (commit messages): LF-011..LF-017, LF-021..LF-023.
- Evidence-only (no fix commit found): LF-001, LF-007, LF-008, LF-009.

## Tracker

| LF | Evidence folder | Commit signals | Status |
| --- | --- | --- | --- |
| LF-001 | `tasks/erp_logic_audit/EVIDENCE_QUERIES/lf-001` | Audit log company resolution + auth login audit events; `AuthAuditIT` | Fixed (pending commit) |
| LF-007 | `tasks/erp_logic_audit/EVIDENCE_QUERIES/lf-007` | Payroll idempotency scoped per company; `PayrollRunIdempotencyIT` | Fixed (pending commit) |
| LF-008 | `tasks/erp_logic_audit/EVIDENCE_QUERIES/lf-008` | none found in commit messages | Evidence-only |
| LF-009 | `tasks/erp_logic_audit/EVIDENCE_QUERIES/lf-009` | none found in commit messages | Evidence-only |
| LF-011 | none | `73afde4` Fix LF-011 GST config health and validation; `a863715` LF-011..LF-015: mark fixed and log gates | Likely fixed |
| LF-012 | none | `229354a` Fix LF-012 WIP posting cost; `a863715` LF-011..LF-015: mark fixed and log gates | Likely fixed |
| LF-013 | none | `235b80f` Fix LF-013 packing status refresh; `a863715` LF-011..LF-015: mark fixed and log gates | Likely fixed |
| LF-014 | none | `896192c` Fix LF-014 null discount default in catalog; `a863715` LF-011..LF-015: mark fixed and log gates | Likely fixed |
| LF-015 | none | `8a82fc1` Fix LF-015 production log list/detail lazy load; `a863715` LF-011..LF-015: mark fixed and log gates | Likely fixed |
| LF-016 | none | `483e4bd`, `5929520`, `7b64620`, `3d3e661`, `5f96acf`, `d666318`, `f207e5f`, `d5f28f5` | Likely fixed |
| LF-017 | none | `483e4bd`, `5929520`, `7b64620`, `3d3e661`, `5f96acf`, `d666318`, `f207e5f`, `d5f28f5` | Likely fixed |
| LF-018 | none | `e7c3147` lead-004-007-017: confirm to lf-018-020 | Needs confirmation |
| LF-019 | none | `HYDRATION.md` marks complete on `pr-coverage-lf-clean` | Confirmed complete |
| LF-020 | none | `51823b6` Update hydration for LF-020; `e7c3147` confirm to lf-018-020 | Needs confirmation |
| LF-021 | none | `b74e8ff` Fix LF-021 opening stock GL posting; `d66fbb1`, `979a0fe`, `08650b0` evidence notes | Likely fixed |
| LF-022 | none | `0c2e3b4` Fix LF-022 purchase return idempotency; `ae1f8aa` doc confirmations | Likely fixed |
| LF-023 | none | `6a98027` Fix LF-023 idempotency conflict handling; `ae1f8aa` doc confirmations | Likely fixed |

## Notes
- Evidence-only means SQL/curl outputs exist but no fix commit was found by message scan.
- "Likely fixed" should be validated by tests or updated completion notes if needed.
