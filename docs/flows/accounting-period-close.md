# Accounting / Period Close Flow

Last reviewed: 2026-03-30

This packet documents the **accounting and period close flow**: the canonical lifecycle for journal posting, bank reconciliation, sub-ledger reconciliation, GST reconciliation, period locking, and the maker-checker workflow for period close. It covers manual and automated journal entry creation, reconciliation sessions, period state transitions, and correction mechanisms.

This flow is **behavior-first** and **code-grounded**. Where the backend is incomplete, blocked, or intentionally partial, the packet explicitly states the current limitation instead of presenting partial behavior as complete.

---

## 1. Actors

| Actor | Role | Authorization Scope |
| --- | --- | --- |
| **Admin** | Full accounting access, period management | `ROLE_ADMIN` |
| **Accounting** | Journal creation, reconciliation, period close | `ROLE_ACCOUNTING` |
| **Sales** | Read-only access to some accounting views | `ROLE_SALES` |
| **Factory** | Read access to relevant journals | `ROLE_FACTORY` |

---

## 2. Entrypoints

### Journal Entry — `AccountingController` (`/api/v1/accounting/journals/**`)

| Entrypoint | Method | Path | Actor | Purpose |
| --- | --- | --- | --- | --- |
| List Journals | GET | `/api/v1/accounting/journals` | ADMIN, ACCOUNTING | List journal entries |
| Get Journal | GET | `/api/v1/accounting/journals/{id}` | ADMIN, ACCOUNTING | Get journal detail |
| Create Journal | POST | `/api/v1/accounting/journals` | ADMIN, ACCOUNTING | Create manual journal |
| Post Journal | POST | `/api/v1/accounting/journals/{id}/post` | ADMIN, ACCOUNTING | Post to ledger |
| Reverse Journal | POST | `/api/v1/accounting/journals/{id}/reverse` | ADMIN, ACCOUNTING | Reverse posted journal |

### Period Management — `AccountingController` (`/api/v1/accounting/periods/**`)

| Entrypoint | Method | Path | Actor | Purpose |
| --- | --- | --- | --- | --- |
| List Periods | GET | `/api/v1/accounting/periods` | ADMIN, ACCOUNTING | List periods |
| Get Period | GET | `/api/v1/accounting/periods/{id}` | ADMIN, ACCOUNTING | Get period detail |
| Create Period | POST | `/api/v1/accounting/periods` | ADMIN | Create new period |
| Lock Period | POST | `/api/v1/accounting/periods/{id}/lock` | ADMIN | Lock period |
| Unlock Period | POST | `/api/v1/accounting/periods/{id}/unlock` | ADMIN | Unlock period |

### Period Close Request — `AccountingController` (`/api/v1/accounting/periods/**`)

| Entrypoint | Method | Path | Actor | Purpose |
| --- | --- | --- | --- | --- |
| Request Close | POST | `/api/v1/accounting/periods/{id}/close-request` | ACCOUNTING | Request period close |
| Approve Close | POST | `/api/v1/accounting/periods/{id}/close-approve` | ADMIN | Approve period close |
| Finalize Close | POST | `/api/v1/accounting/periods/{id}/close-finalize` | ADMIN | Finalize period close |

### Bank Reconciliation — `AccountingController` (`/api/v1/accounting/reconciliation/**`)

| Entrypoint | Method | Path | Actor | Purpose |
| --- | --- | --- | --- | --- |
| Start Session | POST | `/api/v1/accounting/reconciliation/sessions` | ADMIN, ACCOUNTING | Start reconciliation |
| List Sessions | GET | `/api/v1/accounting/reconciliation/sessions` | ADMIN, ACCOUNTING | List sessions |
| Get Session | GET | `/api/v1/accounting/reconciliation/sessions/{id}` | ADMIN, ACCOUNTING | Get session detail |
| Match Entry | POST | `/api/v1/accounting/reconciliation/sessions/{id}/match` | ADMIN, ACCOUNTING | Match bank/book entry |
| Unmatch Entry | POST | `/api/v1/accounting/reconciliation/sessions/{id}/unmatch` | ADMIN, ACCOUNTING | Unmatch entry |
| Add Adjustment | POST | `/api/v1/accounting/reconciliation/sessions/{id}/adjustments` | ADMIN, ACCOUNTING | Add reconciliation adjustment |
| Complete Session | POST | `/api/v1/accounting/reconciliation/sessions/{id}/complete` | ADMIN, ACCOUNTING | Complete session |

### Sub-Ledger Reconciliation — `AccountingController` (`/api/v1/accounting/reconciliation/**`)

| Entrypoint | Method | Path | Actor | Purpose |
| --- | --- | --- | --- | --- |
| Dealer Reconciliation | GET | `/api/v1/accounting/reconciliation/dealers/{dealerId}` | ADMIN, ACCOUNTING | Dealer sub-ledger vs GL |
| Supplier Reconciliation | GET | `/api/v1/accounting/reconciliation/suppliers/{supplierId}` | ADMIN, ACCOUNTING | Supplier sub-ledger vs GL |
| Run Reconciliation | POST | `/api/v1/accounting/reconciliation/run` | ADMIN, ACCOUNTING | Run full reconciliation |

### GST Reconciliation — `AccountingController` (`/api/v1/accounting/gst/**`)

| Entrypoint | Method | Path | Actor | Purpose |
| --- | --- | --- | --- | --- |
| GST Reconciliation | GET | `/api/v1/accounting/gst/reconciliation` | ADMIN, ACCOUNTING | Sales vs purchase GST |

### Opening Balance Import — `OpeningBalanceImportController` (`/api/v1/migration/**`)

| Entrypoint | Method | Path | Actor | Purpose |
| --- | --- | --- | --- | --- |
| Import Opening Balance | POST | `/api/v1/migration/opening-balance` | ADMIN | Import opening balances |

### Tally Import — `TallyImportController` (`/api/v1/migration/**`)

| Entrypoint | Method | Path | Actor | Purpose |
| --- | --- | --- | --- | --- |
| Import Tally Data | POST | `/api/v1/migration/tally-import` | ADMIN | Import Tally XML |

---

## 3. Preconditions

### Journal Creation Preconditions

1. **Period open** — accounting period must be OPEN
2. **Valid journal lines** — at least one debit and one credit, amounts equal
3. **Valid account codes** — accounts must exist in chart of accounts
4. **Company context** — valid company in request context

### Journal Posting Preconditions

1. **Journal in DRAFT status** — not already posted
2. **Period open** — cannot post to closed period
3. **Balanced** — total debits equals total credits
4. **No duplicate posting** — idempotent (re-post checks existing)

### Period Close Preconditions

1. **No open journals** — all journals in the period must be posted
2. **Reconciled** — bank reconciliation and sub-ledger reconciliation complete
3. **Maker-Checker** — request by ACCOUNTING, approved by ADMIN, finalized by ADMIN

### Bank Reconciliation Preconditions

1. **Period open** — reconciliation for open period
2. **Bank account valid** — account exists and is bank type
3. **Statement balance provided** — for variance calculation

### Opening Balance Import Preconditions

1. **Company exists** — target company valid
2. **Period exists** — target period exists
3. **Valid account mappings** — all account codes valid

---

## 4. Lifecycle

### 4.1 Journal Entry Lifecycle

```
[Start] → Validate period open → Validate lines balanced → 
Validate accounts → Save as DRAFT → [End: Journal DRAFT]

[DRAFT] → Post → Validate not already posted → 
Update status POSTED → [End: Journal POSTED]
```

**Key behaviors:**
- Journal created in DRAFT status
- Posting moves to POSTED, makes it immutable
- Reversal creates new journal with reversed amounts

### 4.2 Period Lifecycle

| Status | Meaning | Transitions |
| --- | --- | --- |
| `OPEN` | Active for transactions | Can receive journals |
| `LOCKED` | No new journals, existing visible | Unlock to OPEN |
| `CLOSED` | Finalized, maker-checker complete | Cannot reopen (by design) |

**Period creation:** New periods created by Admin with OPEN status

**Period lock:** Admin locks to prevent new journals

**Period close workflow:**
```
[OPEN] → ACCOUNTING requests close → [PENDING_APPROVAL]
[PENDING_APPROVAL] → ADMIN approves → [PENDING_FINALIZE]
[PENDING_FINALIZE] → ADMIN finalizes → [CLOSED]
```

### 4.3 Bank Reconciliation Lifecycle

```
[Start] → Select bank account → Enter statement balance → 
Create session → [End: Session STARTED]

[STARTED] → Match bank entries to book entries → 
Add adjustments for unreconciled items → [End: In progress]

[IN PROGRESS] → Complete → Validate matched amounts vs statement → 
[End: COMPLETED]
```

**Key behaviors:**
- Matches bank statement lines to book (GL) entries
- Unmatched entries shown as outstanding
- Adjustments create journal entries
- Completion validates total matches statement balance

### 4.4 Sub-Ledger Reconciliation Lifecycle

```
[Start] → Select dealer/supplier → Aggregate sub-ledger → 
Aggregate GL → Compare → [End: Reconciliation report]
```

**Key behaviors:**
- Compares dealer/supplier sub-ledger (from sales/purchasing) to GL
- Shows discrepancy if amounts differ
- Discrepancy resolution may require correcting either sub-ledger or GL

### 4.5 Opening Balance Import Lifecycle

```
[Start] → Validate company → Validate period → Validate mappings → 
Import account balances → [End: Opening balances set]
```

**Key behaviors:**
- Bulk import of account balances for new company setup
- Typically used during tenant onboarding
- Creates journal entries for opening balances

### 4.6 Tally Import Lifecycle

```
[Start] → Parse Tally XML → Map accounts → Validate → 
Create journals → [End: Tally data imported]
```

**Key behaviors:**
- Import from Tally accounting software XML export
- Maps Tally ledgers to ERP accounts
- Creates journal entries from imported data

---

## 5. Completion Boundary / Current Definition of Done

The flow is complete when:

1. **Journal Posted** — Journal moved to POSTED status, immutable
2. **Period Locked** — No more journals can be posted
3. **Period Closed** — Maker-checker workflow complete, period finalized
4. **Bank Reconciliation Complete** — All entries matched or adjusted
5. **Sub-Ledger Reconciled** — Dealer/supplier ledgers match GL
6. **GST Reconciled** — Output tax matches input tax calculation

### Current Limitations

1. **No automatic journal posting** — All journals manually posted

2. **Period close manual** — Requires manual request/approve/finalize workflow

3. **No scheduled reconciliation** — Must be run manually

4. **Correction via reversal only** — Posted journals cannot be edited, only reversed

5. **Opening balance limited** — Basic import only, complex migrations require custom work

6. **Tally import basic** — Limited to standard Tally XML format

7. **No automatic GST return filing** — Just reconciliation, no filing integration

---

## 6. Canonical vs Non-Canonical Paths

### Canonical Paths

| Path | Owner | Notes |
| --- | --- | --- |
| `POST /api/v1/accounting/journals` | `AccountingController` | Manual journal creation |
| `POST /api/v1/accounting/journals/{id}/post` | `AccountingController` | Journal posting |
| `POST /api/v1/accounting/periods/{id}/close-*` | `AccountingController` | Period close workflow |
| `POST /api/v1/accounting/reconciliation/sessions` | `AccountingController` | Bank reconciliation |
| `GET /api/v1/accounting/reconciliation/*` | `AccountingController` | Sub-ledger reconciliation |

### Non-Canonical / Deprecated Paths

| Path | Status | Notes |
| --- | --- | --- |
| Direct period reopen from CLOSED | Not supported | Closed periods cannot be reopened |
| Editing posted journals | Not supported | Must reverse and re-create |
| Automatic journal posting | Not implemented | Manual posting required |

---

## 7. Cross-Module Dependencies

| Module | Dependency | Direction |
| --- | --- | --- |
| `sales` | AR/revenue journals from dispatch, dealer ledger | Trigger (via sales) |
| `purchasing` | AP journals from purchase invoices, supplier ledger | Trigger (via purchasing) |
| `inventory` | Adjustment journals, valuation journals | Trigger (via inventory) |
| `factory` | WIP/cost journals from production | Trigger (via factory) |
| `hr` | Payroll journals from payroll posting | Trigger (via HR) |

## 8. Event/Listener Boundaries

The accounting/period-close flow is materially affected by internal event bridges that create accounting entries from other modules:

| Event | Listener | Phase | Effect on Accounting |
| --- | --- | --- | --- |
| `InventoryMovementEvent` | `InventoryAccountingEventListener` | `AFTER_COMMIT` | Inventory movements (including raw material receipts from P2P GRN and finished goods from manufacturing) automatically create valuation journal entries. This affects period-end inventory asset values. |
| `InventoryValuationChangedEvent` | `InventoryAccountingEventListener` | `AFTER_COMMIT` | Raw material and finished goods valuation changes trigger accounting entries, affecting cost of goods sold and inventory asset values. |
| `JournalEntryPostedEvent` | `JournalEntryPostedAuditListener` | `AFTER_COMMIT` (`fallbackExecution = true`) | Core audit trail marker is created when journals are posted, enabling audit trail lookups during period close verification. |

**Key boundary notes:**
- The `InventoryAccountingEventListener` is conditional on `erp.inventory.accounting.events.enabled` (default: `true`). If disabled, inventory movements silently skip accounting side effects, causing period-end inventory balances to be incomplete.
- These event bridges run in `REQUIRES_NEW` transactions, meaning accounting entries are committed independently of the source transaction. This provides resilience but also means event failures do not roll back the source operation.
- See [orchestrator.md](../modules/orchestrator.md) for the full event bridge map and configuration-guarded risks.

---

## 9. Security Considerations

- **RBAC** — Admin for period management, Accounting for daily operations
- **Company scoping** — All operations scoped to tenant
- **Period state enforcement** — Cannot post to closed/locked periods
- **Maker-checker** — Period close requires multiple roles

---

## 10. Related Documentation

- [docs/modules/MODULE-INVENTORY.md](../modules/MODULE-INVENTORY.md) — Accounting module reference
- [docs/modules/reports.md](../modules/reports.md) — Reporting module for period reports
- [docs/flows/FLOW-INVENTORY.md](FLOW-INVENTORY.md) — Flow inventory
- [docs/developer/accounting-flows/](../developer/accounting-flows/) — Internal accounting documentation

### Relevant ADRs
- [ADR-004-layered-audit-surfaces.md](../adrs/ADR-004-layered-audit-surfaces.md) — Audit trail layers (period close relies on JournalEntryPostedAuditListener for audit verification)
- [ADR-005-flyway-v2-hard-cut-migration-posture.md](../adrs/ADR-005-flyway-v2-hard-cut-migration-posture.md) — Migration posture (period-end data integrity depends on v2 migration completion)

---

## 11. Known Limitations

> **Note**: The authoritative classification for these items is recorded in the [Authoritative Recommendations Register](../RECOMMENDATIONS.md). This section documents factual implementation status only.

| Decision | Notes |
| --- | --- |
| Automatic journal posting | Not implemented. Manual posting required. |
| Scheduled reconciliation | Not implemented. Manual run required. |
| Automatic GST filing | Not implemented. Reconciliation only. |
| Edit posted journals | Not supported. Reversal required to correct posted entries. |
