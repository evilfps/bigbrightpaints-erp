# Cross Workflow Plan

Ticket: `TKT-ERP-STAGE-092`
Generated: `2026-02-19T13:10:26+00:00`

## In-Ticket Dependency Edges

- none

## Recommended Merge Order

1. `SLICE-01` (auth-rbac-company)
2. `SLICE-02` (data-migration)
3. `SLICE-03` (refactor-techdebt-gc)

## Slice Coordination Notes

### SLICE-01 (auth-rbac-company)
- Upstream slices: none
- Downstream slices: none
- External upstream agents to watch: none
- External downstream agents to watch: accounting-domain, factory-production, hr-domain, inventory-domain, purchasing-invoice-p2p, reports-admin-portal, sales-domain

### SLICE-02 (data-migration)
- Upstream slices: none
- Downstream slices: none
- External upstream agents to watch: none
- External downstream agents to watch: release-ops

### SLICE-03 (refactor-techdebt-gc)
- Upstream slices: none
- Downstream slices: none
- External upstream agents to watch: none
- External downstream agents to watch: none

