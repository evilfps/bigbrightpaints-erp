#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" >/dev/null 2>&1 && pwd)"
ROOT="$(cd -- "${SCRIPT_DIR}/.." >/dev/null 2>&1 && pwd)"
COMPOSE_FILE="$ROOT/docker-compose.yml"
PINNED_DB_PORT="5433"
DB_PORT="$PINNED_DB_PORT"
RABBIT_PORT="${RABBIT_PORT:-5672}"
APP_PORT="${APP_PORT:-8081}"

JWT_SECRET="${JWT_SECRET:-placeholder}"
ERP_SECURITY_ENCRYPTION_KEY="${ERP_SECURITY_ENCRYPTION_KEY:-placeholder}"
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

export JWT_SECRET ERP_SECURITY_ENCRYPTION_KEY

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

DB_PORT="$DB_PORT" \
SPRING_PROFILES_ACTIVE='prod,flyway-v2,mock,validation-seed' \
JWT_SECRET="$JWT_SECRET" \
ERP_SECURITY_ENCRYPTION_KEY="$ERP_SECURITY_ENCRYPTION_KEY" \
ERP_VALIDATION_SEED_ENABLED='true' \
ERP_VALIDATION_SEED_PASSWORD="$ERP_VALIDATION_SEED_PASSWORD" \
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

cat <<EOF
[final-validation-reset] Ready.

Runtime:
  Base URL: http://localhost:${APP_PORT}
  MailHog:  http://localhost:8025
  Profiles: prod,flyway-v2,mock,validation-seed

Seeded actors (password source: ERP_VALIDATION_SEED_PASSWORD; reset fallback: ${ERP_VALIDATION_SEED_PASSWORD_SOURCE})
  Seed password: ${ERP_VALIDATION_SEED_PASSWORD}
  validation.admin@example.com        -> MOCK (ROLE_ADMIN, ROLE_ACCOUNTING, ROLE_SALES)
  validation.accounting@example.com   -> MOCK (ROLE_ACCOUNTING)
  validation.sales@example.com        -> MOCK (ROLE_SALES)
  validation.factory@example.com      -> MOCK (ROLE_FACTORY)
  validation.dealer@example.com       -> MOCK (ROLE_DEALER, portal user VALID-DEALER)
  validation.superadmin@example.com   -> SKE + MOCK (ROLE_SUPER_ADMIN, ROLE_ADMIN)
  validation.rival.admin@example.com  -> RIVAL (ROLE_ADMIN)
  validation.rival.dealer@example.com -> RIVAL (ROLE_DEALER, portal user RIVAL-DEALER)

Use this command again whenever you need a clean local adversarial-validation runtime.
EOF
