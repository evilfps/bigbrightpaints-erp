# User Testing

Validation surfaces, setup rules, and concurrency guidance for the platform-owner-first ERP hard-cut mission.

**What belongs here:** how validators should exercise the compose-backed backend, capture email artifacts, and verify repo-static contract truth.

---

## Validation Surfaces

### 1. API runtime validation
- **Type:** backend HTTP/API validation against the compose-backed runtime
- **Base URL:** `http://localhost:8081`
- **Actuator:** `http://localhost:9090/actuator/health`
- **MailHog UI/API:** `http://localhost:8025`
- **Tools:** `curl`, MailHog HTTP endpoints, targeted DB inspection only when a feature explicitly requires it

Representative journeys:
- platform login -> `GET /api/v1/auth/me` -> `/api/v1/superadmin/**`
- tenant onboarding -> MailHog credential capture -> tenant login -> `GET /api/v1/auth/me`
- shared self-service profile/password/reset/MFA flows
- platform support workspace and admin-recovery exception flows
- sales dealer/dashboard/promotions/order/credit flows

### 2. Targeted JVM proof packs
- **Type:** Maven unit/integration/truthsuite coverage for risky refactors and contracts
- **Working directory:** `erp-domain/`
- **Tools:** `mvn`, manifest commands from `.factory/services.yaml`
- **Use when:** a feature changes auth/security, company control plane, sales credit flows, or public contract DTO/controller behavior

### 3. Repo-static contract/governance validation
- **Type:** read-only repo inspection
- **Tools:** `Read`, `Grep`, `LS`, contract guard scripts
- **Use when:** a milestone validates OpenAPI/docs parity, retired routes, canonical docs, or docs-only cleanup behavior

## Validation Concurrency

- **api-runtime:** max concurrent validators **3**
- **jvm-proof:** max concurrent validators **1**
- **repo-static:** max concurrent validators **2**

Rationale:
- the dry run already showed the compose-backed runtime is workable and API validators are lightweight enough for three concurrent curl-driven flows on this machine
- MailHog and tenant onboarding are shared resources, so validators should isolate by tenant code and actor identity
- Maven proof packs share the same checkout and `target/` tree; serialize them
- repo-static checks are read-only and can safely run in small parallel batches

## Setup Steps

1. Run `.factory/init.sh`.
2. Start the approved compose boundary from `.factory/services.yaml` if it is not already healthy.
3. Verify:
   - `http://localhost:9090/actuator/health`
   - `http://localhost:9090/actuator/health/readiness`
   - `GET http://localhost:8081/api/v1/auth/me` returning `200`, `401`, or `403`
4. For onboarding or reset flows, use MailHog to capture the actual email artifact instead of inventing credentials or reset tokens.
5. Create isolated tenant codes per validator run when mutating shared runtime state.
6. For cleanup/doc assertions, skip runtime startup and inspect repo state directly.

## MailHog Guidance

- Prefer MailHog for:
  - onboarding first-admin temporary credentials
  - public forgot-password emails
  - platform-issued admin recovery reset emails
- Treat the email artifact as validation evidence.
- When proving latest-token-wins behavior, capture both email deliveries and demonstrate only the newest token succeeds.

## High-Signal Proof Packs

### Platform / onboarding / auth
- `platform-control-proof`
- `onboarding-proof`
- `self-service-proof`

### Sales
- `sales-dealers-proof`
- `sales-credit-proof`

### Contract cleanup
- `cleanup-contract-proof`
- `contract-guards`
- `docs-lint`

## Validator Guidance

- Prefer end-to-end API proof over source inspection when an assertion names a runtime route.
- For target-state routes introduced by this mission, do not fall back to retired aliases just because they still exist in pre-hard-cut code before the relevant milestone lands.
- Pair privacy-wall exception proofs with denied platform-owner calls to unrelated tenant business APIs.
- Pair canonical-route success proof with retired-route absence or fail-closed proof whenever a milestone retires an alias.
- Use the canonical tenant-admin approval decision route for credit decisions; direct module-specific decision routes belong only in cleanup-retirement checks.

## Known Constraints

- This repository may still contain pre-hard-cut docs/OpenAPI truth until the relevant cleanup milestones land.
- Some target-state routes in the validation contract do not exist yet; validators should only expect them after the features that claim those assertions complete.
- Runtime seed state can drift; prefer onboarding fresh tenants and MailHog-captured credentials over assuming long-lived seed actors are correct.
