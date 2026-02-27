# RAG MCP Sidecar Runbook

## Purpose
Run RAG/MCP context checks as a CI sidecar so risky cross-module regressions are surfaced early with code evidence.

## Local developer flow
1. Build/refresh index:
   - `bash scripts/rag_index.sh --force`
2. Run detectors:
   - `bash scripts/rag_silent_failures.sh --json --top-k 40`
3. Ask grounded query:
   - `bash scripts/rag_query.sh --query "idempotency key mismatch across modules" --top-k 8 --depth 2 --json`
4. Start MCP runtime:
   - `bash scripts/rag_mcp_server.sh`

## Branch refresh flow (after pull/rebase)
```bash
BASE="$(git merge-base HEAD origin/harness-engineering-orchestrator)"
bash scripts/rag_index.sh --changed-only --diff-base "$BASE"
bash scripts/rag_silent_failures.sh --json
```

## CI sidecar workflow
Workflow file:
- `.github/workflows/rag-mcp-sidecar.yml`

PR behavior:
- changed-only index refresh against PR base SHA
- silent-failure scan output in `artifacts/rag/silent_failures.json`
- health summary in `artifacts/rag/health_summary.json`
- uploads `.tmp/rag/context_index.sqlite` and `artifacts/rag/*`

## Guardrail usage pattern for agents
1. `rag_guarded_query` for scoped grounded answers.
2. `rag_dedupe_resolve` before editing duplicate-looking flows.
3. `rag_idempotency_mismatches` for caller/callee key contract drift.
4. `rag_patch_guard` before PR/update merge.
5. `rag_verify_claims` for PR summary truth checks.

## Token profile
- Keep retrieval narrow by default:
  - `top_k=6..8`
  - `depth=2`
  - `candidate_limit<=180`
- Use larger context only for deep incident tracing.
