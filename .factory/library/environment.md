# Environment

Environment variables, external dependencies, and setup notes.

**What belongs here:** required env vars, external dependencies, setup quirks, and runtime caveats.
**What does NOT belong here:** service ports/commands (use `.factory/services.yaml`).

---

## Required Environment Variables

- `SPRING_DATASOURCE_URL` / `SPRING_DATASOURCE_USERNAME` / `SPRING_DATASOURCE_PASSWORD`
- `JWT_SECRET` - minimum 32 bytes for JWT signing
- `ERP_SECURITY_ENCRYPTION_KEY` - minimum 32 bytes for encryption
- `ERP_SECURITY_AUDIT_PRIVATE_KEY` - required audit signing key for strict runtime
- `SPRING_MAIL_HOST` / `SPRING_MAIL_PORT` / `SPRING_MAIL_USERNAME` / `SPRING_MAIL_PASSWORD`
- `ERP_ENVIRONMENT_VALIDATION_ENABLED` / `erp.environment.validation.health-indicator.enabled` when strict readiness proof needs required-config health enabled
- `ERP_LICENSE_ENFORCE` when license enforcement is part of the strict readiness proof
- `ERP_DISPATCH_DEBIT_ACCOUNT_ID` / `ERP_DISPATCH_CREDIT_ACCOUNT_ID` when dispatch/accounting proofs require explicit mapping

## Profiles

- `prod,flyway-v2` - canonical strict runtime profile for this mission
- `mock,validation-seed` - optional helper profile only if a later cleanup feature explicitly adds deterministic auth/bootstrap proof

## Java / Maven

- Java 21
- Maven 3.8+
- Spotless / Google Java Format is enforced through the Maven build

## Runtime Notes

- Use Flyway v2 only for this mission.
- The approved repo-owned compose boundary is `5433` / `5672` / `8025` / `8081` / `9090`.
- Host Postgres `5432` is off-limits for mission runtime work.
- Run Maven from `erp-domain/` so `.mvn/settings.xml` and `.mvn/maven.config` resolve correctly.
- Direct `docker compose up` for dependency services still parses the app service, so datasource, JWT, encryption, and audit-key env vars must be present even when only starting `db`, `rabbitmq`, or `mailhog`.
- The plain strict compose runtime is a smoke surface, not a complete authenticated business-flow proof surface; use targeted Maven suites for business-flow validation unless a later mission feature adds a clean bootstrap/auth fixture path.
- Existing Docker volumes or stopped containers can carry stale validation state; if strict smoke fails for environmental reasons, reset the compose stack before assuming an application regression.

## Mission Validation Notes

- Canonical validation contract for this mission:
  - strict `prod,flyway-v2` compose smoke
  - targeted Maven suites for business-critical flow proof
- Do not rely on legacy seeded actors or old mission-specific reset flows unless a current feature explicitly re-establishes them as canonical proof.
- Docs-only packets in this mission do not require runtime or code validators.
- Cleanup packets that touch runtime/config/schema/tests must use the normal validation path before handoff.
