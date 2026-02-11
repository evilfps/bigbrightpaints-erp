# Seed and Reference Data Strategy (Flyway v2)

## Decision

Reference/master seed data stays in application seeders (not Flyway repeatable data migrations).

## Why

- Existing deterministic seeders are already profile-gated and idempotent:
  - `DataInitializer` (`dev`, `seed`)
  - `BbpSampleDataInitializer` (`dev`, `test`)
  - `BenchmarkDataInitializer` (`benchmark`)
- This avoids shipping environment-specific sample data through schema migrations.
- The v2 chain remains schema-first and environment-neutral.

## v2 Profile Alignment

- `flyway-v2` profile points Flyway to `classpath:db/migration_v2` and uses `flyway_schema_history_v2`.
- `application-seed-flyway-v2.yml` enforces v2 datasource/flyway settings when both `seed` and `flyway-v2` are active.
- Default v2 seed database: `erp_domain_v2` (via `SPRING_DATASOURCE_V2_URL` fallback).

## Determinism Guardrails

- Seeders use lookup-or-create patterns keyed by business identifiers (company code, account code, role name, etc.).
- Company default account pointers are set only when missing, preventing destructive overrides.
- No production profile is used for sample/reference seeding.

## Runbook

- Dev + v2 schema only: `SPRING_PROFILES_ACTIVE=dev,flyway-v2`
- Seed + v2 deterministic seed data: `SPRING_PROFILES_ACTIVE=seed,flyway-v2`
- Test + v2 schema: `SPRING_PROFILES_ACTIVE=test,flyway-v2`

