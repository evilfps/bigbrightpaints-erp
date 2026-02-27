#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
DB_PATH="${RAG_DB_PATH:-$ROOT_DIR/.tmp/rag/context_index.sqlite}"
mkdir -p "$(dirname "$DB_PATH")"

python3 "$ROOT_DIR/scripts/rag/index.py" --db-path "$DB_PATH" "$@"
