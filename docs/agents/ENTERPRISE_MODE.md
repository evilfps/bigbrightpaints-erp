# Enterprise Mode Controls

Last reviewed: 2026-04-02

## What Enterprise Mode Controls

Enterprise mode refers to the set of runtime and governance controls that enforce production-grade safety for high-risk paths in the orchestrator-erp backend.

## High-Risk Change Classes

Changes under any of the following paths are classified as high-risk and subject to enterprise mode controls:

- `erp-domain/src/main/resources/db/migration_v2/`
- `erp-domain/src/main/java/com/bigbrightpaints/erp/modules/auth/`
- `erp-domain/src/main/java/com/bigbrightpaints/erp/modules/rbac/`
- `erp-domain/src/main/java/com/bigbrightpaints/erp/modules/company/`
- `erp-domain/src/main/java/com/bigbrightpaints/erp/modules/hr/`
- `erp-domain/src/main/java/com/bigbrightpaints/erp/modules/accounting/`
- `erp-domain/src/main/java/com/bigbrightpaints/erp/orchestrator/`

## Enterprise Mode Enforcement

### R2 Escalation

- High-risk packets must ship with an updated `docs/approvals/R2-CHECKPOINT.md`.
- The checkpoint must declare: scope, risk trigger, approval mode, escalation decision, rollback owner, expiry, and verification evidence.
- Compatibility-preserving remediation packets may use orchestrator approval.
- Widen-scope or destructive-risk changes require human escalation.

### Migration Guards

- Every `migration_v2` change must update `docs/runbooks/migrations.md` and `docs/runbooks/rollback.md`.
- `bash ci/check-enterprise-policy.sh` enforces this requirement at CI time.

### Test Requirements

- High-risk logic changes must include test modifications or an explicit test waiver in the R2 checkpoint.
- `bash ci/check-enterprise-policy.sh` validates test presence for high-risk changes.

### Review Requirements

- High-risk packets must pass `bash ci/check-codex-review-guidelines.sh` before review.
- Enterprise-risk packets must also pass `bash ci/check-enterprise-policy.sh`.

## Approval Modes

| Mode | When to Use | Who Approves |
| --- | --- | --- |
| Orchestrator | Compatibility-preserving remediation packets | Orchestrator agent |
| Human | Widen-scope, tenant boundary, or destructive migration risk | Human reviewer |

## Cross-references

- [docs/SECURITY.md](../SECURITY.md) — security review policy
- [docs/approvals/R2-CHECKPOINT.md](../approvals/R2-CHECKPOINT.md) — active R2 evidence
- [docs/approvals/R2-CHECKPOINT-TEMPLATE.md](../approvals/R2-CHECKPOINT-TEMPLATE.md) — R2 template
- [docs/agents/PERMISSIONS.md](PERMISSIONS.md) — agent permissions
- [docs/agents/WORKFLOW.md](WORKFLOW.md) — workflow governance
