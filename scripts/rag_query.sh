#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
DB_PATH="${RAG_DB_PATH:-$ROOT_DIR/.tmp/rag/context_index.sqlite}"

python3 "$ROOT_DIR/scripts/rag/query.py" --db-path "$DB_PATH" "$@"
