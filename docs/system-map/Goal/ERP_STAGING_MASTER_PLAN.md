# ERP Staging Master Plan (Final Stability Plan)

Last reviewed: 2026-02-18
Owner: Orchestrator Agent
Status: Active

This is the final execution plan for getting this ERP stable and staging-ready.
Focus is stability, architecture cleanup, usability, reliability, security, and workflow unification.
Feature expansion is not the goal unless required to remove workflow risk.

## 1) Plan Intent
- Deliver one enterprise-grade ERP core that is stable, deployable, and safe for accounting.
- Review all important workflows end-to-end and redesign only where flows are messy, unsafe, duplicated, or confusing.
- Keep the virtual accountant as the long-term vision, but complete core ERP stabilization first.

## 2) Explicit Operating Rules
- Selective redesign rule:
  - understand current workflow first,
  - refactor only when evidence shows risk, duplication, drift, or usability failure.
- Stability-first rule:
  - no speculative feature work,
  - no broad rewrites without measurable gain.
- Docs-only review rule (explicit):
  - for docs-only commits, skip `codex review --commit` and skip review subagent runs.
  - still run `bash ci/lint-knowledgebase.sh` before commit.
- Code-risk review rule:
  - for any runtime/config/schema/test logic change, run commit review + reviewer subagent.
- Lane alignment rule:
  - `fast_lane` applies to docs-only commits and uses only the docs-only review rule above.
  - `strict_lane` applies to accounting, auth/RBAC, migrations, orchestrator semantics, and any runtime/config/schema/test logic change.
  - `strict_lane` minimum harness and review pack:
    - `bash ci/lint-knowledgebase.sh`
    - `bash ci/check-architecture.sh`
    - `bash ci/check-enterprise-policy.sh`
    - `bash ci/check-orchestrator-layer.sh`
    - `bash scripts/verify_local.sh`
    - commit review + one reviewer subagent
- Review-queue saturation rule:
  - if reviewer subagent dispatch is blocked by agent-cap limits, continue bounded slices with full proof (targeted tests + required gates),
  - record blocked review queue in `asyncloop` after each slice,
  - retry reviewer dispatch each cycle,
  - do not mark async-loop final closure complete until queued code commits receive reviewer outcomes.
- Proof rule:
  - every “done” claim requires commands + outputs recorded in `asyncloop`.

## 3) Architecture Improvement Program

### 3.1 Target architecture outcomes
- Single canonical write path per business event.
- Strict module boundaries for accounting, inventory, sales, purchasing, payroll, and orchestrator.
- Consistent idempotency and reference strategy across modules.
- Unified domain vocabulary for external entities:
  - use `partner` as the canonical cross-module term,
  - use `dealer` and `supplier` only for role-specific flows.
- v2 contract stability:
  - keep external API/DTO field names and endpoint semantics role-specific (`dealer*`, `supplier*`),
  - reserve `partner*` for internal abstractions and shared metadata keys,
  - defer any public rename to a future explicit versioned contract migration.
- API contracts treated as product assets, not byproducts.

### 3.2 Required architecture deliverables
- Canonical write-path registry by workflow.
- Duplicate endpoint/service map with closure decision:
  - keep as alias,
  - merge to canonical,
  - prod-gate,
  - deprecate.
- Cross-module linkage contract map kept current and tested.
- Canonical terminology dictionary for contracts, errors, logs, and docs.

## 4) Multi-Tenant SaaS Model (Enterprise Requirement)

### 4.1 Tenant model
- ERP must support multiple companies/tenants on one deployment with strict isolation.
- All business rows must be tenant-scoped and fail-closed on missing/mismatched tenant context.
- No cross-tenant reads/writes from any role below superadmin.

### 4.2 Role model
- `SUPER_ADMIN`:
  - platform-level actor,
  - can create/manage tenants,
  - cannot silently perform tenant business postings without explicit tenant context.
- `ADMIN`:
  - tenant-internal role only,
  - cannot create other tenants,
  - manages users, approvals, settings inside own tenant.
- Enforce by service-layer checks, not controller-only checks.

### 4.4 Superadmin platform control plane (mandatory)
Superadmin must have tenant-level operational controls:
- Observability metrics per tenant:
  - active users,
  - current concurrent sessions/requests,
  - DB/storage usage,
  - API consumption and error-rate summary.
- Quota and usage controls:
  - set/increase/decrease user limits,
  - set/increase/decrease usage limits (API/storage/concurrency as defined by policy tier),
  - soft-limit warnings and hard-limit enforcement rules.
- Enforcement controls:
  - hold/block tenant state with reason code and audit trail,
  - unblock/resume with controlled workflow and audit trail,
  - emergency tenant throttle mode for abuse/incident containment.
- Governance:
  - all superadmin control actions are immutable-audited,
  - no tenant admin can override superadmin platform limits.

### 4.3 Tenant acceptance criteria
- Header/JWT/company mismatch always fails closed.
- Tenant bootstrap and tenant admin creation are superadmin-only.
- Cross-tenant IDOR matrix tests pass for critical APIs.
- Superadmin metrics and limit controls are available and tested.
- Hold/block and quota changes are enforced at runtime and audit-linked.

## 5) Workflow Unification Program (All Major Flows)

### 5.1 Workflow census and review
Review each workflow with this method:
1. map current states and transitions,
2. identify duplicate paths and ambiguous ownership,
3. map accounting impact and drift risk,
4. decide keep/refactor/remove,
5. lock with tests and contracts.

### 5.2 Workflows in mandatory scope
- O2C: dealer -> order -> reservation -> dispatch -> invoice -> AR -> settlement.
- P2P: supplier -> PO -> GRN -> invoice -> AP -> settlement/payment.
- Production: production log -> packing -> finished goods -> dispatch cost linkage.
- Payroll: run -> post -> payment -> liability clearing.
- Returns/reversals for sales and purchasing.
- Period close/reopen/reporting.
- Approval chains for overrides and exceptions.

### 5.3 Cross-module unification criteria
- Every transition has a canonical owner service.
- No status-only update path can bypass financial truth.
- Replay/retry across modules is deterministic and idempotent.

## 6) Accounting Excellence Program

### 6.1 Hard accounting invariants
- Double-entry always balanced.
- Control accounts reconcile with subledgers.
- Period-close boundaries are enforced and fail-closed.
- Reversal chains are explicit and linked.
- Posting references are deterministic and collision-safe.

### 6.2 Usability-focused accounting redesign
- Guided accounting operations for non-experts:
  - intent-first UI/API,
  - pre-post journal preview,
  - clear warnings before risky changes,
  - reason codes for overrides/reversals.
- Reduce manual ambiguity in settlement, adjustments, and returns.

### 6.3 Drift prevention
- Expand drift sentinels for:
  - unlinked docs,
  - duplicate allocations/postings,
  - closed-period drift,
  - inventory-vs-GL mismatch.
- Treat sentinel findings as deployment blockers.

## 7) Tax Program: GST and Non-GST

### 7.1 Tax-flow requirements
- Support both GST and non-GST company modes.
- Deterministic tax decisioning per tenant and per transaction type.
- Clear tax treatment for sales, purchases, returns, and adjustments.

### 7.2 GST deliverables for staging readiness
- GST master data and validation hardening.
- GST posting line consistency with invoice and returns.
- Reconciliation outputs for GST liability and claimability.

### 7.3 External GST integration roadmap (SAP-like target)
- Stage 1: reliable export-ready filing artifacts and API abstraction.
- Stage 2: provider adapter integration for return upload/status retrieval.
- Stage 3: controlled auto-filing/claims with human approval and audit chain.

## 8) Payment and Settlement Program

### 8.1 Split payments
- Support split settlements/payments with strict allocation rules.
- Prevent over-allocation and duplicate application on retries.
- Ensure split allocation has deterministic ordering and replay behavior.

### 8.2 Acceptance criteria
- Same `idempotencyKey` + same payload returns same result.
- Same key + different payload conflicts cleanly.
- Outstanding values never become inconsistent/negative due to replay races.

## 9) Product and Variant Program (Human-Friendly)

### 9.1 Variant model
- Product template + variant dimensions for color/size/pack.
- Deterministic SKU creation and uniqueness.
- Bulk variant generation/edit with validation preview.

### 9.2 Operational usability
- Non-technical users can add products/variants without manual repair.
- Required accounting and inventory mappings validated before publish.
- Variant lifecycle links cleanly into purchasing, production, sales, and accounting.

## 10) Approval and Override Hardening

### 10.1 Approval domains
- Dealer credit and exceptions.
- Supplier exceptions and settlement overrides.
- Manual accounting adjustments and reversals.
- Discount/price/tax override flows.

### 10.2 Hardening rules
- Define approval policy matrix by role and threshold.
- Enforce maker-checker where risk is high.
- All overrides require reason code and immutable audit metadata.

## 11) Sales Target Governance Fix
- Salesperson cannot self-assign or self-approve own targets.
- Tenant admin assigns targets person-wise within same tenant.
- Target changes are audited and optionally approval-gated by policy.

Acceptance criteria:
- Role checks prevent self-target abuse.
- Target assignment APIs require admin authority in tenant scope.
- Reporting reflects assigned targets and fulfillment truthfully.

## 12) API Contract Program (Frontend JSON First)

### 12.1 Contract requirements
- JSON request/response contracts versioned and published from OpenAPI.
- Error model standardized (`401`, `403`, `404`, `409`, `422`, `500`).
- Idempotency and pagination semantics explicit for every write/list endpoint.

### 12.2 Frontend documentation requirements
- Per-portal endpoint map with role matrix.
- Contract examples for common and error flows.
- Breaking-change policy with migration guidance.

### 12.3 Acceptance criteria
- Contract drift checks block undocumented changes.
- Frontend docs generated/refreshed from canonical spec + policy overlays.

## 13) Enterprise Security Control Program
- RBAC and tenant isolation fail-closed semantics.
- Sensitive endpoint minimization and hardened actuator/docs exposure.
- Secret/config validation for production safety.
- PII-safe audit logging and payload redaction.
- Security regression matrix for auth, access, tenant boundaries, and IDOR.

## 14) Reliability and Staging Readiness Program

### 14.1 Reliability requirements
- Deterministic retries and outbox behavior.
- Clear health and readiness signals for integrations.
- Queue/retry/dead-letter visibility and alarms.

### 14.2 Staging gate requirements
- All required CI lanes green on same SHA.
- Flyway v2 drift/overlap scans clean.
- Migration matrix fresh+upgrade rehearsed.
- Predeploy scans clean.
- Rollback drill executed and documented.

### 14.3 Async-loop final ledger gate closure protocol
1. Select an immutable `RELEASE_ANCHOR_SHA` immediately before the active hardening train.
2. Run strict `gate_fast` with anchor:
   - `DIFF_BASE=<RELEASE_ANCHOR_SHA> GATE_FAST_RELEASE_VALIDATION_MODE=true bash scripts/gate_fast.sh`
3. Run remaining ledger gates on the same `HEAD` SHA:
   - `bash scripts/gate_core.sh`
   - `bash scripts/gate_reconciliation.sh`
   - `bash scripts/gate_release.sh`
4. Store command outputs and artifact paths in `asyncloop`.
5. Rotate `RELEASE_ANCHOR_SHA` only after all required ledger gates pass and evidence is recorded.

### 14.4 Reviewer queue saturation checkpoint
When reviewer-agent capacity is externally saturated:
1. Keep code slices small and revert to strongest local proof for each slice (targeted tests minimum; lane gates as required by risk).
2. Append queue status + blocked reason in `asyncloop` with pending commit SHAs.
3. Retry reviewer-subagent dispatch at least once each loop cycle.
4. Before final staging closure, ensure every queued code commit has reviewer outcome (findings or explicit no-findings).

## 15) Virtual Accountant Vision (Deferred Until Stable Base)
This remains strategic vision after stabilization:
- NLP/LLM parses user intent,
- outputs structured accounting-intent JSON,
- system simulates outcome first,
- human approves,
- ERP executes via canonical APIs,
- full audit chain preserved.

Current rule:
- no autonomous AI posting to ledger until base stabilization gates are complete.

## 16) Execution Phases and Slice Queue

### Phase A: Foundation (now)
- finalize architecture map and workflow census.
- establish tenant/superadmin authority boundaries.
- enforce docs-only review-skip policy in operating runbooks.

### Phase B: Core risk closure
- O2C/P2P/production/payroll duplicate-path and idempotency closure.
- approvals and override hardening.
- split payment and settlement race safety.

### Phase C: Data and contract hardening
- GST/non-GST flow hardening.
- API contract and frontend JSON documentation maturity.
- variant SKU lifecycle hardening.

### Phase D: Staging readiness
- full gate and rehearsal package.
- security and reliability hardening pass.
- go/no-go evidence pack.

### Phase E: Virtual-accountant readiness
- intent schema + simulation + approval chain contracts.

## 17) Definition of Done (Staging Ready)
- No unresolved P0 accounting/security/tenant-isolation blockers.
- Canonical write paths and duplicate-closure decisions implemented for major workflows.
- Multi-tenant + superadmin model enforced at service and persistence boundaries.
- GST/non-GST and split-payment flows validated with tests and reconciliation checks.
- Approval and override controls hardened with auditability.
- API contracts and frontend docs synchronized and versioned.
- Staging gate bundle green with rollback evidence.
- Final ledger-gate evidence (`gate_fast/core/reconciliation/release`) captured in `asyncloop` with a fixed anchor SHA.

## 18) Immediate M18 Queue (Stability-Only)
- `M18-S1`: docs-only review skip policy + runbook alignment.
- `M18-S2`: multi-tenant authority model (SUPER_ADMIN vs ADMIN) and tenant bootstrap hardening.
- `M18-S2A`: superadmin control plane (tenant metrics, quota tuning, hold/block, runtime enforcement).
- `M18-S3`: workflow census + duplicate-path decisions for O2C/P2P/production/payroll.
- `M18-S4`: approval/override policy matrix hardening across dealer/supplier/accounting.
- `M18-S5`: split-payment and settlement idempotency/race matrix closure.
- `M18-S6`: GST/non-GST posting and reconciliation design hardening (stage-1 integration readiness).
- `M18-S7`: product template + color/size variant workflow stabilization.
- `M18-S8`: admin-assigned sales-target governance fix.
- `M18-S9`: JSON API contract maturity + frontend docs parity gates.
- `M18-S10`: staging rehearsal evidence pack and rollback drill closure.
