# Test Mapping

> Generated: 2026-03-27
> Repository: BigBright ERP Backend
> Working Directory: `/Users/anas/Documents/Factory/bigbrightpaints-erp_worktrees/codebase-dictionary`

## Summary

| Metric | Count |
|--------|-------|
| **Total Test Files** | 466 |
| **Unit Tests** | 362 |
| **Integration Tests** | 104 |
| **E2E Tests** | 29 |
| **Contract Tests** | 31 |
| **Tests >200 lines (Complex)** | 218 |
| **Tests with @Disabled** | 1 |
| **Tests needing rewrite** | 5 |
| **Tests to delete** | 1 |

---

## By Module

| Module | Source Files | Tests | Test Coverage | Quality | Action |
|--------|-------------|-------|---------------|---------|--------|
| accounting | 179 | 51 | Good | High | Maintain |
| inventory | 85 | 30 | Good | High | Maintain |
| factory | 77 | 10 | Moderate | High | Add tests |
| sales | 74 | 31 | Good | High | Maintain |
| purchasing | 51 | 16 | Moderate | High | Add tests |
| auth | 47 | 23 | Good | High | Maintain |
| hr | 46 | 12 | Moderate | Medium | Add tests |
| admin | 39 | 11 | Moderate | High | Add tests |
| company | 37 | 15 | Good | High | Maintain |
| production | 30 | 9 | Moderate | High | Add tests |
| reports | 26 | 15 | Good | High | Maintain |
| invoice | 14 | 4 | Low | High | **Add tests** |
| rbac | 11 | 5 | Moderate | High | Add tests |
| portal | 9 | 3 | Low | Medium | **Add tests** |
| core/* | 55 | 36 | Good | High | Maintain |
| orchestrator/* | 28 | 11 | Moderate | High | Add tests |
| truthsuite | N/A | 93 | N/A | High | Maintain |
| codered | N/A | 35 | N/A | High | Maintain |
| e2e | N/A | 29 | N/A | High | Maintain |
| regression | N/A | 17 | N/A | High | Maintain |

---

## Test Type Distribution

### Unit Tests (362 files)
- Standard unit tests with `*Test.java` suffix
- Located primarily in `modules/*/service/`, `modules/*/controller/`, `core/*`
- Most use Spring Boot test context with real database (integration-style)

### Integration Tests (104 files)
- Named with `*IT.java` suffix
- Full Spring context with PostgreSQL (Testcontainers or external DB)
- Cross-module integration scenarios

### E2E Tests (29 files)
- Located in `e2e/` package structure
- Full workflow tests (Order-to-Cash, Procure-to-Pay)
- Includes accounting, sales, inventory, production flows

### Contract Tests (31 files)
- Named with `*Contract*Test.java` or `*Contract*IT.java`
- API contract verification
- Security contract tests
- Governance contract tests

---

## Tests by Test Suite Category

| Suite | Count | Purpose |
|-------|-------|---------|
| modules | 234 | Core business logic tests |
| truthsuite | 93 | Canonical behavior and runtime coverage |
| core | 36 | Core infrastructure tests |
| codered | 35 | Critical bug fixes and regression prevention |
| e2e | 29 | End-to-end workflow tests |
| regression | 17 | Regression test suite |
| orchestrator | 11 | Event orchestration tests |
| shared | 4 | Shared DTO tests |
| smoke | 2 | Application health smoke tests |
| performance | 2 | Performance budget tests |
| invariants | 2 | ERP invariant assertions |

---

## Test Quality Issues

### 1. @Disabled Tests

| File | Reason |
|------|--------|
| `e2e/fullcycle/FullCycleE2ETest.java` | Incomplete test implementation - needs proper setup |

### 2. Overly Complex Tests (>500 lines)

| File | Lines | Issue |
|------|-------|-------|
| `modules/accounting/service/AccountingServiceTest.java` | 14,516 | **Refactor into smaller test classes** |
| `modules/sales/service/SalesServiceTest.java` | 4,575 | **Refactor into smaller test classes** |
| `modules/sales/service/SalesReturnServiceTest.java` | 4,056 | **Refactor into smaller test classes** |
| `modules/auth/service/PasswordResetServiceTest.java` | 2,217 | Consider splitting |
| `invariants/ErpInvariantsSuiteIT.java` | 2,146 | Large invariant suite - acceptable |
| `modules/accounting/service/AccountingAuditTrailServiceTest.java` | 1,971 | Consider splitting |
| `modules/inventory/service/FinishedGoodsReservationEngineTest.java` | 1,899 | Consider splitting |
| `modules/invoice/service/InvoiceServiceTest.java` | 1,873 | Consider splitting |
| `modules/inventory/service/FinishedGoodsServiceTest.java` | 1,814 | Consider splitting |
| `modules/inventory/service/OpeningStockImportServiceTest.java` | 1,761 | Consider splitting |

### 3. Tests with Hardcoded Values

- **878 occurrences** of hardcoded values detected across test files
- Common patterns: `companyId = 1L`, `userId = 1L`, `LocalDate.of(202...`, `"test-"` prefixes
- **Recommendation**: Migrate to test fixtures using `TestDataSeeder` and `CanonicalErpDataset`

---

## Tests to Delete

| File | Reason |
|------|--------|
| `e2e/fullcycle/FullCycleE2ETest.java` | @Disabled - incomplete implementation, consider completing or removing |

---

## Tests to Rewrite

| File | Reason | Priority |
|------|--------|----------|
| `modules/accounting/service/AccountingServiceTest.java` | Too large (14K+ lines), split into focused test classes | High |
| `modules/sales/service/SalesServiceTest.java` | Too large (4.5K+ lines), split by feature | High |
| `modules/sales/service/SalesReturnServiceTest.java` | Too large (4K+ lines), split into focused tests | Medium |
| `e2e/fullcycle/FullCycleE2ETest.java` | Incomplete, needs proper setup or removal | Medium |

---

## Coverage Gaps

### Modules with Few/No Tests

| Module | Source Files | Tests | Gap | Priority |
|--------|-------------|-------|-----|----------|
| invoice | 14 | 4 | **Critical** - Only 29% test ratio | High |
| portal | 9 | 3 | **Critical** - Only 33% test ratio | High |
| orchestrator/workflow | 1 | 0 | No tests | High |
| orchestrator/policy | 1 | 0 | No tests | Medium |
| orchestrator/integration | 1 | 0 | No tests | Medium |
| core/validation | 1 | 0 | No tests | Low |
| core/mapper | 1 | 0 | No tests | Low |
| modules/demo | 1 | 0 | Acceptable (demo only) | N/A |

### Critical Services Without Tests

| Service | Module | Risk |
|---------|--------|------|
| InvoiceGenerationEngine | invoice | High |
| InvoiceNumberSequenceService | invoice | Medium |
| PortalAccessService | portal | Medium |
| PortalDashboardService | portal | Medium |
| WorkflowStateService | orchestrator/workflow | Medium |
| PolicyEvaluationService | orchestrator/policy | Medium |

### Missing Edge Case Coverage

- Invoice void and reversal edge cases
- Multi-company portal access scenarios
- Orchestrator workflow state machine transitions
- Period close with pending settlements
- GST rounding edge cases (partially covered in `TS_GstRoundingDeterminismContractTest`)

---

## Test Infrastructure

### Test Base Classes

| Class | Purpose |
|-------|---------|
| `AbstractIntegrationTest` | Base class for all integration tests with Testcontainers |
| `TestDataSeeder` | Utility for creating test data consistently |
| `CanonicalErpDataset` | Standardized test data builder |
| `CanonicalErpDatasetBuilder` | Fluent builder for test datasets |
| `TestDateUtils` | Date handling utilities for tests |
| `TotpTestUtils` | MFA/TOTP testing utilities |

### Test Profiles

- `@ActiveProfiles("test")` - Standard test configuration
- PostgreSQL via Testcontainers (or external DB via env vars)
- Flyway v2 migrations enabled
- RabbitMQ auto-config disabled for tests

### CI Gate Scripts

| Script | Purpose |
|--------|---------|
| `scripts/gate_fast.sh` | Quick pre-commit validation |
| `scripts/gate_core.sh` | Core functionality including O2C E2E |
| `scripts/gate_release.sh` | Full release validation |
| `scripts/gate_reconciliation.sh` | Reconciliation-specific tests |
| `scripts/gate_quality.sh` | Deep quality analysis |

---

## Recommended Test Improvements

### High Priority

1. **Split Large Test Classes**
   - `AccountingServiceTest.java` → Split by feature (JournalEntry, Settlement, PeriodClose, etc.)
   - `SalesServiceTest.java` → Split by feature (OrderCrud, OrderLifecycle, Dispatch, etc.)

2. **Add Invoice Module Tests**
   - Add `InvoiceGenerationEngineTest.java`
   - Add `InvoiceNumberServiceTest.java`
   - Add `InvoiceVoidAndReversalTest.java`

3. **Add Portal Module Tests**
   - Add `PortalAccessServiceTest.java`
   - Add `PortalDashboardServiceTest.java`

### Medium Priority

4. **Migrate to Fixtures**
   - Replace hardcoded IDs with `TestDataSeeder` methods
   - Use `CanonicalErpDatasetBuilder` for complex scenarios

5. **Add Orchestrator Coverage**
   - Add `WorkflowStateServiceTest.java`
   - Add `PolicyEvaluationServiceTest.java`

### Low Priority

6. **Complete or Remove Disabled Tests**
   - Either complete `FullCycleE2ETest.java` or remove it

7. **Add Edge Case Tests**
   - Multi-company scenarios
   - Concurrent operations
   - Period boundary conditions

---

## Test Naming Conventions

| Pattern | Type | Example |
|---------|------|---------|
| `*Test.java` | Unit/Service test | `AccountingServiceTest.java` |
| `*IT.java` | Integration test | `JournalEntryE2ETest.java` |
| `TS_*Test.java` | Truth Suite test | `TS_O2CDispatchCanonicalPostingTest.java` |
| `CR_*Test.java` | Code Red (critical fix) test | `CR_ManualJournalSafetyTest.java` |

---

## Appendix: Full Test Count by Package

```
modules/accounting       51 tests
modules/sales           31 tests
modules/inventory       30 tests
modules/auth            23 tests
modules/purchasing      16 tests
modules/reports         15 tests
modules/company         15 tests
modules/hr              12 tests
modules/factory         10 tests
modules/production       9 tests
modules/admin           11 tests
modules/rbac             5 tests
modules/invoice          4 tests
modules/portal           3 tests
truthsuite/runtime      48 tests
truthsuite/o2c           8 tests
truthsuite/p2p          10 tests
truthsuite/accounting    7 tests
truthsuite/reports       3 tests
truthsuite/other        17 tests
core/*                  36 tests
codered                 35 tests
e2e/*                   29 tests
regression              17 tests
orchestrator            11 tests
```

---

## Related Documents

- [MASTER_INDEX.md](./MASTER_INDEX.md) - Overall codebase index
- [DOMAIN_INVARIANTS.md](./DOMAIN_INVARIANTS.md) - Business invariants
- [ERROR_CODE_CATALOG.md](./ERROR_CODE_CATALOG.md) - Error codes reference
