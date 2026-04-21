#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" >/dev/null 2>&1 && pwd)"
ROOT="$(cd -- "${SCRIPT_DIR}/.." >/dev/null 2>&1 && pwd)"
COMPOSE_FILE="$ROOT/docker-compose.yml"
PINNED_DB_PORT="5433"
PINNED_RABBIT_PORT="15673"
PINNED_RABBIT_MANAGEMENT_PORT="15674"
PINNED_APP_PORT="18081"
PINNED_MANAGEMENT_PORT="19090"
PINNED_MAILHOG_UI_PORT="18025"

DB_PORT="$PINNED_DB_PORT"
RABBIT_PORT="${RABBIT_PORT:-$PINNED_RABBIT_PORT}"
RABBIT_MANAGEMENT_PORT="${RABBIT_MANAGEMENT_PORT:-$PINNED_RABBIT_MANAGEMENT_PORT}"
APP_PORT="${APP_PORT:-$PINNED_APP_PORT}"
MANAGEMENT_PORT="${MANAGEMENT_PORT:-$PINNED_MANAGEMENT_PORT}"
MAILHOG_UI_PORT="${MAILHOG_UI_PORT:-$PINNED_MAILHOG_UI_PORT}"
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
RABBIT_PORT="${RABBIT_PORT:-$PINNED_RABBIT_PORT}"
RABBIT_MANAGEMENT_PORT="${RABBIT_MANAGEMENT_PORT:-$PINNED_RABBIT_MANAGEMENT_PORT}"
APP_PORT="${APP_PORT:-$PINNED_APP_PORT}"
MANAGEMENT_PORT="${MANAGEMENT_PORT:-$PINNED_MANAGEMENT_PORT}"
MAILHOG_UI_PORT="${MAILHOG_UI_PORT:-$PINNED_MAILHOG_UI_PORT}"

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
ERP_INVENTORY_OPENING_STOCK_ENABLED="${ERP_INVENTORY_OPENING_STOCK_ENABLED:-true}"

export \
  JWT_SECRET \
  ERP_SECURITY_ENCRYPTION_KEY \
  SPRING_DATASOURCE_URL \
  SPRING_DATASOURCE_USERNAME \
  SPRING_DATASOURCE_PASSWORD \
  ERP_SECURITY_AUDIT_PRIVATE_KEY \
  RABBIT_MANAGEMENT_PORT \
  MANAGEMENT_PORT \
  MAILHOG_UI_PORT \
  ERP_SEED_MOCK_ADMIN_EMAIL \
  ERP_SEED_MOCK_ADMIN_PASSWORD \
  ERP_INVENTORY_OPENING_STOCK_ENABLED \
  ERP_VALIDATION_SEED_PASSWORD

echo "[final-validation-reset] Resetting compose runtime on approved ports (db=${DB_PORT}, app=${APP_PORT}, rabbit=${RABBIT_PORT}/${RABBIT_MANAGEMENT_PORT}, actuator=${MANAGEMENT_PORT}, mailhog=${MAILHOG_UI_PORT})"
DB_PORT="$DB_PORT" RABBIT_PORT="$RABBIT_PORT" RABBIT_MANAGEMENT_PORT="$RABBIT_MANAGEMENT_PORT" APP_PORT="$APP_PORT" MANAGEMENT_PORT="$MANAGEMENT_PORT" MAILHOG_UI_PORT="$MAILHOG_UI_PORT" docker compose -f "$COMPOSE_FILE" down -v --remove-orphans
DB_PORT="$DB_PORT" RABBIT_PORT="$RABBIT_PORT" RABBIT_MANAGEMENT_PORT="$RABBIT_MANAGEMENT_PORT" APP_PORT="$APP_PORT" MANAGEMENT_PORT="$MANAGEMENT_PORT" MAILHOG_UI_PORT="$MAILHOG_UI_PORT" docker compose -f "$COMPOSE_FILE" up -d db rabbitmq mailhog

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
RABBIT_MANAGEMENT_PORT="$RABBIT_MANAGEMENT_PORT" \
MANAGEMENT_PORT="$MANAGEMENT_PORT" \
MAILHOG_UI_PORT="$MAILHOG_UI_PORT" \
SPRING_PROFILES_ACTIVE='prod,flyway-v2,mock,validation-seed' \
JWT_SECRET="$JWT_SECRET" \
SPRING_DATASOURCE_URL="$SPRING_DATASOURCE_URL" \
SPRING_DATASOURCE_USERNAME="$SPRING_DATASOURCE_USERNAME" \
SPRING_DATASOURCE_PASSWORD="$SPRING_DATASOURCE_PASSWORD" \
ERP_SECURITY_ENCRYPTION_KEY="$ERP_SECURITY_ENCRYPTION_KEY" \
ERP_SECURITY_AUDIT_PRIVATE_KEY="$ERP_SECURITY_AUDIT_PRIVATE_KEY" \
ERP_VALIDATION_SEED_ENABLED='true' \
ERP_VALIDATION_SEED_PASSWORD="$ERP_VALIDATION_SEED_PASSWORD" \
ERP_INVENTORY_OPENING_STOCK_ENABLED="$ERP_INVENTORY_OPENING_STOCK_ENABLED" \
ERP_SEED_MOCK_ADMIN_EMAIL="$ERP_SEED_MOCK_ADMIN_EMAIL" \
ERP_SEED_MOCK_ADMIN_PASSWORD="$ERP_SEED_MOCK_ADMIN_PASSWORD" \
ERP_CORS_ALLOWED_ORIGINS='https://app.bigbrightpaints.com' \
ERP_CORS_ALLOW_TAILSCALE_HTTP_ORIGINS='true' \
ERP_ENVIRONMENT_VALIDATION_HEALTH_INDICATOR_SKIP_WHEN_VALIDATION_DISABLED='true' \
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

mock_admin_email_normalized="$(printf '%s' "$ERP_SEED_MOCK_ADMIN_EMAIL" | tr '[:upper:]' '[:lower:]')"

collect_seed_fixture_errors() {
  docker exec -i erp_db psql -v ON_ERROR_STOP=1 -v "mock_admin_email=$mock_admin_email_normalized" -U erp -d erp_domain -At <<'SQL'
WITH expected_users(email, expected_scope) AS (
  VALUES
    (LOWER(:'mock_admin_email'), 'MOCK'),
    ('validation.admin@example.com', 'MOCK'),
    ('validation.mustchange.admin@example.com', 'MOCK'),
    ('validation.locked.admin@example.com', 'MOCK'),
    ('validation.accounting@example.com', 'MOCK'),
    ('validation.sales@example.com', 'MOCK'),
    ('validation.factory@example.com', 'MOCK'),
    ('validation.mfa.admin@example.com', 'MOCK'),
    ('validation.dealer@example.com', 'MOCK'),
    ('validation.superadmin@example.com', COALESCE((SELECT UPPER(NULLIF(BTRIM(ss.setting_value), '')) FROM system_settings ss WHERE ss.setting_key = 'auth.platform.code' LIMIT 1), 'PLATFORM')),
    ('validation.hold.admin@example.com', 'HOLD'),
    ('validation.blocked.admin@example.com', 'BLOCK'),
    ('validation.quota.alpha@example.com', 'QUOTA'),
    ('validation.quota.beta@example.com', 'QUOTA'),
    ('validation.rival.admin@example.com', 'RIVAL'),
    ('validation.rival.dealer@example.com', 'RIVAL')
),
missing_users AS (
  SELECT format('missing seeded actor %s', eu.email) AS error
  FROM expected_users eu
  WHERE NOT EXISTS (
    SELECT 1
    FROM app_users u
    WHERE LOWER(u.email) = eu.email
  )
),
scope_mismatch_users AS (
  SELECT format(
           'seeded actor %s expected scope %s but found scopes [%s]',
           eu.email,
           eu.expected_scope,
           COALESCE(
             (
               SELECT string_agg(DISTINCT UPPER(COALESCE(u.auth_scope_code, '<null>')), ', ' ORDER BY UPPER(COALESCE(u.auth_scope_code, '<null>')))
               FROM app_users u
               WHERE LOWER(u.email) = eu.email
             ),
             '<none>'
           )
         ) AS error
  FROM expected_users eu
  WHERE eu.expected_scope IS NOT NULL
    AND EXISTS (
      SELECT 1
      FROM app_users u
      WHERE LOWER(u.email) = eu.email
    )
    AND NOT EXISTS (
      SELECT 1
      FROM app_users u
      WHERE LOWER(u.email) = eu.email
        AND UPPER(u.auth_scope_code) = eu.expected_scope
    )
),
empty_scope_users AS (
  SELECT format('seeded actor %s has an empty auth scope code', LOWER(u.email)) AS error
  FROM app_users u
  WHERE LOWER(u.email) IN (SELECT email FROM expected_users)
    AND COALESCE(BTRIM(u.auth_scope_code), '') = ''
),
expected_companies(code) AS (
  VALUES ('MOCK'), ('RIVAL'), ('HOLD'), ('BLOCK'), ('QUOTA')
),
missing_companies AS (
  SELECT format('missing seeded tenant company %s', ec.code) AS error
  FROM expected_companies ec
  WHERE NOT EXISTS (
    SELECT 1
    FROM companies c
    WHERE UPPER(c.code) = ec.code
  )
),
runtime_setting_expectations(company_code, setting_prefix, expected_value) AS (
  VALUES
    ('HOLD', 'tenant.runtime.hold-state.', 'HOLD'),
    ('BLOCK', 'tenant.runtime.hold-state.', 'BLOCKED'),
    ('QUOTA', 'tenant.runtime.hold-state.', 'ACTIVE'),
    ('QUOTA', 'tenant.runtime.max-active-users.', '1')
),
runtime_setting_mismatches AS (
  SELECT format(
           'tenant runtime fixture %s%s expected %s but found %s',
           rse.setting_prefix,
           c.id,
           rse.expected_value,
           COALESCE(ss.setting_value, '<missing>')
         ) AS error
  FROM runtime_setting_expectations rse
  JOIN companies c
    ON UPPER(c.code) = rse.company_code
  LEFT JOIN system_settings ss
    ON ss.setting_key = rse.setting_prefix || c.id::text
  WHERE ss.setting_value IS DISTINCT FROM rse.expected_value
),
expected_dealers(company_code, dealer_code, portal_email) AS (
  VALUES
    ('MOCK', 'VALID-DEALER', 'validation.dealer@example.com'),
    ('RIVAL', 'RIVAL-DEALER', 'validation.rival.dealer@example.com')
),
missing_dealers AS (
  SELECT format('missing seeded dealer %s for company %s', ed.dealer_code, ed.company_code) AS error
  FROM expected_dealers ed
  WHERE NOT EXISTS (
    SELECT 1
    FROM dealers d
    JOIN companies c
      ON c.id = d.company_id
    WHERE UPPER(c.code) = ed.company_code
      AND UPPER(d.code) = ed.dealer_code
  )
),
dealer_portal_mismatches AS (
  SELECT format(
           'seeded dealer %s for company %s expected portal user %s',
           ed.dealer_code,
           ed.company_code,
           ed.portal_email
         ) AS error
  FROM expected_dealers ed
  JOIN dealers d
    ON UPPER(d.code) = ed.dealer_code
  JOIN companies c
    ON c.id = d.company_id
   AND UPPER(c.code) = ed.company_code
  LEFT JOIN app_users u
    ON u.id = d.portal_user_id
  WHERE LOWER(COALESCE(u.email, '')) <> ed.portal_email
),
dealer_receivable_gaps AS (
  SELECT format(
           'seeded dealer %s for company %s is missing receivable-account wiring',
           ed.dealer_code,
           ed.company_code
         ) AS error
  FROM expected_dealers ed
  JOIN dealers d
    ON UPPER(d.code) = ed.dealer_code
  JOIN companies c
    ON c.id = d.company_id
   AND UPPER(c.code) = ed.company_code
  WHERE d.receivable_account_id IS NULL
),
invoice_fixtures_missing AS (
  SELECT format('missing seeded invoice %s for company %s', expected_invoice_number, company_code) AS error
  FROM (
         VALUES
           ('MOCK', 'VAL-MOCK-INV-001'),
           ('RIVAL', 'VAL-RIVAL-INV-001')
       ) AS fixture(company_code, expected_invoice_number)
  WHERE NOT EXISTS (
    SELECT 1
    FROM invoices i
    JOIN companies c
      ON c.id = i.company_id
    WHERE UPPER(c.code) = fixture.company_code
      AND UPPER(i.invoice_number) = fixture.expected_invoice_number
      AND UPPER(COALESCE(i.status, '')) = 'ISSUED'
  )
),
pending_mock_export_missing AS (
  SELECT 'missing mock pending export fixture SALES_REGISTER {"seed":"mock-validation-export"} for company MOCK' AS error
  WHERE NOT EXISTS (
    SELECT 1
    FROM export_requests er
    JOIN companies c
      ON c.id = er.company_id
    WHERE UPPER(c.code) = 'MOCK'
      AND UPPER(COALESCE(er.status, '')) = 'PENDING'
      AND UPPER(COALESCE(er.report_type, '')) = 'SALES_REGISTER'
      AND er.parameters = '{"seed":"mock-validation-export"}'
  )
),
pending_mock_support_missing AS (
  SELECT 'missing mock pending support ticket fixture "Validation seeded support ticket" for company MOCK' AS error
  WHERE NOT EXISTS (
    SELECT 1
    FROM support_tickets st
    JOIN companies c
      ON c.id = st.company_id
    WHERE UPPER(c.code) = 'MOCK'
      AND UPPER(COALESCE(st.category, '')) = 'SUPPORT'
      AND st.subject = 'Validation seeded support ticket'
  )
),
pending_mock_credit_missing AS (
  SELECT 'missing mock pending credit request fixture "Validation seeded dealer credit request" for company MOCK' AS error
  WHERE NOT EXISTS (
    SELECT 1
    FROM credit_requests cr
    JOIN companies c
      ON c.id = cr.company_id
    JOIN dealers d
      ON d.id = cr.dealer_id
    WHERE UPPER(c.code) = 'MOCK'
      AND UPPER(d.code) = 'VALID-DEALER'
      AND UPPER(COALESCE(cr.status, '')) = 'PENDING'
      AND cr.reason = 'Validation seeded dealer credit request'
  )
),
p2p_chain_missing AS (
  SELECT 'missing MOCK P2P purchase order fixture MOCK-P2P-PO-001' AS error
  WHERE NOT EXISTS (
    SELECT 1
    FROM purchase_orders po
    JOIN companies c
      ON c.id = po.company_id
    WHERE UPPER(c.code) = 'MOCK'
      AND UPPER(po.order_number) = 'MOCK-P2P-PO-001'
  )
  UNION ALL
  SELECT 'missing MOCK P2P goods receipt fixture MOCK-P2P-GRN-001' AS error
  WHERE NOT EXISTS (
    SELECT 1
    FROM goods_receipts gr
    JOIN companies c
      ON c.id = gr.company_id
    WHERE UPPER(c.code) = 'MOCK'
      AND UPPER(gr.receipt_number) = 'MOCK-P2P-GRN-001'
  )
  UNION ALL
  SELECT 'missing MOCK P2P purchase invoice fixture MOCK-P2P-INV-001' AS error
  WHERE NOT EXISTS (
    SELECT 1
    FROM raw_material_purchases rmp
    JOIN companies c
      ON c.id = rmp.company_id
    WHERE UPPER(c.code) = 'MOCK'
      AND UPPER(rmp.invoice_number) = 'MOCK-P2P-INV-001'
  )
),
ready_confirm_order_missing AS (
  SELECT 'missing ready-to-confirm sales order fixture idempotency key mock-ready-confirm-order for company MOCK' AS error
  WHERE NOT EXISTS (
    SELECT 1
    FROM sales_orders so
    JOIN companies c
      ON c.id = so.company_id
    WHERE UPPER(c.code) = 'MOCK'
      AND so.idempotency_key = 'mock-ready-confirm-order'
  )
),
pending_validation_export_missing AS (
  SELECT 'missing validation pending export fixture SALES_LEDGER {"range":"LAST_30_DAYS","company":"MOCK","seed":"validation-export-pending"} for company MOCK' AS error
  WHERE NOT EXISTS (
    SELECT 1
    FROM export_requests er
    JOIN companies c
      ON c.id = er.company_id
    WHERE UPPER(c.code) = 'MOCK'
      AND UPPER(COALESCE(er.status, '')) = 'PENDING'
      AND UPPER(COALESCE(er.report_type, '')) = 'SALES_LEDGER'
      AND er.parameters = '{"range":"LAST_30_DAYS","company":"MOCK","seed":"validation-export-pending"}'
  )
),
pending_validation_support_missing AS (
  SELECT 'missing validation pending support ticket fixture "Validation dealer support escalation" for company MOCK' AS error
  WHERE NOT EXISTS (
    SELECT 1
    FROM support_tickets st
    JOIN companies c
      ON c.id = st.company_id
    WHERE UPPER(c.code) = 'MOCK'
      AND UPPER(COALESCE(st.category, '')) = 'SUPPORT'
      AND st.subject = 'Validation dealer support escalation'
  )
),
pending_validation_credit_missing AS (
  SELECT 'missing validation pending credit request fixture "Validation pending credit request" for company MOCK' AS error
  WHERE NOT EXISTS (
    SELECT 1
    FROM credit_requests cr
    JOIN companies c
      ON c.id = cr.company_id
    JOIN dealers d
      ON d.id = cr.dealer_id
    WHERE UPPER(c.code) = 'MOCK'
      AND UPPER(d.code) = 'VALID-DEALER'
      AND UPPER(COALESCE(cr.status, '')) = 'PENDING'
      AND cr.reason = 'Validation pending credit request'
  )
)
SELECT error FROM missing_users
UNION ALL SELECT error FROM scope_mismatch_users
UNION ALL SELECT error FROM empty_scope_users
UNION ALL SELECT error FROM missing_companies
UNION ALL SELECT error FROM runtime_setting_mismatches
UNION ALL SELECT error FROM missing_dealers
UNION ALL SELECT error FROM dealer_portal_mismatches
UNION ALL SELECT error FROM dealer_receivable_gaps
UNION ALL SELECT error FROM invoice_fixtures_missing
UNION ALL SELECT error FROM pending_mock_export_missing
UNION ALL SELECT error FROM pending_mock_support_missing
UNION ALL SELECT error FROM pending_mock_credit_missing
UNION ALL SELECT error FROM p2p_chain_missing
UNION ALL SELECT error FROM ready_confirm_order_missing
UNION ALL SELECT error FROM pending_validation_export_missing
UNION ALL SELECT error FROM pending_validation_support_missing
UNION ALL SELECT error FROM pending_validation_credit_missing
ORDER BY error;
SQL
}

seed_fixture_errors=""
for _ in $(seq 1 45); do
  seed_fixture_errors="$(collect_seed_fixture_errors)"
  if [[ -z "$seed_fixture_errors" ]]; then
    break
  fi
  sleep 2
done

if [[ -n "$seed_fixture_errors" ]]; then
  echo "[final-validation-reset] ERROR: Validation seed fixture verification failed:" >&2
  while IFS= read -r seed_issue; do
    [[ -n "$seed_issue" ]] || continue
    echo "  - $seed_issue" >&2
  done <<< "$seed_fixture_errors"
  exit 1
fi

echo "[final-validation-reset] Validation seed fixtures verified successfully."

seed_user_rows="$(
  docker exec -i erp_db psql -v ON_ERROR_STOP=1 -v "mock_admin_email=$mock_admin_email_normalized" -U erp -d erp_domain -At -F '|' <<'SQL'
SELECT LOWER(email), UPPER(auth_scope_code), public_id
FROM app_users
WHERE LOWER(email) IN (
  LOWER(:'mock_admin_email'),
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
  'validation.quota.beta@example.com',
  'validation.rival.admin@example.com',
  'validation.rival.dealer@example.com'
)
ORDER BY LOWER(email);
SQL
)"

superadmin_public_id="$(printf '%s\n' "$seed_user_rows" | awk -F'|' '$1=="validation.superadmin@example.com" {print $3; exit}')"
superadmin_scope_code="$(printf '%s\n' "$seed_user_rows" | awk -F'|' '$1=="validation.superadmin@example.com" {print $2; exit}')"
mock_admin_public_id="$(printf '%s\n' "$seed_user_rows" | awk -F'|' -v target="$mock_admin_email_normalized" '$1==target {print $3; exit}')"
mfa_admin_public_id="$(printf '%s\n' "$seed_user_rows" | awk -F'|' '$1=="validation.mfa.admin@example.com" {print $3; exit}')"
superadmin_public_id="${superadmin_public_id:-unavailable}"
superadmin_scope_code="${superadmin_scope_code:-unavailable}"
mock_admin_public_id="${mock_admin_public_id:-unavailable}"
mfa_admin_public_id="${mfa_admin_public_id:-unavailable}"

cat <<EOF
[final-validation-reset] Ready.

Runtime:
  Base URL: http://localhost:${APP_PORT}
  Actuator: http://localhost:${MANAGEMENT_PORT}/actuator/health
  MailHog:  http://localhost:${MAILHOG_UI_PORT}
  Profiles: prod,flyway-v2,mock,validation-seed
  Seed verification: PASS (actors, tenant fixtures, dealers, finance/UAT fixtures)

Seeded actors (password source: ERP_VALIDATION_SEED_PASSWORD; reset fallback: ${ERP_VALIDATION_SEED_PASSWORD_SOURCE})
  Seed password: ${ERP_VALIDATION_SEED_PASSWORD}
  ${ERP_SEED_MOCK_ADMIN_EMAIL}                -> MOCK (bootstrap ROLE_ADMIN, ROLE_ACCOUNTING, ROLE_SALES; must-change-password)
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
  validation.superadmin@example.com   -> ${superadmin_scope_code} (ROLE_SUPER_ADMIN, ROLE_ADMIN)
  validation.rival.admin@example.com  -> RIVAL (ROLE_ADMIN)
  validation.rival.dealer@example.com -> RIVAL (ROLE_DEALER, portal user RIVAL-DEALER)

Seeded public ids:
  ${ERP_SEED_MOCK_ADMIN_EMAIL}                -> ${mock_admin_public_id}
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
