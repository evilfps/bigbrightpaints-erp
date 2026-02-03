# Hydration Update Log

## 2026-02-03
- Scope: Block A (P0) Tenant Isolation + Public Attack Surface Hardening.
- Changes:
  - Swagger/OpenAPI prod hardening (disable /swagger-ui/** and /v3/** in prod).
  - Actuator prod hardening (health/info only; env info disabled).
  - CORS safe-by-default (reject wildcard origins; enforce https with localhost http exception).
- Tests:
  - `mvn -B -ntp -Dtest=CR_SwaggerProdHardeningIT test`
  - `mvn -B -ntp -Dtest=CR_ActuatorProdHardeningIT test`
  - `mvn -B -ntp -Dtest=SystemSettingsServiceCorsTest test`
  - `bash scripts/verify_local.sh`
- Notes:
  - `verify_local` still failing due to existing suite failures (see ErpInvariantsSuiteIT / OrchestratorControllerIT).
