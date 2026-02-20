# Cross Workflow Plan

Ticket: `TKT-ERP-STAGE-100`
Generated: `2026-02-20T12:01:45+00:00`

## In-Ticket Dependency Edges

- none

## Recommended Merge Order

1. `SLICE-01` (purchasing-invoice-p2p)
2. `SLICE-02` (sales-domain)
3. `SLICE-03` (refactor-techdebt-gc)
4. `SLICE-04` (repo-cartographer)

## Slice Coordination Notes

### SLICE-01 (purchasing-invoice-p2p)
- Upstream slices: none
- Downstream slices: none
- External upstream agents to watch: auth-rbac-company
- External downstream agents to watch: accounting-domain, inventory-domain

### SLICE-02 (sales-domain)
- Upstream slices: none
- Downstream slices: none
- External upstream agents to watch: auth-rbac-company, orchestrator-runtime
- External downstream agents to watch: accounting-domain, inventory-domain

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

