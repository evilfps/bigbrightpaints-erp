# Cross Workflow Plan

Ticket: `TKT-ERP-STAGE-101`
Generated: `2026-02-20T12:01:45+00:00`

## In-Ticket Dependency Edges

| Upstream Slice | Upstream Agent | Downstream Slice | Downstream Agent | Contract |
| --- | --- | --- | --- | --- |
| SLICE-02 | hr-domain | SLICE-01 | accounting-domain | payroll liability/payment posting linkage |
| SLICE-03 | purchasing-invoice-p2p | SLICE-01 | accounting-domain | ap/posting and settlement linkage |

## Recommended Merge Order

1. `SLICE-02` (hr-domain)
2. `SLICE-03` (purchasing-invoice-p2p)
3. `SLICE-01` (accounting-domain)
4. `SLICE-04` (refactor-techdebt-gc)
5. `SLICE-05` (repo-cartographer)

## Slice Coordination Notes

### SLICE-02 (hr-domain)
- Upstream slices: none
- Downstream slices: SLICE-01
- External upstream agents to watch: auth-rbac-company
- External downstream agents to watch: none

### SLICE-03 (purchasing-invoice-p2p)
- Upstream slices: none
- Downstream slices: SLICE-01
- External upstream agents to watch: auth-rbac-company
- External downstream agents to watch: inventory-domain

### SLICE-01 (accounting-domain)
- Upstream slices: SLICE-02, SLICE-03
- Downstream slices: none
- External upstream agents to watch: auth-rbac-company, factory-production, orchestrator-runtime, sales-domain
- External downstream agents to watch: none

### SLICE-04 (refactor-techdebt-gc)
- Upstream slices: none
- Downstream slices: none
- External upstream agents to watch: none
- External downstream agents to watch: none

### SLICE-05 (repo-cartographer)
- Upstream slices: none
- Downstream slices: none
- External upstream agents to watch: none
- External downstream agents to watch: none

