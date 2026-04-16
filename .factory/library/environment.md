# Environment

Environment variables, external dependencies, and setup notes for the platform-owner-first ERP hard-cut mission.

**What belongs here:** required env vars, runtime dependencies, MailHog/bootstrap quirks, and local validation caveats.
**What does NOT belong here:** service ports/commands (use `.factory/services.yaml`).

---

## Required Environment Variables

- `SPRING_DATASOURCE_URL` / `SPRING_DATASOURCE_USERNAME` / `SPRING_DATASOURCE_PASSWORD`
- `JWT_SECRET`
- `ERP_SECURITY_ENCRYPTION_KEY`
- `ERP_SECURITY_AUDIT_PRIVATE_KEY`
- `SPRING_MAIL_HOST` / `SPRING_MAIL_PORT` / `SPRING_MAIL_USERNAME` / `SPRING_MAIL_PASSWORD`
- `ERP_CORS_ALLOWED_ORIGINS`
- `ERP_ENVIRONMENT_VALIDATION_HEALTH_INDICATOR_SKIP_WHEN_VALIDATION_DISABLED`

## Profiles

- `prod,flyway-v2` — compose-backed validation/runtime profile for this mission
- current local seeded runtime may still use `validation-seed`; prefer fresh onboarding + MailHog capture over assuming static seeded actors are correct

## Java / Maven

- Java 21
- Maven 3.8+
- Run Maven from `erp-domain/`
- Spotless / Google Java Format is enforced through the Maven build

## Runtime Notes

- Approved runtime boundary: Postgres `5433`, RabbitMQ `5672`, MailHog `1025` / `8025`, app `8081`, actuator `9090`
- Host Postgres `5432` is off-limits for mission runtime work
- Direct `docker compose up` still parses the app service, so auth/audit/mail env vars must exist even when only starting dependency services
- MailHog is part of the mission-critical validation path for onboarding and reset flows
- Do not invent bootstrap passwords or reset tokens; capture the actual email artifact

## Mission Validation Notes

- Phase-one billing truth is manual billing-plan state managed by the superadmin control plane
- Shared self-service should validate across platform, tenant, and dealer scopes
- Platform support workspace and shared profile-update routes are target-state surfaces for this mission and may not exist until the corresponding milestones land
- Cleanup validation should treat docs/OpenAPI parity and retired-route truth as first-class deliverables, not optional follow-up
