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
- The helper/subagent model alias is currently unreliable; workers should rely on direct compile/test/curl fallback when delegation fails.
