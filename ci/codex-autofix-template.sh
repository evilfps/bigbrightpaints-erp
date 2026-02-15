#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$ROOT_DIR"

echo "[codex-autofix] running docs + architecture checks"
bash ci/lint-knowledgebase.sh
bash ci/check-architecture.sh

echo "[codex-autofix] running doc-gardener hint script"
bash skills/doc-gardener/scripts/lint-and-fix-hints.sh

echo "[codex-autofix] template run complete"
echo "[codex-autofix] NOTE: automatic PR creation is unspecified (requires repo/app token + policy)."
