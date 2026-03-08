#!/usr/bin/env bash
set -euo pipefail

ROOT="/home/realnigga/Desktop/Mission-control"
COMPOSE_FILE="$ROOT/docker-compose.yml"
DB_PORT="5433"

JWT_SECRET="${JWT_SECRET:-factory-validation-jwt-secret-20260308-keep-local}"
ERP_SECURITY_ENCRYPTION_KEY="${ERP_SECURITY_ENCRYPTION_KEY:-factory-validation-encryption-key-20260308}"
ERP_VALIDATION_SEED_PASSWORD="${ERP_VALIDATION_SEED_PASSWORD:-Validation123!}"

if [[ -f "$ROOT/.env" ]]; then
  set -a
  # shellcheck disable=SC1091
  source "$ROOT/.env"
  set +a
fi

if [[ -z "${JWT_SECRET:-}" || "$JWT_SECRET" == YOUR_* ]]; then
  JWT_SECRET="factory-validation-jwt-secret-20260308-keep-local"
fi
if [[ -z "${ERP_SECURITY_ENCRYPTION_KEY:-}" || "$ERP_SECURITY_ENCRYPTION_KEY" == YOUR_* ]]; then
  ERP_SECURITY_ENCRYPTION_KEY="factory-validation-encryption-key-20260308"
fi

echo "[final-validation-reset] Resetting compose runtime on port ${DB_PORT}"
DB_PORT="$DB_PORT" docker compose -f "$COMPOSE_FILE" down -v --remove-orphans
DB_PORT="$DB_PORT" docker compose -f "$COMPOSE_FILE" up -d db rabbitmq mailhog

for _ in $(seq 1 60); do
  if python3 - <<'PY'
import socket
for host, port in [('127.0.0.1', 5433), ('127.0.0.1', 5672)]:
    s = socket.create_connection((host, port), 2)
    s.close()
PY
  then
    break
  fi
  sleep 2
done

DB_PORT="$DB_PORT" \
SPRING_PROFILES_ACTIVE='prod,flyway-v2,mock,validation-seed' \
JWT_SECRET="$JWT_SECRET" \
ERP_SECURITY_ENCRYPTION_KEY="$ERP_SECURITY_ENCRYPTION_KEY" \
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
docker compose -f "$COMPOSE_FILE" up -d --build app

for _ in $(seq 1 90); do
  status=$(curl -s -o /tmp/final-validation-auth.out -w '%{http_code}' http://localhost:8081/api/v1/auth/me || true)
  if [[ "$status" == "200" || "$status" == "401" || "$status" == "403" ]]; then
    break
  fi
  sleep 2
done

status=$(curl -s -o /tmp/final-validation-auth.out -w '%{http_code}' http://localhost:8081/api/v1/auth/me || true)
if [[ "$status" != "200" && "$status" != "401" && "$status" != "403" ]]; then
  echo "[final-validation-reset] Backend did not become ready; last status=$status"
  exit 1
fi

cat <<EOF
[final-validation-reset] Ready.

Runtime:
  Base URL: http://localhost:8081
  MailHog:  http://localhost:8025
  Profiles: prod,flyway-v2,mock,validation-seed

Seeded actors (password: ${ERP_VALIDATION_SEED_PASSWORD})
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
