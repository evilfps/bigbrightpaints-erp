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

### 4.1.1 Variant model for colors and sizes (required)
- Add a product-template + variant model:
  - `product_template` (brand, category, base tax and account profile),
  - `variant_dimensions` (color, size, finish, pack-size),
  - `product_variant` (template + dimension tuple + sellable SKU).
- SKU generation must be deterministic and human-readable:
  - example pattern: `<brand>-<family>-<color>-<size>-<pack>`.
- Prevent duplicate variants per company by unique dimension tuple constraints.
- Support barcode/alternate-code mapping for warehouse and POS integrations.
- All variant SKUs must map to correct inventory and accounting profiles.

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

### 4.2.1 Human-friendly product workflow
1. Create product template once.
2. Select allowed dimensions (colors/sizes/pack options).
3. Auto-generate variant SKUs and review before publish.
4. Bulk import/edit variants with preview + conflict detection.
5. Publish variants with pricing/accounting profiles in one guided step.
6. Block publish if any required accounting mapping is missing.

### 4.3 Exit criteria for SKU track
- no duplicate SKU creation under concurrent import,
- deterministic SKU lookup for all write paths,
- stock movements and journals fully linkable by reference + IDs.
- variant creation/update flow can be completed by non-technical users without manual data repair.

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

### 6.4 Human-centered accounting workflow redesign targets
- Guided posting flows with clear intent labels:
  - sale, purchase, expense, payroll, adjustment, accrual, reversal.
- Pre-post preview shows:
  - accounts hit,
  - debit/credit totals,
  - period and tax impact,
  - linked documents that will be created/updated.
- Post-submit confirmation returns:
  - journal reference,
  - affected ledger/subledger rows,
  - reconciliation status delta.
- For risky operations (period close, manual adjustments, reversals):
  - mandatory reason code,
  - approval path,
  - immutable audit trail with actor and trace context.

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

## 13. Virtual Accountant Readiness Specification (Post-Stabilization Foundation)

Objective:
- Prepare the ERP so a future accounting-specialized ML/LLM assistant can safely execute workflows through structured intents with human-in-loop supervision.

### 13.1 Intent contract
- Introduce a strict `AccountingIntentRequest` JSON schema (versioned):
  - `intentType` (expense, sale, purchase, settlement, payroll, adjustment, etc),
  - `companyContext`,
  - `sourceNarration`,
  - `extractedFacts`,
  - `confidence`,
  - `proposedActions[]` with deterministic workflow mapping.
- Natural language examples (for future NLP stage) must map to this schema:
  - example: \"bought $200 ChatGPT plan\" -> expense intent with vendor/category/tax/account suggestions.

### 13.2 Human-in-loop safety contract
- Every ML-proposed action executes in simulation mode first.
- UI must show \"what will happen\" before approval:
  - documents created/updated,
  - journal lines,
  - balances impacted,
  - reconciliation effect.
- User must explicitly approve/reject each proposed action set.
- Approved actions execute via the same canonical ERP APIs (no bypass path).

### 13.3 Audit and traceability contract
- Persist full chain:
  - natural-language request hash,
  - extracted intent JSON,
  - approval decision and approver,
  - final ERP operation ids (journal/invoice/settlement ids),
  - rollback/reversal link if corrected.

### 13.4 Readiness exit criteria before ML activation
- base ERP stabilization/deploy gates are fully green,
- intent schema + simulation API is in place,
- approval/audit chain proven in integration tests,
- no direct auto-posting from NLP layer without explicit user approval.

## 14. Immediate Execution Queue Seed
- `M17-S1`: AGENTS and async-loop governance alignment (multi-agent + review-chain enforcement).
- `M17-S2`: OpenAPI contract drift guard + portal endpoint parity checks.
- `M17-S3`: Top-5 duplicate write-path closures with alias parity tests.
- `M17-S4`: Accounting drift sentinel expansion + reconciliation gating.
- `M17-S5`: Test-lane rationalization and flake quarantine with expiry policy.
- `M17-S6`: Staging rehearsal evidence pack template and rollback drill execution.
- `M17-S7`: Virtual-accountant intent schema + simulation/approval contract (no autonomous posting yet).
