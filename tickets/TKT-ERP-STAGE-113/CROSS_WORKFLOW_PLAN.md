# Cross Workflow Plan

Ticket: `TKT-ERP-STAGE-113`
Generated: `2026-02-26T10:34:12+00:00`

## In-Ticket Dependency Edges

| Upstream Slice | Upstream Agent | Downstream Slice | Downstream Agent | Contract |
| --- | --- | --- | --- | --- |
| SLICE-02 | auth-rbac-company | SLICE-01 | accounting-domain | finance/admin authority boundaries |
| SLICE-02 | auth-rbac-company | SLICE-03 | hr-domain | payroll/PII access boundaries |
| SLICE-02 | auth-rbac-company | SLICE-05 | inventory-domain | tenant context and role checks |
| SLICE-02 | auth-rbac-company | SLICE-07 | purchasing-invoice-p2p | tenant-scoped supplier/AP access rules |
| SLICE-02 | auth-rbac-company | SLICE-08 | reports-admin-portal | admin/report access boundaries |
| SLICE-02 | auth-rbac-company | SLICE-09 | sales-domain | tenant and role boundary contract |
| SLICE-03 | hr-domain | SLICE-01 | accounting-domain | payroll liability/payment posting linkage |
| SLICE-04 | orchestrator-runtime | SLICE-01 | accounting-domain | outbox/idempotency posting orchestration |
| SLICE-04 | orchestrator-runtime | SLICE-05 | inventory-domain | exactly-once side-effect orchestration |
| SLICE-04 | orchestrator-runtime | SLICE-09 | sales-domain | async orchestration command contract |
| SLICE-07 | purchasing-invoice-p2p | SLICE-01 | accounting-domain | ap/posting and settlement linkage |
| SLICE-07 | purchasing-invoice-p2p | SLICE-05 | inventory-domain | grn/stock intake coupling |
| SLICE-09 | sales-domain | SLICE-01 | accounting-domain | o2c posting and receivable linkage |
| SLICE-09 | sales-domain | SLICE-05 | inventory-domain | dispatch and stock movement linkage |

## Recommended Merge Order

1. `SLICE-02` (auth-rbac-company)
2. `SLICE-03` (hr-domain)
3. `SLICE-04` (orchestrator-runtime)
4. `SLICE-06` (orchestrator)
5. `SLICE-07` (purchasing-invoice-p2p)
6. `SLICE-08` (reports-admin-portal)
7. `SLICE-09` (sales-domain)
8. `SLICE-01` (accounting-domain)
9. `SLICE-05` (inventory-domain)
10. `SLICE-10` (frontend-documentation)

## Slice Coordination Notes

### SLICE-02 (auth-rbac-company)
- Upstream slices: none
- Downstream slices: SLICE-01, SLICE-03, SLICE-05, SLICE-07, SLICE-08, SLICE-09
- External upstream agents to watch: none
- External downstream agents to watch: factory-production

### SLICE-03 (hr-domain)
- Upstream slices: SLICE-02
- Downstream slices: SLICE-01
- External upstream agents to watch: none
- External downstream agents to watch: none

### SLICE-04 (orchestrator-runtime)
- Upstream slices: none
- Downstream slices: SLICE-01, SLICE-05, SLICE-09
- External upstream agents to watch: none
- External downstream agents to watch: none

### SLICE-06 (orchestrator)
- Upstream slices: none
- Downstream slices: none
- External upstream agents to watch: none
- External downstream agents to watch: none

### SLICE-07 (purchasing-invoice-p2p)
- Upstream slices: SLICE-02
- Downstream slices: SLICE-01, SLICE-05
- External upstream agents to watch: none
- External downstream agents to watch: none

### SLICE-08 (reports-admin-portal)
- Upstream slices: SLICE-02
- Downstream slices: none
- External upstream agents to watch: none
- External downstream agents to watch: none

### SLICE-09 (sales-domain)
- Upstream slices: SLICE-02, SLICE-04
- Downstream slices: SLICE-01, SLICE-05
- External upstream agents to watch: none
- External downstream agents to watch: none

### SLICE-01 (accounting-domain)
- Upstream slices: SLICE-02, SLICE-03, SLICE-04, SLICE-07, SLICE-09
- Downstream slices: none
- External upstream agents to watch: factory-production
- External downstream agents to watch: none

### SLICE-05 (inventory-domain)
- Upstream slices: SLICE-02, SLICE-04, SLICE-07, SLICE-09
- Downstream slices: none
- External upstream agents to watch: factory-production
- External downstream agents to watch: none

### SLICE-10 (frontend-documentation)
- Upstream slices: none
- Downstream slices: none
- External upstream agents to watch: none
- External downstream agents to watch: none

