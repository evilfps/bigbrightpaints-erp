#!/usr/bin/env bash
set -euo pipefail

if [[ $# -lt 1 ]]; then
  echo "usage: $0 <migration-topic-slug>" >&2
  exit 2
fi

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/../../.." && pwd)"
TOPIC="$1"
STAMP="$(date +%Y%m%d)"
OUT="$ROOT_DIR/artifacts/migration-plan-${STAMP}-${TOPIC}.md"

mkdir -p "$ROOT_DIR/artifacts"
cat > "$OUT" <<PLAN
# Migration Plan: ${TOPIC}

- Last reviewed: $(date +%F)
- Scope: TODO
- Risk: TODO

## Forward Plan
1. TODO

## Validation
- [ ] bash scripts/flyway_overlap_scan.sh --migration-set v2
- [ ] bash scripts/schema_drift_scan.sh --migration-set v2
- [ ] bash scripts/release_migration_matrix.sh --migration-set v2

## Rollback Drill
1. TODO restore strategy
2. TODO forward-fix fallback
PLAN

echo "[data-migration] wrote template: $OUT"
