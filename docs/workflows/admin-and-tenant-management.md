# Admin and Tenant Management Workflow

**Audience:** Super admin, company admin, operations support lead

This guide covers tenant setup, user control, module gating, platform settings, export governance, support tickets, and changelog publishing.

## End-to-End Steps

| Step | What to do | Screen + API mapping | What to expect | What can go wrong (and quick fix) |
|---|---|---|---|---|
| 1 | Onboard tenant/company | Template list: `GET /api/v1/superadmin/tenants/coa-templates` → onboard: `POST /api/v1/superadmin/tenants/onboard` | New tenant, admin user, and base accounting structure are created | Invalid template code, duplicate tenant code/email, or quota constraints. Correct input and retry. |
| 2 | Manage users (create, role, status, reset) | User admin: `POST /api/v1/admin/users`, `PUT /api/v1/admin/users/{id}`, status `PUT /api/v1/admin/users/{userId}/status`, reset `POST /api/v1/admin/users/{userId}/force-reset-password` | Access and lifecycle for users are centrally controlled | Role mismatch, invalid company assignment, or disabled account confusion. Confirm role-policy and tenant mapping. |
| 3 | Configure module gating per tenant | `PUT /api/v1/superadmin/tenants/{id}/modules` | Optional modules enabled/disabled per tenant contract | Disabling a module in active use causes 403 for those flows. Plan change window and communicate before toggling. |
| 4 | Update system settings and policy | Company admin settings `GET/PUT /api/v1/admin/settings`; super admin policy `PUT /api/v1/admin/tenant-runtime/policy` | Runtime guardrails and admin controls are updated with audit visibility | Unauthorized role for sensitive toggles (e.g., lock policy), bad origin list, or runtime policy too strict. Roll back safely. |
| 5 | Handle export approvals | Request export: `POST /api/v1/exports/request`; tenant admin/accounting queue: `GET /api/v1/admin/approvals`; decision: `PUT /api/v1/admin/exports/{requestId}/approve` or `/reject`; download token lookup: `GET /api/v1/exports/{requestId}/download` | Sensitive exports are controlled through approval gate; export approval rows keep machine-readable `reportType`, `parameters`, and requester identity in the single inbox | Request remains pending if approver not assigned or policy disabled unexpectedly. Verify approval path and tenant admin/accounting rights. |
| 6 | Process support tickets | Create/list/detail: `POST /api/v1/support/tickets`, `GET /api/v1/support/tickets`, `GET /api/v1/support/tickets/{ticketId}` | Ticket is stored and sync workflow can propagate to GitHub issue tracking | External sync failures can happen; local ticket should still exist. Continue follow-up from local status and retry sync cycle. |
| 7 | Publish changelog updates | Admin create/update/delete: `POST /api/v1/admin/changelog`, `PUT /api/v1/admin/changelog/{id}`, `DELETE /api/v1/admin/changelog/{id}`; public feed: `GET /api/v1/changelog`, highlighted: `GET /api/v1/changelog/latest-highlighted` | Release notes become visible to users and “What’s New” banner can update | Invalid semver/title/body or mistaken highlight selection. Correct entry and republish. |

## Super Admin Monitoring APIs

- Platform dashboard: `GET /api/v1/superadmin/dashboard`
- Tenant list/state actions: `GET /api/v1/superadmin/tenants`, `POST /api/v1/superadmin/tenants/{id}/suspend`, `POST /api/v1/superadmin/tenants/{id}/activate`, `POST /api/v1/superadmin/tenants/{id}/deactivate`
- Tenant usage: `GET /api/v1/superadmin/tenants/{id}/usage`

## Troubleshooting Quick Notes

1. **User cannot log in after admin change:** verify account status + tenant lifecycle state + role assignments.
2. **Module endpoints return 403 suddenly:** module was gated off for that tenant; re-enable if intended.
3. **Export pending too long:** check admin approvals queue and approver role coverage.
4. **Support ticket not visible in external tracker:** confirm integration is enabled; local ERP ticket remains source of truth during outage.
