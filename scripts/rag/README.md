# RAG MCP Context Engine

This folder provides a hybrid graph + retrieval engine for ERP code navigation, safer edits, and early regression detection.

## What it gives agents
- cite-or-refuse retrieval over code + facts
- cross-module graph context (`CALLS`, `ROUTE_TO_HANDLER`, table/event/config usage)
- silent-failure findings (`TRANSACTIONAL_GAP`, `IDEMPOTENCY_GAP`, tenant-scope checks, etc.)
- idempotency key contract tracking and mismatch detection support
- duplicate-flow guidance via logic fingerprints and canonical resolver scoring

## Local quick start
Run from repo root:

```bash
bash scripts/rag_index.sh --force
bash scripts/rag_silent_failures.sh --json --top-k 40
bash scripts/rag_query.sh --query "superadmin tenant boundary" --top-k 8 --depth 2 --json
```

Optional DB override:

```bash
export RAG_DB_PATH=/tmp/context_index.sqlite
```

## MCP server (for agent tool runtime)
```bash
bash scripts/rag_mcp_server.sh
```

The MCP server exposes tools including:
- `rag_guarded_query`
- `rag_patch_guard`
- `rag_verify_claims`
- `rag_dedupe_resolve`
- `rag_idempotency_mismatches`
- `rag_ticket_context`
- `rag_agent_slices`

## After pulling latest branch
Fast refresh for branch updates:

```bash
BASE="$(git merge-base HEAD origin/harness-engineering-orchestrator)"
bash scripts/rag_index.sh --changed-only --diff-base "$BASE"
bash scripts/rag_silent_failures.sh --json
```

## Token-efficient profile
Recommended defaults:
- `rag_guarded_query`: `top_k=6..8`, `depth=2`, `candidate_limit<=180`
- `rag_patch_guard`: `include_full_payloads=false`
- only increase `top_k/depth` for deep incident debugging

## CI sidecar
CI workflow: `.github/workflows/rag-mcp-sidecar.yml`

On pull requests, it:
- updates the index (`--changed-only` against PR base)
- runs silent-failure detectors
- uploads sidecar artifacts (`artifacts/rag/*`, `.tmp/rag/context_index.sqlite`)
