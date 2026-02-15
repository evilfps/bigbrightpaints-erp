#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/../../.." && pwd)"

printf '[safe-refactor] running boundary guard\n'
bash "$ROOT_DIR/ci/check-architecture.sh"

printf '[safe-refactor] running docs guard\n'
bash "$ROOT_DIR/ci/lint-knowledgebase.sh"

printf '[safe-refactor] preflight complete\n'
