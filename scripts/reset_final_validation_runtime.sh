#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" >/dev/null 2>&1 && pwd)"
ROOT="$(cd -- "${SCRIPT_DIR}/.." >/dev/null 2>&1 && pwd)"
COMPOSE_FILE="$ROOT/docker-compose.yml"
PINNED_DB_PORT="5433"
DB_PORT="$PINNED_DB_PORT"
RABBIT_PORT="${RABBIT_PORT:-5672}"
APP_PORT="${APP_PORT:-8081}"
SPRING_DATASOURCE_URL="${SPRING_DATASOURCE_URL:-jdbc:postgresql://db:5432/erp_domain}"
SPRING_DATASOURCE_USERNAME="${SPRING_DATASOURCE_USERNAME:-erp}"
SPRING_DATASOURCE_PASSWORD="${SPRING_DATASOURCE_PASSWORD:-erp}"
ERP_SECURITY_AUDIT_PRIVATE_KEY="${ERP_SECURITY_AUDIT_PRIVATE_KEY:-local-dev-audit-key-not-for-release}"

JWT_SECRET="${JWT_SECRET:-local-dev-jwt-secret-32-bytes-min-20260329}"
ERP_SECURITY_ENCRYPTION_KEY="${ERP_SECURITY_ENCRYPTION_KEY:-local-dev-encryption-key-32b-20260329}"
ERP_VALIDATION_SEED_PASSWORD_SOURCE="configured"
ERP_VALIDATION_SEED_PASSWORD_WAS_EXPORTED=false

if [[ "${ERP_VALIDATION_SEED_PASSWORD+x}" == "x" ]]; then
  EXPORTED_ERP_VALIDATION_SEED_PASSWORD="$ERP_VALIDATION_SEED_PASSWORD"
  ERP_VALIDATION_SEED_PASSWORD_WAS_EXPORTED=true
fi

ERP_VALIDATION_SEED_PASSWORD="${ERP_VALIDATION_SEED_PASSWORD:-}"

if [[ -f "$ROOT/.env" ]]; then
  set -a
  # shellcheck disable=SC1091
  source "$ROOT/.env"
  set +a
fi

if [[ "$ERP_VALIDATION_SEED_PASSWORD_WAS_EXPORTED" == true ]]; then
  ERP_VALIDATION_SEED_PASSWORD="$EXPORTED_ERP_VALIDATION_SEED_PASSWORD"
fi

DB_PORT="$PINNED_DB_PORT"
RABBIT_PORT="${RABBIT_PORT:-5672}"
APP_PORT="${APP_PORT:-8081}"

if [[ -z "${JWT_SECRET:-}" || "$JWT_SECRET" == YOUR_* || "$JWT_SECRET" == "placeholder" ]]; then
  JWT_SECRET="$(python3 - <<'PY'
import secrets
print(secrets.token_urlsafe(48))
PY
)"
fi
if [[ -z "${ERP_SECURITY_ENCRYPTION_KEY:-}" || "$ERP_SECURITY_ENCRYPTION_KEY" == YOUR_* || "$ERP_SECURITY_ENCRYPTION_KEY" == "placeholder" ]]; then
  ERP_SECURITY_ENCRYPTION_KEY="$(python3 - <<'PY'
import secrets
print(secrets.token_hex(32))
PY
)"
fi
if [[ -z "${ERP_VALIDATION_SEED_PASSWORD:-}" || "$ERP_VALIDATION_SEED_PASSWORD" == YOUR_* || "$ERP_VALIDATION_SEED_PASSWORD" == "placeholder" ]]; then
  ERP_VALIDATION_SEED_PASSWORD="$(python3 - <<'PY'
import secrets
print(f"Validation1!{secrets.token_hex(8)}")
PY
)"
  ERP_VALIDATION_SEED_PASSWORD_SOURCE="generated"
fi

ERP_SEED_MOCK_ADMIN_EMAIL="${ERP_SEED_MOCK_ADMIN_EMAIL:-mock.admin@bbp.com}"
ERP_SEED_MOCK_ADMIN_PASSWORD="${ERP_SEED_MOCK_ADMIN_PASSWORD:-$ERP_VALIDATION_SEED_PASSWORD}"

export \
  JWT_SECRET \
  ERP_SECURITY_ENCRYPTION_KEY \
  SPRING_DATASOURCE_URL \
  SPRING_DATASOURCE_USERNAME \
  SPRING_DATASOURCE_PASSWORD \
  ERP_SECURITY_AUDIT_PRIVATE_KEY \
  ERP_SEED_MOCK_ADMIN_EMAIL \
  ERP_SEED_MOCK_ADMIN_PASSWORD \
  ERP_VALIDATION_SEED_PASSWORD

echo "[final-validation-reset] Resetting compose runtime on port ${DB_PORT}"
DB_PORT="$DB_PORT" RABBIT_PORT="$RABBIT_PORT" APP_PORT="$APP_PORT" docker compose -f "$COMPOSE_FILE" down -v --remove-orphans
DB_PORT="$DB_PORT" RABBIT_PORT="$RABBIT_PORT" APP_PORT="$APP_PORT" docker compose -f "$COMPOSE_FILE" up -d db rabbitmq mailhog

ready=0
for _ in $(seq 1 60); do
  if DB_PORT="$DB_PORT" RABBIT_PORT="$RABBIT_PORT" python3 - <<'PY'
import os
import socket

for host, port in [('127.0.0.1', int(os.environ['DB_PORT'])), ('127.0.0.1', int(os.environ['RABBIT_PORT']))]:
    with socket.create_connection((host, port), 2):
        pass
PY
  then
    ready=1
    break
  fi
  sleep 2
done

if [[ "$ready" -ne 1 ]]; then
  echo "[final-validation-reset] ERROR: DB/RabbitMQ did not become ready on ports ${DB_PORT}/${RABBIT_PORT} after 60 attempts" >&2
  exit 1
fi

compose_status=0
set +e
DB_PORT="$DB_PORT" \
SPRING_PROFILES_ACTIVE='prod,flyway-v2,mock,validation-seed' \
JWT_SECRET="$JWT_SECRET" \
SPRING_DATASOURCE_URL="$SPRING_DATASOURCE_URL" \
SPRING_DATASOURCE_USERNAME="$SPRING_DATASOURCE_USERNAME" \
SPRING_DATASOURCE_PASSWORD="$SPRING_DATASOURCE_PASSWORD" \
ERP_SECURITY_ENCRYPTION_KEY="$ERP_SECURITY_ENCRYPTION_KEY" \
ERP_SECURITY_AUDIT_PRIVATE_KEY="$ERP_SECURITY_AUDIT_PRIVATE_KEY" \
ERP_VALIDATION_SEED_ENABLED='true' \
ERP_VALIDATION_SEED_PASSWORD="$ERP_VALIDATION_SEED_PASSWORD" \
ERP_SEED_MOCK_ADMIN_EMAIL="$ERP_SEED_MOCK_ADMIN_EMAIL" \
ERP_SEED_MOCK_ADMIN_PASSWORD="$ERP_SEED_MOCK_ADMIN_PASSWORD" \
ERP_CORS_ALLOWED_ORIGINS='https://app.bigbrightpaints.com' \
ERP_CORS_ALLOW_TAILSCALE_HTTP_ORIGINS='true' \
SPRING_MAIL_HOST='mailhog' \
SPRING_MAIL_PORT='1025' \
SPRING_MAIL_USERNAME='' \
SPRING_MAIL_PASSWORD='' \
SPRING_MAIL_PROPERTIES_MAIL_SMTP_AUTH='false' \
SPRING_MAIL_PROPERTIES_MAIL_SMTP_STARTTLS_ENABLE='false' \
SPRING_MAIL_PROPERTIES_MAIL_SMTP_STARTTLS_REQUIRED='false' \
RABBIT_PORT="$RABBIT_PORT" \
APP_PORT="$APP_PORT" \
docker compose -f "$COMPOSE_FILE" up -d --build app
compose_status=$?
set -e

if [[ "$compose_status" -ne 0 ]]; then
  app_container_state="$(docker inspect -f '{{.State.Status}}' erp_domain_app 2>/dev/null || true)"
  if [[ "$app_container_state" != "running" ]]; then
    echo "[final-validation-reset] ERROR: docker compose up failed before app became runnable" >&2
    exit "$compose_status"
  fi
  echo "[final-validation-reset] docker compose returned non-zero, but erp_domain_app is running; continuing to health probe"
fi

for _ in $(seq 1 90); do
  status=$(curl -s -o /tmp/final-validation-auth.out -w '%{http_code}' "http://localhost:${APP_PORT}/api/v1/auth/me" || true)
  if [[ "$status" == "200" || "$status" == "401" || "$status" == "403" ]]; then
    break
  fi
  sleep 2
done

status=$(curl -s -o /tmp/final-validation-auth.out -w '%{http_code}' "http://localhost:${APP_PORT}/api/v1/auth/me" || true)
if [[ "$status" != "200" && "$status" != "401" && "$status" != "403" ]]; then
  echo "[final-validation-reset] Backend did not become ready; last status=$status"
  exit 1
fi

seed_user_rows="$(
  docker exec erp_db psql -U erp -d erp_domain -At -F '|' <<'SQL' 2>/dev/null || true
SELECT email, auth_scope_code, public_id
FROM app_users
WHERE email IN (
  'mock.admin@bbp.com',
  'validation.admin@example.com',
  'validation.mustchange.admin@example.com',
  'validation.locked.admin@example.com',
  'validation.accounting@example.com',
  'validation.sales@example.com',
  'validation.factory@example.com',
  'validation.mfa.admin@example.com',
  'validation.dealer@example.com',
  'validation.superadmin@example.com',
  'validation.hold.admin@example.com',
  'validation.blocked.admin@example.com',
  'validation.quota.alpha@example.com',
  'validation.quota.beta@example.com'
)
ORDER BY email;
SQL
)"

superadmin_public_id="$(printf '%s\n' "$seed_user_rows" | awk -F'|' '$1=="validation.superadmin@example.com" {print $3; exit}')"
mock_admin_public_id="$(printf '%s\n' "$seed_user_rows" | awk -F'|' '$1=="mock.admin@bbp.com" {print $3; exit}')"
mfa_admin_public_id="$(printf '%s\n' "$seed_user_rows" | awk -F'|' '$1=="validation.mfa.admin@example.com" {print $3; exit}')"
superadmin_public_id="${superadmin_public_id:-unavailable}"
mock_admin_public_id="${mock_admin_public_id:-unavailable}"
mfa_admin_public_id="${mfa_admin_public_id:-unavailable}"

cat <<EOF
[final-validation-reset] Ready.

Runtime:
  Base URL: http://localhost:${APP_PORT}
  MailHog:  http://localhost:8025
  Profiles: prod,flyway-v2,mock,validation-seed

Seeded actors (password source: ERP_VALIDATION_SEED_PASSWORD; reset fallback: ${ERP_VALIDATION_SEED_PASSWORD_SOURCE})
  Seed password: ${ERP_VALIDATION_SEED_PASSWORD}
  mock.admin@bbp.com                -> MOCK (bootstrap ROLE_ADMIN, ROLE_ACCOUNTING, ROLE_SALES; must-change-password)
  validation.admin@example.com        -> MOCK (ROLE_ADMIN, ROLE_ACCOUNTING, ROLE_SALES)
  validation.mustchange.admin@example.com -> MOCK (ROLE_ADMIN; must-change-password)
  validation.locked.admin@example.com -> MOCK (ROLE_ADMIN; locked)
  validation.accounting@example.com   -> MOCK (ROLE_ACCOUNTING)
  validation.sales@example.com        -> MOCK (ROLE_SALES)
  validation.factory@example.com      -> MOCK (ROLE_FACTORY)
  validation.mfa.admin@example.com    -> MOCK (ROLE_ADMIN; MFA enabled, secret JBSWY3DPEHPK3PXP, recovery codes VALMFA0001/VALMFA0002/VALMFA0003)
  validation.dealer@example.com       -> MOCK (ROLE_DEALER, portal user VALID-DEALER)
  validation.hold.admin@example.com   -> HOLD (ROLE_ADMIN; tenant state HOLD)
  validation.blocked.admin@example.com -> BLOCK (ROLE_ADMIN; tenant state BLOCKED)
  validation.quota.alpha@example.com  -> QUOTA (ROLE_ADMIN; active-user quota fixture)
  validation.quota.beta@example.com   -> QUOTA (ROLE_ADMIN; active-user quota fixture)
  validation.superadmin@example.com   -> PLATFORM (ROLE_SUPER_ADMIN, ROLE_ADMIN)
  validation.rival.admin@example.com  -> RIVAL (ROLE_ADMIN)
  validation.rival.dealer@example.com -> RIVAL (ROLE_DEALER, portal user RIVAL-DEALER)

Seeded public ids:
  mock.admin@bbp.com                -> ${mock_admin_public_id}
  validation.mfa.admin@example.com  -> ${mfa_admin_public_id}
  validation.superadmin@example.com -> ${superadmin_public_id}

Seeded admin/UAT fixtures:
  Ready-to-confirm sales order      -> idempotency key mock-ready-confirm-order
  Pending export approval           -> SALES_REGISTER with parameters {"seed":"mock-validation-export"}
  Pending support ticket            -> subject "Validation seeded support ticket"
  Pending credit request            -> reason "Validation seeded dealer credit request"
  MOCK P2P replay chain             -> PO MOCK-P2P-PO-001, GRN MOCK-P2P-GRN-001, invoice MOCK-P2P-INV-001

Use this command again whenever you need a clean local adversarial-validation runtime.
EOF
