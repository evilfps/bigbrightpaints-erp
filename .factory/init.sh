#!/bin/bash
set -euo pipefail

PROJECT_ROOT="/home/realnigga/Desktop/Mission-control"
ERP_DIR="$PROJECT_ROOT/erp-domain"
LIB_DIR="$PROJECT_ROOT/.factory/library"

if [ ! -f "$PROJECT_ROOT/.env" ]; then
  cp "$PROJECT_ROOT/.env.example" "$PROJECT_ROOT/.env"
fi

grep -q '^ERP_SECURITY_ENCRYPTION_KEY=' "$PROJECT_ROOT/.env" || echo "ERP_SECURITY_ENCRYPTION_KEY=YOUR_ENCRYPTION_KEY_HERE" >> "$PROJECT_ROOT/.env"

mkdir -p "$LIB_DIR"

if [ ! -f "$LIB_DIR/erp-definition-of-done.md" ]; then
  cat > "$LIB_DIR/erp-definition-of-done.md" <<'EOF'
# ERP Definition Of Done

Mission-scoped definition of done for the workflow-centric ERP implementation.
EOF
fi

if [ ! -f "$LIB_DIR/remediation-log.md" ]; then
  cat > "$LIB_DIR/remediation-log.md" <<'EOF'
# Remediation Log

Track cleanup, duplicate-truth removals, dead-code removal, and production-readiness fixes.
EOF
fi

if [ ! -f "$LIB_DIR/frontend-v2.md" ]; then
  cat > "$LIB_DIR/frontend-v2.md" <<'EOF'
# Frontend V2 Notes

Backend-facing notes for frontend-v2 consumers. Cross-reference `.factory/library/frontend-handoff.md` for detailed endpoint and contract notes.
EOF
fi

if [ ! -f "$LIB_DIR/frontend-handoff.md" ]; then
  cat > "$LIB_DIR/frontend-handoff.md" <<'EOF'
# Frontend Handoff

Capture backend contract impact for frontend consumers.
EOF
fi

if [ ! -f "$LIB_DIR/tenant-runtime-control-plane.md" ]; then
  cat > "$LIB_DIR/tenant-runtime-control-plane.md" <<'EOF'
# Tenant Runtime Control Plane

Mission notes for canonical tenant/runtime policy behavior, stale-path retirement, and the exact PR catching lane for Lane 01 packets.
EOF
fi

cd "$ERP_DIR"
mvn -q -DskipTests -Djacoco.skip=true compile >/dev/null 2>&1 || true
