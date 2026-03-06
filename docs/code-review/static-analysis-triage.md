# Static-analysis triage

## Scope and evidence

This review classifies the current static-analysis posture, reconstructs the likely shape of the large legacy backlog, identifies the files most likely to concentrate that backlog, and proposes a survivable baseline plus a new-violations-only gate that the current CI can actually sustain.

Primary evidence:

- `qodana.yaml`
- `erp-domain/pom.xml`
- `.github/workflows/ci.yml`
- `scripts/{gate_fast.sh,gate_core.sh,gate_release.sh,gate_quality.sh}`
- size/hotspot inventory of `erp-domain/src/main/java/**` and `erp-domain/src/test/java/**`

Session evidence relevant to static analysis:

- `qodana.yaml` enables a narrow recommended-Java profile plus `UNUSED_IMPORT`, `UNUSED_SYMBOL`, `GroovyUnusedDeclaration`, `DataFlowIssue`, `ConstantConditions`, `SpringJavaInjectionPointsAutowiringInspection`, and `SpringDataPageableParameterMissing`.
- `qodana.yaml` excludes `erp-domain/src/test`, `erp-domain/target`, generated sources, and Flyway migration directories.
- `qodana.yaml` declares `failureConditions.severityThresholds` of `critical: 0` and `any: 50`, but no GitHub Actions workflow invokes Qodana.
- `erp-domain/pom.xml` configures `maven-checkstyle-plugin` with `google_checks.xml`, `failsOnError=false`, and `violationSeverity=warning`, which makes Checkstyle advisory even when run.
- An attempted `mvn -B -ntp -DskipTests -Djacoco.skip=true checkstyle:checkstyle` reproduction earlier in this feature run failed with `No space left on device`, so there is no fresh machine-generated Checkstyle report attached to the repo state.

## Current posture

The repo has **two static-analysis systems, but neither is an effective CI gate today**:

1. **Checkstyle is present but deliberately non-blocking.** The Maven plugin exists in the main build, yet it is configured to warn instead of fail.
2. **Qodana is configured more strictly on paper than in practice.** Its thresholds would fail a run quickly, but there is no workflow or script invoking it.
3. **No baseline artifact is stored in-repo.** There is no checked-in SARIF, XML, or backlog snapshot that would let the team distinguish legacy debt from newly introduced violations.

That means the branch currently has **static-analysis intent, not static-analysis governance**.

## Reconstructing the legacy backlog shape

The mission prompt refers to a very large legacy backlog (roughly `49k` findings), but the current branch does not contain the generated report that produced that number. Given the active tool configuration, the safest reconstruction is:

- the **dominant majority** of a 49k-scale backlog would come from **Checkstyle / style / maintainability noise**, because Checkstyle is the only broad analyzer configured across the Java codebase;
- the **high-severity minority** would come from the much narrower Qodana inspection set (`DataFlowIssue`, `ConstantConditions`, Spring injection checks, etc.);
- the **most operationally important slice** is therefore likely much smaller than the raw backlog number, but it is also the slice that is currently least visible because Qodana is not executed in CI.

In other words, the current backlog should be treated as **large, legacy, and mostly non-blocking — with a smaller hidden critical subset that deserves first-class gating once it is surfaced**.

## Severity buckets

These buckets are the right way to triage the current backlog and any future baseline.

| Severity bucket | What belongs here | Likely current share | Triage stance |
| --- | --- | --- | --- |
| **Blocker** | New security-critical or build-integrity issues: secrets exposure, broken Spring injection on required security/auth beans, impossible dataflow on critical transaction paths, analyzer crashes, or misconfigurations that invalidate the scan itself. | Very small by count, highest by risk. | **Never tolerate on new code.** Fix immediately and block merges. |
| **Critical** | New correctness/nullability issues from `DataFlowIssue`, `ConstantConditions`, or Spring wiring problems in accounting, reconciliation, inventory, tenant enforcement, or auth/reporting paths. | Small minority. | **Block on new findings.** Legacy findings should be isolated and burned down by hotspot. |
| **Major** | Maintainability/performance hazards in giant engine/controller/service files: overly complex methods, deep nesting, duplication, query-plan/path issues, or hotspot files where small edits are risky. | Material secondary slice. | **Do not block the entire legacy set.** Block new majors in hotspot packages first; warn elsewhere until baseline exists. |
| **Minor** | Formatting, naming, Javadoc, import ordering, whitespace, brace style, and similar Google Checkstyle warnings. | Likely the overwhelming majority of any 49k backlog. | **Carry as legacy baseline.** Enforce only on touched files or touched lines. |
| **Info** | Unused imports/symbols, low-risk cleanup suggestions, and purely informational maintainability hints. | Large but low-risk tail. | Report and autofix opportunistically; never stop releases for legacy items. |

## Type groups

| Type group | Current evidence | Main hotspots | Recommended treatment |
| --- | --- | --- | --- |
| **Security** | The strongest static signal would come from Qodana and Spring inspection coverage, but Qodana is dormant; controller/security contract tests exist separately in the test tree. | Auth, reporting, purchasing, invoice/controller security surfaces. | Block new findings immediately once Qodana is active; keep legacy items separately tracked from style debt. |
| **Correctness** | `DataFlowIssue` and `ConstantConditions` are configured, but only on paper today. | Accounting posting, reconciliation, inventory movement, orchestrator outbox, tenant/runtime enforcement. | Block new critical correctness findings; prioritize hotspot files first. |
| **Nullability / dependency wiring** | `SpringJavaInjectionPointsAutowiringInspection` is enabled in `qodana.yaml`; large service/config classes raise the probability of fragile wiring. | `TenantRuntimeEnforcementService`, large accounting/sales service cores, configuration services. | Treat as critical when new; these are deployment-breaking more than cosmetic. |
| **Performance** | No static performance analyzer is wired, but the codebase has performance tests and EXPLAIN-plan diagnostics; some giant service files are likely to accumulate algorithmic/query debt. | `SalesCoreEngine`, `ProductionCatalogService`, report and inventory services. | Track as major; gate new regressions in changed hotspot files instead of attempting a repo-wide fail. |
| **Maintainability** | Giant source and test files strongly suggest concentration of complexity and style churn. | Accounting core, sales core, accounting facade/controller, large service and test files. | Use hotspot-based burn-down; this is the right place to spend backlog-reduction effort. |
| **Style** | Checkstyle is the only broad analyzer that definitely runs against the Java codebase, and it is warning-only. | Repo-wide; especially giant source files and giant tests. | Treat as baseline noise first, then enforce changed-files-only hygiene. |

## Rule concentration hotspots

The backlog is unlikely to be evenly distributed. The following files are strong concentration candidates based on size and architectural centrality.

### Production-code hotspots

| File | LOC | Why it likely concentrates findings | Likely dominant rule families |
| --- | ---: | --- | --- |
| `modules/accounting/internal/AccountingCoreEngineCore.java` | 6297 | Largest production class in the repo; central accounting logic with high change risk. | complexity, maintainability, nullability/correctness, style |
| `modules/sales/service/SalesCoreEngine.java` | 4007 | Very large sales orchestration/service core. | complexity, performance, maintainability, style |
| `modules/accounting/internal/AccountingFacadeCore.java` | 2159 | Large façade over accounting workflows. | maintainability, correctness, style |
| `modules/production/service/ProductionCatalogService.java` | 2124 | Large service likely to accumulate branch-heavy catalog logic. | complexity, performance, style |
| `modules/accounting/controller/AccountingController.java` | 1457 | Large controller surface often attracts style and wiring warnings. | style, Spring wiring, maintainability |
| `modules/reports/service/ReportService.java` | 1385 | Reporting code tends to accumulate complexity and query-related concerns. | performance, complexity, style |
| `modules/accounting/internal/AccountingPeriodServiceCore.java` | 1339 | Period-close logic is correctness-sensitive and branching-heavy. | correctness, complexity, style |
| `modules/accounting/internal/ReconciliationServiceCore.java` | 1219 | Reconciliation is a high-risk correctness hotspot. | correctness, nullability, maintainability |

### Test-code hotspots

Qodana currently excludes tests, so these are mainly **Checkstyle / maintainability** backlog drivers rather than Qodana drivers.

| File | LOC | Why it matters |
| --- | ---: | --- |
| `modules/accounting/service/AccountingServiceTest.java` | 8329 | Largest file in the entire test tree; almost certainly a major source of legacy style noise. |
| `modules/sales/service/SalesServiceTest.java` | 4332 | Same pattern on the sales side. |
| `invariants/ErpInvariantsSuiteIT.java` | 1643 | High-value integration coverage, but large enough to attract style/maintainability churn. |
| `truthsuite/runtime/TS_RuntimeAccountingReplayConflictExecutableCoverageTest.java` | 1242 | Illustrates the size and density of the runtime executable-coverage cluster. |
| `e2e/accounting/ProcureToPayE2ETest.java` | 1114 | Broad flow test likely to produce noisy style debt if left outside a baseline strategy. |
| `codered/CR_PurchasingToApAccountingTest.java` | 1052 | High-value scenario test that should not be drowned by bulk style debt. |

### Concentration pattern

The rule concentration pattern is likely:

1. **Minor/style findings dominate in giant test files and giant service classes.**
2. **Major maintainability findings cluster in the very largest production cores.**
3. **Critical correctness/nullability findings, while fewer, are most likely in accounting/reconciliation/runtime-enforcement hotspots where a single issue has outsized blast radius.**

## Checkstyle and Qodana posture

### Checkstyle

- Configured via `maven-checkstyle-plugin` and `google_checks.xml`.
- Explicitly non-blocking: `failsOnError=false` and warning severity.
- Broad enough to create a very large legacy backlog.
- Most useful as a **changed-files hygiene gate**, not as a whole-repo fail condition.

### Qodana

- Configured with a relatively narrow but meaningful rule set.
- Excludes tests and migration/generated paths, which is good for noise control.
- Has strict thresholds (`critical: 0`, `any: 50`) but no execution path in GitHub Actions.
- Should become the **high-signal gate** for new production-code correctness/security/nullability findings.

## Quality gate strategy

The repo needs a baseline before it needs stricter thresholds.

### Baseline policy

#### Baseline capture

1. Run one full-repo Checkstyle report and one Qodana report on the default branch.
2. Store the machine-readable outputs as build artifacts (not checked into source).
3. Normalize them into a baseline keyed by at least:
   - file path
   - rule / inspection id
   - line (when stable)
   - severity/type bucket
4. Tag each baseline finding with one of the severity and type buckets above.

#### Baseline interpretation

- **Legacy blocker / critical** findings should be tracked explicitly and reduced first.
- **Legacy major** findings should be grouped by hotspot file/package.
- **Legacy minor/info** findings should not block merges globally.

### New-violations-only

The right first hardening step is **not** “fail the repo on 49k findings.” It is “fail PRs only on newly introduced high-risk findings.”

#### Recommended gate rules

| Condition | Gate behavior |
| --- | --- |
| New **blocker** finding of any type | **Fail immediately** |
| New **security** finding | **Fail immediately** |
| New **critical** correctness/nullability/wiring finding | **Fail immediately** |
| New **major** finding in hotspot packages (`modules/accounting/**`, `modules/sales/**`, `modules/inventory/**`, `modules/company/**`, `modules/reports/**`) | **Fail**, or require explicit approval override |
| New **major** finding outside hotspot packages | Warn initially, then ratchet to fail after baseline stabilizes |
| New **minor/info** finding on changed files | Warn or autofix; do not fail the whole PR initially |
| Pre-existing baseline finding untouched by the PR | Report only |

#### Practical implementation sequence

1. **Wire Qodana into CI first**, because it has the best signal-to-noise ratio.
2. **Filter Checkstyle to changed files**, or compare full output against the stored baseline and fail only on new entries.
3. **Fail only on new blocker/security/critical findings** for the first rollout.
4. **Add hotspot major-fail rules** once the team has one or two weeks of stable baseline data.
5. Keep the full legacy backlog visible in scheduled reporting, not in every PR failure.

## Recommended triage order

1. `modules/accounting/internal/**`
2. `modules/sales/service/**`
3. `modules/company/service/TenantRuntimeEnforcementService.java`
4. `modules/reports/service/**`
5. giant test files that create thousands of style-only findings

This order front-loads correctness-sensitive production hotspots and postpones the highest-volume style-noise cleanup until the team has a safe baseline.

## Overall judgment

The current static-analysis setup is **configured for governance but operated as advisory tooling**. The legacy backlog is likely dominated by low-severity style debt, while the smaller high-severity slice is underexposed because Qodana is not actually running in CI. The safest path is therefore:

1. capture a baseline,
2. turn on Qodana and changed-files Checkstyle,
3. block only new blocker/security/critical issues first,
4. then ratchet hotspot majors once the backlog is observable and stable.
