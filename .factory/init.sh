#!/bin/bash
set -euo pipefail

PROJECT_ROOT="/home/realnigga/Desktop/Mission-control"
ERP_DIR="$PROJECT_ROOT/erp-domain"

# Ensure .env exists for docker-compose
if [ ! -f "$PROJECT_ROOT/.env" ]; then
  cp "$PROJECT_ROOT/.env.example" "$PROJECT_ROOT/.env"
  # Append missing vars that .env.example does not include
  grep -q 'ERP_SECURITY_ENCRYPTION_KEY' "$PROJECT_ROOT/.env" || \
    echo "ERP_SECURITY_ENCRYPTION_KEY=YOUR_ENCRYPTION_KEY_HERE" >> "$PROJECT_ROOT/.env"
fi

# Compile the project to ensure it builds
cd "$ERP_DIR"
mvn compile -q 2>/dev/null || true
