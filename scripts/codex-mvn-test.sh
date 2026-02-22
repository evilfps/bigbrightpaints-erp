#!/usr/bin/env bash
set -euo pipefail

REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"

cd "$REPO_ROOT/erp-domain"

# In Codex/cloud containers, internet access is often restricted during runtime.
# Prefetch dependencies first so tests don't fail due to network restrictions.
mvn -B -ntp -DskipTests dependency:go-offline
mvn -B -ntp test
