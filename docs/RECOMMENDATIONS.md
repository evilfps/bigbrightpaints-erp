# Authoritative Recommendations Register

Last reviewed: 2026-03-31

> **⚠️ This is the canonical recommendations surface for the BigBright ERP backend.** All other recommendation or open-decision sections in flow packets, module packets, and handoff documents should defer to this register for authoritative verdicts. This document records the user-approved classifications for all formerly open items from the flow library and `docs/modules/sales.md`.

---

## Purpose

This register serves as the **single source of truth** for product and engineering recommendations that were previously documented as "open decisions" in individual flow and module packets. When the user provides explicit verdicts for these items, they are recorded here rather than re-litigated across multiple packets.

The register classifies each item into one of three categories:
1. **Bug to Fix Now** — items requiring immediate engineering attention due to data integrity, security, or correctness issues
2. **Future Work** — planned improvements prioritized as high/medium/low, to be scheduled in future sprints
3. **Accepted Product Decision** — known limitations or by-design behaviors that the product has accepted as acceptable trade-offs

---

## Classification Legend

| Classification | Meaning | Action |
| --- | --- | --- |
| **🔴 Bug to Fix Now** | Immediate fix required | Engineering should prioritize in current sprint |
| **🟡 Future Work — High** | Important, schedule soon | Plan for next 1-2 sprints |
| **🟡 Future Work — Medium** | Valuable, schedule later | Backlog, plan within quarter |
| **🟡 Future Work — Low** | Nice to have | Backlog, deprioritized |
| **🟢 Accepted Decision** | By-design, accepted trade-off | No action required, document for reference |

---

## Auth and Identity

| Item | Status | Classification | Rationale |
| --- | --- | --- | --- |
| Profile audit gap — user profile changes do not emit audit events | Known gap | 🔴 **Bug to Fix Now** | Audit trail incompleteness is a compliance risk. Should emit audit events for profile mutations. |
| MFA recovery code table unused — service uses column, not relational table | Known | 🟢 **Accepted Decision** | Works as designed via column storage. Table exists for potential future enhancement but is not required. |

---

## Tenant and Admin Management

| Item | Status | Classification | Rationale |
| --- | --- | --- | --- |
| Company deletion — no deletion path, hard delete not supported | Blocked | 🟡 **Future Work — Medium** | Tenant lifecycle management is incomplete. Deletion path needed for tenant offboarding scenarios. |
| Auto-limit enforcement — credit limits stored but not all auto-enforced | Partial | 🔴 **Bug to Fix Now** | Incomplete credit control enforcement creates data integrity and financial risk. All credit limits should be enforced automatically. |

---

## Catalog and Setup

| Item | Status | Classification | Rationale |
| --- | --- | --- | --- |
| Deactivation cascade — brand and item deactivation don't cascade to related entities | Not implemented | 🟡 **Future Work — Low** | Manual cleanup required when deactivating brands/items. Nice to have for operational efficiency. |

---

## Manufacturing and Packing

| Item | Status | Classification | Rationale |
| --- | --- | --- | --- |
| Production plan usage — optional, not enforced before production log | Optional | 🟡 **Future Work — Low** | Planning discipline is advisory. Not critical for current operations. |
| Cost integration — manual entry required for cost allocation | Not automated | 🟡 **Future Work — Medium** | Manual cost entry is error-prone. Automated cost integration from production to accounting would improve accuracy. |

---

## Inventory Management

| Item | Status | Classification | Rationale |
| --- | --- | --- | --- |
| Batch merge — no merge capability for duplicate batches | Not implemented | 🟡 **Future Work — Low** | Operational inconvenience but not critical. Manual consolidation possible. |
| Stock transfer — no inter-location or inter-company transfer capability | Not implemented | 🟡 **Future Work — Medium** | Multi-location inventory movement is a common requirement. Needed for warehouse consolidation scenarios. |

---

## Order-to-Cash (Sales)

*Source: `docs/flows/order-to-cash.md` and `docs/modules/sales.md`*

| Item | Status | Classification | Rationale |
| --- | --- | --- | --- |
| Automated settlement — payment receipt and allocation not automatic | Not implemented | 🟡 **Future Work — High** | Manual settlement is error-prone and time-consuming. Core O2C automation gap. |
| Automated order closure — manual closure required after settlement | Not implemented | 🟡 **Future Work — Low** | Operational inefficiency but low impact. Manual closure acceptable. |
| Single domain event — only SalesOrderCreatedEvent published | Not implemented | 🟡 **Future Work — Low** | Limits event-driven extensions but synchronous patterns work. Nice to have. |
| Shipment tracking integration — transport metadata captured but no carrier integration | Not implemented | 🟡 **Future Work — Low** | Transport metadata available for future integration. Not critical. |
| Dunning is rudimentary — only 45+ day bucket evaluated, no graduated escalation | Not implemented | 🟡 **Future Work — Medium** | Collections process would benefit from graduated escalation. Improve dealer cash flow. |
| Proforma boundary assessment-only — no auto-reservation or accounting entry during order creation | By design | 🟢 **Accepted Decision** | Intentional separation between commercial availability and financial reservation. No change needed. |
| Factory task cancellation — no bidirectional status sync beyond initial cancellation | Not implemented | 🟡 **Future Work — Low** | Visibility gap but operations can work around. Nice to have. |
| No partial fulfillment — fails on stock shortage unless explicitly enabled | Not implemented | 🟡 **Future Work — Medium** | Business requirement for partial fulfillment support. Common use case. |

---

## Procure-to-Pay (Purchasing)

| Item | Status | Classification | Rationale |
| --- | --- | --- | --- |
| PO idempotency — relies on order number uniqueness only | Not implemented | 🟡 **Future Work — High** | No idempotency protection for PO creation. Risk of duplicate POs on retry. |
| Invoice idempotency — relies on invoice number uniqueness only | Not implemented | 🟡 **Future Work — High** | No idempotency protection for purchase invoice creation. Risk of duplicate invoices on retry. |

---

## Invoice and Dealer Finance

| Item | Status | Classification | Rationale |
| --- | --- | --- | --- |
| Automatic payment reconciliation — manual settlement only | Not implemented | 🟡 **Future Work — High** | Core finance automation gap. Manual reconciliation is time-consuming and error-prone. |
| Invoice approval workflow — auto-issued during dispatch, no approval flow | Not implemented | 🟡 **Future Work — Medium** | Some customers require invoice approval before issuance. Needed for B2B scenarios. |

---

## Accounting Period Close

| Item | Status | Classification | Rationale |
| --- | --- | --- | --- |
| Automatic journal posting — manual posting required | Not implemented | 🟡 **Future Work — High** | Period close automation gap. Manual posting is time-consuming during close. |
| Scheduled reconciliation — manual run required | Not implemented | 🟡 **Future Work — Medium** | Automated reconciliation would reduce period close effort. Valuable for recurring reconciliations. |

---

## HR and Payroll

| Item | Status | Classification | Rationale |
| --- | --- | --- | --- |
| HR module default enabled — paused by default (ERP-33) | Paused | 🟢 **Accepted Decision** | Intentional product decision. HR module requires super-admin enable after ERP-33 completion. |
| Non-Indian payroll — PF/ESI/TDS are India-specific | Not supported | 🟢 **Accepted Decision** | Intentional scope limitation. Payroll is India-specific by design. |

---

## Reporting and Export

| Item | Status | Classification | Rationale |
| --- | --- | --- | --- |
| P&L snapshot branch — always returns live data | Not implemented | 🟡 **Future Work — Medium** | Historical period analysis requires snapshot capability. Common reporting need. |
| Cash flow date filtering — returns all-time data | Not implemented | 🟡 **Future Work — Low** | Date range filtering would improve report usability. Nice to have. |

---

## Cross-References

- [docs/INDEX.md](INDEX.md) — Canonical docs entrypoint
- [docs/flows/FLOW-INVENTORY.md](flows/FLOW-INVENTORY.md) — Flow inventory with links to individual flow packets
- [docs/modules/MODULE-INVENTORY.md](modules/MODULE-INVENTORY.md) — Module inventory with links to module packets
- [docs/deprecated/INDEX.md](deprecated/INDEX.md) — Deprecated surfaces registry
- [docs/adrs/INDEX.md](adrs/INDEX.md) — ADR index for architectural decisions

---

## Notes for Contributors

When a new open decision is identified in a flow or module packet:
1. **Do not** create a new recommendation section in that packet
2. **Instead**, add a brief note in the packet's open decisions section with a reference to this register
3. **Escalate** the classification decision to product for verdict
4. **Update** this register once a verdict is received

This ensures the recommendations register remains the single source of truth and prevents conflicting guidance across packets.
