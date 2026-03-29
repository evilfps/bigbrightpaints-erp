# Role Boundaries

## Primary access

- Frontend shell owner: `ROLE_ADMIN`
- Canonical user lifecycle backend: `/api/v1/admin/users/**`
- Canonical approval inbox backend: `GET /api/v1/admin/approvals`

## Hard boundaries

- Do not expose `ROLE_SUPER_ADMIN` anywhere in this portal.
- Do not expose tenant lifecycle, limit, module, or support-recovery actions here.
- Do not expose accounting-only journal or period-close actions here.
- Do not expose factory or dealer navigation here.

## Shared-data caveats

- `GET /api/v1/admin/approvals` can include non-export approval types. Tenant-admin screens in this folder only own export-approval decisions.
- Accounting callers can appear in backend approval or support payloads, but they
  must not land in the tenant-admin shell or see tenant-admin navigation chrome.
- Export-approval action UI belongs to tenant-admin only, even when the backend
  payload includes accounting-origin data.
- `PortalSupportTicketController` allows admin or accounting callers at the
  backend. In this portal, support ticket authoring, detail, and follow-up UX is
  admin-owned only.

## UI implications

- Gate route access with `GET /api/v1/auth/me`, not with cached role assumptions.
- If a shared component is reused in another portal, do not let that leak tenant-admin shell chrome or privileged buttons.
