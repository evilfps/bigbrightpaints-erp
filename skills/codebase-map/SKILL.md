---
name: codebase-map
description: Build and maintain architecture/doc maps for this ERP repository with compact, verifiable outputs.
---

# Skill: codebase-map

## Boundaries
- Allowed: repository mapping docs, cross-link updates, architecture/import map refresh.
- Not allowed: behavioral code changes in domain modules unless explicitly requested.

## Procedure
1. Read `docs/INDEX.md`, `docs/ARCHITECTURE.md`, and `erp-domain/docs/INDEX.md`.
2. Detect stack/entrypoints from `erp-domain/pom.xml`, `docker-compose.yml`, and workflows.
3. Refresh architecture map and module-path mapping.
4. Validate links and freshness markers using `bash ci/lint-knowledgebase.sh`.
5. Run `bash ci/check-architecture.sh` to capture boundary state.
6. Produce a concise change/evidence summary.

## Required tools/commands
- `rg`, `find`, `sed`
- `bash ci/lint-knowledgebase.sh`
- `bash ci/check-architecture.sh`

## Outputs
- Updated `docs/INDEX.md`
- Updated `docs/ARCHITECTURE.md` or `ARCHITECTURE.md`
- Optional architecture evidence artifact under `artifacts/`
