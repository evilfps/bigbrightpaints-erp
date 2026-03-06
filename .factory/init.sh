#!/bin/bash
set -euo pipefail

PROJECT_ROOT="/home/realnigga/Desktop/Mission-control"
ERP_DIR="$PROJECT_ROOT/erp-domain"
LIB_DIR="$PROJECT_ROOT/.factory/library"
FRONTEND_HANDOFF="$LIB_DIR/frontend-handoff.md"

if [ ! -f "$PROJECT_ROOT/.env" ]; then
  cp "$PROJECT_ROOT/.env.example" "$PROJECT_ROOT/.env"
  grep -q 'ERP_SECURITY_ENCRYPTION_KEY' "$PROJECT_ROOT/.env" ||     echo "ERP_SECURITY_ENCRYPTION_KEY=YOUR_ENCRYPTION_KEY_HERE" >> "$PROJECT_ROOT/.env"
fi

if [ ! -f "$FRONTEND_HANDOFF" ]; then
  cat > "$FRONTEND_HANDOFF" <<'EOF'
# Frontend Handoff

Capture backend contract impact for frontend consumers.

## Auth/Admin Contract Impact
- Add notes here whenever a mission changes or confirms auth/admin request-response shapes.
EOF
fi

cd "$ERP_DIR"
mvn compile -q >/dev/null 2>&1 || true
