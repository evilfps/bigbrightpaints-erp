# BigBright Paints ERP - STRIDE Security Threat Model

Generated: 2026-04-09
Version: 1.0.0
Compliance: SOC2
Repository: bigbrightpaints-erp

---

## 1. System Overview

BigBright Paints ERP is a multi-tenant modular monolith built with Spring Boot (Java 21) and PostgreSQL. The system manages financial accounting, sales, inventory, purchasing, manufacturing, HR/payroll, and dealer self-service for a paints manufacturing company. It is deployed as a containerized application with Docker Compose, using RabbitMQ for async messaging, Sentry for error monitoring, and Datadog for APM.

### 1.1 Technology Stack

- **Runtime**: Spring Boot 3.x on Eclipse Temurin JRE 21
- **Database**: PostgreSQL 16 with Flyway migrations (v2 track)
- **Authentication**: JWT (HS256) with token blacklisting and refresh tokens
- **Authorization**: Spring Security with role hierarchy, @PreAuthorize annotations, module gating
- **Encryption**: AES-256-GCM (PBKDF2 key derivation) for data at rest
- **Build**: Maven with Spotless (Google Java Format)
- **CI/CD**: GitHub Actions with pre-commit hooks and Gitleaks
- **Monitoring**: Sentry, Datadog APM, Spring Actuator

### 1.2 Module Inventory

| Module | Description | Risk Tier |
|---|---|---|
| auth | Login, MFA, password reset, token management | HIGH |
| rbac | Role/permission CRUD, role-action matrices | HIGH |
| company | Tenant onboarding, lifecycle, module gating | HIGH |
| accounting | Journals, periods, reconciliation, settlements, imports | HIGH |
| sales | Orders, dispatch, dealers, credit limits | HIGH |
| inventory | Stock truth, batches, movements, adjustments | MEDIUM |
| purchasing | POs, GRN, supplier management, returns | MEDIUM |
| factory | Production logs, packing, batch registration | MEDIUM |
| hr | Employees, attendance, payroll runs and posting | HIGH |
| admin | User management, settings, support tickets | HIGH |
| portal | Dealer self-service, insights, finance | MEDIUM |
| reports | Financial and operational reporting | MEDIUM |
| orchestrator | Outbox, command dispatch, scheduling | LOW |

### 1.3 Architecture Diagram

```
[Web/Admin Clients]  [Dealer Portal]  [Mobile/3rd-party]
        |                  |                  |
        v                  v                  v
  [Spring Security Filter Chain]
    |- JwtAuthenticationFilter
    |- CompanyContextFilter
    |- MustChangePasswordCorridorFilter
    |- ModuleGatingInterceptor
        |
        v
  [REST Controllers (@PreAuthorize)]
        |
        v
  [Module Services / Engines]
    |-> AccountingFacade (cross-module)
    |-> Domain Events -> Event Listeners
        |
        v
  [JPA Repositories -> PostgreSQL]
        |
  [Orchestrator Outbox -> RabbitMQ]
```

---

## 2. Trust Boundaries and Security Zones

### 2.1 Public Zone (Untrusted)

All traffic enters through the API gateway (Spring Boot on port 8081). The following endpoints accept unauthenticated requests:

- `POST /api/v1/auth/login` - Login endpoint
- `POST /api/v1/auth/refresh-token` - Token refresh
- `POST /api/v1/auth/password/forgot` - Password reset request
- `POST /api/v1/auth/password/reset` - Password reset confirmation
- `GET /actuator/health/**` - Health check (read-only)
- `OPTIONS /**` - CORS preflight

Transition validation: JWT signature verification by JwtAuthenticationFilter. Failed attempts are tracked by SecurityMonitoringService with brute-force detection (configurable threshold, default 5 attempts per 15-minute window).

### 2.2 Authenticated Zone (Partially Trusted)

All endpoints behind Spring Security's `anyRequest().authenticated()` rule. Users are identified by JWT claims including `sub` (userId), `companyCode`, and role/permission authorities. The CompanyContextFilter enforces:

- JWT company claim must match request header/claim
- Tenant lifecycle states are enforced (SUSPENDED = read-only, DEACTIVATED = deny-all)
- Super-admin is restricted to control-plane operations only

Authorization is enforced at two levels:
1. **Class/method level**: @PreAuthorize with role checks (ROLE_ADMIN, ROLE_ACCOUNTING, ROLE_SALES, ROLE_FACTORY, ROLE_DEALER, ROLE_SUPER_ADMIN)
2. **Module gating**: ModuleGatingInterceptor denies access to optional modules (MANUFACTURING, HR_PAYROLL, PURCHASING, PORTAL, REPORTS_ADVANCED) when not enabled for the tenant

### 2.3 Internal Zone (Trusted)

- Database access (PostgreSQL on port 5432, bound to localhost in Docker)
- RabbitMQ messaging (port 5672, not exposed externally)
- Service-to-service communication within Docker network
- Flyway migration execution
- Orchestrator outbox/command processing

### 2.4 Control Plane Zone (Super-Admin Only)

Endpoints under `/api/v1/superadmin/**` require ROLE_SUPER_ADMIN. This zone manages:
- Tenant onboarding and lifecycle transitions
- Module gating for tenants
- Admin password resets
- Support context updates
- Force-logout operations

---

## 3. Attack Surface Inventory

### 3.1 External HTTP Endpoints

The system exposes approximately 51 REST controllers with hundreds of individual endpoints. All non-auth endpoints require JWT authentication. Key attack surfaces:

| Surface | Entry Point | Risk |
|---|---|---|
| Authentication | AuthController, MfaController | HIGH |
| Tenant Management | SuperAdminController, SuperAdminTenantOnboardingController | HIGH |
| Financial Data | AccountingController (70+ endpoints) | HIGH |
| Payroll | HrPayrollController | HIGH |
| Sales Orders | SalesController, DealerPortalController | HIGH |
| File Imports | OpeningStockImportController, TallyImportController, OpeningBalanceImportController, CatalogController | MEDIUM |
| Support Tickets | PortalSupportTicketController, DealerPortalSupportTicketController | LOW |

### 3.2 File Upload Surfaces

The system accepts file uploads for:
- **CSV imports**: Opening balances, opening stock, catalog data
- **XML imports**: Tally XML (legacy migration)
- All upload endpoints use `MultipartFile` with content type validation

### 3.3 External Integrations

- **GitHub API**: Support ticket sync via GitHubIssueClient (token-based auth)
- **Sentry**: Error/performance monitoring (DSN-based)
- **Datadog**: Infrastructure/APM monitoring (API key)
- **Email (SMTP)**: Password reset, notifications (via Brevo/SMTP relay)

---

## 4. Critical Assets Classification

### 4.1 PII (Personally Identifiable Information)

| Asset | Storage | Protection |
|---|---|---|
| User emails | `app_users.email` | BCrypt password hash, JWT claims |
| Employee data | `employees` table | Tenant-scoped, ROLE_ADMIN/ROLE_ACCOUNTING |
| Dealer contact info | `dealers` table | Tenant-scoped, ROLE_DEALER (self-only) |
| Payroll data | `payroll_runs`, `payroll_run_lines` | Tenant-scoped, ROLE_ADMIN/ROLE_ACCOUNTING |
| Password reset tokens | `password_reset_tokens` | SHA-256 digest stored, not plaintext |

### 4.2 Credentials and Secrets

| Asset | Storage | Protection |
|---|---|---|
| User passwords | `app_users.password_hash` | BCrypt hash |
| JWT signing key | Environment variable `JWT_SECRET` | HMAC-SHA256, min 256 bits enforced |
| Encryption key | Environment variable `ERP_SECURITY_ENCRYPTION_KEY` | PBKDF2 + AES-256-GCM |
| Audit signing key | Environment variable `ERP_SECURITY_AUDIT_PRIVATE_KEY` | Signs audit payloads |
| GitHub API token | Environment variable `ERP_GITHUB_TOKEN` | For support ticket sync |
| Datadog API key | Environment variable `DD_API_KEY` | APM/monitoring |
| Sentry DSN | Environment variable `SENTRY_DSN` | Error reporting |
| SMTP credentials | `SPRING_MAIL_USERNAME/PASSWORD` | Email delivery |
| License key | Environment variable `ERP_LICENSE_KEY` | Software licensing |

### 4.3 Business-Critical Data

| Asset | Protection |
|---|---|
| Chart of accounts | Tenant-scoped, ROLE_ADMIN/ROLE_ACCOUNTING |
| Journal entries | Immutable after posting, reversal-only edits |
| Accounting periods | Maker-checker workflow for close, super-admin reopen |
| Sales orders | Idempotency keys, status transition enforcement |
| Inventory batches | Atomic deductions, pessimistic locking |
| Payroll runs | Period+type keyed idempotency, approval workflow |

---

## 5. STRIDE Threat Analysis

### 5.1 Spoofing (S)

#### S-1: JWT Token Theft and Replay
- **Severity**: HIGH
- **Likelihood**: MEDIUM
- **Description**: An attacker who obtains a valid JWT (via XSS, network interception, or token leakage) can impersonate the user until token expiration (default 15 minutes for access tokens, 30 days for refresh tokens).
- **Vulnerable Components**: JwtAuthenticationFilter, JwtTokenService
- **Existing Mitigations**: Token blacklisting on logout, user-level token revocation, blacklisted token checks on every request, token ID (jti) tracking, `iatMs` claim for revocation timestamp comparison
- **Gaps**: No token binding to client fingerprint/IP. Refresh tokens stored as SHA-256 digests in database but sent to client. No short-lived sliding window for refresh tokens.
- **Code Reference**: `core/security/JwtAuthenticationFilter.java` (token validation), `core/security/TokenBlacklistService.java` (revocation)

#### S-2: Super-Admin Account Compromise
- **Severity**: CRITICAL
- **Likelihood**: LOW
- **Description**: Super-admin has control-plane access to all tenants. Compromise of this account grants full system access including tenant lifecycle manipulation, admin password resets, and cross-tenant data access.
- **Vulnerable Components**: SuperAdminController, SuperAdminTenantOnboardingController
- **Existing Mitigations**: Super-admin restricted to control-plane only (cannot access tenant business workflows), CompanyContextFilter enforces SUPER_ADMIN_TENANT_BUSINESS_PREFIXES deny list
- **Gaps**: No MFA enforcement documented for super-admin. No hardware key requirement. Super-admin can force-reset tenant admin passwords and force-logout tenants.
- **Code Reference**: `core/security/CompanyContextFilter.java` (SUPER_ADMIN_TENANT_BUSINESS_PREFIXES), `modules/company/controller/SuperAdminController.java` (password reset)

#### S-3: Dealer Portal Identity Spoofing
- **Severity**: HIGH
- **Likelihood**: MEDIUM
- **Description**: A dealer with valid credentials could attempt to access another dealer's data if authorization checks are insufficient.
- **Vulnerable Components**: DealerPortalController, DealerPortalSupportTicketController
- **Existing Mitigations**: ROLE_DEALER required, self-scoped reads (peer-dealer lookups fail closed per architecture docs)
- **Gaps**: Verify that all dealer-scoped queries properly filter by the authenticated user's dealer ID and not just by company.

### 5.2 Tampering (T)

#### T-1: SQL Injection via JPA Queries
- **Severity**: HIGH
- **Likelihood**: LOW
- **Description**: While all database access uses JPA repositories with parameterized queries and @Query annotations (JPQL/native), any use of string concatenation in dynamic queries would be vulnerable.
- **Vulnerable Components**: All Repository interfaces
- **Existing Mitigations**: All observed @Query annotations use parameterized `:named` parameters. JPA/Hibernate parameterizes all queries. No raw string concatenation observed in query construction.
- **Gaps**: Some native queries exist (OrchestratorCommandRepository, ProductionPlanRepository, AuditLogRepository, JournalReferenceMappingRepository, DealerLedgerRepository, AuditActionEventRetryRepository, SystemSettingsRepository). Native queries with parameterized bindings are safe but should be audited for any dynamic SQL construction.
- **Code Reference**: Multiple repository files under `modules/*/domain/*Repository.java`

#### T-2: Cross-Tenant Data Tampering
- **Severity**: CRITICAL
- **Likelihood**: LOW
- **Description**: If tenant-scoping is bypassed, an attacker in one tenant could modify data in another tenant.
- **Vulnerable Components**: CompanyContextFilter, all company-scoped repositories
- **Existing Mitigations**: CompanyContextFilter enforces JWT company claim consistency, all repositories filter by `:company` parameter, lifecycle-aware write admission
- **Gaps**: Verify that every write-path repository method includes company parameter. Some lockBy queries (SalesOrderRepository, PackagingSlipRepository) include company scoping but the locking timeout (5s) could be exploited for denial-of-service.
- **Code Reference**: `core/security/CompanyContextFilter.java`, repository files

#### T-3: Accounting Period Tampering
- **Severity**: HIGH
- **Likelihood**: LOW
- **Description**: Unauthorized modification of closed accounting periods could compromise financial data integrity.
- **Vulnerable Components**: AccountingPeriodServiceCore, AccountingController
- **Existing Mitigations**: Maker-checker workflow for period close, super-admin-only reopen, closing journal reversal on reopen, period lock enforcement
- **Gaps**: Period reopen is super-admin-only without maker-checker, which is documented as by-design but creates a single point of failure for financial controls.
- **Code Reference**: `modules/accounting/internal/AccountingPeriodServiceCore.java`

#### T-4: Mass Assignment via DTO Binding
- **Severity**: MEDIUM
- **Likelihood**: MEDIUM
- **Description**: Spring's automatic request body binding could allow attackers to set fields not intended for modification (e.g., role, companyId, status).
- **Vulnerable Components**: All @RequestBody parameters in controllers
- **Existing Mitigations**: DTOs are defined as Java records (immutable), @Valid annotations with validation constraints
- **Gaps**: Verify that DTO records don't include fields that map to sensitive entity properties (roles, company assignment). Some controllers accept broad DTOs that get mapped to entity fields.

### 5.3 Repudiation (R)

#### R-1: Insufficient Audit Logging for Business Operations
- **Severity**: MEDIUM
- **Likelihood**: HIGH
- **Description**: While the system has an audit infrastructure (AuditService, EnterpriseAuditTrailService), not all business-critical operations may generate audit events.
- **Vulnerable Components**: AuditService, EnterpriseAuditTrailService, AuditLogRepository
- **Existing Mitigations**: AuditService for platform events, EnterpriseAuditTrailService for enterprise actions, SecurityMonitoringService for security events, audit event store in accounting
- **Gaps**: Need to verify comprehensive audit coverage for all financial mutations (journal creation, period operations, payroll postings, dispatch confirmations). SOC2 requires complete audit trail.
- **Code Reference**: `core/audit/AuditService.java`, `core/audittrail/EnterpriseAuditTrailController.java`

#### R-2: Missing Request Correlation for Forensic Analysis
- **Severity**: LOW
- **Likelihood**: MEDIUM
- **Description**: Without consistent request correlation IDs, it may be difficult to trace the full chain of operations for forensic analysis.
- **Existing Mitigations**: Orchestrator tables include trace_id, idempotency_key, request_id correlation fields
- **Gaps**: Verify that correlation IDs propagate through all business flows, not just orchestrator operations.

### 5.4 Information Disclosure (I)

#### I-1: Verbose Error Responses
- **Severity**: MEDIUM
- **Likelihood**: MEDIUM
- **Description**: The GlobalExceptionHandler and CoreFallbackExceptionHandler may expose internal implementation details in error responses.
- **Vulnerable Components**: GlobalExceptionHandler, CoreFallbackExceptionHandler
- **Existing Mitigations**: ApplicationException + ErrorCode business error contract provides structured error responses
- **Gaps**: Verify that fallback exception handlers don't leak stack traces, SQL errors, or internal class names. The management endpoint health.show-details=always may expose database and service details to authenticated users.
- **Code Reference**: `core/exception/GlobalExceptionHandler.java`, `core/exception/CoreFallbackExceptionHandler.java`

#### I-2: Sensitive Data in Logs
- **Severity**: MEDIUM
- **Likelihood**: MEDIUM
- **Description**: Application logs may contain sensitive information such as user IDs, company codes, IP addresses, and potentially financial data.
- **Vulnerable Components**: SecurityMonitoringService, all service classes with logger usage
- **Existing Mitigations**: Security logs record events with user ID and IP (appropriate for security monitoring)
- **Gaps**: Verify no passwords, tokens, or financial values are logged. Hibernate format_sql=true in config may log query parameters including sensitive data. Datadog log injection is enabled (DD_LOGS_INJECTION=true).
- **Code Reference**: `application.yml` (hibernate.format_sql), `core/security/SecurityMonitoringService.java`

#### I-3: Actuator Health Endpoint Data Exposure
- **Severity**: LOW
- **Likelihood**: HIGH
- **Description**: The health endpoint (`/actuator/health`) is unauthenticated and shows details including database connectivity, disk space, and service status.
- **Vulnerable Components**: Spring Actuator configuration
- **Existing Mitigations**: Only health endpoint is exposed (not metrics or env), separate management port (9090) available for production
- **Gaps**: `management.endpoint.health.show-details=always` exposes health details to unauthenticated users on the main port. In production, the management port should be used and health endpoint should not be publicly accessible.
- **Code Reference**: `application.yml` (management configuration)

#### I-4: Insecure Direct Object Reference (IDOR)
- **Severity**: HIGH
- **Likelihood**: MEDIUM
- **Description**: Many endpoints accept entity IDs as path parameters. If authorization checks don't verify that the authenticated user's company owns the referenced entity, cross-tenant data access is possible.
- **Vulnerable Components**: All controllers accepting @PathVariable entity IDs
- **Existing Mitigations**: Repository queries include `:company` parameter filtering, CompanyContextFilter enforces tenant scoping
- **Gaps**: Verify every single repository query that fetches by ID also includes company parameter. Some queries may fetch by ID alone without company scoping (e.g., `FinishedGoodBatchRepository.findById` without company filter).
- **Code Reference**: Various repository files, especially `FinishedGoodBatchRepository.java` line 88 (`select b from FinishedGoodBatch b where b.id = :id` without company filter)

### 5.5 Denial of Service (D)

#### D-1: Brute Force Login Attacks
- **Severity**: MEDIUM
- **Likelihood**: HIGH
- **Description**: Attackers can attempt unlimited login requests to guess passwords.
- **Vulnerable Components**: AuthController, SecurityMonitoringService
- **Existing Mitigations**: SecurityMonitoringService tracks failed logins per user and per IP, configurable threshold (default 5 per 15 minutes), brute force detection, IP blocking with in-memory ConcurrentHashMap
- **Gaps**: Rate limiting uses in-memory maps (ConcurrentHashMap) that are lost on server restart and don't work in multi-instance deployments. No persistent rate limit store. No CAPTCHA for repeated failures. No exponential backoff.
- **Code Reference**: `core/security/SecurityMonitoringService.java`

#### D-2: File Upload Resource Exhaustion
- **Severity**: MEDIUM
- **Likelihood**: MEDIUM
- **Description**: Import endpoints accept file uploads without explicit file size limits at the application level.
- **Vulnerable Components**: OpeningStockImportController, TallyImportController, OpeningBalanceImportController, CatalogController
- **Existing Mitigations**: File content type validation in ProductionCatalogService, file hash verification
- **Gaps**: No explicit maximum file size enforcement visible at controller level. Relies on Spring's default multipart file size limits. Large file imports could consume memory and CPU.
- **Code Reference**: Import controller/service files

#### D-3: Pessimistic Locking Timeout Exploitation
- **Severity**: MEDIUM
- **Likelihood**: LOW
- **Description**: Several critical operations use pessimistic locking with timeouts (5 seconds for SalesOrderRepository, PackagingSlipRepository). An attacker could trigger many concurrent requests to exhaust lock pools.
- **Vulnerable Components**: SalesOrderRepository, PackagingSlipRepository
- **Existing Mitigations**: Lock timeout configured (5 seconds for critical entities, 3 seconds for others)
- **Gaps**: No visible request-level rate limiting per user for these expensive operations.
- **Code Reference**: `modules/sales/domain/SalesOrderRepository.java` (lock timeout hints)

### 5.6 Elevation of Privilege (E)

#### E-1: Role Hierarchy Exploitation
- **Severity**: HIGH
- **Likelihood**: LOW
- **Description**: The role hierarchy grants ROLE_SUPER_ADMIN all ROLE_ADMIN permissions. If a user can obtain ROLE_SUPER_ADMIN, they gain unrestricted control-plane access.
- **Vulnerable Components**: SecurityConfig.roleHierarchy(), RoleController
- **Existing Mitigations**: Role management requires ROLE_ADMIN or ROLE_SUPER_ADMIN, tenant-admin-only restrictions on some operations
- **Gaps**: Verify that role assignment endpoints cannot grant ROLE_SUPER_ADMIN through normal tenant-admin workflows. RoleController allows ROLE_ADMIN to manage roles.
- **Code Reference**: `core/security/SecurityConfig.java` (role hierarchy), `modules/rbac/controller/RoleController.java`

#### E-2: Module Gating Bypass
- **Severity**: MEDIUM
- **Likelihood**: LOW
- **Description**: If the ModuleGatingInterceptor can be bypassed (e.g., by using alternative URL paths), users could access disabled modules.
- **Vulnerable Components**: ModuleGatingInterceptor, ModuleGatingService
- **Existing Mitigations**: Interceptor resolves target module from request path, denied with MODULE_DISABLED error
- **Gaps**: Path-based module resolution could be bypassed through URL encoding, path traversal, or alternative route patterns.
- **Code Reference**: `modules/company/service/ModuleGatingInterceptor.java`

#### E-3: Cross-Host Access (Admin to Dealer Portal)
- **Severity**: MEDIUM
- **Likelihood**: LOW
- **Description**: If authorization boundaries between admin/internal and dealer-portal hosts are not strictly enforced, an admin could access dealer-scoped endpoints or vice versa.
- **Vulnerable Components**: PortalRoleActionMatrix, DealerPortalController, PortalSupportTicketController
- **Existing Mitigations**: PortalRoleActionMatrix defines strict role requirements, dealer reads are self-scoped, admin reads are tenant-scoped
- **Gaps**: Verify that all dealer-portal endpoints enforce ROLE_DEALER exclusively and that admin users cannot access dealer-specific data through other endpoints.
- **Code Reference**: `core/security/PortalRoleActionMatrix.java`

---

## 6. Vulnerability Pattern Library

### 6.1 SQL Injection (Java/Spring Data JPA)

**SAFE pattern** - Parameterized JPQL:
```java
@Query("select u from UserAccount u where u.id = :id and u.company.id = :companyId")
Optional<UserAccount> findByIdAndCompany(@Param("id") Long id, @Param("companyId") Long companyId);
```

**VULNERABLE pattern** - String concatenation (not observed but risk):
```java
// DANGEROUS - Do NOT do this
@Query(value = "SELECT * FROM users WHERE id = " + userId, nativeQuery = true)
```

**Mitigation**: All queries use `@Param`-bound parameters. Native queries observed all use parameterized bindings.

### 6.2 Cross-Site Scripting (XSS)

Since this is a REST API returning JSON, XSS is primarily a concern for:
- Error messages reflected in responses
- Import data (CSV/XML) that might contain script tags
- Dealer/company names that could contain HTML

**Mitigation**: JSON API (no server-rendered HTML), Spring's default JSON serialization escapes special characters.

### 6.3 XML External Entity (XXE) Injection

**SAFE pattern** - Tally XML parser hardening (observed):
```java
DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
factory.setNamespaceAware(false);
factory.setExpandEntityReferences(false);
factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
factory.setFeature("http://xml.org/sax/features/external-general-entities", false);
factory.setFeature("http://xml.org/sax/features/external-parameter-entities", false);
```

**Note**: The XXE hardening is wrapped in try-catch with `// best-effort hardening` comment, meaning it silently degrades if the parser doesn't support these features. This should be fail-closed rather than fail-open for production.

### 6.4 Authentication Bypass

**SAFE pattern** - CompanyContextFilter enforcement:
```java
// JWT company claim must match request context
// Denies tenant-scoped unauthenticated header injection
// Lifecycle-aware write/read admission
```

**Risk pattern**: Any controller method that does not verify company ownership of the requested resource.

### 6.5 IDOR (Insecure Direct Object Reference)

**SAFE pattern** - Company-scoped repository query:
```java
@Query("select o from SalesOrder o where o.company = :company and o.id = :id")
```

**VULNERABLE pattern** - ID-only lookup (observed in some places):
```java
@Query("select b from FinishedGoodBatch b where b.id = :id")
// Missing company filter - could allow cross-tenant access
```

### 6.6 Path Traversal (File Imports)

**Mitigation**: Files are processed via MultipartFile.getInputStream() and parsed in memory, not saved to disk with original filenames. File hash is computed from content, not filename.

### 6.7 CSV Injection

**Risk**: Imported CSV data could contain formula injection payloads (=cmd(), +cmd(), @cmd()) that execute when opened in spreadsheet software.

**Mitigation**: No explicit CSV injection sanitization observed in import services. Data goes directly to database fields.

---

## 7. Security Testing Strategy

### 7.1 Automated Scanning

| Test Type | Tool | Frequency |
|---|---|---|
| Secret detection | Gitleaks | Pre-commit, CI |
| Static analysis | Checkstyle, Spotless | Pre-commit, CI |
| Dependency scanning | Dependabot | Automated |
| Security review | Custom GitHub Action | On PR |

### 7.2 Manual Testing Priorities

1. **Cross-tenant isolation**: Verify all IDOR paths across all controllers
2. **Role boundary enforcement**: Test PortalRoleActionMatrix constraints
3. **File import security**: Test XXE, CSV injection, large file handling
4. **Authentication flows**: Test brute-force detection, token revocation, MFA
5. **Accounting integrity**: Test period close/reopen, journal immutability

### 7.3 SOC2 Compliance Controls Mapping

| SOC2 Criterion | Control | Status |
|---|---|---|
| CC6.1 (Logical Access) | JWT authentication, role hierarchy, @PreAuthorize | Implemented |
| CC6.2 (Authentication) | BCrypt passwords, MFA support, token blacklisting | Implemented |
| CC6.3 (Authorization) | RBAC, PortalRoleActionMatrix, module gating | Implemented |
| CC7.1 (Detection) | SecurityMonitoringService, Sentry, Datadog | Implemented |
| CC7.2 (Monitoring) | Audit trail, enterprise audit trail | Implemented |
| CC8.1 (Change Management) | R2 checkpoint workflow, CI/CD, pre-commit hooks | Implemented |

---

## 8. Assumptions and Accepted Risks

### 8.1 Assumptions

- The application runs behind a reverse proxy (nginx/ALB) that provides TLS termination
- Docker network is trusted for inter-service communication
- PostgreSQL, RabbitMQ are not directly exposed to the internet
- Environment variables are managed securely (not in version control)
- The management port (9090) is not publicly accessible in production

### 8.2 Accepted Risks

- **In-memory rate limiting**: SecurityMonitoringService uses ConcurrentHashMap, which does not persist across restarts and doesn't scale horizontally. Accepted for single-instance deployment.
- **Period reopen without maker-checker**: Super-admin-only reopen is by design, not an oversight.
- **Tally XML import limited error recovery**: Legacy migration path with limited support, canonical replacement is CSV import.
- **XXE hardening best-effort**: Try-catch with silent degradation for XML parser features. Accepted because the Tally import is admin-only and legacy.

---

## 9. Version Changelog

| Version | Date | Author | Changes |
|---|---|---|---|
| 1.0.0 | 2026-04-09 | threat-model-generation skill | Initial threat model generation |
