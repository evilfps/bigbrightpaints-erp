# Security Review Policy

Last reviewed: 2026-03-29

## High-Risk Change Classes

- Changes under `erp-domain/src/main/resources/db/migration_v2/`
- Changes under `erp-domain/src/main/java/com/bigbrightpaints/erp/modules/auth/`
- Changes under `erp-domain/src/main/java/com/bigbrightpaints/erp/modules/rbac/`
- Changes under `erp-domain/src/main/java/com/bigbrightpaints/erp/modules/company/`
- Changes under `erp-domain/src/main/java/com/bigbrightpaints/erp/modules/hr/`
- Changes under `erp-domain/src/main/java/com/bigbrightpaints/erp/modules/accounting/`
- Changes under `erp-domain/src/main/java/com/bigbrightpaints/erp/orchestrator/`

## R2 Approval Workflow

- High-risk packets must ship with an updated `docs/approvals/R2-CHECKPOINT.md`.
- The checkpoint must declare the approval mode, escalation decision, rollback owner, expiry window, and concrete verification evidence.
- Compatibility-preserving remediation packets may use orchestrator approval; widen-scope or destructive-risk changes require human escalation.

## Review Validation Surface

- Docs-only review-policy packets validate with `bash ci/lint-knowledgebase.sh`.
- Runtime/config/schema/test-impacting packets validate through the PR ship-safety lane in `.github/workflows/ci.yml`.
- High-risk packets must also satisfy `High-Risk Change Control` / `bash ci/check-high-risk-changes.sh` and keep rollback notes current.
