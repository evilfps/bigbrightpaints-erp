# Environment

Environment variables, external dependencies, and setup notes.

**What belongs here:** required env vars, external dependencies, setup quirks, and runtime caveats.
**What does NOT belong here:** service ports/commands (use `.factory/services.yaml`).

---

## Required Environment Variables

- `JWT_SECRET` - minimum 32 bytes for JWT signing
- `ERP_SECURITY_ENCRYPTION_KEY` - minimum 32 bytes for encryption
- `SPRING_DATASOURCE_URL` / `SPRING_DATASOURCE_USERNAME` / `SPRING_DATASOURCE_PASSWORD`
- `ERP_DISPATCH_DEBIT_ACCOUNT_ID` / `ERP_DISPATCH_CREDIT_ACCOUNT_ID` when dispatch/accounting proofs need explicit mapping

## ERP-38 Runtime Notes

- Run everything from the dedicated ERP-38 worktree, not the shared checkout.
- Resolve repo-root paths dynamically with `git rev-parse --show-toplevel`.
- Run Maven from `erp-domain/` inside the worktree so `.mvn/settings.xml` and `.mvn/maven.config` resolve correctly.
- Use Flyway v2 only. Do not inspect or modify v1 migrations for this packet.
- The repo-owned compose runtime uses host PostgreSQL port `5433`; host `5432` belongs to another local database.
- Direct `docker compose up` for dependency services still parses the app service, so `JWT_SECRET` and `ERP_SECURITY_ENCRYPTION_KEY` must be present even when only starting `db`, `rabbitmq`, or `mailhog`.
- The plain `prod,flyway-v2` compose app can boot against an empty database; use the reset harness when authenticated runtime validation is required.
- Stale stopped containers from another worktree can block the reset harness because the compose stack reuses fixed names (`erp_db`, `erp_rabbit`, `erp_mailhog`, `erp_domain_app`). Remove the stale stopped containers before rerunning if the reset fails on name conflicts.

## Profiles

- `prod,flyway-v2` - canonical runtime profile for ERP-38 validation
- `mock,validation-seed` - additional seeded validation actors when using the reset harness

## Java / Maven

- Java 21
- Maven 3.8+
- Spotless / Google Java Format is enforced through the Maven build

## Validation Setup Notes

- Prefer `bash scripts/reset_final_validation_runtime.sh` before authenticated API validation.
- If you need deterministic validation passwords, export `ERP_VALIDATION_SEED_PASSWORD` before running the reset harness.
- Use the seeded actors documented in `.factory/library/user-testing.md` for runtime probes.
- `bash scripts/gate_release.sh` expects a local `harness-engineering-orchestrator` ref. If it fails with `canonical base ref 'harness-engineering-orchestrator' was not found`, bootstrap it with `git fetch origin harness-engineering-orchestrator:harness-engineering-orchestrator` before rerunning.
- Failed `gate-fast` / `gate-release` / `gate-reconciliation` runs can leave disposable generated outputs under `artifacts/gate-release/` or `artifacts/gate-reconciliation/`; clean those before handoff so only intentional source changes remain in `git status`.
