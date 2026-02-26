# COVERAGE REMEDIATION REPORT

Ticket: `TKT-ERP-STAGE-113`
Role: `qa-reliability`
Date: `2026-02-26`

## 1) Blocker Statement
`changed_files_coverage` was failing on the integrated branch with low changed-branch coverage in `EventPublisherService.java` (`line_ratio=0.8673`, `branch_ratio=0.6410` from pre-remediation baseline).

## 2) Remediation Actions
- Added focused `EventPublisherServiceTest` scenarios for all uncovered changed branches/lines requested in the remediation brief:
  - publish loop exception handling,
  - stale reclaim branches (null id, reclaim exception, lease-not-due, fence mismatch, dead-letter),
  - markPublished/hold/schedule short-circuit and fence-mismatch behavior,
  - ambiguity marker variants,
  - deterministic retry classifier variants,
  - constructor parse-duration invalid and fallback branches,
  - meter registry registration/evaluation path.
- Maintained production code unchanged.

## 3) Verification Command Log
Executed exactly:

1. `cd erp-domain && mvn -B -ntp -Dtest='EventPublisherServiceTest,TS_OrchestratorExactlyOnceOutboxTest,TS_RuntimeEventPublisherExecutableCoverageTest,AuthTenantAuthorityIT,RoleServiceRbacTenantIsolationTest' test`
   Result: `PASS` (`Tests run: 64, Failures: 0, Errors: 0, Skipped: 0`)

2. `bash ci/check-architecture.sh`
   Result: `PASS`

3. `bash ci/check-enterprise-policy.sh`
   Result: `PASS`

4. `bash ci/check-orchestrator-layer.sh`
   Result: `PASS`

5. `python3 scripts/changed_files_coverage.py --diff-base origin/harness-engineering-orchestrator --jacoco erp-domain/target/site/jacoco/jacoco.xml`
   Result: `PASS`

## 4) Coverage Evidence
Final changed-files coverage summary:
- `files_considered=5`
- `line_covered=211`
- `line_total=211`
- `line_ratio=1.0000` (threshold `0.95`)
- `branch_covered=78`
- `branch_total=78`
- `branch_ratio=1.0000` (threshold `0.90`)
- `passes=true`

Per-file critical remediation target:
- `erp-domain/src/main/java/com/bigbrightpaints/erp/orchestrator/service/EventPublisherService.java`
  - `line_ratio=1.0000`
  - `branch_ratio=1.0000`

## 5) QA Verdict
**GO** for coverage gate closure on the integrated branch.

## 6) Residual Risks
- Integration suites rely on Docker/Testcontainers; results are environment-sensitive if Docker becomes unavailable.
- The changed-files report still lists unmapped changed lines in files with non-executable/structural deltas; this is informational and not blocking because executable line/branch thresholds are fully satisfied.

## 7) Isolation Hints For Future Regression
If coverage regresses again in `EventPublisherService`, first isolate branch misses in:
- `isPublishable` status/dead-letter/time branches,
- stale reclaim guard branches,
- hold/retry guard branches,
- ambiguity marker classifier paths,
- parseDuration null/blank/invalid paths.
These branches now have direct unit-test anchors in `EventPublisherServiceTest`.
