# Workflow Documentation Gap Analysis

**Generated:** 2026-03-28
**Sources:** All 8 workflow docs under `docs/workflows/`, `erp-definition-of-done.md`, `.factory/library/architecture.md`, `docs/architecture.md`

---

## 1. Coverage Summary: Documented vs. Undocumented Flows

| Flow Area | Workflow Doc Exists? | File | Detail Level |
|---|---|---|---|
| Sales Order-to-Cash (O2C) | ✅ Yes | `docs/workflows/sales-order-to-cash.md` | Medium — 9-step table with API mappings, troubleshooting |
| Accounting & Period Close | ✅ Yes | `docs/workflows/accounting-and-period-close.md` | Medium-High — 8-step table, daily ops APIs, reconciliation + discrepancy handling |
| Purchase-to-Pay (P2P) | ✅ Yes | `docs/workflows/purchase-to-pay.md` | Medium — 6-step table with exceptions for returns/voids |
| Inventory Management | ✅ Yes | `docs/workflows/inventory-management.md` | Medium — 4-step table, adjustment type taxonomy, opening stock |
| Manufacturing & Packaging | ✅ Yes | `docs/workflows/manufacturing-and-packaging.md` | Medium — 5-step table, boundary notes, supporting APIs |
| Payroll | ✅ Yes | `docs/workflows/payroll.md` | Medium — 5-step table, statutory deduction mentions (PF/ESI/TDS) |
| Admin & Tenant Management | ✅ Yes | `docs/workflows/admin-and-tenant-management.md` | High — 8-step table covering onboarding, lifecycle, support, exports, changelog |
| Data Migration | ✅ Yes | `docs/workflows/data-migration.md` | Medium — 5-step table, cutover sequence, ownership matrix, go-live criteria |

**All 8 major workflow areas have documentation.** No flow is entirely undocumented at the workflow-doc level.

---

## 2. Detail Level Assessment Per Workflow

### Detail levels found across docs

| Detail Dimension | O2C | Accounting | P2P | Inventory | Mfg | Payroll | Admin | Migration |
|---|---|---|---|---|---|---|---|---|
| End-to-end step table | ✅ | ✅ | ✅ | ✅ | ✅ | ✅ | ✅ | ✅ |
| API endpoint mapping | ✅ | ✅ | ✅ | ✅ | ✅ | ✅ | ✅ | ✅ |
| Error/troubleshooting | ✅ | ✅ | ✅ | ✅ | ✅ | ✅ | ✅ | ✅ |
| State machine / status transitions | ❌ | ❌ | Partial | ❌ | ❌ | ❌ | ❌ | ❌ |
| Validation rules enumerated | ❌ | ❌ | ❌ | ❌ | ❌ | ❌ | ❌ | ❌ |
| Accounting journal effects per step | ❌ | Partial | ❌ | ❌ | ❌ | ❌ | ❌ | ❌ |
| Idempotency semantics documented | Partial | ❌ | Partial | ✅ | ✅ | ❌ | ❌ | ✅ |
| Role/permission requirements per step | ❌ | ❌ | ❌ | ❌ | ❌ | ❌ | ❌ | ❌ |
| Cross-flow dependencies noted | Partial | ❌ | ❌ | ❌ | ❌ | ❌ | ❌ | ❌ |
| Policy decisions recorded | ❌ | ❌ | ❌ | ❌ | ❌ | ❌ | ❌ | ❌ |

### Key observations:

- **All docs follow the same template:** a step table with columns (What to do, Screen + API, What to expect, What can go wrong). This is consistent and useful for operational guidance.
- **No doc includes a formal state machine diagram** or enumerated status transition table (e.g., `DRAFT → CONFIRMED → DISPATCHED → INVOICED`). The `docs/architecture.md` file has Mermaid sequence diagrams for engineering flows, but workflow docs don't expose lifecycle states explicitly.
- **Validation rules are implicit only** — mentioned as "what can go wrong" free text, not enumerated as explicit validation rules (e.g., "quantity must be > 0", "credit limit must not exceed X").
- **Accounting effects are almost entirely absent** from workflow docs. Only the Accounting & Period Close doc partially addresses journal effects. No other workflow doc explains which accounts are debited/credited at each step.
- **Role/permission requirements are missing** from all workflow docs. The DoD defines a canonical portal model (Admin, Accounting, Sales, Factory, Dealer, Super Admin) but no workflow doc maps steps to required roles.

---

## 3. Gap Analysis Against DoD Template Requirements

The DoD (`erp-definition-of-done.md`) defines several principles and requirements. Here's what each workflow doc is missing relative to the DoD:

### 3.1 DoD Requirement: "One canonical posting trigger"

| Workflow Doc | Gap |
|---|---|
| O2C | Mentions dispatch triggers invoice, but does not explicitly state "dispatch confirmation is the ONE canonical trigger" or contrast it with prohibited alternatives |
| P2P | Does not explicitly state "GRN is stock truth only" / "purchase invoice is AP truth only" as the DoD requires |
| Manufacturing | Does not explicitly state that packing-records is the sole packing mutation path and retired routes must not be used |
| **All docs** | None document the anti-patterns or prohibited paths that the DoD calls out (no hidden duplicate-truth listeners, no silent auto-repair) |

### 3.2 DoD Requirement: "Document lifecycle state and accounting lifecycle state must stay separate"

| Workflow Doc | Gap |
|---|---|
| O2C | Does not distinguish order lifecycle states from accounting lifecycle states |
| P2P | Does not separate PO lifecycle from AP lifecycle |
| Manufacturing | Does not separate production log lifecycle from costing journal lifecycle |
| **All docs** | No doc explains this separation principle or how it manifests in the specific flow |

### 3.3 DoD Requirement: "Linked references must remain navigable"

| Workflow Doc | Gap |
|---|---|
| O2C | Does not document the navigable reference chain: order → slip → dispatch → invoice → journal → settlement |
| P2P | Does not document: PO → GRN → purchase invoice → journal → settlement |
| Manufacturing | Does not document: production log → packing record → FG batch → dispatch → invoice |
| **All docs** | Cross-document reference chains are not traced end-to-end |

### 3.4 DoD Requirement: Canonical portal model (role-action matrix)

| Workflow Doc | Gap |
|---|---|
| **All docs** | No workflow doc maps steps to portal roles (Admin, Accounting, Sales, Factory, Dealer, Super Admin). The "Audience" header mentions target users but does not enforce role-based access per step. |

### 3.5 DoD Requirement: Locked mission decisions

| Decision | Documented in Workflow Docs? |
|---|---|
| Migration track: Flyway v2 only | ❌ Not mentioned in any workflow doc |
| Costing: batch actual + packaging carry-forward | ❌ Not mentioned in Manufacturing doc (costing method is implicit in cost traceability step) |
| Settlement: invoice/purchase header-level only | ❌ Not mentioned in O2C or P2P docs |
| Approval model: admin-only, per-document, mandatory reason | ❌ Not mentioned in any workflow doc (period close mentions maker-checker but doesn't specify admin-only constraint) |
| Closed-period exception: explicit admin approval, 1-hour expiry | ❌ Not mentioned in any workflow doc |

---

## 4. DoD Workbook Template Flows with NO Documentation

The DoD and architecture docs reference several flow areas. Cross-referencing:

| Flow/Topic | Referenced in DoD or Architecture | Workflow Doc? |
|---|---|---|
| Corrections/Reversals/Returns | DoD: "corrections use linked reversal, return, note, or reissue flows" | ❌ No dedicated doc; P2P mentions returns briefly |
| Settlement workflows (dealer + supplier) | Architecture §2.1–2.2 | ❌ No dedicated doc; embedded as steps in O2C and P2P |
| Credit limit / override workflow | O2C doc mentions briefly | ❌ No dedicated doc |
| Reconciliation deep-dive (bank, AR/AP, GST) | Accounting doc covers at step level | ❌ No dedicated reconciliation procedure doc |
| Support ticket lifecycle | Architecture §2.8 has sequence diagram | ❌ No dedicated doc; Admin doc covers at step level |
| Portal role-action matrix | DoD §Canonical Portal Model | ❌ No dedicated doc |
| GST compliance / e-invoice / e-way-bill | DoD: "out of scope for phase one" | ✅ Correctly excluded (but no doc marks this boundary explicitly) |

### Flows referenced in architecture docs but lacking workflow documentation:

1. **Detailed correction/reversal procedures** — How to reverse a posted journal, handle a sales return, process a credit note. These are different from forward flows.
2. **Settlement matching/allocation procedures** — How FIFO-style auto-settle works, how manual settlement allocation works, edge cases for partial settlement.
3. **Credit management** — Credit limit requests, approval, override workflows, one-time exceptions.
4. **Reporting and analytics** — How to generate, interpret, and validate period-end reports (trial balance, P&L, balance sheet, GST return). The Accounting doc lists APIs but doesn't document the reporting procedure.
5. **Dealer portal operations** — What dealers can see/do, how they interact with the system. Only implicitly covered in Admin doc.

---

## 5. Policy Decisions: Already Made vs. Still Open

### Already decided (in DoD or architecture):

| Policy | Where Decided | Documented in Workflow Docs? |
|---|---|---|
| Flyway v2 only | DoD | ❌ |
| Batch actual + packaging carry-forward costing | DoD | ❌ |
| Invoice-level settlement only | DoD | ❌ |
| Admin-only approval model | DoD | ❌ |
| Closed-period exception: 1hr expiry | DoD | ❌ |
| Factory must not be accounting/inventory admin | DoD + Architecture | Partial (Manufacturing doc boundary note) |
| Portal host ownership split (ERP-21) | Architecture | Partial (Admin doc mentions retired shared routes) |
| Canonical dispatch is sole commercial-to-accounting trigger | DoD | ❌ |
| Production log is sole manufacturing truth entrypoint | Architecture | ❌ |
| Packing records is sole pack mutation path | Architecture | Partial (Manufacturing doc boundary note) |

### Still open / not decided in reviewed docs:

| Policy Area | Status |
|---|---|
| Exact credit limit calculation formula | Not documented |
| GST return filing integration details | Out of scope (per DoD) but not stated in workflow docs |
| Multi-warehouse handling | Out of scope (per DoD) but not stated in workflow docs |
| Dealer pricing tier mechanics | Not documented in workflow docs |
| Attendance integration for payroll | Mentioned as dependency but not documented |
| Employee onboarding / HR master data workflow | Not documented (no HR workflow doc) |
| Production planning scheduling rules | Not documented |
| Raw material costing method selection (FIFO vs WAC) per product | Mentioned in architecture but not in workflow docs |

---

## 6. Contradictions Between Documents

### 6.1 O2C doc: Period close step vs. dedicated Accounting doc

- **O2C doc Step 9** describes period close as a step in the sales order-to-cash flow.
- **Accounting doc** has a full 8-step period close workflow.
- **Not a contradiction** per se, but the O2C doc oversimplifies period close (request → approve → finalize) while the Accounting doc has a richer model (lock → request → approve/reject → finalize → reports). The O2C doc should either remove Step 9 and link to the Accounting doc, or clearly state it's a summary.

### 6.2 Payroll scope: DoD says "out of scope" but Payroll doc exists

- **DoD** states: "Out of scope for this mission baseline: full payroll expansion."
- **Payroll workflow doc** exists with 5 steps covering create → calculate → approve → post → mark-paid.
- **Resolution:** The DoD says "full payroll expansion" is out of scope, not payroll itself. The current doc covers basic payroll. Not a true contradiction, but the boundary between "basic payroll" and "full payroll expansion" is not articulated.

### 6.3 Manufacturing doc vs. Architecture: Costing detail

- **Manufacturing doc Step 4** says "validate cost traceability" and lists a cost-breakdown API.
- **Architecture doc** §2.3 details RM consumption via FIFO/WAC-aware batch selection and journal posting to WIP/consumption accounts.
- **Gap:** The workflow doc does not explain the costing method, while the architecture doc does. The workflow doc should at least reference that costing method is "batch actual + packaging carry-forward" per DoD.

### 6.4 Inventory doc: Opening stock vs. Data Migration doc

- **Inventory doc Step 3** describes opening stock import.
- **Data Migration doc Step 3** also describes opening stock import with nearly identical content.
- **Not a contradiction** but a duplication. Both should reference a single canonical source or clearly distinguish operational re-import from initial migration.

### 6.5 Admin doc: Support tickets host split vs. Architecture

- **Admin doc Step 7** mentions the host split for support tickets correctly.
- **Architecture §2.8** has a detailed sequence diagram for the same flow.
- **No contradiction** — consistent. However, the Admin doc should link to the architecture doc for engineering detail.

---

## 7. Summary of Major Gaps

### Critical gaps (should be addressed):

1. **No state machine diagrams or status transition tables** in any workflow doc. The DoD requires "document lifecycle state and accounting lifecycle state must stay separate" but no doc enumerates the states.
2. **Accounting effects are almost entirely absent.** Only the Accounting doc partially covers this. O2C, P2P, Manufacturing, and Payroll docs don't explain which accounts are affected at each step.
3. **Locked mission decisions are not reflected** in workflow docs. All five locked decisions (Flyway v2, costing method, settlement granularity, approval model, closed-period exception) are absent.
4. **No role-action mapping** per step despite the DoD defining a canonical portal model.
5. **No correction/reversal workflow doc** despite the DoD requiring linked reversal/return/note/reissue flows.

### Significant gaps (should be addressed over time):

6. **Cross-flow reference chains** are not documented end-to-end in any single workflow doc.
7. **Settlement procedures** lack dedicated documentation for both dealer and supplier flows.
8. **Credit management** workflow is referenced but not documented.
9. **Reporting procedures** are listed as APIs but not documented as a workflow.
10. **Dealer portal operations** have no dedicated workflow doc.

### Minor gaps:

11. **Duplication** between Inventory doc and Data Migration doc on opening stock import.
12. **O2C Step 9** oversimplifies period close vs. the dedicated Accounting doc.
13. **Payroll scope boundary** not articulated relative to DoD's "out of scope" statement.

---

## 8. Recommendations

1. **Add state machine diagrams** to each workflow doc showing document lifecycle states and accounting lifecycle states separately.
2. **Add accounting effect tables** to O2C, P2P, Manufacturing, and Payroll docs showing debit/credit accounts per step.
3. **Add a "Policy Decisions" section** to each workflow doc capturing relevant locked decisions from the DoD.
4. **Add a role-action matrix** to each workflow doc mapping steps to canonical portal roles.
5. **Create a Corrections & Reversals workflow doc** covering journal reversal, sales returns, credit notes, purchase returns (currently only in P2P), and period-close exception handling.
6. **Create a Settlement workflow doc** covering both dealer and supplier settlement mechanics, FIFO allocation, and edge cases.
7. **Deduplicate** opening stock import content between Inventory and Data Migration docs.
8. **O2C doc** should replace Step 9 with a link to the Accounting & Period Close doc rather than duplicating it.
