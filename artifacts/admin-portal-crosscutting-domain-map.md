# Admin / Portal / Cross-Cutting Domain Map

> Deep investigation of the BigBright Paints ERP Admin, Portal, Orchestrator, Core, and Cross-Cutting modules.
> Source root: `erp-domain/src/main/java/com/bigbrightpaints/erp`

---

## 1. Module Overview & Directory Structure

```
erp-domain/src/main/java/com/bigbrightpaints/erp/
├── config/                              # Top-level CORS + RabbitMQ config
├── controller/                          # IntegrationHealthController
├── core/
│   ├── audit/                           # AuditService, AuditLog, AuditEvent
│   ├── audittrail/                      # EnterpriseAuditTrailService, AuditActionEvent
│   │   └── web/                         # EnterpriseAuditTrailController, ingest DTOs
│   ├── config/                          # SystemSettingsService, DataInitializer, etc.
│   ├── domain/                          # VersionedEntity, NumberSequence
│   ├── exception/                       # GlobalExceptionHandler, ErrorCodes
│   ├── health/                          # ConfigurationHealthIndicator
│   ├── idempotency/                     # IdempotencyReservationService
│   ├── mapper/                          # CentralMapperConfig (MapStruct)
│   ├── notification/                    # EmailService (SMTP + Thymeleaf)
│   ├── security/                        # JWT, SecurityConfig, PortalRoleActionMatrix
│   ├── service/                         # NumberSequenceService, CriticalFixtureService
│   ├── util/                            # CompanyClock, MoneyUtils, DashboardWindow, etc.
│   └── validation/                      # ValidationUtils
├── modules/
│   ├── admin/
│   │   ├── controller/                  # AdminSettingsController, AdminUserController,
│   │   │                                # ChangelogController, SuperAdminChangelogController
│   │   ├── domain/                      # SupportTicket, ExportRequest, ChangelogEntry
│   │   ├── dto/                         # Admin DTOs (settings, users, approvals, exports, tickets)
│   │   └── service/                     # AdminUserService, ExportApprovalService,
│   │                                    # ChangelogService, PortalSupportTicketService,
│   │                                    # DealerPortalSupportTicketService,
│   │                                    # SupportTicketGitHubSyncService, GitHubIssueClient,
│   │                                    # TenantRuntimePolicyService, SupportTicketAccessSupport
│   ├── portal/
│   │   ├── controller/                  # PortalFinanceController, PortalInsightsController,
│   │   │                                # PortalSupportTicketController
│   │   ├── dto/                         # DashboardInsights, OperationsInsights,
│   │   │                                # WorkforceInsights, EnterpriseDashboardSnapshot
│   │   └── service/                     # EnterpriseDashboardService, PortalInsightsService,
│   │                                    # TenantRuntimeEnforcementInterceptor,
│   │                                    # TenantRuntimeEnforcementConfig
│   ├── auth/                            # Authentication module (JWT, login, MFA)
│   ├── company/                         # Company/tenant management, ModuleGatingService,
│   │                                    # ModuleGatingInterceptor, TenantRuntimeEnforcementService
│   ├── rbac/                            # Role-based access control
│   ├── production/domain/               # ProductionProduct, ProductionBrand, CatalogImport
│   ├── sales/service/                   # DealerPortalService (dealer self-service)
│   ├── reports/                         # Financial reports + export request endpoints
│   └── [accounting, factory, hr, inventory,
│        invoice, purchasing, demo]       # Other business modules
├── orchestrator/
│   ├── config/                          # OrchestratorFeatureFlags, SchedulerConfig, ShedLockConfig
│   ├── controller/                      # OrchestratorController, DashboardController
│   ├── dto/                             # ApproveOrderRequest, DispatchRequest, etc.
│   ├── event/                           # DomainEvent
│   ├── exception/                       # OrchestratorFeatureDisabledException
│   ├── integration/                     # ExternalSyncService
│   ├── policy/                          # PolicyEnforcer
│   ├── repository/                      # AuditRecord, OrchestratorCommand, OutboxEvent,
│   │                                    # OrderAutoApprovalState, ScheduledJobDefinition
│   ├── scheduler/                       # OutboxPublisherJob, SchedulerService
│   ├── service/                         # CommandDispatcher, EventPublisherService,
│   │                                    # IntegrationCoordinator, DashboardAggregationService,
│   │                                    # OrderAutoApprovalListener, TraceService,
│   │                                    # OrchestratorIdempotencyService,
│   │                                    # CorrelationIdentifierSanitizer
│   └── workflow/                        # WorkflowService
└── shared/dto/                          # ApiResponse, PageResponse, ErrorResponse,
                                         # DocumentLifecycleDto, LinkedBusinessReferenceDto
```

---

## 2. Admin Module

### 2.1 AdminSettingsController (`/api/v1/admin`)

| Method | Path | Auth | Description |
|--------|------|------|-------------|
| `GET` | `/api/v1/admin/settings` | ADMIN | Read system settings snapshot |
| `PUT` | `/api/v1/admin/settings` | SUPER_ADMIN | Update system settings |
| `PUT` | `/api/v1/admin/exports/{id}/approve` | ADMIN, SUPER_ADMIN | Approve export request |
| `PUT` | `/api/v1/admin/exports/{id}/reject` | ADMIN, SUPER_ADMIN | Reject export request |
| `POST` | `/api/v1/admin/notify` | ADMIN | Send email notification to user |
| `GET` | `/api/v1/admin/approvals` | ADMIN, ACCOUNTING, SUPER_ADMIN | Unified approval queue |

### 2.2 AdminUserController (`/api/v1/admin/users`)

| Method | Path | Auth | Description |
|--------|------|------|-------------|
| `GET` | `/api/v1/admin/users` | ADMIN, SUPER_ADMIN | List all tenant users |
| `POST` | `/api/v1/admin/users` | ADMIN, SUPER_ADMIN | Create user (auto-creates Dealer if ROLE_DEALER) |
| `PUT` | `/api/v1/admin/users/{id}` | ADMIN, SUPER_ADMIN | Update user (displayName, roles, company, enabled) |
| `POST` | `/api/v1/admin/users/{id}/force-reset-password` | ADMIN, SUPER_ADMIN | Force password reset |
| `PUT` | `/api/v1/admin/users/{id}/status` | ADMIN, SUPER_ADMIN | Enable/disable user |
| `PATCH` | `/api/v1/admin/users/{id}/suspend` | ADMIN, SUPER_ADMIN | Suspend user |
| `PATCH` | `/api/v1/admin/users/{id}/unsuspend` | ADMIN, SUPER_ADMIN | Unsuspend user |
| `PATCH` | `/api/v1/admin/users/{id}/mfa/disable` | ADMIN, SUPER_ADMIN | Disable MFA for user |
| `DELETE` | `/api/v1/admin/users/{id}` | ADMIN, SUPER_ADMIN | Delete user |

### 2.3 ChangelogController (`/api/v1/changelog`)

| Method | Path | Auth | Description |
|--------|------|------|-------------|
| `GET` | `/api/v1/changelog` | Any authenticated user | List changelog entries (paginated) |
| `GET` | `/api/v1/changelog/latest-highlighted` | Any authenticated user | Get latest highlighted entry |

### 2.4 SuperAdminChangelogController (`/api/v1/superadmin/changelog`)

| Method | Path | Auth | Description |
|--------|------|------|-------------|
| `POST` | `/api/v1/superadmin/changelog` | SUPER_ADMIN | Create changelog entry |
| `PUT` | `/api/v1/superadmin/changelog/{id}` | SUPER_ADMIN | Update changelog entry |
| `DELETE` | `/api/v1/superadmin/changelog/{id}` | SUPER_ADMIN | Soft-delete changelog entry |

### 2.5 Unified Approval Queue (`GET /api/v1/admin/approvals`)

The `AdminApprovalsResponse` aggregates pending approvals across five categories:

| OriginType | OwnerType | Source Entity | Description |
|------------|-----------|---------------|-------------|
| `CREDIT_REQUEST` | SALES | `CreditRequest` | Dealer permanent credit-limit increase |
| `CREDIT_LIMIT_OVERRIDE_REQUEST` | SALES/FACTORY | `CreditLimitOverrideRequest` | Dispatch-time credit override |
| `PAYROLL_RUN` | HR | `PayrollRun` (status=CALCULATED) | Payroll approval (gated by HR_PAYROLL module) |
| `PERIOD_CLOSE_REQUEST` | ACCOUNTING | `PeriodCloseRequest` | Accounting period close |
| `EXPORT_REQUEST` | REPORTS | `ExportRequest` (status=PENDING) | Data export download approval |

Each approval item includes: originType, ownerType, id, publicId, reference, status, summary, requesterUserId, requesterEmail, actionType, actionLabel, approveEndpoint, rejectEndpoint, createdAt.

**Sensitive details** (requester email, action endpoints) are only visible to ADMIN and SUPER_ADMIN roles.

---

## 3. Admin Domain Entities

### 3.1 SupportTicket (`support_tickets` table)

| Field | Type | Description |
|-------|------|-------------|
| `id` | Long | PK |
| `public_id` | UUID | External identifier |
| `company_id` | FK → Company | Tenant |
| `user_id` | Long | Requester user ID |
| `category` | Enum | BUG, FEATURE_REQUEST, SUPPORT |
| `subject` | String(255) | Required |
| `description` | Text | Required |
| `status` | Enum | OPEN → IN_PROGRESS → RESOLVED → CLOSED |
| `github_issue_number` | Long | GitHub issue number after sync |
| `github_issue_url` | String(512) | GitHub issue URL |
| `github_issue_state` | String(32) | OPEN/CLOSED from GitHub |
| `github_synced_at` | Instant | Last successful sync |
| `github_last_error` | Text | Error from last sync attempt |
| `github_last_sync_at` | Instant | Last sync attempt (success or failure) |
| `resolved_at` | Instant | When ticket was resolved |
| `resolved_notification_sent_at` | Instant | When resolution email was sent |
| `created_at` | Instant | Auto-set |
| `updated_at` | Instant | Auto-set |

### 3.2 ExportRequest (`export_requests` table)

| Field | Type | Description |
|-------|------|-------------|
| `id` | Long | PK |
| `company_id` | FK → Company | Tenant |
| `user_id` | Long | Requester |
| `report_type` | String | Type of report to export (e.g., BALANCE_SHEET) |
| `parameters` | Text | JSON parameters for the report |
| `status` | Enum | PENDING → APPROVED / REJECTED / EXPIRED |
| `approved_by` | String | Admin who approved/rejected |
| `approved_at` | Instant | Timestamp |
| `rejection_reason` | Text | Reason if rejected |
| `created_at` | Instant | Auto-set |

### 3.3 ChangelogEntry (`changelog_entries` table)

| Field | Type | Description |
|-------|------|-------------|
| `id` | Long | PK |
| `version_label` | String(32) | e.g., "1.2.0" |
| `title` | String(255) | Entry title |
| `body` | Text | Markdown content |
| `published_at` | Instant | Publication timestamp |
| `created_by` | String(255) | Author |
| `highlighted` | boolean | Featured entry flag |
| `deleted` | boolean | Soft delete flag |
| `deleted_at` | Instant | Soft delete timestamp |
| `created_at` | Instant | Auto-set |
| `updated_at` | Instant | Auto-set |

---

## 4. Admin Services

### 4.1 AdminUserService

Comprehensive user lifecycle management service:

- **createUser**: Provisions tenant accounts via `ScopedAccountBootstrapService`, auto-creates Dealer entity for ROLE_DEALER users with receivable account, enforces tenant user quota via `TenantRuntimePolicyService.assertCanAddEnabledUser()`
- **updateUser**: Supports display name, enabled status, company transfer (with quota check), role changes. Revokes tokens on permission changes.
- **deleteUser**: Revokes all tokens, deletes user, sends deletion email
- **suspend/unsuspend**: Disables user, revokes tokens, sends suspension email
- **forceResetPassword**: Triggers password reset email via `PasswordResetService`
- **disableMfa**: Clears MFA secret and recovery codes, revokes tokens
- **Role assignment guard**: Only SUPER_ADMIN can assign ROLE_ADMIN and ROLE_SUPER_ADMIN
- **Company scope enforcement**: Admin users can only manage users within their own company (SUPER_ADMIN is exempt)
- **Last login tracking**: Resolved from audit log `LOGIN_SUCCESS` events

### 4.2 ExportApprovalService

Manages the export approval workflow:

1. `createRequest()` — Creates PENDING export request, validates actor is authenticated
2. `approve()` — Transitions PENDING → APPROVED, records approver
3. `reject()` — Transitions PENDING → REJECTED, records reason
4. `resolveDownload()` — Returns download metadata if approved or if approval not required
5. `isApprovalRequired()` — Reads from `SystemSettingsService.isExportApprovalRequired()`

### 4.3 SupportTicketGitHubSyncService

Bi-directional sync between support tickets and GitHub Issues:

- **Outbound**: On ticket creation, asynchronously creates a GitHub Issue via `GitHubIssueClient`
- **Inbound**: Scheduled every 5 minutes — polls GitHub for state changes on open tickets
- **State mapping**: GitHub CLOSED → ticket RESOLVED (with notification email), GitHub OPEN → ticket IN_PROGRESS
- **Reopen handling**: If GitHub issue reopened, ticket reverts from RESOLVED/CLOSED to IN_PROGRESS
- **Error handling**: All sync errors stored in `github_last_error`, does not fail ticket creation

### 4.4 PortalSupportTicketService vs DealerPortalSupportTicketService

Two access modes for support tickets sharing `SupportTicketAccessSupport`:

| Service | Scope | Controller |
|---------|-------|------------|
| `PortalSupportTicketService` | All company tickets | `PortalSupportTicketController` (ADMIN/ACCOUNTING) |
| `DealerPortalSupportTicketService` | User's own tickets only | (used by dealer portal) |

Both use the shared `SupportTicketAccessSupport` for ticket creation and response mapping, ensuring consistent GitHub sync behavior.

### 4.5 TenantRuntimePolicyService

Per-tenant quota management:

- **metrics()** — Returns `TenantRuntimeMetricsDto` with: companyCode, state, reasonCode, maxActiveUsers, maxRequestsPerMinute, maxConcurrentRequests, activeUsers, totalUsers, minuteRequestCount, minuteRejectedCount, inFlightRequests, auditChainId, updatedAt
- **assertCanAddEnabledUser()** — Enforces active user quota; throws `BUSINESS_LIMIT_EXCEEDED` with full audit trail on denial

---

## 5. Portal Module

### 5.1 PortalFinanceController (`/api/v1/portal/finance`)

| Method | Path | Auth | Description |
|--------|------|------|-------------|
| `GET` | `/api/v1/portal/finance/ledger?dealerId=` | ADMIN, ACCOUNTING | Dealer ledger view |
| `GET` | `/api/v1/portal/finance/invoices?dealerId=` | ADMIN, ACCOUNTING | Dealer invoice list |
| `GET` | `/api/v1/portal/finance/aging?dealerId=` | ADMIN, ACCOUNTING | Dealer aging report |

Delegates to `DealerPortalService` for data retrieval.

### 5.2 PortalInsightsController (`/api/v1/portal`)

| Method | Path | Auth | Description |
|--------|------|------|-------------|
| `GET` | `/api/v1/portal/dashboard` | ADMIN | Dashboard highlights + pipeline + HR pulse |
| `GET` | `/api/v1/portal/operations` | ADMIN | Production velocity, logistics SLA, supply alerts |
| `GET` | `/api/v1/portal/workforce` | ADMIN | Workforce squad summary, upcoming moments, leaders (gated by HR_PAYROLL) |

### 5.3 PortalSupportTicketController (`/api/v1/portal/support/tickets`)

| Method | Path | Auth | Description |
|--------|------|------|-------------|
| `POST` | `/api/v1/portal/support/tickets` | ADMIN, ACCOUNTING | Create support ticket |
| `GET` | `/api/v1/portal/support/tickets` | ADMIN, ACCOUNTING | List company tickets |
| `GET` | `/api/v1/portal/support/tickets/{ticketId}` | ADMIN, ACCOUNTING | Get ticket by ID |

### 5.4 EnterpriseDashboardService

Full enterprise dashboard aggregation at `/api/v1/portal/dashboard` (via `DashboardController` in orchestrator):

**EnterpriseDashboardSnapshot** includes:
- **Financial**: baseCurrency, netRevenue, taxRevenue, grossRevenue, COGS, grossProfit, cashBalance, arOutstanding, overdueOutstanding, aging (current, 1-30, 31-60, 61-90, over90)
- **Sales**: bookedBacklog, openOrders, bookedOrderValue, bookedOrderCount, orderToCashDays
- **Operations**: inventoryValue, inventoryTurns, producedQty, packedQty, dispatchedQty, yieldPct, wastagePct
- **Ratios**: grossMarginPct, overduePct, inventoryTurns, onTimeDispatchPct
- **Trends**: revenueTrend, cogsTrend, cashTrend, arOverdueTrend (time-series data)
- **Alerts**: LOW_CASH, OVERDUE_AR, LOW_INVENTORY, DISPATCH_BACKLOG, MISSING_PROMISED_DATE, CASH_ACCOUNT_UNMAPPED
- **Breakdowns**: top 5 dealers, top 5 SKUs, top 5 overdue invoices

### 5.5 PortalInsightsService

Three insight categories:

**Dashboard Insights**:
- Revenue run rate, fulfilment SLA, active workforce (if HR enabled), dealer coverage
- Production pipeline by status
- HR pulse: engagement, leave utilization, payroll readiness

**Operations Insights**:
- Production velocity (% of batches vs plans in last 7 days)
- Logistics SLA (% dispatched)
- Working capital (assets + liabilities)
- Supply alerts (top 5 low-stock raw materials)
- Automation runs (factory task status)

**Workforce Insights** (gated by HR_PAYROLL module):
- Squad summary (top 3 roles by count)
- Upcoming moments (payroll runs, leave requests)
- Performance leaders (3 most recently hired active employees)

---

## 6. Cross-Cutting: Core Services

### 6.1 AuditService (`core/audit/`)

Simple, high-throughput audit logging:

- **Async with REQUIRES_NEW propagation** — never blocks business transactions
- **Self-injection pattern** (`@Lazy`) — ensures `@Async` and `@Transactional` proxy work for self-calls
- **Request context capture**: IP address, user agent, request method/path, session ID, trace ID
- **Company resolution**: Resolves company from token (code or numeric ID) via `CompanyRepository`
- **Event types**: LOGIN_SUCCESS, LOGIN_FAILURE, USER_CREATED, USER_UPDATED, USER_DELETED, USER_ACTIVATED, USER_DEACTIVATED, MFA_DISABLED, PASSWORD_RESET_REQUESTED, DATA_CREATE/READ/UPDATE/DELETE/EXPORT, CONFIGURATION_CHANGED, SECURITY_ALERT, SENSITIVE_DATA_ACCESSED, ACCESS_DENIED

Key methods:
- `logEvent()` — generic event logging
- `logAuthSuccess()` / `logAuthFailure()` — authentication events
- `logDataAccess()` — CRUD operation logging
- `logSecurityAlert()` — security event logging
- `logSensitiveDataAccess()` — PII access logging

### 6.2 EnterpriseAuditTrailService (`core/audittrail/`)

Advanced, enterprise-grade audit trail with retry and ML support:

- **Business audit events** (`AuditActionEvent`): Records module, action, entity type/ID, reference number, status, amount/currency, actor, correlation ID, trace ID, ML eligibility flag
- **ML interaction events** (`MlInteractionEvent`): Records UI interactions (click, view, submit, input), screen, target ID, actor identity (with anonymization via HMAC-SHA256)
- **Actor identity handling**: Supports AI personalization opt-in; anonymizes via HMAC if opted out
- **Batch ingestion**: `ingestMlInteractions()` accepts up to 200 events per request
- **Retry system**: Dual-layer — in-memory `ConcurrentLinkedDeque` + persistent `AuditActionEventRetry` table. Scheduled retry every 30s. Max 4 attempts, then drop.
- **Query API**: Paginated query with filters (date range, module, action, status, actor)

**EnterpriseAuditTrailController** (`/api/v1/audit/`):
- `POST /api/v1/audit/business-events` — Record business events
- `POST /api/v1/audit/ml-events` — Ingest ML interaction events
- `GET /api/v1/audit/business-events` — Query business events
- `GET /api/v1/audit/ml-events` — Query ML events

### 6.3 EmailService (`core/notification/`)

SMTP-based email service with Thymeleaf templating:

| Method | Template | Description |
|--------|----------|-------------|
| `sendSimpleEmail()` | Plain text | Generic email |
| `sendUserCredentialsEmailRequired()` | `mail/credentials` | New user credentials with temp password |
| `sendPasswordResetEmailRequired()` | `mail/password-reset` | Password reset link (60-min expiry) |
| `sendPasswordResetConfirmation()` | `mail/password-reset-confirmed` | Reset confirmation |
| `sendAdminEmailChangeVerificationRequired()` | `mail/admin-email-change-verification` | Email change verification token |
| `sendInvoiceEmail()` | `mail/invoice-email` | Invoice with PDF attachment |
| `sendPayrollSheetEmail()` | Plain text + PDF | Payroll payment sheet |
| `sendUserSuspendedEmail()` | `mail/user-suspended` | Account suspension notice |
| `sendUserDeletedEmail()` | `mail/user-deleted` | Account deletion notice |

**Configuration toggles** (via `EmailProperties`):
- `erp.mail.enabled` — Master email switch
- `erp.mail.send-credentials` — Credential email delivery
- `erp.mail.send-password-reset` — Password reset email delivery
- `erp.mail.from-address` — Sender address
- `erp.mail.base-url` — Application base URL for links

### 6.4 Security (`core/security/`)

#### PortalRoleActionMatrix

Defines role-based access control expressions:

| Constant | Expression | Roles |
|----------|-----------|-------|
| `DEALER_ONLY` | `hasAuthority('ROLE_DEALER')` | Dealer |
| `ADMIN_ONLY` | `hasAuthority('ROLE_ADMIN')` | Admin |
| `SUPER_ADMIN_ONLY` | `hasAuthority('ROLE_SUPER_ADMIN')` | Super Admin |
| `ADMIN_OR_ACCOUNTING` | `hasAnyAuthority('ROLE_ADMIN','ROLE_ACCOUNTING')` | Admin, Accounting |
| `ADMIN_SALES_ACCOUNTING` | `hasAnyAuthority('ROLE_ADMIN','ROLE_SALES','ROLE_ACCOUNTING')` | Admin, Sales, Accounting |
| `ADMIN_SALES_FACTORY_ACCOUNTING` | `hasAnyAuthority('ROLE_ADMIN','ROLE_SALES','ROLE_FACTORY','ROLE_ACCOUNTING')` | + Factory |
| `ADMIN_FACTORY` | `hasAnyAuthority('ROLE_ADMIN','ROLE_FACTORY')` | Admin, Factory |
| `ADMIN_FACTORY_SALES` | `hasAnyAuthority('ROLE_ADMIN','ROLE_FACTORY','ROLE_SALES')` | Admin, Factory, Sales |
| `ADMIN_ACCOUNTING_SUPER_ADMIN` | `hasAnyAuthority('ROLE_ADMIN','ROLE_ACCOUNTING','ROLE_SUPER_ADMIN')` | Admin, Accounting, Super Admin |
| `FINANCIAL_DISPATCH` | `hasAnyAuthority('ROLE_ADMIN','ROLE_ACCOUNTING') and hasAuthority('dispatch.confirm')` | Accounting + dispatch |
| `OPERATIONAL_DISPATCH` | `hasAnyAuthority('ROLE_ADMIN','ROLE_FACTORY') and hasAuthority('dispatch.confirm')` | Factory + dispatch |

Also provides contextual `resolveAccessDeniedMessage()` for user-friendly access denied messages.

#### Other Security Components

| Component | Description |
|-----------|-------------|
| `SecurityConfig` | Spring Security configuration |
| `JwtTokenService` / `JwtAuthenticationFilter` | JWT token generation and validation |
| `TokenBlacklistService` | Token revocation for logout/suspension |
| `CompanyContextHolder` / `CompanyContextFilter` | ThreadLocal company context |
| `CryptoService` | AES encryption for bank details |
| `LicensingGuard` | License validation |
| `SecurityMonitoringService` | Security event monitoring |
| `MustChangePasswordCorridorFilter` | Forces password change on first login |
| `SecurityActorResolver` | Resolves current authenticated actor |
| `AuthScopeService` | Multi-scope authentication (platform auth code) |
| `TenantRuntimeAccessService` | Tenant runtime access validation |

### 6.5 SystemSettingsService (`core/config/`)

Runtime-tunable settings persisted to `system_settings` DB table:

| Setting | Config Property | Default | Description |
|---------|----------------|---------|-------------|
| `allowedOrigins` | `erp.cors.allowed-origins` | `http://localhost:3002` | CORS origins |
| `autoApprovalEnabled` | `erp.auto-approval.enabled` | `true` | Sales order auto-approval |
| `periodLockEnforced` | `erp.period-lock.enforced` | `true` | Accounting period lock enforcement |
| `exportApprovalRequired` | `erp.export.require-approval` | `false` | Export approval gate |
| `mailEnabled` | `erp.mail.enabled` | false | Email delivery switch |
| `mailFromAddress` | `erp.mail.from` | — | Sender address |
| `mailBaseUrl` | `erp.mail.base-url` | — | App URL for links |
| `sendCredentials` | `erp.mail.send-credentials` | false | Credential emails |
| `sendPasswordReset` | `erp.mail.send-password-reset` | false | Reset emails |
| `platformAuthCode` | — | — | Platform auth scope code (via AuthScopeService) |

Settings are loaded at startup from DB (falling back to config defaults) and can be updated at runtime via the admin API.

### 6.6 Other Core Services

| Service | Description |
|---------|-------------|
| `NumberSequenceService` | Auto-numbering for business documents |
| `IdempotencyReservationService` | Idempotent request handling |
| `CriticalFixtureService` | Ensures essential data fixtures exist |
| `GlobalExceptionHandler` | Centralized exception handling with `ErrorCode` enum |
| `CompanyClock` | Company timezone-aware clock |
| `DashboardWindow` | Date range resolution for dashboard queries |
| `MoneyUtils` | Currency/BigDecimal utilities |
| `PasswordUtils` | Password hashing utilities |
| `ValidationUtils` | Input validation helpers |
| `BusinessDocumentTruths` | Shared business document validation rules |
| `CentralMapperConfig` | MapStruct configuration |

---

## 7. Module Gating / Feature Flag System

### 7.1 CompanyModule Enum

| Module | Gatable | Core | Description |
|--------|---------|------|-------------|
| `AUTH` | No | Yes | Authentication |
| `ACCOUNTING` | No | Yes | Accounting |
| `SALES` | No | Yes | Sales |
| `INVENTORY` | No | Yes | Inventory |
| `MANUFACTURING` | Yes | No | Factory / Production |
| `HR_PAYROLL` | Yes | No | HR & Payroll |
| `PURCHASING` | Yes | No | Purchasing |
| `PORTAL` | Yes | No | Dealer & Admin portal |
| `REPORTS_ADVANCED` | Yes | No | Financial reports |

**Default enabled gatable modules**: MANUFACTURING, PURCHASING, PORTAL, REPORTS_ADVANCED
(HR_PAYROLL is opt-in.)

### 7.2 ModuleGatingService

- `isEnabled(company, module)` — Checks if a gatable module is in the company's `enabledModules` set
- `requireEnabled(company, module, path)` — Throws `MODULE_DISABLED` if not enabled
- Core modules (AUTH, ACCOUNTING, SALES, INVENTORY) always return `true`

### 7.3 ModuleGatingInterceptor

Spring MVC `HandlerInterceptor` registered on `/api/v1/**`:

| Path Prefix | Module |
|-------------|--------|
| `/api/v1/factory`, `/api/v1/production` | `MANUFACTURING` |
| `/api/v1/hr`, `/api/v1/payroll`, `/api/v1/accounting/payroll` | `HR_PAYROLL` |
| `/api/v1/purchasing`, `/api/v1/suppliers` | `PURCHASING` |
| `/api/v1/portal`, `/api/v1/dealer-portal` | `PORTAL` |
| `/api/v1/reports` | `REPORTS_ADVANCED` |
| `/api/v1/auth` | `AUTH` |
| `/api/v1/accounting`, `/api/v1/invoices`, `/api/v1/audit` | `ACCOUNTING` |
| `/api/v1/sales`, `/api/v1/dealers`, `/api/v1/credit/*` | `SALES` |
| `/api/v1/inventory`, `/api/v1/raw-materials`, `/api/v1/dispatch`, `/api/v1/finished-goods` | `INVENTORY` |

Paths not matching any prefix are ungated (return null → pass through).

### 7.4 OrchestratorFeatureFlags

Application-level feature flags via config properties:

| Flag | Config Property | Default |
|------|----------------|---------|
| `payrollEnabled` | `orchestrator.payroll.enabled` | `false` |
| `factoryDispatchEnabled` | `orchestrator.factory-dispatch.enabled` | `false` |

These control whether the orchestrator dispatches payroll and factory dispatch workflows.

---

## 8. Tenant Runtime Enforcement

### 8.1 TenantRuntimeEnforcementInterceptor

Spring MVC interceptor that gates specific paths:

**Enforced paths**:
- `/api/v1/reports/**`
- `/api/v1/portal/**`
- `/api/v1/demo/**`

**Flow**:
1. Checks if canonical admission already applied (via `TenantRuntimeRequestAttributes`)
2. If not, calls `TenantRuntimeRequestAdmissionService.beginRequest()` with company code, path, method, actor
3. If not admitted, throws `ApplicationException` with:
   - `BUSINESS_LIMIT_EXCEEDED` for quota rejections (includes quotaType, quotaValue, observed)
   - `BUSINESS_INVALID_STATE` for tenant state issues (includes holdState, holdReason)

### 8.2 TenantRuntimeEnforcementConfig

Registers three interceptors in order on `/api/v1/**`:

1. **TenantUsageMetricsInterceptor** — Tracks API request count per tenant
2. **ModuleGatingInterceptor** — Enforces module feature flags
3. **TenantRuntimeEnforcementInterceptor** — Enforces rate limits and tenant state

---

## 9. Orchestrator Module

### 9.1 OrchestratorController (`/api/v1/orchestrator`)

| Method | Path | Auth | Description |
|--------|------|------|-------------|
| `POST` | `/api/v1/orchestrator/orders/{orderId}/approve` | ADMIN, SALES | Approve sales order via command dispatch |
| `POST` | `/api/v1/orchestrator/orders/{orderId}/fulfillment` | ADMIN, FACTORY | Update order fulfillment status |
| `GET` | `/api/v1/orchestrator/traces/{traceId}` | ADMIN, ACCOUNTING, SALES, FACTORY | Get trace events |
| `GET` | `/api/v1/orchestrator/health/integrations` | ADMIN | Integration health check |
| `GET` | `/api/v1/orchestrator/health/events` | ADMIN | Event system health |

**Idempotency**: All mutating operations support `Idempotency-Key` and `X-Request-Id` headers. Auto-generated keys use SHA-256 of command + company + payload signature.

### 9.2 OrderAutoApprovalListener

Event-driven auto-approval:
- Listens for `SalesOrderCreatedEvent` (after transaction commit)
- If `SystemSettingsService.isAutoApprovalEnabled()` is true:
  - Dispatches auto-approve command via `CommandDispatcher`
  - Attaches trace ID to sales order
- If disabled: logs and skips

### 9.3 Orchestrator Infrastructure

| Component | Description |
|-----------|-------------|
| `CommandDispatcher` | Dispatches orchestrator commands (approve, fulfill, payroll) |
| `EventPublisherService` | Publishes domain events |
| `IntegrationCoordinator` | Coordinates external system sync |
| `DashboardAggregationService` | Aggregates dashboard data |
| `TraceService` | Command trace lookup |
| `OrchestratorIdempotencyService` | Command deduplication |
| `OutboxPublisherJob` | Scheduled outbox event publisher |
| `SchedulerService` | Job scheduling with ShedLock |
| `PolicyEnforcer` | Business policy enforcement |
| `WorkflowService` | Workflow state machine management |

### 9.4 Orchestrator Data Model

| Entity | Table | Description |
|--------|-------|-------------|
| `OrchestratorCommand` | `orchestrator_commands` | Command audit log |
| `AuditRecord` | `orchestrator_audit_records` | Orchestrator-specific audit |
| `OutboxEvent` | `outbox_events` | Transactional outbox for event publishing |
| `OrderAutoApprovalState` | `order_auto_approval_states` | Auto-approval tracking |
| `ScheduledJobDefinition` | `scheduled_job_definitions` | Scheduled job registry |
| `DomainEvent` | — | Event base class |

---

## 10. Master Data: Product Catalog

### 10.1 ProductionProduct (`production_products` table)

| Field | Type | Description |
|-------|------|-------------|
| `id` | Long | PK |
| `public_id` | UUID | External ID |
| `company_id` | FK → Company | Tenant |
| `brand_id` | FK → ProductionBrand | Brand (required) |
| `product_name` | String | Product name |
| `category` | String | Product category |
| `default_colour` | String | Default color |
| `size_label` | String | Default size |
| `unit_of_measure` | String | UOM |
| `sku_code` | String | Unique SKU per company |
| `variant_group_id` | UUID | Groups product variants |
| `product_family_name` | String | Product family |
| `hsn_code` | String | HSN code for GST |
| `is_active` | boolean | Active flag |
| `colors` | Set<String> | Available colors |
| `sizes` | Set<String> | Available sizes |
| `carton_sizes` | Map<String, Integer> | Size → pieces per carton |
| `base_price` | BigDecimal | Base selling price |
| `gst_rate` | BigDecimal | GST rate (%) |
| `min_discount_percent` | BigDecimal | Minimum discount (%) |
| `min_selling_price` | BigDecimal | Floor selling price |
| `metadata` | JSON (jsonb) | Flexible metadata |

**Unique constraints**: `(company_id, sku_code)` and `(brand_id, product_name)`

### 10.2 ProductionBrand (`production_brands` table)

| Field | Type | Description |
|-------|------|-------------|
| `id` | Long | PK |
| `public_id` | UUID | External ID |
| `company_id` | FK → Company | Tenant |
| `name` | String | Brand name |
| `code` | String | Brand code |
| `logo_url` | String | Logo URL |
| `description` | String | Brand description |
| `is_active` | boolean | Active flag |

**Unique constraints**: `(company_id, code)` and `(company_id, name)`

### 10.3 CatalogImport (`catalog_imports` table)

Tracks product catalog bulk imports:

| Field | Type | Description |
|-------|------|-------------|
| `id` | Long | PK |
| `company_id` | FK → Company | Tenant |
| `idempotency_key` | String(128) | Import deduplication key |
| `idempotency_hash` | String(64) | Content hash |
| `file_hash` | String(64) | Source file hash |
| `file_name` | String(256) | Source file name |
| `rows_processed` | int | Total rows processed |
| `brands_created` | int | New brands created |
| `products_created` | int | New products created |
| `products_updated` | int | Existing products updated |
| `raw_materials_seeded` | int | Raw materials auto-seeded |
| `errors_json` | Text | Import error details |
| `created_at` | Instant | Import timestamp |

---

## 11. Import/Export Flows

### 11.1 Data Export Approval Flow

```
User → POST /api/v1/exports/request → ExportApprovalService.createRequest()
                                         ↓
                                    ExportRequest (PENDING)
                                         ↓
Admin → GET /api/v1/admin/approvals → Sees pending export in approval queue
                                         ↓
Admin → PUT /api/v1/admin/exports/{id}/approve → ExportApprovalService.approve()
        OR
Admin → PUT /api/v1/admin/exports/{id}/reject  → ExportApprovalService.reject()
                                         ↓
User → GET /api/v1/exports/{id}/download → ExportApprovalService.resolveDownload()
```

**Bypass**: If `SystemSettingsService.isExportApprovalRequired()` is false, downloads are allowed without approval.

### 11.2 Catalog Import Flow

Product catalogs can be bulk-imported via the production module, tracked through `CatalogImport` entities with full idempotency support (key + hash + file hash) and import metrics (brands created, products created/updated, raw materials seeded).

### 11.3 Report Export

Reports support an optional `exportFormat` query parameter. The `ReportController` exposes:

| Endpoint | Report |
|----------|--------|
| `GET /api/v1/reports/balance-sheet` | Balance Sheet |
| `GET /api/v1/reports/profit-loss` | Profit & Loss |
| `GET /api/v1/reports/trial-balance` | Trial Balance |
| `GET /api/v1/reports/cash-flow` | Cash Flow Statement |
| `GET /api/v1/reports/inventory-valuation` | Inventory Valuation |
| `GET /api/v1/reports/gst-return` | GST Return Report |
| `GET /api/v1/reports/aged-debtors` | Aged Debtors |
| `GET /api/v1/reports/inventory-reconciliation` | Inventory Reconciliation |
| `GET /api/v1/reports/balance-warnings` | Balance Warnings |
| `GET /api/v1/reports/reconciliation-dashboard` | Bank Reconciliation Dashboard |
| `GET /api/v1/reports/account-statement` | Account Statement |
| `GET /api/v1/reports/wastage` | Wastage Report |
| `GET /api/v1/reports/production-logs/{id}/cost-breakdown` | Production Cost Breakdown |
| `GET /api/v1/reports/monthly-production-costs` | Monthly Production Costs |
| `GET /api/v1/reports/aging/receivables` | Aged Receivables |
| `GET /api/v1/reports/balance-sheet/hierarchy` | Hierarchical Balance Sheet |
| `GET /api/v1/reports/income-statement/hierarchy` | Hierarchical Income Statement |

**All report endpoints require ADMIN or ACCOUNTING role.**

---

## 12. Dealer Portal (Self-Service)

### 12.1 DealerPortalService Endpoints

Dealer self-service is accessible through `DealerPortalService`:

| Capability | Method | Description |
|------------|--------|-------------|
| `GET /api/v1/dealer-portal/dashboard` | `getMyDashboard()` | Credit status, balance, aging, orders overview |
| `GET /api/v1/dealer-portal/ledger` | `getMyLedger()` | Full ledger view |
| `GET /api/v1/dealer-portal/invoices` | `getMyInvoices()` | Invoice list with outstanding totals |
| `GET /api/v1/dealer-portal/aging` | `getMyOutstandingAndAging()` | Aging buckets + overdue invoices + credit utilization |
| `GET /api/v1/dealer-portal/orders` | `getMyOrders()` | Order list with pending credit exposure |
| `GET /api/v1/dealer-portal/invoices/{id}/pdf` | `getMyInvoicePdf()` | Download invoice PDF |

### 12.2 Dealer Identity Resolution

- Primary: Match authenticated user ID → `Dealer.portalUser` (via `portalUserId`)
- Fallback: Match authenticated email → `Dealer.portalUserEmail` (case-insensitive)
- Enforces single dealer per user (ambiguous mappings throw `AccessDeniedException`)

### 12.3 Credit Status Logic

```
creditUsed = totalOutstanding + pendingOrderExposure
availableCredit = max(0, creditLimit - creditUsed)

Status: WITHIN_LIMIT (ratio < 0.80)
        NEAR_LIMIT  (0.80 ≤ ratio < 1.0)
        OVER_LIMIT  (ratio ≥ 1.0)
```

---

## 13. Key Design Patterns

1. **Multi-tenant isolation**: Every entity has `company_id`; all queries filter via `CompanyContextService`
2. **Dual-layer audit**: Simple `AuditService` for high-throughput events + `EnterpriseAuditTrailService` for business/ML audit trails with retry
3. **Module gating**: URL-path-based feature flag system via `ModuleGatingInterceptor` + `CompanyModule` enum
4. **Tenant runtime enforcement**: Request admission control with quota tracking on portal/reports/demo paths
5. **Approval workflows**: Unified approval queue aggregating 5 approval categories (credit, override, payroll, period close, export)
6. **GitHub integration**: Support tickets bi-directionally sync with GitHub Issues, including auto-notification on resolution
7. **Idempotency**: Export requests, orchestrator commands, catalog imports all use idempotency keys
8. **Role-based access matrix**: Centralized `PortalRoleActionMatrix` with 11+ role combinations
9. **Async processing**: Audit logging, GitHub sync, and event publishing all use `@Async`
10. **Intercepted architecture**: Three interceptor layers (usage metrics → module gating → runtime enforcement) on all `/api/v1/**` paths
