# Cross Workflow Plan

Ticket: `TKT-ERP-STAGE-015`
Generated: `2026-02-16T22:04:48+00:00`

## In-Ticket Dependency Edges

- none

## Recommended Merge Order

1. `SLICE-01` (factory-production)
2. `SLICE-02` (refactor-techdebt-gc)

## Slice Coordination Notes

### SLICE-01 (factory-production)
- Upstream slices: none
- Downstream slices: none
- External upstream agents to watch: auth-rbac-company
- External downstream agents to watch: accounting-domain, inventory-domain

### SLICE-02 (refactor-techdebt-gc)
- Upstream slices: none
- Downstream slices: none
- External upstream agents to watch: none
- External downstream agents to watch: none

