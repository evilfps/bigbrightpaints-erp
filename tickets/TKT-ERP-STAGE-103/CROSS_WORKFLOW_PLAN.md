# Cross Workflow Plan

Ticket: `TKT-ERP-STAGE-103`
Generated: `2026-02-20T12:01:46+00:00`

## In-Ticket Dependency Edges

| Upstream Slice | Upstream Agent | Downstream Slice | Downstream Agent | Contract |
| --- | --- | --- | --- | --- |
| SLICE-01 | orchestrator-runtime | SLICE-03 | inventory-domain | exactly-once side-effect orchestration |
| SLICE-01 | orchestrator-runtime | SLICE-04 | sales-domain | async orchestration command contract |
| SLICE-02 | factory-production | SLICE-03 | inventory-domain | production/packing stock transitions |
| SLICE-04 | sales-domain | SLICE-03 | inventory-domain | dispatch and stock movement linkage |

## Recommended Merge Order

1. `SLICE-01` (orchestrator-runtime)
2. `SLICE-02` (factory-production)
3. `SLICE-04` (sales-domain)
4. `SLICE-03` (inventory-domain)
5. `SLICE-05` (refactor-techdebt-gc)
6. `SLICE-06` (repo-cartographer)

## Slice Coordination Notes

### SLICE-01 (orchestrator-runtime)
- Upstream slices: none
- Downstream slices: SLICE-03, SLICE-04
- External upstream agents to watch: none
- External downstream agents to watch: accounting-domain

### SLICE-02 (factory-production)
- Upstream slices: none
- Downstream slices: SLICE-03
- External upstream agents to watch: auth-rbac-company
- External downstream agents to watch: accounting-domain

### SLICE-04 (sales-domain)
- Upstream slices: SLICE-01
- Downstream slices: SLICE-03
- External upstream agents to watch: auth-rbac-company
- External downstream agents to watch: accounting-domain

### SLICE-03 (inventory-domain)
- Upstream slices: SLICE-01, SLICE-02, SLICE-04
- Downstream slices: none
- External upstream agents to watch: auth-rbac-company, purchasing-invoice-p2p
- External downstream agents to watch: none

### SLICE-05 (refactor-techdebt-gc)
- Upstream slices: none
- Downstream slices: none
- External upstream agents to watch: none
- External downstream agents to watch: none

### SLICE-06 (repo-cartographer)
- Upstream slices: none
- Downstream slices: none
- External upstream agents to watch: none
- External downstream agents to watch: none

