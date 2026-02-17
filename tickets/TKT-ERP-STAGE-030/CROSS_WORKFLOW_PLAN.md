# Cross Workflow Plan

Ticket: `TKT-ERP-STAGE-030`
Generated: `2026-02-17T06:40:04+00:00`

## In-Ticket Dependency Edges

| Upstream Slice | Upstream Agent | Downstream Slice | Downstream Agent | Contract |
| --- | --- | --- | --- | --- |
| SLICE-02 | purchasing-invoice-p2p | SLICE-01 | accounting-domain | ap/posting and settlement linkage |

## Recommended Merge Order

1. `SLICE-02` (purchasing-invoice-p2p)
2. `SLICE-01` (accounting-domain)
3. `SLICE-03` (refactor-techdebt-gc)

## Slice Coordination Notes

### SLICE-02 (purchasing-invoice-p2p)
- Upstream slices: none
- Downstream slices: SLICE-01
- External upstream agents to watch: auth-rbac-company
- External downstream agents to watch: inventory-domain

### SLICE-01 (accounting-domain)
- Upstream slices: SLICE-02
- Downstream slices: none
- External upstream agents to watch: auth-rbac-company, factory-production, hr-domain, orchestrator-runtime, sales-domain
- External downstream agents to watch: none

### SLICE-03 (refactor-techdebt-gc)
- Upstream slices: none
- Downstream slices: none
- External upstream agents to watch: none
- External downstream agents to watch: none

