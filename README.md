# BigBright ERP – Unified Backend

Single Spring Boot 3.3 service (`erp-domain`) that exposes every API the React frontend needs: auth, admin/RBAC, multi-company, inventory, sales, accounting, factory, HR/payroll, reporting, and orchestrated workflows. The legacy orchestrator module is merged into this app, so deploying one jar/container is enough.

## Prerequisites

- Java 21 (Temurin or Adoptium)
- Maven 3.9.x
- Docker Desktop (Linux engine) for Testcontainers and the compose stack

## Build & Test

```bash
# compile + package (skips tests – fast feedback)
mvn -q -f erp-domain/pom.xml -DskipTests package

# full integration suite (requires Docker running)
mvn -q -f erp-domain/pom.xml test
```

Tests spin up Postgres 16 via Testcontainers and mock RabbitMQ, so Docker must be available even on CI.

## Codex Cloud / Remote Docker

When you need Docker/Testcontainers in Codex Cloud (or anywhere without a local daemon), use the Docker-in-Docker harness:

```
./scripts/run-tests-cloud.sh
# run a subset
./scripts/run-tests-cloud.sh mvn -B -pl erp-domain -am -Dtest="*SmokeTest" test
```

Details live in `docs/CLOUD_CONTAINERS.md`.

## Local Development

```bash
cd erp-domain

# Docker/compose profile (default: dev)
SPRING_PROFILES_ACTIVE=dev ./mvnw spring-boot:run

# Local Postgres profile (bigbright_erp / postgres / Anas@2627)
SPRING_PROFILES_ACTIVE=local ./mvnw spring-boot:run
```

- `dev` profile uses the Docker-compose Postgres (`erp/erp`).
- `local` profile is pre-wired for your Windows installation (`bigbright_erp`, user `postgres`, password `Anas@2627`).
- Both profiles seed the same admin user (`admin@bbp.dev` / `ChangeMe123!`).

## Docker & Compose

```
docker compose up -d --build
```

This starts:

- `erp_db` (Postgres 16)
- `erp_rabbit` (RabbitMQ management)
- `erp_domain_app` (Spring Boot jar; exposes 8081)

### Seed Sample Data

```
./scripts/seed-dev.sh        # macOS/Linux
pwsh ./scripts/seed-dev.ps1  # Windows
```

Seeds baseline companies/users/sales/orders for quick smoke testing.

## API Docs

Once the app is running:

- Swagger UI: `http://localhost:8081/swagger-ui/`
- OpenAPI JSON: `http://localhost:8081/v3/api-docs`
- OpenAPI YAML: `http://localhost:8081/v3/api-docs.yaml`

Use these endpoints to generate typed frontend clients.

### CORS

By default the backend allows requests from `http://localhost:3002` (React dev server). Override by setting `ERP_CORS_ALLOWED_ORIGINS` before starting the app or Docker stack, e.g.:

```bash
export ERP_CORS_ALLOWED_ORIGINS="http://localhost:3002,http://localhost:5173"
docker compose up -d
```

Multiple origins can be comma-separated. Methods (`GET/POST/PUT/PATCH/DELETE/OPTIONS`), headers, and credentials are permitted globally.

## Multi-factor Authentication

TOTP-based MFA is baked into the auth module:

- `POST /api/v1/auth/mfa/setup` (auth required) issues a secret + `otpauth://` URI + one-time recovery codes.
- `POST /api/v1/auth/mfa/activate` finalizes enrollment once the user submits a valid 6-digit code.
- `POST /api/v1/auth/mfa/disable` clears the secret after verifying a fresh code or recovery code.
- `/api/v1/auth/login` now accepts optional `mfaCode`/`recoveryCode` fields and requires one whenever the account has MFA enabled.
- Frontend/admin-console wiring details live in `docs/admin-console-mfa.md`.

## Flowcharts & Deep‑Dive

- Visual flowcharts and inch‑by‑inch backend walkthrough: `docs/flowcharts.md`
- Human demo (ChatGPT buys 500 buckets): `docs/demo-chatgpt-500-buckets.md`

## Orchestrated Workflows

High-level flows are exposed under `/api/v1/orchestrator/**`:

- `POST /orders/{id}/approve`: reserves inventory, queues production, posts journals, emits outbox event.
- `POST /factory/dispatch/{batchId}`: marks batches as dispatched, posts accounting entry.
- `POST /payroll/run`: syncs HR data, creates payroll run, posts journals.

`IntegrationCoordinator` calls the underlying modules directly, so workflows are consistent with manual endpoints.

## Continuous Integration

`.github/workflows/erp-domain-ci.yml` runs on push/PR to `main`/`master`:

1. `actions/setup-java` (Temurin 21) with Maven cache
2. `mvn -B -pl erp-domain -am test` (Docker is available on ubuntu-latest runners, so Testcontainers works out of the box)

## Useful Commands

```bash
# Start Postgres + Rabbit + app
docker compose up -d --build

# Tail logs
docker compose logs -f app

# Tear down stack
docker compose down -v

# Seed dev data after stack is up
./scripts/seed-dev.sh
```

## Credentials (dev/demo)

- Admin: `admin@bbp.dev` / `ChangeMe123!`
- Switch company via `POST /api/v1/multi-company/companies/switch` (`{"companyCode":"BBP"}`)

Update `application-prod.yml`/environment variables before deploying to real environments (JWT secrets, DB passwords, Rabbit credentials, etc.).
