#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/../../.." && pwd)"

printf '[codebase-map] root=%s\n' "$ROOT_DIR"
printf '[codebase-map] modules detected:\n'
find "$ROOT_DIR/erp-domain/src/main/java/com/bigbrightpaints/erp/modules" -mindepth 1 -maxdepth 1 -type d -printf '  - %f\n' | sort

printf '[codebase-map] running knowledgebase lint...\n'
bash "$ROOT_DIR/ci/lint-knowledgebase.sh"

printf '[codebase-map] running architecture check...\n'
bash "$ROOT_DIR/ci/check-architecture.sh"

printf '[codebase-map] complete\n'
