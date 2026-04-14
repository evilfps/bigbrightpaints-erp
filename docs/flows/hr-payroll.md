# HR / Payroll Flow

Last reviewed: 2026-03-30

This packet documents the **HR and payroll flow**: the canonical lifecycle for employee management, leave management, attendance tracking, and the complete payroll run lifecycle. It covers the payroll calculation boundary, the accounting posting seam, and the payment recording boundary.

This flow is **behavior-first** and **code-grounded**. Where the backend is incomplete, blocked, or intentionally partial, the packet explicitly states the current limitation instead of presenting partial behavior as complete.

---

## 1. Actors

| Actor | Role | Authorization Scope |
| --- | --- | --- |
| **Admin** | Full HR and payroll access | `ROLE_ADMIN` |
| **Accounting** | Payroll run and posting access | `ROLE_ACCOUNTING` |
| **Employee** (via Admin) | Subject of HR operations | Not direct API access |

---

## 2. Entrypoints

### Employee Management — `HrController` (`/api/v1/hr/employees/**`)

| Entrypoint | Method | Path | Actor | Purpose |
| --- | --- | --- | --- | --- |
| List Employees | GET | `/api/v1/hr/employees` | ADMIN, ACCOUNTING | List all employees |
| Get Employee | GET | `/api/v1/hr/employees/{id}` | ADMIN, ACCOUNTING | Get employee detail |
| Create Employee | POST | `/api/v1/hr/employees` | ADMIN | Create employee |
| Update Employee | PUT | `/api/v1/hr/employees/{id}` | ADMIN | Update employee |
| Delete Employee | DELETE | `/api/v1/hr/employees/{id}` | ADMIN | Soft-delete employee |

### Leave Management — `HrController` (`/api/v1/hr/leave-**`)

| Entrypoint | Method | Path | Actor | Purpose |
| --- | --- | --- | --- | --- |
| List Leave Requests | GET | `/api/v1/hr/leave-requests` | ADMIN, ACCOUNTING | List leave requests |
| Create Leave Request | POST | `/api/v1/hr/leave-requests` | ADMIN | Submit leave request |
| Approve Leave | POST | `/api/v1/hr/leave-requests/{id}/approve` | ADMIN | Approve request |
| Reject Leave | POST | `/api/v1/hr/leave-requests/{id}/reject` | ADMIN | Reject request |
| List Leave Types | GET | `/api/v1/hr/leave-types` | ADMIN, ACCOUNTING | List leave types |
| Create Leave Type | POST | `/api/v1/hr/leave-types` | ADMIN | Create leave type |
| Get Leave Balances | GET | `/api/v1/hr/employees/{employeeId}/leave-balances` | ADMIN, ACCOUNTING | View leave balances |

### Attendance — `HrController` (`/api/v1/hr/attendance/**`)

| Entrypoint | Method | Path | Actor | Purpose |
| --- | --- | --- | --- | --- |
| Mark Attendance | POST | `/api/v1/hr/attendance/date/{date}` | ADMIN | Mark single employee |
| Bulk Mark | POST | `/api/v1/hr/attendance/bulk-mark` | ADMIN | Bulk attendance |
| Import Attendance | POST | `/api/v1/hr/attendance/bulk-import` | ADMIN | Import from file |
| Get Summary | GET | `/api/v1/hr/attendance/summary` | ADMIN, ACCOUNTING | Attendance summary |

### Salary Structures — `HrController` (`/api/v1/hr/salary-structures/**`)

| Entrypoint | Method | Path | Actor | Purpose |
| --- | --- | --- | --- | --- |
| List Structures | GET | `/api/v1/hr/salary-structures` | ADMIN, ACCOUNTING | List templates |
| Create Structure | POST | `/api/v1/hr/salary-structures` | ADMIN | Create template |
| Update Structure | PUT | `/api/v1/hr/salary-structures/{id}` | ADMIN | Update template |

### Payroll Run — `HrPayrollController` (`/api/v1/payroll/**`)

| Entrypoint | Method | Path | Actor | Purpose |
| --- | --- | --- | --- | --- |
| List Runs | GET | `/api/v1/payroll/runs` | ADMIN, ACCOUNTING | List payroll runs |
| Get Run | GET | `/api/v1/payroll/runs/{id}` | ADMIN, ACCOUNTING | Get run detail |
| Create Run | POST | `/api/v1/payroll/runs` | ADMIN, ACCOUNTING | Create run |
| Create Weekly | POST | `/api/v1/payroll/runs/weekly` | ADMIN, ACCOUNTING | Create weekly run |
| Create Monthly | POST | `/api/v1/payroll/runs/monthly` | ADMIN, ACCOUNTING | Create monthly run |
| Calculate | POST | `/api/v1/payroll/runs/{id}/calculate` | ADMIN, ACCOUNTING | Calculate earnings/deductions |
| Approve | POST | `/api/v1/payroll/runs/{id}/approve` | ADMIN, ACCOUNTING | Approve run |
| Post to Accounting | POST | `/api/v1/payroll/runs/{id}/post` | ADMIN, ACCOUNTING | Post journal to accounting |
| Mark Paid | POST | `/api/v1/payroll/runs/{id}/mark-paid` | ADMIN, ACCOUNTING | Record payment reference |
| Weekly Summary | GET | `/api/v1/payroll/summary/weekly` | ADMIN, ACCOUNTING | Weekly summary |
| Monthly Summary | GET | `/api/v1/payroll/summary/monthly` | ADMIN, ACCOUNTING | Monthly summary |

---

## 3. Preconditions

### Employee Creation Preconditions

1. **Employee type valid** — STAFF (monthly) or LABOUR (daily)
2. **Payment schedule valid** — MONTHLY or WEEKLY
3. **Required fields** — name, type, payment schedule
4. **Salary structure if STAFF** — template assigned for salary calculation
5. **Statutory details optional** — PF, ESI, PAN if provided

### Leave Request Preconditions

1. **Leave type exists** — valid leave type ID
2. **Balance available** — sufficient leave balance for period
3. **Dates valid** — start <= end, within current year

### Attendance Preconditions

1. **Employee exists** — valid employee ID
2. **Date not in future** — attendance date <= today
3. **Not already marked** — no duplicate attendance for same employee/date

### Payroll Run Preconditions

1. **HR module enabled** — tenant has HR module enabled
2. **Period+type unique** — cannot create duplicate run for same period/type
3. **Employees exist** — at least one ACTIVE employee
4. **Required accounts exist** — SALARY-EXP, SALARY-PAYABLE, etc.
5. **Period open** — accounting period open for posting

### Payroll Calculation Preconditions

1. **Run in DRAFT status** — not already calculated
2. **Employees exist** — at least one ACTIVE employee
3. **Attendance marked** — attendance data for period (or defaults applied)

### Payroll Posting Preconditions

1. **Run in APPROVED status** — calculated and approved
2. **Required accounts exist** — all payroll accounts present
3. **Period open** — accounting period open

---

## 4. Lifecycle

### 4.1 Employee Lifecycle

```
[Start] → Validate employee data → Assign salary structure → 
Create employee → [End: Employee ACTIVE]
```

**Key behaviors:**
- Employee types: STAFF (monthly salary) and LABOUR (daily wage)
- Payment schedules: MONTHLY for staff, WEEKLY for labour
- Status: ACTIVE by default on creation
- Statutory details: PF, ESI, PAN, tax regime (OLD/NEW) tracked

### 4.2 Leave Request Lifecycle

```
[Start] → Validate leave type → Check balance → Create request → 
[End: PENDING]

[PENDING] → Approve → Update balance → [APPROVED]
[PENDING] → Reject → [REJECTED]
```

**Key behaviors:**
- Leave balances tracked per year per employee
- Approval updates remaining balance

### 4.3 Attendance Lifecycle

```
[Start] → Validate employee → Validate date → 
Mark attendance → [End: Attendance recorded]
```

**Key behaviors:**
- Single employee or bulk marking
- Import from external systems supported
- Attendance linked to payroll calculation

### 4.4 Payroll Run Lifecycle

```
[Start] → Validate period+type unique → Create run → 
[End: DRAFT]

[DRAFT] → Calculate → Compute earnings/deductions → 
[End: CALCULATED]

[CALCULATED] → Approve → [APPROVED]

[APPROVED] → Post to Accounting → Create journal → 
[End: POSTED]

[POSTED] → Mark Paid → Record payment reference → 
[End: PAID (terminal)]
```

**Key behaviors:**
- **Idempotency**: Period+type uniqueness enforced
- **Calculation**: Earnings (gross), deductions (PF, ESI, TDS, professional tax), net pay
- **Posting**: Creates journal entries via AccountingFacade
- **Payment**: Records reference from accounting payment journal

### 4.5 Payroll Posting Seam (HR → Accounting)

```
HR Module → AccountingFacade.postPayrollRun()
  └─> Creates journal entry:
        - DR SALARY-EXP / DR WAGE-EXP
        - CR SALARY-PAYABLE
        - CR PF-PAYABLE / CR ESI-PAYABLE / CR TDS-PAYABLE / CR PROFESSIONAL-TAX-PAYABLE
        - CR EMP-ADV (if advances)
```

### 4.6 Payroll Payment Seam (Accounting → HR)

```
1. Accounting creates payment journal: /api/v1/accounting/payroll/payments
2. HR marks run as PAID via: POST /api/v1/payroll/runs/{id}/mark-paid
3. Payment reference linked back to payroll run
```

---

## 5. Completion Boundary / Current Definition of Done

The flow is complete when:

1. **Employee Created** — Employee exists with type, salary structure
2. **Attendance Recorded** — Attendance marked for pay period
3. **Payroll Calculated** — Earnings and deductions computed
4. **Payroll Approved** — Ready for posting
5. **Payroll Posted** — Journal entries in accounting
6. **Payroll Paid** — Payment reference recorded, run complete

### Current Limitations

1. **HR module paused by default** — New tenants have HR disabled (ERP-33), must be enabled by super-admin

2. **Indian regulatory context only** — PF, ESI, TDS, professional tax are India-specific

3. **No automatic attendance-to-payroll sync** — Must manually recalculate after attendance changes

4. **No employee self-service** — Leave requests admin-only, no employee portal

5. **No multi-currency payroll** — Single currency only

6. **Accounting-host payment seam** — Payroll not fully self-contained, depends on accounting for payment recording

---

## 6. Canonical vs Non-Canonical Paths

### Canonical Paths

| Path | Owner | Notes |
| --- | --- | --- |
| `POST /api/v1/payroll/runs` | `HrPayrollController` | Primary payroll run creation |
| `POST /api/v1/payroll/runs/{id}/calculate` | `HrPayrollController` | Payroll calculation |
| `POST /api/v1/payroll/runs/{id}/post` | `HrPayrollController` | Post to accounting (via AccountingFacade) |
| `POST /api/v1/payroll/runs/{id}/mark-paid` | `HrPayrollController` | Payment recording |
| `GET /api/v1/hr/employees` | `HrController` | Employee management |

### Non-Canonical / Deprecated Paths

| Path | Status | Replacement |
| --- | --- | --- |
| `GET /api/v1/hr/payroll-runs` | Deprecated (410) | Use `/api/v1/payroll/runs` |
| `POST /api/v1/hr/payroll-runs` | Deprecated (410) | Use `/api/v1/payroll/runs` |
| Legacy HR payroll endpoints | Deprecated | Use `/api/v1/payroll/**` |

---

## 7. Cross-Module Dependencies

| Module | Dependency | Direction |
| --- | --- | --- |
| `accounting` | Payroll posting via AccountingFacade, payment recording via accounting journal | Write (post), Write (payment reference) |
| `company` | Module gating — HR module can be enabled/disabled per tenant | Read (gating), Read (context) |

## 8. Event/Listener Boundaries

The HR/payroll flow intersects with the order auto-approval event bridge which can indirectly affect workforce planning and payroll:

| Event | Listener | Phase | Effect on HR/Payroll |
| --- | --- | --- | --- |
| `SalesOrderCreatedEvent` | `OrderAutoApprovalListener` | `AFTER_COMMIT` | When auto-approval is enabled, sales orders are automatically approved after commit. This can trigger inventory reservation and production scheduling, which indirectly affects workforce demand in factory/operations. However, this listener does not directly affect payroll calculation—payroll is independent of sales order processing. |

**Key boundary note:** The `OrderAutoApprovalListener` is conditional on `SystemSettingsService.isAutoApprovalEnabled()`. When disabled, orders stay in their initial status until manually approved. This event bridge does not directly impact the HR/payroll flow but represents a cross-module coordination point where sales order processing can cascade into production scheduling, which may affect workforce planning in factory operations.

The payroll flow itself does not publish events that trigger downstream listeners—it is primarily a consumer of accounting services for posting and payment recording.

---

## 9. Security Considerations

- **RBAC** — Admin for employee/leave, Accounting for payroll operations
- **Company scoping** — All HR data scoped to tenant
- **Module gating** — HR endpoints return MODULE_DISABLED if tenant has HR disabled
- **Period context** — Payroll tied to accounting periods

---

## 10. Related Documentation

- [docs/modules/hr.md](../modules/hr.md) — HR module canonical packet
- [docs/modules/MODULE-INVENTORY.md](../modules/MODULE-INVENTORY.md) — Module inventory
- [docs/flows/FLOW-INVENTORY.md](FLOW-INVENTORY.md) — Flow inventory
- [docs/workflows/payroll.md](../workflows/payroll.md) — Historical operational guide
- [docs/frontend-portals/accounting/README.md](../frontend-portals/accounting/README.md) — Accounting frontend handoff (HR and payroll accounting payloads, RBAC)
- [docs/deprecated/INDEX.md](../deprecated/INDEX.md) — Deprecated surfaces registry (legacy /hr/payroll-runs endpoints)

### Relevant ADRs
- [ADR-002-multi-tenant-auth-scoping.md](../adrs/ADR-002-multi-tenant-auth-scoping.md) — Multi-tenant auth scoping (payroll data must be scoped by tenant/company)
- [ADR-004-layered-audit-surfaces.md](../adrs/ADR-004-layered-audit-surfaces.md) — Audit trail layers (payroll posting creates audit markers in accounting)

---

## 11. Known Limitations

> **Note**: The authoritative classification for these items is recorded in the [Authoritative Recommendations Register](../RECOMMENDATIONS.md). This section documents factual implementation status only.

| Decision | Notes |
| --- | --- |
| HR module default enabled | Paused by default. ERP-33 requires super-admin enable after completion. |
| Non-Indian payroll | Not supported. PF/ESI/TDS are India-specific. |
| Employee self-service | Not implemented. Leave requests are admin-only. |
| Multi-currency payroll | Not supported. Single currency only. |
