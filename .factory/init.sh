#!/bin/bash
set -euo pipefail

PROJECT_ROOT="$(git rev-parse --show-toplevel)"
ERP_DIR="$PROJECT_ROOT/erp-domain"
LIB_DIR="$PROJECT_ROOT/.factory/library"
RESEARCH_DIR="$PROJECT_ROOT/.factory/research"

if [ ! -f "$PROJECT_ROOT/.env" ]; then
  cp "$PROJECT_ROOT/.env.example" "$PROJECT_ROOT/.env"
fi

generate_secret() {
  openssl rand -base64 48 | tr -d '\n'
}

upsert_env_value() {
  key="$1"
  value="$2"
  env_file="$PROJECT_ROOT/.env"
  temp_file="$(mktemp "${TMPDIR:-/tmp}/erp-env.XXXXXX")"

  awk -v key="$key" -v value="$value" '
    BEGIN { replaced = 0 }
    index($0, key "=") == 1 {
      print key "=" value
      replaced = 1
      next
    }
    { print }
    END {
      if (!replaced) {
        print key "=" value
      }
    }
  ' "$env_file" > "$temp_file"

  mv "$temp_file" "$env_file"
}

ensure_generated_secret() {
  key="$1"
  placeholder="$2"
  current_value="$(grep "^${key}=" "$PROJECT_ROOT/.env" | head -n 1 | cut -d= -f2- || true)"

  if [ -z "$current_value" ] || [ "$current_value" = "$placeholder" ]; then
    upsert_env_value "$key" "$(generate_secret)"
  fi
}

ensure_generated_secret "JWT_SECRET" "YOUR_JWT_SECRET_HERE"
ensure_generated_secret "ERP_SECURITY_ENCRYPTION_KEY" "YOUR_ENCRYPTION_KEY_HERE"

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
