# R2 Checkpoint

## Scope
- Feature: `ERP-46 audit read hard-cut`
- Branch: `fix/audit-unification-hard-cut`
- Baseline head: `46f11fcb2a801517578182e0a4905ee5c5b5ba5b`
- Review candidate:
  - hard-cut tenant, tenant-admin, and superadmin audit reads onto explicit canonical controllers
  - merge `audit_logs` and enterprise business audit events behind `AuditAccessService`
  - remove retired audit-trail and audit-digest read paths plus dead scheduler/query code
  - keep accounting trail failures visible in the accounting feed and keep module filters semantically aligned
  - fail closed merged-feed paging with a bounded 5,000-row result window and overflow-safe page math
- Why this is R2: the branch changes live accounting, company/superadmin audit access paths and removes public endpoints. A wrong cut can hide accounting evidence, leak the wrong tenant/platform scope, or break audit review during incidents.

## Risk Trigger
- Triggered by:
  - `erp-domain/src/main/java/com/bigbrightpaints/erp/modules/accounting/**`
  - `erp-domain/src/main/java/com/bigbrightpaints/erp/modules/company/**`
  - `erp-domain/src/main/java/com/bigbrightpaints/erp/modules/admin/**`
- Contract surfaces affected:
  - tenant accounting event review
  - tenant-admin audit review
  - superadmin platform audit review
  - accounting transaction audit detail/provenance
  - audit paging and module-filter semantics
- Failure mode if wrong:
  - accounting audit readers could miss `INTEGRATION_FAILURE` markers for event-trail persistence failures
  - tenant-admin readers could receive `ACCOUNTING` rows while filtering for `BUSINESS`
  - oversized page values could crash merged audit feeds instead of failing closed
  - removed `/api/v1/accounting/audit-trail` or `/api/v1/accounting/audit/digest*` callers could silently drift back into frontend or integration usage
  - superadmin/platform review could regress back onto tenant-scoped surfaces

## Approval Authority
- Mode: human
- Approver: `ERP-46 owner`
- Canary owner: `ERP-46 owner`
- Approval status: `pending human review; PR #177 is the current candidate`
- Basis: this is a destructive audit-surface hard-cut across accounting and platform review paths, so technical green alone is not sufficient for deployment approval.

## Escalation Decision
- Human escalation required: yes
- Reason: the branch removes public audit routes and rewires tenant/platform visibility, so deployment should not rely on automated gate success alone.

## Rollback Owner
- Owner: `ERP-46 owner`
- Rollback method:
  - before merge: abandon `fix/audit-unification-hard-cut` and do not promote the artifact
  - after merge but before deploy: revert the audit hard-cut commits together; do not keep the controller/doc removals while dropping the access-layer fixes
  - after deploy: restore the last known-good pre-hard-cut backend if tenant/platform audit review is impaired
  - do not selectively reintroduce removed audit endpoints beside the canonical controllers
- Rollback trigger:
  - accounting users cannot see canonical audit evidence on `/api/v1/accounting/audit/events`
  - tenant-admin or superadmin review loses the intended scope boundary
  - merged feed paging or module filters produce wrong results or 5xx responses
  - downstream clients still depend on removed `/api/v1/accounting/audit-trail` or digest surfaces

## Expiry
- Valid until: `2026-04-05`
- Re-evaluate if: scope widens beyond ERP-46 audit hard-cut follow-ups, audit ownership changes again, or the approver/canary/rollback owners change.

## Verification Evidence
- Commands run:
  - `mvn -B -ntp --settings erp-domain/.mvn/settings.xml -f erp-domain/pom.xml -Dtest=AuditEventClassifierTest,AuditFeedFilterTest,AuditLogReadAdapterTest,DefaultAuditAccessServiceTest test`
  - `bash scripts/gate_fast.sh`
  - `bash ci/lint-knowledgebase.sh`
  - `bash ci/check-enterprise-policy.sh`
  - `bash ci/check-codex-review-guidelines.sh`
- Result summary:
  - targeted audit-access regression tests are green on the current branch head
  - `gate_fast.sh` passed locally after the latest audit-feed fixes
  - enterprise policy and codex review guideline checks passed locally after the checkpoint update was added to the branch diff
- Artifacts/links:
  - repo checkout: `/Users/anas/Documents/Factory/bigbrightpaints-erp_worktrees/audit-unification-hard-cut`
  - PR: `https://github.com/anasibnanwar-XYE/bigbrightpaints-erp/pull/177`
  - local gate artifacts: `artifacts/gate-fast/`
