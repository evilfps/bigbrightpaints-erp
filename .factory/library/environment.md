# Environment

Environment variables, external dependencies, and setup notes.

**What belongs here:** Required env vars, external API keys/services, dependency quirks, platform-specific notes.
**What does NOT belong here:** Service ports/commands (use `.factory/services.yaml`).

---

## Required Environment Variables
- `JWT_SECRET` - Minimum 32 bytes for HMAC-SHA256 JWT signing
- `ERP_SECURITY_ENCRYPTION_KEY` - Minimum 32 bytes for AES-256-GCM encryption
- `SPRING_DATASOURCE_URL` - PostgreSQL connection URL
- `SPRING_DATASOURCE_USERNAME` / `SPRING_DATASOURCE_PASSWORD` - DB credentials
- `ERP_DISPATCH_DEBIT_ACCOUNT_ID` / `ERP_DISPATCH_CREDIT_ACCOUNT_ID` - Dispatch journal mapping

## Profiles
- `dev` - Local development (H2 fallback, swagger public, relaxed security)
- `prod` - Production (strict security, env-driven config, no swagger)
- `test` - Test (separate DB, disabled auto-approval)
- `flyway-v2` - Flyway migration location override (included in prod group)
- `seed` - Bootstrap initial data (use once then switch to prod)
- `benchmark` - Performance testing (skips date validation)

## Java/Maven
- Java 21 (OpenJDK 21.0.10)
- Maven 3.8.7
- Annotation processors: MapStruct 1.5.5.Final, Lombok 1.18.32

## Security/Auth Mission Notes
- Runtime validation for this mission expects the local PostgreSQL dependency to be available before starting the backend.
- Mail and broker dependencies are only needed when a feature or validator requires runtime-assisted evidence.

## ERP Truth-Stabilization Mission Notes
- Treat Flyway `migration_v2` as the only valid migration track for this mission; do not inspect, modify, or depend on v1 migrations.
- The approved review and integration base is `origin/Factory-droid`.
- Mission-owned compose runtime must use host PostgreSQL port `5433`; host port `5432` belongs to another local database.
- Custom project droids are the approved delegation path for orchestrator-side subagent work in this mission.

## Catalog Surface Consolidation Notes
- Primary validation path for this packet is Maven plus Testcontainers-backed API/integration evidence.
- Use the compose-backed runtime on `5433/8081/9090` only when the feature needs explicit runtime API probes.
- Do not use or depend on the unrelated local PostgreSQL instance on `5432`.
