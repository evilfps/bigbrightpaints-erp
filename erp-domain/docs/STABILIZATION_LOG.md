# Stabilization Log

## 2026-01-02
- Scope: baseline inventory, dedupe identification, OpenAPI tagging, alias deprecations.
- Assumptions (defaults if unknown): Postgres 14+, Flyway previously applied in at least one env, JWT auth, all major modules are critical.
- Detected: Java 21 (pom.xml), Spring Boot 3.3.4 (pom.xml), JWT auth (SecurityConfig), Flyway enabled.
- Commands run:
  - `find src/main/java -maxdepth 4 -type d`
  - `rg --files -g '*Controller.java' src/main/java`
  - `rg -n "@Entity" src/main/java`
  - `rg -n "extends JpaRepository" src/main/java`
  - `ls src/main/resources/db/migration`
  - `python3` inventory scripts for endpoint list and Flyway duplicate detection
  - `mvn test`
  - `mvn package`
  - `sudo -n apt-get update`
- Artifacts created:
  - `docs/endpoint_inventory.tsv` (endpoint inventory snapshot)
  - `docs/API_NOTES.md` regenerated after inventory parsing improvements
- Findings logged:
  - Duplicate/alias endpoints (dealers, HR payroll runs, orchestrator dispatch alias).
  - Duplicate Flyway table/index creation guarded by `IF NOT EXISTS` / DO blocks.
  - Payroll DTO duplication (service-level vs dto package) noted for follow-up.
  - Endpoint inventory parser updated to capture complex generic return types.
- Code changes:
  - Added OpenAPI tagging customizer to group endpoints by major modules.
  - Marked legacy alias endpoints as deprecated for OpenAPI visibility.
- Validation:
  - `mvn test` failed: `JAVA_HOME` not set (Java runtime not available).
  - `mvn package` failed: `JAVA_HOME` not set (Java runtime not available).
  - Boot/migration validation blocked until Java is installed/configured.
- Environment:
  - Java runtime not found in PATH; `sudo` requires password for installing packages.
- Risks/unknowns:
  - Tests rely on Testcontainers (Docker required).
  - RabbitMQ/Kafka/SMTP endpoints may be required for full boot beyond health.
  - Existing DBs may already have Flyway applied; strategy documented in `docs/FLYWAY_AUDIT_AND_STRATEGY.md`.

## 2026-01-02 (continued)
- Commands run:
  - `docker run --rm --network host -v "/mnt/c/Users/ASUS/Downloads/CLI BACKEND":/workspace -w /workspace/erp-domain -v /var/run/docker.sock:/var/run/docker.sock -e TESTCONTAINERS_RYUK_DISABLED=true -e TESTCONTAINERS_HOST_OVERRIDE=host.docker.internal maven:3.9.9-eclipse-temurin-21 mvn test`
  - `docker run --rm --network host -v "/mnt/c/Users/ASUS/Downloads/CLI BACKEND":/workspace -w /workspace/erp-domain -v /var/run/docker.sock:/var/run/docker.sock -e TESTCONTAINERS_RYUK_DISABLED=true -e TESTCONTAINERS_HOST_OVERRIDE=host.docker.internal maven:3.9.9-eclipse-temurin-21 mvn -DskipTests package`
- Failures observed:
  - `PeriodCloseLockIT.closeLockReopenFlow` failed on month-start runs: journal entry rejected with "Entry date cannot be in the future" when seeding day 5/6.
- Fixes:
  - `PeriodCloseLockIT` now uses safe day-of-month for seeding journals to avoid future-date validation failures at month start.
- Validation:
  - `mvn test` (Docker/Testcontainers) succeeded: Tests run: 174, Failures: 0, Errors: 0, Skipped: 4.
  - `mvn test` emitted a surefire warning about a dumpstream (corrupted channel) but did not fail.
  - `mvn -DskipTests package` succeeded; artifact built at `target/erp-domain-0.1.0-SNAPSHOT.jar`.
- Environment:
  - Docker/Testcontainers used with Ryuk disabled and host override for container connectivity.

## 2026-01-02 (boot verification)
- Commands run:
  - `JWT_SECRET=... ERP_SECURITY_ENCRYPTION_KEY=... docker compose up -d --build`
  - `docker exec -i erp_db psql -U erp -d erp_domain -c "select id, code, name from accounts order by id limit 5;"`
  - `docker exec -i erp_db psql -U erp -d erp_domain -c "select id, code, name, type from accounts where type in ('COGS','EXPENSE') order by id limit 5;"`
  - `SPRING_PROFILES_ACTIVE=dev JWT_SECRET=... ERP_SECURITY_ENCRYPTION_KEY=... ERP_DISPATCH_DEBIT_ACCOUNT_ID=6 ERP_DISPATCH_CREDIT_ACCOUNT_ID=3 docker compose up -d --no-deps --force-recreate app`
  - `curl http://localhost:9090/actuator/health`
- Validation:
  - App boots under Docker Compose using `SPRING_PROFILES_ACTIVE=dev` with DB + Rabbit containers.
  - Health endpoint returned `UP` with `dispatchMapping` configured (`debitAccountId=6`, `creditAccountId=3`).
- Notes:
  - Production profile health remained `DOWN` when dispatch mapping IDs were unset; readiness group in prod expects dispatch mapping configuration.

## 2026-01-02 (bug hunt: calculations + cross-module)
- Findings:
  - `modules/sales/service/SalesService.java`: ORDER_TOTAL + GST inclusive can mis-round totals. `taxTotal` is recomputed from rounded `subtotal`, while line-level GST is distributed using `targetTax`. Example: subtotal 100, GST 18% -> base 84.75, targetTax 15.25, recomputed taxTotal 15.26, total 100.01. This can make order total differ from line totals.
  - `modules/inventory/controller/DispatchController.java`: `/api/v1/dispatch/confirm` calls `SalesService.confirmDispatch` (which already invokes `FinishedGoodsService.confirmDispatch`) and then calls `FinishedGoodsService.confirmDispatch` again. It is currently idempotent but duplicates cross-module work and risks divergence if inventory confirmation behavior changes.
  - `modules/hr/service/PayrollService.java` vs `modules/hr/service/PayrollCalculationService.java`: advance deduction caps differ (20% vs 50%) and PF deduction is only applied in `PayrollService`. Preview vs run totals can drift.
  - `modules/hr/service/PayrollService.java`: accounting posting credits salary payable as `gross - PF`, ignoring advance deductions. Net pay is lower, but no clearing entry is created for advances, so payable can be overstated.
  - `modules/inventory/service/FinishedGoodsService.java`: `getDispatchPreview` multiplies by `line.getUnitCost()` without null guard; if legacy batches lack unit cost, preview can throw NPE.

## 2026-01-02 (payroll alignment)
- Changes:
  - Removed PF deductions from attendance-based payroll; advance deductions now use a consistent 20% cap in both `PayrollService` and `PayrollCalculationService`.
  - Payroll posting now books net pay (gross minus advances) to expense + salary payable; advances are cleared in HR on mark-as-paid.
  - Payroll advance clearing now uses `PayrollRunLine.getAdvances()` to cover legacy lines.

## 2026-01-02 (dispatch flow)
- Changes:
  - `/api/v1/dispatch/confirm` now relies on `SalesService.confirmDispatch` as the single inventory mutation path.
  - Added `FinishedGoodsService.getDispatchConfirmation` to return the slip state without re-running inventory updates.

## 2026-01-02 (dispatch flow verification + inventory fix)
- Findings:
  - `GET /api/v1/dispatch/order/{orderId}` failed with `LazyInitializationException` when comparing slip lines to order items because the order was loaded without items outside a session.
  - Dispatch confirm blocked on missing company default accounts in mock profile (`BUS_004`).
- Fixes:
  - Load sales orders with items in `FinishedGoodsService.getPackagingSlip` and `getPackagingSlipByOrder` before calling `slipLinesMatchOrder` and refresh logic.
- Commands run:
  - `SPRING_PROFILES_ACTIVE=mock ... docker compose up -d --build`
  - `curl http://localhost:9090/actuator/health`
  - Mock dispatch flow (login -> set default accounts -> order -> slip -> dispatch -> invoice) via `/api/v1/*` endpoints.
- Validation:
  - Health endpoint returned `UP` (dispatch mapping WARN due to unset debit/credit IDs).
  - Dispatch flow completed: order `7` -> slip `7` -> `DISPATCHED` -> invoice `7`.
  - Default accounts configured via `/api/v1/accounting/default-accounts` using mock account codes `INV`, `COGS`, `REV`, `DISC`, `GST-OUT`.
  - Focused integration tests via Docker/Testcontainers:
    - `CriticalAccountingAxesIT`: Tests run 7, Failures 0, Errors 0.
    - `DispatchConfirmationIT`: Tests run 2, Failures 0, Errors 0.
    - `FactoryPackagingCostingIT`: Tests run 1, Failures 0, Errors 0.
    - Overall: Tests run 10, Failures 0, Errors 0.
  - Surefire warning: corrupted channel dump recorded at `erp-domain/target/surefire-reports/2026-01-02T07-32-33_997-jvmRun1.dumpstream` (build still succeeded).
- Notes:
  - Test logs include repeated "Invalid company ID format" and "Unusual negative balance" warnings from accounting checks; tests still passed.

## Final Deliverable Summary
- Broken/fixed: Period close flow tests stabilized for month-start dates; dispatch slip lookup now loads order items to avoid lazy-load failures.
- Canonical implementations chosen:
  - Dealers: `/api/v1/dealers` (alias `/api/v1/sales/dealers` deprecated).
  - Payroll runs: `/api/v1/payroll/runs` (legacy `/api/v1/hr/payroll-runs` deprecated).
  - Orchestrator dispatch: `/api/v1/orchestrator/dispatch` (alias with `{orderId}` deprecated).
- Duplicate clusters resolved: alias endpoints marked deprecated; OpenAPI tags added by module for consistent grouping.
- Flyway status: 91 migrations; duplicate table/index creations are guarded; no rewrites performed.
- How to deploy: see `docs/DEPLOY_CHECKLIST.md`; requires Java 21 and configured DB credentials.
- Remaining risks: prod profile readiness stays `DOWN` unless dispatch mapping IDs are configured; accounting tests emit noisy warnings under Testcontainers (dumpstream warning present).

## 2026-01-02 (epic-01 kickoff)
- Assumption: `AGENTS.md` not found in repo root; used `.history/AGENTS.md` for async execution rules.
- Added accounting model + posting contract reference documentation.

## 2026-01-02 (epic-01 M1 verification)
- Commands run:
  - `docker run --rm -v "/mnt/c/Users/ASUS/Downloads/CLI BACKEND":/workspace -w /workspace/erp-domain maven:3.9.9-eclipse-temurin-21 mvn -DskipTests compile`
  - `docker run --rm -v "/mnt/c/Users/ASUS/Downloads/CLI BACKEND":/workspace -w /workspace/erp-domain maven:3.9.9-eclipse-temurin-21 mvn -Dcheckstyle.failOnViolation=false checkstyle:check`
  - `docker run --rm --network host -v "/mnt/c/Users/ASUS/Downloads/CLI BACKEND":/workspace -w /workspace/erp-domain -v /var/run/docker.sock:/var/run/docker.sock -e TESTCONTAINERS_RYUK_DISABLED=true -e TESTCONTAINERS_HOST_OVERRIDE=host.docker.internal maven:3.9.9-eclipse-temurin-21 mvn test`
- Validation:
  - `mvn -DskipTests compile` succeeded via Docker.
  - Checkstyle reported 28879 violations; `failOnViolation=false` used to surface baseline warnings without failing.
  - `mvn test` (Docker/Testcontainers) succeeded: Tests run 179, Failures 0, Errors 0, Skipped 4.
  - Surefire warning: corrupted channel dump recorded at `erp-domain/target/surefire-reports/2026-01-02T14-21-47_658-jvmRun1.dumpstream` (build succeeded).

## 2026-01-02 (epic-01 M2 verification)
- Changes:
  - Added reversal guard tests for locked and closed accounting periods in `AccountingService`.
- Commands run:
  - `docker run --rm -v "/mnt/c/Users/ASUS/Downloads/CLI BACKEND":/workspace -w /workspace/erp-domain maven:3.9.9-eclipse-temurin-21 mvn -DskipTests compile`
  - `docker run --rm -v "/mnt/c/Users/ASUS/Downloads/CLI BACKEND":/workspace -w /workspace/erp-domain maven:3.9.9-eclipse-temurin-21 mvn -Dcheckstyle.failOnViolation=false checkstyle:check`
  - `docker run --rm --network host -v "/mnt/c/Users/ASUS/Downloads/CLI BACKEND":/workspace -w /workspace/erp-domain -v /var/run/docker.sock:/var/run/docker.sock -e TESTCONTAINERS_RYUK_DISABLED=true -e TESTCONTAINERS_HOST_OVERRIDE=host.docker.internal maven:3.9.9-eclipse-temurin-21 mvn test`
- Validation:
  - `mvn -DskipTests compile` succeeded via Docker.
  - Checkstyle reported 28879 violations; `failOnViolation=false` used to surface baseline warnings without failing.
  - `mvn test` (Docker/Testcontainers) succeeded: Tests run 181, Failures 0, Errors 0, Skipped 4.
  - Surefire warning: corrupted channel dump recorded at `erp-domain/target/surefire-reports/2026-01-02T14-40-29_972-jvmRun1.dumpstream` (build succeeded).
- Notes:
  - Test logs include repeated "Invalid company ID format" and "Unusual negative balance" warnings plus openhtmltopdf CSS warnings; tests still passed.

## 2026-01-02 (epic-01 M3 verification)
- Changes:
  - Ensured invoice and orchestrator postings persist the sales journal entry ID on the originating order when missing.
  - Kept payroll runs aligned by setting both `journalEntryId` and `journalEntry` references where postings occur; added DTO fallback to use stored IDs.
  - Added payroll run journal link backfill migration and regression tests covering invoice/payroll journal links.
- Commands run:
  - `docker run --rm -v "/mnt/c/Users/ASUS/Downloads/CLI BACKEND":/workspace -w /workspace/erp-domain maven:3.9.9-eclipse-temurin-21 mvn -DskipTests compile`
  - `docker run --rm -v "/mnt/c/Users/ASUS/Downloads/CLI BACKEND":/workspace -w /workspace/erp-domain maven:3.9.9-eclipse-temurin-21 mvn -Dcheckstyle.failOnViolation=false checkstyle:check`
  - `docker run --rm --network host -v "/mnt/c/Users/ASUS/Downloads/CLI BACKEND":/workspace -w /workspace/erp-domain -v /var/run/docker.sock:/var/run/docker.sock -e TESTCONTAINERS_RYUK_DISABLED=true -e TESTCONTAINERS_HOST_OVERRIDE=host.docker.internal maven:3.9.9-eclipse-temurin-21 mvn test`
- Validation:
  - `mvn -DskipTests compile` succeeded via Docker.
  - Checkstyle reported 28892 violations; `failOnViolation=false` used to surface baseline warnings without failing.
  - `mvn test` (Docker/Testcontainers) succeeded: Tests run 183, Failures 0, Errors 0, Skipped 4.
  - Surefire warning: corrupted channel dump recorded at `erp-domain/target/surefire-reports/2026-01-02T15-34-32_253-jvmRun1.dumpstream` (build succeeded).
- Notes:
  - Test logs include repeated "Invalid company ID format", "Unusual negative balance", and openhtmltopdf CSS warnings; tests still passed.

## 2026-01-02 (epic-01 M4 verification)
- Changes:
  - Strengthened journal reversal E2E coverage; cascade reversals now skip reversal entries to avoid reversing the reversal.
- Commands run:
  - `docker run --rm --network host -v "/mnt/c/Users/ASUS/Downloads/CLI BACKEND":/workspace -w /workspace/erp-domain -v /var/run/docker.sock:/var/run/docker.sock -e TESTCONTAINERS_RYUK_DISABLED=true -e TESTCONTAINERS_HOST_OVERRIDE=host.docker.internal maven:3.9.9-eclipse-temurin-21 mvn -DskipTests compile`
  - `docker run --rm --network host -v "/mnt/c/Users/ASUS/Downloads/CLI BACKEND":/workspace -w /workspace/erp-domain maven:3.9.9-eclipse-temurin-21 mvn -Dcheckstyle.failOnViolation=false checkstyle:check`
  - `docker run --rm --network host -v "/mnt/c/Users/ASUS/Downloads/CLI BACKEND":/workspace -w /workspace/erp-domain -v /var/run/docker.sock:/var/run/docker.sock -e TESTCONTAINERS_RYUK_DISABLED=true -e TESTCONTAINERS_HOST_OVERRIDE=host.docker.internal maven:3.9.9-eclipse-temurin-21 mvn test`
- Validation:
  - `mvn -DskipTests compile` succeeded via Docker.
  - Checkstyle reported 28900 violations; `failOnViolation=false` used to surface baseline warnings without failing.
  - `mvn test` (Docker/Testcontainers) succeeded: Tests run 184, Failures 0, Errors 0, Skipped 4.
  - Surefire warning: corrupted channel dump recorded at `erp-domain/target/surefire-reports/2026-01-02T16-08-04_972-jvmRun1.dumpstream` (build succeeded).
- Notes:
  - Test logs include repeated "Invalid company ID format", "Unusual negative balance", and openhtmltopdf CSS warnings plus font cache rebuild; tests still passed.

## 2026-01-02 (epic-01 M5 verification)
- Changes:
  - Added accounting performance indexes for journal, ledger, and event lookups.
- Commands run:
  - `docker run --rm --network host -v "/mnt/c/Users/ASUS/Downloads/CLI BACKEND":/workspace -w /workspace/erp-domain -v /var/run/docker.sock:/var/run/docker.sock -e TESTCONTAINERS_RYUK_DISABLED=true -e TESTCONTAINERS_HOST_OVERRIDE=host.docker.internal maven:3.9.9-eclipse-temurin-21 mvn -DskipTests compile`
  - `docker run --rm --network host -v "/mnt/c/Users/ASUS/Downloads/CLI BACKEND":/workspace -w /workspace/erp-domain maven:3.9.9-eclipse-temurin-21 mvn -Dcheckstyle.failOnViolation=false checkstyle:check`
  - `docker run --rm --network host -v "/mnt/c/Users/ASUS/Downloads/CLI BACKEND":/workspace -w /workspace/erp-domain -v /var/run/docker.sock:/var/run/docker.sock -e TESTCONTAINERS_RYUK_DISABLED=true -e TESTCONTAINERS_HOST_OVERRIDE=host.docker.internal maven:3.9.9-eclipse-temurin-21 mvn test`
- Validation:
  - `mvn -DskipTests compile` succeeded via Docker.
  - Checkstyle reported 28900 violations; `failOnViolation=false` used to surface baseline warnings without failing.
  - `mvn test` (Docker/Testcontainers) succeeded: Tests run 184, Failures 0, Errors 0, Skipped 4.
  - Surefire warning: corrupted channel dump recorded at `erp-domain/target/surefire-reports/2026-01-02T16-25-27_292-jvmRun1.dumpstream` (build succeeded).

## 2026-01-02 (epic-01 final verification)
- Commands run:
  - `docker run --rm --network host -v "/mnt/c/Users/ASUS/Downloads/CLI BACKEND":/workspace -w /workspace/erp-domain -v /var/run/docker.sock:/var/run/docker.sock -e TESTCONTAINERS_RYUK_DISABLED=true -e TESTCONTAINERS_HOST_OVERRIDE=host.docker.internal maven:3.9.9-eclipse-temurin-21 mvn -DskipTests compile`
  - `docker run --rm --network host -v "/mnt/c/Users/ASUS/Downloads/CLI BACKEND":/workspace -w /workspace/erp-domain maven:3.9.9-eclipse-temurin-21 mvn -Dcheckstyle.failOnViolation=false checkstyle:check`
  - `docker run --rm --network host -v "/mnt/c/Users/ASUS/Downloads/CLI BACKEND":/workspace -w /workspace/erp-domain -v /var/run/docker.sock:/var/run/docker.sock -e TESTCONTAINERS_RYUK_DISABLED=true -e TESTCONTAINERS_HOST_OVERRIDE=host.docker.internal maven:3.9.9-eclipse-temurin-21 mvn test`
- Validation:
  - `mvn -DskipTests compile` succeeded via Docker.
  - Checkstyle reported 28900 violations; `failOnViolation=false` used to surface baseline warnings without failing.
  - `mvn test` (Docker/Testcontainers) succeeded: Tests run 184, Failures 0, Errors 0, Skipped 4.
  - Surefire warning: corrupted channel dump recorded at `erp-domain/target/surefire-reports/2026-01-02T16-38-26_271-jvmRun1.dumpstream` (build succeeded).
- Notes:
  - Test logs include repeated "Invalid company ID format", "Unusual negative balance", and openhtmltopdf CSS warnings plus font cache rebuild; tests still passed.

## 2026-01-02 (epic-02 kickoff)
- Assumption: `AGENTS.md` not found in repo root; used `.history/AGENTS.md` for async execution rules.
- Milestones (task-02): M1 document O2C state machines; M2 verify linking across order/dispatch/invoice/journal/ledger;
  M3 audit idempotency; M4 lock rounding/totals; M5 golden-path E2E assertions; M6 reversal/exception coverage.
- Verification gates: Dockerized `mvn -DskipTests compile`, `mvn -Dcheckstyle.failOnViolation=false checkstyle:check`,
  full `mvn test` (Testcontainers) covering invariants/golden paths.

## 2026-01-02 (epic-02 M1 verification)
- Changes:
  - Documented O2C state machines and added `PARTIAL` packaging slip status for partial dispatch behavior.
- Commands run:
  - `docker run --rm -v "/mnt/c/Users/ASUS/Downloads/CLI BACKEND":/workspace -w /workspace/erp-domain maven:3.9.9-eclipse-temurin-21 mvn -DskipTests compile`
  - `docker run --rm -v "/mnt/c/Users/ASUS/Downloads/CLI BACKEND":/workspace -w /workspace/erp-domain maven:3.9.9-eclipse-temurin-21 mvn -Dcheckstyle.failOnViolation=false checkstyle:check`
  - `docker run --rm --network host -v "/mnt/c/Users/ASUS/Downloads/CLI BACKEND":/workspace -w /workspace/erp-domain -v /var/run/docker.sock:/var/run/docker.sock -e TESTCONTAINERS_RYUK_DISABLED=true -e TESTCONTAINERS_HOST_OVERRIDE=host.docker.internal maven:3.9.9-eclipse-temurin-21 mvn test`
- Validation:
  - `mvn -DskipTests compile` succeeded via Docker.
  - Checkstyle reported 28900 violations; `failOnViolation=false` used to surface baseline warnings without failing.
  - `mvn test` (Docker/Testcontainers) succeeded: Tests run 184, Failures 0, Errors 0, Skipped 4.
  - Surefire warning: corrupted channel dump recorded at `erp-domain/target/surefire-reports/2026-01-02T17-53-55_033-jvmRun1.dumpstream` (build succeeded).
- Notes:
  - Test logs include expected sequence contention, "Invalid company ID format"/"Unusual negative balance" warnings,
    and openhtmltopdf font cache rebuild; tests still passed.

## 2026-01-03 (epic-02 M2 verification)
- Changes:
  - Synced dealer ledger entries with invoice metadata (invoice number, due date, payment status, paid amount/date)
    after invoice issuance and dealer settlements (including idempotent settlement replays).
  - Dealer ledger aging queries now exclude non-invoice entries by requiring `invoiceNumber`.
  - O2C invariant test asserts dealer ledger metadata before and after settlement.
  - Test fixtures use system timezone to avoid future-date validation drift; payroll posting clamps entry date to today
    when payroll period end is in the future.
- Commands run:
  - `mvn -f erp-domain/pom.xml -DskipTests compile`
  - `mvn -f erp-domain/pom.xml "-Dcheckstyle.failOnViolation=false" checkstyle:check`
  - `mvn -f erp-domain/pom.xml test`
- Validation:
  - `mvn -DskipTests compile` succeeded.
  - Checkstyle reported 28963 violations; `failOnViolation=false` used to surface baseline warnings without failing.
  - `mvn test` succeeded: Tests run 184, Failures 0, Errors 0, Skipped 4.
- Notes:
  - Test logs include expected sequence contention, "Invalid company ID format"/"Unusual negative balance" warnings,
    and openhtmltopdf CSS warnings; tests still passed.
  - Assumption: test companies use system timezone to keep `LocalDate.now()` aligned with `CompanyClock` and avoid
    "entry date in the future" validation failures.

## 2026-01-03 (epic-02 M3 verification)
- Changes:
  - Added O2C idempotency replays for order creation, dispatch confirm, and dealer settlement in `ErpInvariantsSuiteIT`.
  - Reloaded company context during subledger reconciliation to avoid detached account references.
- Commands run:
  - `mvn -f erp-domain/pom.xml -DskipTests compile`
  - `mvn -f erp-domain/pom.xml "-Dcheckstyle.failOnViolation=false" checkstyle:check`
  - `mvn -f erp-domain/pom.xml test`
  - `mvn -f erp-domain/pom.xml -Dtest=*Sales* test`
- Validation:
  - `mvn -DskipTests compile` succeeded.
  - Checkstyle reported 28971 violations; `failOnViolation=false` used to surface baseline warnings without failing.
  - `mvn test` succeeded: Tests run 186, Failures 0, Errors 0, Skipped 4.
  - `mvn -Dtest=*Sales* test` succeeded: Tests run 21, Failures 0, Errors 0, Skipped 0.
- Notes:
  - Test logs include expected sequence contention, invalid company ID format warnings, negative balance warnings, and openhtmltopdf CSS warnings; tests still passed.

## 2026-01-03 (epic-02 M6 verification)
- Changes:
  - Added O2C reversal/exception coverage for sales returns and credit notes in `ErpInvariantsSuiteIT`.
  - Dispatch confirmation now respects admin credit-limit override to bypass enforcement checks.
  - Updated `SalesServiceTest` constructor wiring to match current `SalesService` signature.
- Commands run:
  - `JAVA_HOME="$PWD/.tools/jdk-21.0.3+9" PATH="$PWD/.tools/jdk-21.0.3+9/bin:$PATH" mvn -f erp-domain/pom.xml -Dmaven.repo.local="$PWD/.tools/m2" -DskipTests compile`
  - `JAVA_HOME="$PWD/.tools/jdk-21.0.3+9" PATH="$PWD/.tools/jdk-21.0.3+9/bin:$PATH" mvn -f erp-domain/pom.xml -Dmaven.repo.local="$PWD/.tools/m2" "-Dcheckstyle.failOnViolation=false" checkstyle:check`
  - `JAVA_HOME="$PWD/.tools/jdk-21.0.3+9" PATH="$PWD/.tools/jdk-21.0.3+9/bin:$PATH" mvn -f erp-domain/pom.xml -Dmaven.repo.local="$PWD/.tools/m2" test`
  - `JAVA_HOME="$PWD/.tools/jdk-21.0.3+9" PATH="$PWD/.tools/jdk-21.0.3+9/bin:$PATH" mvn -f erp-domain/pom.xml -Dmaven.repo.local="$PWD/.tools/m2" -Dtest=*Sales* test`
- Validation:
  - `mvn -DskipTests compile` succeeded.
  - Checkstyle reported 28968 violations; `failOnViolation=false` used to surface baseline warnings without failing.
  - `mvn test` succeeded: Tests run 187, Failures 0, Errors 0, Skipped 4.
  - `mvn -Dtest=*Sales* test` succeeded: Tests run 21, Failures 0, Errors 0, Skipped 0.
- Notes:
  - Test logs include expected sequence contention, invalid company ID format warnings, negative balance warnings, and openhtmltopdf CSS warnings; tests still passed.

## 2026-01-03 (epic-02 M4 verification)
- Changes:
  - Corrected GST inclusive ORDER_TOTAL rounding to distribute tax from the inclusive base and keep tax totals consistent.
  - Added GST inclusive rounding E2E coverage for per-line and order-total flows.
- Commands run:
  - `mvn -f erp-domain/pom.xml -DskipTests compile`
  - `mvn -f erp-domain/pom.xml "-Dcheckstyle.failOnViolation=false" checkstyle:check`
  - `mvn -f erp-domain/pom.xml test`
  - `mvn -f erp-domain/pom.xml -Dtest=*Sales* test`
- Validation:
  - `mvn -DskipTests compile` succeeded.
  - Checkstyle reported 28971 violations; `failOnViolation=false` used to surface baseline warnings without failing.
  - `mvn test` succeeded: Tests run 186, Failures 0, Errors 0, Skipped 4.
  - `mvn -Dtest=*Sales* test` succeeded: Tests run 21, Failures 0, Errors 0, Skipped 0.
- Notes:
  - Test logs include expected sequence contention, invalid company ID format warnings, negative balance warnings, and openhtmltopdf CSS warnings; tests still passed.

## 2026-01-03 (epic-02 M5 verification)
- Changes:
  - No code changes; confirmed O2C golden-path coverage in `ErpInvariantsSuiteIT` (order -> dispatch -> invoice -> settlement) with inventory, journal, and subledger assertions.
- Commands run:
  - `mvn -f erp-domain/pom.xml -DskipTests compile`
  - `mvn -f erp-domain/pom.xml "-Dcheckstyle.failOnViolation=false" checkstyle:check`
  - `mvn -f erp-domain/pom.xml test`
  - `mvn -f erp-domain/pom.xml -Dtest=*Sales* test`
- Validation:
  - `mvn -DskipTests compile` succeeded.
  - Checkstyle reported 28971 violations; `failOnViolation=false` used to surface baseline warnings without failing.
  - `mvn test` succeeded: Tests run 186, Failures 0, Errors 0, Skipped 4.
  - `mvn -Dtest=*Sales* test` succeeded: Tests run 21, Failures 0, Errors 0, Skipped 0.
- Notes:
  - Test logs include expected sequence contention, invalid company ID format warnings, negative balance warnings, and openhtmltopdf CSS warnings; tests still passed.

## 2026-01-04 (epic-04 M1 verification)
- Changes:
  - Documented Procure-to-Pay state machines in `erp-domain/docs/PROCURE_TO_PAY_STATE_MACHINES.md`.
  - Restored missing inventory reference and repository query methods used by dashboards and reports.
  - Aligned P2P invariant request payload with DTO (removed unsupported taxAmount) and made period close E2E dates non-future.
- Commands run:
  - `mvn -f erp-domain/pom.xml -DskipTests compile`
  - `mvn -f erp-domain/pom.xml -Dcheckstyle.failOnViolation=false checkstyle:check`
  - `mvn -f erp-domain/pom.xml test`
  - `mvn -f erp-domain/pom.xml -Dtest=*Purchasing* test`
- Validation:
  - `mvn -DskipTests compile` succeeded.
  - Checkstyle reported 28780 violations; `failOnViolation=false` used to surface baseline warnings without failing.
  - `mvn test` succeeded: Tests run 187, Failures 0, Errors 0, Skipped 4.
  - `mvn -Dtest=*Purchasing* test` succeeded: Tests run 4, Failures 0, Errors 0, Skipped 0.
- Notes:
  - Test logs include expected invalid company ID format warnings, negative balance warnings, and dynamic agent warnings; tests still passed.

## 2026-01-04 (epic-04 M2 verification)
- Changes:
  - Added raw material stock + movement assertions to P2P invariant purchase flow.
  - Added P2P purchase return invariant (isolated company) to verify stock decrement and return movement linkage.
- Commands run:
  - `mvn -f erp-domain/pom.xml -DskipTests compile`
  - `mvn -f erp-domain/pom.xml -Dcheckstyle.failOnViolation=false checkstyle:check`
  - `mvn -f erp-domain/pom.xml test`
  - `mvn -f erp-domain/pom.xml -Dtest=*Purchasing* test`
- Validation:
  - `mvn -DskipTests compile` succeeded.
  - Checkstyle reported 28780 violations; `failOnViolation=false` used to surface baseline warnings without failing.
  - `mvn test` succeeded: Tests run 188, Failures 0, Errors 0, Skipped 4.
  - `mvn -Dtest=*Purchasing* test` succeeded: Tests run 4, Failures 0, Errors 0, Skipped 0.
- Notes:
  - Test logs include expected invalid company ID format warnings, negative balance warnings, and dynamic agent warnings; tests still passed.

## 2026-01-04 (epic-04 M3 verification)
- Changes:
  - Added purchase journal line assertions for inventory/AP in P2P invariants.
  - Added purchase return journal line assertions for AP/inventory credits in P2P return flow.
- Commands run:
  - `mvn -f erp-domain/pom.xml -DskipTests compile`
  - `mvn -f erp-domain/pom.xml -Dcheckstyle.failOnViolation=false checkstyle:check`
  - `mvn -f erp-domain/pom.xml test`
  - `mvn -f erp-domain/pom.xml -Dtest=*Purchasing* test`
- Validation:
  - `mvn -DskipTests compile` succeeded.
  - Checkstyle reported 28780 violations; `failOnViolation=false` used to surface baseline warnings without failing.
  - `mvn test` succeeded: Tests run 188, Failures 0, Errors 0, Skipped 4.
  - `mvn -Dtest=*Purchasing* test` succeeded: Tests run 4, Failures 0, Errors 0, Skipped 0.
- Notes:
  - Test logs include expected invalid company ID format warnings, negative balance warnings, and dynamic agent warnings; tests still passed.

## 2026-01-04 (epic-04 M4 verification)
- Changes:
  - Added supplier statement and aging E2E coverage to reconcile AP ledger activity for purchases and settlements.
  - Shortened supplier/invoice reference inputs to keep purchase journal references within 64 characters.
- Commands run:
  - `mvn -f erp-domain/pom.xml -DskipTests compile`
  - `mvn -f erp-domain/pom.xml -Dcheckstyle.failOnViolation=false checkstyle:check`
  - `mvn -f erp-domain/pom.xml test`
  - `mvn -f erp-domain/pom.xml -Dtest=*Purchasing* test`
- Validation:
  - `mvn -DskipTests compile` succeeded.
  - Checkstyle reported 28780 violations; `failOnViolation=false` used to surface baseline warnings without failing.
  - `mvn test` succeeded: Tests run 189, Failures 0, Errors 0, Skipped 4.
  - `mvn -Dtest=*Purchasing* test` succeeded: Tests run 4, Failures 0, Errors 0, Skipped 0.
- Notes:
  - Test logs include expected invalid company ID format warnings, negative balance warnings, and dynamic agent warnings; tests still passed.

## 2026-01-04 (epic-04 M5 verification)
- Changes:
  - Added Procure-to-Pay E2E coverage for purchase receipt, supplier settlement, and purchase return flows.
- Commands run:
  - `mvn -f erp-domain/pom.xml -DskipTests compile`
  - `mvn -f erp-domain/pom.xml -Dcheckstyle.failOnViolation=false checkstyle:check`
  - `mvn -f erp-domain/pom.xml test`
  - `mvn -f erp-domain/pom.xml -Dtest=*Purchasing* test`
- Validation:
  - `mvn -DskipTests compile` succeeded.
  - Checkstyle reported 28780 violations; `failOnViolation=false` used to surface baseline warnings without failing.
  - `mvn test` succeeded: Tests run 191, Failures 0, Errors 0, Skipped 4.
  - `mvn -Dtest=*Purchasing* test` succeeded: Tests run 4, Failures 0, Errors 0, Skipped 0.
- Notes:
  - Test logs include expected invalid company ID format warnings, negative balance warnings, and dynamic agent warnings; tests still passed.

## 2026-01-04 (epic-04 M6 verification)
- Changes:
  - Added EntityGraph-backed supplier/purchase list queries to reduce N+1 on list endpoints.
  - Added P2P performance indexes for supplier name ordering and purchase history by invoice date.
- Commands run:
  - `mvn -f erp-domain/pom.xml -DskipTests compile`
  - `mvn -f erp-domain/pom.xml -Dcheckstyle.failOnViolation=false checkstyle:check`
  - `mvn -f erp-domain/pom.xml test`
  - `mvn -f erp-domain/pom.xml -Dtest=*Purchasing* test`
- Validation:
  - `mvn -DskipTests compile` succeeded.
  - Checkstyle reported 28785 violations; `failOnViolation=false` used to surface baseline warnings without failing.
  - `mvn test` succeeded: Tests run 191, Failures 0, Errors 0, Skipped 4.
  - `mvn -Dtest=*Purchasing* test` succeeded: Tests run 4, Failures 0, Errors 0, Skipped 0.
- Notes:
  - Test logs include expected invalid company ID format warnings, negative balance warnings, and dynamic agent warnings; tests still passed.

## 2026-01-05 (epic-05 M1 — payroll run states and invariants)
- Changes:
  - Added hire-to-pay state machine and invariants documentation (`HIRE_TO_PAY_STATE_MACHINES.md`), capturing current payroll run statuses, transitions, posting semantics, payment/clearing behavior, and invariants.
- Commands run:
  - `mvn -f erp-domain/pom.xml -DskipTests compile`
  - `mvn -f erp-domain/pom.xml -Dcheckstyle.failOnViolation=false checkstyle:check`
  - `mvn -f erp-domain/pom.xml test`
  - `mvn -f erp-domain/pom.xml -Dtest=*Payroll* test`
- Validation:
  - All commands above succeeded (checkstyle warnings present at baseline; failOnViolation=false).
- Warnings/notes:
  - Checkstyle reports baseline violations (~28k) unrelated to this change.
  - Test logs include expected Testcontainers startup messages and known warnings (invalid company ID format, negative balance notices); no failures.

## 2026-01-05 (epic-05 M2 — payroll calculation sources aligned)
- Changes:
  - Aligned payroll calculations across preview/auto and run flows: attendance-driven computation now matches PayrollService (present/half/holiday/OT handling, 20% advance cap, zero PF in calculation).
  - Auto-created payroll runs now use canonical fields (run type, period start/end, run number, DRAFT status) and populate line/pay totals for traceability.
- Commands run:
  - `mvn -f erp-domain/pom.xml -DskipTests compile`
  - `mvn -f erp-domain/pom.xml -Dcheckstyle.failOnViolation=false checkstyle:check`
  - `mvn -f erp-domain/pom.xml test`
  - `mvn -f erp-domain/pom.xml -Dtest=*Payroll* test`
- Validation:
  - All commands above succeeded (checkstyle warnings remain at baseline; failOnViolation=false).
- Warnings/notes:
  - Baseline checkstyle violations (~28k) unaffected.
  - Test logs contain expected Testcontainers startup chatter and accounting warnings (invalid company ID format, negative balance notices); no test failures.

## 2026-01-06 (epic-05 M3 — payroll posting semantics explicit)
- Changes:
  - Expanded hire-to-pay documentation with explicit gross/net formulas, net-of-advance posting notes, and payment clearing behavior.
- Commands run:
  - `mvn -f erp-domain/pom.xml -DskipTests compile`
  - `mvn -f erp-domain/pom.xml -Dcheckstyle.failOnViolation=false checkstyle:check`
  - `mvn -f erp-domain/pom.xml test`
  - `mvn -f erp-domain/pom.xml -Dtest=*Payroll* test`
- Validation:
  - `mvn -DskipTests compile` succeeded.
  - Checkstyle reported 28857 violations; failOnViolation=false used to surface baseline warnings.
  - `mvn test` succeeded: Tests run 191, Failures 0, Errors 0, Skipped 4.
  - `mvn -Dtest=*Payroll* test` succeeded: Tests run 1, Failures 0, Errors 0, Skipped 0.
- Warnings/notes:
  - Baseline checkstyle violations (~28k) unchanged.
  - Test logs include expected Testcontainers startup chatter, invalid company ID format warnings, negative balance warnings, and dynamic agent warnings; no failures.

## 2026-01-06 (epic-05 M4 — payroll E2E totals + journal assertions)
- Changes:
  - Strengthened hire-to-pay invariants to assert payroll totals, advance deduction, journal lines, and advance balance clearing.
- Commands run:
  - `mvn -f erp-domain/pom.xml -DskipTests compile`
  - `mvn -f erp-domain/pom.xml -Dcheckstyle.failOnViolation=false checkstyle:check`
  - `mvn -f erp-domain/pom.xml test`
  - `mvn -f erp-domain/pom.xml -Dtest=*Payroll* test`
- Validation:
  - `mvn -DskipTests compile` succeeded.
  - Checkstyle reported 28857 violations; failOnViolation=false used to surface baseline warnings.
  - `mvn test` succeeded: Tests run 191, Failures 0, Errors 0, Skipped 4.
  - `mvn -Dtest=*Payroll* test` succeeded: Tests run 1, Failures 0, Errors 0, Skipped 0.
- Warnings/notes:
  - Baseline checkstyle violations (~28k) unchanged.
  - Test logs include expected Testcontainers startup chatter, invalid company ID format warnings, negative balance warnings, and dynamic agent warnings; no failures.

## 2026-01-06 (epic-05 M5 — payroll reversal coverage)
- Changes:
  - Hardened hire-to-pay invariants to sum payroll totals/journal lines and added a weekly payroll reversal scenario to avoid run-number collisions.
- Commands run:
  - `mvn -f erp-domain/pom.xml -DskipTests compile`
  - `mvn -f erp-domain/pom.xml -Dcheckstyle.failOnViolation=false checkstyle:check`
  - `mvn -f erp-domain/pom.xml test`
  - `mvn -f erp-domain/pom.xml -Dtest=*Payroll* test`
- Validation:
  - `mvn -DskipTests compile` succeeded.
  - Checkstyle reported 28857 violations; failOnViolation=false used to surface baseline warnings.
  - `mvn test` succeeded: Tests run 192, Failures 0, Errors 0, Skipped 4.
  - `mvn -Dtest=*Payroll* test` succeeded: Tests run 1, Failures 0, Errors 0, Skipped 0.
- Warnings/notes:
  - Baseline checkstyle violations (~28k) unchanged.
  - Test logs include expected Testcontainers startup chatter, invalid company ID format warnings, negative balance warnings, and dynamic agent warnings; no failures.

## 2026-01-06 (epic-05 M6 — payroll list/run lines/summary performance)
- Changes:
  - Batched payroll summary attendance fetches and added fetch-join payroll run line queries to avoid N+1 lookups.
  - Added payroll run list indexes for company/created_at and company/run_type/created_at (V95__payroll_run_list_indexes.sql).
- Commands run:
  - `mvn -f erp-domain/pom.xml -DskipTests compile`
  - `mvn -f erp-domain/pom.xml -Dcheckstyle.failOnViolation=false checkstyle:check`
  - `mvn -f erp-domain/pom.xml test`
  - `mvn -f erp-domain/pom.xml -Dtest=*Payroll* test`
- Validation:
  - `mvn -DskipTests compile` succeeded (javac warns about javax.annotation.meta.When.MAYBE and deprecated APIs).
  - Checkstyle reported 28861 violations; failOnViolation=false used to surface baseline warnings.
  - `mvn test` succeeded: Tests run 192, Failures 0, Errors 0, Skipped 4.
  - `mvn -Dtest=*Payroll* test` succeeded: Tests run 1, Failures 0, Errors 0, Skipped 0.
- Warnings/notes:
  - Checkstyle baseline now 28861 (previously 28857) due to existing indentation rules in modified HR files.
  - Test logs include expected Testcontainers startup chatter, invalid company ID format warnings, negative balance warnings, and dynamic agent warnings; no failures.

## 2026-01-06 (epic-06 M1 — permission model alignment)
- Changes:
  - Added role permission authorities to `UserPrincipal` and default permission syncing for system roles.
  - Seeded action permissions (dispatch.confirm, factory.dispatch, payroll.run) via Flyway and aligned controller role checks to consolidated roles.
  - Updated ERP invariant base roles and added unit coverage for permission authorities.
- Commands run:
  - `mvn -f erp-domain/pom.xml -DskipTests compile`
  - `mvn -f erp-domain/pom.xml -Dcheckstyle.failOnViolation=false checkstyle:check`
  - `mvn -f erp-domain/pom.xml test`
  - `mvn -f erp-domain/pom.xml -Dtest=*Auth* test`
- Validation:
  - `mvn -DskipTests compile` succeeded (javac warnings about javax.annotation.meta.When.MAYBE, deprecated API notice).
  - Checkstyle reported 28899 violations; `failOnViolation=false` used for baseline visibility.
  - `mvn test` succeeded: Tests run 193, Failures 0, Errors 0, Skipped 4.
  - `mvn -Dtest=*Auth* test` succeeded: Tests run 2, Failures 0, Errors 0, Skipped 0.
- Warnings/notes:
  - Testcontainers emitted auth config warnings and dynamic agent loading notices.
  - Known test logs include invalid company ID format and negative balance warnings; no test failures.
  - Dispatch mapping warning surfaced when debit/credit IDs are unset (expected in tests).

## 2026-01-07 (epic-06 M2 — endpoint exposure audit)
- Changes:
  - Added RBAC guards to production catalog, raw material inventory, and demo endpoints.
  - Explicitly required authentication on profile/MFA flows and authenticated auth endpoints.
- Commands run:
  - `mvn -f erp-domain/pom.xml -DskipTests compile`
  - `mvn -f erp-domain/pom.xml -Dcheckstyle.failOnViolation=false checkstyle:check`
  - `mvn -f erp-domain/pom.xml test`
  - `mvn -f erp-domain/pom.xml -Dtest=*Auth* test`
- Validation:
  - `mvn -DskipTests compile` succeeded (javac warnings about javax.annotation.meta.When.MAYBE, deprecated API notice).
  - Checkstyle reported 28900 violations; `failOnViolation=false` used for baseline visibility.
  - `mvn test` succeeded: Tests run 193, Failures 0, Errors 0, Skipped 4.
  - `mvn -Dtest=*Auth* test` succeeded: Tests run 2, Failures 0, Errors 0, Skipped 0.
- Warnings/notes:
  - Testcontainers auth config warnings and dynamic agent loading notices persisted.
  - Known test logs include invalid company ID format, negative balance warnings, and dispatch mapping warnings; no failures.

## 2026-01-07 (epic-06 M3 — company boundary enforcement)
- Changes:
  - Scoped admin user management to the active company context for list/update/lock operations and validated company assignments.
  - Enforced company scoping in dealer portal lookups, reconciliation cleanup, bulk packing child batch listing, and temporal balance account reads.
  - Added company-aware repository lookups for users, dealers, and inventory reservations.
- Commands run:
  - `mvn -f erp-domain/pom.xml -DskipTests compile`
  - `mvn -f erp-domain/pom.xml -Dcheckstyle.failOnViolation=false checkstyle:check`
  - `mvn -f erp-domain/pom.xml test`
  - `mvn -f erp-domain/pom.xml -Dtest=*Auth* test`
- Validation:
  - `mvn -DskipTests compile` succeeded (javac warnings about javax.annotation.meta.When.MAYBE, deprecated API notice).
  - Checkstyle reported 28933 violations; `failOnViolation=false` used for baseline visibility.
  - `mvn test` succeeded: Tests run 193, Failures 0, Errors 0, Skipped 4.
  - `mvn -Dtest=*Auth* test` succeeded: Tests run 2, Failures 0, Errors 0, Skipped 0.
- Warnings/notes:
  - Testcontainers auth config warnings and dynamic agent loading notices persisted.
  - Test logs include invalid company ID format, negative balance warnings, dispatch mapping warnings, and HTML-to-PDF CSS parse warnings; no failures.

## 2026-01-07 (epic-06 M4 — token lifecycle hardening)
- Changes:
  - Logout now blacklists the current access token and revokes refresh tokens for the active session (or all if missing).
  - Refresh tokens now track issued time and are rejected when user-wide revocation has occurred.
  - Password reset flow now enforces password history rules and revokes active tokens while clearing lockout counters.
- Commands run:
  - `mvn -f erp-domain/pom.xml -DskipTests compile`
  - `mvn -f erp-domain/pom.xml -Dcheckstyle.failOnViolation=false checkstyle:check`
  - `mvn -f erp-domain/pom.xml test`
  - `mvn -f erp-domain/pom.xml -Dtest=*Auth* test`
- Validation:
  - `mvn -DskipTests compile` succeeded (javac warnings about javax.annotation.meta.When.MAYBE, deprecated API notice).
  - Checkstyle reported 28969 violations; `failOnViolation=false` used for baseline visibility.
  - `mvn test` succeeded: Tests run 193, Failures 0, Errors 0, Skipped 4.
  - `mvn -Dtest=*Auth* test` succeeded: Tests run 2, Failures 0, Errors 0, Skipped 0.
- Warnings/notes:
  - Testcontainers auth config warnings and dynamic agent loading notices persisted.
  - Test logs include invalid company ID format, negative balance warnings, dispatch mapping warnings, sequence contention warnings, and HTML-to-PDF CSS parse warnings; no failures.

## 2026-01-07 (epic-06 M5 — audit logging coverage)
- Changes:
  - Added audit events and metadata for dispatch confirmations, settlements, payroll postings, and journal reversals.
  - Logged dispatch/settlement/payroll/reversal actions with slip/order/partner/journal context for traceability.
- Commands run:
  - `mvn -f erp-domain/pom.xml -DskipTests compile`
  - `mvn -f erp-domain/pom.xml -Dcheckstyle.failOnViolation=false checkstyle:check`
  - `mvn -f erp-domain/pom.xml test`
  - `mvn -f erp-domain/pom.xml -Dtest=*Auth* test`
- Validation:
  - `mvn -DskipTests compile` succeeded (javac warnings about javax.annotation.meta.When.MAYBE, deprecated API notice).
  - Checkstyle reported 29110 violations; `failOnViolation=false` used for baseline visibility.
  - `mvn test` succeeded: Tests run 193, Failures 0, Errors 0, Skipped 4.
  - `mvn -Dtest=*Auth* test` succeeded: Tests run 2, Failures 0, Errors 0, Skipped 0.
- Warnings/notes:
  - Testcontainers emitted auth config warnings and dynamic agent loading notices persisted.
  - Test logs include invalid company ID format, negative balance warnings, dispatch mapping warnings, and HTML-to-PDF CSS parse warnings; no failures.

## 2026-01-07 (epic-06 M6 — security regression tests)
- Changes:
  - Added admin security integration coverage for auth-required, role-required, and cross-company blocks.
  - Added refresh-token/logout regression to confirm revoked refresh tokens cannot be reused.
- Commands run:
  - `mvn -f erp-domain/pom.xml -DskipTests compile`
  - `mvn -f erp-domain/pom.xml -Dcheckstyle.failOnViolation=false checkstyle:check`
  - `mvn -f erp-domain/pom.xml test`
  - `mvn -f erp-domain/pom.xml -Dtest=*Auth* test`
- Validation:
  - `mvn -DskipTests compile` succeeded.
  - Checkstyle reported 29110 violations; `failOnViolation=false` used for baseline visibility.
  - `mvn test` succeeded: Tests run 197, Failures 0, Errors 0, Skipped 4.
  - `mvn -Dtest=*Auth* test` succeeded: Tests run 3, Failures 0, Errors 0, Skipped 0.
- Warnings/notes:
  - Testcontainers auth config warnings and dynamic agent loading notices persisted.
  - Test logs include invalid company ID format, negative balance warnings, dispatch mapping warnings, sequence contention warnings, and HTML-to-PDF CSS parse warnings; no failures.

## 2026-01-07 (epic-07 M1 — performance hotspots baseline)
- Changes:
  - Documented hot endpoints, heavy tables, and primary query patterns for Epic 07 performance work.
- Commands run:
  - `mvn -f erp-domain/pom.xml -DskipTests compile`
  - `mvn -f erp-domain/pom.xml -Dcheckstyle.failOnViolation=false checkstyle:check`
  - `mvn -f erp-domain/pom.xml test`
- Validation:
  - `mvn -DskipTests compile` succeeded.
  - Checkstyle reported 29110 violations; `failOnViolation=false` used for baseline visibility.
  - `mvn test` succeeded: Tests run 197, Failures 0, Errors 0, Skipped 4.
- Warnings/notes:
  - Testcontainers auth config warnings and dynamic agent loading notices persisted.
  - Test logs include invalid company ID format, negative balance warnings, dispatch mapping warnings, sequence contention warnings, and HTML-to-PDF CSS parse warnings; no failures.

## 2026-01-07 (epic-07 M2 — measurement instrumentation)
- Changes:
  - Added controller-level timing for journal entries, invoice list endpoints, and sales order list.
  - Added PerformanceExplainIT to log EXPLAIN plans for hot queries (sales orders, invoices, journal lines, outbox, inventory movements, dealer ledger).
- Commands run:
  - `mvn -f erp-domain/pom.xml -DskipTests compile`
  - `mvn -f erp-domain/pom.xml -Dcheckstyle.failOnViolation=false checkstyle:check`
  - `mvn -f erp-domain/pom.xml test`
- Validation:
  - `mvn -DskipTests compile` succeeded.
  - Checkstyle reported 29110 violations; `failOnViolation=false` used for baseline visibility.
  - `mvn test` succeeded: Tests run 198, Failures 0, Errors 0, Skipped 4.
- Warnings/notes:
  - Testcontainers auth config warnings and dynamic agent loading notices persisted.
  - Test logs include invalid company ID format, negative balance warnings, dispatch mapping warnings, sequence contention warnings, and HTML-to-PDF CSS parse warnings; no failures.

## 2026-01-07 (epic-07 M3 — list pagination + index tuning)
- Changes:
  - Added paged list parameters for sales orders and invoices with stable (date desc, id desc) ordering.
  - Switched sales order and invoice list queries to ID paging + fetch to avoid collection-fetch pagination warnings.
  - Added stable paging for journal entry lists (date desc, id desc).
  - Added list-performance indexes for sales orders, invoices, inventory movements, and outbox pending scans.
- Commands run:
  - `mvn -f erp-domain/pom.xml -DskipTests compile`
  - `mvn -f erp-domain/pom.xml -Dcheckstyle.failOnViolation=false checkstyle:check`
  - `mvn -f erp-domain/pom.xml test`
- Validation:
  - `mvn -DskipTests compile` succeeded.
  - Checkstyle reported 29192 violations; `failOnViolation=false` used for baseline visibility.
  - `mvn test` succeeded: Tests run 198, Failures 0, Errors 0, Skipped 4.
  - PerformanceExplainIT logged index scans for sales/invoice lists and inventory movements; outbox pending still used `idx_orchestrator_outbox_status_created`.
- Warnings/notes:
  - Testcontainers auth config warnings and dynamic agent loading notices persisted.
  - Test logs include invalid company ID format, negative balance warnings, dispatch mapping warnings, sequence contention/duplicate key retries, and HTML-to-PDF CSS parse warnings; no failures.

## 2026-01-07 (epic-07 M4 — performance budgets)
- Changes:
  - Defined p95 latency targets for list endpoints and bounded targets for report endpoints.
- Commands run:
  - `mvn -f erp-domain/pom.xml -DskipTests compile`
  - `mvn -f erp-domain/pom.xml -Dcheckstyle.failOnViolation=false checkstyle:check`
  - `mvn -f erp-domain/pom.xml test`
- Validation:
  - `mvn -DskipTests compile` succeeded.
  - Checkstyle reported 29192 violations; `failOnViolation=false` used for baseline visibility.
  - `mvn test` succeeded: Tests run 198, Failures 0, Errors 0, Skipped 4.
- Warnings/notes:
  - Testcontainers auth config warnings and dynamic agent loading notices persisted.
  - Test logs include invalid company ID format, negative balance warnings, dispatch mapping warnings, sequence contention/duplicate key retries, and HTML-to-PDF CSS parse warnings; no failures.

## 2026-01-07 (epic-07 M5 — regression checks)
- Changes:
  - Added PerformanceBudgetIT coverage for list query-count bounds and balance-sheet report timing.
- Commands run:
  - `mvn -f erp-domain/pom.xml -DskipTests compile`
  - `mvn -f erp-domain/pom.xml -Dcheckstyle.failOnViolation=false checkstyle:check`
  - `mvn -f erp-domain/pom.xml test`
- Validation:
  - `mvn -DskipTests compile` succeeded.
  - Checkstyle reported 29192 violations; `failOnViolation=false` used for baseline visibility.
  - `mvn test` succeeded: Tests run 200, Failures 0, Errors 0, Skipped 4.
- Warnings/notes:
  - Testcontainers auth config warnings and dynamic agent loading notices persisted.
  - Test logs include invalid company ID format, negative balance warnings, dispatch mapping warnings, sequence contention/duplicate key retries, and HTML-to-PDF CSS parse warnings; no failures.

## 2026-01-08 (epic-08 M1 — reconciliation contracts)
- Changes:
  - Documented reconciliation contracts for inventory, AR/AP control accounts, and period-close controls.
- Commands run:
  - `mvn -f erp-domain/pom.xml -DskipTests compile`
  - `mvn -f erp-domain/pom.xml -Dcheckstyle.failOnViolation=false checkstyle:check`
  - `mvn -f erp-domain/pom.xml test`
- Validation:
  - `mvn -DskipTests compile` succeeded.
  - Checkstyle reported 29192 violations; `failOnViolation=false` used for baseline visibility.
  - `mvn test` succeeded: Tests run 200, Failures 0, Errors 0, Skipped 4.
- Warnings/notes:
  - Testcontainers auth config warnings, dynamic agent loading notices persisted.
  - Test logs include invalid company ID format, negative balance warnings, dispatch mapping warnings, sequence contention/duplicate key retries, and HTML-to-PDF CSS parse warnings; no failures.

## 2026-01-08 (epic-09 M3 — readiness/health hardening)
- Changes:
  - Added required config health indicator for JWT/encryption/license/mail settings.
  - Added `requiredConfig` to the readiness health group in prod config.
- Commands run:
  - `mvn -f erp-domain/pom.xml -DskipTests compile`
  - `mvn -f erp-domain/pom.xml -Dcheckstyle.failOnViolation=false checkstyle:check`
  - `mvn -f erp-domain/pom.xml test`
  - `DB_PORT=55432 JWT_SECRET=... ERP_SECURITY_ENCRYPTION_KEY=... docker compose up -d --build`
  - `curl --retry 10 --retry-connrefused --retry-delay 5 http://localhost:9090/actuator/health`
- Validation:
  - `mvn -DskipTests compile` succeeded.
  - Checkstyle reported 29436 violations; `failOnViolation=false` used for baseline visibility.
  - `mvn test` succeeded: Tests run 202, Failures 0, Errors 0, Skipped 4.
  - Docker compose boot succeeded with `DB_PORT=55432`; `/actuator/health` returned `{"status":"UP","groups":["liveness","readiness"]}`.
- Warnings/notes:
  - Testcontainers auth config warnings, dynamic agent loading notices persisted.
  - Test logs include invalid company ID format, negative balance warnings, dispatch mapping warnings, sequence contention/duplicate key retries, and HTML-to-PDF CSS parse warnings; no failures.

## 2026-01-08 (epic-09 M4 — outbox reliability)
- Changes:
  - Added outbox retrying metrics/health counts for pending retry events.
  - Documented outbox retry policy and manual replay guidance in deploy checklist.
- Commands run:
  - `mvn -f erp-domain/pom.xml -DskipTests compile`
  - `mvn -f erp-domain/pom.xml -Dcheckstyle.failOnViolation=false checkstyle:check`
  - `mvn -f erp-domain/pom.xml test`
  - `DB_PORT=55432 JWT_SECRET=... ERP_SECURITY_ENCRYPTION_KEY=... docker compose up -d --build`
  - `curl --retry 10 --retry-connrefused --retry-delay 5 http://localhost:9090/actuator/health`
- Validation:
  - `mvn -DskipTests compile` succeeded (javax.annotation warnings and deprecated API notes).
  - Checkstyle reported 29441 violations; `failOnViolation=false` used for baseline visibility.
  - `mvn test` succeeded: Tests run 202, Failures 0, Errors 0, Skipped 4.
  - Docker compose boot succeeded with `DB_PORT=55432`; `/actuator/health` returned `{"status":"UP","groups":["liveness","readiness"]}`.
- Warnings/notes:
  - Initial `/actuator/health` check hit connection reset while the container was still starting; retry succeeded.
  - Testcontainers auth config warnings, dynamic agent loading notices persisted.
  - Test logs include invalid company ID format, negative balance warnings, dispatch mapping warnings, sequence contention/duplicate key retries, and HTML-to-PDF CSS parse warnings; no failures.

## 2026-01-08 (epic-09 M5 — operator smoke checks)
- Changes:
  - Added operator smoke script for health/docs/authenticated profile checks.
  - Documented operator smoke checks in the deploy checklist.
- Commands run:
  - `mvn -f erp-domain/pom.xml -DskipTests compile`
  - `mvn -f erp-domain/pom.xml -Dcheckstyle.failOnViolation=false checkstyle:check`
  - `mvn -f erp-domain/pom.xml test`
  - `DB_PORT=55432 JWT_SECRET=... ERP_SECURITY_ENCRYPTION_KEY=... docker compose up -d --build`
  - `curl --retry 10 --retry-connrefused --retry-delay 5 http://localhost:9090/actuator/health`
- Validation:
  - `mvn -DskipTests compile` succeeded.
  - Checkstyle reported 29441 violations; `failOnViolation=false` used for baseline visibility.
  - `mvn test` succeeded: Tests run 202, Failures 0, Errors 0, Skipped 4.
  - Docker compose boot succeeded with `DB_PORT=55432`; `/actuator/health` returned `{"status":"UP","groups":["liveness","readiness"]}`.
- Warnings/notes:
  - Operator smoke script requires ERP_SMOKE credentials; not executed in this run.
  - Testcontainers auth config warnings, dynamic agent loading notices persisted.
  - Test logs include invalid company ID format, negative balance warnings, dispatch mapping warnings, sequence contention/duplicate key retries, and HTML-to-PDF CSS parse warnings; no failures.

## 2026-01-08 (epic-08 M2 — reconciliation service hardening)
- Changes:
  - Inventory reconciliation now prefers the default inventory control account.
  - Added AP reconciliation against supplier ledger balances.
- Commands run:
  - `mvn -f erp-domain/pom.xml -DskipTests compile`
  - `mvn -f erp-domain/pom.xml -Dcheckstyle.failOnViolation=false checkstyle:check`
  - `mvn -f erp-domain/pom.xml test`
- Validation:
  - `mvn -DskipTests compile` succeeded (javac warnings about missing javax.annotation meta; baseline behavior).
  - Checkstyle reported 29237 violations; `failOnViolation=false` used for baseline visibility.
  - `mvn test` succeeded: Tests run 200, Failures 0, Errors 0, Skipped 4.
- Warnings/notes:
  - Testcontainers auth config warnings, dynamic agent loading notices persisted.
  - Test logs include invalid company ID format, negative balance warnings, dispatch mapping warnings, sequence contention/duplicate key retries, and HTML-to-PDF CSS parse warnings; no failures.

## 2026-01-08 (epic-08 M3 — closing checklist assertions)
- Changes:
  - Added month-end checklist validations for inventory/AR/AP reconciliation variance, unbalanced journals, unposted documents, and missing journal links.
  - Added repository count helpers for invoices, purchases, and payroll runs to surface unposted/unlinked documents.
- Commands run:
  - `mvn -f erp-domain/pom.xml -DskipTests compile`
  - `mvn -f erp-domain/pom.xml -Dcheckstyle.failOnViolation=false checkstyle:check`
  - `mvn -f erp-domain/pom.xml test`
- Validation:
  - `mvn -DskipTests compile` succeeded (javac warnings about missing javax.annotation meta; deprecated API warnings present).
  - Checkstyle reported 29375 violations; `failOnViolation=false` used for baseline visibility.
  - `mvn test` succeeded: Tests run 200, Failures 0, Errors 0, Skipped 4.
- Warnings/notes:
  - Testcontainers auth config warnings, dynamic agent loading notices persisted.
  - Test logs include invalid company ID format, negative balance warnings, dispatch mapping warnings, sequence contention/duplicate key retries, and HTML-to-PDF CSS parse warnings; no failures.

## 2026-01-08 (epic-08 M4 — reconciliation regression tests)
- Changes:
  - Added ReconciliationControlsIT to assert inventory reconciliation variance reaches zero after balancing the ledger and AR/AP reconciliations hold for seeded fixtures.
- Commands run:
  - `mvn -f erp-domain/pom.xml -DskipTests compile`
  - `mvn -f erp-domain/pom.xml -Dcheckstyle.failOnViolation=false checkstyle:check`
  - `mvn -f erp-domain/pom.xml test`
- Validation:
  - `mvn -DskipTests compile` succeeded.
  - Checkstyle reported 29375 violations; `failOnViolation=false` used for baseline visibility.
  - `mvn test` succeeded: Tests run 202, Failures 0, Errors 0, Skipped 4.
- Warnings/notes:
  - Test compile emitted unchecked/unsafe operation warnings in LandedCostRevaluationIT.
  - Testcontainers auth config warnings, dynamic agent loading notices persisted.
  - Test logs include invalid company ID format, negative balance warnings, dispatch mapping warnings, sequence contention/duplicate key retries, and HTML-to-PDF CSS parse warnings; no failures.

## 2026-01-08 (epic-09 M1 — operational runbooks)
- Changes:
  - Documented boot/migrate/backup/restore/rollback runbook steps in DEPLOY_CHECKLIST.
- Commands run:
  - `mvn -f erp-domain/pom.xml -DskipTests compile`
  - `mvn -f erp-domain/pom.xml -Dcheckstyle.failOnViolation=false checkstyle:check`
  - `mvn -f erp-domain/pom.xml test`
  - `DB_PORT=55432 JWT_SECRET=... ERP_SECURITY_ENCRYPTION_KEY=... docker compose up -d --build`
  - `curl --retry 10 --retry-connrefused --retry-delay 5 http://localhost:9090/actuator/health`
  - `docker exec -e PGPASSWORD=erp erp_db pg_dump -U erp -d erp_domain --format=custom --no-owner --no-acl -f /tmp/erp_domain.dump`
  - `docker exec -e PGPASSWORD=erp erp_db createdb -U erp erp_domain_restore_test`
  - `docker exec -e PGPASSWORD=erp erp_db pg_restore -U erp -d erp_domain_restore_test /tmp/erp_domain.dump`
- Validation:
  - `mvn -DskipTests compile` succeeded.
  - Checkstyle reported 29375 violations; `failOnViolation=false` used for baseline visibility.
  - `mvn test` succeeded: Tests run 202, Failures 0, Errors 0, Skipped 4.
  - Docker compose boot succeeded with `DB_PORT=55432`; `/actuator/health` returned `{"status":"UP","groups":["liveness","readiness"]}`.
  - Backup/restore commands completed without error.
- Warnings/notes:
  - Docker compose plugin installed locally (v2.27.1); initial compose boot failed due to port 5432 in use.
  - Testcontainers auth config warnings, dynamic agent loading notices persisted.
  - Test logs include invalid company ID format, negative balance warnings, dispatch mapping warnings, sequence contention/duplicate key retries, and HTML-to-PDF CSS parse warnings; no failures.

## 2026-01-08 (epic-09 M2 — Flyway procedure validation)
- Changes:
  - Updated Flyway audit notes with checksum drift handling, forward-fix guidance, and environment validation steps.
  - Refreshed migration inventory count to 97.
- Commands run:
  - `mvn -f erp-domain/pom.xml -DskipTests compile`
  - `mvn -f erp-domain/pom.xml -Dcheckstyle.failOnViolation=false checkstyle:check`
  - `mvn -f erp-domain/pom.xml test`
  - `DB_PORT=55432 JWT_SECRET=... ERP_SECURITY_ENCRYPTION_KEY=... docker compose up -d --build`
  - `curl --retry 10 --retry-connrefused --retry-delay 5 http://localhost:9090/actuator/health`
  - `docker exec -e PGPASSWORD=erp erp_db psql -U erp -d erp_domain -c "SELECT version, success FROM flyway_schema_history ORDER BY installed_rank DESC LIMIT 5;"`
- Validation:
  - `mvn -DskipTests compile` succeeded.
  - Checkstyle reported 29375 violations; `failOnViolation=false` used for baseline visibility.
  - `mvn test` succeeded: Tests run 202, Failures 0, Errors 0, Skipped 4.
  - Docker compose boot succeeded with `DB_PORT=55432`; `/actuator/health` returned `{"status":"UP","groups":["liveness","readiness"]}`.
  - Flyway history query returned latest versions 93-97 as `success=true`.
- Warnings/notes:
  - Testcontainers auth config warnings, dynamic agent loading notices persisted.
  - Test logs include invalid company ID format, negative balance warnings, dispatch mapping warnings, sequence contention/duplicate key retries, and HTML-to-PDF CSS parse warnings; no failures.

## 2026-01-08 (epic-10 M1 — linkage matrix)
- Changes:
  - Added cross-module linkage matrix documenting expected document→journal→ledger flows and linkage checks across O2C, P2P, production, payroll, and reversals.
- Commands run:
  - `mvn -f erp-domain/pom.xml -DskipTests compile`
  - `mvn -f erp-domain/pom.xml -Dcheckstyle.failOnViolation=false checkstyle:check`
  - `mvn -f erp-domain/pom.xml test`
  - `mvn -f erp-domain/pom.xml -Dtest=*FullCycle* test`
- Validation:
  - `mvn -DskipTests compile` succeeded.
  - Checkstyle reported 29441 violations; `failOnViolation=false` used for baseline visibility.
  - `mvn test` succeeded: Tests run 202, Failures 0, Errors 0, Skipped 4.
  - `mvn -Dtest=*FullCycle* test` succeeded: Tests run 2, Failures 0, Errors 0, Skipped 2.
- Warnings/notes:
  - Testcontainers auth config warnings, dynamic agent loading notices persisted.
  - Test logs include invalid company ID format, negative balance warnings, dispatch mapping warnings, sequence contention/duplicate key retries, and HTML-to-PDF CSS parse warnings; no failures.

## 2026-01-08 (epic-10 M2 — linkage keys inventory)
- Changes:
  - Documented concrete link keys and reference fields across O2C, P2P, production, payroll, and reversals in the linkage matrix.
- Commands run:
  - `mvn -f erp-domain/pom.xml -DskipTests compile`
  - `mvn -f erp-domain/pom.xml -Dcheckstyle.failOnViolation=false checkstyle:check`
  - `mvn -f erp-domain/pom.xml test`
  - `mvn -f erp-domain/pom.xml -Dtest=*FullCycle* test`
- Validation:
  - `mvn -DskipTests compile` succeeded.
  - Checkstyle reported 29441 violations; `failOnViolation=false` used for baseline visibility.
  - `mvn test` succeeded: Tests run 202, Failures 0, Errors 0, Skipped 4.
  - `mvn -Dtest=*FullCycle* test` succeeded: Tests run 2, Failures 0, Errors 0, Skipped 2.
- Warnings/notes:
  - Testcontainers auth config warnings, dynamic agent loading notices persisted.
  - Test logs include invalid company ID format, negative balance warnings, dispatch mapping warnings, sequence contention/duplicate key retries, and HTML-to-PDF CSS parse warnings; no failures.

## 2026-01-08 (epic-10 M3 — linkage invariants)
- Changes:
  - Enforced company alignment across linked journal entries (documents, reversals, and cascades).
  - Added repository helper to resolve journal entry company IDs without lazy loading.
- Commands run:
  - `mvn -f erp-domain/pom.xml -DskipTests compile`
  - `mvn -f erp-domain/pom.xml -Dcheckstyle.failOnViolation=false checkstyle:check`
  - `mvn -f erp-domain/pom.xml test`
  - `mvn -f erp-domain/pom.xml -Dtest=*FullCycle* test`
- Validation:
  - `mvn -DskipTests compile` succeeded (javac warnings about missing javax.annotation meta; deprecated API warnings present).
  - Checkstyle reported 29446 violations; `failOnViolation=false` used for baseline visibility.
  - `mvn test` succeeded: Tests run 202, Failures 0, Errors 0, Skipped 4.
  - `mvn -Dtest=*FullCycle* test` succeeded: Tests run 2, Failures 0, Errors 0, Skipped 2.
- Warnings/notes:
  - Testcontainers auth config warnings, dynamic agent loading notices persisted.
  - Test logs include invalid company ID format, negative balance warnings, dispatch mapping warnings, sequence contention/duplicate key retries, and HTML-to-PDF CSS parse warnings; no failures.
  - PerformanceBudgetIT emitted verbose session metrics; no failures.

## 2026-01-08 (epic-10 M4 — ledger link backfill)
- Changes:
  - Backfilled dealer/supplier ledger journal entry links via reference mappings.
  - Added journal entry lookup indexes on ledger tables.
- Commands run:
  - `mvn -f erp-domain/pom.xml -DskipTests compile`
  - `mvn -f erp-domain/pom.xml -Dcheckstyle.failOnViolation=false checkstyle:check`
  - `mvn -f erp-domain/pom.xml test`
  - `mvn -f erp-domain/pom.xml -Dtest=*FullCycle* test`
- Validation:
  - `mvn -DskipTests compile` succeeded.
  - Checkstyle reported 29446 violations; `failOnViolation=false` used for baseline visibility.
  - `mvn test` succeeded: Tests run 202, Failures 0, Errors 0, Skipped 4.
  - `mvn -Dtest=*FullCycle* test` succeeded: Tests run 2, Failures 0, Errors 0, Skipped 2.
- Warnings/notes:
  - Testcontainers auth config warnings, dynamic agent loading notices persisted.
  - Test logs include invalid company ID format, negative balance warnings, dispatch mapping warnings, sequence contention/duplicate key retries, and HTML-to-PDF CSS parse warnings; no failures.
  - Dispatch debit/credit accounts not configured; dispatch mapping COGS postings skipped.
  - PerformanceBudgetIT emitted verbose session metrics; no failures.

## 2026-01-08 (epic-10 M5 — idempotency checks)
- Changes:
  - Added supplier settlement idempotency repeat assertion to ensure same journal entry and single allocation record for duplicate requests.
- Commands run:
  - `mvn -f erp-domain/pom.xml -DskipTests compile`
  - `mvn -f erp-domain/pom.xml -Dcheckstyle.failOnViolation=false checkstyle:check`
  - `mvn -f erp-domain/pom.xml test`
  - `mvn -f erp-domain/pom.xml -Dtest=*FullCycle* test`
- Validation:
  - `mvn -DskipTests compile` succeeded.
  - Checkstyle reported 29446 violations; `failOnViolation=false` used for baseline visibility.
  - `mvn test` succeeded: Tests run 202, Failures 0, Errors 0, Skipped 4.
  - `mvn -Dtest=*FullCycle* test` succeeded: Tests run 2, Failures 0, Errors 0, Skipped 2.
- Warnings/notes:
  - Testcontainers auth config warnings, dynamic agent loading notices persisted.
  - Test logs include invalid company ID format, negative balance warnings, dispatch mapping warnings, sequence contention/duplicate key retries, and HTML-to-PDF CSS parse warnings; no failures.
  - PerformanceBudgetIT emitted verbose session metrics; no failures.

## 2026-01-08 (payroll advance posting fix)
- Changes:
  - Posted payroll expense at gross and credited employee advances separately instead of netting advances against salary payable.
  - Updated invariants and hire-to-pay flow documentation to assert EMP-ADV credit postings.
- Commands run:
  - `mvn -f erp-domain/pom.xml -Dtest=ErpInvariantsSuiteIT test`
- Validation:
  - `mvn -Dtest=ErpInvariantsSuiteIT test` succeeded: Tests run 9, Failures 0, Errors 0, Skipped 0.
- Warnings/notes:
  - Environment validation disabled; configuration health checks skipped.
  - Test logs include invalid company ID format and negative balance warnings (INV, CASH, EMP-ADV); no failures.

## 2026-01-09 (onboarding scaffolding + linkage fixes)
- Changes:
  - Added accounting onboarding endpoints + service for brands/categories/products/variants/raw materials/suppliers/dealers, opening stock, and opening partner balances with idempotent references.
  - Enforced default account mappings for onboarding product creation; added onboarding RBAC permission and production categories table.
  - Required onboarding products to include WIP + semi-finished account metadata; updated onboarding guide/tests accordingly.
  - Linked dispatch/return inventory movements back to their journal entries and extended invariants coverage.
  - Added onboarding flow tests, updated OpenAPI snapshot, and documented onboarding steps.
  - Fixed ops smoke token parsing (python here-doc input).
- Commands run:
  - `mvn -f erp-domain/pom.xml -DskipTests compile`
  - `mvn -f erp-domain/pom.xml -Dcheckstyle.failOnViolation=false checkstyle:check`
  - `mvn -f erp-domain/pom.xml test`
- Validation:
  - `mvn -DskipTests compile` succeeded.
  - Checkstyle reported 30671 violations; `failOnViolation=false` used for baseline visibility.
  - `mvn test` succeeded: Tests run 204, Failures 0, Errors 0, Skipped 4.
- Warnings/notes:
  - Testcontainers auth config warnings, dynamic agent loading notices persisted.
  - Test logs include invalid company ID format, dispatch mapping not configured, negative balance warnings, and openhtmltopdf CSS parse warnings; no failures.

## 2026-01-09 (epic-10 M4/M5 — onboarding account suggestions + CoA guidance)
- Changes:
  - Added onboarding account suggestions endpoint (defaults + candidate lists by account type) to reduce admin onboarding friction.
  - Documented minimal chart-of-accounts mappings, posting flow summary, required config checklist, and future auto-mapping plan in onboarding guide.
- Commands run:
  - `mvn -f erp-domain/pom.xml -DskipTests compile`
  - `mvn -f erp-domain/pom.xml -Dcheckstyle.failOnViolation=false checkstyle:check`
  - `mvn -f erp-domain/pom.xml test`
  - `mvn -f erp-domain/pom.xml -Dtest=*FullCycle* test`
  - `JWT_SECRET=... ERP_SECURITY_ENCRYPTION_KEY=... ERP_DISPATCH_DEBIT_ACCOUNT_ID=5000 ERP_DISPATCH_CREDIT_ACCOUNT_ID=1200 DB_PORT=55432 docker compose up -d --build`
  - `curl -fsS http://localhost:9090/actuator/health`
- Validation:
  - `mvn -DskipTests compile` succeeded.
  - Checkstyle reported 30757 violations; `failOnViolation=false` used for baseline visibility.
  - `mvn test` succeeded: Tests run 206, Failures 0, Errors 0, Skipped 4.
  - `mvn -Dtest=*FullCycle* test` succeeded: Tests run 2, Failures 0, Errors 0, Skipped 2.
  - Docker Compose app started; `/actuator/health` returned `UP`.
- Warnings/notes:
  - Testcontainers auth config warnings and dynamic agent loading notices persisted.
  - Test logs include invalid company ID format, negative balance warnings, sequence contention retries, and dispatch mapping not configured in tests; no failures.
  - Docker logs note licensing enforcement disabled (erp.licensing.enforce=false).

## 2026-01-10 (epic-10 onboarding integrity -- opening balance idempotency fix)
- Changes:
  - Reused existing opening balance journals for dealer/supplier retries; reject idempotency conflicts and backfill missing ledger links.
- Commands run:
  - `mvn -f erp-domain/pom.xml -DskipTests compile`
  - `mvn -f erp-domain/pom.xml -Dcheckstyle.failOnViolation=false checkstyle:check`
  - `mvn -f erp-domain/pom.xml test`
- Validation:
  - `mvn -DskipTests compile` succeeded.
  - Checkstyle reported 30804 violations; `failOnViolation=false` used for baseline visibility.
  - `mvn test` succeeded: Tests run 206, Failures 0, Errors 0, Skipped 4.
- Warnings/notes:
  - Test logs include expected warnings (invalid company IDs, negative balances, dynamic agent loading); no failures.

## 2026-01-10 (deep debugging program + final predeploy phase planning)
- Scope: create a runnable Deep Debugging Program (planning + docs + test/evidence harness; no feature work), then re-number the existing predeploy pack as the final phase.
- Artifacts created/updated:
  - Debugging program:
    - `tasks/debugging/README.md`
    - `tasks/debugging/task-01-architecture-and-module-map.md`
    - `tasks/debugging/task-02-endpoint-and-portal-matrix.md`
    - `tasks/debugging/task-03-auditability-and-linkage-contracts.md`
    - `tasks/debugging/task-04-module-by-module-deep-debug.md`
    - `tasks/debugging/task-05-reconciliation-and-period-controls.md`
    - `tasks/debugging/task-06-security-rbac-and-company-boundaries.md`
    - `tasks/debugging/task-07-performance-and-ops-evidence.md`
  - Evidence + portal surface:
    - `docs/API_PORTAL_MATRIX.md`
    - `docs/ops_and_debug/EVIDENCE.md` (standard/template + append-only log)
  - Final predeploy phase (renumbered from 11–15 → 08–12):
    - `tasks/predeploy/README.md`
    - `tasks/predeploy/task-08-predeploy-consistency.md`
    - `tasks/predeploy/task-09-ledger-subledger-gaps.md`
    - `tasks/predeploy/task-10-masterdata-and-onboarding-audit.md`
    - `tasks/predeploy/task-11-endpoint-hygiene-and-deprecations.md`
    - `tasks/predeploy/task-12-ops-and-data-integrity.md`
- Notes:
  - Execute tasks sequentially and run the required verification gates after every milestone.
  - Do not start the final predeploy phase until the debugging program tasks are complete.

## 2026-01-10 (debug-01 M1 module map verification)
- Changes:
  - Updated Task 01 module map with verified controllers/services/tables and added portal/reports/demo sections.
  - OpenAPI snapshot normalized after OpenApiSnapshotIT (newline change only).
- Commands run:
  - `mvn -f erp-domain/pom.xml -DskipTests compile`
  - `mvn -f erp-domain/pom.xml -Dcheckstyle.failOnViolation=false checkstyle:check`
  - `mvn -f erp-domain/pom.xml test`
  - `mvn -f erp-domain/pom.xml -Dtest=OpenApiSnapshotIT test`
- Validation:
  - `mvn -DskipTests compile` succeeded.
  - Checkstyle reported 30804 violations; `failOnViolation=false` used for baseline visibility.
  - `mvn test` succeeded: Tests run 206, Failures 0, Errors 0, Skipped 4.
  - `OpenApiSnapshotIT` succeeded: Tests run 1, Failures 0, Errors 0, Skipped 0.
- Warnings/notes:
  - Endpoint inventory mismatch: openapi has endpoints missing from endpoint_inventory.tsv; inventory-only includes `/api/integration/health` (see evidence log).

## 2026-01-10 (debug-01 M2 financial touchpoints)
- Changes:
  - Added verified financial touchpoints list with evidence chains and idempotency markers in Task 01.
  - OpenAPI snapshot normalized after test runs (newline change only).
- Commands run:
  - `mvn -f erp-domain/pom.xml -DskipTests compile`
  - `mvn -f erp-domain/pom.xml -Dcheckstyle.failOnViolation=false checkstyle:check`
  - `mvn -f erp-domain/pom.xml test`
  - `mvn -f erp-domain/pom.xml -Dtest=ErpInvariantsSuiteIT test`
- Validation:
  - `mvn -DskipTests compile` succeeded.
  - Checkstyle reported 30804 violations; `failOnViolation=false` used for baseline visibility.
  - `mvn test` succeeded: Tests run 206, Failures 0, Errors 0, Skipped 4.
  - `ErpInvariantsSuiteIT` succeeded: Tests run 9, Failures 0, Errors 0, Skipped 0.
- Warnings/notes:
  - Idempotency verification flagged for opening stock import and raw material intake (see Task 01 M2 list).
