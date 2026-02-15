#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/../../.." && pwd)"

bash "$ROOT_DIR/ci/lint-knowledgebase.sh" || {
  echo "[doc-gardener] lint failed. Remediation: add Last reviewed marker, fix missing links, update docs/INDEX.md cross-links." >&2
  exit 1
}

echo "[doc-gardener] lint passed"
