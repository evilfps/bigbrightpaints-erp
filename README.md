# BigBright ERP Backend

## 1) Project Overview
BigBright ERP is a multi-company ERP backend for manufacturers and distribution teams. It handles the core business flows end-to-end: order-to-cash, procure-to-pay, manufacturing/packing, inventory control, accounting, HR/payroll, and reporting.

It is designed for:
- Backend engineers extending ERP modules
- Frontend teams integrating against REST APIs
- Ops teams running production-grade deployments

---

## 2) Tech Stack
- **Java 21**
- **Spring Boot 3.3.4**
- **PostgreSQL 16**
- **RabbitMQ**
- **Flyway** (v2 migration track active in production profiles)
- **Maven** for build/test lifecycle
- **Docker / Docker Compose** for local and deployment orchestration

---

## 3) Prerequisites
Install these before running locally:
- Java 21 (`java -version`)
- Maven 3.8+ (`mvn -version`)
- PostgreSQL 16 (or use Docker Compose service)
- Docker + Docker Compose (`docker compose version`)

---

## 4) Local Setup
1. **Clone and enter the repository**
   ```bash
   git clone <your-repo-url> Mission-control
   cd Mission-control
   ```

2. **Create local environment file**
   ```bash
   cp .env.example .env
   ```
   Set at least:
   - `JWT_SECRET` (32+ byte secret)
   - `ERP_SECURITY_ENCRYPTION_KEY` (32+ byte key)

3. **Run the repository bootstrap**
   ```bash
   bash .factory/init.sh
   ```

4. **Start local dependencies**
   ```bash
   DB_PORT=5433 docker compose up -d db rabbitmq mailhog
   ```
   > Mission/local validation is **Flyway v2 only** and reserves host PostgreSQL port `5433`. Do not use `5432` for this repo's compose-backed runtime.

5. **Compile**
   ```bash
   cd erp-domain
   MIGRATION_SET=v2 mvn compile -q
   ```

6. **Run tests**
   ```bash
   MIGRATION_SET=v2 mvn test -Pgate-fast -Djacoco.skip=true
   ```

7. **(Optional) Run the compose-backed app with the same Flyway v2 profile used by the mission**
   ```bash
   SPRING_PROFILES_ACTIVE='prod,flyway-v2' \
   ERP_CORS_ALLOWED_ORIGINS='https://app.bigbrightpaints.com' \
   ERP_CORS_ALLOW_TAILSCALE_HTTP_ORIGINS='true' \
   DB_PORT=5433 \
   SPRING_MAIL_HOST='mailhog' \
   SPRING_MAIL_PORT='1025' \
   SPRING_MAIL_USERNAME='' \
   SPRING_MAIL_PASSWORD='' \
   SPRING_MAIL_PROPERTIES_MAIL_SMTP_AUTH='false' \
   SPRING_MAIL_PROPERTIES_MAIL_SMTP_STARTTLS_ENABLE='false' \
   SPRING_MAIL_PROPERTIES_MAIL_SMTP_STARTTLS_REQUIRED='false' \
   docker compose up -d --build app
   ```

---

## 5) Running Tests
### Quick gate (recommended before commits)
```bash
cd erp-domain
MIGRATION_SET=v2 mvn test -Pgate-fast -Djacoco.skip=true
```

Auth merge-gate regression suites are included in gate-fast. To run them directly:
```bash
cd erp-domain
MIGRATION_SET=v2 mvn test -Djacoco.skip=true -Dtest=AuthPasswordResetPublicContractIT,AdminUserSecurityIT
```

### Full suite
```bash
cd erp-domain
MIGRATION_SET=v2 mvn test -Djacoco.skip=true
```

### Integration tests only
```bash
cd erp-domain
MIGRATION_SET=v2 mvn test -Djacoco.skip=true -Dtest='*IT,*ITCase'
```

### Run tests for a specific module (examples)
```bash
cd erp-domain
MIGRATION_SET=v2 mvn test -Djacoco.skip=true -Dtest='*Accounting*Test,*Accounting*IT'
MIGRATION_SET=v2 mvn test -Djacoco.skip=true -Dtest='*Sales*Test,*Sales*IT'
MIGRATION_SET=v2 mvn test -Djacoco.skip=true -Dtest='*Inventory*Test,*Inventory*IT'
```

### CI-aligned gate scripts (repo root)
```bash
bash scripts/gate_fast.sh
bash scripts/gate_core.sh       # includes O2C E2E coverage (OrderFulfillmentE2ETest, dispatch-invoice-accounting gates)
bash scripts/gate_release.sh
bash scripts/gate_reconciliation.sh
```

### CI-specific setup notes
- CI and local gate scripts default to **Flyway v2** (`MIGRATION_SET=v2`).
- To run gate-fast exactly like CI manually dispatched runs, provide a base SHA:
```bash
DIFF_BASE=<40-char-base-sha> GATE_FAST_REQUIRE_DIFF_BASE=true bash scripts/gate_fast.sh
```
- To build the application image using the same Dockerfile/context as Compose/CI:
```bash
docker build -t erp-test -f erp-domain/Dockerfile .
```

---

## 6) Docker Deployment
`docker-compose.yml` defines these runtime services:
- `db` (PostgreSQL 16)
- `rabbitmq` (message broker + management UI)
- `mailhog` (SMTP sink for local/test email validation)
- `app` (Spring Boot ERP backend, default profile: `prod,flyway-v2`)

### Production-style startup
1. Create production env from template:
   ```bash
   cp .env.prod.template .env
   ```
2. Fill all required secrets and endpoints (`JWT_SECRET`, DB credentials, SMTP, encryption/audit keys, CORS origins, etc.)
3. Start stack:
   ```bash
   docker compose up -d --build
   ```
4. Verify health:
   ```bash
   curl -sf http://localhost:8081/actuator/health
   ```

---

## 7) Module Overview
| Module | Purpose |
|---|---|
| `accounting` | Journals, chart of accounts, settlements, reconciliation, period controls. |
| `admin` | Superadmin/admin controls, approvals, changelog, support tickets, settings. |
| `auth` | Authentication, JWT lifecycle, password reset, MFA, account security. |
| `company` | Tenant/company lifecycle, provisioning, runtime policy, company context. |
| `factory` | Manufacturing execution and packing orchestration services. |
| `hr` | Employee management, attendance, leave, payroll run/calc/posting flows. |
| `inventory` | Raw materials and finished goods, batches, reservations, adjustments, dispatch stock moves. |
| `invoice` | Invoice generation, numbering, settlement linkage, invoice artifacts. |
| `portal` | Dealer/tenant-facing portal endpoints and access-controlled views. |
| `production` | Product/catalog production support, mappings/import paths for production data flows. |
| `purchasing` | Supplier management, purchase orders, goods receipts, returns, purchase accounting linkage. |
| `rbac` | Role and permission management for application authorization. |
| `reports` | Financial/operational reporting: trial balance, P&L, balance sheet, GST, aging, valuation. |
| `sales` | Dealer management, sales orders, lifecycle transitions, dispatch/invoice integration, credit controls. |

---

## 8) API Documentation
- **OpenAPI snapshot in repo:** `openapi.json`
- **Swagger UI (dev profile):** `http://localhost:8081/swagger-ui`
- **Raw OpenAPI endpoint (when enabled):** `http://localhost:8081/v3/api-docs`

Notes:
- Swagger/OpenAPI endpoints are intentionally disabled in production profile.
- Enable dev profile to use interactive API docs locally.
- Public catalog contract is `/api/v1/catalog/**` only.
- Create brands explicitly on `POST /api/v1/catalog/brands`, then create or preview products on `POST /api/v1/catalog/products` using a resolved active `brandId`.
- Use only the canonical catalog endpoints above for brand selection/create and product preview/commit flows.

---

## 9) Architecture
- Architecture reference: [`.factory/library/architecture.md`](.factory/library/architecture.md)
- Frontend/backend contract handoff: [`.factory/library/frontend-v2.md`](.factory/library/frontend-v2.md) and [`.factory/library/frontend-handoff.md`](.factory/library/frontend-handoff.md)
- Cleanup/remediation roll-up: [`.factory/library/remediation-log.md`](.factory/library/remediation-log.md)

Key backend patterns used across modules:
- **Facade pattern:** module entry services centralize validation/routing (for example, accounting facade flows)
- **Engine pattern:** focused workflow engines orchestrate complex business transitions
- **Shared idempotency framework:** signature + reservation + replay detection utilities under `core.idempotency`
- **Outbox/event publication pattern:** reliable cross-module event delivery through orchestrator services

**O2C dispatch posting path:**
`SalesCoreEngine.confirmDispatch` → `AccountingFacade` is the sole canonical dispatch-to-accounting trigger for the order-to-cash flow. Orchestrator dispatch endpoints are fail-closed and do not post journals independently.

---

## 10) Contributing
### Branch naming
Use descriptive branch names, e.g.:
- `feature/<scope>`
- `fix/<scope>`
- `refactor/<scope>`
- `docs/<scope>`

> `sync/*` branches are reserved for branch-convergence/sync workflows used by CI.

### Commit conventions
Follow the existing conventional style seen in repository history:
- `feat(accounting): ...`
- `fix(admin): ...`
- `refactor(inventory): ...`
- `validate(<milestone>): ...`

### CI gates
Primary workflow is `.github/workflows/ci.yml` with these core gates:
- PR: `knowledgebase-lint`, `architecture-check`, `enterprise-policy-check`, `orchestrator-layer-check`, `gate-fast`
- Main branch: `gate-core`
- Release/tag/manual release validation: `gate-release`, `gate-reconciliation`
- Scheduled/manual deep quality: `gate-quality`

### Before opening a PR
```bash
cd erp-domain
mvn compile -q
mvn test -Pgate-fast -Djacoco.skip=true
```
