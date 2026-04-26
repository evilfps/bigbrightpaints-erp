# Admin and Tenant Management Workflow

> ⚠️ **NON-CANONICAL**: This document is superseded by the canonical flow packets in [docs/flows/](flows/). The current admin and tenant management behavior is documented in [docs/flows/tenant-admin-management.md](../flows/tenant-admin-management.md).

**Audience:** Super admin, company admin, operations support lead

This guide covers tenant onboarding, canonical superadmin tenant control, tenant-admin user control, export governance, support tickets, and changelog publishing.

## End-to-End Steps

| Step | What to do | Screen + API mapping | What to expect | What can go wrong (and quick fix) |
|---|---|---|---|---|
| 1 | Onboard tenant/company | Template list: `GET /api/v1/superadmin/tenants/coa-templates` → onboard: `POST /api/v1/superadmin/tenants/onboard` | New tenant, main admin, seeded chart of accounts, and default accounting period are created; onboarding now returns delivery/completion truth instead of a temporary password | Invalid template code, duplicate tenant code/email, or credential-delivery failure. Correct input or restore email delivery, then retry. |
| 2 | Review tenant detail and support context | `GET /api/v1/superadmin/tenants/{id}` → optional support-note/tag update: `PUT /api/v1/superadmin/tenants/{id}/support/context` | Superadmin sees the canonical tenant detail payload: identity, lifecycle, onboarding truth, main admin, limits, usage, modules, support timeline, notes, and tags | Missing onboarding evidence or stale support context. Refresh detail after control-plane changes and keep internal notes/tags current there. |
| 3 | Manage users (create, role, status, reset) | Tenant-admin user surface: `POST /api/v1/admin/users`, `PUT /api/v1/admin/users/{id}`, status `PUT /api/v1/admin/users/{userId}/status`, reset `POST /api/v1/admin/users/{userId}/force-reset-password` | Access and lifecycle for users are centrally controlled inside the current tenant only. Platform super-admin tooling must stay on the `/api/v1/superadmin/tenants/**` family instead of this prefix. | Role mismatch, invalid company assignment, or disabled account confusion. Confirm role-policy and tenant mapping. |
| 4 | Configure lifecycle, limits, and modules per tenant | `PUT /api/v1/superadmin/tenants/{id}/lifecycle`, `PUT /api/v1/superadmin/tenants/{id}/limits`, `PUT /api/v1/superadmin/tenants/{id}/modules` | Tenant lifecycle, quota envelope, and optional modules are mutated through one canonical superadmin control-plane family | Invalid lifecycle transition, limits too strict, or disabling a module in active use. Plan the change window and refresh tenant detail after each control action. |
| 5 | Handle support interventions | Warning: `POST /api/v1/superadmin/tenants/{id}/support/warnings`; admin credential recovery: `POST /api/v1/superadmin/tenants/{id}/support/admin-password-reset`; force logout: `POST /api/v1/superadmin/tenants/{id}/force-logout`; main-admin replacement: `PUT /api/v1/superadmin/tenants/{id}/admins/main`; admin email change: `POST /api/v1/superadmin/tenants/{id}/admins/{adminId}/email-change/{request|confirm}` | Support actions are persisted/audited and reflected back in the tenant detail support timeline | Wrong admin target, missing email verification token, or attempting to remove the current main admin before replacement. Confirm target identity and refresh tenant detail after action completion. |
| 6 | Handle export approvals | Request export: `POST /api/v1/exports/request`; tenant-admin approval inbox: `GET /api/v1/admin/approvals`; decision: tenant admin only via `POST /api/v1/admin/approvals/{originType}/{id}/decisions`; download: `GET /api/v1/exports/{requestId}/download` | Sensitive exports are controlled through the approval gate; export approval rows use `originType=EXPORT_REQUEST` and `ownerType=REPORTS`. The download route returns file bytes when allowed, not a separate `downloadUrl` or status payload. | Request remains pending if no tenant admin takes the decision. If export approval is disabled in system settings, backend policy may allow retrieval without an approved decision. Accounting can request exports and retry download, but approval decisions stay in tenant-admin. |
| 7 | Process support tickets | Internal support desk: `POST /api/v1/portal/support/tickets`, `GET /api/v1/portal/support/tickets`, `GET /api/v1/portal/support/tickets/{ticketId}`; dealer support desk: `POST /api/v1/dealer-portal/support/tickets`, `GET /api/v1/dealer-portal/support/tickets`, `GET /api/v1/dealer-portal/support/tickets/{ticketId}` | Ticket is stored and sync workflow can propagate to GitHub issue tracking while host ownership stays split between admin/accounting and dealer users | External sync failures can happen; local ticket should still exist. Shared `/api/v1/support/**` is retired, so continue follow-up from the host-specific ticket views and retry sync cycle there. |
| 8 | Publish changelog updates | Superadmin write surface: `POST /api/v1/superadmin/changelog`, `PUT /api/v1/superadmin/changelog/{id}`, `DELETE /api/v1/superadmin/changelog/{id}`; authenticated read surface: `GET /api/v1/changelog`, highlighted: `GET /api/v1/changelog/latest-highlighted` | Release notes become visible to signed-in users and “What’s New” banner can update | Invalid semver/title/body, mistaken highlight selection, or an unauthenticated client expecting public reads. Correct entry or require login before fetching the feed. |

## Super Admin Monitoring APIs

- Platform dashboard: `GET /api/v1/superadmin/dashboard`
- Tenant list and detail: `GET /api/v1/superadmin/tenants`, `GET /api/v1/superadmin/tenants/{id}`
- Tenant lifecycle/limits/modules: `PUT /api/v1/superadmin/tenants/{id}/lifecycle`, `PUT /api/v1/superadmin/tenants/{id}/limits`, `PUT /api/v1/superadmin/tenants/{id}/modules`
- Support interventions: `POST /api/v1/superadmin/tenants/{id}/support/warnings`, `POST /api/v1/superadmin/tenants/{id}/support/admin-password-reset`, `PUT /api/v1/superadmin/tenants/{id}/support/context`, `POST /api/v1/superadmin/tenants/{id}/force-logout`
- Admin governance: `PUT /api/v1/superadmin/tenants/{id}/admins/main`, `POST /api/v1/superadmin/tenants/{id}/admins/{adminId}/email-change/request`, `POST /api/v1/superadmin/tenants/{id}/admins/{adminId}/email-change/confirm`
- Changelog write surface: `POST /api/v1/superadmin/changelog`, `PUT /api/v1/superadmin/changelog/{id}`, `DELETE /api/v1/superadmin/changelog/{id}`

## Troubleshooting Quick Notes

1. **User cannot log in after support reset or email change:** verify account status, tenant lifecycle state, `mustChangePassword`, and whether force-logout was executed for the tenant.
2. **Tenant suddenly becomes read-only or blocked:** inspect `GET /api/v1/superadmin/tenants/{id}` for the persisted lifecycle state and latest support timeline entries; use the canonical lifecycle route if the state change was intentional.
3. **Module endpoints return 403 suddenly:** module was gated off for that tenant; re-enable it through `PUT /api/v1/superadmin/tenants/{id}/modules` if intended.
4. **Export pending too long:** check the admin approvals queue and approver role coverage.
5. **Support ticket not visible in external tracker:** confirm integration is enabled; the local ERP ticket remains the source of truth during outage.
