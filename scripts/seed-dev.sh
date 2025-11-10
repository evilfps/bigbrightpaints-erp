#!/usr/bin/env bash
set -euo pipefail

DB_CONTAINER=${DB_CONTAINER:-erp_db}

if ! docker ps --format '{{.Names}}' | grep -q "^${DB_CONTAINER}$"; then
  echo "Container ${DB_CONTAINER} not running. Start compose first (docker compose up -d)." >&2
  exit 1
fi

echo "Copying seed SQL into ${DB_CONTAINER}..."
docker cp "$(dirname "$0")/seed-dev.sql" "${DB_CONTAINER}:/tmp/seed-dev.sql"

echo "Applying seed data..."
docker exec -i "${DB_CONTAINER}" psql -U erp -d erp_domain -f /tmp/seed-dev.sql

echo "Done. Seed data applied."

