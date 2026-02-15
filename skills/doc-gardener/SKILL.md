---
name: doc-gardener
description: Keep knowledge base healthy by finding stale docs, fixing links, and updating indexes.
---

# Skill: doc-gardener

## Boundaries
- Allowed: documentation edits, index updates, link repairs, freshness marker updates.
- Not allowed: silent behavior claims without source confirmation.

## Procedure
1. Run knowledgebase lint.
2. Fix missing links and absent freshness markers in canonical docs.
3. Ensure `docs/INDEX.md` references all new long-lived docs.
4. Keep AGENTS map concise; move depth into docs.
5. Preserve frontend portal taxonomy in docs:
   - Accounting Portal: accounting, inventory, hr, reports, invoice.
   - Factory Portal: factory, production, manufacturing.
6. Summarize what changed and what remains unspecified.

## Required tools/commands
- `bash ci/lint-knowledgebase.sh`
- `rg`, `sed`

## Outputs
- Updated docs with valid links
- Index cross-links refreshed
- Staleness TODOs explicitly tracked
