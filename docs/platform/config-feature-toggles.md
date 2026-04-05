# Platform Configuration and Feature Toggles

Last reviewed: 2026-03-30

This packet documents the high-impact platform settings and feature toggles that maintainers must understand. A maintainer should be able to discover the important platform knobs without searching `application.yml` by hand, and understand their scope, defaults, and operational caveats.

It fulfills VAL-PLAT-007.

> **Scope note:** This packet covers the configuration surface that controls platform-wide and per-tenant behavior. For the runtime-gating architecture that consumes some of these settings, see [core-audit-runtime-settings.md](../modules/core-audit-runtime-settings.md). For the security filter chain and error contract, see [core-security-error.md](../modules/core-security-error.md). For module gating mechanics, see [company.md](../modules/company.md).

---

## Ownership Summary

| Area | Package / Class | Role |
| --- | --- | --- |
| Runtime-tunable settings | `core/config/SystemSettingsService` | Global settings persisted in `system_settings` table; updatable via admin API |
| Email configuration | `core/config/EmailProperties` (`erp.mail.*`) | SMTP and email delivery switches |
| Licensing enforcement | `core/config/LicensingProperties` + `core/security/LicensingGuard` | CryptoLens license check at startup |
| CORS configuration | `core/config/SystemSettingsService` (runtime) + `erp.cors.*` (config) | Allowed origins and HTTP origin policies |
| Security monitoring | `core/security/SecurityMonitoringService` (`security.monitoring.*`) | Brute-force detection, rate limiting, suspicious-activity scoring |
| Security filter toggles | `core/security/SecurityConfig` (`erp.security.*`) | Swagger exposure, encryption key |
| Module gating | `modules/company/service/ModuleGatingService` + `ModuleGatingInterceptor` | Per-tenant module enable/disable via `CompanyModule` enum |
| Runtime enforcement | `modules/company/service/TenantRuntimeEnforcementService` (`erp.tenant.runtime.*`) | Per-tenant rate limits, concurrency, active-user quotas |
| Orchestrator feature flags | `orchestrator/config/OrchestratorFeatureFlags` (`orchestrator.*.enabled`) | Payroll and factory-dispatch orchestration toggles |
| Inventory-accounting bridge | `modules/accounting/event/InventoryAccountingEventListener` (`erp.inventory.accounting.events.enabled`) | Auto-posting of inventory movement/valuation events to GL |
| Inventory feature toggles | `modules/inventory/service/*` (`erp.inventory.*`) | Opening-stock import, raw-material intake |
| Accounting event trail | `modules/accounting/internal/AccountingCoreEngineCore` (`erp.accounting.event-trail.strict`) | Strictness of accounting event-trail validation |
| Enterprise audit trail retry | `core/audittrail/EnterpriseAuditTrailService` (`erp.audit.business.retry.*`) | Retry queue size, max attempts, backoff |
| Seed and bootstrap | `core/config/DataInitializer`, `MockDataInitializer`, etc. (`erp.seed.*`, `erp.validation-seed.*`) | Super-admin, dev-admin, mock-admin, and benchmark seed toggles |
| Environment validation | `core/health/ConfigurationHealthIndicator`, `RequiredConfigHealthIndicator` (`erp.environment.validation.*`) | Config health indicators for prod readiness |
| Benchmark overrides | `core/util/CompanyClock`, `AccountingCoreEngineCore` (`erp.benchmark.*`) | Date override and date-validation skip for benchmarks |
| Outbox tuning | `orchestrator/service/EventPublisherService` (`orchestrator.outbox.*`) | Outbox polling cron, lease seconds, ambiguous-recheck interval |

---

## 1. Runtime-Tunable Global Settings

These settings are managed through `SystemSettingsService` and persisted in the `system_settings` table. They can be changed at runtime via the admin API (`AdminSettingsController`, `/api/v1/admin/settings`) without restarting the application.

### 1.1 Auto-Approval

| Property | Default | Scope | Description |
| --- | --- | --- | --- |
| `auto-approval.enabled` | `true` | Global | Whether sales orders are automatically approved upon creation. When `false`, sales orders require manual approval before proceeding to dispatch. |

**Key:** `auto-approval.enabled` in `system_settings` table

**Config default:** `erp.auto-approval.enabled=true`

**Consumer:** `OrderAutoApprovalListener` in the orchestrator listens for `SalesOrderCreatedEvent` and auto-approves orders when this setting is enabled.

**Operational note:** Disabling auto-approval does not affect existing already-approved orders; it only prevents new orders from being auto-approved.

### 1.2 Period Lock

| Property | Default | Scope | Description |
| --- | --- | --- | --- |
| `period-lock.enforced` | `true` | Global | Whether accounting period locking is enforced. When `true`, journal entries cannot be posted to locked or closed periods. When `false`, the period-lock check is bypassed. |

**Key:** `period-lock.enforced` in `system_settings` table

**Config default:** `erp.period-lock.enforced=true`

**Consumer:** `AccountingCoreEngineCore` checks period state before posting journal entries.

**Operational caveat:** Disabling period-lock enforcement in production weakens financial controls. This should only be disabled deliberately for migration or correction scenarios and re-enabled immediately after.

### 1.3 Export Approval

| Property | Default | Scope | Description |
| --- | --- | --- | --- |
| `export.require-approval` | `false` | Global | Whether data exports require admin approval before they are released to the requesting user. When `true`, exports enter a pending state and must be approved via the admin export-approval workflow. |

**Key:** `export.require-approval` in `system_settings` table

**Config default:** `erp.export.require-approval=false`

**Consumer:** Export controllers check this setting before releasing export data. When enabled, exports are routed through the approval workflow documented in [admin-portal-rbac.md](../modules/admin-portal-rbac.md).

### 1.4 Mail / Notification Settings

| Property | Default | Scope | Description |
| --- | --- | --- | --- |
| `mail.enabled` | (config: `erp.mail.enabled`, default `true`) | Global | Whether email sending is enabled. When `false`, all email delivery is skipped silently. |
| `mail.from` | `noreply@bigbrightpaints.com` | Global | Sender address for outgoing emails. |
| `mail.base-url` | `http://localhost:3004` | Global | Base URL used for links in email templates (password reset, etc.). |
| `mail.send-credentials` | `true` | Global | Whether to send credential emails when new user accounts are created. |
| `mail.send-password-reset` | `true` | Global | Whether to send password-reset emails when users request a reset. |

**Keys:** `mail.enabled`, `mail.from`, `mail.base-url`, `mail.send-credentials`, `mail.send-password-reset` in `system_settings` table

**Config prefix:** `erp.mail.*` (via `EmailProperties`)

**Dual-default note:** The `mail.enabled` setting has a split default that depends on which layer reads it. `EmailProperties` (`@ConfigurationProperties(prefix = "erp.mail")`) defaults `enabled` to `false` at the Java-field level, so email sending is off until explicitly enabled through config or the runtime settings API. However, `SmtpPropertiesValidator` reads `erp.mail.enabled` via `@Value("${erp.mail.enabled:true}")`, which defaults to `true`. In practice this means: if `erp.mail.enabled` is never set, the `EmailProperties` bean sees `false` (no email is sent), but the SMTP validator also sees `true` (so it will enforce SMTP property completeness in the `prod` profile). To avoid a startup failure in production, either set `erp.mail.enabled=false` explicitly (which also silences the validator) or provide complete SMTP configuration. The runtime-tunable value stored in `system_settings` overrides the `EmailProperties` field at startup, but does not affect `SmtpPropertiesValidator`, which only reads the Spring config property.

**Consumer:** `EmailService` checks these flags before attempting to send any email. `CompanyService` and `PasswordResetService` also guard credential and password-reset delivery on the corresponding sub-flags.

**Operational caveat:** If `erp.mail.enabled=true` but SMTP properties (`spring.mail.host`, `spring.mail.username`, `spring.mail.password`) are missing or invalid, email sending will fail at delivery time. The `RequiredConfigHealthIndicator` (when enabled) reports mail as misconfigured in this case.

**SMTP validation:** `SmtpPropertiesValidator` (active only under the `prod` profile, excluded for `seed`) runs at startup and throws `IllegalStateException` if `erp.mail.enabled=true` but required SMTP properties are missing or use the default `changeme` password. This prevents the application from starting in production with incomplete mail configuration. The validator checks `spring.mail.host`, `spring.mail.username`, and `spring.mail.password` (when SMTP auth is enabled). When `erp.mail.enabled=false`, the validator skips all checks entirely.

### 1.5 CORS Allowed Origins

| Property | Default | Scope | Description |
| --- | --- | --- | --- |
| `cors.allowed-origins` | `http://localhost:3002` | Global | Comma-separated list of allowed CORS origins. |
| `erp.cors.allow-tailscale-http-origins` | `false` | Config-only | Allow Tailscale (100.64.0.0/10) HTTP origins in production. |

**Keys:** `cors.allowed-origins` in `system_settings` table (runtime-updatable)

**Config default:** `erp.cors.allowed-origins=http://localhost:3002`

**Consumer:** `SystemSettingsService.buildCorsConfiguration()` builds the Spring CORS configuration from the current origin list.

**Validation rules:**
- Wildcards (`*`) are rejected.
- Only `https` origins are allowed in the `prod` profile by default.
- `http` origins are allowed for localhost/loopback addresses unconditionally.
- Non-localhost `http` origins are rejected in `prod` unless `erp.environment.validation.enabled=false` (private-network IPs) or `erp.cors.allow-tailscale-http-origins=true` (Tailscale IPs).
- Origins with userinfo, query strings, fragments, or non-root paths are rejected.

### 1.6 Platform Auth Code

| Property | Default | Scope | Description |
| --- | --- | --- | --- |
| `auth.platform.code` | `PLATFORM` | Global | The scope code used to identify platform/super-admin authentication. |

**Managed by:** `AuthScopeService` (via `SystemSettingsService.update()`)

**Consumer:** `AuthScopeService` uses this code to identify super-admin vs tenant-scoped JWT tokens.

---

## 2. Per-Tenant Settings

These settings are scoped to individual tenants and are not directly tunable through the admin settings API. They are managed through specialized services.

### 2.1 Runtime Enforcement (Per-Tenant)

| Property Pattern | Default | Scope | Description |
| --- | --- | --- | --- |
| `tenant.runtime.hold-state.{companyId}` | `ACTIVE` | Per-tenant | Runtime state: `ACTIVE`, `HOLD` (mutating requests blocked), or `BLOCKED` (all requests blocked). |
| `tenant.runtime.hold-reason.{companyId}` | — | Per-tenant | Human-readable reason for hold/blocked state. |
| `tenant.runtime.max-concurrent-requests.{companyId}` | `200` | Per-tenant | Maximum concurrent requests allowed. |
| `tenant.runtime.max-requests-per-minute.{companyId}` | `5000` | Per-tenant | Maximum requests per minute allowed. |
| `tenant.runtime.max-active-users.{companyId}` | `500` | Per-tenant | Maximum active users allowed during auth operations. |
| `tenant.runtime.policy-reference.{companyId}` | — | Per-tenant | Audit chain ID for policy-change traceability. |
| `tenant.runtime.policy-updated-at.{companyId}` | — | Per-tenant | Last policy update timestamp. |

**Config defaults:** `erp.tenant.runtime.default-max-concurrent-requests=200`, `erp.tenant.runtime.default-max-requests-per-minute=5000`, `erp.tenant.runtime.default-max-active-users=500`

**Cache TTL:** `erp.tenant.runtime.policy-cache-seconds=15` — both `TenantRuntimeEnforcementService` and `TenantRuntimeAccessService` cache policies in-memory with this TTL.

**Managed by:** `TenantRuntimeEnforcementService` via the super-admin control-plane API.

**Legacy fallback:** `TenantRuntimeAccessService` also reads legacy per-company-code keys (`tenant.runtime.{companyCode}.state`, `tenant.runtime.{companyCode}.quota.max-concurrent`, etc.) as fallbacks when company-ID-scoped keys are absent. Company-ID-scoped keys always take precedence.

**Detailed documentation:** See [core-audit-runtime-settings.md](../modules/core-audit-runtime-settings.md) §2–3 for the full runtime-gating architecture, dual-enforcement-service risk, and counter-reset caveats.

### 2.2 Module Gating (Per-Tenant)

| Property | Scope | Description |
| --- | --- | --- |
| `Company.enabledModules` | Per-tenant (on `Company` entity) | Set of enabled gatable module names. |

**Gatable modules** (can be enabled/disabled per tenant):

| Module | Default Enabled | Path Prefixes |
| --- | --- | --- |
| `MANUFACTURING` | Yes | `/api/v1/factory`, `/api/v1/production` |
| `PURCHASING` | Yes | `/api/v1/purchasing`, `/api/v1/suppliers` |
| `PORTAL` | Yes | `/api/v1/portal`, `/api/v1/dealer-portal` |
| `REPORTS_ADVANCED` | Yes | `/api/v1/reports` |
| `HR_PAYROLL` | **No** | `/api/v1/hr`, `/api/v1/payroll`, `/api/v1/accounting/payroll` |

**Core modules** (always enabled, not gatable):

| Module | Path Prefixes |
| --- | --- |
| `AUTH` | `/api/v1/auth` |
| `ACCOUNTING` | `/api/v1/accounting`, `/api/v1/invoices`, `/api/v1/audit` |
| `SALES` | `/api/v1/sales`, `/api/v1/dealers`, `/api/v1/credit/override-requests`, `/api/v1/credit/limit-requests` |
| `INVENTORY` | `/api/v1/inventory`, `/api/v1/raw-materials`, `/api/v1/dispatch`, `/api/v1/finished-goods` |

**Enforcement:** `ModuleGatingInterceptor` resolves the target module from the request path and calls `ModuleGatingService.requireEnabledForCurrentCompany()`. If the module is disabled for the tenant, the request is rejected with `MODULE_DISABLED`.

**Managed by:** `CompanyService` through company update endpoints.

### 2.3 Accounting Settings (Per-Tenant)

| Setting | Stored On | Description |
| --- | --- | --- |
| `payrollExpenseAccount` | `Company` entity | Default payroll expense account. |
| `payrollCashAccount` | `Company` entity | Default payroll cash/bank account. |
| `gstInputTaxAccountId` | `Company` entity | GST input tax account. |
| `gstOutputTaxAccountId` | `Company` entity | GST output tax account. |
| `gstPayableAccountId` | `Company` entity | GST payable account (optional). |

**Managed by:** `CompanyAccountingSettingsService`

**Note:** These are stored directly on the `Company` entity, not in the `system_settings` table. This avoids key-confusion risk between global and tenant-scoped settings for financial configuration.

---

## 3. Security Configuration

### 3.1 Encryption Key

| Property | Default | Scope | Description |
| --- | --- | --- | --- |
| `erp.security.encryption.key` | (none) | Config-only | AES encryption key for sensitive data encryption via `CryptoService`. Must be at least 32 characters. Checked by `RequiredConfigHealthIndicator`. |

### 3.2 JWT Secret

| Property | Default | Scope | Description |
| --- | --- | --- | --- |
| `jwt.secret` | (none) | Config-only | HMAC secret for JWT token signing. Must be at least 32 characters. Checked by `RequiredConfigHealthIndicator`. |

### 3.3 Swagger/OpenAPI Exposure

| Property | Default | Scope | Description |
| --- | --- | --- | --- |
| `erp.security.swagger-public` | `false` | Config-only | Whether Swagger/OpenAPI endpoints are accessible without authentication. Ignored (forced `false`) when the `prod` profile is active. |

### 3.4 Security Monitoring

| Property | Default | Scope | Description |
| --- | --- | --- | --- |
| `security.monitoring.max-failed-logins` | `5` | Config-only | Failed login attempts before triggering brute-force lockout (30-minute block). |
| `security.monitoring.failed-login-window-minutes` | `15` | Config-only | Time window for counting failed login attempts. Counters are cleared every 15 minutes. |
| `security.monitoring.max-requests-per-minute` | `100` | Config-only | Requests per minute per identifier before rate-limit violation is logged. |
| `security.monitoring.suspicious-activity-threshold` | `10` | Config-only | Suspicious-activity score threshold before triggering a security alert. |

**Implementation note:** `SecurityMonitoringService` tracking maps are purely in-memory (`ConcurrentHashMap` + `AtomicInteger`). They do not survive application restarts. Lockout blocks for users (30 minutes) and IPs (1 hour) are also in-memory only. The actual account lockout state is managed separately via database-backed mechanisms.

### 3.5 Audit Private Key

| Property | Default | Scope | Description |
| --- | --- | --- | --- |
| `erp.security.audit.private-key` | (none) | Config-only | HMAC signing key for enterprise audit trail actor identity. Used by `EnterpriseAuditTrailService`. |

### 3.6 MFA Issuer

| Property | Default | Scope | Description |
| --- | --- | --- | --- |
| `security.mfa.issuer` | Legacy literal default `BigBright ERP` | Config-only | Issuer name displayed in TOTP authenticator apps during MFA enrollment. Override this value when deployments should present current `orchestrator-erp` branding. |

---

## 4. Licensing

| Property | Default | Scope | Description |
| --- | --- | --- | --- |
| `erp.licensing.enforce` | `false` | Config-only | When `true`, the application fails at startup if no license key is provided. When `false`, a warning is logged but the application starts normally. |
| `erp.licensing.license-key` | (none) | Config-only | CryptoLens license key. Required when `erp.licensing.enforce=true`. |
| `erp.licensing.product-id` | `31720` | Config-only | CryptoLens product ID. |
| `erp.licensing.algorithm` | `SKM15` | Config-only | CryptoLens signing algorithm. |
| `erp.licensing.access-token` | (none) | Config-only | Optional CryptoLens access token for remote activation API calls. |

**Implementation:** `LicensingGuard` is an `ApplicationRunner` that runs once at startup (excluded in the `test` profile). When `enforce=true`, it throws `IllegalStateException` if the license key is missing or the product ID is invalid, preventing the application from starting.

**Health check:** `RequiredConfigHealthIndicator` (when `erp.environment.validation.health-indicator.enabled=true`) reports license status: down if `enforce=true` and key is missing, up otherwise.

**Environment variable overrides:** `ERP_LICENSE_ENFORCE`, `ERP_LICENSE_KEY`, `ERP_LICENSE_PRODUCT_ID`, `ERP_LICENSE_ACCESS_TOKEN`.

---

## 5. Orchestrator Feature Flags

| Property | Default | Scope | Description |
| --- | --- | --- | --- |
| `orchestrator.payroll.enabled` | `false` | Config-only | Whether the orchestrator's payroll coordination flow is active. When `false`, payroll-related orchestrator commands and event bridges are skipped. |
| `orchestrator.factory-dispatch.enabled` | `false` | Config-only | Whether the orchestrator's factory-to-dispatch coordination flow is active. When `false`, factory-dispatch orchestrator commands and event bridges are skipped. |

**Consumer:** `OrchestratorFeatureFlags` bean, injected into orchestrator services.

**Default caveat:** Both flags default to `false`, meaning the orchestrator's payroll and factory-dispatch coordination paths are inactive unless explicitly enabled. This is a deliberate safety choice: these flows involve cross-module side effects (accounting postings, inventory adjustments) and should only be activated when the corresponding modules and configuration are ready.

### 5.1 Outbox Tuning

| Property | Default | Scope | Description |
| --- | --- | --- | --- |
| `orchestrator.outbox.cron` | `0/30 * * * * *` (every 30 seconds) | Config-only | Cron expression for the outbox polling job. |
| `orchestrator.outbox.publish-lease-seconds` | `120` | Config-only | Duration (seconds) a claimed outbox event is held before another poller can reclaim it. |
| `orchestrator.outbox.ambiguous-recheck-seconds` | `300` | Config-only | Interval (seconds) before re-checking events in the `PUBLISHING` state that may be stuck. |
| `orchestrator.outbox.lock-at-most-for` | `PT5M` (5 minutes) | Config-only | ShedLock `lockAtMostFor` duration for the outbox polling job. |

**Detailed documentation:** See [orchestrator.md](../modules/orchestrator.md) for the full outbox lifecycle and retry/dead-letter behavior.

---

## 6. Inventory-Accounting Event Bridge

| Property | Default | Scope | Description |
| --- | --- | --- | --- |
| `erp.inventory.accounting.events.enabled` | `true` (via `matchIfMissing`) | Config-only | Whether the `InventoryAccountingEventListener` is active. When active, inventory movement and valuation-change events are automatically posted to the GL as journal entries. |

**Implementation:** Controlled via `@ConditionalOnProperty(prefix = "erp.inventory.accounting", name = "events.enabled", havingValue = "true", matchIfMissing = true)`. This means the listener is active by default but can be disabled by setting `erp.inventory.accounting.events.enabled=false`.

**Consumer:** `InventoryAccountingEventListener` listens for `InventoryMovementEvent` and `InventoryValuationChangedEvent` and creates corresponding journal entries.

**Idempotency:** The listener uses deterministic reference-number construction (either from the source event reference or SHA-256 hash of event data) and checks for existing journal entries before posting.

**Operational caveat:** When this listener is active, it auto-posts GL entries for inventory movements that are not part of a canonical workflow (GRN, sales order, packaging slip). Canonical-workflow movements are skipped by the listener because the owning flow handles GL posting directly. Disabling this toggle means non-canonical inventory movements will not produce GL entries unless handled elsewhere.

---

## 7. Inventory Feature Toggles

| Property | Default | Scope | Description |
| --- | --- | --- | --- |
| `erp.inventory.opening-stock.enabled` | `false` | Config-only | Whether opening-stock import is enabled. When `false`, the opening-stock import endpoint returns `OPENING_STOCK_IMPORT_DISABLED`. |
| `erp.raw-material.intake.enabled` | `false` | Config-only | Whether raw-material intake operations are enabled. When `false`, raw-material intake endpoints return `RAW_MATERIAL_INTAKE_DISABLED`. |

**Operational note:** Both toggles default to `false`. They are operational gates that prevent premature use of these inventory surfaces before the corresponding setup (accounts, warehouses, batch conventions) is complete.

---

## 8. Accounting Event Trail Strictness

| Property | Default | Scope | Description |
| --- | --- | --- | --- |
| `erp.accounting.event-trail.strict` | `true` | Config-only | Whether accounting event-trail validation is strict. When `true`, event-trail integrity checks fail loudly on inconsistencies. When `false`, inconsistencies may be logged but do not prevent operations. |

**Consumer:** `AccountingCoreEngineCore`

**Operational caveat:** This should only be set to `false` for migration or data-correction scenarios. Running with `strict=false` in production weakens the accounting event-trail integrity guarantee.

---

## 9. Enterprise Audit Trail Retry

| Property | Default | Scope | Description |
| --- | --- | --- | --- |
| `erp.audit.business.retry.max-attempts` | `4` | Config-only | Maximum retry attempts for enterprise audit trail events before dropping. |
| `erp.audit.business.retry.max-queue-size` | `500` | Config-only | Maximum in-memory overflow queue size. Events exceeding this are dropped. |
| `erp.audit.business.retry.batch-size` | `50` | Config-only | Batch size for persistent retry processing. |
| `erp.audit.business.retry.backoff-ms` | `30000` | Config-only | Exponential backoff base in milliseconds for retries. |

**Consumer:** `EnterpriseAuditTrailService`

**Detailed documentation:** See [core-audit-runtime-settings.md](../modules/core-audit-runtime-settings.md) §1.2 for the full retry model.

---

## 10. Environment Validation and Seed Toggles

### 10.1 Environment Validation

| Property | Default | Scope | Description |
| --- | --- | --- | --- |
| `erp.environment.validation.enabled` | `false` | Config-only | Enables stricter environment validation, including CORS origin restrictions for non-localhost private-network IPs. |
| `erp.environment.validation.health-indicator.enabled` | (none) | Config-only | When `true`, activates `ConfigurationHealthIndicator` and `RequiredConfigHealthIndicator` as Actuator health indicators. These check JWT secret, encryption key, license, and SMTP configuration. |
| `erp.environment.validation.health-cache-seconds` | `60` | Config-only | Cache TTL for the configuration health report. Minimum 5 seconds. |

**Consumer:** `ConfigurationHealthService`, `ConfigurationHealthIndicator`, `RequiredConfigHealthIndicator`, `SystemSettingsService` (CORS validation).

### 10.2 Seed and Bootstrap

| Property | Default | Scope | Description |
| --- | --- | --- | --- |
| `erp.seed.super-admin.email` | (none) | Config-only | When set, creates/updates the super-admin user at startup. Password is required via `erp.seed.super-admin.password`. |
| `erp.seed.super-admin.password` | (none) | Config-only | Password for the super-admin seed user. |
| `erp.seed.super-admin.company-code` | `PLATFORM` | Config-only | Company code for the super-admin's platform company. |
| `erp.seed.dev-admin.email` | (built-in default) | Config-only | When set, creates/updates a dev-admin user at startup. |
| `erp.seed.dev-admin.password` | (none) | Config-only | Password for the dev-admin seed user. |
| `erp.seed.mock-admin.email` | (none) | Config-only | When set, creates/updates a mock-admin user at startup (for testing environments). |
| `erp.seed.mock-admin.password` | (none) | Config-only | Password for the mock-admin seed user. |
| `erp.seed.benchmark-admin.email` | (none) | Config-only | When set, creates/updates a benchmark-admin user at startup. |
| `erp.seed.benchmark-admin.password` | (none) | Config-only | Password for the benchmark-admin seed user. |
| `erp.validation-seed.enabled` | `false` | Config-only | When `true`, seeds validation-specific test data at startup. |
| `erp.validation-seed.password` | (none) | Config-only | Default password for validation-seed data. |

**Operational note:** All seed initializers are idempotent — they create users only if they don't already exist and update passwords only if the seed password property is set.

### 10.3 Benchmark Overrides

| Property | Default | Scope | Description |
| --- | --- | --- | --- |
| `erp.benchmark.skip-date-validation` | `false` | Config-only | When `true`, skips date validation in accounting posting (allows back-dated entries). Only for benchmark/testing use. |
| `erp.benchmark.override-date` | (none) | Config-only | When set, overrides the company clock to report a fixed date. Used by `CompanyClock` for benchmark isolation. |

**Operational caveat:** Both benchmark toggles should never be enabled in production. They weaken date integrity and financial controls.

---

## 11. Cross-Cutting Scope and Default-Value Caveats

### 11.1 Global vs Tenant Scope

The platform has three distinct configuration scopes:

| Scope | Storage | Management | Examples |
| --- | --- | --- | --- |
| Global (runtime-tunable) | `system_settings` table | Admin API (`/api/v1/admin/settings`) | auto-approval, period-lock, export-approval, CORS origins, mail settings |
| Global (config-only) | `application.yml` / env vars | Application restart required | licensing, security monitoring, encryption key, JWT secret |
| Per-tenant | `system_settings` table (key-qualified) or `Company` entity | Super-admin API or company update API | runtime enforcement, module gating, accounting settings |

**Risk:** Global and tenant-scoped settings in `system_settings` share the same table with only key-naming conventions to distinguish them. There is no schema-level isolation. See [core-audit-runtime-settings.md](../modules/core-audit-runtime-settings.md) §3.2 for the full risk analysis.

### 11.2 In-Memory State Does Not Survive Restart

Several services maintain in-memory state that is lost on application restart:

| Service | In-Memory State | Impact of Reset |
| --- | --- | --- |
| `TenantRuntimeEnforcementService` | Concurrent-request counters, rate-limit windows | Quota counters reset; burst above intended limits possible |
| `TenantRuntimeAccessService` | Same as above | Same as above |
| `SecurityMonitoringService` | Failed-login counters, rate-limit counters, blocked IPs/users, suspicious-activity scores | All security tracking resets; temporary loss of brute-force protection |
| `EnterpriseAuditTrailService` | In-memory retry overflow queue | Queued retry events are lost; persisted retries survive |

### 11.3 Config-Only Toggles Require Restart

The following toggles are read once at application startup and cannot be changed at runtime:

- All `erp.security.*` properties
- All `erp.licensing.*` properties
- All `security.monitoring.*` properties
- `erp.inventory.accounting.events.enabled`
- `erp.inventory.opening-stock.enabled`
- `erp.raw-material.intake.enabled`
- `erp.accounting.event-trail.strict`
- `erp.environment.validation.*`
- `erp.seed.*` and `erp.validation-seed.*`
- `erp.benchmark.*`
- `orchestrator.*.enabled`
- `orchestrator.outbox.*`
- `erp.tenant.runtime.default-*` (defaults, not per-tenant overrides)
- `erp.tenant.runtime.policy-cache-seconds`

### 11.4 Defaults Are Intentionally Conservative

Several toggles have `false` defaults that guard against premature activation of cross-module flows:

| Toggle | Default | Why `false` |
| --- | --- | --- |
| `erp.licensing.enforce` | `false` | Prevents blocking local development and CI. Must be explicitly enabled in secured environments. |
| `orchestrator.payroll.enabled` | `false` | Payroll coordination involves accounting postings; should only be activated when payroll accounts and period controls are configured. |
| `orchestrator.factory-dispatch.enabled` | `false` | Factory-dispatch coordination involves inventory adjustments; should only be activated when inventory and dispatch surfaces are ready. |
| `erp.inventory.opening-stock.enabled` | `false` | Opening-stock import affects batch and valuation state; should only be activated when chart of accounts and batch conventions are configured. |
| `erp.raw-material.intake.enabled` | `false` | Raw-material intake affects inventory and cost tracking; should only be activated when inventory accounts and warehouse setup are complete. |
| `erp.environment.validation.health-indicator.enabled` | (not set) | Config health checks are DB-intensive and should be enabled explicitly where needed. |
| `HR_PAYROLL` module | **Not in default set** | HR/Payroll is an optional module that requires payroll accounts to be configured before use. |

---

## Cross-References

| Document | Relationship |
| --- | --- |
| [core-audit-runtime-settings.md](../modules/core-audit-runtime-settings.md) | Runtime-gating architecture, settings risk analysis, dual-enforcement caveats |
| [core-security-error.md](../modules/core-security-error.md) | Security filter chain, error contract, fail-open vs fail-closed boundaries |
| [company.md](../modules/company.md) | Company lifecycle, module gating, super-admin control plane |
| [auth.md](../modules/auth.md) | Login/refresh/logout, MFA, password reset, token blacklisting |
| [admin-portal-rbac.md](../modules/admin-portal-rbac.md) | Admin settings API, export approval workflow, role-action boundaries |
| [orchestrator.md](../modules/orchestrator.md) | Outbox lifecycle, command dispatch, event bridges, retry/dead-letter |
| [core-idempotency.md](../modules/core-idempotency.md) | Shared and module-local idempotency infrastructure |
| [db-migration.md](db-migration.md) | Migration posture, profile activation, forward-only constraints |
