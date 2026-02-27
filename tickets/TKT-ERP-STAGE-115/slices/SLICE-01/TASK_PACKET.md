# Task Packet

Ticket: `TKT-ERP-STAGE-115`
Slice: `SLICE-01`
Primary Agent: `auth-rbac-company`

## Objective
Implement superadmin dashboard, tenant governance APIs, support warnings, and role-boundary enforcement.

## Required Outputs
- files changed
- command evidence
- tests run and results
- residual risks

## Guardrails
- Superadmin is control-plane only.
- Non-superadmin callers must not be able to assign or discover `ROLE_SUPER_ADMIN` in tenant-admin paths.
- Keep changes fail-closed and audit-evidenced.
