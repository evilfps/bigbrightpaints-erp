# BigBright ERP – Backend

## Prerequisites
- Java 21
- Maven 3.9+
- PostgreSQL (e.g., `bigbright_erp` DB)
- Docker (for Testcontainers/integration tests)

## Run locally
```bash
cd erp-domain
set SPRING_DATASOURCE_URL=jdbc:postgresql://localhost:5432/bigbright_erp
set SPRING_DATASOURCE_USERNAME=postgres
set SPRING_DATASOURCE_PASSWORD=Anas@2627
set SPRING_PROFILES_ACTIVE=dev
mvn spring-boot:run
```
Adjust the datasource/user/password to your environment. The `dev` profile now seeds critical fixtures (periods, GL accounts, dealer/supplier, FG stock) at startup.

### Critical configuration
- JWT secrets must be at least 32 bytes (`JWT_SECRET`), otherwise startup will fail. CI should export a strong `JWT_SECRET` (32+ bytes) to avoid fail-fast during tests.
- If licensing is enabled (`erp.licensing.enforce=true`), set `ERP_LICENSE_KEY` and `ERP_LICENSE_PRODUCT_ID`; for local/dev runs, keep `erp.licensing.enforce=false`.
- Dispatch journals now use server-side account mapping. Configure `ERP_DISPATCH_DEBIT_ACCOUNT_ID` and `ERP_DISPATCH_CREDIT_ACCOUNT_ID` (per profile) to re-enable dispatch postings.
- Outbox publishing now uses ShedLock (JDBC). Ensure the `shedlock` table is present in the ERP database (see ShedLock JDBC schema) so distributed locking works.
- Actuator dispatch mapping health: reports WARN when dispatch debit/credit account IDs are not set (dispatch journals will be skipped).
- Tenant header: all API calls must include the company/tenant header expected by the gateway (e.g., `X-Company-Id`); requests without it will fail downstream. Enforce this at the gateway to avoid 4xx/5xx inside services.
- SMTP credentials: production requires real SMTP password; default `changeme` is rejected at startup.

## Flyway note
There was a gap at V42; a placeholder (`V42__placeholder.sql`) was added. If your database ran higher migrations already, run once with out-of-order to record it:
```bash
mvn -f erp-domain/pom.xml -DskipTests ^
  "-Dflyway.url=jdbc:postgresql://localhost:5432/bigbright_erp" ^
  "-Dflyway.user=postgres" "-Dflyway.password=Anas@2627" ^
  "-Dflyway.outOfOrder=true" flyway:migrate
```

## Build without tests
```bash
mvn -q -f erp-domain/pom.xml -DskipTests package
```

## Regenerate API clients (OpenAPI)
Install the generator locally if you don't have it:
```bash
npm install @openapitools/openapi-generator-cli
```
Generate a Typescript client example:
```bash
npx @openapitools/openapi-generator-cli generate \
  -i openapi.yaml \
  -g typescript-fetch \
  -o clients/typescript
```
Adjust the generator (`-g`) and output path as needed for other SDKs.

## Cloud/Testcontainers
Use `scripts/run-tests-cloud.sh` to run the full suite in a Docker-in-Docker harness (see `docs/CLOUD_CONTAINERS.md`).
