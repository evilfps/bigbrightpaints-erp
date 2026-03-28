#!/bin/bash
set -euo pipefail

PROJECT_ROOT="$(git rev-parse --show-toplevel)"
ERP_DIR="$PROJECT_ROOT/erp-domain"
LIB_DIR="$PROJECT_ROOT/.factory/library"
RESEARCH_DIR="$PROJECT_ROOT/.factory/research"

if [ ! -f "$PROJECT_ROOT/.env" ]; then
  cp "$PROJECT_ROOT/.env.example" "$PROJECT_ROOT/.env"
fi

grep -q '^JWT_SECRET=' "$PROJECT_ROOT/.env" || echo "JWT_SECRET=YOUR_JWT_SECRET_HERE" >> "$PROJECT_ROOT/.env"
grep -q '^ERP_SECURITY_ENCRYPTION_KEY=' "$PROJECT_ROOT/.env" || echo "ERP_SECURITY_ENCRYPTION_KEY=YOUR_ENCRYPTION_KEY_HERE" >> "$PROJECT_ROOT/.env"

mkdir -p "$LIB_DIR" "$RESEARCH_DIR"

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

if [ ! -f "$LIB_DIR/factory-canonical-flow.md" ]; then
  cat > "$LIB_DIR/factory-canonical-flow.md" <<'EOF'
# ERP-38 Canonical Factory Flow

Mission-specific route ownership, cleanup targets, and worker guardrails for the factory hard-cut packet.
EOF
fi

if [ ! -f "$LIB_DIR/portal-split-hard-cut.md" ]; then
  cat > "$LIB_DIR/portal-split-hard-cut.md" <<'EOF'
# ERP-21 Portal Split Hard Cut

Mission-specific host ownership, retirement targets, and proof expectations for the ERP-21 portal cleanup packet.
EOF
fi

cd "$ERP_DIR"
MIGRATION_SET=v2 mvn -q -DskipTests -Djacoco.skip=true -T8 compile >/dev/null 2>&1 || true
