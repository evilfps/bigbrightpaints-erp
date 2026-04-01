# Core Platform Contracts: Security Filter Chain and Error/Exception Handling

Last reviewed: 2026-03-30

This packet documents the **core security filter chain** and the **exception/error contract** that together govern request admission and failure behavior across the BigBright ERP backend. It covers what the platform enforces before any module code runs, how errors are surfaced to callers, and where the platform fails closed versus fails open.

> **Scope note:** This is the first slice of the core platform contracts packet. It covers security filters and exception/error boundaries only. Audit-surface ownership, runtime-gating details, and settings risk are documented in [core-audit-runtime-settings.md](core-audit-runtime-settings.md). Shared-versus-module-local idempotency behavior is documented in [core-idempotency.md](core-idempotency.md). Together the three slices form one coherent canonical reference for core platform contracts (see the reconciled contract table in [core-idempotency.md §5](core-idempotency.md#5-reconciled-core-platform-contract)).

---

## Ownership Summary

| Area | Package | Role |
| --- | --- | --- |
| Security filter chain | `core/security/` | Request admission, authentication, company-context resolution, corridor enforcement |
| Exception/error contract | `core/exception/` | Business error model, global exception handlers, fallback error mapping |
| Shared API envelope | `shared/dto/ApiResponse` | Uniform success/failure response shape |
| Auth module services | `modules/auth/service/` | Token issuance, MFA, password management, user lookup (consumed by filters) |
| Company module services | `modules/company/service/` | Tenant lifecycle, runtime admission (consumed by filters) |

---

## 1. Security Filter Chain

The security filter chain is the platform's primary request-admission pipeline. It runs before any module controller code and is responsible for JWT authentication, company-context resolution, tenant runtime admission, and must-change-password corridor enforcement.

### 1.1 Filter Ordering

The chain is configured in [`SecurityConfig`](../../erp-domain/src/main/java/com/bigbrightpaints/erp/core/security/SecurityConfig.java) using Spring Security's `SecurityFilterChain` bean. The filter ordering is:

```
Incoming Request
  │
  ├─ Spring Security authorization rules (permitAll / authenticated)
  │
  ├─ JwtAuthenticationFilter          (before UsernamePasswordAuthenticationFilter)
  │     Validates JWT, checks blacklist/revocation, loads UserPrincipal, sets SecurityContext
  │
  ├─ CompanyContextFilter             (after JwtAuthenticationFilter)
  │     Resolves company code, validates company access, enforces lifecycle/runtime admission
  │
  ├─ MustChangePasswordCorridorFilter (after CompanyContextFilter)
  │     Blocks non-corridor requests when user must change password
  │
  └─ Controller / module code
```

**Key invariant:** Filters run in strict order. If `JwtAuthenticationFilter` does not establish authentication, the downstream `CompanyContextFilter` and `MustChangePasswordCorridorFilter` operate on an unauthenticated or anonymous security context, and Spring Security's own authorization rules determine whether the request proceeds.

### 1.2 Public Endpoints (No Authentication Required)

The following endpoints are explicitly permitted without authentication:

| Endpoint | Purpose |
| --- | --- |
| `OPTIONS /**` | CORS preflight |
| `POST /api/v1/auth/login` | Credential verification and token issuance |
| `POST /api/v1/auth/refresh-token` | Refresh-token rotation |
| `POST /api/v1/auth/password/forgot` | Password-reset email dispatch |
| `POST /api/v1/auth/password/reset` | Token-based password reset |
| `GET /api/v1/changelog` | Public changelog read |
| `GET /api/v1/changelog/latest-highlighted` | Public latest changelog |
| `GET /actuator/health`, `/actuator/health/**` | Health probes |
| `/swagger-ui/**`, `/v3/api-docs/**` | OpenAPI/Swagger (non-prod only, config-gated) |

All other endpoints require authentication. Swagger/OpenAPI access is further restricted by the `erp.security.swagger-public` configuration flag and is **always denied when the `prod` Spring profile is active**, regardless of the flag value.

### 1.3 JwtAuthenticationFilter

**Source:** [`core/security/JwtAuthenticationFilter.java`](../../erp-domain/src/main/java/com/bigbrightpaints/erp/core/security/JwtAuthenticationFilter.java)

**Responsibility:** Parse and validate the JWT `Bearer` token from the `Authorization` header, check blacklists and user-level token revocation, load the `UserPrincipal`, resolve effective authorities through the role hierarchy, and set the Spring `SecurityContext`.

**Behavior detail:**

1. Extracts the `Bearer` token from the `Authorization` header.
2. Parses the JWT and extracts claims.
3. Checks token-level blacklist via `TokenBlacklistService.isTokenBlacklisted(jti)`.
4. Checks user-level revocation via `TokenBlacklistService.isUserTokenRevoked(userId, issuedAt)`.
5. Loads the `UserPrincipal` from `UserAccountDetailsService`.
6. Rejects disabled accounts and locked accounts.
7. Resolves effective authorities through `RoleHierarchy` (currently `ROLE_SUPER_ADMIN > ROLE_ADMIN`).
8. Sets the `UsernamePasswordAuthenticationToken` in `SecurityContextHolder`.

**Fail-open behavior:**

| Condition | What happens | Fail mode |
| --- | --- | --- |
| No `Authorization` header or no `Bearer` prefix | Filter passes request through; Spring Security decides based on endpoint rules | **Fail-open** (request continues as unauthenticated) |
| Token is blacklisted | Filter logs warning and continues without setting authentication | **Fail-open** (request continues as unauthenticated) |
| User tokens are revoked (issued before revocation timestamp) | Filter logs warning and continues without setting authentication | **Fail-open** (request continues as unauthenticated) |
| Account is disabled | Filter logs warning and continues without setting authentication | **Fail-open** (request continues as unauthenticated) |
| Account is locked | Filter logs warning and continues without setting authentication | **Fail-open** (request continues as unauthenticated) |
| JWT is expired, malformed, or has invalid signature | Filter logs (debug/warn) and continues without setting authentication | **Fail-open** (request continues as unauthenticated) |
| Any other exception during JWT processing | Filter logs error and continues without setting authentication | **Fail-open** (request continues as unauthenticated) |

> **Why fail-open?** The `JwtAuthenticationFilter` deliberately does not reject requests directly. Instead, it either establishes authentication or leaves the security context empty. Spring Security's `authorizeHttpRequests` rules and the downstream `CompanyContextFilter` then decide whether an unauthenticated request should be rejected. This design keeps the filter focused on credential validation and pushes authorization decisions to the appropriate layer.

> **Effective safety net:** For any non-public endpoint, the `authorizeHttpRequests` rule `.anyRequest().authenticated()` ensures that unauthenticated requests receive a 401/403 response from Spring Security before reaching controller code.

### 1.4 CompanyContextFilter

**Source:** [`core/security/CompanyContextFilter.java`](../../erp-domain/src/main/java/com/bigbrightpaints/erp/core/security/CompanyContextFilter.java)

**Responsibility:** Resolve the tenant company code from the JWT claims or `X-Company-Code` header, validate company access, enforce tenant lifecycle restrictions, run tenant runtime admission, and set `CompanyContextHolder` for downstream module code.

**Behavior detail:**

1. Resolves the company code from JWT `companyCode` claim (preferred) or `X-Company-Code` header.
2. Rejects the legacy `X-Company-Id` header with an explicit error.
3. Validates that header and JWT company codes match (if both present).
4. For super-admin users on platform scope, allows control-plane operations but blocks tenant business workflows.
5. Validates that the authenticated user's company matches the requested company code.
6. Checks tenant lifecycle state and restricts write access for suspended tenants and all access for deactivated tenants.
7. Runs tenant runtime admission via `TenantRuntimeRequestAdmissionService`.
8. Sets `CompanyContextHolder.setCompanyCode()` for downstream use.
9. Clears `CompanyContextHolder` in the `finally` block.

**Excluded paths:** The filter skips `/actuator`, `/swagger`, and `/v3` paths. Public password-reset endpoints are passed through early.

**Fail-closed behavior:**

| Condition | Response | Fail mode |
| --- | --- | --- |
| JWT exists but has no `companyCode` claim | 403 `COMPANY_CONTEXT_MISSING` | **Fail-closed** |
| `X-Company-Code` header does not match JWT claim | 403 `COMPANY_CONTEXT_MISMATCH` | **Fail-closed** |
| Legacy `X-Company-Id` header used | 403 `COMPANY_CONTEXT_LEGACY_HEADER_UNSUPPORTED` | **Fail-closed** |
| Unauthenticated request attempts to set company context via header | 403 `COMPANY_CONTEXT_AUTH_REQUIRED` | **Fail-closed** |
| User's company does not match requested company code | 403 `COMPANY_ACCESS_DENIED` | **Fail-closed** |
| Tenant is `SUSPENDED` and request is mutating | 403 `TENANT_LIFECYCLE_RESTRICTED` | **Fail-closed** |
| Tenant is `DEACTIVATED` | 403 `TENANT_LIFECYCLE_RESTRICTED` | **Fail-closed** |
| Super-admin attempts tenant business workflow | 403 `SUPER_ADMIN_PLATFORM_ONLY` | **Fail-closed** |
| Super-admin attempts tenant audit workflow | 403 `SUPER_ADMIN_TENANT_WORKFLOW_DENIED` | **Fail-closed** |
| Control-plane path with missing/unresolvable company ID | 403 `COMPANY_CONTROL_ACCESS_DENIED` | **Fail-closed** |
| Control-plane path requested without authentication | 403 `COMPANY_CONTROL_ACCESS_DENIED` | **Fail-closed** |
| Tenant runtime admission unavailable or denied | 403/429 `TENANT_RUNTIME_ADMISSION_UNAVAILABLE` | **Fail-closed** |
| Unknown principal type in company access validation | — (returns false, request denied) | **Fail-closed** |

> **Why fail-closed?** The `CompanyContextFilter` is the platform's tenant-isolation boundary. Any ambiguity in company context is treated as a security violation and rejected with an explicit 403 response rather than allowed through.

### 1.5 MustChangePasswordCorridorFilter

**Source:** [`core/security/MustChangePasswordCorridorFilter.java`](../../erp-domain/src/main/java/com/bigbrightpaints/erp/core/security/MustChangePasswordCorridorFilter.java)

**Responsibility:** When the authenticated user has `mustChangePassword = true`, block all requests except a narrow corridor of allowed endpoints needed to change the password or read basic profile information.

**Allowed corridor when must-change-password is active:**

| Method | Path | Access |
| --- | --- | --- |
| GET/HEAD | `/api/v1/auth/me` | Read-only |
| GET/HEAD | `/api/v1/auth/profile` | Read-only |
| POST | `/api/v1/auth/password/change` | Mutation |
| POST | `/api/v1/auth/logout` | Mutation |
| POST | `/api/v1/auth/refresh-token` | Mutation |
| OPTIONS | any | CORS preflight |

All other requests receive a 403 response with `PASSWORD_CHANGE_REQUIRED` reason and `mustChangePassword: true` in the response body.

**Fail-closed behavior:** If the user's `mustChangePassword` flag is true and the request path is not in the allowed corridor, the filter rejects the request with 403. Unknown principal types are treated as not requiring a password change (fail-open on the `mustChangePassword` check itself, but the broader authentication chain already protects unauthenticated requests).

### 1.6 Role Hierarchy

The platform defines a role hierarchy in `SecurityConfig`:

```
ROLE_SUPER_ADMIN > ROLE_ADMIN
```

This means `ROLE_SUPER_ADMIN` implicitly includes all authorities granted to `ROLE_ADMIN`. The hierarchy is applied by `JwtAuthenticationFilter` when resolving effective authorities and is also wired into `DefaultMethodSecurityExpressionHandler` for `@PreAuthorize` checks.

### 1.7 Token Blacklisting and Revocation

**Source:** [`core/security/TokenBlacklistService.java`](../../erp-domain/src/main/java/com/bigbrightpaints/erp/core/security/TokenBlacklistService.java)

| Mechanism | Scope | Persistence |
| --- | --- | --- |
| Per-token blacklist (`BlacklistedToken`) | Individual JWT by `jti` claim | Database (survives restarts) |
| User-level revocation (`UserTokenRevocation`) | All tokens for a user issued before `revokedAt` | Database (survives restarts) |

Both mechanisms are checked in `JwtAuthenticationFilter` during request processing. Expired blacklisted tokens and old revocation records are cleaned up hourly via a `@Scheduled` task.

---

## 2. Exception and Error Contract

The platform uses a layered exception-handling model built on `ApplicationException` + `ErrorCode` as the primary business error contract, two `@ControllerAdvice` handlers for different priority levels, and a uniform `ApiResponse` envelope.

### 2.1 ApplicationException and ErrorCode

**Sources:**
- [`core/exception/ApplicationException.java`](../../erp-domain/src/main/java/com/bigbrightpaints/erp/core/exception/ApplicationException.java)
- [`core/exception/ErrorCode.java`](../../erp-domain/src/main/java/com/bigbrightpaints/erp/core/exception/ErrorCode.java)

`ApplicationException` is the base exception for all business-logic errors. Every instance carries:

| Field | Purpose |
| --- | --- |
| `errorCode` | An `ErrorCode` enum value with a machine-readable code and default message |
| `userMessage` | A human-readable message for the caller |
| `details` | An optional key-value map of structured context (e.g., conflict info, limit values) |

`ErrorCode` is a centralized enum organized by domain:

| Prefix | Domain | Examples |
| --- | --- | --- |
| `AUTH_*` | Authentication and authorization | `AUTH_001` invalid credentials, `AUTH_004` insufficient permissions, `AUTH_007` MFA required |
| `BUS_*` | Business logic | `BUS_001` invalid state, `BUS_003` entity not found, `BUS_010` module disabled |
| `VAL_*` | Validation | `VAL_001` invalid input, `VAL_002` missing required field |
| `SYS_*` | System errors | `SYS_001` internal error, `SYS_006` rate limit exceeded |
| `INT_*` | Integration | `INT_001` connection failed, `INT_002` timeout |
| `FILE_*` | File operations | `FILE_001` not found, `FILE_004` size exceeded |
| `CONC_*` | Concurrency | `CONC_001` conflict, `CONC_002` lock timeout |
| `DATA_*` | Data integrity | `DATA_001` duplicate entity |
| `ERR_999` | Unknown / unexpected | Catch-all for unhandled exceptions |

### 2.2 Specialized Exception Types

| Exception | Extends | Purpose |
| --- | --- | --- |
| `CreditLimitExceededException` | `ApplicationException` | Credit-limit breach (maps to `BUS_006`) |
| `AuthSecurityContractException` | `RuntimeException` | Carries explicit HTTP status, error code, and user message for auth/security contract violations |

`AuthSecurityContractException` is not an `ApplicationException` subclass — it is handled by `CoreFallbackExceptionHandler` directly and provides its own HTTP status, code, and details.

### 2.3 GlobalExceptionHandler (Highest Priority)

**Source:** [`core/exception/GlobalExceptionHandler.java`](../../erp-domain/src/main/java/com/bigbrightpaints/erp/core/exception/GlobalExceptionHandler.java)

**Order:** `@Order(Ordered.HIGHEST_PRECEDENCE)` — runs before the fallback handler.

| Exception Type | HTTP Status | Behavior |
| --- | --- | --- |
| `ApplicationException` | Determined by `ErrorCode` prefix mapping | Logs error with trace ID, builds structured response, routes settlement failures to audit |
| `MethodArgumentNotValidException` | 400 | Extracts field errors, returns first occurrence per field |
| `HttpMessageNotReadableException` | 400 | Malformed request body; includes detail in non-prod mode only |
| `ConstraintViolationException` | 400 | Extracts constraint violations, returns field-level errors |
| `IllegalArgumentException` | 400 | Sanitizes enum-related messages for safe display |

**ErrorCode-to-HTTP-status mapping** (for `ApplicationException`):

| ErrorCode prefix | HTTP Status |
| --- | --- |
| `AUTH_*` | 401 Unauthorized |
| `VAL_*` | 400 Bad Request |
| `BUS_*`, `CONC_*`, `DATA_*` | 409 Conflict |
| `SYS_*` | 503 Service Unavailable |
| `INT_*` | 502 Bad Gateway |
| `FILE_*` | 422 Unprocessable Entity |
| Special cases: `BUS_003` (not found), `FILE_001` | 404 Not Found |
| Special case: `AUTH_007` (MFA required) | 428 Precondition Required |
| Special case: `BUS_010` (module disabled) | 403 Forbidden |
| Special case: `SYS_006` (rate limit) | 429 Too Many Requests |

**Production mode behavior:** When the `prod` or `production` Spring profile is active, the handler sanitizes exception details before including them in responses. Only catalog-item concurrency conflict details are allowed through in production, and only for allowlisted keys (`generated`, `conflicts`, `wouldCreate`, `created`, `operation`).

**Audit routing:** `GlobalExceptionHandler` delegates to `AuditExceptionRoutingService` to route settlement failures to the platform audit system. Non-settlement `ApplicationException` instances are not currently routed to audit by the global handler.

### 2.4 CoreFallbackExceptionHandler (Lowest Priority)

**Source:** [`core/exception/CoreFallbackExceptionHandler.java`](../../erp-domain/src/main/java/com/bigbrightpaints/erp/core/exception/CoreFallbackExceptionHandler.java)

**Order:** `@Order(Ordered.LOWEST_PRECEDENCE)` — catches anything not handled by `GlobalExceptionHandler`.

| Exception Type | HTTP Status | Behavior |
| --- | --- | --- |
| `CreditLimitExceededException` | 409 Conflict | Returns structured credit-limit error with details |
| `MfaRequiredException` | 428 Precondition Required | Returns MFA challenge response |
| `InvalidMfaException` | 401 Unauthorized | Returns MFA failure code |
| `AuthenticationException` (Spring) | 401 Unauthorized | Maps `LockedException`, `BadCredentialsException` to specific codes |
| `AuthSecurityContractException` | Custom (from exception) | Returns the exception's explicit status, code, and details |
| `AccessDeniedException` (Spring) | 403 Forbidden | Uses `PortalRoleActionMatrix` for user-friendly message resolution |
| `DataIntegrityViolationException` | 409 Conflict | Infers duplicate vs. foreign-key from exception message |
| `IllegalStateException` | 409 Conflict | Sanitized in production mode |
| `RuntimeException` | 500 Internal Server Error | Generic error; message exposed only in non-prod mode |
| `Exception` | 500 Internal Server Error | Catch-all; exception type exposed only in non-prod mode |

### 2.5 Response Envelope

**Source:** [`shared/dto/ApiResponse.java`](../../erp-domain/src/main/java/com/bigbrightpaints/erp/shared/dto/ApiResponse.java)

All responses (success and failure) use the `ApiResponse<T>` record:

```java
record ApiResponse<T>(boolean success, String message, T data, Instant timestamp)
```

- **Success:** `success = true`, `message` is optional context, `data` holds the payload.
- **Failure:** `success = false`, `message` is a user-facing summary, `data` holds a structured map with `code`, `reason`, `traceId`, and optionally `details`, `errors`, or `timestamp`.

### 2.6 Audit Integration for Error Routing

**Source:** [`core/exception/AuditExceptionRoutingService.java`](../../erp-domain/src/main/java/com/bigbrightpaints/erp/core/exception/AuditExceptionRoutingService.java)

The `GlobalExceptionHandler` routes certain failures to the platform audit system:

| Error path | Audit routing | Current scope |
| --- | --- | --- |
| `ApplicationException` on `/api/v1/accounting/settlements/**` | `AuditEvent.INTEGRATION_FAILURE` with settlement metadata | Settlement failures only |
| Malformed request (`HttpMessageNotReadableException`) | `AuditEvent.INTEGRATION_FAILURE` with request-parse metadata | All malformed request bodies |

Non-settlement `ApplicationException` instances and other exceptions are not currently routed to audit by the global handler.

### 2.7 Production vs Non-Production Error Detail Exposure

| Detail | Production | Non-Production |
| --- | --- | --- |
| `ApplicationException` details map | Sanitized (only allowlisted catalog conflict keys) | Full details |
| Malformed request body cause | Omitted | Included |
| `IllegalStateException` message | Replaced with generic `BUS_001` message | Original message |
| `RuntimeException` message | Generic "internal error" | Original message |
| `Exception` class name | Omitted | Included as `type` |

> **Invariant:** In production mode, the platform never exposes raw exception messages, stack traces, or internal class names in error responses. The only structured details that pass through are explicitly allowlisted.

---

## 3. Fail-Open vs Fail-Closed Summary

The platform makes an explicit architectural choice: **authentication validation is fail-open** (the filter does not reject, it simply does not authenticate and lets Spring Security decide), while **tenant isolation and company-context enforcement are fail-closed** (any ambiguity is rejected with 403).

### 3.1 Fail-Open Surfaces

| Surface | Behavior when edge case occurs | Safety net |
| --- | --- | --- |
| JWT parsing failure | Request continues as unauthenticated | Spring Security `.anyRequest().authenticated()` rejects for protected endpoints |
| Token is blacklisted | Request continues as unauthenticated | Spring Security rejects for protected endpoints |
| User tokens are revoked | Request continues as unauthenticated | Spring Security rejects for protected endpoints |
| Account is disabled or locked | Request continues as unauthenticated | Spring Security rejects for protected endpoints |
| Any unexpected JWT exception | Request continues as unauthenticated | Spring Security rejects for protected endpoints |
| Unknown principal type in `MustChangePasswordCorridorFilter` | Treated as not requiring password change | Authentication chain already protects unauthenticated requests |

### 3.2 Fail-Closed Surfaces

| Surface | Behavior when edge case occurs |
| --- | --- |
| JWT present but missing `companyCode` claim | 403 `COMPANY_CONTEXT_MISSING` |
| `X-Company-Code` header does not match JWT claim | 403 `COMPANY_CONTEXT_MISMATCH` |
| Legacy `X-Company-Id` header | 403 `COMPANY_CONTEXT_LEGACY_HEADER_UNSUPPORTED` |
| Unauthenticated request attempts company-scoped access via header | 403 `COMPANY_CONTEXT_AUTH_REQUIRED` |
| User's company does not match requested company | 403 `COMPANY_ACCESS_DENIED` |
| Tenant is `SUSPENDED` (mutating request) | 403 `TENANT_LIFECYCLE_RESTRICTED` |
| Tenant is `DEACTIVATED` (any request) | 403 `TENANT_LIFECYCLE_RESTRICTED` |
| Super-admin on tenant business path | 403 `SUPER_ADMIN_PLATFORM_ONLY` |
| Super-admin on tenant audit path | 403 `SUPER_ADMIN_TENANT_WORKFLOW_DENIED` |
| Control-plane path without authentication | 403 `COMPANY_CONTROL_ACCESS_DENIED` |
| Control-plane path with unresolvable company ID | 403 `COMPANY_CONTROL_ACCESS_DENIED` |
| User not authorized for target company on control-plane path | 403 `COMPANY_CONTROL_ACCESS_DENIED` |
| Tenant runtime admission unavailable | 403 `TENANT_RUNTIME_ADMISSION_UNAVAILABLE` |
| Tenant runtime admission denied | 403/429 with admission-specific reason |
| Unknown principal type in company access validation | Denied (returns `false`) |
| Must-change-password user outside allowed corridor | 403 `PASSWORD_CHANGE_REQUIRED` |

---

## 4. Key Services in the Security Pipeline

### 4.1 AuthScopeService

**Source:** [`core/security/AuthScopeService.java`](../../erp-domain/src/main/java/com/bigbrightpaints/erp/core/security/AuthScopeService.java)

Manages the platform authentication scope code (default: `PLATFORM`). Super-admin users are scoped to this platform code rather than a tenant company code. The scope code is stored in `SystemSettings` and can be updated (with validation against existing company codes and user accounts).

### 4.2 TokenBlacklistService

**Source:** [`core/security/TokenBlacklistService.java`](../../erp-domain/src/main/java/com/bigbrightpaints/erp/core/security/TokenBlacklistService.java)

Provides two levels of token revocation:

- **Per-token blacklist**: individual JWTs are blacklisted by `jti` with expiration tracking.
- **User-level revocation**: all tokens issued before a timestamp are considered revoked for a given user.

Both are database-backed and survive server restarts. Cleanup runs hourly.

### 4.3 TenantRuntimeRequestAdmissionService

Called by `CompanyContextFilter` to run tenant runtime admission checks (rate limiting, usage constraints, etc.) before allowing the request to proceed. The admission service tracks request begin/complete lifecycle and returns structured denial reasons when admission fails.

> **Note:** The full runtime-gating model and admission details are documented in [core-audit-runtime-settings.md §2](core-audit-runtime-settings.md#2-runtime-gating-split).

---

## 5. Exception Handler Ordering and Responsibility

```
ApplicationException thrown in controller/service
  │
  ├─ GlobalExceptionHandler (@Order(HIGHEST_PRECEDENCE))
  │     Handles: ApplicationException, MethodArgumentNotValid,
  │              HttpMessageNotReadable, ConstraintViolation,
  │              IllegalArgumentException
  │     Routes: settlement failures to audit
  │
  └─ CoreFallbackExceptionHandler (@Order(LOWEST_PRECEDENCE))
        Handles: CreditLimitExceededException, MfaRequiredException,
                 InvalidMfaException, AuthenticationException,
                 AuthSecurityContractException, AccessDeniedException,
                 DataIntegrityViolationException, IllegalStateException,
                 RuntimeException, Exception
```

**Inheritance note:** `CreditLimitExceededException` extends `ApplicationException`, so it is technically matchable by both handlers. It is handled by `CoreFallbackExceptionHandler` because Spring resolves the most specific `@ExceptionHandler` method, and `CoreFallbackExceptionHandler` declares a handler for the concrete `CreditLimitExceededException` type. In practice, the `@Order` annotations ensure `GlobalExceptionHandler` is consulted first, but Spring's exception-handler resolution prefers the most specific type match regardless of order.

---

## 6. Cross-References

| Document | Relationship |
| --- | --- |
| [core-audit-runtime-settings.md](core-audit-runtime-settings.md) | Second slice: audit-surface ownership, runtime gating, settings risk |
| [core-idempotency.md](core-idempotency.md) | Third/integrating slice: shared and module-local idempotency behavior |
| [docs/modules/auth.md](auth.md) | Auth module: login/refresh/logout/MFA/password lifecycle, token issuance |
| [docs/modules/company.md](company.md) | Company module: tenant lifecycle, runtime admission, module gating |
| [docs/modules/admin-portal-rbac.md](admin-portal-rbac.md) | Admin/portal/RBAC: role-action boundaries, access-control enforcement |
| [docs/ARCHITECTURE.md](../ARCHITECTURE.md) | Architecture overview: security, tenancy, authorization |
| [docs/SECURITY.md](../SECURITY.md) | Security review policy, high-risk change classes |
| [docs/RELIABILITY.md](../RELIABILITY.md) | Reliability: retry, dead-letter, idempotency patterns |
| [docs/adrs/INDEX.md](../adrs/INDEX.md) | ADR index: including ADR-002 (multi-tenant auth scoping) |

---

## 7. Current Limitations and Known Gaps

1. **Audit routing is narrow:** Only settlement failures and malformed requests are routed to the platform audit system from the global exception handler. Other `ApplicationException` instances that indicate important business failures (e.g., concurrency conflicts, credit-limit breaches) are not automatically routed.
2. **Settlement audit detail is specialized:** The `SettlementExceptionHandler` maintains its own allowlist for settlement failure metadata keys, which is separate from the general error detail sanitization logic.
3. **Audit coverage is documented separately:** Audit-surface ownership (platform audit vs. enterprise audit trail vs. accounting event store) is documented in [core-audit-runtime-settings.md](core-audit-runtime-settings.md) §1. This slice covers only the exception-to-audit routing that occurs in the global exception handler.
4. **Runtime-gating details are documented separately:** The full runtime-gating model, admission parameters, and rate-limiting details are documented in [core-audit-runtime-settings.md](core-audit-runtime-settings.md) §2.
5. **Module-local exception subclasses:** Some modules define their own exception types (e.g., `MfaRequiredException`, `InvalidMfaException`). These are handled by `CoreFallbackExceptionHandler` rather than through the `ApplicationException` + `ErrorCode` pattern, creating a slight inconsistency in the error contract.
