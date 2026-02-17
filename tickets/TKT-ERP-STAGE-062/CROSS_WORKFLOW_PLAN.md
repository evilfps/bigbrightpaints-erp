# Cross Workflow Plan

Ticket: `TKT-ERP-STAGE-062`
Generated: `2026-02-17T19:35:13+00:00`

## In-Ticket Dependency Edges

| Upstream Slice | Upstream Agent | Downstream Slice | Downstream Agent | Contract |
| --- | --- | --- | --- | --- |
| SLICE-02 | purchasing-invoice-p2p | SLICE-01 | accounting-domain | ap/posting and settlement linkage |
| SLICE-03 | sales-domain | SLICE-01 | accounting-domain | o2c posting and receivable linkage |

## Recommended Merge Order

1. `SLICE-02` (purchasing-invoice-p2p)
2. `SLICE-03` (sales-domain)
3. `SLICE-01` (accounting-domain)
4. `SLICE-04` (refactor-techdebt-gc)

## Slice Coordination Notes

### SLICE-02 (purchasing-invoice-p2p)
- Upstream slices: none
- Downstream slices: SLICE-01
- External upstream agents to watch: auth-rbac-company
- External downstream agents to watch: inventory-domain

### SLICE-03 (sales-domain)
- Upstream slices: none
- Downstream slices: SLICE-01
- External upstream agents to watch: auth-rbac-company, orchestrator-runtime
- External downstream agents to watch: inventory-domain

### SLICE-01 (accounting-domain)
- Upstream slices: SLICE-02, SLICE-03
- Downstream slices: none
- External upstream agents to watch: auth-rbac-company, factory-production, hr-domain, orchestrator-runtime
- External downstream agents to watch: none

### SLICE-04 (refactor-techdebt-gc)
- Upstream slices: none
- Downstream slices: none
- External upstream agents to watch: none
- External downstream agents to watch: none

