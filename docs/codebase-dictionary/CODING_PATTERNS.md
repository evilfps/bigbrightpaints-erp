# Coding Patterns

This document defines the standard coding patterns used in the BigBright ERP codebase. All new code should follow these patterns to ensure consistency, maintainability, and correctness.

---

## Table of Contents

1. [Controller Patterns](#controller-patterns)
2. [Service Patterns](#service-patterns)
3. [Entity Patterns](#entity-patterns)
4. [Repository Patterns](#repository-patterns)
5. [DTO Patterns](#dto-patterns)
6. [Exception Patterns](#exception-patterns)
7. [Testing Patterns](#testing-patterns)
8. [Logging Patterns](#logging-patterns)

---

## Controller Patterns

### Pattern: Request Handling

**When to use:** All controller endpoints handling REST requests.

**Example from codebase:**

```java
// From SalesController.java
@PostMapping("/sales/orders")
@PreAuthorize("hasAnyAuthority('ROLE_SALES','ROLE_ADMIN')")
public ResponseEntity<ApiResponse<SalesOrderDto>> createOrder(
    @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey,
    @RequestHeader(value = "X-Idempotency-Key", required = false) String legacyIdempotencyKey,
    @Valid @RequestBody SalesOrderRequest request) {
  SalesOrderRequest resolved = applyOrderIdempotencyKey(request, idempotencyKey, legacyIdempotencyKey);
  return ResponseEntity.ok(
      ApiResponse.success("Order created", salesOrderCrudService.createOrder(resolved)));
}
```

**Key elements:**
- Always use `@PreAuthorize` for security
- Use `@Valid` annotation for request body validation
- Return `ResponseEntity<ApiResponse<T>>` for consistent API responses
- Support idempotency keys for write operations via headers
- Add success message to `ApiResponse.success()`

**File reference:** `modules/sales/controller/SalesController.java`

---

### Pattern: Pagination

**When to use:** List endpoints returning multiple records.

**Example from codebase:**

```java
// From SalesController.java
@GetMapping("/sales/orders")
@Timed(value = "erp.sales.orders.list", description = "List sales orders")
@PreAuthorize(PortalRoleActionMatrix.ADMIN_SALES_FACTORY_ACCOUNTING)
public ResponseEntity<ApiResponse<List<SalesOrderDto>>> orders(
    @RequestParam(required = false) String status,
    @RequestParam(required = false) Long dealerId,
    @RequestParam(defaultValue = "0") int page,
    @RequestParam(defaultValue = "100") int size) {
  return ResponseEntity.ok(
      ApiResponse.success(salesOrderCrudService.listOrders(status, dealerId, page, size)));
}
```

**Key elements:**
- Use `page` and `size` query parameters with sensible defaults
- Add `@Timed` annotation for metrics
- Filter parameters should be optional (`required = false`)
- Default page size should be reasonable (50-100)

**File reference:** `modules/sales/controller/SalesController.java`

---

### Pattern: Company Context Access

**When to use:** When endpoint needs current company information.

**Example from codebase:**

```java
// From AccountingController.java
@GetMapping("/date-context")
@PreAuthorize("hasAnyAuthority('ROLE_ADMIN','ROLE_ACCOUNTING')")
public ResponseEntity<ApiResponse<Map<String, Object>>> getAccountingDateContext() {
  Company company = companyContextService.requireCurrentCompany();
  LocalDate today = companyClock.today(company);
  Instant now = companyClock.now(company);
  Map<String, Object> payload = new HashMap<>();
  payload.put("companyId", company != null ? company.getId() : null);
  payload.put("companyCode", company != null ? company.getCode() : null);
  payload.put("timezone", company != null ? company.getTimezone() : null);
  payload.put("today", today);
  payload.put("now", now);
  return ResponseEntity.ok(ApiResponse.success("Accounting date context", payload));
}
```

**Key elements:**
- Inject `CompanyContextService` and `CompanyClock`
- Use `requireCurrentCompany()` to get current tenant
- Always use `CompanyClock` for date/time operations, not `LocalDate.now()`

**File reference:** `modules/accounting/controller/AccountingController.java`

---

### Pattern: Controller-Level Exception Handling

**When to use:** Module-specific exception handling needs.

**Example from codebase:**

```java
// From AccountingController.java
@ExceptionHandler(ApplicationException.class)
public ResponseEntity<ApiResponse<Map<String, Object>>> handleApplicationException(
    ApplicationException ex, HttpServletRequest request) {
  String traceId = UUID.randomUUID().toString();
  Map<String, Object> errorData = new HashMap<>();
  errorData.put("code", ex.getErrorCode().getCode());
  errorData.put("message", ex.getUserMessage());
  errorData.put("reason", ex.getUserMessage());
  errorData.put("path", request != null ? request.getRequestURI() : null);
  errorData.put("traceId", traceId);
  Map<String, Object> details = ex.getDetails();
  if (!details.isEmpty()) {
    errorData.put("details", details);
  }
  return ResponseEntity.status(HttpStatus.BAD_REQUEST)
      .body(ApiResponse.failure(ex.getUserMessage(), errorData));
}
```

**Key elements:**
- Use `@ExceptionHandler` at controller level for module-specific handling
- Always include trace ID for debugging
- Include error code from `ErrorCode` enum
- Include request path for context

**File reference:** `modules/accounting/controller/AccountingController.java`

---

### Anti-Pattern: Direct Repository Access

**Don't do this:**

```java
// PROHIBITED
@GetMapping("/dealers")
public List<Dealer> listDealers() {
  return dealerRepository.findAll(); // Missing company filter
}
```

**Do this instead:**

```java
// CORRECT
@GetMapping("/sales/dealers")
@PreAuthorize(PortalRoleActionMatrix.ADMIN_SALES_ACCOUNTING)
public ResponseEntity<ApiResponse<List<DealerResponse>>> listDealers() {
  return ResponseEntity.ok(ApiResponse.success("Dealer directory", dealerService.listDealers()));
}
```

---

## Service Patterns

### Pattern: Constructor Injection

**When to use:** All service classes.

**Example from codebase:**

```java
// From AccountingService.java
@Service
public class AccountingService extends AccountingCoreService {

  private final JournalEntryService journalEntryService;
  private final DealerReceiptService dealerReceiptService;
  private final SettlementService settlementService;
  private final CreditDebitNoteService creditDebitNoteService;
  private final AccountingAuditService accountingAuditService;
  private final InventoryAccountingService inventoryAccountingService;
  private final ObjectProvider<AccountingFacade> accountingFacadeProvider;

  @Autowired
  public AccountingService(
      CompanyContextService companyContextService,
      AccountRepository accountRepository,
      JournalEntryRepository journalEntryRepository,
      DealerLedgerService dealerLedgerService,
      SupplierLedgerService supplierLedgerService,
      // ... other dependencies
      ObjectProvider<AccountingFacade> accountingFacadeProvider) {
    super(/* parent constructor call */);
    this.journalEntryService = journalEntryService;
    this.dealerReceiptService = dealerReceiptService;
    this.settlementService = settlementService;
    this.creditDebitNoteService = creditDebitNoteService;
    this.accountingAuditService = accountingAuditService;
    this.inventoryAccountingService = inventoryAccountingService;
    this.accountingFacadeProvider = accountingFacadeProvider;
  }
}
```

**Key elements:**
- Use constructor injection (not field injection)
- Mark dependencies as `final`
- Use `@Autowired` on constructor (optional in Spring 4.3+)
- Use `ObjectProvider` for optional/circular dependencies

**File reference:** `modules/accounting/service/AccountingService.java`

---

### Pattern: Facade Pattern for Complex Operations

**When to use:** Complex cross-module operations, especially financial.

**Example from codebase:**

```java
// From AccountingFacade.java
@Service
public class AccountingFacade extends AccountingFacadeCore {

  public JournalEntryDto createManualJournal(ManualJournalRequest request) {
    if (request == null) {
      throw validationMissingField("Manual journal request is required");
    }
    if (request.lines() == null || request.lines().isEmpty()) {
      throw validationInvalidInput("Manual journal requires at least one line");
    }

    BigDecimal totalDebit = BigDecimal.ZERO;
    BigDecimal totalCredit = BigDecimal.ZERO;
    // ... validation and processing

    if (totalDebit.subtract(totalCredit).abs().compareTo(BigDecimal.ZERO) > 0) {
      throw validationInvalidInput("Manual journal entry must balance")
          .withDetail("totalDebit", totalDebit)
          .withDetail("totalCredit", totalCredit);
    }

    return createStandardJournal(journalRequest);
  }
}
```

**Key elements:**
- Facades orchestrate complex workflows
- Centralize validation logic
- Provide single entry point for module operations
- Return standardized DTOs

**File reference:** `modules/accounting/service/AccountingFacade.java`

---

### Pattern: Idempotency Handling

**When to use:** All write operations that should be idempotent.

**Example from codebase:**

```java
// From SalesController.java - Idempotency key resolution
private SalesOrderRequest applyOrderIdempotencyKey(
    SalesOrderRequest request, String idempotencyKeyHeader, String legacyIdempotencyKeyHeader) {
  if (request == null) {
    return null;
  }
  String primaryHeader = normalizeIdempotencyHeader(idempotencyKeyHeader);
  String legacyHeader = normalizeIdempotencyHeader(legacyIdempotencyKeyHeader);
  if (primaryHeader != null && legacyHeader != null && !primaryHeader.equals(legacyHeader)) {
    throw new ApplicationException(
            ErrorCode.VALIDATION_INVALID_INPUT,
            "Idempotency key mismatch between Idempotency-Key and X-Idempotency-Key headers")
        .withDetail("idempotencyKey", primaryHeader)
        .withDetail("legacyIdempotencyKey", legacyHeader);
  }
  String resolvedKey = IdempotencyHeaderUtils.resolveBodyOrHeaderKey(
      request.idempotencyKey(), primaryHeader, legacyHeader);
  // ...
}
```

**Key elements:**
- Accept idempotency keys via header and/or request body
- Use `IdempotencyHeaderUtils` for consistent resolution
- Support legacy header names (`X-Idempotency-Key`)
- Validate key consistency between sources

**File reference:** `modules/sales/controller/SalesController.java`

---

### Pattern: Delegation to Specialized Services

**When to use:** When a service needs to delegate to specialized sub-services.

**Example from codebase:**

```java
// From SalesOrderCrudService.java
@Service
public class SalesOrderCrudService {

  private final SalesCoreEngine salesCoreEngine;
  private final SalesIdempotencyService salesIdempotencyService;

  public SalesOrderCrudService(
      SalesCoreEngine salesCoreEngine, SalesIdempotencyService salesIdempotencyService) {
    this.salesCoreEngine = salesCoreEngine;
    this.salesIdempotencyService = salesIdempotencyService;
  }

  public SalesOrderDto createOrder(SalesOrderRequest request) {
    return salesIdempotencyService.createOrderWithIdempotency(request);
  }

  public SalesOrderDto updateOrder(Long id, SalesOrderRequest request) {
    return salesCoreEngine.updateOrder(id, request);
  }
}
```

**Key elements:**
- Thin wrapper services delegate to specialized engines
- Separate concerns (CRUD vs lifecycle vs idempotency)
- Keep service methods focused and single-purpose

**File reference:** `modules/sales/service/SalesOrderCrudService.java`

---

### Anti-Pattern: Business Logic in Controllers

**Don't do this:**

```java
// PROHIBITED
@PostMapping("/orders")
public OrderDto createOrder(@RequestBody OrderRequest request) {
  if (request.getTotalAmount().compareTo(BigDecimal.ZERO) <= 0) {
    throw new IllegalArgumentException("Invalid amount");
  }
  Order order = new Order();
  // ... direct entity manipulation
  return orderRepository.save(order);
}
```

**Do this instead:**

```java
// CORRECT
@PostMapping("/orders")
public ResponseEntity<ApiResponse<OrderDto>> createOrder(@Valid @RequestBody OrderRequest request) {
  return ResponseEntity.ok(ApiResponse.success("Order created", orderService.createOrder(request)));
}
```

---

## Entity Patterns

### Pattern: Base Entity with Versioning

**When to use:** All entities that need optimistic locking.

**Example from codebase:**

```java
// From VersionedEntity.java
@MappedSuperclass
public abstract class VersionedEntity {

  @Version
  @Column(name = "version", nullable = false)
  private Long version = 0L;

  public Long getVersion() {
    return version;
  }

  public void setVersion(Long version) {
    this.version = version;
  }
}
```

**Key elements:**
- Extend `VersionedEntity` for optimistic locking
- `@Version` field automatically managed by JPA
- Prevents lost updates in concurrent scenarios

**File reference:** `core/domain/VersionedEntity.java`

---

### Pattern: Company Scoping

**When to use:** All multi-tenant entities.

**Example from codebase:**

```java
// From Account.java
@Entity
@Table(name = "accounts", uniqueConstraints = @UniqueConstraint(columnNames = {"company_id", "code"}))
public class Account extends VersionedEntity {

  @Id
  @GeneratedValue(strategy = GenerationType.IDENTITY)
  private Long id;

  @Column(name = "public_id", nullable = false)
  private UUID publicId;

  @ManyToOne(optional = false, fetch = FetchType.LAZY)
  @JoinColumn(name = "company_id")
  private Company company;

  @Column(nullable = false)
  private String code;

  // ...
}
```

**Key elements:**
- Always include `company` relationship for multi-tenant entities
- Use `@UniqueConstraint` with `company_id` for tenant-scoped uniqueness
- Use `LAZY` fetch type for company relationships
- Include both `id` (internal) and `publicId` (external) identifiers

**File reference:** `modules/accounting/domain/Account.java`

---

### Pattern: Pre-Persist Hooks

**When to use:** Setting default values before entity persistence.

**Example from codebase:**

```java
// From Account.java
@PrePersist
public void prePersist() {
  if (publicId == null) {
    publicId = UUID.randomUUID();
  }
}

// From Company.java
@PrePersist
public void prePersist() {
  if (publicId == null) {
    publicId = UUID.randomUUID();
  }
  if (createdAt == null) {
    createdAt = CompanyTime.now();
  }
  if (timezone == null) {
    timezone = "UTC";
  }
  if (lifecycleState == null) {
    lifecycleState = CompanyLifecycleState.ACTIVE;
  }
  enabledModules = CompanyModule.normalizeEnabledGatableModuleNames(enabledModules);
  supportTags = normalizeSupportTags(supportTags);
  initializeQuotaDefaults();
}

@PreUpdate
public void preUpdate() {
  enabledModules = CompanyModule.normalizeEnabledGatableModuleNames(enabledModules);
  supportTags = normalizeSupportTags(supportTags);
  initializeQuotaDefaults();
}
```

**Key elements:**
- Use `@PrePersist` for defaults on insert
- Use `@PreUpdate` for normalization on update
- Never rely on client for internal fields like `publicId`

**File reference:** `modules/accounting/domain/Account.java`, `modules/company/domain/Company.java`

---

### Pattern: Entity Validation Methods

**When to use:** Domain-level validation rules.

**Example from codebase:**

```java
// From Account.java
/**
 * Guard against invalid balances by account type.
 * Assets/expenses/COGS must not go negative.
 */
public void validateBalanceUpdate(BigDecimal newBalance) {
  if (newBalance == null) {
    throw new IllegalArgumentException("Account balance cannot be null");
  }
  AccountType safeType = type;
  if (safeType == null) {
    return;
  }
  // Soft guards: warn on unusual signs but do not block
  if ((safeType == AccountType.ASSET
          || safeType == AccountType.EXPENSE
          || safeType == AccountType.COGS)
      && newBalance.compareTo(BigDecimal.ZERO) < 0) {
    log.warn("Unusual negative balance {} for {} account {}", newBalance, safeType, code);
  }
  if ((safeType == AccountType.LIABILITY
          || safeType == AccountType.REVENUE
          || safeType == AccountType.EQUITY)
      && newBalance.compareTo(BigDecimal.ZERO) > 0) {
    log.warn("Unusual debit balance {} for {} account {}", newBalance, safeType, code);
  }
}
```

**Key elements:**
- Domain entities can have validation methods
- Use warnings for soft violations, exceptions for hard violations
- Call validation from setters where appropriate

**File reference:** `modules/accounting/domain/Account.java`

---

### Pattern: Enum Usage

**When to use:** Fixed sets of values for entity fields.

**Example from codebase:**

```java
// From Account.java
@Enumerated(EnumType.STRING)
@Column(nullable = false)
private AccountType type;
```

**Key elements:**
- Use `@Enumerated(EnumType.STRING)` for database storage
- Always use `EnumType.STRING` (not `ORDINAL`) for maintainability
- Define enums in the same package as the entity or a common `enums` package

---

## Repository Patterns

### Pattern: Company-Scoped Queries

**When to use:** All repository queries in multi-tenant context.

**Example from codebase:**

```java
// From AccountRepository.java
public interface AccountRepository extends JpaRepository<Account, Long> {
  
  List<Account> findByCompanyOrderByCodeAsc(Company company);

  Optional<Account> findByCompanyAndId(Company company, Long id);

  Optional<Account> findByCompanyAndCodeIgnoreCase(Company company, String code);

  @Lock(LockModeType.PESSIMISTIC_WRITE)
  @Query("select a from Account a where a.company = :company and a.id = :id")
  Optional<Account> lockByCompanyAndId(@Param("company") Company company, @Param("id") Long id);

  @Modifying(flushAutomatically = true)
  @Query("UPDATE Account a SET a.balance = a.balance + :delta WHERE a.company = :company AND a.id = :id")
  int updateBalanceAtomic(
      @Param("company") Company company, @Param("id") Long id, @Param("delta") BigDecimal delta);
}
```

**Key elements:**
- Always include `company` parameter in queries
- Use method naming convention: `findByCompanyAnd...`
- Use `@Lock` for pessimistic locking when needed
- Use `@Modifying(flushAutomatically = true)` for update queries

**File reference:** `modules/accounting/domain/AccountRepository.java`

---

### Pattern: Custom Query Methods

**When to use:** Complex queries not expressible with method naming.

**Example from codebase:**

```java
// From AccountRepository.java
@Query("SELECT a FROM Account a WHERE a.company = :company AND a.parent.id = :parentId ORDER BY a.code")
List<Account> findChildrenByParentId(
    @Param("company") Company company, @Param("parentId") Long parentId);

@Query("SELECT a FROM Account a WHERE a.company = :company ORDER BY a.hierarchyLevel, a.code")
List<Account> findAllOrderedByHierarchy(@Param("company") Company company);
```

**Key elements:**
- Use `@Query` with JPQL for complex queries
- Always bind parameters with `@Param`
- Include `company` scoping

**File reference:** `modules/accounting/domain/AccountRepository.java`

---

### Pattern: Atomic Updates

**When to use:** When you need to update a field without loading the entity.

**Example from codebase:**

```java
// From AccountRepository.java
@Modifying(flushAutomatically = true)
@Query("UPDATE Account a SET a.balance = a.balance + :delta WHERE a.company = :company AND a.id = :id")
int updateBalanceAtomic(
    @Param("company") Company company, @Param("id") Long id, @Param("delta") BigDecimal delta);
```

**Key elements:**
- Use `@Modifying` annotation for update/delete queries
- Add `flushAutomatically = true` to flush pending changes first
- Return `int` for affected row count

**File reference:** `modules/accounting/domain/AccountRepository.java`

---

### Anti-Pattern: Unscoped Queries

**Don't do this:**

```java
// PROHIBITED
List<Account> findAll();
Account findById(Long id);
```

**Do this instead:**

```java
// CORRECT
List<Account> findByCompanyOrderByCodeAsc(Company company);
Optional<Account> findByCompanyAndId(Company company, Long id);
```

---

## DTO Patterns

### Pattern: Request DTOs with Validation

**When to use:** All API request payloads.

**Example from codebase:**

```java
// From SalesOrderRequest.java
public record SalesOrderRequest(
    Long dealerId,
    @NotNull BigDecimal totalAmount,
    String currency,
    String notes,
    @NotEmpty List<@Valid SalesOrderItemRequest> items,
    String gstTreatment,
    BigDecimal gstRate,
    Boolean gstInclusive,
    String idempotencyKey,
    String paymentMode) {

  // Business methods for normalization
  public String normalizedPaymentMode() {
    String normalized = rawNormalizedPaymentMode();
    if (DEFAULT_PAYMENT_MODE.equals(normalized)) {
      return DEFAULT_PAYMENT_MODE;
    }
    if (LEGACY_HYBRID_PAYMENT_MODE.equals(normalized)) {
      return HYBRID_PAYMENT_MODE;
    }
    return normalized;
  }

  public String resolveIdempotencyKey() {
    String normalized = IdempotencyUtils.normalizeKey(idempotencyKey);
    if (normalized != null) {
      return normalized;
    }
    return resolveDerivedIdempotencyKey(normalizedPaymentMode(), false);
  }
}
```

**Key elements:**
- Use Java records for immutable DTOs
- Add validation annotations (`@NotNull`, `@NotEmpty`, `@Valid`)
- Include helper methods for normalization/resolution
- Include `idempotencyKey` field for write operations

**File reference:** `modules/sales/dto/SalesOrderRequest.java`

---

### Pattern: Response Wrapping with ApiResponse

**When to use:** All API responses.

**Example from codebase:**

```java
// From ApiResponse.java
@JsonInclude(JsonInclude.Include.NON_NULL)
public record ApiResponse<T>(boolean success, String message, T data, Instant timestamp) {
  
  public static <T> ApiResponse<T> success(String message, T data) {
    return new ApiResponse<>(true, message, data, CompanyTime.now());
  }

  public static <T> ApiResponse<T> success(T data) {
    return success(null, data);
  }

  public static <T> ApiResponse<T> failure(String message) {
    return new ApiResponse<>(false, message, null, CompanyTime.now());
  }

  public static <T> ApiResponse<T> failure(String message, T data) {
    return new ApiResponse<>(false, message, data, CompanyTime.now());
  }
}
```

**Key elements:**
- Use `ApiResponse<T>` wrapper for all responses
- Include `success`, `message`, `data`, and `timestamp`
- Use `CompanyTime.now()` for consistent timestamps
- Use `@JsonInclude(NON_NULL)` to omit null fields

**File reference:** `shared/dto/ApiResponse.java`

---

### Pattern: Error Response Structure

**When to use:** Error responses.

**Example from codebase:**

```java
// From ErrorResponse.java
public record ErrorResponse(
    Instant timestamp,
    int status,
    String error,
    String message,
    String path,
    Map<String, Object> details) {
  
  public static ErrorResponse of(
      int status, String error, String message, String path, Map<String, Object> details) {
    return new ErrorResponse(CompanyTime.now(), status, error, message, path, details);
  }
}
```

**Key elements:**
- Include timestamp, status, error type, message, and path
- Include `details` map for additional context
- Use factory method `of()` for construction

**File reference:** `shared/dto/ErrorResponse.java`

---

### Pattern: Request vs Response DTOs

**When to use:** Separating input and output structures.

**Guidelines:**
- **Request DTOs:** Include validation annotations, idempotency keys, mutable fields
- **Response DTOs:** Include computed fields, timestamps, references to related entities
- **Never reuse** the same DTO for both request and response

**Example:**
- `SalesOrderRequest` → input with validation
- `SalesOrderDto` → output with computed fields

---

## Exception Patterns

### Pattern: ApplicationException Usage

**When to use:** All application-specific business exceptions.

**Example from codebase:**

```java
// From ApplicationException.java
public class ApplicationException extends RuntimeException {

  private final ErrorCode errorCode;
  private final Map<String, Object> details;
  private final String userMessage;

  public ApplicationException(ErrorCode errorCode) {
    super(errorCode.getDefaultMessage());
    this.errorCode = errorCode;
    this.userMessage = errorCode.getDefaultMessage();
    this.details = new HashMap<>();
  }

  public ApplicationException(ErrorCode errorCode, String userMessage) {
    super(userMessage);
    this.errorCode = errorCode;
    this.userMessage = userMessage;
    this.details = new HashMap<>();
  }

  public ApplicationException withDetail(String key, Object value) {
    this.details.put(key, value);
    return this; // Fluent API
  }

  public ErrorCode getErrorCode() {
    return errorCode;
  }

  public Map<String, Object> getDetails() {
    return new HashMap<>(details);
  }

  public String getUserMessage() {
    return userMessage;
  }
}
```

**Key elements:**
- Extend `RuntimeException` (unchecked)
- Always include an `ErrorCode`
- Support fluent `withDetail()` for additional context
- Separate `userMessage` from technical message

**File reference:** `core/exception/ApplicationException.java`

---

### Pattern: ErrorCode Definition

**When to use:** Defining standardized error codes.

**Example from codebase:**

```java
// From ErrorCode.java
public enum ErrorCode {
  // Authentication & Authorization (1000-1999)
  AUTH_INVALID_CREDENTIALS("AUTH_001", "Invalid credentials provided"),
  AUTH_TOKEN_EXPIRED("AUTH_002", "Authentication token has expired"),
  AUTH_INSUFFICIENT_PERMISSIONS("AUTH_004", "Insufficient permissions for this operation"),

  // Business Logic Errors (2000-2999)
  BUSINESS_INVALID_STATE("BUS_001", "Operation not allowed in current state"),
  BUSINESS_ENTITY_NOT_FOUND("BUS_003", "Requested resource not found"),

  // Validation Errors (3000-3999)
  VALIDATION_INVALID_INPUT("VAL_001", "Invalid input provided"),
  VALIDATION_MISSING_REQUIRED_FIELD("VAL_002", "Required field is missing"),

  // System Errors (4000-4999)
  SYSTEM_INTERNAL_ERROR("SYS_001", "An internal error occurred"),

  // Concurrency Errors (7000-7999)
  CONCURRENCY_CONFLICT("CONC_001", "Resource was modified by another user");

  private final String code;
  private final String defaultMessage;

  ErrorCode(String code, String defaultMessage) {
    this.code = code;
    this.defaultMessage = defaultMessage;
  }

  public String getCode() {
    return code;
  }

  public String getDefaultMessage() {
    return defaultMessage;
  }
}
```

**Key elements:**
- Group codes by category with numeric ranges
- Use format: `CATEGORY_###` (e.g., `AUTH_001`)
- Provide clear default messages
- Never expose internal details in default messages

**File reference:** `core/exception/ErrorCode.java`

---

### Pattern: Global Exception Handling

**When to use:** Centralized exception handling for consistent API responses.

**Example from codebase:**

```java
// From GlobalExceptionHandler.java
@ControllerAdvice
@Order(Ordered.HIGHEST_PRECEDENCE)
public class GlobalExceptionHandler extends ResponseEntityExceptionHandler {

  @ExceptionHandler(ApplicationException.class)
  public ResponseEntity<ApiResponse<Map<String, Object>>> handleApplicationException(
      ApplicationException ex, HttpServletRequest request) {
    String traceId = UUID.randomUUID().toString();
    logger.error(
        "Application error [{}] - Code: {}, Path: {}, User: {}",
        traceId,
        ex.getErrorCode().getCode(),
        request.getRequestURI(),
        request.getUserPrincipal() != null ? request.getUserPrincipal().getName() : "anonymous",
        ex);
    
    Map<String, Object> data = new HashMap<>();
    data.put("code", ex.getErrorCode().getCode());
    data.put("message", ex.getUserMessage());
    data.put("reason", ex.getUserMessage());
    data.put("traceId", traceId);
    data.put("timestamp", LocalDateTime.now());
    data.put("path", request.getRequestURI());
    
    Map<String, Object> details = resolveResponseDetails(ex, request, ex.getDetails());
    if (!details.isEmpty()) {
      data.put("details", details);
    }
    
    HttpStatus status = determineHttpStatus(ex.getErrorCode());
    return ResponseEntity.status(status).body(ApiResponse.failure(ex.getUserMessage(), data));
  }

  @Override
  protected ResponseEntity<Object> handleMethodArgumentNotValid(
      MethodArgumentNotValidException ex, HttpHeaders headers, HttpStatusCode status, WebRequest request) {
    String traceId = UUID.randomUUID().toString();
    Map<String, String> fieldErrors = new LinkedHashMap<>();
    for (FieldError error : ex.getBindingResult().getFieldErrors()) {
      fieldErrors.putIfAbsent(error.getField(), error.getDefaultMessage());
    }
    String reason = buildValidationReason(fieldErrors);
    Map<String, Object> data = new HashMap<>();
    data.put("code", ErrorCode.VALIDATION_INVALID_INPUT.getCode());
    data.put("message", reason);
    data.put("errors", fieldErrors);
    return ResponseEntity.badRequest().body(ApiResponse.failure(reason, data));
  }
}
```

**Key elements:**
- Use `@ControllerAdvice` for global handling
- Use `@Order` to control handler precedence
- Always log exceptions with trace ID
- Map error codes to appropriate HTTP status
- Handle validation errors specially

**File reference:** `core/exception/GlobalExceptionHandler.java`

---

### Pattern: Fallback Exception Handling

**When to use:** Catch-all for unhandled exceptions.

**Example from codebase:**

```java
// From CoreFallbackExceptionHandler.java
@ControllerAdvice
@Order(Ordered.LOWEST_PRECEDENCE)
public class CoreFallbackExceptionHandler {

  @ExceptionHandler(RuntimeException.class)
  public ResponseEntity<ApiResponse<Map<String, Object>>> handleRuntime(
      RuntimeException ex, HttpServletRequest request) {
    String traceId = UUID.randomUUID().toString();
    logger.error("Unexpected error [{}] - Path: {}", traceId, request.getRequestURI(), ex);
    
    Map<String, Object> data = new HashMap<>();
    data.put("code", ErrorCode.SYSTEM_INTERNAL_ERROR.getCode());
    data.put("message", isProductionMode() 
        ? "An internal error occurred. Please try again later."
        : ex.getMessage());
    data.put("traceId", traceId);
    
    return ResponseEntity.internalServerError().body(ApiResponse.failure("Internal error", data));
  }
}
```

**Key elements:**
- Use `@Order(LOWEST_PRECEDENCE)` to be catch-all
- Hide internal details in production mode
- Always include trace ID for debugging

**File reference:** `core/exception/CoreFallbackExceptionHandler.java`

---

## Testing Patterns

### Pattern: Unit Test Naming

**When to use:** All unit tests.

**Example from codebase:**

```java
// From AccountTest.java
class AccountTest {

  @Test
  void setParent_setsHierarchyLevelToParentPlusOne() {
    Account parent = new Account();
    parent.setHierarchyLevel(2);
    Account child = new Account();

    child.setParent(parent);

    assertThat(child.getHierarchyLevel()).isEqualTo(3);
  }

  @Test
  void validateBalanceUpdate_throwsOnNullBalance() {
    Account account = new Account();

    assertThatThrownBy(() -> account.validateBalanceUpdate(null))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("balance");
  }

  @Test
  void validateBalanceUpdate_allowsNegativeAssetBalance() {
    Account account = new Account();
    account.setType(AccountType.ASSET);

    assertThatCode(() -> account.validateBalanceUpdate(new BigDecimal("-1.00")))
        .doesNotThrowAnyException();
  }
}
```

**Key elements:**
- Use `methodName_scenario_expectedResult` naming
- Use AssertJ assertions (`assertThat`, `assertThatThrownBy`, `assertThatCode`)
- One assertion concept per test
- Use descriptive test names that read like documentation

**File reference:** `erp-domain/src/test/java/.../accounting/domain/AccountTest.java`

---

### Pattern: Integration Test Setup

**When to use:** Tests requiring database context.

**Example from codebase:**

```java
// From AbstractIntegrationTest.java
@SpringBootTest(
    classes = TestApplication.class,
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@Import(TestBeansConfig.class)
public abstract class AbstractIntegrationTest {

  @Autowired protected TestDataSeeder dataSeeder;
  @Autowired protected CompanyRepository companyRepository;

  @DynamicPropertySource
  static void registerDataSource(DynamicPropertyRegistry registry) {
    registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
    registry.add("spring.datasource.username", POSTGRES::getUsername);
    registry.add("spring.datasource.password", POSTGRES::getPassword);
    registry.add("spring.flyway.enabled", () -> true);
    registry.add("spring.flyway.locations", () -> "classpath:db/migration_v2");
  }

  protected Company enableModule(String companyCode, CompanyModule module) {
    Company company = companyRepository
        .findByCodeIgnoreCase(companyCode)
        .orElseThrow(() -> new IllegalArgumentException("Company not found: " + companyCode));
    return enableModule(company, module);
  }
}
```

**Key elements:**
- Extend `AbstractIntegrationTest` for all integration tests
- Use Testcontainers for PostgreSQL
- Use `@DynamicPropertySource` for configuration
- Use `TestDataSeeder` for consistent test data

**File reference:** `erp-domain/src/test/java/.../test/AbstractIntegrationTest.java`

---

### Pattern: Integration Test Structure

**When to use:** Repository and service integration tests.

**Example from codebase:**

```java
// From JournalLineRepositoryIT.java
class JournalLineRepositoryIT extends AbstractIntegrationTest {

  @Autowired private JournalLineRepository journalLineRepository;
  @Autowired private JournalEntryRepository journalEntryRepository;
  @Autowired private AccountRepository accountRepository;

  @Test
  void summarizeTotalsByCompanyAndJournalEntryIds_isTenantScoped() {
    String suffix = UUID.randomUUID().toString().substring(0, 8).toUpperCase();
    Company companyA = dataSeeder.ensureCompany("JLR-A-" + suffix, "Journal Repo A " + suffix);
    Company companyB = dataSeeder.ensureCompany("JLR-B-" + suffix, "Journal Repo B " + suffix);

    Account accountA = saveAccount(companyA, "CASH-A-" + suffix);
    Account accountB = saveAccount(companyB, "CASH-B-" + suffix);

    JournalEntry entryA = saveJournalEntry(companyA, "JE-A-" + suffix);
    JournalEntry entryB = saveJournalEntry(companyB, "JE-B-" + suffix);

    saveLine(entryA, accountA, new BigDecimal("100.00"), BigDecimal.ZERO);
    saveLine(entryB, accountB, new BigDecimal("250.00"), BigDecimal.ZERO);

    List<JournalLineRepository.JournalEntryLineTotals> totals =
        journalLineRepository.summarizeTotalsByCompanyAndJournalEntryIds(
            companyA, List.of(entryA.getId(), entryB.getId()));

    assertThat(totals).hasSize(1);
    assertThat(totals.getFirst().getJournalEntryId()).isEqualTo(entryA.getId());
  }
}
```

**Key elements:**
- Use unique suffixes to avoid test collisions
- Test tenant isolation explicitly
- Use helper methods for entity creation
- End integration test class with `IT` suffix

**File reference:** `erp-domain/src/test/java/.../accounting/domain/JournalLineRepositoryIT.java`

---

### Pattern: Utility Class Testing

**When to use:** Testing pure utility functions.

**Example from codebase:**

```java
// From MoneyUtilsTest.java
class MoneyUtilsTest {

  @Test
  void zeroIfNull_returnsZero() {
    assertThat(MoneyUtils.zeroIfNull(null)).isEqualByComparingTo(BigDecimal.ZERO);
  }

  @Test
  void zeroIfNull_returnsValue() {
    BigDecimal value = new BigDecimal("12.50");
    assertThat(MoneyUtils.zeroIfNull(value)).isEqualByComparingTo(value);
  }

  @Test
  void safeMultiply_multipliesValues() {
    assertThat(MoneyUtils.safeMultiply(new BigDecimal("2.5"), new BigDecimal("4")))
        .isEqualByComparingTo(new BigDecimal("10.0"));
  }

  @Test
  void withinTolerance_withinDelta_true() {
    assertThat(
        MoneyUtils.withinTolerance(
            new BigDecimal("10.00"), new BigDecimal("10.05"), new BigDecimal("0.10")))
        .isTrue();
  }
}
```

**Key elements:**
- Test edge cases (null, zero, negative)
- Use `isEqualByComparingTo` for BigDecimal comparisons
- Simple, focused tests for each method

**File reference:** `erp-domain/src/test/java/.../core/util/MoneyUtilsTest.java`

---

## Logging Patterns

### Pattern: SLF4J with Parameterized Logging

**When to use:** All logging statements.

**Example from codebase:**

```java
// From AuditService.java
private static final Logger logger = LoggerFactory.getLogger(AuditService.class);

public void logEvent(AuditEvent event, AuditStatus status, Map<String, String> metadata) {
  // ...
  logger.debug("Audit event logged: {} - Status: {}", event, status);
}

// From GlobalExceptionHandler.java
logger.error(
    "Application error [{}] - Code: {}, Path: {}, User: {}",
    traceId,
    ex.getErrorCode().getCode(),
    request.getRequestURI(),
    request.getUserPrincipal() != null ? request.getUserPrincipal().getName() : "anonymous",
    ex);

// From Account.java
log.warn("Unusual negative balance {} for {} account {}", newBalance, safeType, code);
```

**Key elements:**
- Use SLF4J (`Logger` and `LoggerFactory`)
- Use parameterized logging (`{}` placeholders)
- Include trace ID in error logs for debugging
- Use appropriate log levels: `debug`, `info`, `warn`, `error`
- Never concatenate strings in log calls

**File reference:** `core/audit/AuditService.java`, `core/exception/GlobalExceptionHandler.java`

---

### Pattern: Audit Logging for Business Events

**When to use:** All significant business operations.

**Example from codebase:**

```java
// From AuditService.java
public void logSuccess(AuditEvent event, Map<String, String> metadata) {
  self.logEvent(event, AuditStatus.SUCCESS, metadata);
}

public void logFailure(AuditEvent event, String reason) {
  Map<String, String> metadata = new HashMap<>();
  metadata.put("reason", safeString(reason));
  self.logEvent(event, AuditStatus.FAILURE, metadata);
}

public void logDataAccess(String resourceType, String resourceId, String operation) {
  Map<String, String> metadata = new HashMap<>();
  metadata.put("resourceType", safeString(resourceType));
  metadata.put("resourceId", safeString(resourceId));
  metadata.put("operation", operation);
  self.logEvent(AuditEvent.DATA_READ, AuditStatus.SUCCESS, metadata);
}
```

**Key elements:**
- Use `AuditService` for business-level audit logging
- Include resource type, ID, and operation
- Use appropriate `AuditEvent` enum value
- Log both success and failure cases

**File reference:** `core/audit/AuditService.java`

---

### Pattern: Async Audit Logging

**When to use:** Audit logging that shouldn't block main operation.

**Example from codebase:**

```java
// From AuditService.java
@Async
@Transactional(propagation = Propagation.REQUIRES_NEW)
public void logEventAsync(
    AuditEvent event,
    AuditStatus status,
    Map<String, String> metadata,
    String usernameOverride,
    String companyCodeOverride,
    Map<String, String> requestContext) {
  logEventInternal(event, status, metadata, usernameOverride, companyCodeOverride, requestContext);
}
```

**Key elements:**
- Use `@Async` for non-blocking logging
- Use `REQUIRES_NEW` propagation to separate transaction
- Audit failures should not affect main operation

**File reference:** `core/audit/AuditService.java`

---

## Summary of Anti-Patterns to Avoid

| Anti-Pattern | Why It's Wrong | Correct Approach |
|-------------|----------------|------------------|
| Direct repository access from controller | Bypasses business logic, no company scoping | Always go through service layer |
| Missing company filter in queries | Cross-tenant data leak | Always include `company` in repository queries |
| `LocalDate.now()` or `Instant.now()` | Inconsistent timezone handling | Use `CompanyClock` for tenant-aware time |
| Field injection (`@Autowired` on fields) | Harder to test, hidden dependencies | Use constructor injection |
| String concatenation in logging | Performance impact | Use parameterized logging `{}` |
| `EnumType.ORDINAL` for enums | Brittle when reordering | Use `EnumType.STRING` |
| Catching and swallowing exceptions | Hides problems | Log and rethrow or handle properly |
| Business logic in entities | Mixed concerns | Keep entities as data holders, use services |
| Missing idempotency on writes | Duplicate operations | Add idempotency key support |
| Hardcoded HTTP status codes | Inconsistent error mapping | Use `ErrorCode` to HTTP status mapping |

---

*Last updated: 2026-03-27*
*Generated from codebase analysis of BigBright ERP*
