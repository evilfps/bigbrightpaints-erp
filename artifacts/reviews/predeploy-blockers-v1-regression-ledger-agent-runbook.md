# Regression Ledger Agent Runbook

## Purpose
Track regressions discovered during commit-by-commit review, then prove whether each regression still exists at branch `HEAD` or was fixed by later commits.

Primary ledger file:
- `artifacts/reviews/predeploy-blockers-v1-regression-ledger.tsv`

Related commit inventory:
- `artifacts/reviews/predeploy-blockers-v1-commit-ledger.tsv`

## Required policy
1. Never close a regression entry based on reading code only.
2. Every regression must have:
   - an executable reproducer test name
   - an exact verification command
   - bad commit and fix commit fields (or `pending` until proven)
3. If bad/fix commit is unknown, mark as `pending` and keep `head_status=OPEN`.

## Ledger column contract
1. `regression_id`: stable key (`REG-YYYY-MM-DD-NNN`).
2. `severity`: `P0|P1|P2|P3`.
3. `regression_name`: one-line business-meaningful title.
4. `introduced_by_commit`: first commit proven to contain regression (`pending` allowed initially).
5. `introduced_by_subject`: exact commit subject.
6. `discovered_in_commit`: commit under review when issue was detected.
7. `discovered_on`: date in `YYYY-MM-DD`.
8. `fixed_by_commit`: first commit proven to remove regression (`pending` until proven).
9. `fixed_by_subject`: exact commit subject.
10. `head_status`: `OPEN|FIXED_UNVERIFIED|FIXED_VERIFIED|NOT_REPRODUCIBLE`.
11. `affected_modules`: comma-separated modules.
12. `evidence_paths`: `path:line;path:line`.
13. `reproducer_test`: `Class#method`.
14. `verification_command`: exact command.
15. `impact_summary`: business/accounting/operational impact.
16. `owner`: reviewer/agent handle.
17. `notes`: root-cause and caveats.

## Standard workflow per regression
1. Add initial row immediately when found.
2. Write/identify deterministic reproducer test and fill `reproducer_test` + `verification_command`.
3. Identify bad commit.
   - Use `git bisect run` with the reproducer command.
4. Identify first good commit after bad commit.
   - Walk forward commit-by-commit until reproducer first passes.
5. Verify at current `HEAD`.
   - Run reproducer + impacted module tests.
6. Update ledger row.
   - Fill fixed commit fields.
   - Set `head_status=FIXED_VERIFIED` only when tests pass at `HEAD`.

## Command snippets
Find open items:
```bash
awk -F'\t' 'NR==1 || $10=="OPEN" || $10=="FIXED_UNVERIFIED"' \
  artifacts/reviews/predeploy-blockers-v1-regression-ledger.tsv
```

Run one reproducer:
```bash
cd erp-domain
mvn -B -ntp -Dtest=<ClassName> test
```

Bisect for first bad commit:
```bash
git bisect start HEAD <known-good-commit>
git bisect run bash -lc 'cd erp-domain && mvn -q -Dtest=<ClassName> test'
git bisect reset
```

## Review output requirement (for each commit reviewed)
1. Commit hash + subject.
2. Regressions introduced/impacted (`regression_id` list).
3. Evidence paths.
4. Whether each regression is still active at `HEAD`.
5. If fixed later, include fix commit hash + subject.

## Guardrails
1. Do not overwrite existing rows; append new rows or update exact matching `regression_id`.
2. Do not mark `FIXED_VERIFIED` without executable evidence.
3. If evidence conflicts, set `head_status=FIXED_UNVERIFIED` and document blocker in `notes`.
