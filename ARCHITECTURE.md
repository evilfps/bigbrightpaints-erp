# Architecture Map - orchestrator_erp

Last reviewed: 2026-02-15

This root file is a quick navigation map.
The canonical architecture specification is `docs/ARCHITECTURE.md`.

## Fast Links
- Canonical architecture spec: `docs/ARCHITECTURE.md`
- Root docs index: `docs/INDEX.md`
- Domain docs index: `docs/INDEX.md`
- Async loop runbook: `docs/ASYNC_LOOP_OPERATIONS.md`
- CI harness entrypoint: `scripts/verify_local.sh`

## Core boundaries (summary)
- Runtime domain service: `erp-domain`
- Cross-module orchestrator: `erp-domain/src/main/java/com/bigbrightpaints/erp/orchestrator`
- Canonical docs and runbooks: `docs/`
- Mechanical gates and checks: `scripts/`, `ci/`, `.github/workflows/`

## Update contract
If architecture behavior changes, update `docs/ARCHITECTURE.md` and cross-links in `docs/INDEX.md` in the same patch.
