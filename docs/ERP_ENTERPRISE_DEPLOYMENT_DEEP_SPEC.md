# ERP Enterprise Deployment Deep Spec (AI-First, Accounting-Safe)

Last reviewed: 2026-02-15
Owner: Orchestrator Agent
Status: Proposed for active execution

## 1. Problem Statement
The current system has high capability but inconsistent execution quality due to:
- duplicate write paths and duplicate planning surfaces,
- weak API contract governance for frontend consumers,
- noisy test volume with mixed signal,
- CI/release evidence that is not consistently tied to immutable release SHAs,
- accounting correctness risks when paths bypass canonical posting/idempotency boundaries.

Target outcome:
- enterprise-grade backend that is deployable with deterministic accounting behavior,
- AI-heavy development with low human touch but high mechanical safety,
- one clear execution model for long-running agent loops.

## 2. Non-Negotiable Principles
- Accounting truth must be deterministic and auditable.
- One canonical write path per business event.
- Idempotency must be mismatch-safe (replay with different payload fails closed).
- Flyway v2 only for all new schema work.
- No deploy claim without proof bundle tied to one SHA.

## 3. Operating Model for AI Agents

### 3.1 Agent topology (not single-agent YOLO)
- Orchestrator agent:
  - owns intent, plan, risk posture, and final merge quality.
- Worker agents:
  - own bounded implementation slices in parallel (by module or contract boundary).
- Reviewer agents:
  - run commit-level adversarial review and invariant checks.

### 3.2 Required review chain per commit
1. targeted harness run
2. `codex review --commit <sha>`
3. one independent review agent result
4. findings disposition in `asyncloop`

### 3.3 Execution lanes
- `fast_lane`: docs, non-critical refactors, guard/docs wiring.
- `strict_lane`: accounting, auth/RBAC, migrations, orchestrator, period close, settlements.

## 4. Product and SKU Lifecycle Specification

### 4.1 Canonical product identity model
- Introduce/standardize canonical SKU classes:
  - `RAW_MATERIAL`
  - `PACKAGING_MATERIAL`
  - `BULK_SEMI_FINISHED`
  - `FINISHED_GOOD`
- Each SKU must have:
  - stable company-scoped code,
  - unit-of-measure contract,
  - inventory valuation method,
  - posting profile (inventory/WIP/COGS/revenue/tax mappings where applicable).

### 4.2 SKU lifecycle flow (end-to-end)
1. Master creation/import -> validated SKU identity and account mappings.
2. Procurement intake -> raw material stock + AP posting at supplier invoice stage.
3. Production log -> consume raw, create bulk, post WIP/cost.
4. Packing/bulk conversion -> consume bulk+packaging, produce finished goods.
5. Sales order/reservation -> stock commitment without financial posting.
6. Dispatch confirmation -> inventory issue + invoice + AR/COGS journals.
7. Settlement/receipt -> AR clearing and ledger linkage.
8. Returns/reversals -> explicit reversal links and control-account integrity.
9. Period close -> snapshot freeze and deterministic as-of reporting.

### 4.3 Exit criteria for SKU track
- no duplicate SKU creation under concurrent import,
- deterministic SKU lookup for all write paths,
- stock movements and journals fully linkable by reference + IDs.

## 5. Domain Workflow Specification

### 5.1 Order-to-Cash (O2C)
Target contract:
- canonical dispatch confirmation path owns SHIPPED/DISPATCHED semantics,
- no status-only bypass that can skip invoice/journal/ledger linkage,
- dealer-facing reads remain scoped and non-enumerable.

Required invariant checks:
- order -> slip -> invoice -> AR journal -> dealer ledger linkage is complete,
- COGS can post only once per slip reference,
- replay cannot create second revenue/COGS journals.

### 5.2 Procure-to-Pay (P2P)
Target contract:
- PO/GRN chain controls quantity and ownership,
- supplier invoice drives AP + inventory/tax postings,
- settlement/payment APIs share one idempotency and allocation contract.

Required invariant checks:
- no over-allocation beyond outstanding,
- no duplicate settlement allocations on retries/concurrency,
- purchase returns unwind inventory/AP with explicit references.

### 5.3 Production-to-Pack
Target contract:
- production and packing retries are idempotent,
- deterministic references for bulk pack and packaging consumption,
- all financial postings routed through accounting boundary.

Required invariant checks:
- no double-consume of raw/packaging under retry,
- parent-child batch lineage preserved,
- movement-to-journal linkage complete for posted events.

### 5.4 Hire-to-Pay (Payroll)
Target contract:
- one payroll run identity per scope (company/runType/period),
- posting and payment are explicit and separately traceable,
- PAID status requires payment evidence.

Required invariant checks:
- payroll journal balance and account usage correctness,
- advances/liabilities clear deterministically,
- run status transitions remain linear and fail-closed.

## 6. Accounting Safety and Anti-Drift Specification

### 6.1 Core invariants
- double-entry always balanced,
- AR/AP/subledger balances reconcile to control accounts,
- period-close immutability for closed periods,
- reversal chains (`reversal_of_id`) are complete and traceable.

### 6.2 Drift sentinels
Implement or enforce scheduled and predeploy checks for:
- unlinked source docs (dispatch/invoice/journal/ledger gaps),
- duplicate idempotency allocations or posting references,
- closed-period late postings and snapshot mismatch,
- inventory/GL divergence for movement-linked postings.

### 6.3 Accounting hard-stop policy
- if drift sentinel finds P0 rows, deployment is blocked,
- fail-open logging-only behavior is disallowed for accounting-critical writes in strict policy modes.

## 7. API Contract and Frontend Documentation Specification

### 7.1 Contract governance
- OpenAPI spec is canonical machine contract.
- Breaking contract diffs require explicit approval label + migration notes.
- Alias endpoints must declare:
  - canonical endpoint,
  - compatibility window,
  - deprecation/removal target.

### 7.2 Frontend documentation quality bar
For each portal (`ADMIN`, `ACCOUNTING`, `SALES`, `FACTORY`, `DEALER`):
- endpoint map generated from OpenAPI + RBAC overlays,
- role/permission matrix,
- error semantics (`401/403/409/422`) and retry behavior,
- pagination/filter/sort contract,
- idempotency expectations for writes.

### 7.3 Documentation anti-slop policy
- no “ready” claim without linked evidence command + artifact path,
- stale docs failing parity checks are CI failures.

## 8. Test and Quality Specification

### 8.1 Test lane architecture
- Lane A: `bash scripts/gate_fast.sh` for PR safety and contract guards.
- Lane B: `bash scripts/gate_core.sh` for integration invariants.
- Lane C: `bash scripts/gate_release.sh` for migration matrix + predeploy scans.
- Lane D: `bash scripts/gate_reconciliation.sh` for accounting truth parity.
- Lane E: `bash scripts/gate_quality.sh` for nightly flake/perf budget.

### 8.2 Flake and noise management
- quarantine flaky tests with owner + expiry date,
- require deterministic reproduction notes,
- reject permanent quarantine without fix plan.

### 8.3 Deploy-blocking invariant coverage
Maintain a live map from each P0 blocker class to specific test names and guard scripts.
No blocker can be marked closed without executable coverage.

## 9. CI/CD and Environment Specification

### 9.1 Environment tiers
- `dev_local`: fast iteration, targeted checks.
- `ci_pr`: deterministic fast lane.
- `ci_main`: full core lane.
- `staging_rehearsal`: release lane + predeploy scans + smoke + rollback drill.

### 9.2 Artifact contract
Each promotion candidate must publish:
- commit SHA,
- gate results,
- migration matrix output,
- predeploy scan output,
- smoke and health results,
- residual risk ledger.

### 9.3 Migration safety (Flyway v2)
Required commands on release lane:
- `bash scripts/schema_drift_scan.sh --migration-set v2`
- `bash scripts/flyway_overlap_scan.sh --migration-set v2`
- `bash scripts/release_migration_matrix_v2.sh`

## 10. Phase Plan (Deep Program)

### Phase P0: Governance and Flow Control (Week 1)
- unify execution control-plane docs,
- align AGENTS/INDEX/asyncloop discovery path,
- enforce multi-agent execution policy and review chain.

Exit evidence:
- `bash ci/lint-knowledgebase.sh`
- asyncloop queue with active phase slices.

### Phase P1: Contract and Dedup Foundation (Weeks 1-3)
- API contract gate in CI,
- top duplicate write paths closed or safely gated,
- canonical alias policy documented and tested.

Exit evidence:
- contract drift check output,
- duplicate-path parity tests green.

### Phase P2: Workflow and Accounting Hardening (Weeks 2-6)
- O2C/P2P/production/payroll invariants locked with tests,
- anti-drift sentinels expanded,
- strict fail-closed behavior for accounting-critical paths.

Exit evidence:
- reconciliation and invariant suites green,
- drift scans zero for staging dataset.

### Phase P3: CI Reliability and Release Readiness (Weeks 4-8)
- flaky test budget under threshold,
- SHA-linked artifact bundles,
- staging rehearsal with rollback drill.

Exit evidence:
- release lane + rehearsal bundle complete,
- go/no-go checklist all green.

### Phase P4: Production Promotion Framework (Weeks 8+)
- controlled rollout policy with canary/monitoring,
- incident/rollback playbook exercised,
- post-deploy accounting drift monitors active.

Exit evidence:
- production promotion decision pack,
- post-deploy monitoring baseline with zero unresolved P0.

## 11. Detailed Review and Approval Policy
- R1: orchestrator checkpoint when requirement conflict appears.
- R2: mandatory for high-risk semantic changes.
- R3: human only for irreversible production actions.

For strict-lane slices, required review stack:
- backend consistency review,
- accounting logic review,
- workflow state review,
- security access review,
- release readiness review (if deploy-facing).

## 12. Metrics of Success
- Zero unresolved P0 blockers.
- Zero unresolved v2 drift/overlap findings at release rehearsal.
- No duplicate posting incidents for critical write paths.
- Flake budget below agreed threshold for release window.
- Frontend contract docs synchronized with OpenAPI on each release SHA.

## 13. Immediate Execution Queue Seed
- `M17-S1`: AGENTS and async-loop governance alignment (multi-agent + review-chain enforcement).
- `M17-S2`: OpenAPI contract drift guard + portal endpoint parity checks.
- `M17-S3`: Top-5 duplicate write-path closures with alias parity tests.
- `M17-S4`: Accounting drift sentinel expansion + reconciliation gating.
- `M17-S5`: Test-lane rationalization and flake quarantine with expiry policy.
- `M17-S6`: Staging rehearsal evidence pack template and rollback drill execution.
