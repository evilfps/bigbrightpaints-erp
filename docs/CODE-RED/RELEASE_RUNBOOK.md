# CODE-RED Release Runbook (Authoritative)

Last updated: 2026-02-05

Purpose: a single, authoritative, step-by-step runbook for CODE-RED releases.
This is the source of truth for release steps, gates, and failure criteria.

References
- Release plan and scope: `docs/CODE-RED/release-plan.md`
- Go/No-Go gate: `docs/CODE-RED/GO_NO_GO_CHECKLIST.md`
- Env var checklist: `erp-domain/docs/DEPLOY_CHECKLIST.md`

---

## 0) Preflight (Release Captain)

1) Confirm the release commit is clean.
Command: `git status -sb`
Expected: branch header with no local changes.
Fail if: any local modifications are present on the release commit.

2) Confirm the release gate exists and is executable.
Command: `test -x scripts/verify_local.sh`
Expected: exit code 0.
Fail if: missing or non-executable.

3) Run the full local gate on the release commit.
Command: `bash scripts/verify_local.sh`
Expected: output includes `[verify_local] OK` and Maven `BUILD SUCCESS`.
Fail if: any scan reports findings or the command exits non-zero.

4) Run strict scan mode (fail-on-findings).
Command: `FAIL_ON_FINDINGS=true bash scripts/verify_local.sh`
Expected: output includes `[verify_local] OK` with zero findings.
Fail if: any scan returns findings or non-zero exit.

---

## 1) Capture Flyway Expectations (Release Commit Evidence)

1) Record migration count and max version for this release.
Command: `ls -1 erp-domain/src/main/resources/db/migration | wc -l`
Expected: a single integer; record it as `expected_migration_count`.
Fail if: the command fails or output is not a single integer.

2) Record the highest migration version for this release.
Command: `ls -1 erp-domain/src/main/resources/db/migration | sed -n 's/^V\\([0-9]\\+\\)__.*$/\\1/p' | sort -n | tail -n 1`
Expected: a single integer; record it as `expected_max_version`.
Fail if: the command fails or output is not a single integer.

---

## 2) Production Backup (Pre-Release Safety)

1) Take a production backup before any deploy activity.
Command (example): `pg_dump --format=custom --no-owner --no-acl --file=backup_$(date +%F).dump "$PROD_DATABASE_URL"`
Expected: exit code 0 and a non-empty backup file.
Fail if: backup fails or the output file is missing/empty.

2) Store the backup off-host and encrypt at rest (per ops policy).
Expected: backup is accessible for restore testing.
Fail if: no confirmed backup exists before deploy.

---

## 3) Staging Snapshot Restore (Prod-Like Data)

1) Restore the latest approved production snapshot into staging.
Command (example): `pg_restore --clean --if-exists --no-owner --dbname "$STAGING_DATABASE_URL" backup_YYYY-MM-DD.dump`
Expected: restore completes successfully with exit code 0.
Fail if: restore errors or the staging DB cannot be queried afterward.

2) Verify the app boots against staging after restore.
Command: start the staging app (standard deploy workflow).
Expected: app starts and logs show Flyway migration validation and/or migration success.
Fail if: app fails to boot or Flyway reports validation errors.

---

## 4) Staging Migration Validation (Flyway Convergence Gate)

1) Validate Flyway history matches repo expectations on staging.
Command: `psql "$STAGING_DATABASE_URL" -c "SELECT count(*) FROM flyway_schema_history WHERE success = true;"`
Expected: `count` equals `expected_migration_count`.
Fail if: mismatch.

2) Validate Flyway max version on staging.
Command: `psql "$STAGING_DATABASE_URL" -c "SELECT max(version) FROM flyway_schema_history WHERE success = true;"`
Expected: `max` equals `expected_max_version`.
Fail if: mismatch.

---

## 5) Staging Predeploy Scans (NO-SHIP if Any Rows)

1) Run CODE-RED predeploy scans on staging.
Command: `psql "$STAGING_DATABASE_URL" -f scripts/db_predeploy_scans.sql`
Expected: zero rows returned for all scans.
Fail if: any rows are returned or the command exits non-zero.

---

## 6) Staging Smoke Test

1) Run the operator smoke script against staging.
Command: `erp-domain/scripts/ops_smoke.sh`
Expected: exit code 0.
Fail if: any smoke step fails.

---

## 7) Staging Monitoring + Soak Gate

1) Confirm health endpoints are reachable and return expected fields.
Command: `curl -fsS "$BASE_URL/actuator/health"`
Expected: HTTP 200 with `"status":"UP"`.
Fail if: non-200 or missing status.

Command: `curl -fsS -H "Authorization: Bearer $OPS_TOKEN" "$BASE_URL/api/integration/health"`
Expected: HTTP 200 with `"status":"UP"` and a `timestamp`.
Fail if: non-200 or missing status/timestamp.

Command: `curl -fsS -H "Authorization: Bearer $OPS_TOKEN" "$BASE_URL/api/v1/orchestrator/health/integrations"`
Expected: HTTP 200 with keys `orders`, `plans`, `accounts`, `employees`.
Fail if: non-200 or missing keys.

Command: `curl -fsS -H "Authorization: Bearer $OPS_TOKEN" "$BASE_URL/api/v1/orchestrator/health/events"`
Expected: HTTP 200 with keys `pendingEvents`, `retryingEvents`, `deadLetters`.
Fail if: non-200 or missing keys.

2) Validate outbox health is stable.
Expected: `deadLetters == 0` and `retryingEvents` is not growing unbounded.
Fail if: dead letters > 0 or retry backlog grows during soak.

3) Soak the system for at least one business cycle.
Command (example log scan): `rg -n \"ERROR|Exception|dead_letter\" logs/erp-backend.log`
Expected: no new error spikes in posting/idempotency/outbox during the soak window.
Fail if: new error bursts or repeated idempotency conflicts appear.

---

## 8) Production Deploy (After Staging Is Green)

1) Deploy the release artifact to production using the standard deploy pipeline.
Expected: app boots cleanly; Flyway validates without errors.
Fail if: any deploy step fails, or Flyway validation fails.

2) Re-run predeploy scans on production (if access is available).
Command: `psql "$PROD_DATABASE_URL" -f scripts/db_predeploy_scans.sql`
Expected: zero rows.
Fail if: any rows are returned or the command exits non-zero.

---

## 9) Documentation Evidence (Required)

1) Attach the release evidence to the release ticket:
- `expected_migration_count` and `expected_max_version`
- Staging scan results (zero rows)
- `bash scripts/verify_local.sh` output summary (OK + BUILD SUCCESS)

2) If any step fails, stop and open a controlled fix plan.
Do not proceed to production if any NO-GO criteria are triggered.
