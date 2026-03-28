# Role Boundaries

## Access model

- Required backend authority: `ROLE_SUPER_ADMIN`
- Required frontend shell: platform-only shell

## Hard boundaries

- A superadmin must not see tenant-admin navigation or approval-inbox navigation.
- A superadmin must not be routed into `/tenant/*`, `/accounting/*`, `/sales/*`, `/factory/*`, or `/dealer/*`.
- Platform operators can view tenant support and lifecycle state, but they do not perform tenant business actions from this portal.
- `GET /api/v1/admin/approvals` is not part of this portal even though some backend internals may expose broader authorities elsewhere.

## UI implications

- Hide tenant business tabs entirely, not just disable them.
- Render tenant mutations only when both the role guard and `availableActions` allow them.
- Support timeline is read-heavy. Recovery actions remain explicit and operator-driven.
