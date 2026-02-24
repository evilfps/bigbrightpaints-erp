# Cross Workflow Plan

Ticket: `TKT-ERP-STAGE-108`
Generated: `2026-02-24T09:55:24+00:00`

## In-Ticket Dependency Edges

| Upstream Slice | Upstream Agent | Downstream Slice | Downstream Agent | Contract |
| --- | --- | --- | --- | --- |
| SLICE-02 | factory-production | SLICE-01 | accounting-domain | wip/variance/cogs posting linkage |
| SLICE-02 | factory-production | SLICE-03 | inventory-domain | production/packing stock transitions |

## Recommended Merge Order

1. `SLICE-02` (factory-production)
2. `SLICE-01` (accounting-domain)
3. `SLICE-03` (inventory-domain)
4. `SLICE-04` (frontend-documentation)
5. `SLICE-05` (refactor-techdebt-gc)

## Slice Coordination Notes

### SLICE-02 (factory-production)
- Upstream slices: none
- Downstream slices: SLICE-01, SLICE-03
- External upstream agents to watch: auth-rbac-company
- External downstream agents to watch: none

### SLICE-01 (accounting-domain)
- Upstream slices: SLICE-02
- Downstream slices: none
- External upstream agents to watch: auth-rbac-company, hr-domain, orchestrator-runtime, purchasing-invoice-p2p, sales-domain
- External downstream agents to watch: none

### SLICE-03 (inventory-domain)
- Upstream slices: SLICE-02
- Downstream slices: none
- External upstream agents to watch: auth-rbac-company, orchestrator-runtime, purchasing-invoice-p2p, sales-domain
- External downstream agents to watch: none

### SLICE-04 (frontend-documentation)
- Upstream slices: none
- Downstream slices: none
- External upstream agents to watch: none
- External downstream agents to watch: none

### SLICE-05 (refactor-techdebt-gc)
- Upstream slices: none
- Downstream slices: none
- External upstream agents to watch: none
- External downstream agents to watch: none

