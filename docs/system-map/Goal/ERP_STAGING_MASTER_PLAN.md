# ERP Staging Master Plan (Final Stability Plan)

Last reviewed: 2026-02-17
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

### 14.3 Async-loop final ledger gate closure protocol (Stage-065 one-SHA contract)
1. Refresh to integration `HEAD` and pin:
   - `RELEASE_HEAD_SHA=$(git rev-parse HEAD)`
   - immutable `RELEASE_ANCHOR_SHA` immediately before the active hardening train.
2. Run strict `gate_fast` with anchor; release validation mode must fail closed on vacuous changed-file coverage:
   - `DIFF_BASE=<RELEASE_ANCHOR_SHA> GATE_FAST_RELEASE_VALIDATION_MODE=true bash scripts/gate_fast.sh`
   - Record the resulting `artifacts/gate-fast/changed-coverage.json` showing `"vacuous": false` as part of the closure evidence.
3. Run remaining ledger gates without changing `HEAD`:
   - `bash scripts/gate_core.sh`
   - `bash scripts/gate_reconciliation.sh`
   - `bash scripts/gate_release.sh`
4. Enforce runtime quarantine contract checks before accepting `gate_reconciliation`/`gate_release` closure evidence:
   - `scripts/test_quarantine.txt` entries use: `<test_path> | owner=<owner> | repro=<repro> | start=YYYY-MM-DD | expiry=YYYY-MM-DD`.
   - Required keys are `owner`, `repro`, `start`, and `expiry`.
   - `expiry` must be `>= start` and `<= start + 14 calendar days`; missing/invalid/expired metadata fails closed and blocks Section 14.3 closure.
5. Store command outputs and artifact paths in `asyncloop`, including per-gate one-SHA proof fields:
   - `gate_name`
   - `head_sha_before` and `head_sha_after`
   - `exit_code`
6. Fail closed if any recorded SHA differs from `RELEASE_HEAD_SHA`; mixed-SHA evidence is invalid for Stage-065 closure.
7. Rotate `RELEASE_ANCHOR_SHA` only after all required ledger gates pass and one-SHA evidence is recorded.
8. Operators must mirror the detailed checklist in `docs/ASYNC_LOOP_OPERATIONS.md#section-14.3-final-gate-protocol-stage-065-one-sha-closure` so the runbook and plan stay lockstep.
9. Enforce ticket closure metadata parity before marking tickets done:
   - run `python3 scripts/check_ticket_status_parity.py` (or `bash ci/lint-knowledgebase.sh`) so `ticket.yaml`, `SUMMARY.md`, and `TIMELINE.md` cannot drift.

### 14.4 Reviewer queue saturation checkpoint
When reviewer-agent capacity is externally saturated:
1. Keep code slices small and revert to strongest local proof for each slice (targeted tests minimum; lane gates as required by risk).
2. Append queue status + blocked reason in `asyncloop` with pending commit SHAs.
3. Retry reviewer-subagent dispatch at least once each loop cycle.
4. Before final staging closure, ensure every queued code commit has reviewer outcome (findings or explicit no-findings).

### 14.5 Deterministic high-signal verify strategy (codex-exec)
- Reliability objective: reduce flake/time overhead by defaulting to the smallest fail-closed verify lane.
- Lane model:
  - `docs_lane`: `bash ci/lint-knowledgebase.sh` for docs-only slices.
  - `fast_lane`: `bash scripts/gate_fast.sh` for default bounded code slices.
  - `strict_lane`: `bash scripts/gate_fast.sh` then `bash scripts/gate_core.sh` for accounting/auth/migration/orchestrator semantics.
  - `ledger_lane`: Section 14.3 full anchored ledger gate sequence on one SHA.
- Promotion/demotion rules:
  - promote only when risk scope or failing signal requires stronger proof,
  - do not downgrade after a failure without explicit evidence-backed scope reduction,
  - keep quarantine/flake checks fail-closed (no ad-hoc retry bypass).

### 14.6 Autonomous operator workflow (codex-exec)
1. Pin `VERIFY_HEAD_SHA` and lane base (`DIFF_BASE` or `RELEASE_ANCHOR_SHA`) before running any lane command.
2. Execute lane commands in deterministic order with no duplicate reruns on unchanged `HEAD`.
3. Record command list, exit outcomes, and artifact paths in `asyncloop` for every run.
4. On repeated failure at unchanged `HEAD`, fail closed, log blocker evidence, and escalate via `R2`.
5. Keep runbook and plan synchronized by following `docs/ASYNC_LOOP_OPERATIONS.md` Section 14.4/14.5.
6. Before marking a slice done, run `bash ci/lint-knowledgebase.sh` so ticket metadata parity remains enforced.

### 14.7 Merge-ready ticket sequencing and deployment-gate discipline
For integration PR and merge-queue posture:
1. Sequence merge-ready tickets by dependency and risk ordering, not by local completion order.
2. Require integration-base freshness before merge-ready promotion (fetch + rebase/merge onto current integration branch) and rerun required lane checks on refreshed `HEAD`.
3. Treat shared-path conflicts as fail-closed blockers until resolved on latest base with explicit evidence (conflict paths + upstream SHA).
4. Invalidate pre-conflict gate evidence; only post-resolution `HEAD` gate outputs are admissible.
5. Accept Section 14.3 final closure only from integration `HEAD` after merge sequencing is complete and recorded in `asyncloop`.

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

## 19) Active Ticket Ledger (Orchestrator)
- 2026-02-16: `TKT-ERP-STAGE-001` merged into `harness-engineering-orchestrator` (M18-S2 and M18-S8 baseline hardening).
- 2026-02-16: `TKT-ERP-STAGE-002` merged into `harness-engineering-orchestrator` with full strict-lane proof (`verify_local` green on commit `9dab6f5b`) for M18-S4 closure slice.
- 2026-02-16: `TKT-ERP-STAGE-005` merged into `harness-engineering-orchestrator` (`2005bfc3`) for M18-S2A tenant hold/block runtime enforcement with overlap arbitration (`SLICE-02` dropped, `SLICE-01` canonicalized).
- 2026-02-16: `TKT-ERP-STAGE-006` merged into `harness-engineering-orchestrator` (`cccfc824`, `196954a7`) for M18-S3A canonical workflow decision registry + CI guard enforcement (`scripts/guard_workflow_canonical_paths.sh` integrated into `ci/check-enterprise-policy.sh`).
- 2026-02-17: `TKT-ERP-STAGE-007` merged into `harness-engineering-orchestrator` (`e2600104`, `28ab4f55`) for M18-S6A GST/non-GST drift-guard closure across purchasing and accounting with strict-lane proof (`verify_local` + targeted truthsuite checks).
- 2026-02-17: `TKT-ERP-STAGE-008` merged into `harness-engineering-orchestrator` (`fb9690f7`, `0fbfae28`, `089a393d`) for M18-S9A OpenAPI drift + portal endpoint-map parity closure across release-ops and docs lanes.
- 2026-02-17: `TKT-ERP-STAGE-009` merged into `harness-engineering-orchestrator` (`cc9e64ce`, `cdec6d60`) for M18-S10A rollback rehearsal evidence closure (release gate traceability manifest + migration matrix/rollback artifacts + runbook evidence protocol).
- 2026-02-17: `TKT-ERP-STAGE-010` merged into `harness-engineering-orchestrator` (`e7f2dac3`, `af055f29`) as targeted GST truth-contract unblocker to remove brittle variable-name coupling in tax rounding assertions.
- 2026-02-17: `TKT-ERP-STAGE-011` merged into `harness-engineering-orchestrator` (`2f9a17ea`) to restore P2P settlement truth-contract consistency (`TS_P2PPurchaseJournalLinkageTest`) after Stage-010 and keep gate-release truth lane green.
- 2026-02-17: `TKT-ERP-STAGE-012` merged into `harness-engineering-orchestrator` (`aebaea61`) for M18-S5A settlement idempotency guard hardening (truthsuite lock for supplier on-account adjustment restrictions, over-allocation cap, and non-negative remaining clamp).
- 2026-02-17: `TKT-ERP-STAGE-013` merged into `harness-engineering-orchestrator` (`ae97e28a`, `33976fbb`) for M18-S5B settlement replay race diagnostics hardening (allocation-count/signature digest metadata on idempotency payload conflicts + runtime truthsuite compatibility guard).
- 2026-02-17: `TKT-ERP-STAGE-014` merged into `harness-engineering-orchestrator` (`458529c9`) for M18-S10B governance codification (release rollback traceability-evidence policy and truthsuite semantic-contract policy in CODE-RED decision log).
- 2026-02-17: `TKT-ERP-STAGE-015` merged into `harness-engineering-orchestrator` (`a0231f8d`, `ae58bb09`) for M18-S7A bulk-variant race hardening (duplicate SKU conflict convergence to deterministic skip, with explicit production test coverage and retry-policy guard expansion).
- 2026-02-17: `TKT-ERP-STAGE-016` merged into `harness-engineering-orchestrator` (`88894889`) for M18-S7B variant SKU normalization hardening (fail-closed validation for empty sanitized prefix/base/color/size fragments in bulk generation).
- 2026-02-17: `TKT-ERP-STAGE-017` merged into `harness-engineering-orchestrator` (`96700dd4`) for period-close policy hardening (explicit reason requirement enforced for period lock/close actions with accounting unit coverage).
- 2026-02-17: `TKT-ERP-STAGE-018` merged into `harness-engineering-orchestrator` (`0bbc45eb`) for M18-S6B GST/non-GST boundary hardening (non-GST mode deterministic zero-return path with fail-closed mixed-mode guard and targeted accounting unit coverage).
- 2026-02-17: `TKT-ERP-STAGE-020` merged into `harness-engineering-orchestrator` (`0342ff27`) for M18-S6C GST period-boundary hardening (future-period return requests fail closed with deterministic validation and accounting unit coverage).
- 2026-02-17: `TKT-ERP-STAGE-021` merged into `harness-engineering-orchestrator` (`2ba464ec`) for M18-S3D period-close checklist gate hardening (deterministic unresolved-control ordering with fail-closed policy diagnostics and accounting unit coverage).
- 2026-02-17: `TKT-ERP-STAGE-022` merged into `harness-engineering-orchestrator` (`a15c7799`) for M18-S6D GST reconciliation signal hardening (liability-vs-claimability routing on raw balances with final-signal rounding and half-cent edge-case coverage).
- 2026-02-17: `TKT-ERP-STAGE-026` merged into `harness-engineering-orchestrator` (`89b352bf`, `8032fe10`, `e1986b58`, `ac352078`, merge commits `9ed27299`, `3a87dad0`, `f9beac4b`, `1a86c4a8`; post-merge guard stabilization `8a6246b5`) for M18-S9A contract-proof hardening (OpenAPI snapshot verify/refresh semantics, accounting-portal parity guardrails, and portal docs parity lock evidence) with full strict-lane suite green.
- 2026-02-17: `TKT-ERP-STAGE-023` merged into `harness-engineering-orchestrator` (`86617ad2`) for M18-S3E period lifecycle closure (LOCKED -> CLOSED transition now executes close pipeline with mandatory reason and accounting policy coverage).
- 2026-02-17: `TKT-ERP-STAGE-024` merged into `harness-engineering-orchestrator` (`2243955a`, `20558287`, `9fa2c1e4`) for build-unblock closure across accounting/purchasing/audittrail repository compatibility contracts used by strict-lane compile and audit listing paths.
- 2026-02-17: `TKT-ERP-STAGE-025` merged into `harness-engineering-orchestrator` (`437835ef`, merge commit `e1ef4bb6`) for CriticalAccountingAxes full-suite unblock (GST-mode truth-contract stabilization without weakening tax invariants).
- 2026-02-17: `TKT-ERP-STAGE-027` merged into `harness-engineering-orchestrator` (`aa33f7ec`) for M18-S3F reference-number period hardening (company-clock period source with timezone-safe fallback behavior and runtime truth coverage updates).
- 2026-02-17: `TKT-ERP-STAGE-028` merged into `harness-engineering-orchestrator` (`81dbf54d`) for M18-S9A confidence-suite hardening (runtime reference-number executable coverage + CODE-RED catalog criticality promotion + Stage-028 reviewer evidence pack).
- 2026-02-17: `TKT-ERP-STAGE-029` is `canceled` as superseded stale-base work; closure is carried by `TKT-ERP-STAGE-030` on `harness-engineering-orchestrator` with strict-lane green parity (`tickets/TKT-ERP-STAGE-029/reports/closure-20260217-superseded-by-stage-030.md`).
- 2026-02-17: `TKT-ERP-STAGE-030` merged into `harness-engineering-orchestrator` (`3e2a36b3`) as async-base parity reconciliation closure for Stage-029 follow-up: accounting/p2p/test-contract slices were integrated and strict harness proof is green (`check-architecture`, `check-enterprise-policy`, `verify_local` PASS with `Tests run: 1296, Failures: 0, Errors: 0, Skipped: 4`), with Stage-001 auth/sales deltas confirmed patch-equivalent on integration.
- 2026-02-17: `TKT-ERP-STAGE-032` merged into `harness-engineering-orchestrator` (`dac7119c`, `5be17817`) for Section 14 reliability/staging-gate hardening (release gate duplicate-guard delegation in `verify_local` with fail-closed markers + deterministic invariant suite execution anchors) with full strict proof (`architecture`, `enterprise-policy`, `verify_local` all green on same SHA).
- 2026-02-17: `TKT-ERP-STAGE-034` merged into `harness-engineering-orchestrator` (`403ac857`) as scoped unblocker for Section 14.3 by restoring confidence-suite catalog completeness and knowledgebase link contract validity (`python3 scripts/validate_test_catalog.py` + `bash ci/lint-knowledgebase.sh` both green).
- 2026-02-17: `TKT-ERP-STAGE-033` completed for Section 14.3 ledger gate closure after Stage-034 unblock; anchored release gates are green on integration SHA `403ac857` (`gate_release` and `gate_reconciliation` both PASS with local DB env override evidence).
- 2026-02-17: `TKT-ERP-STAGE-035` is `canceled` as superseded stale-base work; valid Section 14.3 one-SHA closure is sourced from `TKT-ERP-STAGE-036` (`tickets/TKT-ERP-STAGE-035/reports/closure-20260217-superseded-by-stage-036.md`).
- 2026-02-17: `TKT-ERP-STAGE-036` merged into `harness-engineering-orchestrator` (`7dac0bce`, `3e8d9fe6`) to eliminate harness base-branch drift and complete Section 14.3 full-gate proof on one SHA (`RELEASE_HEAD_SHA=3e8d9fe677da1b40ada34a8528c92e396f382015`, `RELEASE_ANCHOR_SHA=07cc472ea5e087ada11caefa25ef68dab3b86005`; `gate_fast`, `gate_core`, `gate_reconciliation`, `gate_release` all PASS) with final strict harness parity via `bash scripts/verify_local.sh` (PASS, `Tests run: 1296, Failures: 0, Errors: 0, Skipped: 4`).
- 2026-02-17: `TKT-ERP-STAGE-037` merged into `harness-engineering-orchestrator` (`0b271bfe`, `85b98247`, follow-up `a1e9259e`) for gate-fast vacuous-coverage hardening: structural-only diffs now avoid false vacuous failure, blocking coverage gaps still fail closed, docs updated for non-vacuous evidence requirement, and final proof is green (`gate_fast`, `gate_reconciliation`, `gate_release`, `verify_local` PASS).
- 2026-02-17: `TKT-ERP-STAGE-038` merged into `harness-engineering-orchestrator` (`586a12ed`, `0d77fffd`) for flake-quarantine contract tightening: `scripts/check_flaky_tags.py` now fails closed on missing/invalid/expired quarantine expiry metadata and policy docs were aligned to require expiry-bounded quarantine plus dual release signal-quality evidence; closure proof is green (`lint-knowledgebase`, `check-architecture`, `check-enterprise-policy`, `gate_reconciliation`, `gate_release`, `verify_local` PASS with `Tests run: 1296, Failures: 0, Errors: 0, Skipped: 4`).
- 2026-02-17: `TKT-ERP-STAGE-039` merged into `harness-engineering-orchestrator` (`5cf59b8c`, `e3b79ab7`) for quarantine metadata contract enforcement: `scripts/check_flaky_tags.py` now fails closed unless quarantine entries carry valid `owner`, `repro`/`repro_notes`, `start`, and `expiry` metadata with `expiry <= start + 14 days`, and Section 14.3 runbooks/docs were updated to keep operator protocol synchronized with runtime policy; closure proof is green (`lint-knowledgebase`, `check-architecture`, `check-enterprise-policy`, `gate_reconciliation`, `gate_release`, `verify_local` PASS with `Tests run: 1296, Failures: 0, Errors: 0, Skipped: 4`).
- 2026-02-17: `TKT-ERP-STAGE-040` completed as control-plane closure parity reconciliation: stale statuses in `TKT-ERP-STAGE-001` and `TKT-ERP-STAGE-030` were backfilled to match merged evidence, and ticket ledger traceability was aligned without runtime code changes (`lint-knowledgebase`, `check-architecture`, `check-enterprise-policy` PASS).
- 2026-02-17: `TKT-ERP-STAGE-041` merged into `harness-engineering-orchestrator` for closure-drift fail-fast hardening: added `scripts/check_ticket_status_parity.py`, wired it into `ci/lint-knowledgebase.sh`, and codified the parity requirement in async-loop/runbook closure protocol; strict release checks are green (`lint-knowledgebase`, `gate_reconciliation`, `gate_release` PASS).
- 2026-02-17: `TKT-ERP-STAGE-042` merged into `harness-engineering-orchestrator` for period-reopen canonicalization closure: reopen reasons are normalized once and reused across persisted audit metadata and closing-entry reversal, with truth-contract alignment (`TS_PeriodCloseAtomicSnapshotTest`) and strict validation proof green (`check-architecture`, `*Accounting*`, full `mvn test`, `verify_local` PASS).
- 2026-02-17: `TKT-ERP-STAGE-043` merged into `harness-engineering-orchestrator` for period checklist immutability hardening: checklist confirmations now fail closed on `CLOSED` periods to preserve close-boundary integrity, with policy coverage and strict-lane proof green (`*Accounting*`, `verify_local` PASS).
- 2026-02-17: `TKT-ERP-STAGE-044` merged into `harness-engineering-orchestrator` for close-idempotency hardening: repeated close calls on already closed periods now short-circuit without snapshot recapture, with policy+truth evidence and strict proof green (`check-architecture`, `*Accounting*`, full `mvn test`, `verify_local` PASS).
- 2026-02-17: `TKT-ERP-STAGE-045` merged into `harness-engineering-orchestrator` for month-end checklist closed-period guard hardening: `updateMonthEndChecklist` now fail-closes on `CLOSED` periods via service-layer policy guard, with dedicated accounting policy coverage and strict proof green (`AccountingPeriodServicePolicyTest`, `*Accounting*`, `verify_local` PASS).
- 2026-02-17: `TKT-ERP-STAGE-046` canceled and superseded (stale base-branch bootstrap on local `harness-engineering-orchestrator` ref); replacement ticket re-bootstrap performed on `tmp/orch-exec-20260217`.
- 2026-02-17: `TKT-ERP-STAGE-047` merged into `tmp/orch-exec-20260217` for M18-S2A tenant control-plane metrics baseline: superadmin-only `GET /api/v1/companies/{id}/tenant-metrics` with denial-audit trail and strict proof green (`check-architecture`, targeted auth/company tests, `verify_local` PASS).
- 2026-02-17: `TKT-ERP-STAGE-048` merged into `tmp/orch-exec-20260217` (`6313aaa7`, merge commit `ef957378`) for M18-S2A authority-gap closure: tenant configuration updates now require superadmin authority at controller+service layers with auth/company coverage and strict harness proof (`check-architecture`, targeted auth/company tests, `verify_local` PASS).
- 2026-02-17: `TKT-ERP-STAGE-049` merged into `tmp/orch-exec-20260217` (`364c9a94`; closure `22d22ad2`) for M18-S2A controller fail-fast parity: superadmin-only prefilters enforced for tenant bootstrap/lifecycle/metrics endpoints with strict proof green (`check-architecture`, targeted auth/company tests, `verify_local` PASS).
- 2026-02-17: `TKT-ERP-STAGE-050` merged into `tmp/orch-exec-20260217` (`9b9fb1b7`; closure `9ef0566f`) for M18-S2A observability envelope stage-1: tenant metrics payload extended with deterministic API activity/error-rate counters under superadmin governance, with strict proof green (`check-architecture`, targeted auth/company tests, `verify_local` PASS).
- 2026-02-17: `TKT-ERP-STAGE-051` merged into `tmp/orch-exec-20260217` (`3ecf2f39`, `9380420b`; closure `b87fbb67`) for Section 14 reliability lane optimization: deterministic high-signal verify lanes codified across scripts/docs with ordered merge proof green (`gate_release`, `gate_reconciliation`, `lint-knowledgebase` PASS).
- 2026-02-17: `TKT-ERP-STAGE-052` merged into `tmp/orch-exec-20260217` (`efbaa809`, `f7345277`) for M18-S3 payroll posting fail-closed linkage guard: POSTED runs without journal linkage now fail closed, truthsuite guard semantics were aligned to `hasPostingJournalLink(run)`, and strict proof is green (`check-architecture`, targeted truthsuite payroll check, full `mvn test`, `verify_local` PASS).
- 2026-02-17: `TKT-ERP-STAGE-053` merged into `tmp/orch-exec-20260217` (`0dd344f7`, merge `2c0e63e0`; post-merge fix `AdminApprovalRbacIT`) for M18-S4 credit approval governance hardening: approve/reject endpoints now require explicit reason metadata via contract DTO, RBAC integration assertions were aligned to submit reason payloads, and strict proof is green (`*Sales*` tests, `AdminApprovalRbacIT`, full `mvn test`, `check-architecture`, `verify_local` PASS).
- 2026-02-17: `TKT-ERP-STAGE-054` merged into `tmp/orch-exec-20260217` (`e1fc732f`, merge `9b76ecd8`) for M18-S5B orchestrator idempotency reservation hardening: command scope reservation now uses conflict-safe upsert (`reserveScope`) to avoid duplicate-key exception churn while preserving strict payload-hash conflict semantics, with orchestrator guards and full suite proof green (`check-architecture`, `guard_orchestrator_correlation_contract`, `OrchestratorIdempotencyServiceTest`, full `mvn test` PASS).
- 2026-02-17: `TKT-ERP-STAGE-055` merged into `tmp/orch-exec-20260217` (`fa25cef2`, merge `1543ac80`) for runtime truthsuite contract alignment after Stage-054: executable coverage tests now stub/verify `reserveScope + lockByScope` semantics instead of legacy `saveAndFlush` duplicate-key flow, with strict proof green (`check-architecture`, targeted runtime truthsuite, full `mvn test` PASS).
- 2026-02-17: `TKT-ERP-STAGE-056` merged into `tmp/orch-exec-20260217` (`be4a9df5`, `8b2344d3`, merges `bd87245d`, `4d6fc63d`) for release-gate determinism and merge-ready discipline closure: `gate_release` now sets deterministic local release-matrix DB target defaults while honoring explicit `PG*` overrides, and orchestration docs/master-plan sequencing were aligned for merge-first deployment safety; proof is green (`gate_release`, `gate_reconciliation`, `lint-knowledgebase`, ticket verify PASS).
- 2026-02-17: `TKT-ERP-STAGE-057` merged into `tmp/orch-exec-20260217` (`ee5ffc69`, merge `579f728c`) for M18-S2A superadmin tenant metrics expansion: tenant metrics now include deterministic audit-derived session count and audit storage footprint fields (`distinctSessionCount`, `auditStorageBytes`) with strict proof green (`check-architecture`, targeted auth/company tests, full `mvn test` PASS with `Tests run: 1321, Failures: 0, Errors: 0, Skipped: 4`); superseded overlap slice (`SLICE-01`) was explicitly consolidated out before closure.
- 2026-02-17: `TKT-ERP-STAGE-058` moved to `in_progress` on `tmp/orch-exec-20260217`; codex-exec slice runs launched with explicit model/reasoning (`gpt-5.3-codex`, `xhigh/xhigh/high`) and per-slice log capture under `tickets/TKT-ERP-STAGE-058/dispatch/`.
- 2026-02-17: `TKT-ERP-STAGE-058` completed on `tmp/orch-exec-20260217` (`merge: stage-058 slice-02 data-migration`, `merge: stage-058 slice-01 auth-rbac-company`, closure `ba6421ad`) with canonical tenant quota foundation now landed across both migration chains (`db/migration/V137__company_quota_controls.sql` + `db/migration_v2/V20__company_quota_controls.sql`), runtime contract aligned to `quotaMax*` + fail-closed quota enforcement, and merged-SHA proof green (`AuthTenantAuthorityIT`, `CompanyQuotaContractTest`, `check-architecture`, `check-enterprise-policy` PASS).
- 2026-02-17: data-migration command contract normalized from stale `release_migration_matrix_v2.sh` alias to canonical `scripts/release_migration_matrix.sh --migration-set v2` across agent contracts/runbooks/active packet evidence to prevent recurring false blockers.
- 2026-02-17: `TKT-ERP-STAGE-059` bootstrapped + dispatched as next M18-S2A successor (`Tenant Quota Runtime Enforcement`) with isolated auth-rbac-company + refactor-techdebt-gc slices on `tmp/orch-exec-20260217`.
- 2026-02-17: `TKT-ERP-STAGE-060` bootstrapped + dispatched for quota contract/docs parity (`openapi + portal docs + drift guard + planning sync`) with five isolated slices (`auth-rbac-company`, `release-ops`, `frontend-documentation`, `refactor-techdebt-gc`, `repo-cartographer`).
- 2026-02-17: `TKT-ERP-STAGE-061` bootstrapped + dispatched for pre-deploy accounting/data safety finalization (accounting invariants + reconciliation gate evidence).
- 2026-02-18: `TKT-ERP-STAGE-061` completed on `tmp/orch-exec-20260217` (`a7f7357a`, `9e25df63`) with ordered slice merges (`release-ops` then `accounting-domain`) and merged-SHA required checks green (`gate_release`, `gate_reconciliation`, `*Accounting*`, `verify_local`).
- 2026-02-17: `TKT-ERP-STAGE-062` bootstrapped + dispatched for backend workflow UX simplification hardening (reason-coded fail-closed behavior across accounting/p2p/sales).
- 2026-02-18: `TKT-ERP-STAGE-062` completed on `tmp/orch-exec-20260217` (`aa07da8d`, `de5cbb9f`, `395ffcf3`, `f405fa19`; closure `bac0cc16`) with ordered slice merges (`SLICE-02` -> `SLICE-03` -> `SLICE-01` -> `SLICE-04`) and merged-head checks green (`check-architecture`, `*Sales*`, `*Accounting*`, `verify_local`).
- 2026-02-17: `TKT-ERP-STAGE-063` bootstrapped + dispatched for portal contracts and onboarding handoff completion (`frontend-documentation` + `repo-cartographer` lanes).
- 2026-02-18: `TKT-ERP-STAGE-063` completed on `tmp/orch-exec-20260217` (`verify report: 20260218-010733`) as merged no-op closure because both slice branches were already contained in base with required portal contract and onboarding documentation present.
- 2026-02-18: `TKT-ERP-STAGE-064` bootstrapped for migration/rollback rehearsal parity on release-candidate SHA (`release-ops` + `repo-cartographer` lanes) and added to active preplanned queue.
- 2026-02-18: `TKT-ERP-STAGE-064` completed on `tmp/orch-exec-20260217` (`18278495`, `168039a9`; verify report `20260218-011837`) with ordered merges (`release-ops` -> `repo-cartographer`), reviewer evidence closed, and merged-head release gates green (`lint-knowledgebase`, `gate_release`, `gate_reconciliation` PASS).

## 20) V1 Deployment Priority Stack (Senior Orchestrator Order)
Deployment is blocked until priorities below are satisfied in order:

1. Accounting/Data Safety (P0)
  - close all unresolved accounting correctness risks (double-entry, control-account reconciliation, period lock/close integrity, idempotency conflicts)
  - fail-closed behavior for risky writes must be verified by truth tests and reconciliation gates
  - no unresolved migration or rollback uncertainty for active schema set
2. Tenant/Security Safety (P0)
  - superadmin/admin boundary enforcement must be complete and test-backed
  - cross-tenant IDOR and company mismatch matrix must be green on critical APIs
  - tenant lifecycle/quota actions must be immutable-audited
3. Workflow Clarity + Backend UX (P1)
  - simplify messy backend workflows by reducing ambiguous transitions, normalizing reason codes, and returning actionable error messages
  - enforce canonical action sequences for close/reopen, settlement, approval, and lifecycle control
4. Frontend Contract/Handoff Readiness (P1)
  - portal-by-portal contract packs must be complete and synchronized with OpenAPI
  - onboarding docs must exist for bootstrap, first posting, and period close rehearsal
5. Final Staging Go/No-Go (P0)
  - same-SHA gate proof for `gate_fast`, `gate_core`, `gate_reconciliation`, `gate_release`
  - reviewer evidence complete for every merge-bound slice

## 21) Backend UX Simplification Program (Pre-Deploy)
Focus is backend workflow usability, not cosmetic UI polish:

- convert cryptic validation failures into explicit, domain-actionable errors with reason codes
- add guided workflow contracts for high-risk operations:
  - period close/reopen
  - AP/AR settlement and replay conflict resolution
  - approval/override chains
  - tenant lifecycle/quota control actions
- enforce deterministic state-machine transitions; reject out-of-order actions with corrective hints
- remove duplicate/messy code paths where they create behavioral ambiguity

Acceptance evidence:
- targeted policy/truth tests for each simplified workflow
- no regression in accounting safety checks
- updated contract docs/examples for success + failure paths

## 22) Onboarding Program (Pre-Deploy)
Required onboarding deliverables are now in scope before v1 staging handoff:

- `docs/onboarding/v1-tenant-bootstrap.md`
- `docs/onboarding/v1-accounting-first-posting.md`
- `docs/onboarding/v1-period-close-rehearsal.md`

These flows are mandatory for deployment readiness because they reduce operator error at first-use and enforce safe defaults.

## 23) Frontend Handoff Program (Portal-by-Portal)
Canonical handoff manifest:
- `docs/frontend-v1-portal-handoff.yaml`

Required frontend surfaces:
- Tenant portals: `ADMIN`, `ACCOUNTING`, `FACTORY`, `SALES`, `DEALER`
- Platform control plane: `SUPER_ADMIN_CONSOLE` (separate surface with dedicated login + JSON-rendered UI contract)

Portal handoff is complete only when each portal has:
- endpoint map and role matrix
- payload and error examples
- state-transition guidance for workflow-driven screens
- tenant/RBAC constraints documented for frontend engineers

Post-v1 note:
- after stable deploy, run dedicated test-suite cleanup program to reduce noisy/flaky coverage and preserve high-signal confidence lanes.

## 24) V1 Deployment Critical Path (Ticketized, Must Follow Order)
Current deployment sequence is now explicitly ticketized to avoid drift:

1. `TKT-ERP-STAGE-061` (P0): accounting/data safety finalization
  - close remaining accounting invariant risks
  - close reconciliation + period-lifecycle safety blockers
  - green proof required on merged base SHA
2. `TKT-ERP-STAGE-062` (P1): backend workflow UX simplification
  - normalize reason-coded failures for high-risk writes
  - simplify close/reopen, settlement, approval, lifecycle error handling
3. `TKT-ERP-STAGE-063` (P1): frontend contract + onboarding handoff completion
  - complete portal endpoint maps and JSON contract handoff
  - complete user onboarding runbooks and role-by-role first-use flows
4. `TKT-ERP-STAGE-064` (P0): migration + rollback rehearsal parity on release candidate SHA
  - active migration chain and rollback evidence must be deterministic
  - run `bash scripts/release_migration_matrix.sh --migration-set v2` on the pinned release-candidate SHA and archive outputs in artifacts + `asyncloop`
  - rollback rehearsal evidence must reuse that same `release_anchor_sha` and link `asyncloop` + `docs/approvals/R2-CHECKPOINT.md`
5. `TKT-ERP-STAGE-065` (P0): one-SHA release gate closure on integration head
  - pin immutable `RELEASE_HEAD_SHA=$(git rev-parse HEAD)` after integration-base refresh
  - `gate_fast`, `gate_core`, `gate_reconciliation`, `gate_release` all green with per-gate `head_sha_before/head_sha_after` equal to `RELEASE_HEAD_SHA`
  - closure evidence includes `RELEASE_ANCHOR_SHA`, gate logs/artifacts, and `artifacts/gate-fast/changed-coverage.json` with `"vacuous": false`
6. `TKT-ERP-STAGE-066` (P0): final staging go/no-go evidence pack
  - reviewer outcomes complete for all merge-bound slices
  - unresolved P0 accounting/security/tenant blockers must be zero

Do not reorder the above without explicit R2 evidence and updated dependency rationale.

## 25) Backend UX Simplification Targets (Pre-Deploy)
This is required to ship safely for non-expert operators and reduce workflow error rate:

1. Accounting UX contracts (highest priority)
  - period close/reopen: explicit prerequisite diagnostics, mandatory reasons, deterministic denial messages
  - posting/settlement: idempotency conflict diagnostics with clear operator next actions
  - reconciliation: actionable mismatch categories (linking, amount, period boundary)
2. Tenant/platform UX contracts
  - superadmin lifecycle/quota actions return explicit fail-closed reasons with remediation hints
  - tenant admins receive consistent “superadmin-required” boundary messaging
3. Sales/P2P workflow UX contracts
  - approval and override responses must include reason-code context and state transition guidance
  - out-of-order lifecycle actions must fail closed with corrective path hints
4. Factory workflow UX contracts
  - production and packing transitions must expose next valid actions only
  - duplicate or replay submissions must converge to deterministic outcomes

Required evidence for Section 25 completion:
- targeted policy/truth tests pass for touched workflows
- no accounting invariant regression
- portal handoff docs reflect all changed error and state-transition contracts
