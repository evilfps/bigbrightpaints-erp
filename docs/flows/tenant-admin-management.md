# Tenant / Admin Management Flow

Last reviewed: 2026-03-30

This packet documents the **tenant/admin management flow**: the canonical lifecycle from super-admin tenant onboarding through ongoing tenant administration, user management, and support operations. It covers company creation, admin provisioning, lifecycle state transitions, usage controls, module gating, user CRUD, support interventions, and platform governance surfaces.

This flow is **behavior-first** and **code-grounded**. Where the backend is incomplete, blocked, or intentionally partial, the packet explicitly states the current limitation instead of presenting partial behavior as complete.

---

## 1. Actors

| Actor | Role | Authorization Scope |
| --- | --- | --- |
| **Super admin** | User with `ROLE_SUPER_ADMIN` | Platform-wide access to `/api/v1/superadmin/**` and `/api/v1/companies/**` |
| **Tenant admin** | User with `ROLE_ADMIN` within a tenant | Manage own tenant users, settings, exports |
| **Tenant user** | Regular user with any role | Self-service only, cannot access admin surfaces |
| **Accounting role** | User with `ROLE_ACCOUNTING` | Can approve exports, access approval inbox (read-only) |

---

## 2. Entrypoints

| Entrypoint | Method | Path | Actor | Purpose |
| --- | --- | --- | --- | --- |
| List CoA templates | GET | `/api/v1/superadmin/tenants/coa-templates` | `ROLE_SUPER_ADMIN` | Get available chart-of-accounts templates |
| Onboard tenant | POST | `/api/v1/superadmin/tenants/onboard` | `ROLE_SUPER_ADMIN` | Create company, admin user, seed CoA, create default period |
| List tenants | GET | `/api/v1/superadmin/tenants` | `ROLE_SUPER_ADMIN` | List all tenants with optional status filter |
| Get tenant detail | GET | `/api/v1/superadmin/tenants/{id}` | `ROLE_SUPER_ADMIN` | Get full tenant detail with support timeline |
| Update lifecycle | PUT | `/api/v1/superadmin/tenants/{id}/lifecycle` | `ROLE_SUPER_ADMIN` | Update tenant lifecycle state (ACTIVE/SUSPENDED/DEACTIVATED) |
| Update limits | PUT | `/api/v1/superadmin/tenants/{id}/limits` | `ROLE_SUPER_ADMIN` | Update tenant usage limits (max users, requests) |
| Update modules | PUT | `/api/v1/superadmin/tenants/{id}/modules` | `ROLE_SUPER_ADMIN` | Enable/disable modules for tenant |
| Support warning | POST | `/api/v1/superadmin/tenants/{id}/support/warnings` | `ROLE_SUPER_ADMIN` | Issue support warning |
| Support admin reset | POST | `/api/v1/superadmin/tenants/{id}/support/admin-password-reset` | `ROLE_SUPER_ADMIN` | Force-reset tenant admin password |
| Support context | PUT | `/api/v1/superadmin/tenants/{id}/support/context` | `ROLE_SUPER_ADMIN` | Update support notes and tags |
| Force logout | POST | `/api/v1/superadmin/tenants/{id}/force-logout` | `ROLE_SUPER_ADMIN` | Log out all tenant users |
| Replace main admin | PUT | `/api/v1/superadmin/tenants/{id}/admins/main` | `ROLE_SUPER_ADMIN` | Replace main admin user |
| Request email change | POST | `/api/v1/superadmin/tenants/{id}/admins/{adminId}/email-change/request` | `ROLE_SUPER_ADMIN` | Request admin email change |
| Confirm email change | POST | `/api/v1/superadmin/tenants/{id}/admins/{adminId}/email-change/confirm` | `ROLE_SUPER_ADMIN` | Confirm admin email change |
| Admin list users | GET | `/api/v1/admin/users` | `ROLE_ADMIN` | List tenant users |
| Admin create user | POST | `/api/v1/admin/users` | `ROLE_ADMIN` | Create tenant user |
| Admin update user | PUT | `/api/v1/admin/users/{id}` | `ROLE_ADMIN` | Update user details |
| Admin user status | PUT | `/api/v1/admin/users/{userId}/status` | `ROLE_ADMIN` | Enable/disable user |
| Admin force-reset | POST | `/api/v1/admin/users/{userId}/force-reset-password` | `ROLE_ADMIN` | Reset user's password |
| Request export | POST | `/api/v1/exports/request` | `ROLE_ADMIN`, `ROLE_ACCOUNTING` | Request sensitive export |
| Approve export | PUT | `/api/v1/admin/exports/{requestId}/approve` | `ROLE_ADMIN` | Approve export request |
| Reject export | PUT | `/api/v1/admin/exports/{requestId}/reject` | `ROLE_ADMIN` | Reject export request |
| Publish changelog | POST | `/api/v1/superadmin/changelog` | `ROLE_SUPER_ADMIN` | Publish release notes |
| List changelog | GET | `/api/v1/changelog` | Authenticated | List release notes |

---

## 3. Preconditions

### Tenant Onboarding Preconditions

1. **Caller must be super admin** — `ROLE_SUPER_ADMIN` required
2. **Company code must be unique** — no existing company with same code
3. **Admin email must be unique** — no existing user with same email across platform
4. **CoA template must exist** — valid template code from `/api/v1/superadmin/tenants/coa-templates`
5. **Mail must be configured** — `erp.mail.enabled` and `erp.mail.send-password-reset` for credential delivery

### Lifecycle Transition Preconditions

1. **Valid state transition** — must follow `ACTIVE ↔ SUSPENDED → DEACTIVATED` machine
2. **Reason required** — each transition requires a reason string
3. **Main admin cannot be removed without replacement** — cannot deactivate main admin before new main admin is set

### Admin User Management Preconditions

1. **Caller must be tenant admin** — `ROLE_ADMIN` within own tenant
2. **Target user must belong to same tenant** — unless caller is super admin
3. **Role must be valid** — valid role ID from `Role` table

### Export Approval Preconditions

1. **Export must be pending** — request exists with status `PENDING`
2. **Caller must be tenant admin** — `ROLE_ADMIN` required to approve/reject
3. **Accounting role cannot approve** — accounting sees export requests in inbox but cannot take action

---

## 4. Lifecycle

### 4.1 Tenant Onboarding Lifecycle

```
[Start] → Validate super-admin auth → Validate company code uniqueness → 
Validate admin email uniqueness → Get CoA template → Create company entity → 
Create admin user account → Generate temp password → Seed chart of accounts → 
Create default accounting period → Send admin credentials email → [End: Tenant operational]
```

**Key behaviors:**
- Onboarding creates: company record, admin user with `ROLE_ADMIN`, seeded CoA from template, default period
- Admin password is temporary — user must change on first login
- If email delivery fails, onboarding returns success but admin must use support reset path

### 4.2 Tenant Lifecycle State Machine

```
ACTIVE ←→ SUSPENDED → DEACTIVATED
  │               │             ↑
  │               └─────────────┘
  └─────────────────────────────┘
```

| From State | To State | API | Effect |
| --- | --- | --- | --- |
| ACTIVE | SUSPENDED | `PUT /api/v1/superadmin/tenants/{id}/lifecycle` | Tenant becomes read-only (HTTP 423 on mutations) |
| SUSPENDED | ACTIVE | `PUT /api/v1/superadmin/tenants/{id}/lifecycle` | Tenant restored to full operation |
| ACTIVE | DEACTIVATED | `PUT /api/v1/superadmin/tenants/{id}/lifecycle` | Tenant fully blocked |
| SUSPENDED | DEACTIVATED | `PUT /api/v1/superadmin/tenants/{id}/lifecycle` | Tenant fully blocked |
| DEACTIVATED | ACTIVE | `PUT /api/v1/superadmin/tenants/{id}/lifecycle` | Tenant recovered |

**Runtime states** (enforced per-request, separate from lifecycle):

| Runtime State | Effect |
| --- | --- |
| `ACTIVE` | Normal operation |
| `HOLD` | Read-only (mutations denied with 423) |
| `BLOCKED` | All requests denied with 403 |

### 4.3 Admin User Management Lifecycle

```
[Create user] → Validate admin auth → Validate unique email → Create user with temp password → 
Send credentials → [End: User created]

[Update user] → Validate admin auth → Validate target user → Update mutable fields → [End: User updated]

[Disable user] → Validate admin auth → Revoke all sessions → Send suspension email → [End: User disabled]

[Force-reset password] → Validate admin auth → Generate reset token → Send reset email → 
[End: Reset link delivered]
```

### 4.4 Support Intervention Lifecycle

```
[Support warning] → Record warning in support timeline → [End: Warning issued]

[Force logout] → Revoke all refresh tokens → Blacklist all access tokens → [End: All sessions terminated]

[Admin password reset] → Generate reset token → Send reset email → [End: Reset link delivered]

[Main admin replacement] → Validate current main admin → Validate new user → 
Update main admin flag → [End: Main admin replaced]
```

### 4.5 Export Approval Lifecycle

```
[Request export] → Create export request with PENDING status → Log request → [End: Request pending]

[Admin reviews] → GET /api/v1/admin/approvals → [End: Review pending requests]

[Approve] → Validate admin auth → Validate request pending → Update status to APPROVED → 
Generate download token → Notify requester → [End: Export approved]

[Reject] → Validate admin auth → Validate request pending → Update status to REJECTED → 
Notify requester → [End: Export rejected]
```

---

## 5. Completion Boundary / Current Definition of Done

The flow is complete when:

1. **Tenant onboarding** — Company exists, admin user created, CoA seeded, default period created
2. **Lifecycle transition** — New state persisted, runtime enforcement updated, audit logged
3. **User management** — User created/updated/disabled, sessions revoked as needed
4. **Support intervention** — Action recorded in support timeline, effect applied
5. **Export approval** — Request approved/rejected, requester notified

### Current Limitations

1. **Company cannot be deleted** — `DELETE /api/v1/companies/{id}` always denies deletion

2. **Accounting cannot approve exports** — Accounting role sees export requests in approval inbox but cannot approve/reject. Export requests with accounting-only viewers have `null` action fields.

3. **Main admin cannot be self-deactivated** — Cannot remove the current main admin before replacement is in place

4. **Changelog publish has no approval** — Super-admin can publish changelog without review

5. **Support ticket sync is best-effort** — Support tickets sync to GitHub but sync failures don't block ticket creation

6. **Tenant limits not auto-enforced** — Limits are stored but automatic enforcement (e.g., preventing new user creation when at limit) is not fully implemented

---

## 6. Canonical vs Non-Canonical Paths

### Canonical Paths

| Path | Owner | Notes |
| --- | --- | --- |
| `POST /api/v1/superadmin/tenants/onboard` | `SuperAdminTenantOnboardingController` | Primary tenant creation |
| `PUT /api/v1/superadmin/tenants/{id}/lifecycle` | `SuperAdminController` | Canonical lifecycle control |
| `PUT /api/v1/superadmin/tenants/{id}/limits` | `SuperAdminController` | Quota management |
| `PUT /api/v1/superadmin/tenants/{id}/modules` | `SuperAdminController` | Module gating |
| `POST /api/v1/admin/users` | `AdminUserController` | Tenant user creation |
| `PUT /api/v1/admin/users/{userId}/status` | `AdminUserController` | User enable/disable |
| `POST /api/v1/admin/users/{userId}/force-reset-password` | `AdminUserController` | Admin password reset |

### Non-Canonical / Deprecated Paths

| Path | Status | Replacement |
| --- | --- | --- |
| `/api/v1/support/**` (shared) | Deprecated | Use host-specific: `/api/v1/portal/support/tickets` or `/api/v1/dealer-portal/support/tickets` |

---

## 7. Cross-Module Dependencies

| Module | Dependency | Direction |
| --- | --- | --- |
| `auth` | User authentication, password reset, session revocation | Write via service |
| `rbac` | Role definitions, permission matrix | Read |
| `accounting` | CoA seeding, period creation | Write via service |
| `sales` | Export request creation, approval inbox | Read |
| `portal` | Support ticket surface (dealer) | Write |

---

## 8. Security Considerations

- **Super-admin auth required** — All `/api/v1/superadmin/**` endpoints require `ROLE_SUPER_ADMIN`
- **Tenant isolation** — Tenant admins cannot access other tenants' data
- **Session revocation** — User disable and force-logout revoke all active sessions
- **Export approval gate** — Sensitive exports require tenant admin approval
- **Company context filter** — Mismatched company header vs token claims fails closed

---

## 9. Related Documentation

- [docs/modules/company.md](../modules/company.md) — Company module canonical packet
- [docs/modules/admin-portal-rbac.md](../modules/admin-portal-rbac.md) — Admin and RBAC boundaries
- [docs/modules/auth.md](../modules/auth.md) — Auth module for credential flows
- [docs/flows/FLOW-INVENTORY.md](FLOW-INVENTORY.md) — Flow inventory

---

## 10. Known Limitations

> **Note**: The authoritative classification for these items is recorded in the [Authoritative Recommendations Register](../RECOMMENDATIONS.md). This section documents factual implementation status only.

| Decision | Notes |
| --- | --- |
| Company deletion | No deletion path exists. Hard delete is not supported. |
| Auto-limit enforcement | Credit limits are stored but not all are automatically enforced. Some require manual intervention. |
| Changelog approval | No review workflow exists for changelog entries. |
