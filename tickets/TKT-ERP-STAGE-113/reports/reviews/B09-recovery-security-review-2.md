# B09 Recovery Security Review 2

Ticket: `TKT-ERP-STAGE-113`
Branch: `tickets/tkt-erp-stage-113/b09-orchestrator-correlation-sanitization-recovery`
Worktree: `/Users/anas/Documents/orchestrator_erp/bigbrightpaints-erp_worktrees/TKT-ERP-STAGE-113/blocker-remediation-orchestrator_worktrees/TKT-ERP-STAGE-113/B09-orchestrator-correlation-sanitization-recovery`
Reviewer: `security-governance`
Date: `2026-02-26`
Reviewed HEAD: `355ca643bc9dfbb1f82c5db9140caf95f2fb36ae`

## Findings (Ordered by Severity)

### MEDIUM - Evidence SHA metadata is inconsistent with the reviewed remediation commit
- Impact:
  - Governance traceability is not cryptographically consistent: the evidence file claims a different full SHA than the actual branch head.
  - Merge/release reviewers cannot prove that recorded command outcomes were captured on the exact reviewed artifact without manual reconciliation.
- Anchors:
  - `tickets/TKT-ERP-STAGE-113/reports/evidence/B09-recovery-checks.md:7`
  - `tickets/TKT-ERP-STAGE-113/reports/evidence/B09-recovery-checks.md:8`
  - `git rev-parse HEAD` -> `355ca643bc9dfbb1f82c5db9140caf95f2fb36ae`
- Minimal remediation plan:
  - Update `B09-recovery-checks.md` SHA fields (`Remediation commit`, `Resulting HEAD`) to the actual full HEAD SHA.
  - Add one inline command transcript line in the evidence file (`git rev-parse HEAD`) so the SHA in metadata and executed evidence stay coupled.
- Required verification steps to prove closure:
  1. Run `git rev-parse HEAD` in this worktree and record the value.
  2. Run `rg -n "Remediation commit|Resulting HEAD" tickets/TKT-ERP-STAGE-113/reports/evidence/B09-recovery-checks.md` and confirm both lines match the `git rev-parse HEAD` output exactly.
  3. Confirm expected ancestry still holds: `git merge-base --is-ancestor 355ca643bc9dfbb1f82c5db9140caf95f2fb36ae HEAD` exits `0`.

## Validated Controls (No Additional Findings)

### Malformed `orderId` paths fail-closed for approve/fulfillment with no outbox/audit side effects
- Verification status: **PASS**
- Anchors:
  - `erp-domain/src/main/java/com/bigbrightpaints/erp/orchestrator/service/IntegrationCoordinator.java:149`
  - `erp-domain/src/main/java/com/bigbrightpaints/erp/orchestrator/service/IntegrationCoordinator.java:303`
  - `erp-domain/src/main/java/com/bigbrightpaints/erp/orchestrator/service/IntegrationCoordinator.java:739`
  - `erp-domain/src/test/java/com/bigbrightpaints/erp/orchestrator/OrchestratorControllerIT.java:453`
  - `erp-domain/src/test/java/com/bigbrightpaints/erp/orchestrator/OrchestratorControllerIT.java:479`
  - `erp-domain/src/test/java/com/bigbrightpaints/erp/orchestrator/service/CommandDispatcherTest.java:120`
  - `erp-domain/src/test/java/com/bigbrightpaints/erp/orchestrator/service/CommandDispatcherTest.java:194`
- Evidence:
  - `cd erp-domain && mvn -B -ntp -Dtest='OrchestratorControllerIT,IntegrationCoordinatorTest,CommandDispatcherTest,TS_RuntimeOrchestratorCorrelationCoverageTest' test` -> `Tests run: 71, Failures: 0, Errors: 0, Skipped: 0`, `BUILD SUCCESS`.
  - Integration tests explicitly assert `400` and unchanged outbox/audit counters for malformed `orderId` on both endpoints.

### Malformed identifier logs are sanitized (no raw control-character payloads)
- Verification status: **PASS**
- Anchors:
  - `erp-domain/src/main/java/com/bigbrightpaints/erp/orchestrator/service/IntegrationCoordinator.java:757`
  - `erp-domain/src/main/java/com/bigbrightpaints/erp/orchestrator/service/CorrelationIdentifierSanitizer.java:88`
  - `erp-domain/src/test/java/com/bigbrightpaints/erp/orchestrator/service/IntegrationCoordinatorTest.java:264`
- Evidence:
  - Runtime log path now emits `Rejected non-numeric identifier [<sanitized-or-invalid-fingerprint>]` via sanitizer helper.
  - Unit test asserts parse-failure logs do not contain raw payload (`abc`, `injected`, or newline) and instead contain sanitized `invalid#...` fingerprint output.

## Command Evidence
- `git rev-parse HEAD` -> `355ca643bc9dfbb1f82c5db9140caf95f2fb36ae`
- `git merge-base --is-ancestor 355ca643bc9dfbb1f82c5db9140caf95f2fb36ae HEAD` -> exit `0`
- `bash scripts/guard_orchestrator_correlation_contract.sh` -> `[guard_orchestrator_correlation_contract] OK`
- `cd erp-domain && mvn -B -ntp -Dtest='OrchestratorControllerIT,IntegrationCoordinatorTest,CommandDispatcherTest,TS_RuntimeOrchestratorCorrelationCoverageTest' test` -> `BUILD SUCCESS`
- `cd erp-domain && mvn -B -ntp -Dtest='*Orchestrator*' test` -> `Tests run: 65, Failures: 0, Errors: 0, Skipped: 0`, `BUILD SUCCESS`
- `python3 scripts/changed_files_coverage.py --diff-base tickets/tkt-erp-stage-113/blocker-remediation-orchestrator --jacoco erp-domain/target/site/jacoco/jacoco.xml` -> `passes=true`, `line_ratio=0.9607843137254902`, `branch_ratio=0.9411764705882353`
- `bash ci/check-architecture.sh` -> `[architecture-check] OK`
- `bash ci/check-enterprise-policy.sh` -> `[enterprise-policy] OK`

## Residual Risks / Testing Gaps
- Endpoint malformed-path integration tests currently assert representative malformed token `abc`; encoded control-character path variants (`%0A`) and overlong path identifiers are not explicitly covered in `OrchestratorControllerIT` yet.
- Changed-files coverage is suite-sensitive in this branch: running the narrower targeted test set alone produced a temporary threshold miss (`line_ratio=0.9281`) until the broader `*Orchestrator*` suite was rerun.
