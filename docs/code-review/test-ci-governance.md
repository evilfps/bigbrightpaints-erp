# Test / CI / governance

## Scope and evidence

This review inventories the current automated-test layers, the GitHub Actions workflows and shell gates that exercise them, the effective hard-vs-advisory posture of those gates, and the biggest signal-vs-noise problems in the current quality pipeline.

Primary evidence:

- `.github/workflows/{ci.yml,doc-lint.yml,codex-review.yml,codex-autofix.yml,rag-mcp-sidecar.yml}`
- `README.md`
- `docs/developer-guide.md`
- `.factory/services.yaml`
- `erp-domain/pom.xml`
- `qodana.yaml`
- `ci/{lint-knowledgebase.sh,check-architecture.sh,check-enterprise-policy.sh,check-orchestrator-layer.sh}`
- `scripts/{gate_fast.sh,gate_core.sh,gate_release.sh,gate_reconciliation.sh,gate_quality.sh,verify_local.sh,validate_test_catalog.py,check_flaky_tags.py,changed_files_coverage.py,module_coverage_gate.py,pit_mutation_summary.py,flake_rate_gate.py,guard_openapi_contract_drift.sh}`
- `erp-domain/src/test/java/com/bigbrightpaints/erp/**`

Session evidence gathered for this review:

- `cd /home/realnigga/Desktop/Mission-control/erp-domain && mvn test -Pgate-fast -Djacoco.skip=true` passed with `394` tests run, `0` failures, `0` errors.
- `cd /home/realnigga/Desktop/Mission-control && python3 scripts/validate_test_catalog.py --tests-root erp-domain/src/test/java/com/bigbrightpaints/erp/truthsuite --gate gate-fast` passed in compatibility mode because `docs/CODE-RED/confidence-suite/TEST_CATALOG.json` is missing; it discovered `85` tagged truthsuite tests anyway.
- `cd /home/realnigga/Desktop/Mission-control && python3 scripts/check_flaky_tags.py --tests-root erp-domain/src/test/java --gate gate-fast` passed with `0` quarantine entries and `0` flaky-tag lane violations.
- `cd /home/realnigga/Desktop/Mission-control && bash scripts/guard_openapi_contract_drift.sh` exited successfully only because `docs/endpoint-inventory.md` is missing and the guard explicitly falls back to compatibility mode.
- `curl -i http://localhost:9090/actuator/health` returned HTTP `503` with `{"status":"DOWN","groups":["liveness","readiness"]}` during this session, so live runtime evidence remains weaker than the repo-side CI evidence.

## Test inventory

### Primary suite map

The repository has a large and varied test tree, but only a narrow subset is exercised by default CI.

| Test layer | Approx. executable file count | Representative evidence | How it runs today | CI posture |
| --- | ---: | --- | --- | --- |
| Unit / focused service-controller tests | 179 | `modules/accounting/service/AccountingServiceTest.java`, `modules/company/service/CompanyServiceTest.java`, `modules/sales/service/SalesServiceTest.java` | Default Maven includes `*Test`/`*Tests`; local developer guide also advertises a unit-focused suite excluding `*IT` and `*codered*`. | **Not** part of the default PR/main hard gates; mostly local/full-suite coverage. |
| Integration tests (`*IT`, `*ITCase`, `*IntegrationTest`) | 41 | `OpenApiSnapshotIT.java`, `modules/company/CompanyControllerIT.java`, `modules/auth/AuthControllerIT.java` | Share `AbstractIntegrationTest`, which boots Spring and Testcontainers PostgreSQL on Flyway v2. | Mostly **outside** the hard CI lanes unless they live under `truthsuite/**`. |
| Truthsuite | 88 | `truthsuite/o2c/TS_O2CDispatchCanonicalPostingTest.java`, `truthsuite/p2p/TS_P2PGoodsReceiptIdempotencyTest.java`, `truthsuite/runtime/TS_RuntimeAccountingFacadeExecutableCoverageTest.java` | `pom.xml` profiles `gate-fast`, `gate-core`, `gate-release`, and `gate-reconciliation` override Surefire includes to only `truthsuite/**/*`. | **Primary hard gate surface**. |
| CODE-RED | 35 | `codered/CR_PeriodCloseAtomicityTest.java`, `codered/CR_PurchasingToApAccountingTest.java`, `codered/CR_DispatchBusinessMathFuzzTest.java` | Dedicated `codered` Maven profile includes `**/codered/**/CR_*.java`. | **Not wired** into any GitHub Actions workflow. |
| E2E | 28 | `e2e/accounting/ProcureToPayE2ETest.java`, `e2e/sales/OrderFulfillmentE2ETest.java`, `e2e/fullcycle/FullCycleE2ETest.java` | Full-stack `AbstractIntegrationTest` flows. | **Not wired** into CI; one flagship file is disabled. |
| Regression | 18 | `regression/OpeningStockPostingRegressionIT.java`, `regression/InventoryAccountingEventListenerIT.java` | Executable in full Maven runs or by targeted selection. | **Not wired** into the normal hard gates. |
| Smoke | 2 | `smoke/ApplicationSmokeTest.java`, `smoke/CriticalPathSmokeTest.java` | Basic startup and happy-path API checks. | **Not wired** into CI. |
| Performance | 3 | `performance/PerformanceBudgetIT.java`, `performance/PerformanceExplainIT.java`, `modules/accounting/service/AccountingServiceBenchmarkTest.java` | Budget, EXPLAIN-plan logging, and an opt-in benchmark guarded by `Assumptions.assumeTrue(Boolean.getBoolean("runBenchmarks"))`. | **Not wired** into CI. |
| Standalone contract-style tests | 12 | `InvoiceControllerSecurityContractTest.java`, `PurchasingWorkflowControllerSecurityContractTest.java`, `ReportControllerContractTest.java` | Scattered through module test packages rather than a dedicated contract lane. | Partly covered only when a file also sits in truthsuite or a broader full run. |

### Truthsuite composition

The truthsuite is the hard-gated lane, but it is not homogeneous:

- `45` of the `88` truthsuite files sit under `truthsuite/runtime/`.
- `36` of those `45` runtime files are `*ExecutableCoverage*` tests.
- Tag-derived membership shows roughly:
  - `80` truthsuite files tagged for `gate-fast` (`@Tag("critical")`)
  - `85` truthsuite files tagged for `gate-core` / `gate-release`
  - `52` truthsuite files tagged for `gate-reconciliation`

That means the critical CI lane is dominated by runtime path/coverage-style assertions, not by cross-module scenario tests.

### Notable omitted high-signal suites

Several suites exist but are not part of the normal CI hard gates:

- `erp-domain/src/test/java/com/bigbrightpaints/erp/invariants/ErpInvariantsSuiteIT.java` — the most comprehensive cross-module invariant suite.
- `erp-domain/src/test/java/com/bigbrightpaints/erp/codered/**` — the strongest concurrency / idempotency / production-hardening lane.
- `erp-domain/src/test/java/com/bigbrightpaints/erp/smoke/**` — direct startup, health, login, and critical-path smoke checks.
- `erp-domain/src/test/java/com/bigbrightpaints/erp/e2e/**` — broad end-to-end workflows; `FullCycleE2ETest` is explicitly `@Disabled("Incomplete test implementation - needs proper setup")`.
- `erp-domain/src/test/java/com/bigbrightpaints/erp/performance/**` — useful for budgets and query-plan inspection, but currently outside any gate.

## CI workflow inventory

| Workflow | Trigger | Purpose | Effective posture |
| --- | --- | --- | --- |
| `.github/workflows/ci.yml` | PR, push to `main`, tags, schedule, manual dispatch | Primary repository CI. Runs knowledgebase/policy checks and the `gate-fast`, `gate-core`, `gate-release`, `gate-reconciliation`, and `gate-quality` scripts on different triggers. | **Primary quality workflow**. |
| `.github/workflows/doc-lint.yml` | PR, push to `main`, manual | Runs `bash ci/lint-knowledgebase.sh`. | Functionally **duplicates** the `knowledgebase-lint` job already present in `ci.yml`. |
| `.github/workflows/codex-review.yml` | PR, manual | Enforces Codex review policy via `scripts/enforce_codex_review_policy.sh`. | Governance/process workflow, not product-quality evidence. |
| `.github/workflows/codex-autofix.yml` | Manual | Runs template autofix checks. | Manual helper, not a hard release gate. |
| `.github/workflows/rag-mcp-sidecar.yml` | PR on selected paths, manual | Builds/refreshes RAG index and silent-failure artifacts. | Observability/support workflow, not a direct shipping gate. |

## Gate classification

### Workflow-level gate map

| Gate | Trigger | Main checks | Nominal status | Effective status on this branch |
| --- | --- | --- | --- | --- |
| `knowledgebase-lint` | PR, `main`, manual | `ci/lint-knowledgebase.sh` | Hard | Hard, but mostly in compatibility mode because the repo lacks the full canonical knowledgebase contract set. |
| `architecture-check` | PR, `main` | import-edge allowlist and architecture doc presence | Hard | Hard on obvious failures, but import-edge enforcement relaxes when `agents/catalog.yaml` is absent. |
| `enterprise-policy-check` | PR, `main`, release-related runs | R2 approval workflow checks for high-risk paths | Hard | Hard, but path-scoped; docs-only/test-only changes mostly bypass it. |
| `orchestrator-layer-check` | PR, `main` | agent-layer contract validation | Hard | Effectively advisory because the script falls back to compatibility mode when canonical orchestrator-layer files are absent and `.codex/config.toml` exists. |
| `gate-fast` | PR or manual with `diff_base` | truthsuite critical lane + several contract guards + changed-files coverage summary | Hard | Mixed: the job fails on script/test crashes, but catalog validation, OpenAPI inventory, and changed-files coverage all degrade to compatibility or warning-only behavior in common cases. |
| `gate-core` | `main` | wider truthsuite lane + fixture matrix + module coverage gate | Hard | Stronger than `gate-fast`; module coverage is a real hard fail. Some documentation-based guards still allow compatibility mode. |
| `gate-release` | tags, manual release validation | release-grade truth lane, `verify_local.sh`, migration matrix, Flyway guards | Hard | Strongest hard gate, but only on tag/manual paths, not on every PR. |
| `gate-reconciliation` | tags, manual release validation | reconciliation-only truthsuite lane + surefire summary | Hard | Hard when invoked, but not part of PR/main feedback. |
| `gate-quality` | nightly schedule or manual opt-in | PIT mutation lane + 20-run flake window | Hard when run | **Advisory by frequency** because it is not part of normal PR or `main` branch gating. |
| Checkstyle | none in workflow | `maven-checkstyle-plugin` in `pom.xml` | Advisory | Advisory only; no workflow invokes it and `failsOnError=false`. |
| Qodana | none in workflow | `qodana.yaml` | Intended hard thresholds in config | **Not active**; there is no workflow invoking Qodana. |

### Script-level hard vs advisory details

| Surface | Evidence | Classification | Why |
| --- | --- | --- | --- |
| Test catalog validation | `scripts/validate_test_catalog.py` | Advisory in practice | Missing `docs/CODE-RED/confidence-suite/TEST_CATALOG.json` causes a successful compatibility-mode exit instead of a failure. |
| Flaky quarantine contract | `scripts/check_flaky_tags.py`, `scripts/test_quarantine.txt` | Hard, but underused | The script enforces metadata and lane exclusions, but there are currently `0` quarantine entries, so it is governance scaffolding more than an active control. |
| OpenAPI drift guard | `scripts/guard_openapi_contract_drift.sh` | Advisory in practice | Missing `docs/endpoint-inventory.md` produces a warning and success (`fail-open compatibility mode`). |
| Changed-files coverage | `scripts/gate_fast.sh`, `scripts/changed_files_coverage.py` | Advisory on PRs | `gate_fast.sh` explicitly warns and continues when changed-files coverage misses thresholds; it only tightens in release-validation mode. |
| Module coverage gate | `scripts/gate_core.sh`, `scripts/module_coverage_gate.py` | Hard | Below-threshold module/class coverage fails `gate-core`. |
| Mutation testing | `scripts/gate_quality.sh`, `scripts/pit_mutation_summary.py` | Hard but off the critical path | Thresholds are strict when run, but the lane is schedule/manual only. |
| Flake-rate gate | `scripts/gate_quality.sh`, `scripts/flake_rate_gate.py` | Hard but off the critical path | The repo has a real 20-run flake gate, but only in scheduled/manual quality runs. |
| Checkstyle | `erp-domain/pom.xml` | Advisory | The plugin is configured with `failsOnError=false` and warning severity. |
| Qodana | `qodana.yaml` | Dormant | Thresholds exist in config, but no workflow enforces them. |

## Usefulness assessment

### High-signal controls

1. **`gate-release` is the most meaningful real gate.** It is the only lane that combines hard guards, high-signal truthsuite coverage, `verify_local.sh`, and migration-matrix validation.
2. **`gate-core` adds a real coverage ratchet.** `module_coverage_gate.py` is harder to game than raw test counts because it checks specific high-risk packages/classes.
3. **The best truthsuite tests are business-invariant tests, not executable-coverage tests.** Examples include `TS_O2CDispatchCanonicalPostingTest`, `TS_P2PGoodsReceiptIdempotencyTest`, `TS_OrchestratorExactlyOnceOutboxTest`, and `TS_DoubleEntryMathInvariantTest`.
4. **The specialized shell guards are good at policy drift.** Migration ownership, referential-contract, correlation, and workflow guards provide targeted governance that unit tests alone would miss.

### Useful but noisy / lower-signal areas

1. **Truthsuite runtime coverage dominates the pass count.** `45` runtime truthsuite files — `36` of them named `*ExecutableCoverage*` — are useful as tripwires, but they are weaker than scenario or invariant tests for proving business correctness.
2. **Smoke, E2E, and performance suites exist but are not trusted enough to gate.** That lowers their current operational value because regressions can accumulate there without CI feedback.
3. **`PerformanceExplainIT` is diagnostic, not assertive.** It logs query plans but sets no threshold, so it is helpful for human analysis and weak as an automated governance control.
4. **`AccountingServiceBenchmarkTest` is explicitly opt-in.** It only runs when `-DrunBenchmarks=true`, so it is not part of any normal confidence lane.
5. **The developer-facing “unit-focused” and “full” local commands are misleadingly separated.** In `.factory/services.yaml`, `test-unit` and `test-full` are effectively the same command and both exclude IT/codered coverage.

### Redundancy

| Redundant or overlapping surface | Evidence | Assessment |
| --- | --- | --- |
| `doc-lint.yml` vs `knowledgebase-lint` job in `ci.yml` | both run `bash ci/lint-knowledgebase.sh` | Operational duplication; useful only if the team wants a dedicated doc-only workflow badge. |
| Repeated preflight guards across `gate-fast`, `gate-core`, and `gate-release` | shared scripts for catalog, flaky tags, correlation, OpenAPI, audit/portal guards | Mostly acceptable duplication: it keeps each lane self-contained, but it does lengthen CI time. |
| Runtime executable-coverage truthsuite vs module/service/controller unit tests | many `truthsuite/runtime/TS_Runtime*ExecutableCoverageTest.java` alongside existing unit/controller tests | This is the biggest signal-overlap problem in the test tree. The tests are not useless, but the hard gate leans too heavily on shallow path coverage. |

## Missing gates and weak controls

1. **No hard CI lane executes CODE-RED.** The repo has `35` CODE-RED files aimed at concurrency, idempotency, and production-hardening failures, but none of the workflows invoke `-Pcodered`.
2. **No hard CI lane executes smoke tests.** `ApplicationSmokeTest` and `CriticalPathSmokeTest` are exactly the tests that should validate a deployable build, yet they sit outside the workflows.
3. **No hard CI lane executes the strongest cross-module invariant suite.** `ErpInvariantsSuiteIT` is manually valuable and operationally omitted.
4. **E2E coverage is not governable today.** The tree contains `28` E2E files, but they are not gated, and `FullCycleE2ETest` is disabled because setup is incomplete.
5. **Static-analysis governance is weak.** Checkstyle is warning-only, Qodana is configured but not run, and there is no “new violations only” baseline enforcement.
6. **The test-catalog contract is fail-open right now.** Missing `docs/CODE-RED/confidence-suite/TEST_CATALOG.json` means the gate does not prove ownership/lane metadata consistency.
7. **The OpenAPI guard is fail-open right now.** Missing `docs/endpoint-inventory.md` means the drift guard cannot actually block API/documentation divergence.
8. **Changed-files coverage does not block PRs.** The script warns and continues, so it is reporting rather than governing.
9. **Flake governance is too far from the PR path.** A 20-run flake lane exists, but only on nightly/manual quality runs.
10. **Performance governance is observational, not contractual.** There is no hard budget gate in CI for query plans, latency, or throughput.

## Overall judgment

The current pipeline is **better at policy drift and targeted truth assertions than at broad release confidence**. The repository has many test categories, but the effective hard-gated surface is narrow: mostly truthsuite plus a set of shell guards. That narrowness keeps `gate-fast` and `gate-core` relatively quick and deterministic, but it leaves major blind spots around CODE-RED, smoke, E2E, cross-module invariants, performance, and static analysis.

The most important governance gap is not a lack of tests; it is that the **highest-risk suites are present but optional**, while several “hard” controls quietly degrade to compatibility mode. In other words, the repo has more quality machinery than it is currently enforcing.
