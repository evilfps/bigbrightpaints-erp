# Codex Security Scan Issue Backlog - 2026-03-26

> Status: execution backlog closed on branch `packet/erp-39-hardcut-integration`.

## Grouped remediation packets

ERP-39 intentionally grouped the 49 exported rows into a small execution backlog instead of one issue per row.

| Packet | Scope | Final status |
|---|---|---|
| Authz and tenant-boundary hardening | tenant access control, portal/catalog exposure, supplier details, export ownership, policy sync | Closed on branch |
| Financial integrity and business-abuse hardening | replay, provenance binding, credit-limit enforcement, closed-period mutation, settlement correctness | Closed on branch |
| Platform/runtime hardening | workflow/config posture, prod exposure, query pressure, shell execution, error redaction | Closed on branch |
| Deeper-repro packet | negative revaluation sign, settlement audit leakage, settlement replay date guard, auto-settlement drift | Closed on branch after proof |

## Packet closure summary

### Authz and tenant-boundary hardening

Closed by the integrated ERP-39 branch. This packet covered the verified-current tenant and authorization findings and left no verified-current row open.

### Financial integrity and business-abuse hardening

Closed by the integrated ERP-39 branch. This packet covered the verified-current finance and abuse-path findings and the two settlement-path proof-first fixes that reproduced.

### Platform/runtime hardening

Closed by the integrated ERP-39 branch. This packet covered the verified-current workflow/config/runtime items relevant to the 2026-03-26 export.

### Deeper-repro packet

Closed by proof-first execution:

- negative revaluation sign bug reproduced and was fixed
- settlement audit idempotency leakage was live and was fixed
- settlement-date replay mismatch reproduced and was fixed
- auto-settlement drift under changed allocation state reproduced and was fixed

## Remaining ERP-39 backlog

None on this branch for the 2026-03-26 export set.

The remaining work after ERP-39 is operational, not backlog-class remediation:

- keep PR `#173` green through final review/merge
- merge/rebase onto mainline when approved
- deploy using the documented migration and rollback runbooks

These are release steps, not unresolved scanner findings.
