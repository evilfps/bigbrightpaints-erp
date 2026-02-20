# Cross Workflow Plan

Ticket: `TKT-ERP-STAGE-099`
Generated: `2026-02-20T12:01:45+00:00`

## In-Ticket Dependency Edges

| Upstream Slice | Upstream Agent | Downstream Slice | Downstream Agent | Contract |
| --- | --- | --- | --- | --- |
| SLICE-01 | auth-rbac-company | SLICE-02 | reports-admin-portal | admin/report access boundaries |

## Recommended Merge Order

1. `SLICE-01` (auth-rbac-company)
2. `SLICE-02` (reports-admin-portal)
3. `SLICE-03` (refactor-techdebt-gc)
4. `SLICE-04` (repo-cartographer)

## Slice Coordination Notes

### SLICE-01 (auth-rbac-company)
- Upstream slices: none
- Downstream slices: SLICE-02
- External upstream agents to watch: none
- External downstream agents to watch: accounting-domain, factory-production, hr-domain, inventory-domain, purchasing-invoice-p2p, sales-domain

### SLICE-02 (reports-admin-portal)
- Upstream slices: SLICE-01
- Downstream slices: none
- External upstream agents to watch: none
- External downstream agents to watch: none

### SLICE-03 (refactor-techdebt-gc)
- Upstream slices: none
- Downstream slices: none
- External upstream agents to watch: none
- External downstream agents to watch: none

### SLICE-04 (repo-cartographer)
- Upstream slices: none
- Downstream slices: none
- External upstream agents to watch: none
- External downstream agents to watch: none

