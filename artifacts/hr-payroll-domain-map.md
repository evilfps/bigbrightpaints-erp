# HR / Payroll Domain Map

> Auto-generated deep investigation of the BigBright Paints ERP HR/Payroll module.
> Source root: `erp-domain/src/main/java/com/bigbrightpaints/erp/modules/hr`

---

## 1. Module Structure

```
modules/hr/
├── controller/
│   ├── HrController.java              # Employee, Attendance, Leave, Salary Structures
│   └── HrPayrollController.java       # Payroll runs & workflow
├── domain/
│   ├── Employee.java                  # Employee master entity
│   ├── EmployeeRepository.java
│   ├── Attendance.java                # Daily attendance record
│   ├── AttendanceRepository.java
│   ├── LeaveRequest.java              # Leave application
│   ├── LeaveRequestRepository.java
│   ├── LeaveBalance.java              # Yearly leave balance per type
│   ├── LeaveBalanceRepository.java
│   ├── LeaveTypePolicy.java           # Leave type configuration
│   ├── LeaveTypePolicyRepository.java
│   ├── PayrollRun.java                # Payroll batch/run header
│   ├── PayrollRunRepository.java
│   ├── PayrollRunLine.java            # Per-employee payroll line
│   ├── PayrollRunLineRepository.java
│   ├── SalaryStructureTemplate.java   # Salary component template
│   └── SalaryStructureTemplateRepository.java
├── dto/
│   ├── EmployeeRequest.java           # Create/update employee request
│   ├── EmployeeDto.java               # Employee response
│   ├── MarkAttendanceRequest.java     # Single attendance mark
│   ├── BulkMarkAttendanceRequest.java # Bulk attendance mark
│   ├── AttendanceBulkImportRequest.java # Multi-date bulk import
│   ├── AttendanceDto.java
│   ├── AttendanceSummaryDto.java
│   ├── MonthlyAttendanceSummaryDto.java
│   ├── LeaveRequestRequest.java
│   ├── LeaveRequestDto.java
│   ├── LeaveStatusUpdateRequest.java
│   ├── LeaveBalanceDto.java
│   ├── LeaveTypePolicyDto.java
│   ├── SalaryStructureTemplateRequest.java
│   ├── SalaryStructureTemplateDto.java
│   ├── PayrollRunRequest.java
│   └── PayrollRunDto.java
└── service/
    ├── HrService.java                 # Facade for employee/leave/attendance/salary
    ├── EmployeeService.java           # Employee CRUD + bank detail encryption
    ├── AttendanceService.java         # Attendance marking (single/bulk/import)
    ├── LeaveService.java              # Leave requests + balance management
    ├── SalaryStructureTemplateService.java  # Salary component templates
    ├── PayrollService.java            # Payroll facade (orchestrates run/calc/post)
    ├── PayrollRunService.java         # Payroll run creation + idempotency
    ├── PayrollCalculationService.java # Pay calculation engine
    ├── PayrollPostingService.java     # Approve, post to GL, mark paid
    ├── PayrollCalculationSupport.java # Helper: standard hours, loan deduction cap
    └── StatutoryDeductionEngine.java  # PF, ESI, TDS, Professional Tax
```

### Cross-module dependencies
- **Accounting** (`modules/accounting`): `AccountingFacade` for posting journal entries and recording payroll payments
- **Company** (`modules/company`): `Company`, `CompanyContextService`, `CompanyClock`
- **Core**: `AuditService`, `CryptoService`, `SecurityActorResolver`, `IdempotencyUtils`, `ValidationUtils`, `CompanyEntityLookup`

---

## 2. HR API Endpoints

### HrController (`/api/v1/hr`)

| Method | Path | Description |
|--------|------|-------------|
| `GET` | `/api/v1/hr/employees` | List all employees |
| `POST` | `/api/v1/hr/employees` | Create employee |
| `PUT` | `/api/v1/hr/employees/{id}` | Update employee |
| `DELETE` | `/api/v1/hr/employees/{id}` | Delete employee |
| `GET` | `/api/v1/hr/salary-structures` | List salary structure templates |
| `POST` | `/api/v1/hr/salary-structures` | Create salary structure template |
| `PUT` | `/api/v1/hr/salary-structures/{id}` | Update salary structure template |
| `GET` | `/api/v1/hr/leave-requests` | List all leave requests |
| `POST` | `/api/v1/hr/leave-requests` | Create leave request |
| `PATCH` | `/api/v1/hr/leave-requests/{id}/status` | Approve/reject/cancel leave |
| `GET` | `/api/v1/hr/leave-types` | List active leave type policies |
| `GET` | `/api/v1/hr/employees/{employeeId}/leave-balances` | Get leave balances (per year) |
| `GET` | `/api/v1/hr/attendance/today` | Today's attendance |
| `GET` | `/api/v1/hr/attendance/date/{date}` | Attendance for specific date |
| `GET` | `/api/v1/hr/attendance/summary` | Today's attendance summary |
| `GET` | `/api/v1/hr/attendance/summary/monthly` | Monthly attendance summary |
| `GET` | `/api/v1/hr/attendance/employee/{employeeId}` | Employee attendance date range |
| `POST` | `/api/v1/hr/attendance/mark/{employeeId}` | Mark single attendance |
| `POST` | `/api/v1/hr/attendance/bulk-mark` | Bulk mark attendance for date |
| `POST` | `/api/v1/hr/attendance/bulk-import` | Multi-date bulk import |
| `GET` | `/api/v1/hr/payroll-runs` | **DEPRECATED** (returns 410 Gone) |
| `POST` | `/api/v1/hr/payroll-runs` | **DEPRECATED** (returns 410 Gone) |

### HrPayrollController (`/api/v1/payroll`)

| Method | Path | Description |
|--------|------|-------------|
| `GET` | `/api/v1/payroll/runs` | List all payroll runs |
| `GET` | `/api/v1/payroll/runs/weekly` | List weekly payroll runs |
| `GET` | `/api/v1/payroll/runs/monthly` | List monthly payroll runs |
| `GET` | `/api/v1/payroll/runs/{id}` | Get payroll run detail |
| `GET` | `/api/v1/payroll/runs/{id}/lines` | Get payroll run line items |
| `POST` | `/api/v1/payroll/runs` | Create payroll run (generic) |
| `POST` | `/api/v1/payroll/runs/weekly` | Create weekly payroll run |
| `POST` | `/api/v1/payroll/runs/monthly` | Create monthly payroll run |
| `POST` | `/api/v1/payroll/runs/{id}/calculate` | Calculate pay for run |
| `POST` | `/api/v1/payroll/runs/{id}/approve` | Approve payroll run |
| `POST` | `/api/v1/payroll/runs/{id}/post` | Post to accounting (GL) |
| `POST` | `/api/v1/payroll/runs/{id}/mark-paid` | Mark as paid |
| `GET` | `/api/v1/payroll/summary/weekly` | Weekly pay summary (preview) |
| `GET` | `/api/v1/payroll/summary/monthly` | Monthly pay summary (preview) |
| `GET` | `/api/v1/payroll/summary/current-week` | Current week pay summary |
| `GET` | `/api/v1/payroll/summary/current-month` | Current month pay summary |

### Accounting Payroll Endpoints (`/api/v1/accounting`)

| Method | Path | Description |
|--------|------|-------------|
| `POST` | `/api/v1/accounting/payroll/payments` | Record payroll payment (creates payment journal entry) |

**All endpoints require `ROLE_ADMIN` or `ROLE_ACCOUNTING`.**

---

## 3. Entity Map

### 3.1 Employee (`employees` table)

| Field | Type | Description |
|-------|------|-------------|
| `id` | Long | Primary key |
| `public_id` | UUID | External identifier |
| `company_id` | FK → Company | Tenant |
| `first_name` | String | Required |
| `last_name` | String | Required |
| `email` | String | Required, unique per company |
| `phone` | String | Optional |
| `role` | String | Job role title |
| `status` | String | Default "ACTIVE" |
| `hired_date` | LocalDate | Hire date |
| `date_of_birth` | LocalDate | DOB |
| `gender` | Enum | MALE, FEMALE, OTHER, UNDISCLOSED |
| `emergency_contact_name` | String | |
| `emergency_contact_phone` | String | |
| `department` | String | |
| `designation` | String | |
| `date_of_joining` | LocalDate | |
| `employment_type` | Enum | FULL_TIME, PART_TIME, CONTRACT, INTERN |
| **employee_type** | **Enum** | **STAFF (monthly salary) or LABOUR (daily wage)** |
| `monthly_salary` | BigDecimal | For STAFF |
| `daily_wage` | BigDecimal | For LABOUR |
| **payment_schedule** | **Enum** | **MONTHLY (staff) or WEEKLY (labour)** |
| `working_days_per_month` | Integer | Default 26 |
| `weekly_off_days` | Integer | Default 1 |
| `salary_structure_template_id` | FK → SalaryStructureTemplate | Optional template |
| `pf_number` | String | Provident Fund number |
| `esi_number` | String | ESI number |
| `pan_number` | String | PAN (validated format: AAAAA9999A) |
| `tax_regime` | Enum | OLD or NEW (default NEW) |
| `bank_account_number_encrypted` | String | Encrypted bank account |
| `bank_name_encrypted` | String | Encrypted bank name |
| `ifsc_code_encrypted` | String | Encrypted IFSC code |
| `bank_branch_encrypted` | String | Encrypted bank branch |
| `advance_balance` | BigDecimal | Outstanding advance (deducted from salary) |
| `overtime_rate_multiplier` | BigDecimal | Default 1.5x |
| `double_ot_rate_multiplier` | BigDecimal | Default 2.0x |
| `standard_hours_per_day` | BigDecimal | Default 8 |

**Unique constraint**: `(company_id, email)`

### 3.2 Attendance (`attendance` table)

| Field | Type | Description |
|-------|------|-------------|
| `id` | Long | PK |
| `company_id` | FK → Company | Tenant |
| `employee_id` | FK → Employee | Required |
| `attendance_date` | LocalDate | Required |
| `status` | Enum | PRESENT, HALF_DAY, ABSENT, LEAVE, HOLIDAY, WEEKEND |
| `check_in_time` | LocalTime | |
| `check_out_time` | LocalTime | |
| `marked_by` | String | Username who marked |
| `marked_at` | Instant | Auto-set on persist |
| `remarks` | String | |
| `is_holiday` | boolean | |
| `is_weekend` | boolean | |
| `regular_hours` | BigDecimal(5,2) | Standard work hours |
| `overtime_hours` | BigDecimal(5,2) | Extra hours |
| `double_overtime_hours` | BigDecimal(5,2) | Holiday OT hours |
| `base_pay` | BigDecimal(19,2) | Calculated base pay |
| `overtime_pay` | BigDecimal(19,2) | Calculated OT pay |
| `total_pay` | BigDecimal(19,2) | Calculated total |
| `payroll_run_id` | Long | Links to PayrollRun when processed |

**Unique constraint**: `(company_id, employee_id, attendance_date)`

### 3.3 LeaveRequest (`leave_requests` table)

| Field | Type | Description |
|-------|------|-------------|
| `id` | Long | PK |
| `public_id` | UUID | External ID |
| `company_id` | FK → Company | |
| `employee_id` | FK → Employee | |
| `leave_type` | String | Required |
| `start_date` | LocalDate | Required |
| `end_date` | LocalDate | Required |
| `status` | String | PENDING, APPROVED, REJECTED, CANCELLED |
| `reason` | String | |
| `total_days` | BigDecimal(10,2) | Calculated inclusive days |
| `decision_reason` | String | |
| `approved_by` | String | Username |
| `approved_at` | Instant | |
| `rejected_by` | String | Username |
| `rejected_at` | Instant | |
| `created_at` | Instant | |

### 3.4 LeaveBalance (`leave_balances` table)

| Field | Type | Description |
|-------|------|-------------|
| `id` | Long | PK |
| `public_id` | UUID | |
| `company_id` | FK | |
| `employee_id` | FK → Employee | |
| `leave_type` | String | |
| `balance_year` | Integer | |
| `opening_balance` | BigDecimal(10,2) | From carry-forward |
| `accrued` | BigDecimal(10,2) | Annual entitlement |
| `used` | BigDecimal(10,2) | Approved leave days used |
| `remaining` | BigDecimal(10,2) | = opening + accrued - used |
| `carry_forward_applied` | BigDecimal(10,2) | |
| `last_recalculated_at` | Instant | |

**Unique constraint**: `(company_id, employee_id, leave_type, balance_year)`

### 3.5 LeaveTypePolicy (`leave_type_policies` table)

| Field | Type | Description |
|-------|------|-------------|
| `id` | Long | PK |
| `public_id` | UUID | |
| `company_id` | FK | |
| `leave_type` | String | Required, unique per company |
| `display_name` | String | Required |
| `annual_entitlement` | BigDecimal(10,2) | Days per year |
| `carry_forward_limit` | BigDecimal(10,2) | Max carry-forward days |
| `active` | boolean | Default true |
| `created_at` | Instant | |

### 3.6 SalaryStructureTemplate (`salary_structure_templates` table)

| Field | Type | Description |
|-------|------|-------------|
| `id` | Long | PK |
| `public_id` | UUID | |
| `company_id` | FK | |
| `code` | String | Unique per company |
| `name` | String | |
| `description` | String | |
| `basic_pay` | BigDecimal(19,2) | Basic component |
| `hra` | BigDecimal(19,2) | House Rent Allowance |
| `da` | BigDecimal(19,2) | Dearness Allowance |
| `special_allowance` | BigDecimal(19,2) | Special allowance |
| `employee_pf_rate` | BigDecimal(5,2) | Default 12.00% |
| `employee_esi_rate` | BigDecimal(5,2) | Default 0.75% |
| `esi_eligibility_threshold` | BigDecimal(19,2) | Default ₹21,000 |
| `professional_tax` | BigDecimal(19,2) | Default ₹200 |
| `active` | boolean | Default true |
| `created_at` | Instant | |

### 3.7 PayrollRun (`payroll_runs` table)

| Field | Type | Description |
|-------|------|-------------|
| `id` | Long | PK |
| `public_id` | UUID | |
| `company_id` | FK → Company | |
| `run_number` | String | e.g., PR-W-2025-W48, PR-M-2025-11 |
| `run_type` | Enum | WEEKLY or MONTHLY |
| `period_start` | LocalDate | Required |
| `period_end` | LocalDate | Required |
| `status` | Enum | DRAFT → CALCULATED → APPROVED → POSTED → PAID / CANCELLED |
| `total_employees` | Integer | |
| `total_present_days` | BigDecimal(10,2) | |
| `total_overtime_hours` | BigDecimal(10,2) | |
| `total_base_pay` | BigDecimal(19,2) | |
| `total_overtime_pay` | BigDecimal(19,2) | |
| `total_deductions` | BigDecimal(19,2) | |
| `total_net_pay` | BigDecimal(19,2) | |
| `journal_entry_id` | Long | Posting journal link |
| `journal_entry_ref_id` | FK → JournalEntry | ORM link to posting journal |
| `payment_journal_entry_id` | Long | Payment journal link |
| `payment_reference` | String | |
| `payment_date` | LocalDate | |
| `idempotency_key` | String | For deduplication |
| `idempotency_hash` | String | Request signature |
| `created_by` | String | |
| `created_at` | Instant | |
| `approved_by` | String | |
| `approved_at` | Instant | |
| `posted_by` | String | |
| `posted_at` | Instant | |
| `remarks` | String | |

### 3.8 PayrollRunLine (`payroll_run_lines` table)

| Field | Type | Description |
|-------|------|-------------|
| `id` | Long | PK |
| `payroll_run_id` | FK → PayrollRun | |
| `employee_id` | FK → Employee | |
| **Attendance summary** | | |
| `present_days` | BigDecimal(5,2) | |
| `half_days` | BigDecimal(5,2) | |
| `absent_days` | BigDecimal(5,2) | |
| `leave_days` | BigDecimal(5,2) | |
| `holiday_days` | BigDecimal(5,2) | |
| **Hours** | | |
| `regular_hours` | BigDecimal(10,2) | |
| `overtime_hours` | BigDecimal(10,2) | |
| `double_ot_hours` | BigDecimal(10,2) | |
| **Rate info** | | |
| `daily_rate` | BigDecimal(19,2) | Captured at calculation time |
| `hourly_rate` | BigDecimal(19,2) | daily_rate / standard_hours |
| `ot_rate_multiplier` | BigDecimal(5,2) | Default 1.5x |
| `double_ot_multiplier` | BigDecimal(5,2) | Default 2.0x |
| **Earnings** | | |
| `base_pay` | BigDecimal(19,2) | |
| `overtime_pay` | BigDecimal(19,2) | |
| `holiday_pay` | BigDecimal(19,2) | |
| `gross_pay` | BigDecimal(19,2) | base + OT + holiday |
| `basic_salary_component` | BigDecimal(19,2) | From salary structure |
| `hra_component` | BigDecimal(19,2) | |
| `da_component` | BigDecimal(19,2) | |
| `special_allowance_component` | BigDecimal(19,2) | |
| **Deductions** | | |
| `advance_deduction` | BigDecimal(19,2) | Loan/advance recovery |
| `pf_deduction` | BigDecimal(19,2) | Provident Fund |
| `tax_deduction` | BigDecimal(19,2) | TDS |
| `esi_deduction` | BigDecimal(19,2) | ESI |
| `professional_tax_deduction` | BigDecimal(19,2) | |
| `loan_deduction` | BigDecimal(19,2) | |
| `leave_without_pay_deduction` | BigDecimal(19,2) | |
| `other_deductions` | BigDecimal(19,2) | |
| `total_deductions` | BigDecimal(19,2) | Sum of all deductions |
| **Net** | | |
| `net_pay` | BigDecimal(19,2) | gross - total_deductions (min 0) |
| `payment_status` | Enum | PENDING, PROCESSING, PAID, FAILED, CANCELLED |
| `payment_reference` | String | |

---

## 4. Employee Master Structure

### Key design decisions:
- **Two employee types**: `STAFF` (monthly salary) and `LABOUR` (daily wage)
- **Payment schedules**: `MONTHLY` for staff, `WEEKLY` for labourers
- **Bank details are encrypted** at rest using `CryptoService` - plain text fields (`bankAccountNumber`, `bankName`, `ifscCode`) are nulled after encryption; encrypted variants stored in `*_encrypted` columns
- **Salary structure** can be assigned via template or set directly on employee
- **Advance balance** tracks outstanding advance payments, deducted during payroll

### Employee daily rate calculation:
```
STAFF: daily_rate = monthly_salary / working_days_per_month (default 26)
LABOUR: daily_rate = daily_wage
```

### Employee → SalaryStructureTemplate relationship:
- Optional FK link
- When template is assigned without explicit `monthlySalary`, the template's `totalEarnings()` becomes the monthly salary
- Template provides: basic, HRA, DA, special allowance components, PF rate, ESI rate, ESI threshold, professional tax

---

## 5. Attendance Input Flows

### 5.1 Single Mark
`POST /api/v1/hr/attendance/mark/{employeeId}`

Request: `MarkAttendanceRequest(date?, status, checkInTime?, checkOutTime?, regularHours?, overtimeHours?, doubleOvertimeHours?, holiday, weekend, remarks?)`

- If date is null, uses company clock "today"
- Upserts: if attendance already exists for (company, employee, date), updates it
- Records who marked it (`markedBy` = current user) and when (`markedAt` = company time)
- `status` must be one of: PRESENT, HALF_DAY, ABSENT, LEAVE, HOLIDAY, WEEKEND

### 5.2 Bulk Mark (same date, multiple employees)
`POST /api/v1/hr/attendance/bulk-mark`

Request: `BulkMarkAttendanceRequest(employeeIds, date, status, checkInTime?, checkOutTime?, regularHours?, overtimeHours?, remarks?)`

- Validates all employee IDs exist for the current company
- De-duplicates employee IDs
- Upserts all attendance records
- Returns results in the same order as input employee IDs

### 5.3 Bulk Import (multi-date, multi-employee)
`POST /api/v1/hr/attendance/bulk-import`

Request: `AttendanceBulkImportRequest(records: List<BulkMarkAttendanceRequest>)`

- Iterates over records, calling `bulkMarkAttendance` for each
- Non-transactional across records (individual records succeed/fail independently)

### 5.4 Query Endpoints
- Today's attendance: `GET /api/v1/hr/attendance/today`
- By date: `GET /api/v1/hr/attendance/date/{date}`
- By employee + range: `GET /api/v1/hr/attendance/employee/{employeeId}?startDate=&endDate=`
- Today summary: `GET /api/v1/hr/attendance/summary` → total, present, absent, half-day, leave, not-marked counts
- Monthly summary: `GET /api/v1/hr/attendance/summary/monthly?year=&month=` → grouped by employee with day counts + OT hours

---

## 6. Leave Management

### 6.1 Leave Type Policies
- Configured per company: leave type code, display name, annual entitlement, carry-forward limit
- Example: `SICK_LEAVE`, `CASUAL_LEAVE`, `EARNED_LEAVE`, `PRIVILEGED_LEAVE`
- Active flag controls visibility

### 6.2 Leave Balance Lifecycle
1. **Auto-created on first access**: When leave balances are queried or a leave is approved, `ensureBalance()` creates a `LeaveBalance` record for the (employee, leave_type, year) if not present
2. **Opening balance** = carry-forward from previous year's remaining (capped by policy's `carryForwardLimit`)
3. **Accrued** = policy's `annualEntitlement`
4. **Remaining** = opening_balance + accrued - used
5. **Recalculated on approval/rejection**: When leave is approved, `used` increases and `remaining` decreases. If rejected after approval, the delta is reverted.

### 6.3 Leave Request Flow
1. **Create** (`POST /api/v1/hr/leave-requests`):
   - Validates: employee exists, date range valid, no overlapping non-rejected/non-cancelled requests
   - Computes `totalDays` = inclusive days between start and end
   - Default status: PENDING (but can be created directly as APPROVED or REJECTED)
   - If created as APPROVED: immediately deducts balance

2. **Update Status** (`PATCH /api/v1/hr/leave-requests/{id}/status`):
   - Transition to APPROVED: deduct balance, set approvedBy/approvedAt
   - Transition to REJECTED: set rejectedBy/rejectedAt
   - Transition from APPROVED → non-APPROVED: revert balance delta
   - Cannot go from APPROVED back to PENDING

### 6.4 Validation Rules
- `startDate` and `endDate` are required; `endDate >= startDate`
- Overlapping leave requests are rejected for the same employee
- Insufficient balance check on approval
- `leaveType` is normalized to uppercase

---

## 7. Payroll Run Flow

### 7.1 State Machine

```
DRAFT ──→ CALCULATED ──→ APPROVED ──→ POSTED ──→ PAID
  │            │              │           │
  └── CANCELLED ←─────────────┘           │
                                          │
                                     (requires payment journal)
```

### 7.2 Create Payroll Run
**Ways to create:**
1. `POST /api/v1/payroll/runs` - Generic (specify runType, periodStart, periodEnd)
2. `POST /api/v1/payroll/runs/weekly?weekEndingDate=` - Auto-calculates Mon-Sat period
3. `POST /api/v1/payroll/runs/monthly?year=&month=` - Auto-calculates month start/end

**Run number format:**
- Weekly: `PR-W-{year}-W{week}` (e.g., PR-W-2025-W48)
- Monthly: `PR-M-{year}-{month}` (e.g., PR-M-2025-11)

**Idempotency:**
- Key: `PAYROLL:{runType}:{periodStart}:{periodEnd}`
- Hash: SHA-256 of (runType + periodStart + periodEnd + remarks)
- If an existing run with the same idempotency key exists and signature matches, returns the existing run
- If signature doesn't match, throws `CONCURRENCY_CONFLICT`

### 7.3 Calculate (`POST /api/v1/payroll/runs/{id}/calculate`)
- **Precondition**: Status must be `DRAFT`
- **Deletes** any existing lines (recalculation supported)
- **Selects employees**:
  - WEEKLY run → all `LABOUR` employees with status ACTIVE
  - MONTHLY run → all `STAFF` employees with status ACTIVE
- For each employee, calculates `PayrollRunLine` (see Section 8)
- Aggregates totals on PayrollRun: totalEmployees, totalBasePay, totalOvertimePay, totalDeductions, totalNetPay, totalPresentDays, totalOvertimeHours
- Sets status to `CALCULATED`

### 7.4 Approve (`POST /api/v1/payroll/runs/{id}/approve`)
- **Precondition**: Status must be `CALCULATED`
- **Validation**: Must have at least one calculated line
- Sets status to `APPROVED`, records approvedBy/approvedAt

### 7.5 Post to Accounting (`POST /api/v1/payroll/runs/{id}/post`)
- **Precondition**: Status must be `APPROVED` (or already POSTED for idempotency)
- **Requires accounts** (see Section 10)
- Creates journal entry via `AccountingFacade.postPayrollRun()`
- Links attendance records to payroll run
- Sets `journalEntryId` on PayrollRun
- Logs `PAYROLL_POSTED` audit event with full metadata
- Sets status to `POSTED`

### 7.6 Mark as Paid (`POST /api/v1/payroll/runs/{id}/mark-paid`)
- **Precondition**: Must have `paymentJournalEntryId` set (via accounting payment endpoint)
- **Precondition**: Status must be `POSTED` or `PAID`
- Updates all line items to `paymentStatus = PAID`
- Sets `paymentReference` from the payment journal's reference number
- **Reduces employee advance balance** by the advance deduction amount for each line
- Sets status to `PAID`, records paymentDate

---

## 8. Pay Calculation Engine

### 8.1 Per-Employee Line Calculation (`PayrollCalculationService.calculateEmployeePay`)

#### Attendance Aggregation
Queries all attendance records for the employee within the run's period and tallies:
- present_days, half_days, absent_days, leave_days, holiday_days
- regular_hours, overtime_hours, double_overtime_hours

#### Rate Determination
```
daily_rate = employee.dailyRate (Labour: dailyWage, Staff: monthlySalary/workingDays)
hourly_rate = daily_rate / standard_hours_per_day
ot_rate = hourly_rate × ot_rate_multiplier (1.5x)
double_ot_rate = hourly_rate × double_ot_multiplier (2.0x)
```

#### Earnings Calculation

**For STAFF with SalaryStructureTemplate (MONTHLY run):**
```
leaveWithoutPayDays = absent_days + leave_days + (half_days × 0.5)
payableRatio = 1 - (leaveWithoutPayDays / workingDaysPerMonth)   [min 0]
basic_component = template.basic_pay × payableRatio
hra_component = template.hra × payableRatio
da_component = template.da × payableRatio
special_allowance = template.special_allowance × payableRatio
base_pay = basic + hra + da + special
leaveWithoutPayDeduction = template.totalEarnings - base_pay
```

**For STAFF without template:**
```
absence_deduction = daily_rate × (absent_days + half_days × 0.5)
gross_pay = monthly_salary - absence_deduction   [min 0]
```

**For LABOUR (WEEKLY run):**
```
effective_days = present_days + (half_days × 0.5)
base_pay = daily_rate × effective_days
holiday_pay = daily_rate × holiday_days
```

#### Overtime Calculation (all types)
```
overtime_pay = (hourly_rate × 1.5 × overtime_hours) + (hourly_rate × 2.0 × double_ot_hours)
```

#### Gross Pay
```
gross_pay = base_pay + overtime_pay + holiday_pay
```

#### Deduction Calculation (see Section 9)
```
total_deductions = loan/advance + PF + ESI + TDS + professional_tax + other
net_pay = max(0, gross_pay - total_deductions)
```

### 8.2 Pay Summaries (Preview, no run needed)
- `GET /api/v1/payroll/summary/weekly?weekEndingDate=` → calculates what each labourer would earn
- `GET /api/v1/payroll/summary/monthly?year=&month=` → calculates what each staff member would earn
- These are read-only projections, don't create payroll runs

---

## 9. Deduction / Reimbursement Handling

### 9.1 Statutory Deduction Engine (`StatutoryDeductionEngine`)

| Deduction | Basis | Rate | Threshold | Notes |
|-----------|-------|------|-----------|-------|
| **PF** (Provident Fund) | basic_component | 12% (configurable) | None | Applied to basic salary component |
| **ESI** (Employee State Insurance) | gross_pay | 0.75% (configurable) | ₹21,000 (configurable) | Only if gross <= threshold |
| **TDS** (Tax Deducted at Source) | gross_pay | 10% flat | Annual exemption: OLD=₹250K, NEW=₹300K | Projected annual: gross × periods_per_year |
| **Professional Tax** | N/A | Fixed ₹200 (configurable) | MONTHLY runs only | From salary structure template |

#### TDS Calculation Detail
```
periodsPerYear = WEEKLY ? 52 : 12
projectedAnnualGross = grossPay × periodsPerYear
annualExemption = (regime == OLD) ? 250000 : 300000
taxableAnnual = projectedAnnualGross - annualExemption  [min 0]
annualTax = taxableAnnual × 0.10
periodTDS = annualTax / periodsPerYear
```

### 9.2 Loan/Advance Deduction (`PayrollCalculationSupport`)
- Deducted from employee's `advanceBalance`
- **Capped at 20% of gross pay** per period
- Formula: `min(advanceBalance, grossPay × 0.20)`
- On payment, the employee's `advanceBalance` is reduced by the deducted amount

### 9.3 Leave Without Pay Deduction
- Calculated as the difference between full salary and pro-rated salary
- Only when absences + leaves exceed the working days expectation

### 9.4 Other Deductions
- `other_deductions` field exists on `PayrollRunLine`
- Must be zero to post to accounting (classified deductions required)
- Validation in `PayrollPostingService.postPayrollToAccounting()`: throws `BUSINESS_CONSTRAINT_VIOLATION` if other_deductions > 0

---

## 10. Payroll Posting to Accounting

### 10.1 Required Chart of Accounts
The following accounts must exist in the company's Chart of Accounts before posting:

| Account Code | Account Type | Purpose |
|-------------|-------------|---------|
| `SALARY-EXP` | EXPENSE | Monthly staff salary expense |
| `WAGE-EXP` | EXPENSE | Weekly labourer wage expense |
| `SALARY-PAYABLE` | LIABILITY | Salary payable (net after deductions) |
| `EMP-ADV` | ASSET | Employee advance recovery |
| `PF-PAYABLE` | LIABILITY | PF deduction payable |
| `ESI-PAYABLE` | LIABILITY | ESI deduction payable |
| `TDS-PAYABLE` | LIABILITY | TDS deduction payable |
| `PROFESSIONAL-TAX-PAYABLE` | LIABILITY | Professional tax payable |

### 10.2 Journal Entry Construction

The posting creates a single journal entry with these lines:

**Debit (expense):**
- `SALARY-EXP` or `WAGE-EXP` (based on run type) = total gross pay

**Credits (liabilities + payable):**
- `SALARY-PAYABLE` = gross - loans - PF - ESI - TDS - professional_tax (net payable)
- `PF-PAYABLE` = total PF deducted
- `ESI-PAYABLE` = total ESI deducted
- `TDS-PAYABLE` = total TDS deducted
- `PROFESSIONAL-TAX-PAYABLE` = total professional tax deducted
- `EMP-ADV` = total advance/loan recovered (if > 0)

Only non-zero liability lines are included.

### 10.3 Validation Before Posting
- Run must be APPROVED
- Must have at least one calculated line
- Total gross pay must be > 0
- `other_deductions` must be 0 (must classify all deductions)
- Total deductions cannot exceed gross pay (salary payable must be >= 0)

### 10.4 Post-Posting Actions
- Attendance records for the period are linked back to the payroll run via `payrollRunId`
- Audit event `PAYROLL_POSTED` is logged with full metadata

---

## 11. Batch Payment Processing

### 11.1 Record Payroll Payment (Accounting side)
`POST /api/v1/accounting/payroll/payments`

Request: `PayrollPaymentRequest(payrollRunId, cashAccountId, expenseAccountId, amount, referenceNumber?, memo?)`

Flow:
1. Locks the PayrollRun (pessimistic write)
2. Validates run is POSTED or PAID
3. Validates posting journal exists (`journalEntryId`)
4. Resolves the `SALARY-PAYABLE` account
5. Computes payable amount from posting journal's SALARY-PAYABLE credit lines
6. Validates payment amount matches payable (within tolerance)
7. **Idempotent**: If payment journal already exists, returns existing
8. Creates payment journal entry:
   - Debit: `SALARY-PAYABLE` (reduce liability)
   - Credit: `CASH` (cash outflow)
9. Stores `paymentJournalEntryId` on PayrollRun

### 11.2 Mark Paid Flow (HR side)
After payment journal is created:
`POST /api/v1/payroll/runs/{id}/mark-paid`

- Reads payment journal reference
- Sets all line items to `PAID`
- Reduces employee advance balances
- Sets PayrollRun status to `PAID`

### 11.3 Payroll Sheet (PDF/HTML)
Template: `erp-domain/src/main/resources/templates/payroll-sheet.html`

A Thymeleaf template for generating printable payroll payment sheets with:
- Company name, period, reference
- Employee rows: name, type, days, daily rate, gross, advance, net pay, signature column
- Totals row
- "Cash to Withdraw" box
- Signature lines: Prepared By, Approved By, Cash Disbursed By

---

## 12. Approval Workflows

### 12.1 Payroll Approval Chain
The payroll follows a strict sequential workflow:

```
Create (DRAFT) → Calculate (CALCULATED) → Approve (APPROVED) → Post (POSTED) → Pay (PAID)
```

- **Create**: Any ADMIN/ACCOUNTING user
- **Calculate**: Any ADMIN/ACCOUNTING user (recalculates from attendance)
- **Approve**: Any ADMIN/ACCOUNTING user (separate from calculator for segregation)
- **Post**: Any ADMIN/ACCOUNTING user (creates GL entries)
- **Mark Paid**: Any ADMIN/ACCOUNTING user (after payment journal recorded)

### 12.2 Leave Approval
- Simple approve/reject model
- `approvedBy` / `rejectedBy` tracked with timestamps
- No multi-level approval; direct approve/reject
- Balance deduction happens on approval; reverted on rejection/cancellation
- Cannot revert APPROVED to PENDING

### 12.3 Admin Approval Queue
The `AdminApprovalItemDto` in the OpenAPI spec includes `originType: PAYROLL_RUN` and `ownerType: HR`, indicating payroll runs can appear in the admin approval queue.

---

## 13. Validation Rules Summary

### 13.1 Employee Validations
| Rule | Location |
|------|----------|
| firstName, lastName, email required | `@NotBlank` on `EmployeeRequest` |
| Email format validated | `@Email` annotation |
| PAN format: `^[A-Z]{5}[0-9]{4}[A-Z]$` | `EmployeeService.validatePan()` |
| dateOfJoining must be after dateOfBirth | `EmployeeService.validateDateChronology()` |
| STAFF must have monthlySalary or salaryStructureTemplateId | `EmployeeService.validateCompensation()` |
| LABOUR must have positive dailyWage | `EmployeeService.validateCompensation()` |
| Salary components cannot be negative | `SalaryStructureTemplateService.safeMoney()` |
| Template total earnings must be positive | `SalaryStructureTemplateService.applyMutableFields()` |
| Template code must be unique per company | DB unique constraint + service check |
| Email unique per company | DB unique constraint |

### 13.2 Attendance Validations
| Rule | Location |
|------|----------|
| status is required, must be valid enum | `AttendanceService.parseAttendanceStatus()` |
| endDate >= startDate for queries | `AttendanceService.validateDateRange()` |
| Employee must exist for current company | `CompanyEntityLookup.requireEmployee()` |
| Unique per (company, employee, date) | DB unique constraint (upsert behavior) |

### 13.3 Leave Validations
| Rule | Location |
|------|----------|
| employeeId, leaveType, startDate, endDate required | Field validation |
| endDate >= startDate | `LeaveService.validateDateRange()` |
| No overlapping leave requests | `LeaveRequestRepository.existsOverlappingByEmployeeIdAndDates()` |
| Insufficient balance check | `LeaveService.applyBalanceDelta()` |
| Cannot revert APPROVED → PENDING | `LeaveService.updateLeaveStatus()` |

### 13.4 Payroll Validations
| Rule | Location |
|------|----------|
| Period dates required, end >= start | `PayrollRunService.createPayrollRun()` |
| Duplicate period prevention | Idempotency key check |
| Can only calculate from DRAFT | `PayrollCalculationService.calculatePayroll()` |
| Can only approve from CALCULATED | `PayrollPostingService.approvePayroll()` |
| Cannot approve empty run | Line count check |
| Can only post from APPROVED | `PayrollPostingService.postPayrollToAccounting()` |
| Required accounts must exist | `PayrollPostingService.findAccountByCode()` |
| other_deductions must be 0 | Posting validation |
| Total deductions ≤ gross pay | `salaryPayableAmount >= 0` check |
| Payment journal required before PAID | `PayrollPostingService.markAsPaid()` |
| Payment amount must match payable | `AccountingCoreEngineCore.recordPayrollPayment()` |
| standardHoursPerDay > 0 | `PayrollCalculationSupport.requireValidStandardHoursPerDay()` |
| Advance deduction capped at 20% of gross | `PayrollCalculationSupport.ADVANCE_DEDUCTION_CAP` |
| Net pay floored at 0 | `if (netPay < 0) netPay = 0` |

### 13.5 Encryption / Security
| Rule | Location |
|------|----------|
| Bank details encrypted at rest | `EmployeeService.applyEncryptedBankDetails()` |
| Plain text bank fields nulled | After encryption |
| PAN normalized (uppercase, trimmed) | `IdempotencyUtils.normalizeToken()` |
| PF/ESI numbers normalized | Same |

---

## 14. Events & Audit

### 14.1 Audit Events
| Event | Trigger | Metadata |
|-------|---------|----------|
| `PAYROLL_POSTED` | After successful journal posting | payrollRunId, runNumber, runType, periodStart, periodEnd, journalEntryId, postingDate, totalGrossPay, totalAdvances, netPayable, totalPf, totalEsi, totalTds, totalProfessionalTax |

### 14.2 No Domain Events
The HR module does **not** publish Spring application events. It uses direct service-to-service calls and the audit log for traceability.

---

## 15. Key Design Patterns

1. **Company-scoped multi-tenancy**: Every entity has a `company_id` FK; all queries filter by current company context
2. **Pessimistic locking**: Employee updates and payroll run operations use `PESSIMISTIC_WRITE` locks
3. **Idempotency**: Payroll runs use idempotency keys + signature hashes for safe retries
4. **Encrypted PII**: Bank details encrypted via `CryptoService`, plain text columns nulled
5. **Facade pattern**: `HrService` and `PayrollService` are facades delegating to specialized sub-services
6. **Versioned entities**: Employee, LeaveRequest, LeaveBalance, LeaveTypePolicy, SalaryStructureTemplate extend `VersionedEntity` (optimistic locking via `@Version`)
7. **Two-tier payroll**: STAFF (monthly) vs LABOUR (weekly) with separate payroll run types
8. **Statutory compliance**: Indian payroll deductions (PF, ESI, TDS, Professional Tax) with configurable rates
