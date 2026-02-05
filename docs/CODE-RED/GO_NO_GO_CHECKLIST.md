# CODE-RED Go / No-Go Checklist (Deployment Gate)

Last updated: 2026-02-03

This is the single deploy gate for CODE-RED. If any **NO‑GO** condition is true, we do not ship.

Reference runbook:
- `docs/CODE-RED/release-plan.md`
- `erp-domain/docs/DEPLOY_CHECKLIST.md`

## GO (All Required)

Code + tests
- `bash scripts/verify_local.sh` is green on the release commit.
- On the release commit, run the CODE-RED scans in **fail-on-findings** mode (or record an explicit waiver):
  - `FAIL_ON_FINDINGS=true bash scripts/verify_local.sh`
- CI is green on the same commit (see `.github/workflows/ci.yml`).

Database safety
- Staging has a production-like dataset (restored snapshot or equivalent).
- Run `scripts/db_predeploy_scans.sql` on staging and it returns **zero rows**.
- Flyway migrations apply cleanly on staging; no checksum surprises.
- Flyway is forward-only: no edits to already-applied migrations (checksum drift is **NO-GO** unless proven safe and repaired).
- Flyway history matches repo expectations on staging (count + max version; see `docs/CODE-RED/release-plan.md`).

Production config safety (safe subset mode allowed)
- `JWT_SECRET` is set to a non-default value; DB credentials and SMTP credentials are production values (no placeholders like `changeme`).
- `ERP_LICENSE_KEY` / licensing config is present and correct for prod (see `erp-domain/docs/DEPLOY_CHECKLIST.md`).
- Orchestrator paths that can set `SHIPPED/DISPATCHED` without canonical dispatch are **OFF** (feature-flagged and tested).
- Swagger/OpenAPI is not publicly exposed in prod:
  - `/swagger-ui/**` and `/v3/**` are disabled or secured on a management port with auth.
- Actuator exposure is minimal and safe in prod:
  - prefer `health` + `info` only, and no detailed leak surfaces.
- CORS settings are safe-by-default:
  - no wildcard (`*`) origins when credentials are enabled; origins are explicit and validated.
- Enterprise auditability identifiers are in place:
  - requestId/traceId are consistently propagated (ingress → audit/outbox → errors), so we can answer “who did what” postmortems.
- Mutating/nondeterministic slip lookup (`/api/v1/dispatch/order/{orderId}`) is **OFF** or is read-only + fail-closed on ambiguity.
- Packing endpoints are **OFF** unless manufacturing idempotency + deterministic references + facade-owned posting are complete.
- Inventory→GL automation is **OFF** unless it is durable + observable + exactly-once (outbox-backed).

Smoke + soak
- Run `erp-domain/scripts/ops_smoke.sh` successfully.
- Actuator readiness is green: `GET /actuator/health/readiness` (DB + required config + broker).
- Integration health surfaces are **not** public:
  - `GET /api/integration/health` requires admin/ops auth.
- Orchestrator health endpoints are reachable with an authorized token:
  - `GET /api/v1/orchestrator/health/integrations`
  - `GET /api/v1/orchestrator/health/events`
- Monitor logs/outbox health for at least one business cycle (dispatch + settlement) without errors or retries piling up.

## NO-GO (Any One Blocks Shipping)

Predeploy scans
- `scripts/db_predeploy_scans.sql` returns any rows (unlinked slips/invoices/journals, duplicates, negative stock, closed-period drift/missing snapshots/late postings, etc.).

Workflow truth mismatch
- Any reachable endpoint can:
  - set `SHIPPED/DISPATCHED` without slip/invoice/journal/ledger linkage, OR
  - create journals without the canonical posting policy (`AccountingFacade` boundary), OR
  - mutate inventory/accounting from a GET or nondeterministic “most recent” selection.
- Any known double-post risk remains unresolved or ungated:
  - AR/Revenue can be posted twice due to reference namespace mismatch (canonical `INV-<orderNumber>` vs invoice-number ref).
  - COGS can be posted twice due to slip-vs-order reference mismatch (`COGS-<slipNumber>` vs `COGS-<orderNumber>`).
- Idempotency is not mismatch-safe:
  - if “return existing” can silently accept materially different payloads (amount/accounts/lines), it is NO-GO.

Operational risk
- Packing/production endpoints can be retried and double-consume or double-post.
- Period close/as-of correctness is not enforceable (closed periods can drift when “today” changes).
- Flyway history is inconsistent (checksum drift, missing versions, extra versions, or count/max mismatch).
- Public attack surface is unintentionally open:
  - Swagger/OpenAPI or detailed actuator endpoints are reachable without auth in prod.
  - CORS allows wildcard origins with credentials.
- Enterprise auditability is missing:
  - the system cannot link a critical write (dispatch/payment/journal) back to a stable requestId/traceId/user without parsing payloads.

## Rollback Rule (Always)

If something is wrong after deploy:
1) Roll back the application first.
2) Disable risky feature flags (orchestrator/packing/inventory→GL) if needed.
3) Re-run `scripts/db_predeploy_scans.sql` to assess impact.
4) Do not run ad-hoc manual SQL “fixes” under pressure; use an audited repair plan.
